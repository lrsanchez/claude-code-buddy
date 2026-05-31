#!/usr/bin/env bash
# Claude Buddy — Linux / macOS launcher
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Ensure bleak is installed
if ! python3 -c "import bleak" 2>/dev/null; then
    echo "Installing bleak..."
    python3 -m pip install --quiet bleak
fi

exec python3 "$SCRIPT_DIR/buddy_daemon.py" "$@"
