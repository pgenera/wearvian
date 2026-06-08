# Rivian passive-entry / drive BLE protocol (reverse-engineered)

Derived from a full HCI snoop of the **official Rivian app** doing a successful
passive unlock + drive on the same Gen-1 R1S (`2c:a7:74:fe:3c:b1`, advertised
name "Rivian Phone Key"), 2026-06-02. Only protocol *structure* is recorded here
— no captured key material. The raw snoop is kept out of the repo.

> The sections below are the full RE journey (some early assumptions were later
> overturned). For the **confirmed, working** protocol read the summary immediately
> below; later "RE update" sections supersede earlier ones where they conflict.

## ✅ Working protocol (confirmed on-vehicle 2026-06-05)

Passive **unlock** and **drive enable** both work from the watch's own BLE radio, fully offline.
The whole thing runs **in the clear** — no link-layer pairing/encryption, no bonding, no
signed-params (those belong to the PRE-CCC variant this Gen-1 car doesn't use).

**Per-device session (identical for the "Rivian Phone Key" PRIMARY and each "Rivian Sensor 1–4"):**
1. connect → discover → request HIGH connection priority,
2. subscribe CCCD on **0x12** + **0x15** only (NEVER 0x1c),
3. write **PhoneProfile (`10 80`) → 0x20**,
4. write **phoneId → 0x12**; car notifies a **16-byte vehicle-id echo** on 0x12,
5. write **pNonce (48 B) → 0x15**; car notifies a **48-byte vNonce** on 0x15,
6. subscribe CCCD on **0x20**; write **SensorInformation (`01`) → 0x20**; receive the car's ranging stream,
7. stream **37-byte heartbeats → 0x1b** (`counter(4,LE) ‖ rssiByte ‖ HMAC`), the 5th byte = the
   phone-measured **RSSI** to that device (`readRemoteRssi`, default −128). The car localizes the watch
   by RSSI across all devices → inside cabin = drive, at a door = unlock. The phone sends **nothing**
   special for unlock/drive — they're the car's decision from RSSI.

**Crypto:** `sessionSecret = HKDF-SHA256(ECDH(watchPriv, vehiclePub))`; HMAC-SHA256 signs nonces &
heartbeats; AES-128-GCM (key = first16(HMAC(sessionSecret, CONSTANT_B)), AAD = pNonce⊕vNonce) wraps the
64-byte **active commands** on 0x20. Active commands (unlock/lock proven on-vehicle) = full table in
`ActiveCommandFrames.Cmd`.

**Gotchas handled:** re-enroll leaves an orphaned BLE bond (encryption key-missing 0x6 → pairing
timeout) — `PairingManager.removeStaleBond()` clears it. **Cloud-only (not on BLE):** state-of-charge /
range (GraphQL `vehicleState`), open-liftgate/-tailgate (app routes via cloud; `0x2a` is the likely
firmware code, untested), charging, HVAC temp/seats.

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

### Signed-params — smali RE (2026-06-03, baksmali of classes3.dex)

`s60/b0.c` builds a protobuf `n1` and signs it via **`s60/b0.e(List<l1>, l60.x)`** (the same signer
the PRE-CCC ranging path uses). The signing is **HMAC-SHA256 with the session key — no new crypto**:
```
payload   = p1{ repeated l1 msgs }.toByteArray()        // the two built l1 messages, serialized
counter   = xVar.r (++ per signed msg)                  // l60.x/l60.t session packet counter
signature = q60.a.b( payload ‖ s60.h.n(counter) ‖ s60.h.p(xVar.b) ‖ pNonce ‖ vNonce )
          = HMAC-SHA256( sessionSecret, payload ‖ counter(4,LE) ‖ phoneId(16,BE) ‖ pNonce(16) ‖ vNonce(16) )
```
- `xVar.b = phoneId UUID`; `s60.h.p = o(uuid, BIG_ENDIAN)` (16B BE). `s60.h.n = int → 4B LE`.
- `xVar.d = pNonce`, `xVar.e = vNonce`. `q60.a.b(x) = HMAC-SHA256(sessionSecret, x)` (already reproduced).
- Envelope `e2` (built by `e()`): `{ data=payload(ByteString), seq=counter, vNonceNull=bool, pNonceNull=bool,
  sig=c2{ algo=HMAC_SHA256, mac=ByteString } }`. Written to `Q` (`0823DA14`, PLAIN_DATA_IN).

The `n1` payload carries: l1#1 = `{ counter, r{ x{} } }`; l1#2 = `{ PhoneProfile f0{ s1{ model(r1 enum),
swVersion(s60.h.e()) } } }`. Capability byte = `s60.h.f(ctx) | PSEUDO_PAIRING`.

**Crypto is solved; the remaining work is byte-exact protobuf reconstruction** of the ~10 message
types (`n1,l1,r,x,f0,s1,p1,e2,c2,d2` + enums `r1` model, `b2`=HMAC_SHA256) — field numbers/wire types
recoverable from the generated protobuf classes in `smali/classes3` — then: build+sign signed-params →
write to `Q` → await `Q` response → `createBond` → subscribe encrypted chars → heartbeat. No unknown
key or unrecoverable secret remains.

## RE update 2026-06-05 — official-app btsnoop of a FRESH device: sensors are in-the-clear (supersedes bonding/signed-params)

Captured an HCI btsnoop of the official app setting up a **brand-new, never-paired** device on this
R1S (738 s, `~/wearvian/snoop/btsnoop_hci.log`). It overturns the bonding/signed-params theory for
this Gen-1 car:

- **No link-layer pairing, no encryption, no bonding** — `0` Encryption Change events, `0` LTK
  requests, `0` Pairing Request/Response/Confirm. The only SMP PDU is the phone *receiving*
  "Signing Information" (CSRK), unencrypted. **Our HCI 0x05 failures were self-inflicted by our own
  `createBond()` (builds 2g/2h).** Bonding is removed.
- **No Q-char (0823DA14) / signed-params writes at all** — those belong to the **PRE-CCC** variant
  this car doesn't use. `SignedParams.kt`/`.proto` stay in-tree but unused for this vehicle.
- **The handshake is identical for the PK and every sensor** (in the clear):
  1. subscribe CCCD on **0x12 + 0x15 only** (NEVER 0x1c — the app never touches it; we were
     subscribing it first, which broke our sensor sessions),
  2. write **PhoneProfile (`10 80`) → 0x20**,
  3. write **phoneId → 0x12** ⇒ car notifies a **16-byte vehicle-id echo** on 0x12,
  4. write **pNonce (48 B) → 0x15** ⇒ car notifies a **48-byte vNonce** on 0x15,
  5. subscribe CCCD on **0x20** ⇒ receive the car's ranging stream (831 msgs observed),
  6. stream **0x1b heartbeats (37 B, RSSI 5th byte)**.
- Counts over the capture: CCCD 0x12 ×31, 0x15 ×31, 0x20 ×14, **0x1c ×0**; 14 full handshakes
  (14 echoes + 14 vNonces); heartbeats on ~13 connection handles ⇒ **sensors heartbeat too**.
- **Validated our framing**: heartbeat `00000000 80 <32B HMAC>`, nonce 48 B, 0x20 writes `01`/`1080`.

Implemented in `ble/VehicleSession.kt` (2026-06-05): drop bonding, drop 0x1c, subscribe
0x12/0x15 then 0x20, add the PhoneProfile write. Limitation: the bugreport scrubbed BD addresses,
so PK-vs-sensor can't be labelled from this capture (a raw `btsnoop_hci.log` pulled directly keeps
them). The earlier "## RE update 2026-06-03 (2)/(3)" bonding/signed-params sections are superseded
for this car.

## On-vehicle command results (2026-06-05, R1S)

Confirmed by the user driving/operating the actual vehicle with the watch as the only key:

| command | request code | result |
|---------|--------------|--------|
| Drive enable (presence/heartbeat) | — | ✅ works |
| Unlock / Lock | `0x03` / `0x06` | ✅ works |
| Frunk (hood) open / close | `0x26` / `0x27` | ✅ works |
| Liftgate (hatch) open / close | `0x2a` / `0x2b` | ✅ works (confirms the `0x2a` pattern-prediction) |
| **All windows open / close** | `0x15` / `0x16` | ❌ no effect |
| **Charge-port door open / close** | `0x36` / `0x37` | ❌ no effect |
| Gear Guard, Flash lights, Sound (page 3) | `0x17`/`0x18`, `0x6e`, `0x6f` | untested |

**Windows / charge-port are NOT a frame bug.** Our request codes are byte-exact with the official
app's descriptors (`h60/w` `{21,0}`=OPEN_ALL_WINDOWS, `h60/m` `{22,0}`=CLOSE, `h60/x` `{54,0}`=
OPEN_CHARGE_PORT_DOOR, `h60/n` `{55,0}`=CLOSE), and both carry firmware BLE **response** codes
(`OPEN_ALL_WINDOWS` 38/39, `CLOSE` 40/41, `OPEN_CHARGE_PORT_DOOR` 104/105, `CLOSE` 106/107 in
`CommandReturnValue`). The frame is structurally identical to frunk (bare 2-byte code, no parameter).
So the non-actuation is **vehicle-side** — the firmware receives the command but doesn't act (likely
gated: the official app may route these via cloud, or the vehicle requires a state/condition we don't
meet). **Next diagnostic (no new capture needed):** tap windows-open and read the `0x20`/`0x1c`
response in the debug console — a FAIL reply (`0x27`=open-windows-fail, `0x69`=open-charge-fail) means
*received-but-rejected* (state/permission gate); silence means *ignored*. That distinguishes a
conditional gate from a hard cloud-only block.

## Complete BLE active-command table (2026-06-05, from h60/* → k2.NAME)

Extracted every command descriptor (`h60/*` static `new byte[]{lo,0}, k2.NAME`). Authoritative copy
lives in `ActiveCommandFrames.Cmd`. **Open/close pairs are adjacent (open = close−1.)**

| code | command | | code | command |
|------|---------|-|------|---------|
| 0x03 | UNLOCK_ALL_CLOSURES | | 0x2b | CLOSE_LIFTGATE |
| 0x06 | LOCK_ALL_CLOSURES | | **0x2a** | **OPEN_LIFTGATE** (see note) |
| 0x26 / 0x27 | OPEN / CLOSE_FRUNK | | 0x10 / 0x11 | OPEN / CLOSE_TONNEAU (R1T) |
| 0x15 / 0x16 | OPEN / CLOSE_ALL_WINDOWS | | 0x0e / 0x0f | RELEASE_LEFT / RIGHT_SIDE_BIN (R1T) |
| 0x36 / 0x37 | OPEN / CLOSE_CHARGE_PORT | | 0x17 / 0x18 | ENABLE / DISABLE_GEAR_GUARD |
| 0x07 / 0x34 | PANIC_ON / OFF | | 0x19 / 0x1a | CABIN_PRECONDITION_ENABLE / DISABLE |
| 0x6e | FLASH_EXTERNAL_LIGHTS | | 0x72 / 0x73 | DRIVE_AUTH_USER_INPUT ALLOW / DENY |
| 0x6f | ACTIVATE_EXTERNAL_SOUND | | 0x5e / 0x5f | DRIVE_AUTH_MOBILE_NOTIF ENABLE / DISABLE |

**OPEN_LIFTGATE / OPEN_TAILGATE note:** in this app build their command classes are nulled to an
empty BLE byte[] (the app routes them via the **cloud**), and `0x2a` is reserved as `k2.NONE`. But
`0x2a` is exactly the open-liftgate code by the open=close−1 pattern, so the **vehicle firmware almost
certainly still accepts `0x2a`** — Rivian just stopped the app sending it over BLE. Worth trying
on-vehicle. (`0x0a/0x0c/0x12` also map to `k2.NONE` — reserved no-ops.)

**Cloud-only (no BLE code — need INTERNET / the companion):** WakeVehicle, Start/Stop charging,
SetChargingLimit, CabinPreconditioningSetTemperature (0x35 takes a temp arg), all HVAC seat/defrost
controls, GearGuard video, climate hold, software InstallNow. **State of charge / range / mileage are
cloud telemetry** (GraphQL `vehicleState`: `batteryLevel`, `distanceToEmpty`, `vehicleMileage`) — the
BLE path has no battery fields, so SoC is **not** retrievable over Bluetooth.

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

---

## 2026-06-06 — sleeping-truck command capture (charge-port + windows resolved)

Capture: official app on a **sleeping** R1S — unlock, then open/close charge-port door, then
vent/close windows. Decoded with `tools/btsnoop_rivian.py` (reusable; see its header for the full
protocol notes). This **overturns the "windows/charge-port are cloud-only" guess**.

### The 0x20 channel is the command *and* status bus

All command traffic and all status traffic share characteristic `0x20` (GATT value handle `0x0020`).
Frames are tagged by their first two bytes:

| tag | dir | size | role |
|-----|-----|------|------|
| `16 01` | phone → veh | 64 B | **COMMAND** = `[16 01][12B IV][AES-128-GCM ct+tag]` (our existing frame) |
| `17 01` | veh → phone | 67 B | **CMD-ACK** — encrypted CommandReturnValue (one per command) |
| `18 01` | veh → phone | 114 B | **STATUS** — encrypted vehicle/closure state, streamed continuously |
| `01 01…` | veh → phone | 11 B | SensorInformation (handshake control) |
| `07 xx … 01 00` | veh → phone | 6 B | per-heartbeat ranging/RSSI response |

### Windows & charge-port ARE sent over BLE — byte-identical to unlock

The capture has **5 `16 01` command frames** (unlock, CP-open, CP-close, windows-vent,
windows-close), each 64 B, each answered by a `17 01` ack. **The frame format and channel are
identical to unlock.** So our command codes (`0x36/0x37`, `0x15/0x16`) and frame wrapping are
correct — these commands are *not* cloud-routed.

### Why ours fail: they need a live, awake presence session — not a one-shot

The truck was asleep. The app does **not** just connect-and-command. It:

1. Reconnects + re-handshakes (phoneId `0x12` + nonce `0x15`) **4× over ~5 s** while the vehicle wakes.
2. Streams 37-byte heartbeats to `0x1b` continuously (**755** in this session, ~12 Hz) plus ranging.
3. Waits until the vehicle starts pushing `18 01` STATUS (first at t≈9.2 s) — i.e. it's awake.
4. **Only then** (t≈14 s, ~10 s after first contact) sends the first command, and keeps the
   heartbeat/status session running across all 5 commands.

Our `ActiveCommandManager` does a one-shot: connect → handshake → one command → disconnect.
Unlock/lock tolerate that (security module answers half-asleep), but charge-port/windows are gated
on the vehicle considering the phone **present** via the sustained heartbeat+ranging session. They
get a `17 01` ack (received) but the body modules drop them with no presence — matching the earlier
"received-but-rejected, vehicle-side gating" observation.

**Heartbeat counter:** the 37-byte heartbeat is `[le32 counter][1B flag][32B keyed payload]`. The
`le32` counter is plaintext, increments per authenticated message, and **resets to 0 on each
reconnect**. Commands share this running counter (HMAC binds `le32(counter)`), so a command sent
mid-session must use the *current* counter — a hardcoded `counter=0` is valid only as the **first**
message of a fresh connection (which is exactly why our one-shot unlock works and a warmup-then-unlock
regressed earlier).

### Implications for the app

- To make charge-port / windows / liftgate / etc. work, **send active commands through a live
  presence session** (the same heartbeat/ranging stream as drive) using the **running counter**, not
  the isolated one-shot connection. The drive presence loop in `PresenceService` already does most of
  this — fold command-sending into it rather than a separate connect.
- **Status is recoverable:** subscribe `0x20`, decrypt the `18 01` frames with our session secret to
  read closure/lock state (this is how the official app "knows"). Decode of the 114-B plaintext
  fields (lock, charge-port, windows, frunk, liftgate) is the next step — needs our own session so we
  hold the key; the official app's frames are encrypted under its key and can't be read offline.
- Final validation is on-vehicle (we hold the key; the car is the oracle) — unchanged constraint.

---

## 2026-06-06 (pm) — command counter is a separate "csn" (decompile-confirmed)

On-vehicle test of the command-via-session build: the vehicle terminated the link
(disconnect status `0x13`) within ~100–270 ms of **every** command, no `17 01` ack — the
command frames were rejected. The build fed the running **heartbeat** counter (74, 10, 12…)
into the command HMAC. The decompile shows why:

- **Commands and heartbeats use independent counters.** Command builder `em/f0.f`:
  `int i = tVar.r; tVar.r = i + 1;` — the command sequence number **`csn`** (`l60.x.r`,
  logged as `csn=` in `l60.t.b`). Heartbeats use a different field (`l60/i0` builds the
  PASSIVE_ENTRY request with `j0Var.k`). Both feed the same HMAC preimage shape
  (`pNonce ‖ vNonce ‖ le32(counter) ‖ payload`, `jh/a.i0` branch 2 vs 3) but from
  separate counter spaces.
- **csn init by connection type** (`l60.x` ctor / `a()` reset, table `l60.v`):
  `LEGACY → 0`, `PRE_CCC → 1`, `CCC → 1`. Our Gen-1 link is **LEGACY** (no CCC link
  encryption), so **csn starts at 0**, +1 per command, reset on each fresh nonce handshake.
- This is why the proven one-shot (single command, csn=0) always worked, and why feeding
  the heartbeat counter broke it. Fix: `VehicleSession` keeps a dedicated `commandCounter`
  (csn), 0-based, reset per reconnect.

Also confirmed: `s60/i0` message types `ActiveCMDRequest=0x16`, `ActiveCMDResponse=0x17`,
`VehicleStatus=0x18`; inbound decrypt `s60/e.a` = `IV=bytes[2..14]`, `ct+tag=bytes[14..]`,
AES-128-GCM, AAD=`pNonce⊕vNonce` — byte-identical to our `ActiveCommandFrames.decryptInbound`.

**Open thread — 20-byte vs 114-byte VehicleStatus.** Our session only ever receives 20-byte
`18 01` frames; the official app only ever receives 114-byte ones. A 20-byte frame is too
short to be a valid GCM VehicleStatus (20−2−12 = 6 < 16-byte tag) — the official decrypt
would fail on it too. So our session is getting a different/"lesser" status stream than the
official app's, persistently (not just during command rejection). Cause not yet found;
candidates: a status-subscription/request step we skip (the app subscribes `0x1c`
CHAR_VEHICLE_STATUS; we deliberately don't), or a session-class difference. Chase next.

---

## 2026-06-06 (pm) — VehicleStatus DECODED via 0x1c (hypothesis confirmed)

Subscribing CHAR_VEHICLE_STATUS (0x1c) PRIMARY-only/post-auth (branch
`wearvian-vehicle-status-0x1c`) WORKED — `subscribe=true`, no link breakage, and the vehicle
streamed **plaintext, structured** status frames (the rich stream we never got on 0x20). The
"missing subscription" hypothesis was correct: our old blanket drop of 0x1c (451fc90, to fix the
in-the-clear sensors) also killed the legitimate PRIMARY status subscription.

Frame = `[le32 counter (resets per reconnect)] ‖ [16-byte plaintext status]`. Decoded by correlating
deduped frames against a scripted on-vehicle action sequence (unlock, charge-port, frunk, lights,
4 windows in order, driver door close/open, lock):

| status byte | field | notes |
|---|---|---|
| `[0]` | lock/wake | `0x11` locked&asleep → `0x10` awake/unlocked (flipped at unlock) |
| `[1]` hi nibble | asleep flag | `0xf*` asleep → `0x0*` awake; reverts after lock |
| `[1]` bit `0x08` | **driver door** | 1=closed, 0=open (matched door close→open) |
| `[2]` hi nibble | asleep flag | `0xa*` asleep → `0x0*` awake |
| `[2]` bits `0x08`,`0x04` | **frunk + charge-port** | two closure bits; which-is-which TBD (needs a clean per-closure pass) |
| `[3]` 4 bits | **windows** | `0x08`=driver, `0x04`=passenger, `0x02`=driver-rear, `0x01`=pass-rear; 1=closed. `0x0f`=all closed. Matched 4 windows in exact order. |
| `[4..15]` | static config | `00 41 11 18 28 01 00 50 78 00 00 00` unchanged this session |

CONFIDENT: windows (`[3]`) + driver door (`[1]` bit 0x08). LIKELY: lock/sleep (`[0]` + `[1]`/`[2]`
hi-nibbles), frunk/charge-port (`[2]`). NOT in this frame: exterior lights (no byte changed during
L/R light toggles). The compact 20-byte 0x20 ack/status frames stayed undecryptable (the implicit-IV
prober found no match) — but moot now that 0x1c gives plaintext status directly.

### Refinement (2026-06-06, charge-then-frunk capture)
- `[1]` low nibble = **DOORS** (4 bits, 1=closed): `0x08`=driver, `0x04`=passenger, `0x02`=rear-driver,
  `0x01`=rear-passenger. CONFIRMED — a door walk (passenger→rear-passenger→rear-driver) cleared bits in order.
- `[2]` bit `0x04` = **liftgate** (CONFIRMED via OPEN/CLOSE_LIFTGATE 0x2a/0x2b timing); bit `0x08` = **frunk** (front).
  Charge-port still uncaptured-awake (it was cycled while the car was asleep → frames frozen at `11ffac`).
- `[0]` = **wake/sleep** (1=asleep), NOT lock — both unlocks coincided with wake, and a liftgate-while-locked
  also flipped it. Lock and sleep are coupled in all captures so far; no clean lock-only bit yet.
- `[5]` (frame byte 9) = `0x41` = 65 = **SoC%** strong candidate (matches 65.3% at capture time; constant across
  sessions as expected). Unproven until a capture with a different SoC. Charge-limit (70%) byte not yet found;
  other static bytes `[8]=0x28`,`[11]=0x50`,`[12]=0x78` unidentified (range/temp/limit?).
- Full decode now: `[0]`=wake/sleep, `[1]`=doors, `[2]`=frunk/liftgate(/charge-port), `[3]`=windows,
  `[5]`=SoC?, rest static/unknown.
  > **Superseded 2026-06-07:** `[5]`=SoC, `[7]`=cabin °C, `[8]`=range km, `[6]`lo=charge-state,
  > `[9..10]`=charge power are now decoded (the "byte-identical across all sessions" note only held
  > within one day's data). See the 2026-06-07 section at the bottom. Overturns the F1 "battery/range
  > = cloud-only" call.

### Refinement 2 (2026-06-06, locks capture — decode essentially complete)
- **LOCK** = high nibbles of `[1]` (0xf0) and `[2]` (0xa0): set=locked, clear=unlocked. Confirmed by two
  lock/unlock cycles WHILE AWAKE (toggled cleanly; brief `7f` transient mid-unlock). So `[0]` bit0 = asleep
  is SEPARATE from lock; earlier logs had both nibbles set only because locked＆asleep coincided.
- **FRUNK** = `[2]` bit `0x08` CONFIRMED (OPEN_FRUNK 0x26 cleared, CLOSE_FRUNK 0x27 set). So `[2]`:
  `0x08`=frunk, `0x04`=liftgate (1=closed), high-nibble=locked.
- **Charge port NOT in this frame** — cycled twice while awake, zero byte change.
- **Charge limit NOT in this frame** — changed 70→73% on the app, zero byte change. SoC `[5]=0x41=65` was a
  COINCIDENCE, not SoC: `[4..15]` byte-identical across all sessions. Confirms F1 (battery/SoC/limit = cloud
  GraphQL only, no BLE fields). Don't try to read battery from 0x1c.
- Final 0x1c map: `[0]`=asleep, `[1]`=lock(hi)/doors(lo), `[2]`=lock(hi)/frunk0x08/liftgate0x04,
  `[3]`=windows, `[4..15]`=static config. Everything needed for status icons is here except charge-port (cloud).

---

## 2026-06-07 — 0x1c carries SoC, range, and cabin temp (supersedes "battery = cloud-only")

A new on-vehicle capture (`sunday-status.log`) with **independent ground truth** — SoC 48.4%,
range 137 mi, cabin 86°F, climate setpoint 69°F — cracked three telemetry bytes in the 16-byte
status. The earlier "`[4..15]` byte-identical / battery is cloud-only" conclusion was an artifact
of comparing only **same-day** sessions; across days these bytes move with the real values.

Sunday status (`100f0c0f0030111edc00005078000000`) vs two documented prior captures:

| byte | sunday | prior-A | prior-B | field | check |
|------|--------|---------|---------|-------|-------|
| `[5]` | 48 | 59 | 65 | **SoC, integer %** | sunday 48 == 48.4% ✓ |
| `[7]` | 30 | 14 | 24 | **cabin temp, °C** | sunday 30 °C == 86 °F ✓ |
| `[8]` | 220 | 10 | 40 | **est. range, km** (low byte) | sunday 220 km == 137 mi ✓ |
| `[6]` lo | 1 | 1 | 1 | charge state (see below) | moved later, same day |

> **Range is `[8]` only, NOT `[8..9]`.** The charge capture below proves `[9]` is the charge-power
> low byte, so it can't also be range's high byte. The prior-A/B `[9]=01` values are unreliable
> (early RE; possibly a tiny charge-power reading, not range), so the "266/280 km" and the
> "~282 mi full-charge cross-check" are withdrawn. `[8]`=220 km matches sunday's 137 mi exactly;
> range above ~255 km would need a high byte we haven't located (capture at >158 mi to find it).

### Charge session (`sunday-status-plugged-in-and-charging.log`) — `[6]` and `[9..10]` decoded

A capture across unplug → plug → fault → charge ramp, with the user's narrative as ground truth
("charging failed at first / check charger, then ramped 2 kW → 9.1 kW, not sure I caught the end"):

**`[6]` low nibble = charge-state enum** (high nibble constant `0x1`). Maps exactly to the narrative:

| `[6]` | state | when |
|-------|-------|------|
| `0x11` | unplugged | Sunday |
| `0x15` | plugged, not charging (waiting for schedule) | the 16:09 idle capture |
| `0x17` | fault / "check charger" | the failed first attempt |
| `0x12` | starting/negotiating (one transient frame) | "tried again" |
| `0x13` | charging | the whole power ramp |

It's an enum, not a bitfield (charging `0x_3` lacks the "plugged" bit that idle `0x_5` carries).

**`[9..10]` (LE16) = live charge power, 1/64 kW per count** (= 15.625 W; `raw ÷ 64` kW). On-vehicle
the app read **~10 kW** at the captured plateau (raw ~644 → 10.06 kW). A first cut read the 0.1-kW
display as 10 W/count (`raw ÷ 100`), which showed 6.4 kW for the same frame — wrong by exactly
`10 / 6.4 ≈ 100/64`, pinning the real scale to **1/64 kW** (a binary fixed-point, not decimal).
(An even earlier cut guessed ≈14 W/count.) `644 / 64 = 10.06 kW`, `148 / 64 = 2.31 kW` (the ~2 kW
ramp start). Only `[6]`,`[9]`,`[10]` move during charging; SoC/cabin/range hold steady, as expected.

Implemented in `service/VehicleStatus.kt`: `State` exposes `socPercent`, `cabinTempC`, `rangeKm`
(low byte), `chargeState` ([ChargeState] enum), and `chargePowerW` (+`chargePowerKw`), null on short
frames, with known-answer tests (`VehicleStatusTest`) pinned to the real Sunday + charging frames.
Charge **limit** and the climate **setpoint** are still absent (both cloud-only; the setpoint never
varied across captures — to find it, capture before/after deliberately changing it).

**Revised 0x1c map:** `[0]`=asleep, `[1]`=lock(hi)/doors(lo), `[2]`=lock(hi)/frunk`0x08`/liftgate`0x04`,
`[3]`=windows, `[5]`=SoC %, `[6]`lo=charge-state, `[7]`=cabin °C, `[8]`=range km (low byte),
`[9..10]`=charge power (1/64 kW/count), `[11]`=const `0x50`, `[12]`=const `0x78`, `[4]`/`[13..15]`=zero/unknown.
Not in frame: charge limit, climate setpoint, charge-port door (all cloud-only).
