package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.launch.remotedefs.ActiveDefinitions
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * Shipping-backed Android intent profiles for the standalone emulator seeds whose payload contracts
 * are known. This stays pure so the truth table can be unit-tested without Android stubs.
 */
internal object ShippingLaunchProfiles {
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ACTION_APS3E = "aenu.intent.action.APS3E"
    // xendroid.compose's own custom action, read off its EmulatorHostActivity manifest filter and off the
    // app's own internal game-launch Intent builder (see EmulatorSeedRegistry for the full dex evidence).
    private const val ACTION_XENDROID = "xendroid.intent.action.xendroid"

    private const val PKG_RETROARCH_AARCH64 = "com.retroarch.aarch64"
    private const val PKG_RETROARCH_UNIVERSAL = "com.retroarch"
    private const val PKG_RETROARCH_RA32 = "com.retroarch.ra32"
    private const val ACTIVITY_RETROARCH = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private const val PKG_DOLPHIN = "org.dolphinemu.dolphinemu"
    private const val ACTIVITY_DOLPHIN = "org.dolphinemu.dolphinemu.ui.main.MainActivity"

    /**
     * The activity Dolphin shows the running game in. The dispatch activity above is its startup handler, which starts
     * this one at once (walk-32g, Nova: `START .../.ui.main.MainActivity`, then `START .../.activities.EmulationActivity`
     * 159 ms later, displayed 107 ms after that). Well known: it is the class Dolphin's own game launcher names.
     */
    private const val ACTIVITY_DOLPHIN_GAME = "org.dolphinemu.dolphinemu.activities.EmulationActivity"
    private const val PKG_FLYCAST = "com.flycast.emulator"
    private const val ACTIVITY_FLYCAST = "com.flycast.emulator.MainActivity"

    /**
     * The same package's activity in older builds: Flycast v2.2 (versionCode 8), on the Nova, has only this one, so the
     * Dreamcast row read "Not supported yet" with Flycast installed (observed 2026-09-28).
     */
    private const val ACTIVITY_FLYCAST_REICAST = "com.reicast.emulator.MainActivity"
    private const val PKG_DUCKSTATION = "com.github.stenzek.duckstation"
    private const val ACTIVITY_DUCKSTATION = "com.github.stenzek.duckstation.EmulationActivity"
    private const val PKG_PPSSPP = "org.ppsspp.ppsspp"
    private const val PKG_PPSSPP_GOLD = "org.ppsspp.ppssppgold"
    private const val ACTIVITY_PPSSPP = "org.ppsspp.ppsspp.PpssppActivity"
    private const val PKG_MELONDS = "me.magnum.melonds"
    private const val PKG_MELONDS_NIGHTLY = "me.magnum.melonds.nightly"
    private const val PKG_MELONDS_DUAL = "me.magnum.melondualds"
    private const val ACTIVITY_MELONDS = "me.magnum.melonds.ui.emulator.EmulatorActivity"
    private const val PKG_AETHERSX2 = "xyz.aethersx2.android"
    private const val PKG_NETHERSX2_TURNIP = "xyz.aethersx2.cturnip"
    private const val ACTIVITY_AETHERSX2 = "xyz.aethersx2.android.EmulationActivity"
    private const val PKG_CITRA = "org.citra.citra_emu"
    private const val ACTIVITY_CITRA = "org.citra.citra_emu.activities.EmulationActivity"
    // 3DS Citra fork — reuses Citra's EmulationActivity class + the identical VIEW+content contract (verified
    // on Thor 2026-07-09 from the installed APK's intent filters).
    private const val PKG_LIME3DS = "io.github.lime3ds.android"
    private const val PKG_AZAHAR = "org.azahar_emu.azahar"
    private const val ACTIVITY_LIME3DS = "org.citra.citra_emu.activities.EmulationActivity"
    private val EdenPackages = setOf(
        "dev.eden.eden_emulator",
        "dev.eden.eden_emulator.nightly",
        "com.miHoYo.Yuanshen.nightly",
        "dev.legacy.eden_emulator",
        "dev.legacy.eden_emulator.nightly",
    )
    private const val ACTIVITY_EDEN = "org.yuzu.yuzu_emu.activities.EmulationActivity"
    private const val PKG_CITRON = "org.citron.citron_emu"
    private const val ACTIVITY_CITRON = "org.citron.citron_emu.activities.EmulationActivity"
    private const val PKG_RPCSX = "net.rpcsx"
    private const val ACTIVITY_RPCSX = "net.rpcsx.MainActivity"
    // The pair the retired shipping catalog observed. Nothing installs this package, so this profile
    // never fires; it exists because every non-recognition seed must be dispatchable and the observed
    // id is still seeded. Kept exactly as observed rather than "corrected" -- see PKG_X360_MOBILE.
    private const val PKG_X360_MOBILE_OBSERVED = "emu.x360.mobile"
    private const val ACTIVITY_X360_MOBILE_OBSERVED = "emu.x360.mobile.X360MobileDashboardActivity"
    // The pair that actually ships, read off the device 2026-08-09 via
    // `cmd package resolve-activity` and launch-proven with `am start`. The observed pair above
    // matches nothing installed, which is why X360 Mobile never launched.
    private const val PKG_X360_MOBILE = "emu.x360mobile.com"
    private const val ACTIVITY_X360_MOBILE = "emu.x360mobile.com.SplashActivity"
    private const val PKG_XENDROID = "xendroid.compose"
    private const val ACTIVITY_XENDROID = "xendroid.compose.EmulatorHostActivity"
    private const val PKG_VITA3K = "org.vita3k.emulator"
    private const val ACTIVITY_VITA3K = "org.vita3k.emulator.Emulator"
    private const val PKG_ARMSX2_LEGACY = "come.nanodata.armsx2"
    private const val PKG_ARMSX2_CURRENT = "com.armsx2"
    private const val ACTIVITY_ARMSX2_LEGACY = "com.armsx2.MainActivity"
    private const val ACTIVITY_ARMSX2_CURRENT = "com.armsx2.BootSplashActivity"

