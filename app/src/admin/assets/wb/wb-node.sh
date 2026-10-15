#!/bin/bash
# Mayak Admin: call-tunnel test node (whitelist-bypass headless creator, MIT) for DION or WB Stream.
# Runs as its own systemd service "mayak-call". It only makes outbound connections to the call service,
# opens no ports and does not touch CSQTT, 3x-ui or anything else on the server.
#
# Usage (as root): wb-node.sh install dion|wb | newroom | status | uninstall
# install expects in /tmp/mayak-wb: creator (binary), and cookies.json and/or room.
set -u
DIR=/opt/mayak-call
UNIT=/etc/systemd/system/mayak-call.service
STAGE=/tmp/mayak-wb

say() { echo "WB_$1=$2"; }

remove_legacy() {
    # First test version used the name mayak-wb.
    if [ -f /etc/systemd/system/mayak-wb.service ]; then
        systemctl disable --now mayak-wb >/dev/null 2>&1 || true
        rm -f /etc/systemd/system/mayak-wb.service
        systemctl daemon-reload
    fi
    rm -rf /opt/mayak-wb
}

wait_link() {
    # Success = the node is in its room (log line from the creator since this start).
    local since="$1"
    for _ in $(seq 1 60); do
        if journalctl -u mayak-call --since "@$since" --no-pager 2>/dev/null \
            | grep -q -E '\[auth\] room=|\[room\] (created|rejoined) room='; then
            if [ -s "$DIR/rooms.log" ]; then
                say LINK "$(tail -n 1 "$DIR/rooms.log")"
                return 0
            fi
        fi
        sleep 1
    done
    say ERROR "узел не смог зайти в комнату"
    journalctl -u mayak-call --since "@$since" -n 25 --no-pager 2>/dev/null | sed 's/^/LOG: /'
    return 1
}

case "${1:-}" in
install)
    PLATFORM="${2:-}"
    case "$PLATFORM" in dion|wb) ;; *) say ERROR "неизвестный сервис"; exit 1 ;; esac
    [ -s "$STAGE/creator" ] || { say ERROR "нет файла узла"; exit 1; }
    [ -s "$STAGE/cookies.json" ] || [ -s "$STAGE/room" ] || { say ERROR "нет ни входа, ни ссылки на комнату"; exit 1; }
    remove_legacy
    systemctl stop mayak-call 2>/dev/null || true
    # Keep the room across updates of the same service, so links on phones stay valid.
    KEEP=""
    if [ "$(cat "$DIR/platform" 2>/dev/null)" = "$PLATFORM" ] && [ -s "$DIR/rooms.log" ]; then
        KEEP=$(tail -n 1 "$DIR/rooms.log")
    fi
    rm -rf "$DIR"
    mkdir -p "$DIR"
    [ -n "$KEEP" ] && echo "$KEEP" > "$DIR/rooms.log"
    chmod 700 "$DIR"
    echo "$PLATFORM" > "$DIR/platform"
    install -m 0755 "$STAGE/creator" "$DIR/creator"
    if [ -s "$STAGE/cookies.json" ]; then install -m 0600 "$STAGE/cookies.json" "$DIR/cookies.json"; fi
    if [ -s "$STAGE/room" ]; then
        ID=$(tr -cd 'A-Za-z0-9_-' < "$STAGE/room")
        if [ "$PLATFORM" = wb ]; then
            printf 'wbstream://%s\n' "$ID" > "$DIR/rooms.log"
            [ -s "$DIR/cookies.json" ] || touch "$DIR/guest"
        else
            printf 'dion://%s\n' "$ID" > "$DIR/rooms.log"
        fi
    fi
    rm -rf "$STAGE"
    cat > "$DIR/run.sh" <<'EOF'
#!/bin/bash
# Reuse the same room after restarts, so the link on phones stays valid.
cd /opt/mayak-call || exit 1
PLATFORM=$(cat platform)
ROOM=""
[ -s rooms.log ] && ROOM=$(tail -n 1 rooms.log)
if [ "$PLATFORM" = wb ] && [ -f guest ] && [ -n "$ROOM" ]; then
    # Fresh WB guest token on every start. WB lets guests join rooms, not create them.
    UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36'
    TOKEN=$(curl -fsS -m 20 -A "$UA" -H 'Content-Type: application/json' -H 'Origin: https://stream.wb.ru' \
        -X POST https://stream.wb.ru/auth/api/v1/auth/user/guest-register \
        -d '{"displayName":"Mayak","device":{"deviceName":"Linux","deviceType":"PARTICIPANT_DEVICE_TYPE_WEB_DESKTOP"}}' \
        | grep -o '"accessToken":"[^"]*"' | head -n 1 | cut -d'"' -f4)
    if [ -n "$TOKEN" ]; then
        printf '[{"name":"wb_access_token","value":"%s"}]' "$TOKEN" > cookies.json
        chmod 600 cookies.json
    fi
fi
# DION rewrites cookies.json itself when it refreshes or logs in again (email/password are inside).
exec ./creator --cookies cookies.json --name Mayak --resources moderate --write-file rooms.log ${ROOM:+--room "$ROOM"}
EOF
    chmod 0755 "$DIR/run.sh"
    cat > "$UNIT" <<EOF
[Unit]
Description=Mayak call-tunnel test node
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$DIR/run.sh
Restart=always
RestartSec=10
MemoryMax=160M
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    START=$(date +%s)
    systemctl enable --now mayak-call >/dev/null 2>&1
    wait_link "$START"
    ;;
newroom)
    [ -d "$DIR" ] || { say ERROR "узел не установлен"; exit 1; }
    if [ -f "$DIR/guest" ]; then say ERROR "узел сидит гостем в чужой комнате, новую создать не может"; exit 1; fi
    systemctl stop mayak-call
    rm -f "$DIR/rooms.log"
    START=$(date +%s)
    systemctl start mayak-call
    wait_link "$START"
    ;;
status)
    if [ ! -f "$UNIT" ]; then say STATUS "не установлен"; exit 0; fi
    P=$(cat "$DIR/platform" 2>/dev/null)
    if systemctl is-active --quiet mayak-call; then say STATUS "работает ($P)"; else say STATUS "остановлен ($P)"; fi
    [ -s "$DIR/rooms.log" ] && say LINK "$(tail -n 1 "$DIR/rooms.log")"
    journalctl -u mayak-call -n 15 --no-pager 2>/dev/null | sed 's/^/LOG: /'
    ;;
uninstall)
    systemctl disable --now mayak-call >/dev/null 2>&1 || true
    rm -f "$UNIT"
    systemctl daemon-reload
    rm -rf "$DIR" "$STAGE"
    remove_legacy
    rm -rf /usr/local/lib/mayak-call
    say STATUS "удалён"
    ;;
*)
    say ERROR "неизвестная команда"
    exit 1
    ;;
esac
