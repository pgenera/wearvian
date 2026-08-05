---
name: vehicle-status-0x1c-decode
description: Rivian BLE vehicle status = plaintext 0x1c frames; layout is the app's own schema n50.f.SCHEMA_VERSION_1 (recovered from decompile 2026-07-05); charge POWER not present; subscribe PRIMARY-only post-auth
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

**AUTHORITATIVE UPDATE 2026-07-05 (supersedes the empirical guesses below):** the 0x1c layout is
the official app's own status schema **`n50.f.SCHEMA_VERSION_1`** (version byte 0x10, size 35),
recovered from the decompile — parser is `m6/b` = `BLEPath_LegacyBleVehicleStatusInterceptor`
method `A`, a byte→{mask→field} dictionary (fields/value-maps in `n50.d`). The app subscribes 0x1c
but the BLE lib (em/f0) doesn't field-parse it; this app-layer interceptor does, HMAC-verifying the
body — same parser as the richer 35-byte `requestFullVehicleStatusMessage` VAS response the app uses
when cloud is down. Our 16-byte push = `status[0..15]` of this layout. **0x1c is PLAINTEXT** (our
decode matches reality with no key); the crypto the user associates with it is the *full-status* VAS
path, not this push. Corrections applied to `VehicleStatus.kt`/`decode_status.py` (branch
wearvian-0x1c-schema): `[0]`bit0 "asleep" is really **CGM_ARM_ALARM** (Gear-Guard armed; kept as a
sleep proxy — drives the watch wake trigger); `[4]` old climateOn(0x0c)+inMotion(0x20) are one
**4-bit preconditioning enum** `[4]&0x3c>>2` (climateOn=1..4; inMotion DELETED — 0x20 was value-8
"unavailable" shown while driving); `[5]` SoC is **7-bit** (bit7=anti-theft alarm); `[6]` charge
`0x_8`=**user_stopped** (resolves the old "unidentified 0x_8" → PLUGGED_IDLE). `[11..26]` have NO
schema entries = reserved/framing (resolves the "config tail" mystery). NOT in the push (CONFIRMED
on-vehicle 2026-07-05 via 0.9.4-debug): `[2]`bit0 charge-port-door read "open" on a CLOSED port and
never changed — sibling frunk/liftgate use the same masked-0=open sense and work, so the bit is
UNPOPULATED, not inverted. So charge-port + `[2]`bit1 tonneau + `[5]`0x80 anti-theft are NOT in the
plaintext push — only the poll/full-status message (CHARGE_PORT_DOOR_OPEN_CLOSED /
CHARGE_PORT_CONTROL_STATE) or cloud. Wiring reverted in 0.9.5. Net: the push carries exactly the
`[0..10]` fields we decode; everything else needs the poll message. Full 35-byte message adds
`[27..34]`: pet mode, per-closure NEXT_ACTION, driver
occupancy, immobilizer, trailer, power mode. Full schema table in docs/passive-entry-protocol.md
(2026-07-05 section). Empirical history retained below.

**FULL status (type-0x18) 2026-07-05:** beyond the compact 0x1c push there's a FULL 35-byte status
(all schema indices 0..34, incl. per-closure NEXT_ACTION + CHARGE_PORT_CONTROL_STATE). The vehicle
PUSHES it as a **type-0x18 GCM frame on P/CHAR_ACTIVE_COMMAND** (the channel we already subscribe +
write PhoneProfile to — NOT an explicit request; phone only writes 1080/01). 126-byte frame → decrypt
(our active-cmd key schedule) → `[body][32B HMAC]`, schema at body offset 4 (per jh.q.j/m6/b.A).
**BLOCKER (on-vehicle 2026-07-08):** our watch session gets 20-byte `1801` beacons (below the 30B GCM
min → decryptInbound null), while the phone app gets 126-byte full frames. Tested live: a lock→unlock
cycle produced ACKs (17 01) + 0x1c pushes but ZERO full frames, only the one 20-byte beacon at connect.
**Root cause UNKNOWN.** DISPROVEN: keyDeviceSubtype=WATCH (the test device is enrolled asWatch=FALSE /
"acts as PHONE" and still gets only the beacon). DOUBTED (by owner): CCC-vs-legacy. NO poll exists
(no status VasRequestType/opcode; phone gets it PUSHED, only writes PhoneProfile+SensorInfo). Don't
re-chase WATCH-subtype or an app-side poll — the open question is why the vehicle picks the compact
beacon for our session (a handshake/session-class detail), likely only answerable by diffing a full
official-app session vs ours on the same car. SHIPPED 0.9.6 speculative+gated: `service/FullVehicleStatus.kt`
parses it if ever received + feeds UI (closure opening/closing throb + disable-when-not-allowed;
charge-port state); INERT (Full.valid=false) until a decryptable frame arrives. onActiveChannelMessage
logs every status frame size/shape so on-vehicle is diagnosable from logs (a ≥30B frame = watch CAN
get it). NEXT_ACTION bit-assembly follows m6/b.A but is UNVALIDATED — raw per-field values logged.
Tile also redesigned 0.9.6: frunk/hatch single toggles + window vent/close. See [[watch-vs-phone-enrollment]].

