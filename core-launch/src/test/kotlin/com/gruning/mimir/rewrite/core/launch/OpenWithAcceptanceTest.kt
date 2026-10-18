package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsMerge
import com.gruning.mimir.rewrite.core.launch.remotedefs.EffectiveDefinitions
import com.gruning.mimir.rewrite.core.launch.remotedefs.ExtensionsRow
import com.gruning.mimir.rewrite.core.launch.remotedefs.FilenameRegexRow
import com.gruning.mimir.rewrite.core.launch.remotedefs.OverlayRow
import com.gruning.mimir.rewrite.core.launch.remotedefs.RegistryBundledDefinitions
import com.gruning.mimir.rewrite.core.launch.remotedefs.ValidatedOverlay
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the earlier update D1: the Open-with list LABELS which emulators do not list the game's file. It is information only: the candidates,
 * their order, what is selectable, the Recommended pick, the launch pick and Play are exactly what they were.
 */
class OpenWithAcceptanceTest {
    private val psp = PlatformId.PSP
    private val gba = PlatformId.GBA
    private val ppsspp = "org.ppsspp.ppsspp"
    private val ppssppGold = "org.ppsspp.ppssppgold"
    private val retroArchSeed = "com.retroarch.aarch64"
    private val gameId = GameId("game:psp:acceptance")

    private val bundled = RegistryBundledDefinitions.build()
    private val noOverlay: EffectiveDefinitions = DefinitionsMerge.merge(bundled, null)

    private fun defs(vararg rows: OverlayRow): EffectiveDefinitions =
        DefinitionsMerge.merge(bundled, ValidatedOverlay(1, 3L, "raw", "canonical", rows.toList()))

    private fun pspCandidates(vararg installed: String): List<EmulatorProfile> =
        EmulatorSeedRegistry.candidatesFor(psp) { it in installed }.filter { !it.isRetroArchCore }

    private fun gbaCores(host: String): List<EmulatorProfile> =
        EmulatorSeedRegistry.candidatesFor(gba) { it == host }.filter { it.isRetroArchCore }

    private fun controller(
        candidates: List<EmulatorProfile>,
        fileName: String?,
        definitions: EffectiveDefinitions,
        storedOverride: EmulatorId? = null,
        platformDefault: EmulatorId? = null,
        withNamePort: Boolean = true,
        onNameRead: () -> Unit = {},
    ) = if (withNamePort) {
        LaunchController(
            emulatorProvider = InstalledEmulatorProvider { candidates },
            locatorResolver = { _, _, _, _ -> LaunchLocatorResult("/storage/emulated/0/ROMs/sample.iso", LaunchLocatorForm.FILE_PATH) },
            overrideResolver = { storedOverride },
            overrideSetter = { _, _ -> Unit },
            overrideClearer = { Unit },
            biosResolver = { _, _, _ -> emptyList() },
            proofRecorder = { Unit },
            dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
            platformDefault = { platformDefault },
            ioDispatcher = Dispatchers.Unconfined,
            confirmLaunchCue = {},
            gameLaunchArmed = {},
            launchMemberFileNames = { _, _, _ -> onNameRead(); listOfNotNull(fileName) },
            definitions = { definitions },
        )
    } else {
        // The constructor as it stood before no file-name port, the default definitions.
        LaunchController(
            emulatorProvider = InstalledEmulatorProvider { candidates },
            locatorResolver = { _, _, _, _ -> LaunchLocatorResult("/storage/emulated/0/ROMs/sample.iso", LaunchLocatorForm.FILE_PATH) },
            overrideResolver = { storedOverride },
            overrideSetter = { _, _ -> Unit },
            overrideClearer = { Unit },
            biosResolver = { _, _, _ -> emptyList() },
            proofRecorder = { Unit },
            dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
            platformDefault = { platformDefault },
            ioDispatcher = Dispatchers.Unconfined,
            confirmLaunchCue = {},
            gameLaunchArmed = {},
        )
    }

    private fun options(c: LaunchController, platform: PlatformId = psp) = runBlocking { c.openWithOptions(gameId, platform) }

    // ---- the truth table, through openWithOptions -----------------------------------------------------------------

