#!/usr/bin/env python3
"""Claude Buddy — Divoom Times Gate widgets (pixel-art style).

Renders up to three widgets on a 64×64 canvas with a 5×7 pixel font, then
nearest-neighbor upscales ×2 to the Times Gate's 128×128 LCDs — proper chunky
Divoom pixel-art, no antialiasing. Refreshes every TIMESGATE_INTERVAL s:

  meter   — Claude usage rings: session 5h % + weekly 7d % (from /status)
  tokens  — output tokens today + active session counts (from /status)
  beeper  — unread chats from Beeper Desktop's local API (localhost:23373)

Times Gate API quirks (differ from the Pixoo-64):
  * every request needs an integer LocalToken (Divoom app → device settings),
    otherwise the device answers {"error_code": "DeviceToken is err"}
  * Draw/SendHttpGif PicData is base64 JPEG (not raw RGB), PicWidth 128
  * LcdArray (length 5) selects the target screen, e.g. [1,0,0,0,0]
  * PicID must increase monotonically; reset once via Draw/ResetHttpGifId

Config via environment / bridge/.env:
  TIMESGATE_TOKEN       required — LocalToken from the Divoom app
  TIMESGATE_IP          optional — device IP (auto-discovered if unset)
  TIMESGATE_LCD_METER   optional — screen 0-4 for the usage rings (default 1)
  TIMESGATE_LCD_TOKENS  optional — screen for tokens today (default 2)
  TIMESGATE_LCD_BEEPER  optional — screen for Beeper unreads (default 3)
  TIMESGATE_INTERVAL    optional — refresh seconds (default 30)
  BUDDY_TOKEN           required — daemon auth for /status
  BUDDY_HTTP_PORT       optional — daemon port (default 7700)

Beeper auth: bridge/.beeper_token.json (from the OAuth PKCE approval flow)
is preferred; falls back to BEEPER_ACCESS_TOKEN. Widget skipped if neither.

Usage:
  python3 timesgate_widget.py                     # run the push loop
  python3 timesgate_widget.py --once              # single push, then exit
  python3 timesgate_widget.py --preview DIR [--demo]  # render PNGs only
"""

import base64
import io
import json
import os
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

from PIL import Image, ImageDraw

SIZE   = 128   # device LCD resolution
CANVAS = 64    # internal pixel-art canvas, upscaled ×2

# Palette — matches the app/R1 meter: true black, Claude coral, severity ramp
BLACK  = (0, 0, 0)
CORAL  = (217, 119, 87)
NEON   = (0, 255, 65)   # Matrix green — widget titles
GRAY   = (110, 110, 110)
TRACK  = (38, 38, 42)
WHITE  = (235, 235, 235)
TEAL   = (45, 212, 191)
ORANGE = (251, 146, 60)
AMBER  = (245, 158, 11)
RED    = (239, 68, 68)


def severity_color(pct: int):
    if pct >= 90: return RED
    if pct >= 80: return AMBER
    if pct >= 60: return ORANGE
    return TEAL


# ── 5×7 pixel font (proportional via column trimming) ─────────────────────────