    /**
     * The activity ARMSX2 shows the running game in. **Inferred from one build on one device, not a documented contract.**
     * The logcat (Nova, 2026-09-30) has two launches of the same build: the dispatch above is displayed, the
     * emulator's own process then starts `com.armsx2/.Main` with the game's content URI about 3.6 s after the dispatch
     * (from its own uid), and `.Main` is displayed 0.2 s after that. No other ARMSX2 build, and neither the legacy nor the
     * ARMSX3 package, has been observed, so those get no rule. If a build renames this class the launch cover ends on its
     * ceiling instead of on the game, which is a longer cover and not a wrong one.
     */
    private const val ACTIVITY_ARMSX2_CURRENT_GAME = "com.armsx2.Main"
    private const val PKG_CEMU = "info.cemu.cemu"
    private const val ACTIVITY_CEMU = "info.cemu.cemu.emulation.EmulationActivity"
    private val M64PlusPackages = setOf(
        "org.mupen64plusae.v3.fzurita",
        "org.mupen64plusae.v3.fzurita.pro",
        "org.mupen64plusae.v3.alpha",
    )
    private const val ACTIVITY_M64PLUS = "paulscode.android.mupen64plusae.SplashActivity"
    private const val PKG_APS3E = "aenu.aps3e"
    private const val ACTIVITY_APS3E = "aenu.aps3e.EmulatorActivity"
    // Exported launcher activity read from the public izzy2lost/PSX2 AndroidManifest.xml. No incoming
    // Intent is ever read by this activity (MainActivity.java has no getIntent() call): open-shell only.
    private const val PKG_PSX2 = "com.izzy2lost.psx2"
    private const val ACTIVITY_PSX2 = "com.izzy2lost.psx2.MainActivity"
    // Read from the device (com.armsx3 -> com.armsx2.BootSplashActivity) and confirmed against
    // the public ARMSX2/ARMSX3 AndroidManifest.xml, which declares the same class exported with MAIN/
    // LAUNCHER. Actually an RPCS3-based PS3 build wearing the ARMSX2 UI shell -- see EmulatorSeedRegistry.
    private const val PKG_ARMSX3 = "com.armsx3"
    private const val ACTIVITY_ARMSX3 = "com.armsx2.BootSplashActivity"
    // Original Xbox. Sole exported activity per the device read and the public izzy2lost/xemu
    // AndroidManifest.xml (LauncherActivity; MainActivity runs the emulator core in a private :xemu
    // process). DIRECT_PAYLOAD, device-proven 2026-08-11 -- see EmulatorSeedRegistry for the full
    // device evidence (Conker : Live & Reloaded booted through this exact content:// URI contract).
    private const val PKG_X1BOX = "com.izzy2lost.x1box"
    private const val ACTIVITY_X1BOX = "com.izzy2lost.x1box.LauncherActivity"
    private const val PKG_SNES9X_PLUS = "com.explusalpha.Snes9xPlus"
    private const val ACTIVITY_SNES9X_PLUS = "com.imagine.BaseActivity"

