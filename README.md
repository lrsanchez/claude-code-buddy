# Claude Code Buddy

A companion ecosystem for **Claude Code CLI** — live session monitoring, tool-call approvals, usage metering, and a voice assistant, all running on a Rabbit R1 and/or Android device.

![Claude Code Buddy](screenshot.png)

---

## What it does

### Android app
- **Live session state** — sessions running, waiting, token count, level — updating every 3 s via HTTP poll
- **Tool-call approvals** — every Bash command shows an approval card; tap **Approve** or **Deny**
- **Auto-approve toggle** — flip "manual → auto" in the top bar to approve all tools silently
- **Live conversation** — follow the chat between you and Claude as it happens
- **Tool history** — recent tool calls with timestamps
- **Usage rings** — session (5-hour) and weekly usage % pulled from the Anthropic API
- **Animated buddy** — expressive character reacting to session state
- **Portrait + landscape** — responsive layout with chat/tools panel toggle
- **Multiple sessions** — tracks all active Claude Code sessions, tagged by session ID

### Rabbit R1 — Usage Meter creation
- Two ring gauges: **session %** and **weekly %** with reset countdowns
- Severity coloring: teal → orange → amber → red (pulses at 90 %+)
- Auto-pairs via `BUDDY_TOKEN` — no code entry on first launch

### Rabbit R1 — Voice creation
- **Push-to-talk** voice assistant powered by OpenRouter (default: `perplexity/sonar` for real-time web search)
- **STT** via faster-whisper (local, runs on your machine or VPS)
- **TTS** via edge-tts (Microsoft Neural voices, returned as audio from the server)
- Beep feedback at each state: listening / sent / reply ready
- Scrollable transcript history via scroll wheel

---

## Architecture

```
Claude Code CLI  ──hooks──▶  bridge/buddy_daemon.py  ──HTTP──▶  Android app
                                      │
                              HTTP server :7700
                              ┌─────────────────┐
                              │  GET /status     │  ← Android polls every 3 s
                              │  POST /decision  │  ← Android posts Approve/Deny
                              │  GET /meter      │  ← usage % for rings
                              │  GET /voice      │  ← R1 voice creation HTML
                              │  POST /ask       │  ← R1 voice STT→LLM→TTS
                              │  GET /auto-token │  ← silent pairing
                              └─────────────────┘
                                      │
                                Pinggy tunnel
                                      │
                              Rabbit R1 creations
```

**Three components:**

**1. Android app** — foreground service that polls `/status` every 3 s, renders live state with Jetpack Compose on a true-black OLED theme. No BLE required.

**2. Bridge daemon** — Python process hooking into Claude Code CLI over a Unix socket; exposes an HTTP API for the Android app and R1 creations.

**3. Voice server** — standalone service (runs locally or in Docker on a VPS) for the R1 Voice creation.

---

## Hardware

Designed for **Android gaming tablets** (RedMagic Nova/Astra, Razer Edge) and **Rabbit R1**. The Android app works on any Android 8+ device. The R1 creations work on any Rabbit R1.

---

## Setup

### 1 — Android app

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

On first launch, enter your daemon URL (e.g. `https://yoursubdomain.a.pinggy.link`). The app auto-pairs via `/auto-token` — no pairing code needed if `BUDDY_TOKEN` is set in `.env`.

### 2 — Bridge daemon (local machine)

Requires Python 3.9+.

```bash
# Linux / macOS
./bridge/start-buddy.sh
```

The script auto-installs dependencies, starts a [Pinggy](https://pinggy.io) tunnel on port 7700 (via ssh), and prints the public URL + pairing code. Without a `PINGGY_TOKEN` it uses the free tier: a random URL that rotates and a tunnel that expires after ~60 min. With a Pinggy Pro token you get a persistent URL that survives restarts.

**`.env`** (create in `bridge/`, never committed):
```bash
OPENROUTER_KEY=sk-or-v1-...
BUDDY_TOKEN=your-random-token-here   # shared between daemon and all clients
TTS_VOICE=en-US-AriaNeural
VOICE_MODEL=perplexity/sonar
PINGGY_TOKEN=your-pinggy-token       # optional — Pinggy Pro, persistent URL
PINGGY_URL=https://your-domain       # optional — pin the public URL (custom domain)
```

### 3 — Claude Code hooks

Run once to wire the daemon into Claude Code:

```bash
python3 bridge/setup-hooks.py
```

This adds hooks for `SessionStart`, `UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `Notification`, and `Stop` to `~/.claude/settings.json`. Safe to run multiple times.

To remove:
```bash
python3 bridge/setup-hooks.py --remove
```

### 4 — R1 creations

With the daemon running and the Pinggy tunnel active:

| Creation | QR page |
|----------|---------|
| Usage Meter | `https://your-url/qr` |
| Voice | `https://your-url/voice/qr` |

Open the QR page in a desktop browser, scan with the R1. Both creations auto-pair via `BUDDY_TOKEN`.

---

## Voice — VPS deployment (Docker)

The voice server can run standalone on a VPS.

**Files needed on the VPS** (clone the repo or copy manually):
```
bridge/voice_server.py
bridge/voice_handler.py
bridge/r1-voice.html
bridge/Dockerfile
bridge/docker-compose.yml
bridge/requirements-voice.txt
```

```bash
# On the VPS
cd claude-code-buddy/bridge

# Create .env
cat > .env << 'EOF'
OPENROUTER_KEY=sk-or-v1-...
BUDDY_TOKEN=your-token
TTS_VOICE=en-US-AriaNeural
VOICE_MODEL=perplexity/sonar
VOICE_PUBLIC_URL=https://r1-voice.yourdomain.com
EOF

# Build and start (port 3200)
docker compose up -d
docker compose logs -f
```

**nginx reverse proxy** (with certbot SSL):
```nginx
server {
    listen 443 ssl;
    server_name r1-voice.yourdomain.com;
    client_max_body_size 10M;
    location / {
        proxy_pass http://127.0.0.1:3200;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_read_timeout 90s;
    }
}
```

```bash
sudo certbot --nginx -d r1-voice.yourdomain.com
```

---

## Display states (Android)

| State | Trigger | Character |
|-------|---------|-----------|
| **sleep** | Not connected | Eyes closed, slow breathing |
| **searching** | Polling, no response | Teal, searching animation |
| **idle** | Connected, nothing active | Gentle blink and sway |
| **busy** | Claude is generating | Quicker bob, shimmer |
| **attention** | Session waiting for input | Alert, amber glow |
| **approval** | Tool call pending decision | Approval card overlay |
| **celebrate** | Level up (every 50K tokens) | Bounce + level badge |

---

## Daemon output

```
12:49:13  ·  Meter:    http://localhost:7700/     qr → /qr
12:49:13  ·  Voice:    http://localhost:7700/voice  qr → /voice/qr
────────────────────────────────────────
  PAIRING CODE →  482913  (valid 5m)
────────────────────────────────────────
12:49:41  →  Session A3F2 — generating response
12:49:43  ⏳  Bash  git push origin main
12:49:45  ✔  Bash  [✔1 ✘0]
12:49:46  ◆  Claude: Pushed to origin/main.
```

---

## Roadmap

- [ ] Haptic patterns for approval / deny / level-up (Android)
- [ ] Physical button mapping — Approve/Deny via hardware buttons
- [ ] Multi-device dashboard via LAN/Tailscale WebSocket

---

## License

MIT