_FONT = {
    "0": (0x0E,0x11,0x13,0x15,0x19,0x11,0x0E), "1": (0x04,0x0C,0x04,0x04,0x04,0x04,0x0E),
    "2": (0x0E,0x11,0x01,0x02,0x04,0x08,0x1F), "3": (0x1F,0x02,0x04,0x02,0x01,0x11,0x0E),
    "4": (0x02,0x06,0x0A,0x12,0x1F,0x02,0x02), "5": (0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E),
    "6": (0x06,0x08,0x10,0x1E,0x11,0x11,0x0E), "7": (0x1F,0x01,0x02,0x04,0x08,0x08,0x08),
    "8": (0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E), "9": (0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C),
    "A": (0x0E,0x11,0x11,0x1F,0x11,0x11,0x11), "B": (0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E),
    "C": (0x0E,0x11,0x10,0x10,0x10,0x11,0x0E), "D": (0x1C,0x12,0x11,0x11,0x11,0x12,0x1C),
    "E": (0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F), "F": (0x1F,0x10,0x10,0x1E,0x10,0x10,0x10),
    "G": (0x0E,0x11,0x10,0x17,0x11,0x11,0x0F), "H": (0x11,0x11,0x11,0x1F,0x11,0x11,0x11),
    "I": (0x0E,0x04,0x04,0x04,0x04,0x04,0x0E), "J": (0x07,0x02,0x02,0x02,0x02,0x12,0x0C),
    "K": (0x11,0x12,0x14,0x18,0x14,0x12,0x11), "L": (0x10,0x10,0x10,0x10,0x10,0x10,0x1F),
    "M": (0x11,0x1B,0x15,0x15,0x11,0x11,0x11), "N": (0x11,0x11,0x19,0x15,0x13,0x11,0x11),
    "O": (0x0E,0x11,0x11,0x11,0x11,0x11,0x0E), "P": (0x1E,0x11,0x11,0x1E,0x10,0x10,0x10),
    "Q": (0x0E,0x11,0x11,0x11,0x15,0x12,0x0D), "R": (0x1E,0x11,0x11,0x1E,0x14,0x12,0x11),
    "S": (0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E), "T": (0x1F,0x04,0x04,0x04,0x04,0x04,0x04),
    "U": (0x11,0x11,0x11,0x11,0x11,0x11,0x0E), "V": (0x11,0x11,0x11,0x11,0x11,0x0A,0x04),
    "W": (0x11,0x11,0x11,0x15,0x15,0x15,0x0A), "X": (0x11,0x11,0x0A,0x04,0x0A,0x11,0x11),
    "Y": (0x11,0x11,0x11,0x0A,0x04,0x04,0x04), "Z": (0x1F,0x01,0x02,0x04,0x08,0x10,0x1F),
    "%": (0x18,0x19,0x02,0x04,0x08,0x13,0x03), ".": (0x00,0x00,0x00,0x00,0x00,0x0C,0x0C),
    "+": (0x00,0x04,0x04,0x1F,0x04,0x04,0x00), "-": (0x00,0x00,0x00,0x1F,0x00,0x00,0x00),
    "·": (0x00,0x00,0x0C,0x0C,0x00,0x00,0x00), "&": (0x08,0x14,0x14,0x08,0x15,0x12,0x0D),
    "#": (0x0A,0x0A,0x1F,0x0A,0x1F,0x0A,0x0A), "/": (0x01,0x01,0x02,0x04,0x08,0x10,0x10),
    "!": (0x04,0x04,0x04,0x04,0x04,0x00,0x04), "?": (0x0E,0x11,0x01,0x02,0x04,0x00,0x04),
    ":": (0x00,0x0C,0x0C,0x00,0x0C,0x0C,0x00), ",": (0x00,0x00,0x00,0x00,0x0C,0x04,0x08),
    " ": (0x00,0x00,0x00,0x00,0x00,0x00,0x00),
}

_glyph_cache: dict = {}


def _glyph(ch):
    """Return (rows, left, width) with empty side columns trimmed."""
    if ch in _glyph_cache:
        return _glyph_cache[ch]
    rows = _FONT.get(ch)
    if rows is None:
        _glyph_cache[ch] = None
        return None
    mask = 0
    for r in rows:
        mask |= r
    if mask == 0:
        g = (rows, 0, 3)  # space
    else:
        left = 0
        while not mask & (0x10 >> left):
            left += 1
        right = 4
        while not mask & (0x10 >> right):
            right -= 1
        g = (rows, left, right - left + 1)
    _glyph_cache[ch] = g
    return g


def _clean(s: str) -> str:
    """Uppercase + fold to renderable glyphs; keep non-ascii chars (like ·)
    that the font knows, drop accents/emoji it doesn't."""
    out = []
    for ch in unicodedata.normalize("NFKD", s):
        u = ch.upper()
        if u in _FONT:
            out.append(u)
        else:
            a = ch.encode("ascii", "ignore").decode()
            if a:
                out.append(a.upper())
    return "".join(out)


