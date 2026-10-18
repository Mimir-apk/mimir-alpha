# Native RetroAchievements reader notices

This directory vendors only the source required for local, canonical game
identification. It does not add a network path. This short file is not the complete license
inventory: see the repository root `NOTICE` (sections 4 and 5) and
`app/src/main/assets/legal/` for subcomponent/runtime notices.

## rcheevos

- Source: <https://github.com/RetroAchievements/rcheevos>
- Pinned commit: `2ac45d357bce2906bb0f1438f3eaf8ce6e78e3c4`
- License: MIT; the complete upstream text is in `vendor/rcheevos/LICENSE`.
- Local build excludes ZIP and encrypted-console support. Mimir's existing
  Kotlin cartridge hasher remains authoritative for the streaming cartridge
  lane; the native subset supplies the 13 seek/disc algorithms.

## libchdr

- Source: <https://github.com/rtissera/libchdr>
- Pinned commit: `6cde5348eb118da3baf94f75a69577a005a484fd`
- License: BSD-3-Clause; the complete upstream text is in
  `vendor/libchdr/LICENSE.txt`.
- Local change: the upstream test subdirectory is guarded by the
  `CHDR_BUILD_TESTS` CMake option so Android consumes the library only.
