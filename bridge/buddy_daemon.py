#!/usr/bin/env python3
"""
Claude Buddy BLE bridge daemon — multi-session aware.

Usage:
    ./start-buddy.sh            # clean logs
    ./start-buddy.sh --verbose  # full BLE debug
"""

import asyncio
import json
import logging
import os
import random
import secrets
import signal
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Optional

from voice_handler import VoiceHandler, VOICE_MODEL

try:
    from aiohttp import web as aweb
    import aiohttp
    HAS_HTTP = True
except ImportError:
    HAS_HTTP = False

# ── Machine stats (CPU / RAM / GPU memory) ────────────────────────────────────
# Shown on the Android app and the Times Gate "SYSTEM" widget. Linux-only
# (/proc + amdgpu sysfs); returns None elsewhere so the key is simply absent.

_cpu_prev: Optional[tuple] = None   # (idle, total) jiffies from the last sample
_gpu_paths: Optional[tuple] = None  # cached ((used, total), …) sysfs path pairs


def _find_gpu_paths():
    """VRAM + GTT path pairs. On unified-memory APUs (e.g. Strix Halo) the
    dedicated carve-out is tiny and real GPU allocations live in GTT, so
    both are summed to reflect actual GPU memory usage."""
    global _gpu_paths
    if _gpu_paths is None:
        import glob
        _gpu_paths = ()
        for total in sorted(glob.glob("/sys/class/drm/card*/device/mem_info_vram_total")):
            pairs = [(total.replace("_total", "_used"), total)]
            gtt_total = os.path.join(os.path.dirname(total), "mem_info_gtt_total")
            if os.path.exists(gtt_total):
                pairs.append((gtt_total.replace("_total", "_used"), gtt_total))
            _gpu_paths = tuple(pairs)
            break
    return _gpu_paths


def machine_stats() -> Optional[dict]:
    global _cpu_prev
    try:
        with open("/proc/stat") as f:
            nums = [int(x) for x in f.readline().split()[1:]]
        idle, total = nums[3] + nums[4], sum(nums)  # idle + iowait
        cpu = -1
        if _cpu_prev and total > _cpu_prev[1]:
            didle, dtotal = idle - _cpu_prev[0], total - _cpu_prev[1]
            cpu = round(100 * (1 - didle / dtotal))
        _cpu_prev = (idle, total)

        mem = {}
        with open("/proc/meminfo") as f:
            for line in f:
                key, _, val = line.partition(":")
                if key in ("MemTotal", "MemAvailable"):
                    mem[key] = int(val.split()[0]) * 1024
                    if len(mem) == 2:
                        break
        out = {"cpu": cpu,
               "ram_used": mem["MemTotal"] - mem["MemAvailable"],
               "ram_total": mem["MemTotal"]}

        paths = _find_gpu_paths()
        if paths:
            used = total = 0
            for used_path, total_path in paths:
                with open(used_path) as f:
                    used += int(f.read())
                with open(total_path) as f:
                    total += int(f.read())
            out["gpu_used"], out["gpu_total"] = used, total
        return out
    except Exception:
        return None


# ── NUS UUIDs ─────────────────────────────────────────────────────────────────
NUS_SERVICE   = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX        = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # daemon writes snapshots here
NUS_TX        = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # device notifies here (best-effort)
DECISION_CHAR = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"  # daemon polls here for Approve/Deny

SOCKET_PATH    = "/tmp/claude-buddy.sock"
SESSION_CACHE  = "/tmp/claude-buddy-sessions.json"
SESSION_TTL    = 1800  # drop a session after 30 min with no hook activity
# How long to wait for a device tap before deferring to the CLI's own prompt.
# Configurable via BUDDY_DECISION_TIMEOUT env var (default 30 s).
BUDDY_DECISION_TIMEOUT = float(os.environ.get("BUDDY_DECISION_TIMEOUT", "30"))
CHAT_POLL_S    = 1.0
MAX_CHAT       = 25
MAX_ENTRIES    = 15

# ── HTTP / meter ──────────────────────────────────────────────────────────────
HTTP_PORT         = int(os.environ.get("BUDDY_HTTP_PORT", "7700"))
TOKEN_FILE        = os.path.expanduser("~/.local/share/claude-buddy/tokens.json")
CREDENTIALS_PATHS = [
    os.path.expanduser("~/.claude/.credentials.json"),
    os.path.expanduser("~/.config/claude/.credentials.json"),
]
METER_POLL_S      = 60         # how often to hit the Anthropic API
PAIRING_WINDOW_S  = 300        # 5-min validity for a pairing code
PAIRING_MAX_TRIES = 5          # lock code after this many wrong attempts
BUDDY_TOKEN       = os.environ.get("BUDDY_TOKEN", "")   # pre-shared; skips pairing if set
CREATION_HTML     = os.path.join(os.path.dirname(__file__), "r1-meter.html")
QR_HTML           = os.path.join(os.path.dirname(__file__), "r1-qr.html")
VOICE_HTML        = os.path.join(os.path.dirname(__file__), "r1-voice.html")

# ── Logging ───────────────────────────────────────────────────────────────────
R="\033[0m"; BOLD="\033[1m"; DIM="\033[2m"
GREEN="\033[32m"; YELLOW="\033[33m"; RED="\033[31m"
CYAN="\033[36m"; BLUE="\033[34m"; GREY="\033[90m"

ICONS = {
    "SCAN": f"{CYAN}◌{R}", "CONNECT": f"{GREEN}●{R}", "DISCONNECT": f"{RED}○{R}",
    "WAIT": f"{YELLOW}⏳{R}", "APPROVE": f"{GREEN}✔{R}", "DENY": f"{RED}✘{R}",
    "CHAT_USER": f"{BLUE}▸{R}", "CHAT_AI": f"{CYAN}◆{R}",
    "SESSION": f"{CYAN}⊕{R}", "SUBMIT": f"{BLUE}→{R}",
    "SOCKET": f"{GREY}⌁{R}", "ERROR": f"{RED}!{R}", "INFO": f"{GREY}·{R}",
}

