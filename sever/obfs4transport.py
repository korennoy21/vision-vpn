"""Linux adapter for an independently installed obfs4 SOCKS5 client.

This module is NOT an obfs4 implementation. It never opens a direct connection
to the bridge: all network data, including TLS, goes through the local PT.
"""
import asyncio
import ipaddress
import ssl

from .wire import Kind, ProtocolError, encode, json_bytes, json_object, read_frame


async def close_stream(writer):
    writer.close()
    try:
        await asyncio.wait_for(writer.wait_closed(), 2)
    except (OSError, TimeoutError):
        writer.transport.abort()


async def socks_connect(reader, writer, endpoint):
    # Tor PT SOCKS5 extension: transport arguments occupy username/password.
    # Require username/password negotiation so arguments cannot be discarded.
    writer.write(b"\x05\x01\x02")
    await writer.drain()
    if await reader.readexactly(2) != b"\x05\x02":
        raise ProtocolError("PT must accept SOCKS5 transport arguments")
    args = f"cert={endpoint['cert']};iat-mode={endpoint['iat_mode']}".encode("ascii")
    if not 1 <= len(args) <= 255:
        raise ProtocolError("invalid transport arguments")
    writer.write(b"\x01" + bytes([len(args)]) + args + b"\x01\x00")
    await writer.drain()
    if await reader.readexactly(2) != b"\x01\x00":
        raise ProtocolError("PT rejected transport arguments")
    # Literal bridge IP: no local DNS lookup, no target-name ambiguity.
    address = ipaddress.ip_address(endpoint["host"])
    atyp = 1 if address.version == 4 else 4
    writer.write(b"\x05\x01\x00" + bytes([atyp]) + address.packed
                 + endpoint["port"].to_bytes(2, "big"))
    await writer.drain()
    version, status, reserved, atyp = await reader.readexactly(4)
    if (version, status, reserved) != (5, 0, 0):
        raise ProtocolError("PT connection failed")
    if atyp == 1:
        size = 4
    elif atyp == 4:
        size = 16
    elif atyp == 3:
        size = (await reader.readexactly(1))[0]
        if size == 0:
            raise ProtocolError("invalid SOCKS5 bound address")
    else:
        raise ProtocolError("invalid SOCKS5 address type")
    await reader.readexactly(size + 2)


async def open_session(profile, cafile=None):
    from .profile import validate_profile
    validate_profile(profile)
    if profile["transport"] != "obfs4":
        raise ValueError("obfs4 profile required")
    ctx = ssl.create_default_context(cafile=cafile)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.maximum_version = ssl.TLSVersion.TLSv1_3
    proxy = profile["local_proxy"]
    for endpoint in profile["endpoints"]:
        writer = None
        try:
            async with asyncio.timeout(30):
                reader, writer = await asyncio.open_connection(proxy["host"], proxy["port"])
                await socks_connect(reader, writer, endpoint)
                # TLS starts only AFTER the PT tunnel is established. Certificate
                # chain and server_name checks remain mandatory inside obfs4.
                await writer.start_tls(ctx, server_hostname=endpoint["server_name"],
                                       ssl_handshake_timeout=10)
                writer.write(encode(Kind.AUTH, json_bytes({"user": profile["user"], "token": profile["token"]})))
                await writer.drain()
                kind, data = await read_frame(reader, 10)
                if kind != Kind.CONFIG:
                    raise ProtocolError("CONFIG required")
                config = json_object(data)
                if config.get("mtu") != 1280 or config.get("prefix") != 24:
                    raise ProtocolError("unsupported network configuration")
                return reader, writer, config
        except asyncio.CancelledError:
            if writer:
                await close_stream(writer)
            raise
        except (OSError, ValueError, TimeoutError, asyncio.IncompleteReadError):
            if writer:
                await close_stream(writer)
    raise ConnectionError("No obfs4 endpoint accepted the session; no direct fallback")
