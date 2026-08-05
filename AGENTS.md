# AGENTS.md — wearvian (Wear OS watch app)

Operational guide for AI coding agents working in this repo. This file is the durable home for
project knowledge that previously lived only in a Claude Code memory store (`~/.claude/…/memory/`);
a verbatim archive of those notes is in `docs/agent-memory/`. Prefer this file; fall back to the
archive for detail.

## What this is

**wearvian** turns a Wear OS smartwatch into a standalone **Rivian phone key** over BLE: lock/unlock,
passive proximity entry, drive-enable, and at-a-glance vehicle status — no phone or fob needed. The
BLE and cloud protocols were reverse-engineered from the official Rivian app.

- **applicationId:** `org.fivesevenfive.wearvian` (the code namespace is the same; companion uses
  `…​.companion` as its *namespace* but the SAME applicationId — see Signing).
- **Related repos/dirs on this VM:**
  - `../companion` — phone companion app (GitHub `pgenera/wearvian-companion`). Has its own AGENTS.md.
  - `../rivian-re` — persistent decompile + BLE capture archive + `oracle/` recovered schemas. Not
    version-controlled; regenerable per its `README.md`. Source of truth for protocol questions.
  - `../tools` — release helper scripts (`play_upload.py`, `play_promote.py`, `ha_probe.py`) + saved
    release-notes files. Not a git repo; lives on VM disk only.

## Environment & host

- **JDK 21:** `/usr/lib/jvm/java-21-openjdk-amd64` (set `JAVA_HOME`).
- **Android SDK:** `ANDROID_HOME=/home/pgenera/android-sdk`.
- **Host:** ~14 GiB RAM, 4 cores, **no swap**. Gradle heap is 6 GiB (`gradle.properties`); the daemon
  is resident and shared with the companion project (identical jvmargs → one daemon). R8 is the
  memory-hungry step.
- **Run builds in the FOREGROUND.** Backgrounded Gradle processes do not reliably persist on this
  host — start the build, wait, verify the artifact. (`nice -n 19` to stay friendly to the box.)

## Toolchain versions

AGP **8.11.1**, Kotlin **2.0.21** (+ compose-compiler plugin 2.0.21), Gradle **8.14.3**.
`compileSdk`/`targetSdk` = **36** (Android 16 — Play-mandated), `minSdk` = **33** (Wear OS 4+).
AGP 8.11 natively supports compileSdk 36 (no `suppressUnsupportedCompileSdk` flag needed).

## Gradle root & build commands

