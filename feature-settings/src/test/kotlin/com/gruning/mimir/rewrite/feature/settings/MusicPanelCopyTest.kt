package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings › Music's row copy (spec `music-settings-spec.md`, 2026-09-30): what each line says in each state,
 * and the two copy constraints the copy carries -- "Coming next" belongs to the ENGINEERING rows alone, and no row
 * ever shows a path or a storage identity.
 */
class MusicPanelCopyTest {

    private fun model(
        musicEnabled: Boolean = true,
        nowPlaying: MusicNowPlayingReadModel? = null,
        folder: MusicFolderReadModel? = null,
    ) = MusicReadModel(
        musicEnabled = musicEnabled,
        nowPlaying = nowPlaying,
        source = MusicSourceValue.MIMIR,
        customSourceAvailable = false,
        order = MusicOrderValue.IN_ORDER,
        volumePercent = 50,
        songTitlesEnabled = true,
        folder = folder,
        launchSoundEnabled = true,
        launchSoundVolumePercent = 100,
        gameMusic = GameMusicDownloadReadModel.Unavailable,
        playOnHighlight = false,
    )

    private fun folder(
        trackCount: Int = 42,
        lastScanLabel: String? = "Scanned just now",
        scanning: Boolean = false,
        error: String? = null,
    ) = MusicFolderReadModel(
        friendlyPath = "SD card › ROMs › music",
        trackCount = trackCount,
        lastScanLabel = lastScanLabel,
        scanning = scanning,
        error = error,
    )

    @Test
    fun `the keep-music row defaults On, says what it needs, and never shows a storage id`() {
        // the row is "Let other music play over games"; "Play outside Mimir" (Behaviour) keeps its own name. The apostrophe is typographic (the release content gate).
        assertEquals("Let other music play over games", MusicKeepInGamesTitle)
        assertEquals("A game you start can’t pause music from other apps. Needs the Tweaker.", MusicKeepInGamesCopy)
        assertEquals("On", musicToggleLabel(true))
        assertTrue(MusicDetailRowIds.contains(MusicKeepMusicInGamesRowId))
        assertFalse(MusicKeepInGamesCopy.contains("TAKE_AUDIO_FOCUS"))
        assertFalse(MusicKeepInGamesCopy.contains("appops"))
    }

    @Test
    fun `every Music row id is under the music prefix and routes to the Music category`() {
        assertEquals("ten rows on the page (issue #273: it was twenty-two)", 10, MusicDetailRowIds.size)
        assertEquals("the Equalizer row follows Volume", MusicDetailRowIds.indexOf(MusicVolumeRowId) + 1, MusicDetailRowIds.indexOf(MusicEqualizerRowId))
        assertEquals("Order follows Source", MusicDetailRowIds.indexOf(MusicSourceRowId) + 1, MusicDetailRowIds.indexOf(MusicOrderRowId))
        assertEquals("Behaviour follows the Equalizer", MusicDetailRowIds.indexOf(MusicEqualizerRowId) + 1, MusicDetailRowIds.indexOf(MusicBehaviourRowId))
        assertEquals("no two rows share an id", MusicDetailRowIds.size, MusicDetailRowIds.toSet().size)
        MusicDetailRowIds.forEach { rowId ->
            assertTrue(rowId, rowId.startsWith("music/"))
            assertEquals(rowId, rowId, canonicalSettingsDetailRowId(rowId))
            assertEquals(rowId, SettingsCategory.MUSIC, settingsCategoryForFocusKey(detailKey(rowId)))
            assertEquals(rowId, DetailScope, settingsScopeForFocusKey(detailKey(rowId)))
        }
        assertEquals("the rail lands on the first row", MusicDetailRowIds.first(), settingsFirstDetailRowId(SettingsCategory.MUSIC))
    }

    @Test
    fun `Show other players is the last switch on the Behaviour page and reads the grant`() {
        val ids = MusicBehaviourRowIds
        assertEquals(MusicUnplugRowId, ids[ids.indexOf(MusicOtherPlayersRowId) - 1])
        assertEquals(MusicOtherPlayersRowId, ids.last())
        assertEquals("Granted", musicOtherPlayersValue(true))
        assertEquals("Not allowed", musicOtherPlayersValue(false))
        assertFalse("the grant defaults to not held until the app has read it", model().otherPlayersAccessGranted)
    }

