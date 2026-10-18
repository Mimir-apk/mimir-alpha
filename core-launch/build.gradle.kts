// MIMIR-REWRITE :core-launch — launch readiness + dispatch policy.
//
// Android library (it will hold the real launch-intent dispatch + package-installed checks). The
// FOUNDATION slice authored here is the readiness ENGINE: pure functions from (game, installed
// emulators, BIOS presence, Open-With override) → LaunchReadiness, plus the interfaces the app wires
// to native (override lookup, proof generation). No network; launch never sources ROMs/BIOS.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.launch"
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
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-storage"))
    // The scan's own M3U parser and limits, so the launch pre-check reads a playlist exactly as the scan did.
    implementation(project(":core-resolver"))

    // The UI sound engine — MimirAudioFocus, wired for the audio-focus handoff QA gate.
    implementation(project(":core-audio"))

    // Room runtime on the compile classpath so core-launch can reach the DAO accessors on
    // MimirDatabase (its RoomDatabase supertype). core-storage exposes Room only as `implementation`,
    // so it is not transitive — the real launch adapters (locator/override/bios/proof) need it here.
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // FileProvider — the file-path launch form exposes ROMs as content:// URIs (StrictMode forbids
    // handing a raw file:// URI to the emulator on targetSdk ≥ 24). See IntentLaunchDispatcher.
    implementation(libs.androidx.core.ktx)

    testImplementation(project(":core-platform"))
    testImplementation(project(":core-metadata"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

android.sourceSets.getByName("test").resources.srcDir(rootProject.layout.projectDirectory.dir("platforms"))
android.sourceSets.getByName("test").resources.srcDir(
    rootProject.layout.projectDirectory.dir("core-ui/src/main/assets"),
)

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("mimir.appManifest", rootProject.file("app/src/main/AndroidManifest.xml").absolutePath)
}
