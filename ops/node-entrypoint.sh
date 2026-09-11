#!/bin/sh
set -eu
# Intended for a dedicated Linux test VPS. Add only rules owned by this project.
test -c /dev/net/tun
if [ "$(cat /proc/sys/net/ipv4/ip_forward)" != 1 ]; then
    echo 'Enable net.ipv4.ip_forward=1 on the host before starting the node.' >&2
    exit 1
fi
sever_uplink=$(ip -4 route show default | awk '/default/ {print $5; exit}')
if [ -z "$sever_uplink" ]; then echo 'No IPv4 default route found' >&2; exit 1; fi
iptables -C FORWARD -i sever0 -o "$sever_uplink" -s 10.77.0.0/24 -m comment --comment sever-suite -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i sever0 -o "$sever_uplink" -s 10.77.0.0/24 -m comment --comment sever-suite -j ACCEPT
iptables -C FORWARD -i "$sever_uplink" -o sever0 -d 10.77.0.0/24 -m conntrack --ctstate RELATED,ESTABLISHED -m comment --comment sever-suite -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i "$sever_uplink" -o sever0 -d 10.77.0.0/24 -m conntrack --ctstate RELATED,ESTABLISHED -m comment --comment sever-suite -j ACCEPT
iptables -t nat -C POSTROUTING -s 10.77.0.0/24 -o "$sever_uplink" -m comment --comment sever-suite -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.77.0.0/24 -o "$sever_uplink" -m comment --comment sever-suite -j MASQUERADE
exec python -m sever.managed
