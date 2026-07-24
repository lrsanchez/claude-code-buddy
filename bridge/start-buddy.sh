#!/usr/bin/env bash
# Claude Buddy — launcher
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load .env if present
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -o allexport
    # shellcheck disable=SC1090
    source "$SCRIPT_DIR/.env"
    set +o allexport
fi

HTTP_PORT="${BUDDY_HTTP_PORT:-7700}"

# Ensure Python dependencies are installed
for pkg in bleak aiohttp qrcode faster-whisper edge-tts; do
    if ! python3 -c "import ${pkg//-/_}" 2>/dev/null; then
        echo "Installing $pkg..."
        python3 -m pip install --quiet "$pkg"
    fi
done

# ── Pinggy tunnel (optional — only needed for access from OUTSIDE the LAN) ───
# On the local network the app/R1 reach the daemon directly at this machine's
# IP, so the tunnel is skipped unless you say yes (or set PINGGY_ENABLE=1/0 in
# .env to skip the question). Set PINGGY_TOKEN (Pinggy Pro) for a persistent
# URL; without it the free tier gives a random URL that expires after ~60 min.
START_PINGGY="${PINGGY_ENABLE:-}"
if [ -z "$START_PINGGY" ] && [ -t 0 ]; then
    printf "Start Pinggy tunnel for remote (outside-LAN) access? [y/N] (No in 15s) "
    REPLY=""
    if read -r -t 15 REPLY; then :; else echo; fi
    case "$REPLY" in
        y|Y|yes|YES) START_PINGGY=1 ;;
        *)           START_PINGGY=0 ;;
    esac
fi
[ -z "$START_PINGGY" ] && START_PINGGY=1   # non-interactive: keep old behavior

if [ "$START_PINGGY" != "1" ]; then
    # The daemon binds 0.0.0.0, so it's reachable on every local interface —
    # give the app both URLs (LAN as primary, Tailscale as fallback).
    LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
    TS_IP=$(tailscale ip -4 2>/dev/null | head -n1) || true
    echo "  Pinggy skipped — daemon reachable at:"
    echo "    LAN:       http://${LAN_IP:-<this-machine>}:$HTTP_PORT"
    if [ -n "$TS_IP" ]; then
        echo "    Tailscale: http://$TS_IP:$HTTP_PORT"
    fi
