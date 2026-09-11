"""Managed WSS node. Polls the panel over HTTPS; expires access on lease loss."""
import asyncio
import contextlib
import ipaddress
import json
import os
from pathlib import Path
import re
import ssl
import time
from urllib.parse import urlsplit
import aiohttp
from aiohttp import web
from .server import Gateway
from .webtransport import make_app


def validate_users(users):
    if not isinstance(users, dict) or len(users) > 253:
        raise ValueError('invalid user set')
    ips = set()
    for name, row in users.items():
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', name) or not isinstance(row, dict):
            raise ValueError('invalid user')
        ip = ipaddress.ip_address(row.get('ip', ''))
        if ip.version != 4 or ip not in ipaddress.ip_network('10.77.0.0/24') or not 2 <= int(ip) & 255 <= 254 or str(ip) in ips:
            raise ValueError('invalid address')
        if not isinstance(row.get('token_sha256'), str) or not re.fullmatch(r'[a-f0-9]{64}', row['token_sha256']):
            raise ValueError('invalid token hash')
        ips.add(str(ip))
    return users


class Controller:
    def __init__(self, gateway, base_url, token, allow_http=False):
        self.gateway, self.base_url, self.token = gateway, base_url.rstrip('/'), token
        parsed = urlsplit(self.base_url)
        if parsed.scheme != 'https' and not (allow_http and parsed.scheme == 'http' and parsed.hostname == '127.0.0.1'):
            raise ValueError('controller requires HTTPS')
        if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
            raise ValueError('invalid control URL')
        if not re.fullmatch(r'[A-Za-z0-9_-]{43}', token):
            raise ValueError('invalid node token')
        self.last_success = None

    def expire(self, now=None):
        now = time.monotonic() if now is None else now
        if self.last_success is None or now - self.last_success >= 60:
            self.gateway.replace_users({})

    async def sync_once(self, session):
        async with session.get(self.base_url + '/agent/config', allow_redirects=False) as response:
            if response.status != 200:
                raise ConnectionError('control config rejected')
            raw = bytearray()
            async for chunk in response.content.iter_chunked(16384):
                raw.extend(chunk)
                if len(raw) > 131072:
                    raise ValueError('control response too large')
            data = json.loads(raw)
            users = validate_users(data['users'])
            if data.get('path') != '/api/session' or data.get('lease_seconds') != 60:
                raise ValueError('unsupported controller config')
            activity = data.get('activity') if isinstance(data.get('activity'), dict) else {}
        self.gateway.replace_users(users)
        self.gateway.activity.configure(
            enabled=activity.get('enabled', False) is True,
            log_ips=activity.get('log_ips', True) is True,
        )
        self.last_success = time.monotonic()
        async with session.post(self.base_url + '/agent/status', json={'online': len(self.gateway.peers)}, allow_redirects=False) as response:
            if response.status != 200:
                raise ConnectionError('status report rejected')

        # Activity reporting is optional and must never interrupt VPN access.
        batch = self.gateway.activity.drain(500)
        if batch:
            try:
                async with session.post(self.base_url + '/agent/activity', json={'events': batch}, allow_redirects=False) as response:
                    if response.status != 200:
                        raise ConnectionError('activity report rejected')
            except (OSError, ValueError, aiohttp.ClientError, TimeoutError, ConnectionError):
                self.gateway.activity.restore(batch)

    async def run(self):
        # A separate watchdog expires access even while a network request is pending.
        async def watchdog():
            while True:
                self.expire()
                await asyncio.sleep(1)
        watch = asyncio.create_task(watchdog())
        ssl_context = ssl.create_default_context()
        ssl_context.minimum_version = ssl.TLSVersion.TLSv1_2
        try:
            async with aiohttp.ClientSession(headers={'Authorization': 'Bearer ' + self.token},
                timeout=aiohttp.ClientTimeout(total=10), trust_env=False,
                cookie_jar=aiohttp.DummyCookieJar(), connector=aiohttp.TCPConnector(ssl=ssl_context)) as session:
                while True:
                    try:
                        await self.sync_once(session)
                    except (OSError, ValueError, KeyError, TypeError, aiohttp.ClientError, TimeoutError):
                        # Do not print URLs, authorization headers or user data.
                        print('Control sync failed; existing access expires after 60 seconds', flush=True)
                    await asyncio.sleep(5)
        finally:
            watch.cancel()
            await asyncio.gather(watch, return_exceptions=True)
            self.gateway.replace_users({})


async def main():
    from .tun import LinuxTun
    config = json.loads(Path(os.environ.get('NODE_CONFIG', '/run/sever/node.json')).read_text())
    tun = LinuxTun('sever0', '10.77.0.1/24')
    gateway = Gateway(tun, {})
    runner = web.AppRunner(make_app(gateway, site_root=Path(__file__).resolve().parent.parent / 'ops/site'), access_log=None, shutdown_timeout=3)
    tasks = []
    try:
        controller = Controller(gateway, config['control_url'], config['node_token'])
        await runner.setup()
        await web.TCPSite(runner, '127.0.0.1', 8787).start()
        tasks = [asyncio.create_task(gateway.tun_loop()), asyncio.create_task(controller.run())]
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done: task.result()
    finally:
        for task in tasks: task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        gateway.replace_users({})
        await runner.cleanup()
        tun.close()

if __name__ == '__main__':
    asyncio.run(main())
