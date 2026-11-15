# Spec 2: interfaces between app components

**Status:** descriptive. This records how the watch app's components behave and what they expect of
each other at watch `main` `b19ab03` (0.9.12, versionCode 1064) and companion `main` `ee48d38`.
Where code, comments and older docs disagree, the code's behavior is described and the disagreement
is listed in [§12](#12-discrepancies-and-ambiguities). Nothing here changes code.

The wire protocol to the vehicle is Spec 1 (`ble-protocol.md`). The watch ↔ phone enrollment
payloads are also documented in `docs/companion-enrollment-protocol.md`, which is current.

## 1. Component map

Everything runs in one process (`org.fivesevenfive.wearvian`). There is no `INTERNET` permission.

| Component | Kind | Owns |
|---|---|---|
| `WearvianApp` | Application | Installs the keyguard check for `SharedSecretCache`, warms `EnrollmentStore`, clears / prefetches the secret on screen and unlock broadcasts |
| `PresenceService` | Foreground service (`connectedDevice`) | The presence state machine, all long-lived BLE, wake locks, the ongoing notification |
| `VehicleSession` | Object per BLE device | One connection's handshake, heartbeats, command drain, status intake |
| `ActiveCommandManager` | Object per command + static state | One-shot commands and the tile's pre-warmed link |
| `CommandDispatcher` | Singleton | The single entry point for sending a command; picks the route |
| `CommandBus` | Singleton | Queue of commands for the live PK session |
| `PresenceStatus` | Singleton | Per-link state, the "any link UP" flag, the notification summary |
| `VehicleStatus` | Singleton | Last decoded vehicle state, in memory only |
| `MainActivity` + `SetupViewModel` | UI | Setup flow, arm / disarm, command taps |
| `KeyTileService` | Tile | Six command buttons and the key button |
| `SocComplicationService`, `RangeComplicationService` | Complications | Read `TelemetryStore` |
| `BootReceiver`, `UpgradeReceiver`, `KeyOffReceiver` | Receivers | Start / stop the service |
| `CompanionEnrollmentClient` | Data Layer client | Enrollment request / result / ack |
| Stores | SharedPreferences | See [§9](#9-persistent-state) |

### Process-wide shared state

These are the contracts most components rely on. All are plain statics or `StateFlow`s; none
survives the process.

| State | Written by | Read by |
|---|---|---|
| `PresenceService.running` | service start / `onDestroy` | UI, tile, `CommandDispatcher`, pre-warm gate |
| `PresenceService.passive` | passive entry / exit, lock, `onDestroy` | UI, `CommandDispatcher` |
| `PresenceService.parkedNearby` | passive entry / exit, departure, lock | `CommandDispatcher` |
| `VehicleSession.heartbeatsPaused` | the service only | every session's heartbeat loop, the service's sensor loop and notification |
| `PresenceStatus.connected` | sessions (`set`), service (`remove`, `reset`) | service monitors |
| `PresenceStatus.primaryLink` | the PK session | bring-up wake lock |
| `CommandBus.primaryAuthenticated` | the PK session, `stopBle` | `CommandBus.submit` (TTL choice) |
| `CommandBus.lastSubmitMs` / `lastCommandMs` | `submit`, `noteManual`, `noteCommand` | watch-key burst; fruitless-wake hold-off |
| `VehicleStatus.state` | the PK session (`update`), service (`clear`), reset (`reset`) | service monitors, UI, tile |
| `SharedSecretCache` | everyone who needs the secret | sessions, one-shots |
| `ReconnectHurry` | `CommandBus.submit`, burst start, driving-doze exit | session backoff waits, the sensor loop tick |

## 2. Presence state machine

`PresenceService` holds the state in a handful of flags, not an enum. The combinations that occur
are named below; the names in quotes are the notification's content text.

| State | Flags | BLE | Main wake lock | Heartbeats |
|---|---|---|---|---|
| **Off** | service not running | none | no | no |
| **Active** | running, not passive / locked / driving | PK + sensor sessions | yes | yes |
| **Driving-doze** (phone key) "In gear · presence paused" | `driving` | PK session only; sensors closed | no | paused |
| **Standby** (watch key) "Standby · open a door to drive" | `watchMode`, `heartbeatsPaused` | PK session; no sensors | no | paused |
| **Burst** (watch key) | `watchMode`, not paused | PK + sensor sessions | yes | yes |
| **Passive, parked nearby** "Parked nearby" | `passive`, `!seenDeparture` | offloaded scan only | no | none |
| **Passive, departed** "Waiting for vehicle" | `passive`, `seenDeparture` | offloaded scan + PK autoConnect | no | none |
| **Locked** "Watch locked" | `locked` | none | no | none |
| **Bluetooth off** "Bluetooth off" | `btOff` (overlays active or passive) | none | no | none |

The notification title carries a coarser mode: `paused` when locked, `passive` when passive, else
`active`. `keyArmed` (persisted) is the user's intent and is independent of all of the above: an
armed key can be in any state including Off (service killed and not yet restarted).

### Serialization

All transitions run on a single-thread coroutine scope and, where they suspend, under the
`transition` mutex. Callbacks from other threads (scan results, GATT autoConnect, broadcasts,
`onStartCommand`, alarms) only launch onto that scope. The keyguard is re-read at the top of every
transition that depends on it (`syncLockStateLocked`); the polled `locked` field never decides
alone.

### Transitions

**Off → running** (`onStartCommand`)

1. `ServiceStartGate`: no Bluetooth permission → stop. A system sticky restart (null intent) with
   the key not armed → stop; during bedtime → disarm and stop.
2. `startForeground`; refused → post "key stopped" alert, stop.
3. No enrollment → stop.
4. `watchMode = enrollment.asWatch || DebugOverrides.forceWatch`.
5. `StartupPolicy.resolve(trigger, locked, passiveIdle, passiveSeenDeparture)`:

| Trigger | Locked | Result |
|---|---|---|
| any | yes | **Locked**; unlock restores passive if the trigger was go-passive, or upgrade with persisted passive |
| `ACTION_GO_PASSIVE` | no | manual passive |
| `ACTION_UPGRADE_RESTORE` with `passiveIdle` persisted | no | **Passive**, restoring the persisted `seenDeparture` |
| anything else (app open, boot, sticky restart, tile) | no | **Active** |

A start that reaches an already-running service: go-passive → manual passive; passive → wake to
active; otherwise ignored.

**Active → Passive**, three routes:

| Route | Condition | Sub-state entered |
|---|---|---|
| Idle (`monitorIdle`) | No link UP, then a 45 s offloaded-scan probe sees no vehicle node and no reconnect. If nodes are seen or the probe scan fails: wait for a reconnect until 5 min after the links dropped. | departed |
| Parked-idle (`monitorStableIdle`, phone keys only) | Status `valid && live`, not driving, not in gear, and (lock, any door, any window, frunk, liftgate, charge state, asleep, gear) unchanged for 15 min, or 5 min when charge state is charging / starting / plugged-idle. Sampled every 30 s. | parked nearby |
| Manual (`ACTION_GO_PASSIVE`, the UI's long-press / settings button) | Watch not locked | by whether a link is UP at that moment |

Passive entry (`enterPassiveLocked`): set `passive`; `seenDeparture = !PresenceStatus.connected`
(or the restored value); persist `passiveIdle` and `passiveSeenDeparture`; clear driving-doze and
`heartbeatsPaused`; stop all sessions and wait for them to close; drop any one-shot or pre-warmed
link; mark vehicle status stale; release the wake lock; arm the offloaded scan; arm the PK
autoConnect only if departed; refresh the tile.

**Inside Passive**

- The scan (`ProximityWake`): `LOW_POWER`, sticky match, one advertisement, `FIRST_MATCH` and
  `MATCH_LOST`, filtered on the vehicle service UUID plus every stored MAC. `ProximityTracker`
  keeps the set of nodes currently seen.
- **Parked nearby → departed**: every seen node lost and none found for 30 s (`DEPART_DEBOUNCE_MS`,
  under a timed wake lock), then `markDeparted`: set `seenDeparture`, persist it, arm the PK
  autoConnect.
- **Blind departure**: arming the scan while parked nearby assumes all nodes lost and starts a 90 s
  countdown (30 s after a Bluetooth restart); a sighting cancels it, silence confirms departure.
  If an automatic wake follows a blind departure within 15 min the car never left
  (`BlindDeparturePolicy`): blind countdowns are then skipped until a departure is observed or
  passive is entered with the links really down.
- **Sighting while parked nearby**: ignored, except as a return when the set has been empty for the
  window, or a node was lost with nothing seen for 3 min, or the scan saw nothing for 3 min after
  arming.
- **Scan failure**: retried by `AlarmManager` (30 s doubling to 5 min); the notification says
  "Proximity scan unavailable · retrying" from the third failure; the autoConnect is armed if
  departed, else a departure countdown starts.

**Passive → Active** (`exitPassiveLocked`)

| Cause | Automatic? |
|---|---|
| `FIRST_MATCH` judged a return, unless held off after fruitless wakes (5, 10, 20, 40 min) | yes |
| PK autoConnect completed | yes |
| `PresenceService.start` on a passive service (app toggle, tile key button) | no |
| `wakeToActive` after a one-shot UNLOCK while parked nearby | no |

On exit: bedtime is re-checked (rising edge → key off instead); the keyguard is re-read (locked →
go to Locked, remembering passive); scan and autoConnect are torn down; then `startActivePresence`.

**Active ↔ Driving-doze** (phone keys, `monitorDriving`)

- Enter: gear is R, N or D on a `valid && live` status while any link is UP. Sets
  `heartbeatsPaused`, closes the sensor sessions, releases the wake lock.
- Exit: gear back to Park or unknown. Reacquires the wake lock, resumes heartbeats, hurries the
  sensor loop.
- If no link is UP while in gear and dozing: exit and disable driving-doze until the next Park.
- A dropped PK link while dozing takes a bounded "bring-up" wake lock (≤ 30 s, until PK is UP).

**Standby ↔ Burst** (watch keys, `monitorWatchPresence` + `WatchBurstPolicy`)

- A watch key comes up in Standby and never runs parked-idle or driving-doze.
- Burst triggers: a door-open edge; an asleep → awake edge; the first live status after start /
  passive / lock when the car is awake, parked and unlocked or open; any command submit to the
  queue; a successful one-shot UNLOCK.
- A burst lasts 3 min after the last trigger. Status-driven bursts also end when the car reports
  asleep or in gear; a manual one does not.

**Any → Locked** (`LockSyncPolicy`)

- Detected by screen-off / screen-on / user-present broadcasts and a 2 s poll.
- Remembers `wasPassive` and the passive sub-state; tears down scan, autoConnect, all sessions;
  clears `SharedSecretCache`; releases wake locks.
- Independently, each session stops writing heartbeats within about 1 s of the keyguard locking,
  and `CommandDispatcher` and one-shots refuse commands on a fresh keyguard read.

**Locked → Passive / Active**: unlock restores passive (with the remembered sub-state) if it was
passive at lock, else active. The secret is prefetched first.

**Bluetooth off / on**: off tears down whatever the current mode holds; on re-reads the keyguard
and rebuilds the same mode (passive re-arms the scan with a 30 s blind window).

**Bedtime** (`BedtimePolicy`, `BedtimeMode`)

- State is read from two undocumented `Settings.Global` keys: `bedtime_mode` and
  `setting_bedtime_mode_running_state`. Neither present → `UNKNOWN` → the feature does nothing.
- The service baselines the state at start. A later change to `ACTIVE` disarms the key, posts an
  alert notification and stops the service. Checked by a content observer, the screen broadcasts,
  the 2 s poll and every passive exit.
- The key is not re-armed when bedtime ends. A key the user turns on during bedtime stays on.
- Boot, upgrade and sticky restart during bedtime disarm the key instead of starting it.

**Any → Off**: UI toggle, the notification's "Disable" action (`KeyOffReceiver`), bedtime, reset or
wipe, or the system killing the process. `onDestroy` tears everything down, marks status stale,
refreshes the tile and removes the notification.

### Timers and wake locks

| Purpose | Mechanism | Bound |
|---|---|---|
| Active presence, burst | `wearvian:presence` partial wake lock | untimed |
| Departure debounce | timed wake lock | window + 5 s |
| Idle probe | timed wake lock | 45 s + 5 s |
| Session bring-up without the main lock | `wearvian:bring-up` | 30 s + 5 s |
| Driving-doze sensor close | timed wake lock | 5 s |
| Lock sync on a broadcast | timed wake lock | 5 s |
| Passive scan retry | `ELAPSED_REALTIME_WAKEUP` alarm + 5 s wake lock | 30 s to 5 min |
| One-shot command | `wearvian:oneshot` | deadline + 5 s |

Coroutine delays do not advance while the CPU is suspended, so every decision that must survive
sleep is either under one of these or judged lazily from `elapsedRealtime()` timestamps.

## 3. `PresenceService` API

| Call | Effect |
|---|---|
| `start(context)` | `startForegroundService`; false if Bluetooth permission is missing or the system refuses |
| `startAfterUpgrade(context)` | Same, with `ACTION_UPGRADE_RESTORE` |
| `goPassiveNow(context)` | Same, with `ACTION_GO_PASSIVE` |
| `wakeToActive(context)` | In-process wake if a service is running (no foreground start needed), else `start` |
| `stop(context)` | `stopService` |
| `running`, `passive`, `parkedNearby` | `StateFlow<Boolean>` |

Callers set `keyArmed` themselves; the service only ever clears it (bedtime).

## 4. Sessions ↔ service

- The service launches sessions as children of one loop job; cancelling that job closes every
  link. Each session's `finally` disconnects and closes its GATT.
- The loop computes the shared secret once (retrying on failure, polling every 2 s while the watch
  is locked) and passes the same array to every session; it is zeroed when the loop ends.
- A session reports `CONNECTING → CONNECTED → UP → DOWN` to `PresenceStatus` under a unique label.
  A `DOWN` for an unknown label is ignored.
- Sessions read `heartbeatsPaused` and the keyguard themselves each beat; the service does not
  call into a running session.
- The PK session alone drains `CommandBus`, feeds `VehicleStatus`, and maintains
  `CommandBus.primaryAuthenticated`.
- Learned device addresses are added to `VehicleAddressStore` and reused for the passive scan
  filters and for starting sensor sessions without a scan.

## 5. Command path

`CommandDispatcher.send(context, enrollment, keyManager, code, label)` is the only entry point (app
and tile). It returns `Sent` or `Failed(reason)`.

| Fresh keyguard read | Service | Route |
|---|---|---|
| locked | any | **Blocked** → `WATCH_LOCKED`; secret cache cleared |
| unlocked | running and not passive | **Live session** via `CommandBus` |
| unlocked | not running, or passive | **One-shot** via `ActiveCommandManager` |

**Live session.** `submit` stamps the command with a TTL: 5 s if the PK session was authenticated
at submit, else 20 s. The PK session polls the queue every heartbeat tick, including while
heartbeats are paused for driving or standby, but not while the watch is locked. A command older
than its TTL is dropped, never sent late. The caller waits TTL + 1 s for pickup, then up to 6 s for
the write. Failure is always reported as `NOT_CONNECTED`. The queue is cleared when an
authenticated PK session ends and whenever the service stops BLE. There is no fallback to a
one-shot.

**One-shot.** Deadline 35 s from the tap to the command write; past it the frame is never written
(`EXPIRED`). One retry for a retryable failure. Uses the tile's pre-warmed link if one is fresh.
Success is reported at the write acknowledgement; the link closes in the background afterwards.
Follow-ups after it closes: a successful UNLOCK counts as a manual action for a watch-key burst,
and an UNLOCK sent while parked nearby wakes the service to active.

**Failure reasons** shown to the user (double buzz + toast, `CommandFeedback`): watch locked,
vehicle not paired, vehicle not reachable, vehicle not connected, timed out, couldn't send.

## 6. Tile

- Renders from `SettingsStore.keyArmed` (key button gold when armed) and `VehicleStatus.state`
  (the button matching the current state is filled: white when live, gray when stale).
- Command buttons are `LoadAction`s carrying a random per-render nonce. A tap is acted on only if
  its nonce differs from `SettingsStore.lastTileClickNonce`, which is then updated; this stops a
  refresh from replaying the last tap.
- The key button launches `MainActivity` with `EXTRA_ACTIVATE_KEY`; the activity arms and starts
  (or wakes) the key once the phase is `BONDED`.
- On tile enter: if the watch is locked, clear the secret; if the service is not running, pre-warm
  (ECDH + connect + discover + subscribe to PK, 10 s connect window, held 15 s); otherwise only
  prefetch the secret. On tile leave: drop an unused pre-warmed link unless a tap is waiting on it.
- `TileRefresher.refresh` is called by the service on mode changes and on debounced (3 s) status
  changes; it is deferred while the app is in the foreground and flushed in `onStop`.

## 7. Receivers

| Receiver | Trigger | Behavior |
|---|---|---|
| `BootReceiver` | `BOOT_COMPLETED` | Armed and not bedtime → `start` (comes up Locked until first unlock). Armed and bedtime → disarm, refresh tile. |
| `UpgradeReceiver` | `MY_PACKAGE_REPLACED` | Armed and not bedtime → `startAfterUpgrade`. Armed and bedtime → disarm, refresh tile. |
| `KeyOffReceiver` | Notification action | Disarm, `stop`. |

`SetupViewModel.refresh` (every app open) also starts the service if the key is armed and it is not
running.

## 8. UI ↔ service

- Phases: `LOADING`, `NEEDS_SETUP`, `AWAITING_COMPANION`, `ENROLLED`, `PAIRING`, `BONDED`, `ERROR`.
- Status label: "Key off" when not armed; "Key passive" when passive, or armed but not running;
  "Key active" otherwise.
- Toggle → `setPresence(on)`: writes `keyArmed`, then `start` or `stop`.
- Pairing success sets `Enrollment.bonded`, arms the key and starts the service.
- `reset` keeps the Keystore key and clears everything else; `wipeIdentity` also deletes the key.

## 9. Persistent state

| Store | File | Contents | Encrypted |
|---|---|---|---|
| Keystore | alias `wearvian_phone_key` | EC private key | hardware |
| `EnrollmentStore` | `wearvian_enrollment` | userId, vehicleId, VIN, vasVehicleId, vehiclePublicKey, vasPhoneId, identityId, asWatch, bonded | yes |
| `SettingsStore` | `wearvian_settings` | keyArmed, forceR1t, passiveIdle, passiveSeenDeparture, lastTileClickNonce | no |
| `VehicleAddressStore` | `wearvian_vehicle_addrs` | learned MACs, PK MAC | no |
| `TelemetryStore` | `wearvian_telemetry` | last SoC, range, time | no |
| `PendingEnrollmentRequests` | `wearvian_enroll_requests` | request ids and send times (24 h) | no |

`VehicleStatus`, `PresenceStatus`, `CommandBus` and the shared secret are memory only.

## 10. Watch ↔ companion

Transport: Wear Data Layer between two apps with the same `applicationId` and signing key. The
watch advertises capability `wearvian_watch`; the phone `wearvian_companion_enrollment`.

| Path | Direction | Kind | Payload (JSON, UTF-8) |
|---|---|---|---|
| `/wearvian/enroll/request` | watch → phone | message | `v`, `requestId`, `publicKey`, `deviceName`, `deviceType` |
| `/wearvian/enroll/result/<requestId>` | phone → watch | DataItem, bytes under `payload` | success result |
| `/wearvian/enroll/result` | phone → watch | message | success or error result |
| `/wearvian/enroll/ack` | watch → phone | message | `v`, `requestId` |

Success result: `v`, `requestId`, `status: "ok"`, `asWatch`, `userId`, `vehicles[]` each with
`vehicleId`, `vin`, `vasVehicleId`, `vehiclePublicKey`, `vasPhoneId`, `identityId`. Error result:
`v`, `requestId`, `status: "error"`, `error`. No Rivian tokens cross the link.

Sequence:

1. Watch ensures its key exists, records the request id (valid 24 h), sends the request to the
   capability node, or to every connected node if none advertises it, and waits 5 min.
2. Phone's listener service stores the one pending request (24 h) and notifies the user.
3. After sign-in and enrollment the phone puts the DataItem (urgent) and also sends the message.
4. Watch accepts the first result whose `requestId` matches, saves the **first** vehicle, deletes
   the stored items for its outstanding requests and sends the ack to every connected node.
5. Phone deletes its DataItem on the ack (3 min wait in the UI, or later in the listener service).
6. A watch that stopped waiting picks a stored result up on its next app open while not enrolled.

The companion's "Enroll as watch key" checkbox (default on) decides the cloud device type and the
`asWatch` it reports; an existing enrollment of the same key reports its own type.

## 11. Invariants

1. The private key and the shared secret never leave the watch; the companion sees only the public
   key.
2. No heartbeat, command or ECDH while the keyguard reads locked.
3. A command is never written later than its TTL (queue) or deadline (one-shot).
4. At most one handshake on PK at a time.
5. While the service is running and not passive, the PK link belongs to the presence session.
6. The foreground service is never left and re-entered to change mode; passive and locked keep it
   running so that waking needs no background foreground-service start.
7. A filled control shows vehicle state, not the action taken.
8. Only an app upgrade resumes persisted passive; every other start comes up active or locked.

## 12. Discrepancies and ambiguities

Found while writing this spec. None are fixed here. D-numbers continue from Spec 1.

**D16. Comments say a paused heartbeat loop does not drain commands; it does.**
`CommandBus.lastSubmitMs`, `WatchBurstPolicy` and `monitorWatchPresence` all say a queued command
"is only drained while heartbeats run" and justify the manual burst trigger that way.
`VehicleSession` drains the queue in the paused branch for driving and standby (only the locked
pause blocks it). The burst on a manual command is therefore needed for drive-enable after an
unlock, not for delivery.

**D17. The app says "Key active" while the notification says "paused".**
`buildNotification` states its mode word "matches the app's key-status EXACTLY". When the watch is
locked the notification mode is `paused`, but the UI has no locked state and shows "Key active"
(running, not passive). Bluetooth off, driving-doze and standby are also "active" in both, with
only the notification text saying otherwise.

**D18. Manual passive from Off, or after a locked start, enters "departed" beside the car.**
The sub-state is computed as "no link UP → departed". Starting the service straight into passive
(key was off) or restoring passive on unlock after a locked go-passive start has no links by
construction, so it arms the PK autoConnect even when parked beside the car. The autoConnect then
completes at once and wakes the key to active. The upgrade path avoids this by restoring the
persisted sub-state; these two paths do not.

**D19. "No link UP" and "PK up" are used interchangeably.**
`PresenceStatus.connected` is true if any link, including a sensor, is UP. It gates idle → passive,
driving-doze's link check, the parked-nearby decision and the fruitless-wake counter. Commands and
vehicle status need the PK specifically. With PK down and a sensor up, the key stays active with
no status and no command path, and commands fail after 20 s as "vehicle not connected".

**D20. No one-shot fallback while active.**
With the service running and not passive, a command is only ever queued. If PK is unreachable but
the service has not yet gone passive (up to 45 s to 5 min), or Bluetooth is off, or the phone key
is "not paired", every tap waits out the TTL and fails. This is by design for the PK-ownership
rule, but the wait is up to 21 s per tap.

**D21. Bedtime key-off is announced on only one of four paths.**
A running service posts the "key turned off" alert. Boot, upgrade and sticky restart during bedtime
disarm silently (the tile goes dark). `allowBackgroundStart` treats `UNKNOWN` as allowed, which is
the documented fail-safe.

**D22. `setPresence(true)` reports running regardless of the start result.**
`PresenceService.start` returns false when Bluetooth permission is missing or the system refuses
the foreground start; the view model sets `presenceRunning = true` anyway. The `running` flow never
emits (it was already false), so the UI can show "Key active" with no service until the next
`refresh`.

**D23. Stale "lands later" comments about watch mode.**
`Enrollment.asWatch`, `PresenceService.watchMode` and `SetupUiState.asWatch` are commented as stored
now with behavior "in a later branch" / "incrementally". The standby and burst lifecycle is on
`main`. `monitorWatchPresence` also still says the standby link's survival is "UNVALIDATED
on-vehicle"; whether that is still true is not recorded.

**D24. Log lines that misstate the cause.**
`goPassive` logs "idle 5m → passive" when the 45 s probe decided it. `markDeparted` logs the 30 s
debounce when a 90 s blind window ran. `StartTrigger.NORMAL` is documented as covering a "bond
auto-start" that does not exist. The notification action is labelled "Disable" where the comments
and manifest say "Off".

**D25. Watch ↔ companion loose ends.**
- `v` is sent in every payload and checked by neither side.
- The watch always sends `deviceType: "watch"` and `deviceName: "Pixel Watch 4"` (hard-coded).
- Only the first vehicle in a result is saved; the rest are discarded without notice.
- Error results are message-only. If the watch is not listening when the phone fails, the watch
  learns nothing and times out after 5 min.
- Results are accepted from any node on a matching `requestId`; trust rests on the Data Layer's
  same-app-and-signature rule.
- The phone holds one pending request; a second watch request replaces the first.

**D26. The service's state is implicit.**
The nine states in §2 are combinations of `passive`, `locked`, `driving`, `btOff`, `watchMode`,
`seenDeparture`, the static `heartbeatsPaused` and whether the wake lock is held. Several
transitions reset `heartbeatsPaused` to different values (false on lock and passive entry, `watchMode`
on Bluetooth off, true in `startActivePresence` for a watch key). They agree today because every
path back to active goes through `startActivePresence`, but nothing enforces it.
