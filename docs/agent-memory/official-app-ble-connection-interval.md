---
name: official-app-ble-connection-interval
description: "Official Rivian app runs BLE links at 45ms baseline (never HIGH); relax intervals, don't tear down links"
metadata: 
  node_type: memory
  type: reference
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

From the official-app HCI btsnoop (`rivian-re/captures/2026-06-05T0612_setup-plus-driving_2021s.btsnoop`,
analyzed with tshark 2026-06-16): the official Rivian app runs its 5 BLE links at a **45 ms baseline**
(connection interval 36 × 1.25 ms) and dynamically relaxes to **90 ms / 135 ms** (72 / 108). It
**NEVER uses `CONNECTION_PRIORITY_HIGH` (11–15 ms)**, and the car happily honours intervals up to
135 ms.

Implications for the watch (battery work, branch `wearvian-wakelock-reduction`):
- Our `CONNECTION_PRIORITY_HIGH` was ~4× more aggressive than Rivian's own app for no benefit — switched
  the active baseline to **BALANCED** (~30–50 ms; matches the app's 45 ms, still carries 300 ms heartbeats).
- The car honours slow intervals, so the `LOW_POWER` request during driving-doze is likely accepted, not overridden.
- **Don't tear down the sensor links to save power** — the capture shows BLE reconnection is flaky (25
  `0x3e` failed-to-establish among 70 connects / 65 disconnects / 23 handles). Interval relaxation is the
  official app's own strategy and far safer than teardown-and-rebuild-per-drive.

Reading connection interval from inside the app is impossible: `onConnectionUpdated` is a hidden API;
`requestConnectionPriority()` only returns "request started". Verify actual intervals via an HCI/btsnoop
sniff. See [[ref-btsnoop-captures]], [[drive-gated-on-signed-params]], [[vehicle-status-0x1c-decode]].
