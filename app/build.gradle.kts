// Mimir application variants, signing, native assets and module composition.
// Explicit import: inside a Gradle Kotlin DSL script `java` resolves to the Java plugin extension,
// not the java package, so the fully-qualified java.util.Properties does not compile here.
import java.io.File
import java.util.Properties
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Developer convenience only. A personal SteamGridDB API key must never be compiled into a release APK;
// release users provide their own key through SteamGridDbKeyStore's in-app connection flow.
val localSteamGridDbKey = project.rootProject.file("local.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("STEAMGRIDDB_API_KEY=") }
    ?.substringAfter("=")?.trim().orEmpty()

// ── Release signing ───────────────────────────────────────────────────────────────────────────────
// The signing key NEVER enters the repository. Each machine supplies its own, from the environment
// first (CI and one-off shells) and then from the untracked local.properties. Both sources are
// already gitignored, and *.keystore / *.jks / *.p12 are too.
//
// Absent or incomplete credentials are NOT an error: the release build stays unsigned and emits
// app-release-unsigned.apk exactly as it always has. That fallback is load-bearing --
// tools/mimir-quality-gate.sh:312 assembles release specifically as an "unsigned artifact", so the
// host gate has to keep passing on a machine that holds no key at all.
val mimirLocalProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

// Both sources are Java platform types, so the nullability is spelled out rather than inferred --
// otherwise Kotlin treats the elvis result as non-null and `.trim()` would throw when neither is set.
fun releaseSigningInput(environmentVariable: String, localProperty: String): String? {
    val fromEnvironment: String? = System.getenv(environmentVariable)
    val fromLocalProperties: String? = mimirLocalProperties.getProperty(localProperty)
    return (fromEnvironment ?: fromLocalProperties)?.trim()?.takeIf { it.isNotEmpty() }
}

val releaseSigningInputs = mapOf(
    "keystore" to releaseSigningInput("MIMIR_RELEASE_KEYSTORE", "mimir.release.keystore"),
    "storePassword" to releaseSigningInput("MIMIR_RELEASE_STORE_PASSWORD", "mimir.release.storePassword"),
    "keyAlias" to releaseSigningInput("MIMIR_RELEASE_KEY_ALIAS", "mimir.release.keyAlias"),
    "keyPassword" to releaseSigningInput("MIMIR_RELEASE_KEY_PASSWORD", "mimir.release.keyPassword"),
)

// Resolved against the repo root so a relative path in local.properties works from any subproject.
val releaseKeystoreFile = releaseSigningInputs["keystore"]
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

val releaseSigningReady = releaseKeystoreFile != null &&
    releaseSigningInputs.filterKeys { it != "keystore" }.values.all { it != null }

// A half-configured key is a mistake worth surfacing. Silently emitting an unsigned APK when someone
// clearly intended to sign one is how an unsigned build reaches a device. Names only, never values.
if (!releaseSigningReady && releaseSigningInputs.values.any { it != null }) {
    val missing = releaseSigningInputs.filterValues { it == null }.keys
    val keystoreUnreadable = releaseSigningInputs["keystore"] != null && releaseKeystoreFile == null
    logger.warn(
        buildString {
            append("MIMIR: release signing is only partially configured, so :app:assembleRelease will ")
            append("produce an UNSIGNED artifact. ")
            if (missing.isNotEmpty()) append("Missing: ${missing.joinToString(", ")}. ")
            if (keystoreUnreadable) append("Keystore path is set but no readable file exists there. ")
            append("See BUILDING.md > Distributing a build for the four required inputs.")
        },
    )
}

android {
    namespace = "com.gruning.mimir.rewrite"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.gruning.mimir.rewrite"
        minSdk = 30            // Android 11+ per the plan
        targetSdk = 35
        // Keep version codes increasing so signed updates replace older installs without a wipe.
        versionCode = 7
        versionName = "0.1.0-alpha.1-demo"
        manifestPlaceholders["mimirTweakerRequiredApiVersion"] = "4"
        testInstrumentationRunner = "com.gruning.mimir.rewrite.MimirLabTestRunner"
        buildConfigField("boolean", "FINAL_SHELF_ENABLED", "true")
        buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"\"")
    }

    // Shared across development machines so a tester's device accepts debug builds made on any Mac.
    // The rewrite app and the Tweaker MUST stay on one key: CONTROL_TWEAKER is protectionLevel
    // "signature", and DirectBootKeyAuthority checks the caller's certificate at runtime. Not a
    // release key — passwords are documented in debug/README.md by design.
    // CI runners and fresh worktrees may not have the shared file; the fallback preserves AGP's
    // machine-local default when the shared key is unavailable.
    val sharedDebugKeystore = rootProject.file("debug/mimir-shared-debug.keystore")
    val defaultDebugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
    signingConfigs {
        getByName("debug") {
            if (sharedDebugKeystore.isFile) {
                storeFile = sharedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                logger.warn("MIMIR: shared debug keystore is absent; APK will carry this machine's default debug signer at ${defaultDebugKeystore.absolutePath} and it will NOT install over existing builds signed with the shared key (never uninstall a device build to force it).")
            }
        }
        // Created only when a complete key is present, so a keyless machine has no half-built config
        // to trip over and `assembleRelease` behaves exactly as it did before this existed.
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseSigningInputs.getValue("storePassword")
                keyAlias = releaseSigningInputs.getValue("keyAlias")
                keyPassword = releaseSigningInputs.getValue("keyPassword")
                // README records the shipped posture as "APK Signature Scheme v3, one signer", and a
                // signed artifact was verified against that: apksigner reports v3 true, everything
                // else false. Declared explicitly so the config states the posture rather than
                // inheriting it. v1 is dead weight at minSdk 30, and v2 is redundant once v3 covers
                // every API level this app runs on.
                enableV1Signing = false
                enableV2Signing = false
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "FINAL_SHELF_ENABLED", "true")
            // FUX-04 SGDB tile test: the key lives only in untracked local.properties and debug APKs.
            buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"$localSteamGridDbKey\"")
        }
        release {
            isMinifyEnabled = false
            // Signed only when this machine holds a complete key. Otherwise release remains unsigned,
            // which is what the host gate assembles and asserts.
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Home Shelf is enabled in production; debug/release must not ship different
            // navigation products under one version.
            buildConfigField("boolean", "FINAL_SHELF_ENABLED", "true")
            buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"\"")
        }
        // Profileable release code for performance measurements, signed with the shared debug key.
        // The package ID is unchanged, so use a separate test device when its signer differs.
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
        // Non-debuggable distribution build paired with Tweaker through the shared signer.
        // The developer artwork key stays empty; AlphaVariantCredentialBoundaryTest and the APK gate
        // verify that boundary. This variant does not require separately configured release signing.
        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"\"")
        }
        create("lab") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".lab"
            versionNameSuffix = "-lab"
            matchingFallbacks += listOf("debug")
            // The isolated instrumentation target must never inherit a developer credential.
            buildConfigField("String", "STEAMGRIDDB_API_KEY", "\"\"")
        }
        // Runnable test app with a separate package ID, preserving any existing install and data.
        // It inherits debug credentials to match debug behavior. Unlike the engine-only lab target,
        // it has activities; its manifest removes HOME/LAUNCHER filters to prevent taking over Home.
        // Start explicitly: adb shell am start -n com.gruning.mimir.rewrite.devlab/com.gruning.mimir.rewrite.MainActivity
        create("devlab") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devlab"
            versionNameSuffix = "-devlab"
            matchingFallbacks += listOf("debug")
        }
    }

    // App instrumentation is intentionally built only against the isolated, separate-UID lab target.
    // Instrumentation must not use the primary debug/release packages.
    testBuildType = "lab"

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    // Packed DAT indexes are memory-mapped through AssetManager.openFd(). APK compression would make
    // openFd unavailable, so keep this format stored verbatim. ID catalogues remain streamed `.idcatgz`.
    androidResources {
        noCompress += "midx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Compose under Robolectric composes against real resources; without this it gets stubs
            // and every setContent fails at inflation.
            isIncludeAndroidResources = true

            // `android.util.Log` throws "not mocked" in a plain JVM test. Process-scoped state objects
            // that trace their own transitions are unit-tested directly, so
            // without this a DEBUG log line inside one turns every test of it red — which is a bad
            // reason to leave a surface un-instrumented. Defaults, not stubs that pretend to work.
            isReturnDefaultValues = true


        }
    }
}

