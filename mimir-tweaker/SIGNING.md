# Signing Mimir and Tweaker

Mimir requests `com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER`, a signature permission.
Mimir and Tweaker must have matching signing certificates to communicate. Both package IDs stay
the same across the debug/beta/release variants described in BUILDING.md.

## Personal builds

Follow [BUILDING.md](../BUILDING.md) in order. Create your private
`debug/mimir-shared-debug.keystore` **before building either APK**, and keep it for later updates.
Tweaker reads that exact file; it does not use `~/.android/debug.keystore` as a fallback. Mimir
uses the shared file if present and otherwise falls back to the machine's default debug key.
If you created the shared file after building Mimir, rebuild Mimir.

Check each APK with the SDK Build-Tools tool:

```sh
apksigner verify --print-certs path/to/app.apk
```

Compare the signer certificate SHA-256 digests. They must match. For an existing install, the
candidate must also match the installed app's signer and meet Android's version requirements.
Do not uninstall or clear data to bypass a mismatch.

For APK selection, installation order and missing controls, start with
[installation troubleshooting](../docs/install.md#troubleshooting). A matching signature allows the
apps to communicate; it does not establish device or firmware support.

## Official Alpha

The official launcher and Tweaker share this certificate SHA-256 fingerprint:

```text
964a64cf186537819583ce379dc425132e68812e0293108ffcb50dab15cf2df3
```

This public fingerprint is not the private key. Your own key cannot produce official updates.
Official release assets, hashes and matching source are linked from [Releases](https://github.com/Mimir-apk/mimir-alpha/releases).

For release-signing options see [BUILDING.md](../BUILDING.md#distributing-a-build). Install the paired
Tweaker first, then Mimir. No legacy qualification manifest, calibration report or certificate of
hardware testing is required for normal discovered profiles. Runtime capabilities and safety checks
still apply.