    @Test
    fun an_emulator_with_a_list_is_labelled_per_file_and_one_without_stays_null() {
        val candidates = pspCandidates(ppsspp, ppssppGold)
        val d = defs(ExtensionsRow(ppsspp, psp, listOf("cso", "pbp")))
        val table = listOf(
            "Game.CSO" to true,
            "game.pbp" to true,
            "game.iso" to false,
            "noext" to false,
        )
        for ((name, expected) in table) {
            val o = options(controller(candidates, name, d))
            assertEquals("PPSSPP lists $name", expected, o.candidates.first { it.packageName == ppsspp }.listsFile)
            assertNull("PPSSPP Gold has no list: $name", o.candidates.first { it.packageName == ppssppGold }.listsFile)
            assertFalse("Gold has no list, so the file is on 'a' list for it", o.noInstalledEmulatorListsFile)
        }
        assertEquals("cso", options(controller(candidates, "Game.CSO", d)).fileExtension)
        assertNull(options(controller(candidates, "noext", d)).fileExtension)
        assertNull("a name that cannot be read labels nothing", options(controller(candidates, null, d)).candidates.firstNotNullOfOrNull { it.listsFile })
        assertNull(options(controller(candidates, null, d)).fileExtension)
    }

    @Test
    fun the_extension_a_label_prints_is_only_ever_a_short_run_of_letters_and_digits() {
        assertEquals("cso", openWithDisplayExtension("Game.CSO"))
        assertEquals("gz", openWithDisplayExtension("a.tar.gz"))
        assertNull(openWithDisplayExtension(null))
        assertNull(openWithDisplayExtension("noext"))
        assertNull(openWithDisplayExtension("trailing."))
        assertNull(openWithDisplayExtension("odd.‮cso"))
        assertNull(openWithDisplayExtension("name.with space"))
        assertNull(openWithDisplayExtension("name." + "x".repeat(11)))
        assertEquals("x".repeat(10), openWithDisplayExtension("name." + "x".repeat(10)))
    }

    @Test
    fun the_none_of_your_emulators_flag_needs_every_installed_emulator_to_have_a_list_without_the_file() {
        val candidates = pspCandidates(ppsspp, ppssppGold)
        val both = defs(ExtensionsRow(ppsspp, psp, listOf("pbp")), ExtensionsRow(ppssppGold, psp, listOf("pbp")))
        val bothUnlisted = options(controller(candidates, "game.cso", both))
        assertTrue(bothUnlisted.noInstalledEmulatorListsFile)
        assertFalse("a file that is listed clears it", options(controller(candidates, "game.pbp", both)).noInstalledEmulatorListsFile)

        // One installed emulator without a list accepts everything, so the file is not unlisted anywhere.
        val onlyOne = defs(ExtensionsRow(ppsspp, psp, listOf("pbp")))
        assertFalse(options(controller(candidates, "game.cso", onlyOne)).noInstalledEmulatorListsFile)

        // A NOT installed emulator does not count either way: only PPSSPP is installed here, and it has a list without .cso.
        val onlyInstalled = pspCandidates(ppsspp)
        val flagged = options(controller(onlyInstalled, "game.cso", onlyOne))
        assertTrue(flagged.noInstalledEmulatorListsFile)
        assertTrue(flagged.candidates.any { !it.installed && it.listsFile == null })

        // Nothing installed: there is nothing to say.
        assertFalse(options(controller(pspCandidates(), "game.cso", both)).noInstalledEmulatorListsFile)
    }

    // ---- nothing else moves --------------------------------------------------------------------------------------

    @Test
    fun with_no_overlay_openWithOptions_is_what_it_was() {
        for (platform in listOf(psp, gba)) {
            val candidates = if (platform == psp) pspCandidates(ppsspp, ppssppGold) else gbaCores("com.retroarch.aarch64")
            for (name in listOf("game.iso", "GAME.CSO", "noext", null)) {
                val before = options(controller(candidates, name, noOverlay, withNamePort = false), platform)
                var nameReads = 0
                val after = options(controller(candidates, name, noOverlay, onNameRead = { nameReads++ }), platform)
                assertEquals("with no list anywhere the file name is never even read", 0, nameReads)
                assertEquals("the candidates, field for field, for $platform / $name", before.candidates, after.candidates)
                assertEquals(before.hasOverride, after.hasOverride)
                assertTrue("no list anywhere", after.candidates.all { it.listsFile == null })
                assertFalse(after.noInstalledEmulatorListsFile)
            }
        }
    }

