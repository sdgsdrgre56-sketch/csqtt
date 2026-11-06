#!/bin/sh
# Бухта: подготовка сервера. Ничего не меняет в 3x-ui: отдельная подсеть 10.77.0.0/24.
set -e
ip tuntap add dev buhta0 mode tun 2>/dev/null || true
ip addr replace 10.77.0.1/24 dev buhta0
ip link set buhta0 up mtu 1200
sysctl -w net.ipv4.ip_forward=1
iptables -t nat -C POSTROUTING -s 10.77.0.0/24 ! -o buhta0 -j MASQUERADE 2>/dev/null || \
  iptables -t nat -A POSTROUTING -s 10.77.0.0/24 ! -o buhta0 -j MASQUERADE
iptables -C FORWARD -i buhta0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -i buhta0 -j ACCEPT
iptables -C FORWARD -o buhta0 -j ACCEPT 2>/dev/null || iptables -I FORWARD -o buhta0 -j ACCEPT
echo "buhta0 готов"
