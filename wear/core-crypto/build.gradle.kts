import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
}

// Group/name let the Android app substitute this via composite build:
//   implementation("org.fivesevenfive.wearvian:core-crypto")
group = "org.fivesevenfive.wearvian"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // org.json is part of the Android platform at runtime; here it is compile-only
    // so it is never bundled into the app, and available to JVM unit tests.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

kotlin {
    // Java 17 bytecode keeps this consumable by the Android app module.
    // No jvmToolchain(...) so the build uses the running JDK (>= 17) without
    // needing toolchain auto-provisioning.
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}
