"""Adapter tests use a plain loopback SOCKS stub, NOT real obfs4.

They prove argument framing, TLS validation and tunnel routing only.
"""
import asyncio
import base64
import copy
import contextlib
import hashlib
import unittest
from unittest.mock import patch

from sever.client import open_session
from sever.profile import validate_profile
from sever.pt_backend import listen
from sever.server import Gateway
from sever.wire import Kind, encode, read_frame
import test_protocol as legacy


class AdapterTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        legacy.TLSTests.setUpClass.__func__(cls)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    async def asyncSetUp(self):
        self.token = "a" * 43
        self.tun = legacy.MemoryTun()
        self.gateway = Gateway(self.tun, {"alice": {"ip": "10.77.0.2",
            "token_sha256": hashlib.sha256(self.token.encode()).hexdigest()}})
        self.backend = await listen(self.gateway, self.cert, self.key, port=0)
        self.backend_port = self.backend.sockets[0].getsockname()[1]
        self.tasks, self.writers, self.arguments, self.targets = set(), [], [], []
        self.mode = "ok"
        self.proxy = await asyncio.start_server(self.handle_proxy, "127.0.0.1", 0)
        self.proxy_port = self.proxy.sockets[0].getsockname()[1]
        self.pump = asyncio.create_task(self.gateway.tun_loop())
        self.profile = {"version": 2, "protocol": "sever1", "transport": "obfs4",
            "name": "Adapter lab", "user": "alice", "token": self.token,
            "local_proxy": {"host": "127.0.0.1", "port": self.proxy_port},
            "endpoints": [{"host": "192.0.2.10", "port": 443,
                "server_name": "localhost", "cert": base64.b64encode(b"c" * 52).decode().rstrip("="),
                "iat_mode": 1}]}

    async def asyncTearDown(self):
        self.proxy.close()
        self.backend.close()
        for writer in self.writers:
            writer.close()
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(asyncio.gather(*(w.wait_closed() for w in self.writers), return_exceptions=True), 2)
        self.pump.cancel()
        for task in list(self.tasks):
            task.cancel()
        await asyncio.gather(self.pump, *self.tasks, return_exceptions=True)
        await asyncio.wait_for(self.proxy.wait_closed(), 3)
        await asyncio.wait_for(self.backend.wait_closed(), 3)

    async def handle_proxy(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        self.writers.append(writer)
        pumps = []
        upstream_w = None
        try:
            self.assertEqual(await reader.readexactly(3), b"\x05\x01\x02")
            writer.write(b"\x05\x00" if self.mode == "noauth" else b"\x05\x02")
            await writer.drain()
            if self.mode == "noauth":
                self.assertEqual(await reader.read(1), b"")
                return
            version, size = await reader.readexactly(2)
            self.assertEqual(version, 1)
            args = await reader.readexactly(size)
            size = (await reader.readexactly(1))[0]
            self.assertEqual(await reader.readexactly(size), b"\x00")
            self.arguments.append(args)
            writer.write(b"\x01\x00")
            await writer.drain()
            self.assertEqual(await reader.readexactly(4), b"\x05\x01\x00\x01")
            self.targets.append(await reader.readexactly(6))
            if self.mode == "reject":
                writer.write(b"\x05\x05\x00\x01" + b"\x00" * 6)
                await writer.drain()
                return
            upstream_r, upstream_w = await asyncio.open_connection("127.0.0.1", self.backend_port)
            self.writers.append(upstream_w)
            # Split SOCKS reply to verify stream framing, not TCP packet assumptions.
            for part in [b"\x05", b"\x00\x00\x01", b"\x00" * 6]:
                writer.write(part)
                await writer.drain()
                await asyncio.sleep(0)
            async def relay(src, dst):
                while data := await src.read(16384):
                    dst.write(data)
                    await dst.drain()
            pumps = [asyncio.create_task(relay(reader, upstream_w)),
                     asyncio.create_task(relay(upstream_r, writer))]
            await asyncio.wait(pumps, return_when=asyncio.FIRST_COMPLETED)
        except (OSError, asyncio.IncompleteReadError):
            pass
        finally:
            for pump in pumps:
                pump.cancel()
            await asyncio.gather(*pumps, return_exceptions=True)
            if upstream_w:
                upstream_w.close()
            writer.close()
            self.tasks.discard(task)

    async def test_adapter_packet_roundtrip_and_transport_args(self):
        r, w, config = await open_session(self.profile, self.cert)
        self.writers.append(w)
        self.assertEqual(config["ip"], "10.77.0.2")
        self.assertEqual(w.get_extra_info("ssl_object").version(), "TLSv1.3")
        expected = f"cert={self.profile['endpoints'][0]['cert']};iat-mode=1".encode()
        self.assertEqual(self.arguments, [expected])
        self.assertEqual(self.targets, [b"\xc0\x00\x02\x0a\x01\xbb"])
        w.write(encode(Kind.PACKET, legacy.packet()))
        await w.drain()
        self.assertEqual(await asyncio.wait_for(self.tun.outgoing.get(), 2), legacy.packet())
        reply = legacy.packet("1.1.1.1", "10.77.0.2")
        self.tun.incoming.put_nowait(reply)
        self.assertEqual(await read_frame(r, 2), (Kind.PACKET, reply))
        w.write(encode(Kind.PING, b"abcdefgh"))
        await w.drain()
        self.assertEqual(await read_frame(r, 2), (Kind.PONG, b"abcdefgh"))

    async def test_reject_proxy_noauth_downgrade(self):
        self.mode = "noauth"
        with self.assertRaises(ConnectionError):
            await open_session(self.profile, self.cert)
        self.assertFalse(self.arguments)
        self.assertFalse(self.gateway.peers)

    async def test_proxy_failure_never_dials_bridge_directly(self):
        self.mode = "reject"
        original = asyncio.open_connection
        with patch("asyncio.open_connection", wraps=original) as dial:
            with self.assertRaises(ConnectionError):
                await open_session(self.profile, self.cert)
            self.assertEqual(dial.call_count, 1)
            self.assertEqual(dial.call_args.args, ("127.0.0.1", self.proxy_port))

    async def test_wrong_tls_name_and_untrusted_ca(self):
        with self.assertRaises(ConnectionError):
            await open_session(self.profile)
        self.profile["endpoints"][0]["server_name"] = "wrong.example"
        with self.assertRaises(ConnectionError):
            await open_session(self.profile, self.cert)
        self.assertFalse(self.gateway.peers)

    async def test_wrong_user_token_rejected_inside_tls(self):
        self.profile["token"] = "x" * 43
        with self.assertRaises(ConnectionError):
            await open_session(self.profile, self.cert)
        self.assertFalse(self.gateway.peers)

    async def test_backend_rejects_public_bind(self):
        with self.assertRaises(ValueError):
            await listen(self.gateway, self.cert, self.key, bind="0.0.0.0", port=0)

    async def test_profile_rejects_external_proxy_and_invalid_bridge(self):
        changes = [("proxy", "host", "8.8.8.8"), ("proxy", "host", "localhost"),
                   ("proxy", "port", True), ("endpoint", "host", "bridge.example"),
                   ("endpoint", "cert", "bad"), ("endpoint", "cert", "A" * 70 + ";x=1"),
                   ("endpoint", "iat_mode", True), ("endpoint", "iat_mode", 3),
                   ("endpoint", "server_name", "bad/name")]
        validate_profile(self.profile)
        for target, key, value in changes:
            profile = copy.deepcopy(self.profile)
            (profile["local_proxy"] if target == "proxy" else profile["endpoints"][0])[key] = value
            with self.subTest(target=target, key=key, value=value), self.assertRaises(ValueError):
                validate_profile(profile)
