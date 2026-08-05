---
name: query-live-track-before-vcode-bump
description: the committed versionCode default trails the Play track; query the live track before picking a new code
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e533784a-1d88-4acf-8f1d-58490cfb0c28
  modified: 2026-07-22T00:16:33.266Z
---

Before bumping a versionCode for a Play upload, query the **live track state** (list app bundles +
the target track's current release) — do NOT trust the committed default in `build.gradle.kts` or a
prior summary.

**Why:** throwaway/log-capture builds are uploaded with the `-PvCode=N` CLI override, which advances
the Play track WITHOUT editing (or committing) the default versionCode. So the committed default
routinely trails the real track. In 2026-07 the committed watch default was 1053 while wear:internal
had already reached 1056 ("flap fix + startup tests") via `-PvCode=` builds. Picking 1054 (summary
said "1053 is live") collided with an existing bundle AND was a downgrade from 1056 → Play rejected
the commit with 403 "does not allow any existing users to upgrade to the newly added APKs."

**How to apply:** run the inspector pattern — insert an edit, `edits().bundles().list()` for all
uploaded versionCodes, `edits().tracks().get()` for the live release's versionCodes — then choose the
next code strictly above every uploaded bundle and the live release. See [[play-store-api-access]],
[[be-sure-before-release-bundle]].
