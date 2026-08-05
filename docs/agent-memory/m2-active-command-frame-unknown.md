---
name: m2-active-command-frame-unknown
description: "M2 active-BLE-command write-frame isn't in the reference client; needs on-vehicle sniffing. Signing is ready."
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

The M2 milestone (active BLE commands: lock/unlock, frunk, windows, honk) is **blocked on an unknown byte-frame**, NOT on crypto. Verified by reading the reference `bretterer/rivian-python-client`:

- `src/rivian/ble.py` only implements **pairing** (`pair_phone`). The `ACTIVE_ENTRY` characteristic (`5249565F-…`) is used solely to trigger OS bonding (`start_notify(..., lambda _, __: None)`), never to send commands. There is **no active-command write-frame anywhere in the reference client** — it likely sends drive/closure commands over the cloud (`sendVehicleCommand`), or the BLE active-entry frame is undocumented.
- So the exact layout of how `command + timestamp + HMAC` is packed and written to `ACTIVE_ENTRY` must be **reverse-engineered by sniffing the actual vehicle** (HCI snoop log) — it can't be built offline.

**Signing is already done and proven:** our `RivianCrypto.signCommand(secret, command, timestamp)` matches the reference `utils.generate_vehicle_command_hmac` (HMAC-SHA256 over `command+timestamp` with the HKDF-derived ECDH key). The whole M1/M2 crypto core is pinned to a reference-generated vector in `RivianCryptoTest` (5/5 passing; vector independently re-confirmed against the real `cryptography` lib). Only the frame wrapping is missing.

**How to apply:** Don't write speculative M2 frame code before the on-vehicle proof. When at the car, capture the Bluetooth HCI snoop log during an official-app command to recover the `ACTIVE_ENTRY` frame layout, then build `ActiveCommandFrames.kt` mirroring `PairingFrames.kt`. See [[rivian-auth-unauthenticated]].
