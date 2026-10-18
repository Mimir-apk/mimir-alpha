package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosPresence
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchProfile
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Readiness resolution coverage for the launch pipeline decisions the Launch Engineer owns:
 *  - installed vs not-installed emulator (Ready vs NeedsEmulator),
 *  - Open-With override (wins when installed, falls through to Auto when the override isn't installed),
 *  - needs-BIOS blocking,
 *  - missing file,
 *  - unsupported platform,
 *  - the resolved LaunchTarget's emulator launch KEY + locator selection by form.
 *
 * The engine is pure, so these are fakes — the same seam the real [LaunchController] snapshots into.
 */
class LaunchReadinessResolutionTest {

    private val gid = GameId("group:ps1:ff7")
    private val ps1 = PlatformId.PS1
    private val duckId = EmulatorId.fromNativeLauncherKey("com.github.stenzek.duckstation")
    private val raId = EmulatorId.fromNativeLauncherKey("com.retroarch")

    private fun emu(
        id: EmulatorId,
        installed: Boolean,
        form: LaunchLocatorForm = LaunchLocatorForm.FILE_PATH,
    ) = EmulatorProfile(
        id = id,
        packageName = id.value,
        displayName = id.value,
        retroArchCore = if (id == raId) "pcsx_rearmed" else null,
        installed = installed,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = form,
        supportedPlatforms = setOf(ps1),
    )

    private fun engine(
        candidates: List<EmulatorProfile>,
        override: EmulatorId? = null,
        bios: List<BiosProfile> = emptyList(),
        locator: LaunchLocatorResult? = LaunchLocatorResult("/sd/ps1/ff7.chd", LaunchLocatorForm.FILE_PATH),
    ) = LaunchReadinessEngine(
        emulators = { candidates },
        overrides = { override },
        bios = { _, _ -> bios },
        locators = object : LaunchLocatorProvider {
            override fun locatorFor(
                gameId: GameId,
                launchMemberEntryKey: EntryKey?,
                preferredDisc: Int,
                preferredForm: LaunchLocatorForm,
            ): LaunchLocatorResult? = locator
        },
    )

    private fun profile(override: EmulatorId? = null, requiresBios: Boolean = false) =
        LaunchProfile(gameId = gid, platformId = ps1, overrideEmulatorId = override, requiresBios = requiresBios)

    // ── installed vs not ──────────────────────────────────────────────────────

    @Test
    fun installedEmulatorResolvesReadyWithNativeLaunchKey() {
        val readiness = engine(listOf(emu(duckId, installed = true))).resolve(profile())
        assertTrue(readiness is LaunchReadiness.Ready)
        val target = (readiness as LaunchReadiness.Ready).target
        // The resolved target carries the native launch KEY (== package for standalones).
        assertEquals("com.github.stenzek.duckstation", target.emulator.id.value)
        assertEquals("/sd/ps1/ff7.chd", target.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, target.locatorForm)
    }

    @Test
    fun noInstalledCandidateResolvesNeedsEmulatorWithInstallCandidates() {
        val readiness = engine(listOf(emu(duckId, installed = false), emu(raId, installed = false)))
            .resolve(profile())
        assertTrue(readiness is LaunchReadiness.NeedsEmulator)
        // The not-installed candidates are offered to install.
        assertEquals(2, (readiness as LaunchReadiness.NeedsEmulator).candidates.size)
    }

    @Test
    fun noCandidatesAtAllResolvesUnsupported() {
        val readiness = engine(emptyList()).resolve(profile())
        assertTrue(readiness is LaunchReadiness.Unsupported)
        assertEquals(ps1, (readiness as LaunchReadiness.Unsupported).platformId)
    }

    // ── override ──────────────────────────────────────────────────────────────

    @Test
    fun installedOverrideWinsOverAutoDefault() {
        // Auto default would be duck (first, installed); override to RetroArch (also installed) wins.
        val candidates = listOf(emu(duckId, installed = true), emu(raId, installed = true))
        val readiness = engine(candidates, override = raId).resolve(profile(override = raId))
        assertTrue(readiness is LaunchReadiness.Ready)
        val target = (readiness as LaunchReadiness.Ready).target
        assertEquals(raId, target.emulator.id)
        assertTrue("overrideApplied flagged", target.overrideApplied)
    }

    @Test
    fun overrideNotInstalledFallsThroughToInstalledAuto() {
        // Override points at RetroArch (NOT installed); Auto must fall through to installed duck.
        val candidates = listOf(emu(duckId, installed = true), emu(raId, installed = false))
        val readiness = engine(candidates, override = raId).resolve(profile(override = raId))
        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(duckId, (readiness as LaunchReadiness.Ready).target.emulator.id)
    }

    @Test
    fun autoPrefersInstalledStandaloneDispatchCandidateOverRetroArchPs1() {
        // RetroArch PS1 is an advanced per-core route; when DuckStation is also installed, Auto remains
        // DuckStation-first like shipping instead of picking the package-family core candidate.
        val candidates = listOf(emu(raId, installed = true), emu(duckId, installed = true))
        val readiness = engine(candidates).resolve(profile())
        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(duckId, (readiness as LaunchReadiness.Ready).target.emulator.id)
    }

    @Test
    fun autoCanSelectInstalledRetroArchCoreCandidate() {
        val gba = PlatformId.GBA
        val raGba = EmulatorProfile(
            id = EmulatorId.fromNativeLauncherKey("gba.retroarch.aarch64.mgba"),
            packageName = "com.retroarch.aarch64",
            displayName = "RetroArch 64 / mGBA",
            retroArchCore = "mgba",
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(gba),
        )
        val readiness = LaunchReadinessEngine(
            emulators = { listOf(raGba) },
            overrides = { null },
            bios = { _, _ -> emptyList() },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult? = LaunchLocatorResult("/sd/gba/metroid.gba", LaunchLocatorForm.FILE_PATH)
            },
        ).resolve(LaunchProfile(gameId = GameId("group:gba:metroid"), platformId = gba))

        assertTrue(readiness is LaunchReadiness.Ready)
        val target = (readiness as LaunchReadiness.Ready).target
        assertEquals("gba.retroarch.aarch64.mgba", target.emulator.id.value)
        assertEquals("mgba", target.emulator.retroArchCore)
        assertEquals("/sd/gba/metroid.gba", target.locator)
    }

    // ── needs BIOS ────────────────────────────────────────────────────────────

    @Test
    fun requiredMissingBiosBlocksWithNeedsBios() {
        val bios = listOf(
            BiosProfile(
                platformId = ps1,
                fileName = "scph5501.bin",
                required = true,
                presence = BiosPresence.MISSING,
            ),
        )
        val readiness = engine(listOf(emu(duckId, installed = true)), bios = bios)
            .resolve(profile(requiresBios = true))
        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, (readiness as LaunchReadiness.NeedsBios).missing.size)
        assertEquals("scph5501.bin", readiness.missing.first().fileName)
    }

    // ── confirmed-missing BIOS is NON-BLOCKING ────────

    @Test
    fun confirmedMissingBiosStillResolvesADispatchableTargetOnNeedsBios() {
        // Play remains dispatchable while stating any unverified launch support: the emulator is installed and the locator resolves, so NeedsBios must carry the
        // same target a Ready readiness would have — that is what lets LaunchController dispatch while
        // still reporting the honest reason.
        val bios = listOf(
            BiosProfile(
                platformId = ps1,
                fileName = "scph5501.bin",
                required = true,
                presence = BiosPresence.MISSING,
            ),
        )
        val readiness = engine(listOf(emu(duckId, installed = true)), bios = bios)
            .resolve(profile(requiresBios = true))
        assertTrue(readiness is LaunchReadiness.NeedsBios)
        val needsBios = readiness as LaunchReadiness.NeedsBios
        assertNotNull("a confirmed-missing BIOS must still resolve a dispatchable target", needsBios.target)
        assertEquals(duckId, needsBios.target?.emulator?.id)
        assertEquals("/sd/ps1/ff7.chd", needsBios.target?.locator)
    }

    @Test
    fun missingFileStillBlocksEvenWhenBiosIsAlsoConfirmedMissing() {
        // MissingFile stays a hard block regardless of BIOS state: there is no locator to hand the
        // emulator at all, so reaching it (unlike the BIOS case) would not help the player.
        val bios = listOf(
            BiosProfile(
                platformId = ps1,
                fileName = "scph5501.bin",
                required = true,
                presence = BiosPresence.MISSING,
            ),
        )
        val readiness = engine(listOf(emu(duckId, installed = true)), bios = bios, locator = null)
            .resolve(profile(requiresBios = true))
        assertTrue(readiness is LaunchReadiness.MissingFile)
    }

    @Test
    fun unknownBiosPresenceDoesNotBlockLaunch() {
        // Honest UNKNOWN (scoped storage) surfaces but does NOT veto — the engine still goes Ready.
        val bios = listOf(
            BiosProfile(
                platformId = ps1,
                fileName = "scph5501.bin",
                required = true,
                presence = BiosPresence.UNKNOWN,
            ),
        )
        val readiness = engine(listOf(emu(duckId, installed = true)), bios = bios)
            .resolve(profile(requiresBios = true))
        assertTrue("UNKNOWN presence does not block", readiness is LaunchReadiness.Ready)
    }

    @Test
    fun consensusGateNeedsPlatformRequirementRequiredRowAndConfirmedMissing() {
        val confirmedMissingRequired = BiosProfile(
            platformId = ps1,
            fileName = "scph5501.bin",
            required = true,
            presence = BiosPresence.MISSING,
        )
        val confirmedMissingOptional = confirmedMissingRequired.copy(required = false)

        val platformDoesNotRequire = engine(listOf(emu(duckId, installed = true)), bios = listOf(confirmedMissingRequired))
            .resolve(profile(requiresBios = false))
        val rowDoesNotRequire = engine(listOf(emu(duckId, installed = true)), bios = listOf(confirmedMissingOptional))
            .resolve(profile(requiresBios = true))

        assertTrue("platform requirement is part of consensus", platformDoesNotRequire is LaunchReadiness.Ready)
        assertTrue("row requirement is part of consensus", rowDoesNotRequire is LaunchReadiness.Ready)
    }

    // ── missing file ──────────────────────────────────────────────────────────

    @Test
    fun unreachableFileResolvesMissingFile() {
        val readiness = engine(listOf(emu(duckId, installed = true)), locator = null).resolve(profile())
        assertTrue(readiness is LaunchReadiness.MissingFile)
        assertEquals(gid, (readiness as LaunchReadiness.MissingFile).gameId)
    }

    // ── locator selection by the resolved emulator's form ─────────────────────

    @Test
    fun contentUriEmulatorGetsContentUriLocatorForm() {
        val dsId = EmulatorId.fromNativeLauncherKey("me.magnum.melonds")
        val ds = PlatformId.DS
        val engine = LaunchReadinessEngine(
            emulators = {
                listOf(
                    EmulatorProfile(
                        id = dsId,
                        packageName = dsId.value,
                        displayName = dsId.value,
                        installed = true,
                        capability = EmulatorCapability.DIRECT_PAYLOAD,
                        locatorForm = LaunchLocatorForm.CONTENT_URI,
                        supportedPlatforms = setOf(ds),
                    ),
                )
            },
            overrides = { null },
            bios = { _, _ -> emptyList() },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult {
                    // The engine must request the resolved emulator's form (CONTENT_URI here).
                    assertEquals(LaunchLocatorForm.CONTENT_URI, preferredForm)
                    return LaunchLocatorResult("content://tree/doc/ff7.nds", LaunchLocatorForm.CONTENT_URI)
                }
            },
        )
        val readiness = engine.resolve(LaunchProfile(gameId = gid, platformId = ds))
        assertTrue(readiness is LaunchReadiness.Ready)
        val target = (readiness as LaunchReadiness.Ready).target
        assertEquals(LaunchLocatorForm.CONTENT_URI, target.locatorForm)
        assertTrue(target.locator.startsWith("content://"))
    }
}
