#!/usr/bin/env python3
"""
Claude Buddy Voice — standalone server for VPS deployment.

Serves the R1 voice creation and handles STT → LLM → TTS.
No BLE, no Claude Code hooks — voice only.

Endpoints:
  GET  /voice      — R1 creation HTML
  GET  /voice/qr   — install QR code
  GET  /auto-token — pre-shared token (BUDDY_TOKEN in .env)
  POST /pair       — 6-digit pairing handshake
  POST /ask        — voice request (auth required)

Environment (.env or shell):
  OPENROUTER_KEY     required
  BUDDY_TOKEN        pre-shared token — skips pairing on creation open
  TTS_VOICE          edge-tts voice  (default: en-US-AriaNeural)
  VOICE_MODEL        OpenRouter model (default: perplexity/sonar)
  VOICE_HTTP_PORT    HTTP port        (default: 7701)
  VOICE_PUBLIC_URL   public HTTPS URL for QR (auto-detected from request if unset)
"""

import asyncio
import io
import json
import logging
import os
import random
import re
import secrets
import signal
import sys
import time
from typing import Optional

from aiohttp import web as aweb

from voice_handler import VoiceHandler

# ── config ────────────────────────────────────────────────────────────────────

HTTP_PORT        = int(os.environ.get("VOICE_HTTP_PORT", "7701"))
BUDDY_TOKEN      = os.environ.get("BUDDY_TOKEN", "")
PUBLIC_URL       = os.environ.get("VOICE_PUBLIC_URL", "")   # override for QR
PAIRING_WINDOW_S = 300
PAIRING_MAX_TRIES = 5
TOKEN_FILE       = os.path.expanduser("~/.local/share/claude-buddy/voice-tokens.json")
VOICE_HTML       = os.path.join(os.path.dirname(__file__), "r1-voice.html")

R    = "\033[0m";  BOLD = "\033[1m";  DIM = "\033[2m"
GREEN = "\033[32m"; CYAN = "\033[36m"

# ── logging ───────────────────────────────────────────────────────────────────

logging.basicConfig(
    level  = logging.INFO,
    format = "%(asctime)s.%(msecs)03d  %(message)s",
    datefmt = "%H:%M:%S",
)
log = logging.getLogger("voice")


# ── token store ───────────────────────────────────────────────────────────────

class TokenStore:
    def __init__(self, path: str):
        self._path = path
        self._tokens: set = set()
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
        self._code = f"{random.randint(0, 999_999):06d}"
        self._code_expires = time.time() + PAIRING_WINDOW_S
        self._code_attempts = 0
        return self._code

    @property
    def window_open(self) -> bool:
        return bool(self._code) and time.time() < self._code_expires

    def try_pair(self, code: str) -> Optional[str]:
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
        self._save()
        return token

    def is_valid(self, token: str) -> bool:
        return token in self._tokens

    def register(self, token: str):
        self._tokens.add(token)
        self._save()


# ── QR helper ─────────────────────────────────────────────────────────────────

def make_qr_svg(data: str) -> str:
    try:
        import qrcode
        from qrcode.image.svg import SvgPathImage
        qr = qrcode.QRCode(
            version=None,
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=10, border=3, image_factory=SvgPathImage,
        )
        qr.add_data(data)
        qr.make(fit=True)
        buf = io.BytesIO()
        qr.make_image().save(buf)
        svg = buf.getvalue().decode("utf-8")
        svg = svg.replace("<?xml version='1.0' encoding='UTF-8'?>", "").strip()
        svg = re.sub(r'width="[^"]+" height="[^"]+"', 'width="220" height="220"', svg)
        return svg
    except Exception as e:
        return f'<text fill="#ff5a1f" font-size="12" x="4" y="20">QR error: {e}</text>'


# ── server ────────────────────────────────────────────────────────────────────

