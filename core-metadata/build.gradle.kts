// Mimir Alpha :core-metadata — the metadata subsystem.
//
// Identity + presentation metadata (canonical title, box art, description) from layered sources:
//  - OFFLINE (this slice): the bundled TitleCatalog (via core-resolver's CanonicalTitleSource) and,
//    later, local No-Intro/Redump/MAME DAT hash-identity. NO network.
//  - ONLINE (opt-in, later slice, isolated here so the network + INTERNET permission live in ONE module):
//    ScreenScraper.fr (hash-first) + TheGamesDB fallback. Metadata ONLY — never content.
//
// This module holds NO android.permission.INTERNET in the foundation. The library resolves + dedups
// fully offline; the online enricher is added behind an opt-in gate.
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ScreenScraper SOFTWARE registration (opt-in cover fetch): devid/devpassword/softname are the
// per-app credential ScreenScraper requires on EVERY call — NOT a quota account, NOT a secret in the
// legal sense, but still kept out of the repo. Read from the untracked local.properties (already
// git-ignored) and baked into BuildConfig with EMPTY defaults, so a checkout with no creds simply has
// the fetch feature unconfigured (isConfigured=false) and the app stays fully offline. NEVER hardcode
// these; NEVER log them.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ssProp(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"")

val onlineRegistrationBuildConfigFields = listOf(
    "SCREENSCRAPER_DEV_ID",
    "SCREENSCRAPER_DEV_PASSWORD",
    "SCREENSCRAPER_SOFTNAME",
    "IGDB_TWITCH_CLIENT_ID",
    "IGDB_TWITCH_CLIENT_SECRET",
)

android {
    namespace = "com.gruning.mimir.rewrite.core.metadata"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }

        buildConfigField("String", "SCREENSCRAPER_DEV_ID", "\"${ssProp("screenscraper.devid")}\"")
        buildConfigField("String", "SCREENSCRAPER_DEV_PASSWORD", "\"${ssProp("screenscraper.devpassword")}\"")
        buildConfigField("String", "SCREENSCRAPER_SOFTNAME", "\"${ssProp("screenscraper.softname", "MimirApk")}\"")
        // IGDB/Twitch app registration uses empty defaults like the ScreenScraper fields above.
        // Until both values are present, IgdbRegistration.isConfigured is false and IgdbMetadataSource returns GateClosed
        // before any request is built.
        buildConfigField("String", "IGDB_TWITCH_CLIENT_ID", "\"${ssProp("igdb.twitch.clientid")}\"")
        buildConfigField("String", "IGDB_TWITCH_CLIENT_SECRET", "\"${ssProp("igdb.twitch.clientsecret")}\"")
    }

    buildTypes {
        getByName("release") {
            onlineRegistrationBuildConfigFields.forEach { field ->
                buildConfigField("String", field, "\"\"")
            }
        }
        create("lab") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            onlineRegistrationBuildConfigFields.forEach { field ->
                buildConfigField("String", field, "\"\"")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        // The synthetic matrix is one test-only source compiled by both host JUnit
        // and Android instrumentation. Keeping one source prevents the emulator pass from silently
        // exercising a smaller or different denominator than the host scorecard.
        getByName("test").java.srcDir("src/testShared/kotlin")
        getByName("androidTest").java.srcDir("src/testShared/kotlin")
    }
}

val retroAchievementsRegistryInput =
    rootProject.layout.projectDirectory.file("platforms/mimir-platform-registry.v2.json")

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Bind host RA reconciliation to the generated registry without copying it into production resources.
    inputs.file(retroAchievementsRegistryInput)
    systemProperty("mimir.retroAchievements.registry", retroAchievementsRegistryInput.asFile.absolutePath)
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-storage"))
    // core-resolver for the CanonicalTitleSource seam + the bundled TitleCatalog (offline identity).
    implementation(project(":core-resolver"))

    implementation(libs.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // Opt-in online enricher: parse the ScreenScraper JSON response (pure-JVM testable). HTTP itself
    // uses java.net.HttpURLConnection (no client dependency) behind an injectable seam.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // R6-P2-03 lever B: ScreenScraperTransientRetryTest uses runTest's virtual clock to skip the retry
    // backoff delay. Same version app/build.gradle.kts already resolves offline.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
