#!/bin/bash
# Mayak Admin: WB Stream test node (whitelist-bypass headless creator, MIT).
# Runs as its own systemd service "mayak-wb". It only makes outbound connections to WB Stream,
# opens no ports and does not touch CSQTT, 3x-ui or anything else on the server.
#
# Usage (as root): wb-node.sh install|newroom|status|uninstall
# install expects /tmp/mayak-wb/wbstream and /tmp/mayak-wb/cookies.json uploaded beforehand.
set -u
DIR=/opt/mayak-wb
UNIT=/etc/systemd/system/mayak-wb.service
STAGE=/tmp/mayak-wb

say() { echo "WB_$1=$2"; }

wait_link() {
    for _ in $(seq 1 45); do
        if [ -s "$DIR/rooms.log" ]; then
            say LINK "$(tail -n 1 "$DIR/rooms.log")"
            return 0
        fi
        if ! systemctl is-active --quiet mayak-wb; then
            break
        fi
        sleep 1
    done
    say ERROR "узел не создал комнату"
    journalctl -u mayak-wb -n 25 --no-pager 2>/dev/null | sed 's/^/LOG: /'
    return 1
}

case "${1:-}" in
install)
    [ -s "$STAGE/wbstream" ] || { say ERROR "нет файла узла"; exit 1; }
    [ -s "$STAGE/cookies.json" ] || { say ERROR "нет токена WB"; exit 1; }
    systemctl stop mayak-wb 2>/dev/null || true
    mkdir -p "$DIR"
    install -m 0755 "$STAGE/wbstream" "$DIR/wbstream"
    install -m 0600 "$STAGE/cookies.json" "$DIR/cookies.json"
    rm -rf "$STAGE"
    cat > "$DIR/run.sh" <<'EOF'
#!/bin/bash
# Reuse the same room after restarts, so the link on phones stays valid.
cd /opt/mayak-wb || exit 1
ROOM=""
[ -s rooms.log ] && ROOM=$(tail -n 1 rooms.log)
exec ./wbstream --cookies cookies.json --name Mayak --resources moderate --write-file rooms.log ${ROOM:+--room "$ROOM"}
EOF
    chmod 0755 "$DIR/run.sh"
    cat > "$UNIT" <<EOF
[Unit]
Description=Mayak WB Stream test node
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$DIR/run.sh
Restart=always
RestartSec=5
MemoryMax=160M
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable --now mayak-wb >/dev/null 2>&1
    wait_link
    ;;
newroom)
    [ -d "$DIR" ] || { say ERROR "узел не установлен"; exit 1; }
    systemctl stop mayak-wb
    rm -f "$DIR/rooms.log"
    systemctl start mayak-wb
    wait_link
    ;;
status)
    if [ ! -f "$UNIT" ]; then say STATUS "не установлен"; exit 0; fi
    if systemctl is-active --quiet mayak-wb; then say STATUS "работает"; else say STATUS "остановлен"; fi
    [ -s "$DIR/rooms.log" ] && say LINK "$(tail -n 1 "$DIR/rooms.log")"
    journalctl -u mayak-wb -n 15 --no-pager 2>/dev/null | sed 's/^/LOG: /'
    ;;
uninstall)
    systemctl disable --now mayak-wb >/dev/null 2>&1 || true
    rm -f "$UNIT"
    systemctl daemon-reload
    rm -rf "$DIR" "$STAGE"
    say STATUS "удалён"
    ;;
*)
    say ERROR "неизвестная команда"
    exit 1
    ;;
esac
