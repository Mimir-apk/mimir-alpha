---
name: build-and-test
description: Use when you have changed code in Mimir or the Mimir Tweaker and need to build the affected app, run the unit tests for the changed modules and read the results.
---

# Build and test

Run from the repository root. Needs JDK 17, Android SDK 35, NDK 27.0.12077973, CMake 3.22.1 and
Node.js; see BUILDING.md. If one is missing, say so instead of reporting a result you did not get.

## 1. Build what you changed

```sh
./gradlew :app:assembleDebug                                   # Mimir, or any module under it
./gradlew -p mimir-tweaker :app:assembleProductionDebug        # only if mimir-tweaker/ changed
```

The Tweaker build needs `debug/mimir-shared-debug.keystore` (BUILDING.md, step 3).

## 2. Run the tests of the changed modules

```sh
./gradlew :<module>:testDebugUnitTest                # core-*/feature-* Android modules
./gradlew :core-model:test :core-platform:test       # the two plain JVM modules
./gradlew :app:testDebugUnitTest --tests '<fully.qualified.TestClass>'   # :app is large; name classes
./gradlew -p mimir-tweaker :app:testSafeDebugUnitTest :app:testLabDebugUnitTest :app:testProductionDebugUnitTest
```

A change in `core-*` can break `:app` tests that use it: also run the `:app` classes that name the
code you touched. Then run `python3 tools/mimir_ui_invariants_gate.py` and make sure your change adds
no finding (F1 must stay empty).

## 3. Read the results

Gradle writes one JUnit XML per test class to `<module>/build/test-results/<task>/TEST-*.xml`
(`mimir-tweaker/app/build/test-results/` for the Tweaker).

```sh
python3 tools/ci/junit_summary.py <module>/build/test-results
```

It prints totals per module and task, and every failing test as `Class.method` with the first line of
its message. In a raw file, `<testsuite tests= failures= errors= skipped=>` holds the counts and each
failing `<testcase>` has a `<failure>` or `<error>` child with the message and stack trace.

Report the command, the totals and the names of any failures. If a test was already failing before
your change, check against the unmodified tree before attributing it to your work.
