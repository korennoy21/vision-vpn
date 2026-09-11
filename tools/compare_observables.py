#!/usr/bin/env python3
"""Loopback experiment: actual encrypted TCP streams, not a TSPU emulator."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import secrets
import ssl
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import aiohttp
from aiohttp import web
from sever.client import open_session
from sever.server import Gateway, tls_context
from sever.webtransport import make_app
from sever.wire import HEADER, Kind, encode, json_bytes, read_frame


def legacy_encode(kind, payload):
    # The 0.1.0 client algorithm, retained only as a measurement baseline.
    pad = (-(8 + len(payload))) % 256 + secrets.randbelow(2) * 256
    return HEADER.pack(1, kind, len(payload), pad, 0) + payload + secrets.token_bytes(pad)


def lengths(raw):
    result = []
    pos = 0
    while pos < len(raw):
        if len(raw) - pos < 5:
            raise RuntimeError("partial TLS record header")
        n = int.from_bytes(raw[pos + 3:pos + 5], "big")
        if pos + 5 + n > len(raw):
            raise RuntimeError("partial TLS record")
        if raw[pos] == 23:
            result.append(n)
        pos += 5 + n
    return result


class Recorder:
    def __init__(self, port):
        self.port, self.active = port, False
        self.capture = bytearray()
        self.tasks = set()

    async def handle(self, client_r, client_w):
        task = asyncio.current_task(); self.tasks.add(task)
        backend_r, backend_w = await asyncio.open_connection("127.0.0.1", self.port)
        async def pump(reader, writer, capture):
            while data := await reader.read(16384):
                if capture and self.active: self.capture.extend(data)
                writer.write(data); await writer.drain()
        pumps = [asyncio.create_task(pump(client_r, backend_w, True)), asyncio.create_task(pump(backend_r, client_w, False))]
        try:
            done, _ = await asyncio.wait(pumps, return_when=asyncio.FIRST_COMPLETED)
            for finished in done: finished.result()
        finally:
            for p in pumps: p.cancel()
            await asyncio.gather(*pumps, return_exceptions=True)
            backend_w.close(); client_w.close()
            await asyncio.gather(backend_w.wait_closed(), client_w.wait_closed(), return_exceptions=True)
            self.tasks.discard(task)


async def measure(port, cert, samples, legacy=False):
    recorder = Recorder(port)
    relay = await asyncio.start_server(recorder.handle, "127.0.0.1", 0)
    relay_port = relay.sockets[0].getsockname()[1]
    async with relay:
        if legacy:
            context = ssl.create_default_context(cafile=cert)
            context.minimum_version = ssl.TLSVersion.TLSv1_3
            reader, writer = await asyncio.open_connection("127.0.0.1", relay_port, ssl=context, server_hostname="localhost")
            writer.write(legacy_encode(Kind.AUTH, json_bytes({"user": "lab", "token": "a" * 43})))
            await writer.drain()
            assert (await read_frame(reader, 2))[0] == Kind.CONFIG
        else:
            profile = {"version": 2, "protocol": "sever1", "transport": "wss", "user": "lab", "token": "a" * 43,
                       "endpoints": [{"host": "localhost", "port": relay_port, "path": "/api/session"}]}
            reader, writer, _ = await open_session(profile, cert)
        recorder.active = True
        try:
            for _ in range(samples):
                nonce = secrets.token_bytes(8)
                writer.write((legacy_encode if legacy else encode)(Kind.PING, nonce))
                await writer.drain()
                assert await read_frame(reader, 2) == (Kind.PONG, nonce)
            recorder.active = False
        finally:
            writer.close(); await writer.wait_closed()
    if recorder.tasks: await asyncio.gather(*list(recorder.tasks))
    sizes = lengths(recorder.capture)
    return {"samples": samples, "tls_application_records": len(sizes), "unique_lengths": len(set(sizes)),
            "min_length": min(sizes), "max_length": max(sizes), "unique_remainders_mod_256": len({n % 256 for n in sizes}),
            "lengths": sizes}


async def run(cert, key, root, samples):
    class NoTun:
        def write(self, data): raise AssertionError("This experiment sends only probes, no IP packets")
    users = {"lab": {"ip": "10.77.0.2", "token_sha256": hashlib.sha256(("a" * 43).encode()).hexdigest()}}
    gateway = Gateway(NoTun(), users)
    old = await asyncio.start_server(gateway.handle, "127.0.0.1", 0, ssl=tls_context(cert, key))
    old_port = old.sockets[0].getsockname()[1]
    runner = web.AppRunner(make_app(Gateway(NoTun(), users), site_root=root), access_log=None)
    await runner.setup()
    context = tls_context(cert, key); context.set_alpn_protocols(["http/1.1"])
    await web.TCPSite(runner, "127.0.0.1", 0, ssl_context=context).start()
    new_port = runner.addresses[0][1]
    client_context = ssl.create_default_context(cafile=cert)
    try:
        async with old:
            old_r, old_w = await asyncio.open_connection("127.0.0.1", old_port, ssl=client_context, server_hostname="localhost")
            old_w.write(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"); await old_w.drain()
            old_response = await asyncio.wait_for(old_r.read(1024), 2)
            old_w.close(); await old_w.wait_closed()
            async with aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=client_context)) as client:
                async with client.get(f"https://localhost:{new_port}/") as response:
                    new_status, new_body = response.status, await response.read()
            before = await measure(old_port, cert, samples, legacy=True)
            after = await measure(new_port, cert, samples)
    finally:
        await runner.cleanup()
    return {"method": "Loopback TCP relay observing actual encrypted TLS records; no TLS keys used by recorder",
            "baseline": "0.1.0 padding algorithm over raw TLS, reproduced with current compatible frame receiver",
            "new_transport": "aiohttp WSS over TLS 1.3; nginx and native mobile transport not exercised",
            "http_probe": {"before_response_bytes": len(old_response), "after_status": new_status, "after_body_bytes": len(new_body)},
            "before": before, "after": after,
            "interpretation": "Tests removal of two specific signals, not resistance to a classifier, active scanner generally, or TSPU"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=100)
    parser.add_argument("--output")
    args = parser.parse_args()
    if not 1 <= args.samples <= 1000: parser.error("samples must be 1..1000")
    with tempfile.TemporaryDirectory() as temp:
        cert, key = [str(Path(temp) / name) for name in ("cert.pem", "key.pem")]
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-keyout", key,
            "-out", cert, "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost"], check=True, capture_output=True)
        root = Path(temp) / "site"; root.mkdir()
        (root / "index.html").write_text("<!doctype html><title>Lab website</title><p>Local measurement fixture</p>")
        report = asyncio.run(run(cert, key, str(root), args.samples))
    encoded = json.dumps(report, indent=2)
    if args.output: Path(args.output).write_text(encoded + "\n")
    short = {**report, "before": {k:v for k,v in report["before"].items() if k != "lengths"},
             "after": {k:v for k,v in report["after"].items() if k != "lengths"}}
    print(json.dumps(short, indent=2))


if __name__ == "__main__": main()
