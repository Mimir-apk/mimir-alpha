// MIMIR-REWRITE :core-audio — UI sound engine.
//
// Android library (needs Context/AudioManager/SoundPool — not pure-JVM like core-model). Phase 1 is
// a leaf module: zero dependency on core-model/core-ui/etc., so it builds and unit-tests in complete
// isolation. Nothing in the app graph depends on this module yet.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.audio"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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
    implementation(libs.kotlinx.coroutines.core)
    // Your music's player and its media service (see MimirMusicService). The theme beds stay on the platform MediaPlayer.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    testImplementation(libs.junit)
}
