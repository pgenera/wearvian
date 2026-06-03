# Rivian passive-entry / drive BLE protocol (reverse-engineered)

Derived from a full HCI snoop of the **official Rivian app** doing a successful
passive unlock + drive on the same Gen-1 R1S (`2c:a7:74:fe:3c:b1`, advertised
name "Rivian Phone Key"), 2026-06-02. Only protocol *structure* is recorded here
— no captured key material. The raw snoop is kept out of the repo.

## Key finding

Passive entry is **not** "bond once and rely on proximity." It is a continuous,
authenticated, bidirectional **session** that the key must actively maintain. Our
M1 watch app does the bond + initial handshake and then goes silent, so the
vehicle terminates the link (`HCI Disconnect reason 0x13`, remote-terminated, on
a ~7 s loop) and never grants entry/drive.

## GATT characteristics observed (service `RIVSENSORSERVICE` = `52495356-454e-534f-5253-455256494345`)

| Handle | UUID | ASCII name | Role | In watch app? |
|--------|------|-----------|------|---------------|
| 0x12 | `AA49565A-4D4F-424B-4559-5F5752495445` | `\xaaIVZMOBKEY_WRITE` | phone-id write; vehicle echoes vehicle-id (notify) | yes |
| 0x15 | `E020A15D-E730-4B2C-908B-51DAF9D41E19` | — | phone nonce+HMAC write; vehicle nonce (notify) | yes |
| 0x18 | `5249565F-4D4F-424B-4559-5F5752495445` | `RIV_MOBKEY_WRITE` | **active-entry command write** (unused in passive session) | yes |
| 0x1b | `52495649-414E-2052-4541-442043484152` | `RIVIAN READ CHAR` | **continuous presence heartbeat (phone → vehicle)** | **NO** |
| 0x1c | `afb2e704-842b-4e6a-9bd2-b1b305828f24` | — | vehicle status | yes |
| 0x20 | `5ae32b92-eafb-471b-afe8-e88eec4a4774` | — | **control/ranging channel** (phone writes `01`,`10a8`; vehicle notifies ~279×) | **NO** |
| 0x23 | `5be32b92-eafb-471b-afe8-e88eec4a4775` | — | second control/ranging channel | **NO** |

Plus the phone connects to **"Rivian Sensor 1–4"** (separate BLE peripherals) so
the car can localize the key by RSSI. The watch app connects to none of them.

## Session flow (per connection, from the official app)

1. write phone-id (vasPhoneId, 16 bytes) → `0x12`; vehicle notifies vehicle-id back
2. write control char `0x20`: `01`, then `10a8`
3. write pairing frame (16-byte nonce ‖ 32-byte HMAC = 48 bytes) → `0x15`; vehicle notifies its nonce
4. **stream heartbeats** → `0x1b` at ~12 Hz for the life of the session
5. vehicle streams notifications on `0x20` (challenge/ranging) the whole time
6. session held stably (62 s observed), then clean local disconnect (`0x16`)

## Heartbeat frame (handle 0x1b) — 37 bytes

```
[ 4 bytes: little-endian counter ][ 1 byte: flag/type ][ 32 bytes: HMAC ]
```

- counter increments per frame (resets per connection)
- 5th byte varies widely (0x80, 0xb0–0xbe) — flag/type or part of a counter/nonce
- trailing 32 bytes ≈ HMAC-SHA256 (same derived ECDH→HKDF key family as pairing);
  exact message preimage (counter? counter‖flag? + vehicle challenge from 0x20?)
  still to be confirmed — likely incorporates the vehicle's `0x20` notifications.

## Active commands (lock/unlock/frunk/etc.) — ENCRYPTED, on 0x20 (not 0x18)

A second full snoop (2026-06-02) with 8 discrete commands (lock, unlock, hood open/close,
charge-port open/close, trunk open/close) showed:

- **Zero writes to `0x18`** ("RIV_MOBKEY_WRITE" / `CHAR_ACTIVE_ENTRY`). The active-entry
  characteristic our app targets is **not used** by the official app for commands.
- Each command is a **64-byte write to `0x20`** (`5ae32b92-…`, the same control/ranging channel),
  one per button press (8 writes timed ~5 s apart matching the presses).
- Frame = constant 2-byte header **`16 01`** + **62 bytes of high-entropy data** (212/256 distinct
  byte values). No plaintext command strings → the payload is **encrypted + authenticated**, not
  `command+timestamp+HMAC` in the clear.

