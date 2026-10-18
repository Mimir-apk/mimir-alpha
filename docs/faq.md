# FAQ

Install steps and permissions are in [install.md](install.md). Spanish:
[es/preguntas-frecuentes.md](es/preguntas-frecuentes.md).

**What is Mimir?**
A game launcher for Android handhelds, made to be used with a controller. It scans the game folders
you choose, shows your library as shelves and opens games in the emulator you set up. Touch works too.
Exit the emulator, use Home
if Mimir is your Home app, or use the Mimir menu where available to return.

**Which handhelds does it run on?**
Mimir needs Android 11 or newer. The maintainers have used it on the AYN Thor (both screens), the
AYN Odin 3 and the Retroid Pocket Nova. On any other device, tell us what works and what does not.

**Does Mimir come with games or BIOS files?**
No. Bring your own games and your own emulators.

**How do I get my first game running?**
Follow [Your first game](install.md#your-first-game): set up the emulator, choose a game folder,
let Mimir scan it, review the platform's emulator and launch a game. You can do this offline without
Tweaker or a provider account. See [troubleshooting](install.md#troubleshooting) for an empty library
or a game that does not launch.

**Why is there no Tweaker icon, or why do the two apps not connect?**
The production Tweaker opens through Utility inside Mimir. Use both APKs from the same release;
different app version numbers are expected. Install Tweaker first and then Mimir. Their signing
certificates must match, including for personal builds. Hardware controls also need supported
firmware capabilities. See [installation troubleshooting](install.md#troubleshooting).

**How do I make Mimir my Home app?**
First setup offers "Make Mimir your Home app". Choose Allow, then pick Mimir in Android's prompt. If you
skipped it, open Android Settings › Default apps › Home app and choose Mimir. If Android asks which Home
app to use, choose Mimir, then Always.

**Where do I connect ScreenScraper?**
**ScreenScraper is unavailable in the official 0.1.0-alpha.1-demo APK.** It has no developer registration,
so a personal account cannot enable artwork or game-music downloads. A personal debug build can use
your own developer registration (see [BUILDING.md](../BUILDING.md#optional-online-services)). In a
configured build, connect through Settings › Appearance › Enrichment & artwork. Downloaded files
remain on the device after disconnection.

**What is the SteamGridDB key for?**
It is an optional source for game icons, hero art and platform icons. PC games need it for heroes and
most logos. Enter your own key in Settings › Appearance › Enrichment & artwork › Access key.
It is stored encrypted on the device and sent to SteamGridDB to authenticate requests.
Release builds come without a key.

**What does Enrich do?**
In a configured build it asks ScreenScraper for one game's description and art. That provider is
unavailable in the official Alpha. For PC games it mainly uses SteamGridDB. After a completed
result, including no match, the Enrich button goes away. Use Customize media to change art after that.
If a request fails, for example because the network is down, the button stays so you can try again.

**What is game music?**
The sound under a game's launch animation, cut from that game's ScreenScraper video clip.
ScreenScraper downloading is unavailable in the official Alpha. In a configured debug build,
connect ScreenScraper first, then download it in Settings › Music › Game music. Launch sound sets its volume:
Off, 25%, 50%, 75% or 100%. A game whose clip has no sound gets no game music.

**What is Mimir Tweaker?**
An optional second app with hardware controls. Its panel appears inside Mimir's Utility panel: a
performance profile (Stock, Quiet, Balanced or Performance) that you preview and then Apply or Cancel, a
fan mode (Auto or Manual, with a fan response setting), and a performance overlay. Profiles support
Snapdragon 865, 8 Gen 2 and 8 Elite families only on compatible firmware with the required built-in
vendor access. Fan controls also depend on the device's available controls.
"Let other music play over games" also needs it. Everything
else works without it.

**What does "dual screen" mean?**
A handheld with two physical displays, such as the AYN Thor. Mimir uses the lower screen, which it calls
Bottom, for a second surface with two pages, Shelf and Activity. Swipe to switch. Activity shows what you
are playing or last played, this week's games, your most-launched games and achievements. It counts
launches, not playtime. While a game is paused, NOW PLAYING shows PAUSED beside the clock. Bottom also
has a music player (Volume, Equalizer, Source) and a trackpad. The first time you open the trackpad,
Mimir asks for your consent.

**What does Mimir send over the network?**
Scanning and launch dispatch work offline. Separate connected features can refresh while the app is
running, without another tap for each request. Providers include ScreenScraper in configured builds,
SteamGridDB (the Steam store description lookup for PC
games rides on that setting), RetroAchievements, Enrich, and Settings › Emulators › Check for updates,
which downloads a small list of launch details from GitHub. When a scan finds new games, Mimir
asks before it fetches their art. A configured personal debug build can also use IGDB/Twitch as an
artwork/details fallback under the artwork settings. See [Privacy](../PRIVACY.md) for request data,
stored account information and exports.

**Where do I report a bug?**
Open an issue with the bug form. See [install.md](install.md#report-a-bug) for what to include.
