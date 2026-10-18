# Mimir

Mimir is a game launcher for Android handhelds. Choose your game folders, browse your library with
a controller or touch, and launch games in the emulators you have installed. It can also be your
Home app. Mimir includes no games, BIOS files or firmware.

## Install

Download **Mimir** from [Releases](https://github.com/Mimir-apk/mimir-alpha/releases/tag/v0.1.0-alpha.1-demo).
The **Mimir Tweaker** APK is optional; install it first if you want its hardware controls.
You need Android 11 or newer.

- [Download and install](docs/install.md#get-the-apks) · [Set up your first game](docs/install.md#your-first-game)
- [Updates, permissions and troubleshooting](docs/install.md#updating)
- [Known issues](docs/known-issues.md) · [FAQ](docs/faq.md)
- Español: [Instalación](docs/es/instalar.md) · [Preguntas frecuentes](docs/es/preguntas-frecuentes.md)

This is **Alpha Release 1 - Demo** (`0.1.0-alpha.1-demo`). Expect bugs. Mimir has been used on the AYN Thor, AYN Odin 3 and Retroid
Pocket Nova; that is not a compatibility guarantee for every feature or firmware. Other devices and firmware may behave differently. To return from a game, exit the emulator, use Home if Mimir is your Home app,
or use the Mimir menu where available.

Updates from the same signer keep your library and settings. A self-built APK uses your own key
and cannot replace the official APK. Do not uninstall your existing copy just to bypass that check:
uninstalling deletes its private data.

## What is available

- Local library scanning, shelves, Favorites and Tomes (collections).
- Game launching through your chosen emulator, plus a file manager and music player.
- Controller navigation and touch. On the Thor, a lower-screen shelf, activity page, music player
  and trackpad.
- Optional SteamGridDB artwork using your own key, and RetroAchievements account features.
- Optional Tweaker profiles for Snapdragon 865, 8 Gen 2 and 8 Elite families on compatible firmware
  with the required built-in vendor access. Fan controls depend on the device's available controls.

**ScreenScraper downloads are unavailable in the official Alpha.** Its build has no
ScreenScraper developer registration; signing into a personal account cannot enable those downloads.
This also affects downloading game music from ScreenScraper. Local artwork and your own music still
work. IGDB is unconfigured in this release too. See the [release notes](docs/release-notes/0.1.0-alpha.1-demo.md).

## Build your own copy

[BUILDING.md](BUILDING.md) walks through getting the source, installing the tools, creating a shared
signing key, building both apps and installing them. It includes Windows PowerShell and macOS/Linux
commands. No service account or developer API key is needed to build.

GitHub Actions is disabled for this release repository. The checked-in workflow and commands in
[BUILDING.md](BUILDING.md) remain available for reproducible personal builds and tests. Official APKs
are attached to Releases; personal builds use a different signing key and cannot update them.

## Your data

Library scanning and launch dispatch work offline. Online artwork and account features are optional;
they contact their providers when enabled. Permissions, stored data, provider requests and backups
are explained in [Privacy](PRIVACY.md) and the [installation guide](docs/install.md).

## License and names

Mimir's and Mimir Tweaker's original code is **GPL-3.0-or-later**. You may use, modify and redistribute
it under that license, including commercially. Keep the required notices and provide corresponding
source when distributing binaries, as the license requires. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
Third-party code, data and artwork keep their own terms. [Licensing](docs/licensing.md) explains
redistribution and asset-specific permissions.

The software license does not grant trademark rights in the Mimir name or branding. When publishing
a fork, avoid presenting it as an official Mimir release; a distinct name and icon help prevent that
confusion. This branding policy does not restrict private modifications, truthful attribution, or
rights granted by the GPL.
Other product names and marks belong to their respective owners; naming them does not imply endorsement.
Android is a trademark of Google LLC.

## Help and development

Report bugs through [Issues](https://github.com/Mimir-apk/mimir-alpha/issues). Report security problems
privately using [SECURITY.md](SECURITY.md). Contributions are welcome under the
[Mimir CLA](CLA.md). See [CONTRIBUTING.md](CONTRIBUTING.md) for signing and contribution steps.

Developer instructions are in [AGENTS.md](AGENTS.md). Maintainers: [release procedure](docs/RELEASING.md)
and [release files](docs/release-notes/0.1.0-alpha.1-demo-files.md).
