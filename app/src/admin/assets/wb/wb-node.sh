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
    # Success = the node has joined the room ("[auth] room=..." in its log since this start).
    local since="$1"
    for _ in $(seq 1 45); do
        if journalctl -u mayak-wb --since "@$since" --no-pager 2>/dev/null | grep -q '\[auth\] room='; then
            if [ -s "$DIR/rooms.log" ]; then
                say LINK "$(tail -n 1 "$DIR/rooms.log")"
                return 0
            fi
        fi
        sleep 1
    done
    say ERROR "узел не смог зайти в комнату"
    journalctl -u mayak-wb --since "@$since" -n 25 --no-pager 2>/dev/null | sed 's/^/LOG: /'
    return 1
}

case "${1:-}" in
install)
    [ -s "$STAGE/wbstream" ] || { say ERROR "нет файла узла"; exit 1; }
    [ -s "$STAGE/cookies.json" ] || [ -s "$STAGE/room" ] || { say ERROR "нет ни токена WB, ни ссылки на комнату"; exit 1; }
    systemctl stop mayak-wb 2>/dev/null || true
    mkdir -p "$DIR"
    install -m 0755 "$STAGE/wbstream" "$DIR/wbstream"
    if [ -s "$STAGE/cookies.json" ]; then
        install -m 0600 "$STAGE/cookies.json" "$DIR/cookies.json"
    fi
    if [ -s "$STAGE/room" ]; then
        # An existing room (made by someone allowed to create rooms): the node joins it as a guest.
        printf 'wbstream://%s\n' "$(tr -cd 'A-Za-z0-9_-' < "$STAGE/room")" > "$DIR/rooms.log"
        touch "$DIR/guest"
    else
        rm -f "$DIR/guest"
    fi
    rm -rf "$STAGE"
    cat > "$DIR/run.sh" <<'EOF'
#!/bin/bash
# Reuse the same room after restarts, so the link on phones stays valid.
cd /opt/mayak-wb || exit 1
ROOM=""
[ -s rooms.log ] && ROOM=$(tail -n 1 rooms.log)
if [ -f guest ] && [ -n "$ROOM" ]; then
    # Fresh guest token on every start, so it never goes stale. WB lets guests join rooms, not create them.
    UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
    TOKEN=$(curl -fsS -m 20 -A "$UA" -H 'Content-Type: application/json' -H 'Origin: https://stream.wb.ru' \
        -X POST https://stream.wb.ru/auth/api/v1/auth/user/guest-register \
        -d '{"displayName":"Mayak","device":{"deviceName":"Linux","deviceType":"PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP"}}' \
        | grep -o '"accessToken":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    if [ -n "$TOKEN" ]; then
        printf '[{"name":"wb_access_token","value":"%s"}]' "$TOKEN" > cookies.json
        chmod 600 cookies.json
    else
        echo "guest-register failed, using the previous token" >&2
    fi
fi
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
    START=$(date +%s)
    systemctl enable --now mayak-wb >/dev/null 2>&1
    wait_link "$START"
    ;;
newroom)
    [ -d "$DIR" ] || { say ERROR "узел не установлен"; exit 1; }
    if [ -f "$DIR/guest" ]; then say ERROR "узел сидит в чужой комнате; новую комнату гость создать не может"; exit 1; fi
    systemctl stop mayak-wb
    rm -f "$DIR/rooms.log"
    START=$(date +%s)
    systemctl start mayak-wb
    wait_link "$START"
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
