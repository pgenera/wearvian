# wearvian

WearOS app that acts as a Rivian phone key (BLE) for a Gen-1 R1S — unlock and **drive**
enabled by BLE proximity, fully offline once set up. No companion phone app is required to
*operate* (only to *enroll*).

> **Key protocol finding:** there is no discrete "drive" command. Once the watch is enrolled
> (one-time cloud step) and BLE-bonded to the vehicle, the R1S's passive-entry logic unlocks and
> enables drive automatically whenever it localizes the bonded key. The watch's job is to *be* a
> bonded phone key that maintains BLE presence. See [`PLAN.md`](PLAN.md) for the full design,
> protocol constants, and current status.

## Status (2026-06-07, v0.4.1)

**The watch is a fully working offline phone key — confirmed on-vehicle (Gen-1 R1S).** With the
phone in airplane mode, the watch enrolls (once, via the companion), then drives passive
entry/unlock and active commands entirely from its own BLE radio.

Confirmed working on the vehicle:

- ✅ **Passive entry + drive enable** by BLE presence (heartbeat/ranging session). The vehicle
  unlocks on approach and allows drive with phone/cloud fully off.
- ✅ **Active commands** — unlock/lock (`0x03`/`0x06`), frunk open/close (`0x26`/`0x27`), liftgate
  open/close (`0x2a`/`0x2b`). (Windows + charge-port frames are byte-correct but the vehicle
  ignores them on a one-shot connection — see `docs/passive-entry-protocol.md`; parked.)
- ✅ **Live vehicle status** — lock, doors, windows, frunk, liftgate decoded from the plaintext
  `0x1c` status stream and reflected in the UI (`docs/passive-entry-protocol.md` has the byte map).
- ✅ **Watch tile** — hex layout: key in the center plus six icon controls (lock/unlock, frunk
  open/close, hatch open/close), with vehicle-state button shading and live refresh.
- ✅ **M2 proximity wake** — opt-in passive idle (releases the wake lock, tears down BLE) with a
  hardware-offloaded scan that auto-rebuilds the link on approach; full active→passive→wake→active
  cycle confirmed on-vehicle. See `docs/proximity-wake.md`.

Foundations:

- ✅ `wear/core-crypto/` — standalone Kotlin/JVM module, **tests pass**
  (`cd wear/core-crypto && ./gradlew test`). secp256r1 keygen + ECDH + HKDF-SHA256 + HMAC +
  `signCommand`, with known-answer parity against the `rivian-python-client` reference.
- ✅ `wear/` — Android Wear OS app (Compose, BLE handshake, command + presence session, tile).
  Builds to a debug APK against the Android SDK. No `INTERNET` permission.
- ✅ **Companion phone app** — own repo,
  [`wearvian-companion`](https://github.com/pgenera/wearvian-companion). Performs the Rivian
  login/MFA + `EnrollPhone` and hands the watch its VAS IDs over the Wear OS Data Layer. The
  watch's EC private key never leaves the watch.

The standalone Flask cloud-auth broker (`auth-server/`) and the watch's QR/browser enrollment
flow have been **removed** — the companion app replaces them.

## Layout

| Path                | What                                                              |
|---------------------|------------------------------------------------------------------|
| `wear/`             | Android Gradle project — the watch app                           |
| `wear/core-crypto/` | Pure-JVM crypto/protocol module (composite build, unit-tested)   |
| `PLAN.md`           | Original M1 design + protocol constants (historical; M1 is done) |
| `docs/passive-entry-protocol.md` | Reverse-engineered BLE protocol: handshake, heartbeat, active-command crypto, `0x1c` status map |
| `docs/proximity-wake.md`         | M2 passive idle + auto-wake-on-approach design |
| `docs/play-store-packaging.md`   | F3: single-listing (watch + companion) release packaging |
| `docs/next-steps.md`             | Current backlog / what's blocked on testing vs. pickable |

Enrollment handoff protocol: see the companion repo's
[`PROTOCOL.md`](https://github.com/pgenera/wearvian-companion/blob/main/PROTOCOL.md).

## Building

Requires JDK 21 and an Android SDK with `platforms;android-35` + `build-tools;35.0.0`.

```sh
cd wear
./gradlew :app:assembleDebug          # watch app -> app/build/outputs/apk/debug/
cd core-crypto && ./gradlew test      # crypto parity tests
```

## Next steps

The phone-key milestones (M1 unlock/drive, M2 proximity wake, F5 live status) are validated
on-vehicle. Remaining work is in [`docs/next-steps.md`](docs/next-steps.md); the headline items:

1. **Play Store packaging** (F3) — blocked on the developer account; bundles + signing ready
   (`docs/play-store-packaging.md`).
2. **Windows / charge-port** — byte-correct but ignored on a one-shot connection; likely need a
   command sent through the live presence session (`docs/passive-entry-protocol.md`).
3. **Panic command** with a confirmation dialog.
