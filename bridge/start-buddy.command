#!/usr/bin/env bash
# Claude Buddy — macOS double-click launcher (.command files open in Terminal)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! python3 -c "import bleak" 2>/dev/null; then
    echo "Installing bleak..."
    python3 -m pip install --quiet bleak
fi

python3 "$SCRIPT_DIR/buddy_daemon.py"