    private val RetroArchLegacyFallbackCores = mapOf(
        PlatformId.GB to "gambatte",
        PlatformId.GBC to "gambatte",
        PlatformId.GBA to "mgba",
        PlatformId("megadrive") to "genesis_plus_gx_wide",
        PlatformId.NES to "nestopia",
    )

    private val RetroArchAutoDispatchPlatforms = EmulatorSeedRegistry.retroArchPlatforms() - PlatformId.PS1

    /**
     * @param userId the Android user the dispatching process runs as (see [androidUserIdOf]). Only the RetroArch
     *   paths depend on it; user 0 is the default, so every caller without a [android.content.Context] is unchanged.
     */
    fun profileFor(target: LaunchTarget, userId: Int = 0): LaunchDispatchProfile? {
        retroArchProfileFor(target, userId)?.let { return it }
        val profile = profileForStandalone(target.platformId, target.emulator.packageName) ?: return null
        val installed = target.emulator.installedActivityName
            ?.takeIf { it in alternateActivitiesFor(target.platformId, target.emulator) }
            ?: return profile
        return profile.copy(activityName = installed)
    }

    /**
     * Other activities the same package has answered to in other builds, tried by the component preflight when the
     * profile's own is missing. Same intent, same payload: only the class name drifted.
     *
     * Answered by the effective definitions: the compiled rule below plus, when the player has fetched one, the
     * verified overlay's additions for the same package and platform (appended, never ahead). The overlay can only
     * name a class of a package this build already queries and already has a profile for, and the component preflight
     * still requires the class to be exported and enabled on the device before it is ever launched.
     */
    fun alternateActivitiesFor(platformId: PlatformId, emulator: EmulatorProfile): List<String> =
        if (emulator.isRetroArchCore) {
            emptyList()
        } else {
            ActiveDefinitions.current.activityAlternatesFor(emulator.packageName, platformId)
        }

    /**
     * The compiled-in alternate activities only: the bundled side of the merge that [alternateActivitiesFor] reads.
     * Never consults the overlay, so the definitions can be built from it without a cycle.
     */
    fun bundledAlternateActivitiesFor(platformId: PlatformId, packageName: String): List<String> =
        if (platformId == PlatformId.DREAMCAST && packageName == PKG_FLYCAST) {
            listOf(ACTIVITY_FLYCAST_REICAST)
        } else {
            emptyList()
        }

    fun isDispatchSupported(platformId: PlatformId, emulatorPackageName: String): Boolean =
        profileForStandalone(platformId, emulatorPackageName) != null ||
            (isRetroArchPackage(emulatorPackageName) && platformId in RetroArchAutoDispatchPlatforms)

