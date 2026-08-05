---
name: ambient-idle-screen
description: Wear ambient (always-on) idle screen for wearvian — low-fi mirror of the KEY page
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Shipped 2026-07-02 (watch/wear, on `main`, versionName 0.9.0). Replaces the system's blur-with-clock
fallback with a custom always-on screen. Built via `androidx.wear:wear:1.3.0` `AmbientLifecycleObserver`
+ `<uses-library com.google.android.wearable required=false>`. Files: `ui/AmbientScreen.kt` (new),
wiring in `ui/MainActivity.kt`.

Design the user converged on (don't regress):
- **Mirror the interactive KEY page** (`ControlScreens.KeyPage`) as closely as makes sense: same
  column geometry (18dp pad, 10dp spacing), status label with range/temp flanks, lock/unlock row, and
  lock-state line — but thin OUTLINE glyphs, nothing filled. Drop the key icon; put a **large clock**
  in its slot. Clock uses `android.text.format.DateFormat.getTimeFormat(context)` (respects 24h +
  locale — a hardcoded 12h formatter was a bug). Shows "Charging" (bolt) when parked+charging; in gear,
  don't light the lock glyph brighter than the rest.
- **Burn-in safety = official Wear guidance**: black bg, outlines/no fills, pure white in low-bit,
  nudge whole layout a few px each `onUpdateAmbient` (~1/min). `burnInProtectionRequired` /
  `deviceHasLowBitAmbient` come from `AmbientDetails`.
- **Timeout back to watch face**: `AMBIENT_TIMEOUT_MS = 10min`, tracked as an absolute
  `elapsedRealtime` deadline set ONCE per session (NOT reset by re-entry/wrist-glances or vehicle
  updates, or driving glances keep it alive forever). Re-checked in `onUpdateAmbient` as a doze
  backstop; `finish()` returns to the watch face.
- **Touch**: on the Pixel Watch, taps ARE delivered in ambient and fall through to the live UI unless
  consumed. So the overlay Box consumes background taps (`detectTapGestures{}`) — a wake tap no longer
  toggles the key — while the lock/unlock glyphs are `clickable` and DO send the command from ambient
  (unlock disabled in gear). `dispatchTouchEvent` also rejects palm-over-screen (getSize()>=0.5 /
  >2 pointers); normal Pixel-Watch taps read well under 0.5.

See [[parked-idle-power-save]], [[passive-wake-autoconnect]], [[vehicle-status-0x1c-decode]].
