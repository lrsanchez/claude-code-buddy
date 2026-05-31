# CLAUDE.md — Claude Hardware Buddy (Android)

## Purpose

Build a native Android app that acts as a **Claude Hardware Buddy**: a Bluetooth LE
*peripheral* that pairs with the Claude desktop apps (Claude Cowork / Claude Code
Desktop, macOS/Windows) over the documented Hardware Buddy BLE protocol, and displays
live Claude Code session state on the device's screen — with on-screen **Approve / Deny**
for permission prompts.

Target device class: **Android gaming tablets — RedMagic (Nova / Astra) and Razer Edge
class**. These are Qualcomm Snapdragon devices (Snapdragon 8 Gen 3 Leading Version /
8 Elite / G3x Gen 2), with large high-refresh OLED screens, big batteries, and strong
haptics. The app must run as a normal sideloaded APK — no root, no jailbreak.

## What it talks to

The Claude desktop app, when **Developer Mode** is enabled
(`Help → Troubleshooting → Enable Developer Mode`), exposes a BLE bridge via
`Developer → Open Hardware Buddy…`. The desktop is the BLE **central**; it scans for and
connects to one peripheral that advertises the Nordic UART Service. Our Android app is
that peripheral.

The bridge connects to **one** peripheral at a time. Multi-screen / multi-device
simultaneous display is explicitly out of scope for v1 (see "Future").

---

## Architecture decision (v1)

**Direct mode**: the Android device *is* the BLE peripheral. The app runs a
`BluetoothGattServer` exposing the Nordic UART Service, advertises itself, parses the
newline-delimited JSON stream from the desktop, renders it, and writes permission
decisions back.

**UI: Kotlin + Jetpack Compose (decided).** Native is the right fit for a single-device
ambient display — full control over state animations and haptics, no JS-bridge surface,
and fewer moving parts to survive the OS process killer. The BLE/protocol layer is kept
cleanly separated from the UI, so a future web rewrite (for the multi-device LAN
dashboard) would touch only the UI layer, not the protocol code.

---

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3) + Compose animation APIs (`updateTransition`,
  `Animatable`, `rememberInfiniteTransition`, `AnimatedContent` / `AnimatedVisibility`);
  custom `Canvas` for the buddy character
- **BLE:** Android platform APIs only — `BluetoothManager`, `BluetoothGattServer`,
  `BluetoothLeAdvertiser`. No third-party BLE wrapper (those mostly target the *central*
  role; we need the peripheral/GATT-server role).
- **JSON:** `kotlinx.serialization`
- **Async:** Kotlin Coroutines + `StateFlow` for UI state
- **Service:** Foreground `Service` (type `connectedDevice`) to keep advertising + the
  GATT link alive while the screen is an ambient display
- **minSdk:** 26 · **targetSdk:** latest stable · build with the current AGP/Gradle

---

## BLE wire protocol (authoritative — implement exactly)

### Transport

Nordic UART Service (NUS), newline-delimited UTF-8 JSON, one object per `\n`-terminated
line.

| Role | UUID |
| --- | --- |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX — desktop → device (we receive **writes**) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX — device → desktop (we send **notifications**) | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

Rules:
- Advertise a device name that **starts with `Claude`** so the desktop picker can filter
  to us. Append a few bytes of the BT MAC to stay distinguishable if multiple buddies
  exist.
- Both directions fragment at the negotiated **MTU**. On send, chunk TX payloads to
  `(MTU − 3)` bytes per notification. On receive, **accumulate** incoming write bytes into
  a per-connection buffer and split on `\n` before parsing — a single JSON object may span
  multiple writes.
- If no inbound snapshot arrives for **~30 s**, treat the connection as dead.

### Inbound (desktop → device, written to RX)

**Heartbeat snapshot** — sent on any change, plus a keepalive every 10 s:

```json
{
  "total": 3,
  "running": 1,
  "waiting": 1,
  "msg": "approve: Bash",
  "entries": ["10:42 git push", "10:41 yarn test", "10:39 reading file..."],
  "tokens": 184502,
  "tokens_today": 31200,
  "prompt": { "id": "req_abc123", "tool": "Bash", "hint": "rm -rf /tmp/foo" }
}
```

