---
name: queued-ux-robustness-tasks
description: "Queued post-enrollment tasks — key persistence, notification launch, keep-awake, digit keyboard, explicit lock/unlock"
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Tasks the user queued after the first successful on-vehicle enrollment (2026-06-02), to address before/around the M2-M3 work. Listed in the user's stated priority:

1. ~~**TOP PRIORITY — never reset the keying material.**~~ **DONE (2026-06-02).** Root cause was the one-tap unconfirmed "Reset" button calling `keyManager.deleteKey()`. Fixed: `SetupViewModel.reset()` now KEEPS the Keystore key (clears only enrollment cache); destructive deletion moved to `wipeIdentity()` (not wired to any casual button); Reset button is now two-tap; `refresh()` logs `hasKey=` each launch to verify persistence. `ensureKey()` was already idempotent and `allowBackup="false"` already set, so `adb install -r` preserves key + `EncryptedSharedPreferences`. (Built into APK pending next watch build.)
2. ~~Notification launches app on tap.~~ **DONE (2026-06-02).** `PresenceService` notification now has a `PendingIntent` to `MainActivity` as `contentIntent`.
3. ~~Keep the watch awake during the pairing/companion dance.~~ **DONE (2026-06-02).** `MainActivity` adds `FLAG_KEEP_SCREEN_ON` while phase ∈ {LOADING, NEEDS_SETUP, AWAITING_COMPANION, ENROLLED, PAIRING}; clears it once BONDED/ERROR.
4. **Companion app: digit keyboard for PIN/OTP entry.** DEFERRED — user wants to avoid touching the companion app for a while. The OTP `OutlinedTextField` should use `KeyboardOptions(keyboardType = KeyboardType.NumberPassword/Number)`.
5. **Explicit lock / unlock buttons** for when the vehicle has passive entry turned OFF. This is the M2 active-command path — and on 2026-06-02 neither passive entry nor drive worked on the Gen-1 R1S, so active commands may become the PRIMARY mechanism (matches the roadmap risk). Blocked on the active write-frame: see [[m2-active-command-frame-unknown]].

Also still pending (separate, smaller): the two companion logging tweaks (raw `getUserInfo` body + GraphQL-error surfacing) and the already-compiled version string. See [[enroll-requires-driver-invite-accepted]].