    @Test
    fun the_file_name_is_read_once_and_only_when_some_candidate_has_a_list() {
        val candidates = pspCandidates(ppsspp, ppssppGold)
        var reads = 0
        options(controller(candidates, "game.cso", defs(ExtensionsRow(ppsspp, psp, listOf("pbp"))), onNameRead = { reads++ }))
        assertEquals(1, reads)
        // A list on a platform or package no candidate belongs to does not count.
        reads = 0
        options(controller(candidates, "game.cso", defs(ExtensionsRow(retroArchSeed, gba, listOf("gba"))), onNameRead = { reads++ }))
        assertEquals(0, reads)
    }

    @Test
    fun autos_choice_and_the_launch_pick_are_unchanged_in_every_case() {
        val candidates = pspCandidates(ppsspp, ppssppGold)
        val goldId = candidates.first { it.packageName == ppssppGold }.id
        val variants = listOf(
            noOverlay,
            defs(ExtensionsRow(ppsspp, psp, listOf("pbp"))),
            defs(ExtensionsRow(ppsspp, psp, listOf("pbp")), ExtensionsRow(ppssppGold, psp, listOf("pbp"))),
            defs(FilenameRegexRow(ppsspp, psp, "^eboot\\.bin\$"), FilenameRegexRow(ppssppGold, psp, "^.*\\.cso\$")),
            defs(ExtensionsRow(ppsspp, psp, listOf("iso", "cso", "pbp")), FilenameRegexRow(ppssppGold, psp, "^(a+)+\$")),
        )
        val names = listOf(null, "game.iso", "game.cso", "game.pbp", "noext", "x".repeat(300))
        val stored = listOf<EmulatorId?>(null, goldId)
        for (override in stored) for (platformDefault in stored) for (d in variants) for (name in names) {
            val baseline = options(controller(candidates, name, noOverlay, override, platformDefault, withNamePort = false))
            val now = options(controller(candidates, name, d, override, platformDefault))
            val context = "override=$override default=$platformDefault name=$name"
            assertEquals(context, baseline.hasOverride, now.hasOverride)
            // Everything but the label is identical.
            assertEquals(context, baseline.candidates, now.candidates.map { it.copy(listsFile = null) })
            assertEquals(context, baseline.candidates.map { it.emulatorId }, now.candidates.map { it.emulatorId })
            assertEquals(context, baseline.candidates.map { it.isAutoPick }, now.candidates.map { it.isAutoPick })
            assertEquals(context, baseline.candidates.map { it.isLaunchPick }, now.candidates.map { it.isLaunchPick })
            assertEquals(context, baseline.candidates.map { it.selectable }, now.candidates.map { it.selectable })
        }
    }

    @Test
    fun a_game_whose_file_is_on_no_list_is_still_ready_and_still_launches() = runBlocking {
        val candidates = pspCandidates(ppsspp, ppssppGold)
        val both = defs(ExtensionsRow(ppsspp, psp, listOf("pbp")), ExtensionsRow(ppssppGold, psp, listOf("pbp")))
        val controller = controller(candidates, "game.cso", both)

        assertTrue(options(controller).noInstalledEmulatorListsFile)
        val readiness = controller.resolveReadiness(gameId, psp)
        assertTrue("an unlisted ROM stays Ready: $readiness", readiness is LaunchReadiness.Ready)
        assertEquals(ppsspp, (readiness as LaunchReadiness.Ready).target.emulator.packageName)
        val result = controller.launch(gameId, psp)
        assertTrue("and it launches", result.launched)
        assertEquals(readiness.target.emulator.id, (result.readiness as LaunchReadiness.Ready).target.emulator.id)
    }

    // ---- RetroArch's one list ------------------------------------------------------------------------------------

