// Mimir Alpha :feature-browse — Browse. The corrected product meaning: the recover surface for
// HIDDEN games (hidden games are recoverable without file deletion) — NOT a placement
// queue, duplicate shelf, or file organizer. Mimir Alpha indexes in place, so there is no "unplaced inbox".
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.gruning.mimir.rewrite.feature.browse"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(project(":core-model"))   // GameRow + ids
    implementation(project(":core-input"))    // the ONE focus authority
    implementation(project(":core-ui"))       // MediaArtFrame + palette
    implementation(project(":feature-settings")) // theme façade tokens
    testImplementation(libs.junit)
}
