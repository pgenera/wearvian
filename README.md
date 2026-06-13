# wearvian

Wear OS app that acts as a Rivian phone key (BLE) — unlock and **drive** enabled by BLE proximity,
fully offline once set up. A companion phone app is needed only to *enroll* (a Rivian cloud step),
never to *operate*. The watch has **no `INTERNET` permission**.

> **Key protocol finding:** there is no discrete "drive" command. Once the watch is enrolled
> (one-time cloud step) and presence-authenticated to the vehicle, the vehicle's passive-entry logic
> unlocks and enables drive automatically whenever it localizes the key (via an authenticated RSSI
> heartbeat session — *not* OS bonding, which the official app does not use). The watch's job is to
> *be* a phone key that maintains that BLE presence.

## Status

Version **0.5.3** (versionCode 1013). The watch is a working offline phone key, confirmed on-vehicle
on a Gen-1 **R1S**; **R1T** (truck) support is implemented from the decompile but not yet tested on a
truck. With the phone in airplane mode, the watch enrolls once (via the companion) then drives
passive entry/unlock and active commands entirely from its own BLE radio.

Confirmed working on the vehicle (R1S):

- ✅ **Passive entry + drive enable** by BLE presence (authenticated heartbeat/ranging session).
- ✅ **Active commands** — unlock/lock, frunk, liftgate, windows, charge port, climate precondition,
  panic. (Commands ride the live presence session, which is what makes the closure commands the
  vehicle gates on presence actually actuate.)
- ✅ **Live vehicle status** — lock, doors, windows, frunk, liftgate, SoC, range, cabin temp, charge
  state + time-to-limit, decoded from the plaintext `0x1c` stream and shown in the UI/tile.
- ✅ **Watch tile** — hex layout (key + six controls) with vehicle-state shading and live refresh.
- ✅ **Power-saving passive mode** (always on) — after an idle stretch it releases the wake lock and
  tears down BLE, then a hardware-offloaded scan rebuilds the link on approach.

R1T (decompile-derived, untested on a truck): the rear closure shows a **Tailgate** (open only — no
tailgate-close command exists) instead of the R1S hatch; the model is decoded from the VIN.

Foundations:

- ✅ `wear/core-crypto/` — standalone Kotlin/JVM module (secp256r1 + ECDH + HKDF-SHA256 + HMAC +
  AES-GCM command frames), known-answer parity tests. `cd wear/core-crypto && ./gradlew test`.
- ✅ `wear/` — Wear OS app (Compose, BLE handshake, command + presence session, tile). No `INTERNET`.
- ✅ **Companion phone app** — own repo,
  [`wearvian-companion`](https://github.com/pgenera/wearvian-companion). Performs the Rivian
  login/MFA + `EnrollPhone` and hands the watch its VAS IDs over the Wear OS Data Layer. The watch's
  EC private key never leaves the watch.

## Layout

| Path | What |
|------|------|
| `wear/` | Android Gradle project — the watch app |
| `wear/core-crypto/` | Pure-JVM crypto/protocol module (composite build, unit-tested) |
| `docs/passive-entry-protocol.md` | The reverse-engineered BLE protocol: handshake, heartbeat, active-command crypto, command-code table, `0x1c` status map. **Source of truth.** |
| `docs/companion-enrollment-protocol.md` | Watch ↔ phone Data Layer enrollment contract |
| `docs/proximity-wake.md` | Passive idle + auto-wake-on-approach design |
| `docs/play-store-packaging.md` | Single-listing (watch + companion) release packaging |
| `docs/wearvian-overview.md` | Plain-language overview (setup, battery, security) |
| `docs/next-steps.md` | Current backlog |
| `docs/HISTORY.md` | Original M1 design + early RE constants (historical) |

> Protocol/RE docs live **only in this (private) repo** — they're intentionally not in the
> companion repo.

## Building

Requires JDK 21 and an Android SDK with `platforms;android-35` + `build-tools;35.0.0`.

```sh
cd wear
./gradlew :app:assembleDebug          # watch app -> app/build/outputs/apk/debug/
cd core-crypto && ./gradlew test      # crypto parity tests
```