def ptext_w(s: str, scale: int = 1) -> int:
    s = _clean(s)
    w = 0
    for ch in s:
        g = _glyph(ch)
        if g is None:
            continue
        w += (g[2] + 1) * scale
    return w - scale if w else 0


def ptext(d: ImageDraw.ImageDraw, x: int, y: int, s: str, color, scale: int = 1) -> int:
    """Draw pixel text; returns the x after the last glyph."""
    for ch in _clean(s):
        g = _glyph(ch)
        if g is None:
            continue
        rows, left, width = g
        for ry, bits in enumerate(rows):
            for cx in range(width):
                if bits & (0x10 >> (left + cx)):
                    px, py = x + cx * scale, y + ry * scale
                    if scale == 1:
                        d.point((px, py), fill=color)
                    else:
                        d.rectangle([px, py, px + scale - 1, py + scale - 1], fill=color)
        x += (width + 1) * scale
    return x


def ptext_center(d, cx, y, s, color, scale: int = 1):
    ptext(d, cx - ptext_w(s, scale) // 2, y, s, color, scale)


def _truncate(s: str, max_w: int, scale: int = 1) -> str:
    if ptext_w(s, scale) <= max_w:
        return s
    while s and ptext_w(s + ".", scale) > max_w:
        s = s[:-1]
    return s + "."


def _ring(draw, cx, cy, radius, stroke, pct, color):
    box = [cx - radius, cy - radius, cx + radius, cy + radius]
    draw.arc(box, 0, 360, fill=TRACK, width=stroke)
    if pct > 0:
        draw.arc(box, -90, -90 + 360 * min(pct, 100) / 100, fill=color, width=stroke)


def _fmt_reset(minutes: int) -> str:
    if minutes <= 0: return ""
    d, rem = divmod(minutes, 1440)
    h, m = divmod(rem, 60)
    if d > 0: return f"{d}D{h}H"
    if h > 0: return f"{h}H{m:02d}"
    return f"{m}M"


def _fmt_tokens(n: int) -> str:
    if n >= 10_000_000: return f"{n/1e6:.1f}M"
    if n >= 1_000_000:  return f"{n/1e6:.2f}M"
    if n >= 100_000:    return f"{n/1e3:.0f}K"
    if n >= 10_000:     return f"{n/1e3:.1f}K"
    return str(n)


def _blank(label: str):
    img = Image.new("RGB", (CANVAS, CANVAS), BLACK)
    d = ImageDraw.Draw(img)
    ptext_center(d, CANVAS // 2, 2, label, NEON)
    return img, d


def _waiting(d, msg: str):
    ptext_center(d, CANVAS // 2, 28, "· · ·", GRAY)
    ptext_center(d, CANVAS // 2, 44, msg, GRAY)


# ── Widgets (drawn at 64×64) ──────────────────────────────────────────────────

def render_meter(status: dict) -> Image.Image:
    img, d = _blank("CLAUDE")
    if status.get("s", -1) < 0:
        _waiting(d, "WAITING")
        return img

    rings = [
        (16, status["s"], status.get("sr", 0), "5H"),
        (48, status["w"], status.get("wr", 0), "7D"),
    ]
    for cx, pct, reset_min, label in rings:
        color = severity_color(pct)
        _ring(d, cx, 29, 13, 3, pct, color)
        pct_text = f"{pct}%" if pct < 100 else "100"
        ptext_center(d, cx, 26, pct_text, WHITE)
        ptext_center(d, cx, 46, label, color)
        reset = _fmt_reset(reset_min)
        if reset:
            ptext_center(d, cx, 56, reset, GRAY)
    return img


def render_tokens(status: dict) -> Image.Image:
    img, d = _blank("TODAY")
    text = _fmt_tokens(status.get("tokens_today", 0))
    scale = 2 if ptext_w(text, 2) <= CANVAS - 2 else 1
    ptext_center(d, CANVAS // 2, 16, text, WHITE, scale)
    ptext_center(d, CANVAS // 2, 34, "TOKENS", GRAY)

    total   = status.get("total", 0)
    running = status.get("running", 0)
    waiting = status.get("waiting", 0)
    parts = [(f"{total}S", WHITE if total else GRAY),
             (f"{running}R", TEAL if running else GRAY)]
    if waiting > 0:
        parts.append((f"{waiting}W", AMBER))
    text_all = "·".join(t for t, _ in parts)
    x = CANVAS // 2 - ptext_w(text_all) // 2
    for i, (t, color) in enumerate(parts):
        x = ptext(d, x, 50, t, color)
        if i < len(parts) - 1:
            x = ptext(d, x, 50, "·", GRAY)
    return img


def render_beeper(chats, error: str = "") -> Image.Image:
    """chats: list of (title, unread_count), or None when unavailable."""
    img, d = _blank("BEEPER")
    if chats is None:
        _waiting(d, error or "OFFLINE")
        return img

    total = sum(c for _, c in chats)
    ptext_center(d, CANVAS // 2, 12, str(total), WHITE if total else GRAY, 2)
    ptext_center(d, CANVAS // 2, 29, "NEW MSGS" if total != 1 else "NEW MSG",
                 CORAL if total else GRAY)

    rows = chats[:3] if len(chats) <= 3 else chats[:2]
    y = 39
    for title, count in rows:
        cnt = str(count)
        cnt_w = ptext_w(cnt)
        name = _truncate(title, CANVAS - 4 - cnt_w - 4)
        ptext(d, 2, y, name, WHITE)
        ptext(d, CANVAS - 2 - cnt_w, y, cnt, CORAL)
        y += 9
    if len(chats) > 3:
        ptext_center(d, CANVAS // 2, y, f"+{len(chats) - 2} MORE", GRAY)
    return img


def jpeg_bytes(img: Image.Image) -> bytes:
    big = img.resize((SIZE, SIZE), Image.NEAREST)
    buf = io.BytesIO()
    big.save(buf, "JPEG", quality=95)
    return buf.getvalue()


# ── HTTP helpers (stdlib only) ────────────────────────────────────────────────

def _post_json(url: str, payload: dict, timeout: int = 8) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def _get_json(url: str, token: str, timeout: int = 8) -> dict:
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def discover_ip() -> str:
    """Find the first Times Gate on the LAN via the Divoom cloud."""
    data = _post_json("https://app.divoom-gz.com/Device/ReturnSameLANDevice", {})
    for dev in data.get("DeviceList", []):
        return dev["DevicePrivateIP"]
    raise RuntimeError("no Divoom device found on the LAN (set TIMESGATE_IP)")


class TimesGate:
    def __init__(self, ip: str, local_token: int):
        self.url = f"http://{ip}/post"
        self.token = local_token
        self.pic_id = 0

    def send(self, command: dict) -> dict:
        resp = _post_json(self.url, {**command, "LocalToken": self.token})
        err = resp.get("error_code")
        if err not in (0, None):
            raise RuntimeError(f"Times Gate rejected {command.get('Command')}: {err}")
        return resp

    def reset_pic_id(self):
        self.send({"Command": "Draw/ResetHttpGifId"})
        self.pic_id = 0

    def push_jpeg(self, jpg: bytes, screen: int):
        lcd = [0] * 5
        lcd[screen] = 1
        self.pic_id += 1
        self.send({
            "Command": "Draw/SendHttpGif",
            "LcdArray": lcd,
            "PicNum": 1,
            "PicWidth": SIZE,
            "PicOffset": 0,
            "PicID": self.pic_id,
            "PicSpeed": 1000,
            "PicData": base64.b64encode(jpg).decode(),
        })


def fetch_status(port: int, buddy_token: str) -> dict:
    return _get_json(f"http://localhost:{port}/status", buddy_token)


def fetch_beeper_unreads(token: str):
    """Unread, unmuted, unarchived chats → [(title, unreadCount), …] by recency."""
    q = urllib.parse.urlencode({"unreadOnly": "true", "limit": 30})
    data = _get_json(f"http://localhost:23373/v1/chats/search?{q}", token)
    chats = []
    for c in data.get("items", []):
        if c.get("isMuted") or c.get("isArchived"):
            continue
        n = c.get("unreadCount", 0)
        if n > 0:
            chats.append((c.get("title") or "?", n))
    return chats


def beeper_token() -> str:
    """Prefer bridge/.beeper_token.json (written by the OAuth flow) over the
    BEEPER_ACCESS_TOKEN env var, which may hold a stale token from ~/.bashrc."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".beeper_token.json")
    try:
        with open(path) as f:
            return json.load(f).get("access_token", "")
    except (OSError, ValueError):
        return os.environ.get("BEEPER_ACCESS_TOKEN", "")


def load_env():
    """Load bridge/.env so the script also works standalone (not via start-buddy.sh)."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.isfile(path):
        return
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            os.environ.setdefault(k.strip(), v.strip())


def main():
    load_env()
    port = int(os.environ.get("BUDDY_HTTP_PORT", 7700))
    buddy_token = os.environ.get("BUDDY_TOKEN", "")
    bp_token = beeper_token()

    if "--preview" in sys.argv:
        outdir = sys.argv[sys.argv.index("--preview") + 1]
        os.makedirs(outdir, exist_ok=True)
        if "--demo" in sys.argv:
            status = {"s": 42, "sr": 137, "w": 87, "wr": 3990,
                      "tokens_today": 3178220, "total": 5, "running": 3, "waiting": 1}
            chats = [("Maria", 3), ("dev-alerts", 12), ("Work group", 1), ("Bob", 2)]
        else:
            status = fetch_status(port, buddy_token)
            chats = fetch_beeper_unreads(bp_token) if bp_token else None
        for name, img in [("meter", render_meter(status)),
                          ("tokens", render_tokens(status)),
                          ("beeper", render_beeper(chats, "NO TOKEN"))]:
            img.resize((SIZE, SIZE), Image.NEAREST).save(os.path.join(outdir, f"tg_{name}.png"))
        print(f"previews → {outdir}/tg_*.png")
        return

    local_token = os.environ.get("TIMESGATE_TOKEN", "")
    if not local_token:
        sys.exit("TIMESGATE_TOKEN not set (Divoom app → device settings → LocalToken)")
    if not buddy_token:
        sys.exit("BUDDY_TOKEN not set")

    ip = os.environ.get("TIMESGATE_IP") or discover_ip()
    interval = int(os.environ.get("TIMESGATE_INTERVAL", 30))
    screens = {
        "meter":  int(os.environ.get("TIMESGATE_LCD_METER",
                      os.environ.get("TIMESGATE_LCD", 1))),
        "tokens": int(os.environ.get("TIMESGATE_LCD_TOKENS", 2)),
        "beeper": int(os.environ.get("TIMESGATE_LCD_BEEPER", 3)),
    }

    gate = TimesGate(ip, int(local_token))
    gate.reset_pic_id()
    print(f"Times Gate widgets → {ip} {screens}, every {interval}s", flush=True)

    last: dict = {}
    while True:
        try:
            status = fetch_status(port, buddy_token)
        except Exception as e:
            print(f"status fetch failed: {e}", flush=True)
            status = {"s": -1}

        if bp_token:
            try:
                chats = fetch_beeper_unreads(bp_token)
                beeper_img = render_beeper(chats)
            except Exception as e:
                print(f"beeper fetch failed: {e}", flush=True)
                err = "EXPIRED" if "401" in str(e) else "OFFLINE"
                beeper_img = render_beeper(None, err)
                bp_token = beeper_token()  # pick up a renewed token file live
        else:
            beeper_img = None  # widget disabled — leave the screen alone

        widgets = {"meter": render_meter(status), "tokens": render_tokens(status)}
        if beeper_img is not None:
            widgets["beeper"] = beeper_img

        for name, img in widgets.items():
            try:
                jpg = jpeg_bytes(img)
                if jpg != last.get(name):
                    gate.push_jpeg(jpg, screens[name])
                    last[name] = jpg
            except Exception as e:
                print(f"push {name} failed: {e}", flush=True)
                last.pop(name, None)  # force redraw on recovery
        if "--once" in sys.argv:
            break
        time.sleep(interval)


if __name__ == "__main__":
    main()
