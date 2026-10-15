#!/bin/bash
# DION Mayak: server node with several DION channels (whitelist-bypass headless creator, MIT).
# Every channel is its own room and its own systemd service "mayak-dion@<n>"; the phone joins all
# rooms at once and spreads traffic over them. Only outbound connections to DION, no open ports,
# CSQTT, 3x-ui, the Mayak Admin test node (mayak-call) and the rest of the server are not touched.
#
# Usage (as root): dion-node.sh install <count> | status | newroom <n|all> | uninstall
# install expects in /tmp/mayak-dion: creator (binary), cookies.json (shared account) and/or
# cookies<n>.json (own account for channel n), optional room<n> (keep or set that channel's room).
set -u
DIR=/opt/mayak-dion
UNIT=/etc/systemd/system/mayak-dion@.service
STAGE=/tmp/mayak-dion
MAX=12

say() { echo "DION_$1=$2"; }

installed_channels() {
    ls -d "$DIR"/[0-9]* 2>/dev/null | sed 's#.*/##' | sort -n
}

# Waits until every listed channel is in its room (log line since $1), prints its link.
wait_links() {
    local since="$1"; shift
    local pending=" $* " n ok=0 bad=0
    for _ in $(seq 1 120); do
        for n in $pending; do
            if journalctl -u "mayak-dion@$n" --since "@$since" --no-pager 2>/dev/null \
                | grep -q -E '\[room\] (created|rejoined) room=' && [ -s "$DIR/$n/rooms.log" ]; then
                say "LINK$n" "$(tail -n 1 "$DIR/$n/rooms.log")"
                pending=${pending/ $n / }
                ok=$((ok + 1))
            elif journalctl -u "mayak-dion@$n" --since "@$since" --no-pager 2>/dev/null | grep -q 'FATAL'; then
                say "FAIL$n" "$(journalctl -u "mayak-dion@$n" --since "@$since" --no-pager 2>/dev/null | grep FATAL | tail -n 1 | sed 's/.*\[FATAL\] *//' | cut -c1-200)"
                pending=${pending/ $n / }
                bad=$((bad + 1))
            fi
        done
        [ -z "${pending// /}" ] && break
        sleep 1
    done
    for n in $pending; do
        say "FAIL$n" "не зашёл в комнату за 2 минуты"
        bad=$((bad + 1))
    done
    for n in "$@"; do
        journalctl -u "mayak-dion@$n" --since "@$since" -n 6 --no-pager 2>/dev/null | sed "s/^/LOG: [$n] /"
    done
    say STATUS "в комнатах: $ok, с ошибкой: $bad"
    [ "$ok" -gt 0 ]
}

case "${1:-}" in
install)
    COUNT="${2:-5}"
    if ! [[ "$COUNT" =~ ^[0-9]+$ ]] || [ "$COUNT" -lt 1 ] || [ "$COUNT" -gt "$MAX" ]; then say ERROR "число каналов 1–$MAX"; exit 1; fi
    [ -s "$STAGE/creator" ] || { say ERROR "нет файла узла"; exit 1; }
    for n in $(seq 1 "$COUNT"); do
        if [ ! -s "$STAGE/cookies$n.json" ] && [ ! -s "$STAGE/cookies.json" ] && [ ! -s "$DIR/$n/cookies.json" ]; then
            say ERROR "нет входа DION для канала $n"; exit 1
        fi
    done
    for n in $(installed_channels); do systemctl stop "mayak-dion@$n" 2>/dev/null || true; done
    mkdir -p "$DIR"
    chmod 700 "$DIR"
    install -m 0755 "$STAGE/creator" "$DIR/creator"
    # Channels beyond the new count go away.
    for n in $(installed_channels); do
        if [ "$n" -gt "$COUNT" ]; then
            systemctl disable "mayak-dion@$n" >/dev/null 2>&1 || true
            rm -rf "${DIR:?}/$n"
        fi
    done
    for n in $(seq 1 "$COUNT"); do
        mkdir -p "$DIR/$n"
        chmod 700 "$DIR/$n"
        if [ -s "$STAGE/cookies$n.json" ]; then
            install -m 0600 "$STAGE/cookies$n.json" "$DIR/$n/cookies.json"
        elif [ -s "$STAGE/cookies.json" ]; then
            install -m 0600 "$STAGE/cookies.json" "$DIR/$n/cookies.json"
        fi
        # A room given from the phone replaces the kept one; otherwise the old room stays,
        # so links on phones stay valid across updates.
        if [ -s "$STAGE/room$n" ]; then
            ID=$(tr -cd 'A-Za-z0-9_-' < "$STAGE/room$n")
            [ -n "$ID" ] && printf 'dion://%s\n' "$ID" > "$DIR/$n/rooms.log"
        fi
    done
    rm -rf "$STAGE"
    cat > "$DIR/run.sh" <<'EOF'
