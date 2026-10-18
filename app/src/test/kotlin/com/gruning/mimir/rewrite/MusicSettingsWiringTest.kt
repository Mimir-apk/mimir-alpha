package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.CustomMusicFallback
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.OutputFormat
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.MimirMusicHours
import com.gruning.mimir.rewrite.core.audio.MimirMusicOrder
import com.gruning.mimir.rewrite.core.audio.MimirMusicSource
import com.gruning.mimir.rewrite.core.audio.MimirNowPlaying
import com.gruning.mimir.rewrite.core.audio.MusicLibraryError
import com.gruning.mimir.rewrite.core.audio.MusicLibraryState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.feature.settings.GameMusicDownloadReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicDetailRowIds
import com.gruning.mimir.rewrite.feature.settings.MusicFadeRowId
import com.gruning.mimir.rewrite.feature.settings.MusicHoursRowId
import com.gruning.mimir.rewrite.feature.settings.MusicHoursValue
import com.gruning.mimir.rewrite.feature.settings.MusicOrderRowId
import com.gruning.mimir.rewrite.feature.settings.MusicOutsideMimirRowId
import com.gruning.mimir.rewrite.feature.settings.MusicScreenOffRowId
import com.gruning.mimir.rewrite.feature.settings.MusicUnplugRowId
import com.gruning.mimir.rewrite.feature.settings.MusicKeepMusicInGamesRowId
import com.gruning.mimir.rewrite.feature.settings.MusicEqualizerRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetCancelRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetConfirmRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderRowId
import com.gruning.mimir.rewrite.feature.settings.MusicGameDownloadRowId
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundRowId
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourRowId
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourRowIds
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundValue
import com.gruning.mimir.rewrite.feature.settings.MusicOrderValue
import com.gruning.mimir.rewrite.feature.settings.MusicPlayerEnabledRowId
import com.gruning.mimir.rewrite.feature.settings.MusicRequestsTodayReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicOtherPlayersRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSongTitlesRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSourceRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSourceValue
import com.gruning.mimir.rewrite.feature.settings.MusicVolumeRowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings › Music's app-side seams (`MusicSettingsWiring.kt`, spec 2026-09-30): the engine-to-pane mappings,
 * the dial's percent arithmetic, X's one claim, and the row-command table every NOW row dispatches through.
 */
class MusicSettingsWiringTest {

    private val idle = GameMusicDownloadState.Idle(have = 3, total = 10)

    // ── the row-command table: every NOW row, exactly one command ─────────────────────────────────────

    @Test
    fun `every Music row maps to a command and a foreign row maps to none`() {
        MusicDetailRowIds.forEach { rowId ->
            assertNotNull("$rowId must dispatch", musicRowCommand(rowId, gameMusic = idle))
        }
        MusicBehaviourRowIds.forEach { rowId ->
            assertNotNull("the Behaviour page row $rowId must dispatch too", musicRowCommand(rowId, gameMusic = idle))
        }
        assertNull(musicRowCommand("appearance/theme", gameMusic = idle))
        assertNull("the prefix alone is not a row", musicRowCommand("music/", gameMusic = idle))
        // The rows that were folded away no longer dispatch: a stale id does nothing rather than something else.
        listOf("music/player/nowplaying", "music/custom/rescan", "music/game/launchsound/volume").forEach {
            assertNull("$it is gone", musicRowCommand(it, gameMusic = idle))
        }
    }

    @Test
    fun `each row's command is the spec's`() {
        fun command(rowId: String, gameMusic: GameMusicDownloadState = idle) =
            musicRowCommand(rowId, gameMusic)

        assertEquals(MusicRowCommand.ToggleMusic, command(MusicPlayerEnabledRowId))
        assertEquals(MusicRowCommand.OpenSourcePicker, command(MusicSourceRowId))
        assertEquals(MusicRowCommand.OpenBehaviour, command(MusicBehaviourRowId))
        assertEquals(MusicRowCommand.OpenVolumeDial(MusicVolumeDialTarget.MUSIC), command(MusicVolumeRowId))
        assertEquals(MusicRowCommand.ToggleSongTitles, command(MusicSongTitlesRowId))
        assertEquals("A opens the notification-access page", MusicRowCommand.OpenNotificationAccess, command(MusicOtherPlayersRowId))
        assertEquals(MusicRowCommand.PickFolder, command(MusicFolderRowId))
        assertEquals("A on Launch sound opens the five steps", MusicRowCommand.OpenLaunchSoundPicker, command(MusicLaunchSoundRowId))
        assertEquals(MusicRowCommand.ToggleFade, command(MusicFadeRowId))
        assertEquals(MusicRowCommand.OpenHoursPicker, command(MusicHoursRowId))
        assertEquals(MusicRowCommand.ToggleOutsideMimir, command(MusicOutsideMimirRowId))
        assertEquals(MusicRowCommand.ToggleScreenOffPause, command(MusicScreenOffRowId))
        assertEquals(MusicRowCommand.ToggleUnplugPause, command(MusicUnplugRowId))
        assertEquals(MusicRowCommand.ToggleKeepMusicInGames, command(MusicKeepMusicInGamesRowId))
        assertEquals(MusicRowCommand.GameDownload(MusicGameDownloadAction.START), command(MusicGameDownloadRowId))
        // The Forget folder confirmation's two rows (Y on the folder row opens it).
        assertEquals(MusicRowCommand.CancelFolderForget, command(MusicFolderForgetCancelRowId))
        assertEquals(MusicRowCommand.ConfirmFolderForget, command(MusicFolderForgetConfirmRowId))
        // Play on highlight is hidden until it ships (update): no row, so no command at all.
        assertNull("Play on highlight has no row", command("music/game/highlight"))
    }

