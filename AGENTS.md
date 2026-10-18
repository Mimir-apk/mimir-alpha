# AGENTS.md

Notes for AI coding tools that contribute to Mimir. It is plain documentation and reads the same for
people. Read README.md, BUILDING.md and CONTRIBUTING.md first; this file only adds the map and the rules.

Mimir is a controller-first Android launcher for gaming handhelds (Kotlin, Jetpack Compose, Room). The
repository holds two apps with separate Gradle builds: Mimir (root build, package
`com.gruning.mimir.rewrite`) and the optional Mimir Tweaker (`mimir-tweaker/`, `com.gruning.mimir.tweaker`).
GPL-3.0-or-later.

## Modules

Root build, 19 projects (`settings.gradle.kts`):

- `app`: activities, services and the coordinators that wire the modules (`MainActivity`,
  `MimirAppCoordinator`, `HomeShelfCoordinator`, onboarding, settings and companion bridges)
- `core-model`: pure-JVM domain types and the network gates in `posture/`
- `core-storage`: Room database and DAOs; schemas in `core-storage/schemas`
- `core-input`: controller actions, keymap and `FocusAuthority`
- `core-ui`: theme tokens, shared components, platform art
- `core-resolver`: library scan (`scan/`), recognition pipeline, library store
- `core-launch`: emulator seeds and detection, launch preflight and dispatch, `remotedefs/`
- `core-metadata`: game identity (DAT indexes, hashing, native code in `src/main/cpp`), artwork and
  metadata sources (`online/`)
- `core-platform`: pure-JVM platform registry kernel, generated from `platforms/`
- `core-files`, `feature-files`: vendored file-manager engine and its screens (see NOTICE)
- `core-audio`: UI sounds and the music service
- `core-companion-native`: second-display selection and overlay/task seams
- `feature-library`: Home shelf, Tomes, game lists and detail
- `feature-settings`: Settings screens
- `feature-browse`: recovering hidden games
- `feature-companion`: the second-screen surface
- `core-tweaker-client`: Binder client for the Tweaker
- `:mimir-tweaker-contract` (`mimir-tweaker/contract`): the AIDL both builds compile

Tweaker build (`mimir-tweaker/settings.gradle.kts`): `:contract` and `:app`. Other directories:
`platforms/` (registry data), `tools/` (build, data and check scripts), `docs/` (user documentation).

## Build and test

```sh
./gradlew :app:assembleDebug
./gradlew -p mimir-tweaker :app:assembleProductionDebug
./gradlew :<module>:testDebugUnitTest           # Android modules; core-model and core-platform use :test
./gradlew :app:testDebugUnitTest --tests '<fully.qualified.Class>'
./gradlew -p mimir-tweaker :app:testSafeDebugUnitTest :app:testLabDebugUnitTest :app:testProductionDebugUnitTest
python3 tools/mimir_ui_invariants_gate.py       # focus, hex colours, regex brackets, must-consume
python3 tools/ci/junit_summary.py <module>/build/test-results   # totals and failing tests
```

Needs JDK 17, Android SDK 35, NDK 27.0.12077973, CMake 3.22.1 and Node.js (BUILDING.md). The `:app`
suite is large: run the classes you touched. Instrumentation tests under `*/src/androidTest` need a
device and are not run in CI.

## Where to start

- **Launch path:** `core-launch` (`LaunchController`, `LaunchPreflight`, `IntentLaunchDispatcher`,
  `EmulatorSeedRegistry`); the app side starts in `MainActivity` and `MimirAppCoordinator`.
- **Scanning and identity:** `core-resolver/.../scan/` (`SourceWalker`, `ScanRecognizer`),
  `RecognitionPipeline`; identity in `core-metadata` (`RomFingerprinter`, `DatIdentitySource`,
  `PackedDatIndex`); bundled indexes in `app/src/main/assets/dat`.
- **Shelves UI:** `feature-library` (`HomeShelfScreen`, `GameShelf`) and `app/.../HomeShelfCoordinator`.
- **Second-screen companion:** `feature-companion` (`CompanionSurface`), `app/.../Companion*.kt`
  (`CompanionDisplayController`), `core-companion-native` (`SecondaryDisplaySelector`).
- **Tweaker bridge:** `IMimirTweaker.aidl`; client `core-tweaker-client` (`TweakerV4Session`); service
  `mimir-tweaker/app/.../MimirTweakerService.kt`; vendor transport `PServerClient.kt`.
- **Network:** `app/.../MimirProviderEgress.kt` and `core-model/.../posture/`.
- **Platforms and emulators:** `platforms/`, `tools/platform-registry`, `tools/emulator-definitions`.

## Invariants

1. **Offline first.** Scanning and launching never touch the network. Online features are opt-in, start
   from a player action, and sit behind the gates in `core-model/.../posture/`.
2. **No game content.** Never add code, links or data that download or point to ROMs, BIOS or firmware.
   The bundled DAT indexes identify files the player already has; they carry checksums, not content.
3. **Focus goes through `FocusAuthority`** (`core-input`). No `focusable()` or `focusTarget()` from Compose;
   the UI gate fails on them.
4. **Text is in string resources**, with an es-419 entry in `values-b+es+419` beside the English. No
   hard-coded colours in `feature-*`; use `core-ui` tokens.
5. **No secrets in code.** Credentials come from `local.properties`, which stays untracked, as do keystores.
   Never log a ScreenScraper or RetroAchievements URL.
6. **The Tweaker accepts only fixed operations.** `IMimirTweaker` takes named operations and ids, never a
   command or path; callers need the signature permission; unconfirmed writes are read back. Do not add a method that
   passes a shell string or a setting key and value through. See SECURITY.md.
7. **Do not hand-edit generated files**, such as the platform registry under `platforms/` (checked by
   `core-platform:platformSupportGate`).
8. **File-size budget.** `SourceFileSizeRatchetTest` caps main Kotlin files at 2,000 lines unless listed in
   `app/src/test/resources/source-file-size-budget.txt`.

## Verifying a change

1. Run the unit tests of each module you touched (and the Tweaker's, if you touched it).
2. Run the UI gate and resolve findings; F1 (raw Compose focusable) must stay empty.
3. For network, permission or manifest changes, also run `python3 tools/mimir_connected_posture_gate.py`.
   Resolve findings and keep new transport boundaries covered by its tests.
4. `tools/mimir-quality-gate.sh` runs the full ladder with Gradle `--offline`. See BUILDING.md
   for prerequisites, memory guidance and the Windows directory-sync test limitation.
5. Documentation-only changes need no build.

Do not claim a test passed unless you ran it. If a tool is missing (SDK, NDK, device), say what you could
not run.
