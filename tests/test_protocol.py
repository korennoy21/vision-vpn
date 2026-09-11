import asyncio
import hashlib
import ipaddress
import json
from pathlib import Path
import ssl
import struct
import subprocess
import shutil
import tempfile
import unittest

from sever.server import Gateway, load_users, tls_context
from sever.wire import HEADER, Kind, ProtocolError, encode, ipv4, json_bytes, json_object, parse_header, read_frame


def packet(src="10.77.0.2", dst="1.1.1.1", body=b"test"):
    header = bytearray(struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(body), 0, 0, 64, 1, 0,
        ipaddress.IPv4Address(src).packed, ipaddress.IPv4Address(dst).packed))
    words = struct.unpack("!10H", header)
    checksum = sum(words)
    while checksum >> 16:
        checksum = (checksum & 65535) + (checksum >> 16)
    header[10:12] = ((~checksum) & 65535).to_bytes(2, "big")
    return bytes(header) + body


class WireTests(unittest.IsolatedAsyncioTestCase):
    async def test_stream_split_and_coalesced(self):
        reader = asyncio.StreamReader()
        raw = encode(Kind.PING, b"12345678") + encode(Kind.CLOSE)
        async def feed():
            for byte in raw:
                reader.feed_data(bytes([byte]))
                await asyncio.sleep(0)
        feeding = asyncio.create_task(feed())
        self.assertEqual(await read_frame(reader), (Kind.PING, b"12345678"))
        self.assertEqual(await read_frame(reader), (Kind.CLOSE, b""))
        await feeding

    async def test_truncated_and_deadline(self):
        reader = asyncio.StreamReader()
        reader.feed_data(encode(Kind.PING, b"12345678")[:10])
        reader.feed_eof()
        with self.assertRaises(asyncio.IncompleteReadError):
            await read_frame(reader)
        with self.assertRaises(TimeoutError):
            await read_frame(asyncio.StreamReader(), 0.01)

    async def test_header_bounds_and_unknown(self):
        for values in [(2, 1, 0, 0, 0), (1, 99, 0, 0, 0), (1, 1, 4097, 0, 0),
                       (1, 1, 0, 512, 0), (1, 1, 0, 0, 1)]:
            with self.assertRaises(ProtocolError):
                parse_header(HEADER.pack(*values))
        with self.assertRaises(ProtocolError):
            encode(Kind.PACKET, b"x" * 4097)

    async def test_padding_and_vectors(self):
        for length in (0, 1, 20, 248, 1280, 4096):
            raw = encode(Kind.PACKET, b"x" * length)
            kind, n, p = parse_header(raw[:8])
            self.assertEqual(n, length)
            self.assertEqual(len(raw), 8 + length + p)
            self.assertLessEqual(p, 511)
        vectors = json.loads((Path(__file__).parent.parent / "docs/vectors.json").read_text())
        for row in vectors:
            self.assertEqual(encode(row["kind"], bytes.fromhex(row["payload_hex"]), padded=False).hex(), row["frame_hex"])

    async def test_packet_validation(self):
        self.assertEqual(ipv4(packet()), ("10.77.0.2", "1.1.1.1"))
        for bad in (b"", b"\x60" * 40, packet()[:-1], packet(body=b"a" * 1280), b"\x4f" + packet()[1:]):
            with self.assertRaises(ProtocolError):
                ipv4(bad)
        with self.assertRaises(ProtocolError):
            ipv4(packet(), source="10.77.0.3")
        with self.assertRaises(ProtocolError):
            json_object(b"[]")


class MemoryTun:
    def __init__(self):
        self.incoming = asyncio.Queue()
        self.outgoing = asyncio.Queue()
    async def read(self):
        return await self.incoming.get()
    def write(self, data):
        self.outgoing.put_nowait(data)


class TLSTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.cert, cls.key = [str(Path(cls.temp.name) / n) for n in ("cert.pem", "key.pem")]
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
            "-keyout", cls.key, "-out", cls.cert, "-subj", "/CN=localhost",
            "-addext", "subjectAltName=DNS:localhost"], check=True, capture_output=True)
    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    async def asyncSetUp(self):
        self.token = "a" * 43
        self.users = {"alice": {"ip": "10.77.0.2", "token_sha256": hashlib.sha256(self.token.encode()).hexdigest()},
                      "bob": {"ip": "10.77.0.3", "token_sha256": hashlib.sha256(("b" * 43).encode()).hexdigest()}}
        self.tun = MemoryTun()
        self.gateway = Gateway(self.tun, self.users)
        self.handlers = set()
        async def handle(r, w):
            task = asyncio.current_task()
            self.handlers.add(task)
            try:
                await self.gateway.handle(r, w)
            finally:
                self.handlers.discard(task)
        self.server = await asyncio.start_server(handle, "127.0.0.1", 0, ssl=tls_context(self.cert, self.key), ssl_handshake_timeout=1)
        self.port = self.server.sockets[0].getsockname()[1]
        self.pump = asyncio.create_task(self.gateway.tun_loop())
        self.writers = []

    async def asyncTearDown(self):
        for w in self.writers:
            w.close()
        await asyncio.gather(*(w.wait_closed() for w in self.writers), return_exceptions=True)
        self.server.close()
        await self.server.wait_closed()
        self.pump.cancel()
        await asyncio.gather(self.pump, return_exceptions=True)
        tasks = list(self.handlers)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def connect(self, token=None, user="alice", hostname="localhost"):
        ctx = ssl.create_default_context(cafile=self.cert)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_3
        r, w = await asyncio.open_connection("127.0.0.1", self.port, ssl=ctx, server_hostname=hostname)
        self.writers.append(w)
        self.assertEqual(w.get_extra_info("ssl_object").version(), "TLSv1.3")
        w.write(encode(Kind.AUTH, json_bytes({"user": user, "token": self.token if token is None else token})))
        await w.drain()
        return r, w

    async def test_real_tls_packet_roundtrip_and_ping(self):
        r, w = await self.connect()
        kind, payload = await read_frame(r)
        self.assertEqual(kind, Kind.CONFIG)
        self.assertEqual(json_object(payload)["ip"], "10.77.0.2")
        outgoing = packet()
        w.write(encode(Kind.PACKET, outgoing))
        await w.drain()
        self.assertEqual(await asyncio.wait_for(self.tun.outgoing.get(), 1), outgoing)
        response = packet("1.1.1.1", "10.77.0.2")
        self.tun.incoming.put_nowait(response)
        self.assertEqual(await read_frame(r), (Kind.PACKET, response))
        w.write(encode(Kind.PING, b"12345678"))
        await w.drain()
        self.assertEqual(await read_frame(r), (Kind.PONG, b"12345678"))

    async def test_invalid_auth(self):
        for user, token in [("alice", "wrong" * 9), ("unknown", "a" * 43), ([], "a" * 43)]:
            r, _ = await self.connect(token=token, user=user)
            self.assertEqual(await asyncio.wait_for(r.read(1), 1), b"")
        self.assertFalse(self.gateway.peers)

    async def test_duplicate_replaces_stale_owner_for_handover(self):
        r, _ = await self.connect()
        await read_frame(r)
        r2, w2 = await self.connect()
        kind, payload = await read_frame(r2)
        self.assertEqual(kind, Kind.CONFIG)
        self.assertEqual(json_object(payload)["ip"], "10.77.0.2")
        self.assertEqual(await asyncio.wait_for(r.read(1), 1), b"")
        self.assertIn("10.77.0.2", self.gateway.peers)
        w2.write(encode(Kind.PING, b"abcdefgh"))
        await w2.drain()
        self.assertEqual(await read_frame(r2), (Kind.PONG, b"abcdefgh"))

    async def test_spoofed_source_rejected(self):
        r, w = await self.connect()
        await read_frame(r)
        w.write(encode(Kind.PACKET, packet(src="10.77.0.3")))
        await w.drain()
        self.assertEqual(await asyncio.wait_for(r.read(1), 1), b"")
        self.assertTrue(self.tun.outgoing.empty())

    async def test_private_destination_is_dropped_without_killing_session(self):
        r, w = await self.connect()
        await read_frame(r)
        w.write(encode(Kind.PACKET, packet(dst="169.254.169.254")))
        await w.drain()
        self.assertTrue(self.tun.outgoing.empty())
        w.write(encode(Kind.PING, b"abcdefgh"))
        await w.drain()
        self.assertEqual(await read_frame(r), (Kind.PONG, b"abcdefgh"))

    async def test_peer_isolation(self):
        r1, _ = await self.connect()
        await read_frame(r1)
        r2, _ = await self.connect(user="bob", token="b" * 43)
        await read_frame(r2)
        reply = packet("1.1.1.1", "10.77.0.3")
        self.tun.incoming.put_nowait(reply)
        self.assertEqual(await read_frame(r2), (Kind.PACKET, reply))
        with self.assertRaises(TimeoutError):
            await read_frame(r1, 0.02)

    async def test_hostname_verification(self):
        loop = asyncio.get_running_loop()
        previous = loop.get_exception_handler()
        def expected_reset(loop, context):
            if not isinstance(context.get("exception"), ConnectionResetError):
                if previous:
                    previous(loop, context)
                else:
                    loop.default_exception_handler(context)
        loop.set_exception_handler(expected_reset)
        try:
            with self.assertRaises(ssl.SSLCertVerificationError):
                await self.connect(hostname="wrong.example")
            await asyncio.sleep(0.01)
        finally:
            loop.set_exception_handler(previous)

    @unittest.skipUnless(shutil.which("java"), "Java 17 required")
    async def test_android_wire_java_interoperability(self):
        root = Path(__file__).resolve().parent.parent
        compile_process = await asyncio.create_subprocess_exec("java", "com.sun.tools.javac.Main", "-d", self.temp.name,
            str(root / "android-client/app/src/main/java/dev/sever/vpn/Wire.java"), str(root / "tests/JavaInterop.java"),
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        stdout, stderr = await compile_process.communicate()
        self.assertEqual(compile_process.returncode, 0, stderr.decode())
        process = await asyncio.create_subprocess_exec("java", "-cp", self.temp.name, "JavaInterop", self.cert,
            str(self.port), packet().hex(), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        async def reflect():
            data = await asyncio.wait_for(self.tun.outgoing.get(), 10)
            self.assertEqual(data, packet())
            self.tun.incoming.put_nowait(packet("1.1.1.1", "10.77.0.2"))
        echo = asyncio.create_task(reflect())
        try:
            stdout, stderr = await asyncio.wait_for(process.communicate(), 15)
            await echo
            self.assertEqual(process.returncode, 0, stderr.decode())
            self.assertIn(b"JAVA_INTEROP_OK", stdout)
        finally:
            echo.cancel()
            await asyncio.gather(echo, return_exceptions=True)
            if process.returncode is None:
                process.kill()
                await process.wait()

    async def test_idle_expiry(self):
        self.gateway.timeout = 0.1
        r, _ = await self.connect()
        await read_frame(r)
        self.assertEqual(await asyncio.wait_for(r.read(1), 1), b"")

    async def test_user_file_duplicate_validation(self):
        path = Path(self.temp.name) / "users.json"
        path.write_text(json.dumps(self.users))
        self.assertEqual(load_users(path), self.users)
        self.users["bob"]["ip"] = "10.77.0.2"
        path.write_text(json.dumps(self.users))
        with self.assertRaises(ValueError):
            load_users(path)


if __name__ == "__main__":
    unittest.main()
