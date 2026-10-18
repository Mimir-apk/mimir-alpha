// MIMIR-REWRITE — authoritative root project registry.
// Project registrations below are the module inventory.

pluginManagement {
    repositories {
        // Order matters: google() first for AGP/AndroidX, then Maven Central for Kotlin/Coil.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS keeps every dependency source declared here, centrally.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // The single version catalog lives at gradle/libs.versions.toml and is auto-loaded as `libs`.
}

rootProject.name = "Mimir-rewrite"

include(":app")

// ── App, UI, input, companion, and audio modules ──────────────────────────────
include(":core-input")
include(":feature-settings")
include(":feature-library")
include(":feature-companion") // dual-screen companion
include(":core-ui")           // shared design system
include(":feature-browse")     // Browse (M6) — the hidden-games RECOVER surface
include(":feature-files")      // file-manager UI for :core-files
include(":core-companion-native") // dual-display overlay/task native seams
include(":core-audio")         // UI sound engine, Phase 1 self-contained
include(":core-tweaker-client") // Binder client for the isolated Mimir Tweaker APK
include(":mimir-tweaker-contract")

// The AIDL is shared from ONE source (the isolated Tweaker tree) instead of copied into the rewrite.
project(":mimir-tweaker-contract").projectDir = file("mimir-tweaker/contract")

// ── Domain, storage, resolver, launch, metadata, and platform modules ─────────
include(":core-model")
include(":core-storage")
include(":core-files") // vendored file-manager engine (SysAdminDoc/FileExplorer, MIT) — see NOTICE
include(":core-resolver")
include(":core-launch")
include(":core-metadata") // metadata subsystem: identity + art/description sources (offline seed + opt-in online)
include(":core-platform") // generated, pure-JVM platform identity/capability kernel

// Registry closure is part of every application verification/packaging path even while PK-00 remains
// behavior-dark and is not yet a runtime dependency.
gradle.rootProject {
    project(":app").tasks.matching { task ->
        task.name.startsWith("assemble") ||
            task.name.startsWith("bundle") ||
            task.name.startsWith("check")
    }.configureEach {
        dependsOn(":core-platform:platformSupportGate")
    }
}
