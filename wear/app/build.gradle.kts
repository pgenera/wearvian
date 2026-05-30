plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.fivesevenfive.wearvian"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.fivesevenfive.wearvian"
        minSdk = 33          // Wear OS 4+
        targetSdk = 34       // foreground-service types are required at 34+
        versionCode = 1
        versionName = "0.1.0"

        // Base URL of your auth-server (auth-server/). Set this to your Tailscale
        // hostname (e.g. "http://wearvian.your-tailnet.ts.net:8080") or App Engine
        // URL. Used only during one-time enrollment.
        buildConfigField("String", "BROKER_URL", "\"http://wearvian.local:8080\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    // Encrypted on-device storage for keys / enrollment data
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR code rendering for the auth handoff
    implementation("com.google.zxing:core:3.5.3")

    // Cloud calls during one-time enrollment only
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