    @Test
    fun retroarch_cores_are_keyed_by_the_seed_package_whichever_build_is_installed() {
        for (host in listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")) {
            val cores = gbaCores(host)
            assertTrue("seven GBA cores for $host", cores.size >= 2)
            assertTrue("the candidates carry the installed build: $host", cores.all { it.packageName == host })
            assertTrue(cores.all { EmulatorSeedRegistry.acceptanceKeyFor(it) == retroArchSeed })

            val d = defs(ExtensionsRow(retroArchSeed, gba, listOf("gba", "agb")))
            val listed = options(controller(cores, "Game.GBA", d), gba)
            assertTrue("every core shares the platform's one list: $host", listed.candidates.all { it.listsFile == true })
            assertEquals("gba", listed.fileExtension)

            val unlisted = options(controller(cores, "game.zip", d), gba)
            assertTrue(unlisted.candidates.all { it.listsFile == false })
            assertTrue("the only emulators installed are cores with no zip", unlisted.noInstalledEmulatorListsFile)
        }
        // A standalone emulator keys by its own package.
        val ppssppProfile = pspCandidates(ppsspp).first { it.packageName == ppsspp }
        assertEquals(ppsspp, EmulatorSeedRegistry.acceptanceKeyFor(ppssppProfile))
    }

    @Test
    fun the_retroarch_key_is_the_named_seed_not_the_first_seed_that_has_cores() {
        assertEquals(retroArchSeed, EmulatorSeedRegistry.RETROARCH_SEED_PACKAGE)
        val seed = EmulatorSeedRegistry.seedForPackage(retroArchSeed)!!
        assertEquals("retroarch", seed.familyId)
        assertTrue("the named seed is the one with cores", seed.retroArchCoreByPlatform.isNotEmpty())

        val core = gbaCores("com.retroarch.ra32").first()
        assertEquals(retroArchSeed, EmulatorSeedRegistry.acceptanceKeyFor(core))
        // Another seed gaining cores, even sitting first in the table, moves nothing.
        val other = EmulatorSeedRegistry.Seed(
            packageName = "com.example.morecores",
            displayName = "More cores",
            locatorForm = LaunchLocatorForm.FILE_PATH,
            platforms = setOf(gba),
            retroArchCoreByPlatform = mapOf(gba to "extra"),
            familyId = "other",
        )
        assertEquals(retroArchSeed, EmulatorSeedRegistry.acceptanceKeyFor(core, listOf(other) + EmulatorSeedRegistry.SEEDS))
        assertEquals(retroArchSeed, EmulatorSeedRegistry.acceptanceKeyFor(core, EmulatorSeedRegistry.SEEDS + other))
        assertEquals(retroArchSeed, EmulatorSeedRegistry.acceptanceKeyFor(core, listOf(other)))
        // And a standalone candidate still keys by its own package whatever the table holds.
        val standalone = pspCandidates(ppsspp).first { it.packageName == ppsspp }
        assertEquals(ppsspp, EmulatorSeedRegistry.acceptanceKeyFor(standalone, listOf(other) + EmulatorSeedRegistry.SEEDS))
    }

    // ---- a group whose files Play can pick between ----------------------------------------------------------------