    /** True when this exact candidate has a concrete intent profile, independent of Auto preference. */
    fun isCandidateDispatchSupported(platformId: PlatformId, emulator: EmulatorProfile): Boolean =
        if (emulator.capability == EmulatorCapability.RECOGNIZE_ONLY || emulator.componentAvailable == false) {
            false
        } else if (emulator.isRetroArchCore) {
            emulator.capability == EmulatorCapability.DIRECT_PAYLOAD &&
                isRetroArchPackage(emulator.packageName) &&
                !emulator.retroArchCore.isNullOrBlank() &&
                emulator.canServe(platformId)
        } else if (emulator.capability == EmulatorCapability.OPEN_ONLY &&
            emulator.locatorForm == LaunchLocatorForm.NONE
        ) {
            !emulator.openShellInferred && (
                emulator.componentAvailable == true ||
                    profileForStandalone(platformId, emulator.packageName) != null
                )
        } else {
            profileForStandalone(platformId, emulator.packageName) != null
        }

    /**
     * True when this exact candidate's dispatch profile actually hands the emulator a game locator,
     * rather than only opening its shell (`LaunchPathStrategy.NO_CONTENT`).
     *
     * The dispatch profile's `pathStrategy` is the source of truth here, not [EmulatorCapability]. Both
     * describe roughly the same fact, but capability is seed metadata about the emulator while
     * pathStrategy is read straight off the profile [IntentLaunchDispatcher] switches on to build the
     * actual intent (`LaunchPathStrategy.NO_CONTENT -> Unit`, i.e. nothing attached) — the two CAN
     * disagree, and only pathStrategy cannot lie about what Mimir actually sends. Device evidence
     * 2026-08-11: `com.armsx3` seeded `EmulatorCapability.OPEN_ONLY` outranked `aenu.aps3e`
     * (`EmulatorCapability.DIRECT_PAYLOAD`) for PS3 Auto because nothing distinguished "has a dispatch
     * profile" from "that profile carries content" (`PlatformEmulatorStatus.kt`).
     */
    fun isPayloadCapable(platformId: PlatformId, emulator: EmulatorProfile): Boolean {
        if (!isCandidateDispatchSupported(platformId, emulator)) return false
        if (emulator.isRetroArchCore) return true // RetroArch dispatch always carries ABSOLUTE_PATH_EXTRA.
        return profileForStandalone(platformId, emulator.packageName)?.pathStrategy != LaunchPathStrategy.NO_CONTENT
    }

    fun targetActivityFor(platformId: PlatformId, emulator: EmulatorProfile): String? =
        if (emulator.isRetroArchCore && isRetroArchPackage(emulator.packageName)) {
            ACTIVITY_RETROARCH
        } else {
            val profileActivity = profileForStandalone(platformId, emulator.packageName)?.activityName
            emulator.installedActivityName
                ?.takeIf { profileActivity != null && it in alternateActivitiesFor(platformId, emulator) }
                ?: profileActivity
        }

    private fun retroArchProfileFor(target: LaunchTarget, userId: Int): LaunchDispatchProfile? {
        val pkg = target.emulator.packageName
        if (!isRetroArchPackage(pkg)) return null
        val core = target.emulator.retroArchCore ?: RetroArchLegacyFallbackCores[target.platformId] ?: return null
        val corePath = rebaseRetroArchPath(retroArchCoreDir(userId) + core + "_libretro_android.so", pkg)
        return LaunchDispatchProfile(
            packageName = pkg,
            activityName = ACTIVITY_RETROARCH,
            pathStrategy = LaunchPathStrategy.ABSOLUTE_PATH_EXTRA,
            pathExtraKey = "ROM",
            stringExtras = mapOf(
                "LIBRETRO" to corePath,
                "CONFIGFILE" to rebaseRetroArchPath(retroArchConfigPath(userId), pkg),
            ),
        )
    }

    private fun profileForStandalone(platformId: PlatformId, emulatorPackageName: String): LaunchDispatchProfile? {
        val platform = platformId.value
        val pkg = emulatorPackageName
        return when {
            (platform == PlatformId.GAMECUBE.value || platform == PlatformId.WII.value) &&
                pkg == PKG_DOLPHIN -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_DOLPHIN,
                action = ACTION_MAIN,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                gameActivityName = ACTIVITY_DOLPHIN_GAME,
            )

            platform == PlatformId.DREAMCAST.value && pkg == PKG_FLYCAST -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_FLYCAST,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.PLAIN_PATH_DATA,
            )

