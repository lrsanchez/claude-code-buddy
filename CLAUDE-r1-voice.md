# CLAUDE.md — R1 Claude Voice (Push-to-Talk)

## Purpose

A self-hosted **rabbit r1 creation**: hold the talk button, speak, and get an answer from a
top model (via **OpenRouter** — Claude or whatever you point it at) spoken back. Turn-based
(walkie-talkie) — deliberately mimicking the R1's native push-to-talk feel — but the brain
is your OpenRouter model instead of the built-in R1 one.

The goal is *better answers*, not a fancier interaction model. Keep the front end thin;
put the intelligence in the backend.

## Why this shape

- The R1's built-in AI is the thing we're replacing, so the LLM call must route to your
  **OpenRouter** model through our backend (not the SDK's built-in
  `PluginMessageHandler` LLM channel, which is rabbit's model).
- This is a **hand-built, self-hosted SDK creation** (not an intern-generated one), so the
  "no speech-to-text / no hosted backend" limitation does **not** apply — that restriction
  is only for creations vibe-coded on-device with intern.

## Architecture

```
            hold PTT → record → release
   ┌──────────────────────────────────────────┐
   │  R1 creation (HTML/JS, 240×282 webview)    │
   │   • longPressStart → start mic capture     │
   │   • longPressEnd   → stop, POST audio      │
   │   • speak reply via device TTS + show text │
   └───────────────┬───────────────▲───────────┘
                   │ audio (HTTPS)  │ reply text (+optional audio)
                   ▼                │
   ┌──────────────────────────────────────────┐
   │  Backend on droplet (FastAPI / Node)       │
   │   POST /ask:  STT → OpenRouter → reply       │
   │   POST /pair: code → device token (1st run)  │
   │   • STT  (Whisper / Deepgram)               │
   │   • OpenRouter (model configurable) + history│
   │   • returns reply text (+ optional TTS)     │
   │   exposed via Cloudflare Tunnel / TS Funnel │
   └──────────────────────────────────────────┘
```

## R1 creation (front end)

**SDK surface to use** (from `rabbit-hmi-oss/creations-sdk`, see plugin-demo):
- Talk button: `window.addEventListener('longPressStart' / 'longPressEnd', …)` for
  hold-to-record; `sideClick` for a short press (use for cancel / repeat-last).
- Scroll wheel: `scrollUp` / `scrollDown` → scroll the transcript.
- Storage: `window.creationStorage.plain` (Base64-encoded JSON) for short conversation
  history / settings.
- TTS for the reply: the SDK speech module (speaks text through the R1 speaker). If device
  TTS proves awkward to call with arbitrary text, fall back to backend-generated TTS audio
  played via an HTML `<audio>` element.

**Voice capture:** record mic audio in the webview on PTT hold (`getUserMedia` +
`MediaRecorder`), then POST the blob to the backend. STT happens server-side — do **not**
rely on the device exposing STT to the creation.
> ⚠️ Verify first that `getUserMedia` mic capture works inside the R1 creation webview.
> Community creations report working voice input (see `andr3w-hilton/rabbit-r1-creations-public`
> → `R1_CREATION_TIPS.md`), but confirm on-device before building the rest.

**UI (240×282, portrait):** minimal and glanceable — a big talk/state indicator
(`pairing → idle → listening → thinking → speaking`, where **pairing** is first-run only,
see Pairing & auth), the last exchange as text (so it's usable muted), and a thin
transcript the wheel scrolls. Touch targets ≥ 44×44. Avoid heavy animation (limited CPU;
one creation runs at a time).

**Hosting & install:** plain static HTML/JS, no build step. Host on your server / Netlify /
GitHub Pages. Generate the install QR with the `qr` utility in the SDK repo; the R1 caches
the URL, so bump a `?v=` query param to push updates.

## Backend (droplet)

- **`POST /ask`** — accepts audio (multipart) or text; pipeline: STT → assemble messages
  (system prompt + recent history + new turn) → OpenRouter model → return `{ text, audio? }`.
  Requires `Authorization: Bearer <device-token>` (see Pairing & auth).
- **STT:** Whisper (self-host `faster-whisper`) or a STT API (Deepgram) — pick for latency.
- **Brain:** **OpenRouter** — one endpoint/key for many models (Claude, plus the others
  you already run like Kimi), so you can swap or A/B the model with a config change, no code
  edit. System prompt tunes it as a **voice** assistant: short, spoken-style answers, no
  markdown, no long lists. Use a **fast model** — for voice, latency beats marginal quality,
  and replies must be short enough to speak. Set the OpenRouter model id in env/config.
- **TTS (if backend-side):** ElevenLabs or Cartesia for a natural voice (the R1's stock
  voice is ElevenLabs, so it'll feel native); return audio for the creation to play.
- **Memory:** keep per-session history server-side keyed by a session id the creation
  generates on first launch (store it in `creationStorage`).
- **Auth + exposure:** front it with Cloudflare Tunnel (custom domain) or Tailscale Funnel
  (both give HTTPS). Neither tunnel authenticates on its own — auth is the **pairing-issued
  device token** below; the creation sends `Authorization: Bearer <token>` on every request.

## Pairing & auth (first run)

The creation ships with **no secret baked in** — a credential is established once, at first
launch, via a pairing handshake. The gate is access to your server logs (already behind
SSH), so an attacker would need both the endpoint URL *and* your droplet logs.

**Flow**
1. First launch: no stored token → the creation shows the **pairing** screen.
2. You open a time-boxed pairing window on the droplet (a small CLI command, or the server
   logs a fresh code on demand). It generates a **6-digit code**, prints it to the logs,
   valid ~5 min, single-use.
3. You read the code from the logs and enter it on the rabbit (scroll wheel to pick a digit,
   click to advance). The creation calls `POST /pair { code }`.
4. Server validates → issues a **durable device token** (32 random bytes) → the creation
   stores it in `creationStorage.plain` and sends it as `Authorization: Bearer <token>` on
   every `/ask`. Subsequent launches skip pairing.

**Endpoints**
- `POST /pair { code }` → `{ token }` on success. Validates: window open, code matches, not
  expired, not already used, under the attempt limit.
- `POST /ask` requires a valid bearer token, checked against a server-side allowlist of
  issued tokens.

**Hardening (the parts that matter)**
- **Rate-limit + lock `/pair`.** A 6-digit code is brute-forceable in seconds otherwise —
  invalidate the code after ~5 wrong attempts; keep expiry short and use single-use.
- **Pairing off by default.** Only accept `/pair` while a window is explicitly open; reject
  otherwise.
- **Revocable tokens.** Keep issued tokens in a server-side allowlist; delete an entry to
  kick a device.
- **Keep the spend cap + per-token rate limit regardless.** `creationStorage` is Base64,
  *not encrypted*, so the token is only as safe as device access — the OpenRouter credit
  cap is what bounds damage if it ever leaks.
- **HTTPS only** (already via the tunnel) so the code and token aren't sniffable.

**Recovery:** if device storage is wiped (e.g. a reinstall), open a new window and re-pair
with a fresh code.

## Latency budget (turn-based, set expectations)

`record → upload → STT → OpenRouter → TTS → play`. Keep each hop tight: stream the upload on
PTT release, use a fast STT and a fast model, cap reply length. Show the
`thinking`/`speaking` states so the wait is legible rather than dead air.

## Constraints

- Screen 240×282; one creation at a time; limited CPU/storage.
- Replies must be short — they're spoken aloud.
- The SDK talks over its own JS channels (`PluginMessageHandler.postMessage()` /
  `window.onPluginMessage`); we only use the built-in channel for TTS, not for the LLM.

## Reference repos

- **`rabbit-hmi-oss/creations-sdk`** — official: `plugin-demo` (hardware events, TTS,
  storage, LLM channel) and `qr` (install QR generator).
- **`andr3w-hilton/rabbit-r1-creations-public`** — community: `R1_CREATION_TIPS.md` covers
  voice input, storage, keyboard, gotchas; "bring your own host."
- **`ShayneP/rabbit-r1-livekit-skill`** — if you ever want real-time (WebRTC) voice instead
  of turn-based; supports Claude. Keep in back pocket.

## Open decisions

1. **STT:** self-hosted Whisper vs Deepgram API (latency vs setup).
2. **TTS:** device SDK speech vs backend ElevenLabs/Cartesia (simplicity vs voice quality).
3. **OpenRouter model:** which model id as default (latency vs quality for spoken replies),
   and whether to expose a quick in-creation model switcher.
4. **Exposure:** Cloudflare Tunnel vs Tailscale Funnel (both HTTPS; auth is the pairing
   token, not the tunnel).
5. **History depth:** how many turns to keep in context for a voice session.
6. **Pairing details:** how to open the window (CLI command vs code logged on boot), code
   length/lifetime, and whether device tokens rotate or expire.
