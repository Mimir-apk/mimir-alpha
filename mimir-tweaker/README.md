# Mimir Tweaker

Mimir Tweaker is the optional hardware-control app for Mimir. Install it before Mimir if you want
its controls. The production flavor has no launcher activity: open the Utility panel in Mimir.
The two apps must be signed with the same key.

Profiles support Snapdragon 865, 8 Gen 2 and 8 Elite families where firmware exposes the required
built-in vendor service and runtime capabilities. A supported chip alone does not guarantee access.
Fan controls depend on available fan controls; installing Tweaker cannot add missing firmware access.
Normal discovered profiles do not need a qualification manifest or certification.

[Installation](../docs/install.md) · [Build both apps](../BUILDING.md) · [Signing](SIGNING.md)

## Trying the official pair

Download both APKs from [the same release](https://github.com/Mimir-apk/mimir-alpha/releases/tag/v0.1.0-alpha.1-demo).
Mimir is version 0.1.0-alpha.1-demo and Tweaker is 0.4.4-tw12; their version numbers do not need to match.
Install Tweaker first, then Mimir, and use Settings › Controls › Utility › Open in Mimir. The absence of a Tweaker launcher icon
is expected. If the controls are unavailable, follow [installation troubleshooting](../docs/install.md#troubleshooting).
Do not mix an official app with a self-built or CI app: they may have different signing certificates.

You can scan, browse and launch games in Mimir without Tweaker. See [Your first game](../docs/install.md#your-first-game).

## Developer details

Package: `com.gruning.mimir.tweaker`. Current version: `0.4.4-tw12`, Binder API **11**.
The AIDL contract is in `contract/`; the service is `MimirTweakerService` and requires the
signature permission `com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER`.

This is a separate Gradle build. From the repository root, after creating the shared key as
described in BUILDING.md:

```sh
./gradlew -p mimir-tweaker :app:assembleProductionDebug
./gradlew -p mimir-tweaker :app:testSafeDebugUnitTest :app:testLabDebugUnitTest :app:testProductionDebugUnitTest
```

Use `.\gradlew.bat` in PowerShell. `safe` compiles hardware writes off; `lab` includes diagnostics;
`production` is the normal flavor. Legacy qualification code and historical TW/WAVE2 specifications
remain in source for compatibility and technical reference. They are not installation instructions
or proof that a device has been tested. APK signing and legacy profile-qualification keys serve
different purposes. Do not add private keys to source, Gradle properties, logs or APKs.

[LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) cover licensing and third-party credits.
