---
name: build-debugrelease-target
description: How to produce a Play-uploadable watch build with debug features on (0x1c log capture)
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

To capture vehicle-status logs (the gated `0x1c`/`0x20` `VEHICLE_STATUS` frame logging in
`VehicleSession`, plus the SETTINGS/debug page) from a **Play-distributable** build, use the
`debugRelease` build type in `watch/wear/app/build.gradle.kts`:

```bash
nice -n 19 ./gradlew bundleDebugRelease -PvCode=<n>
# → app/build/outputs/bundle/debugRelease/wearvian-debugRelease.aab  (versionName 0.6.2-debug)
```

It `initWith(release)` so it's **upload-key signed + R8** (Play-acceptable, non-debuggable, installs
over the enrolled app without re-enroll) but flips `BuildConfig.PRODUCTION=false`, which is the gate
that turns the `0x1c` status logging back on (R8 does NOT strip `Log` — proguard-rules.pro only keeps
BLE callbacks). `-PvCode=<n>` overrides `versionCode` per build (defaultConfig reads `project.findProperty("vCode")`),
so each throwaway upload gets a fresh code without editing the file.

Rules: **ALWAYS pass an explicit, incremented `-PvCode`** — higher than the highest versionCode used by
ANY prior build, release OR debugRelease (they share the watch 1xxx lane). Do NOT build a debugRelease
without `-PvCode`: it then defaults to the base `versionCode` in build.gradle.kts, which equals the
current *release* versionCode, so it collides on Play and can't be uploaded (user feedback 2026-06-17 —
this bit them). Latest consumed code: **1046** (release, 0.9.1, 2026-07-03); the watch 1xxx lane keeps
climbing, so pass the next `-PvCode` higher than that. **Upload to a closed/internal testing track ONLY** —
never production: it logs sensitive usage data and exposes dev UI.

**When to ship debugRelease vs release:** `bundleRelease` (PRODUCTION=true) STRIPS the dev UI — the
SETTINGS page (with **Force passive** / force-R1T / force-watch) is only added when `!BuildConfig.PRODUCTION`
(`ControlScreens.kt` ~line 112), and the swipe-left BLE **debug console** + 0x1c logging are likewise gated.
So when the user needs to force passive transitions or read the on-device log, ship **debugRelease** — a
plain release build has none of it. (Mistake made 2026-07-02: told the user to "swipe to the debug console"
on a release build where it didn't exist.) See [[vehicle-status-0x1c-decode]],
[[toolchain-setup]], [[no-build-by-default]], [[gradle-builds-run-foreground]].
