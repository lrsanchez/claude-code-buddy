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
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

from bleak import BleakScanner, BleakClient
from bleak.exc import BleakError

# ── NUS UUIDs ─────────────────────────────────────────────────────────────────
NUS_SERVICE   = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
NUS_RX        = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # daemon writes snapshots here
NUS_TX        = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # device notifies here (best-effort)
DECISION_CHAR = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"  # daemon polls here for Approve/Deny

SOCKET_PATH    = "/tmp/claude-buddy.sock"
SESSION_CACHE  = "/tmp/claude-buddy-sessions.json"
HEARTBEAT_S    = 3
BLE_CHUNK      = 20
SCAN_TIMEOUT   = 30.0
PROMPT_TIMEOUT = 120.0
CHAT_POLL_S    = 1.0
MAX_CHAT       = 25
MAX_ENTRIES    = 15

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
    tokens: int          = 0
    tokens_today: int    = 0
    session_start_tokens: int = 0
    pending: Optional[PendingPrompt] = None
    active_prompt: Optional[dict] = None  # kept in every snapshot while waiting

# ── Transcript helpers ────────────────────────────────────────────────────────

def _text(content) -> str:
    if isinstance(content, str): return content.strip()
    if isinstance(content, list):
        for b in content:
            if isinstance(b, dict) and b.get("type") == "text":
                return b.get("text","").strip()
    return ""

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
                    name = b.get("name","?")
                    inp  = b.get("input",{})
                    hint = str(inp.get("command") or inp.get("file_path") or
                               inp.get("description") or inp.get("prompt") or "")[:60].replace("\n"," ")
                    ts_raw = obj.get("timestamp","")
                    ts = ts_raw[11:16] if len(ts_raw) >= 16 else time.strftime("%H:%M")
                    entries.append(f"{ts} {name}" + (f": {hint}" if hint else ""))
    except (OSError, IOError): pass
    session.entries      = list(reversed(entries))[:MAX_ENTRIES]
    session.tokens       = tokens
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

# ── Daemon ────────────────────────────────────────────────────────────────────