The watch can read live vehicle status (lock, doors, windows, frunk, charge-port) over BLE by
subscribing **CHAR_VEHICLE_STATUS (0x1c = afb2e704-842b-4e6a-9bd2-b1b305828f24)** on the PRIMARY
phone-key link, **post-auth** (not on sensors, not pre-auth — pre-auth on the in-the-clear sensors
forced link encryption / HCI 0x05, which is why we'd dropped it entirely in 451fc90). Decompile basis:
`em/f0.e` non-secured branch subscribes `l60.i.O` (=0x1c). Confirmed on-vehicle 2026-06-06: subscribe=true,
no breakage, **plaintext** structured frames stream.

Frame = `[le32 counter (resets per reconnect)] ‖ [16-byte plaintext status]`. Decoded layout (status bytes):
- `[0]`: bit0 = **asleep** (1=asleep, 0=awake). Separate from lock.
- `[1]`: hi-nibble = **LOCKED** (0xf0 set=locked); low nibble = **DOORS** (1=closed): 0x08=driver, 0x04=passenger, 0x02=rear-driver, 0x01=rear-passenger. CONFIRMED (door walk + lock/unlock cycles).
- `[2]`: hi-nibble = **LOCKED** (0xa0 set=locked); bit 0x08 = **frunk** (CONFIRMED OPEN/CLOSE_FRUNK 0x26/0x27), bit 0x04 = **liftgate** (CONFIRMED 0x2a/0x2b). 1=closed.
- `[3]`: **windows**, 1=closed: 0x08=driver, 0x04=passenger, 0x02=driver-rear, 0x01=passenger-rear. CONFIRMED.
- `[4]`: **climate/HVAC state** (DECODED 2026-06-09 via a climate on→off capture; 0 in every other capture). Stepped 0 → **0x04 (preconditioning starting)** → **0x08 (running)** → 0 (off) as the user turned climate on then off. Treat `& 0x0c != 0` as climate-on. Implemented as `State.climateOn`; UI fills the Climate button + shows "Preconditioning" while on. (Cabin temp `[7]` rose 27→29°C during the run = heat precondition; SoC `[5]` briefly misread 0 mid-session — transient.)
- `[6]` lo-nibble: **charge-state enum**. CONFIRMED via plug/charge narrative: 0x_1=unplugged, 0x_2=starting, 0x_3=charging, 0x_5=plugged-idle (waiting for schedule), 0x_7=fault("check charger"). Enum, not bitfield. **0x_8 = UNIDENTIFIED** (seen once 2026-06-09 at charge start, ETA raw 0 — some pre-charge/handshake state; maps to UNKNOWN in code; to ID it, note what the official app shows when it next appears).
- `[6]` **hi-nibble = GEAR / PRNDL** (DECODED 2026-06-16 from prndl.log, was assumed "const 0x1" only because every prior capture was parked): **1=Park, 2=Reverse, 3=Neutral, 4=Drive**. `State.gear` (enum). Also `[4]` bit **0x20 = in-motion/drive flag** (trails the gear by ~1 frame; `State.inMotion`). Used by PresenceService **driving-doze**: gear leaving Park → release wake lock + pause heartbeats but KEEP the link/0x1c. VALIDATED 2026-06-16 (drive-sleep.log, stationary ~34 s): the car kept streaming 0x1c with zero heartbeats (continuous counter, no reconnect), so battery is saved while in gear. **Promoted to production 2026-06-16** (PRODUCTION gate removed) — the user confirmed the watch stays on-wrist/in-range the whole drive, so the link never drops from range. KATs in VehicleStatusTest pinned to prndl.log; mirrored in decode_status.py (gear column). See [[parked-idle-power-save]], [[feature-branches-not-production-guards]].
- `[7]`: **cabin temperature, °C** (CONFIRMED twice: 30°C==86°F, 26°C==79°F).
- `[8..9]`: **estimated range, km** (280==174mi; CONFIRMED 2026-06-10 via mileage.log `18 01`=0x0118=280km). It is a **9-bit field that OVERLAPS the charge ETA in `[9]`**: range = `[8]` + **bit0 of `[9]`**, i.e. `([8] | [9]<<8) & 0x1ff`. When NOT charging the ETA is absent so `[9]` is a clean range high byte → use **full `[8..9]` LE16** (allows >511km/317mi). While charging `[9..10]` is the ETA, so `[9]`'s upper bits aren't range → **mask to 9 bits**. CONFIRMED 2026-06-12 via mileage-charging.log: at 68% charging, `36 cd` → `0xcd36 & 0x1ff` = 310km = **193mi** ✓ (app agreed). Two earlier WRONG reads: (1) old "[8..9] full LE16 always" overflowed mid-charge to 52534km; (2) the "[8] alone while charging" fix read 0x36=54km=**34mi** — the reported bug — and only *looked* right in the 50% amperage-sweep (0xe3=227km, where bit0 of `[9]` was 0). The mask reproduces 227 there (`0xe3d0 & 0x1ff`=0x0e3) AND 310 at 68%. KATs: `rangeUsesHighByteWhenNotCharging` (idle, full), `rangeIsNineBitsWhileCharging` (charging→310), amperage KATs (→227). Mirror in `decode_status.py` + `test_range_nine_bits_while_charging`.
- `[9..10]`: **charge TIME-to-complete, LE16 — NOT power** (CORRECTED 2026-06-09; the old "1/70 kW power" read was WRONG). Discriminator: a **fixed-SoC (50%) amperage sweep** — at constant SoC the energy-remaining is fixed, so power MUST rise with current, but the field *falls*: 20A→1488, 28A→1042, 44A→656, with raw×current ≈ const (~29k). That's the signature of time ∝ 1/power; impossible for power. The old 2-capture "raw/70=kW" fit (644→9.1, 692→9.9) and the "raw/255≈5.1kW" anomaly were the SAME illusion — both sat in the same ~9–10kW band where power and time are numerically degenerate, and neither varied current independently of power. **= charge ETA to the set LIMIT, 15 s per raw count = raw/4 min** (CONFIRMED 2026-06-10; the interim 16 s/count was WRONG). Two experiments + simultaneous app readings: (1) fixed-SoC(50%) amperage sweep proved ∝1/power (NOT power); (2) a **70%→90% limit A/B** at fixed SoC/current jumped raw **654→1184** ⇒ target is the **limit**, not 100% (reversed an interim "time-to-full" guess); (3) **scale = 15 s/count**: a simultaneous reading — official app **10h 13m** (613 min) vs our display **10h 58m** (658 min ⇒ settled raw 2468, multiple of 4) — gives 613×60/2468 = **14.9 s/count**, and the field **steps by 4 counts** so 4×15 = clean **60 s** display resolution (16 s would be an odd 64 s). The earlier "16 s" pin matched the *settled* raw 1184 to the app's "5h 18m", but 5h18m = raw 1272 (=318×4, clean) which sat in that 90% log's *early* ramp 1188..1368 — i.e. the app reading was taken early, not at the settled 1184, which is what inflated the scale to 16. Cross-check: 44A@70% raw 654→164 min vs physical 167 min (20% of Gen-1 Large 135kWh @9.7kW). SHIPPED: `State.chargeEtaSeconds = chargeTimeRaw*15`; ControlScreens shows "Charging · Xh Ym left". NOTE the **limit% itself is NOT in the frame** (cloud-only), so the UI shows the remaining time without the target%. **True charge POWER is NOT in this 16-byte frame at all** — across the sweep, only `[9..10]` (time) and `[7]` (cabin temp) varied; nothing tracks current. (Kills the old "find the AC/DC charge-mode selector" hunt — there is no selector.)
- `[11..15]` config tail is **NOT constant** across captures: old Sunday/charge frames = `50 78 00 00 00`; 2026-06-09 charging frames = `14 78 00 80 0c`. Only `[12]`=0x78 (and `[13]`=0x00) holds. `[4]`: zero/unknown. (decode_status.py marker path now takes the trailing 16 bytes instead of anchoring on the tail; fallback anchors on `[12..13]`=`7800`.)
- **NOT in this frame (cloud-only):** charge-port door, charge **limit**, **climate setpoint**, and **live charge power** (see `[9..10]`). Limit CONFIRMED absent 2026-06-08: a 70%→90% change left the frame byte-identical except `[5]`SoC/`[8]`range (natural drift). Setpoint=69°F constant across captures. To chase the setpoint, capture before/after deliberately changing it.

> CORRECTION (2026-06-07): two earlier calls were WRONG. (1) "battery/range=cloud-only" — SoC/range/cabin-temp/charge ARE on BLE; ground truth confirms. (2) "range=`[8..9]` LE16 / ~282mi cross-check" was REFUTED-then-REINSTATED: `[9]` is the range high byte **when not charging** (mileage.log: 280km), but the charge ETA low byte **while charging** — it's multiplexed, so `[8..9]` is right except mid-charge where range = `[8]` only. Only charge-limit + climate-setpoint are cloud-only.

LOCK/CLOSURE: `[1]`&`[2]` high nibbles=lock; ALL CONFIRMED: lock, doors([1]lo), windows([3]), frunk([2]0x08), liftgate([2]0x04), asleep([0]bit0), + SoC([5]), charge-state([6]lo), cabin°C([7]), range-km-low([8]); `[9..10]`=charge-time (∝1/power, unit TBD). Decode captures with `tools/decode_status.py LOG` (Python mirror of the Kotlin parser; keep the byte layout in sync, KATs in `tools/test_decode_status.py`). Implemented in `service/VehicleStatus.kt` (`State.socPercent/cabinTempC/rangeKm/chargeState/chargeTimeRaw`, KATs in `VehicleStatusTest` pinned to real Sunday + the 2026-06-09 amperage-sweep frames). ControlScreens shows "Charging" with NO kW figure (power isn't available). The compact 20-byte 0x20 `18 01` ack/status frames remain encrypted/undecodable but moot. See [[active-commands-are-encrypted]], [[ref-rivian-decompile-archive]], docs/passive-entry-protocol.md.
