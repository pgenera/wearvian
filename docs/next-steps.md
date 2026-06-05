# wearvian — next steps (as of 2026-06-05, v0.2.5)

`main` is tagged **v0.2.5** in both repos. The core phone key (passive entry, drive,
lock/unlock, frunk, hatch) is **confirmed on-vehicle**. M2 proximity-wake and Play
packaging are scaffolded but **not yet validated**.

## Blocked on you (testing / accounts)
1. **Test M2 proximity wake at the car** — the make-or-break question: after going
   passive, does the OS's offloaded scan **restart the foreground service on approach**,
   or do we hit `proximity: FGS start blocked`? Watch `adb logcat -s wearvian`. If blocked
   → pivot the wake delivery to `CompanionDeviceManager` (idle-timer / MAC-learning halves
   stay). Idle timeout is back to the production **5 min**; ask for a 90 s test build for
   faster cycles. See `docs/proximity-wake.md`.
2. **Play Store** — once the developer account exists: create the upload keystore
   (`keytool`), fill `keystore.properties` in both repos, `:app:bundleRelease` each, upload
   the **phone** + **Wear** AABs to one listing with Play App Signing. See
   `docs/play-store-packaging.md`.

## Code I can pick up (mostly unblocked)
3. **Deep-sleep BLE wake (retry, carefully)** — a *separate, opt-in* path (don't touch the
   proven command flow). Fix the regression cause: continue the command counter past the
   heartbeat count (`counter = WAKE_BEATS`) instead of resetting to 0. Needs a genuine
   deep-sleep repro to confirm. (Reverted attempt: commit 73a5218.)
4. **F5 — live lock/closure status icons** — reflect real vehicle state in the UI. Gated on
   an on-vehicle `0x1c` decode capture (tap lock/unlock/frunk while logging, correlate which
   byte flips).
5. **Panic command** — add it with a confirmation dialog (the one control that needs a
   confirm before firing).
6. **Monetization architecture** (only if pursuing a paid tier) — keep **free = offline**
   with the clean no-INTERNET manifest; deliver **paid cloud features via a dynamic feature
   module** that adds INTERNET on purchase, so the free privacy story stays intact. Decision
   pending on exactly which feature/permission is gated.

## Parked / known limitations
- **Windows (`0x15`/`0x16`) + charge-port (`0x36`/`0x37`)**: request codes are byte-correct
  but the vehicle ignores them over BLE — hidden in the UI. Revisit with the `0x20`/`0x1c`
  FAIL-vs-silence diagnostic to learn whether it's a state gate or a hard cloud-only block.

## Housekeeping
- Remove the stray runtime logs committed earlier in history (`wearvian-*.log`) and gitignore
  them.
- Delete the merged feature branches (`wearvian-m1-phone-key`, `wearvian-m2-proximity-wake`)
  once `main` is confirmed canonical.
