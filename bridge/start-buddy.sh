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

# ── Tailscale Funnel ──────────────────────────────────────────────────────────
if command -v tailscale &>/dev/null; then
    echo "Starting Tailscale Funnel on port $HTTP_PORT…"
    tailscale funnel "$HTTP_PORT" &>/dev/null &
    sleep 1   # give funnel a moment to register

    TS_URL=$(tailscale status --json 2>/dev/null | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    name = d.get('Self', {}).get('DNSName', '').rstrip('.')
    if name: print('https://' + name)
except Exception:
    pass
" 2>/dev/null)

    if [ -n "$TS_URL" ]; then
        echo ""
        echo "  ┌─────────────────────────────────────────────┐"
        echo "  │  Tailscale URL:  $TS_URL"
        echo "  │  Meter QR:       $TS_URL/qr"
        echo "  │  Voice QR:       $TS_URL/voice/qr"
        echo "  └─────────────────────────────────────────────┘"
        echo ""
    else
        echo "  Tailscale funnel started (could not read URL — run: tailscale funnel status)"
    fi
else
    echo "  tailscale not found — skipping funnel (expose manually if needed)"
fi

exec python3 "$SCRIPT_DIR/buddy_daemon.py" "$@"
