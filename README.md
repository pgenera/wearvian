# wearvian

WearOS app that acts as a Rivian phone key (BLE) for a Gen-1 R1S — unlock and **drive**
enabled by BLE proximity, fully offline once set up. No companion phone app is required to
*operate* (only to *enroll*).

> **Key protocol finding:** there is no discrete "drive" command. Once the watch is enrolled
> (one-time cloud step) and BLE-bonded to the vehicle, the R1S's passive-entry logic unlocks and
> enables drive automatically whenever it localizes the bonded key. The watch's job is to *be* a
> bonded phone key that maintains BLE presence. See [`PLAN.md`](PLAN.md) for the full design,
> protocol constants, and current status.

## Status (2026-05-30)

- ✅ `wear/core-crypto/` — standalone Kotlin/JVM module, **tests pass**
  (`cd wear/core-crypto && ./gradlew test`). secp256r1 keygen + ECDH + HKDF-SHA256 + HMAC, with
  known-answer parity against the `rivian-python-client` reference.
- ✅ `wear/` — Android Wear OS app (Compose, BLE pairing/bond, presence service). **Builds to a
  debug APK** against the Android SDK. Enrollment now happens via the companion phone app over the
  Wear OS Data Layer (no cloud calls from the watch).
- ✅ **Companion phone app** — lives in its own repo,
  [`wearvian-companion`](https://github.com/pgenera/wearvian-companion). Performs the Rivian
  login/MFA + `EnrollPhone` and hands the watch its VAS IDs over the Data Layer. The watch's
  private key never leaves the watch.

The standalone Flask cloud-auth broker (`auth-server/`) and the watch's QR/browser enrollment
flow have been **removed** — the companion app replaces them. The watch app no longer requests
the `INTERNET` permission.

## Layout

| Path                | What                                                              |
|---------------------|------------------------------------------------------------------|
| `wear/`             | Android Gradle project — the watch app                           |
| `wear/core-crypto/` | Pure-JVM crypto/protocol module (composite build, unit-tested)   |
| `PLAN.md`           | Detailed design, protocol constants, risks, verification plan    |

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

1. On-vehicle: enroll (via companion) → BLE bond → verify unlock + drive enable by proximity
   (see PLAN.md §Verification). This is the definitive milestone-1 acceptance test.
2. Implement the watch-side BLE presence/localization tuning once validated on the R1S.
