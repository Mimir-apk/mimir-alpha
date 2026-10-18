package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the emulator SEED registry mapping: candidate shape, the native launch-key invariant
 * for standalone and RetroArch-core emulators, live-installed stamping, per-platform locator forms, and
 * the multi-fork platforms (PS2). The registry is the seed [PackageManagerEmulatorProvider] probes.
 */
class EmulatorSeedRegistryTest {

    @Test
    fun newSeedRowsFailClosedUntilCapabilityIsExplicitlyProven() {
        val unproven = EmulatorSeedRegistry.Seed(
            packageName = "com.example.unproven",
            displayName = "Unproven",
            locatorForm = LaunchLocatorForm.EITHER,
            platforms = setOf(PlatformId.PS1),
        )

        assertEquals(EmulatorCapability.RECOGNIZE_ONLY, unproven.capability)
    }

    /** Every standalone seed's launch key (EmulatorId) equals its package name (native launch seam contract). */
    @Test
    fun everyStandaloneSeedLaunchKeyEqualsItsPackageName() {
        for (platform in PlatformId.run { listOf(PS1, PS2, PSP, DS, THREE_DS, GAMECUBE, WII, DREAMCAST, N64, SNES) }) {
            EmulatorSeedRegistry.candidatesFor(platform, isInstalled = { true }).forEach { candidate ->
                if (!candidate.isRetroArchCore) {
                    assertEquals(
                        "launch key must equal package for standalone seed",
                        candidate.packageName,
                        candidate.id.value,
                    )
                }
            }
        }
    }

    @Test
    fun retroArchSeedsUseNativeCoreLaunchKeys() {
        val gba = EmulatorSeedRegistry.candidatesFor(PlatformId.GBA) { it == "com.retroarch.aarch64" }
            .first { it.packageName == "com.retroarch.aarch64" }

        assertEquals("gba.retroarch.aarch64.mgba", gba.id.value)
        assertEquals("mgba", gba.retroArchCore)
        assertEquals("RetroArch 64 / mGBA", gba.displayName)
        assertTrue("RetroArch 64 probed installed", gba.installed)

        val universal = EmulatorSeedRegistry.candidatesFor(PlatformId.GB) { it == "com.retroarch" }
            .first { it.isRetroArchCore }
        assertEquals("com.retroarch", universal.packageName)
        assertEquals("gb.retroarch.aarch64.gambatte", universal.id.value)
    }

    /** `installed` reflects the probe: only packages the probe says are installed come back installed. */
    @Test
    fun installedFlagReflectsTheProbe() {
        val installedPackages = setOf("com.github.stenzek.duckstation")
        val ps1 = EmulatorSeedRegistry.candidatesFor(PlatformId.PS1) { it in installedPackages }

        val duck = ps1.first { it.packageName == "com.github.stenzek.duckstation" }
        assertTrue("DuckStation probed installed", duck.installed)
        // RetroArch is also a PS1 candidate but was NOT in the installed set.
        val retroarch = ps1.firstOrNull { it.isRetroArchCore }
        assertTrue("retroarch is a PS1 candidate", retroarch != null)
        assertFalse("retroarch not installed", retroarch!!.installed)
    }

    /** DS wants the exact SAF content URI; PS1/PS2/PSP/GC want a plain file path. */
    @Test
    fun locatorFormsMatchTheProduct() {
        val ds = EmulatorSeedRegistry.dispatchSeedsFor(PlatformId.DS)
        assertTrue("DS present", ds.isNotEmpty())
        assertTrue("melonDS DS is content-uri", ds.all { it.locatorForm == LaunchLocatorForm.CONTENT_URI })

        val threeDs = EmulatorSeedRegistry.dispatchSeedsFor(PlatformId.THREE_DS)
        assertTrue("3DS content-uri", threeDs.all { it.locatorForm == LaunchLocatorForm.CONTENT_URI })

        val ps2 = EmulatorSeedRegistry.candidatesFor(PlatformId.PS2) { true }
        assertEquals(
            LaunchLocatorForm.FILE_PATH,
            ps2.first { it.packageName == "xyz.aethersx2.android" }.locatorForm,
        )
        assertEquals(
            LaunchLocatorForm.FILE_PATH,
            ps2.first { it.packageName == "come.nanodata.armsx2" }.locatorForm,
        )
        assertEquals(
            LaunchLocatorForm.FILE_PATH,
            ps2.first { it.packageName == "com.armsx2" }.locatorForm,
        )
    }

