# Known issues in 0.1.0-alpha.1-demo

Reported behavior can depend on the device, firmware and emulator. If an issue behaves differently
on your device, include the Mimir version and what happened in your report.
Spanish: [es/problemas-conocidos.md](es/problemas-conocidos.md).

## Open problems

- **Bottom: the floating ᛗ coin covers content.** On the AYN Thor's lower screen (Bottom), any touch
  shows the floating ᛗ coin. It can sit over a platform tile and cut its label: "Nintendo 64" reads
  "Nintendo 6". It can also cover part of the Activity page.
- **Bottom: the game count disappears after a game.** The Bottom header shows how many platforms and
  games you have, for example "18 platforms · 63 games". After you launch a game and come back, the
  count is gone and stays gone.
- **Quick Launch: the "Opens on" chooser is see-through.** Hold an app in Bottom's Quick Launch and the
  chooser that asks where it opens lets the heading and the app tiles show through behind it.
- **Customize media: Search can stick.** If you press Search several times in a row, the page can stay on
  "searching".
- **Now Playing clock frozen after Recents.** If you bring a parked game back through Android's Recents,
  the Now Playing clock stays frozen. It runs again when you return with Mimir's Resume.
- **Emulators draw on-screen pads.** Some emulators draw their own on-screen pads while the Thor's pad is
  attached. That is an emulator setting, not Mimir. Turn the on-screen controls off in the emulator.

## Limitations

- **ScreenScraper and IGDB are unconfigured in the official Alpha.** A personal account does
  not enable ScreenScraper artwork or game-music downloads in this APK. Local media still works;
  SteamGridDB uses your own key.
- **Tweaker firmware support.** Supported chips still need the built-in vendor access and runtime
  capabilities. Fan controls are available only where the device exposes them.

- **Very large fonts.** At the largest Android font sizes, a narrow screen can drop a button, for example
  the Search pill on a game list.
- **Dark app icons.** Android app icons with a black symbol are hard to see on the launch card's dark
  plate.
- **Game music "skipped".** The game-music card counts games whose files have moved or been deleted as
  "skipped" and does not say why. Rescan your library to clear them.
- **WAV to FLAC.** You can hear a short gap when one track is a WAV file and the next is FLAC, or the
  other way round.
- **Music folders named after a platform.** In your music folder, a sub-folder named after a game platform
  (for example "Genesis" or "Saturn") is treated as that platform's game music.
- **Low-latency audio and the System EQ.** Games that play through Android's low-latency audio path skip
  effects such as the System EQ. RetroArch does; other emulators may behave differently.
- **Builds you make yourself.** They carry your own signing key, so they cannot update a release build,
  and a personal debug build needs your own developer registration for ScreenScraper (see [BUILDING.md](../BUILDING.md)).
