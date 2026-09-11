import asyncio
import base64
import hashlib
import json
from pathlib import Path
import shutil
import ssl
import tempfile
import unittest
from unittest.mock import patch

import aiohttp
from aiohttp import web
from sever.client import open_session
from sever.heartbeat import Heartbeat
from sever.profile import validate_profile
from sever.server import Gateway, tls_context
from sever.webtransport import WSReader, WSWriter, authorize, check_message, make_app
from sever.wire import Kind, ProtocolError, encode, read_frame
import test_protocol as legacy
from test_protocol import MemoryTun, packet


class WebTransportTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        legacy.TLSTests.setUpClass.__func__(cls)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    async def asyncSetUp(self):
        self.token = "a" * 43
        self.users = {"alice": {"ip": "10.77.0.2", "token_sha256": hashlib.sha256(self.token.encode()).hexdigest()}}
        self.tun = MemoryTun()
        self.gateway = Gateway(self.tun, self.users)
        self.site = tempfile.TemporaryDirectory()
        (Path(self.site.name) / "index.html").write_text("<!doctype html><title>Test website</title><p>Website content</p>")
        app = make_app(self.gateway, site_root=self.site.name)
        self.runner = web.AppRunner(app, access_log=None, shutdown_timeout=2)
        await self.runner.setup()
        context = tls_context(self.cert, self.key)
        context.set_alpn_protocols(["http/1.1"])
        await web.TCPSite(self.runner, "127.0.0.1", 0, ssl_context=context).start()
        self.port = self.runner.addresses[0][1]
        self.base = f"https://localhost:{self.port}"
        self.ctx = ssl.create_default_context(cafile=self.cert)
        self.ctx.minimum_version = ssl.TLSVersion.TLSv1_3
        self.client = aiohttp.ClientSession(connector=aiohttp.TCPConnector(ssl=self.ctx), timeout=aiohttp.ClientTimeout(total=5))
        self.pump = asyncio.create_task(self.gateway.tun_loop())

    async def asyncTearDown(self):
        await self.client.close()
        self.pump.cancel()
        await asyncio.gather(self.pump, return_exceptions=True)
        await self.runner.cleanup()
        self.site.cleanup()

    def profile(self):
        return {"version": 2, "protocol": "sever1", "transport": "wss", "name": "Lab",
                "user": "alice", "token": self.token,
                "endpoints": [{"host": "localhost", "port": self.port, "path": "/api/session"}]}

    async def connect(self):
        return await self.client.ws_connect(self.base + "/api/session", auth=aiohttp.BasicAuth("alice", self.token), compress=0)

    async def test_ordinary_https_get_and_head(self):
        for method in ("GET", "HEAD"):
            async with self.client.request(method, self.base + "/") as response:
                self.assertEqual(response.status, 200)
                self.assertEqual(response.content_type, "text/html")
                body = await response.read()
                if method == "GET": self.assertIn(b"Website content", body)
                else: self.assertEqual(body, b"")

    async def test_unauthenticated_upgrade_matches_website_404(self):
        headers = {"Upgrade": "websocket", "Connection": "Upgrade", "Sec-WebSocket-Version": "13",
                   "Sec-WebSocket-Key": base64.b64encode(b"0" * 16).decode()}
        baseline = None
        for path, auth in [("/missing", None), ("/api/session", None), ("/api/session", aiohttp.BasicAuth("alice", "x" * 43)),
                           ("/api/session", aiohttp.BasicAuth("unknown", "x" * 43))]:
            async with self.client.get(self.base + path, headers=headers, auth=auth) as response:
                sample = (response.status, response.content_type, await response.read())
                self.assertNotIn("Sec-WebSocket-Accept", response.headers)
                self.assertNotIn("WWW-Authenticate", response.headers)
                if baseline is None: baseline = sample
                self.assertEqual(sample, baseline)
        self.assertEqual(baseline[0], 404)
        self.assertFalse(self.gateway.peers)

    async def test_wss_roundtrip_python_client(self):
        reader, writer, config = await open_session(self.profile(), self.cert)
        try:
            self.assertEqual(config["ip"], "10.77.0.2")
            writer.write(encode(Kind.PACKET, packet()))
            await writer.drain()
            self.assertEqual(await asyncio.wait_for(self.tun.outgoing.get(), 2), packet())
            response = packet("1.1.1.1", "10.77.0.2")
            self.tun.incoming.put_nowait(response)
            self.assertEqual(await read_frame(reader, 2), (Kind.PACKET, response))
            writer.write(encode(Kind.PING, b"abcdefgh")); await writer.drain()
            self.assertEqual(await read_frame(reader, 2), (Kind.PONG, b"abcdefgh"))
        finally:
            writer.close(); await writer.wait_closed()

    async def test_wss_rejects_wrong_hostname_and_untrusted_ca(self):
        with self.assertRaises(ConnectionError):
            await open_session(self.profile())
        profile = self.profile()
        profile["endpoints"][0]["host"] = "127.0.0.1"
        with self.assertRaises(ConnectionError):
            await open_session(profile, self.cert)
        self.assertFalse(self.gateway.peers)

    async def test_duplicate_session_replaces_first_for_handover(self):
        async with await self.connect() as first:
            first_reader = WSReader(first)
            self.assertEqual((await read_frame(first_reader, 2))[0], Kind.CONFIG)
            async with await self.connect() as second:
                second_reader = WSReader(second)
                self.assertEqual((await read_frame(second_reader, 2))[0], Kind.CONFIG)
                msg = await first.receive()
                self.assertIn(msg.type, [aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR])
                await second.send_bytes(encode(Kind.PING, b"abcdefgh"))
                self.assertEqual(await read_frame(second_reader, 2), (Kind.PONG, b"abcdefgh"))

    async def test_batched_packet_messages_roundtrip(self):
        async with await self.connect() as ws:
            reader = WSReader(ws)
            self.assertEqual((await read_frame(reader, 2))[0], Kind.CONFIG)
            first, second = packet(), packet(dst='8.8.8.8')
            await ws.send_bytes(encode(Kind.PACKET, first, padded=False) + encode(Kind.PACKET, second, padded=False))
            self.assertEqual(await asyncio.wait_for(self.tun.outgoing.get(), 2), first)
            self.assertEqual(await asyncio.wait_for(self.tun.outgoing.get(), 2), second)
            a = packet('1.1.1.1', '10.77.0.2')
            b = packet('8.8.8.8', '10.77.0.2')
            self.tun.incoming.put_nowait(a); self.tun.incoming.put_nowait(b)
            self.assertEqual(await read_frame(reader, 2), (Kind.PACKET, a))
            self.assertEqual(await read_frame(reader, 2), (Kind.PACKET, b))

    async def test_text_and_bad_binary_are_rejected(self):
        for value in ["text", b"bad", encode(Kind.PING, b"abcdefgh") + b"trailing"]:
            async with await self.connect() as ws:
                await read_frame(WSReader(ws), 2)
                if isinstance(value, str): await ws.send_str(value)
                else: await ws.send_bytes(value)
                msg = await ws.receive()
                self.assertIn(msg.type, [aiohttp.WSMsgType.CLOSE, aiohttp.WSMsgType.CLOSED])
            await asyncio.sleep(0)

    async def test_websocket_control_ping_still_works(self):
        async with await self.client.ws_connect(self.base + "/api/session", auth=aiohttp.BasicAuth("alice", self.token), autoping=False) as ws:
            await ws.receive()
            await ws.ping(b"control")
            pong = await ws.receive()
            self.assertEqual(pong.type, aiohttp.WSMsgType.PONG)
            self.assertEqual(pong.data, b"control")

    async def test_plain_legacy_auth_cannot_upgrade(self):
        async with self.client.post(self.base + "/api/session", data=encode(Kind.AUTH, b"{}")) as response:
            self.assertEqual(response.status, 404)

    async def test_redirect_is_not_followed(self):
        seen = []
        redirect_app = web.Application()
        async def redirect(request):
            raise web.HTTPFound("/target")
        async def target(request):
            seen.append(request.headers.get("Authorization")); return web.Response(text="wrong")
        redirect_app.router.add_get("/api/session", redirect)
        redirect_app.router.add_get("/target", target)
        runner = web.AppRunner(redirect_app, access_log=None)
        await runner.setup()
        await web.TCPSite(runner, "127.0.0.1", 0, ssl_context=tls_context(self.cert, self.key)).start()
        profile = self.profile(); profile["endpoints"][0]["port"] = runner.addresses[0][1]
        try:
            with self.assertRaises(ConnectionError): await open_session(profile, self.cert)
            self.assertEqual(seen, [])
        finally: await runner.cleanup()

    @unittest.skipUnless(shutil.which("java"), "Java 17 required")
    async def test_java_standard_wss_to_python(self):
        root = Path(__file__).resolve().parent.parent
        compile = await asyncio.create_subprocess_exec("java", "com.sun.tools.javac.Main", "-d", self.temp.name,
            str(root / "android-client/app/src/main/java/dev/sever/vpn/Wire.java"), str(root / "tests/JavaWssInterop.java"),
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        _, errors = await compile.communicate()
        self.assertEqual(compile.returncode, 0, errors.decode())
        process = await asyncio.create_subprocess_exec("java", "-cp", self.temp.name, "JavaWssInterop", self.cert,
            str(self.port), packet().hex(), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        async def reflect():
            self.assertEqual(await asyncio.wait_for(self.tun.outgoing.get(), 10), packet())
            self.tun.incoming.put_nowait(packet("1.1.1.1", "10.77.0.2"))
        reflector = asyncio.create_task(reflect())
        try:
            output, errors = await asyncio.wait_for(process.communicate(), 15)
            self.assertEqual(process.returncode, 0, errors.decode())
            await reflector
            self.assertIn(b"JAVA_WSS_OK", output)
        finally:
            reflector.cancel(); await asyncio.gather(reflector, return_exceptions=True)
            if process.returncode is None: process.kill(); await process.wait()


class PolicyTests(unittest.TestCase):
    def test_no_padding_quantization(self):
        lengths = set()
        for padding in range(512):
            with patch("sever.wire.secrets.randbelow", return_value=padding):
                data = encode(Kind.PING, b"12345678")
            check_message(data); lengths.add(len(data))
        self.assertEqual(len(lengths), 512)
        self.assertEqual(len({n % 256 for n in lengths}), 256)

    def test_heartbeat_idle_and_liveness_under_upload(self):
        now = [0.0]
        policy = Heartbeat(clock=lambda: now[0], random=lambda n: 5000)
        now[0] = 24; self.assertFalse(policy.due())
        policy.packet_sent()
        now[0] = 30; self.assertFalse(policy.due())
        now[0] = 40; policy.packet_sent()
        now[0] = 55; self.assertTrue(policy.due())
        policy.ping_sent()
        self.assertFalse(policy.due())

    def test_profile_rejects_raw_transport_and_injected_path(self):
        base = {"version": 2, "protocol": "sever1", "transport": "wss", "user": "alice", "token": "a" * 43,
                "endpoints": [{"host": "vpn.example.com", "port": 443, "path": "/api/session"}]}
        validate_profile(base)
        for change in [{"transport": "tls"}, {"version": 1}]:
            with self.assertRaises(ValueError): validate_profile({**base, **change})
        for path in ["//evil.example", "/api\r\nAuthorization: bad", "/_auth", "/a?token=x"]:
            modified = {**base, "endpoints": [{**base["endpoints"][0], "path": path}]}
            with self.assertRaises(ValueError): validate_profile(modified)

    def test_auth_parser_malformed_input(self):
        for value in [None, "", "Basic !!!!", "Basic " + "A"*2000, "Bearer token", "Basic " + base64.b64encode(b"alice").decode()]:
            self.assertIsNone(authorize(value, {}))
