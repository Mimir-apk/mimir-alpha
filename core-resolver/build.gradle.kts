// MIMIR-REWRITE :core-resolver — recognition + dedup pipeline.
//
// Android library (it will grow SAF/content-resolver reads for hashing/serial extraction, hence
// Android not pure-JVM). The FOUNDATION slice authored here is deliberately platform-independent:
// the extension→platform seed pass + the grouping logic are pure functions over core-model types,
// so they are unit-testable without a device. Depends on core-model (+ core-storage for the read
// models it will eventually persist into).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.resolver"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").resources.srcDir(rootProject.layout.projectDirectory.dir("platforms"))
        getByName("test").resources.srcDir(rootProject.layout.projectDirectory.dir("platforms"))
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-platform"))
    implementation(project(":core-storage"))

    // The library repository here wires the Room DAOs (declared in :core-storage) to the recognition
    // pipeline, so it needs Room's runtime types (RoomDatabase supertype) on THIS module's classpath.
    // :core-storage exposes Room as `implementation`, so it is not transitive — declared explicitly.
    // Room 2.6.1 is in this machine's offline cache (verified); no new network fetch.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