| Field | Meaning |
| --- | --- |
| `total` | count of all sessions |
| `running` | sessions actively generating |
| `waiting` | sessions blocked on a permission prompt |
| `msg` | one-line summary for a small display |
| `entries` | recent transcript lines, newest first |
| `tokens` | cumulative output tokens since desktop launch |
| `tokens_today` | output tokens since local midnight (persisted) |
| `prompt` | present **only** when a decision is needed; echo `prompt.id` back |

Derived signals: `running > 0` = something is generating; `waiting > 0` = a prompt is
blocking; `total == 0` = nothing open.

**Turn event** — one-shot per completed turn; dropped by desktop if it serializes >4 KB:

```json
{ "evt": "turn", "role": "assistant", "content": [{ "type": "text", "text": "..." }] }
```

**Time sync** (epoch seconds, timezone offset seconds):
```json
{ "time": [1775731234, -25200] }
```

**Owner name:**
```json
{ "cmd": "owner", "name": "Felix" }
```

**Commands that require an ack** (see below): `status`, `name`, `owner`, `unpair`.

### Outbound (device → desktop, notify on TX)

**Permission decision** — send when the user taps Approve/Deny; `id` must equal
`prompt.id` exactly. `"once"` approves, `"deny"` rejects:

```json
{"cmd":"permission","id":"req_abc123","decision":"once"}
{"cmd":"permission","id":"req_abc123","decision":"deny"}
```

**Acks** — every desktop `cmd` expects a matching ack. `n` is a generic counter (0 unless
meaningful). On failure set `ok:false` and optionally `error`:

```json
{ "ack": "name",  "ok": true }
{ "ack": "owner", "ok": true }
{ "ack": "unpair","ok": true }
```

**Status response** to `{"cmd":"status"}` (desktop polls every couple seconds for its
stats panel). Omit any field we don't have:

```json
{
  "ack": "status",
  "ok": true,
  "data": {
    "name": "Clawd",
    "sec": true,
    "bat": { "pct": 87, "mV": 4012, "mA": -120, "usb": true },
    "sys": { "up": 8412, "heap": 84200 },
    "stats": { "appr": 42, "deny": 3, "vel": 8, "nap": 12, "lvl": 5 }
  }
}
```

- `bat.pct` / `bat.usb` from `BatteryManager`; `bat.mA` negative means charging.
- `sys.up` = app/service uptime seconds; `sys.heap` = free heap (best-effort).
- `stats`: our own counters — `appr`/`deny` decisions made, `lvl` = floor(tokens / 50000),
  others optional.
- `sec`: `true` once the link is bonded/encrypted (see Security).

### Folder push (OPTIONAL — v1 may decline)

The desktop can stream a folder (e.g. GIF character packs) via
`char_begin → file → chunk(base64) → file_end → … → char_end`, each step acked. **v1
behavior: do not ack `char_begin`**, so the desktop times out gracefully. Revisit if/when
we add custom character art (see Future). If implemented later: validate `file.path`
(reject `..` and absolute paths) before writing.

---

## App responsibilities (core loop)

1. **Advertise** the NUS as a peripheral with a `Claude…` name.
2. **Accumulate** RX write bytes, split on `\n`, parse each line as JSON.
3. **Render** the current snapshot (state machine below) + transcript + token counters.
4. **Send** the permission decision on Approve/Deny tap.
5. **Ack** `status` (and `name`/`owner`/`unpair`) so the desktop stats panel populates.

### Display state machine