    @Test
    fun `the help line names the sideload path in the pane's voice and carries no storage id`() {
        assertEquals(
            "If Android blocks it, open Mimir’s App info › ⋮ › Allow restricted settings first.",
            MusicOtherPlayersRestrictedHelp,
        )
        assertTrue(MusicOtherPlayersCopy.isNotBlank())
        assertFalse(MusicOtherPlayersCopy.contains("content:") || MusicOtherPlayersCopy.contains("file:"))
    }

    @Test
    fun `toggle and percent values read like Utility's`() {
        assertEquals("On", musicToggleLabel(true))
        assertEquals("Off", musicToggleLabel(false))
        assertEquals("Off", musicPercentLabel(0))
        assertEquals("5%", musicPercentLabel(5))
        assertEquals("100%", musicPercentLabel(100))
        assertEquals("100%", musicPercentLabel(130))
    }

    @Test
    fun `the Music row says what Off does, and when on names the track and the output, never the source the Source row already says`() {
        assertEquals("Off silences the player everywhere.", musicPlayerSupportingCopy(model(musicEnabled = false)))
        assertEquals(
            "Off silences the player everywhere.",
            musicPlayerSupportingCopy(model(musicEnabled = false, nowPlaying = MusicNowPlayingReadModel("Fallen Hero", "Mimir", singleTrack = true))),
        )
        assertEquals("Nothing playing right now.", musicPlayerSupportingCopy(model(musicEnabled = true)))
        assertEquals(
            "Fallen Hero",
            musicPlayerSupportingCopy(model(nowPlaying = MusicNowPlayingReadModel("Fallen Hero", "Mimir", singleTrack = true))),
        )
        // A theme bed has no output; Your music reports one once the player has said (the Output row's figure, folded into this line).
        assertEquals(
            "Song\nOutput: 16-bit · 48 kHz",
            musicPlayerSupportingCopy(
                model(nowPlaying = MusicNowPlayingReadModel("Song", "Music on this device", singleTrack = false, formatLine = "FLAC · 24/96 · 2,304 kbps"))
                    .copy(outputValue = "16-bit · 48 kHz"),
            ),
        )
    }

    @Test
    fun `the folder row suggests when empty, counts tracks with a thousands separator, says Scanning while a scan runs, or reports the error`() {
        assertEquals("None chosen. Suggested: SD › ROMs › music.", musicFolderCopy(null))
        assertEquals("SD card › ROMs › music · 42 tracks", musicFolderCopy(folder()))
        assertEquals("SD card › ROMs › music · 1 track", musicFolderCopy(folder(trackCount = 1)))
        assertEquals("SD card › ROMs › music · 1,234 tracks", musicFolderCopy(folder(trackCount = 1234)))
        assertEquals("SD card › ROMs › music · Scanning…", musicFolderCopy(folder(scanning = true)))
        assertEquals(
            "SD card › ROMs › music · Mimir can no longer read this folder.",
            musicFolderCopy(folder(error = "Mimir can no longer read this folder.")),
        )
    }

    @Test
    fun `the download row's idle line carries the request count with the age of its read or says unknown`() {
        assertEquals("7 of 12 games have music · requests today unknown", musicDownloadIdleCopy(7, 12, null))
        assertEquals("1 of 1 game has music · requests today unknown", musicDownloadIdleCopy(1, 1, null))
        val withCount = musicDownloadIdleCopy(7, 12, MusicRequestsTodayReadModel(count = 40, ageLabel = "12 min ago"))
        assertEquals("7 of 12 games have music · 40 requests today, read 12 min ago", withCount)
        assertFalse("never a promised allowance", withCount.contains("left") || withCount.contains("remaining"))
    }

    @Test
    fun `the two gate lines are each other's mirror and the gated chip reads Busy`() {
        assertEquals("Artwork is downloading.", MusicDownloadEnrichRunningCopy)
        assertEquals("Busy", MusicDownloadBusyChip)
        assertEquals("Nothing to download yet.", MusicDownloadCheckingCopy)
    }

