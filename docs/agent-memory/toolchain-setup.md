---
name: toolchain-setup
description: "JDK + Android SDK locations, build commands, repo layout, signing, and host facts for the wearvian apps"
metadata: 
  node_type: memory
  type: project
  originSessionId: b194ce1f-01a8-4332-84ac-f74059335d3e
---

Build toolchain for the wearvian apps (watch Gradle root `watch/wear/`, companion `companion/`):

- **JDK 21** at `/usr/lib/jvm/java-21-openjdk-amd64`. The system also carries a newer JDK (25) that is TOO NEW for Gradle 8.14.3 / AGP 8.7.3 — never build with it. JDK **17 is not installable** via this Debian's apt (no candidate), but 21 is correct: the `JavaVersion.VERSION_17` in the build files is the bytecode target, not the build JVM. Point Gradle at 21 via `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (or `-Dorg.gradle.java.home=...`).
- **Android SDK** at `/home/pgenera/android-sdk`, installed via Google cmdline-tools — NOT the Debian `android-sdk` package, which is a dead end (no platforms, build-tools 29 only, no sdkmanager). Contains `platforms;android-35`, `build-tools;35.0.0` (+`34.0.0`, auto-pulled by AGP), `platform-tools`. Point builds at it with `export ANDROID_HOME=/home/pgenera/android-sdk` and/or a gitignored `local.properties` with `sdk.dir=/home/pgenera/android-sdk`. Needs AGP 8.7.3, Kotlin 2.0.21, compileSdk 35.

Typical build: `export ANDROID_HOME=… JAVA_HOME=…; nice -n 19 ./gradlew assembleDebug|assembleRelease|bundleRelease`.

**Repo layout** (reorg 2026-06-08, under `/home/pgenera/wearvian/`): watch repo at `watch/` with its Gradle root at **`watch/wear/`** (run `./gradlew` from there); companion repo at `companion/` (Gradle root = repo root); Rivian decompile at `rivian-re/`.

**Release signing:** `/home/pgenera/wearvian/wearvian-upload.jks` via each project's `keystore.properties` (present). Watch release falls back to the debug key if keystore.properties is absent; companion only signs when present. NOTE: `~/.android/debug.keystore` on this host was freshly generated (different signature than the old machine), so in-place `adb install -r` over debug builds installed from the old machine will fail until that keystore is restored.

**This host:** ~14 GiB RAM, NO swap, 4 cores (upgraded 2026-06-16 from 7.3 GiB / 2 cores). RAM/daemon tuning lives in [[gradle-daemon-config]]. Don't build unsolicited: [[no-build-by-default]].

**Output paths:** watch APK `watch/wear/app/build/outputs/apk/{debug,release}/wearvian-{debug,release}.apk`; watch AAB `…/bundle/release/wearvian-release.aab`; companion `companion/app/build/outputs/apk/{debug,release}/app-{debug,release}.apk`.

**User preference (2026-05-30):** run all Gradle builds at LOW CPU priority — prefix every invocation with `nice -n 19` (builds were hogging the machine).