Map the snapshot to a visual state (mirrors the reference firmware's seven states):

| State | Trigger |
| --- | --- |
| `sleep` | not connected / no snapshot for 30 s |
| `idle` | connected, `waiting == 0`, `running == 0` |
| `busy` | `running > 0` |
| `attention` | `waiting > 0` (also: vibrate + visually flag) |
| `celebrate` | `floor(tokens/50000)` increased since last snapshot |
| `approval` | `prompt` present → show the approval card |

Persist `lvl`, `appr`, `deny`, and last-seen token level across restarts
(`DataStore`/SharedPreferences).

---

## Android implementation notes (the parts that bite)

- **Peripheral-mode gate.** At startup check
  `bluetoothAdapter.bluetoothLeAdvertiser != null` and
  `bluetoothAdapter.isMultipleAdvertisementSupported`. If the advertiser is null, the
  chipset can't be a peripheral — show a clear "this device can't act as a BLE peripheral"
  screen and stop. The Snapdragon targets (8 Gen 3 / 8 Elite / G3x Gen 2) almost
  certainly support the peripheral role, so this gate is expected to pass — but still
  **verify on the actual target model before building UI** (no-code way: install
  *nRF Connect for Mobile*, try its Advertiser tab).
- **Advertised name.** The 31-byte adv packet can't hold the service UUID *and* a long
  name. Put the 128-bit service UUID in the **advertisement** and the `Claude…` name in
  the **scan response** (`setIncludeDeviceName(true)` after setting a short adapter name,
  or pack name bytes manually). Keep the name short.
- **Permissions (runtime + manifest):**
  - API ≥ 31: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (request at runtime).
  - API < 31: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`.
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+),
    `POST_NOTIFICATIONS` (API 33+).
- **GATT server setup:** `BluetoothManager.openGattServer`; primary service with the two
  characteristics. RX = `PROPERTY_WRITE | PROPERTY_WRITE_NO_RESPONSE`. TX =
  `PROPERTY_NOTIFY` + a **CCCD** descriptor (`00002902-…`). Track CCCD subscribe/unsubscribe
  in `onDescriptorWriteRequest` and `sendResponse`; only notify when subscribed.
- **MTU:** handle `onMtuChanged`; chunk every TX line to `(mtu − 3)` bytes.
- **Notify:** set the TX characteristic value, call
  `notifyCharacteristicChanged(device, txChar, confirm=false)`; serialize notifications
  (await the previous `onNotificationSent`-equivalent / back-pressure) so chunks don't
  race.
- **Foreground service** holds the GATT server + advertiser and a notification; the
  Activity binds to it for state. For ambient display keep the screen on
  (`FLAG_KEEP_SCREEN_ON`) and document that the device should stay plugged in (advertising
  + screen-on is power-hungry; Doze will throttle a backgrounded app).
- **Gaming-OS background management (the real risk on these tablets).** RedMagic OS and
  similar gaming skins aggressively kill background apps and apply heavy battery
  optimization, which will silently drop a long-running BLE service. Mitigate: request
  battery-optimization exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` /
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), use the `connectedDevice` foreground
  service type, keep the Activity foregrounded as the ambient display, and write a
  README step telling the user to lock the app in recents and whitelist it from the
  device's own auto-management (RedMagic GameSpace / battery settings). Treat "service
  survives an hour idle, plugged in" as an explicit acceptance test on the real device.
- **Reconnect:** the desktop auto-reconnects; keep advertising whenever no central is
  connected.

### Security (phased)

- **v1 (POC):** unencrypted is acceptable — the desktop connects either way. Report
  `sec:false` (or omit) in the status ack.
- **v2 (recommended for daily use):** require bonding. Mark the NUS characteristics and
  the TX CCCD as **encrypted** (`PERMISSION_*_ENCRYPTED`), which forces pairing on first
  GATT access; report `sec:true` afterward. Handle `{"cmd":"unpair"}` by removing the bond
  (`BluetoothDevice#removeBond` via reflection, or clear our stored state) and ack it.
  **Caveat:** Android's peripheral pairing IO-capability is system-mediated — you will
  likely get *Just Works* or *numeric comparison*, not the clean DisplayOnly 6-digit
  passkey the spec describes for ESP32/nRF. Bonding still encrypts the link (AES-CCM);
  just don't promise a specific passkey UX. Rationale to surface to the user: transcript
  snippets and tool-call hints flow over this link and are otherwise sniffable in radio
  range.

---

## Visual design language

Calm, premium, **dark-first** — it should feel like a quiet companion on the desk that
only gets loud when it needs you. Not gamer-RGB-loud.

- **Theme:** true-black (`#000000`) base (OLED power + burn-in friendly), layered dark
  surfaces for cards, one warm **Claude coral** accent (~`#D97757`) for highlights/brand.
  Semantic colors: `running` = cool teal/blue, `waiting`/attention = warm amber→coral
  alert, approve/success = green, deny/danger = red.
