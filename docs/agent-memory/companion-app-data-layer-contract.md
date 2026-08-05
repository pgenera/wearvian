---
name: companion-app-data-layer-contract
description: Wear OS Data Layer message contract between the wearvian watch app and the companion phone app
metadata: 
  node_type: memory
  type: reference
  originSessionId: 1a773a26-8818-4ca0-87c2-81d3e14bc129
---

The companion phone app (separate repo `pgenera/wearvian-companion`, see [[subagent-sandbox-confinement]]) does the one-time/monthly Rivian cloud enrollment and hands results to the watch over the **Wear OS Data Layer** (`MessageClient`/`DataClient`/`CapabilityClient`). The watch generates its EC keypair; the private key NEVER leaves the watch — only the public key (X9.62 uncompressed hex) is sent to the phone for `EnrollPhone`.

Contract (authoritative copy lives in `companion/PROTOCOL.md` — GitHub repo `pgenera/wearvian-companion`):
- Capabilities: phone advertises `wearvian_companion_enrollment`; watch advertises `wearvian_watch`.
- Path `/wearvian/enroll/request` (watch→phone): JSON `{v:1, requestId, publicKey(hex), deviceName, deviceType:"watch"}`.
- Path `/wearvian/enroll/result` (phone→watch): JSON `{v:1, requestId, status:"ok", vehicles:[{vehicleId, vin, vasVehicleId, vehiclePublicKey, vasPhoneId, identityId}], userId, sessionTokens:{csrfToken, appSessionToken, userSessionToken}}`. Failure: `{v:1, requestId, status:"error", error}`.

Implemented on both sides: watch `comms/EnrollmentContract.kt` + `comms/CompanionEnrollmentClient.kt`; companion `wear/EnrollmentContract.kt` + `WearEnrollmentListenerService.kt`. The old Flask `auth-server/` and watch QR flow were removed 2026-05-30.
- Primary transport `MessageClient`; fall back to `DataClient` PutDataRequest at same path if payload exceeds ~100KB.

**How to apply:** When implementing the watch side of enrollment, match these paths/payloads exactly. Rivian `EnrollPhone` publicKey must be X9.62 uncompressed hex (not PEM).
