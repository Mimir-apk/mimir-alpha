package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShippingLaunchProfilesTest {

    @Test
    fun mupenAlphaUsesCompleteUriExtraWhileOtherVariantsKeepData() {
        val alpha = requireNotNull(profile(PlatformId.N64, "org.mupen64plusae.v3.alpha"))
        assertFalse(alpha.attachUriAsData)
        assertEquals("paulscode.android.mupen64plusae.ActivityHelper.Keys.ROM_PATH", alpha.uriStringExtraKey)
        assertEquals(LaunchPathStrategy.SHAREABLE_URI_DATA, alpha.pathStrategy)
        for (pkg in listOf("org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro")) {
            val other = requireNotNull(profile(PlatformId.N64, pkg))
            assertTrue(other.attachUriAsData)
            assertNull(other.uriStringExtraKey)
        }
    }

    @Test
    fun currentOfficialDolphinUsesItsExportedStartupHandlerUriContract() {
        val gameCube = profile(PlatformId.GAMECUBE, "org.dolphinemu.dolphinemu")
        val wii = profile(PlatformId.WII, "org.dolphinemu.dolphinemu")

        listOf(gameCube, wii).forEach { route ->
            assertEquals("android.intent.action.MAIN", route!!.action)
            assertEquals("org.dolphinemu.dolphinemu.ui.main.MainActivity", route.activityName)
            assertEquals(LaunchPathStrategy.SHAREABLE_URI_DATA, route.pathStrategy)
        }
        assertNull(profile(PlatformId.WII, "org.dolphinemu.mmjr"))
        val dolphin = EmulatorSeedRegistry.seedsFor(PlatformId.GAMECUBE)
            .first { it.packageName == "org.dolphinemu.dolphinemu" }
        assertEquals(EmulatorCapability.DIRECT_PAYLOAD, dolphin.capability)
    }

    @Test
    fun duckStationAndAetherSx2UseBootPathRoutes() {
        val duck = profile(PlatformId.PS1, "com.github.stenzek.duckstation")
        assertEquals("android.intent.action.MAIN", duck!!.action)
        assertEquals("com.github.stenzek.duckstation.EmulationActivity", duck.activityName)
        assertEquals("bootPath", duck.pathExtraKey)
        assertEquals(false, duck.booleanExtras["resumeState"])

        val nether = profile(PlatformId.PS2, "xyz.aethersx2.android")
        assertEquals("xyz.aethersx2.android.EmulationActivity", nether!!.activityName)
        assertEquals("bootPath", nether.pathExtraKey)

        val turnip = profile(PlatformId.PS2, "xyz.aethersx2.cturnip")
        assertEquals("xyz.aethersx2.android.EmulationActivity", turnip!!.activityName)
        assertEquals("bootPath", turnip.pathExtraKey)
    }

    @Test
    fun ppssppGoldTargetsSharedPpssppActivityWithDataRoute() {
        val profile = profile(PlatformId.PSP, "org.ppsspp.ppssppgold")
        assertEquals("android.intent.action.VIEW", profile!!.action)
        assertEquals("org.ppsspp.ppsspp.PpssppActivity", profile.activityName)
        assertEquals(LaunchPathStrategy.FILE_PROVIDER_DATA, profile.pathStrategy)
        assertEquals(true, profile.addDefaultCategory)
    }

    @Test
    fun melonDsAndCitraUseSafContentDataRoutes() {
        val melon = profile(PlatformId.DS, "me.magnum.melonds")
        assertEquals("me.magnum.melonds.LAUNCH_ROM", melon!!.action)
        assertEquals("me.magnum.melonds.ui.emulator.EmulatorActivity", melon.activityName)
        assertEquals(LaunchPathStrategy.CONTENT_URI_DATA, melon.pathStrategy)
        assertEquals("application/octet-stream", melon.mimeType)
        assertEquals("uri", melon.uriStringExtraKey)

        val nightly = profile(PlatformId.DS, "me.magnum.melonds.nightly")
        assertEquals("me.magnum.melonds.nightly.LAUNCH_ROM", nightly!!.action)
        assertEquals(LaunchPathStrategy.CONTENT_URI_DATA, nightly.pathStrategy)

        val dual = profile(PlatformId.DS, "me.magnum.melondualds")
        assertEquals("me.magnum.melondualds.LAUNCH_ROM", dual!!.action)
        assertEquals("me.magnum.melonds.ui.emulator.EmulatorActivity", dual.activityName)
        assertEquals(LaunchPathStrategy.CONTENT_URI_DATA, dual.pathStrategy)

        val citra = profile(PlatformId.THREE_DS, "org.citra.citra_emu")
        assertEquals("android.intent.action.VIEW", citra!!.action)
        assertEquals("org.citra.citra_emu.activities.EmulationActivity", citra.activityName)
        assertEquals(LaunchPathStrategy.CONTENT_URI_DATA, citra.pathStrategy)
        assertEquals("application/octet-stream", citra.mimeType)
    }

    @Test
    fun auditedDirectFamiliesUseExactComponentsAndPayloadEnvelopes() {
        val expected = listOf(
            ExpectedRoute(PlatformId.PS2, "come.nanodata.armsx2", "com.armsx2.MainActivity",
                "android.intent.action.VIEW", LaunchPathStrategy.ABSOLUTE_PATH_EXTRA, null, null, "path"),
            ExpectedRoute(PlatformId.PS2, "com.armsx2", "com.armsx2.BootSplashActivity",
                "android.intent.action.VIEW", LaunchPathStrategy.SHAREABLE_URI_DATA, null, null),
            ExpectedRoute(PlatformId("nintendo_wii_u"), "info.cemu.cemu",
                "info.cemu.cemu.emulation.EmulationActivity", "android.intent.action.VIEW",
                LaunchPathStrategy.SHAREABLE_URI_DATA, "*/*", null),
            ExpectedRoute(PlatformId.N64, "org.mupen64plusae.v3.fzurita",
                "paulscode.android.mupen64plusae.SplashActivity", "android.intent.action.VIEW",
                LaunchPathStrategy.SHAREABLE_URI_DATA, "application/octet-stream", null),
            ExpectedRoute(PlatformId("ps3"), "aenu.aps3e", "aenu.aps3e.EmulatorActivity",
                "aenu.intent.action.APS3E", LaunchPathStrategy.SHAREABLE_URI_DATA, null, "iso_uri"),
            ExpectedRoute(PlatformId.SNES, "com.explusalpha.Snes9xPlus", "com.imagine.BaseActivity",
                "android.intent.action.VIEW", LaunchPathStrategy.SHAREABLE_URI_DATA,
                "application/octet-stream", null),
            ExpectedRoute(PlatformId.SWITCH, "dev.legacy.eden_emulator",
                "org.yuzu.yuzu_emu.activities.EmulationActivity", "android.intent.action.VIEW",
                LaunchPathStrategy.CONTENT_URI_DATA, "application/octet-stream", null),
            ExpectedRoute(PlatformId.SWITCH, "dev.legacy.eden_emulator.nightly",
                "org.yuzu.yuzu_emu.activities.EmulationActivity", "android.intent.action.VIEW",
                LaunchPathStrategy.CONTENT_URI_DATA, "application/octet-stream", null),
            ExpectedRoute(PlatformId.SWITCH, "com.miHoYo.Yuanshen.nightly",
                "org.yuzu.yuzu_emu.activities.EmulationActivity", "android.intent.action.VIEW",
                LaunchPathStrategy.CONTENT_URI_DATA, "application/octet-stream", null),
            ExpectedRoute(PlatformId.THREE_DS, "org.azahar_emu.azahar",
                "org.citra.citra_emu.activities.EmulationActivity", "android.intent.action.VIEW",
                LaunchPathStrategy.CONTENT_URI_DATA, "application/octet-stream", null),
        )

        expected.forEach { route ->
            val actual = profile(route.platformId, route.packageName)
            assertEquals("${route.platformId}/${route.packageName} component", route.activity, actual!!.activityName)
            assertEquals("${route.platformId}/${route.packageName} action", route.action, actual.action)
            assertEquals("${route.platformId}/${route.packageName} locator", route.pathStrategy, actual.pathStrategy)
            assertEquals("${route.platformId}/${route.packageName} MIME", route.mimeType, actual.mimeType)
            assertEquals("${route.platformId}/${route.packageName} URI extra", route.uriExtra, actual.uriStringExtraKey)
            assertEquals("${route.platformId}/${route.packageName} path extra", route.pathExtra, actual.pathExtraKey)
            assertTrue("${route.platformId}/${route.packageName} uses DEFAULT", actual.addDefaultCategory)
        }
    }

    @Test
    fun everyEdenVariantRetainsAnIndependentExactProfile() {
        val packages = listOf(
            "dev.eden.eden_emulator",
            "dev.eden.eden_emulator.nightly",
            "com.miHoYo.Yuanshen.nightly",
            "dev.legacy.eden_emulator",
            "dev.legacy.eden_emulator.nightly",
        )

        packages.forEach { pkg ->
            val route = profile(PlatformId.SWITCH, pkg)
            assertEquals(pkg, route!!.packageName)
            assertEquals(LaunchPathStrategy.CONTENT_URI_DATA, route.pathStrategy)
        }
        assertEquals(packages.toSet(), packages.map { profile(PlatformId.SWITCH, it)!!.packageName }.toSet())
    }

    @Test
    fun retroArchCoreTargetsUseShippingLibretroExtras() {
        val gba = profile(
            platformId = PlatformId.GBA,
            packageName = "com.retroarch.aarch64",
            retroArchCore = "mgba",
        )

        assertNull(gba!!.action)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", gba.activityName)
        assertEquals(LaunchPathStrategy.ABSOLUTE_PATH_EXTRA, gba.pathStrategy)
        assertEquals("ROM", gba.pathExtraKey)
        assertEquals(
            "/data/user/0/com.retroarch.aarch64/cores/mgba_libretro_android.so",
            gba.stringExtras["LIBRETRO"],
        )
        assertEquals(
            "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
            gba.stringExtras["CONFIGFILE"],
        )
    }

    @Test
    fun retroArchPathsRebaseToTheInstalledBuildPackage() {
        val gb = profile(
            platformId = PlatformId.GB,
            packageName = "com.retroarch",
            retroArchCore = "gambatte",
        )

        assertEquals(
            "/data/user/0/com.retroarch/cores/gambatte_libretro_android.so",
            gb!!.stringExtras["LIBRETRO"],
        )
        assertEquals(
            "/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg",
            gb.stringExtras["CONFIGFILE"],
        )
    }

    @Test
    fun selectedAlternateRetroArchCoreOwnsTheExactLibretroArtifactPath() {
        val vbaM = profile(
            platformId = PlatformId.GBA,
            packageName = "com.retroarch.aarch64",
            retroArchCore = "vbam",
        )

        assertEquals(
            "/data/user/0/com.retroarch.aarch64/cores/vbam_libretro_android.so",
            vbaM!!.stringExtras["LIBRETRO"],
        )
        assertEquals("ROM", vbaM.pathExtraKey)
        assertEquals(LaunchPathStrategy.ABSOLUTE_PATH_EXTRA, vbaM.pathStrategy)
    }

    @Test
    fun unsupportedSeedRoutesRemainUnclaimedByThisSlice() {
        assertNull(profile(PlatformId.PS1, "com.retroarch"))
        assertNull(profile(PlatformId.PS2, "skmp.duckstation"))
        assertTrue(ShippingLaunchProfiles.isDispatchSupported(PlatformId.GBA, "com.retroarch.aarch64"))
        assertTrue(!ShippingLaunchProfiles.isDispatchSupported(PlatformId.PS1, "com.retroarch.aarch64"))
    }

    @Test
    fun everySeedCandidateHasAnExactDispatchProfile() {
        EmulatorSeedRegistry.allSeedPlatforms().forEach { platform ->
            EmulatorSeedRegistry.candidatesFor(platform) { true }.forEach { candidate ->
                val supported = ShippingLaunchProfiles.isCandidateDispatchSupported(platform, candidate)
                if (candidate.capability == EmulatorCapability.RECOGNIZE_ONLY) {
                    assertTrue("${platform.value}/${candidate.id.value} recognition row leaked into dispatch", !supported)
                } else {
                    assertTrue("${platform.value}/${candidate.id.value} must not be selectable without dispatch", supported)
                }
            }
        }
    }

    @Test
    fun experimentalShellProfilesCarryNoGameContent() {
        val ps3 = profile(PlatformId("ps3"), "net.rpcsx")
        val xbox360 = profile(PlatformId("xbox360"), "emu.x360mobile.com")

        assertEquals(LaunchPathStrategy.NO_CONTENT, ps3!!.pathStrategy)
        assertEquals("net.rpcsx.MainActivity", ps3.activityName)
        assertEquals(LaunchPathStrategy.NO_CONTENT, xbox360!!.pathStrategy)
        val vita = profile(PlatformId("sony_playstation_vita"), "org.vita3k.emulator")
        assertEquals(LaunchPathStrategy.NO_CONTENT, vita!!.pathStrategy)
        assertEquals("emu.x360mobile.com.SplashActivity", xbox360.activityName)
    }

    /**
     * Both installed Xbox 360 shells, pinned by the exact package/activity
     * pairs read off the Odin on 2026-08-09. Before that reading the registry knew only
     * "emu.x360.mobile" -- an id nothing installs -- so neither shell was recognised and neither
     * could launch. Recognition is the whole fix here: the package id is the match key.
     *
     * X360 Mobile stays OPEN_ONLY/NO_CONTENT -- no ROM-payload contract is proven for it. Xendroid is
     * DIRECT_PAYLOAD, code-proven (not yet device-certified) by decompiling the installed APK's
     * classes.dex: see the isolation test below and EmulatorSeedRegistry for the full evidence.
     */
    @Test
    fun installedXbox360ShellsAreRecognizedAndDispatchToTheirRealActivities() {
        val x360Mobile = EmulatorSeedRegistry
            .candidatesFor(PlatformId("xbox360")) { it == "emu.x360mobile.com" }
            .first { it.packageName == "emu.x360mobile.com" }
        assertEquals("emu.x360mobile.com capability", EmulatorCapability.OPEN_ONLY, x360Mobile.capability)
        assertTrue(
            "emu.x360mobile.com must be dispatchable",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId("xbox360"), x360Mobile),
        )
        val x360MobileDispatch = requireNotNull(profile(PlatformId("xbox360"), "emu.x360mobile.com")) {
            "emu.x360mobile.com has no dispatch profile"
        }
        assertEquals(
            "emu.x360mobile.com activity",
            "emu.x360mobile.com.SplashActivity",
            x360MobileDispatch.activityName,
        )
        // No proven ROM-payload contract; claiming one is how a launch silently no-ops after
        // appearing to succeed.
        assertEquals("emu.x360mobile.com path strategy", LaunchPathStrategy.NO_CONTENT, x360MobileDispatch.pathStrategy)

        val xendroid = EmulatorSeedRegistry
            .candidatesFor(PlatformId("xbox360")) { it == "xendroid.compose" }
            .first { it.packageName == "xendroid.compose" }
        assertEquals("xendroid.compose capability", EmulatorCapability.DIRECT_PAYLOAD, xendroid.capability)
        assertTrue(
            "xendroid.compose must be dispatchable",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId("xbox360"), xendroid),
        )
        val xendroidDispatch = requireNotNull(profile(PlatformId("xbox360"), "xendroid.compose")) {
            "xendroid.compose has no dispatch profile"
        }
        assertEquals(
            "xendroid.compose activity",
            "xendroid.compose.EmulatorHostActivity",
            xendroidDispatch.activityName,
        )
        assertEquals("xendroid.compose action", "xendroid.intent.action.xendroid", xendroidDispatch.action)
        assertEquals(
            "xendroid.compose path strategy",
            LaunchPathStrategy.SHAREABLE_URI_DATA,
            xendroidDispatch.pathStrategy,
        )
        assertEquals("xendroid.compose URI extra", "game_uri", xendroidDispatch.uriStringExtraKey)
    }

    /**
     * The actual defect this promotion fixes, pinned in isolation from the broader profile-shape
     * assertions above so a partial revert still fails here. The reported defect on 2026-08-11
     * was that X360/xendroid did not launch directly into the game.
     *
     * A FIRST promotion attempt here was wrong and was reverted: its "device proof" dispatched with an
     * explicit component (`am start -n`), which bypasses intent-filter matching entirely, so
     * EmulatorHostActivity opened only because it was named -- loading its Vulkan driver on every start
     * (which happens regardless of whether a game was found) was misread as a game booting. The
     * manifest independently confirms a content:// URI could never have reached that activity as intent
     * DATA either way: its android.intent.action.VIEW filter carries no <data> element (device-confirmed,
     * `cmd package query-activities -a VIEW -d content://x/y` returns zero xendroid matches).
     *
     * This time the contract comes from decompiling the installed APK's classes.dex, not a device test:
     * EmulatorHostActivity.onCreate hands getIntent() to a reader that checks the String extra
     * "game_uri" first (then "AutoStartFile", then Intent.getData() as a last resort) and finishes
     * without booting if none resolve to a file. The SAME "game_uri" key, with the SAME custom action,
     * is what xendroid's own internal UI builds when a player picks a game in its own library --
     * producer and consumer agree.
     */
    @Test
    fun xendroidCarriesTheGameAsAGameUriExtraOnItsOwnCustomAction() {
        val dispatch = requireNotNull(profile(PlatformId("xbox360"), "xendroid.compose")) {
            "xendroid.compose has no dispatch profile"
        }
        assertEquals(
            "xendroid must target EmulatorHostActivity, not the MainActivity launcher shell",
            "xendroid.compose.EmulatorHostActivity",
            dispatch.activityName,
        )
        assertEquals(
            "xendroid must dispatch its own custom action, not a generic MAIN/VIEW",
            "xendroid.intent.action.xendroid",
            dispatch.action,
        )
        assertEquals(
            "the game must ride the proven \"game_uri\" String extra",
            "game_uri",
            dispatch.uriStringExtraKey,
        )
        assertFalse(
            "relying on Intent data alone was the reverted mistake -- the VIEW filter has no <data> to match",
            dispatch.pathStrategy == LaunchPathStrategy.CONTENT_URI_DATA,
        )
    }

    /**
     * X1 BOX (original Xbox) promotion: device proof -- launching
     * 'Conker : Live & Reloaded' through this exact content:// URI contract on LauncherActivity
     * produced the com.izzy2lost.x1box:xemu core process, MainActivity foregrounded (the emulator, not
     * the launcher), and live xemu rendering ("xemu-android: refresh frame=3000 tex=2 ...
     * size=640x480", "present drawable=1920x1080", plus live xemu-gpu/xemu-fpu/xemu-rw counters).
     * Pinned here because promoting X1 BOX out of newlySeededOpenShellsCarryNoGameContent below would
     * otherwise leave its DIRECT_PAYLOAD dispatch shape with no coverage.
     */
    @Test
    fun x1BoxUsesItsProvenContentUriDirectPayloadContract() {
        val dispatch = requireNotNull(profile(PlatformId("xbox"), "com.izzy2lost.x1box")) {
            "com.izzy2lost.x1box has no dispatch profile"
        }
        assertEquals("com.izzy2lost.x1box activity", "com.izzy2lost.x1box.LauncherActivity", dispatch.activityName)
        assertEquals("com.izzy2lost.x1box action", "android.intent.action.VIEW", dispatch.action)
        assertEquals("com.izzy2lost.x1box path strategy", LaunchPathStrategy.CONTENT_URI_DATA, dispatch.pathStrategy)
        assertEquals("com.izzy2lost.x1box mime", "application/octet-stream", dispatch.mimeType)
        assertTrue("com.izzy2lost.x1box must use category.DEFAULT", dispatch.addDefaultCategory)

        val candidate = EmulatorSeedRegistry.candidatesFor(PlatformId("xbox")) { it == "com.izzy2lost.x1box" }
            .first { it.packageName == "com.izzy2lost.x1box" }
        assertEquals(EmulatorCapability.DIRECT_PAYLOAD, candidate.capability)
        assertTrue(
            "com.izzy2lost.x1box must be dispatchable",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId("xbox"), candidate),
        )
    }

    // com.izzy2lost.x1box (xbox) was OPEN_ONLY here until the device proof
    // promoted it to DIRECT_PAYLOAD (see EmulatorSeedRegistry and
    // x1BoxUsesItsProvenContentUriDirectPayloadContract above) -- it is no longer a declared-open
    // shell and does not belong in this list.
    @Test
    fun declaredOpenShellSeedsRemainDispatchable() {
        val declaredShells = listOf(
            PlatformId("ps3") to "net.rpcsx",
            PlatformId("xbox360") to "emu.x360mobile.com",
            PlatformId("sony_playstation_vita") to "org.vita3k.emulator",
            PlatformId.PS2 to "com.izzy2lost.psx2",
            PlatformId("ps3") to "com.armsx3",
        )
        declaredShells.forEach { (platformId, packageName) ->
            val candidate = EmulatorSeedRegistry.candidatesFor(platformId) { it == packageName }
                .first { it.packageName == packageName }
            assertEquals(EmulatorCapability.OPEN_ONLY, candidate.capability)
            assertFalse(candidate.openShellInferred)
            assertTrue(
                "${platformId.value}/$packageName",
                ShippingLaunchProfiles.isCandidateDispatchSupported(platformId, candidate),
            )
        }
    }

    /**
     * PSX2 and ARMSX3 newly installed and seeded. Neither has a proven
     * ROM-payload contract on this device, so both are OPEN_ONLY/NO_CONTENT -- claiming a payload contract without one (see
     * aenu.aps3e). Package/activity pairs are read from each emulator's own public
     * AndroidManifest.xml (izzy2lost/PSX2, ARMSX2/ARMSX3).
     *
     * X1 BOX (original Xbox) was seeded alongside these two OPEN_ONLY/NO_CONTENT, but a separate test on
     * 2026-08-11 device-proved its content:// URI contract on the Odin (Conker :
     * Live & Reloaded booted through LauncherActivity into the xemu core) and promoted it to
     * DIRECT_PAYLOAD -- it no longer belongs in this "carries no game content" list. See
     * x1BoxUsesItsProvenContentUriDirectPayloadContract above for its pinned DIRECT_PAYLOAD shape.
     */
    @Test
    fun newlySeededOpenShellsCarryNoGameContent() {
        val expected = listOf(
            Triple(PlatformId.PS2, "com.izzy2lost.psx2", "com.izzy2lost.psx2.MainActivity"),
            Triple(PlatformId("ps3"), "com.armsx3", "com.armsx2.BootSplashActivity"),
        )
        expected.forEach { (platformId, packageName, activityName) ->
            val dispatch = requireNotNull(profile(platformId, packageName)) {
                "$packageName has no dispatch profile"
            }
            assertEquals("$packageName activity", activityName, dispatch.activityName)
            assertEquals("$packageName action", "android.intent.action.MAIN", dispatch.action)
            assertEquals("$packageName path strategy", LaunchPathStrategy.NO_CONTENT, dispatch.pathStrategy)
        }
    }

    @Test
    fun retroArchCoreWithoutDirectPayloadIsUnsupported() {
        val recognizeOnlyCore = EmulatorProfile(
            id = EmulatorId("gba.retroarch.aarch64.mgba"),
            packageName = "com.retroarch.aarch64",
            displayName = "RetroArch / mGBA",
            retroArchCore = "mgba",
            installed = true,
            capability = EmulatorCapability.RECOGNIZE_ONLY,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(PlatformId.GBA),
        )
        assertFalse(ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.GBA, recognizeOnlyCore))
    }

    @Test
    fun retroArchCoreRequiresDirectPayloadAcrossCapabilityShapes() {
        val base = EmulatorProfile(
            id = EmulatorId("gba.retroarch.aarch64.mgba"),
            packageName = "com.retroarch.aarch64",
            displayName = "RetroArch / mGBA",
            retroArchCore = "mgba",
            installed = true,
            componentAvailable = true,
            supportedPlatforms = setOf(PlatformId.GBA),
        )

        val openOnlyNone = base.copy(
            capability = EmulatorCapability.OPEN_ONLY,
            locatorForm = LaunchLocatorForm.NONE,
            openShellInferred = false,
        )
        assertFalse(
            "OPEN_ONLY/NONE must not bypass DIRECT_PAYLOAD",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.GBA, openOnlyNone),
        )

        val openOnlyFilePath = base.copy(
            capability = EmulatorCapability.OPEN_ONLY,
            locatorForm = LaunchLocatorForm.FILE_PATH,
        )
        assertFalse(
            "OPEN_ONLY/FILE_PATH must not bypass DIRECT_PAYLOAD",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.GBA, openOnlyFilePath),
        )

        val recognizeOnly = base.copy(
            capability = EmulatorCapability.RECOGNIZE_ONLY,
            locatorForm = LaunchLocatorForm.FILE_PATH,
        )
        assertFalse(
            "RECOGNIZE_ONLY RetroArch core must stay closed",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.GBA, recognizeOnly),
        )

        val directPayload = base.copy(
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
        )
        assertTrue(
            "DIRECT_PAYLOAD RetroArch core with proof remains dispatchable",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.GBA, directPayload),
        )
    }

    /**
     * GameNative's marker profile must make it a real, payload-carrying Auto/dispatch candidate
     * for pc_games -- not just "recognized". [ShippingLaunchProfiles.isPayloadCapable] is the one this
     * codebase has been burned by getting wrong before (see the com.armsx3/aenu.aps3e history on that
     * function): a DISPATCHER_OWNED_PAYLOAD profile must read as payload-capable exactly like a URI
     * route would, even though buildProfileIntent never actually attaches anything for it.
     */
    @Test
    fun gameNativeCarriesADirectPayloadContractForPcGames() {
        val dispatch = requireNotNull(profile(PlatformId("pc_games"), "app.gamenative")) {
            "app.gamenative has no dispatch profile"
        }
        assertEquals("app.gamenative activity", "app.gamenative.MainActivity", dispatch.activityName)
        assertEquals("app.gamenative action", "app.gamenative.LAUNCH_GAME", dispatch.action)
        assertEquals(
            "app.gamenative path strategy",
            LaunchPathStrategy.DISPATCHER_OWNED_PAYLOAD,
            dispatch.pathStrategy,
        )
        assertFalse("GameNative's producer never adds category.DEFAULT", dispatch.addDefaultCategory)

        val candidate = EmulatorSeedRegistry.candidatesFor(PlatformId("pc_games")) { it == "app.gamenative" }
            .first { it.packageName == "app.gamenative" }
        assertEquals(EmulatorCapability.DIRECT_PAYLOAD, candidate.capability)
        assertEquals(LaunchLocatorForm.FILE_PATH, candidate.locatorForm)
        assertTrue(
            "app.gamenative must be dispatchable",
            ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId("pc_games"), candidate),
        )
        assertTrue(
            "app.gamenative genuinely carries a game payload, unlike a NO_CONTENT shell",
            ShippingLaunchProfiles.isPayloadCapable(PlatformId("pc_games"), candidate),
        )
        assertTrue(
            "app.gamenative must report dispatch-supported at the platform/package level too",
            ShippingLaunchProfiles.isDispatchSupported(PlatformId("pc_games"), "app.gamenative"),
        )
    }

    @Test
    fun retroArchPathsNameTheUserTheLaunchIsDispatchedFrom() {
        val gba = profile(
            platformId = PlatformId.GBA,
            packageName = "com.retroarch.aarch64",
            retroArchCore = "mgba",
            userId = 10,
        )

        assertEquals(
            "app-private data is per user: a secondary user's RetroArch keeps its cores under /data/user/10",
            "/data/user/10/com.retroarch.aarch64/cores/mgba_libretro_android.so",
            gba!!.stringExtras["LIBRETRO"],
        )
        assertEquals(
            "and its shared storage is /storage/emulated/10, not the owner's /storage/emulated/0",
            "/storage/emulated/10/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
            gba.stringExtras["CONFIGFILE"],
        )
    }

    @Test
    fun retroArchUserPathsStillRebaseToTheInstalledBuildPackage() {
        val gb = profile(
            platformId = PlatformId.GB,
            packageName = "com.retroarch",
            retroArchCore = "gambatte",
            userId = 11,
        )

        assertEquals(
            "/data/user/11/com.retroarch/cores/gambatte_libretro_android.so",
            gb!!.stringExtras["LIBRETRO"],
        )
        assertEquals(
            "/storage/emulated/11/Android/data/com.retroarch/files/retroarch.cfg",
            gb.stringExtras["CONFIGFILE"],
        )
    }

    @Test
    fun theOwnersUserKeepsTheExactPathsItAlwaysHad() {
        val explicitOwner = profile(
            platformId = PlatformId.GBA,
            packageName = "com.retroarch.aarch64",
            retroArchCore = "mgba",
            userId = 0,
        )
        val defaulted = profile(
            platformId = PlatformId.GBA,
            packageName = "com.retroarch.aarch64",
            retroArchCore = "mgba",
        )

        assertEquals(defaulted, explicitOwner)
        assertEquals(
            "/data/user/0/com.retroarch.aarch64/cores/mgba_libretro_android.so",
            explicitOwner!!.stringExtras["LIBRETRO"],
        )
        assertEquals(
            "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
            explicitOwner.stringExtras["CONFIGFILE"],
        )
    }

    @Test
    fun theUserIdIsTheUidDividedByTheUserBlockSize() {
        assertEquals("an app of the owner", 0, androidUserIdOf(10_234))
        assertEquals("the system uid", 0, androidUserIdOf(1_000))
        assertEquals("an app of user 10", 10, androidUserIdOf(1_010_234))
        assertEquals("a work profile at user 11", 11, androidUserIdOf(1_110_001))
        assertEquals("a stubbed or unresolved uid reads as the owner", 0, androidUserIdOf(-1))
    }

    private fun profile(
        platformId: PlatformId,
        packageName: String,
        retroArchCore: String? = null,
        userId: Int? = null,
    ): LaunchDispatchProfile? {
        val target = LaunchTarget(
            gameId = GameId("game:${platformId.value}:sample"),
            platformId = platformId,
            emulator = EmulatorProfile(
                id = EmulatorId.fromNativeLauncherKey(packageName),
                packageName = packageName,
                displayName = packageName,
                retroArchCore = retroArchCore,
                installed = true,
                capability = EmulatorCapability.DIRECT_PAYLOAD,
                locatorForm = LaunchLocatorForm.FILE_PATH,
                supportedPlatforms = setOf(platformId),
            ),
            locator = if (platformId == PlatformId.DS || platformId == PlatformId.THREE_DS) {
                "content://mimir/sample"
            } else {
                "/storage/sample/game.rom"
            },
            locatorForm = LaunchLocatorForm.FILE_PATH,
            title = "Sample",
        )
        // No userId exercises the default argument, so every pre-existing test also proves the default is user 0.
        return if (userId == null) {
            ShippingLaunchProfiles.profileFor(target)
        } else {
            ShippingLaunchProfiles.profileFor(target, userId)
        }
    }

    private data class ExpectedRoute(
        val platformId: PlatformId,
        val packageName: String,
        val activity: String,
        val action: String,
        val pathStrategy: LaunchPathStrategy,
        val mimeType: String?,
        val uriExtra: String?,
        val pathExtra: String? = null,
    )
}