**Implication:** the command payload's cipher, key derivation, plaintext layout, and nonce
construction **cannot be recovered from a snoop** (ciphertext only) nor by on-vehicle trial-and-error
(can't brute-force an AEAD scheme). The drive heartbeat (`0x1b`, 37 B, per-session-keyed) is in the
same boat. Obtaining the algorithm from a reference implementation (e.g. WaaKey's watchOS binary) is
the only tractable path to active commands and drive. `0x18` plaintext-HMAC framing is a dead end.

## Command crypto — reverse-engineered from the official Android app (jadx/baksmali, 2026-06-02)

Decompiled `com.rivian.android.consumer`. Rivian's BLE lib is obfuscated (packages `s60/q60/pv/l60/…`)
but the app-layer names in `com.rivian.android.vehicle.session.*` are intact. Derived facts only (no
copied code); reimplement cleanly.

**Primitives (`pv/f3`):**
- `B0(label, vehiclePubHex)` = **ECDH** (P-256, provider BC): watch private key (selected by `label`)
  × vehicle public key (hex, X9.62) → raw shared secret.
- HKDF: `HKDFBytesGenerator(SHA256)`, `HKDFParameters(ikm, salt=null, info=null)`, `generateBytes(32)`
  → 32-byte session secret. **Identical to our `RivianCrypto.deriveSecretKey`.**
- `H0(msg, key)` = **HMAC-SHA256(key, msg)** (BouncyCastle HMac/SHA256), 32-byte out.

**Key schedule (`q60/a` = "cryptoManager"):**
- session secret `d` established from ECDH→HKDF (and likely mixed with pnonce/vnonce — TODO confirm).
- **AES-128 message key = first 16 bytes of `HMAC-SHA256(sessionSecret, CONSTANT_B)`**, where
  `CONSTANT_B` (32 bytes) = `07862b2ed8328106a7cdff7b5c1b23bedffddb33a6aa3c82b0fedc784485df77`.
  (`q60/a.b(x) = H0(x, d)`; `q60/a.c(secret)` sets `d` then key = first16(b(CONSTANT_B)).)

**GATT message framing (`s60/e` = "BleMessageHelper"), char `0x20`, 64 bytes:**
```
[0x16 0x01  header(2) ][ IV (12) ][ AES-128/GCM ciphertext (34) ][ GCM tag (16) ]
```
- cipher `AES_128/GCM/NoPadding`; decrypt: IV = bytes[2..14], ct+tag = bytes[14..end].
- `cipher.updateAAD(<aad>)` — AAD content TODO (likely the 2-byte header and/or nonce/direction).
- IV is per-message (in the frame); sender generates it. Uses **pnonce** (phone) / **vnonce**
  (vehicle) per direction ("null pnonce/vnonce when decrypting GATT message").
- plaintext = 34 bytes → command code + timestamp/counter (exact layout TODO).

**Outgoing command frame — fully decoded** (clean Java from `em/f0.f(h60/d dVar, l60/t tVar)`):
```
frame  = [ msgType(1) ][ version(1) ][ IV(12) ][ AES-128-GCM(plaintext, AAD) ]   → write to char P (0x20)
 msgType = s60.i0.ActiveCMDRequest.getId() = (byte)22 = 0x16   (ActiveCMDResponse=0x17; other types below)
 version = s60.f.VERSION_1 = 0x01
 IV      = first 12 bytes of UUID.randomUUID() serialized little-endian  (per-message random)
 AAD     = s60.h.s(pNonce, vNonce)                                       (function of both nonces)
 key     = q60.a.e  = AES-128 = first16(HMAC-SHA256(sessionSecret, CONSTANT_B))
 plaintext = jh.a.i0(vasReq)   // serialized VasRequest
```
**`VasRequest` (`l60.k0`)** = `{ type: VasRequestType(l60.m0)=ACTIVE_COMMAND, csnOrPacketNo: int counter
(tVar.r, ++ per msg), phoneId: UUID, vehicleId: UUID, pNonce: byte[], vNonce: byte[], attrValue: byte[]
(the command payload, from dVar.a.c) }`. Incoming responses (`s60/e.a`) decrypt the same shape.

`s60.i0` message types (frame byte 0): SensorInformation, SensorFirmwareVersion, BLEInterfaceVersion,
**KeepAlive** (likely the `0x1b` heartbeat), PhoneProfile, PhoneStatusMotion, **ActiveCMDRequest=0x16**,
ActiveCMDResponse=0x17, VehicleStatus. Note `q60/a.a(label,vehPubHex,payload)` is the *cloud* HMAC
signer (`H0`), not the BLE path.

**Low-RAM jadx pipeline that works (use this, NOT full-app jadx):** `tools/smali.jar` assembles a
chosen subset of `.smali` into a mini.dex, then `jadx -Xmx512m mini.dex` decompiles clean Java safely.
Built `mini-java/` from packages em,h60,q60,pv,s60,l60.

**VasRequest serialization (`jh.a.i0`, all LITTLE_ENDIAN), branch by `VasRequestType` ordinal (`l60.l0`):**
- **type 1 (auth/pairing nonce):** `r(pNonce)(16) ‖ r(HMAC-SHA256(sessionSecret, pNonce))(32)` = 48 bytes
  — **identical to our existing `PairingFrames` nonce write**, cross-validating the whole derivation.
- **type 2 (signed msg):** `r(attrValue) ‖ r(HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ counter(4) ‖ attrValue))(32)`
- **type 3 (= ACTIVE_COMMAND):** `r( counter(4) ‖ attrValue ) ‖ r( HMAC-SHA256(sessionSecret, pNonce(16) ‖ vNonce(16) ‖ counter(4) ‖ attrValue) )(32)`

where `q60.a.b(x) = HMAC-SHA256(sessionSecret, x)`. Helpers resolved: **`s60.h.r(x)=x`** (identity —
`ByteBuffer.wrap().order().array()` is the same bytes); **`s60.h.s(a,b)=a XOR b`**; `s60.h.o(uuid,LE)`
= 16-byte little-endian UUID (IV = its first 12 bytes).

**CONCRETE active-command construction (the 64-byte `0x20` frame), fully resolved:**
```
counter   = tVar.r (++ per message)
attrValue = 2-byte command code            // 64B frame ⇒ 34B plaintext ⇒ ACTIVE_COMMAND = serializer branch 2 ⇒ attrValue=2B
plaintext = attrValue(2) ‖ HMAC-SHA256(sessionSecret, pNonce(16) ‖ vNonce(16) ‖ counter(4,LE) ‖ attrValue(2))   // 34B
IV        = first 12 bytes of o(UUID.randomUUID(), LITTLE_ENDIAN)      // random
AAD       = pNonce XOR vNonce
AESkey    = first16(HMAC-SHA256(sessionSecret, CONSTANT_B))
ct        = AES-128-GCM(AESkey, IV, AAD).encrypt(plaintext)            // 34 + 16 tag = 50
frame     = 0x16 ‖ 0x01 ‖ IV(12) ‖ ct(50)                              // = 64 bytes, write to char 0x20
```
(counter is authenticated-only, not in the plaintext — both sides track the session packet number.)

**Command codes (`attrValue`, 2-byte little-endian; from `h60/o` etc., enum `em/k2`):**

| code | command | | code | command |
|------|---------|-|------|---------|
| 0x0003 | UNLOCK_ALL_CLOSURES | | 0x0027 | CLOSE_FRUNK |
| 0x0006 | LOCK_ALL_CLOSURES(_FEEDBACK) | | 0x002b | CLOSE_LIFTGATE |
| 0x0007 / 0x0034 | PANIC_ON / PANIC_OFF | | 0x0036 / 0x0037 | OPEN / CLOSE_CHARGE_PORT_DOOR |
| 0x0015 / 0x0016 | OPEN / CLOSE_ALL_WINDOWS | | 0x006e | FLASH_EXTERNAL_LIGHTS |
| 0x0017 / 0x0018 | ENABLE / DISABLE_GEAR_GUARD | | 0x006f | ACTIVATE_EXTERNAL_SOUND |
| 0x0019 / 0x001a | CABIN_PRECONDITION EN/DISABLE | | 0x0072 / 0x0073 | DRIVE_AUTH_USER_INPUT ALLOW/DENY |
| 0x0026 | OPEN_FRUNK | | 0x000e/0x000f/0x0010/0x0011 | bins / tonneau |

(plus OPEN_LIFTGATE/OPEN_TAILGATE/WAKE_VEHICLE in `em/k2` — codes in sibling `h60/*` registries.)
So **UNLOCK = `attrValue = {0x03,0x00}`, LOCK = `{0x06,0x00}`** — drop into the construction above.

**The active-command protocol is fully reverse-engineered.** Implementable now against our crypto core
(`RivianCrypto` ECDH→HKDF→HMAC already matches).

## Drive heartbeat — fully reverse-engineered

`VasRequestType` (`l60.m0`) = `AUTH_PNONCE(0)→serializer branch 1`, `ACTIVE_COMMAND(1)→branch 2`,
`PASSIVE_ENTRY(2)→branch 3` (confirmed in `l60.l0`). The drive presence heartbeat is a **PASSIVE_ENTRY**
VasRequest, serialized via branch 3 and written **unencrypted** to char `0x1b` ("RIVIAN READ CHAR")
~12×/sec:
```
heartbeat(37B) = counter(4,LE, ++ per msg) ‖ flag(1) ‖ HMAC-SHA256(sessionSecret, pNonce(16) ‖ vNonce(16) ‖ counter(4,LE) ‖ flag(1))
```
- `flag` = a 1-byte phone status/motion byte (`s60.i0.PhoneStatusMotion`; observed 0x80, 0xb0–0xbe in
  the snoop — exact semantics TBD; a sane constant likely suffices for drive-enable, confirm on-vehicle).
- This explains the snoop exactly: 37B = 4+1+32; LE counter; and same-counter→different-HMAC across
  sessions (because pNonce/vNonce differ per session) = the "per-session keyed" behavior we measured.

**Both active commands AND drive heartbeat are fully specified.** The crypto/frames map onto our
existing core. Decompile/tools persist at `/home/pgenera/.claude/jobs/1a773a26/tmp/` (`jx3/` = clean
Java of classes3.dex).

## Drive needs multi-sensor localization (confirmed in the Android app)

Active *commands* (unlock/lock/frunk) work with a single connection to "Rivian Phone Key" — verified
on-vehicle. **Drive does NOT**, because passive entry is **localization-based**, handled by a separate
`l60/j0` "BLEPath_SensorPassiveEntryManager":

- The app scans (by service UUID — the `RIVSENSORSERVICE` / `SERVICE_ACTIVE_ENTRY` UUID the sensors
  advertise) and opens a `l60.q0` connection to **each vehicle sensor**: a `PRIMARY` (the main module)
  plus location sensors, identified via `VehicleSensorInfo {sensorType (s60.g0), sensorLocation,
  sensorNodeId, rssi, isPreCCC, isCache}` (`com.rivian.android.vehicle.session.definition`).
- It runs the presence/heartbeat session across them and gates on **RSSI** (e.g. `rssi <= -85` checks)
  so the car can triangulate the phone: inside cabin → enable drive; near a door → unlock that door.
- The heartbeat is a **PhoneStatusMotion** message (`p60/b`); its 1-byte flag is the phone's motion/
  velocity state (HMAC-covered, so any value authenticates — `b60` `SensorConnectionState` even tracks
  `velocity`). So the motion flag is not the blocker; the missing piece is the sensor connections.

**Implication for our app:** a single "Rivian Phone Key" connection + heartbeat gives auth + presence
but **not localization**, so drive can't be granted. Drive requires a new component that connects to
the sensors and runs the session across all of them.

**On-vehicle (2026-06-03):** wake-lock fix confirmed — heartbeat streams continuously (ctr 0,13,26,…).
An open BLE scan filtered by `SERVICE_ACTIVE_ENTRY` found **0** devices (driver's seat and outside),
so that's the wrong discovery mechanism. From the decompile: the sensors are matched by their
**advertised vehicleId/nodeId** (`VehicleSensorInfo.a` = NODE_ID/VEHICLE_ID/ADDRESS adv fields), and
**`VehicleSensorInfo` carries `address` (MAC), `sensorLocation` (INTERIOR/EXTERIOR), `sensorNodeId`,
`vehicleId`, `rssi`, `isCache`** (`com.rivian.android.vehicle.session.definition.VehicleSensorInfo`).
Also: the `0x1c` status byte differs by position (`…10 07…` driver vs `…10 0f…` outside) — encodes
location. **Milestone-2 unknowns:** the real scan service-UUID + advertised-data layout (in `l60/y0`/
`l60/i`, archive at `/home/pgenera/rivian-re`), and the source of `VehicleSensorInfo` (enrollment/cloud
vs. primary-reported — the network code is in classes2, not yet decompiled to clean Java).

## RE update 2026-06-03 — heartbeat flag = RSSI; sensors authenticate; 0x20 kickoff (confirmed in decompile)

Read the per-connection session class `l60/i` and the ranging manager `l60/j0` (clean Java in
`/home/pgenera/rivian-re/java-classes3`). Three of our drive assumptions were wrong; corrected:

1. **The heartbeat's 5th "flag" byte is the phone-measured RSSI, not a motion flag.** `l60/j0`
   ("BLEPath_SensorPassiveEntryManager") calls `BluetoothGatt.readRemoteRssi()` every ~300 ms,
   stores it (`j0.g`, default `j0.h = -128`), and `l60/i0` builds the heartbeat as a `PASSIVE_ENTRY`
   `VasRequest` with `attrValue = { (byte) rssi }`. So:
   ```
   heartbeat(37B) = counter(4,LE) ‖ rssiByte(1) ‖ HMAC(secret, pNonce(16)‖vNonce(16)‖counter(4,LE)‖rssiByte(1))
   ```
   This explains the snoop exactly (`0x80` = −128 before the first reading, then `0xb0–0xbe` = −80…−66
   = real RSSI). **Our code sent a constant `0x80` ⇒ the car always saw us at −128 (farthest) ⇒ never
   localized us in-cabin ⇒ "no key detected".** Flag byte must track live RSSI.

2. **Heartbeat cadence is ~300 ms** (`j0.e` self-reschedules at `(qVar.o||preCcc) ? 1000 : 300` ms;
   `l60/i.n()` enforces a 300 ms floor), not 75 ms. Write type is `NO_RESPONSE` unless `qVar.o`.

3. **Sensors fully authenticate too** — "connect-and-hold" was wrong (it's why the held links churn:
   an unauthenticated idle GATT connection is dropped). `l60/i.m()`/`y()` run the phoneId+nonce
   handshake for non-PRIMARY as well; only the *extra* PRIMARY-only coroutine `l60/i.I()` is gated on
   `f == PRIMARY`. Every connection: connect → handshake → AUTHENTICATED → ranging heartbeat to 0x1b
   carrying that link's RSSI. The car triangulates across all of them (`rssi > -85` = in-range gate
   in `l60/i0`).

**0x20 (`5ae32b92…`) is `VEHICLE_MESSAGE_UUID`** — the main message channel (also the active-command
channel). On 0x20 notify-enable the app writes `{ SensorInformation.getId() }` (`s60/i0`:
SensorInformation **= 0x01**) to it (`l60/i.m()`), matching the snoop's first `0x20` write `01`. The
`10a8` write is a `PhoneProfile` message (PhoneProfile **= 0x10**, payload `a8`). Ranging is enabled
per-connection by `l60/i.o()` ("Ranging session enabled"), gated on `tVar.v` (isAuth), driven by the
vehicle's 0x20 messages — which starts the `j0` heartbeat stream.

Handshake chars confirmed identical to ours: `m60/c.f = aa49565a…` (0x12 phoneId, written
**BIG_ENDIAN**), `m60/c.g = e020a15d…` (0x15 nonce). PRE_CCC variant (`l60/i.j==true`, service
`72CDDCA3…`, chars `R/S/T`) is a newer encrypted path our Gen-1 car does **not** use — the snoop is
the LEGACY path, which is exactly our working active-command handshake.

## RE update 2026-06-03 (2) — on-vehicle: write type, cadence, and the sensor bond wall

On-vehicle log `1240a` with submit-code + disconnect-status logging resolved the open items:

- **0x1b write type — WITH_RESPONSE.** The heartbeat char advertises `props=0x0c` =
  `PROPERTY_WRITE(0x08) | PROPERTY_WRITE_NO_RESPONSE(0x04)`. Since `0x08` is set,
  `qVar.o=true`, so the app writes with `WRITE_TYPE_DEFAULT` (with response). Our adaptive
  picker chose WITH_RESPONSE — correct. (Earlier hardcoded NO_RESPONSE was wrong-vs-app.)
- **Heartbeat cadence ~1 Hz is correct, not a bug.** `l60/j0.e` reschedules at
  `(qVar.o || preCcc) ? 1000 : 300` ms. With `qVar.o=true` (0x1b has PROPERTY_WRITE) the app's
  own cadence is **1000 ms**. Our measured ~1.1 s matches; the 300 ms value only applies to a
  no-response heartbeat char. `requestConnectionPriority(HIGH)` is accepted (`connPriReq=true`)
  but doesn't materially change this.
- **Congestion was NOT the sensor blocker.** The submit-code logging showed the real failure:
  each sensor `connected → discovered → disconnected status=0x05` ~200 ms after discovery,
  *before any write*. `0x05` = HCI **Authentication Failure**. The later `SERVICE_NOT_BOUND` /
  CCCD timeouts are just downstream of operating on the dead link.
- **Root cause: sensors require link-layer bonding/encryption, and we only bonded the PRIMARY.**
  The sensors are separate BLE devices (`74:B8:39:…`); the first CCCD write to an encrypted char
  (we hit `0x1c` first) forces encryption, there's no bond → `0x05`. Confirmed in the decompile:
  `l60/i.r()` calls `createBond()` for any device with `getBondState()!=BONDED`, and `l60/b0.e`
  only advances a **sensor** to `AUTHENTICATED` when `getBondState()==BONDED`. The app also
  subscribes only the unencrypted handshake chars (`0x12`/`0x15`) pre-auth — never `0x1c` first.

**Implication / current step:** bond each sensor (`createBond` + await `ACTION_BOND_STATE_CHANGED`)
before GATT I/O. Open question this tests: whether the sensors accept a direct bond, or require
car-mediated provisioning (in the app, `createBond` is triggered mid-handshake off the `Q`-char
`SIGNED_PARAMS_SENT` step, which our simplified PK handshake doesn't replicate).

## RE update 2026-06-03 (3) — sensor bonding is gated on a key-derived "signed params" auth

On-vehicle `1344` (full `-b all` OS log) + decompile pinned why `createBond()` on a sensor fails:

- OS stack: `createBond` starts real SMP (`BOND_NONE→BONDING`) but the pairing link dies at
  establishment — `btm_ble_read_remote_features_complete: HCI_ERR_CONN_FAILED_ESTABLISHMENT`,
  then `smp_proc_pairing_cmpl: SMP_FAIL`, **no SMP Pairing-Request/Failed PDUs**. So the sensor
  isn't completing SMP — it isn't *receptive* to bonding from us.
- Decompile: the auth state machine is `INIT → PID_PNONCE_SENT → SIGNED_PARAMS_SENT →
  AUTHENTICATED` (`l60/z`), and `createBond()` (`l60/i.r()`) only fires **in SIGNED_PARAMS_SENT**,
  after the vehicle answers. The `SIGNED_PARAMS_SENT` step (`l60/h` case 1) computes a "signed
  params" message via **`s60/b0.c(context, tVar, …)`** and writes it to the **`Q` char
  `0823DA14` (PLAIN_DATA_IN)**; there's a `s60.u.PSEUDO_PAIRING` / `PSEUDO_PAIRING_NOT_ALLOWED`
  capability flag (the snoop's `10 a8` PhoneProfile write — `0xa8` encodes it).

**Conclusion:** the sensor SMP bond is *authorized* by a cryptographic proof derived from the
enrollment/session key (the signed params on `Q`). The phone proves it holds the key, the sensor
then becomes receptive, and only then does SMP pairing succeed. Our simplified handshake stops at
PID/pNonce and never sends signed params → sensors never become receptive → SMP fails (contention
was only a secondary effect of 4 concurrent attempts). The PRIMARY works because it was bonded
during initial provisioning (add-key mode) and persists.

**Gating RE target:** `s60/b0.c` (the signed-params builder) — **did not decompile to clean Java**
(jadx: "Method not decompiled", 399 instr). Needs a smali/baksmali pass + the `n1`/`l60.x`
protobuf shape. This is the prerequisite for sensor bonding, hence for drive localization.

## What this means for the app

- **Passive entry/drive needs a new presence-session component**: after bonding,
  subscribe to `0x20`/`0x15`/`0x12` notifications, write the `0x20` control bytes,
  and stream authenticated 37-byte heartbeats to `0x1b` continuously while in
  range, likely folding in the vehicle's `0x20` challenge. Sensor-connection
  (ranging) may also be required for door-localized unlock vs. drive-enable.
- **Active commands (M2)** still need their own capture: this passive session
  never wrote to `0x18`. Capture the official app pressing lock/unlock to recover
  the `0x18` command frame.
- Re-confirm the heartbeat HMAC preimage before implementing; our crypto core
  already has the HMAC/HKDF primitives.
