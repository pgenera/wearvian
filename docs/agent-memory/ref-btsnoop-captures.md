---
name: ref-btsnoop-captures
description: Archived official-app HCI btsnoop captures (ground truth) with per-journey metadata
metadata: 
  node_type: memory
  type: reference
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

Official-app HCI btsnoop captures (ground truth for the watch RE) live at
`/home/pgenera/wearvian/rivian-re/captures/` with a README mapping each log → user journey. Keep out of git
(contain vehicle/phone identifiers + keyed material). Open with tshark/Wireshark.

- `2026-06-05T0611_fresh-pairing_738s.btsnoop` — first-time setup of a never-paired device; proved
  sensors are in-the-clear (no bonding/encryption/signed-params, never subscribe 0x1c).
- `2026-06-05T0702_passive-unlock-drive-lock_339s.btsnoop` — full walk-up→unlock→sit→brake→drive→
  walk-away→lock cycle; proved the phone sends nothing special for drive (RSSI heartbeats only).
- `2026-06-05T0612_setup-plus-driving_2021s.btsnoop` — long corroborating session.

Source bugreports in `~/`. See [[drive-gated-on-signed-params]], [[ref-rivian-decompile-archive]],
and `wearvian/docs/passive-entry-protocol.md` (RE update 2026-06-05).
