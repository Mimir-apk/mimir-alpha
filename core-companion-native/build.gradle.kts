plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.companionnative"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // The custom ᛗ mark's geometry (ink bounds, trim, the 44/56 box and face ratios) is shared with Settings' Mimir-button picker, so it
    // lives in the pure-JVM bottom module both can read. :core-ui stays out of this module by design (CompanionButtonPressSourcePinTest).
    implementation(project(":core-model"))

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