class _Fmt(logging.Formatter):
    def format(self, r):
        msg = r.getMessage()
        if r.levelno >= logging.ERROR: msg = f"{RED}{msg}{R}"
        elif r.levelno >= logging.WARNING: msg = f"{YELLOW}{msg}{R}"
        return f"{GREY}{time.strftime('%H:%M:%S')}{R}  {getattr(r,'icon',ICONS['INFO'])}  {msg}"

def _setup(verbose: bool):
    h = logging.StreamHandler(); h.setFormatter(_Fmt())
    logging.getLogger().setLevel(logging.DEBUG if verbose else logging.INFO)
    logging.getLogger().addHandler(h)
    for n in ("bleak","bleak.backends","dbus_fast","asyncio"):
        logging.getLogger(n).setLevel(logging.DEBUG if verbose else logging.WARNING)

def _log(icon: str, msg: str, level=logging.INFO):
    r = logging.LogRecord("buddy", level, "", 0, msg, (), None)
    r.icon = ICONS.get(icon, ICONS["INFO"])
    logging.getLogger("buddy").handle(r)

# ── Data ──────────────────────────────────────────────────────────────────────

@dataclass
class PendingPrompt:
    id: str
    future: asyncio.Future

@dataclass
class Session:
    id: str
    short_id: str              # last 4 chars, uppercase
    transcript_path: str = ""
    transcript_pos: int  = 0
    running: bool        = False
    waiting: bool        = False
    entries: list        = field(default_factory=list)
    chat: list           = field(default_factory=list)  # this session's own chat
    tokens: int          = 0
    tokens_today: int    = 0
    session_start_tokens: int = 0
    last_seen: float     = 0.0   # wall time of last hook activity (for TTL expiry)
    name: str            = ""    # human-readable name from /rename
    pending: Optional[PendingPrompt] = None
    active_prompt: Optional[dict] = None  # kept in every snapshot while waiting

@dataclass
class MeterCache:
    s:  int   = -1    # session usage % (-1 = not yet known)
    sr: int   = 0     # session reset in minutes
    w:  int   = -1    # weekly usage %
    wr: int   = 0     # weekly reset in minutes
    st: str   = "error"
    ts: float = 0.0

# ── Transcript helpers ────────────────────────────────────────────────────────

def _text(content) -> str:
    if isinstance(content, str): return content.strip()
    if isinstance(content, list):
        for b in content:
            if isinstance(b, dict) and b.get("type") == "text":
                return b.get("text","").strip()
    return ""

_SESSIONS_DIR = os.path.expanduser("~/.claude/sessions")

def _read_session_name(session_id: str) -> str:
    """Read the human-readable session name from ~/.claude/sessions/<pid>.json.
    Returns the best (non-empty) name when multiple PID files share the same sessionId."""
    best = ""
    try:
        for fname in os.listdir(_SESSIONS_DIR):
            if not fname.endswith(".json"):
                continue
            try:
                with open(os.path.join(_SESSIONS_DIR, fname)) as f:
                    data = json.load(f)
                if data.get("sessionId") != session_id:
                    continue
                name = data.get("name", "").strip()
                if name:
                    best = name
            except (OSError, json.JSONDecodeError):
                pass
    except OSError:
        pass
    return best

def refresh_entries(session: Session):
    """Re-parse the transcript to rebuild tool entries and token count."""
    entries, tokens = [], 0
    try:
        with open(session.transcript_path, errors="replace") as f:
            for raw in f:
                raw = raw.strip()
                if not raw: continue
                try: obj = json.loads(raw)
                except: continue
                msg = obj.get("message", {})
                if not isinstance(msg, dict) or msg.get("role") != "assistant": continue
                tokens += msg.get("usage", {}).get("output_tokens", 0)
                for b in msg.get("content", []):
                    if not isinstance(b, dict) or b.get("type") != "tool_use": continue
                    tool_name = b.get("name","?")
                    inp  = b.get("input",{})
                    hint = str(inp.get("command") or inp.get("file_path") or
                               inp.get("description") or inp.get("prompt") or "")[:60].replace("\n"," ")
                    ts_raw = obj.get("timestamp","")
                    ts = ts_raw[11:16] if len(ts_raw) >= 16 else time.strftime("%H:%M")
                    entries.append(f"{ts} {tool_name}" + (f": {hint}" if hint else ""))
    except (OSError, IOError): pass
    session.entries = list(reversed(entries))[:MAX_ENTRIES]
    session.tokens  = tokens
    # Update name from the live sessions file (written by /rename)
    name = _read_session_name(session.id)
    if name: session.name = name
    session.tokens_today = max(0, tokens - session.session_start_tokens)

def scan_chat(path: str, pos: int) -> tuple[list, int]:
    """Read new chat messages from transcript starting at pos. Returns (entries_oldest_first, new_pos)."""
    new_entries = []
    try:
        with open(path, errors="replace") as f:
            f.seek(pos)
            for raw in f:
                raw = raw.strip()
                if not raw: continue
                try: obj = json.loads(raw)
                except: continue
                msg = obj.get("message", {})
                if not isinstance(msg, dict): continue
                role, content = msg.get("role"), msg.get("content", [])
                text = _text(content)
                if not text or role not in ("user","assistant"): continue
                if role == "assistant":
                    has_text = (isinstance(content, str) or
                                any(isinstance(b,dict) and b.get("type")=="text" and b.get("text","").strip()
                                    for b in (content if isinstance(content,list) else [])))
                    if not has_text: continue
                new_entries.append({"role": role, "text": text[:200] + ("…" if len(text)>200 else "")})
            new_pos = f.tell()
    except (OSError, IOError): new_pos = pos
    return new_entries, new_pos

# ── Token store (pairing + HTTP auth) ────────────────────────────────────────

