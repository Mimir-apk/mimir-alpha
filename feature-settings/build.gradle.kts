import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

// Mimir Alpha :feature-settings — settings screens.
// Uses the shared theme tokens and focus authority.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gruning.mimir.rewrite.feature.settings"
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

    // Compose-under-Robolectric needs real Android resources on the unit-test classpath (core-ui's own block, the same reason): the
    // Music pane's transitions are proved on a paused clock.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// CreditsTomeTest reads the root notice; edits must invalidate cached attribution results.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.layout.projectDirectory.file("NOTICE"))
        .withPropertyName("bundledDataNotice")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // BackHandler for the shared MimirSelectOverlay (an inline overlay, not a Dialog — it must intercept Back).
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // The Settings surface is the first consumer of the input/focus authority.
    implementation(project(":core-input"))
    // The shared design system — the theme façade (MimirColors/Type/Spacing) now reads core-ui's live
    // theme palette so every Settings surface re-skins with the active Norse theme.
    implementation(project(":core-ui"))
    // THE data contract: the Library panel binds the real summary + source rows. The app
    // collects the repository Flows and passes typed rows down; this module never touches core-storage.
    implementation(project(":core-model"))

    testImplementation(libs.junit)
    // The Compose test block, mirroring :core-ui's exactly (cached offline).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
