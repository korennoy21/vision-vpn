#!/usr/bin/env python3
"""Explicit integration probe against an operator's configured obfs4 endpoint.

Does not create TUN or modify routes. Success is connectivity, not stealth.
"""
import argparse
import asyncio
import json
from pathlib import Path
import secrets
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from sever.client import open_session
from sever.obfs4transport import close_stream
from sever.wire import Kind, encode, read_frame


async def main(args):
    profile = json.loads(Path(args.profile).read_text())
    if profile.get("transport") != "obfs4":
        raise ValueError("probe requires transport=obfs4")
    reader, writer, _ = await open_session(profile, args.ca)
    elapsed = []
    try:
        for _ in range(args.samples):
            nonce = secrets.token_bytes(8)
            start = time.monotonic()
            writer.write(encode(Kind.PING, nonce))
            await asyncio.wait_for(writer.drain(), 10)
            if await read_frame(reader, 10) != (Kind.PONG, nonce):
                raise ConnectionError("unexpected probe response")
            elapsed.append((time.monotonic() - start) * 1000)
        print(json.dumps({"successful_roundtrips": len(elapsed),
            "mean_rtt_ms": sum(elapsed) / len(elapsed),
            "obfs4_process_verified_by_probe": False,
            "detection_tested": False}, indent=2))
    finally:
        await close_stream(writer)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("profile")
    parser.add_argument("--ca", help="Private/test CA; never disables verification")
    parser.add_argument("--samples", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.samples <= 100:
        parser.error("samples must be 1..100")
    asyncio.run(main(args))
