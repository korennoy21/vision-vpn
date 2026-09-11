"""Linux lab client. Does not change the default route; see docs/LINUX.md."""
import argparse
import asyncio
import contextlib
import json
import secrets
import ssl
from pathlib import Path
from .wire import Kind, ProtocolError, encode, ipv4, json_bytes, json_object, read_frame
from .heartbeat import Heartbeat
from .profile import validate_profile
from .webtransport import WSReader, WSWriter
import aiohttp


async def open_session(profile, cafile=None):
    validate_profile(profile)
    if profile["transport"] == "obfs4":
        from .obfs4transport import open_session as open_obfs4
        return await open_obfs4(profile, cafile)
    context = ssl.create_default_context(cafile=cafile)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.maximum_version = ssl.TLSVersion.TLSv1_3
    # aiohttp follows HTTP redirects when opening a WebSocket. Reject every redirect
    # before another request is sent, including a same-host path change.
    trace = aiohttp.TraceConfig()
    async def reject_redirect(session, ctx, params):
        raise ProtocolError("WebSocket redirects are not allowed")
    trace.on_request_redirect.append(reject_redirect)
    for endpoint in profile["endpoints"]:
        writer = None
        session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15), trace_configs=[trace],
                                        cookie_jar=aiohttp.DummyCookieJar(), trust_env=False)
        try:
            url = f"wss://{endpoint['host']}:{endpoint['port']}{endpoint['path']}"
            ws = await session.ws_connect(url, auth=aiohttp.BasicAuth(profile["user"], profile["token"], encoding="utf-8"),
                ssl=context, compress=0, max_msg_size=4615, heartbeat=None,
                timeout=aiohttp.ClientWSTimeout(ws_receive=90, ws_close=2))
            reader, writer = WSReader(ws), WSWriter(ws, session)
            kind, payload = await read_frame(reader, 10)
            if kind != Kind.CONFIG:
                raise ProtocolError("CONFIG required")
            config = json_object(payload)
            if config.get("mtu") != 1280 or config.get("prefix") != 24:
                raise ProtocolError("unsupported network configuration")
            return reader, writer, config
        except asyncio.CancelledError:
            if writer:
                writer.close()
                with contextlib.suppress(Exception):
                    await writer.wait_closed()
            await session.close()
            raise
        except (OSError, ValueError, TimeoutError, aiohttp.ClientError, asyncio.IncompleteReadError):
            if writer:
                writer.close()
                with contextlib.suppress(Exception):
                    await writer.wait_closed()
            await session.close()
    raise ConnectionError("No endpoint accepted the session")


async def main(a):
    from .tun import LinuxTun
    profile = json.loads(Path(a.profile).read_text())
    reader, writer, config = await open_session(profile, a.ca)
    try:
        tun = LinuxTun(a.tun, config["ip"] + "/24")
    except BaseException:
        writer.close()
        with contextlib.suppress(Exception):
            await writer.wait_closed()
        raise
    lock = asyncio.Lock()
    heartbeat_policy = Heartbeat()
    async def send(kind, data):
        async with lock:
            writer.write(encode(kind, data))
            await asyncio.wait_for(writer.drain(), 10)
            if kind == Kind.PING:
                heartbeat_policy.ping_sent()
            else:
                heartbeat_policy.packet_sent()
    async def up():
        while True:
            packet = await tun.read()
            if packet[0] >> 4 == 4:
                ipv4(packet, source=config["ip"])
                await send(Kind.PACKET, packet)
    async def down():
        while True:
            kind, payload = await read_frame(reader)
            if kind == Kind.PACKET:
                ipv4(payload, destination=config["ip"])
                tun.write(payload)
            elif kind != Kind.PONG or len(payload) != 8:
                raise ProtocolError("unexpected frame")
    async def heartbeat():
        while True:
            await asyncio.sleep(1)
            if heartbeat_policy.due():
                await send(Kind.PING, secrets.token_bytes(8))
    print("Tunnel ready. Routes are NOT changed automatically. Address:", config["ip"])
    tasks = [asyncio.create_task(f()) for f in (up, down, heartbeat)]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        writer.close()
        await writer.wait_closed()
        tun.close()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("profile")
    p.add_argument("--tun", default="severc0")
    p.add_argument("--ca", help="Test CA PEM; production uses system trust")
    asyncio.run(main(p.parse_args()))