- **Typography:** a clean geometric/grotesk display face for the hero state line and big
  counts; **monospace** for `prompt.hint` and transcript lines (they're command-ish).
  High-contrast, readable across a room.
- **Design tokens (define once, reference everywhere):** colors, type scale, spacing,
  corner radii, elevation, and a set of **named motion specs** (below). One source of
  truth so every component feels coherent.
- **Density:** generous spacing — this is glanceable, not a dense dashboard.

## Screen layout (landscape primary — desk display; responsive to portrait)

Hero-led:
- **Top bar:** animated connection dot, a greeting using owner name + a live clock (from
  the `time` sync), device name, small settings affordance.
- **Hero (center/left):** the **buddy character**, large — the emotional focal point that
  carries the current state.
- **Side/lower panel:** session chips (`total` / `running` / `waiting`); a **token meter**
  (animated `tokens_today` count + a progress arc to the next level, `tokens % 50000`,
  with a level badge); the **transcript** (`entries`, newest first).
- **Approval card:** center overlay when `prompt` is present; dims/blurs everything else.

## The buddy character

- **Compose-drawn** expressive character via `Canvas` — no external assets, scales crisply
  at any resolution, fully animatable. (If you want richer art later: Lottie or an
  image-sequence, wired to the same state hooks.)
- **Concept:** a simple rounded form with expressive **eyes** + posture + color that reads
  instantly from across the desk. State is conveyed by expression, motion, and accent —
  not by text.
- **Per-state expression & motion:**
  - **sleep** (disconnected / stale >30 s): eyes closed, dimmed, slow "breathing" scale
    pulse (~0.3–0.5 Hz), occasional drifting Z. Lowest brightness.
  - **idle** (connected, quiet): eyes open, periodic natural blink, gentle bob/sway,
    occasional look-around. Calm accent.
  - **busy** (`running > 0`): livelier — quicker bob, a subtle activity shimmer/aura, eyes
    focused; intensity may scale with `running`.
  - **attention** (`waiting > 0`): alert posture, accent shifts to the warm alert color,
    pulsing glow + a pulsing screen-edge border, synced haptic tick. Built to catch your
    eye from across the room.
  - **celebrate** (level up): bounce + confetti burst, brief "Level N" flourish, then
    settle back.
- Transitions between states are **animated morphs / cross-fades** (spring) — never hard
  cuts.

## Motion & animation

- Use Compose animation throughout: `updateTransition` for the state machine,
  `rememberInfiniteTransition` for idle loops (breathe / blink / bob), `Animatable` &
  `animate*AsState` for one-shots, `AnimatedContent` for content swaps, `AnimatedVisibility`
  for the approval card.
- **Spring physics** for anything that enters or moves (natural, lively); short tweens for
  color/opacity only.
- Target the panel's high refresh (120/165 Hz): keep animations cheap (Canvas + graphics-
  layer transforms, minimal recomposition), aim for a solid 60+ fps.
- **Named motion specs** in the theme — e.g. `enterSpring` (medium bounce), `settleSpring`
  (low bounce), `pulse` (~1.2 s ease-in-out infinite), `blink` (fast), `breathe` (slow).
  Components reference these so motion is consistent.
- **Micro-interactions:**
  - token counter tweens (count-up); level arc fills smoothly
  - `running` chip has a live pulse when `running > 0`; `waiting` chip glows when
    `waiting > 0`
  - new transcript lines slide + fade in at the top; older lines recede
  - connection dot springs + pulses on connect/disconnect
  - buttons: press scale-down + ripple + haptic
- **Approval card choreography:** springs up + scales in on arrival with the alert haptic;
  background dims/blurs; on **Approve** → satisfying confirm (accent flash + upward
  dismiss + success haptic), on **Deny** → distinct dismiss (downward + firmer haptic);
  buttons lock until the next snapshot clears `prompt`.
- **Haptics** (strong X-axis linear motors): distinct patterns for prompt-arrival,
  approve, deny, and level-up — make a pending approval *felt*, not just seen.

## Component inventory

`AppTheme` (tokens + motion specs) · `TopBar` · `BuddyCharacter` (Canvas + state) ·
`StateBackground` (ambient glow / edge pulse) · `SessionChips` · `TokenMeter` (count +
level arc) · `TranscriptList` · `ApprovalCard` · `ConfettiOverlay`.