// The renderer probe is compiled from checked-in C++ for every build; no opaque device-specific
// binary is accepted as an asset. The helper has no public command/PID/path surface: the certified
// vendor route stages it beside its fixed request/record files and invokes it without arguments.
//
// The asset root has no location of its own: AGP assigns it when the variant registration below adds
// this task's output as a generated assets directory. Registering the root with outputs.dir is what makes
// this task its producer, and every consumer of a variant's assets inherits that producer as a dependency.
// That also makes the root task-produced: Gradle rejects Provider.map reads of it until this task has
// completed, from inside this task too, so the helper's path uses the location-only DirectoryProperty.file.
val renderFpsAssetRoot = objects.directoryProperty()
val buildRenderFpsHelper by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the bounded PID-scoped renderer FPS helper from source."
    val requiredNdkRevision = "27.0.12077973"
    val localSdkDir = mimirLocalProperties.getProperty("sdk.dir")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { rootProject.file(it).absolutePath }
    val sdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
        .orElse(providers.environmentVariable("ANDROID_HOME"))
        .orElse(localSdkDir ?: "")
    val ndkRoot = providers.environmentVariable("ANDROID_NDK_HOME")
        .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
        .orElse(sdkRoot.map { if (it.isBlank()) "" else "$it/ndk/$requiredNdkRevision" })
    val hostOs = System.getProperty("os.name").lowercase()
    val hostTag = when {
        hostOs.contains("mac") -> "darwin-x86_64"
        hostOs.contains("win") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    // The Windows NDK ships the compiler twice: an extensionless wrapper, which is a shell script
    // CreateProcess cannot run, and a .cmd wrapper it can. Exec needs the .cmd one.
    val clangName = "aarch64-linux-android26-clang++" + if (hostOs.contains("win")) ".cmd" else ""
    val ndkSourceProperties = ndkRoot.map { file("$it/source.properties") }
    val clang = ndkRoot.map { file("$it/toolchains/llvm/prebuilt/$hostTag/bin/$clangName") }
    val output = renderFpsAssetRoot.file("mimir-render-fps/render_fps_helper")
    // Fingerprint both the installed revision and the selected compiler path so a toolchain swap
    // cannot leave a previously generated helper apparently up to date.
    inputs.file(ndkSourceProperties)
    inputs.file(clang)
    inputs.files(
        file("src/main/cpp/render_fps_protocol.h"),
        file("src/main/cpp/render_fps_protocol.cpp"),
        file("src/main/cpp/render_fps_elf.h"),
        file("src/main/cpp/render_fps_elf.cpp"),
        file("src/main/cpp/render_fps_pairing.h"),
        file("src/main/cpp/render_fps_azahar.h"),
        file("src/main/cpp/render_fps_armsx2.h"),
        file("src/main/cpp/render_fps_cemu.h"),
        file("src/main/cpp/render_fps_duckstation.h"),
        file("src/main/cpp/render_fps_eden.h"),
        file("src/main/cpp/render_fps_flycast.h"),
        file("src/main/cpp/render_fps_melonds.h"),
        file("src/main/cpp/render_fps_mupen.h"),
        file("src/main/cpp/render_fps_x1box.h"),
        file("src/main/cpp/render_fps_packages.h"),
        file("src/main/cpp/render_fps_retroarch.h"),
        file("src/main/cpp/render_fps_helper.cpp"),
    )
    outputs.dir(renderFpsAssetRoot)
    outputs.file(output)
    doFirst {
        val sourceProperties = ndkSourceProperties.get()
        val installedRevision = if (sourceProperties.isFile) {
            Properties().apply { sourceProperties.inputStream().use { load(it) } }
                .getProperty("Pkg.Revision")
                ?.trim()
        } else {
            null
        }
        if (installedRevision != requiredNdkRevision) {
            throw GradleException(
                "render_fps_helper requires Android NDK $requiredNdkRevision; " +
                    "resolved ${sourceProperties.parentFile.absolutePath} with " +
                    "${installedRevision ?: "no readable source.properties"}. " +
                    "Set ANDROID_NDK_HOME to the required NDK or configure sdk.dir in local.properties.",
            )
        }
        if (!clang.get().isFile) {
            throw GradleException("Missing NDK compiler: ${clang.get().absolutePath}")
        }
        output.get().asFile.parentFile.mkdirs()
        commandLine(
            clang.get().absolutePath,
            "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
            "-fPIE", "-pie", "-static-libstdc++",
            file("src/main/cpp/render_fps_protocol.cpp").absolutePath,
            file("src/main/cpp/render_fps_elf.cpp").absolutePath,
            file("src/main/cpp/render_fps_helper.cpp").absolutePath,
            // liblog: the helper's launcher discards stdout/stderr, so the Eden admission line
            // (eden_target=pinned|shape) goes to logcat.
            "-llog",
            "-o", output.get().asFile.absolutePath,
        )
    }
}