    /** PS2 exposes the full known family while exact proof-backed rows remain independently typed. */
    @Test
    fun ps2SeedsProofBackedBootPathPackages() {
        val ps2Packages = EmulatorSeedRegistry.candidatesFor(PlatformId.PS2) { true }.map { it.packageName }
        assertTrue("NetherSX2 seeded", "xyz.aethersx2.android" in ps2Packages)
        assertTrue("NetherSX2 Turnip seeded", "xyz.aethersx2.cturnip" in ps2Packages)
        assertTrue("current ARMSX2 seeded", "com.armsx2" in ps2Packages)
        assertTrue("legacy-package ARMSX2 retained", "come.nanodata.armsx2" in ps2Packages)
        assertTrue("Play! recognized", "com.virtualapplications.play" in ps2Packages)
        assertFalse("obsolete debug ARMSX2 must never seed", "come.nanodata.armsx2.debug" in ps2Packages)
    }

    @Test
    fun ppssppGoldPackageMatchesShippingApplicationId() {
        val pspPackages = EmulatorSeedRegistry.candidatesFor(PlatformId.PSP) { true }.map { it.packageName }
        assertTrue("PPSSPP Gold shipping package seeded", "org.ppsspp.ppssppgold" in pspPackages)
        assertFalse("dotted PPSSPP Gold package is not a real shipping package", "org.ppsspp.ppsspp.gold" in pspPackages)
    }

    /** A platform with no seed → no candidates (drives LaunchReadiness.Unsupported). */
    @Test
    fun unknownPlatformHasNoCandidates() {
        val none = EmulatorSeedRegistry.candidatesFor(PlatformId("atari_jaguar_cd")) { true }
        assertTrue("no candidates for an unseeded platform", none.isEmpty())
    }

    /** The declared `<queries>` list (allSeedPackages) is distinct and non-empty. */
    @Test
    fun allSeedPackagesAreDistinctAndNonEmpty() {
        val pkgs = EmulatorSeedRegistry.allSeedPackages()
        assertTrue("has seed packages", pkgs.isNotEmpty())
        assertEquals("distinct", pkgs.size, pkgs.toSet().size)
        assertTrue("RetroArch 32-bit scoped package is visible", "com.retroarch.ra32" in pkgs)
    }

    @Test
    fun launchTargetProcessAuthorityIncludesOnlyDispatchApprovedPackages() {
        val all = EmulatorSeedRegistry.allSeedPackages().toSet()
        val dispatch = EmulatorSeedRegistry.allDispatchPackages()

        assertEquals("dispatch packages are distinct", dispatch.size, dispatch.toSet().size)
        assertTrue("dispatch authority is a seed subset", all.containsAll(dispatch))
        assertTrue("RetroArch universal is dispatch-authorized", "com.retroarch" in dispatch)
        assertTrue("RetroArch 64 is dispatch-authorized", "com.retroarch.aarch64" in dispatch)
        assertTrue("RetroArch 32 is dispatch-authorized", "com.retroarch.ra32" in dispatch)
        val recognitionOnlyPackages = EmulatorSeedRegistry.SEEDS
            .groupBy(EmulatorSeedRegistry.Seed::packageName)
            .filterValues { rows -> rows.all { it.capability == EmulatorCapability.RECOGNIZE_ONLY } }
            .keys
        assertTrue(
            "no recognition-only package receives process authority",
            recognitionOnlyPackages.intersect(dispatch.toSet()).isEmpty(),
        )
        assertTrue("official Dolphin receives proven direct-launch authority", "org.dolphinemu.dolphinemu" in dispatch)
        assertFalse("recognition-only DraStic is excluded", "com.dsemu.drastic" in dispatch)
    }

    @Test
    fun biosRequirementIsScopedToTheSelectedEmulatorPackage() {
        val required = setOf(
            "xyz.aethersx2.android",
            "xyz.aethersx2.cturnip",
            "com.armsx2",
            "come.nanodata.armsx2",
            "com.github.stenzek.duckstation",
        )
        val optionalOrHle = setOf(
            "me.magnum.melonds",
            "com.dsemu.drastic",
            "org.devmiyax.yabasanshioro2",
            "com.flycast.emulator",
            "com.seleuco.mame4d2024",
            "com.retroarch.aarch64",
            "com.retroarch",
            "com.retroarch.ra32",
            "org.ppsspp.ppsspp",
            "org.ppsspp.ppssppgold",
        )

        required.forEach { assertTrue("$it requires BIOS", EmulatorSeedRegistry.requiresBios(it)) }
        optionalOrHle.forEach { assertFalse("$it does not hard-require BIOS", EmulatorSeedRegistry.requiresBios(it)) }
    }

