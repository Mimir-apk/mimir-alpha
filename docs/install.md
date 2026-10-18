# Install Mimir

Mimir needs Android 11 or newer. This is an alpha: see [known-issues.md](known-issues.md). Short
answers to common questions are in [faq.md](faq.md). Spanish: [es/instalar.md](es/instalar.md).

## Get the APKs

Download them from [Releases](https://github.com/Mimir-apk/mimir-alpha/releases/tag/v0.1.0-alpha.1-demo):

| App | File attached to the Alpha Release 1 - Demo | Needed? |
|---|---|---|
| Mimir (`com.gruning.mimir.rewrite`) | [mimir-0.1.0-alpha.1-demo.apk](https://github.com/Mimir-apk/mimir-alpha/releases/download/v0.1.0-alpha.1-demo/mimir-0.1.0-alpha.1-demo.apk) | Yes |
| Mimir Tweaker (`com.gruning.mimir.tweaker`) | [mimir-tweaker-0.4.4-tw12.apk](https://github.com/Mimir-apk/mimir-alpha/releases/download/v0.1.0-alpha.1-demo/mimir-tweaker-0.4.4-tw12.apk) | Optional hardware controls |

The two apps have different version numbers; use the pair from the same release. GitHub's source
ZIP/tar.gz files are for building, not installing on Android. [Release files](release-notes/0.1.0-alpha.1-demo-files.md)
lists the APK hashes, signing certificate and matching source.

For the official build, use the APKs attached to that release. Android may ask you to allow installs from
the app you opened the file with; allow it for this install.

The Tweaker is a separate app with hardware controls. Its performance profiles work only on handhelds
with a Snapdragon 865, 8 Gen 2 or 8 Elite chip and compatible firmware exposing the required built-in
vendor access. Chip support alone is not enough; fan controls also depend on available hardware. The setting
"Let other music play over games" also needs it. Scanning, browsing and launching do not require it. If you want
the Tweaker, install it first and Mimir after it.

Both release APKs are signed with the same key, which is how the two apps recognise each other. An APK
you build yourself carries your own key: Android will not install it over a release build, and a
Mimir and Tweaker built with different keys will not pair.

## Your first game

1. Install and set up your emulator separately. Check that it opens a game directly before trying
   to launch that game through Mimir. Mimir does not include emulators, games or BIOS files.
2. Open Mimir and finish the setup prompts. You can skip optional permissions and account connections;
   see [permissions below](#what-mimir-asks-for). You do not need an online account to scan or launch.
3. Select **Choose folder** during setup and grant access to your game folder. Later, use
   **Settings › Library › Add Library folder** for another location. **Choose Mimir ROM folder**
   changes the primary folder; use Add Library folder when you want to keep the existing one too.
4. Let the scan finish. If files were added or moved, use **Settings › Library › Rescan library**.
   Keep the storage connected and grant access again if Android asks.
5. In **Settings › Emulators**, review the default emulator for each scanned platform. The list
   fills after games are scanned. Use **Choose emulator**, **Choose another** or **Change** to
   select an installed emulator when needed.
6. Choose a game and launch it. **Open with** selects an emulator for that game; **Automatic** returns
   it to the platform default. If it does not launch, check [troubleshooting](#troubleshooting)
   before changing your library.

To return, exit the emulator, use Android Home if Mimir is your Home app, or use the Mimir menu
where available. Automatic return varies by emulator and firmware. Artwork downloads and making
Mimir your Home app can wait until after your first successful launch.

If you installed Tweaker, use **Settings › Controls › Utility › Open** in Mimir to find its controls. The production Tweaker
has no separate launcher icon. Controls appear according to available hardware and firmware;
see [Tweaker help](../mimir-tweaker/README.md).

## Updating

Install the new APK over the old one: open it and choose Update, or run `adb install -r <file>`. Your
library, Tomes and settings stay.

- Do not uninstall Mimir to update it. Uninstalling removes that data.
- Do not install an older build over a newer one. The older app may not be able to read the newer
  database.

## What Mimir asks for

During first setup Mimir asks for four things. Each is optional; choose "Not now" to skip. You can
change them later in Android's settings.

| Ask | What it is for |
|---|---|
| All files access | Mimir's own file manager, and showing and saving artwork beside your games. If you decline, you choose folders in Android's picker instead. |
| Display over other apps | The Mimir button: an M you hold for a menu, even in the middle of a game. |
| Usage access | Lets Mimir see when you leave a game, so the Mimir button can show over it. A handheld with a second screen, like the AYN Thor, does not need this. |
| Home app | Makes Mimir the Home app, so the handheld starts in Mimir. You can pick another in Android Settings › Default apps. |

Later, only when you use the feature:

- **Notifications**: asked once, the first time you turn on the performance overlay, or the Mimir
  button on a single-screen handheld. Both run whatever you answer.
- **Modify system settings**: asked from the Utility panel for the brightness slider. If the device
  uses auto-brightness, turn that off first, or the slider cannot change brightness.

Three features are switched on by you, in Android's settings:

- **Mimir Keyboard**, an input method. Settings › Controls › Keyboard in Mimir shows the state and
  takes you to Android's keyboard settings.
- **The trackpad service**, an Accessibility service. It can tap and swipe where the trackpad pointer
  is. It cannot read the screen or what you type.
- **Notification access**, so Mimir can show and control other apps' music players (Settings › Music ›
  Behaviour › Show other players).

Android grants these at install without asking:

- Internet, for artwork, metadata and achievement actions you start. Scanning and launching never use it.
- Foreground service and wake lock, for the Mimir button, the performance overlay
  and music that keeps playing with Mimir in the background.
- Vibrate, audio settings (the music volume in Utility) and ending background processes (for closing
  games Mimir started).
- Control of the Tweaker, a permission only an app with the Tweaker's signature can hold.

Mimir does not ask to see every app on the device. It lists the emulators it knows and sees apps that
have a launcher entry.

For stored data, provider requests and backups, see [Privacy](../PRIVACY.md). Notification access
lets Mimir read media notifications; the keyboard itself receives text you enter through it.

## Troubleshooting

| What you see | What to check |
|---|---|
| Android says the update is incompatible or the package conflicts | Official, self-built and CI APKs can have different signing keys. Use an update from the same signer as the installed app. Do not uninstall or clear data to bypass the error. See [signing](../mimir-tweaker/SIGNING.md). |
| No Tweaker icon after installation | This is expected for the production app. Open Utility inside Mimir. |
| Tweaker is unavailable in Mimir | Use both APKs from the same release, install Tweaker first, then install/update Mimir and reopen it. Self-builds need the shared key before either app is built. A supported chip still needs compatible vendor access; installation cannot add it. |
| The library is empty or games disappeared | Check that the storage is connected and Mimir can access the selected folder. Add the intended folder in Settings › Library, then Rescan library. Avoid replacing the primary folder when you meant to add another. |
| A game appears but will not launch | Open it directly in the emulator, check that platform's default in Settings › Emulators, then try Open with for the game. Emulator setup and file-format support vary. |
| ScreenScraper sign-in does not enable downloads | The official Alpha has no ScreenScraper developer registration. Personal account sign-in cannot enable it. Local media works; SteamGridDB uses your own key. See the [FAQ](faq.md). |

If these steps do not resolve it, check [known issues](known-issues.md) and report the problem below.

## Report a bug

Open an issue with the bug form. Give your device, Android version and the Mimir version (Settings ›
About › Version), whether you used official APKs or a self-build, and the Tweaker version if relevant.
For a launch problem include the emulator name/version and whether the same file opens directly in it.
Describe what you did, what you expected and what happened. A screenshot or short video
helps. Logs are optional; remove account names and emails first. Never post a password or a link that
contains one.
