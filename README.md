# wearvian

WearOS app that acts as a Rivian phone key (BLE) for a Gen-1 R1S — unlock and **drive**
enabled by BLE proximity, fully offline once set up, no companion phone app required to *operate*.

> **Key protocol finding:** there is no discrete "drive" command. Once the watch is enrolled
> (one-time cloud step) and BLE-bonded to the vehicle, the R1S's passive-entry logic unlocks and
> enables drive automatically whenever it localizes the bonded key. The watch's job is to *be* a
> bonded phone key that maintains BLE presence. See [`PLAN.md`](PLAN.md) for the full design,
> protocol constants, and current status.

## Status — transfer checkpoint (2026-05-30)

Committed from an environment without the Android SDK, to be continued where the SDK is available.

- ✅ `wear/core-crypto/` — standalone Kotlin/JVM module, **tests pass** here
  (`cd wear/core-crypto && gradle clean test`). secp256r1 keygen + ECDH + HKDF-SHA256 + HMAC,
  with known-answer parity against the `rivian-python-client` reference.
- ⚠️ `wear/` — Android Wear OS app (Compose, BLE pairing/bond, presence service). Authored but
  **not yet compiled against the Android SDK or run on a device**.
- ⚠️ `auth-server/` — Python/Flask cloud-auth broker. **Reference only**: superseded by a planned
  **companion Android phone app** (own repo) that will own the Rivian login/MFA + enrollment.

## Layout

| Path              | What                                                               |
|-------------------|--------------------------------------------------------------------|
| `wear/`           | Android Studio Gradle project — the watch app                      |
| `wear/core-crypto/` | Pure-JVM crypto/protocol module (composite build, unit-tested)   |
| `auth-server/`    | Python Flask Rivian auth broker (reference; being replaced)        |
| `PLAN.md`         | Detailed design, protocol constants, risks, verification plan      |

## Next steps

1. Open `wear/` in Android Studio (SDK installed) and get it building/running on a Pixel Watch 4.
2. Build the companion Android phone app (Rivian login/MFA + `EnrollPhone`, hands credentials to
   the watch over the Wear OS Data Layer); structure it for extraction to its own repository.
3. On-vehicle: enroll → BLE bond → verify unlock + drive enable by proximity (see PLAN.md §Verification).
