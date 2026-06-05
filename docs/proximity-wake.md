# Proximity wake — passive idle + auto-arm on approach (M2)

Goal: make the mobile key something you leave on all day without draining the watch.
Instead of a permanent foreground service + wake lock that scans/heartbeats forever, the
app idles at **near-zero battery** when the car is away and **auto-re-arms on approach**
using a hardware-offloaded BLE scan the OS holds even after our process is killed.

## Flow (as built on `wearvian-m2-proximity-wake`)

1. **Active** — `PresenceService` runs as today: foreground, wake lock, sensor sessions,
   RSSI heartbeats. Drive/unlock work exactly as before.
2. **Learn the beacons** — while active, every PRIMARY/sensor MAC we connect to is saved
   to `VehicleAddressStore`. The vehicle also advertises its **VAS service UUID** (derived
   from `vasVehicleId`), which the sensors broadcast.
3. **Idle → passive** — `monitorIdle()` watches `PresenceStatus.connected`. After
   `IDLE_TIMEOUT_MS` (5 min) with **no link UP**, `goPassive()`:
   - arms `ProximityWake` (offloaded `startScan(filters, settings, PendingIntent)` filtered
     on the VAS service UUID + known MACs, `SCAN_MODE_LOW_POWER`, first-match),
   - `stopSelf()` → `onDestroy` releases the wake lock, drops the FGS notification, cancels
     the sessions. The process can now be killed; the OS keeps the scan registered.
4. **Wake on approach** — when a matching advertisement appears, the OS broadcasts the
   PendingIntent to `VehicleProximityReceiver`, which disarms the scan and calls
   `PresenceService.start()` → back to step 1.

`onStartCommand` always disarms any pending scan (we're awake now) and clears `goingPassive`.
A **user** stop (deactivating the key) disarms the wake in `onDestroy`; only a *passive*
stop keeps it armed (the `goingPassive` flag distinguishes them). If `arm()` fails we stay
foreground rather than go dark and never return.

## Components
- `ble/ProximityWake.kt` — arm/disarm the offloaded PendingIntent scan (first-match, falls
  back to all-matches if the chip lacks offloaded batching).
- `service/VehicleProximityReceiver.kt` — receives the wake broadcast, restarts presence.
- `store/VehicleAddressStore.kt` — remembers learned vehicle MACs.
- `service/PresenceService.kt` — idle timer, `goPassive()`, MAC learning, disarm-on-start.

## ⚠️ Open risk: FGS background-start (Android 12+)
Starting a foreground service from a background broadcast can throw
`ForegroundServiceStartNotAllowedException`. The receiver wraps the start in `runCatching`
and logs `proximity: FGS start blocked` to the debug console if it hits this.

**Fallback if it's blocked:** `CompanionDeviceManager` + a `CompanionDeviceService`. Its
`onDeviceAppeared(...)` callback **is** allowlisted to start an FGS, and is the OS-sanctioned
"wake me when my companion device is near" API. Cost: a one-time system **association
dialog** per vehicle (normal apps can't self-manage associations). If on-device testing
shows the scan-PendingIntent path can't start the service, switch the wake delivery to CDM
(the idle-timer / passive-stop / MAC-learning halves stay as-is).

## On-device test checklist (needs adb / the vehicle)
- [ ] Active → walk away → after 5 min: debug log shows `idle … → passive; wake armed=true`,
      notification clears, wake lock released (battery stops draining).
- [ ] Walk back → `proximity: vehicle nearby … → start presence`, sessions come back up,
      passive unlock/drive still work.
- [ ] Confirm `arm rc=0` (0 = success); if `FIRST_MATCH` falls back, note `rc` for ALL_MATCHES.
- [ ] Watch for `FGS start blocked` — if present, pivot to the CDM fallback above.
- [ ] Battery: measure idle drain in passive vs the old always-on service.

## Tunables
- `PresenceService.IDLE_TIMEOUT_MS` (5 min).
- `ProximityWake` scan mode (`SCAN_MODE_LOW_POWER`) and match settings.