    @Test
    fun a_group_is_unlisted_only_when_none_of_its_files_is_listed_whichever_one_play_would_pick() {
        // One game, two regions as two files: the primary (the default launch member) is the Europe .cso, the other the USA .iso.
        val europe = entity("europe", "Game (Europe).cso", "/roms/psp/Game (Europe).cso", 0, gameId = "two-regions", platform = "psp", primary = true)
        val usa = entity("usa", "Game (USA).iso", "/roms/psp/Game (USA).iso", 0, gameId = "two-regions", platform = "psp", primary = false)
        val gatherer = RoomLaunchLocatorGatherer(fakeGameDao(europe, usa))
        val group = GameId("two-regions")
        assertEquals(listOf("Game (Europe).cso", "Game (USA).iso"), runBlocking { gatherer.launchMemberFileNames(group, null, 0) })
        assertEquals("the first name is the default launch member", "Game (Europe).cso", runBlocking { gatherer.launchMemberFileName(group, null, 0) })
        assertEquals("a chosen member is tested alone", listOf("Game (USA).iso"), runBlocking { gatherer.launchMemberFileNames(group, EntryKey("usa"), 0) })

        fun optionsFor(listed: List<String>, key: EntryKey? = null): OpenWithOptions {
            val candidates = pspCandidates(ppsspp)
            val controller = LaunchController(
                emulatorProvider = InstalledEmulatorProvider { candidates },
                locatorResolver = { _, _, _, _ -> LaunchLocatorResult("/roms/psp/x", LaunchLocatorForm.FILE_PATH) },
                overrideResolver = { null },
                overrideSetter = { _, _ -> Unit },
                overrideClearer = { Unit },
                biosResolver = { _, _, _ -> emptyList() },
                proofRecorder = { Unit },
                dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
                ioDispatcher = Dispatchers.Unconfined,
                launchMemberFileNames = gatherer::launchMemberFileNames,
                definitions = { defs(ExtensionsRow(ppsspp, psp, listed)) },
            )
            return runBlocking { controller.openWithOptions(group, psp, launchMemberEntryKey = key) }
        }
        fun ppssppLists(o: OpenWithOptions) = o.candidates.first { it.packageName == ppsspp }.listsFile

        // PPSSPP lists only .iso: the Europe .cso is the default, but Play can start the USA .iso, so it is NOT "doesn't list".
        val isoOnly = optionsFor(listOf("iso"))
        assertEquals(true, ppssppLists(isoOnly))
        assertFalse(isoOnly.noInstalledEmulatorListsFile)
        // Only .cso: the other file is the unlisted one, and the answer is the same.
        assertEquals(true, ppssppLists(optionsFor(listOf("cso"))))
        // Neither file is listed: now it is false, and the label names the default member's extension.
        val neither = optionsFor(listOf("chd"))
        assertEquals(false, ppssppLists(neither))
        assertTrue(neither.noInstalledEmulatorListsFile)
        assertEquals("cso", neither.fileExtension)
        // A caller that chose a member names what Play will start: that one alone.
        assertEquals(false, ppssppLists(optionsFor(listOf("iso"), key = EntryKey("europe"))))
        assertEquals(true, ppssppLists(optionsFor(listOf("iso"), key = EntryKey("usa"))))
    }

    // ---- a multi-disc member -------------------------------------------------------------------------------------

