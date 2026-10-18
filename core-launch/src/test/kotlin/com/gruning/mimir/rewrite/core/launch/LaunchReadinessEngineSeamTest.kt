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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine coverage for the native launch seam contracts: the readiness engine must (a) pass the chosen group
 * MEMBER to the locator provider, and (b) resolve BIOS truth for the RESOLVED emulator, filtered by
 * [BiosProfile.appliesTo].
 */
class LaunchReadinessEngineSeamTest {

    private val ps2 = PlatformId.PS2
    private val gid = GameId("group:ps2:gow")
    private val nether = EmulatorId.fromNativeLauncherKey("ps2.nethersx2")
    private val armsx2 = EmulatorId.fromNativeLauncherKey("ps2.armsx2")

    private fun emu(id: EmulatorId, form: LaunchLocatorForm = LaunchLocatorForm.FILE_PATH) = EmulatorProfile(
        id = id,
        packageName = "xyz.aethersx2.android",
        displayName = "NetherSX2",
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = form,
        supportedPlatforms = setOf(ps2),
    )

    // ── Correction 3: locator provider receives the chosen member entry key ───

    @Test
    fun engineForwardsChosenMemberEntryKeyToLocatorProvider() {
        var seenMember: EntryKey? = null
        var seenPreferredDisc = 0
        var seenForm: LaunchLocatorForm? = null
        val disc2 = EntryKey("entry:ps2:gow-disc2")

        val engine = LaunchReadinessEngine(
            emulators = { listOf(emu(nether)) },
            overrides = { null },
            bios = { _, _ -> emptyList() },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult? {
                    seenMember = launchMemberEntryKey
                    seenPreferredDisc = preferredDisc
                    seenForm = preferredForm
                    return LaunchLocatorResult("/storage/SD/ps2/gow-d2.iso", LaunchLocatorForm.FILE_PATH)
                }
            },
        )

        val readiness = engine.resolve(
            LaunchProfile(
                gameId = gid,
                platformId = ps2,
                launchMemberEntryKey = disc2,
                preferredDisc = 2,
                requiresBios = false,
            ),
        )
        assertTrue(readiness is LaunchReadiness.Ready)
        assertEquals(disc2, seenMember)
        assertEquals(2, seenPreferredDisc)
        assertEquals(LaunchLocatorForm.FILE_PATH, seenForm)
    }

    @Test
    fun enginePassesNullMemberWhenProfileHasNoChosenDisc() {
        var seenMember: EntryKey? = EntryKey("sentinel")
        val engine = LaunchReadinessEngine(
            emulators = { listOf(emu(nether)) },
            overrides = { null },
            bios = { _, _ -> emptyList() },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult? {
                    seenMember = launchMemberEntryKey
                    return LaunchLocatorResult("/x.iso", LaunchLocatorForm.FILE_PATH)
                }
            },
        )
        engine.resolve(LaunchProfile(gameId = gid, platformId = ps2))
        assertEquals(null, seenMember)
    }

    // ── Correction 2: BIOS resolved for the chosen emulator, filtered by appliesTo ──

    @Test
    fun biosResolvedForChosenEmulatorAndFiltersByAppliesTo() {
        var biosQueriedFor: EmulatorId? = null
        // A MISSING BIOS scoped to a DIFFERENT emulator (armsx2) must NOT block a NetherSX2 launch.
        val engine = LaunchReadinessEngine(
            emulators = { listOf(emu(nether)) },
            overrides = { null },
            bios = { _, requested ->
                biosQueriedFor = requested
                listOf(
                    BiosProfile(
                        platformId = ps2,
                        fileName = "ps2-bios.bin",
                        required = true,
                        presence = BiosPresence.MISSING,
                        emulatorId = armsx2, // scoped to a different fork
                    ),
                )
            },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult = LaunchLocatorResult("/x.iso", LaunchLocatorForm.FILE_PATH)
            },
        )
        val readiness = engine.resolve(
            LaunchProfile(gameId = gid, platformId = ps2, requiresBios = true),
        )
        // Resolved emulator is NetherSX2 → the armsx2-scoped missing BIOS does not apply → Ready.
        assertEquals(nether, biosQueriedFor)
        assertTrue(readiness is LaunchReadiness.Ready)
    }

    @Test
    fun biosMissingForChosenEmulatorBlocksLaunch() {
        val engine = LaunchReadinessEngine(
            emulators = { listOf(emu(nether)) },
            overrides = { null },
            bios = { _, _ ->
                listOf(
                    BiosProfile(
                        platformId = ps2,
                        fileName = "ps2-bios.bin",
                        required = true,
                        presence = BiosPresence.MISSING,
                        emulatorId = nether, // scoped to the RESOLVED emulator
                    ),
                )
            },
            locators = object : LaunchLocatorProvider {
                override fun locatorFor(
                    gameId: GameId,
                    launchMemberEntryKey: EntryKey?,
                    preferredDisc: Int,
                    preferredForm: LaunchLocatorForm,
                ): LaunchLocatorResult = LaunchLocatorResult("/x.iso", LaunchLocatorForm.FILE_PATH)
            },
        )
        val readiness = engine.resolve(
            LaunchProfile(gameId = gid, platformId = ps2, requiresBios = true),
        )
        assertTrue(readiness is LaunchReadiness.NeedsBios)
        assertEquals(1, (readiness as LaunchReadiness.NeedsBios).missing.size)
    }
}
