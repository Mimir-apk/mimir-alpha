// Mimir Alpha :feature-files — the face of the vendored file-manager engine (slice 5).
//
// This is the Compose surface for :core-files :
// a controller-first directory browser for a handheld held at arm's length. The engine
// (LocalFileRepository, LocalListingPolicy, ArchiveHelper) is fully vendored and tested already;
// this module owns none of that -- it only lists rows, drives focus through the single
// FocusAuthority, and reports a chosen directory / an extract intent back to whoever mounts it.
// Mounting into the app shell / Quick Launch is a LATER slice; nothing here is wired up yet.
//
// Dependency posture, deliberately narrow:
//  - :core-files  — the engine. FileItem is used directly as this surface's row source; no parallel
//    "entry" type is invented (unlike the retired feature-library FolderBrowser*, which could not
//    depend on core-storage and so shuttled a hand-rolled FolderBrowserEntry across that boundary).
//  - :core-input  — the ONE FocusAuthority + Modifier.mimirFocusable. Compose's own focusable()/
//    focusTarget()/FocusRequester are never used -- see the repo-wide grep gate.
//  - :core-ui     — LocalMimirPalette + the shared spacing/type tokens + the focus-ring modifier. No
//    hardcoded hex anywhere in this module; tools/mimir_ui_invariants_gate.py enforces that.
// No :feature-library, no :core-storage, no :feature-settings: this module does not depend on any
// other feature module or on the ROM-aware storage layer. It is a standalone utility surface.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gruning.mimir.rewrite.feature.files"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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

    // Update's sweep (10-09): the module's first rendered test (a PNG of its buttons and its search row under Robolectric), the same set-up :feature-library and :core-ui use.
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

    implementation(libs.kotlinx.coroutines.core)

    // The engine (read-only listing + extractable classification) and the ONE focus authority.
    implementation(project(":core-files"))
    implementation(project(":core-input"))
    // Shared design system: LocalMimirPalette + spacing/type tokens + the focus-ring modifier.
    implementation(project(":core-ui"))

    testImplementation(libs.junit)
    // The rendered capture test: :feature-library's JVM Compose-test block, copied exactly (Robolectric 4.14.1 and the BOM-pinned ui-test-junit4 are in the offline cache).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
