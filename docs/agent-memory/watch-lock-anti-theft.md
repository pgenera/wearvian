---
name: watch-lock-anti-theft
description: Stolen-watch protection — Layer 1 (runtime keyguard gating) + Layer 2 (unlockedDeviceRequired new keys); branch wearvian-watch-lock
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Before this, NOTHING gated the phone key on watch lock/wrist state — an activated key kept streaming presence heartbeats off-wrist, so a stolen watch could passively unlock/drive. Fixed on branch `wearvian-watch-lock` (2026-06-05, UNTESTED on-device).

**Key nuance:** the Keystore EC key is used ONLY for the initial ECDH; after that the derived `sharedSecret` is cached in app memory and every heartbeat/command uses *that*. So `setUnlockedDeviceRequired` on the key (Layer 2) only gates *establishing* a session — it does NOT stop an already-running session. Runtime protection MUST be app-level (Layer 1).

- **Layer 2** (`KeyManager.generate`): new keys get `setUnlockedDeviceRequired(true)`. Existing keys untouched (ensureKey generates only when absent) — user explicitly wanted current key to keep working.
- **Layer 1** (runtime): `VehicleSession` heartbeat loop skips sending while `keyguard.isDeviceLocked` (GATT stays up → resumes instantly on unlock); `ActiveCommandManager.sendCommand` aborts if locked. Protects old AND new keys.
- **Dependency:** only engages if the watch has a secure lock (PIN/pattern). `SetupViewModel.isDeviceSecure()` → Settings page warns "No watch lock set — protection OFF" when false. On Wear OS, a secure lock + removal → `isDeviceLocked()` true; worn (even screen-off) → false.

**CRITICAL on-device test before merge:** confirm `isDeviceLocked()` stays false while worn with screen off, or passive entry breaks for the legit wearer. See [[passive-entry-is-a-session]], [[drive-gated-on-signed-params]].