            platform == PlatformId.PSP.value && isPpssppPackage(pkg) -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_PPSSPP,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.FILE_PROVIDER_DATA,
            )

            platform == PlatformId.PS1.value && pkg == PKG_DUCKSTATION -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_DUCKSTATION,
                action = ACTION_MAIN,
                pathStrategy = LaunchPathStrategy.ABSOLUTE_PATH_EXTRA,
                pathExtraKey = "bootPath",
                booleanExtras = mapOf("resumeState" to false),
            )

            platform == PlatformId.PS2.value && isAetherSx2BootPathPackage(pkg) -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_AETHERSX2,
                pathStrategy = LaunchPathStrategy.ABSOLUTE_PATH_EXTRA,
                pathExtraKey = "bootPath",
            )

            platform == PlatformId.DS.value && isMelonDsPackage(pkg) -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_MELONDS,
                action = "$pkg.LAUNCH_ROM",
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.CONTENT_URI_DATA,
                mimeType = "application/octet-stream",
                uriStringExtraKey = "uri",
            )

            platform == PlatformId.THREE_DS.value && pkg == PKG_CITRA -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_CITRA,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.CONTENT_URI_DATA,
                mimeType = "application/octet-stream",
            )

            // Lime3DS (3DS, Citra fork) — same EmulationActivity + VIEW/content/octet-stream contract as Citra.
            platform == PlatformId.THREE_DS.value && (pkg == PKG_LIME3DS || pkg == PKG_AZAHAR) -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_LIME3DS,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.CONTENT_URI_DATA,
                mimeType = "application/octet-stream",
            )

            // Switch — Eden / Citron (Yuzu-lineage). Verified VIEW+content+octet-stream → EmulationActivity.
            platform == PlatformId.SWITCH.value && pkg in EdenPackages -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_EDEN,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.CONTENT_URI_DATA,
                mimeType = "application/octet-stream",
            )

            platform == PlatformId.SWITCH.value && pkg == PKG_CITRON -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_CITRON,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.CONTENT_URI_DATA,
                mimeType = "application/octet-stream",
            )

            platform == PlatformId.PS2.value && pkg == PKG_ARMSX2_LEGACY -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_ARMSX2_LEGACY,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.ABSOLUTE_PATH_EXTRA,
                pathExtraKey = "path",
            )

            platform == PlatformId.PS2.value && pkg == PKG_ARMSX2_CURRENT -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_ARMSX2_CURRENT,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                gameActivityName = ACTIVITY_ARMSX2_CURRENT_GAME,
            )

            // PSX2 reads no incoming Intent (verified against MainActivity.java, no getIntent() call
            // anywhere in the class): open the shell, carry no ROM locator, exactly like X360 Mobile.
            platform == PlatformId.PS2.value && pkg == PKG_PSX2 -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_PSX2,
                action = ACTION_MAIN,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.NO_CONTENT,
            )

            platform == "nintendo_wii_u" && pkg == PKG_CEMU -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_CEMU,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                mimeType = "*/*",
            )

            platform == PlatformId.N64.value && pkg in M64PlusPackages -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_M64PLUS,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                mimeType = "application/octet-stream",
                // Alpha 246's Splash handler calls data.getPath(), losing the content authority.
                // Its no-data branch forwards this complete URI extra to GalleryActivity instead.
                uriStringExtraKey = if (pkg == "org.mupen64plusae.v3.alpha")
                    "paulscode.android.mupen64plusae.ActivityHelper.Keys.ROM_PATH" else null,
                attachUriAsData = pkg != "org.mupen64plusae.v3.alpha",
            )

            platform == "ps3" && pkg == PKG_APS3E -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_APS3E,
                action = ACTION_APS3E,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                uriStringExtraKey = "iso_uri",
            )

            platform == PlatformId.SNES.value && pkg == PKG_SNES9X_PLUS -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_SNES9X_PLUS,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                mimeType = "application/octet-stream",
            )

            platform == "ps3" && pkg == PKG_RPCSX -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_RPCSX,
                action = ACTION_MAIN,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.NO_CONTENT,
            )

            // ARMSX3 (RPCS3-based, wearing the ARMSX2 UI shell) -- open the shell, no PS3 firmware or
            // game-boot proof for this specific app. Same honest shape as RPCSX above.
            platform == "ps3" && pkg == PKG_ARMSX3 -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_ARMSX3,
                action = ACTION_MAIN,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.NO_CONTENT,
            )

            platform == "xbox360" && pkg == PKG_X360_MOBILE_OBSERVED -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_X360_MOBILE_OBSERVED,
                action = ACTION_MAIN,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.NO_CONTENT,
            )

            platform == "xbox360" && pkg == PKG_X360_MOBILE -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_X360_MOBILE,
                action = ACTION_MAIN,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.NO_CONTENT,
            )

            // Code-proven from the installed APK's classes.dex (xendroid.compose), not yet
            // device-certified -- see EmulatorSeedRegistry for the full evidence chain and why the
            // earlier "device proof" for this exact package was invalid and reverted. The game travels as
            // the String extra "game_uri" (a content:// URI or a bare absolute path -- EmulatorHostActivity
            // resolves either), carried via the real custom action instead of Intent data, because the
            // VIEW filter's missing <data> element means a content:// payload set as intent data can never
            // reach it. X360 Mobile (above) stays NO_CONTENT/OPEN_ONLY -- nothing is proven for it.
            platform == "xbox360" && pkg == PKG_XENDROID -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_XENDROID,
                action = ACTION_XENDROID,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.SHAREABLE_URI_DATA,
                uriStringExtraKey = "game_uri",
            )

            // Original Xbox -- device proof. LauncherActivity forwards
            // intent.data as a content:// dvdUri to MainActivity when BIOS/HDD prerequisites are
            // already configured; that contract booted 'Conker : Live & Reloaded' on the Odin
            // -- see EmulatorSeedRegistry for the full device evidence.
            platform == "xbox" && pkg == PKG_X1BOX -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_X1BOX,
                action = ACTION_VIEW,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.CONTENT_URI_DATA,
                mimeType = "application/octet-stream",
            )

            platform == "sony_playstation_vita" && pkg == PKG_VITA3K -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = ACTIVITY_VITA3K,
                action = ACTION_MAIN,
                addDefaultCategory = true,
                pathStrategy = LaunchPathStrategy.NO_CONTENT,
            )

            // GameNative (PC Games, R-2b) -- two-sided proven contract (both the shortcut producer and
            // the intent consumer read from the installed build's own dex): LAUNCH_GAME + explicit MainActivity +
            // typed app_id/game_source extras read from the shortcut FILE'S CONTENT, not its path. This
            // is a MARKER profile only -- it exists so isCandidateDispatchSupported/isPayloadCapable/
            // isDispatchSupported correctly recognize GameNative as a real payload-carrying candidate.
            // The actual intent is built by IntentLaunchDispatcher.buildGameNativeIntent, which
            // intercepts BEFORE profileFor() is ever consulted for this package, because GameNative's
            // proven flags (NEW_TASK|CLEAR_TOP only) conflict with buildProfileIntent's hardcoded
            // ResidentLaunchFlags -- see LaunchPathStrategy.DISPATCHER_OWNED_PAYLOAD below.
            platform == "pc_games" && pkg == GameNativeLaunchRoute.PACKAGE_NAME -> LaunchDispatchProfile(
                packageName = pkg,
                activityName = GameNativeLaunchRoute.ACTIVITY_NAME,
                action = GameNativeLaunchRoute.ACTION_LAUNCH_GAME,
                pathStrategy = LaunchPathStrategy.DISPATCHER_OWNED_PAYLOAD,
            )

            else -> null
        }
    }

    private fun isPpssppPackage(packageName: String): Boolean =
        packageName == PKG_PPSSPP || packageName == PKG_PPSSPP_GOLD

    private fun isAetherSx2BootPathPackage(packageName: String): Boolean =
        packageName == PKG_AETHERSX2 || packageName == PKG_NETHERSX2_TURNIP

    private fun isMelonDsPackage(packageName: String): Boolean =
        packageName == PKG_MELONDS ||
            packageName == PKG_MELONDS_NIGHTLY ||
            packageName == PKG_MELONDS_DUAL

    private fun isRetroArchPackage(packageName: String): Boolean =
        packageName == PKG_RETROARCH_AARCH64 ||
            packageName == PKG_RETROARCH_UNIVERSAL ||
            packageName == PKG_RETROARCH_RA32

    /**
     * RetroArch's own files, spelled for the Android USER the launch is dispatched from. Both roots carry the user id
     * (`/storage/emulated/<id>` for shared storage, `/data/user/<id>` for app-private data), and `0` is only the owner's
     * profile: in a secondary user or a work profile the hard-coded `0` named a config and a core that are not there, and
     * RetroArch opened on a black screen (Cocoon the earlier update, iiSU the earlier update, ES-DE #2005). Spelled for the stock package; the
     * installed build's package is substituted by [rebaseRetroArchPath].
     */
    private fun retroArchConfigPath(userId: Int): String =
        "/storage/emulated/$userId/Android/data/$PKG_RETROARCH_AARCH64/files/retroarch.cfg"

    private fun retroArchCoreDir(userId: Int): String = "/data/user/$userId/$PKG_RETROARCH_AARCH64/cores/"

    private fun rebaseRetroArchPath(path: String, packageName: String): String =
        if (packageName == PKG_RETROARCH_AARCH64) path else path.replace(PKG_RETROARCH_AARCH64, packageName)
}