// Register the root as a generated assets directory of every variant, so the helper lands at the fixed
// APK path assets/mimir-render-fps/render_fps_helper.
//
// Not android.sourceSets.main.assets.srcDir(...): AGP 8.7.3 resolves legacy srcDirs to plain Files when it
// builds each variant's sources, which drops any task dependency the argument carried, builtBy included.
// Every reader of a variant's assets therefore read a directory no task owned. A name-matched dependsOn
// covered merge*Assets only; the lint model writers and lint analysis tasks read it unguarded, and that is
// what failed :app:assembleRelease at generateReleaseLintVitalReportModel. addGeneratedSourceDirectory
// hands each consumer a provider produced by buildRenderFpsHelper instead, so no consumer is named here,
// and AGP leaves generated directories out of lint's inputs.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(buildRenderFpsHelper) { renderFpsAssetRoot }
    }
}

android.sourceSets.getByName("test").resources.srcDir(rootProject.layout.projectDirectory.dir("platforms"))

// The deterministic adoption proof's synthetic tuner axis (app/src/debug/kotlin/LabTunerAxis.kt) is
// referenced unconditionally from MainActivity, which lives in the shared main source set — so every
// variant needs the symbol. `release` gets the no-op stub in app/src/release/kotlin; `lab` and `devlab`
// get the real thing by sharing the debug directory.
//
// **Sharing rather than three stubs, because devlab's own charter above demands it**: it "inherits debug
// WHOLE... a variant that behaves differently from the thing being tested is a worse instrument". A
// devlab where the arming extra silently did nothing would diverge from debug in exactly the way that
// comment forbids, and `lab` is the isolated separate-UID engine target the instrumentation runs on
// (testBuildType = "lab"), which is the last variant that should be missing a diagnostic. Both are
// debuggable, both initWith(debug), and neither is a shipping artifact.
//
// Structurally the property that matters is unchanged: `release` does not contain the axis code at all.
listOf("lab", "devlab").forEach { buildType ->
    android.sourceSets.getByName(buildType).java.srcDir("src/debug/kotlin")
}
// perf is release with a profileable flag (see its build type), so it compiles release's stubs, not the debug
// axis: `initWith(release)` copies the build type's settings but not its source set.
android.sourceSets.getByName("perf").java.srcDir("src/release/kotlin")
// beta is release code too (see its build type), so it takes the same no-op stub: without this line MainActivity's
// reference to LabTunerAxis does not compile in assembleBeta. Like every build type, it still ships the Tweaker
// contract module through :core-tweaker-client; only the debug-side direct mount below is left out.
android.sourceSets.getByName("beta").java.srcDir("src/release/kotlin")

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // PERF-G: composition tracing — names every composable in a perfetto trace. Debug-only; inert
    // unless tracing is explicitly enabled via the androidx.tracing.perfetto broadcast.
    debugImplementation(libs.androidx.compose.runtime.tracing)
    debugImplementation(libs.androidx.tracing.perfetto)
    debugImplementation(libs.androidx.tracing.perfetto.binary)

    // The synthetic tuner axis and the daemon's admission union must agree about cpu ids, the published
    // frequency table and the stock row. `mimir-tweaker/contract` is the ONE module both Gradle builds
    // mount, so the constants live there and neither side can drift: a drift would surface as an
    // UNDISCOVERED_CPU/UNDISCOVERED_FREQUENCY refusal indistinguishable from the daemon correctly
    // refusing a genuinely bad record. Debuggable variants only — release carries the no-op stub and has
    // no use for the constants.
    debugImplementation(project(":mimir-tweaker-contract"))
    add("labImplementation", project(":mimir-tweaker-contract"))
    add("devlabImplementation", project(":mimir-tweaker-contract"))

    // Lifecycle-viewmodel-compose: viewModel() factory host for the Library screen's state holder.
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // R01 S2 (SPINE-4): first-party profile installer — activates the app-code baseline profile
    // (app/src/main/baseline-prof.txt) on release installs. Was only transitive before; the version matches
    // the transitively-cached 1.3.1 so the offline build resolves from the local gradle cache.
    implementation(libs.androidx.profileinstaller)

    // The input/focus authority + the real Compose surfaces.
    implementation(project(":core-input"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-library"))
    // File manager integration: the vendored
    // browser engine + its face, mounted behind the FOLDER_BROWSER overlay kind. :core-files is
    // declared directly (not just transitively through :feature-files) because FileBrowserStore.kt
    // talks to LocalFileRepository/ArchiveHelper itself -- `implementation` dependencies are not
    // re-exported to consumers, so :feature-files's own implementation(project(":core-files")) does
    // not put those types on :app's classpath by itself.
    implementation(project(":feature-files"))
    implementation(project(":core-files"))
    // Browse (M6) — the hidden-games recover surface.
    implementation(project(":feature-browse"))
    // The dual-screen companion surface — the app mounts it on the secondary display (Presentation).
    implementation(project(":feature-companion"))
    // Native dual-display plumbing: secondary overlay M-button, display-targeted app launch, session seams.
    implementation(project(":core-companion-native"))
    // The shared design system — the app root wraps content in core-ui MimirTheme(themeId) so every
    // surface picks up the active Norse theme; also the Appearance picker + theme-persistence source.
    implementation(project(":core-ui"))
    // THE data contract. The app owns the single LibraryRepository instance + provides it.
    implementation(project(":core-model"))
    // Data layer — LibraryStore (Room-backed scan→dedup); pulls core-storage transitively.
    implementation(project(":core-resolver"))
    // The generated platform-identity kernel, on the MAIN classpath (it was testImplementation-only
    // until 2026-08-14). FileBrowserPlatformClassifier.kt calls ExtensionPlatformClassifier, whose
    // constructor and platformHintFromFolder both carry a `PlatformRegistry` DEFAULT ARGUMENT — Kotlin
    // must resolve that type at the call site even when the argument is never passed, and :core-resolver
    // declares :core-platform as `implementation`, which is not re-exported. Same reason :core-files is
    // declared directly a few lines above; found by a compile failure, not assumed.
    implementation(project(":core-platform"))
    // The launch pipeline — LaunchStore.controller(context) provides the LaunchController.
    implementation(project(":core-launch"))
    implementation(project(":core-tweaker-client"))
    // The UI sound engine — MimirAudio/MimirAudioFocus/AudioPreferences, wired in Phase 1b.
    implementation(project(":core-audio"))
    // core-audio's Media3 brings guava, which replaces listenablefuture 1.0 with an empty stub; WorkManager's ListenableFuture then needs guava here (see libs.versions.toml).
    implementation(libs.guava)
    // The metadata subsystem — offline identity + (opt-in) online art/descriptions; the app supplies the
    // Android ContentOpener for on-demand fingerprinting/enrichment.
    implementation(project(":core-metadata"))
    // Direct DB access for the on-demand-hash dev proof (read a game's persisted SAF document uri).
    implementation(project(":core-storage"))
    // Room on the app compile classpath so it can reach MimirDatabase's DAO accessors (the dev hash hook).
    implementation(libs.room.runtime)
    // EP-04 write boundaries use Room's suspend transaction extension to resolve aliases and persist
    // metadata/member reads against one database snapshot.
    implementation(libs.room.ktx)
    // Durable background identity enrichment — survives a full app kill mid-hash (resumes on restart).
    implementation(libs.androidx.work.runtime.ktx)
    // Coil — the app installs a LOCAL-ONLY singleton ImageLoader (network-free, LocalOnlyInterceptor) so
    // the feature-library box-art renderer can never fetch a remote image (offline posture, legal line).
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Compose harness for JVM tests. Versions come from the BOM already used by `implementation`
    // (2024.09.03 -> 1.7.3), which is what the offline cache holds -- this adds no new version.
    // It exists for the render-vs-focus-position class: a row can be drawn but unreachable, or
    // reachable but never drawn, and neither the compiler nor any source assertion sees it.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-junit4 asks for androidx.test.ext:junit 1.1.5 transitively; the cache holds 1.2.1, the
    // version the catalog already pins for androidTest. Declaring it directly lets conflict
    // resolution pick the one we have, so the harness stays --offline.
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(project(":core-platform"))
    // Shared IPC tests import the contract directly, including in release unit tests.
    testImplementation(project(":mimir-tweaker-contract"))
    // Real org.json for JVM tests — the android.jar copy is a stub that throws (HomeCollections round-trip).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // The runtime and unit-test classpaths pin androidx.collection to 1.4.4 by consistent resolution; the
    // androidTest compile classpath does not, so it resolved the 1.4.0 that Compose 1.7.3 asks for, whose jar is
    // not in the offline cache, and compileLabAndroidTestKotlin failed with "No cached version available".
    // Declaring the cached version directly lets conflict resolution pick it, the same way the unit-test block
    // above does for androidx.test.ext:junit.
    androidTestImplementation("androidx.collection:collection:1.4.4")
}

// These policies use Gradle APIs only; signing, variants and generated assets stay app-owned.
apply(from = rootProject.file("gradle/app-test-policy.gradle.kts"))
apply(from = rootProject.file("gradle/app-manifest-checks.gradle.kts"))
