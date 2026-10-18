package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.launch.remotedefs.ActiveDefinitions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId

/**
 * The SEED registry of emulator packages Mimir knows how to launch, and which platform each serves.
 *
 * This is deliberately a small, honest, hand-curated list — NOT an attempt to mirror the shipping
 * product's full launcher catalog (auto-default-to-installed +
 * per-game override). It is the seed the [PackageManagerEmulatorProvider] probes: for each platform we
 * ask "which of these packages are installed right now?" and hand the readiness engine live candidates.
 *
 * Native launch seam contract:
 *  - `EmulatorId` is the native LAUNCH KEY — the string native's `candidateByTargetKey` accepts as
 *    `targetPackageKey`. For standalone emulators the launch key IS the package name, so here
 *    `id == packageName`. RetroArch cores carry `<platform>.retroarch.<build>.<core>` keys and are NOT
 *    seeded generically (a RetroArch launch resolves a specific core, not the bare package) — the seed
 *    covers the standalone emulators the console launches directly plus proof-shaped RetroArch core
 *    candidates (`<platform>.retroarch.<build>.<core>`), never a bare package-as-core shortcut.
 *  - Locator form mirrors the current product's real launch forms: DS/3DS/Switch want the exact SAF
 *    `content://` document URI; everything else takes a plain file path. Saturn accepts either.
 *
 * Posture: this registry is a STATIC fact table. It never touches the network and never sources an
 * emulator — it only says "if package X is installed, it can serve platform Y in form Z".
 */
object EmulatorSeedRegistry {

    /**
     * One seed row: an emulator package, its human name, the locator form it wants, and the platforms it
     * can serve. Standalone rows use [packageName] as their launch key. RetroArch rows derive the native
     * launch key from [retroArchCoreByPlatform] plus [retroArchAdditionalCoresByPlatform].
     */
    data class Seed(
        val packageName: String,
        val displayName: String,
        val locatorForm: LaunchLocatorForm,
        val platforms: Set<PlatformId>,
        /** Recommended core per platform. This remains first so existing Auto behavior is stable. */
        val retroArchCoreByPlatform: Map<PlatformId, String> = emptyMap(),
        /** Additional shipping-registry cores, in their original Open With order. */
        val retroArchAdditionalCoresByPlatform: Map<PlatformId, List<String>> = emptyMap(),
        /** Stable family identity; package variants remain distinct exact launch/persistence keys. */
        val familyId: String = packageName,
        /** Human/audit-facing package variant or release channel (stable, nightly, legacy, pro, etc.). */
        val variant: String = "stable",
        /** Strongest verified behavior; recognition alone never becomes selectable or an Auto target. */
        val capability: EmulatorCapability = EmulatorCapability.RECOGNIZE_ONLY,
        /** Emulator-family truth, not platform truth: only the selected package can require BIOS. */
        val requiresBios: Boolean = false,
        /**
         * Existing emulator-owned folder Mimir may probe, relative to the primary shared storage root
         * (`Android/data/<package>/files/bios`); null means scoped/private location unknown. Relative because the root
         * carries the Android user id: [biosFolderPath] spells it for the user the process runs as.
         */
        val biosFolderRelativePath: String? = null,
        /** See [EmulatorProfile.residentTaskWithoutIntentRefresh]; false for every seed except aPS3e. */
        val residentTaskWithoutIntentRefresh: Boolean = false,
    ) {
        /**
         * The BIOS folder for the Android user [userId] (see [androidUserIdOf]): `/storage/emulated/<id>/<relative>`.
         * User 0, the owner, is the default and gets the exact path this registry always held; a secondary user or a
         * work profile gets its own storage root instead of the owner's.
         */
        fun biosFolderPath(userId: Int = 0): String? =
            biosFolderRelativePath?.let { "${primaryStorageRootFor(userId)}/$it" }
    }

    private val P = PlatformId
    private val RetroArchPackageCandidates = listOf(
        "com.retroarch.aarch64",
        "com.retroarch",
        "com.retroarch.ra32",
    )
    private val RetroArchRecommendedCores = RetroArchCoreCatalog.recommendedByPlatform
    private val RetroArchAdditionalCores = RetroArchCoreCatalog.additionalByPlatform
    private val RetroArchPlatforms = RetroArchRecommendedCores.keys

