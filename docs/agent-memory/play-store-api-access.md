---
name: play-store-api-access
description: "How to push wearvian AABs to Play internal testing via the Android Publisher API (key, SA, tracks)"
metadata: 
  node_type: memory
  type: reference
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

The watch can publish to Google Play programmatically (verified working 2026-06-17).

- **Service-account key:** `/home/pgenera/fivesevenfive.org_kittenarium-bf96e078232c.json`
  (SA `849072943716@project.gserviceaccount.com`, project `fivesevenfive.org:kittenarium`). Granted
  release access to the wearvian app in the Play Console. Use scope
  `https://www.googleapis.com/auth/androidpublisher`.
- **DON'T use the GCE metadata SA directly** — the instance's OAuth scopes don't include androidpublisher
  (or cloud-platform) and the metadata server silently ignores `?scopes=`, so any call 403s with
  `ACCESS_TOKEN_SCOPE_INSUFFICIENT`. The downloaded JSON key is what carries the right scope.
- **Tooling:** apt Debian packages `python3-googleapi` + `python3-google-auth` (no venv needed):
  `service_account.Credentials.from_service_account_file(KEY, scopes=[...androidpublisher])` →
  `build('androidpublisher','v3',...)`.
- **Package:** `org.fivesevenfive.wearvian` (one listing for both apps).
- **Tracks:** Wear OS has SEPARATE tracks from the phone. Watch AAB → **`wear:internal`**; companion
  (phone) AAB → **`internal`**. Full list seen: production/beta/alpha/internal + wear:Alycia/
  wear:Watch Alpha/wear:beta/wear:internal/wear:production.
- **Upload flow:** edits.insert → edits.bundles.upload(MediaFileUpload aab) → edits.tracks.update
  (track=wear:internal, release with the bundle's versionCode, status="completed"/"draft") → edits.commit.
- **Rules:** INTERNAL/wear:internal track ONLY — never production. Push only when the user explicitly
  says "upload". Each uploadable build needs a fresh, incremented versionCode ([[build-debugrelease-target]]).
  See [[toolchain-setup]].
