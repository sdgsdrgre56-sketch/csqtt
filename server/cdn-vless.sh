#!/bin/bash
# VLESS через CDN (VK Cloud CDN и любой другой HTTP-CDN), Debian/Ubuntu, root:
#   curl -fsSL https://raw.githubusercontent.com/sdgsdrgre56-sketch/csqtt/pulsar/server/cdn-vless.sh | bash -s -- ваш.домен
# Ставит Xray: VLESS + xHTTP на порту 80 (без TLS — TLS делает CDN). Транспорт xHTTP
# в режиме packet-up ходит обычными HTTP-запросами, поэтому проходит через любой CDN,
# даже без поддержки WebSocket. Повторный запуск сохраняет UUID и путь.
# Удаление: bash cdn-vless.sh --remove
set -euo pipefail

say() { printf '\033[1;36m%s\033[0m\n' "$*"; }
die() { printf '\033[1;31mОШИБКА: %s\033[0m\n' "$*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || die "запустите от root"

CONF=/etc/cdn-vless
if [ "${1:-}" = "--remove" ]; then
	systemctl disable --now xray 2>/dev/null || true
	bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ remove || true
	say "Xray удалён. Настройки оставлены в $CONF."
	exit 0
fi

DOMAIN="${1:-}"
[ -z "$DOMAIN" ] && [ -f "$CONF/domain" ] && DOMAIN=$(cat "$CONF/domain")
[ -n "$DOMAIN" ] || die "укажите домен CDN: bash -s -- ваш.домен"

command -v curl >/dev/null || { apt-get update -qq && apt-get install -y -qq curl; }
command -v qrencode >/dev/null || { apt-get update -qq && apt-get install -y -qq qrencode >/dev/null 2>&1 || true; }

say "Ставлю Xray…"
bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install >/dev/null \
	|| die "Xray не установился (GitHub недоступен?)"

mkdir -p "$CONF"; chmod 700 "$CONF"
echo "$DOMAIN" > "$CONF/domain"
[ -s "$CONF/uuid" ] || /usr/local/bin/xray uuid > "$CONF/uuid"
[ -s "$CONF/path" ] || echo "/$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')" > "$CONF/path"
UUID=$(cat "$CONF/uuid"); XPATH=$(cat "$CONF/path")

cat > /usr/local/etc/xray/config.json <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [{
    "listen": "0.0.0.0", "port": 80, "protocol": "vless",
    "settings": { "clients": [{ "id": "$UUID" }], "decryption": "none" },
    "streamSettings": { "network": "xhttp", "xhttpSettings": { "path": "$XPATH", "mode": "auto" } },
    "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"] }
  }],
  "outbounds": [{ "protocol": "freedom" }, { "protocol": "blackhole", "tag": "block" }]
}
EOF

/usr/local/bin/xray run -test -config /usr/local/etc/xray/config.json >/dev/null || die "конфиг Xray не прошёл проверку"
cat > /etc/sysctl.d/90-cdn-vless.conf <<'EOF'
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
EOF
sysctl -p /etc/sysctl.d/90-cdn-vless.conf >/dev/null 2>&1 || true
command -v ufw >/dev/null && ufw allow 80/tcp >/dev/null 2>&1 || true
systemctl enable xray >/dev/null 2>&1; systemctl restart xray
sleep 1
systemctl is-active --quiet xray || die "Xray не запустился: journalctl -u xray -n 30"

# проверка изнутри: на свой путь Xray должен ответить, на чужой — нет
CODE=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1$XPATH/" || true)

EP=$(printf '%s' "$XPATH" | sed 's#/#%2F#g')
LINK="vless://$UUID@$DOMAIN:443?encryption=none&security=tls&sni=$DOMAIN&host=$DOMAIN&fp=chrome&alpn=h2%2Chttp%2F1.1&type=xhttp&path=$EP&mode=packet-up#CDN-VK"
echo
say "Готово. Xray слушает порт 80, путь $XPATH (проверка: $CODE)."
say "Источник (origin) для CDN: http://$(hostname -I | awk '{print $1}'):80, кэширование ВЫКЛЮЧИТЬ."
echo
say "Ссылка VLESS (заработает, когда CDN выдаст сертификат на $DOMAIN):"
echo "$LINK"
command -v qrencode >/dev/null && qrencode -t ANSIUTF8 "$LINK" || true
