# Maintenance and changes across modules

For installation and personal builds, start with [BUILDING.md](../BUILDING.md). This page is for contributors changing module boundaries, build policy or application lifecycle code.

## Current boundaries

The app owns production composition and repository injection. `LibraryInjection` binds the Library to the Room-backed `LibraryStore`; fake repositories are for tests and previews. Features consume domain and shared UI contracts rather than another feature's implementation.

`StorageGrantRecovery` owns one Activity's storage-grant audit job and pending picker request. Replacing or destroying its host cancels the work and releases its picker callback. Only the pending root URI is saved across recreation; live grants and roots are re-read. The process coordinator retains the prompt verdict and “Later” dismissal. Other app-shell responsibilities remain to be extracted.

Shared colors, type mappings and spacing live in `core-ui/theme`. The option picker, its artwork cache and controller dispatch live in `core-ui/select`. They use `core-input`'s existing `FocusAuthority`; callers still own their focus scopes. Settings and Library use the same implementation.

`app/build.gradle.kts` owns application IDs, signing, provider-credential boundaries, source sets and generated native assets. Gradle-only policies are separated into:

- `gradle/app-test-policy.gradle.kts`: test source inputs, sharding, exclusions and test-event logging.
- `gradle/app-manifest-checks.gradle.kts`: lab/devlab manifest checks and their connection to `check`.

These are applied scripts for the current app. They are not a repository-wide convention-plugin migration. The Tweaker remains a separate Gradle build.

## Work that remains

| Area | Remaining work | Evidence needed before closing |
|---|---|---|
| Application lifecycle | Reduce the responsibilities held by `MainActivity` and `MimirAppCoordinator`, beginning with one lifecycle owner at a time. | State-transition tests, cancellation/cleanup tests and a scoped integration check. Moving methods into another file is insufficient. |
| Build configuration | Assess reusable convention plugins and extend checks across variants without weakening signing or credential isolation. | Actual variant configuration and manifest/artifact checks, including keyless and partially configured signing. Never publish credential values. |
| Tests | Replace source inspection with behavioral tests where runtime behavior can be isolated. Keep source checks for structural rules. | A reproduced failure followed by an observable passing regression test. |
| CI | Extend blocking assertions beyond critical modules when their baselines are understood. | Explicit failing-test enforcement and complete dated XML; a green workflow alone is insufficient. |
| Portability | Complete the local test ladder on Windows, including recovery/archive directory synchronization. | Windows results for the affected archive tests; Linux passing is separate evidence. |

## Changes across repositories

Public changes need an issue or PR record that identifies responsibility, tested source, destination integration and whether results were freshly executed.

For work crossing module boundaries:

1. Open or claim an issue stating the problem, intended behavior, affected modules and acceptance checks. Link an existing issue rather than starting a duplicate effort.
2. Name one implementation owner and coordinate overlapping files before editing. Reviewers can work independently; competing writes to the same files need an explicit handoff.
3. Use a bounded PR. Record its base and tested head, explain responsibility changes, and link related PRs. Changes shared with another repository need a destination commit or a clear pending status; “synced” is not enough.
4. Report commands, environment, failures, errors and skips. Separate freshly executed tests from cached results, and builds from device observations. Keep known failures visible by method, not only by total.
5. Close the issue only when the agreed checks and destination integration are complete. Put unfinished work and its next check in the issue/PR. A successful merge is not device or release acceptance.

Public records must stand alone: include public source/run links and reproducible commands, not private checkout paths or unavailable screenshots. Do not copy private credentials, account URLs, device details, product plans or repository history into them.

## Checks for these boundaries

Use the tool and signing setup in BUILDING.md. Run Gradle commands sequentially. The first resolution needs network access; `--offline` is appropriate only with a populated dependency cache.

```sh
python3 tools/test_module_boundaries.py
./gradlew :feature-library:testDebugUnitTest :feature-settings:testDebugUnitTest
./gradlew :core-ui:testDebugUnitTest --tests '*MimirSelectOverlayOrnamentTest'
./gradlew :app:testDebugUnitTest --tests 'com.gruning.mimir.rewrite.AlphaVariantCredentialBoundaryTest' --tests 'com.gruning.mimir.rewrite.SourceFileSizeRatchetTest'
./gradlew :app:testDebugUnitTest --tests '*LibraryInjectionPersistenceTest' --tests '*StorageGrantRecoveryTest' --tests '*StorageGrantAuditTest' --tests '*StorageGrantAuditWiringTest'
./gradlew :app:verifyLabIsolationManifest :app:verifyDevlabManifest
./gradlew :app:assembleDebug
```

The Python guard runs in the required workflow's changed-paths job and checks dependency/import ownership; it is not a UI test. The production-binding test writes through Room and reopens the database through `LibraryInjection`. Storage recovery has behavioral ownership/cancellation tests and a Robolectric check using Room and Android's grant adapter; the shell wiring checks remain source checks. These do not establish device lifecycle reliability. The manifest tasks check merged lab/devlab manifests; they do not certify every variant. The source-size class checks both limits and stale entries. Inspect the actual JUnit XML under each module's `build/test-results` and report skips rather than treating them as executed passes.

CI blocks model, storage, launch, resolver and Tweaker assertions; other Mimir assertions remain report-only. Lint, the full local ladder and device checks are separate. See [BUILDING.md](../BUILDING.md#tests-and-ci) and [CONTRIBUTING.md](../CONTRIBUTING.md).