elif command -v ssh &>/dev/null; then
    echo "Starting Pinggy tunnel on port $HTTP_PORT…"
    PINGGY_LOG="$SCRIPT_DIR/.pinggy.log"
    if [ -n "$PINGGY_TOKEN" ]; then
        PINGGY_HOST="$PINGGY_TOKEN@${PINGGY_SERVER:-pro.pinggy.io}"
    else
        PINGGY_HOST="${PINGGY_SERVER:-a.pinggy.io}"
    fi

    # Dedicated passphrase-less key — Pinggy accepts any key; this keeps ssh
    # fully non-interactive (no passphrase/password prompt from personal keys)
    PINGGY_KEY="$SCRIPT_DIR/.pinggy_key"
    [ -f "$PINGGY_KEY" ] || ssh-keygen -q -t ed25519 -f "$PINGGY_KEY" -N "" -C "claude-buddy-pinggy"

    # Kill any stale supervisor/tunnel left over from a previous run
    PINGGY_PID_FILE="$SCRIPT_DIR/.pinggy.pid"
    if [ -f "$PINGGY_PID_FILE" ]; then
        kill -- -"$(cat "$PINGGY_PID_FILE")" 2>/dev/null || true
        rm -f "$PINGGY_PID_FILE"
    fi
    pkill -f "R0:localhost:$HTTP_PORT.*pinggy\.io" 2>/dev/null || true

    # Supervisor loop: a shaky connection drops the ssh tunnel — keepalives
    # (15 s × 2) detect the dead link fast, then we reconnect until killed.
    # setsid forks, so the supervisor writes its own PID (its process-group id).
    setsid bash -c '
        LOG="$1"; KEY="$2"; PORT="$3"; HOST="$4"; PID_FILE="$5"
        echo $$ > "$PID_FILE"
        while true; do
            : > "$LOG"
            ssh -p 443 \
                -i "$KEY" \
                -o IdentitiesOnly=yes \
                -o BatchMode=yes \
                -o StrictHostKeyChecking=no \
                -o UserKnownHostsFile=/dev/null \
                -o ServerAliveInterval=15 \
                -o ServerAliveCountMax=2 \
                -o ConnectTimeout=10 \
                -o ExitOnForwardFailure=yes \
                -R0:localhost:"$PORT" "$HOST" >> "$LOG" 2>&1
            echo "tunnel dropped ($(date)) — reconnecting in 5 s…" >> "$LOG"
            sleep 5
        done
    ' pinggy-supervisor "$PINGGY_LOG" "$PINGGY_KEY" "$HTTP_PORT" "$PINGGY_HOST" "$PINGGY_PID_FILE" &

    # Public URL: pinned via PINGGY_URL (custom domain), otherwise parse the
    # https URL that Pinggy prints on connect
    PG_URL="$PINGGY_URL"
    if [ -z "$PG_URL" ]; then
        for _ in $(seq 1 20); do
            # First https URL Pinggy prints (domains vary by tier); skip the
            # dashboard link in the free-tier upsell line
            PG_URL=$(grep -oE 'https://[a-zA-Z0-9.-]+' "$PINGGY_LOG" 2>/dev/null \
                     | grep -vi 'dashboard\.pinggy' | head -n1)
            [ -n "$PG_URL" ] && break
            sleep 0.5
        done
    fi

    if [ -n "$PG_URL" ]; then
        echo ""
        echo "  ┌─────────────────────────────────────────────┐"
        echo "  │  Pinggy URL:  $PG_URL"
        echo "  │  Meter QR:    $PG_URL/qr"
        echo "  │  Voice QR:    $PG_URL/voice/qr"
        echo "  └─────────────────────────────────────────────┘"
        if [ -z "$PINGGY_TOKEN" ]; then
            echo "  Free tier: this URL rotates and the tunnel expires after ~60 min."
            echo "  Set PINGGY_TOKEN in .env for a persistent URL."
        fi
        echo ""
    else
        echo "  Pinggy tunnel started but no URL yet — check $PINGGY_LOG"
    fi
else
    echo "  ssh not found — skipping Pinggy tunnel (expose manually if needed)"
fi

# ── Divoom Times Gate widget (optional) ───────────────────────────────────────
# Set TIMESGATE_TOKEN in .env (LocalToken from the Divoom app) to push the
# usage meter to a Times Gate screen. See timesgate_widget.py for options.
TIMESGATE_WIDGET_PID=""
if [ -n "$TIMESGATE_TOKEN" ]; then
    if ! python3 -c "import PIL" 2>/dev/null; then
        echo "Installing pillow..."
        python3 -m pip install --quiet pillow
    fi
    echo "Starting Times Gate widget…"
    pkill -f "timesgate_widget\.py" 2>/dev/null || true
    python3 "$SCRIPT_DIR/timesgate_widget.py" > "$SCRIPT_DIR/.timesgate.log" 2>&1 &
    TIMESGATE_WIDGET_PID=$!
fi

# Stop the tunnel supervisor + widget when the daemon exits (Ctrl-C included)
cleanup() {
    if [ -f "${PINGGY_PID_FILE:-}" ]; then
        kill -- -"$(cat "$PINGGY_PID_FILE")" 2>/dev/null || true
        rm -f "$PINGGY_PID_FILE"
    fi
    [ -n "$TIMESGATE_WIDGET_PID" ] && kill "$TIMESGATE_WIDGET_PID" 2>/dev/null || true
}
trap cleanup EXIT

python3 "$SCRIPT_DIR/buddy_daemon.py" "$@"
