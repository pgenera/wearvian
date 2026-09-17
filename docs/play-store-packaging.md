# Play Store packaging (F3)

Goal: one Play listing that puts the **watch app** on watches and the **companion app**
on phones, installing each automatically on the right device.

## How the "single listing" actually works
- A Play listing == one **package name**. Both apps already share
  `org.fivesevenfive.wearvian` (required for the Wear Data Layer), so they belong under
  **one** Play app.
- You upload **two Android App Bundles (AAB)** to that one app: the **phone** bundle and
  the **Wear** bundle. Play tells them apart by the watch bundle's
  `<uses-feature android:name="android.hardware.type.watch" android:required="true">`
  and delivers each to the right form factor. (The old "embed the watch APK inside the
  phone APK" model is removed — separate bundles is the only current way.)
- The watch app is **standalone** (`com.google.android.wearable.standalone=true`, no
  INTERNET) so it installs and runs on the watch on its own; the phone app is its
  companion. Installing the phone app surfaces/installs the watch app on the paired watch.
- Constraints: **same signing key** for both, and **different `versionCode`s** (we lane
  them: `1xxx` = Wear, `2xxx` = phone — see each `build.gradle.kts`).

## Signing, explained
Every Android app is cryptographically signed; updates must be signed with the same key,
which is how the system trusts an update came from you.

With **Play App Signing** (recommended, default) there are *two* keys:
- **App signing key** — the key Google uses to sign the APKs delivered to users. Google
  generates and stores this for you; you never handle it. If you ever lose your upload
  key, this one is safe, so you can recover.
- **Upload key** — a key *you* hold, used to sign the AAB you upload. Google verifies it,
  strips it, and re-signs with the app signing key. Losing it is recoverable via support.

Because both apps live under one Play app, **both AABs are signed with the same upload
key**, and Google signs both with the same app signing key — so on-device they share a
signature and the Data Layer works.

### One-time: create the upload keystore
A *keystore* is a file holding your key(s), protected by passwords. Create one (used for
BOTH apps), then **back it up and keep the passwords safe**:

```sh
keytool -genkeypair -v \
  -keystore wearvian-upload.jks \
  -alias wearvian \
  -keyalg RSA -keysize 2048 -validity 10000
# prompts: a keystore password, a key password (can match), and a name/org (any values)
```

Then create `keystore.properties` (gitignored) in **each repo root** — `wear/` and the
companion repo root — from `keystore.properties.example`, pointing at the same `.jks`:

```
storeFile=/absolute/path/to/wearvian-upload.jks
storePassword=...
keyAlias=wearvian
keyPassword=...
```

Without `keystore.properties` the release build still compiles, just unsigned (fine for
CI; you can't upload an unsigned bundle).

## Debug vs production builds

The **release** build type is "production" (what goes to Play); **debug** is the full
development build. `BuildConfig.PRODUCTION` (`build.gradle.kts` build types) carries this at
runtime:

- **Production hides debug-only surfaces.** `ui/ControlScreens.kt` shows the working pager pages —
  **Key, Closures, Charge, Alarm** — on production. The **Settings** page (now just the debug
  "Force R1T" test switch + manual passive) is **debug-only**. The swipe-left BLE debug console
  stays in both for now. Also: the plaintext `0x1c`/`0x20` vehicle-status frames are logged only in
  debug (`VehicleSession` gates them on `!BuildConfig.PRODUCTION`).
- To gate more later: read `BuildConfig.PRODUCTION` (false in debug, true in release).

## Local sideload testing of the release build

Until `keystore.properties` exists, the **release build signs with the debug key** (see the release
`signingConfig` in `build.gradle.kts`). That makes it installable AND gives it the **same signature
as your debug builds**, so:

- `adb install -r app/build/outputs/apk/release/wearvian-release.apk` swaps a debug build for the
  obfuscated release **in place** — no uninstall, so the watch's enrolled Keystore key survives and
  you don't re-enroll. (Output is named `wearvian-release.apk` via `archivesName`.)
- Switching back to a debug build the same way is also seamless.
- **Caveat:** a build installed from the Play Store is re-signed by Google's app-signing key, so
  switching between a Play build and any local build *will* require uninstall + re-enroll.
- Once you create `keystore.properties`, the release auto-switches to the real upload key — a
  debug-key release must **never** be uploaded to Play.

## Obfuscation (R8) — a courtesy to Rivian

The release build runs **R8 minify + obfuscation** (`isMinifyEnabled = true`). This renames
classes/methods so the reverse-engineered Rivian protocol isn't trivially readable from the shipped
APK. It's deliberately modest — the wire bytes are unchanged (the car still works), and a determined
reader can still recover logic; the intent is to **not hand-publish Rivian's protocol** before
talking to them, not hardened DRM. The rename map is written to
`app/build/outputs/mapping/release/mapping.txt` (keep it for deobfuscating any crash reports).

Notes:
- **Heap:** R8 OOMs the Gradle daemon at 2 GiB, so `gradle.properties` sets `-Xmx4096m`. On a
  memory-pressured host the release build can be slow (swapping).
- **Lint:** `lintVitalRelease` runs on release. We disable the `InvalidFragmentVersionForActivityResult`
  false positive (we use `ComponentActivity`/`activity-compose`, no Fragments).
- The crypto is pure JCA (string algorithm ids), so obfuscation doesn't affect it; the only keep
  rule is for `BluetoothGattCallback` subclasses (`proguard-rules.pro`).
- **Runtime-test the release build before uploading** — R8 can break Compose/reflection paths that
  only surface at runtime.

## Build the signed bundles
```sh
# watch  -> wear/app/build/outputs/bundle/release/app-release.aab
cd wear && ./gradlew :app:bundleRelease

# phone  -> app/build/outputs/bundle/release/app-release.aab
cd ../../wearvian-companion && ./gradlew :app:bundleRelease
```
(Both with `-Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64` in this environment.)

## Play Console steps (you, in the browser)
1. **Create app** → package `org.fivesevenfive.wearvian`. Accept **Play App Signing**
   (let Google generate the app signing key; you provide the upload key by signing the
   first bundle with it).
2. Create a release (start with **Internal testing** — fastest, no review wait).
3. Upload the **phone** AAB, then the **Wear** AAB to the same release. Play recognizes
   the watch bundle by its `uses-feature watch` and lists it as the Wear OS form factor.
4. Fill the store listing once (icon, screenshots — phone + Wear screenshots, short/full
   description, privacy policy). For Wear, add at least one watch screenshot.
5. Roll out to internal testing → install on your phone from the test link → the watch app
   offers to install on the paired watch.

## Gotchas / notes
- **versionCode** must increase every upload and never collide between the two bundles
  (the `1xxx`/`2xxx` lanes handle this).
- Permissions review: the watch declares BLE + foreground-service + wake-lock and **no
  INTERNET** (privacy story is strong — the EC key never leaves the watch; no cloud to
  operate). Be ready to justify foreground-service-connected-device and BLE in the form.
- No third-party code ships in either app — only our own clean-room implementation. Keep it
  that way.
- Debug builds are signed with the shared debug key, so the Data Layer also works in
  development; just don't mix a debug build of one app with a release build of the other.
