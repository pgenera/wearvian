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
- Nothing reverse-engineered/decompiled ships in either app — keep it that way.
- Debug builds are signed with the shared debug key, so the Data Layer also works in
  development; just don't mix a debug build of one app with a release build of the other.