    @Test
    fun recognitionRowsAreVisibleButNeverDispatchCandidates() {
        val rows = listOf(
            PlatformId.DS to "com.dsemu.drastic",
            PlatformId.SATURN to "org.devmiyax.yabasanshioro2",
            PlatformId.ARCADE to "com.seleuco.mame4d2024",
            PlatformId.GAMECUBE to "org.dolphinemu.mmjr",
        )
        rows.forEach { (platform, pkg) ->
            val seed = EmulatorSeedRegistry.seedsFor(platform).first { it.packageName == pkg }
            assertEquals(EmulatorCapability.RECOGNIZE_ONLY, seed.capability)
            assertFalse(EmulatorSeedRegistry.dispatchSeedsFor(platform).any { it.packageName == pkg })
        }
        assertEquals(
            EmulatorCapability.DIRECT_PAYLOAD,
            EmulatorSeedRegistry.seedForPackage("come.nanodata.armsx2")!!.capability,
        )
    }

    @Test
    fun r39CompletionPlatformsHaveConcreteRetroArchCoreKeys() {
        val expected = mapOf(
            "atari_5200" to "a5200",
            "coleco_colecovision" to "gearcoleco",
            "commodore_64" to "vice_x64",
            "commodore_amiga" to "puae",
            "gce_vectrex" to "vecx",
            "microsoft_msx" to "bluemsx",
            "microsoft_msx2" to "bluemsx",
            "nec_pc_engine_supergrafx" to "mednafen_supergrafx",
            "nec_pc_fx" to "mednafen_pcfx",
            "sinclair_zx_spectrum" to "fuse",
            "snk_neo_geo_cd" to "neocd",
            "commodore_cd32" to "puae",
            "nintendo_family_computer_disk_system" to "nestopia",
            "philips_cd_i" to "same_cdi",
            "sharp_x68000" to "px68k",
        )

        expected.forEach { (platformValue, core) ->
            val platform = PlatformId(platformValue)
            val candidate = EmulatorSeedRegistry.candidatesFor(platform) { it == "com.retroarch.aarch64" }
                .first { it.retroArchCore == core }
            assertEquals(core, candidate.retroArchCore)
            assertEquals("$platformValue.retroarch.aarch64.$core", candidate.id.value)
            assertTrue(ShippingLaunchProfiles.isCandidateDispatchSupported(platform, candidate))
        }
    }

    @Test
    fun everyMappedRetroArchPlatformExposesItsOrderedShippingCoreSet() {
        val registry = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("mimir-platform-registry.v2.json"),
        ).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val registrations = registry
            .getValue("collections").jsonObject
            .getValue("retroArchRegistrations").jsonArray
            .map { it.jsonObject }
            .associateBy { it.getValue("platformId").jsonPrimitive.content }
        var shippingBackedCoreCount = 0

        RetroArchCoreCatalog.byPlatform.forEach { (runtimePlatform, platformCores) ->
            val candidates = EmulatorSeedRegistry.candidatesFor(runtimePlatform) {
                it == "com.retroarch.aarch64"
            }.filter { it.isRetroArchCore }
            assertEquals(
                "$runtimePlatform dispatch order",
                platformCores.cores.map(RetroArchCoreCatalog.Core::dispatchStem),
                candidates.map { it.retroArchCore },
            )
            assertEquals(
                "$runtimePlatform candidate ids are unique",
                candidates.size,
                candidates.map { it.id }.distinct().size,
            )
            assertEquals(
                "$runtimePlatform keeps its recommended core first",
                platformCores.cores.first().dispatchStem,
                candidates.first().retroArchCore,
            )

            platformCores.shippingPlatformId?.let { shippingPlatformId ->
                val source = requireNotNull(registrations[shippingPlatformId]) {
                    "Missing locked shipping registration for $shippingPlatformId"
                }
                val sourceStems = source.getValue("coreRegistrations").jsonArray
                    .map { it.jsonObject.getValue("stem").jsonPrimitive.content }
                assertEquals(
                    "$runtimePlatform shipping core order",
                    sourceStems,
                    platformCores.cores.map(RetroArchCoreCatalog.Core::sourceStem),
                )
                shippingBackedCoreCount += sourceStems.size
            }
        }