## Display hygiene

- **Reduce-motion:** honor the OS accessibility setting and offer an in-app toggle — fall
  back to fades/dimming, drop loops and confetti.
- **OLED burn-in:** true-black background; dim globally in `idle`; subtle periodic
  pixel-shift on persistent elements (top bar, counters); the ambient character motion
  already prevents static pinning. Optional deep-dim/screensaver after long idle.
- Keep it glanceable from across a desk.

---

## Project structure

```
app/src/main/java/<pkg>/
  ble/
    NusGattServer.kt        // GATT server, characteristics, CCCD, notify+chunk
    NusAdvertiser.kt        // advertiser, name/scan-response, peripheral-mode gate
    LineFramer.kt           // byte accumulation + \n split
  protocol/
    Messages.kt             // kotlinx.serialization models (snapshot, turn, prompt, acks)
    ProtocolHandler.kt      // inbound dispatch, ack generation, decision/status outbound
  service/
    BuddyService.kt         // foreground service owning BLE + state
  state/
    BuddyState.kt           // StateFlow<BuddyUiState>, state machine, persisted stats
  ui/
    BuddyScreen.kt          // Compose UI + ApprovalCard + state visuals
  MainActivity.kt
  permissions/Permissions.kt
```

---

## Milestones

- **M0 — Gate check (do first, no app):** confirm the target hardware can advertise as a
  peripheral (nRF Connect Advertiser; bonus: fake the NUS as a GATT server and confirm it
  shows in the desktop's Hardware Buddy picker).
- **M1 — Skeleton link:** advertise NUS, accept connection, log parsed inbound lines, ack
  `status` minimally. Confirm the device appears + connects in the desktop picker.
- **M2 — Display & visual design:** theme/tokens + named motion specs; the hero buddy
  character (Canvas) with per-state expressions and transitions; session chips, token
  meter + level arc, transcript; the state-machine driving it all. Build per "Visual
  design language", "The buddy character", and "Motion & animation". (Confetti/celebrate
  flourish may trail into M4 polish.)
- **M3 — Approvals:** approval card + Approve/Deny → permission decision back; update
  `appr`/`deny` stats.
- **M4 — Robustness:** foreground service, reconnect, MTU chunking, 30 s dead-link
  handling, time/owner handling, persisted stats/level, celebrate transition.
- **M5 — Bonding (optional):** encrypted characteristics + `unpair`, `sec:true`.

---

## Build & run

- Standard Gradle Android project; assemble a debug APK and sideload to the handheld
  (`adb install`), or distribute the APK directly.
- Document the desktop side in the README: enable Developer Mode → Open Hardware Buddy →
  Connect → pick the `Claude…` device.

## Acceptance criteria

- Device appears as `Claude…` in the desktop Hardware Buddy picker and connects.
- Snapshot fields render correctly and update live; state machine matches triggers.
- A pending `prompt` shows the approval card; tapping Approve/Deny resolves the
  corresponding tool call in the desktop session, and the card clears on the next
  snapshot.
- `status` polls are answered (stats panel populates).
- Connection survives screen-on idle and auto-recovers after the desktop sleeps/wakes.
- Graceful, explicit failure if the chipset can't advertise as a peripheral.

## Out of scope / Future

- **Multi-device dashboard:** desktop bonds to one peripheral. To drive several Android
  screens at once, designate one device/box (a spare Android, Pi, or ESP32) as the bridge
  and re-broadcast the heartbeat over LAN/Tailscale as a websocket; other devices render a
  web dashboard. A WebView/React UI in this app would make that frontend reusable.
- **Folder push / custom GIF character packs.**
- Mapping handheld **physical buttons** to Approve/Deny.

---

## Open questions (confirm before M2)

1. **Exact target model** — RedMagic Nova, RedMagic Astra, or Razer Edge? All Snapdragon
   (gate expected to pass); confirm the specific one so the README's whitelist/battery
   steps name the right OS skin and settings.
2. **Encryption in scope for first release** (M5) or defer to a follow-up?

*(UI direction and buddy visuals are now specified above: native Compose, animated
Canvas-drawn character. The character's specific art direction — exact shape, palette,
eye style — can be refined during M2 without changing the architecture.)*
