# Contributing to Mimir

## Contributor agreement

Code, documentation, art and data patches need acceptance of [Mimir CLA v1.1](CLA.md).
You retain copyright. The CLA explicitly permits Mimir to sublicense your original contributions,
including commercially or under proprietary licenses. Third-party material keeps its own terms.
Ordinary bug reports and questions do not require a CLA.

### Sign with your first pull request

1. Read CLA.md and use GitHub's **Copy permalink** so the link names the exact commit, not `main`.
2. From your own GitHub account, post this comment on the pull request after filling in the fields:

   ```text
   I have read and agree to Mimir CLA v1.1 at [immutable CLA permalink].
   This acceptance covers pull request [URL], including its existing contributions, and my later Contributions within section 1 of that agreement.
   I intend this statement and my typed name to be my electronic signature.
   Full legal name: [name]
   GitHub account: [username]
   I sign for: [myself, or the full legal name of the entity I am authorized to represent]
   ```

   This comment is public. Do not post addresses, identity documents or employment contracts.
3. Each copyright owner contributing to the pull request must be covered. Signing for yourself
   does not sign for someone else's work or your employer's property.

The maintainer checks the signature manually before merging and applies `cla:accepted` only after
that check. There is no automatic CLA bot. A checkbox, commit sign-off, someone else's comment or
the label alone is not a signature. A later CLA version needs a new acceptance.

### Maintainer steps

- Confirm the comment is from the contributor's account, contains the required statement and
  name, and links to the immutable v1.1 text whose recipient is the project's actual operator.
- Account for every contributor/copyright owner, including employer-owned and third-party work.
  Ask for authority or a separate owner acceptance where needed; do not treat another person's
  Git author name as consent.
- Preserve the full agreement and applicable signing instructions and their hashes, comment JSON/URL and timestamp, contributor/entity
  identity, pull-request number and relevant commit IDs in private project records before merging.
- Apply `cla:accepted` only when coverage is complete. Recheck coverage if contributors, copyright owners or employer authority change.
  Preserve existing signed versions; changing CLA.md requires renewed agreement to the new terms.

Acceptance records support attribution and permission tracking and are kept while the contributions
are used and as needed to handle related claims. Public comments remain subject to GitHub's own
privacy controls. Contact the project through Issues for agreement questions; do not attach private
documents to a public issue.

For redistribution and third-party credits, follow [Attribution](docs/attribution.md).

## What fits

Welcome: bug fixes, fixes for a specific device or emulator, corrections to platform and emulator
data, performance work, tests, translations and documentation. For anything bigger than a fix, open
an issue first so the design can be agreed before you write it.

Changes to the Tweaker (`mimir-tweaker/`) write to hardware. Say which device and chip you tested on,
and keep its tests passing.

## Rules the code keeps

- **Offline first.** Scanning and launching never use the network. Any online feature is opt-in and
  starts from a player action. The existing routes sit behind the gates in `core-model` (`posture/`),
  and `tools/mimir_connected_posture_gate.py` looks for network calls outside them.
- **No game content.** No code, link or data that fetches, indexes or points to ROMs, BIOS files or
  firmware. Mimir launches what the player already has.
- **Focus goes through `FocusAuthority`** (`core-input`). Do not use Compose's `focusable()` or
  `focusTarget()` directly.
- **User-visible text lives in string resources**, with a Spanish (es-419) entry beside the English
  one in `values-b+es+419`. Existing screens are still being moved over; new text should not add to
  that.
- **No hard-coded colours in `feature-*` modules.** Use the `core-ui` theme tokens.
- **No secrets in the repository.** Only the supported build-time service credentials described in
  BUILDING.md belong in git-ignored `local.properties`. Enter player passwords through the app's
  account connection. Keep private signing/qualification keys outside committed source; never put
  qualification private keys in Gradle properties, environment variables, logs or APKs.
  Never log a ScreenScraper or RetroAchievements request URL, or any credential; those URLs carry the
  player's login.
- **Keep files within the size budget.** `SourceFileSizeRatchetTest` in `:app` fails when a main Kotlin file
  grows past its recorded budget in `app/src/test/resources/source-file-size-budget.txt`, or when an
  unlisted file passes 2,000 lines. Split the file. Raising a budget on purpose means running
  `tools/update-file-size-budget.sh` and saying why in the commit.

## Before you open a pull request

1. Add or update relevant tests for behavior changes. Documentation-only corrections need a diff
   and reference check, not an APK build or unrelated unit suites.
2. For code changes, run the relevant unit tests in affected modules, and the Tweaker's when its
   behavior changes. The commands
   are in [BUILDING.md](BUILDING.md).
3. For applicable UI/code changes, run `python3 tools/mimir_ui_invariants_gate.py`. It lists findings by rule. Resolve findings rather than changing the baseline to hide them;
   F1 (raw `focusable`) must stay empty.
4. For broader code validation, `tools/mimir-quality-gate.sh` runs the whole ladder: tool self-tests, debug and release builds,
   unit tests, lint, and the three source gates. It runs Gradle with `--offline`, so build once online first,
   and it needs Bash, Python 3 and Node.js. On Windows use Git Bash for these scripts. Its launcher
   uses an unpacked Gradle 8.9 distribution (the wrapper download can provide it), or `MIMIR_GRADLE`
   pointing to that distribution's executable. Cache the needed test/lint dependencies online too;
   an app build alone may not fetch them all. See BUILDING.md for memory guidance and the Windows
   directory-sync test limitation; use Linux for the full JVM ladder.
5. Keep the pull request to one concern. Describe what changed and why. If you tested on a device, say
   which one and what you did.

GitHub Actions is disabled for this release repository. When enabled in a personal fork, the
preserved CI workflow builds the debug APK and gates assertions in `core-model`, `core-storage`, `core-launch`,
`core-resolver` and Tweaker tests. Other Mimir assertions remain report-only. Setup, compilation,
incomplete execution, missing results and entirely skipped critical suites fail CI. The `CI verdict`
check rejects unexpected job skips. Read the annotations and XML for report-only failures.
For shell prerequisites and gate commands, see [BUILDING.md](BUILDING.md#command-line-checks).

For module ownership, maintenance gaps and changes that cross module boundaries, see
[Maintenance](docs/maintenance.md).

## Reporting bugs

Use the bug form under Issues. Give your device, Android version, the Mimir version (Settings ›
About), what you did, what you expected and what happened. Remove account names, emails and passwords
from anything you attach. Report security problems as described in [SECURITY.md](SECURITY.md), not in
an issue.
