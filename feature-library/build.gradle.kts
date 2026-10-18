// Library UI consumes domain, input and shared UI contracts; it has no feature dependencies.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gruning.mimir.rewrite.feature.library"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Motion audit batch C (2026-09-28): the Library's page host is tested on a paused clock under Robolectric,
    // the same set-up core-ui uses (fonts inflate from res/font).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Lifecycle-aware collection of the repository Flows in Compose + a ViewModel scope.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // THE data contract + the ONE focus authority.
    implementation(project(":core-model"))
    implementation(project(":core-input"))
    // The launch pipeline (Launch Engineer): the LaunchController fired on A/CONFIRM over a game.
    implementation(project(":core-launch"))
    // The shared design system directly — the one MediaArtFrame box-art component + theme palette.
    implementation(project(":core-ui"))
    // AU02: the Bifröst backdrop's MEDIA BED (the preview clip's own audio) is governed by the audio
    // module's pure policy + preference/focus flows — one governance source for cues AND bed.
    implementation(project(":core-audio"))
    // Coil renders the OFFLINE local box art into the frame's art slot. LOCAL-ONLY at runtime: the app
    // installs a network-free ImageLoader (LocalOnlyInterceptor) so a stray remote URL can never fetch.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    // The paused-clock page-host tests: core-ui's JVM Compose-test block, copied exactly (Robolectric 4.14.1 and
    // the BOM-pinned ui-test-junit4 are already in the offline cache).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
