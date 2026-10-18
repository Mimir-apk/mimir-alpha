// Mimir Alpha :core-storage — Room persistence.
//
// Android library. Owns the SQLite schema (entities/DAOs/@Database) + mappers to/from core-model.
// KSP runs the Room compiler. No INTERNET, no network — pure local persistence (posture manifest).
// Depends ONLY on core-model for the typed truth it maps to.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.storage"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Room schema export dir — deterministic migrations are auditable in VCS.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        // MigrationTestHelper reads the committed Room exports as assets so the test executes the actual
        // historical schema, not a hand-written approximation.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core-model"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.sqlite.jdbc)
    // Robolectric host test for GameIdentityDao.rekeyRoomState: the only way
    // to execute the REAL private rekeyRoomState body (via its public reconcileCurrentWinners/reconcileGroup
    // entry points) against a real Room-generated Dao_Impl without a device. Every other "SqlTest" in this
    // module reproduces SQL constants against a raw JDBC connection and never runs Room-generated code —
    // that gap is exactly what left rekeyRoomState with zero coverage. Same version already used elsewhere
    // in this repo (app, core-tweaker-client) and present in the local Gradle cache, so this resolves
    // --offline.
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.room.testing)
}
