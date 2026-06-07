# Proximity wake — passive idle + auto-wake on approach (M2)

> **Status: confirmed on-vehicle (2026-06-07).** The full active→passive→wake→active cycle
> works on the R1S: after the idle timeout the service goes passive (wake lock released, BLE
> torn down, notification reads "Passive · waiting for vehicle"), and the hardware-offloaded
> scan auto-rebuilds the link on approach — with the app **not** foregrounded, the exact case
> the dead-end paths below failed. Passive entry/drive work after the wake.

Goal: make the mobile key something you leave on all day. When the car is away the app idles
with the wake lock released (CPU can sleep) and **auto-rebuilds the link on approach**.

## Design — stay-alive passive (in-process offloaded scan)

In passive we **keep the foreground service + its notification up** but go completely idle:
release the `PARTIAL_WAKE_LOCK`, tear down the BLE sessions, and register a single low-power
**hardware-offloaded scan** with an in-process `ScanCallback`. The chip does the filtering and
wakes the app processor on a match, so `PresenceService` rebuilds the link on approach.

Because we never leave the foreground, reviving BLE needs **no background→foreground
transition** — sidestepping the Android-12 limit that throws
`ForegroundServiceStartNotAllowedException` on a cold FGS start. Plain BLE scanning is something
3p apps may do (we already scan for the sensors), so no special privilege is needed. The
trade-off: an FGS legally requires an ongoing notification, so a "Passive · waiting for vehicle"
notification stays visible while idle.

### Dead ends (both ruled out on-vehicle, 2026-06)
- **Offloaded scan → PendingIntent broadcast → `startForegroundService`**: the cold FGS start is
  blocked by the Android-12 background-start limit. Confirmed: never transitioned.
- **CompanionDeviceManager** (`onDeviceAppeared` is FGS-start allowlisted): `associate()` fails
  with *"3p apps are not allowed to create associations on watch."* Wear OS forbids third-party
  CDM associations outright — unusable for us.

### Flow
1. **Active** — `PresenceService`: foreground, wake lock, sensor sessions, RSSI heartbeats.
   Every PRIMARY/sensor MAC is learned into `VehicleAddressStore` (used as scan filters).
2. **Idle → passive** — `monitorIdle()` after `IDLE_TIMEOUT_MS` (5 min) with no link, gated on
   `proximityWakeEnabled` (default OFF) + not locked → `enterPassive()`:
   - `passive=true`, `_passive` flow flips the UI to "Key passive";
   - `stopBle()` + `releaseWakeLock()`;
   - `ProximityWake.scanForVehicle(…, proximityCallback)` arms the in-process offloaded scan
     (VAS service UUID + learned MACs, low-power, first-match);
   - notification → **"Passive · waiting for vehicle"**. Service stays running (no `stopSelf`).
3. **Wake on approach** — `proximityCallback.onScanResult` → `exitPassive()` stops the scan,
   re-acquires the wake lock, `startBle()`, back to active. `monitorIdle()` resumes.

`exitPassive()` respects the lock gate (if locked when proximity fires, stays down until
unlock); `monitorLock()` stops the passive scan on lock; a plain restart while `passive` (e.g.
START_STICKY after a kill, or any external start) is treated as a wake.

Passive mode is opt-in (`SettingsStore.proximityWakeEnabled`, default OFF); enabling it is a
plain toggle with no association/dialog and works anywhere.

## Components
- `ble/ProximityWake.kt` — `scanForVehicle()/stopScan()` (in-process offloaded `ScanCallback`).
- `service/PresenceService.kt` — idle timer, `enterPassive()/exitPassive()`, `passive` flow,
  lock gating, MAC learning.
- `store/SettingsStore.kt` — `proximityWakeEnabled` (default OFF).
- `ui/ControlScreens.kt` — settings toggle + "Key passive" label driven by `PresenceService.passive`.

## On-device test checklist (validated on-vehicle 2026-06-07)
- [x] Enable Auto power-save (anywhere — plain toggle, no dialog).
- [x] Active → walk away → after the idle timeout: log `→ passive (idle, service alive); proximity
      watch=true`, notification reads "Passive · waiting for vehicle", notification **stays up**,
      wake lock released.
- [x] Walk back → `proximity hit … → wake` then `proximity wake → active`; passive unlock/drive work.
      **Key validation:** this fires with the app not foregrounded — the case the dead-end paths failed.
- [x] Turn the key off while passive → `watch stopped`, notification cleared.

## Tunables
- `PresenceService.IDLE_TIMEOUT_MS` (5 min).
- `ProximityWake` scan mode (`SCAN_MODE_LOW_POWER`) + match settings.
