import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing credentials live in <repo-root>/keystore.properties (gitignored).
// Both the watch and companion apps MUST be signed with the SAME key — required by the
// Wear Data Layer (same applicationId + signature) and by Play (one listing, one key).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

// Name output artifacts wearvian-<variant>.apk (e.g. wearvian-release.apk) instead of the
// default app-<variant>.apk, so the sideload-test build is easy to pick out.
base { archivesName.set("wearvian") }

android {
    namespace = "org.fivesevenfive.wearvian"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.fivesevenfive.wearvian"
        minSdk = 33          // Wear OS 4+
        targetSdk = 35       // Play requires new apps to target API 35+ (FGS types since 34)
        // versionCode lanes under the shared package: 1xxx = Wear, 2xxx = phone.
        // Must stay unique across BOTH apps and only ever increase.
        // Overridable on the CLI so throwaway internal/log-capture builds each take a fresh, higher
        // code without editing this file:  ./gradlew bundleInternal -PvCode=1019
        versionCode = (project.findProperty("vCode") as? String)?.toIntOrNull() ?: 1050
        versionName = "0.9.5"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // PRODUCTION gates the published feature set + protocol exposure: the release build
        // (Play Store) is production, debug is the full development build. Read at runtime via
        // BuildConfig.PRODUCTION (e.g. ControlScreens hides unfinished pages on production).
        debug {
            buildConfigField("boolean", "PRODUCTION", "false")
        }
        release {
            buildConfigField("boolean", "PRODUCTION", "true")
            // R8 shrink + obfuscate: renames classes/methods so the Rivian protocol isn't
            // trivially readable from the shipped APK (the wire bytes are unchanged, so it
            // stays functional). Modest by design — a courtesy to Rivian, not hardened DRM.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing: use the real upload key when keystore.properties is present (for Play);
            // otherwise fall back to the DEBUG key so the release is installable for local UI
            // testing AND shares a signature with debug builds — `adb install -r` then swaps
            // debug<->release in place without uninstalling, so the enrolled Keystore key
            // survives (no re-enroll). A debug-key release must NEVER be uploaded to Play.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Play-uploadable build with debug features ON. Inherits release (upload-key signing + R8,
        // so Play accepts it and it installs over the enrolled app without re-enroll), but flips
        // PRODUCTION off — enabling the SETTINGS page and the 0x1c/0x20 vehicle-status logging used
        // to capture frames — and marks the version "-debug". Build with a fresh code each time:
        //     ./gradlew bundleDebugRelease -PvCode=1019
        // NEVER upload to the production track: it logs sensitive usage data and exposes dev UI.
        create("debugRelease") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            buildConfigField("boolean", "PRODUCTION", "false")
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Stub android.* calls (e.g. android.util.Log via util/Log.kt) return defaults
            // instead of throwing, so framework-free logic can be unit-tested on the JVM.
            isReturnDefaultValues = true
        }
    }

    lint {
        // We use ComponentActivity + activity-compose (no Fragments); this lintVital check flags
        // a transitive androidx.fragment version we never use. False positive — don't fail release.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

dependencies {
    // Shared, JVM-tested crypto (composite build).
    implementation("org.fivesevenfive.wearvian:core-crypto")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Compose for Wear OS
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation") // VerticalPager
    implementation("androidx.compose.material:material-icons-extended") // control glyphs
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // Wear OS Ongoing Activity: surface the presence FGS as a small key icon on the watch
    // face (instead of a persistent notification card). Decorates the FGS notification.
    implementation("androidx.wear:wear-ongoing:1.0.0")

    // Wear OS ambient (always-on) support: AmbientLifecycleObserver lets us render our own
    // low-fidelity idle screen instead of the system's blur-with-clock fallback.
    implementation("androidx.wear:wear:1.3.0")

    // Wear OS Tile: quick lock/unlock/key without opening the app.
    implementation("androidx.wear.tiles:tiles:1.4.0")
    // protolayout 1.4.0: 1.2.0 bundled a vulnerable protolayout-external-protobuf (pre-fix
    // protobuf-javalite, CVE-2024-7254 — recursive-message StackOverflow). 1.4.0 carries the patched one.
    implementation("androidx.wear.protolayout:protolayout:1.4.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    // Wear OS watch-face complications: publish battery SoC + estimated range as data sources
    // a watch face can show. The -ktx artifact gives the suspending data-source base class.
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Encrypted on-device storage for keys / enrollment data
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Wear OS Data Layer: receive enrollment from the companion phone app.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath (the android.jar stub would otherwise be
    // mocked away by returnDefaultValues), so EnrollmentContract JSON round-trips work.
    testImplementation("org.json:json:20240303")
}
