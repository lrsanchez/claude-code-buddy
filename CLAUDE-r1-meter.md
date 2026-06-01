# CLAUDE.md — R1 Usage Meter (`/meter` add-on)

## What this is

A usage gauge for the R1: current 5-hour **session %** and **weekly %** with reset
countdowns — the same data ClawdMeter shows, but exposed as a `/meter` endpoint on your
**existing buddy daemon** and rendered by an R1 creation.

## Placement

`/meter` is a new read-only endpoint on the buddy daemon — same repo, same process, same
tunnel + pairing auth. The daemon gains a **second internal data source**: a background
poller, alongside the hook ingest that feeds `/status`.

```
One daemon:
  hook ingest  ──▶ state ──▶ GET /status, POST /decision   (event-driven)
  60s poller   ──▶ cache ──▶ GET /meter                     (poll-driven)
  all behind the same Tunnel/Funnel + pairing token
```

## Data source (the ClawdMeter method)

A background task polls roughly every **60 s**:

1. Read the **Claude Code OAuth token** locally — macOS Keychain (service
   `Claude Code-credentials`) or `~/.claude/.credentials.json` on Linux.
2. Make a **minimal** authenticated call to `api.anthropic.com/v1/messages`
   (e.g. `max_tokens: 1`) and read the **rate-limit / utilization response headers** →
   session %, session reset, weekly %, weekly reset.
3. Cache the parsed values. `/meter` always serves the cache and never blocks on the
   upstream call.

> ⚠️ The exact header names for the subscription session/weekly utilization aren't
> officially documented — they come back on a request authenticated with the Claude Code
> OAuth token (not a normal API key). **Verify them empirically:** log the full response
> headers on first run, or read the parsing in ClawdMeter's daemon
> (`HermannBjorgvin/Clawdmeter`, `daemon/`). Treat header names as something to confirm,
> not assume.

## Endpoint

`GET /meter` → JSON, behind the same bearer token as the rest of the daemon:

```json
{ "s": 38, "sr": 142, "w": 61, "wr": 5040, "st": "ok", "ts": 1748600000 }
```

- `s` session % · `sr` session reset (min) · `w` weekly % · `wr` weekly reset (min) ·
  `st` status (`ok` / `stale` / `error`) · `ts` last successful poll (epoch).
- On poll failure (missing token, 401, upstream rate-limited), serve last-known values with
  `st: "stale"` + age, so the device degrades gracefully instead of blanking.

## R1 meter creation

- A creation — standalone, or a second screen inside the buddy creation — that polls
  `/meter` every ~30–60 s and renders **two ring gauges + reset countdowns**
  (see `r1-meter-ui.html`).
- Reuses the same paired device token (`Authorization: Bearer …`).
- Severity coloring: calm under ~70 %, amber ~70–85 %, red + pulse above — so a glance
  tells you whether you're about to hit a wall.

## Notes

- The poll costs ~1 token/min — negligible.
- It uses your own local Claude credentials on your own machine to read your own usage —
  fine for personal use, just be aware the daemon now touches that token.
- **Not an official usage API** — it's the local token + real rate-limit headers. Sturdier
  than scraping the web app, but if Anthropic ships an official `claude usage`
  command/endpoint, repoint the poller at that.
- Keep the 60 s cadence; don't hammer the API.

## Open bits

1. Standalone meter creation vs a screen inside the buddy creation.
2. Exact header field names (verify on first run; the one real unknown).
3. Color thresholds + whether to surface a "near limit" alert (and how loud).
