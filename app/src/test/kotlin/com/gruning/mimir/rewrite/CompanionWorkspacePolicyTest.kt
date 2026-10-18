package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionWorkspacePolicyTest {

    // an edge reclaim from a foreign app must land back on
    // whatever CompanionWorkspacePage the Bottom was showing when the app took it over, not always
    // Activity. CompanionWorkspaceState.page is process state a foreign app's Presentation teardown never
    // touches (CompanionBottomFolderState's own KDoc: "a Presentation remount does not lose it") — it
    // already holds the hand-off value by the time a reclaim runs, so the reclaim branch (`!controllerActive`
    // in `handleCompanionOverlayAction`) must leave it alone rather than force it. That forced landing was an unintended override, and no test can drive MainActivity's live snapshot
    // state from a JVM unit test, so the source itself is the instrument, same shape as
    // MainActivityNotificationLaunchDisplayTest.
    @Test
    fun reclaimFromAForeignAppDoesNotForceTheActivityPage() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt")
        assertTrue("${file.path} must be readable from the app module dir", file.isFile)
        val source = file.readText()
        assertFalse(
            "the reclaim branch (!controllerActive) must not force CompanionWorkspacePage.ACTIVITY — that " +
                "overwrote the page the Bottom was showing at hand-off",
            source.contains("CompanionWorkspaceState.show(CompanionWorkspacePage.ACTIVITY)"),
        )
    }

    @Test
    fun header_swipe_is_horizontal_and_page_directional() {
        assertEquals(CompanionWorkspaceSwipe.LEFT, companionWorkspaceSwipe(-40f, 2f, 8f))
        assertEquals(CompanionWorkspaceSwipe.RIGHT, companionWorkspaceSwipe(40f, 2f, 8f))
        assertEquals(CompanionWorkspaceSwipe.NONE, companionWorkspaceSwipe(4f, 3f, 8f))
        assertEquals(CompanionWorkspaceSwipe.NONE, companionWorkspaceSwipe(-40f, 44f, 8f))
    }

    @Test
    fun retained_home_is_used_only_for_an_empty_idle_remount() {
        val home = CompanionPayload(mode = CompanionMode.HOME, title = "Shelf")
        assertEquals(home, companionPresentationPayload(CompanionPayload.idle(), home))
        assertEquals(CompanionPayload(mode = CompanionMode.NOW_PLAYING, gameId = GameId("g")), companionPresentationPayload(CompanionPayload(mode = CompanionMode.NOW_PLAYING, gameId = GameId("g")), home))
    }

    @Test
    fun shelf_projects_now_playing_to_home_and_activity_keeps_matching_live_game() {
        val home = CompanionPayload(
            mode = CompanionMode.HOME,
            themeId = "retained-theme",
            homeLanes = listOf(CompanionHomeLane("apps", "Apps", "")),
        )
        val gameId = GameId("g")
        val nowPlaying = CompanionPayload(
            mode = CompanionMode.NOW_PLAYING,
            gameId = gameId,
            title = "Live game",
            themeId = "live-theme",
        )
        val game = MimirGameSession(
            operation = MimirGameSessionOperation("op"),
            sequence = 1,
            gameId = gameId,
            platformId = com.gruning.mimir.rewrite.core.model.PlatformId("nes"),
            returnAnchor = MimirGameReturnAnchor.Library(
                com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                    com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                    "shelf",
                ),
            ),
            startedAtEpochMs = 1,
            dualScreen = true,
            phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
        )

        val shelf = companionShelfPayload(nowPlaying, home)

        assertEquals(CompanionMode.HOME, shelf.mode)
        assertEquals(home.homeLanes, shelf.homeLanes)
        assertEquals("live-theme", shelf.themeId)
        assertEquals(game, companionActivityGame(game))
        assertEquals(null, companionActivityGame(game.copy(phase = MimirSessionPhase.ENDED)))
    }

    @Test
    fun activity_game_requires_matching_live_session() {
        val game = MimirGameSession(
            operation = MimirGameSessionOperation("op"),
            sequence = 1,
            gameId = GameId("g"),
            platformId = com.gruning.mimir.rewrite.core.model.PlatformId("nes"),
            returnAnchor = MimirGameReturnAnchor.Library(
                com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                    com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                    "shelf",
                ),
            ),
            startedAtEpochMs = 1,
            dualScreen = true,
            phase = MimirSessionPhase.FOREGROUND_PROVEN,
        )
        assertEquals(game, companionActivityGame(game))
        // R6-P1-03: retired the payload-game-id match (was GameId("other") → null) — presence no longer
        // depends on a payload id at all, so there is nothing left to mismatch.
        assertEquals(null, companionActivityGame(game.copy(phase = MimirSessionPhase.ENDED)))
    }

    @Test
    fun activity_game_is_the_live_session_even_with_no_retained_card() {
        val game = MimirGameSession(
            operation = MimirGameSessionOperation("op"),
            sequence = 1,
            gameId = GameId("g"),
            title = "Chrono Trigger",
            platformId = com.gruning.mimir.rewrite.core.model.PlatformId("snes"),
            returnAnchor = MimirGameReturnAnchor.Library(
                com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                    com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                    "shelf",
                ),
            ),
            startedAtEpochMs = 1,
            placement = MimirGamePlacement.MAIN,
            dualScreen = false,
            phase = MimirSessionPhase.DISPATCHED,
        )
        // A MAIN-only game with no retained card (never seeded, or cleared by
        // a resume) is still the current game: presence comes from the session authority alone.
        assertEquals(game, companionActivityGame(game))
    }

    @Test
    fun media_selection_prefers_playing_and_actions_follow_advertised_bits() {
        assertEquals(1, companionMediaSelectionIndex(listOf(false, true, false)))
        assertEquals(0, companionMediaSelectionIndex(listOf(false, false)))
        assertEquals(1, companionMediaSelectionIndex(listOf(false, false), preferredIndex = 1))
        assertEquals(1, companionMediaSelectionIndex(listOf(false, true), preferredIndex = 0))
        assertTrue(companionMediaActionSupported(CompanionMediaAction.PLAY_PAUSE, false, android.media.session.PlaybackState.ACTION_PLAY))
        assertFalse(companionMediaActionSupported(CompanionMediaAction.PLAY_PAUSE, true, android.media.session.PlaybackState.ACTION_PLAY))
        assertTrue(companionMediaCommandMatches("token", "token"))
        assertFalse(companionMediaCommandMatches("old", "new"))
    }

    @Test
    fun media_timing_requires_a_finite_snapshot_before_showing_progress() {
        assertEquals(
            CompanionMediaTimingSnapshot(positionMs = 12_000L, durationMs = 60_000L),
            companionMediaTimingSnapshot(12_000L, 60_000L),
        )
        assertNull(companionMediaTimingSnapshot(null, 60_000L))
        assertNull(companionMediaTimingSnapshot(-1L, 60_000L))
        assertNull(companionMediaTimingSnapshot(60_001L, 60_000L))
        assertNull(companionMediaTimingSnapshot(1L, 0L))
    }

    @Test
    fun media_projection_advances_playing_snapshot_and_clamps_to_duration() {
        assertEquals(
            15_000L,
            companionMediaProjectedPosition(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackState = CompanionMediaPlaybackState.PLAYING,
                positionUpdateElapsedRealtimeMs = 20_000L,
                playbackSpeed = 1f,
                nowElapsedRealtimeMs = 25_000L,
            ),
        )
        assertEquals(
            60_000L,
            companionMediaProjectedPosition(
                positionMs = 59_000L,
                durationMs = 60_000L,
                playbackState = CompanionMediaPlaybackState.PLAYING,
                positionUpdateElapsedRealtimeMs = 20_000L,
                playbackSpeed = 2f,
                nowElapsedRealtimeMs = 25_000L,
            ),
        )
        assertEquals(
            10_000L,
            companionMediaProjectedPosition(
                positionMs = 10_000L,
                durationMs = 60_000L,
                playbackState = CompanionMediaPlaybackState.PAUSED,
                positionUpdateElapsedRealtimeMs = 20_000L,
                playbackSpeed = 1f,
                nowElapsedRealtimeMs = 25_000L,
            ),
        )
    }

    @Test
    fun media_projection_preserves_callback_position_for_invalid_clock_or_speed() {
        val base = 10_000L
        fun project(updateTime: Long, speed: Float, now: Long) = companionMediaProjectedPosition(
            positionMs = base,
            durationMs = 60_000L,
            playbackState = CompanionMediaPlaybackState.PLAYING,
            positionUpdateElapsedRealtimeMs = updateTime,
            playbackSpeed = speed,
            nowElapsedRealtimeMs = now,
        )

        assertEquals(base, project(updateTime = 0L, speed = 1f, now = 25_000L))
        assertEquals(base, project(updateTime = 30_000L, speed = 1f, now = 25_000L))
        assertEquals(base, project(updateTime = 20_000L, speed = -1f, now = 25_000L))
        assertEquals(base, project(updateTime = 20_000L, speed = Float.NaN, now = 25_000L))
        assertEquals(base, project(updateTime = 20_000L, speed = 1f, now = -1L))
    }

    @Test
    fun media_seek_is_bounded_and_requires_advertised_capability() {
        assertEquals(30_000L, companionMediaSeekPosition(30_000L, 60_000L, canSeek = true))
        assertNull(companionMediaSeekPosition(-1L, 60_000L, canSeek = true))
        assertNull(companionMediaSeekPosition(60_001L, 60_000L, canSeek = true))
        assertNull(companionMediaSeekPosition(30_000L, null, canSeek = true))
        assertNull(companionMediaSeekPosition(30_000L, 60_000L, canSeek = false))
    }

    @Test
    fun media_commands_require_both_fresh_session_and_track_identity() {
        assertTrue(companionMediaCommandMatches("session-a", "session-a"))
        assertFalse(companionMediaCommandMatches("session-a", "session-b"))
        assertTrue(companionMediaTrackMatches("track-a", "track-a"))
        assertFalse(companionMediaTrackMatches("track-a", "track-b"))
        assertTrue(companionMediaTrackMatches(null, "track-b"))
    }

    @Test
    fun media_artwork_ignores_remote_uris() {
        assertTrue(companionMediaArtworkUriIsLocal("content://media/42"))
        assertTrue(companionMediaArtworkUriIsLocal("file:///tmp/cover.png"))
        assertFalse(companionMediaArtworkUriIsLocal("https://example.invalid/cover.png"))
        assertFalse(companionMediaArtworkUriIsLocal("cover.png"))
    }

    @Test
    fun media_skip_and_transient_art_policy_are_bounded_and_generation_safe() {
        assertEquals(
            CompanionMediaPlaybackState.SKIPPING,
            CompanionMediaSessionBridge.companionMediaPlaybackState(
                android.media.session.PlaybackState.STATE_SKIPPING_TO_NEXT,
            ),
        )
        assertTrue(
            companionMediaArtworkMayRetainPrevious(
                sameSession = true,
                retentionStartedAtElapsedMs = 1_000L,
                nowElapsedMs = 2_499L,
                candidateState = CompanionMediaArtworkLoadState.LOADING,
            ),
        )
        assertFalse(
            companionMediaArtworkMayRetainPrevious(
                sameSession = true,
                retentionStartedAtElapsedMs = 1_000L,
                nowElapsedMs = 2_501L,
                candidateState = CompanionMediaArtworkLoadState.ERROR,
            ),
        )
        assertFalse(
            companionMediaArtworkMayRetainPrevious(
                sameSession = false,
                retentionStartedAtElapsedMs = 1_000L,
                nowElapsedMs = 1_100L,
                candidateState = CompanionMediaArtworkLoadState.NO_ART,
            ),
        )
        assertTrue(companionMediaArtworkCompletionAccepted("A", 3L, "A", 3L))
        assertFalse(companionMediaArtworkCompletionAccepted("A", 3L, "A", 2L))
        assertFalse(companionMediaArtworkCompletionAccepted("A", 3L, "B", 3L))
    }

    @Test
    fun dock_lock_preserves_the_selected_game_and_blocks_entry_actions() {
        val open = companionActivityDetailState("game:1", "game:1", true, true)
        val dock = companionActivityDetailState(open.selectedSessionKey, "game:1", true, false)
        assertTrue(dock.showGame)
        assertEquals("game:1", dock.selectedSessionKey)
        assertFalse(dock.canOpen)
        assertEquals(open, companionActivityDetailState(dock.selectedSessionKey, "game:1", true, true))
    }

    @Test
    fun game_completion_or_replacement_clears_the_old_detail_even_while_dock_is_open() {
        assertNull(companionActivityDetailState("game:1", "game:1", false, false).selectedSessionKey)
        assertNull(companionActivityDetailState("game:1", "game:2", true, false).selectedSessionKey)
        assertNull(companionActivityDetailState("game:1", null, true, true).selectedSessionKey)
    }

    @Test
    fun back_returns_to_media_without_invalidating_the_live_game_entry() {
        val media = companionActivityDetailState(null, "game:1", true, true)
        assertFalse(media.showGame)
        assertTrue(media.canOpen)
    }

    @Test
    fun session_elapsed_uses_monotonic_dispatch_time_and_rejects_unknown_or_future_start() {
        assertEquals(12_000L, companionActivitySessionElapsed(8_000L, 20_000L))
        assertNull(companionActivitySessionElapsed(null, 20_000L))
        assertNull(companionActivitySessionElapsed(21_000L, 20_000L))
        assertNull(companionActivitySessionElapsed(-1L, 20_000L))
    }

    @Test
    fun achievement_summary_is_bound_to_game_account_and_connected_service_access() {
        val snapshot = CompanionActivityAchievementSnapshot("game", "player", 3, 10)
        assertEquals(snapshot, companionActivityAchievementFor(snapshot, "game", "player", true))
        assertNull(companionActivityAchievementFor(snapshot, "other", "player", true))
        assertNull(companionActivityAchievementFor(snapshot, "game", "another-player", true))
        assertNull(companionActivityAchievementFor(snapshot, "game", "player", false))
        assertNull(companionActivityAchievementFor(snapshot.copy(total = 0), "game", "player", true))
        assertNull(companionActivityAchievementFor(snapshot.copy(unlocked = 11), "game", "player", true))
    }

    @Test
    fun achievement_hub_projection_keeps_only_valid_rows_for_the_current_account() {
        val current = CompanionActivityAchievementSnapshot(
            gameId = "current-game",
            account = "player",
            unlocked = 3,
            total = 10,
            checkedAtEpochMillis = 20L,
        )
        val otherAccount = current.copy(gameId = "other-account-game", account = "other")
        val emptySet = current.copy(gameId = "empty-set", total = 0, unlocked = 0)
        val impossibleProgress = current.copy(gameId = "impossible-progress", unlocked = 11)

        assertEquals(
            listOf(current),
            companionActivityAchievementSnapshotsFor(
                listOf(otherAccount, impossibleProgress, current, emptySet),
                account = "player",
                enabled = true,
            ),
        )
        assertTrue(
            companionActivityAchievementSnapshotsFor(
                listOf(current),
                account = "player",
                enabled = false,
            ).isEmpty(),
        )
    }
}