class TokenStore:
    """Manages the pairing handshake and the set of issued bearer tokens."""

    def __init__(self, path: str):
        self._path = path
        self._tokens: set[str] = set()
        self._code: Optional[str] = None
        self._code_expires: float = 0.0
        self._code_attempts: int = 0
        self._load()

    def _load(self):
        try:
            with open(self._path) as f:
                self._tokens = set(json.load(f).get("tokens", []))
        except (OSError, json.JSONDecodeError):
            pass

    def _save(self):
        os.makedirs(os.path.dirname(self._path), exist_ok=True)
        with open(self._path, "w") as f:
            json.dump({"tokens": list(self._tokens)}, f)

    def open_window(self) -> str:
        """Generate a new 6-digit pairing code and open the 5-minute window."""
        self._code = f"{random.randint(0, 999_999):06d}"
        self._code_expires = time.time() + PAIRING_WINDOW_S
        self._code_attempts = 0
        return self._code

    @property
    def window_open(self) -> bool:
        return bool(self._code) and time.time() < self._code_expires

    def try_pair(self, code: str) -> Optional[str]:
        """Validate code → return a new bearer token, or None on failure.
        The code is valid for multiple uses within its window so both the
        meter and voice creations can pair with the same code."""
        if not self._code or time.time() >= self._code_expires:
            return None
        self._code_attempts += 1
        if self._code_attempts > PAIRING_MAX_TRIES:
            self._code = None
            return None
        if code != self._code:
            return None
        token = secrets.token_hex(32)
        self._tokens.add(token)
        # Do NOT clear self._code — keep it valid until the window expires
        # so the same code can pair multiple creations in one go.
        self._save()
        return token

    def register(self, token: str):
        """Pre-register a known token (e.g. from BUDDY_TOKEN env var)."""
        self._tokens.add(token)
        self._save()

    def is_valid(self, token: str) -> bool:
        return token in self._tokens


# ── Daemon ────────────────────────────────────────────────────────────────────

