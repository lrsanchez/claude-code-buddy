#!/usr/bin/env python3
"""
Configure Claude Code hooks to connect to the Claude Buddy daemon.

Usage:
    python3 setup-hooks.py          # install hooks
    python3 setup-hooks.py --remove # remove hooks
    python3 setup-hooks.py --check  # show current status

Merges hooks into ~/.claude/settings.json without touching any existing config.
Safe to run multiple times (idempotent).
"""

import json
import os
import shutil
import sys
from datetime import datetime

R    = "\033[0m";  BOLD = "\033[1m";  DIM  = "\033[2m"
GREEN = "\033[32m"; YELLOW = "\033[33m"; RED = "\033[31m"; CYAN = "\033[36m"

SETTINGS_PATH = os.path.expanduser("~/.claude/settings.json")
HOOK_SCRIPT   = os.path.abspath(os.path.join(os.path.dirname(__file__), "buddy_hook.py"))

# Hooks to install — keyed by event name
HOOKS_TO_INSTALL = {
    "SessionStart": {
        "type": "command",
        "command": f"python3 {HOOK_SCRIPT}",
        "timeout": 5,
    },
    "UserPromptSubmit": {
        "type": "command",
        "command": f"python3 {HOOK_SCRIPT}",
        "timeout": 5,
    },
    "PreToolUse": {
        "type": "command",
        "command": f"python3 {HOOK_SCRIPT}",
        "timeout": 130,
        "statusMessage": "⏳ Waiting for Buddy approval…",
    },
    "PostToolUse": {
        "type": "command",
        "command": f"python3 {HOOK_SCRIPT}",
        "timeout": 5,
    },
    "Notification": {
        "type": "command",
        "command": f"python3 {HOOK_SCRIPT}",
        "timeout": 5,
    },
    "Stop": {
        "type": "command",
        "command": f"python3 {HOOK_SCRIPT}",
        "timeout": 5,
    },
}


def load_settings() -> dict:
    if not os.path.exists(SETTINGS_PATH):
        return {}
    try:
        with open(SETTINGS_PATH) as f:
            return json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        print(f"{RED}Cannot read {SETTINGS_PATH}: {e}{R}")
        sys.exit(1)


def save_settings(settings: dict, backup: bool = True):
    os.makedirs(os.path.dirname(SETTINGS_PATH), exist_ok=True)
    if backup and os.path.exists(SETTINGS_PATH):
        ts  = datetime.now().strftime("%Y%m%d_%H%M%S")
        bak = SETTINGS_PATH + f".bak.{ts}"
        shutil.copy2(SETTINGS_PATH, bak)
        print(f"{DIM}  backup → {bak}{R}")
    with open(SETTINGS_PATH, "w") as f:
        json.dump(settings, f, indent=2)
        f.write("\n")


def hook_present(event_hooks: list, cmd: str) -> bool:
    """Return True if our command already exists under this event."""
    for group in event_hooks:
        for h in group.get("hooks", []):
            if h.get("command") == cmd:
                return True
    return False


def install():
    if not os.path.exists(HOOK_SCRIPT):
        print(f"{RED}Hook script not found: {HOOK_SCRIPT}{R}")
        sys.exit(1)

    settings = load_settings()
    hooks    = settings.setdefault("hooks", {})
    added    = []
    skipped  = []

    for event, hook_def in HOOKS_TO_INSTALL.items():
        event_hooks = hooks.setdefault(event, [])
        if hook_present(event_hooks, hook_def["command"]):
            skipped.append(event)
            continue
        # Append as a new hook group
        event_hooks.append({"hooks": [hook_def]})
        added.append(event)

    if not added and not skipped:
        print(f"{YELLOW}Nothing to do.{R}")
        return

    if added:
        save_settings(settings)
        print(f"\n{GREEN}{BOLD}Hooks installed{R} → {SETTINGS_PATH}\n")
        for e in added:
            print(f"  {GREEN}+{R} {e}")
    if skipped:
        if added:
            print()
        for e in skipped:
            print(f"  {DIM}~ {e} (already present){R}")

    print(f"\n{DIM}Restart Claude Code (or open a new session) for hooks to take effect.{R}\n")


def remove():
    settings = load_settings()
    hooks    = settings.get("hooks", {})
    removed  = []

    for event, hook_def in HOOKS_TO_INSTALL.items():
        cmd         = hook_def["command"]
        event_hooks = hooks.get(event, [])
        new_groups  = []
        changed     = False

        for group in event_hooks:
            new_inner = [h for h in group.get("hooks", []) if h.get("command") != cmd]
            if len(new_inner) != len(group.get("hooks", [])):
                changed = True
            if new_inner:
                new_groups.append({"hooks": new_inner})
            # Drop group entirely if it became empty

        if changed:
            if new_groups:
                hooks[event] = new_groups
            else:
                del hooks[event]
            removed.append(event)

    if not removed:
        print(f"{YELLOW}No Buddy hooks found to remove.{R}")
        return

    # Clean up empty hooks dict
    if not hooks:
        settings.pop("hooks", None)

    save_settings(settings)
    print(f"\n{RED}{BOLD}Hooks removed{R} → {SETTINGS_PATH}\n")
    for e in removed:
        print(f"  {RED}-{R} {e}")
    print()


def check():
    settings = load_settings()
    hooks    = settings.get("hooks", {})
    print(f"\n{BOLD}Claude Buddy hook status{R}  ({SETTINGS_PATH})\n")
    for event, hook_def in HOOKS_TO_INSTALL.items():
        event_hooks = hooks.get(event, [])
        present     = hook_present(event_hooks, hook_def["command"])
        mark        = f"{GREEN}✓{R}" if present else f"{RED}✗{R}"
        print(f"  {mark}  {event}")
    print()
    print(f"{DIM}Hook script: {HOOK_SCRIPT}{R}")
    print(f"{DIM}Script exists: {'yes' if os.path.exists(HOOK_SCRIPT) else 'NO — missing!'}{R}\n")


if __name__ == "__main__":
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg == "--remove":
        remove()
    elif arg == "--check":
        check()
    elif arg in ("", "--install"):
        install()
    else:
        print(f"Usage: {sys.argv[0]} [--install|--remove|--check]")
        sys.exit(1)
