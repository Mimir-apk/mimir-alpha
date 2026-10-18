// Mimir Alpha :core-input — the controller input + focus AUTHORITY.
//
// A single focus authority reconciles the handheld input paths:
// on a handheld, THREE input worlds fight over one gamepad — (1) the platform's own directional
// focus engine (the WebView's native DOM-focus nav in the old product), (2) dual-source
// KEY|KEYBOARD button events that duplicate/corrupt every press, and (3) the app's own logical
// focus model. The old product spent weeks reconciling these across native/JS boundaries. Here we
// make the reconciliation a first-class, testable module with ONE focus authority.
//
// android-library (not kotlin-jvm): it references Compose Modifier/FocusRequester + android
// KeyEvent/MotionEvent constants so the authority can be wired straight into the Compose tree.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.input"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
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

    // Compose-under-Robolectric (MimirActivatableInteractionTest) needs the merged Android resources on the
    // unit-test classpath, same as :core-ui.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Compose UI is needed for the Modifier.mimirFocusable authority hook + FocusRequester/FocusState.
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    // Coroutines/Flow: the FocusAuthority exposes focus as an observable single source of truth.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // JVM Compose harness for press-reporting behaviour — mirrors :core-ui exactly (same coordinates, same
    // explicit androidx.test.ext:junit pin so --offline resolves from the cache).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // ui-test-junit4 pulls activity-compose 1.7.0, which is not in the offline cache; pin the cataloged 1.9.3
    // (the version :app resolves) so conflict resolution picks a cached one.
    testImplementation(libs.androidx.activity.compose)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
