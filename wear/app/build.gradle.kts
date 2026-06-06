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

android {
    namespace = "org.fivesevenfive.wearvian"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.fivesevenfive.wearvian"
        minSdk = 33          // Wear OS 4+
        targetSdk = 34       // foreground-service types are required at 34+
        // versionCode lanes under the shared package: 1xxx = Wear, 2xxx = phone.
        // Must stay unique across BOTH apps and only ever increase.
        versionCode = 1006
        versionName = "0.3.0"
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
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign release builds when the keystore is present; otherwise leave unsigned
            // (CI without secrets can still build, you just can't upload it).
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
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

    // Wear OS Tile: quick lock/unlock/key without opening the app.
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    // Encrypted on-device storage for keys / enrollment data
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Wear OS Data Layer: receive enrollment from the companion phone app.
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