#!/bin/bash
# One channel. Reuses its room after restarts so the link on phones stays valid.
N="$1"
cd "/opt/mayak-dion/$N" || exit 1
# Channels sign in one after another, not all in the same second.
sleep $(( (N - 1) * 2 ))
ROOM=""
[ -s rooms.log ] && ROOM=$(tail -n 1 rooms.log)
# DION rewrites cookies.json itself when it refreshes or logs in again (email/password are inside).
exec ../creator --cookies cookies.json --name "Mayak $N" --resources moderate --write-file rooms.log ${ROOM:+--room "$ROOM"}
EOF
    chmod 0755 "$DIR/run.sh"
    cat > "$UNIT" <<EOF
[Unit]
Description=DION Mayak channel %i
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$DIR/run.sh %i
Restart=always
RestartSec=10
MemoryMax=120M
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    START=$(date +%s)
    for n in $(seq 1 "$COUNT"); do systemctl enable "mayak-dion@$n" >/dev/null 2>&1; systemctl restart "mayak-dion@$n"; done
    wait_links "$START" $(seq 1 "$COUNT")
    ;;
newroom)
    WHICH="${2:-all}"
    [ -d "$DIR" ] || { say ERROR "узел не установлен"; exit 1; }
    if [ "$WHICH" = all ]; then LIST=$(installed_channels); else LIST="$WHICH"; fi
    for n in $LIST; do
        [ -d "$DIR/$n" ] || { say ERROR "нет канала $n"; exit 1; }
        systemctl stop "mayak-dion@$n"
        rm -f "$DIR/$n/rooms.log"
    done
    START=$(date +%s)
    for n in $LIST; do systemctl start "mayak-dion@$n"; done
    # shellcheck disable=SC2086
    wait_links "$START" $LIST
    ;;
status)
    if [ ! -f "$UNIT" ]; then say STATUS "не установлен"; exit 0; fi
    up=0; total=0
    for n in $(installed_channels); do
        total=$((total + 1))
        if systemctl is-active --quiet "mayak-dion@$n"; then up=$((up + 1)); say "STATE$n" "работает"; else say "STATE$n" "остановлен"; fi
        [ -s "$DIR/$n/rooms.log" ] && say "LINK$n" "$(tail -n 1 "$DIR/$n/rooms.log")"
        journalctl -u "mayak-dion@$n" -n 3 --no-pager 2>/dev/null | sed "s/^/LOG: [$n] /"
    done
    say STATUS "работают $up из $total"
    ;;
uninstall)
    for n in $(installed_channels); do systemctl disable --now "mayak-dion@$n" >/dev/null 2>&1 || true; done
    rm -f "$UNIT"
    systemctl daemon-reload
    rm -rf "$DIR" "$STAGE" /usr/local/lib/mayak-dion
    say STATUS "удалён"
    ;;
*)
    say ERROR "неизвестная команда"
    exit 1
    ;;
esac
