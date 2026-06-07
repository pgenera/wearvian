# Proximity wake — passive idle + auto-wake on approach (M2)

Goal: make the mobile key something you leave on all day without draining the watch. When the
car is away the app idles at **near-zero battery** (foreground service stopped, no wake lock),
and the OS **cold-starts it on approach**.

## Design — CompanionDeviceManager (CDM)

The wake mechanism is **CompanionDeviceManager presence observation**, the OS-sanctioned
"wake my app when my companion device is near" API. Its callback
(`CompanionDeviceService.onDeviceAppeared`) is **allowlisted to start a foreground service
from the background** — the exact thing the Android-12 limit blocks for an ordinary
broadcast. So we can fully stop the presence service when idle and rely on the OS to
cold-start it, even from a killed process.

> Earlier attempt (removed): an offloaded `startScan(…, PendingIntent)` whose broadcast tried
> to start the FGS. Confirmed on-vehicle (2026-06-06) that the cold start throws
> `ForegroundServiceStartNotAllowedException`, so it never transitioned. CDM replaces it.

### Passive mode is opt-in
`SettingsStore.proximityWakeEnabled` defaults **OFF** — by default the key stays
foreground-active whenever it's on. Enabling "Auto power-save" performs a **one-time CDM
association** (a system chooser dialog naming the Rivian phone key); the setting is only
persisted once the user confirms.

### Flow
1. **Enable** (settings toggle) → `SetupViewModel.setProximityWake(true)`:
   - if not yet associated, emit `associationRequest`; `MainActivity` calls
     `CompanionManager.requestAssociation(...)` and launches the chooser `IntentSender`;
   - on confirm → `startObservingDevicePresence(mac)` + persist `proximityWakeEnabled=true`.
2. **Active** — `PresenceService`: foreground, wake lock, sensor sessions, RSSI heartbeats.
   Every PRIMARY/sensor MAC is learned into `VehicleAddressStore` (informational).
3. **Idle → passive** — `monitorIdle()` after `IDLE_TIMEOUT_MS` (5 min) with no link →
   `goPassive()` (gated on `proximityWakeEnabled` + not locked) → `enterPassive()`:
   ensures CDM is observing, then `stopSelf()`. Wake lock released, notification cleared,
   process may be killed. **Refuses to stop if there's no association** (nothing would wake us).
4. **Wake on approach** — the OS binds `VehiclePresenceService.onDeviceAppeared(...)`, which
   (if the key is armed + enrolled) calls `PresenceService.start()` → back to active.
   `onDeviceDisappeared` is logged only; teardown stays driven by the idle timer to avoid a
   false stop on a brief BLE blip.

Observation lifecycle: started on enable / when re-entering passive / on `refresh()` while
armed+passive; **stopped** when the key is deactivated (toggle off, notification "Off",
reset, wipe) so a deliberately-off key can't be re-woken.

## Components
- `ble/CompanionManager.kt` — wraps CDM: `isSupported`/`isAssociated`, `requestAssociation`
  (BLE name filter on `Rivian Phone Key`, single-device), `startObserving`/`stopObserving`.
- `service/VehiclePresenceService.kt` — `CompanionDeviceService`; `onDeviceAppeared` cold-starts presence.
- `service/PresenceService.kt` — idle timer, `goPassive()/enterPassive()` (stopSelf), MAC learning.
- `ui/MainActivity.kt` — launches the association `IntentSender`, reports the result to the VM.
- `ui/SetupViewModel.kt` — toggle/association lifecycle, observation start/stop.

## Manifest
- `<uses-feature android:name="android.software.companion_device_setup" required="false"/>`
- `REQUEST_COMPANION_RUN_IN_BACKGROUND`,
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
- `<service .VehiclePresenceService>` with `BIND_COMPANION_DEVICE_SERVICE` +
  `<action android:name="android.companion.CompanionDeviceService"/>`

## On-device test checklist (needs the vehicle)
- [ ] First enable of Auto power-save → system association dialog shows the Rivian phone key;
      confirm → log `cdm: associated …` then `cdm: observing …`.
- [ ] Active → walk away → after 5 min: log `→ passive (service stopping); CDM observing`,
      notification clears, wake lock released, idle battery ~flat.
- [ ] Walk back **with the app NOT in the foreground / process killed** → log
      `cdm: device appeared …` then presence comes up; passive unlock/drive work. (This is the
      v1 failure case — the key validation for CDM.)
- [ ] Turn the key off while passive → `cdm: observation stopped`; approach no longer wakes it.
- [ ] Cancel the association dialog → toggle reverts to off, nothing observed.

## Tunables
- `PresenceService.IDLE_TIMEOUT_MS` (5 min).
- Association filter (`CompanionManager`): currently device-name `Rivian Phone Key`,
  `setSingleDevice(true)`.

## If CDM proves unreliable on Wear OS
Wear OS CDM presence has historically been spottier than on phones. If `onDeviceAppeared`
doesn't fire reliably, the fallback is the stay-alive design (keep the FGS up in passive with
the wake lock released + an in-process offloaded `ScanCallback`), which wakes without any
background FGS-start because we never leave the foreground — at the cost of a persistent
notification while idle.
