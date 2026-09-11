import argparse
import asyncio
import hashlib
import hmac
import ipaddress
import json
import logging
import ssl
from pathlib import Path

from .activity import ActivityCollector
from .wire import Kind, MTU, ProtocolError, encode, ipv4, json_bytes, json_object, read_frame

LOG = logging.getLogger("vision")


def load_users(path):
    users = json.loads(Path(path).read_text())
    if not isinstance(users, dict) or not users:
        raise ValueError("users must be a nonempty object")
    ips = set()
    for name, record in users.items():
        address = ipaddress.IPv4Address(record["ip"])
        digest = record["token_sha256"]
        if not isinstance(name, str) or not 1 <= len(name) <= 64:
            raise ValueError("invalid user")
        if address not in ipaddress.IPv4Network("10.77.0.0/24") or int(address) & 255 not in range(2, 255):
            raise ValueError("user address must be 10.77.0.2..254")
        if str(address) in ips or len(bytes.fromhex(digest)) != 32:
            raise ValueError("duplicate address or invalid hash")
        ips.add(str(address))
    return users


class Gateway:
    def __init__(self, tun, users, dns="1.1.1.1", timeout=90, activity=None):
        ipaddress.IPv4Address(dns)
        self.tun, self.users, self.dns, self.timeout = tun, users, dns, timeout
        self.peers = {}
        self.peer_writers = {}
        self.connections = 0
        self.activity = activity if activity is not None else ActivityCollector()
        self.ip_users = {record["ip"]: name for name, record in users.items()}

    def replace_users(self, users):
        previous = self.users
        self.users = users
        self.ip_users = {record["ip"]: name for name, record in users.items()}
        for name, old in previous.items():
            if users.get(name) != old:
                writer = self.peer_writers.get(old['ip'])
                if writer is not None:
                    writer.close()

    async def tun_loop(self):
        while True:
            packet = await self.tun.read()
            try:
                _, dst = ipv4(packet)
            except ProtocolError:
                continue
            queue = self.peers.get(dst)
            if queue is not None and not queue.full():
                user = self.ip_users.get(dst)
                if user:
                    self.activity.observe_downstream(user, packet)
                # Data plane packets are not padded in performance mode.
                queue.put_nowait(encode(Kind.PACKET, packet, padded=False))

    async def handle(self, reader, writer):
        if self.connections >= 256:
            writer.close(); return
        self.connections += 1
        address, queue, tasks = None, None, []
        try:
            kind, payload = await read_frame(reader, min(10, self.timeout))
            if kind != Kind.AUTH:
                raise ProtocolError("AUTH required")
            auth = json_object(payload)
            user, token = auth.get("user"), auth.get("token")
            if not isinstance(user, str) or not isinstance(token, str) or not 32 <= len(token) <= 256:
                raise ProtocolError("authentication failed")
            record = self.users.get(user)
            digest = hashlib.sha256(token.encode()).hexdigest()
            expected = record["token_sha256"] if record else "0" * 64
            if not hmac.compare_digest(expected, digest) or record is None:
                raise ProtocolError("authentication failed")
            address = record["ip"]

            # Handover: a fresh authenticated connection replaces a stale Wi-Fi/LTE session.
            old_writer = self.peer_writers.get(address)
            if old_writer is not None and old_writer is not writer:
                old_writer.close()
                await asyncio.sleep(0)

            queue = asyncio.Queue(maxsize=512)
            self.peers[address] = queue
            self.peer_writers[address] = writer
            writer.write(encode(Kind.CONFIG, json_bytes({
                "ip": address, "prefix": 24, "mtu": MTU, "dns": self.dns,
                "features": ["batch-v1", "packet-no-padding-v1", "handover-v1"]
            }), padded=False))
            await asyncio.wait_for(writer.drain(), 10)

            async def transmit():
                while True:
                    # Coalesce several queued frames before a drain() to improve throughput.
                    first = await queue.get()
                    writer.write(first)
                    for _ in range(31):
                        try: writer.write(queue.get_nowait())
                        except asyncio.QueueEmpty: break
                    await asyncio.wait_for(writer.drain(), 10)

            async def receive():
                while True:
                    kind, payload = await read_frame(reader, self.timeout)
                    if kind == Kind.PACKET:
                        _, dst = ipv4(payload, source=address)
                        destination = ipaddress.IPv4Address(dst)
                        # Internet exit only. Invalid/local packets are dropped, not used to kill the session.
                        if not destination.is_global or destination.is_multicast:
                            continue
                        self.activity.observe_upstream(user, payload)
                        self.tun.write(payload)
                    elif kind == Kind.PING and len(payload) == 8:
                        await asyncio.wait_for(queue.put(encode(Kind.PONG, payload, padded=False)), 10)
                    elif kind == Kind.CLOSE and not payload:
                        return
                    else:
                        raise ProtocolError("unexpected frame")

            tasks = [asyncio.create_task(transmit()), asyncio.create_task(receive())]
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
        except (ProtocolError, ValueError, asyncio.IncompleteReadError, ConnectionError, TimeoutError, ssl.SSLError):
            LOG.debug("session closed")
        finally:
            for task in tasks: task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if address is not None and queue is not None and self.peers.get(address) is queue:
                del self.peers[address]
                if self.peer_writers.get(address) is writer:
                    self.peer_writers.pop(address, None)
            self.connections -= 1
            writer.close()
            try:
                await asyncio.wait_for(writer.wait_closed(), 2)
            except (TimeoutError, ConnectionError, ssl.SSLError):
                pass


def tls_context(cert, key):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.maximum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(cert, key)
    context.num_tickets = 0
    return context


if __name__ == "__main__":
    raise SystemExit("Raw TLS listener removed. Run python -m sever.webtransport behind Caddy TLS.")
