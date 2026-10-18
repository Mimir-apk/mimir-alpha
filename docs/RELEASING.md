# Releasing Mimir

This repository contains the source for the public Mimir launcher and optional Mimir Tweaker. See
[BUILDING.md](../BUILDING.md) for prerequisites and [known issues](known-issues.md) before testing.

## Prepare a release

1. Choose a public source commit. Update version names, and increase Android version codes for an
   update to an earlier release. Keep the launcher and Tweaker versions in the release notes.
2. Update the English/Spanish user documentation and known issues. Confirm each merged contribution has the
   signed CLA coverage described in [CONTRIBUTING.md](../CONTRIBUTING.md).
3. Review [licensing.md](licensing.md). Resolve redistribution/provenance and required notice
   gaps for included material; disclosure alone does not grant permission. Preserve corresponding
   source/build scripts and license texts beside the APKs. Do not distribute assets without the applicable rights.
   Run the applicable unit tests and source-size checks. Record failures and existing baselines by
   test name; a successful CI verdict alone does not prove that every report-only suite passed.
4. Inspect the exact tracked tree for credentials, signing files, local settings, personal data and
   build/test captures. Run a secret scan over both the tree and the history being published.
5. Build from this source. Official alpha builds use `:app:assembleBeta`: this build type contains
   release runtime code, is not debuggable/profileable, and leaves the developer artwork key empty.
   Its internal build-type name does not change the public version or package name. Sign with the
   maintained official key; never commit or upload signing inputs. Self-build instructions and the
   unsigned `release` alternative are in BUILDING.md.
6. Build the optional Tweaker from its own Gradle build, using the `production` flavor and `beta`
   build type. Verify both APK certificates match. An unrelated signing key cannot update existing
   installs or satisfy the signature permission between the apps.
7. Run `tools/check-apk-key-free.sh` against the launcher. Verify actual package IDs, versions,
   non-debuggable posture and signatures, and generate SHA-256 checksums for the APKs.
8. Test a data-preserving upgrade on the declared device: compare its installed certificate first,
   install with replacement, check the installed hash and cold start, and inspect crash logs. Do not
   uninstall to bypass a signing mismatch. State which interaction/visual checks remain open.
9. Confirm README/FAQ/release feature claims match the actual variant, including disabled
   ScreenScraper/IGDB registration, signing and firmware limits. Verify provider/backup descriptions
   against [Privacy](../PRIVACY.md).
10. Tag the release source commit. Create a prerelease with the APKs, `SHA256SUMS.txt`, release notes
   and the APK/source information. Link the exact source tag and source archives in the release body;
   a manifest alone is not corresponding source. Never move an existing release tag for a docs fix.
   Download the uploaded assets and compare their hashes before closing
   the release checklist. Keep private vulnerability reporting enabled in repository settings.

## Official first-alpha certificate

Both official APKs use this SHA-256 signing-certificate fingerprint:

```text
964a64cf186537819583ce379dc425132e68812e0293108ffcb50dab15cf2df3
```

This is public verification data, not a signing key. SHA-256 file checksums identify one APK; the
certificate identifies the signer across updates. Neither establishes hardware compatibility by
itself. Future releases must explicitly document any signer migration.

## After publication

Keep the source/tag, APK checksums, build/test results and device evidence aligned. Confirm the
release links and issue form work. Review reports against the declared known issues. Hardware
coverage, accessibility and translations are continuing work; do not mark them complete
merely because an APK builds or launches.