class VoiceServer:

    def __init__(self):
        self._tokens = TokenStore(TOKEN_FILE)
        if BUDDY_TOKEN:
            self._tokens.register(BUDDY_TOKEN)
        self._voice = VoiceHandler()

    def _creation_url(self, request) -> str:
        if PUBLIC_URL:
            return PUBLIC_URL.rstrip("/") + "/voice"
        proto = request.headers.get("X-Forwarded-Proto", "http")
        host  = (request.headers.get("X-Forwarded-Host")
                 or request.headers.get("Host")
                 or f"localhost:{HTTP_PORT}")
        return f"{proto}://{host}/voice"

    async def _require_auth(self, request):
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            raise aweb.HTTPUnauthorized()
        if not self._tokens.is_valid(auth[7:].strip()):
            raise aweb.HTTPForbidden()

    # ── routes ────────────────────────────────────────────────────────────────

    async def route_creation(self, request):
        try:
            with open(VOICE_HTML, "rb") as f:
                return aweb.Response(body=f.read(), content_type="text/html")
        except OSError:
            return aweb.Response(text="r1-voice.html not found", status=404)

    async def route_qr(self, request):
        url = self._creation_url(request)
        payload = json.dumps({
            "title":       "Claude Buddy Voice",
            "url":         url + "?v=1",
            "description": "Push-to-talk AI assistant via OpenRouter",
            "themeColor":  "#ff5a1f",
        }, separators=(",", ":"))
        svg  = make_qr_svg(payload)
        html = f"""<!DOCTYPE html><html><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>voice · install</title>
<style>
body{{background:#070708;color:#f4f2ee;font-family:ui-monospace,monospace;
  display:flex;align-items:center;justify-content:center;min-height:100vh}}
.card{{display:flex;flex-direction:column;align-items:center;gap:20px;
  padding:40px;background:#0a0a0c;border:1px solid #1c1b19;border-radius:20px;
  max-width:360px;width:100%}}
.kick{{font-size:10px;letter-spacing:.4em;text-transform:uppercase;color:#ff5a1f}}
.title{{font-size:22px;font-weight:800;letter-spacing:-.02em;margin-top:6px}}
.qr{{background:#fff;border-radius:12px;padding:14px;display:flex}}
.url{{background:#0d0d0f;border:1px solid #1c1b19;border-radius:8px;
  padding:8px 12px;font-size:10px;color:#46d6cf;word-break:break-all;text-align:center;width:100%}}
.note{{font-size:9px;color:#6e6c66;text-align:center;line-height:1.6}}
.note b{{color:#f4f2ee;font-weight:normal}}
</style></head><body>
<div class="card">
  <div style="text-align:center">
    <div class="kick">rabbit r1 · creation</div>
    <div class="title">voice</div>
  </div>
  <div class="qr">{svg}</div>
  <div class="url">{url}</div>
  <div class="note">
    Open this page via your <b>public URL</b> so the QR encodes the right address.<br>
    A pairing code is printed in the server logs.
  </div>
</div></body></html>"""
        return aweb.Response(text=html, content_type="text/html")

    async def route_auto_token(self, request):
        if not BUDDY_TOKEN:
            return aweb.Response(status=404)
        return aweb.json_response({"token": BUDDY_TOKEN})

    async def route_pair(self, request):
        try:
            data = await request.json()
        except Exception:
            return aweb.Response(text="bad json", status=400)
        token = self._tokens.try_pair(str(data.get("code", "")).strip())
        if not token:
            return aweb.json_response({"ok": False, "error": "invalid code or window closed"}, status=403)
        log.info("R1 paired — token issued")
        return aweb.json_response({"ok": True, "token": token})

    async def route_ask(self, request):
        await self._require_auth(request)
        try:
            result = await self._voice.handle_ask(request)
            return aweb.json_response(result)
        except Exception as e:
            log.exception(f"route_ask unhandled error: {e}")
            return aweb.json_response({"ok": False, "error": "server error"}, status=500)

    # ── main ──────────────────────────────────────────────────────────────────

    def _print_code(self, code: str):
        bar = f"{BOLD}{CYAN}{'─' * 42}{R}"
        print(f"\n{bar}")
        print(f"  {BOLD}{CYAN}PAIRING CODE →  {GREEN}{code}{R}  {CYAN}(valid {PAIRING_WINDOW_S//60}m){R}")
        print(f"  {DIM}SIGUSR1 to get a new code{R}")
        print(f"{bar}\n", flush=True)

    async def run(self):
        @aweb.middleware
        async def ngrok_headers(request, handler):
            resp = await handler(request)
            resp.headers["ngrok-skip-browser-warning"] = "1"
            return resp

        app = aweb.Application(middlewares=[ngrok_headers])
        app.router.add_get( "/voice",       self.route_creation)
        app.router.add_get( "/voice/qr",    self.route_qr)
        app.router.add_get( "/auto-token",  self.route_auto_token)
        app.router.add_post("/pair",         self.route_pair)
        app.router.add_post("/ask",          self.route_ask)

        # Open pairing window and print code
        code = self._tokens.open_window()
        log.info(f"Voice server on port {HTTP_PORT}")
        log.info(f"QR: http://localhost:{HTTP_PORT}/voice/qr  (open via public URL)")
        self._print_code(code)

        # Reprint code every 30s while window is open
        async def _reminder():
            while True:
                await asyncio.sleep(30)
                if self._tokens.window_open and self._tokens._code:
                    self._print_code(self._tokens._code)
        asyncio.create_task(_reminder())

        # SIGUSR1 reopens pairing window
        loop = asyncio.get_event_loop()
        loop.add_signal_handler(signal.SIGUSR1, lambda: self._print_code(self._tokens.open_window()))

        # Pre-load Whisper in background
        loop.run_in_executor(None, self._voice.load_whisper)

        runner = aweb.AppRunner(app)
        await runner.setup()
        await aweb.TCPSite(runner, "0.0.0.0", HTTP_PORT).start()
        log.info("Ready.")
        while True:
            await asyncio.sleep(3600)


if __name__ == "__main__":
    verbose = "--verbose" in sys.argv or "-v" in sys.argv
    if verbose:
        logging.getLogger().setLevel(logging.DEBUG)
    try:
        asyncio.run(VoiceServer().run())
    except KeyboardInterrupt:
        print(f"\n{DIM}Stopped.{R}")
