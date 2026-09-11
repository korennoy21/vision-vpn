import asyncio
import fcntl
import os
import re
import struct
import subprocess


class LinuxTun:
    def __init__(self, name, address):
        if not re.fullmatch(r"[a-zA-Z0-9_-]{1,15}", name):
            raise ValueError("invalid TUN name")
        self.fd = os.open("/dev/net/tun", os.O_RDWR | os.O_NONBLOCK)
        try:
            fcntl.ioctl(self.fd, 0x400454CA, struct.pack("16sH", name.encode(), 0x0001 | 0x1000))
            subprocess.run(["ip", "address", "add", address, "dev", name], check=True)
            subprocess.run(["ip", "link", "set", "dev", name, "mtu", "1280", "up"], check=True)
        except BaseException:
            os.close(self.fd)
            raise

    async def read(self):
        loop = asyncio.get_running_loop()
        ready = loop.create_future()
        def wake():
            if not ready.done():
                ready.set_result(None)
        loop.add_reader(self.fd, wake)
        try:
            await ready
            return os.read(self.fd, 65535)
        finally:
            loop.remove_reader(self.fd)

    def write(self, packet):
        try:
            os.write(self.fd, packet)
        except BlockingIOError:
            pass  # Bounded memory: congestion drops packets; transports may retransmit.

    def close(self):
        os.close(self.fd)
