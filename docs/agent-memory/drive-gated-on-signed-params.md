---
name: drive-gated-on-signed-params
description: "DRIVE WORKS on-vehicle (2026-06-05) — Gen-1 sensors use an in-the-clear app-layer handshake (no bond/encryption/signed-params); RSSI heartbeats localize for drive"
metadata: 
  node_type: memory
  type: project
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

**STATUS 2026-06-05: DRIVE ENABLE CONFIRMED WORKING on-vehicle** — the watch enabled driving via its
own BLE radio, fully offline. (Passive unlock not yet confirmed.) Reached after the "Drive 3" rewrite
(commit 451fc90: in-the-clear handshake, drop bonding/0x1c, match the official app). Re-enroll gotcha:
a stale BLE bond from a prior enrollment causes encryption key-missing (0x6) → pairing timeout;
`PairingManager.removeStaleBond()` auto-clears it (358cbdf). Watch notification shows vehicle
connection state (f0a0363). Both repos pushed.

**CORRECTION (2026-06-05, from an official-app btsnoop of a fresh never-paired device):** my earlier
"sensors need signed-params + bonding" theory was WRONG for this Gen-1 R1S. The snoop of the official
app setting up a brand-new device shows the sensors authenticate **entirely in the clear**:

- **No LE pairing, no encryption, no bonding** — 0 Encryption Change events, 0 LTK requests, 0 Pairing
  Request/Response/Confirm. (Only "Rcvd Signing Information"/CSRK PDUs, unencrypted.)
- **No Q-char (0823DA14) / signed-params writes at all.** The signed-params path (`s60/b0`, Q char) is
  the **PRE-CCC** variant; our car uses the **LEGACY** path which doesn't use it. `SignedParams.kt`
  stays in the tree but is **unused for this car** (still correct for PRE-CCC).
- Official app's per-sensor handshake (in-the-clear): enable CCCD on **0x12 + 0x15 only** → write
  **PhoneProfile to 0x20** (`1080`) → write **phoneId to 0x12** → nonce exchange on **0x15** → stream
  **0x1b heartbeats**. It does **not** enable 0x1c notify on sensors and **never** bonds.

**Our `0x05` (HCI auth failure) was self-inflicted:** it appeared only in builds 2g/2h, which
proactively called `createBond()`. The sensors don't support that pairing, so our bond attempt killed
the link (CONN_FAILED_ESTABLISHMENT). **Fix: drop bonding (revert 2g/2h); replicate the official app's
in-the-clear sensor handshake** (only 0x12/0x15 CCCDs; PhoneProfile→phoneId→nonce order; no 0x1c on
sensors; no createBond).

Snoop also **validated our framing**: heartbeat `00000000 80 <32B HMAC>` (37B), nonce 48B, 0x20 writes
`01`/`1080` — command/heartbeat/nonce crypto is correct. Snoop kept at `~/wearvian/snoop/btsnoop_hci.log`.
Sensor GATT handle map (per char value handle): 0x12=0x0012, 0x15=0x0015, 0x18=0x0018, 0x1b=0x001b,
0x1c=0x001d, 0x20=0x0020, 0x23=0x0023.

Unlock/lock work on-vehicle. See [[active-commands-are-encrypted]], [[passive-entry-is-a-session]],
[[ref-rivian-decompile-archive]].
