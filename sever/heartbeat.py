import secrets
import time


class Heartbeat:
    """Idle probe with jitter and a hard, jittered liveness bound during uploads."""
    def __init__(self, clock=time.monotonic, random=secrets.randbelow):
        self.clock, self.random = clock, random
        self.ping_sent()

    def packet_sent(self):
        self.idle_deadline = self.clock() + self.idle_interval

    def ping_sent(self):
        now = self.clock()
        self.idle_interval = 20 + self.random(15001) / 1000
        self.idle_deadline = now + self.idle_interval
        self.probe_deadline = now + 50 + self.random(15001) / 1000

    def due(self):
        return self.clock() >= min(self.idle_deadline, self.probe_deadline)