    @Test
    fun `the download row's A follows the engine's state`() {
        assertEquals(MusicGameDownloadAction.START, musicGameDownloadAction(idle))
        assertEquals(
            MusicGameDownloadAction.CONNECT,
            musicGameDownloadAction(idle.copy(block = GameMusicBlock.NotConnected)),
        )
        assertEquals(
            MusicGameDownloadAction.WAIT,
            musicGameDownloadAction(idle.copy(block = GameMusicBlock.EnrichRunning)),
        )
        assertEquals(
            MusicGameDownloadAction.CANCEL,
            musicGameDownloadAction(GameMusicDownloadState.Running(done = 2, total = 10, written = 1)),
        )
        assertEquals(
            "Cancel stays reachable whatever the block says",
            MusicGameDownloadAction.CANCEL,
            musicGameDownloadAction(GameMusicDownloadState.Running(2, 10, 1, block = GameMusicBlock.EnrichRunning)),
        )
        assertEquals(
            "pressing again after a batch resumes the missing ones",
            MusicGameDownloadAction.START,
            musicGameDownloadAction(GameMusicDownloadState.Finished(written = 5, skipped = 1, failed = 0)),
        )
    }

    @Test
    fun `X rescans and Y forgets only on the folder row and only when one is chosen, and the footer names each exactly then`() {
        assertTrue(musicRowSecondaryRescans(MusicFolderRowId, hasFolder = true))
        assertFalse(musicRowSecondaryRescans(MusicFolderRowId, hasFolder = false))
        assertFalse(musicRowSecondaryRescans(MusicSourceRowId, hasFolder = true))
        assertFalse(musicRowSecondaryRescans(null, hasFolder = true))
        assertFalse("a retired id is not the folder row", musicRowSecondaryRescans("music/custom/rescan", hasFolder = true))

        assertTrue(musicRowTertiaryForgets(MusicFolderRowId, hasFolder = true))
        assertFalse(musicRowTertiaryForgets(MusicFolderRowId, hasFolder = false))
        assertFalse(musicRowTertiaryForgets(MusicSourceRowId, hasFolder = true))
        assertFalse(musicRowTertiaryForgets(null, hasFolder = true))
        assertFalse("a retired id is not the folder row", musicRowTertiaryForgets("music/custom/rescan", hasFolder = true))

        assertEquals(
            "both keys, with the etched keycaps' tokens, in this order",
            listOf(ShellFooterHint(token = "X", label = "Rescan"), ShellFooterHint(token = "Y", label = "Forget")),
            musicFooterHints(MusicFolderRowId, hasFolder = true),
        )
        assertEquals("no folder: X and Y are unclaimed, so the legend names neither", emptyList<ShellFooterHint>(), musicFooterHints(MusicFolderRowId, hasFolder = false))
        assertEquals("on any other row the legend is the shell own", emptyList<ShellFooterHint>(), musicFooterHints(MusicVolumeRowId, hasFolder = true))
        assertEquals("with the rail focused there is no row", emptyList<ShellFooterHint>(), musicFooterHints(null, hasFolder = true))
    }

    @Test
    fun `Y asks first and only the confirmation's own row forgets the folder, and X never does`() {
        val file = java.io.File(
            listOf(
                "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsCoordinator.kt",
                "src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsCoordinator.kt",
            ).first { java.io.File(it).isFile },
        )
        val source = file.readText()
        val tertiary = source.substringAfter("val onRowTertiary: (String) -> Boolean = { rowId ->").substringBefore("val onRescanFolder")
        assertTrue("Y opens the confirmation", tertiary.contains("openMusicFolderForget()"))
        assertFalse("Y does not forget on its own press", tertiary.contains(".forget("))
        val secondary = source.substringAfter("val onRowSecondary: (String) -> Boolean = ").substringBefore("// Y on the Music folder row opens")
        assertFalse("X never forgets", secondary.contains(".forget("))
        assertTrue("X rescans", secondary.contains("MimirMusicLibrary.rescan(context)"))
        assertEquals("one place forgets the folder", 1, Regex("""MimirMusicLibrary\.forget\(""").findAll(source).count())
        val confirm = source.substringAfter("fun confirmMusicFolderForget()").substringBefore("fun runMusicRowCommand")
        assertTrue(confirm.contains("MimirMusicLibrary.forget(context)"))
        assertTrue("and the player is told", confirm.contains("Music folder forgotten"))
        assertTrue(source.contains("MusicRowCommand.ConfirmFolderForget -> confirmMusicFolderForget()"))
    }

