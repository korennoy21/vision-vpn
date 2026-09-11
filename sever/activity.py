"""Privacy-preserving VISION site activity metadata collector.

Collects only destination metadata visible to the VPN node (DNS names, public IPs,
flow counters and byte counts). It does not decrypt HTTPS or inspect HTTP bodies.
"""
from collections import OrderedDict
import ipaddress
import struct
import time


MAX_DOMAIN = 253
MAX_PENDING = 4096
MAX_MAPPINGS_PER_USER = 2048


def _ipv4(packet):
    if not isinstance(packet, (bytes, bytearray)) or len(packet) < 20 or packet[0] >> 4 != 4:
        return None
    ihl = (packet[0] & 15) * 4
    total = int.from_bytes(packet[2:4], "big")
    if ihl < 20 or total < ihl or total > len(packet):
        return None
    proto = packet[9]
    src = str(ipaddress.IPv4Address(packet[12:16]))
    dst = str(ipaddress.IPv4Address(packet[16:20]))
    return ihl, total, proto, src, dst


def _transport(packet, meta):
    ihl, total, proto, _, _ = meta
    if proto == 17 and total >= ihl + 8:  # UDP
        src_port, dst_port, length = struct.unpack("!HHH", packet[ihl:ihl + 6])
        if length < 8 or ihl + length > total:
            return None
        return "udp", src_port, dst_port, ihl + 8, ihl + length, 0
    if proto == 6 and total >= ihl + 20:  # TCP
        src_port, dst_port = struct.unpack("!HH", packet[ihl:ihl + 4])
        data_offset = (packet[ihl + 12] >> 4) * 4
        if data_offset < 20 or ihl + data_offset > total:
            return None
        flags = packet[ihl + 13]
        return "tcp", src_port, dst_port, ihl + data_offset, total, flags
    return None


def _read_name(data, offset, depth=0):
    if depth > 12 or offset < 0 or offset >= len(data):
        raise ValueError("bad dns name")
    labels = []
    original_next = None
    while True:
        if offset >= len(data):
            raise ValueError("truncated dns name")
        length = data[offset]
        if length == 0:
            offset += 1
            if original_next is None:
                original_next = offset
            break
        if length & 0xC0 == 0xC0:
            if offset + 1 >= len(data):
                raise ValueError("truncated dns pointer")
            pointer = ((length & 0x3F) << 8) | data[offset + 1]
            if original_next is None:
                original_next = offset + 2
            pointed, _ = _read_name(data, pointer, depth + 1)
            if pointed:
                labels.append(pointed)
            break
        if length & 0xC0 or length > 63 or offset + 1 + length > len(data):
            raise ValueError("invalid dns label")
        raw = bytes(data[offset + 1:offset + 1 + length])
        try:
            label = raw.decode("ascii")
        except UnicodeDecodeError as exc:
            raise ValueError("non-ascii dns label") from exc
        labels.append(label)
        offset += 1 + length
        if sum(len(x) for x in labels) + len(labels) > MAX_DOMAIN:
            raise ValueError("dns name too long")
    name = ".".join(labels).lower().rstrip(".")
    if name and (len(name) > MAX_DOMAIN or any(ord(c) < 33 or ord(c) > 126 for c in name)):
        raise ValueError("invalid dns name")
    return name, original_next


def parse_dns(data):
    """Return (question_domain, A answers[(ip, ttl)]) or None."""
    if len(data) < 12:
        return None
    _, flags, qdcount, ancount, _, _ = struct.unpack("!HHHHHH", data[:12])
    if qdcount < 1 or qdcount > 8 or ancount > 64:
        return None
    offset = 12
    question = None
    try:
        for q in range(qdcount):
            name, offset = _read_name(data, offset)
            if offset + 4 > len(data):
                return None
            qtype, qclass = struct.unpack("!HH", data[offset:offset + 4])
            offset += 4
            if q == 0 and qclass == 1:
                question = name
        answers = []
        if flags & 0x8000:  # response
            for _ in range(ancount):
                _, offset = _read_name(data, offset)
                if offset + 10 > len(data):
                    break
                rtype, rclass, ttl, rdlength = struct.unpack("!HHIH", data[offset:offset + 10])
                offset += 10
                if offset + rdlength > len(data):
                    break
                rdata = data[offset:offset + rdlength]
                offset += rdlength
                if rclass == 1 and rtype == 1 and rdlength == 4:
                    answers.append((str(ipaddress.IPv4Address(rdata)), max(30, min(int(ttl), 86400))))
        return question, answers
    except (ValueError, struct.error, ipaddress.AddressValueError):
        return None


def _dns_payload(packet, transport):
    proto, src_port, dst_port, start, end, _ = transport
    if src_port != 53 and dst_port != 53:
        return None
    payload = bytes(packet[start:end])
    if proto == "tcp":
        if len(payload) < 2:
            return None
        length = int.from_bytes(payload[:2], "big")
        if length < 12 or length > len(payload) - 2:
            return None
        payload = payload[2:2 + length]
    return payload


