// MIMIR-REWRITE :core-ui — the shared design system (Designer lane).
// The 9 Norse themes as Compose ColorSchemes, elevated tokens, media-art component, motion.
// Shared UI and picker input contracts; no app or feature dependencies.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Text-metrics harness:
    // Compose-under-Robolectric needs real Android resources on the unit-test classpath to inflate the
    // shipped IBM Plex Sans/Mono TTFs (`res/font`) — same requirement `app/build.gradle.kts` already
    // states for `OnboardingScreenFitTest`. Without this, setContent() fails at inflation rather than
    // measuring anything.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core-input"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // The ONE local-only cover painter (MimirBoxArt) lives beside MediaArtFrame so feature-library and
    // feature-companion share one loader path (companion review WP-1 option ii, B10).
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // RC-1 text-metrics harness — mirrors app/build.gradle.kts's own JVM Compose-test block exactly
    // (same BOM-pinned ui-test-junit4, same explicit androidx.test.ext:junit override) rather than
    // inventing a second shape; no version.ref exists in gradle/libs.versions.toml for Robolectric, so
    // this is a raw coordinate the same way app's is. 4.14.1 plus its NATIVE-graphics transitive deps
    // (nativeruntime, nativeruntime-dist-compat, shadows-framework, the sdk=34 android-all-instrumented
    // jar) are already resolved into ~/.gradle and ~/.m2 by app's own Robolectric use, so this adds no
    // new network fetch under --offline — verified before writing the test body itself.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // ui-test-junit4 asks for androidx.test.ext:junit 1.1.5 transitively; the cache holds 1.2.1 (the
    // version the catalog already pins for androidTest here and for app's own JVM Compose tests).
    // Declaring it directly lets conflict resolution pick the cached one, keeping this --offline.
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
