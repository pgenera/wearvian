---
name: passive-wake-autoconnect
description: "Passive-mode wake now uses a GATT autoConnect on PK, gated on seenDeparture, alongside FIRST_MATCH"
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Added 2026-07-02 (watch/wear, on `main`). Fixes unreliable "walk up to the car → wake from passive."
Root cause: passive idle tore down all BLE and relied SOLELY on one offloaded `FIRST_MATCH` scan
(detect-then-connect, no self-heal — a single missed match stranded it).

Fix: `ble/PassiveAutoConnect.kt` — a bare `device.connectGatt(autoConnect=true)` pending on the bonded
**PK** (`RivianBle.DEVICE_NAME`). The BT **controller** completes it whenever PK next advertises →
`onConnectionStateChange(CONNECTED)` → `exitPassive()`. autoConnect *is* the connect (no detect→connect
race), needs **no wake lock**, and its power cost ≈ the existing offloaded scan (both controller-offloaded
low-duty). It's a COMPANION to `ProximityWake` FIRST_MATCH, not a replacement — the scan still provides
`MATCH_LOST` departure detection. On wake, `passiveLink.disarm()` closes the bare gatt so the full
`VehicleSession` reconnects (runForever already has an autoConnect fallback for PK).

**Critical gotcha (caused a "won't stay passive" bounce):** autoConnect must respect the SAME
`seenDeparture` gate as FIRST_MATCH. Arming it while parked-nearby reconnects PK immediately and bounces
straight back to active. So arm autoConnect ONLY when the car is actually gone: in `enterPassive` iff
`seenDeparture` already set, else in the `MATCH_LOST` handler right after it sets `seenDeparture=true`.
Disarm in `exitPassive` / lock teardown / `onDestroy`.

Wired in `PresenceService`: field `passiveLink = PassiveAutoConnect { scope.launch { exitPassive() } }`.
Debug log lines: `passive-autoconnect: armed on PK` / `PK reconnected → wake`. See
[[parked-idle-power-save]], [[passive-entry-is-a-session]], [[m2-proximity-wake]].
