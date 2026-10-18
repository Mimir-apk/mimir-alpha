# Redistributing Mimir and crediting its sources

Use this page when publishing a fork, sending a patch upstream, or distributing your own APK.
Private personal builds do not require publication of your modifications.

## A complete distribution

1. Keep [LICENSE](../LICENSE), [NOTICE](../NOTICE) and the license/copyright headers for material
   you retain. Describe your modifications and identify your public build as unofficial.
2. Provide the corresponding source for your binary, including your modifications and build scripts,
   under GPL-3.0-or-later. Link the exact source revision and provide a source archive alongside the
   APK. A link to an unrelated upstream revision is not source for your modified binary.
3. Preserve `app/src/main/assets/legal/`, the Tweaker's `app/src/main/assets/legal/`,
   `core-ui/src/main/assets/fonts/IBMPlex-OFL.txt`, and the BAXY notice/license files when using
   their components. Include relevant notices with the APK distribution and make credits accessible.
   [Runtime dependencies](../app/src/main/assets/legal/DEPENDENCIES.md) links the additional texts
   and source for the MPL-licensed Public Suffix List. A link to this page alone does not replace
   license texts or other notices that a component requires you to retain.
4. Add the applicable source credits below. Record the source revision and your changes for
   replacement or updated datasets. Do not label all bundled data/artwork as GPL.
5. For an upstream patch, identify each added third-party file, creator/source, immutable revision,
   license and modifications in the pull request. Accept the [CLA](../CLA.md) only for rights you
   hold; third-party material retains its own terms.

## Component credits and conditions

| Material | What to preserve or state | Publisher terms |
|---|---|---|
| Mimir and Tweaker original code | Mimir copyright, GPL-3.0-or-later text, modification notices and matching source. | [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) |
| MIT/BSD source and runtime libraries | Preserve the exact copyright, license and disclaimer texts in NOTICE and the linked dependency files; retain applicable Apache NOTICE text and describe source modifications. | [Component inventory](../NOTICE), [runtime inventory](../app/src/main/assets/legal/DEPENDENCIES.md) |
| BAXY Automatic console SVGs | Credit BAXY Automatic / baxysquare, retain `NOTICE_BAXY_AUTOMATIC.txt` and `LICENSE_BAXY_AUTOMATIC.txt` under `core-ui/src/main/assets/platform-console-art/`. Record changes. No endorsement or product trademark rights are granted. | [Pinned MIT declaration](https://github.com/baxysquare/baxy-retroarch-themes/blob/af8db5db4cbb76c0db1334f5096ac2289f83efcf/README.md) |
| Switch title catalog | Retain Blake Warner's copyright and the MIT text in `app/src/main/assets/legal/LICENSE-titledb.txt`. | [Upstream license](https://github.com/blawar/titledb/blob/73d736a823163279ad570eadbd9ab524a0286739/LICENSE) |
| IBM Plex fonts | Retain the IBM copyright and SIL OFL text. Modified fonts must respect the reserved font name Plex; do not sell the fonts by themselves. | [IBM license](https://github.com/IBM/plex/blob/master/LICENSE.txt) |
| Six edited Kenney Interface Sounds | CC0 requires no attribution; the existing Kenney credit and modification list may be kept. | [Kenney asset page](https://kenney.nl/assets/interface-sounds), [Mimir changes](../core-audio/PROVENANCE.md) |
| No-Intro identity metadata | Its Data Usage License permits reuse, modification and distribution within the rights it controls, without mandatory credit. Existing No-Intro credit may be kept. This does not license uploaded photos or game content. | [Data Usage License](https://datomatic.no-intro.org/terms.html) |
| Redump identity metadata | Its FAQ permits reuse of contributed metadata. Keep intermediary notices where applicable; do not describe that policy as an MIT license for the underlying database. | [Metadata FAQ](https://wiki.redump.info/Redump) |
| Wikidata genre statements | Structured data is CC0. Credit Wikidata voluntarily; do not extend this statement to other inputs in the combined genre map. | [Wikidata licensing](https://www.wikidata.org/wiki/Wikidata:Licensing) |
| libretro PS2 genre material | Credit libretro-database contributors, link the source and CC BY-SA 4.0, identify changes, and apply ShareAlike to adapted material as required. This does not establish permission for the combined OpenVGDB input. | [libretro license](https://github.com/libretro/libretro-database/blob/master/LICENSE), [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| GameTDB identifiers/titles | Keep a visible GameTDB link and contact the project about your software. Clarify permission for website/database hosting rather than assuming software permission covers it. | [Developer FAQ](https://www.gametdb.com/Main/FAQ) |

For the libretro-derived material, a credit may read:

> Genre material from libretro-database contributors, Sony - PlayStation 2.rdb, under CC BY-SA 4.0.
> Mimir normalized titles, grouped genres and combined the inputs.

Include the [source](https://github.com/libretro/libretro-database) and
[license](https://creativecommons.org/licenses/by-sa/4.0/) links with that credit. For newly
regenerated data, also identify the actual input commit; do not invent the legacy input revision.

For the recolored Android robot, include Google's prescribed credit and license link in the
creative or its accompanying credits:

> The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License.

Link [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) and identify the palette recoloring.
[Google's brand guidelines](https://developer.android.com/distribute/marketing-tools/brand-guidelines#android)
apply separately to its marks.

## Material requiring a separate rights basis

These entries are not covered merely by adding a credit:

- `core-resolver/src/main/assets/genre/genre-map.tsvgz` combines OpenVGDB v29.0 with other inputs.
  No OpenVGDB data grant is identified in its [repository](https://github.com/OpenVGDB/OpenVGDB);
  its [licensing question](https://github.com/OpenVGDB/OpenVGDB/issues/43) remains open. Obtain a
  grant covering your use or regenerate from inputs with documented permission. The verified
  libretro/Wikidata terms above do not license OpenVGDB material.
- `app/src/main/assets/dat/mame0287.mpaygz`, `mame0287.mmemgz`, `mame0287.mdepgz` and
  `arcade.idcatgz` are driver/title indexes. MAME's [CC0 dedication for `hash/`](https://github.com/mamedev/mame/blob/mame0287/COPYING)
  does not by itself establish their terms. Establish an applicable rights basis or replace them.
- The eleven `core-ui/src/main/res/drawable-nodpi/mimir_media_art_*.png` placeholders come from
  Mimir's 3D CD Box Art Generation pack. An applicable creator grant and any incorporated source
  rights are needed before treating them as freely licensed assets.

## Connected services

Mimir uses SteamGridDB and ScreenScraper with authorization. Project authorization is separate
from a general redistribution license for downloaded content and from permission for another
project. Forks should use their own registrations/keys and confirm the terms applicable to them.

- [SteamGridDB terms](https://www.steamgriddb.com/terms): keep service permission distinct from
  the rights in uploaded artwork.
- [ScreenScraper API conditions](https://www.screenscraper.fr/webapi2.php): fully free applications
  are the normal permitted category; other arrangements require authorization.
- [IGDB API documentation](https://api-docs.igdb.com/): commercial integration uses its partnership
  process; that process can be free of charge. A free API price is not a blanket content license.
- [RetroAchievements API](https://api-docs.retroachievements.org/): follow account, access and rate
  limits. The rcheevos code's MIT grant is separate from site content and service access.

The official Alpha has no ScreenScraper or IGDB developer registration configured. Enabling
an integration in a personal build does not change the provider's terms or the app's GPL license.
