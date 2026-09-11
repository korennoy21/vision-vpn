"""Private TLS backend for the obfs4 server reverse proxy."""
import argparse
import asyncio
import contextlib
import ipaddress

from .server import Gateway, load_users, tls_context


async def listen(gateway, cert, key, bind="127.0.0.1", port=8788, sessions=None):
    if not ipaddress.ip_address(bind).is_loopback:
        raise ValueError("PT backend must bind to a literal loopback address")
    if sessions is None:
        sessions = set()
    async def handle(reader, writer):
        task = asyncio.current_task()
        sessions.add(task)
        try:
            await gateway.handle(reader, writer)
        finally:
            sessions.discard(task)
    return await asyncio.start_server(handle, bind, port,
        ssl=tls_context(cert, key), ssl_handshake_timeout=10,
        ssl_shutdown_timeout=2, limit=16384)


async def main(args):
    from .tun import LinuxTun
    users = load_users(args.users)
    context = tls_context(args.cert, args.key)  # Validate files before opening TUN.
    del context
    tun = LinuxTun(args.tun, "10.77.0.1/24")
    sessions, server = set(), None
    try:
        gateway = Gateway(tun, users, args.dns)
        server = await listen(gateway, args.cert, args.key, port=args.port, sessions=sessions)
        print(f"Private TLS backend on 127.0.0.1:{args.port}; external obfs4 required", flush=True)
        await gateway.tun_loop()
    finally:
        if server:
            server.close()
        for task in list(sessions):
            task.cancel()
        await asyncio.gather(*sessions, return_exceptions=True)
        if server:
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(server.wait_closed(), 12)
        tun.close()


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8788)
    p.add_argument("--tun", default="sever0")
    p.add_argument("--dns", default="1.1.1.1")
    p.add_argument("--users", required=True)
    p.add_argument("--cert", required=True)
    p.add_argument("--key", required=True)
    asyncio.run(main(p.parse_args()))