    @Test
    fun `the download row's finished line drops the zero figures`() {
        assertEquals("Done · 5 written", musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(5, 0, 0)))
        assertEquals(
            "Done · 5 written · 2 skipped · 1 failed",
            musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(5, 2, 1)),
        )
    }

    // ── combined49: a card that sat still, a Cancel that did nothing, a "Done" that was a stop, "4 failed" with no reason ──

    private fun running(
        done: Int = 0,
        total: Int = 4,
        written: Int = 0,
        currentIndex: Int = 0,
        current: String? = null,
        stopping: Boolean = false,
    ) = GameMusicDownloadReadModel.Running(done, total, written, currentIndex, current, stopping)

    @Test
    fun `while a game is looked up the line names it, so the card never sits unchanged`() {
        assertEquals(
            "Looking up 1 of 4 · Chrono Trigger\n0 written",
            musicDownloadRunningCopy(running(currentIndex = 1, current = "Chrono Trigger")),
        )
        assertEquals(
            "a game with no name still moves the line",
            "Looking up 3 of 4\n2 written",
            musicDownloadRunningCopy(running(done = 2, written = 2, currentIndex = 3, current = null)),
        )
        assertEquals("between lookups it is the counts, as it always was", "2 of 4 · 1 written", musicDownloadRunningCopy(running(done = 2, written = 1)))
        assertEquals("before anything is counted it does not claim 0 of 0", "Starting…", musicDownloadRunningCopy(running(total = 0)))
    }

    @Test
    fun `a press on Cancel turns the line to Stopping at once, over the same counts`() {
        assertEquals(
            "Stopping…\n2 of 4 · 1 written",
            musicDownloadRunningCopy(running(done = 2, written = 1, currentIndex = 3, current = "Chrono Trigger", stopping = true)),
        )
        assertEquals("Stopping…", musicDownloadRunningCopy(running(total = 0, stopping = true)))
        assertEquals("Stopping", MusicDownloadStoppingChip)
    }

    @Test
    fun `a stopped batch says Stopped and never Done`() {
        assertEquals("Stopped · 2 written", musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(2, 0, 0, stopped = true)))
        assertEquals(
            "Stopped · 0 written",
            musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(0, 0, 0, stopped = true)),
        )
        assertEquals("Done · 2 written", musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(2, 0, 0)))
    }

    @Test
    fun `the failed games' shared reason is one true hint after the counts, and each reason has its own words`() {
        assertEquals(
            "Done · 0 written · 4 failed\nScreenScraper didn’t answer — try again later",
            musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(0, 0, 4, hint = GameMusicFailureValue.NO_ANSWER)),
        )
        assertEquals(
            "a stop that also met failures says both",
            "Stopped · 1 written · 2 failed\nScreenScraper’s daily limit is reached",
            musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(1, 0, 2, stopped = true, hint = GameMusicFailureValue.DAILY_LIMIT)),
        )
        assertEquals(
            mapOf(
                GameMusicFailureValue.NO_ANSWER to "ScreenScraper didn’t answer — try again later",
                GameMusicFailureValue.DAILY_LIMIT to "ScreenScraper’s daily limit is reached",
                GameMusicFailureValue.BUSY to "ScreenScraper is busy — try again later",
                GameMusicFailureValue.SIGN_IN_REFUSED to
                    "ScreenScraper sign-in was refused — reconnect in Settings › Appearance › Enrichment & artwork",
                GameMusicFailureValue.BUILD_REFUSED to "ScreenScraper isn’t available in this build",
                GameMusicFailureValue.COULDNT_SAVE to "Couldn’t save the sound",
            ),
            GameMusicFailureValue.entries.associateWith { it.copy },
        )
        assertEquals("no hint, no second line", "Done · 3 written · 1 failed", musicDownloadFinishedCopy(GameMusicDownloadReadModel.Finished(3, 0, 1)))
    }

    @Test
    fun `the Source row explains itself until Your music cannot play and then says why the theme's music is sounding`() {
        assertEquals(MusicSourceDefaultCopy, musicSourceCopy(null))
        assertEquals(MusicSourceDefaultCopy, musicSourceCopy(model()))
        val fallback = "Playing Mimir’s music: your folder has no tracks Mimir can play."
        assertEquals(fallback, musicSourceCopy(model().copy(customFallbackCopy = fallback)))
        assertFalse(MusicSourceDefaultCopy.contains(MusicComingNextCopy))
    }

    @Test
    fun `Coming next is one phrase and no NOW row's copy ever carries it`() {
        assertEquals("Coming next", MusicComingNextCopy)
        val nowRowLines = listOf(
            musicPlayerSupportingCopy(model()),
            musicFolderCopy(null),
            musicFolderCopy(folder()),
            MusicLaunchSoundCopy,
            musicBehaviourSummary(model()),
            musicDownloadIdleCopy(3, 10, null),
            MusicDownloadConnectFirstCopy,
            MusicDownloadEnrichRunningCopy,
            MusicDownloadCheckingCopy,
            MusicFolderFormatsCopy,
        )
        nowRowLines.forEach { line -> assertFalse(line, line.contains(MusicComingNextCopy)) }
    }

    @Test
    fun `the source labels are player words and never the storage identities`() {
        assertEquals("Mimir’s music", MusicSourceValue.MIMIR.label)
        assertEquals("Music on this device", MusicSourceValue.CUSTOM.label)
        MusicSourceValue.entries.forEach { assertFalse(it.label, it.label.contains(it.name)) }
        assertNull("a Music info row has no activation to route", canonicalSettingsDetailRowId("music"))
    }

    @Test
    fun `the Equalizer row says the preset and what it reaches when on, and what it does when off, never its own value twice`() {
        val on = model().copy(equalizerEnabled = true, equalizerPresetLabel = "Rock")
        assertEquals("Rock · All sound on this device", musicEqualizerSupportingCopy(on))
        assertEquals("On", musicEqualizerActionLabel(on))
        val off = model().copy(equalizerEnabled = false, equalizerPresetLabel = "Rock")
        assertEquals("Shapes all sound on this device.", musicEqualizerSupportingCopy(off))
        assertEquals("Off", musicEqualizerActionLabel(off))
        // On, but the global engine is out of reach (another app holds it, or Android refused it): only Mimir's own music is shaped, and the row says so.
        val mimirOnly = model().copy(equalizerEnabled = true, equalizerMimirMusicOnly = true, equalizerPresetLabel = "Rock")
        assertEquals("Rock · Music on this device only right now", musicEqualizerSupportingCopy(mimirOnly))
        assertEquals("On", musicEqualizerActionLabel(mimirOnly))
        // Off says the same whatever the engine did last: nothing is reached.
        assertEquals("Shapes all sound on this device.", musicEqualizerSupportingCopy(off.copy(equalizerMimirMusicOnly = true)))
        listOf(on, off, mimirOnly).forEach { m ->
            assertFalse(
                "the line carries what the value does not",
                musicEqualizerSupportingCopy(m).equals(musicEqualizerActionLabel(m), ignoreCase = true),
            )
        }
    }

    @Test
    fun `the Equalizer row never names an emulator or a vendor app`() {
        val lines = listOf(
            MusicEqualizerOffCopy, MusicEqualizerAllSoundCopy, MusicEqualizerMimirOnlyCopy,
            musicEqualizerSupportingCopy(model().copy(equalizerEnabled = true, equalizerPresetLabel = "Rock")),
            musicEqualizerSupportingCopy(model().copy(equalizerEnabled = true, equalizerMimirMusicOnly = true, equalizerPresetLabel = "Rock")),
        )
        val banned = Regex("\\b(azahar|melonds|retroarch|odin|thor|system eq)\\b", RegexOption.IGNORE_CASE)
        lines.forEach { line -> assertFalse("\"$line\" names an emulator, a vendor app or the old switch", banned.containsMatchIn(line)) }
    }

    @Test
    fun `no row on the page says on its line what its value already says`() {
        fun assertNoRepeat(rowId: String, line: String, value: String) =
            assertFalse("$rowId repeats its value \"$value\" as its line \"$line\"", line.trim().equals(value.trim(), ignoreCase = true))
        listOf(true, false).forEach { on ->
            val m = model().copy(musicEnabled = on, equalizerEnabled = on, equalizerPresetLabel = "Rock")
            assertNoRepeat(MusicPlayerEnabledRowId, musicPlayerSupportingCopy(m), musicToggleLabel(m.musicEnabled))
            assertNoRepeat(MusicEqualizerRowId, musicEqualizerSupportingCopy(m), musicEqualizerActionLabel(m))
            assertNoRepeat(MusicVolumeRowId, "Also in Utility as Music volume.", musicPercentLabel(m.volumePercent))
            assertNoRepeat(MusicLaunchSoundRowId, MusicLaunchSoundCopy, musicPercentLabel(m.launchSound.percent))
        }
        MusicLaunchSoundValue.entries.forEach { assertNoRepeat(MusicLaunchSoundRowId, MusicLaunchSoundCopy, musicPercentLabel(it.percent)) }
    }

    // ── the rows of 2026-10-07 ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the seven switches sit on the Behaviour page in the order they are read`() {
        val ids = MusicBehaviourRowIds
        assertEquals(
            listOf(MusicSongTitlesRowId, MusicFadeRowId, MusicHoursRowId, MusicOutsideMimirRowId, MusicScreenOffRowId, MusicUnplugRowId, MusicOtherPlayersRowId),
            ids,
        )
        ids.forEach { id -> assertFalse(" is a switch, not a page row", id in MusicDetailRowIds) }
    }

    @Test
    fun `the new rows speak in full words and carry no code, no path and no storage name`() {
        val lines = listOf(
            MusicOrderCopy, MusicFadeCopy, MusicOutsideMimirCopy, MusicScreenOffCopy, MusicUnplugCopy,
        ) + MusicHoursValue.entries.map(::musicHoursCopy) + MusicOrderValue.entries.map { it.detail }
        lines.forEach { line ->
            assertTrue(line, line.isNotBlank() && line.endsWith("."))
            assertFalse(line, line.contains("_") || line.contains("content:") || line.contains("file:"))
            assertFalse("only Play on highlight is coming next: $line", line.contains(MusicComingNextCopy))
        }
    }

    @Test
    fun `Order is the spec's two words and each says what it does`() {
        assertEquals(listOf("In order", "Shuffle"), MusicOrderValue.entries.map { it.label })
        MusicOrderValue.entries.forEach { assertTrue(it.detail.isNotBlank()) }
        assertEquals("a theme bed is one piece, so the row says whose music it governs", "Applies to Music on this device.", MusicOrderCopy)
    }

    @Test
    fun `Music hours says its window while one is set and that any time is fine while none is`() {
        assertEquals("Music can play at any time of day.", musicHoursCopy(MusicHoursValue.ALL_DAY))
        assertEquals(
            "Music plays from 7:00 AM to 7:00 PM. Pressing play outside those hours plays it until they change.",
            musicHoursCopy(MusicHoursValue.DAYTIME),
        )
        assertEquals(listOf("All day", "Daytime", "Evening", "Not overnight"), MusicHoursValue.entries.map { it.label })
    }

    @Test
    fun `the rows default to the spec's behaviour before the app has said anything`() {
        val fresh = model()
        assertFalse("gapless by default", fresh.fadeBetweenTracks)
        assertEquals(MusicHoursValue.ALL_DAY, fresh.hours)
        assertTrue("plays everywhere by default", fresh.playOutsideMimir)
        assertTrue(fresh.pauseOnScreenOff)
        assertTrue(fresh.pauseOnUnplug)
    }

    @Test
    fun `every new row id is under the music prefix and routes to the Music category`() {
        listOf(MusicOrderRowId, MusicFadeRowId, MusicHoursRowId, MusicOutsideMimirRowId, MusicScreenOffRowId, MusicUnplugRowId).forEach { rowId ->
            assertEquals(rowId, rowId, canonicalSettingsDetailRowId(rowId))
            assertEquals(rowId, SettingsCategory.MUSIC, settingsCategoryForFocusKey(detailKey(rowId)))
        }
    }

    // ── update: the page of ten rows ─────────────────────────────────────────────────────────────

    @Test
    fun `the page is these ten rows in this order`() {
        assertEquals(
            listOf(
                "music/player/enabled",
                "music/player/source",
                "music/player/order",
                "music/player/volume",
                "music/player/equalizer",
                "music/player/behaviour",
                "music/custom/folder",
                "music/game/launchsound",
                "music/game/keepmusic",
                "music/game/download",
            ),
            MusicDetailRowIds,
        )
    }

    /** The summary with its no-break spaces read as spaces, the words a reader sees. */
    private fun summary(model: MusicReadModel): String = musicBehaviourSummary(model).replace(Char(0x00A0), ' ')

    @Test
    fun `the Behaviour row names the switches that are on, by short name, in page order`() {
        val defaults = model()
        assertEquals("Titles · Outside Mimir · Screen-off pause · Unplug pause", summary(defaults))
        val some = defaults.copy(fadeBetweenTracks = true, songTitlesEnabled = false, pauseOnUnplug = false, playOutsideMimir = false)
        assertEquals("Fade · Screen-off pause", summary(some))
        val all = defaults.copy(fadeBetweenTracks = true, hours = MusicHoursValue.EVENING, otherPlayersAccessGranted = true)
        assertEquals("Titles · Fade · Hours · Outside Mimir · Screen-off pause · Unplug pause · Other players", summary(all))
        val none = defaults.copy(
            songTitlesEnabled = false, playOutsideMimir = false, pauseOnScreenOff = false, pauseOnUnplug = false,
        )
        assertEquals("Everything is off.", musicBehaviourSummary(none))
        assertEquals("Open", MusicBehaviourAction)
        // Each name holds its words with no-break spaces: a wrapping line breaks between names and never inside one.
        val wrapped = musicBehaviourSummary(all)
        assertTrue(wrapped.split(" · ").all { name -> ' ' !in name })
    }

    @Test
    fun `the Launch sound steps are Off then 25 to 100 percent, and read as the percent words Utility uses`() {
        assertEquals(listOf("Off", "25%", "50%", "75%", "100%"), MusicLaunchSoundValue.entries.map { it.label })
        assertEquals(listOf("Off", "25%", "50%", "75%", "100%"), MusicLaunchSoundValue.entries.map { musicPercentLabel(it.percent) })
        assertEquals(listOf(false, true, true, true, true), MusicLaunchSoundValue.entries.map { it.enabled })
    }

    @Test
    fun `a stored Launch sound toggle and level read as one step, both ways`() {
        // Off is exactly "the toggle is off", whatever the level was.
        listOf(0, 25, 50, 100).forEach { assertEquals(MusicLaunchSoundValue.OFF, MusicLaunchSoundValue.fromStored(enabled = false, volumePercent = it)) }
        // The toggle on at a step reads that step.
        MusicLaunchSoundValue.entries.filter { it.enabled }.forEach { step ->
            assertEquals(step, MusicLaunchSoundValue.fromStored(enabled = true, volumePercent = step.percent))
        }
        // A level between steps rounds to the nearest one.
        assertEquals(MusicLaunchSoundValue.QUARTER, MusicLaunchSoundValue.fromStored(true, 30))
        assertEquals(MusicLaunchSoundValue.HALF, MusicLaunchSoundValue.fromStored(true, 40))
        assertEquals(MusicLaunchSoundValue.HALF, MusicLaunchSoundValue.fromStored(true, 60))
        assertEquals(MusicLaunchSoundValue.THREE_QUARTERS, MusicLaunchSoundValue.fromStored(true, 65))
        assertEquals(MusicLaunchSoundValue.THREE_QUARTERS, MusicLaunchSoundValue.fromStored(true, 80))
        assertEquals(MusicLaunchSoundValue.FULL, MusicLaunchSoundValue.fromStored(true, 90))
        // Off is only the toggle off: the toggle on never reads as Off, so a level of zero or a few percent reads as the quietest step,
        // and a level past the scale is clamped.
        assertEquals(MusicLaunchSoundValue.QUARTER, MusicLaunchSoundValue.fromStored(true, 0))
        assertEquals(MusicLaunchSoundValue.QUARTER, MusicLaunchSoundValue.fromStored(true, 12))
        assertEquals(MusicLaunchSoundValue.QUARTER, MusicLaunchSoundValue.fromStored(true, 13))
        (0..100).forEach { percent ->
            assertTrue("on at $percent is never Off", MusicLaunchSoundValue.fromStored(true, percent) != MusicLaunchSoundValue.OFF)
            assertEquals("off at $percent is always Off", MusicLaunchSoundValue.OFF, MusicLaunchSoundValue.fromStored(false, percent))
        }
        assertEquals(MusicLaunchSoundValue.FULL, MusicLaunchSoundValue.fromStored(true, 150))
        // The default a fresh install holds (toggle on, level 100) is 100%.
        val fresh = model()
        assertEquals(MusicLaunchSoundValue.FULL, fresh.launchSound)
        assertEquals(MusicLaunchSoundValue.OFF, fresh.copy(launchSoundEnabled = false).launchSound)
        assertEquals(MusicLaunchSoundValue.HALF, fresh.copy(launchSoundVolumePercent = 50).launchSound)
    }

    @Test
    fun `the Launch sound row says what it is and the old second row is gone`() {
        assertEquals("Its own level, apart from the music volume.", MusicLaunchSoundCopy)
        assertFalse(MusicDetailRowIds.any { it.endsWith("launchsound/volume") })
    }

    @Test
    fun `retired Music row ids land on the row that carries them now, and a Behaviour switch restores to the row that opens its page`() {
        assertEquals(MusicPlayerEnabledRowId, canonicalSettingsDetailRowId("music/player/nowplaying"))
        assertEquals(MusicFolderRowId, canonicalSettingsDetailRowId("music/custom/rescan"))
        assertEquals(MusicLaunchSoundRowId, canonicalSettingsDetailRowId("music/game/launchsound/volume"))
        // A press on a switch still routes by its own id ...
        MusicBehaviourRowIds.forEach { assertEquals(it, canonicalSettingsDetailRowId(it)) }
        // ... but a persisted focus on one is restored to the Behaviour row, because the page is not open at startup.
        MusicBehaviourRowIds.forEach {
            assertEquals(detailKey(MusicBehaviourRowId), canonicalSettingsFocusKey(detailKey(it)))
        }
        assertEquals(detailKey(MusicFolderRowId), canonicalSettingsFocusKey(detailKey("music/custom/rescan")))
        // A Behaviour-scope startup request stands for the detail scope.
        assertEquals(
            SettingsCategory.MUSIC,
            settingsInitialFocusCategory(detailKey(MusicBehaviourRowId), MusicBehaviourScope),
        )
    }

    @Test
    fun `the Forget folder confirmation's rows route by their own ids, restore to the Music folder row, and its scope stands for the detail scope`() {
        assertEquals(listOf(MusicFolderForgetCancelRowId, MusicFolderForgetConfirmRowId), MusicFolderForgetRowIds)
        MusicFolderForgetRowIds.forEach {
            assertEquals(it, canonicalSettingsDetailRowId(it))
            assertEquals(SettingsCategory.MUSIC, settingsCategoryForFocusKey(detailKey(it)))
            assertFalse("$it is not a row of the page", it in MusicDetailRowIds)
            assertEquals(detailKey(MusicFolderRowId), canonicalSettingsFocusKey(detailKey(it)))
        }
        assertEquals(SettingsCategory.MUSIC, settingsInitialFocusCategory(detailKey(MusicFolderRowId), MusicFolderForgetScope))
        assertEquals(setOf(MusicBehaviourScope, MusicFolderForgetScope), MusicSubPageScopes)
        assertTrue(MusicFolderForgetExplainerCopy.contains("will not be deleted or moved"))
    }

    @Test
    fun `the Forget folder confirmation says the files are safe once, in its explainer, and the rows do not say it again`() {
        assertEquals(
            "Mimir will stop playing from this folder. Your music files will not be deleted or moved.",
            MusicFolderForgetExplainerCopy,
        )
        val safety = Regex("(deleted|moved|untouched|stay)", RegexOption.IGNORE_CASE)
        val lines = listOf(MusicFolderForgetExplainerCopy, MusicFolderForgetCancelCopy, MusicFolderForgetConfirmCopy)
        assertEquals("one line says the files are safe: $lines", listOf(MusicFolderForgetExplainerCopy), lines.filter { safety.containsMatchIn(it) })
        assertFalse("the explainer has one safety sentence, not two", MusicFolderForgetExplainerCopy.contains("exactly where they are"))
        assertEquals("Forget it in Mimir only.", MusicFolderForgetConfirmCopy)
    }

    @Test
    fun `Play on highlight is hidden but its preference is still in the read model`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/MusicPanel.kt").readText()
        assertFalse("no row titled Play on highlight", source.contains("title = \"Play on highlight\""))
        assertFalse("no Coming next copy on the page", source.contains("MusicComingNextCopy"))
        assertFalse(model().copy(playOnHighlight = false).playOnHighlight)
        assertTrue(model().copy(playOnHighlight = true).playOnHighlight)
    }
}