class BuddyDaemon:
    def __init__(self, verbose: bool = False):
        _setup(verbose)
        self._sessions: dict[str, Session] = {}
        self._global_chat: list = []
        self._start_time  = time.time()
        self._approve_cnt = 0
        self._deny_cnt    = 0
        self._token_store = TokenStore(TOKEN_FILE)
        if BUDDY_TOKEN:
            self._token_store.register(BUDDY_TOKEN)
        self._meter_cache = MeterCache()
        self._voice       = VoiceHandler()
        self._restore_sessions()

    def _restore_sessions(self):
        """Reload only RECENT transcripts from last run (so we don't resurrect
        sessions the user ended an hour ago). A session counts as recent if its
        transcript file was modified within SESSION_TTL."""
        now = time.time()
        try:
            with open(SESSION_CACHE) as f:
                for entry in json.load(f):
                    sid   = entry.get("id", "")
                    path  = entry.get("transcript_path", "")
                    short = entry.get("short_id", sid[-4:].upper() if len(sid) >= 4 else sid)
                    if not (sid and path and os.path.exists(path)):
                        continue
                    try:
                        mtime = os.path.getmtime(path)
                    except OSError:
                        continue
                    if now - mtime > SESSION_TTL:
                        continue  # stale — skip
                    s = Session(id=sid, short_id=short, transcript_path=path)
                    s.last_seen = mtime
                    refresh_entries(s)
                    s.session_start_tokens = s.tokens  # baseline at restore so tokens_today starts at 0
                    s.tokens_today = 0
                    self._sessions[sid] = s
                    _log("SESSION", f"Restored session {BOLD}{short}{R} from cache")
        except (OSError, json.JSONDecodeError, KeyError):
            pass

    def _persist_sessions(self):
        """Save current transcript paths so the next daemon run can restore them."""
        try:
            data = [{"id": s.id, "short_id": s.short_id, "transcript_path": s.transcript_path}
                    for s in self._sessions.values() if s.transcript_path and os.path.exists(s.transcript_path)]
            with open(SESSION_CACHE, 'w') as f:
                json.dump(data, f)
        except OSError:
            pass

    # ── Session helpers ───────────────────────────────────────────────────────

    def _get_session(self, session_id: str, transcript_path: str = "") -> Session:
        if session_id not in self._sessions:
            short = (session_id[-4:] if len(session_id) >= 4 else session_id).upper()
            self._sessions[session_id] = Session(id=session_id, short_id=short)
            n = len(self._sessions)
            _log("SESSION", f"Session {BOLD}{short}{R}  (total: {n})")
        s = self._sessions[session_id]
        s.last_seen = time.time()
        if transcript_path and transcript_path != s.transcript_path:
            s.transcript_path = transcript_path
            s.transcript_pos  = 0
            self._persist_sessions()
        return s

    def _prune_sessions(self):
        """Drop sessions with no hook activity for SESSION_TTL (ended/abandoned)."""
        now = time.time()
        dead = [sid for sid, s in self._sessions.items()
                if s.last_seen and (now - s.last_seen) > SESSION_TTL]
        for sid in dead:
            short = self._sessions[sid].short_id
            del self._sessions[sid]
            _log("SESSION", f"Session {short} expired (idle > {SESSION_TTL//60}m)")
        if dead:
            self._persist_sessions()

    def _aggregate(self) -> dict:
        self._prune_sessions()
        sessions = list(self._sessions.values())
        multi = len(sessions) > 1
        running = sum(1 for s in sessions if s.running)
        waiting = sum(1 for s in sessions if s.waiting)
        tokens  = sum(s.tokens for s in sessions)
        today   = sum(s.tokens_today for s in sessions)
        # Merge entries; tag with session if multiple
        entries = []
        for s in sessions:
            pfx = f"[{s.short_id}] " if multi else ""
            for e in s.entries:
                entries.append(f"{pfx}{e}")
        # msg from first waiting session
        msg = next((s.entries[0] if s.entries else f"approve — [{s.short_id}]"
                    for s in sessions if s.waiting), "")
        # Per-session breakdown so the app can filter to a single session.
        # Only sent when 2+ sessions (the selector is hidden for one), which
        # avoids duplicating chat/entries in the common single-session case.
        per_session = [] if not multi else [{
            "id":      s.id,
            "short":   s.short_id,
            "name":    s.name,
            "running": s.running,
            "waiting": s.waiting,
            "tokens":  s.tokens,
            "entries": s.entries[:MAX_ENTRIES],
            "chat":    s.chat[:MAX_CHAT],
        } for s in sessions]
        return {
            "total": len(sessions),
            "running": running,
            "waiting": waiting,
            "msg": msg,
            "entries": entries[:MAX_ENTRIES],
            "tokens": tokens,
            "tokens_today": today,
            "chat": self._global_chat[:MAX_CHAT],  # merged ("All" view)
            "sessions": per_session,
        }

    # ── Chat follow ───────────────────────────────────────────────────────────

    async def chat_follow_loop(self):
        while True:
            await asyncio.sleep(CHAT_POLL_S)
            multi = len(self._sessions) > 1
            changed = False
            for session in list(self._sessions.values()):
                if not session.transcript_path: continue
                try:
                    new_entries, new_pos = scan_chat(session.transcript_path, session.transcript_pos)
                    session.transcript_pos = new_pos
                    if not new_entries: continue
                    changed = True
                    for e in reversed(new_entries):
                        entry = {"role": e["role"], "text": e["text"]}
                        # per-session chat (untagged — the panel header shows which)
                        session.chat.insert(0, dict(entry))
                        # global merged chat (tagged when multiple sessions)
                        if multi: entry["session"] = session.name or session.short_id
                        self._global_chat.insert(0, entry)
                        who = "You" if e["role"]=="user" else "Claude"
                        icon = "CHAT_USER" if e["role"]=="user" else "CHAT_AI"
                        tag = f" {DIM}[{session.short_id}]{R}" if multi else ""
                        _log(icon, f"{BOLD}{who}{R}{tag}: {DIM}{e['text'][:70]}{R}")
                    session.chat = session.chat[:MAX_CHAT]
                    self._global_chat = self._global_chat[:MAX_CHAT]
                except Exception as exc:
                    logging.getLogger("buddy").debug(f"chat_follow [{session.short_id}]: {exc}")

    # ── Socket server ─────────────────────────────────────────────────────────

    async def socket_loop(self):
        if os.path.exists(SOCKET_PATH): os.unlink(SOCKET_PATH)
        server = await asyncio.start_unix_server(self._handle_hook, SOCKET_PATH)
        os.chmod(SOCKET_PATH, 0o666)
        _log("SOCKET", f"Hook socket: {SOCKET_PATH}")
        async with server: await server.serve_forever()

    async def _handle_hook(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        try:
            msg   = json.loads((await reader.read(65536)).decode())
            event = msg.get("event")
            if   event == "pre_tool":      resp = await self._pre_tool(msg)
            elif event == "post_tool":     await self._post_tool(msg);    resp = {"continue": True}
            elif event == "user_submit":   await self._user_submit(msg);  resp = {"continue": True}
            elif event == "notification":  await self._notification(msg); resp = {"continue": True}
            elif event == "stop":          await self._stop(msg);         resp = {"continue": True}
            elif event == "session_start": await self._session_start(msg);resp = {"continue": True}
            else: resp = {"continue": True}
            writer.write(json.dumps(resp).encode())
            await writer.drain()
        except Exception as e: _log("ERROR", f"Hook: {e}", logging.ERROR)
        finally: writer.close()

    async def _session_start(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        if s.transcript_path:
            refresh_entries(s)
            s.session_start_tokens = s.tokens
            s.tokens_today = 0
        s.running = True   # Claude will respond to session start

    async def _user_submit(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        s.running = True   # User sent a message, Claude is now generating
        _log("SUBMIT", f"Session {BOLD}{s.short_id}{R} — generating response")

    async def _pre_tool(self, msg: dict) -> dict:
        sid  = msg.get("session_id","unknown")
        tool = msg.get("tool_name","?")
        inp  = msg.get("tool_input",{})
        hint = str(inp.get("command") or inp.get("file_path") or
                   inp.get("description") or inp.get("prompt") or "")[:100].replace("\n"," ")
        s = self._get_session(sid, msg.get("transcript_path",""))

        s.running = True
        s.waiting = True

        pid    = f"req_{int(time.monotonic()*1000)%1_000_000_000}"
        prompt = {"id": pid, "tool": tool, "hint": hint}
        loop   = asyncio.get_event_loop()
        future = loop.create_future()
        s.pending       = PendingPrompt(id=pid, future=future)
        s.active_prompt = prompt  # persisted so heartbeats keep sending it

        multi = len(self._sessions) > 1
        tag = f" {DIM}[{s.short_id}]{R}" if multi else ""
        hint_short = hint[:60] + ("…" if len(hint)>60 else "")
        _log("WAIT", f"{BOLD}{tool}{R}{tag}  {DIM}{hint_short}{R}")

        # The always-on keepalive loop polls DECISION and resolves this future.
        # On timeout, DEFER (not deny) so the CLI's normal flow takes over —
        # either approval path works, and we never hard-block.
        deferred = False
        try:
            decision = await asyncio.wait_for(future, timeout=BUDDY_DECISION_TIMEOUT)
        except asyncio.TimeoutError:
            deferred = True
            decision = None
            _log("INFO", f"No tablet response — deferring {tool} to CLI", logging.WARNING)
        finally:
            s.pending       = None
            s.active_prompt = None  # clear so heartbeats stop sending the prompt

        s.waiting = False
        s.running = True

        if deferred:
            return {"defer": True}
        if decision == "once":
            self._approve_cnt += 1
            _log("APPROVE", f"{GREEN}{BOLD}{tool}{R}{tag}  {DIM}[✔{self._approve_cnt} ✘{self._deny_cnt}]{R}")
            return {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}
        self._deny_cnt += 1
        _log("DENY", f"{RED}{BOLD}{tool}{R}{tag}  {DIM}[✔{self._approve_cnt} ✘{self._deny_cnt}]{R}")
        return {"hookSpecificOutput":{
            "hookEventName":"PreToolUse","permissionDecision":"deny",
            "permissionDecisionReason":"Denied via Claude Buddy",
        }}

    async def _post_tool(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        s.waiting = False
        s.running = True  # Claude is processing the tool result, still running

    async def _notification(self, msg: dict):
        text = msg.get("message","")
        if text:
            ts = time.strftime("%H:%M")
            sid = msg.get("session_id","")
            s = self._sessions.get(sid)
            if s: s.entries = [f"{ts} ◈ {text[:60]}"] + s.entries[:MAX_ENTRIES-1]

    async def _stop(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        s.running = False  # Claude finished this turn
        s.waiting = False

    # ── Meter poller ──────────────────────────────────────────────────────────

    def _read_claude_token(self) -> Optional[str]:
        """Read the Claude Code OAuth access token from disk."""
        for path in CREDENTIALS_PATHS:
            if not os.path.exists(path):
                continue
            try:
                with open(path) as f:
                    data = json.load(f)
                # Format: {"claudeAiOauth": {"accessToken": "..."}}  (current)
                if "claudeAiOauth" in data:
                    return data["claudeAiOauth"].get("accessToken")
                # Format: {"claudeAiOauthTokens": {"accessToken": "..."}}  (older)
                if "claudeAiOauthTokens" in data:
                    return data["claudeAiOauthTokens"].get("accessToken")
                # Format: {"accessToken": "..."}
                if "accessToken" in data:
                    return data["accessToken"]
                # Try nested keys
                for key in ("oauth", "credentials", "token"):
                    sub = data.get(key)
                    if isinstance(sub, dict):
                        t = sub.get("accessToken") or sub.get("access_token")
                        if t:
                            return t
                _log("ERROR", f"Unknown credentials format, keys: {list(data.keys())}", logging.WARNING)
            except (OSError, json.JSONDecodeError) as e:
                logging.getLogger("buddy").debug(f"credentials read ({path}): {e}")
        return None

    def _parse_meter_headers(self, headers: dict):
        """Parse Anthropic unified rate-limit headers into the meter cache.

        Actual header names (confirmed empirically):
          anthropic-ratelimit-unified-5h-utilization   float 0.0-1.0
          anthropic-ratelimit-unified-5h-reset         epoch seconds
          anthropic-ratelimit-unified-7d-utilization   float 0.0-1.0
          anthropic-ratelimit-unified-7d-reset         epoch seconds
        """
        h = {k.lower(): v for k, v in headers.items()}
        c = self._meter_cache

        def utilization_pct(key: str) -> Optional[int]:
            val = h.get(key)
            if val is None:
                return None
            try:
                return min(100, max(0, round(float(val) * 100)))
            except ValueError:
                return None

        def mins_until_epoch(key: str) -> Optional[int]:
            val = h.get(key)
            if val is None:
                return None
            try:
                delta = int(float(val)) - time.time()
                return max(0, int(delta / 60))
            except ValueError:
                return None

        s  = utilization_pct("anthropic-ratelimit-unified-5h-utilization")
        sr = mins_until_epoch("anthropic-ratelimit-unified-5h-reset")
        w  = utilization_pct("anthropic-ratelimit-unified-7d-utilization")
        wr = mins_until_epoch("anthropic-ratelimit-unified-7d-reset")

        if s  is not None: c.s  = s
        if sr is not None: c.sr = sr
        if w  is not None: c.w  = w
        if wr is not None: c.wr = wr

    async def _poll_meter(self):
        access_token = self._read_claude_token()
        if not access_token:
            self._meter_cache.st = "error"
            return
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    "https://api.anthropic.com/v1/messages",
                    headers={
                        "Authorization":    f"Bearer {access_token}",
                        "anthropic-version": "2023-06-01",
                        "content-type":     "application/json",
                        "x-app-name":       "claude-code",
                    },
                    json={
                        "model":    "claude-haiku-4-5-20251001",
                        "max_tokens": 1,
                        "messages": [{"role": "user", "content": "hi"}],
                    },
                    timeout=aiohttp.ClientTimeout(total=20),
                ) as resp:
                    hdrs = dict(resp.headers)
                    # On first successful poll, log all limit/usage headers so we
                    # can identify the right field names empirically.
                    if self._meter_cache.ts == 0.0:
                        interesting = {k: v for k, v in hdrs.items()
                                       if any(w in k.lower() for w in
                                              ("limit","remaining","reset","quota","usage","rate"))}
                        _log("INFO", f"Rate headers discovered: {interesting}")
                    self._parse_meter_headers(hdrs)
                    self._meter_cache.st = "ok"
                    self._meter_cache.ts = time.time()
        except Exception as e:
            _log("ERROR", f"meter poll failed: {e}", logging.WARNING)
            if self._meter_cache.st == "ok":
                self._meter_cache.st = "stale"

    async def meter_poll_loop(self):
        while True:
            await self._poll_meter()
            await asyncio.sleep(METER_POLL_S)

    # ── HTTP server ───────────────────────────────────────────────────────────

    async def _require_auth(self, request) -> Optional[str]:
        """Return bearer token if valid, else raise HTTP 401/403."""
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            raise aweb.HTTPUnauthorized(reason="Bearer token required")
        token = auth[7:].strip()
        if not self._token_store.is_valid(token):
            raise aweb.HTTPForbidden(reason="Invalid token")
        return token

    async def _serve_file(self, path: str) -> aweb.Response:
        try:
            with open(path, "rb") as f:
                return aweb.Response(body=f.read(), content_type="text/html")
        except OSError:
            return aweb.Response(text=f"file not found: {path}", status=404)

    async def _route_creation(self, request):
        return await self._serve_file(CREATION_HTML)

    def _make_qr_svg(self, url: str) -> str:
        """Generate a QR code SVG string for url. Returns an error snippet on failure."""
        try:
            import io, re, qrcode
            from qrcode.image.svg import SvgPathImage
            qr = qrcode.QRCode(
                version=None,
                error_correction=qrcode.constants.ERROR_CORRECT_M,
                box_size=10, border=3,
                image_factory=SvgPathImage,
            )
            qr.add_data(url)
            qr.make(fit=True)
            buf = io.BytesIO()
            qr.make_image().save(buf)
            svg = buf.getvalue().decode("utf-8")
            # Strip XML declaration; fix to a fixed display size
            svg = svg.replace("<?xml version='1.0' encoding='UTF-8'?>", "").strip()
            svg = re.sub(r'width="[^"]+" height="[^"]+"', 'width="220" height="220"', svg)
            return svg
        except ImportError:
            return '<text fill="#ff5a1f" font-family="monospace" font-size="12" x="10" y="30">pip install qrcode</text>'
        except Exception as e:
            return f'<text fill="#ff5a1f" font-family="monospace" font-size="12" x="10" y="30">QR error: {e}</text>'

    async def _route_test(self, request):
        """Minimal diagnostic page — confirms the WebView can load JS at all."""
        html = ("<!DOCTYPE html><html><head>"
                "<meta charset='UTF-8'>"
                "<meta name='viewport' content='width=240,initial-scale=1,user-scalable=no'>"
                "</head><body style='background:#000;color:#0f0;padding:12px;"
                "font-family:monospace;font-size:11px;width:240px;height:282px;overflow:hidden'>"
                "<div id='o'>loading…</div>"
                "<canvas id='c' width='84' height='84' style='display:block;margin:8px 0'></canvas>"
                "<script>"
                "try{"
                "  var o=document.getElementById('o');"
                "  o.textContent='JS OK';"
                "  var c=document.getElementById('c').getContext('2d');"
                "  c.strokeStyle='#46d6cf';c.lineWidth=8;"
                "  c.beginPath();c.arc(42,42,34,-Math.PI/2,Math.PI);c.stroke();"
                "  o.textContent='JS+canvas OK\\n'+navigator.userAgent.slice(0,60);"
                "}catch(e){document.body.textContent='err:'+e;}"
                "</script></body></html>")
        return aweb.Response(text=html, content_type="text/html")

    async def _route_qr(self, request):
        # Detect scheme — Pinggy injects X-Forwarded-Proto: https
        proto = request.headers.get("X-Forwarded-Proto", "http")
        host  = (request.headers.get("X-Forwarded-Host")
                 or request.headers.get("Host")
                 or f"localhost:{HTTP_PORT}")
        creation_url = f"{proto}://{host}/"
        # R1 expects a JSON payload in the QR, not a bare URL
        payload = json.dumps({
            "title":       "Claude Buddy",
            "url":         creation_url + "?ngrok-skip-browser-warning=1&v=3",
            "description": "Live Claude Code session monitor + approvals",
            "themeColor":  "#ff5a1f",
        }, separators=(",", ":"))
        svg = self._make_qr_svg(payload)
        html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>r1 · install creation</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,600;12..96,800&family=DM+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
:root{{--bg:#070708;--screen:#0a0a0c;--ink:#f4f2ee;--dim:#6e6c66;--line:#1c1b19;--orange:#ff5a1f;--amber:#ffb347;--teal:#46d6cf}}
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:radial-gradient(900px 400px at 70% -10%,#13110e 0,transparent 60%),var(--bg);color:var(--ink);font-family:"DM Mono",ui-monospace,monospace;min-height:100vh;display:flex;align-items:center;justify-content:center;-webkit-font-smoothing:antialiased}}
.card{{display:flex;flex-direction:column;align-items:center;gap:28px;padding:48px 40px 44px;background:var(--screen);border:1px solid var(--line);border-radius:24px;box-shadow:0 40px 80px -30px #000;max-width:420px;width:100%}}
.kick{{font-size:10px;letter-spacing:.42em;text-transform:uppercase;color:var(--orange);font-weight:500}}
.title{{font-family:"Bricolage Grotesque",sans-serif;font-weight:800;font-size:26px;letter-spacing:-.02em;margin-top:10px}}
.title em{{font-style:normal;color:var(--dim)}}
.sub{{font-size:10.5px;color:var(--dim);margin-top:10px;line-height:1.7;letter-spacing:.03em;text-align:center}}
.sub b{{color:var(--ink);font-weight:500}}
.qr-frame{{background:#fff;border-radius:16px;padding:16px;display:flex;align-items:center;justify-content:center}}
.url-box{{width:100%;background:#0d0d0f;border:1px solid var(--line);border-radius:10px;padding:10px 14px;font-size:11px;color:var(--teal);letter-spacing:.02em;word-break:break-all;text-align:center}}
.divider{{width:100%;height:1px;background:var(--line)}}
.steps{{width:100%;display:flex;flex-direction:column;gap:10px}}
.step{{display:flex;gap:12px;align-items:flex-start;font-size:10.5px;color:var(--dim);letter-spacing:.02em;line-height:1.55}}
.step .n{{font-family:"Bricolage Grotesque",sans-serif;font-weight:800;font-size:13px;color:var(--orange);flex-shrink:0;line-height:1.35}}
.step b{{color:var(--ink);font-weight:500}}
</style>
</head>
<body>
<div class="card">
  <div style="text-align:center">
    <div class="kick">rabbit r1 · creation</div>
    <div class="title">install <em>/ usage meter</em></div>
    <div class="sub">scan with your r1 to install.<br>must be accessed via your <b>Pinggy URL</b> for the QR to encode the right address.</div>
  </div>
  <div class="qr-frame">{svg}</div>
  <div class="url-box">{payload}</div>
  <div class="divider"></div>
  <div class="steps">
    <div class="step"><span class="n">1</span><span>Run <b>./start-buddy.sh</b> (starts a Pinggy tunnel), then open this page via your Pinggy URL — not localhost.</span></div>
    <div class="step"><span class="n">2</span><span>On the r1: <b>Settings → Creations → Install</b> and scan the QR above.</span></div>
    <div class="step"><span class="n">3</span><span>First launch shows the <b>pair</b> screen — enter the 6-digit code from the daemon logs using the scroll wheel.</span></div>
  </div>
</div>
</body>
</html>"""
        return aweb.Response(text=html, content_type="text/html")

    async def _route_auto_token(self, request):
        """Return the pre-shared BUDDY_TOKEN so creations can self-pair silently.
        Only useful when BUDDY_TOKEN is set in .env — returns 404 otherwise.
        Unprotected by design: the Pinggy URL is public but unguessable, so
        anyone who has the URL is assumed to be the owner."""
        if not BUDDY_TOKEN:
            return aweb.Response(status=404)
        return aweb.json_response({"token": BUDDY_TOKEN})

    async def _route_pair(self, request):
        try:
            data = await request.json()
        except Exception:
            return aweb.Response(text="bad json", status=400)
        code = str(data.get("code", "")).strip()
        token = self._token_store.try_pair(code)
        if token is None:
            return aweb.json_response({"ok": False, "error": "invalid code or window closed"}, status=403)
        _log("CONNECT", f"R1 paired successfully — token issued")
        return aweb.json_response({"ok": True, "token": token})

    async def _route_meter(self, request):
        await self._require_auth(request)
        c = self._meter_cache
        return aweb.json_response({
            "s":  c.s,
            "sr": c.sr,
            "w":  c.w,
            "wr": c.wr,
            "st": c.st,
            "ts": int(c.ts),
        })


    def _sync_active_sessions(self):
        """Discover running Claude Code sessions from ~/.claude/sessions/ and register
        any that the daemon hasn't seen yet (e.g. sessions started before the daemon).
        When the same sessionId appears in multiple PID files, prefer the one with a name."""
        # Collect best entry per sessionId (non-empty name wins over empty)
        best: dict = {}
        try:
            for fname in os.listdir(_SESSIONS_DIR):
                if not fname.endswith(".json"):
                    continue
                try:
                    with open(os.path.join(_SESSIONS_DIR, fname)) as f:
                        data = json.load(f)
                    sid = data.get("sessionId", "")
                    if not sid:
                        continue
                    name = data.get("name", "").strip()
                    # Replace existing entry only if this one has a name and previous doesn't
                    if sid not in best or (name and not best[sid].get("name", "").strip()):
                        best[sid] = data
                except (OSError, json.JSONDecodeError, KeyError):
                    pass
        except OSError:
            pass

        for sid, data in best.items():
            # Update name on already-tracked sessions too
            name = data.get("name", "").strip()
            if sid in self._sessions:
                if name:
                    self._sessions[sid].name = name
                continue
            cwd = data.get("cwd", "")
            if not cwd:
                continue
            transcript = os.path.expanduser(
                f"~/.claude/projects/{cwd.replace('/', '-')}/{sid}.jsonl"
            )
            if not os.path.exists(transcript):
                continue
            s = self._get_session(sid, transcript)
            if name:
                s.name = name
            _log("SESSION", f"Discovered session {BOLD}{s.short_id}{R}"
                 + (f" · {name}" if name else ""))

    async def _route_status(self, request):
        """Full session snapshot + meter — polled by the Android app every 3 s."""
        await self._require_auth(request)
        # Auto-discover sessions started before the daemon / before hooks fired
        self._sync_active_sessions()
        # Refresh entries so session names (/rename) and tokens stay current
        for s in self._sessions.values():
            if s.transcript_path:
                refresh_entries(s)
        snap = self._aggregate()
        active = next((s.active_prompt for s in self._sessions.values() if s.active_prompt), None)
        if active:
            snap["prompt"] = active
        c = self._meter_cache
        snap.update({"s": c.s, "sr": c.sr, "w": c.w, "wr": c.wr})
        m = machine_stats()
        if m:
            snap["machine"] = m
        return aweb.json_response(snap)

    async def _route_decision(self, request):
        """Receive Approve/Deny from any HTTP client (Android app, R1, browser)."""
        await self._require_auth(request)
        try:
            data = await request.json()
        except Exception:
            return aweb.json_response({"ok": False, "error": "bad json"}, status=400)
        pid = data.get("id", "")
        dec = data.get("decision", "deny")
        for s in self._sessions.values():
            if s.pending and s.pending.id == pid and not s.pending.future.done():
                s.pending.future.set_result(dec)
                icon = "APPROVE" if dec == "once" else "DENY"
                _log(icon, f"HTTP decision: {dec} [{pid[:16]}]")
                if dec == "once":
                    self._approve_cnt += 1
                else:
                    self._deny_cnt += 1
                return aweb.json_response({"ok": True})
        return aweb.json_response({"ok": False, "error": "prompt not found"}, status=404)

    async def _route_clear_chat(self, request):
        """Clear all in-memory chat history (does not touch transcript files)."""
        await self._require_auth(request)
        self._global_chat.clear()
        for s in self._sessions.values():
            s.chat.clear()
            # Advance transcript_pos so we don't re-read old messages
            if s.transcript_path and os.path.exists(s.transcript_path):
                s.transcript_pos = os.path.getsize(s.transcript_path)
        _log("CHAT", "Chat history cleared")
        return aweb.json_response({"ok": True})

    # ── Voice routes ──────────────────────────────────────────────────────────

    async def _route_voice(self, request):
        return await self._serve_file(VOICE_HTML)

    async def _route_voice_qr(self, request):
        proto = request.headers.get("X-Forwarded-Proto", "http")
        host  = (request.headers.get("X-Forwarded-Host")
                 or request.headers.get("Host")
                 or f"localhost:{HTTP_PORT}")
        creation_url = f"{proto}://{host}/voice"
        payload = json.dumps({
            "title":       "Claude Buddy Voice",
            "url":         creation_url + "?ngrok-skip-browser-warning=1&v=1",
            "description": "Push-to-talk voice assistant via OpenRouter",
            "themeColor":  "#ff5a1f",
        }, separators=(",", ":"))
        svg  = self._make_qr_svg(payload)
        html = f"""<!DOCTYPE html><html><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>r1 voice · install</title>
<style>
body{{background:#070708;color:#f4f2ee;font-family:ui-monospace,monospace;
  display:flex;align-items:center;justify-content:center;min-height:100vh;
  -webkit-font-smoothing:antialiased}}
.card{{display:flex;flex-direction:column;align-items:center;gap:20px;
  padding:40px;background:#0a0a0c;border:1px solid #1c1b19;border-radius:20px;
  box-shadow:0 30px 60px -20px #000;max-width:360px;width:100%}}
.kick{{font-size:10px;letter-spacing:.4em;text-transform:uppercase;color:#ff5a1f}}
.title{{font-size:22px;font-weight:800;letter-spacing:-.02em;margin-top:6px}}
.title em{{font-style:normal;color:#6e6c66}}
.qr{{background:#fff;border-radius:12px;padding:14px;display:flex}}
.url{{background:#0d0d0f;border:1px solid #1c1b19;border-radius:8px;
  padding:8px 12px;font-size:10px;color:#46d6cf;word-break:break-all;text-align:center;width:100%}}
.note{{font-size:9px;color:#6e6c66;text-align:center;line-height:1.6}}
.note b{{color:#f4f2ee;font-weight:normal}}
</style></head><body>
<div class="card">
  <div style="text-align:center">
    <div class="kick">rabbit r1 · creation</div>
    <div class="title">voice <em>/ openrouter</em></div>
  </div>
  <div class="qr">{svg}</div>
  <div class="url">{creation_url}</div>
  <div class="note">
    Open this page via your <b>Pinggy URL</b> so the QR encodes the right address.<br>
    Delete the old voice creation first, then scan.<br>
    A new pairing code is shown in the daemon logs (SIGUSR1 to refresh).
  </div>
</div></body></html>"""
        return aweb.Response(text=html, content_type="text/html")

    async def _route_ask(self, request):
        await self._require_auth(request)
        result = await self._voice.handle_ask(request)
        return aweb.json_response(result)

    async def http_loop(self):
        if not HAS_HTTP:
            _log("ERROR", "aiohttp not installed — HTTP server disabled. Run: pip install aiohttp", logging.WARNING)
            return

        @aweb.middleware
        async def _ngrok_headers(request, handler):
            resp = await handler(request)
            resp.headers["ngrok-skip-browser-warning"] = "1"
            return resp

        app = aweb.Application(middlewares=[_ngrok_headers])
        app.router.add_get( "/",           self._route_creation)
        app.router.add_get( "/qr",         self._route_qr)
        app.router.add_get( "/test",       self._route_test)
        app.router.add_get( "/auto-token", self._route_auto_token)
        app.router.add_post("/pair",       self._route_pair)
        app.router.add_get( "/meter",      self._route_meter)
        app.router.add_get( "/status",     self._route_status)
        app.router.add_post("/decision",   self._route_decision)
        app.router.add_post("/clear-chat", self._route_clear_chat)
        # Voice creation
        app.router.add_get( "/voice",    self._route_voice)
        app.router.add_get( "/voice/qr", self._route_voice_qr)
        app.router.add_post("/ask",      self._route_ask)

        # Open pairing window on startup and log the code
        code = self._token_store.open_window()
        _log("INFO",    f"Meter:     http://localhost:{HTTP_PORT}/     qr → /qr")
        _log("INFO",    f"Voice:     http://localhost:{HTTP_PORT}/voice  qr → /voice/qr")
        if not VOICE_MODEL or "OPENROUTER_KEY" not in os.environ:
            _log("INFO", f"Voice LLM: set OPENROUTER_KEY env var to enable")
        # Pre-load Whisper in background so it's ready on first /ask
        asyncio.get_event_loop().run_in_executor(None, self._voice.load_whisper)

        loop = asyncio.get_event_loop()

        def _print_pair_code(c: str):
            bar = f"{BOLD}{CYAN}{'─' * 38}{R}"
            print(f"\n{bar}")
            print(f"  {BOLD}{CYAN}PAIRING CODE →  {GREEN}{c}{R}  {CYAN}(valid {PAIRING_WINDOW_S//60}m){R}")
            print(f"  {DIM}SIGUSR1 to get a new code · pairs meter + voice{R}")
            print(f"{bar}\n", flush=True)

        _print_pair_code(code)

        # Re-print every 30 s while the window is still open so it doesn't scroll away
        async def _pair_code_reminder():
            while True:
                await asyncio.sleep(30)
                if self._token_store.window_open:
                    _print_pair_code(self._token_store._code)

        asyncio.create_task(_pair_code_reminder())

        # SIGUSR1 opens a fresh pairing window without restarting
        def _reopen_pair():
            new_code = self._token_store.open_window()
            _print_pair_code(new_code)
        loop.add_signal_handler(signal.SIGUSR1, _reopen_pair)

        runner = aweb.AppRunner(app)
        await runner.setup()
        site = aweb.TCPSite(runner, "0.0.0.0", HTTP_PORT)
        await site.start()
        while True:
            await asyncio.sleep(3600)

    # ── Main ──────────────────────────────────────────────────────────────────

    async def run(self):
        print(f"\n{BOLD}Claude Buddy{R}  {DIM}HTTP + hooks bridge{R}\n"
              f"  Socket  {DIM}{SOCKET_PATH}{R}\n"
              f"  Tip     supports multiple simultaneous Claude Code sessions\n")
        await asyncio.gather(
            self.socket_loop(),
            self.chat_follow_loop(),
            self.http_loop(),
            self.meter_poll_loop(),
        )


if __name__ == "__main__":
    verbose = "--verbose" in sys.argv or "-v" in sys.argv
    try: asyncio.run(BuddyDaemon(verbose=verbose).run())
    except KeyboardInterrupt: print(f"\n{DIM}Stopped.{R}")
