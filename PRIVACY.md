# Privacy in Mimir 0.1.0-alpha.1-demo

Updated October 18, 2026. This describes the public Alpha. Modified builds and external
emulators can behave differently.

## Data on your device

Mimir stores your chosen folders, game filenames and identifiers, library settings, Favorites,
Tomes, launch history, downloaded media and connected-account state. The keyboard receives text
you enter through it. If you enable notification access, Mimir can read media notifications to
show and control other players. The trackpad Accessibility service performs gestures; it is
declared without permission to retrieve window content.

Passwords and player API keys use Android Keystore-backed encryption in app-private storage.
Usernames and connection metadata are not all encrypted; they remain in app-private preferences.
Encryption at rest does not keep a provider from receiving the credentials needed to authenticate
a request, and does not protect a device whose operating system or app process is compromised.

Mimir's Android automatic backup is disabled. User-created exports are separate: backup JSON can
contain a profile name, folder URI hints, library selections and achievement information. Those
exports are not encrypted by Mimir. Review them before sharing. Credential stores are excluded
from recovery exports. Logs, screenshots and videos can also reveal filenames or account names.

## Online features

Scanning and launch dispatch do not require provider requests. Online features are optional and
use the app's connection/consent settings. A connected feature can refresh without a separate tap
for every request. Providers receive your IP address and ordinary connection information, as well
as the request data below; their own policies apply.

| Provider | Data used for a request |
|---|---|
| SteamGridDB | Player API key, game searches/identifiers and requested artwork. PC description lookup can also contact the Steam storefront under the artwork setting. |
| RetroAchievements | Account name and API key, plus game/achievement identifiers used for account and achievement features. |
| ScreenScraper, in a configured debug build | Developer registration, member sign-in when supplied, game names, identifiers, file sizes and/or hashes, and requested media. It is unconfigured in the official Alpha. |
| IGDB/Twitch, in a configured debug build | Developer authentication and game queries for artwork/details fallback under the artwork settings. It is unconfigured in the official Alpha. |
| GitHub | Emulator-definition downloads when you choose Check for updates. Ordinary browser links to source, help or credits also contact the site you open. |

Tweaker's optional signed device-catalog refresh is disabled in this release because no catalog
verification key is compiled in. It uses the bundled catalog instead.

Provider information: [SteamGridDB](https://www.steamgriddb.com/),
[Steam privacy](https://store.steampowered.com/privacy_agreement/),
[RetroAchievements](https://retroachievements.org/), [ScreenScraper](https://www.screenscraper.fr/),
[IGDB API](https://api-docs.igdb.com/) and [GitHub privacy](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
These links identify external services; Mimir does not control their retention or processing.

Mimir 0.1.0-alpha.1-demo has no advertising/analytics SDK or automatic crash-upload service. Reporting a
bug sends what you choose to attach to GitHub. Public issues and attachments can be read by others;
remove personal data and secrets first. Use [private reporting](SECURITY.md) for vulnerabilities.

## Removing data and asking questions

Disconnect services and remove their keys through Mimir's connection settings. Disconnection does
not delete media already downloaded. Use the relevant file manager to remove those files if wanted.
Android's Clear storage or uninstall removes Mimir's private data and settings; files saved beside
games or exported elsewhere can remain. These actions do not erase records kept by a provider or
public GitHub posts. Use each provider's account controls for those records.

For questions about this description, open a [Mimir issue](https://github.com/Mimir-apk/mimir-alpha/issues)
without private account data.