class BuddyDaemon:
    def __init__(self, verbose: bool = False):
        _setup(verbose)
        self._sessions: dict[str, Session] = {}
        self._global_chat: list = []      # all sessions merged, newest first
        self.ble_client: Optional[BleakClient] = None
        self._rx_buf    = ""
        self._send_lock = asyncio.Lock()
        self._start_time  = time.time()
        self._approve_cnt = 0
        self._deny_cnt    = 0
        self._restore_sessions()

    def _restore_sessions(self):
        """Reload transcript paths from last run so history shows on reconnect."""
        try:
            with open(SESSION_CACHE) as f:
                for entry in json.load(f):
                    sid   = entry.get("id", "")
                    path  = entry.get("transcript_path", "")
                    short = entry.get("short_id", sid[-4:].upper() if len(sid) >= 4 else sid)
                    if sid and path and os.path.exists(path):
                        s = Session(id=sid, short_id=short, transcript_path=path)
                        refresh_entries(s)
                        s.session_start_tokens = 0
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
        if transcript_path and transcript_path != s.transcript_path:
            s.transcript_path = transcript_path
            s.transcript_pos  = 0
            self._persist_sessions()
        return s

    def _aggregate(self) -> dict:
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
        return {
            "total": len(sessions),
            "running": running,
            "waiting": waiting,
            "msg": msg,
            "entries": entries[:MAX_ENTRIES],
            "tokens": tokens,
            "tokens_today": today,
            "chat": self._global_chat[:MAX_CHAT],
        }

    # ── BLE ───────────────────────────────────────────────────────────────────

    async def ble_loop(self):
        while True:
            try: await self._connect_and_run()
            except (BleakError, asyncio.TimeoutError, OSError) as e:
                _log("DISCONNECT", f"BLE dropped ({e!r}) — retrying in 10 s", logging.WARNING)
                self.ble_client = None
                await asyncio.sleep(10)

    async def _connect_and_run(self):
        _log("SCAN", "Scanning for Claude Buddy…")
        device = await BleakScanner.find_device_by_filter(
            lambda d, _: bool(d.name and d.name.startswith("Claude")),
            timeout=SCAN_TIMEOUT,
        )
        if device is None:
            _log("SCAN", "No Claude* device found — will retry", logging.WARNING); return
        _log("CONNECT", f"Found {BOLD}{device.name}{R}  {DIM}({device.address}){R}")
        # Brief pause so Android's GATT server is fully ready before we start
        # service discovery — avoids "failed to discover services" on rapid reconnects
        await asyncio.sleep(1.5)
        async with BleakClient(device, disconnected_callback=lambda _: self._on_disc(), timeout=30.0) as client:
            self.ble_client = client
            await client.start_notify(NUS_TX, self._on_notify)
            _log("CONNECT", f"Connected — heartbeats every {HEARTBEAT_S} s")
            await self._send_snapshot()
            while client.is_connected:
                await asyncio.sleep(HEARTBEAT_S)
                await self._send_snapshot()

    def _on_disc(self):
        _log("DISCONNECT", "BLE disconnected"); self.ble_client = None

    def _on_notify(self, _s, data: bytearray):
        self._rx_buf += data.decode("utf-8", errors="replace")
        while "\n" in self._rx_buf:
            line, self._rx_buf = self._rx_buf.split("\n", 1)
            line = line.strip()
            if line: asyncio.create_task(self._handle_rx(line))

    async def _handle_rx(self, line: str):
        logging.getLogger("buddy").debug(f"RX: {line}")
        try: msg = json.loads(line)
        except: return
        cmd = msg.get("cmd")
        if cmd == "permission":
            pid, dec = msg.get("id",""), msg.get("decision","deny")
            for s in self._sessions.values():
                if s.pending and s.pending.id == pid and not s.pending.future.done():
                    s.pending.future.set_result(dec); break
        elif cmd == "status":
            await self._send_line(self._status_ack())
        elif cmd in ("name","owner","unpair"):
            await self._send_line(json.dumps({"ack": cmd, "ok": True}))

    async def _send_snapshot(self, prompt: Optional[dict] = None):
        if not self.ble_client or not self.ble_client.is_connected: return
        for s in self._sessions.values():
            if s.transcript_path: refresh_entries(s)
        snap = self._aggregate()
        # Use explicitly passed prompt, or the stored active_prompt from any waiting session
        active = prompt or next(
            (s.active_prompt for s in self._sessions.values() if s.active_prompt), None
        )
        if active: snap["prompt"] = active
        await self._send_line(json.dumps(snap))

    async def _send_line(self, line: str):
        if not self.ble_client or not self.ble_client.is_connected: return
        data = (line + "\n").encode("utf-8")
        async with self._send_lock:
            for i in range(0, len(data), BLE_CHUNK):
                try: await self.ble_client.write_gatt_char(NUS_RX, data[i:i+BLE_CHUNK], response=True)
                except BleakError as e:
                    _log("ERROR", f"Write: {e}", logging.WARNING); break

    def _status_ack(self) -> str:
        import resource
        return json.dumps({
            "ack":"status","ok":True,"data":{
                "name":"Claude CLI","sec":False,
                "sys":{"up":int(time.time()-self._start_time),
                       "heap":resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024},
                "stats":{"appr":self._approve_cnt,"deny":self._deny_cnt,
                         "lvl": sum(s.tokens for s in self._sessions.values())//50_000},
            },
        })

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
                        if multi: entry["session"] = session.short_id
                        self._global_chat.insert(0, entry)
                        who = "You" if e["role"]=="user" else "Claude"
                        icon = "CHAT_USER" if e["role"]=="user" else "CHAT_AI"
                        tag = f" {DIM}[{session.short_id}]{R}" if multi else ""
                        _log(icon, f"{BOLD}{who}{R}{tag}: {DIM}{e['text'][:70]}{R}")
                    self._global_chat = self._global_chat[:MAX_CHAT]
                except Exception as exc:
                    logging.getLogger("buddy").debug(f"chat_follow [{session.short_id}]: {exc}")
            if changed: await self._send_snapshot()

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
        await self._send_snapshot()

    async def _user_submit(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        s.running = True   # User sent a message, Claude is now generating
        _log("SUBMIT", f"Session {BOLD}{s.short_id}{R} — generating response")
        await self._send_snapshot()

    async def _poll_decision(self, prompt_id: str, future: asyncio.Future):
        """Poll DECISION_CHAR every 500ms until we get a matching decision or future resolves."""
        POLL_MS = 0.5
        while not future.done():
            try:
                if self.ble_client and self.ble_client.is_connected:
                    data = await self.ble_client.read_gatt_char(DECISION_CHAR)
                    text = data.decode("utf-8", errors="replace").strip()
                    if text:
                        msg = json.loads(text)
                        if msg.get("id") == prompt_id and not future.done():
                            logging.getLogger("buddy").debug(f"DECISION poll hit: {text}")
                            future.set_result(msg.get("decision", "deny"))
                            return
            except asyncio.CancelledError:
                return
            except Exception as e:
                logging.getLogger("buddy").debug(f"poll error: {e}")
            await asyncio.sleep(POLL_MS)

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
        await self._send_snapshot(prompt=prompt)

        # Run BLE READ polling concurrently with notification listener
        poll_task = asyncio.create_task(self._poll_decision(pid, future))
        try:
            decision = await asyncio.wait_for(future, timeout=PROMPT_TIMEOUT)
        except asyncio.TimeoutError:
            decision = "deny"
            _log("DENY", f"Timeout — auto-denied {tool}", logging.WARNING)
        finally:
            poll_task.cancel()
            s.pending       = None
            s.active_prompt = None  # clear so heartbeats stop sending the prompt

        if decision == "once":
            self._approve_cnt += 1
            _log("APPROVE", f"{GREEN}{BOLD}{tool}{R}{tag}  {DIM}[✔{self._approve_cnt} ✘{self._deny_cnt}]{R}")
            result = {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}
        else:
            self._deny_cnt += 1
            _log("DENY", f"{RED}{BOLD}{tool}{R}{tag}  {DIM}[✔{self._approve_cnt} ✘{self._deny_cnt}]{R}")
            result = {"hookSpecificOutput":{
                "hookEventName":"PreToolUse","permissionDecision":"deny",
                "permissionDecisionReason":"Denied via Claude Buddy",
            }}
        # Tool is executing (approved) or Claude will respond to denial — still running
        s.waiting = False
        s.running = True
        await self._send_snapshot()
        return result

    async def _post_tool(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        s.waiting = False
        s.running = True  # Claude is processing the tool result, still running
        await self._send_snapshot()

    async def _notification(self, msg: dict):
        text = msg.get("message","")
        if text:
            ts = time.strftime("%H:%M")
            sid = msg.get("session_id","")
            s = self._sessions.get(sid)
            if s: s.entries = [f"{ts} ◈ {text[:60]}"] + s.entries[:MAX_ENTRIES-1]
        await self._send_snapshot()

    async def _stop(self, msg: dict):
        sid = msg.get("session_id","unknown")
        s   = self._get_session(sid, msg.get("transcript_path",""))
        s.running = False  # Claude finished this turn
        s.waiting = False
        await self._send_snapshot()

    # ── Main ──────────────────────────────────────────────────────────────────

    # ── HTTP decision endpoint ────────────────────────────────────────────────

    # ── Main ──────────────────────────────────────────────────────────────────

    async def run(self):
        print(f"\n{BOLD}Claude Buddy{R}  {DIM}BLE ↔ CLI bridge{R}\n"
              f"  Socket  {DIM}{SOCKET_PATH}{R}\n"
              f"  Tip     supports multiple simultaneous Claude Code sessions\n")
        await asyncio.gather(self.ble_loop(), self.socket_loop(), self.chat_follow_loop())


if __name__ == "__main__":
    verbose = "--verbose" in sys.argv or "-v" in sys.argv
    try: asyncio.run(BuddyDaemon(verbose=verbose).run())
    except KeyboardInterrupt: print(f"\n{DIM}Stopped.{R}")
