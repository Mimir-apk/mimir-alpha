// Mimir Alpha :core-files — vendored file-manager engine (SysAdminDoc/FileExplorer, MIT).
//
// Android library, not pure kotlin("jvm") like :core-model — forced by `android.net.Uri` in
// FileItem.kt, the one Android-SDK dependency in the vendored `core/model` slice. Uri comes from
// the Android SDK classpath AGP provides automatically; it is not a Maven artifact.
//
// No INTERNET permission, no network dependency: the vendored network/cloud call sites
// (RepositoryCapabilities.network()/cloud(), httpRepositoryException(), the NETWORK/CLOUD
// provider kinds, CLOUD_SIGN_IN, and the remote-only RepositoryOperation values) were stripped
// during vendoring, not merely disabled — see NOTICE and each source file's header comment.
// This module contains the vendored model, staging and extraction layers.
//
// The archive/extraction layer: adds zip4j + commons-compress, both
// Apache-2.0, verified from each artifact's own embedded/POM licence text (see NOTICE). junrar
// (NOASSERTION -- the UnRAR licence verbatim) and sevenzipjbinding (NOASSERTION/LGPL) are NOT
// dependencies here -- junrar because the supported extraction formats exclude RAR and ArchiveHelper.kt's RAR
// code path was deleted, not disabled (see ArchiveHelper.kt's header); sevenzipjbinding because,
// verified by grep across the whole upstream clone, it was never actually imported by
// ArchiveHelper.kt or wired to any dependency in the first place -- upstream's 7z support is
// commons-compress's own `sevenz` package. commons-compress pulls three of its own MANDATORY
// (non-optional) transitive dependencies -- commons-codec, commons-io, commons-lang3 -- all
// Apache Commons, all verified Apache-2.0 the same way.
//
// ADDITIONAL DEPENDENCY: `org.tukaani:xz` (0BSD, verified
// from its own POM) is also added. commons-compress declares it `optional=true`, but a real JUnit run proved
// 7z is non-functional without it: commons-compress's `sevenz.Coders` class touches
// `org.tukaani.xz.FilterOptions` in its own static initializer for ANY SevenZFile/SevenZOutputFile
// use, so every 7z read or write throws NoClassDefFoundError without xz on the classpath -- not
// merely an edge case for `.xz`/`.txz` TAR files, which is where this was first (wrongly) assumed
// to be scoped. Upstream's own `core/data/build.gradle.kts` already declares
// `implementation(libs.xz)` unconditionally, confirming this is a real requirement, not an
// upstream oversight. `com.github.luben:zstd-jni` (also optional, BSD-2-Clause) is deliberately
// NOT added: nothing in the kept surface exercises `.zst`, and ArchiveHelper.kt's refusal branch
// for it already fails honestly. See ArchiveHelper.kt's header for the full accounting.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gruning.mimir.rewrite.core.files"
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
    // The job model moved to :core-model so :core-resolver and the other
    // families can see it. `api`, not `implementation`, because :feature-files consumes FileQueueEntry
    // through this module and does not declare :core-model itself — same re-export pattern as
    // core-platform/build.gradle.kts:16.
    api(project(":core-model"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)

    testImplementation(libs.junit)
}
