# Licensing

## Mimir

Mimir's and Mimir Tweaker's original code is **GPL-3.0-or-later**. [LICENSE](../LICENSE) contains the
terms. You may use and modify it privately and redistribute it, including commercially, subject to
the license. When distributing binaries, provide the corresponding source and required notices.

The official Alpha's source is [v0.1.0-alpha.1-demo](https://github.com/Mimir-apk/mimir-alpha/tree/v0.1.0-alpha.1-demo).
This repository begins with a clean source snapshot; earlier development history and release tags
are not included. Source archives are available beside its APKs. [BUILDING.md](../BUILDING.md) explains how to build
with your own signing key. Signing keys are not distributed with the source.

The software license does not grant trademark rights. Identify modified public builds as unofficial
and avoid branding that suggests endorsement by Mimir. Private modifications do not require renaming.
Third-party product names and marks remain with their owners.

For a fork, upstream patch or redistributed APK, follow the [attribution instructions](attribution.md).

## Third-party material

[NOTICE](../NOTICE) lists the components, sources and license texts. It includes:

- MIT/BSD-licensed source, BAXY console art, IBM Plex under the SIL Open Font License, Google Android
  robot artwork under CC BY 3.0, and Kenney sounds under CC0.
- Runtime Java/Kotlin libraries under Apache-2.0, MIT and 0BSD. Their exact versions and retained
  notices are in [the dependency list](../app/src/main/assets/legal/DEPENDENCIES.md). OkHttp also
  includes Public Suffix List data under MPL-2.0.
- NDK C++ runtime notices, including LLVM exceptions and legacy runtime terms.
- No-Intro metadata under its [Data Usage License](https://datomatic.no-intro.org/terms.html), within
  the rights that project controls; Redump metadata under its [published metadata-use policy](https://wiki.redump.info/Redump).
- GameTDB identifiers/titles under its [software developer invitation](https://www.gametdb.com/Main/FAQ).
  The FAQ asks for a GameTDB link and project contact, and requires permission for use on a website.
  The in-app credit/link is provided; project contact and permission for public-source website
  redistribution are still required records.

These licenses do not grant rights to games, BIOS files, game artwork or trademarks owned by others.
Online-service access is also separate from permission to redistribute downloaded art, music or text.

## Asset-specific permission records

The genre map combines OpenVGDB v29.0, libretro-database's PlayStation 2 genres, Wikidata and Mimir
supplements. The libretro material is [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
with attribution to libretro-database contributors; Wikidata structured data is CC0. Mimir normalized
and combined the inputs. OpenVGDB's data redistribution permission and the historical libretro
input revision still need to be recorded. Mimir's software GPL does not replace these data terms.

The eleven physical-media placeholders came from the "3D CD Box Art Generation" pack;
creation/export and reference-material permission records are needed for that pack. The four
MAME-derived driver/title indexes need their applicable redistribution grant. MAME's separate CC0
terms for its `hash/` folder do not establish a license for those driver-derived outputs.

## Connected services

Mimir has authorization to use SteamGridDB and ScreenScraper. That project permission does not
relicense downloaded artwork or automatically transfer to forks. See [service attribution and
access instructions](attribution.md#connected-services) for the provider links and self-build scope.

## Contributions

The [Mimir CLA](../CLA.md) names Mimir, in the Dominican Republic, as recipient. Contributors retain
copyright and explicitly permit commercial and proprietary sublicensing of rights they hold.
[CONTRIBUTING.md](../CONTRIBUTING.md) gives the signing and manual verification steps. The agreement
cannot grant third-party rights or remove existing GPL rights from copies already distributed.

For a rights concern, open a [Mimir issue](https://github.com/Mimir-apk/mimir-alpha/issues) identifying the
specific file and source. Do not post private documents or credentials. Security reports belong in
the [private security channel](../SECURITY.md).