class ActivityCollector:
    def __init__(self, enabled=True, log_ips=True):
        self.enabled = bool(enabled)
        self.log_ips = bool(log_ips)
        self.pending = OrderedDict()
        self.ip_domains = {}
        self.udp_flows = {}

    def configure(self, enabled=None, log_ips=None):
        if enabled is not None:
            self.enabled = bool(enabled)
        if log_ips is not None:
            self.log_ips = bool(log_ips)
        if not self.enabled:
            self.pending.clear()
            self.ip_domains.clear()
            self.udp_flows.clear()

    def _cleanup(self, now):
        for user, mappings in list(self.ip_domains.items()):
            expired = [ip for ip, (_, expiry) in mappings.items() if expiry <= now]
            for ip in expired:
                mappings.pop(ip, None)
            if not mappings:
                self.ip_domains.pop(user, None)
        for key, seen in list(self.udp_flows.items()):
            if now - seen > 120:
                self.udp_flows.pop(key, None)

    def _domain_for_ip(self, user, ip, now):
        mapping = self.ip_domains.get(user, {}).get(ip)
        if not mapping:
            return ""
        domain, expiry = mapping
        if expiry <= now:
            self.ip_domains.get(user, {}).pop(ip, None)
            return ""
        return domain

    def _add(self, user, domain="", dest_ip="", *, queries=0, connections=0, tx=0, rx=0,
             protocol="", now=None):
        if not self.enabled or not isinstance(user, str) or not user:
            return
        now = time.time() if now is None else float(now)
        domain = (domain or "").lower().rstrip(".")[:MAX_DOMAIN]
        dest_ip = dest_ip or ""
        if dest_ip:
            try:
                address = ipaddress.IPv4Address(dest_ip)
                if not address.is_global:
                    dest_ip = ""
            except ipaddress.AddressValueError:
                dest_ip = ""
        if not domain and not dest_ip:
            return
        if not domain and not self.log_ips:
            return
        key = (user, domain, dest_ip)
        row = self.pending.get(key)
        if row is None:
            if len(self.pending) >= MAX_PENDING:
                self.pending.popitem(last=False)
            row = {
                "user": user, "domain": domain, "dest_ip": dest_ip,
                "first_seen": now, "last_seen": now, "queries": 0,
                "connections": 0, "tx": 0, "rx": 0, "protocol": protocol or "",
            }
            self.pending[key] = row
        else:
            self.pending.move_to_end(key)
            row["last_seen"] = max(row["last_seen"], now)
            if protocol and row["protocol"] and protocol != row["protocol"]:
                row["protocol"] = "mixed"
            elif protocol:
                row["protocol"] = protocol
        row["queries"] += max(0, int(queries))
        row["connections"] += max(0, int(connections))
        row["tx"] += max(0, int(tx))
        row["rx"] += max(0, int(rx))

    def _remember_answers(self, user, domain, answers, now):
        if not domain:
            return
        mappings = self.ip_domains.setdefault(user, {})
        for ip, ttl in answers:
            if len(mappings) >= MAX_MAPPINGS_PER_USER and ip not in mappings:
                # Drop the soonest-expiring mapping first.
                oldest = min(mappings.items(), key=lambda item: item[1][1])[0]
                mappings.pop(oldest, None)
            mappings[ip] = (domain, now + ttl)

    def observe_upstream(self, user, packet, now=None):
        if not self.enabled:
            return
        now = time.time() if now is None else float(now)
        meta = _ipv4(packet)
        if not meta:
            return
        _, total, _, _, dst = meta
        transport = _transport(packet, meta)
        if not transport:
            return
        proto, src_port, dst_port, _, _, flags = transport
        dns = _dns_payload(packet, transport)
        if dns is not None and dst_port == 53:
            parsed = parse_dns(dns)
            if parsed and parsed[0]:
                self._add(user, parsed[0], "", queries=1, protocol="dns", now=now)
            return
        domain = self._domain_for_ip(user, dst, now)
        connection = 0
        if proto == "tcp":
            # SYN without ACK is a new outbound connection.
            connection = 1 if (flags & 0x02 and not flags & 0x10) else 0
        elif proto == "udp" and dst_port in (80, 443, 853):
            key = (user, dst, dst_port)
            last = self.udp_flows.get(key)
            if last is None or now - last > 60:
                connection = 1
            self.udp_flows[key] = now
        # Keep activity volume focused on web-like flows or already-resolved domains.
        if domain or dst_port in (80, 443, 853):
            self._add(user, domain, dst, connections=connection, tx=total, protocol=proto, now=now)
        if int(now) % 31 == 0:
            self._cleanup(now)

    def observe_downstream(self, user, packet, now=None):
        if not self.enabled:
            return
        now = time.time() if now is None else float(now)
        meta = _ipv4(packet)
        if not meta:
            return
        _, total, _, src, _ = meta
        transport = _transport(packet, meta)
        if not transport:
            return
        proto, src_port, _, _, _, _ = transport
        dns = _dns_payload(packet, transport)
        if dns is not None and src_port == 53:
            parsed = parse_dns(dns)
            if parsed:
                domain, answers = parsed
                self._remember_answers(user, domain, answers, now)
            return
        domain = self._domain_for_ip(user, src, now)
        if domain:
            self._add(user, domain, src, rx=total, protocol=proto, now=now)

    def drain(self, limit=500):
        limit = max(1, min(int(limit), 1000))
        rows = []
        for key in list(self.pending.keys())[:limit]:
            rows.append(self.pending.pop(key))
        return rows

    def restore(self, rows):
        for item in rows or []:
            self._add(
                item.get("user", ""), item.get("domain", ""), item.get("dest_ip", ""),
                queries=item.get("queries", 0), connections=item.get("connections", 0),
                tx=item.get("tx", 0), rx=item.get("rx", 0), protocol=item.get("protocol", ""),
                now=item.get("last_seen", time.time()),
            )