    /**
     * The seed table. Kept small + honest; every entry is a real, current Android emulator package.
     * Multiple packages can serve one platform (e.g. the PS2 forks) — the readiness engine picks the
     * first INSTALLED one, or the per-game Open-With override.
     */
    val SEEDS: List<Seed> = listOf(
        // Multi-system direct route. Package resolution may select universal/32-bit at runtime, but the
        // persisted core key remains stable and exact.
        Seed(
            packageName = RETROARCH_SEED_PACKAGE,
            displayName = "RetroArch 64",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = RetroArchPlatforms,
            retroArchCoreByPlatform = RetroArchRecommendedCores,
            retroArchAdditionalCoresByPlatform = RetroArchAdditionalCores,
            familyId = "retroarch",
            variant = "aarch64",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
        ),

        // Current official Dolphin accepts a grantable ROM URI through its exported MainActivity
        // StartupHandler. Forks remain recognition-only until their payload contracts are proven.
        Seed("org.dolphinemu.dolphinemu", "Dolphin", LaunchLocatorForm.EITHER,
            setOf(P.GAMECUBE, P.WII), familyId = "dolphin", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.mm.jr", "Dolphin MMJR", LaunchLocatorForm.EITHER,
            setOf(P.GAMECUBE, P.WII), familyId = "dolphin", variant = "mmjr",
            capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("org.dolphinemu.mmjr", "Dolphin MMJR2", LaunchLocatorForm.EITHER,
            setOf(P.GAMECUBE, P.WII), familyId = "dolphin", variant = "mmjr2",
            capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed(
            packageName = "info.cemu.cemu",
            displayName = "Cemu",
            locatorForm = LaunchLocatorForm.EITHER,
            platforms = setOf(PlatformId("nintendo_wii_u")),
            familyId = "cemu",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
        ),

        Seed(
            packageName = "com.github.stenzek.duckstation",
            displayName = "DuckStation",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(P.PS1),
            familyId = "duckstation",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/com.github.stenzek.duckstation/files/bios",
        ),
        Seed("com.epsxe.ePSXe", "ePSXe", LaunchLocatorForm.EITHER, setOf(P.PS1),
            familyId = "epsxe", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.emulator.fpse64", "FPseNG", LaunchLocatorForm.EITHER, setOf(P.PS1),
            familyId = "fpse", variant = "ng", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.emulator.fpse", "FPse", LaunchLocatorForm.EITHER, setOf(P.PS1),
            familyId = "fpse", variant = "legacy", capability = EmulatorCapability.RECOGNIZE_ONLY),

        Seed("org.ppsspp.ppsspp", "PPSSPP", LaunchLocatorForm.FILE_PATH, setOf(P.PSP),
            familyId = "ppsspp", variant = "free", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.ppsspp.ppssppgold", "PPSSPP Gold", LaunchLocatorForm.FILE_PATH, setOf(P.PSP),
            familyId = "ppsspp", variant = "gold", capability = EmulatorCapability.DIRECT_PAYLOAD),

        Seed("me.magnum.melonds", "melonDS", LaunchLocatorForm.CONTENT_URI, setOf(P.DS),
            familyId = "melonds", capability = EmulatorCapability.DIRECT_PAYLOAD,
            biosFolderRelativePath = "Android/data/me.magnum.melonds/files"),
        Seed("me.magnum.melonds.nightly", "melonDS Nightly", LaunchLocatorForm.CONTENT_URI, setOf(P.DS),
            familyId = "melonds", variant = "nightly", capability = EmulatorCapability.DIRECT_PAYLOAD,
            biosFolderRelativePath = "Android/data/me.magnum.melonds.nightly/files"),
        Seed("me.magnum.melondualds", "melonDS DualDS", LaunchLocatorForm.CONTENT_URI, setOf(P.DS),
            familyId = "melonds", variant = "dual-display", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("com.dsemu.drastic", "DraStic", LaunchLocatorForm.CONTENT_URI, setOf(P.DS),
            familyId = "drastic", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.hydra.noods", "NooDS", LaunchLocatorForm.EITHER, setOf(P.DS),
            familyId = "noods", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.sky.SkyEmu", "SkyEmu", LaunchLocatorForm.EITHER, setOf(P.DS),
            familyId = "skyemu", capability = EmulatorCapability.RECOGNIZE_ONLY),

        Seed(
            packageName = "xyz.aethersx2.android",
            displayName = "NetherSX2",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(P.PS2),
            familyId = "aethersx2",
            variant = "nether",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/xyz.aethersx2.android/files/bios",
        ),
        Seed(
            packageName = "xyz.aethersx2.cturnip",
            displayName = "NetherSX2 Turnip",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(P.PS2),
            familyId = "aethersx2",
            variant = "turnip",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/xyz.aethersx2.cturnip/files/bios",
        ),
        Seed(
            packageName = "com.armsx2",
            displayName = "ARMSX2",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(P.PS2),
            familyId = "armsx2",
            variant = "current",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/com.armsx2/files/bios",
        ),
        Seed(
            packageName = "come.nanodata.armsx2",
            displayName = "ARMSX2",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(P.PS2),
            familyId = "armsx2",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/come.nanodata.armsx2/files/bios",
        ),
        Seed("com.virtualapplications.play", "Play!", LaunchLocatorForm.EITHER, setOf(P.PS2),
            familyId = "play", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.sbro.emucorex", "EmuCoreX", LaunchLocatorForm.EITHER, setOf(P.PS2),
            familyId = "emucorex", capability = EmulatorCapability.RECOGNIZE_ONLY),
        // newly installed, seeded at its honest capability. PSX2's own
        // MainActivity.java has no getIntent() call anywhere in the class (read from the public
        // izzy2lost/PSX2 source): the file-open flow it does have (startActivityResultLocalFilePlay,
        // MainActivity.java:1575-1595) is the RESULT callback of the app's own in-app SAF picker, not a
        // handler for an incoming external launch Intent. There is no payload contract to claim, so
        // this is OPEN_ONLY, not DIRECT_PAYLOAD -- inventing one here is the exact failure this seed
        // avoids. BIOS path read from BiosVerifier.getBiosDirectory (same source): getExternalFilesDir
        // + "bios", matching the AetherSX2/ARMSX2 shape.
        Seed(
            packageName = "com.izzy2lost.psx2",
            displayName = "PSX2",
            locatorForm = LaunchLocatorForm.NONE,
            platforms = setOf(P.PS2),
            familyId = "psx2",
            capability = EmulatorCapability.OPEN_ONLY,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/com.izzy2lost.psx2/files/bios",
        ),

        Seed("com.flycast.emulator", "Flycast", LaunchLocatorForm.FILE_PATH, setOf(P.DREAMCAST),
            familyId = "flycast", capability = EmulatorCapability.DIRECT_PAYLOAD,
            biosFolderRelativePath = "Android/data/com.flycast.emulator/files/data"),
        Seed("io.recompiled.redream", "redream", LaunchLocatorForm.EITHER, setOf(P.DREAMCAST),
            familyId = "redream", capability = EmulatorCapability.RECOGNIZE_ONLY),

        Seed("org.mupen64plusae.v3.fzurita", "M64Plus FZ", LaunchLocatorForm.EITHER, setOf(P.N64),
            familyId = "m64plus", variant = "free", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.mupen64plusae.v3.fzurita.pro", "M64Plus FZ Pro", LaunchLocatorForm.EITHER, setOf(P.N64),
            familyId = "m64plus", variant = "pro", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.mupen64plusae.v3.alpha", "Mupen64Plus AE", LaunchLocatorForm.EITHER, setOf(P.N64),
            familyId = "m64plus", variant = "alpha", capability = EmulatorCapability.DIRECT_PAYLOAD),

        Seed("com.explusalpha.Snes9xPlus", "Snes9x EX+", LaunchLocatorForm.EITHER, setOf(P.SNES),
            familyId = "emu-ex-plus-alpha", variant = "snes9x",
            capability = EmulatorCapability.DIRECT_PAYLOAD),

        // Switch variants stay exact and independent. Existing persisted mainline IDs are never rewritten.
        Seed("dev.eden.eden_emulator", "Eden", LaunchLocatorForm.CONTENT_URI, setOf(P.SWITCH),
            familyId = "eden", variant = "mainline-stable", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("dev.eden.eden_emulator.nightly", "Eden Nightly", LaunchLocatorForm.CONTENT_URI, setOf(P.SWITCH),
            familyId = "eden", variant = "mainline-nightly", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("com.miHoYo.Yuanshen.nightly", "Eden Optimized Nightly", LaunchLocatorForm.CONTENT_URI,
            setOf(P.SWITCH), familyId = "eden", variant = "optimized-nightly",
            capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("dev.legacy.eden_emulator", "Eden Legacy", LaunchLocatorForm.CONTENT_URI, setOf(P.SWITCH),
            familyId = "eden", variant = "legacy-stable", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("dev.legacy.eden_emulator.nightly", "Eden Legacy Nightly", LaunchLocatorForm.CONTENT_URI,
            setOf(P.SWITCH), familyId = "eden", variant = "legacy-nightly",
            capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.citron.citron_emu", "Citron Legacy", LaunchLocatorForm.CONTENT_URI, setOf(P.SWITCH),
            familyId = "citron", variant = "legacy", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.citron.emu", "Citron", LaunchLocatorForm.CONTENT_URI, setOf(P.SWITCH),
            familyId = "citron", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("org.kenjinx.android", "Kenji-NX", LaunchLocatorForm.EITHER, setOf(P.SWITCH),
            familyId = "kenjinx", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("skyline.emu", "Skyline", LaunchLocatorForm.EITHER, setOf(P.SWITCH),
            familyId = "skyline", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("org.sudachi.sudachi_emu.ea", "Sudachi", LaunchLocatorForm.CONTENT_URI, setOf(P.SWITCH),
            familyId = "sudachi", capability = EmulatorCapability.RECOGNIZE_ONLY),

        // Device report 2026-08-11: aPS3e boots a game from a COLD launch but not a WARM one (a
        // second launch while its task is still alive just resurfaces the running session). Read from
        // the installed APK's own AndroidManifest.xml and classes.dex: EmulatorActivity is
        // launchMode="singleTask" in its own ":emu" process and declares no `onNewIntent` override (its
        // method list is <init>/close/delay_on_create/onCreate/onDestroy/onStart/on_create/
        // show_main_memu/show_memory_search_view -- no onNewIntent), so a warm relaunch is routed to that
        // missing callback instead of onCreate and the new "iso_uri" extra is silently dropped. aPS3e's
        // OTHER exported action, "aenu.intent.action.REENTRY_QUISK_START", does NOT fix this: it is
        // declared on a different activity entirely (QuickStartActivity, the app's own MAIN/LAUNCHER
        // screen) and its handler only decides whether to show that screen's setup UI instead of
        // auto-continuing to a saved profile -- it never reads iso_uri and never reaches
        // EmulatorActivity. See [EmulatorProfile.residentTaskWithoutIntentRefresh] for the mitigation
        // this drives on Mimir's side.
        Seed("aenu.aps3e", "aPS3e", LaunchLocatorForm.EITHER, setOf(PlatformId("ps3")),
            familyId = "aps3e", capability = EmulatorCapability.DIRECT_PAYLOAD,
            residentTaskWithoutIntentRefresh = true),
        Seed("net.rpcsx", "RPCSX", LaunchLocatorForm.NONE, setOf(PlatformId("ps3")),
            familyId = "rpcsx", capability = EmulatorCapability.OPEN_ONLY),
        // ARMSX3 is a PS3 emulator despite its version-string similarity to ARMSX2: the ARMSX2/ARMSX3
        // GitHub README states "Uses the latest RPCS3 upstream code" and its build target is
        // rpcsx-android/libarmsx3-core.so -- RPCS3 is a PS3 core, not PS2's PCSX2. It reuses ARMSX2's UI
        // shell (applicationId com.armsx3, Kotlin package com.armsx2, exported com.armsx2.BootSplashActivity
        // per the device read and the upstream manifest) with the PS2 core swapped for RPCS3's, and
        // upstream's own README calls it a "Proof of concept". OPEN_ONLY: no PS3 firmware is known configured
        // for this specific app and no game-boot evidence exists, matching aPS3e's own un-modeled BIOS state
        // above rather than inventing one.
        Seed(
            packageName = "com.armsx3",
            displayName = "ARMSX3",
            locatorForm = LaunchLocatorForm.NONE,
            platforms = setOf(PlatformId("ps3")),
            familyId = "armsx3",
            capability = EmulatorCapability.OPEN_ONLY,
        ),
        // The id the retired shipping catalog observed. No build has ever been seen using it, which is
        // exactly why X360 Mobile never launched -- the package id is the whole match key. It stays
        // seeded because support-set-manifest.json still observes it and every observed package must
        // resolve to a seed; an id nothing installs is inert, so keeping it costs nothing.
        Seed("emu.x360.mobile", "X360 Mobile", LaunchLocatorForm.NONE, setOf(PlatformId("xbox360")),
            familyId = "x360-mobile", capability = EmulatorCapability.OPEN_ONLY),
        // The id actually installed, read off the device 2026-08-09 (`pm list packages`) and
        // launch-proven with `am start`. Same family as the observed id above, distinct channel.
        Seed("emu.x360mobile.com", "X360 Mobile", LaunchLocatorForm.NONE, setOf(PlatformId("xbox360")),
            familyId = "x360-mobile", variant = "shipping", capability = EmulatorCapability.OPEN_ONLY),
        // Second Xbox 360 shell on the test device. Reported 2026-08-11: X360/xendroid did not
        // launch directly into a game. A first DIRECT_PAYLOAD promotion here was WRONG and was
        // reverted: its "device proof" dispatched with an explicit component (`am start -n`), which
        // bypasses intent-filter matching entirely, so EmulatorHostActivity opened only because it was
        // named -- loading its Vulkan driver on every start was misread as a game booting. The manifest
        // confirms a content:// payload could never have reached it as intent DATA either way:
        // EmulatorHostActivity's android.intent.action.VIEW filter carries no <data> element (a filter
        // with no <data> matches only intents carrying no URI), device-confirmed with
        // `cmd package query-activities -a VIEW -d content://x/y` returning zero xendroid matches.
        //
        // DIRECT_PAYLOAD here instead, code-proven (not yet device-certified) by decompiling the
        // installed APK's classes.dex: EmulatorHostActivity.onCreate hands `getIntent()` to a reader that
        // checks the String extra "game_uri" first, then "AutoStartFile", then falls back to
        // Intent.getData() -- and if none resolve to a file it logs "No bootable game in launch intent;
        // finishing" and calls finish() without booting anything. The SAME "game_uri" key is what the
        // app's OWN internal launcher builds when a player picks a game in its own UI: it constructs
        // `Intent("xendroid.intent.action.xendroid").putExtra("game_uri", game.launchUri)` and sends that
        // to itself (confirmed against the kotlinx.serialization descriptor for the app's `Game` model,
        // whose first declared property is "launchUri" -- the exact field that intent-builder reads).
        // MainActivity independently forwards to the same "game_uri"/action pair when it is opened with a
        // resolvable file, a third agreeing code path. Needs a fresh device boot (a real title running,
        // not just the process starting) before this can be called certified rather than code-proven; see
        // ShippingLaunchProfiles for the dispatch shape this drives.
        Seed("xendroid.compose", "Xendroid", LaunchLocatorForm.EITHER, setOf(PlatformId("xbox360")),
            familyId = "xendroid", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("jp.xenia.emulator", "Xenia Android", LaunchLocatorForm.EITHER, setOf(PlatformId("xbox360")),
            familyId = "xenia", capability = EmulatorCapability.RECOGNIZE_ONLY),
        // Original Xbox (2001), not Xbox One -- confirmed from the installed
        // APK's own binary: lib/arm64-v8a/libxemu.so plus nv2a/mcpx/xbe strings are xemu's original-Xbox
        // GPU/boot-ROM/executable terms; the "Xbox One" string hits are SDL controller names, not console
        // support. Platform id "xbox" (not "xbox360") is already a BOUND entry in
        // mimir-platform-registry.v2.json with baseGameExtensions ["iso","xiso"], canonical folder "xbox",
        // and a live ScreenScraperSystemMap id (32) -- added 2026-08-10 alongside xbox360/ps3, ahead of any
        // emulator seed. This is the first seed to target it, so it also drops "xbox" from
        // ExtensionLaunchCoverage.unsupportedCanonicalPlatforms.
        // DIRECT_PAYLOAD -- device proof: X1 BOX's own LauncherActivity forwards
        // intent.data as a content:// dvdUri to MainActivity (FrontendLaunchHelper.kt, izzy2lost/xemu
        // source) whenever the BIOS/HDD prerequisites are already configured, and xemu.toml's
        // `dvd_path = '/dev/fdset/0'` is consistent with it opening a content:// grant itself. That
        // source-grounded CONTENT_URI hypothesis is no longer unconfirmed: launching 'Conker : Live &
        // Reloaded' through this exact contract on the Odin produced the
        // com.izzy2lost.x1box:xemu core process, MainActivity foregrounded (the emulator, not the
        // launcher), and live xemu rendering ("xemu-android: refresh frame=3000 tex=2 ... size=640x480",
        // "present drawable=1920x1080", plus live xemu-gpu/xemu-fpu/xemu-rw counters).
        // BIOS confirmed present and sized-correct on the Odin 2026-08-11: mcpx.bin (512B),
        // flash.bin (1,048,576B) under .../files/x1box/, NOT .../files/bios/ like the PS-family seeds --
        // xemu.toml's [sys.files] names bootrom_path/flashrom_path as the load-bearing pair; eeprom.bin is
        // per-console state and hdd.img is a disk image, neither is "the BIOS".
        Seed(
            packageName = "com.izzy2lost.x1box",
            displayName = "X1 BOX",
            locatorForm = LaunchLocatorForm.CONTENT_URI,
            platforms = setOf(PlatformId("xbox")),
            familyId = "x1box",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            requiresBios = true,
            biosFolderRelativePath = "Android/data/com.izzy2lost.x1box/files/x1box",
        ),
        Seed("org.vita3k.emulator", "Vita3K", LaunchLocatorForm.NONE,
            setOf(PlatformId("sony_playstation_vita")), familyId = "vita3k",
            capability = EmulatorCapability.OPEN_ONLY),

        // Declaration order IS the Auto order (resolveInstalledEmulator: the earlier payload-capable package wins),
        // so within a family the current official release is declared before the legacy-package build. With both Azahar builds installed a 3DS game opens in `org.azahar_emu.azahar`, not in the
        // older `io.github.lime3ds.android` package. A stored per-game Open-With or per-platform choice still wins
        // over this order. The two display names differ so the Open With list, the card's EMULATOR fact and the
        // Settings line can tell the builds apart (the lime3ds package's own app label reads "AzaharPlus").
        Seed("org.azahar_emu.azahar", "Azahar", LaunchLocatorForm.CONTENT_URI, setOf(P.THREE_DS),
            familyId = "azahar", variant = "vanilla", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("io.github.lime3ds.android", "Azahar (Lime3DS build)", LaunchLocatorForm.CONTENT_URI, setOf(P.THREE_DS),
            familyId = "azahar", variant = "play", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("io.github.azaharplus.android", "AzaharPlus", LaunchLocatorForm.CONTENT_URI, setOf(P.THREE_DS),
            familyId = "azahar", variant = "plus", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("org.citra.citra_emu", "Citra", LaunchLocatorForm.CONTENT_URI, setOf(P.THREE_DS),
            familyId = "citra", variant = "legacy", capability = EmulatorCapability.DIRECT_PAYLOAD),
        Seed("org.citra.citra_emu.canary", "Citra Canary", LaunchLocatorForm.CONTENT_URI, setOf(P.THREE_DS),
            familyId = "citra", variant = "canary", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("org.citra.emu", "Citra MMJ", LaunchLocatorForm.EITHER, setOf(P.THREE_DS),
            familyId = "citra", variant = "mmj", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("io.github.mandarine3ds.mandarine", "Mandarine", LaunchLocatorForm.CONTENT_URI, setOf(P.THREE_DS),
            familyId = "mandarine", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.panda3ds.pandroid", "Panda3DS", LaunchLocatorForm.EITHER, setOf(P.THREE_DS),
            familyId = "panda3ds", capability = EmulatorCapability.RECOGNIZE_ONLY),

        Seed("org.devmiyax.yabasanshioro2", "Yaba Sanshiro 2", LaunchLocatorForm.EITHER, setOf(P.SATURN),
            familyId = "yaba-sanshiro", variant = "free", capability = EmulatorCapability.RECOGNIZE_ONLY,
            biosFolderRelativePath = "Android/data/org.devmiyax.yabasanshioro2/files/yabause/bios"),
        Seed("org.devmiyax.yabasanshioro2.pro", "Yaba Sanshiro 2 Pro", LaunchLocatorForm.EITHER,
            setOf(P.SATURN), familyId = "yaba-sanshiro", variant = "pro",
            capability = EmulatorCapability.RECOGNIZE_ONLY),

        Seed("com.seleuco.mame4d2024", "MAME4droid 2024", LaunchLocatorForm.EITHER, setOf(P.ARCADE),
            familyId = "mame4droid", variant = "2024", capability = EmulatorCapability.RECOGNIZE_ONLY,
            biosFolderRelativePath = "Android/data/com.seleuco.mame4d2024/files/roms"),
        Seed("com.seleuco.mame4droid", "MAME4droid", LaunchLocatorForm.EITHER, setOf(P.ARCADE),
            familyId = "mame4droid", variant = "0.139", capability = EmulatorCapability.RECOGNIZE_ONLY),

        // Official EX Plus Alpha family. Only Snes9x EX+ has an independently proven payload route in
        // this gate; framework ancestry alone is not used to promote the remaining packages.
        Seed("com.explusalpha.A2600Emu", "2600.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("atari2600")), familyId = "emu-ex-plus-alpha", variant = "a2600",
            capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.C64Emu", "C64.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("commodore_64")), familyId = "emu-ex-plus-alpha", variant = "c64",
            capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.GbaEmu", "GBA.emu", LaunchLocatorForm.EITHER, setOf(P.GBA),
            familyId = "emu-ex-plus-alpha", variant = "gba", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.GbcEmu", "GBC.emu", LaunchLocatorForm.EITHER, setOf(P.GB, P.GBC),
            familyId = "emu-ex-plus-alpha", variant = "gbc", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.LynxEmu", "Lynx.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("atarilynx")), familyId = "emu-ex-plus-alpha", variant = "lynx",
            capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.MdEmu", "MD.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("megadrive"), PlatformId("mastersystem"), PlatformId("gamegear"), PlatformId("segacd")),
            familyId = "emu-ex-plus-alpha", variant = "md", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.MsxEmu", "MSX.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("microsoft_msx"), PlatformId("microsoft_msx2")),
            familyId = "emu-ex-plus-alpha", variant = "msx", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.NeoEmu", "NEO.emu", LaunchLocatorForm.EITHER, setOf(P.ARCADE),
            familyId = "emu-ex-plus-alpha", variant = "neo", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.NesEmu", "NES.emu", LaunchLocatorForm.EITHER,
            setOf(P.NES, PlatformId("nintendo_family_computer_disk_system")),
            familyId = "emu-ex-plus-alpha", variant = "nes", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.NgpEmu", "NGP.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("ngp"), PlatformId("ngpc")), familyId = "emu-ex-plus-alpha", variant = "ngp",
            capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.PceEmu", "PCE.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("pcengine"), PlatformId("pcenginecd"), PlatformId("nec_pc_engine_supergrafx")),
            familyId = "emu-ex-plus-alpha", variant = "pce", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.SaturnEmu", "Saturn.emu", LaunchLocatorForm.EITHER, setOf(P.SATURN),
            familyId = "emu-ex-plus-alpha", variant = "saturn", capability = EmulatorCapability.RECOGNIZE_ONLY),
        Seed("com.explusalpha.SwanEmu", "Swan.emu", LaunchLocatorForm.EITHER,
            setOf(PlatformId("wonderswan"), PlatformId("wonderswancolor")),
            familyId = "emu-ex-plus-alpha", variant = "swan", capability = EmulatorCapability.RECOGNIZE_ONLY),

        // GameNative (R-2b) -- PC Games (Steam/Epic/GOG/Amazon/custom shortcuts). Two-sided proven
        // contract: both the shortcut PRODUCER (ShortcutUtilsKt.createPinnedShortcut) and the intent
        // CONSUMER (IntentLaunchManager.parseLaunchIntent) were read from the installed build's own dex
        // and agree. The "locator" is the
        // shortcut FILE's path; its CONTENT (not the path itself) carries the decimal app_id, which is
        // why this is FILE_PATH rather than NONE -- LaunchReadinessEngine must actually resolve the real
        // file for GameNativeLaunchRoute to read. Code-proven, NOT yet device-certified: no .steam file
        // was available on the test card (contract doc, OWED). app.gamenative.gold (the paid variant
        // referenced in NativeShelfPackages' curated app-shelf list) is a SEPARATE, unverified package --
        // deliberately not seeded here; its contract has not been read.
        Seed(
            packageName = GameNativeLaunchRoute.PACKAGE_NAME,
            displayName = "GameNative",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(PlatformId("pc_games")),
            familyId = "gamenative",
            capability = EmulatorCapability.DIRECT_PAYLOAD,
        ),
    )

    /** Every known seed for [platformId], including installed-but-unproven recognition rows. */
    fun seedsFor(platformId: PlatformId): List<Seed> =
        SEEDS.filter { platformId in it.platforms }

    /** Seeds Mimir may actually dispatch to; recognition-only packages never enter this set. */
    fun dispatchSeedsFor(platformId: PlatformId): List<Seed> =
        seedsFor(platformId).filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY }

    fun seedForPackage(packageName: String): Seed? =
        SEEDS.firstOrNull { it.packageName == packageName }

    /**
     * The package an emulator's list of accepted files is keyed by (the definitions' extension and filename rules, see
     * [com.gruning.mimir.rewrite.core.launch.remotedefs.EffectiveDefinitions.listsFile]): the SEED's own package. A RetroArch
     * core candidate carries whichever RetroArch build is installed in [EmulatorProfile.packageName] ([candidatesFor]
     * swaps it in), but the only RetroArch package a definitions file may name is the seed's, and every core of a platform
     * shares that one list, so the build that happens to be installed must not pick the key.
     */
    fun acceptanceKeyFor(candidate: EmulatorProfile): String = acceptanceKeyFor(candidate, SEEDS)

    /**
     * [acceptanceKeyFor] over a given seed table. The RetroArch key is the NAMED seed ([RETROARCH_SEED_PACKAGE]), looked up by that
     * package, never "the first seed that has cores": another seed gaining cores, or sitting earlier in the table, moves nothing.
     */
    internal fun acceptanceKeyFor(candidate: EmulatorProfile, seeds: List<Seed>): String =
        if (candidate.isRetroArchCore) {
            seeds.firstOrNull { it.packageName == RETROARCH_SEED_PACKAGE && it.retroArchCoreByPlatform.isNotEmpty() }?.packageName
                ?: RETROARCH_SEED_PACKAGE
        } else {
            candidate.packageName
        }

    /** The one seed every RetroArch core candidate belongs to, and the package a definitions file names for RetroArch rows. */
    const val RETROARCH_SEED_PACKAGE: String = "com.retroarch.aarch64"

    /**
     * Default emulator suggestion: the one emulator Mimir names for a platform with none installed.
     * Derived from this table, never invented: the first standalone seed Mimir can hand a game to directly, else
     * RetroArch where it serves the platform, else null (a platform with no dispatchable seed names nothing).
     * A name only; Mimir links to or installs nothing.
     */
    fun recommendedDisplayNameFor(platformId: PlatformId): String? {
        val dispatchable = dispatchSeedsFor(platformId)
        return (
            dispatchable.firstOrNull { it.familyId != "retroarch" && it.capability == EmulatorCapability.DIRECT_PAYLOAD }
                ?: dispatchable.firstOrNull { it.familyId == "retroarch" }
            )?.displayName
    }

    /** BIOS requirement/folder truth for [packageName], including non-launch-certified emulator families. */
    fun biosSeedForPackage(packageName: String): Seed? =
        SEEDS.firstOrNull { it.packageName == packageName }
            ?: SEEDS.firstOrNull { it.packageName == RETROARCH_SEED_PACKAGE }
                ?.takeIf { packageName in RetroArchPackageCandidates }

    fun requiresBios(packageName: String): Boolean = biosSeedForPackage(packageName)?.requiresBios == true

    /** [packageName]'s BIOS folder as the Android user [userId] sees it; user 0 (the default) is the owner's path. */
    fun biosFolderPath(packageName: String, userId: Int = 0): String? =
        biosSeedForPackage(packageName)?.biosFolderPath(userId)

    /**
     * Build the [EmulatorProfile] candidates for [platformId], stamping each with the live [installed]
     * flag from [isInstalled]. The candidate `id` is the native launch key (= the package name for
     * these standalone emulators). Order is registry order (most-compatible-first) so the engine's
     * Auto default picks the first installed candidate.
     */
    fun candidatesFor(
        platformId: PlatformId,
        isInstalled: (packageName: String) -> Boolean,
    ): List<EmulatorProfile> = seedsFor(platformId).flatMap { seed ->
        // The effective definitions: the compiled cores for this seed and platform in their compiled order (the
        // recommended core first, which is the Auto default), then any the player's verified overlay appended.
        val retroArchCores = ActiveDefinitions.current.retroArchCoresFor(seed.packageName, platformId)
        val installedRetroArchPackage = retroArchCores
            .takeIf { it.isNotEmpty() }
            ?.let { RetroArchPackageCandidates.firstOrNull(isInstalled) }
        val candidateCores: List<String?> = if (retroArchCores.isEmpty()) listOf(null) else retroArchCores

        candidateCores.map { retroArchCore ->
            val persistedRetroArchCoreId = retroArchCore?.let {
                RetroArchCoreCatalog.persistedStem(platformId, it)
            }
            EmulatorProfile(
                id = EmulatorId.fromNativeLauncherKey(
                    persistedRetroArchCoreId?.let { core -> retroArchLaunchKey(platformId, seed.packageName, core) }
                        ?: seed.packageName,
                ),
                packageName = installedRetroArchPackage ?: seed.packageName,
                displayName = seed.displayName + (
                    retroArchCore?.let { " / ${retroArchCoreLabel(platformId, it)}" } ?: ""
                ),
                retroArchCore = retroArchCore,
                installed = if (retroArchCore != null) {
                    installedRetroArchPackage != null
                } else {
                    isInstalled(seed.packageName)
                },
                capability = seed.capability,
                locatorForm = seed.locatorForm,
                supportedPlatforms = seed.platforms,
                residentTaskWithoutIntentRefresh = seed.residentTaskWithoutIntentRefresh,
            )
        }
    }

    /** Every distinct emulator package in the seed (for the app's scoped `<queries>` declaration). */
    fun allSeedPackages(): List<String> = (SEEDS.map { it.packageName } + RetroArchPackageCandidates).distinct()

    /** Packages with an approved open-only or direct-payload route; recognition alone is not force-stop authority. */
    fun allDispatchPackages(): List<String> {
        val dispatchSeeds = SEEDS.filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY }
        val packages = dispatchSeeds.map { it.packageName }
        val retroArchVariants = RetroArchPackageCandidates.takeIf {
            dispatchSeeds.any { seed -> seed.retroArchCoreByPlatform.isNotEmpty() }
        }.orEmpty()
        return (packages + retroArchVariants).distinct()
    }

    /** Every platform with at least one recognized package family. */
    fun allSeedPlatforms(): Set<PlatformId> = SEEDS.asSequence()
        .flatMap { it.platforms.asSequence() }
        .toCollection(linkedSetOf())

    /** Every platform with at least one open-only or direct-payload route. */
    fun allDispatchPlatforms(): Set<PlatformId> = SEEDS.asSequence()
        .filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY }
        .flatMap { it.platforms.asSequence() }
        .toCollection(linkedSetOf())

    /** RetroArch-served platforms used by dispatch preference and certification posture. */
    internal fun retroArchPlatforms(): Set<PlatformId> = RetroArchPlatforms

    /**
     * The curated display name for a KNOWN emulator package, or null if it isn't a seeded emulator. Lets the
     * native shelf (and any surface) prefer one consistent, branded name over the app's raw PackageManager
     * label — which is unpolished or rebranded ("citron" lowercase; "AzaharPlus" on the Lime3DS package)
     *. Non-emulator apps return null and keep their own label.
     */
    fun displayNameFor(packageName: String): String? =
        SEEDS.firstOrNull { it.packageName == packageName }?.displayName

    private fun retroArchLaunchKey(platformId: PlatformId, packageName: String, core: String): String =
        "${platformId.value}.retroarch.${retroArchPackageSegment(packageName)}.$core"

    private fun retroArchPackageSegment(packageName: String): String = when (packageName) {
        "com.retroarch.aarch64" -> "aarch64"
        "com.retroarch.ra32" -> "ra32"
        else -> "universal"
    }

    private fun retroArchCoreLabel(platformId: PlatformId, core: String): String =
        RetroArchCoreCatalog.displayName(platformId, core) ?: core
}
