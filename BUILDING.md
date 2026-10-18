# Build your own Mimir

These steps build a personal debug copy of Mimir and its optional Tweaker. No online-service
credentials are required. The source for the official Alpha is tag `v0.1.0-alpha.1-demo`;
use `main` for the current documentation and any later changes.

Your builds cannot update official installs because the signing keys differ. Keep your existing
install and its data; do not uninstall it to get past a signature error. This recipe uses the same
package IDs as the official apps and therefore does not install alongside them. A separate device
or emulator is the simplest place to try a personal build.

## 1. Install the tools

Install [Git](https://git-scm.com/downloads), a JDK 17 distribution such as
[Temurin](https://adoptium.net/temurin/releases/?version=17), [Node.js](https://nodejs.org/en/download)
and [Android Studio](https://developer.android.com/studio). Put `git`, `java`, `keytool` and `node`
on your terminal's PATH. Set `JAVA_HOME` to your JDK 17 installation, then open a new terminal.

In Android Studio's SDK Manager, install:

| Component | Version |
|---|---|
| Android SDK Platform | Android 15 / API 35 |
| Android SDK Build-Tools | 35.0.0 |
| Android SDK Platform-Tools | Includes `adb` |
| Android SDK Command-line Tools | Includes `sdkmanager` |
| NDK (Side by side) | 27.0.12077973 |
| CMake | 3.22.1 |

Use **Show Package Details** to select the exact NDK and CMake versions. Accept the SDK licenses.
Note the SDK location shown in SDK Manager. Gradle 8.9, Android Gradle Plugin 8.7.3 and Kotlin 2.0.21
come from this checkout; do not install a separate Gradle. Node's version is not pinned.

If using the SDK command-line tools instead of SDK Manager, add their `bin` directory to PATH and run:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
sdkmanager --licenses
```

In PowerShell the executable is `sdkmanager.bat`. Review and accept the prompts yourself. Google's
[SDK tool instructions](https://developer.android.com/tools/sdkmanager) explain installation and SDK paths.

## 2. Get the source and select the SDK

In a terminal:

```sh
git clone https://github.com/Mimir-apk/mimir-alpha.git
cd mimir-alpha
```

This checks out current `main`. To build the source matching the official Alpha Release 1 - Demo instead,
run `git switch --detach v0.1.0-alpha.1-demo` before continuing. Keep both apps on the same checkout
and shared signing key. A personal build of that tag still cannot update an official install.

On Windows, use PowerShell. Before running Gradle, select a short, writable temporary directory.
The example below creates `C:/Temp/mimir-build`; use another short path if that location is not writable.
Some Windows/JDK 17 setups fail before Gradle tasks start with `Unable to establish loopback connection`
when using the default temporary directory. Keep both `TEMP` and `TMP` set in the terminal used for
both builds; these assignments affect this terminal and its child processes only.

Set the SDK path too (the example is Android Studio's usual location):

```powershell
$mimirBuildTemp = 'C:/Temp/mimir-build'
New-Item -ItemType Directory -Path $mimirBuildTemp -Force | Out-Null
$env:TEMP = $mimirBuildTemp
$env:TMP = $mimirBuildTemp
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME/ndk/27.0.12077973"
java -version
node --version
```

On macOS/Linux, replace the SDK path below with the location shown in your SDK Manager:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
java -version
node --version
```

Android Studio usually uses `$HOME/Library/Android/sdk` on macOS. An alternative to `ANDROID_HOME`
is a git-ignored `local.properties` containing `sdk.dir=/absolute/path/to/sdk` (use forward slashes
on Windows). If `ANDROID_NDK_ROOT` is already set, point it to the same pinned NDK too.

## 3. Create your shared signing key before building either app

Both apps must use the same certificate. Mimir falls back to the machine's Android debug key if
the file below is absent; Tweaker has no fallback. Creating the shared file first avoids mismatched
APKs. If you already have this file from your own previous builds, keep it and skip creation.

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Path debug -Force | Out-Null
keytool -genkeypair -keystore debug/mimir-shared-debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

macOS/Linux:

```sh
mkdir -p debug
keytool -genkeypair -keystore debug/mimir-shared-debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

The file is ignored by Git. Keep a private backup: changing or losing it prevents updates to your
existing personal builds. These fixed debug passwords are for local development, not a recommended
public-release signing setup. Never upload the keystore.

## 4. Build

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat -p mimir-tweaker :app:assembleProductionDebug
```

macOS/Linux:

```sh
./gradlew :app:assembleDebug
./gradlew -p mimir-tweaker :app:assembleProductionDebug
```

Run them one at a time. Omit the second command if you do not need Tweaker. The first build needs
network access for Gradle and dependencies; later builds may use `--offline` once everything is cached.
Only install an artifact from a successful build.

| App | APK path relative to this folder |
|---|---|
| Mimir | `app/build/outputs/apk/debug/app-debug.apk` |
| Tweaker | `mimir-tweaker/app/build/outputs/apk/production/debug/app-production-debug.apk` |

`production` is the normal Tweaker flavor. `safe` disables hardware writes and `lab` includes
diagnostics. Ordinary discovered profiles need compatible chipset/firmware capabilities, not a
qualification certificate. The APK signing certificate is a different requirement: both apps need it.

If you built Mimir before creating the shared key, rebuild Mimir now so both artifacts use that key.

## 5. Install on a test device

Enable USB debugging and approve the computer on the device. Add `$ANDROID_HOME/platform-tools`
to PATH (PowerShell: `$env:ANDROID_HOME/platform-tools`) or run `adb` by its full path. Confirm
`adb devices` shows the intended device; if several are connected, use `adb -s SERIAL` for each command.

```sh
adb install -r mimir-tweaker/app/build/outputs/apk/production/debug/app-production-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Skip the first command if not using Tweaker. If Android reports an incompatible signature, stop;
do not uninstall or clear data as a workaround. Open Mimir and choose your folders and emulators.
Follow [Your first game](docs/install.md#your-first-game) after installation. See
[installation](docs/install.md) for permissions and [Tweaker signing](mimir-tweaker/SIGNING.md)
for certificate checks. Debug builds are for your own testing, especially if you add service credentials.

## Optional online services

The official Alpha, `release` and `beta` builds contain no ScreenScraper or IGDB developer
registration. A personal account cannot enable an unconfigured provider. For your own **debug**
build, obtain developer credentials from the provider under its terms, then place them in the
git-ignored root `local.properties` before building:

```properties
screenscraper.devid=YOUR_DEVELOPER_ID
screenscraper.devpassword=YOUR_DEVELOPER_PASSWORD
screenscraper.softname=MimirApk
igdb.twitch.clientid=YOUR_CLIENT_ID
igdb.twitch.clientsecret=YOUR_CLIENT_SECRET
```

ScreenScraper needs both developer values plus the player's account connection in the app.
IGDB needs both Twitch values; its API has usage/attribution requirements described in its
[documentation](https://api-docs.igdb.com/). Neither provider's credentials grant redistribution
rights over every image, description or music clip.

SteamGridDB works with a player key entered in the app. An optional `STEAMGRIDDB_API_KEY` in
`local.properties` is baked into debug APKs only. Release/beta builds empty it. Do not distribute
APKs containing your developer credentials or upload `local.properties`.

## Memory for larger builds

Run release compilation, tests and lint as separate commands. Combining them can compile the app
and its large test suite at the same time. If Kotlin reports `Not enough memory` or `Java heap
space`, use a machine with enough free RAM and retry the failed command with fewer workers and
an explicit compiler heap, for example:

```sh
./gradlew :app:assembleRelease --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx6g
```

Use the same flags with the test or lint task when needed. This example reserves up to 6 GiB for
the Kotlin compiler, in addition to Gradle and test processes; it is not appropriate for a low-memory
machine. Do not commit your machine-specific settings or run `clean` to work around a heap error.

Lint runs inside the Gradle JVM, so increasing the Kotlin daemon heap does not give lint more memory.
If lint exhausts Gradle's heap or spends excessive time collecting garbage, a machine with enough
free RAM can use a separate override:

```sh
./gradlew :app:lintRelease --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx8g -Dfile.encoding=UTF-8 -XX:+UseParallelGC'
```

This reserves up to 8 GiB for Gradle, separately from any Kotlin compiler or test processes. Keep
these resource overrides local and run the larger commands one at a time.

## Command-line checks

For the shell gates, install Git Bash on Windows and Python 3.10 or newer. In that shell,
`git --version`, `node --version`, `python3 --version` and `unzip -v` must work. Packaging and
checksums use Python's standard library; separate `zip` and `shasum` commands are not needed.

If Python works as `python` in Git Bash but `python3` opens the Microsoft Store or is missing,
create a launcher in your own user directory:

```sh
mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/python3" <<'SH'
#!/usr/bin/env sh
exec python "$@"
SH
chmod +x "$HOME/.local/bin/python3"
export PATH="$HOME/.local/bin:$PATH"
python3 --version
```

Build once using step 4 to download/unpack the wrapper distribution. The shell gate finds that
Gradle 8.9 distribution automatically, or accepts `MIMIR_GRADLE` pointing to its `bin/gradle`.
It uses `--offline`; the test and lint dependencies must be cached as well as the build dependencies.
A missing dependency is a setup failure, not a passing check.

```sh
sh tools/platform-registry/check.sh
sh tools/platform-registry/check-source.sh
python3 tools/mimir_connected_posture_gate.py
python3 tools/mimir_ui_invariants_gate.py
python3 tools/mimir_hardcoded_text_gate.py
sh tools/mimir-quality-gate.sh
```

The last command runs tool self-tests, debug/release builds, JVM tests, Tweaker checks, lint and
source gates. This local ladder fails on any failing step; CI gates critical assertions and reports the rest.
CI does not run this shell ladder or `:app:lintRelease`. A green CI run therefore does not certify
lint or the local ladder; run those checks separately and read their findings.
The recovery writer uses POSIX directory synchronization. Some recovery/archive JVM tests therefore
fail on Windows with directory file-access errors. Use Linux (including CI, or WSL with its own Linux
SDK/NDK) for the complete test ladder. Windows APK builds and the other source/tool checks remain
usable. Read named failures; do not interpret a failed ladder as a successful gate.
A nonzero exit needs investigation; do not change a baseline just to obtain a pass. The text gate
and UI gates check specific source patterns, not complete localization or accessibility.

## Tests and CI

Use `./gradlew` below on macOS/Linux, or `.\gradlew.bat` in PowerShell:

```sh
./gradlew :core-input:testDebugUnitTest
./gradlew :core-model:test :core-platform:test
./gradlew :app:testDebugUnitTest --tests 'com.gruning.mimir.rewrite.SourceFileSizeRatchetTest'
./gradlew -p mimir-tweaker :app:testSafeDebugUnitTest :app:testLabDebugUnitTest :app:testProductionDebugUnitTest
```

JVM tests need no device. `:app` is large; run relevant classes with `--tests`. Results are JUnit XML
under `<module>/build/test-results/<task>/`. With Python 3 installed,
`python3 tools/ci/junit_summary.py <path>` prints totals and failures (`python` may be the Windows name).
The Bash quality-gate scripts require Bash, Python 3 and a warmed dependency cache; see
[CONTRIBUTING.md](CONTRIBUTING.md). A build alone does not warm every test or lint dependency.

GitHub Actions is disabled for this release repository. The preserved workflow, when enabled in
a personal fork, runs on eligible main/integration pushes, non-draft pull requests and manual dispatches.
The workflow splits app tests into four shards plus slow classes. Assertions in `core-model`,
`core-storage`, `core-launch`, `core-resolver` and Tweaker tests block CI. Other Mimir assertions
remain report-only. Setup, compilation, incomplete runs, missing results and entirely skipped
critical suites fail CI. The `CI verdict` check requires every job selected by the path filter
to succeed. Supplemental runs use a separate check name. Read XML and summaries for report-only
failures. Lint and instrumentation/device checks are separate.

Push builds upload a `mimir-debug-apk` artifact with a checksum, retained for seven days. Manual
dispatch uploads it only if **run_emulator** is selected; a normal dispatch and pull-request build
do not upload that APK. Download artifacts from the run page (GitHub sign-in may be required).
Forks may need Actions enabled first. CI uses a runner debug key; it neither signs official updates
nor publishes a release. Official Alpha APKs are attached to [Releases](https://github.com/Mimir-apk/mimir-alpha/releases).

## Distributing a build

For a personal non-debuggable build, after step 3 use `:app:assembleBeta` and
`-p mimir-tweaker :app:assembleProductionBeta`; they use your shared signing key. They are not official
Mimir builds. Output paths are `app/build/outputs/apk/beta/app-beta.apk` and
`mimir-tweaker/app/build/outputs/apk/production/beta/app-production-beta.apk`.

For separately managed release signing, `:app:assembleRelease` reads all four inputs below from
the environment or git-ignored `local.properties`. With none it produces `app-release-unsigned.apk`.
Partial configuration warns and leaves the artifact unsigned.

| Input | Environment variable | Local property |
|---|---|---|
| Keystore | `MIMIR_RELEASE_KEYSTORE` | `mimir.release.keystore` |
| Store password | `MIMIR_RELEASE_STORE_PASSWORD` | `mimir.release.storePassword` |
| Alias | `MIMIR_RELEASE_KEY_ALIAS` | `mimir.release.keyAlias` |
| Key password | `MIMIR_RELEASE_KEY_PASSWORD` | `mimir.release.keyPassword` |

The configured launcher release signer uses APK Signature Scheme v3. Tweaker's `release` variant
is unsigned; sign it with the matching key using `apksigner` before installation. This does not
describe the official Alpha's `beta` signing setup. Verify both actual certificates, package
IDs and versions. `tools/check-apk-key-free.sh <apk>` checks the launcher's developer artwork key,
debuggable flag and signature. Check provider configuration and third-party notices separately.

Before sharing binaries, review [licensing](docs/licensing.md), preserve third-party notices,
provide the matching source/build scripts under the applicable licenses, and distinguish your build
from official Mimir. Signing or passing tests does not resolve third-party asset permissions.

## Bundled identity data

The checkout includes checksum/title indexes; no regeneration is needed to build. `tools/fetch-dats.sh`
downloads upstream data and needs Bash, curl, unzip, gzip, Python 3 and network access. It may produce
different bytes as upstream changes. [NOTICE](NOTICE) records origins and unresolved terms; the
generator's license does not automatically license its input data.
