// MIMIR-REWRITE :core-model — pure Kotlin/JVM domain types.
//
// This module is intentionally NOT an Android module. Per the architecture map it is a
// plain `kotlin("jvm")` library so the domain truth models are JVM-unit-testable with zero
// device / emulator / AndroidX dependency. Everything downstream (core-storage, core-resolver,
// core-launch, feature-*) depends on these types; they may add Android, this must not.
//
// Serialization provides typed cross-process payloads; persistence is Room's job in core-storage.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Cross-process payloads use local JSON across the primary↔companion boundary, never network.
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Flow for the LibraryRepository contract (pure-JVM coroutines; the data layer + UI both depend on this).
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.test {
    inputs.property(
        "mimirRecoveryCapacityProof",
        providers.environmentVariable("MIMIR_RECOVERY_CAPACITY").orElse("0"),
    )
}
