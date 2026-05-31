#!/usr/bin/env python3
"""
Claude Code hook script — called by Claude Code CLI for every PreToolUse,
PostToolUse, Notification, Stop, and SessionStart event.

Forwards the event to buddy_daemon.py over a Unix socket.
If the daemon is not running, hooks pass through silently (Claude Code
behaves as if the hook wasn't there).
"""

import json
import os
import socket
import sys
import time

SOCKET_PATH = "/tmp/claude-buddy.sock"
TIMEOUT_S   = PROMPT_TIMEOUT = 130  # slightly above daemon's 120 s


def send_to_daemon(msg: dict) -> dict:
    try:
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(TIMEOUT_S)
        sock.connect(SOCKET_PATH)
        sock.sendall(json.dumps(msg).encode())
        sock.shutdown(socket.SHUT_WR)

        chunks = []
        while True:
            chunk = sock.recv(4096)
            if not chunk:
                break
            chunks.append(chunk)
        sock.close()
        return json.loads(b"".join(chunks).decode())
    except (FileNotFoundError, ConnectionRefusedError):
        # Daemon not running — pass through
        return {"continue": True}
    except Exception as e:
        sys.stderr.write(f"buddy_hook: {e}\n")
        return {"continue": True}


def build_hint(tool_name: str, tool_input: dict) -> str:
    """Extract a short one-line hint from the tool input."""
    if tool_name == "Bash":
        cmd = tool_input.get("command", "")
        # Show first non-empty, non-comment line
        for line in cmd.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                return line[:100]
        return cmd[:100]
    if tool_name in ("Edit", "Write", "Read", "MultiEdit"):
        return tool_input.get("file_path", "")[:100]
    if tool_name == "Agent":
        return tool_input.get("description", "")[:100]
    # MCP tools: use first string value
    for v in tool_input.values():
        if isinstance(v, str) and v:
            return v[:100]
    return ""


def main():
    raw = sys.stdin.read()
    try:
        hook = json.loads(raw)
    except json.JSONDecodeError:
        sys.stdout.write(json.dumps({"continue": True}))
        return

    event_name     = hook.get("hook_event_name", "")
    tool_name      = hook.get("tool_name", "")
    tool_input     = hook.get("tool_input", {})
    transcript     = hook.get("transcript_path", "")
    session_id     = hook.get("session_id", "")
    permission_mode = hook.get("permission_mode", "default")

    if event_name == "SessionStart":
        send_to_daemon({
            "event":           "session_start",
            "session_id":      session_id,
            "transcript_path": transcript,
        })
        sys.stdout.write(json.dumps({"continue": True, "suppressOutput": True}))
        return

    if event_name == "UserPromptSubmit":
        send_to_daemon({
            "event":           "user_submit",
            "session_id":      session_id,
            "transcript_path": transcript,
        })
        sys.stdout.write(json.dumps({"continue": True}))
        return

    if event_name == "PreToolUse":
        hint = build_hint(tool_name, tool_input)
        resp = send_to_daemon({
            "event":            "pre_tool",
            "tool_name":        tool_name,
            "tool_input":       tool_input,
            "tool_hint":        hint,
            "transcript_path":  transcript,
            "session_id":       session_id,
            "permission_mode":  permission_mode,
        })
        # "defer" → the buddy couldn't decide (not connected, or no tap in time).
        # Emit NO permission decision so Claude Code's own flow handles it.
        # Either approval path works; the CLI is never hard-blocked on the tablet.
        if resp.get("defer"):
            sys.stdout.write(json.dumps({"continue": True}))
        else:
            sys.stdout.write(json.dumps(resp))
        return

    if event_name == "PostToolUse":
        send_to_daemon({
            "event":           "post_tool",
            "tool_name":       tool_name,
            "transcript_path": transcript,
            "session_id":      session_id,
        })
        sys.stdout.write(json.dumps({"continue": True}))
        return

    if event_name == "Notification":
        message = hook.get("message", "")
        send_to_daemon({
            "event":   "notification",
            "message": message,
        })
        sys.stdout.write(json.dumps({"continue": True}))
        return

    if event_name == "Stop":
        send_to_daemon({
            "event":           "stop",
            "transcript_path": transcript,
            "session_id":      session_id,
        })
        sys.stdout.write(json.dumps({"continue": True}))
        return

    # Unknown event — pass through
    sys.stdout.write(json.dumps({"continue": True}))


if __name__ == "__main__":
    main()