    // ── the dial's arithmetic ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stored level opens on its nearest detent and a detent stores back as a fraction`() {
        assertEquals(50, musicVolumePercent(0.5f))
        assertEquals(35, musicVolumePercent(0.37f))
        assertEquals(100, musicVolumePercent(1f))
        assertEquals(0, musicVolumePercent(-0.2f))
        assertEquals(0.55f, musicVolumeFraction(55))
        assertEquals(1f, musicVolumeFraction(120))
        assertEquals("the launch sound's default is full", 100, musicVolumePercent(1.0f))
    }

    @Test
    fun `the dial targets carry the rows' own words`() {
        assertEquals("Volume", MusicVolumeDialTarget.MUSIC.title)
        assertEquals("Also in Utility as Music volume.", MusicVolumeDialTarget.MUSIC.subtitle)
        assertEquals("the Launch sound is a choice of five steps now, so the dial has the one target", listOf(MusicVolumeDialTarget.MUSIC), MusicVolumeDialTarget.entries)
    }

    // ── Launch sound: one choice over the two stored preferences ─────────────────────────────────────────

    @Test
    fun `a step is written as the toggle and the level, and Off leaves the level alone`() {
        assertEquals(MusicLaunchSoundWrite(enabled = false, volume = null), musicLaunchSoundWrite(MusicLaunchSoundValue.OFF))
        assertEquals(MusicLaunchSoundWrite(enabled = true, volume = 0.25f), musicLaunchSoundWrite(MusicLaunchSoundValue.QUARTER))
        assertEquals(MusicLaunchSoundWrite(enabled = true, volume = 0.5f), musicLaunchSoundWrite(MusicLaunchSoundValue.HALF))
        assertEquals(MusicLaunchSoundWrite(enabled = true, volume = 0.75f), musicLaunchSoundWrite(MusicLaunchSoundValue.THREE_QUARTERS))
        assertEquals(MusicLaunchSoundWrite(enabled = true, volume = 1f), musicLaunchSoundWrite(MusicLaunchSoundValue.FULL))
    }

    @Test
    fun `the stored toggle and level read as the step, and a step survives a write and a read`() {
        // The two stored preferences as they were before the merge (the toggle, and a level the dial had snapped to five percent).
        assertEquals(MusicLaunchSoundValue.FULL, musicLaunchSoundValue(enabled = true, volume = 1f))
        assertEquals(MusicLaunchSoundValue.OFF, musicLaunchSoundValue(enabled = false, volume = 1f))
        assertEquals(MusicLaunchSoundValue.OFF, musicLaunchSoundValue(enabled = false, volume = 0.4f))
        assertEquals("a level between steps rounds to the nearest", MusicLaunchSoundValue.HALF, musicLaunchSoundValue(enabled = true, volume = 0.4f))
        assertEquals(MusicLaunchSoundValue.QUARTER, musicLaunchSoundValue(enabled = true, volume = 0.3f))
        assertEquals(MusicLaunchSoundValue.THREE_QUARTERS, musicLaunchSoundValue(enabled = true, volume = 0.7f))
        // Off is only the toggle off: the toggle on never reads as Off, whatever the stored level (shared-volume behavior).
        assertEquals("on at a level of zero reads as the quietest step", MusicLaunchSoundValue.QUARTER, musicLaunchSoundValue(enabled = true, volume = 0f))
        assertEquals(MusicLaunchSoundValue.QUARTER, musicLaunchSoundValue(enabled = true, volume = 0.05f))
        assertEquals(MusicLaunchSoundValue.QUARTER, musicLaunchSoundValue(enabled = true, volume = 0.12f))
        (0..100).forEach { percent ->
            assertNotEquals("on at $percent% is never Off", MusicLaunchSoundValue.OFF, musicLaunchSoundValue(enabled = true, volume = percent / 100f))
            assertEquals("off at $percent% is always Off", MusicLaunchSoundValue.OFF, musicLaunchSoundValue(enabled = false, volume = percent / 100f))
        }

        // Both ways: write a step, read the pair back, and get the step.
        MusicLaunchSoundValue.entries.forEach { step ->
            val write = musicLaunchSoundWrite(step)
            val storedVolume = write.volume ?: 0.4f // Off keeps whatever level was stored
            assertEquals("$step", step, musicLaunchSoundValue(write.enabled, storedVolume))
        }
    }

    @Test
    fun `the read model carries the step, built from the two stored preferences`() {
        fun step(enabled: Boolean, volume: Float) = musicReadModel(
            musicEnabled = true, nowPlaying = null, source = MimirMusicSource.MIMIR, order = MimirMusicOrder.IN_ORDER, ambientVolume = 0.5f,
            songTitlesEnabled = true, library = MusicLibraryState(), launchSoundEnabled = enabled, launchSoundVolume = volume,
            gameMusic = idle, requestsToday = null, playOnHighlight = false, nowMillis = 0L,
        ).launchSound
        assertEquals(MusicLaunchSoundValue.FULL, step(true, 1f))
        assertEquals(MusicLaunchSoundValue.HALF, step(true, 0.5f))
        assertEquals(MusicLaunchSoundValue.OFF, step(false, 0.5f))
    }

    @Test
    fun `the seven Behaviour switches dispatch the commands the page rows always did`() {
        assertEquals(
            listOf(
                MusicRowCommand.ToggleSongTitles,
                MusicRowCommand.ToggleFade,
                MusicRowCommand.OpenHoursPicker,
                MusicRowCommand.ToggleOutsideMimir,
                MusicRowCommand.ToggleScreenOffPause,
                MusicRowCommand.ToggleUnplugPause,
                MusicRowCommand.OpenNotificationAccess,
            ),
            MusicBehaviourRowIds.map { musicRowCommand(it, idle) },
        )
    }

    // ── engine to pane ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `source and order map both ways and the labels are words`() {
        MimirMusicSource.entries.forEach { source ->
            assertEquals(source, mimirMusicSource(musicSourceValue(source)))
        }
        assertEquals(MusicOrderValue.IN_ORDER, musicOrderValue(MimirMusicOrder.IN_ORDER))
        assertEquals(MusicOrderValue.SHUFFLE, musicOrderValue(MimirMusicOrder.SHUFFLE))
        assertEquals("Mimir", musicSourceLabel(MimirMusicSource.MIMIR))
        assertEquals("Music on this device", musicSourceLabel(MimirMusicSource.CUSTOM))
    }

    @Test
    fun `now playing reads the title and a source word and a bed is a single track`() {
        val bed = musicNowPlayingReadModel(MimirNowPlaying("Fallen Hero", MimirMusicSource.MIMIR))
        assertNotNull(bed)
        assertEquals("Fallen Hero", bed!!.title)
        assertEquals("Mimir", bed.source)
        assertTrue(bed.singleTrack)
        val track = musicNowPlayingReadModel(MimirNowPlaying("Overworld", MimirMusicSource.CUSTOM))
        assertFalse(track!!.singleTrack)
        assertNull(musicNowPlayingReadModel(null))
    }

    @Test
    fun `the folder reads friendly with the index's figures and its error in words`() {
        assertNull(musicFolderReadModel(MusicLibraryState(), nowMillis = 1_000L))
        val state = MusicLibraryState(
            treeUri = "file:///storage/emulated/0/ROMs/music",
            trackCount = 42,
            lastScanMillis = 60_000L,
            scanning = false,
            error = MusicLibraryError.GRANT_LOST,
        )
        val folder = musicFolderReadModel(state, nowMillis = 90_000L)!!
        assertFalse("never the raw URI", folder.friendlyPath.contains("file://"))
        assertTrue(folder.friendlyPath.endsWith("music"))
        assertEquals(42, folder.trackCount)
        assertEquals("Scanned just now", folder.lastScanLabel)
        assertEquals("Mimir can no longer read this folder. Choose it again.", folder.error)
        assertEquals("This folder couldn’t be read. Try a rescan.", musicLibraryErrorCopy(MusicLibraryError.UNREADABLE))
    }

    @Test
    fun `the last-scan label steps from just now to days`() {
        val now = 10L * 24 * 60 * 60_000L
        assertNull(musicLastScanLabel(null, now))
        assertEquals("Scanned just now", musicLastScanLabel(now - 30_000L, now))
        assertEquals("Scanned 5 min ago", musicLastScanLabel(now - 5 * 60_000L, now))
        assertEquals("Scanned 3 h ago", musicLastScanLabel(now - 3 * 60 * 60_000L, now))
        assertEquals("Scanned 1 day ago", musicLastScanLabel(now - 25 * 60 * 60_000L, now))
        assertEquals("Scanned 2 days ago", musicLastScanLabel(now - 49 * 60 * 60_000L, now))
        assertEquals("a clock that went backwards is just now", "Scanned just now", musicLastScanLabel(now + 60_000L, now))
    }

    @Test
    fun `the requests-today figure carries the age of the read it came from and is unknown when never read`() {
        val now = 10L * 24 * 60 * 60_000L
        assertNull(musicRequestsTodayReadModel(null, now))
        assertEquals(
            MusicRequestsTodayReadModel(count = 40, ageLabel = "12 min ago"),
            musicRequestsTodayReadModel(ArtworkConnectController.RequestsTodayReading(40, now - 12 * 60_000L), now),
        )
        assertEquals("just now", musicRequestsTodayReadModel(ArtworkConnectController.RequestsTodayReading(1, now), now)?.ageLabel)
    }

    @Test
    fun `the shared gate reads both ways and names what is running`() {
        assertNull(artworkDownloadBlockedCopy(ArtworkOperation.NONE))
        assertNull(artworkDownloadBlockedCopy(ArtworkOperation.ENRICH))
        assertEquals("Game music is downloading.", artworkDownloadBlockedCopy(ArtworkOperation.GAME_MUSIC))
        assertEquals("Artwork is downloading.", ArtworkDownloadingCopy)
        assertTrue(artworkEntryMayStart(ArtworkOperation.NONE))
        assertTrue("enrich claims are shared", artworkEntryMayStart(ArtworkOperation.ENRICH))
        assertFalse(artworkEntryMayStart(ArtworkOperation.GAME_MUSIC))
        assertTrue(gameMusicEntryMayStart(ArtworkOperation.NONE))
        assertFalse(gameMusicEntryMayStart(ArtworkOperation.ENRICH))
        assertFalse("a second music batch is the engine's own refusal, not this gate's", !gameMusicEntryMayStart(ArtworkOperation.GAME_MUSIC))
    }

    @Test
    fun `the batch state maps with a running batch first and a block over an idle or finished one`() {
        val reading = MusicRequestsTodayReadModel(count = 40, ageLabel = "just now")
        assertEquals(
            GameMusicDownloadReadModel.Idle(have = 3, total = 10, requestsToday = reading),
            gameMusicDownloadReadModel(idle, requestsToday = reading),
        )
        assertEquals(
            GameMusicDownloadReadModel.Idle(have = 3, total = 10, requestsToday = null),
            gameMusicDownloadReadModel(idle, requestsToday = null),
        )
        assertEquals(
            "total 0 is the engine's not-counted-yet, never a 0 of 0",
            GameMusicDownloadReadModel.Unavailable,
            gameMusicDownloadReadModel(GameMusicDownloadState.Idle(have = 0, total = 0), requestsToday = reading),
        )
        assertEquals(
            GameMusicDownloadReadModel.NotConnected,
            gameMusicDownloadReadModel(idle.copy(block = GameMusicBlock.NotConnected), requestsToday = reading),
        )
        assertEquals(
            GameMusicDownloadReadModel.EnrichRunning,
            gameMusicDownloadReadModel(
                GameMusicDownloadState.Finished(1, 0, 0, block = GameMusicBlock.EnrichRunning),
                requestsToday = null,
            ),
        )
        assertEquals(
            GameMusicDownloadReadModel.Running(done = 2, total = 10, written = 1),
            gameMusicDownloadReadModel(GameMusicDownloadState.Running(2, 10, 1, block = GameMusicBlock.EnrichRunning), null),
        )
        assertEquals(
            GameMusicDownloadReadModel.Finished(written = 5, skipped = 2, failed = 1),
            gameMusicDownloadReadModel(GameMusicDownloadState.Finished(5, 2, 1), null),
        )
    }

    @Test
    fun `the card hears which game is being looked up, that Cancel was pressed, that a run was stopped, and why games failed`() {
        assertEquals(
            GameMusicDownloadReadModel.Running(done = 1, total = 4, written = 0, currentIndex = 2, current = "Chrono Trigger", stopping = false),
            gameMusicDownloadReadModel(
                GameMusicDownloadState.Running(done = 1, total = 4, written = 0, currentIndex = 2, current = "Chrono Trigger"),
                null,
            ),
        )
        assertTrue(
            "Cancel pressed is Stopping on the row, and a block never hides a running batch",
            (
                gameMusicDownloadReadModel(
                    GameMusicDownloadState.Running(1, 4, 0, stopping = true, block = GameMusicBlock.NotConnected),
                    null,
                ) as GameMusicDownloadReadModel.Running
                ).stopping,
        )
        assertEquals(
            GameMusicDownloadReadModel.Finished(
                written = 0,
                skipped = 0,
                failed = 4,
                stopped = true,
                hint = com.gruning.mimir.rewrite.feature.settings.GameMusicFailureValue.DAILY_LIMIT,
            ),
            gameMusicDownloadReadModel(
                GameMusicDownloadState.Finished(0, 0, 4, stopped = true, failure = GameMusicFailure.DAILY_LIMIT),
                null,
            ),
        )
    }

    @Test
    fun `every engine reason has its own words on the card`() {
        assertEquals(
            GameMusicFailure.entries.map { it.name },
            GameMusicFailure.entries.map { gameMusicFailureValue(it).name },
        )
    }

    @Test
    fun `the suggested document id is music under the library tree`() {
        assertEquals("primary:ROMs/music", musicFolderSuggestedDocumentId("primary:ROMs"))
        assertEquals("1234-5678:ROMs/music", musicFolderSuggestedDocumentId("1234-5678:ROMs/"))
    }

    @Test
    fun `the read model offers Your music once the folder holds tracks and carries both volumes as detents`() {
        val model = musicReadModel(
            musicEnabled = true,
            nowPlaying = MimirNowPlaying("Mimir Ambient", MimirMusicSource.MIMIR),
            source = MimirMusicSource.MIMIR,
            order = MimirMusicOrder.SHUFFLE,
            ambientVolume = 0.37f,
            songTitlesEnabled = true,
            library = MusicLibraryState(treeUri = "file:///storage/emulated/0/ROMs/music", trackCount = 1),
            launchSoundEnabled = true,
            launchSoundVolume = 1f,
            gameMusic = idle,
            requestsToday = null,
            playOnHighlight = false,
            nowMillis = 60_000L,
        )
        assertTrue(model.customSourceAvailable)
        assertNull(model.customFallbackCopy)
        assertEquals(MusicSourceValue.MIMIR, model.source)
        assertEquals(MusicOrderValue.SHUFFLE, model.order)
        assertEquals(35, model.volumePercent)
        assertEquals(100, model.launchSoundVolumePercent)
        assertEquals("Mimir Ambient", model.nowPlaying?.title)
        assertEquals(1, model.folder?.trackCount)
        assertEquals(GameMusicDownloadReadModel.Idle(3, 10, null), model.gameMusic)
        assertFalse("no grant unless the app says so", model.otherPlayersAccessGranted)
    }

    @Test
    fun `the read model carries the notification-access grant it was handed`() {
        fun model(granted: Boolean) = musicReadModel(
            musicEnabled = true,
            nowPlaying = null,
            source = MimirMusicSource.MIMIR,
            order = MimirMusicOrder.SHUFFLE,
            ambientVolume = 0.5f,
            songTitlesEnabled = true,
            library = MusicLibraryState(),
            launchSoundEnabled = true,
            launchSoundVolume = 1f,
            gameMusic = idle,
            requestsToday = null,
            playOnHighlight = false,
            nowMillis = 0L,
            otherPlayersAccessGranted = granted,
        )
        assertTrue(model(granted = true).otherPlayersAccessGranted)
        assertFalse(model(granted = false).otherPlayersAccessGranted)
    }

    @Test
    fun `the read model carries whether the music is audible, and claims nothing when it is not told`() {
        // The default: a caller that does not say claims nothing, so the chip reads Paused, never Playing.
        val untold = musicReadModel(
            musicEnabled = true,
            nowPlaying = null,
            source = MimirMusicSource.MIMIR,
            order = MimirMusicOrder.SHUFFLE,
            ambientVolume = 0.5f,
            songTitlesEnabled = true,
            library = MusicLibraryState(),
            launchSoundEnabled = true,
            launchSoundVolume = 1f,
            gameMusic = idle,
            requestsToday = null,
            playOnHighlight = false,
            nowMillis = 0L,
        )
        assertFalse("not told, so not claimed", untold.playing)
        fun told(playing: Boolean) = musicReadModel(
            musicEnabled = true,
            nowPlaying = null,
            source = MimirMusicSource.MIMIR,
            order = MimirMusicOrder.SHUFFLE,
            ambientVolume = 0.5f,
            songTitlesEnabled = true,
            library = MusicLibraryState(),
            launchSoundEnabled = true,
            launchSoundVolume = 1f,
            gameMusic = idle,
            requestsToday = null,
            playOnHighlight = false,
            nowMillis = 0L,
            playing = playing,
        )
        assertTrue(told(playing = true).playing)
        assertFalse("held or silent reads as paused, not playing", told(playing = false).playing)
        // The projection reads the engine's sounding signal (the bed's deck, Your music's own playing state) gated by the stream level, not the switch or the pause reason.
        val projection = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirSettingsReadCoordinator.kt").readText()
        assertTrue(projection.contains("combine(MimirMusicControl.state.map { it.mimirMusicSounding }, MimirMusicControl.mediaVolume, ::mimirMusicAudible).distinctUntilChanged()"))
        assertTrue("a hold arriving rebuilds the model", projection.contains("trackFormat, musicSounding,"))
        assertTrue(projection.contains("playing = musicSounding,"))
    }

    @Test
    fun `Your music is offered only for a chosen readable folder with tracks`() {
        val tree = "file:///storage/emulated/0/ROMs/music"
        assertFalse(musicCustomSourceAvailable(MusicLibraryState()))
        assertFalse("no tracks", musicCustomSourceAvailable(MusicLibraryState(treeUri = tree, trackCount = 0)))
        assertTrue(musicCustomSourceAvailable(MusicLibraryState(treeUri = tree, trackCount = 3)))
        assertFalse(
            "a folder that cannot be read plays nothing, so the choice would change nothing audible",
            musicCustomSourceAvailable(MusicLibraryState(treeUri = tree, trackCount = 3, error = MusicLibraryError.GRANT_LOST)),
        )
    }

    @Test
    fun `a folder that cannot play says in words that the theme's music is sounding, and only while Your music is chosen`() {
        CustomMusicFallback.entries.forEach { reason ->
            val copy = musicCustomFallbackCopy(reason)
            assertTrue(copy, copy.isNotBlank())
            assertFalse("never a path or a storage id", copy.contains("content:") || copy.contains("file:"))
        }
        fun model(source: MimirMusicSource, fallback: CustomMusicFallback?) = musicReadModel(
            musicEnabled = true,
            nowPlaying = MimirNowPlaying("Mimir Ambient", MimirMusicSource.MIMIR),
            source = source,
            order = MimirMusicOrder.SHUFFLE,
            ambientVolume = 0.5f,
            songTitlesEnabled = true,
            library = MusicLibraryState(),
            launchSoundEnabled = true,
            launchSoundVolume = 1f,
            gameMusic = GameMusicDownloadState.Idle(0, 0),
            requestsToday = null,
            playOnHighlight = false,
            nowMillis = 0L,
            customFallback = fallback,
        )
        assertEquals(
            musicCustomFallbackCopy(CustomMusicFallback.EMPTY),
            model(MimirMusicSource.CUSTOM, CustomMusicFallback.EMPTY).customFallbackCopy,
        )
        assertNull(model(MimirMusicSource.MIMIR, CustomMusicFallback.EMPTY).customFallbackCopy)
        assertNull(model(MimirMusicSource.CUSTOM, null).customFallbackCopy)
    }

    @Test
    fun `a folder of one track restarts on A and a folder with a next track skips`() {
        assertTrue(musicNowPlayingReadModel(MimirNowPlaying("Solo", MimirMusicSource.CUSTOM, singleTrack = true))!!.singleTrack)
        assertFalse(musicNowPlayingReadModel(MimirNowPlaying("One of many", MimirMusicSource.CUSTOM, singleTrack = false))!!.singleTrack)
    }

    // ── the Equalizer row, and the track's format on Now playing and Output ──────────────────────────

    private val flac = TrackFormat(
        codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304,
        output = OutputFormat(bitDepth = 16, sampleRateHz = 48_000),
    )

    private fun formatModel(
        source: MimirMusicSource = MimirMusicSource.CUSTOM,
        fallback: CustomMusicFallback? = null,
        format: TrackFormat? = flac,
        equalizer: EqualizerSettings = EqualizerSettings(),
    ) = musicReadModel(
        musicEnabled = true,
        nowPlaying = MimirNowPlaying("Song", source),
        source = source,
        order = MimirMusicOrder.SHUFFLE,
        ambientVolume = 0.5f,
        songTitlesEnabled = true,
        library = MusicLibraryState(treeUri = "file:///storage/emulated/0/ROMs/music", trackCount = 3),
        launchSoundEnabled = true,
        launchSoundVolume = 1f,
        gameMusic = idle,
        requestsToday = null,
        playOnHighlight = false,
        nowMillis = 0L,
        customFallback = fallback,
        equalizer = equalizer,
        trackFormat = format,
    )

    @Test
    fun `the Equalizer row is the player's preset when it is on, and Off when it is not`() {
        val off = formatModel()
        assertFalse(off.equalizerEnabled)
        val rock = formatModel(equalizer = EqualizerSettings(enabled = true).withPreset(EqualizerPresetId.ROCK))
        assertTrue(rock.equalizerEnabled)
        assertEquals("Rock", rock.equalizerPresetLabel)
        assertEquals("Custom", formatModel(equalizer = EqualizerSettings(enabled = true).withBand(0, 3f)).equalizerPresetLabel)
    }

    @Test
    fun `Your music's source format is Now playing's second line and its output is the Output row, until known`() {
        val playing = formatModel()
        assertEquals("FLAC · 24/96 · 2,304 kbps", playing.nowPlaying?.formatLine)
        assertEquals("16-bit · 48 kHz", playing.outputValue)
        val unknown = formatModel(format = null)
        assertNull(unknown.nowPlaying?.formatLine)
        assertNull(unknown.outputValue)
        // The output is reported by the audio track, which can come after the source line.
        val sourceOnly = formatModel(format = flac.copy(output = null))
        assertEquals("FLAC · 24/96 · 2,304 kbps", sourceOnly.nowPlaying?.formatLine)
        assertNull(sourceOnly.outputValue)
    }

    @Test
    fun `a theme bed has no format line and no Output row, even for a stale format`() {
        assertNull(formatModel(source = MimirMusicSource.MIMIR).nowPlaying?.formatLine)
        assertNull(formatModel(source = MimirMusicSource.MIMIR).outputValue)
        val fallingBack = formatModel(fallback = CustomMusicFallback.EMPTY)
        assertNull("Your music chosen but the bed is what sounds", fallingBack.nowPlaying?.formatLine)
        assertNull(fallingBack.outputValue)
    }

    // ── the rows of 2026-10-07 ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `each new row has its own command, and Order and Music hours open menus rather than flip`() {
        fun command(rowId: String) = musicRowCommand(rowId, gameMusic = idle)
        assertEquals(MusicRowCommand.OpenOrderPicker, command(MusicOrderRowId))
        assertEquals(MusicRowCommand.OpenHoursPicker, command(MusicHoursRowId))
        assertEquals(MusicRowCommand.ToggleFade, command(MusicFadeRowId))
        assertEquals(MusicRowCommand.ToggleOutsideMimir, command(MusicOutsideMimirRowId))
        assertEquals(MusicRowCommand.ToggleScreenOffPause, command(MusicScreenOffRowId))
        assertEquals(MusicRowCommand.ToggleUnplugPause, command(MusicUnplugRowId))
        val commands = listOf(MusicOrderRowId, MusicHoursRowId, MusicFadeRowId, MusicOutsideMimirRowId, MusicScreenOffRowId, MusicUnplugRowId)
            .map { command(it) }
        assertEquals("six rows, six different commands", 6, commands.toSet().size)
    }

    @Test
    fun `order and hours map both ways, one to one`() {
        MimirMusicOrder.entries.forEach { assertEquals(it, mimirMusicOrder(musicOrderValue(it))) }
        MusicOrderValue.entries.forEach { assertEquals(it, musicOrderValue(mimirMusicOrder(it))) }
        MimirMusicHours.entries.forEach { assertEquals(it, mimirMusicHours(musicHoursValue(it))) }
        MusicHoursValue.entries.forEach { assertEquals(it, musicHoursValue(mimirMusicHours(it))) }
        assertEquals("one value each side", MimirMusicHours.entries.size, MusicHoursValue.entries.size)
    }

    @Test
    fun `the words the Music hours menu shows are the window the engine holds`() {
        fun clock(minuteOfDay: Int): String {
            val hour24 = minuteOfDay / 60
            val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
            return "$hour12:${(minuteOfDay % 60).toString().padStart(2, '0')} ${if (hour24 < 12) "AM" else "PM"}"
        }
        MimirMusicHours.entries.filter { it.limited }.forEach { hours ->
            assertEquals(
                "${hours.name}: the picker's words",
                "${clock(hours.startMinute)} to ${clock(hours.endMinute)}",
                musicHoursValue(hours).detail,
            )
        }
        assertFalse(MimirMusicHours.ALL_DAY.limited)
        assertEquals("Music plays at any time of day.", MusicHoursValue.ALL_DAY.detail)
    }

    @Test
    fun `the read model carries each new choice, and defaults to the spec's behaviour`() {
        val defaults = formatModel()
        assertFalse("gapless by default", defaults.fadeBetweenTracks)
        assertEquals(MusicHoursValue.ALL_DAY, defaults.hours)
        assertTrue(defaults.playOutsideMimir)
        assertTrue(defaults.pauseOnScreenOff)
        assertTrue(defaults.pauseOnUnplug)

        val chosen = musicReadModel(
            musicEnabled = true,
            nowPlaying = null,
            source = MimirMusicSource.MIMIR,
            order = MimirMusicOrder.IN_ORDER,
            ambientVolume = 0.5f,
            songTitlesEnabled = true,
            library = MusicLibraryState(),
            launchSoundEnabled = true,
            launchSoundVolume = 1f,
            gameMusic = idle,
            requestsToday = null,
            playOnHighlight = false,
            nowMillis = 0L,
            hours = MimirMusicHours.EVENING,
            fadeBetweenTracks = true,
            playOutsideMimir = false,
            pauseOnScreenOff = false,
            pauseOnUnplug = false,
        )
        assertEquals(MusicOrderValue.IN_ORDER, chosen.order)
        assertEquals(MusicHoursValue.EVENING, chosen.hours)
        assertTrue(chosen.fadeBetweenTracks)
        assertFalse(chosen.playOutsideMimir)
        assertFalse(chosen.pauseOnScreenOff)
        assertFalse(chosen.pauseOnUnplug)
    }

    @Test
    fun `the panel names the music hours as the reason, with the way to listen anyway`() {
        assertEquals(MusicHintSchedule, musicPanelHint(MusicPauseReason.SCHEDULE))
        assertEquals("Paused outside your music hours. Press play to listen anyway.", MusicHintSchedule)
        // Control: the player's own pause says nothing, and the older reasons keep their words.
        assertNull(musicPanelHint(MusicPauseReason.USER))
        assertEquals(MusicHintScreenOff, musicPanelHint(MusicPauseReason.SCREEN_OFF))
    }

    // ── the folder row (alpha cert 2026-10-08, E7) ─────────────────────────────────────────────────────

    @Test
    fun `a folder picked in Mimir's browser reads with its real names, not percent codes`() {
        assertEquals(
            "Internal storage › Music › Cert Artist Delta › Rejected Formats",
            musicFolderFriendlyPath("file:///storage/emulated/0/Music/Cert%20Artist%20Delta/Rejected%20Formats"),
        )
    }

    @Test
    fun `decoding the folder name for display leaves plus signs and accents alone`() {
        assertEquals(
            "Internal storage › Music › Mario+Luigi › Café",
            musicFolderFriendlyPath("file:///storage/emulated/0/Music/Mario+Luigi/Caf%C3%A9"),
        )
    }

    @Test
    fun `a plain folder name and a system-picker tree read as before`() {
        assertEquals(
            "Internal storage › Music › MimirCertMusic",
            musicFolderFriendlyPath("file:///storage/emulated/0/Music/MimirCertMusic"),
        )
        assertEquals(
            "SD card › Music › Chip Tunes",
            musicFolderFriendlyPath("content://com.android.externalstorage.documents/tree/1234-5678%3AMusic%2FChip%20Tunes"),
        )
    }
}
