import asyncio
import ipaddress
import json
import secrets
import struct
from enum import IntEnum

VERSION = 1
MAX_PAYLOAD = 4096
MAX_PADDING = 511
MTU = 1280
HEADER = struct.Struct("!BBHHH")


class Kind(IntEnum):
    AUTH = 1
    CONFIG = 2
    PACKET = 3
    PING = 4
    PONG = 5
    CLOSE = 6


class ProtocolError(ValueError):
    pass


def encode(kind, payload=b"", *, padded=None):
    kind = Kind(kind)
    if len(payload) > MAX_PAYLOAD:
        raise ProtocolError("payload too large")
    # No size buckets: the previous 256-byte quantization survived TLS encryption.
    # This removes that particular signal; uniform padding is not browser mimicry.
    if padded is None:
        padded = kind != Kind.PACKET
    pad = secrets.randbelow(96) if padded else 0
    return HEADER.pack(VERSION, kind, len(payload), pad, 0) + payload + secrets.token_bytes(pad)


def parse_header(data):
    version, kind, length, padding, reserved = HEADER.unpack(data)
    if version != VERSION or reserved or length > MAX_PAYLOAD or padding > MAX_PADDING:
        raise ProtocolError("invalid header")
    try:
        return Kind(kind), length, padding
    except ValueError as exc:
        raise ProtocolError("unknown frame") from exc


async def read_frame(reader, timeout=90):
    async def read():
        kind, length, padding = parse_header(await reader.readexactly(HEADER.size))
        data = await reader.readexactly(length + padding)
        return kind, data[:length]
    return await asyncio.wait_for(read(), timeout)


def json_bytes(value):
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def json_object(data):
    try:
        value = json.loads(data)
    except (ValueError, UnicodeError) as exc:
        raise ProtocolError("invalid JSON") from exc
    if not isinstance(value, dict):
        raise ProtocolError("expected JSON object")
    return value


def ipv4(packet, *, source=None, destination=None):
    if len(packet) < 20 or len(packet) > MTU or packet[0] >> 4 != 4:
        raise ProtocolError("invalid IPv4 packet")
    ihl = (packet[0] & 15) * 4
    if ihl < 20 or ihl > len(packet) or int.from_bytes(packet[2:4], "big") != len(packet):
        raise ProtocolError("invalid IPv4 length")
    src, dst = str(ipaddress.IPv4Address(packet[12:16])), str(ipaddress.IPv4Address(packet[16:20]))
    if source is not None and source != src:
        raise ProtocolError("source mismatch")
    if destination is not None and destination != dst:
        raise ProtocolError("destination mismatch")
    return src, dst
