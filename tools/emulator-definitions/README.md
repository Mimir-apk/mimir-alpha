# Mimir emulator definitions

A small list of launch details for the emulators Mimir already knows. The app can download it when the player presses
**Settings > Emulators > Emulator definitions > Check for updates**. Nothing is fetched on a schedule, on a scan or on a
launch, and the app works the same without it.

Repository: https://github.com/Mimir-apk/mimir-emulator-definitions (public; changes require a maintainer merge).
Sources: rows are written from the emulators' own manifests and device proof. Daijisho's
MIT-licensed platform data may be used as a reference with attribution; Cocoon's data has no licence and is never copied.

## What is in it (details only)

Four row types, nothing else. The app rejects the **whole file** on any other key or value:

| `type` | fields | meaning |
| --- | --- | --- |
| `activity_alternate` | `package`, `platform`, `activity` | another class name the same emulator has answered to in another build |
| `retroarch_core` | `package`, `platform`, `core` | another RetroArch core for a platform RetroArch already serves |
| `extensions` | `package`, `platform`, `extensions[]` | file extensions the emulator lists. **Only labels the Open with list, see below** |
| `filename_regex` | `package`, `platform`, `regex` | a filename rule (a restricted, length-capped pattern) the emulator lists. **Only labels the Open with list, see below** |

`activity_alternate` and `retroarch_core` rows change how a game launches. `extensions` and `filename_regex` rows never do:
they make an emulator's Open with row say "Doesn't list .cso files" when the game's own file is not on its list, and when
every installed emulator has a list without the file, one line above the rows says "None of your emulators list .cso files."
Nothing is hidden, Play and Automatic are unchanged, and no row can add, remove or reorder an emulator.

Rows for one emulator and platform are a UNION. With no row the emulator has no list and every file counts as listed. The first
row narrows that to a list, and every later row only adds to it; a row never removes anything built into the app. All of
RetroArch's cores share the one list of its package (`com.retroarch.aarch64`) for a platform. A `filename_regex` is matched
by Mimir's own linear-time matcher against the lowercase file name; a name over 255 characters, or a rule that cannot be read,
matches nothing.

Rules that are enforced by the app, not by this repository:

- `package` must be an emulator the app already queries and `platform` one it already serves. **A new emulator package
  needs an app update.** There is no way to add a direct-launch entry from here.
- No intent action, extra, path, capability, kill flag or URL field exists. Appended rows never change which emulator is
  the default for a platform, and never add force-stop authority.
- At most 256 rows, 128 KiB, strings of 256 bytes, and four alternates per package and platform.
- Ordinals run 1 to 999,999,999, and a pointer may be at most 100 above what the app already holds (the stored release,
  else the release built into the app). A bigger jump is refused, so a typo cannot lock the app out of every later release.

## The first release: `defs-1`

`definitions.json` mirrors the tables compiled into the app (the one Flycast alternate activity and every RetroArch core of
every launchable emulator), so applying it changes nothing. Later releases add rows.

| file | what it is | SHA-256 |
| --- | --- | --- |
| `definitions.json` | the release, tag `defs-1` | `2ab63c0719c70105b908cf66d60a2b932c150bab555102d39ff17e41de76236f` |
| `pointer.json` | names the release and its checksum; lives on `main` | (it is the thing that moves) |

## How the app finds a release

1. It reads `https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/pointer.json`
   (`{ordinal, tag, sha256}`; the only mutable file; `tag` is always `defs-<ordinal>`).
2. If it already holds that checksum, or the app itself contains that ordinal or newer, it stops: "Up to date".
3. Otherwise it reads `https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/<tag>/definitions.json`,
   checks that the bytes hash to the pointer's `sha256`, validates every row, and only then stores it. Any failure keeps
   what the player had (the previous update, else the built-in list) and says "Couldn't update; using built-in".

`sha256` in the pointer is the SHA-256 of the **exact bytes** of `definitions.json` (what `sha256sum` prints). The file's own
inner `"sha256"` is a second, self-check hash over its canonical body, and `"sig"` is reserved for signing later.

## Publish the first release (maintainer)

Files to publish, exactly as they are in this folder (`tools/emulator-definitions/` in the Mimir repository):

```
.gitattributes        (forces LF; the checksum is over LF bytes)
README.md
definitions.json
pointer.json
```

Do not publish `seal.py` unless you want it in the data repository too; it is how you reseal after editing rows.

1. Check the files first, from this folder:
   ```
   python seal.py --check              # prints "sealed"
   python -c "import hashlib;print(hashlib.sha256(open('definitions.json','rb').read()).hexdigest())"
   ```
   The hash must be the one in the table above and the `sha256` inside `pointer.json`.
2. In a clone of the data repository, on a branch, add `.gitattributes`, `README.md` and `definitions.json` (not yet
   `pointer.json`). Open the pull request and merge it to `main`.
3. Tag that merge commit and push the tag: `git tag defs-1 && git push origin defs-1`. Tags are immutable; never move one.
4. Then add `pointer.json` to `main` in a second merge. Order matters: a pointer that reaches `main` before its tag exists
   makes the app report "Couldn't update" until the tag appears.
5. Verify what GitHub serves (on Windows use `Get-FileHash -Algorithm SHA256` in PowerShell for the second line):
   ```
   curl -s https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/main/pointer.json
   curl -s https://raw.githubusercontent.com/Mimir-apk/mimir-emulator-definitions/defs-1/definitions.json | sha256sum
   ```
   The second hash must equal the pointer's `sha256`. raw.githubusercontent.com caches for about 5 minutes.

## Publish a later release (`defs-2`, ...)

1. Edit `definitions.json`: add or change rows, and raise `"ordinal"` by one (it only ever goes up; the app refuses a lower
   or reused one, or one more than 100 ahead of what it holds).
2. `python seal.py` recomputes the inner checksum, rewrites the file in the pinned layout (one row per line, keys sorted, LF)
   and rewrites `pointer.json` for the new ordinal and checksum. `python seal.py --check` verifies without writing.
3. Merge the file, tag the merge commit `defs-<ordinal>`, push the tag, then merge `pointer.json`, as above.
4. Anything that adds behaviour the app has not walked on a device (a new launch route) does not belong here: it needs an
   app release with the contract proven on the device.

To fold a release into the app instead (the next app build contains it): raise `BUNDLED_BASELINE_ORDINAL` in
`RegistryBundledDefinitions.kt` to that ordinal in the same commit.

## Regenerating `definitions.json` from the app's tables (Mimir repository)

When the compiled tables change, regenerate the mirror (the test fails until you do, and says so):

```
MIMIR_REGENERATE_DEFINITIONS=1 ./gradlew :core-launch:testDebugUnitTest \
    --tests "com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsDataFilesTest"
```

The generator is deterministic (sorted keys, sorted rows, LF, ASCII, one final newline), so the same tables always give the
same bytes and the same checksum. `DefinitionsDataFilesTest` also pins that this README states the current checksum.
