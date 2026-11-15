# Spec 1: app ↔ car BLE protocol

**Status:** descriptive. This records what the watch app does on the wire at watch `main`
`b19ab03` (0.9.12, versionCode 1064). It is written from the code, not from the vehicle: where the
code and its comments or the older docs disagree, the code's behavior is described and the
disagreement is listed in [§12](#12-discrepancies-and-ambiguities). Nothing here changes code.

History and the reverse-engineering trail are in `docs/passive-entry-protocol.md`; this file is the
current-state reference. Source files are under
`wear/app/src/main/kotlin/org/fivesevenfive/wearvian/` (`ble/`, `service/`) and
`wear/core-crypto/src/main/kotlin/org/fivesevenfive/wearvian/` (`protocol/`, `crypto/`).

Characteristics are referred to by the short handle tags the code and logs use (`0x12`, `0x15`,
`0x1b`, `0x1c`, `0x20`). These are labels, not values the code depends on: every characteristic is
looked up by UUID.

## 1. Roles and devices

The watch is always the BLE **central** and GATT **client**. The vehicle exposes several
peripherals:

| Device | How it is found | Label in logs | Role |
|---|---|---|---|
| Phone-key module ("PRIMARY", "PK") | Advertised local name `Rivian Phone Key` at pairing; afterwards by the stored MAC | `PK` | Handshake, heartbeats, commands, vehicle status |
| Location sensors | Advertise the vehicle's VAS id as a 128-bit service UUID; name `Rivian Sensor N` | `S1`…`Sn`, `S?-xxxx` | Handshake and heartbeats only (ranging) |

`RivianBle.vehicleServiceUuid` turns `vasVehicleId` (dashed UUID or 32 hex chars) into that service
UUID. A scan hit that is not named `Rivian Sensor …` and is not the PK is labelled `PK2` and treated
as a sensor.

There are four kinds of GATT client the app opens, all to the same characteristics:

1. **Pairing** (`PairingManager`): one-time, to PK.
2. **Presence session** (`VehicleSession`): long-lived, one to PK and one per sensor.
3. **One-shot command** (`ActiveCommandManager`): short-lived, to PK, used when no presence session
   owns the PK link. Includes the tile's pre-warmed link.
4. **Passive wake trigger** (`PassiveAutoConnect`): a bare `autoConnect=true` connection to PK that
   does no GATT traffic at all; its connect event is the signal.

At most one of clients 2 and 3 runs a handshake on PK at a time (see [§9](#9-concurrency-on-the-pk-link)).

## 2. GATT surface

All UUIDs from `RivianBle`.

| Tag | UUID | Used as | Direction |
|---|---|---|---|
| `0x12` | `AA49565A-4D4F-424B-4559-5F5752495445` | Phone id in, vehicle id echo out | write + notify |
| `0x15` | `E020A15D-E730-4B2C-908B-51DAF9D41E19` | Phone nonce in, vehicle nonce out | write + notify |
| `0x1b` | `52495649-414e-2052-4541-442043484152` ("RIVIAN READ CHAR") | Presence heartbeat | write |
| `0x1c` | `afb2e704-842b-4e6a-9bd2-b1b305828f24` | Vehicle status stream | notify (PK only) |
| `0x20` | `5ae32b92-eafb-471b-afe8-e88eec4a4774` | PhoneProfile, SensorInformation, encrypted commands in; ranging chatter, command acks and encrypted status out | write + notify |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | Notification enable (`01 00`) | descriptor write |

Characteristics are found by scanning every discovered service for the UUID; the code does not
depend on which service owns them. `SERVICE_ACTIVE_ENTRY`
(`52495356-454e-534f-5253-455256494345`) and `CHAR_ACTIVE_ENTRY`
(`5249565F-4D4F-424B-4559-5F5752495445`) are defined but never used.

All writes are write-with-response except the heartbeat, whose type depends on the characteristic's
properties ([§6](#6-presence-heartbeat-0x1b)). Indications are never used.

## 3. Link layer

- **No encryption or bonding is needed for a session.** Sessions and one-shots run in the clear and
  never call `createBond`. Authentication is entirely the application-layer handshake.
- **Pairing does bond the PK** (`createBond`, 60 s) as its final step and fails if bonding is
  rejected. Before connecting it removes any existing bond on that device (hidden `removeBond`, by
  reflection) because a bond left by an earlier enrollment makes the stack try to encrypt with a
  stale key and the vehicle drops the link.
- **Locating PK** (`PhoneKey.find`): the MAC stored at pairing, preferring the bonded entry for it;
  if the bond was removed, `getRemoteDevice(mac)`; only when no MAC was stored (installs paired
  before it was recorded) the first bonded device named `Rivian Phone Key`. Null while Bluetooth is
  off.
- **MTU.** The app never calls `requestMtu` and does not implement `onMtuChanged`. See D1.
- **Connection priority.**
  | Situation | Request |
  |---|---|
  | Presence session after service discovery, and when heartbeats resume | `BALANCED` |
  | Presence session while heartbeats are paused (watch locked, driving, watch-key standby) | `LOW_POWER` |
  | One-shot command (own connect, or on taking over a pre-warmed link) | `HIGH` |
  | Pre-warmed link while held idle | none (stack default) |
  The return value is logged; the resulting interval is not observed.
- **PHY, data length, and connection parameters** are left to the stack.

## 4. Cryptography

Pure-JVM implementations in `core-crypto`; the private key is in the Android Keystore.

| Item | Definition |
|---|---|
| Key pair | secp256r1, Keystore alias `wearvian_phone_key`, `PURPOSE_AGREE_KEY`, non-exportable, StrongBox if available else TEE, `setUnlockedDeviceRequired(true)` |
| Public key encoding | hex X9.62 uncompressed point, `04 ‖ X(32) ‖ Y(32)` |
| `sharedSecret` | ECDH(watch private, `vehiclePublicKey`), computed inside the Keystore |
| `sessionSecret` | HKDF-SHA256(`sharedSecret`), salt = 32 zero bytes, empty info, 32 bytes out |
| `aesKey` | first 16 bytes of HMAC-SHA256(`sessionSecret`, `CONSTANT_B`) |
| `CONSTANT_B` | `07862b2ed8328106a7cdff7b5c1b23bedffddb33a6aa3c82b0fedc784485df77` |
| AAD | `pNonce XOR vNonce` (16 bytes) |
| Cipher | AES-128-GCM, 12-byte IV, 16-byte tag |

`sharedSecret` is static per (watch key, vehicle key). `SharedSecretCache` keeps it in memory only
while the watch is unlocked and zeroes it on any locked keyguard reading; the ECDH takes 0.5 to 2 s
in StrongBox, so it is prefetched (on unlock, when the tile comes into view, alongside a one-shot's
connect). `pNonce` and `vNonce` are per connection, so every MAC and ciphertext is bound to one
connection.

## 5. Session handshake

Performed on every (re)connection, identically for PK and sensors except where marked. Order as
implemented in `VehicleSession.runOnce`:

| # | Step | Timeout | On failure |
|---|---|---|---|
| 1 | `connectGatt(autoConnect)` and wait for `STATE_CONNECTED` | 35 s direct, 10 min autoConnect | attempt counted `NO_CONNECT` |
| 2 | `discoverServices` | 5 s | session ends (`CONNECTED`) |
| 3 | `requestConnectionPriority(BALANCED)` | none | ignored |
| 4 | Enable notify on `0x12`, then `0x15` | 5 s each | logged, continues |
| 5 | Write `10 80` (PhoneProfile) to `0x20`, if `0x20` exists | 5 s | logged, continues |
| 6 | Write phone id (16 bytes) to `0x12`; wait for the notify | 5 s write, 3 s echo | no echo: continues; echo present and ≠ `vasVehicleId`: session ends |
| 7 | Write `pNonce(16) ‖ HMAC-SHA256(sessionSecret, pNonce)` (48 bytes) to `0x15`; wait for the notify | 5 s each | session ends |
| 8 | `vNonce` = first 16 bytes of the `0x15` notification. Link is now **UP** (authenticated) | | |
| 9 | Enable notify on `0x20`; write `01` (SensorInformation) to `0x20` | 5 s each | logged, continues |
| 10 | PK only: enable notify on `0x1c` | 5 s | logged, continues |
| 11 | `readRemoteRssi`, then the heartbeat loop | | |

Phone id on the wire is `vasPhoneId` with dashes removed, hex-decoded. The vehicle id comparison is
hex, case-insensitive, against `vasVehicleId` with dashes removed. `pNonce` is 16 bytes from
`SecureRandom`.

`0x1b`, `0x12` and `0x15` are required characteristics (missing: the session ends). `0x20` and
`0x1c` are optional; a session without `0x20` still heartbeats but cannot send commands.

**Pairing** (`PairingManager.pair`) uses steps 1, 2, 4, 6, 7 with different bounds: a 15 s
`LOW_LATENCY` scan filtered on the device name `Rivian Phone Key` (the first hit is used), 35 s
connect, 5 s per GATT operation, every step mandatory, then stores the PK MAC (after the vehicle id
matches) and bonds. The vehicle must be in its "Set Up" state for this key.

**One-shot command** (`ActiveCommandManager`) differs from the session handshake:

- always a direct connect, bounded by the tap's deadline;
- `requestConnectionPriority(HIGH)` before service discovery;
- subscribes `0x12`, `0x15` **and `0x20`** before authenticating; never `0x1c`;
- writes neither PhoneProfile nor SensorInformation;
- the vehicle id echo is **mandatory** (5 s);
- sends exactly one command with `csn = 0` and disconnects.

## 6. Presence heartbeat (`0x1b`)

Frame, 37 bytes, plaintext:

```
counter (4, LE) ‖ rssi (1) ‖ HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ counter (4, LE) ‖ rssi)
```

- `counter` starts at 0 for each connection and increments once per beat that was issued without
  the link being cycled (it advances after a timed-out or failed write too, not after a skipped
  beat).
- `rssi` is the last `readRemoteRssi` result as a signed byte; −128 (`0x80`) until the first read
  completes. RSSI is re-read every 4th beat (about every 1.2 s).
- **Period:** 300 ms between the end of one beat's handling and the next (so the real rate is
  slightly under 3.3 Hz, and lower when writes are slow).
- **Write type:** with-response if `0x1b` advertises `PROPERTY_WRITE`, else without-response.
- **Write timeout:** 1.5 s per beat. A timed-out write is not abandoned: later beats are skipped
  while it is still pending (`HeartbeatWriteGate`). The link is closed and reconnected if one write
  stays pending, or the stack keeps answering `WRITE_REQUEST_BUSY`, for 6 s, or after 5 consecutive
  failed writes.
- **Session keys** (`sessionSecret`, `aesKey`) are derived once per `VehicleSession` object and
  wiped when its reconnect loop ends.

The heartbeat is the presence signal: the vehicle localizes the key from the RSSI each link reports
and does passive unlock, lock and drive-enable from that. There is no drive command.

### Pausing

Each beat, before writing, the loop checks two process-wide conditions:

| Condition | Source | Commands still sent? |
|---|---|---|
| Watch locked | `WatchLock.isLocked` (keyguard, cached ≤ 1 s) | no |
| Heartbeats paused | `VehicleSession.heartbeatsPaused` (driving-doze, watch-key standby) | yes, on PK |

While paused the link, its subscriptions and its nonces are kept; nothing is written to `0x1b`; the
heartbeat counter does not advance; connection priority is `LOW_POWER`; and the loop still ticks
every 300 ms (or as often as the CPU is awake). On resume it requests `BALANCED` and continues with
the same counter and nonces.

## 7. Vehicle status stream (`0x1c`)

Subscribed on the PK link only, after authentication. Plaintext; no MAC is checked (see D2).

Frame: `counter (4, LE) ‖ status[0..15]`. The parser (`VehicleStatus.update`) ignores the counter,
needs at least 8 bytes, and needs 15 bytes for the telemetry fields.

| Byte | Bits | Field | Decoded as |
|---|---|---|---|
| `status[0]` | `0x01` | Gear Guard armed | `asleep` (a proxy, see D10) |
| `status[1]` | `0x0f` | Four doors, 1 = closed | `anyDoorOpen` = low nibble ≠ `0xf` |
| `status[2]` | `0xf0` | Closure lock bits | `locked` = any set |
| `status[2]` | `0x08` | Frunk, 1 = closed | `frunkOpen` |
| `status[2]` | `0x04` | Liftgate / tailgate, 1 = closed | `liftgateOpen` |
| `status[3]` | `0x0f` | Four windows, 1 = closed | `anyWindowOpen` |
| `status[4]` | `0x3c` | Cabin preconditioning enum (`>> 2`) | `climateOn` = value 1..4 |
| `status[5]` | `0x7f` | State of charge, % | `socPercent` |
| `status[6]` | `0xf0` | Gear: 1 P, 2 R, 3 N, 4 D | `gear`, else `UNKNOWN` |
| `status[6]` | `0x0f` | Charge state: 1 ready, 2 connecting, 3 active, 5 scheduled, 7 station error, 8 user stopped | `chargeState` (see D9) |
| `status[7]` | all | Cabin temperature, °C, read signed | `cabinTempC` |
| `status[8..9]` | | Range, km: LE16 when not charging; `& 0x1ff` while charging or connecting | `rangeKm` |
| `status[9..10]` | | Charge ETA to the limit, LE16, 15 s per count, only while charging or connecting | `chargeTimeRaw` |

Everything else in the frame is ignored.

### Gear and Park detection

Gear comes only from `status[6]`'s high nibble on `0x1c`. `VehicleStatus.State.gear` is `PARK`,
`REVERSE`, `NEUTRAL`, `DRIVE`, or `UNKNOWN` (frame too short, or any other nibble value).

- "In gear" means R, N or D. `UNKNOWN` is treated as not in gear everywhere.
- A gear reading counts only while the state is `valid && live` and at least one link is UP.
- Consumers: driving-doze (phone keys), the watch-key burst policy, and the parked-idle drop. All
  three are described in Spec 2.

`live` means "a session is confirming this state". It is set true by every parsed frame and set
false only when the service enters passive, Bluetooth turns off, or the service stops (see D11).

## 8. Active commands (`0x20`)

### Outbound frame

64 bytes for a 2-byte command code:

```
16 01 ‖ IV (12, random) ‖ AES-128-GCM(aesKey, IV, AAD, plaintext)
plaintext = code (2, LE) ‖ HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ csn (4, LE) ‖ code (2, LE))
```

`csn` is the command sequence number, separate from the heartbeat counter. It starts at 0 on every
connection. On a presence session it increments after every command whose write was attempted,
whether or not the write succeeded (D7). A one-shot always uses 0.

Other `0x20` writes are plaintext and unauthenticated: `10 80` (PhoneProfile) before the handshake
and `01` (SensorInformation) after it.

### Codes

Codes the UI and tile can send are marked. All are from `ActiveCommandFrames.Cmd`.

| Code | Name | Sent by app | Notes |
|---|---|---|---|
| `0x0003` | UNLOCK_ALL | yes | |
| `0x0006` | LOCK_ALL | yes | |
| `0x0026` / `0x0027` | OPEN / CLOSE_FRUNK | yes | |
| `0x002a` | OPEN_LIFTGATE | yes (R1S) | predicted from the open = close − 1 pattern, unverified (D8) |
| `0x002b` | CLOSE_LIFTGATE | yes (R1S) | |
| `0x0024` | OPEN_TAILGATE | yes (R1T) | no close command exists |
| `0x0015` / `0x0016` | OPEN / CLOSE_ALL_WINDOWS | yes | presence-gated |
| `0x0036` / `0x0037` | OPEN / CLOSE_CHARGE_PORT | yes | presence-gated |
| `0x0010` / `0x0011` | OPEN / CLOSE_TONNEAU_COVER | no | R1T; defined only |
| `0x000e` / `0x000f` | RELEASE_LEFT / RIGHT_SIDE_BIN | no | R1T; defined only |
| `0x0007` / `0x0034` | PANIC_ON / OFF | yes | |
| `0x006e`, `0x006f` | FLASH_LIGHTS, ACTIVATE_SOUND | no | defined only |
| `0x0017` / `0x0018` | ENABLE / DISABLE_GEAR_GUARD | no | defined only |
| `0x0019` / `0x001a` | CABIN_PRECONDITION_ENABLE / DISABLE | yes | |
| `0x0072`, `0x0073`, `0x005e`, `0x005f` | DRIVE_AUTH_* | no | defined only |

"yes" means the constant is wired to a control in `ControlScreens`. The tile sends only lock,
unlock, frunk and the rear closure.

### Inbound on `0x20`

| First byte | Meaning | Handling |
|---|---|---|
| `0x17` | Command response | Decrypt attempt, logged. Never interpreted (D6). |
| `0x18` | Encrypted vehicle status | Debug builds: decrypt attempt, logged on change. Production: dropped. |
| other | Ranging / control chatter | Logged, throttled. |

Decrypt attempts assume the outbound layout (`type ‖ 01 ‖ IV(12) ‖ ct ‖ tag`); when that fails a
prober tries a set of implicit-IV derivations. The UI takes vehicle state from `0x1c`, never from
`0x20`.

### What "sent" means

A command is reported delivered when its GATT write is acknowledged. The vehicle's `0x17` response
is not awaited by a presence session and is waited for at most 1 s by a one-shot, for the log only.
There is no confirmation that the vehicle acted; the next `0x1c` frame is the only evidence.

### Presence-gated commands

Lock and unlock work from a one-shot. Charge port, windows and liftgate are accepted only when the
vehicle already considers the key present, so they are meant to ride a live presence session with
its running `csn`. The app does not enforce this: a one-shot sends whatever code it is given.

## 9. Concurrency on the PK link

Two handshakes on PK at once would share one ACL and overwrite each other's nonces. The rules:

- `ActiveCommandManager.oneShotLock` (process-wide mutex) is held for a whole one-shot (both
  attempts) and for each tile pre-warm connect.
- The PRIMARY `VehicleSession` calls `awaitOneShotIdle()` before every connect attempt: it cancels
  an in-progress pre-warm, waits for the mutex, and closes an unused pre-warmed link.
- Entering passive calls `awaitOneShotIdle()` before arming the PK autoConnect.
- While the presence service is running and not passive, commands are queued to the session
  (`CommandBus`) and never open a second client, even while that session is still connecting.

The passive autoConnect client and a one-shot client can coexist on PK: the one-shot's connection
completes the pending autoConnect too (D12).

## 10. Timing reference

| Constant | Value | Where |
|---|---|---|
| Direct connect timeout (session, pairing) | 35 s | `VehicleSession.CONNECT_MS`, `PairingManager` |
| autoConnect wait before re-issuing | 10 min | `AUTOCONNECT_WAIT_MS` |
| GATT operation timeout | 5 s | `OP_MS` (session, one-shot, pairing) |
| Vehicle id echo wait (session) | 3 s | `PHONEID_ECHO_MS` |
| Heartbeat period | 300 ms | `HEARTBEAT_PERIOD_MS` |
| Heartbeat write timeout | 1.5 s | `HB_WRITE_TIMEOUT_MS` |
| Stuck write / persistent BUSY before cycling | 6 s | `HeartbeatWriteGate.STALL_MAX_MS` |
| Consecutive failed heartbeats before cycling | 5 | `HeartbeatWriteGate.MAX_FAILURES` |
| RSSI refresh | every 4th beat | `RSSI_EVERY` |
| Reconnect backoff | 1.5 s base, ×2 per failure, cap 60 s, ±25 % jitter | `ReconnectPolicy` |
| Quick retries after connected-but-unauthenticated | 4 at the base delay | `QUICK_CONNECTED_RETRIES` |
| Authenticated time that resets the backoff | 10 s | `MIN_STABLE_SESSION_MS` |
| Switch to autoConnect | PK after 1 failed connect, sensor after 3 | `ReconnectPolicy.useAutoConnect` |
| Sensor discovery scan | 6 s, `LOW_LATENCY`, service-UUID filter | `SensorScanner.discover` |
| Sensor rescan interval | 15 s, doubling to 120 s while nothing new | `RescanSchedule` |
| Stagger between new sensor connects | 1.5 s | `PresenceService.STAGGER_MS` |
| Pairing scan | 15 s, `LOW_LATENCY`, name filter | `PairingManager` |
| Bond wait | 60 s | `PairingManager.BOND_TIMEOUT_MS` |
| One-shot tap deadline | 35 s from the tap to the command write | `OneShotPolicy.DEADLINE_MS` |
| One-shot retry | once, after 500 ms, only for a retryable failure with ≥ 3 s left | `OneShotPolicy`, `RETRY_DELAY_MS` |
| Quick connect failure (retryable) | under 5 s | `QUICK_CONNECT_FAILURE_MS` |
| One-shot response linger | 1 s | `RESPONSE_LINGER_MS` |
| One-shot ECDH wait | 10 s | `ECDH_MS` |
| Pre-warm connect window / hold | 10 s / 15 s | `PREWARM_CONNECT_MS`, `PREWARM_HOLD_MS` |
| Queued command TTL | 5 s if PK was authenticated at submit, else 20 s | `CommandBus` |
| Watch-lock read staleness | 1 s | `WatchLock.TTL_MS` |

## 11. Reconnect behavior

`VehicleSession.runForever` loops connect → session → backoff until cancelled.

- Outcomes: `NO_CONNECT` (never connected, or `connectGatt` unavailable), `CONNECTED` (connected,
  handshake failed), `AUTHENTICATED`.
- An authenticated session that lasted under 10 s counts like `CONNECTED`.
- `ReconnectHurry.hurry()` ends every backoff wait in progress. It is called when a command is
  queued, when a watch-key burst starts and when driving-doze ends.
- A disconnect fails every pending GATT wait at once and ends the heartbeat-period wait.
- On session end the PK session marks `CommandBus` as not authenticated and, if it had
  authenticated, drops queued commands.
- Sensor sessions are started from saved addresses immediately (staggered), then from discovery
  scans. Sensors are not started or scanned for while heartbeats are paused.

## 12. Discrepancies and ambiguities

Found while writing this spec. None are fixed here.

**D1. MTU is never negotiated by the app, and several payloads exceed the default.**
No `requestMtu` call or `onMtuChanged` callback exists. `docs/wearvian-overview.md` §9 states the
link runs at the default ATT MTU (20-byte notifications). At that MTU the 37-byte heartbeat, 48-byte
nonce write and 64-byte command only fit as long (prepared) writes, which Android does for
write-with-response but not for write-without-response. The code picks without-response for the
heartbeat whenever `0x1b` lacks `PROPERTY_WRITE`. Which case holds on the vehicle, and what MTU the
links actually run at, is not recorded anywhere in the tree. Notifications are affected the same
way: the 48-byte `0x15` reply and anything past 20 bytes on `0x1c` or `0x20` would be truncated.

**D2. The vehicle is not authenticated to the watch.**
Only the first 16 bytes of the `0x15` reply are used; the rest (the vehicle's MAC, which D1 says is
cut off anyway) is never verified. Pairing does not look at the reply's content at all, yet its
comment reads "vehicle is authenticated". The vehicle id echo is a public value. `0x1c` is
plaintext and unverified, and it drives driving-doze, the parked-idle drop, the watch-key burst
and everything the UI shows. A device that can spoof the PK MAC can therefore feed state to the
watch; it cannot forge the watch's heartbeats or commands.

**D3. Three different rules for the vehicle id echo.**
The presence session waits 3 s and carries on if there is no echo. One-shot and pairing wait 5 s
and fail without it. So a vehicle that does not echo gets a working presence session but no
one-shot commands.

**D4. The one-shot handshake is not the documented sequence.**
`VehicleSession` documents its order as "exact order from the capture" (PhoneProfile first, `0x20`
subscribed only after auth, SensorInformation after). The one-shot subscribes `0x20` before auth
and skips both PhoneProfile and SensorInformation. It works for lock and unlock; whether the
difference matters for anything else is untested.

**D5. Comments and docs still say `0x1c` is never subscribed.**
`VehicleSession`'s class comment and `docs/passive-entry-protocol.md` say only `0x12`, `0x15` and
`0x20` are subscribed and "NEVER 0x1c"; two code comments call the PK `0x1c` subscription an
"EXPERIMENT" on a branch. It is on `main`, and status, gear detection, driving-doze, parked-idle,
the tile and the complications all depend on it.

**D6. Command responses are never interpreted.**
`0x17` frames are logged only. The code carries two incompatible theories of their layout
(explicit IV in `decryptInbound`, implicit IV in `probeInbound`) and neither is recorded as
confirmed. A command the vehicle rejects is reported to the user as sent.

**D7. `csn` advances after a failed command write.**
`drainCommandBus` increments `commandCounter` whether or not the write succeeded (but not when the
command is skipped because an earlier write still holds the slot). If the vehicle never received
the failed frame, the watch's `csn` is one ahead for the rest of the connection. Whether the
vehicle requires an exact match or only a non-repeating value is not recorded.

**D8. OPEN_LIFTGATE uses a predicted code.**
`0x002a` is derived from a pattern; the code's own comment notes the decompile's canonical value is
`0x1f` and that `0x2a` is a different enum entry there. Marked "verify on-vehicle" and still
shipped as the R1S hatch-open button.

**D9. Two charge states decode to UNKNOWN.**
Schema values 4 (complete) and 6 (vehicle error) are not mapped. A car that is plugged in and
finished charging is therefore not in `PLUGGED_STATES`, so the parked-idle drop uses the 15 minute
timeout instead of the 5 minute plugged-in one, and the UI has no plugged-in indication for it.

**D10. `asleep` is Gear Guard's armed bit.**
The field is documented as a proxy. If Gear Guard is disabled or does not arm (for example at a
saved location), `asleep` stays false. It feeds the watch-key burst triggers and the parked-idle
snapshot.

**D11. `live` is not cleared when the PK link drops or the watch locks.**
`VehicleStatus.clear()` is called on entering passive, on Bluetooth off and in `onDestroy`, but not
when the PRIMARY session ends while active and not in the lock transition. Consequences: after a PK
drop the last gear stays `live`; driving-doze's "link is live" test is "any link UP" (a sensor
counts) and so can hold a stale in-gear reading; while locked the tile and app draw the last state
as live; and `WatchBurstPolicy`'s comment that state is stale across a lock is not true of the
code.

**D12. Any one-shot wakes a departed passive key.**
With the car departed, passive holds an `autoConnect` client on PK. A one-shot's direct connection
to PK completes that client too, which wakes the service to active. `CommandDispatcher` states the
intent as "an UNLOCK while parked nearby also re-activates the key", but in the departed sub-state
a lock or frunk one-shot wakes it as a side effect.

**D13. Pairing still bonds, and success requires it.**
Every other path treats the bond as unnecessary (and `PhoneKey.find` works without one), but
pairing fails with "Bonding was rejected" if `createBond` does not complete. The stored flag
`Enrollment.bonded` really means "paired"; the name `NOT_BONDED` on a command failure means "no PK
address or Bluetooth off".

**D14. Unused protocol code.**
`RivianBle.Command` (string command names), `SERVICE_ACTIVE_ENTRY`, `CHAR_ACTIVE_ENTRY`,
`KeyManager.signCommand` / `RivianCrypto.signCommand` (ASCII command + timestamp HMAC) and
`SignedParams` (the "Q" characteristic protobuf) have no callers in the app. They describe
protocol theories that were superseded.

**D15. The official app's interval is cited against HIGH, and the one-shot uses HIGH.**
`VehicleSession` notes the official app never uses the `HIGH` connection priority; the one-shot
requests it on every tap. Not known to cause a problem.
