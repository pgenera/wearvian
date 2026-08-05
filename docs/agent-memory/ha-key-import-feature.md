---
name: ha-key-import-feature
description: HA-key import flow — bring an existing Rivian key onto the watch via QR + companion
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Lets an already-enrolled Rivian phone key (negotiated by the unofficial Home
Assistant integration) be imported onto the watch without re-enrolling or burning
a key slot. Branch **wearvian-ha-import** on BOTH repos (built + on internal
testing 2026-06-18: watch 1028, companion 2015, both 0.7.0; not yet merged to main
or pushed to GitHub).

- **QR generator:** `companion/tools/ha_to_qr.py` — OFFLINE. Reads an HA
  `core.config_entries` (or single exported entry), extracts keypair +
  `user_session_token` (+ uid/username), renders a console QR. No network (the HA
  box may be offline). Payload schema `t:"wearvian-ha-import"`.
- **Companion:** hidden — tap the bottom half of the home screen **5×** to reveal
  "Scan HA import QR" (GMS Code Scanner, no camera permission). Then: mint fresh
  CSRF + the QR's user_session_token → `RivianCloud.resolveImport` (getUserInfo
  ONLY, no EnrollPhone — key already enrolled) → push key+enrollment to the watch,
  await ack. `RivianGql.findEnrolledKey` handles `enrolled` being a LIST.
- **Watch:** `WearImportListenerService` (manifest-registered) receives the push;
  `KeyManager.importKey` stores the key NON-EXTRACTABLY in the Keystore (own alias
  `wearvian_imported_key`, `unlockedDeviceRequired`) via a bcpkix self-signed cert,
  then discards the raw bytes. Mode = which alias exists (imported wins, never both).
  Caveats: not StrongBox (HW won't import arbitrary keys; software/TEE), and the
  plaintext existed off-device pre-import.

**OUTCOME (2026-06-21): SHELVED — won't work.** Import + cloud resolve + push to watch
all work end-to-end, BUT the truck won't let the watch initiate BLE pairing without
first DELETING the HA key — which defeats the purpose (the watch IS that key). Making it
viable would mean rewiring HA's auth to enroll as a watch instead of a BT proxy: not
worth it. Branch **wearvian-ha-import** kept on GitHub (both repos) as a record, NOT
merged. **Side effect:** logging into the HA Rivian account from the companion (the
normal enrollment path runs a full `login`) ROTATES the session and invalidates HA's
stored access/refresh/user_session_token → HA can't auth. Fix tool:
`companion/tools/ha_reauth.py` (fresh Rivian login → patches the three token fields in
HA's core.config_entries; stop HA first). Lesson: a Rivian `login` invalidates other
sessions' tokens; `getUserInfo` (read) does not.

**HA uses BLE only for the one-time phone-key negotiation, then runs cloud-only**
(user, 2026-06-20). So there's no ongoing HA↔car BLE to contend with the watch: after
import the watch is the sole live BLE user of that key. No need to remove the cloud key
(the watch IS that key — deleting it kills the watch); a stale car↔HA bond just sits
inert and our pairing auto-clears it if it interferes. Import verified end-to-end
2026-06-20: companion resolves + pushes, watch stores key + enrollment and acks the VIN,
watch shows "ready to pair." (Watch UI needed an auto-refresh on import-landed — fixed.)

**Key facts proven live (getUserInfo with HA's stored user_session_token works):**
a fresh CSRF + the stored `user_session_token` authenticates getUserInfo — no
re-login. HA's `vehicle_control` id is NOT the vehicleId/vasVehicleId (opaque); the
vehicle is resolved by public-key → enrolledPhones → enrolled[].vehicleId. HA blob
does NOT store the vehicle public key (fetched live), so a cloud call is required.
NEVER commit `core.config_entries`/`ha-key.json` (real creds; gitignored in
companion). Relates to [[rivian-test-account]], [[play-store-api-access]],
[[wear-datalayer-same-appid]], [[no-logging-long-strings-or-secrets]].
