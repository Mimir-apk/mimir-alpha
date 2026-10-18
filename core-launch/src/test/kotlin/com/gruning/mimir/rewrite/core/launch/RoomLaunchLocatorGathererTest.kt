package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files

class RoomLaunchLocatorGathererTest {
    @Test
    fun contentUriUsesExactDocumentUriNeverTreeRoot() {
        val result = launchLocatorFrom(
            entity(
                sourceRootUri = "content://com.android.externalstorage.documents/tree/primary%3ARoms",
                sourceDocumentUri = "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fds%2Fwhite.nds",
            ),
            LaunchLocatorForm.CONTENT_URI,
        )

        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fds%2Fwhite.nds",
            result?.locator,
        )
        assertEquals(LaunchLocatorForm.CONTENT_URI, result?.form)
    }

    /**
     * A content-URI emulator must still launch a game that was indexed by PATH.
     *
     * Measured on the Odin 2026-09-06. Its only persisted SAF tree grant belongs to
     * `com.gruning.mimir.rewrite.devlab`; the production package holds none, so the scanner indexed the
     * SD by file path (it has all-files access) and every row came back with `sourceDocumentUri = null`.
     * Switch seeds declare `CONTENT_URI`, so this function returned null for all of them and every Switch
     * launch died as `MissingFile` -- while PS1, PS2 and GameCube, whose seeds are file-path forms, kept
     * working. That explains why file-path platforms launched while content-URI platforms failed.
     *
     * The file is right there and readable. `LaunchPathStrategy.SHAREABLE_URI_DATA` already hands such a
     * path to an emulator as a `content://` URI through Mimir's own FileProvider -- it is what Dolphin,
     * AetherSX2, Cemu and aPS3e have always used. Refusing to produce a locator at all is what made the
     * game unlaunchable, not any real inability to reach it.
     */
    @Test
    fun contentUriFallsBackToTheFilePathWhenNoDocumentUriWasIndexed() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri = null,
                managedPath = "/storage/6A7F-FCFE/ROMs/switch/Zelda TotK [0100F2C0115B6000][v0].nsp",
            ),
            LaunchLocatorForm.CONTENT_URI,
        )

        assertEquals(
            "a path-indexed game must still be launchable by a content-URI emulator",
            "/storage/6A7F-FCFE/ROMs/switch/Zelda TotK [0100F2C0115B6000][v0].nsp",
            result?.locator,
        )
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun contentUriMissingDocumentReturnsMissingEvenWhenTreeRootExists() {
        val result = launchLocatorFrom(
            entity(
                sourceRootUri = "content://com.android.externalstorage.documents/tree/primary%3ARoms",
                sourceDocumentUri = null,
            ),
            LaunchLocatorForm.CONTENT_URI,
        )

        assertNull(result)
    }

    @Test
    fun staleExplicitLaunchMemberRefusesInsteadOfLaunchingAnotherDisc() = runBlocking {
        val gatherer = RoomLaunchLocatorGatherer(
            fakeGameDao(
                entity(
                    entryKey = "disc-1",
                    gameId = "multi",
                    managedPath = "/roms/disc-1.chd",
                    contentKind = "DISC_MEMBER",
                ),
            ),
        )

        val result = gatherer.resolve(
            gameId = GameId("multi"),
            launchMemberEntryKey = EntryKey("stale-disc-2"),
            preferredDisc = 0,
            preferredForm = LaunchLocatorForm.FILE_PATH,
        )

        assertNull(result)
    }

    @Test
    fun filePathCanUseExternalStorageDocumentUriPath() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri = "content://com.android.externalstorage.documents/document/691E-7BEA%3AROMs%2FArcade%2Frobotbwl.zip",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/691E-7BEA/ROMs/Arcade/robotbwl.zip", result?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun filePathCanUseTreeScopedExternalStorageDocumentUriPath() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri =
                    "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs/document/691E-7BEA%3AROMs%2Farcade%2Frobotbwl.zip",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/691E-7BEA/ROMs/arcade/robotbwl.zip", result?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun filePathDecodesPrimaryVolumeSpacesAndPlusWithoutFormDecoding() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri =
                    "content://com.android.externalstorage.documents/document/primary%3AROMs%2FPSP%2FA%2BB%20Racing.iso",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/emulated/0/ROMs/PSP/A+B Racing.iso", result?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun filePathIgnoresQueryAndFragmentAfterDocumentId() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri =
                    "content://com.android.externalstorage.documents/document/691E-7BEA%3AROMs%2Farcade%2Frobotbwl.zip?token=abc#frag",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/691E-7BEA/ROMs/arcade/robotbwl.zip", result?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun filePathRejectsMalformedExternalStorageDocumentUri() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri =
                    "content://com.android.externalstorage.documents/document/primary%3AROMs%2Fbad%ZZname.zip",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertNull(result)
    }

    @Test
    fun filePathRejectsHomeExternalStorageDocumentUri() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri =
                    "content://com.android.externalstorage.documents/document/home%3AROMs%2Frobotbwl.zip",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertNull(result)
    }

    @Test
    fun filePathCanUsePrimaryRootDocumentUri() {
        val result = launchLocatorFrom(
            entity(
                sourceDocumentUri = "content://com.android.externalstorage.documents/document/primary%3A",
            ),
            LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/emulated/0", result?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun filePathRejectsNonExternalStorageDocumentUriWithoutManagedPath() {
        val result = launchLocatorFrom(
            entity(sourceDocumentUri = "content://com.example.provider/document/robotbwl.zip"),
            LaunchLocatorForm.FILE_PATH,
        )

        assertNull(result)
    }

    @Test
    fun eitherPrefersManagedPathThenDocumentUri() {
        val managed = launchLocatorFrom(
            entity(
                managedPath = "/storage/emulated/0/Mimir/Roms/ds/white.nds",
                sourceDocumentUri = "content://doc/white.nds",
            ),
            LaunchLocatorForm.EITHER,
        )
        val document = launchLocatorFrom(
            entity(sourceDocumentUri = "content://doc/white.nds"),
            LaunchLocatorForm.EITHER,
        )

        assertEquals("/storage/emulated/0/Mimir/Roms/ds/white.nds", managed?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, managed?.form)
        assertEquals("content://doc/white.nds", document?.locator)
        assertEquals(LaunchLocatorForm.CONTENT_URI, document?.form)
    }

    @Test
    fun explicitMemberFromSameGameIsHonored() = runBlocking {
        val primary = entity(
            entryKey = "game-1-disc-1",
            gameId = "game-1",
            managedPath = "/storage/emulated/0/Mimir/Roms/ps1/game-disc-1.chd",
            discNumber = 1,
            isPrimaryMember = true,
        )
        val chosen = entity(
            entryKey = "game-1-disc-2",
            gameId = "game-1",
            managedPath = "/storage/emulated/0/Mimir/Roms/ps1/game-disc-2.chd",
            discNumber = 2,
            isPrimaryMember = false,
        )
        val gatherer = RoomLaunchLocatorGatherer(fakeGameDao(primary, chosen))

        val result = gatherer.resolve(
            GameId("game-1"),
            EntryKey("game-1-disc-2"),
            0,
            LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/emulated/0/Mimir/Roms/ps1/game-disc-2.chd", result?.locator)
        assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
    }

    @Test
    fun explicitMemberFromDifferentGameRefusesInsteadOfLaunchingDefault() = runBlocking {
        val primary = entity(
            entryKey = "game-1-primary",
            gameId = "game-1",
            managedPath = "/storage/emulated/0/Mimir/Roms/gba/metroid.gba",
            isPrimaryMember = true,
        )
        val foreign = entity(
            entryKey = "game-2-primary",
            gameId = "game-2",
            managedPath = "/storage/emulated/0/Mimir/Roms/gba/golden-sun.gba",
            isPrimaryMember = true,
        )
        val gatherer = RoomLaunchLocatorGatherer(fakeGameDao(primary, foreign))

        val result = gatherer.resolve(
            GameId("game-1"),
            EntryKey("game-2-primary"),
            0,
            LaunchLocatorForm.FILE_PATH,
        )

        assertNull(result)
    }

    @Test
    fun missingPlaylistIsGeneratedFromEveryOrderedDisc() = runBlocking {
        val temp = Files.createTempDirectory("mimir-m3u-test").toFile()
        try {
            val disc1 = entity(
                entryKey = "re2-leon",
                gameId = "resident-evil-2",
                displayName = "Resident Evil 2 (Disc 1).chd",
                managedPath = "/storage/roms/Resident Evil 2 (Disc 1).chd",
                discNumber = 1,
                contentKind = "DISC_MEMBER",
            )
            val disc2 = entity(
                entryKey = "re2-claire",
                gameId = "resident-evil-2",
                displayName = "Resident Evil 2 (Disc 2).chd",
                managedPath = "/storage/roms/Resident Evil 2 (Disc 2).chd",
                discNumber = 2,
                contentKind = "DISC_MEMBER",
                isPrimaryMember = false,
            )
            val gatherer = RoomLaunchLocatorGatherer(
                fakeGameDao(disc1, disc2),
                GeneratedM3uWriter(temp),
            )

            val result = gatherer.resolve(
                GameId("resident-evil-2"),
                launchMemberEntryKey = null,
                preferredDisc = 0,
                preferredForm = LaunchLocatorForm.FILE_PATH,
            )

            val generated = result?.locator?.let { java.io.File(it) }
            assertEquals(LaunchLocatorForm.FILE_PATH, result?.form)
            assertTrue(generated?.isFile == true)
            assertEquals(
                "#EXTM3U\n" +
                    "/storage/roms/Resident Evil 2 (Disc 1).chd\n" +
                    "/storage/roms/Resident Evil 2 (Disc 2).chd\n",
                generated?.readText(),
            )
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun contentUriRequirementNeverReturnsAGeneratedFilePlaylist() = runBlocking {
        val temp = Files.createTempDirectory("mimir-m3u-content-form").toFile()
        try {
            val disc1 = entity(
                entryKey = "disc-1",
                gameId = "set",
                managedPath = "/storage/roms/Set (Disc 1).chd",
                sourceDocumentUri = "content://roms/set-disc-1",
                discNumber = 1,
                contentKind = "DISC_MEMBER",
            )
            val disc2 = entity(
                entryKey = "disc-2",
                gameId = "set",
                managedPath = "/storage/roms/Set (Disc 2).chd",
                sourceDocumentUri = "content://roms/set-disc-2",
                discNumber = 2,
                contentKind = "DISC_MEMBER",
                isPrimaryMember = false,
            )

            val result = RoomLaunchLocatorGatherer(
                fakeGameDao(disc1, disc2),
                GeneratedM3uWriter(temp),
            ).resolve(
                GameId("set"),
                launchMemberEntryKey = null,
                preferredDisc = 0,
                preferredForm = LaunchLocatorForm.CONTENT_URI,
            )

            assertEquals("content://roms/set-disc-1", result?.locator)
            assertEquals(LaunchLocatorForm.CONTENT_URI, result?.form)
            assertTrue(temp.listFiles().orEmpty().isEmpty())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun existingPlaylistIsPreferredWithoutGeneratingAReplacement() = runBlocking {
        val playlist = entity(
            entryKey = "set-playlist",
            gameId = "set",
            displayName = "Set.m3u",
            managedPath = "/storage/roms/Set.m3u",
            discNumber = 0,
            contentKind = "MULTI_DISC_PLAYLIST",
        )
        val disc1 = entity(
            entryKey = "set-disc-1",
            gameId = "set-disc-1",
            displayName = "Set (Disc 1).chd",
            managedPath = "/storage/roms/Set (Disc 1).chd",
            discNumber = 1,
            contentKind = "DISC_MEMBER",
            isPrimaryMember = false,
            duplicateGroupId = "set",
        )
        val disc2 = disc1.copy(
            entryKey = "set-disc-2",
            gameId = "set-disc-2",
            displayName = "Set (Disc 2).chd",
            managedPath = "/storage/roms/Set (Disc 2).chd",
            discNumber = 2,
        )

        val result = RoomLaunchLocatorGatherer(fakeGameDao(playlist, disc1, disc2)).resolve(
            GameId("set"),
            launchMemberEntryKey = null,
            preferredDisc = 0,
            preferredForm = LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/roms/Set.m3u", result?.locator)
    }

    @Test
    fun ambiguousVersionSetsNeverGenerateAMixedPlaylist() = runBlocking {
        val temp = Files.createTempDirectory("mimir-m3u-variant-safety").toFile()
        try {
            val usDisc1 = entity(
                entryKey = "us-disc-1",
                gameId = "two-version-set",
                displayName = "Game (USA) (Disc 1).chd",
                managedPath = "/storage/roms/Game (USA) (Disc 1).chd",
                discNumber = 1,
                contentKind = "DISC_MEMBER",
            )
            val usDisc2 = entity(
                entryKey = "us-disc-2",
                gameId = "two-version-set",
                displayName = "Game (USA) (Disc 2).chd",
                managedPath = "/storage/roms/Game (USA) (Disc 2).chd",
                discNumber = 2,
                contentKind = "DISC_MEMBER",
                isPrimaryMember = false,
            )
            val jpDisc1 = entity(
                entryKey = "jp-disc-1",
                gameId = "two-version-set",
                displayName = "Game (Japan) (Disc 1).chd",
                managedPath = "/storage/roms/Game (Japan) (Disc 1).chd",
                discNumber = 1,
                contentKind = "DISC_MEMBER",
                isPrimaryMember = false,
            )
            val jpDisc2 = entity(
                entryKey = "jp-disc-2",
                gameId = "two-version-set",
                displayName = "Game (Japan) (Disc 2).chd",
                managedPath = "/storage/roms/Game (Japan) (Disc 2).chd",
                discNumber = 2,
                contentKind = "DISC_MEMBER",
                isPrimaryMember = false,
            )
            val gatherer = RoomLaunchLocatorGatherer(
                fakeGameDao(usDisc1, usDisc2, jpDisc1, jpDisc2),
                GeneratedM3uWriter(temp),
            )

            val defaultResult = gatherer.resolve(
                GameId("two-version-set"),
                launchMemberEntryKey = null,
                preferredDisc = 0,
                preferredForm = LaunchLocatorForm.FILE_PATH,
            )
            val exactResult = gatherer.resolve(
                GameId("two-version-set"),
                launchMemberEntryKey = EntryKey("jp-disc-2"),
                preferredDisc = 0,
                preferredForm = LaunchLocatorForm.FILE_PATH,
            )

            assertEquals("/storage/roms/Game (USA) (Disc 1).chd", defaultResult?.locator)
            assertEquals("/storage/roms/Game (Japan) (Disc 2).chd", exactResult?.locator)
            assertTrue(temp.listFiles().orEmpty().isEmpty())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun generatedPlaylistRejectsLineInjection() {
        val temp = Files.createTempDirectory("mimir-m3u-safety").toFile()
        try {
            val result = GeneratedM3uWriter(temp).write(
                "set",
                listOf("/storage/roms/disc-1.chd", "/storage/roms/disc-2.chd\n/other/file"),
            )

            assertNull(result)
            assertTrue(temp.listFiles().orEmpty().isEmpty())
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun preferredDiscBypassesAutomaticPlaylistAndLaunchesThatMember() = runBlocking {
        val disc1 = entity(
            entryKey = "disc-1",
            gameId = "game-1",
            managedPath = "/storage/roms/disc-1.chd",
            discNumber = 1,
            contentKind = "DISC_MEMBER",
        )
        val disc2 = entity(
            entryKey = "disc-2",
            gameId = "game-1",
            managedPath = "/storage/roms/disc-2.chd",
            discNumber = 2,
            contentKind = "DISC_MEMBER",
            isPrimaryMember = false,
        )

        val result = RoomLaunchLocatorGatherer(fakeGameDao(disc1, disc2)).resolve(
            GameId("game-1"),
            launchMemberEntryKey = null,
            preferredDisc = 2,
            preferredForm = LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/roms/disc-2.chd", result?.locator)
    }

    @Test
    fun unavailablePrimaryFallsThroughToAvailableAliasMember() = runBlocking {
        val unavailablePrimary = entity(
            entryKey = "card-copy",
            gameId = "logical-game",
            managedPath = "/storage/EJECTED/roms/game.gba",
            availability = "STORAGE_UNAVAILABLE",
        )
        val availableAlias = entity(
            entryKey = "internal-copy",
            gameId = "internal-copy",
            duplicateGroupId = "logical-game",
            managedPath = "/storage/emulated/0/roms/game.gba",
            isPrimaryMember = false,
            availability = "AVAILABLE",
        )

        val result = RoomLaunchLocatorGatherer(
            fakeGameDao(unavailablePrimary, availableAlias),
        ).resolve(
            GameId("logical-game"),
            launchMemberEntryKey = null,
            preferredDisc = 0,
            preferredForm = LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/storage/emulated/0/roms/game.gba", result?.locator)
    }

    @Test
    fun canonicalP2LexicalOrderCannotSteerDefaultLaunchMember() = runBlocking {
        val physicalFirstKey = physicalResidentKey("aa", "same.gba")
        val physicalSecondKey = physicalResidentKey("z", "same.gba")
        assertTrue(physicalFirstKey > physicalSecondKey)
        val physicalSecond = entity(
            entryKey = physicalSecondKey,
            gameId = "codec-order",
            sourceRootUri = "z",
            relativePath = "same.gba",
            displayName = "Same.gba",
            managedPath = "/roms/physical-second.gba",
            discNumber = 0,
            isPrimaryMember = false,
        )
        val physicalFirst = entity(
            entryKey = physicalFirstKey,
            gameId = "codec-order",
            sourceRootUri = "aa",
            relativePath = "same.gba",
            displayName = "Same.gba",
            managedPath = "/roms/physical-first.gba",
            discNumber = 0,
            isPrimaryMember = false,
        )

        val result = RoomLaunchLocatorGatherer(
            fakeGameDao(physicalSecond, physicalFirst),
        ).resolve(
            GameId("codec-order"),
            launchMemberEntryKey = null,
            preferredDisc = 0,
            preferredForm = LaunchLocatorForm.FILE_PATH,
        )

        assertEquals("/roms/physical-first.gba", result?.locator)
    }

    private fun entity(
        entryKey: String = "entry-1",
        gameId: String = "game-1",
        platformId: String = "nds",
        displayName: String = "Pokemon White.nds",
        title: String = "Pokemon White",
        sourceRootUri: String = "root",
        sourceDocumentUri: String? = null,
        managedPath: String? = null,
        relativePath: String = "ds/Pokemon White.nds",
        discNumber: Int = 1,
        isPrimaryMember: Boolean = true,
        contentKind: String = "ROM",
        duplicateGroupId: String? = null,
        availability: String = "AVAILABLE",
    ): GameEntity = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = platformId,
        contentKind = contentKind,
        displayName = displayName,
        title = title,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "NONE",
        locationKind = "EXTERNAL_REFERENCE",
        availability = availability,
        sizeBytes = 1_024,
        contentHash = null,
        sourceRootUri = sourceRootUri,
        sourceDocumentUri = sourceDocumentUri,
        managedPath = managedPath,
        relativePath = relativePath,
        discNumber = discNumber,
        duplicateGroupId = duplicateGroupId,
        isPrimaryMember = isPrimaryMember,
        duplicateGroupKind = null,
    )

    private fun fakeGameDao(vararg entities: GameEntity): GameDao {
        val stored = entities.associateBy { it.entryKey }
        return Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
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
                else -> error("Unexpected GameDao call in RoomLaunchLocatorGathererTest: ${method.name}")
            }
        } as GameDao
    }
}
