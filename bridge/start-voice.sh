#!/usr/bin/env bash
# Claude Buddy Voice — standalone VPS launcher
# Runs ONLY the voice server (no BLE, no Claude Code hooks, no meter).
# Copy these files to the VPS:  voice_server.py  voice_handler.py  r1-voice.html  .env
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load .env
if [ -f "$SCRIPT_DIR/.env" ]; then
    set -o allexport
    # shellcheck disable=SC1090
    source "$SCRIPT_DIR/.env"
    set +o allexport
fi

# Install dependencies
for pkg in aiohttp qrcode faster-whisper edge-tts; do
    if ! python3 -c "import ${pkg//-/_}" 2>/dev/null; then
        echo "Installing $pkg..."
        python3 -m pip install --quiet "$pkg"
    fi
done

exec python3 "$SCRIPT_DIR/voice_server.py" "$@"
