import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val productQualificationTrustAnchors = providers
    .gradleProperty("mimirQualificationTrustAnchorsBase64")
    .orElse(providers.environmentVariable("MIMIR_QUALIFICATION_TRUST_ANCHORS_BASE64"))
    .orElse("")
val productQualificationManifests = providers
    .gradleProperty("mimirQualificationManifestsBase64")
    .orElse(providers.environmentVariable("MIMIR_QUALIFICATION_MANIFESTS_BASE64"))
    .orElse("")
val qualificationGeneratorSourceDirectory = file("../tools/qualification").canonicalFile

// The live family catalog (assets/tweaker-families.json, refreshed over HTTPS). One host, from this file only:
// the debug build type reads the `staging` branch of the public catalog repo, every other build type (beta,
// release, whatever flavour) reads `main`. The `.sig` URL is this URL + ".sig" (derived in FamilyCatalogFetcher).
val familyCatalogRawBaseUrl = "https://raw.githubusercontent.com/Mimir-apk/tweaker-families"
fun familyCatalogUrl(branch: String): String = "$familyCatalogRawBaseUrl/$branch/tweaker-families.json"
// Base64 of the raw 32-byte Ed25519 public key that signs the catalog. A public key is not a secret. EMPTY until
// Empty means the live fetch is disabled and only the bundled catalog is used.
val familyCatalogPublicKeyBase64 = ""

