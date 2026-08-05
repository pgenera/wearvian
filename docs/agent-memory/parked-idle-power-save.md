---
name: parked-idle-power-save
description: "connected-but-idle → passive battery save; MATCH_LOST departure gating; always-on, not toggle-gated"
metadata: 
  node_type: memory
  type: project
  originSessionId: 8e844f0a-84b8-4e92-a905-a528fceed476
---

Battery feature added 2026-06-09 (watch/wear). Drops the wake lock + GATT heartbeats when the key
is active but the car is parked-nearby and idle, by **reusing the existing Passive mode** — NOT a
new tier. Two changes only:

1. **Second entry path** `PresenceService.monitorStableIdle()`: when meaningful vehicle state
   (lock/doors/windows/frunk/liftgate/chargeState/asleep — NOT SoC/range/temp, they drift) holds
   steady for a timeout, call `enterPassive()`. Timeout = 5 min if plugged
   (CHARGING/PLUGGED_IDLE/STARTING), else 15 min. Any meaningful change resets the timer.
2. **No snap-back** via `seenDeparture` flag + `ProximityWake` scan now requesting
   `CALLBACK_TYPE_FIRST_MATCH or CALLBACK_TYPE_MATCH_LOST`. `enterPassive` sets
   `seenDeparture = !PresenceStatus.connected.value` (car present → false). FIRST_MATCH while false
   = the car we're parked beside → ignored. MATCH_LOST → debounce 30s → `seenDeparture=true`; next
   FIRST_MATCH = real return → `exitPassive()`. This is the user's own "OS watch for car going away,
   re-arm beacon listener after debounce" idea, one scan + one flag.

**Key decisions the user enforced (don't regress):**
- Entry trigger must NOT depend on "locked" — user doesn't reliably lock (esp. doesn't lock when
  plugging in). State-stable-for-timeout is the trigger; plugged-in only *accelerates* it.
- Idle→passive is **always-on**. (UPDATE 2026-06-13: the `proximityWakeEnabled` toggle/setting was
  REMOVED entirely — `SettingsStore.proximityWakeEnabled`, the Settings switch, `setProximityWake`,
  and the `goPassive()` gate are all gone; BOTH the disconnect path `monitorIdle`/`goPassive` and the
  stable-idle path are now unconditionally on, except while locked.) Capability preserved: passive
  commands fall back to one-shot `ActiveCommandManager` (SetupViewModel.sendCommand gates the
  CommandBus path on `!PresenceService.passive.value` and nudges `start()` to wake).
- UI reuses "Key passive" label — no new state added to SetupViewModel/ControlScreens.

**OPEN RISK (verify on-vehicle):** the whole depart→return relies on **MATCH_LOST firing reliably
on the Pixel Watch 4** — a flakier/delayed BLE code path than FIRST_MATCH (which is validated).
Verify via DebugLog that MATCH_LOST arrives when the watch leaves range. If not, fall back to a
duty-cycled low-power RSSI scan (user explicitly left that door open). Limitation accepted: car
staying in continuous BLE range the whole time (sitting in yard next to it) won't auto-reactivate
— a manual tap wakes it. See [[passive-entry-is-a-session]], [[vehicle-status-0x1c-decode]],
[[m2-proximity-wake]]. Compiles + unit tests pass; not yet on-vehicle tested.
