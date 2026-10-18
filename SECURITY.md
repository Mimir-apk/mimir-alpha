# Security policy

## Reporting a vulnerability

Use GitHub's private reporting: open the **Security** tab of this repository and choose **Report a
vulnerability**. Do not open a public issue or pull request for a security problem.

Include the component and version, the device and Android version, steps to reproduce, and what an
attacker gains. Reports are handled as time allows, with no fixed response time. Please keep the
details private until a fix is released.

There is no bounty.

Only the latest alpha release is supported.

## In scope

**The Tweaker's privileged bridge.** The Tweaker changes CPU, GPU and fan settings through a vendor
system service on the handheld, so a flaw here can affect the device and not just the app. It is meant
to work like this:

- Its Binder service is protected by the signature permission
  `com.gruning.mimir.tweaker.permission.CONTROL_TWEAKER`. Every call from outside the Tweaker's own
  process is checked against it, so only apps signed with the same key can call.
- The interface (`mimir-tweaker/contract`, `IMimirTweaker.aidl`) offers fixed operations: apply a named
  profile, restore, set a fan mode, set a frame limit, hold game audio focus, and so on. Callers pass
  profile ids, mode names, numbers and package names. No method takes a command or a file path. One
  retired method, `setDisplayRateSettingV8`, still has a key and a value in its signature; it never
  reads them and always refuses. An unknown profile id or an invalid fan selection is refused.
- Writes reach the vendor service as short fixed-form statements; one over 198 bytes is never sent.
  Each comes back in a frame that carries its exit status and a per-call nonce. A write that is not
  confirmed counts as ambiguous and is read back before the Tweaker treats it as done.
- The vendor service must pass an identity check before the Tweaker uses it for writes: the protocol
  marker, root uid and gid, the `pservice` SELinux domain and its executable path. A missing or
  mismatched answer means it is refused.
- A separate guardian process exists to restore stock settings when an active profile is interrupted.

A way around any of these points is in scope: running something other than the fixed operations,
calling without the signature, or getting a write accepted without confirmation.

**Mimir's own exported components.** Its launcher and home activities, and three services Android binds: the
keyboard (input method), the trackpad accessibility service, and the media notification listener. The
trackpad service is declared to perform gestures only; it cannot read window content.

**Credentials.** The ScreenScraper, RetroAchievements and SteamGridDB credentials a player enters, and
the request URLs that carry secrets. Passwords and API keys use Keystore-backed encryption at rest
and must not be exposed in logs or reports. Usernames and connection metadata remain in app-private
storage and are not all encrypted.
See [PRIVACY.md](PRIVACY.md) for provider requests and exports.

**Network posture.** Anything that makes Mimir use the network during a scan or a launch, or reach a
host other than the ones it is limited to.

**Untrusted files.** Memory-safety or logic flaws in how Mimir reads game files, archives and disc
images, including the native hashing code in `core-metadata/src/main/cpp`.

## Out of scope

Third-party emulators, the handheld vendor's own services, and builds modified or signed by someone
else.