    @Test
    fun a_multi_disc_game_is_tested_by_its_own_disc_file_never_by_the_generated_playlist() {
        val temp = Files.createTempDirectory("mimir-acceptance-242").toFile()
        try {
            val romDir = File(temp, "ps1").apply { mkdirs() }
            val disc1 = entity("disc-1", "Game (Disc 1).cue", File(romDir, "Game (Disc 1).cue").absolutePath, 1)
            val disc2 = entity("disc-2", "Game (Disc 2).cue", File(romDir, "Game (Disc 2).cue").absolutePath, 2)
            val gatherer = RoomLaunchLocatorGatherer(fakeGameDao(disc1, disc2), GeneratedM3uWriter(File(temp, "playlists")))
            val multi = GameId("resident-evil-2")

            // The launch itself is handed a generated playlist ...
            val located = runBlocking { gatherer.resolve(multi, null, 0, LaunchLocatorForm.FILE_PATH) }
            assertTrue("the locator is the generated playlist: ${located?.locator}", located?.locator?.endsWith(".m3u") == true)
            // ... but the file under test is the disc's own.
            assertEquals("Game (Disc 1).cue", runBlocking { gatherer.launchMemberFileName(multi, null, 0) })
            assertEquals("Game (Disc 2).cue", runBlocking { gatherer.launchMemberFileName(multi, null, 2) })
            assertEquals("Game (Disc 2).cue", runBlocking { gatherer.launchMemberFileName(multi, EntryKey("disc-2"), 0) })
            assertNull("an unknown member names nothing", runBlocking { gatherer.launchMemberFileName(multi, EntryKey("gone"), 0) })
            assertNull(runBlocking { gatherer.launchMemberFileName(GameId("no-such-game"), null, 0) })

            val duck = "com.github.stenzek.duckstation"
            val ps1Candidates = EmulatorSeedRegistry.candidatesFor(PlatformId.PS1) { it == duck }.filter { it.packageName == duck }
            val withCue = LaunchController(
                emulatorProvider = InstalledEmulatorProvider { ps1Candidates },
                locatorResolver = { g, m, d, f -> gatherer.resolve(g, m, d, f) },
                overrideResolver = { null },
                overrideSetter = { _, _ -> Unit },
                overrideClearer = { Unit },
                biosResolver = { _, _, _ -> emptyList() },
                proofRecorder = { Unit },
                dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
                ioDispatcher = Dispatchers.Unconfined,
                launchMemberFileNames = gatherer::launchMemberFileNames,
                definitions = { defs(ExtensionsRow(duck, PlatformId.PS1, listOf("cue", "chd"))) },
            )
            val listedByCue = runBlocking { withCue.openWithOptions(multi, PlatformId.PS1) }
            assertEquals("cue", listedByCue.fileExtension)
            assertEquals(listOf(true), listedByCue.candidates.map { it.listsFile })

            // The same list without .cue: the disc is unlisted, although the m3u the launch is handed would be a different case.
            val m3uOnly = LaunchController(
                emulatorProvider = InstalledEmulatorProvider { ps1Candidates },
                locatorResolver = { g, m, d, f -> gatherer.resolve(g, m, d, f) },
                overrideResolver = { null },
                overrideSetter = { _, _ -> Unit },
                overrideClearer = { Unit },
                biosResolver = { _, _, _ -> emptyList() },
                proofRecorder = { Unit },
                dispatcher = LaunchDispatcher { LaunchOutcome.IntentSent },
                ioDispatcher = Dispatchers.Unconfined,
                launchMemberFileNames = gatherer::launchMemberFileNames,
                definitions = { defs(ExtensionsRow(duck, PlatformId.PS1, listOf("m3u"))) },
            )
            val unlisted = runBlocking { m3uOnly.openWithOptions(multi, PlatformId.PS1) }
            assertEquals(listOf(false), unlisted.candidates.map { it.listsFile })
            assertNotEquals("m3u", unlisted.fileExtension)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun an_existing_playlist_member_is_named_as_it_is() {
        val playlist = entity("playlist", "Game.m3u", "/roms/ps1/Game.m3u", 0, contentKind = "MULTI_DISC_PLAYLIST")
        val disc1 = entity("disc-1", "Game (Disc 1).cue", "/roms/ps1/Game (Disc 1).cue", 1)
        val disc2 = entity("disc-2", "Game (Disc 2).cue", "/roms/ps1/Game (Disc 2).cue", 2)
        val gatherer = RoomLaunchLocatorGatherer(fakeGameDao(disc1, disc2, playlist))
        assertEquals("Game.m3u", runBlocking { gatherer.launchMemberFileName(GameId("resident-evil-2"), null, 0) })
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private fun entity(
        entryKey: String,
        displayName: String,
        path: String,
        discNumber: Int,
        contentKind: String = "ROM",
        gameId: String = "resident-evil-2",
        platform: String = "ps1",
        primary: Boolean = discNumber <= 1,
    ): GameEntity = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = platform,
        contentKind = contentKind,
        displayName = displayName,
        title = "Resident Evil 2",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "NONE",
        locationKind = "EXTERNAL_REFERENCE",
        availability = "AVAILABLE",
        sizeBytes = 1_024,
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = path,
        relativePath = "$platform/$displayName",
        discNumber = discNumber,
        duplicateGroupId = null,
        isPrimaryMember = primary,
        duplicateGroupKind = null,
    )

    private fun fakeGameDao(vararg entities: GameEntity): GameDao {
        val stored = entities.associateBy { it.entryKey }
        return Proxy.newProxyInstance(GameDao::class.java.classLoader, arrayOf(GameDao::class.java)) { _, method, args ->
            when (method.name) {
                "byEntryKey" -> stored[args?.get(0) as String]
                "membersOfGame" -> {
                    val groupId = args?.get(0) as String
                    stored.values
                        .filter { it.gameId == groupId || it.duplicateGroupId == groupId }
                        .sortedWith(
                            compareBy<GameEntity> { it.availability != "AVAILABLE" }
                                .thenByDescending { it.isPrimaryMember }
                                .thenBy { it.discNumber },
                        )
                }
                else -> error("Unexpected GameDao call in OpenWithAcceptanceTest: ${method.name}")
            }
        } as GameDao
    }
}