android {
    namespace = "com.gruning.mimir.tweaker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gruning.mimir.tweaker"
        minSdk = 30
        targetSdk = 35
        versionCode = 13
        versionName = "0.4.5-tw13"
        // 11: the game audio-focus hold; 10: the discovered-CEILING family,
        // written by the daemon. Kept in
        // step with MimirTweakerService.API_VERSION -- the manifest publishes it as meta-data, so the
        // two must never drift or the app's cheap pre-bind version check reads a number the binder
        // disagrees with. ManifestControlPermissionContractTest asserts exactly that equality, so
        // this line is not optional bookkeeping: leaving it at 9 reddens the build.
        manifestPlaceholders["mimirTweakerApiVersion"] = "11"
        buildConfigField("String", "FAMILY_CATALOG_URL", familyCatalogUrl("main").asBuildConfigString())
        buildConfigField("String", "FAMILY_CATALOG_PUBLIC_KEY_BASE64", familyCatalogPublicKeyBase64.asBuildConfigString())
    }

    // Same shared debug key as Mimir Alpha app. CONTROL_TWEAKER is protectionLevel "signature" and
    // DirectBootKeyAuthority verifies the caller's certificate, so the two APKs must never drift
    // onto different keys. This is a separate Gradle build, so the path is relative to mimir-tweaker/.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("../debug/mimir-shared-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Debug reads the `staging` catalog branch to test drafted family entries.
        // without touching what beta testers receive. Every other build type keeps the defaultConfig `main` URL
        // (declared again on beta below so it never depends on what initWith(release) copies).
        getByName("debug") {
            buildConfigField("String", "FAMILY_CATALOG_URL", familyCatalogUrl("staging").asBuildConfigString())
        }
        // beta: release code that is not debuggable and
        // not profileable, signed with the same shared certificate as the debug build so Mimir Alpha app's
        // CONTROL_TWEAKER signature permission still pairs. `release` itself stays unsigned. Same application
        // id and version as every other build. Only the production flavour is built (see androidComponents
        // below). Nothing here touches admission, signature gating or capability checks.
        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = false
            matchingFallbacks += listOf("release")
            buildConfigField("String", "FAMILY_CATALOG_URL", familyCatalogUrl("main").asBuildConfigString())
        }
    }

    flavorDimensions += "posture"
    productFlavors {
        create("safe") {
            dimension = "posture"
            val posture = "SAFE"
            val hardwareWritesCompiled = false
            val signedManifestRequired = false
            manifestPlaceholders["mimirTweakerPosture"] = posture
            manifestPlaceholders["mimirTweakerHardwareWritesCompiled"] = hardwareWritesCompiled.toString()
            manifestPlaceholders["mimirTweakerSignedManifestRequired"] = signedManifestRequired.toString()
            buildConfigField("String", "POSTURE", "\"$posture\"")
            buildConfigField("boolean", "HARDWARE_WRITES_COMPILED", hardwareWritesCompiled.toString())
            // Lab mutation controls are absent; read-only probe/observation remains available.
            buildConfigField("boolean", "DIAGNOSTICS_COMPILED", "false")
            buildConfigField("boolean", "SIGNED_MANIFEST_REQUIRED", signedManifestRequired.toString())
            buildConfigField("String", "QUALIFICATION_TRUST_ANCHORS_BASE64", "\"\"")
            buildConfigField("String", "QUALIFICATION_MANIFESTS_BASE64", "\"\"")
        }
        create("lab") {
            dimension = "posture"
            val posture = "LAB"
            val hardwareWritesCompiled = true
            val signedManifestRequired = false
            manifestPlaceholders["mimirTweakerPosture"] = posture
            manifestPlaceholders["mimirTweakerHardwareWritesCompiled"] = hardwareWritesCompiled.toString()
            manifestPlaceholders["mimirTweakerSignedManifestRequired"] = signedManifestRequired.toString()
            buildConfigField("String", "POSTURE", "\"$posture\"")
            buildConfigField("boolean", "HARDWARE_WRITES_COMPILED", hardwareWritesCompiled.toString())
            buildConfigField("boolean", "DIAGNOSTICS_COMPILED", "true")
            buildConfigField("boolean", "SIGNED_MANIFEST_REQUIRED", signedManifestRequired.toString())
            buildConfigField("String", "QUALIFICATION_TRUST_ANCHORS_BASE64", "\"\"")
            buildConfigField("String", "QUALIFICATION_MANIFESTS_BASE64", "\"\"")
        }
        create("production") {
            dimension = "posture"
            val posture = "PRODUCTION"
            val hardwareWritesCompiled = true
            val signedManifestRequired = true
            manifestPlaceholders["mimirTweakerPosture"] = posture
            manifestPlaceholders["mimirTweakerHardwareWritesCompiled"] = hardwareWritesCompiled.toString()
            manifestPlaceholders["mimirTweakerSignedManifestRequired"] = signedManifestRequired.toString()
            buildConfigField("String", "POSTURE", "\"$posture\"")
            buildConfigField("boolean", "HARDWARE_WRITES_COMPILED", hardwareWritesCompiled.toString())
            buildConfigField("boolean", "DIAGNOSTICS_COMPILED", "false")
            buildConfigField("boolean", "SIGNED_MANIFEST_REQUIRED", signedManifestRequired.toString())
            buildConfigField(
                "String",
                "QUALIFICATION_TRUST_ANCHORS_BASE64",
                productQualificationTrustAnchors.get().asBuildConfigString(),
            )
            buildConfigField(
                "String",
                "QUALIFICATION_MANIFESTS_BASE64",
                productQualificationManifests.get().asBuildConfigString(),
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Host-only signer: compile it with JVM tests so runtime byte compatibility is enforced,
    // but never expose it to main, androidTest, a flavor, or an APK.
    sourceSets.getByName("test").java.srcDir(qualificationGeneratorSourceDirectory)
}

val verifyQualificationGeneratorSourceIsolation = tasks.register("verifyQualificationGeneratorSourceIsolation") {
    group = "verification"
    description = "Fails if the host qualification signer enters a shipping Android source set."
    doLast {
        val testSources = android.sourceSets.getByName("test").java.srcDirs.map(File::getCanonicalFile)
        check(qualificationGeneratorSourceDirectory in testSources) {
            "Host qualification generator is missing from the unit-test source set."
        }
        android.sourceSets
            .filterNot { it.name == "test" }
            .forEach { sourceSet ->
                val leaked = sourceSet.java.srcDirs
                    .map(File::getCanonicalFile)
                    .any { sourceDirectory ->
                        sourceDirectory == qualificationGeneratorSourceDirectory ||
                            qualificationGeneratorSourceDirectory.toPath().startsWith(sourceDirectory.toPath())
                    }
                check(!leaked) {
                    "Host qualification generator leaked into Android source set ${sourceSet.name}."
                }
            }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyQualificationGeneratorSourceIsolation)
}

// beta exists for the production flavour only, and gets no test variants, so the Tweaker's unit-test
// variant count and its baselines do not change.
androidComponents {
    beforeVariants(selector().withBuildType("beta")) { variantBuilder ->
        variantBuilder.enable = variantBuilder.productFlavors.contains("posture" to "production")
        variantBuilder.enableUnitTest = false
        variantBuilder.enableAndroidTest = false
    }
}

val verifyQualificationGeneratorNotPackaged = tasks.register("verifyQualificationGeneratorNotPackaged") {
    group = "verification"
    description = "Scans Production debug, beta and release APKs and rejects any packaged host qualification signer."
    dependsOn("assembleProductionDebug", "assembleProductionBeta", "assembleProductionRelease")
    doLast {
        val marker = "QualificationManifestGenerator"
        listOf(
            "outputs/apk/production/debug/app-production-debug.apk",
            // The beta build gets the same isolation proof.
            "outputs/apk/production/beta/app-production-beta.apk",
            "outputs/apk/production/release/app-production-release-unsigned.apk",
        ).forEach { relativePath ->
            val apk = layout.buildDirectory.file(relativePath).get().asFile
            check(apk.isFile) {
                "Production APK is missing for host-tool isolation verification: $relativePath"
            }
            val leaked = ZipFile(apk).use { archive ->
                archive.entries().asSequence()
                    .filter { entry -> entry.name.endsWith(".dex") }
                    .any { entry ->
                        archive.getInputStream(entry).use { input ->
                            String(input.readBytes(), Charsets.ISO_8859_1).contains(marker)
                        }
                    }
            }
            check(!leaked) { "Host qualification generator was packaged in $relativePath." }
        }
    }
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyQualificationGeneratorNotPackaged)
}

dependencies {
    implementation(project(":contract"))
    implementation("com.google.crypto.tink:tink:1.7.0") {
        isTransitive = false
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