The Gradle project root is **`wear/`** (wrapper at `wear/gradlew`, module `:app`). From the repo root:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/pgenera/android-sdk
nice -n 19 ./wear/gradlew -p wear :app:compileDebugKotlin      # fast compile check
nice -n 19 ./wear/gradlew -p wear :app:testDebugUnitTest        # JVM unit tests
nice -n 19 ./wear/gradlew -p wear :app:assembleDebug            # sideload APK -> wearvian-debug.apk
nice -n 19 ./wear/gradlew -p wear :app:bundleRelease            # Play AAB (R8 + lintVital)
```

### Build variants (buildType → `BuildConfig.PRODUCTION`)

| Variant | PRODUCTION | R8 | Signing | Use |
|---|---|---|---|---|
| `debug` | false | no | debug key | development / sideload |
| `release` | true | yes | upload key | production Play release |
| `debugRelease` | false | yes | upload key | Play **testing track** with debug features + verbose logging on; versionName gets a `-debug` suffix |

`PRODUCTION` gates the published feature set, protocol exposure, and verbose logging. Notably the
persistent **crash recorder** (`util/CrashLog` → `WearvianApp`) is **debug-only** (`if
(BuildConfig.PRODUCTION) return`): it writes stack traces to `filesDir/crashes/`. Pull them with
`adb root && adb shell cat /data/data/org.fivesevenfive.wearvian/files/crashes/*`. Production builds
do NOT record crashes — use `debugRelease` on the test track if you need crash capture.

## Signing

`keystore.properties` at the repo root (gitignored) supplies the upload-key credentials for the
`release` signing config. **The watch and companion apps MUST share the same signing key AND the same
`applicationId` (`org.fivesevenfive.wearvian`)** — the Wear Data Layer only delivers messages between
apps that match on both, and Play requires one key per listing. Never commit the keystore or its
passwords.

## Versioning & Play releases

- **versionCode lanes:** `1xxx` = watch, `2xxx` = phone. Unique across BOTH apps, only ever increase.
- Overridable per-build without editing the file: `./wear/gradlew … -PvCode=1099` (used for throwaway
  log-capture builds).
- **⚠ Query the LIVE Play track before choosing a versionCode.** The committed default routinely
  trails the real track because `-PvCode=` throwaway builds advance Play without editing this file.
  Picking a stale/lower code causes Play to reject the rollout ("existing users cannot upgrade").
  List uploaded bundles + the target track's current release via the Play Developer API, then pick a
  code strictly above every uploaded bundle AND the live release.
- **Upload (internal tracks only):** `python3 ../tools/play_upload.py <aab> <track> <notes.txt>`.
  Watch → **`wear:internal`**, phone → **`internal`**. The uploader is idempotent + retrying. The
  service-account key and `PKG` are configured at the top of that script.
- **Promote** internal → production with `../tools/play_promote.py`.
- Don't build a Play binary unless asked; commit + compile-check is the default.

## Git conventions

- Remotes use **HTTPS** (the `gh` CLI holds credentials); default SSH remotes fail host-key
  verification on this host. Watch = `pgenera/wearvian`, companion = `pgenera/wearvian-companion`.
- **Self-review the diff before committing**, not after.
- **Write/update JVM unit tests** for testable logic you touch (e.g. `VehicleStatusTest`).
- Build experimental features on a **feature branch**, not behind a `BuildConfig.PRODUCTION` guard on
  main.
- Commit/push only when asked. Branch first if on `main` for non-trivial work.

## Safety

- **Never drive/lock a real vehicle from the owner's production Rivian account.** Use the dedicated
  wearvian test Rivian account. Enrolling a watch requires that account to have **accepted an R1S
  driver invite in the official Rivian app first** — otherwise it sees 0 vehicles.
- **Never log secrets or long opaque strings** (keys, tokens, session material). Log state *names*,
  not values.

## Protocol / architecture pointers

Deep detail lives in `../rivian-re` and `docs/`; the essentials:

- **BLE vehicle status:** plaintext 16-byte frames on characteristic `0x1c`, decoded in
  `service/VehicleStatus.kt` using the app's own `SCHEMA_VERSION_1` layout (recovered in
  `rivian-re/oracle`). Subscribe PRIMARY post-auth. SoC is 7-bit; charge/preconditioning are enums.
- **Active commands (lock/unlock/drive):** AES-128-GCM frames on characteristic `0x20`. The signing +
  codes table are implemented and confirmed working on-vehicle. Gen-1 sensors are in-the-clear.
- **Presence state machine** (`service/PresenceService.kt`): `passive` + `seenDeparture` sub-state.
  "Parked nearby" = `passive && !seenDeparture` (car in range); "departed" = `passive &&
  seenDeparture`. Long-press the key → passive; an explicit **unlock** (only) from parked-nearby
  reactivates the live key.
- **BLE connection interval:** the official app runs ~45 ms baseline, relaxing to 90/135 ms — it
  relaxes intervals rather than tearing down. Match that; don't thrash HIGH/teardown.
- **Passive entry is a continuous authenticated session** (heartbeat `0x1b` + control `0x20` +
  sensors), not just bond+proximity.

## Where the old knowledge went

The full point-in-time memory store from Claude Code is archived verbatim in `docs/agent-memory/`
(one markdown file per note, with an index in `MEMORY.md`). Some entries are dated/ephemeral (battery
baselines, queued task lists); treat them as historical observations and verify against current code
before relying on them.
