#!/bin/bash
# Установка сервера «Пульсар» одной командой (Debian/Ubuntu, root):
#   curl -fsSL https://raw.githubusercontent.com/sdgsdrgre56-sketch/csqtt/pulsar/server/install.sh | bash -s -- 'KEY:SECRET@hb.ru-msk.vkcloud-storage.ru/бакет/папка'
# Повторный запуск обновляет программу; ключ шифрования и ссылка сохраняются.
# Удаление: bash install.sh --remove
set -euo pipefail

VERSION="1.0.0"
REPO="sdgsdrgre56-sketch/csqtt"
BIN=/usr/local/bin/pulsar
CONF=/etc/pulsar

say() { printf '\033[1;36m%s\033[0m\n' "$*"; }
die() { printf '\033[1;31mОШИБКА: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "запустите от root (sudo -i)"

if [ "${1:-}" = "--remove" ]; then
	systemctl disable --now pulsar 2>/dev/null || true
	rm -f /etc/systemd/system/pulsar.service "$BIN"
	systemctl daemon-reload
	say "Пульсар удалён. Ключ и настройки оставлены в $CONF (удалите вручную, если не нужны)."
	exit 0
fi

BOX="${1:-}"
if [ -z "$BOX" ] && [ -f "$CONF/env" ]; then
	BOX=$(sed -n 's/^PULSAR_BOX=//p' "$CONF/env")
fi
[ -n "$BOX" ] || die "укажите облако: KEY:SECRET@hb.ru-msk.vkcloud-storage.ru/бакет/папка"
BOX=$(printf '%s' "$BOX" | tr -d ' \r\n' | sed -E 's#^(https?|s3)://##I')
printf '%s' "$BOX" | grep -Eq '^[^:@]+:[^@]+@[A-Za-z0-9.:-]+/[A-Za-z0-9._-]+(/[A-Za-z0-9._/-]+)?$' \
	|| die "строка облака должна быть вида KEY:SECRET@хост/бакет/папка"

case "$(uname -m)" in
	x86_64 | amd64) ARCH=amd64 ;;
	aarch64 | arm64) ARCH=arm64 ;;
	*) die "процессор $(uname -m) не поддерживается" ;;
esac

command -v curl >/dev/null || { apt-get update -qq && apt-get install -y -qq curl; }

say "Скачиваю Пульсар $VERSION ($ARCH)…"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
URL="https://github.com/$REPO/releases/download/pulsar-v$VERSION"
curl -fsSL -o "$TMP/pulsar" "$URL/pulsar-linux-$ARCH" || die "не скачалось с GitHub"
curl -fsSL -o "$TMP/SHA256SUMS" "$URL/SHA256SUMS" || die "не скачалась контрольная сумма"
WANT=$(awk -v f="pulsar-linux-$ARCH" '$2==f {print $1}' "$TMP/SHA256SUMS")
GOT=$(sha256sum "$TMP/pulsar" | cut -d' ' -f1)
[ -n "$WANT" ] && [ "$WANT" = "$GOT" ] || die "контрольная сумма не совпала"
install -m 0755 "$TMP/pulsar" "$BIN"

mkdir -p "$CONF"
chmod 700 "$CONF"
umask 077
printf 'PULSAR_BOX=%s\n' "$BOX" > "$CONF/env"

say "Проверяю облако…"
"$BIN" check --box "$BOX" --key-file "$CONF/key" || die "облако не принимает ключи — проверьте строку облака"
LINK=$("$BIN" link --box "$BOX" --key-file "$CONF/key" --name "Пульсар $(hostname -I 2>/dev/null | awk '{print $1}')") || die "не удалось создать ссылку"

cat > /etc/systemd/system/pulsar.service <<'UNIT'
[Unit]
Description=Pulsar tunnel server (VK Cloud box)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=/etc/pulsar/env
ExecStart=/usr/local/bin/pulsar server --key-file /etc/pulsar/key
Restart=always
RestartSec=3
LimitNOFILE=1048576
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadOnlyPaths=/etc/pulsar
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

# больше буферов TCP — быстрее скачивание с сайтов
cat > /etc/sysctl.d/90-pulsar.conf <<'SYS'
net.core.rmem_max = 33554432
net.core.wmem_max = 33554432
net.ipv4.tcp_rmem = 4096 131072 33554432
net.ipv4.tcp_wmem = 4096 65536 33554432
SYS
sysctl -q -p /etc/sysctl.d/90-pulsar.conf 2>/dev/null || true
if modprobe tcp_bbr 2>/dev/null; then
	printf 'net.core.default_qdisc = fq\nnet.ipv4.tcp_congestion_control = bbr\n' > /etc/sysctl.d/91-pulsar-bbr.conf
	sysctl -q -p /etc/sysctl.d/91-pulsar-bbr.conf 2>/dev/null || true
fi

systemctl daemon-reload
systemctl enable -q pulsar
systemctl restart pulsar
sleep 4
if ! systemctl is-active -q pulsar; then
	journalctl -u pulsar -n 20 --no-pager
	die "сервер не запустился — журнал выше"
fi

echo
say "Готово! Пульсар работает."
echo
echo "Ссылка подключения (вставьте в приложение «Пульсар» и в Маяк Браузер):"
echo
printf '\033[1;32m%s\033[0m\n' "$LINK"
echo
if command -v qrencode >/dev/null || apt-get install -y -qq qrencode >/dev/null 2>&1; then
	echo "QR-код для телефона:"
	qrencode -t ansiutf8 -m 1 "$LINK" || true
fi
echo "Ссылку можно показать снова: pulsar link --box \"\$(sed -n 's/^PULSAR_BOX=//p' $CONF/env)\" --key-file $CONF/key"
echo "Журнал: journalctl -u pulsar -f"