internal data class LaunchDispatchProfile(
    val packageName: String,
    val activityName: String,
    val action: String? = null,
    val addDefaultCategory: Boolean = false,
    val pathStrategy: LaunchPathStrategy,
    val pathExtraKey: String? = null,
    val booleanExtras: Map<String, Boolean> = emptyMap(),
    val stringExtras: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val uriStringExtraKey: String? = null,
    /** False only for receivers that consume the complete URI extra; ClipData still grants access. */
    val attachUriAsData: Boolean = true,
    /**
     * The activity the emulator shows the RUNNING GAME in, when it differs from [activityName] (the dispatch activity,
     * which is a startup handler or a boot splash that hands over to the game a moment later). Null for every profile
     * that dispatches straight into its game screen, which is nearly all of them. The launch cover
     * ends when the game opens rather than at intent dispatch, covering the emulator's loading
     * between the two, so it reads this and only this: a profile without one gets no cover. See [LaunchCoverRule].
     */
    val gameActivityName: String? = null,
)

internal enum class LaunchPathStrategy {
    ABSOLUTE_PATH_EXTRA,
    FILE_PROVIDER_DATA,
    CONTENT_URI_DATA,
    /** Existing SAF content URI, or a path converted through Mimir's private FileProvider. */
    SHAREABLE_URI_DATA,
    PLAIN_PATH_DATA,
    NO_CONTENT,

    /**
     * [IntentLaunchDispatcher] builds this profile's ENTIRE intent itself, from typed data derived from
     * the locator's file CONTENT (see `buildGameNativeIntent`'s per-package intercept in `buildIntent()`,
     * which fires before `buildProfileIntent`'s generic switch is ever reached for this package).
     * Distinct from [NO_CONTENT] only in that this candidate genuinely DOES carry a real game payload --
     * [ShippingLaunchProfiles.isPayloadCapable] must say so, unlike a true open-shell target. See the
     * `com.armsx3`/`aenu.aps3e` history above [isPayloadCapable] for why that distinction is load-bearing,
     * not cosmetic.
     */
    DISPATCHER_OWNED_PAYLOAD,
}
