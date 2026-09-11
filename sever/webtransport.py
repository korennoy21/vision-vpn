"""VISION WSS transport with backward-compatible multi-frame WebSocket batching."""
import argparse
import asyncio
import base64
import binascii
from collections import deque
import hashlib
import hmac
import ipaddress
from pathlib import Path

import aiohttp
from aiohttp import web

from .server import Gateway, load_users
from .wire import Kind, ProtocolError, HEADER, encode, json_bytes, parse_header

MAX_FRAME = 4615
MAX_MESSAGE = 65536
MAX_PENDING = 512


def authorize(header, users):
    if not isinstance(header, str) or len(header) > 1024:
        return None
    try:
        scheme, encoded = header.split(" ", 1)
        if scheme.lower() != "basic":
            return None
        user, token = base64.b64decode(encoded, validate=True).decode("utf-8").split(":", 1)
        if not 1 <= len(user) <= 64 or not 32 <= len(token) <= 256:
            return None
        record = users.get(user)
        actual = hashlib.sha256(token.encode()).hexdigest()
        expected = record["token_sha256"] if record else "0" * 64
        if hmac.compare_digest(actual, expected) and record:
            return {"user": user, "token": token}
    except (ValueError, UnicodeError, binascii.Error):
        pass
    return None


def check_message(data):
    """Validate one or more complete VISION frames in one WS binary message."""
    if not isinstance(data, bytes) or not 8 <= len(data) <= MAX_MESSAGE:
        raise ProtocolError("invalid WebSocket message")
    offset = 0
    while offset < len(data):
        if len(data) - offset < HEADER.size:
            raise ProtocolError("truncated WebSocket frame")
        _, length, padding = parse_header(data[offset:offset + HEADER.size])
        size = HEADER.size + length + padding
        if size > MAX_FRAME or offset + size > len(data):
            raise ProtocolError("invalid WebSocket frame length")
        offset += size
    if offset != len(data):
        raise ProtocolError("invalid WebSocket message")


class WSReader:
    def __init__(self, ws, initial=b""):
        self.ws, self.buffer = ws, bytearray(initial)

    async def readexactly(self, n):
        if not 0 <= n <= MAX_MESSAGE:
            raise ProtocolError("read too large")
        while len(self.buffer) < n:
            message = await self.ws.receive()
            if message.type == aiohttp.WSMsgType.BINARY:
                check_message(message.data)
                self.buffer.extend(message.data)
                if len(self.buffer) > MAX_MESSAGE * 2:
                    raise ProtocolError("receive overflow")
            elif message.type == aiohttp.WSMsgType.TEXT:
                raise ProtocolError("binary messages required")
            else:
                raise asyncio.IncompleteReadError(bytes(self.buffer), n)
        result = bytes(self.buffer[:n])
        del self.buffer[:n]
        return result


class WSWriter:
    def __init__(self, ws, session=None):
        self.ws, self.session = ws, session
        self.pending = deque()
        self.close_task = None

    def write(self, data):
        # write() still receives one encoded frame, but drain() coalesces frames.
        check_message(data)
        if self.ws.closed or self.close_task or len(self.pending) >= MAX_PENDING:
            raise ConnectionError("closed or congested transport")
        self.pending.append(data)

    async def drain(self):
        while self.pending:
            batch = bytearray()
            while self.pending and len(batch) + len(self.pending[0]) <= MAX_MESSAGE:
                batch.extend(self.pending.popleft())
            if not batch:
                batch.extend(self.pending.popleft())
            await self.ws.send_bytes(bytes(batch))

    def close(self):
        if self.close_task is None:
            self.pending.clear()
            async def closing():
                try:
                    await asyncio.wait_for(self.ws.close(), 2)
                finally:
                    if self.session:
                        await self.session.close()
            self.close_task = asyncio.create_task(closing())

    async def wait_closed(self):
        self.close()
        await self.close_task


def make_app(gateway, path="/api/session", site_root=None):
    import re
    if not re.fullmatch(r"/[A-Za-z0-9][A-Za-z0-9/_-]{0,126}", path) or path.startswith("/_"):
        raise ValueError("invalid endpoint path")
    root = Path(site_root).resolve() if site_root else None
    app = web.Application(client_max_size=MAX_MESSAGE)

    async def fallback(request):
        if root and request.method in ("GET", "HEAD"):
            name = request.path.lstrip("/") or "index.html"
            candidate = (root / name).resolve()
            if candidate.is_relative_to(root) and candidate.is_file():
                return web.FileResponse(candidate)
        return web.Response(status=404, text="Not found\n", content_type="text/plain")

    async def auth(request):
        credentials = authorize(request.headers.get("Authorization"), gateway.users)
        return web.Response(status=204 if credentials else 401)

    async def tunnel(request):
        credentials = authorize(request.headers.get("Authorization"), gateway.users)
        ws = web.WebSocketResponse(compress=False, max_msg_size=MAX_MESSAGE, timeout=2)
        if not credentials or request.method != "GET" or not ws.can_prepare(request).ok:
            return await fallback(request)
        if gateway.connections >= 256:
            return web.Response(status=503)
        await ws.prepare(request)
        reader = WSReader(ws, encode(Kind.AUTH, json_bytes(credentials), padded=False))
        writer = WSWriter(ws)
        await gateway.handle(reader, writer)
        await writer.wait_closed()
        return ws

    app.router.add_get("/_auth", auth)
    app.router.add_route("*", path, tunnel)
    app.router.add_route("*", "/{tail:.*}", fallback)
    return app


async def main(args):
    from .tun import LinuxTun
    if not ipaddress.ip_address(args.bind).is_loopback:
        raise ValueError("plaintext backend must bind to loopback")
    users = load_users(args.users)
    tun = LinuxTun(args.tun, "10.77.0.1/24")
    gateway = Gateway(tun, users, args.dns)
    runner = web.AppRunner(make_app(gateway, args.path), access_log=None)
    try:
        await runner.setup()
        await web.TCPSite(runner, args.bind, args.port).start()
        print(f"VISION WSS backend on {args.bind}:{args.port}; publish through Caddy TLS", flush=True)
        await gateway.tun_loop()
    finally:
        await runner.cleanup(); tun.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--tun", default="sever0")
    parser.add_argument("--dns", default="1.1.1.1")
    parser.add_argument("--path", default="/api/session")
    parser.add_argument("--users", required=True)
    asyncio.run(main(parser.parse_args()))