        assertEquals("mapped RetroArch platform count", 42, RetroArchCoreCatalog.byPlatform.size)
        assertEquals("mapped RetroArch core count", 105, RetroArchCoreCatalog.coreCount())
        assertEquals("shipping-backed core count", 101, shippingBackedCoreCount)
    }

    @Test
    fun gbaOffersVbaAndOtherShippingCoresWithoutChangingTheMgbaDefault() {
        val candidates = EmulatorSeedRegistry.candidatesFor(PlatformId.GBA) {
            it == "com.retroarch.aarch64"
        }.filter { it.isRetroArchCore }

        assertEquals(
            listOf("mgba", "gpsp", "vba_next", "vbam", "mednafen_gba", "meteor", "skyemu"),
            candidates.map { it.retroArchCore },
        )
        assertEquals("RetroArch 64 / mGBA", candidates.first().displayName)
        assertEquals(
            "gba.retroarch.aarch64.vbam",
            candidates.first { it.retroArchCore == "vbam" }.id.value,
        )
        assertEquals(
            EmulatorId("gba.retroarch.aarch64.vbam"),
            resolveInstalledEmulator(
                candidates = candidates,
                preferredEmulatorId = EmulatorId("gba.retroarch.aarch64.vbam"),
                platformId = PlatformId.GBA,
            )?.id,
        )
    }

    @Test
    fun experimentalShellsAreSeededAsNoContentOnly() {
        val ps3 = EmulatorSeedRegistry.candidatesFor(PlatformId("ps3")) { true }
            .first { it.packageName == "net.rpcsx" }
        val xbox360 = EmulatorSeedRegistry.candidatesFor(PlatformId("xbox360")) { true }
            .first { it.packageName == "emu.x360mobile.com" }

        assertEquals(LaunchLocatorForm.NONE, ps3.locatorForm)
        assertEquals("net.rpcsx", ps3.packageName)
        assertEquals(LaunchLocatorForm.NONE, xbox360.locatorForm)
        assertEquals("emu.x360mobile.com", xbox360.packageName)
        val vita = EmulatorSeedRegistry.candidatesFor(PlatformId("sony_playstation_vita")) { true }.single()
        assertEquals(LaunchLocatorForm.NONE, vita.locatorForm)
        assertEquals("org.vita3k.emulator", vita.packageName)
    }

    @Test
    fun edenFamilyPreservesEveryExactPackageVariant() {
        val eden = EmulatorSeedRegistry.seedsFor(PlatformId.SWITCH)
            .filter { it.familyId == "eden" }
        assertEquals(
            setOf(
                "dev.eden.eden_emulator",
                "dev.eden.eden_emulator.nightly",
                "com.miHoYo.Yuanshen.nightly",
                "dev.legacy.eden_emulator",
                "dev.legacy.eden_emulator.nightly",
            ),
            eden.map { it.packageName }.toSet(),
        )
        assertEquals(5, eden.map { it.variant }.toSet().size)
        assertTrue(eden.all { it.capability == EmulatorCapability.DIRECT_PAYLOAD })
    }

    @Test
    fun exactEdenDefaultOrOverrideNeverCollapsesAcrossChannels() {
        val candidates = EmulatorSeedRegistry.candidatesFor(PlatformId.SWITCH) { true }
        val requested = EmulatorId("dev.legacy.eden_emulator.nightly")

        val resolved = resolveInstalledEmulator(candidates, requested, PlatformId.SWITCH)

        assertEquals(requested, resolved!!.id)
        assertEquals("dev.legacy.eden_emulator.nightly", resolved.packageName)
        assertFalse(resolved.id == EmulatorId("dev.legacy.eden_emulator"))
    }

    @Test
    fun shippingStandaloneCatalogHasExactPlatformFamilyChannelAndCapabilityMapping() {
        val manifest = requireNotNull(javaClass.classLoader?.getResourceAsStream("support-set-manifest.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val rows = manifest.getValue("standalone").jsonObject.getValue("targetRows").jsonArray
        val expected = shippingTargetExpectations()
        val manifestPackages = rows.map { it.jsonObject.getValue("packageName").jsonPrimitive.content }.toSet()

        assertEquals("shipping target-row count changed", 44, rows.size)
        assertEquals(expected.keys, manifestPackages)
        rows.forEach { rowElement ->
            val row = rowElement.jsonObject
            val packageName = row.getValue("packageName").jsonPrimitive.content
            val catalogPlatform = PlatformId(
                row.getValue("catalogEntryId").jsonPrimitive.content.removePrefix("shipping-catalog:"),
            )
            val sourceOrdinal = row.getValue("sourceOrdinal").jsonPrimitive.content.toInt()
            val expectedRowId = "shipping-catalog-standalone:${catalogPlatform.value}:" +
                (sourceOrdinal + 1).toString().padStart(2, '0')
            val expectation = requireNotNull(expected[packageName]) { "No target contract for $packageName" }
            val seed = requireNotNull(EmulatorSeedRegistry.seedForPackage(packageName)) {
                "Shipping package $packageName is not recognized"
            }
            assertEquals("$packageName row id/ordinal", expectedRowId, row.getValue("id").jsonPrimitive.content)
            assertTrue("$packageName catalog label is blank", row.getValue("label").jsonPrimitive.content.isNotBlank())
            assertEquals("$packageName catalog platform", expectation.catalogPlatform, catalogPlatform)
            assertEquals("$packageName runtime platforms", expectation.runtimePlatforms, seed.platforms)
            assertEquals("$packageName family", expectation.family, seed.familyId)
            assertEquals("$packageName channel", expectation.variant, seed.variant)
            assertEquals("$packageName capability", expectation.capability, seed.capability)
        }
        val aetherAliases = rows.map { it.jsonObject }.filter {
            it.getValue("packageName").jsonPrimitive.content == "xyz.aethersx2.android"
        }.map {
            Triple(
                it.getValue("id").jsonPrimitive.content,
                it.getValue("label").jsonPrimitive.content,
                it.getValue("sourceOrdinal").jsonPrimitive.content.toInt(),
            )
        }
        assertEquals(
            "NetherSX2/AetherSX2 catalog aliases must retain two rows over one exact runtime package",
            listOf(
                Triple("shipping-catalog-standalone:ps2:01", "NetherSX2", 0),
                Triple("shipping-catalog-standalone:ps2:02", "AetherSX2", 1),
            ),
            aetherAliases,
        )

        val generatedAdditions = manifest.getValue("additiveExpansion").jsonObject
            .getValue("standaloneTargetRows").jsonArray
        assertTrue("generated support manifest unexpectedly acquired standalone additions", generatedAdditions.isEmpty())

        val runtimeAdditions = setOf(
            "aenu.aps3e",
            "com.explusalpha.A2600Emu",
            "com.explusalpha.C64Emu",
            "com.explusalpha.GbaEmu",
            "com.explusalpha.GbcEmu",
            "com.explusalpha.LynxEmu",
            "com.explusalpha.MdEmu",
            "com.explusalpha.MsxEmu",
            "com.explusalpha.NeoEmu",
            "com.explusalpha.NesEmu",
            "com.explusalpha.NgpEmu",
            "com.explusalpha.PceEmu",
            "com.explusalpha.Snes9xPlus",
            "com.explusalpha.SwanEmu",
            "com.retroarch",
            "com.retroarch.aarch64",
            "com.retroarch.ra32",
            "com.armsx2",
            "com.miHoYo.Yuanshen.nightly",
            "com.seleuco.mame4d2024",
            "com.seleuco.mame4droid",
            "dev.legacy.eden_emulator",
            "dev.legacy.eden_emulator.nightly",
            // Both Xbox 360 shells installed on the Odin 2026-08-09, neither of which the
            // retired catalog ever observed. "emu.x360mobile.com" is the id X360 Mobile actually
            // ships under; the catalog's "emu.x360.mobile" matches nothing, which is why it never
            // launched. Xendroid was simply never registered.
            "emu.x360mobile.com",
            "xendroid.compose",
            "org.citron.citron_emu",
            "org.citron.emu",
            "info.cemu.cemu",
            "org.vita3k.emulator",
            "xyz.aethersx2.cturnip",
            // three newly installed packages, none observed by the retired
            // shipping catalog. com.armsx3 and com.izzy2lost.x1box are the first seeds against "ps3"
            // and "xbox" respectively that were not carried over from the shipping catalog.
            "com.izzy2lost.psx2",
            "com.armsx3",
            "com.izzy2lost.x1box",
            // R-2b: GameNative, the first seed against "pc_games" -- a Mimir-native synthetic platform
            // the retired shipping catalog never had (PC games via Steam/Epic/GOG/Amazon/custom
            // shortcuts is not a shape that existed in that product at all).
            "app.gamenative",
        )
        assertEquals(
            "runtime additive recognition ledger changed without an explicit audit update",
            runtimeAdditions,
            EmulatorSeedRegistry.allSeedPackages().toSet() - manifestPackages,
        )
    }

    @Test
    fun configuredRetroArchCoresCloseAgainstPinnedArm64Artifacts() {
        val pinnedArtifacts = setOf(
            "a5200", "bluemsx", "fbneo", "fuse", "gambatte", "gearcoleco", "genesis_plus_gx",
            "genesis_plus_gx_wide", "mednafen_lynx", "mednafen_ngp", "mednafen_pce_fast",
            "mednafen_pcfx", "mednafen_saturn", "mednafen_supergrafx", "mednafen_vb",
            "mednafen_wswan", "mgba", "mupen64plus_next_gles3", "neocd", "nestopia",
            "pcsx_rearmed", "picodrive", "pokemini", "prosystem", "puae", "px68k", "same_cdi",
            "snes9x", "stella", "vecx", "vice_x64", "virtualjaguar",
        )
        val configured = EmulatorSeedRegistry.seedsFor(PlatformId.SATURN)
            .first { it.packageName == "com.retroarch.aarch64" }
            .retroArchCoreByPlatform.values.toSet()

        val saturn = EmulatorSeedRegistry.candidatesFor(PlatformId.SATURN) { true }
            .first { it.isRetroArchCore }
        assertEquals("saturn.retroarch.aarch64.beetle_saturn", saturn.id.value)
        assertEquals("mednafen_saturn", saturn.retroArchCore)
        assertEquals(
            saturn.id,
            resolveInstalledEmulator(
                candidates = EmulatorSeedRegistry.candidatesFor(PlatformId.SATURN) { true },
                preferredEmulatorId = EmulatorId("saturn.retroarch.aarch64.beetle_saturn"),
                platformId = PlatformId.SATURN,
            )?.id,
        )
        assertTrue(
            "configured cores absent from pinned ARM64 artifact closure: ${configured - pinnedArtifacts}",
            pinnedArtifacts.containsAll(configured),
        )
    }

    private fun shippingTargetExpectations(): Map<String, ShippingTargetExpectation> {
        val direct = EmulatorCapability.DIRECT_PAYLOAD
        val recognize = EmulatorCapability.RECOGNIZE_ONLY
        val open = EmulatorCapability.OPEN_ONLY
        fun one(
            platform: PlatformId,
            family: String,
            variant: String = "stable",
            capability: EmulatorCapability = recognize,
            runtimePlatforms: Set<PlatformId> = setOf(platform),
        ) = ShippingTargetExpectation(platform, runtimePlatforms, family, variant, capability)

        return linkedMapOf(
            "io.github.lime3ds.android" to one(PlatformId.THREE_DS, "azahar", "play", direct),
            "org.azahar_emu.azahar" to one(PlatformId.THREE_DS, "azahar", "vanilla", direct),
            "io.github.azaharplus.android" to one(PlatformId.THREE_DS, "azahar", "plus"),
            "org.citra.citra_emu" to one(PlatformId.THREE_DS, "citra", "legacy", direct),
            "org.citra.citra_emu.canary" to one(PlatformId.THREE_DS, "citra", "canary"),
            "org.citra.emu" to one(PlatformId.THREE_DS, "citra", "mmj"),
            "io.github.mandarine3ds.mandarine" to one(PlatformId.THREE_DS, "mandarine"),
            "com.panda3ds.pandroid" to one(PlatformId.THREE_DS, "panda3ds"),
            "com.flycast.emulator" to one(PlatformId.DREAMCAST, "flycast", capability = direct),
            "io.recompiled.redream" to one(PlatformId.DREAMCAST, "redream"),
            "me.magnum.melonds" to one(PlatformId.DS, "melonds", capability = direct),
            "me.magnum.melonds.nightly" to one(PlatformId.DS, "melonds", "nightly", direct),
            "me.magnum.melondualds" to one(PlatformId.DS, "melonds", "dual-display", direct),
            "com.dsemu.drastic" to one(PlatformId.DS, "drastic"),
            "com.hydra.noods" to one(PlatformId.DS, "noods"),
            "com.sky.SkyEmu" to one(PlatformId.DS, "skyemu"),
            "org.dolphinemu.dolphinemu" to one(
                PlatformId.GAMECUBE,
                "dolphin",
                capability = direct,
                runtimePlatforms = setOf(PlatformId.GAMECUBE, PlatformId.WII),
            ),
            "org.mm.jr" to one(
                PlatformId.GAMECUBE,
                "dolphin",
                "mmjr",
                runtimePlatforms = setOf(PlatformId.GAMECUBE, PlatformId.WII),
            ),
            "org.dolphinemu.mmjr" to one(
                PlatformId.GAMECUBE,
                "dolphin",
                "mmjr2",
                runtimePlatforms = setOf(PlatformId.GAMECUBE, PlatformId.WII),
            ),
            "org.mupen64plusae.v3.fzurita" to one(PlatformId.N64, "m64plus", "free", direct),
            "org.mupen64plusae.v3.fzurita.pro" to one(PlatformId.N64, "m64plus", "pro", direct),
            "org.mupen64plusae.v3.alpha" to one(PlatformId.N64, "m64plus", "alpha", direct),
            "com.github.stenzek.duckstation" to one(PlatformId.PS1, "duckstation", capability = direct),
            "com.epsxe.ePSXe" to one(PlatformId.PS1, "epsxe"),
            "com.emulator.fpse64" to one(PlatformId.PS1, "fpse", "ng"),
            "com.emulator.fpse" to one(PlatformId.PS1, "fpse", "legacy"),
            "xyz.aethersx2.android" to one(PlatformId.PS2, "aethersx2", "nether", direct),
            "come.nanodata.armsx2" to one(PlatformId.PS2, "armsx2", capability = direct),
            "com.virtualapplications.play" to one(PlatformId.PS2, "play"),
            "com.sbro.emucorex" to one(PlatformId.PS2, "emucorex"),
            "net.rpcsx" to one(PlatformId("ps3"), "rpcsx", capability = open),
            "org.ppsspp.ppsspp" to one(PlatformId.PSP, "ppsspp", "free", direct),
            "org.ppsspp.ppssppgold" to one(PlatformId.PSP, "ppsspp", "gold", direct),
            "org.devmiyax.yabasanshioro2" to one(PlatformId.SATURN, "yaba-sanshiro", "free"),
            "org.devmiyax.yabasanshioro2.pro" to one(PlatformId.SATURN, "yaba-sanshiro", "pro"),
            "com.explusalpha.SaturnEmu" to one(PlatformId.SATURN, "emu-ex-plus-alpha", "saturn"),
            "dev.eden.eden_emulator" to one(PlatformId.SWITCH, "eden", "mainline-stable", direct),
            "dev.eden.eden_emulator.nightly" to one(PlatformId.SWITCH, "eden", "mainline-nightly", direct),
            "org.kenjinx.android" to one(PlatformId.SWITCH, "kenjinx"),
            "skyline.emu" to one(PlatformId.SWITCH, "skyline"),
            "org.sudachi.sudachi_emu.ea" to one(PlatformId.SWITCH, "sudachi"),
            "emu.x360.mobile" to one(PlatformId("xbox360"), "x360-mobile", capability = open),
            "jp.xenia.emulator" to one(PlatformId("xbox360"), "xenia"),
        )
    }

    /**
     * Policy 2026-08-11. PSX2 and ARMSX3 stay seeded OPEN_ONLY: no payload contract is proven
     * for either of them on this device, and claiming one without proof is the exact failure aenu.aps3e
     * (item 1 of the prior pass) already shows. com.armsx3 targets "ps3", not "ps2" -- despite reusing
     * ARMSX2's UI package/branding, its own upstream README states it runs "the latest RPCS3 upstream
     * code". X1 BOX (com.izzy2lost.x1box) is promoted to DIRECT_PAYLOAD by a separate policy,
     * also 2026-08-11: 'Conker : Live & Reloaded' booted through a content:// URI on LauncherActivity
     * on the Odin, producing the com.izzy2lost.x1box:xemu core process,
     * MainActivity foregrounded (the emulator, not the launcher), and live xemu rendering
     * ("xemu-android: refresh frame=3000 tex=2 ... size=640x480") -- the CONTENT_URI hypothesis this
     * file previously declined to claim without a device boot is now device-proven.
     */
    @Test
    fun newlyInstalledPackagesAreSeededAtProvenCapability() {
        val psx2 = requireNotNull(EmulatorSeedRegistry.seedForPackage("com.izzy2lost.psx2"))
        assertEquals(EmulatorCapability.OPEN_ONLY, psx2.capability)
        assertEquals(setOf(PlatformId.PS2), psx2.platforms)
        assertTrue(psx2.requiresBios)
        assertEquals(
            "/storage/emulated/0/Android/data/com.izzy2lost.psx2/files/bios",
            psx2.biosFolderPath(),
        )

        val armsx3 = requireNotNull(EmulatorSeedRegistry.seedForPackage("com.armsx3"))
        assertEquals(EmulatorCapability.OPEN_ONLY, armsx3.capability)
        assertEquals(setOf(PlatformId("ps3")), armsx3.platforms)
        assertEquals("armsx3", armsx3.familyId)

        val x1box = requireNotNull(EmulatorSeedRegistry.seedForPackage("com.izzy2lost.x1box"))
        assertEquals(EmulatorCapability.DIRECT_PAYLOAD, x1box.capability)
        assertEquals(LaunchLocatorForm.CONTENT_URI, x1box.locatorForm)
        assertEquals(setOf(PlatformId("xbox")), x1box.platforms)
        assertTrue(x1box.requiresBios)
        assertEquals(
            "/storage/emulated/0/Android/data/com.izzy2lost.x1box/files/x1box",
            x1box.biosFolderPath(),
        )
    }

    /** Every BIOS folder the registry holds, spelled for the owner (user 0) exactly as it was before it followed the user. */
    private val ownersBiosFolders = mapOf(
        "com.github.stenzek.duckstation" to
            "/storage/emulated/0/Android/data/com.github.stenzek.duckstation/files/bios",
        "me.magnum.melonds" to "/storage/emulated/0/Android/data/me.magnum.melonds/files",
        "me.magnum.melonds.nightly" to "/storage/emulated/0/Android/data/me.magnum.melonds.nightly/files",
        "xyz.aethersx2.android" to "/storage/emulated/0/Android/data/xyz.aethersx2.android/files/bios",
        "xyz.aethersx2.cturnip" to "/storage/emulated/0/Android/data/xyz.aethersx2.cturnip/files/bios",
        "com.armsx2" to "/storage/emulated/0/Android/data/com.armsx2/files/bios",
        "come.nanodata.armsx2" to "/storage/emulated/0/Android/data/come.nanodata.armsx2/files/bios",
        "com.izzy2lost.psx2" to "/storage/emulated/0/Android/data/com.izzy2lost.psx2/files/bios",
        "com.flycast.emulator" to "/storage/emulated/0/Android/data/com.flycast.emulator/files/data",
        "com.izzy2lost.x1box" to "/storage/emulated/0/Android/data/com.izzy2lost.x1box/files/x1box",
        "org.devmiyax.yabasanshioro2" to
            "/storage/emulated/0/Android/data/org.devmiyax.yabasanshioro2/files/yabause/bios",
        "com.seleuco.mame4d2024" to "/storage/emulated/0/Android/data/com.seleuco.mame4d2024/files/roms",
    )

    @Test
    fun theOwnersUserKeepsTheExactBiosFoldersItAlwaysHad() {
        val seededWithAFolder = EmulatorSeedRegistry.SEEDS
            .filter { it.biosFolderPath() != null }
            .associate { it.packageName to it.biosFolderPath() }

        assertEquals("no BIOS folder was added or dropped", ownersBiosFolders, seededWithAFolder)
        ownersBiosFolders.forEach { (packageName, expected) ->
            assertEquals(packageName, expected, EmulatorSeedRegistry.biosFolderPath(packageName))
            assertEquals(packageName, expected, EmulatorSeedRegistry.biosFolderPath(packageName, userId = 0))
            assertEquals(packageName, expected, EmulatorSeedRegistry.seedForPackage(packageName)!!.biosFolderPath(0))
        }
    }

    @Test
    fun aSecondaryUsersBiosFoldersAreUnderItsOwnStorageRoot() {
        assertEquals(
            "a secondary user's shared storage is /storage/emulated/10, not the owner's /storage/emulated/0",
            "/storage/emulated/10/Android/data/com.github.stenzek.duckstation/files/bios",
            EmulatorSeedRegistry.biosFolderPath("com.github.stenzek.duckstation", userId = 10),
        )
        assertEquals(
            "/storage/emulated/10/Android/data/me.magnum.melonds/files",
            EmulatorSeedRegistry.biosFolderPath("me.magnum.melonds", userId = 10),
        )
        assertEquals(
            "a work profile at user 11 reads its own folder too",
            "/storage/emulated/11/Android/data/com.flycast.emulator/files/data",
            EmulatorSeedRegistry.biosFolderPath("com.flycast.emulator", userId = 11),
        )
        ownersBiosFolders.forEach { (packageName, owners) ->
            val secondary = EmulatorSeedRegistry.biosFolderPath(packageName, userId = 10)
            assertEquals(
                "$packageName differs from the owner's only by the user id",
                owners.replaceFirst("/storage/emulated/0/", "/storage/emulated/10/"),
                secondary,
            )
            assertFalse("$packageName must not name the owner's storage", secondary!!.startsWith("/storage/emulated/0/"))
        }
    }

    @Test
    fun aPackageWithoutAKnownBiosFolderHasNoneForAnyUser() {
        assertNull(EmulatorSeedRegistry.biosFolderPath("com.retroarch.aarch64"))
        assertNull(EmulatorSeedRegistry.biosFolderPath("com.retroarch.aarch64", userId = 10))
        assertNull(EmulatorSeedRegistry.biosFolderPath("com.example.not.seeded", userId = 10))
    }

    private data class ShippingTargetExpectation(
        val catalogPlatform: PlatformId,
        val runtimePlatforms: Set<PlatformId>,
        val family: String,
        val variant: String,
        val capability: EmulatorCapability,
    )
}
