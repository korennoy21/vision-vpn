import ipaddress
import struct
import unittest

from sever.activity import ActivityCollector, parse_dns


def qname(name):
    return b''.join(bytes([len(label)]) + label.encode('ascii') for label in name.split('.')) + b'\0'


def dns_query(name='example.com'):
    return struct.pack('!HHHHHH', 0x1234, 0x0100, 1, 0, 0, 0) + qname(name) + struct.pack('!HH', 1, 1)


def dns_response(name='example.com', address='93.184.216.34'):
    question = qname(name) + struct.pack('!HH', 1, 1)
    answer = b'\xc0\x0c' + struct.pack('!HHIH', 1, 1, 60, 4) + ipaddress.IPv4Address(address).packed
    return struct.pack('!HHHHHH', 0x1234, 0x8180, 1, 1, 0, 0) + question + answer


def ipv4_udp(src, dst, src_port, dst_port, payload):
    udp = struct.pack('!HHHH', src_port, dst_port, 8 + len(payload), 0) + payload
    total = 20 + len(udp)
    ip = struct.pack('!BBHHHBBH4s4s', 0x45, 0, total, 0, 0, 64, 17, 0,
                     ipaddress.IPv4Address(src).packed, ipaddress.IPv4Address(dst).packed)
    return ip + udp


def ipv4_tcp(src, dst, src_port, dst_port, flags, payload=b''):
    offset_flags = (5 << 12) | flags
    tcp = struct.pack('!HHIIHHHH', src_port, dst_port, 0, 0, offset_flags, 65535, 0, 0) + payload
    total = 20 + len(tcp)
    ip = struct.pack('!BBHHHBBH4s4s', 0x45, 0, total, 0, 0, 64, 6, 0,
                     ipaddress.IPv4Address(src).packed, ipaddress.IPv4Address(dst).packed)
    return ip + tcp


class ActivityTests(unittest.TestCase):
    def test_dns_parser_and_flow_mapping(self):
        parsed = parse_dns(dns_response())
        self.assertEqual(parsed[0], 'example.com')
        self.assertEqual(parsed[1][0][0], '93.184.216.34')

        c = ActivityCollector(enabled=True, log_ips=True)
        c.observe_upstream('alice', ipv4_udp('10.77.0.2', '1.1.1.1', 53000, 53, dns_query()), now=1000)
        c.observe_downstream('alice', ipv4_udp('1.1.1.1', '10.77.0.2', 53, 53000, dns_response()), now=1000.1)
        c.observe_upstream('alice', ipv4_tcp('10.77.0.2', '93.184.216.34', 55000, 443, 0x02), now=1001)
        c.observe_downstream('alice', ipv4_tcp('93.184.216.34', '10.77.0.2', 443, 55000, 0x12), now=1001.1)
        rows = c.drain()
        query = next(r for r in rows if r['domain'] == 'example.com' and not r['dest_ip'])
        flow = next(r for r in rows if r['domain'] == 'example.com' and r['dest_ip'] == '93.184.216.34')
        self.assertEqual(query['queries'], 1)
        self.assertEqual(flow['connections'], 1)
        self.assertGreater(flow['tx'], 0)
        self.assertGreater(flow['rx'], 0)

    def test_unresolved_web_ip_and_disabled_mode(self):
        c = ActivityCollector(enabled=True, log_ips=True)
        c.observe_upstream('alice', ipv4_tcp('10.77.0.2', '8.8.8.8', 51000, 443, 0x02), now=2000)
        rows = c.drain()
        self.assertEqual(rows[0]['domain'], '')
        self.assertEqual(rows[0]['dest_ip'], '8.8.8.8')
        c.configure(enabled=False)
        c.observe_upstream('alice', ipv4_tcp('10.77.0.2', '8.8.8.8', 51001, 443, 0x02), now=2001)
        self.assertEqual(c.drain(), [])

    def test_restore_preserves_counters(self):
        c = ActivityCollector()
        c.observe_upstream('alice', ipv4_udp('10.77.0.2', '1.1.1.1', 53000, 53, dns_query()), now=3000)
        rows = c.drain()
        self.assertEqual(len(rows), 1)
        c.restore(rows)
        restored = c.drain()
        self.assertEqual(restored[0]['queries'], 1)
        self.assertEqual(restored[0]['domain'], 'example.com')


if __name__ == '__main__':
    unittest.main()
