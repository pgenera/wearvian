---
name: be-sure-before-release-bundle
description: "Don't build release bundles speculatively — verify on a fast debug build first, build the AAB once"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Release bundles (`bundleRelease`, R8) take minutes — don't build one until you're
confident the code is final and correct. Batch all pending changes, verify on a
fast `assembleDebug` (~35s) installed via adb when a device is connected, and only
then build the release AAB **once** for upload.

**Why:** The user flagged building several release bundles in a row (2017/2018/2019),
each discarded because the next fix arrived right after. Wasteful and slow.

**How to apply:** When iterating on a fix, use debug APKs + on-device check (or
logcat) to confirm behavior. Reserve `bundleRelease` for the final, confirmed build
right before pushing to a Play track. Relates to [[build-debugrelease-target]],
[[no-build-by-default]], [[ha-key-import-feature]].
