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

# ── Pinggy tunnel ─────────────────────────────────────────────────────────────
# Set PINGGY_TOKEN in .env (Pinggy Pro) for a persistent URL on pro.pinggy.io.
# Without a token, falls back to the free tier on a.pinggy.io (random URL,
# sessions expire after ~60 min).
if command -v ssh &>/dev/null; then
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

    # Kill any stale tunnel left over from a previous run
    pkill -f "R0:localhost:$HTTP_PORT.*pinggy\.io" 2>/dev/null || true

    ssh -p 443 \
        -i "$PINGGY_KEY" \
        -o IdentitiesOnly=yes \
        -o BatchMode=yes \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        -o ServerAliveInterval=30 \
        -o ExitOnForwardFailure=yes \
        -R0:localhost:"$HTTP_PORT" "$PINGGY_HOST" \
        > "$PINGGY_LOG" 2>&1 &

    # Public URL: pinned via PINGGY_URL (custom domain), otherwise parse the
    # https URL that Pinggy prints on connect
    PG_URL="$PINGGY_URL"
    if [ -z "$PG_URL" ]; then
        for _ in $(seq 1 20); do
            PG_URL=$(grep -oE 'https://[a-zA-Z0-9.-]+\.pinggy\.link' "$PINGGY_LOG" 2>/dev/null | head -n1)
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

exec python3 "$SCRIPT_DIR/buddy_daemon.py" "$@"
