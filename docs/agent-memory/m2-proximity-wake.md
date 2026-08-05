---
name: m2-proximity-wake
description: M2 always-armed/low-battery milestone — idle to passive + offloaded BLE scan auto-arm; branch wearvian-m2-proximity-wake
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Next milestone after v0.2 (started 2026-06-05): make the mobile key leave-on-all-day without draining the watch. Branch `wearvian-m2-proximity-wake` (off `wearvian-m1-phone-key`).

**Design (scaffolded, compiles, UNTESTED — no adb yet):** while active, learn every PRIMARY/sensor MAC into `VehicleAddressStore`. After `IDLE_TIMEOUT_MS`=5min with no link UP, `PresenceService.goPassive()` arms `ProximityWake` (hardware-offloaded `startScan(filters, settings, PendingIntent)` filtered on the VAS service UUID + learned MACs, SCAN_MODE_LOW_POWER, first-match) then `stopSelf()` → releases wake lock + drops FGS. OS holds the scan even with our process killed; on first match it broadcasts to `VehicleProximityReceiver` → disarm + `PresenceService.start()`. `goingPassive` flag keeps the wake armed on a passive stop but disarms on a user stop; `onStartCommand` always disarms.

**CONFIRMED on-device (2026-06-06):** v1 passive→active does NOT work when the app process isn't running — the FGS background-start restriction. Proximity scan fires, `VehicleProximityReceiver` runs, but `PresenceService.start()` throws `ForegroundServiceStartNotAllowedException` from a cold process.

**CDM was tried and is DEAD on Wear OS (2026-06-07, on-vehicle):** `CompanionDeviceManager.associate()` fails instantly with *"3p apps are not allowed to create associations on watch."* — Wear OS forbids third-party CDM associations outright (the CDM *service* exists / `getSystemService` non-null, so a naive `isSupported` check passes, but `associate()` always fails). So `onDeviceAppeared` is unreachable. CDM code fully removed.

**BOTH OS-assisted wake paths are ruled out on this watch:** (1) offloaded-scan→PendingIntent→`startForegroundService` = FGS-bg-start blocked; (2) CDM = 3p assoc blocked. Only viable mechanism = stay-alive (never leave foreground).

**CURRENT = stay-alive passive (2026-06-07, on `main`, compiles, UNTESTED on-vehicle):** `enterPassive()` keeps the FGS + notification up, sets `passive`/`_passive`, `stopBle()` + `releaseWakeLock()`, and arms an **in-process** offloaded `ScanCallback` via `ProximityWake.scanForVehicle(vasVehicleId, callback)` (filters = VAS service UUID + learned MACs from `VehicleAddressStore`, low-power, FIRST_MATCH). On a hit, `proximityCallback.onScanResult` → `exitPassive()` re-acquires wake lock + `startBle()`. No bg→fg transition, so no FGS-start limit; plain BLE scan is allowed for 3p. Notification while idle = **"Passive · waiting for vehicle"** (user-requested). `ProximityWake.kt` recreated as in-process-scan-only (no PendingIntent/receiver). UI tri-state via `PresenceService.passive` StateFlow (`presencePassive` in `SetupUiState`; "Key passive" label). **Passive defaults OFF**; plain toggle (no dialog, works anywhere); `monitorLock` stops the scan on lock; restart-while-passive → `exitPassive`. `monitorIdle` drops to passive then blocks on `connected` until revived.

**VERIFIED on-vehicle (2026-06-07, `no-cdm-sunday.log`):** stay-alive passive→active WORKS. Manual `startPassive` → `→ passive (idle, service alive); proximity watch=true (6 filters)` + notification "Passive · waiting for vehicle"; ~7s later `proximity hit <sensor MAC> → wake` → `watch stopped` → `wake lock acquired` → `proximity wake → active` → real reconnect: S1 full handshake (`session up`), 0x1b heartbeats streaming, climbed to 3 links — all with the app NOT foregrounded. This is the milestone that CDM + the PendingIntent-broadcast path both failed. (Note: the proximity FIRST_MATCH hit reported rssi=0 — cosmetic.)

Unrelated flakiness seen in the same log (NOT passive-related, pre-existing): the PK/PRIMARY module repeatedly `Timed out waiting for 10000 ms` and retried while sensors connected fine (PRIMARY-asleep behavior); a sensor bounced with GATT `status=133` a couple times under simultaneous connects. Potential future polish: PK reconnect latency.

Full design + checklist in `docs/proximity-wake.md`.

Related: [[drive-gated-on-signed-params]] (sensors advertise the VAS id; passive works), [[queued-ux-robustness-tasks]].
