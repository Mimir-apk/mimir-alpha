package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.session.MediaSession
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import com.gruning.mimir.rewrite.feature.companion.CompanionPaneLiveScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * W2d: Main's music panel at full size on the Bottom. The media tile on the Activity page opens it as a scene of the page's own swap, on the real
 * [CompanionWorkspaceSurface] at the Presentation's measured 1240 x 1080 px at 369 dpi (537.7 x 468.3 dp), paused clock, with a fake engine and system volume
 * (the production ones are process singletons).
 *
 * Entry, exit, precedence over the takeover, the layout at font scale 1.0 and 1.3, the art that does not respond, the volume receipts (one per lift), the
 * transport and switch receipts, "Stopped" when playback ends under the open panel, reduced motion, and the sideways drag each control owns. Each layout
 * prints its measured geometry as `MUSIC-ZONES`. Pixels are the JVM's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionMusicPanelSceneTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private var density = 0f

    private class FakeEngine(initial: MusicControlState, library: com.gruning.mimir.rewrite.core.audio.MusicLibraryView = com.gruning.mimir.rewrite.core.audio.MusicLibraryView()) : MusicPanelEngine {
        override val state = MutableStateFlow(initial)
        override val library = MutableStateFlow(library)
        override val musicVolume = MutableStateFlow(0.4f)
        override val mediaVolume = MutableStateFlow(0.7f)
        override val cappedByMedia = MutableStateFlow(false)
        override val equalizer = MutableStateFlow(EqualizerSettings(enabled = true))
        val calls = mutableListOf<String>()

        override fun playPause(source: MusicSource) { calls += "playPause:$source" }
        override fun next(source: MusicSource) { calls += "next:$source" }
        override fun previous(source: MusicSource) { calls += "previous:$source" }
        override fun setMusicVolume(fraction: Float) {
            calls += "music"
            musicVolume.value = fraction
        }
        override fun setMediaVolume(fraction: Float) {
            calls += "media"
            mediaVolume.value = fraction
        }
        override fun applyEqualizerPreset(id: EqualizerPresetId) {
            calls += "preset"
            equalizer.value = equalizer.value.withPreset(id)
        }
        override fun seekYourMusic(positionMs: Long) {
            calls += "seek:$positionMs"
        }
        override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean {
            calls += "open"
            return true
        }
    }

    private class FakeMedia : MediaVolumeGateway {
        override fun isAvailable() = true
        override fun isFixedVolume() = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? = null
    }

    private val art: Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private val yours = YourMusicState(title = "Dreams of the Hedgehog", isPlaying = false, canPrevious = true, canNext = true)
    private val spotify = OtherPlayerSnapshot(
        title = "A Track With A Fairly Long Name",
        artist = "Some Artist",
        appLabel = "Spotify",
        artwork = art,
        isPlaying = true,
        canPrevious = true,
        canNext = true,
        packageName = "example.player",
    )

    /** Another player full (its art and a switch row for Your music), the equalizer on: every part of the panel. */
    private val bothState = MusicControlState(yours = yours, other = spotify, otherAccess = true)

    /** Your music alone, no notification access: the foot hint and the equalizer, no art. */
    private val yoursOnlyState = MusicControlState(yours = yours.copy(isPlaying = true), otherAccess = false)

    private var engine = FakeEngine(bothState)
    private var takeoverKey by mutableStateOf<String?>(null)
    /** The host's process state for the player (Y0), and whether the Activity scene is in composition at all (it is not, on the shelf). */
    private var playerOpen by mutableStateOf(false)
    private var showSurface by mutableStateOf(true)
    /** D24: the workspace pane this surface sits in is parked (neither shown nor dragged). */
    private var parkedPane by mutableStateOf(false)
    private val bridgeSeeks = mutableListOf<Long>()
    private var back = 0

    @Before
    fun setUp() {
        ShadowLog.clear()
        BottomUiReceipts.log.resetWindow(DISPLAY, BottomSurface.PRESENTATION_OWNED)
        // The log rate-limits a control id to a few lines a second, process-wide: tests that open the panel one after another would starve each other of receipts.
        (com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog::class.java.getDeclaredField("controlWindows").apply { isAccessible = true }.get(BottomUiReceipts.log) as MutableMap<*, *>).clear()
        takeoverKey = null
        playerOpen = false
        showSurface = true
        parkedPane = false
        bridgeSeeks.clear()
        back = 0
    }

    @After
    fun restoreAnimatorScale() = setAnimatorScale(1f)

    /** Animator scale (Developer options): 0 is reduced motion. The setter is hidden, so by reflection. */
    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun track(): CompanionMediaTrack {
        val token = MediaSession(ApplicationProvider.getApplicationContext<Application>(), "music-panel-scene").sessionToken
        return CompanionMediaTrack(
            title = "A Track With A Fairly Long Name",
            artist = "Some Artist",
            appLabel = "Spotify",
            packageName = "example.player",
            canPrevious = true,
            canPlayPause = true,
            canNext = true,
            sessionIdentity = token,
            trackIdentity = "track-1",
            playbackState = CompanionMediaPlaybackState.PLAYING,
            positionMs = 61_000L,
            durationMs = 240_000L,
            canSeek = true,
        )
    }

    private val zone = ZoneId.of("UTC")
    private val liveRow = GameRow(gameId = GameId("g1"), title = "Sonic the Hedgehog 2", platformId = PlatformId("genesis"), memberCount = 1, duplicateKind = null)

    private fun bento(live: Boolean = false) = ActivityBentoData(
        gameCount = 12,
        launched = listOf(liveRow),
        shelfPool = emptyList(),
        week = null,
        liveGameId = if (live) liveRow.gameId else null,
        liveTitle = if (live) liveRow.title else null,
        liveRow = if (live) liveRow else null,
        nowMillis = 0L,
        zone = zone,
    )

    private fun session() = MimirGameSession(
        operation = MimirGameSessionOperation("op-music-panel"),
        sequence = 1,
        gameId = liveRow.gameId,
        title = liveRow.title,
        platformId = liveRow.platformId,
        returnAnchor = MimirGameReturnAnchor.Library(
            com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget.shelf(
                com.gruning.mimir.rewrite.feature.library.LibraryShelfMode.EMULATION,
                "shelf",
            ),
        ),
        startedAtEpochMs = 1,
        dispatchedAtElapsedMs = 1_000_000L,
        targetPackageName = "org.example.emulator",
        dualScreen = false,
        phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    )

    private var drags = mutableListOf<Float>()
    private var dragEnds = 0

    private fun mount(
        fontScale: Float = 1.0f,
        state: MusicControlState = bothState,
        media: CompanionMediaUiState = CompanionMediaUiState.Active(track()),
        withTakeover: Boolean = false,
        library: com.gruning.mimir.rewrite.core.audio.MusicLibraryView = com.gruning.mimir.rewrite.core.audio.MusicLibraryView(),
    ) {
        engine = FakeEngine(state, library)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale), LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    density = LocalDensity.current.density
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        if (showSurface) {
                        val game = if (withTakeover) session() else null
                        CompanionPaneLiveScope(live = !parkedPane) {
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = true,
                            recentApp = null,
                            game = game,
                            media = media,
                            onRecentApp = {},
                            onReturnToGame = null,
                            onMediaAccess = {},
                            onMediaAction = { _, _, _ -> },
                            bento = bento(live = withTakeover),
                            gameCardAvailableGameId = game?.gameId,
                            gameCardSessionKey = game?.let { nowPlayingSessionKey(it) },
                            takeoverKey = takeoverKey,
                            onTakeoverKeyChange = if (withTakeover) ({ takeoverKey = it }) else null,
                            gameCardContent = if (withTakeover) {
                                { _, _ -> Box(Modifier.fillMaxSize().testTag(TakeoverProbeTag)) }
                            } else {
                                null
                            },
                            onWorkspaceDrag = { delta -> drags += delta; 0f },
                            onWorkspaceDragEnd = { dragEnds += 1; true },
                            onWorkspaceDragCancel = {},
                            onMediaSeek = { position, _, _ -> bridgeSeeks += position },
                            musicPanelEngine = engine,
                            musicPanelMediaVolume = FakeMedia(),
                            musicPlayerOpen = playerOpen,
                            onMusicPlayerOpenChange = { playerOpen = it },
                        )
                        }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** A paused clock needs the write announced and two frames: one to deliver it, one to recompose. */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    /** The strip's tabs when the engine has no library to browse: the Library tab  is offered only with one, and [MusicLibraryBottomRenderTest] covers it. */
    private val StripTabs = MusicPlayerTab.entries.filter { it != MusicPlayerTab.LIBRARY }

    private fun receipts(): List<String> = ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }

    private fun count(fragment: String) = receipts().count { fragment in it }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private class Box4(val x: Float, val y: Float, val w: Float, val h: Float) {
        val right get() = x + w
        val bottom get() = y + h
        override fun toString() = "%.1fx%.1f@(%.1f,%.1f)".format(w, h, x, y)
    }

    private fun SemanticsNode.dp(): Box4 {
        val p = positionInRoot
        return Box4(p.x / density, p.y / density, size.width / density, size.height / density)
    }

    private fun bounds(tag: String): Box4 = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().dp()

    private fun pressable(label: String): SemanticsNode =
        compose.onAllNodes(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)
            .fetchSemanticsNodes().single()

    private fun overlaps(a: Box4, b: Box4, tol: Float = 0.5f) =
        a.x + a.w - tol > b.x && b.x + b.w - tol > a.x && a.y + a.h - tol > b.y && b.y + b.h - tol > a.y

    private fun scrollMax(tag: String): Float =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f

    private fun tapTile() {
        compose.onNodeWithTag(ActivityTileLowerLeftTag, useUnmergedTree = true).performTouchInput {
            // The art row's free end: not the art, not a button, not the seek lane.
            click(Offset(width * 0.85f, 14.dp.toPx()))
        }
        settle()
    }

    private fun openPanel() {
        tapTile()
        assertTrue("the panel is the scene", hasTag(CompanionMusicPanelTag))
    }

    // ── entry and exit ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on the media tile opens the panel and writes the open receipt and the surface receipt once`() {
        mount()
        assertTrue("the Activity page is the scene to begin with", hasTag(ActivityPageTag))
        assertFalse(hasTag(CompanionMusicPanelTag))
        tapTile()
        assertTrue("the panel replaced the page", hasTag(CompanionMusicPanelTag))
        assertFalse("and the page is gone once the swap has settled", hasTag(ActivityPageTag))
        assertEquals("the tile's open receipt, once: ${receipts()}", 1, count("id=activity-media.open_panel"))
        assertEquals("the panel's surface enter, once: ${receipts()}", 1, receipts().count { it.startsWith("ev=surface.enter surface=music-panel display=$DISPLAY") })
        assertEquals("the panel is still entered after the swap: nothing closed its surface under it: ${receipts()}", 0, receipts().count { it.startsWith("ev=surface.exit surface=music-panel") })
        assertEquals("no new transition receipt names the panel", 0, receipts().count { it.startsWith("ev=transition") && it.contains("music-panel") })
    }

    @Test
    fun `a tap on Play-Pause on the tile does not open the panel, nor does the seek lane, nor an access tile`() {
        mount()
        compose.onAllNodes(hasContentDescription("Pause") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)[0].performClick()
        settle()
        assertFalse("Play/Pause consumed the touch first", hasTag(CompanionMusicPanelTag))
        assertEquals(1, count("id=activity-media.play_pause"))
        assertEquals(0, count("id=activity-media.open_panel"))
        compose.onAllNodes(hasContentDescription("Seek playback"), useUnmergedTree = true)[0].performTouchInput { click() }
        settle()
        assertFalse("the seek lane consumed the touch first", hasTag(CompanionMusicPanelTag))
        assertEquals("and the tap on the lane seeked once, to the middle of the track (F13): $bridgeSeeks", 1, bridgeSeeks.size)
        assertEquals(0, count("id=activity-media.open_panel"))
    }

    @Test
    fun `an access tile does not open the panel`() {
        mount(state = MusicControlState(), media = CompanionMediaUiState.AccessRequired)
        compose.onNodeWithTag(ActivityTileLowerLeftTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.85f, 14.dp.toPx())) }
        settle()
        assertFalse(hasTag(CompanionMusicPanelTag))
        assertEquals(0, count("id=activity-media.open_panel"))
    }

    @Test
    fun `the collapse chevron returns to the Activity page and writes its receipt`() {
        mount()
        openPanel()
        compose.onNodeWithTag(CompanionMusicPlayerCollapseTag, useUnmergedTree = true).performClick()
        settle()
        assertTrue("back on the resting layout", hasTag(ActivityPageTag))
        assertFalse(hasTag(CompanionMusicPanelTag))
        assertEquals(1, count("id=music-panel.back_to_activity"))
        assertEquals("the surface left once: ${receipts()}", 1, receipts().count { it.startsWith("ev=surface.exit surface=music-panel display=$DISPLAY") })
        assertEquals(0, back)
    }

    /** One frame-and-a-bit steps: the write is announced, then the clock runs [ms]. */
    private fun frames(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /**
     * D24: a swap that lands while the pane is PARKED snaps. The player closes (Now Playing ending under a swipe to the shelf does the same to the page) and
     * the page is back at once, with no departing page left to fade behind a pane nobody can see. Control: the same close in a live pane is still animating
     * 48 ms in.
     */
    @Test
    fun `a swap that lands while the pane is parked snaps, and a live one animates`() {
        mount()
        openPanel()
        frames(1_000L)
        // The control: live, the departing panel is still on screen part-way through its exit.
        playerOpen = false
        frames(48L)
        assertTrue("fixture: a live swap is still running 48 ms in", hasTag(CompanionMusicPanelTag))
        frames(1_000L)
        assertFalse(hasTag(CompanionMusicPanelTag))

        openPanel()
        frames(1_000L)
        parkedPane = true
        frames(100L)
        playerOpen = false
        frames(48L)
        assertFalse("parked, the swap snapped: no departing page 48 ms in", hasTag(CompanionMusicPanelTag))
        assertTrue("and the page it landed on is there", hasTag(ActivityPageTag))
    }

    @Test
    fun `a takeover requested while the panel is open is dropped, and Back lands on the resting layout`() {
        mount(withTakeover = true)
        takeoverKey = null
        // The live game's takeover is not open to begin with here: the tile shows (the host holds no key), the panel opens from it.
        openPanel()
        // The launch glide sets the host's key while the panel is up.
        takeoverKey = nowPlayingSessionKey(session())
        settle()
        assertTrue("the panel outranks the takeover", hasTag(CompanionMusicPanelTag))
        assertFalse("the takeover did not draw", hasTag(TakeoverProbeTag))
        assertNull("the automatic entry was dropped, not queued", takeoverKey)
        compose.onNodeWithTag(CompanionMusicPlayerCollapseTag, useUnmergedTree = true).performClick()
        settle()
        assertFalse("Back did not reveal the dropped takeover", hasTag(TakeoverProbeTag))
        assertTrue(hasTag(ActivityPageTag))
    }

    @Test
    fun `the player is still open after the Activity scene leaves composition and comes back`() {
        mount()
        openPanel()
        assertTrue("the host holds the player open", playerOpen)
        // The shelf: the Activity scene is not composed at all. Y0: the flag is the host's, so nothing here forgets it.
        showSurface = false
        settle()
        assertFalse("the scene is gone", hasTag(CompanionMusicPanelTag))
        assertTrue("and the host still says open", playerOpen)
        showSurface = true
        parkedPane = false
        settle()
        assertTrue("the player is as it was left", hasTag(CompanionMusicPanelTag))
        assertFalse("and not the resting layout", hasTag(ActivityPageTag))
    }

    @Test
    fun `a close from the player clears the host's flag, so the shelf trip after it finds the resting layout`() {
        mount()
        openPanel()
        compose.onNodeWithTag(CompanionMusicPlayerCollapseTag, useUnmergedTree = true).performClick()
        settle()
        assertFalse("the host's flag was cleared", playerOpen)
        showSurface = false
        settle()
        showSurface = true
        parkedPane = false
        settle()
        assertTrue("the resting layout", hasTag(ActivityPageTag))
        assertFalse(hasTag(CompanionMusicPanelTag))
    }

    // ── layout ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private data class Fixture(val name: String, val state: MusicControlState, val art: Boolean, val seek: Boolean)

    private fun assertLayout(fixture: Fixture, fontScale: Float) {
        val where = "${fixture.name} at fontScale=$fontScale"
        val canvasCenter = hostWidthDp / 2f
        val band = bounds(CompanionMusicPanelBandTag)
        val heading = bounds(CompanionMusicPanelHeadingTag)
        val chevron = bounds(CompanionMusicPlayerCollapseTag)
        val body = bounds(CompanionMusicPlayerBodyTag)
        val art = bounds(CompanionMusicPlayerArtTag)
        val column = bounds(CompanionMusicPlayerColumnTag)
        val title = bounds(CompanionMusicPlayerTitleTag)
        val transport = bounds(CompanionMusicPlayerTransportTag)
        val strip = bounds(CompanionMusicPlayerTabStripTag)
        val seek = bounds(MusicSeekBarTag)
        val total = bounds(MusicSeekTotalTag)
        val label = if (fixture.state.other?.isPlaying == true || fixture.state.yours?.isPlaying == true) "Pause" else "Play"
        val previous = pressable("Previous").dp()
        val play = pressable(label).dp()
        val next = pressable("Next").dp()
        println(
            "PLAYER-ZONES $where host=${"%.1f".format(hostWidthDp)}x${"%.1f".format(hostHeightDp)}dp band=$band heading=$heading chevron=$chevron body=$body art=$art column=$column title=$title " +
                "seek=$seek total=$total transport=$transport previous=$previous play=$play next=$next strip=$strip",
        )
        // The top bar: 48 dp, the chevron a 48 dp target at the left, the heading on the centre line and clear of the recent-app icon's reserve (the page's end less 62 dp).
        assertTrue("the band is at least 48 dp at $where: $band", band.h >= 47.9f)
        assertEquals("the chevron is a 48 dp target at $where", 48f, chevron.w, 0.6f)
        assertEquals("the chevron is a 48 dp target at $where", 48f, chevron.h, 0.6f)
        assertEquals("the heading's centre is the canvas centre at $where", canvasCenter, heading.x + heading.w / 2f, 1f)
        val reserveStart = hostWidthDp - ActivityPageInsetDp - ActivityRecentAppReserveDp
        assertTrue("the heading ends at ${heading.right}, before the recent-app reserve at $reserveStart at $where", heading.right <= reserveStart + 0.6f)
        assertTrue("the heading ${heading.x} starts after the chevron's end ${chevron.right} at $where", heading.x >= chevron.right - 0.6f)
        // The art: a square at the left gutter, as large as fits (264 dp: the body is taller), vertically centred in the body, ignoring taps.
        assertEquals("the art is square at $where", art.w, art.h, 0.6f)
        assertEquals("the art is 264 dp at $where", 264f, art.w, 0.6f)
        assertEquals("the art starts at the 24 dp gutter at $where", 24f, art.x, 0.6f)
        assertEquals("the art is vertically centred in the body at $where", body.y + body.h / 2f, art.y + art.h / 2f, 1f)
        if (fixture.art) {
            val image = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MusicPanelArtImageTestTag), useUnmergedTree = true).fetchSemanticsNodes().single().dp()
            assertEquals("the art image fills the art square at $where", 264f, image.w, 0.6f)
        }
        // The right column: at least 200 dp, 24 dp from the art, inside the right gutter; Play on its centre line.
        assertTrue("the column is at least 200 dp wide at $where: $column", column.w >= 199.9f)
        assertEquals("the column is 24 dp from the art at $where", 24f, column.x - art.right, 0.6f)
        assertTrue("the column stays inside the right gutter at $where", column.right <= hostWidthDp - 24f + 0.6f)
        assertEquals("Play's centre is the column's centre at $where", column.x + column.w / 2f, play.x + play.w / 2f, 1f)
        assertEquals("Play is 56 dp at $where", 56f, play.w, 0.6f)
        assertEquals("Previous's target is 56 dp at $where", 56f, previous.w, 0.6f)
        assertEquals("Next's target is 56 dp at $where", 56f, next.w, 0.6f)
        assertTrue("Previous, Play, Next in that order at $where", previous.right <= play.x + 0.6f && play.right <= next.x + 0.6f)
        // The slot under the artist is kept whether or not the track has a position.
        assertEquals("the seek zone is 28 dp at $where", 28f, seek.h, 0.6f)
        assertEquals("the seek bar is the column's width at $where", column.w, seek.w, 0.6f)
        assertTrue("the transport is under the seek bar at $where", transport.y >= seek.bottom - 0.6f)
        // The tab strip is at the bottom (the 36 dp dock reserve under it), 44 dp tall, under the body.
        assertEquals("the strip ends at the dock reserve at $where", hostHeightDp - ActivityDockReserveDp, strip.bottom, 0.6f)
        assertEquals("the strip is 44 dp at $where", 44f, strip.h, 0.6f)
        assertTrue("the strip is under the body at $where", strip.y >= body.bottom - 0.6f)
        // Nothing clips: the whole column sits inside the body, the art too, and no control overlaps another.
        assertTrue("the title starts inside the body at $where", title.y >= body.y - 0.6f)
        assertTrue("the transport ends inside the body at $where: ${transport.bottom} vs ${body.bottom}", transport.bottom <= body.bottom + 0.6f)
        assertTrue("the seek times end inside the body at $where", total.bottom <= body.bottom + 0.6f)
        assertTrue("the art is inside the body at $where", art.y >= body.y - 0.6f && art.bottom <= body.bottom + 0.6f)
        val controls = linkedMapOf("chevron" to chevron, "previous" to previous, "play" to play, "next" to next)
        controls.forEach { (name, b) ->
            assertTrue("$name $b is at least 48 dp wide at $where", b.w >= 47.9f)
            assertTrue("$name $b is at least 48 dp tall at $where", b.h >= 47.9f)
        }
        val names = controls.keys.toList()
        for (i in names.indices) for (j in i + 1 until names.size) {
            assertFalse("${names[i]} must not overlap ${names[j]} at $where", overlaps(controls.getValue(names[i]), controls.getValue(names[j])))
        }
        // The three tabs share the strip, each at least 48 dp wide and the strip's height.
        val tabBoxes = StripTabs.map { bounds(companionMusicPlayerTabTag(it)) }
        tabBoxes.forEach { t ->
            assertTrue("a tab is at least 48 dp wide at $where: $t", t.w >= 47.9f)
            assertEquals("a tab is the strip's height at $where", 44f, t.h, 0.6f)
            assertTrue("a tab is inside the strip at $where", t.y >= strip.y - 0.6f && t.bottom <= strip.bottom + 0.6f)
        }
    }

    private val layoutFixtures = listOf(
        Fixture("other-player-with-art-and-position", bothState, art = true, seek = true),
        Fixture("yours-alone-without-position", yoursOnlyState, art = false, seek = false),
    )

    private fun layoutAt(fixture: Fixture, fontScale: Float) {
        mount(fontScale, fixture.state)
        openPanel()
        assertLayout(fixture, fontScale)
    }

    @Test
    fun `another player's card is YouTube Music's landscape player at the default font`() = layoutAt(layoutFixtures[0], 1.0f)

    @Test
    fun `Your music alone is the same player with the seek slot kept at the default font`() = layoutAt(layoutFixtures[1], 1.0f)

    @Test
    fun `another player's card keeps the player whole and every control at 48 dp or more at font scale 1_3`() = layoutAt(layoutFixtures[0], 1.3f)

    @Test
    fun `Your music alone keeps the player whole at font scale 1_3`() = layoutAt(layoutFixtures[1], 1.3f)

    @Test
    fun `the heading never runs under the recent-app reserve even at a larger font`() {
        mount(1.3f, layoutFixtures[0].state)
        openPanel()
        val heading = bounds(CompanionMusicPanelHeadingTag)
        val chevron = bounds(CompanionMusicPlayerCollapseTag)
        val reserveStart = hostWidthDp - ActivityPageInsetDp - ActivityRecentAppReserveDp
        assertTrue("the heading ends at ${heading.right}, before $reserveStart", heading.right <= reserveStart + 0.6f)
        assertTrue("the heading starts at ${heading.x}, after the chevron ends at ${chevron.right}", heading.x >= chevron.right - 0.6f)
        assertEquals("centred", hostWidthDp / 2f, heading.x + heading.w / 2f, 1f)
    }

    // ── the tab strip and its sheet ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun tapTab(tab: MusicPlayerTab) {
        compose.onNodeWithTag(companionMusicPlayerTabTag(tab), useUnmergedTree = true).performClick()
        settle()
    }

    private fun openSheet(tab: MusicPlayerTab) {
        tapTab(tab)
        assertTrue("the sheet is up for ${tab.name}", hasTag(CompanionMusicPlayerSheetTag))
    }

    @Test
    fun `the strip offers Volume, Equalizer and Source in that order with the sheet closed`() {
        mount()
        openPanel()
        assertFalse("no sheet to begin with", hasTag(CompanionMusicPlayerSheetTag))
        val xs = StripTabs.map { bounds(companionMusicPlayerTabTag(it)).x }
        assertEquals("in order", xs.sorted(), xs)
        assertEquals(3, StripTabs.count { hasTag(companionMusicPlayerTabTag(it)) })
        // The words are the tab's own: chrome text, capitals by intent, found by description as well.
        StripTabs.forEach { tab ->
            assertTrue("${tab.name} has its description", compose.onAllNodes(hasContentDescription("${tab.label} tab"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun `each tab raises the sheet with its own pieces and the same tab closes it`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.VOLUME)
        assertTrue("Game & media", hasTag(CompanionMusicPanelMediaRowTag))
        assertTrue("Music", hasTag(CompanionMusicPanelMusicRowTag))
        assertFalse("not the equalizer", hasTag(CompanionMusicPanelEqualizerRowTag))
        assertFalse("not the switch row", hasTag(MusicPanelSwitchRowTestTag))
        val sheet = bounds(CompanionMusicPlayerSheetTag)
        val strip = bounds(CompanionMusicPlayerTabStripTag)
        println("PLAYER-ZONES sheet=$sheet strip=$strip host=${"%.1f".format(hostHeightDp)}")
        assertTrue("the sheet is about 60% of the canvas at most: ${sheet.h}", sheet.h <= hostHeightDp * 0.6f + 0.6f)
        assertEquals("the sheet stands on the strip", strip.y, sheet.bottom, 0.6f)
        openSheet(MusicPlayerTab.EQUALIZER)
        assertTrue("the equalizer row", hasTag(CompanionMusicPanelEqualizerRowTag))
        assertFalse("and not the sliders", hasTag(CompanionMusicPanelMediaRowTag) || hasTag(CompanionMusicPanelMusicRowTag))
        openSheet(MusicPlayerTab.SOURCE)
        assertTrue("the switch row", hasTag(MusicPanelSwitchRowTestTag))
        assertFalse("and not the equalizer", hasTag(CompanionMusicPanelEqualizerRowTag))
        tapTab(MusicPlayerTab.SOURCE)
        assertFalse("the same tab closes the sheet", hasTag(CompanionMusicPlayerSheetTag))
        assertTrue("the player stays", hasTag(CompanionMusicPanelTag))
        assertEquals("three opens: ${receipts()}", 3, count("id=music-panel.tab_open"))
        assertEquals("and the one close", 1, count("id=music-panel.tab_close"))
    }

    @Test
    fun `Your music alone offers its foot hint under the Source tab`() {
        mount(1.0f, yoursOnlyState)
        openPanel()
        openSheet(MusicPlayerTab.SOURCE)
        assertFalse("there is no second source to switch to", hasTag(MusicPanelSwitchRowTestTag))
        val hint = yoursOnlyState.let { musicPanelModel(it, stopped = false, musicVolume = 0.4f, mediaVolume = 0.7f, mediaAvailable = true, mediaFixed = false, cappedByMedia = false, capPushed = false).accessHint }
        assertNotNull("the model carries the access hint", hint)
        assertTrue("and the sheet says it", compose.onAllNodes(hasContentDescription(hint!!) or hasText(hint), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `the chevron closes the sheet first and then collapses the player`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.VOLUME)
        compose.onNodeWithTag(CompanionMusicPlayerCollapseTag, useUnmergedTree = true).performClick()
        settle()
        assertFalse("the first press closed the sheet", hasTag(CompanionMusicPlayerSheetTag))
        assertTrue("and the player is still up", hasTag(CompanionMusicPanelTag))
        assertTrue(playerOpen)
        compose.onNodeWithTag(CompanionMusicPlayerCollapseTag, useUnmergedTree = true).performClick()
        settle()
        assertFalse("the second collapsed it", playerOpen)
        assertTrue(hasTag(ActivityPageTag))
        assertEquals(1, count("id=music-panel.back_to_activity"))
    }

    private fun dragDown(tag: String, dp: Float = 120f) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, dp.dp.toPx() / 6f), delayMillis = 40) }
            up()
        }
        settle()
    }

    @Test
    fun `a drag down on the sheet closes the sheet only, and a drag down on the player collapses it`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.VOLUME)
        dragDown(CompanionMusicPlayerSheetGripTag)
        assertFalse("the sheet closed", hasTag(CompanionMusicPlayerSheetTag))
        assertTrue("the player did not collapse with it", hasTag(CompanionMusicPanelTag) && playerOpen)
        dragDown(CompanionMusicPlayerArtTag)
        assertFalse("a drag down on the player collapsed it", playerOpen)
        assertTrue("to the resting layout", hasTag(ActivityPageTag))
        assertEquals("one collapse receipt", 1, count("id=music-panel.back_to_activity"))
    }

    @Test
    fun `a drag down that starts on the seek bar is the bar's and does not collapse the player`() {
        mount()
        openPanel()
        dragDown(MusicSeekBarTag)
        assertTrue("the player is still up", playerOpen && hasTag(CompanionMusicPanelTag))
    }

    @Test
    fun `a short or mostly sideways drag does not collapse the player`() {
        mount()
        openPanel()
        dragDown(CompanionMusicPlayerArtTag, dp = 30f)
        assertTrue("a short drag is not a collapse", playerOpen)
        compose.onNodeWithTag(CompanionMusicPlayerArtTag, useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(20.dp.toPx(), 20.dp.toPx()), delayMillis = 40) }
            up()
        }
        settle()
        assertTrue("a diagonal drag is not a collapse", playerOpen)
    }

    // ── the seek bar ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.let { config ->
            // The intent text's words are its description (clearAndSetSemantics), the plain Text's are its text.
            config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }.ifEmpty { config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("") }
        }

    private fun yoursTimed(canSeek: Boolean = true, position: Long = 5_000L, duration: Long = 3_725_000L) = yours.copy(
        isPlaying = true,
        positionMs = position,
        durationMs = duration,
        positionUpdatedAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
        positionAdvancing = false,
        canSeek = canSeek,
    )

    @Test
    fun `the seek bar shows the elapsed and total times in m_ss`() {
        mount()
        openPanel()
        assertEquals("elapsed", "1:01", textOf(MusicSeekElapsedTag))
        assertEquals("total", "4:00", textOf(MusicSeekTotalTag))
        assertTrue("a seekable bar says so", compose.onAllNodes(hasContentDescription("Seek playback")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `a track over an hour reads h_mm_ss`() {
        mount(1.0f, MusicControlState(yours = yoursTimed(), otherAccess = false))
        openPanel()
        assertEquals("0:05", textOf(MusicSeekElapsedTag))
        assertEquals("1:02:05", textOf(MusicSeekTotalTag))
        assertEquals("the formatter's own answer", "1:02:05", companionMediaFormatTime(3_725_000L))
        assertEquals("under an hour is m:ss", "59:59", companionMediaFormatTime(3_599_000L))
    }

    private fun scrub(from: Float, to: Float) {
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput {
            down(Offset(width * from, height / 2f))
            val steps = 6
            repeat(steps) { moveBy(Offset(width * (to - from) / steps, 0f), delayMillis = 40) }
        }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()
    }

    @Test
    fun `a drag on another player's seek bar seeks once on release through the bridge, never during the drag`() {
        mount()
        openPanel()
        scrub(0.1f, 0.6f)
        assertTrue("nothing is sent while the finger is down: $bridgeSeeks", bridgeSeeks.isEmpty())
        assertTrue("the workspace pan never saw the scrub: $drags", drags.isEmpty())
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { up() }
        settle()
        assertEquals("one seek on the release: $bridgeSeeks", 1, bridgeSeeks.size)
        assertTrue("at about 60% of the 240 s track: ${bridgeSeeks.single()}", kotlin.math.abs(bridgeSeeks.single() - 144_000L) < 24_000L)
        assertEquals("and one receipt", 1, count("id=music-panel.seek"))
        assertTrue("Your music was not asked: ${engine.calls}", engine.calls.none { it.startsWith("seek:") })
    }

    @Test
    fun `a drag on Your music's seek bar seeks the engine once on release`() {
        mount(1.0f, MusicControlState(yours = yoursTimed(), otherAccess = false))
        openPanel()
        scrub(0.1f, 0.5f)
        assertTrue("nothing is sent while the finger is down: ${engine.calls}", engine.calls.none { it.startsWith("seek:") })
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { up() }
        settle()
        val seeks = engine.calls.filter { it.startsWith("seek:") }
        assertEquals("one seek: ${engine.calls}", 1, seeks.size)
        val ms = seeks.single().removePrefix("seek:").toLong()
        assertTrue("at about half of the 3,725 s track: $ms", kotlin.math.abs(ms - 1_862_500L) < 400_000L)
        assertTrue("the bridge was not asked: $bridgeSeeks", bridgeSeeks.isEmpty())
    }

    /**
     * W3e fix round, item 2: Your music's gesture identity is the player's own per-playing key, not the title. A track change under the finger to a track with the same title
     * and the same length once left the gesture alive, and the release seeked the new track.
     */
    @Test
    fun `a track change mid-scrub to a track with the same title and length ends the gesture and the release seeks nothing`() {
        val first = yoursTimed().copy(trackKey = "/music/a.mp3#1")
        mount(1.0f, MusicControlState(yours = first, otherAccess = false))
        openPanel()
        scrub(0.1f, 0.5f)
        assertTrue("fixture: the drag is live and nothing is sent yet: ${engine.calls}", engine.calls.none { it.startsWith("seek:") })
        // The next track: the same title, the same length, another file.
        engine.state.value = MusicControlState(yours = first.copy(trackKey = "/music/b.mp3#2"), otherAccess = false)
        settle()
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { up() }
        settle()
        assertTrue("the release seeked nothing: ${engine.calls}", engine.calls.none { it.startsWith("seek:") })
        assertEquals("and wrote no seek receipt", 0, count("id=music-panel.seek"))
    }

    @Test
    fun `a scrub on the same track still seeks once when the player republishes the position under the finger`() {
        val first = yoursTimed().copy(trackKey = "/music/a.mp3#1")
        mount(1.0f, MusicControlState(yours = first, otherAccess = false))
        openPanel()
        scrub(0.1f, 0.5f)
        engine.state.value = MusicControlState(yours = first.copy(positionMs = 9_000L, positionUpdatedAtElapsedRealtimeMs = first.positionUpdatedAtElapsedRealtimeMs + 1L), otherAccess = false)
        settle()
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { up() }
        settle()
        assertEquals("one seek: ${engine.calls}", 1, engine.calls.count { it.startsWith("seek:") })
    }

    // ── F13: a tap on the seek track seeks (Thor re-check on combined16, 2026-10-08) ─────────────────────────────────────────────

    /** The media tile's own seek lane on the Activity page (no panel open), found by its description. */
    private fun tileSeekLane() = compose.onAllNodes(hasContentDescription("Seek playback"), useUnmergedTree = true)[0]

    private fun swipeAcross(node: androidx.compose.ui.test.SemanticsNodeInteraction) {
        node.performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, -12.dp.toPx()), delayMillis = 40) }
            up()
        }
        settle()
    }

    @Test
    fun `a single tap on the media tile's seek lane seeks to the tapped fraction, with no drag first`() {
        mount()
        tileSeekLane().performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        settle()
        assertEquals("one seek for one tap: $bridgeSeeks", 1, bridgeSeeks.size)
        assertTrue("at 25% of the 240 s track, not the 61 s it was at: ${bridgeSeeks.single()}", kotlin.math.abs(bridgeSeeks.single() - 60_000L) <= 100L)
        assertFalse("the tap on the lane never opens the panel", hasTag(CompanionMusicPanelTag))
        assertTrue("and never reaches the workspace pan: $drags", drags.isEmpty())
    }

    @Test
    fun `a tap seeks the same whether the track is playing or paused`() {
        val paused = track().copy(playbackState = CompanionMediaPlaybackState.PAUSED)
        mount(media = CompanionMediaUiState.Active(paused))
        tileSeekLane().performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        settle()
        assertEquals("one seek: $bridgeSeeks", 1, bridgeSeeks.size)
        assertTrue("at 75% of the 240 s track: ${bridgeSeeks.single()}", kotlin.math.abs(bridgeSeeks.single() - 180_000L) <= 100L)
    }

    @Test
    fun `a vertical swipe starting on the media tile's seek lane seeks nothing and does not pan the page`() {
        mount()
        swipeAcross(tileSeekLane())
        assertTrue("a swipe across the track is not a seek: $bridgeSeeks", bridgeSeeks.isEmpty())
        assertTrue("the lane still keeps the finger from the page's pan: $drags", drags.isEmpty())
        assertFalse(hasTag(CompanionMusicPanelTag))
    }

    @Test
    fun `a drag along the media tile's seek lane still seeks once on the release`() {
        mount()
        tileSeekLane().performTouchInput {
            down(Offset(width * 0.1f, height / 2f))
            repeat(6) { moveBy(Offset(width * 0.08f, 0f), delayMillis = 40) }
        }
        assertTrue("nothing is sent while the finger is down: $bridgeSeeks", bridgeSeeks.isEmpty())
        tileSeekLane().performTouchInput { up() }
        settle()
        assertEquals("one seek on the release: $bridgeSeeks", 1, bridgeSeeks.size)
        assertTrue("at about 58% of the track: ${bridgeSeeks.single()}", kotlin.math.abs(bridgeSeeks.single() - 139_000L) < 24_000L)
    }

    @Test
    fun `a single tap on another player's panel seek bar seeks through the bridge to the tapped fraction`() {
        mount()
        openPanel()
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        settle()
        assertEquals("one seek: $bridgeSeeks", 1, bridgeSeeks.size)
        assertTrue("at 25% of the 240 s track: ${bridgeSeeks.single()}", kotlin.math.abs(bridgeSeeks.single() - 60_000L) <= 100L)
        assertEquals("and one receipt", 1, count("id=music-panel.seek"))
        assertTrue("the player stays open: a tap on the bar is the bar's", playerOpen && hasTag(CompanionMusicPanelTag))
    }

    @Test
    fun `a single tap on Your music's seek bar seeks the engine to the tapped fraction`() {
        mount(1.0f, MusicControlState(yours = yoursTimed(), otherAccess = false))
        openPanel()
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        settle()
        val seeks = engine.calls.filter { it.startsWith("seek:") }
        assertEquals("one seek: ${engine.calls}", 1, seeks.size)
        val ms = seeks.single().removePrefix("seek:").toLong()
        assertTrue("at 25% of the 3,725 s track: $ms", kotlin.math.abs(ms - 931_250L) <= 2_000L)
    }

    @Test
    fun `a vertical swipe across the panel's seek bar seeks nothing`() {
        mount()
        openPanel()
        swipeAcross(compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true))
        assertTrue("not a seek: $bridgeSeeks", bridgeSeeks.isEmpty())
        assertTrue("Your music was not asked: ${engine.calls}", engine.calls.none { it.startsWith("seek:") })
        assertEquals("and wrote no seek receipt", 0, count("id=music-panel.seek"))
    }

    @Test
    fun `the track position under a finger is its fraction of the length, clamped to the track`() {
        assertEquals(60_000L, seekTrackPositionAt(x = 25f, widthPx = 100, durationMs = 240_000L))
        assertEquals("left of the track is the start", 0L, seekTrackPositionAt(x = -30f, widthPx = 100, durationMs = 240_000L))
        assertEquals("right of the track is the end", 240_000L, seekTrackPositionAt(x = 400f, widthPx = 100, durationMs = 240_000L))
        assertEquals("a zero-width track divides by nothing", 0L, seekTrackPositionAt(x = 0f, widthPx = 0, durationMs = 240_000L))
    }

    @Test
    fun `a track that cannot be seeked shows read-only progress and takes no drag`() {
        mount(1.0f, MusicControlState(yours = yoursTimed(canSeek = false), otherAccess = false))
        openPanel()
        assertTrue("progress is read-only", compose.onAllNodes(hasContentDescription("Playback progress")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no seek action", compose.onAllNodes(hasContentDescription("Seek playback")).fetchSemanticsNodes().isEmpty())
        scrub(0.1f, 0.6f)
        compose.onNodeWithTag(MusicSeekBarTag, useUnmergedTree = true).performTouchInput { up() }
        settle()
        assertTrue("nothing was seeked: ${engine.calls}", engine.calls.none { it.startsWith("seek:") })
    }

    @Test
    fun `there is no seek bar without a position and its slot is kept so nothing jumps`() {
        mount(1.0f, yoursOnlyState)
        openPanel()
        assertTrue("no bar to seek", compose.onAllNodes(hasContentDescription("Seek playback")).fetchSemanticsNodes().isEmpty())
        assertTrue("and no progress either", compose.onAllNodes(hasContentDescription("Playback progress")).fetchSemanticsNodes().isEmpty())
        assertEquals("the slot is the same 28 dp", 28f, bounds(MusicSeekBarTag).h, 0.6f)
    }

    // ── the art, the controls and their receipts ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tap on the art does nothing on the Bottom`() {
        mount()
        openPanel()
        val artNode = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MusicPanelArtImageTestTag), useUnmergedTree = true)
        artNode[0].performClick()
        settle()
        assertTrue("the panel stays", hasTag(CompanionMusicPanelTag))
        assertFalse("the other player's app was not opened: ${engine.calls}", "open" in engine.calls)
        assertTrue("and nothing else was done: ${engine.calls}", engine.calls.isEmpty())
        assertEquals(0, receipts().count { it.contains("id=music-panel.") })
    }

    private fun transportNode(label: String) =
        compose.onAllNodes(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)[0]

    /**
     * W3e fix round, item 1: the controller's touch tracking is back. A press acts on the source it began on: the controller holds a follow of the playing source while a
     * finger is down, so the card does not move under it, and the release skips the source that was pressed.
     */
    @Test
    fun `a press on Next acts on the source it began on when another player starts under the finger`() {
        val yoursPlaying = yours.copy(isPlaying = true)
        mount(1.0f, MusicControlState(yours = yoursPlaying, other = spotify.copy(isPlaying = false), otherAccess = true))
        openPanel()
        transportNode("Next").performTouchInput { down(center) }
        // Another player starts and Your music pauses: untracked, the card would now be that player's.
        engine.state.value = MusicControlState(yours = yoursPlaying.copy(isPlaying = false), other = spotify.copy(isPlaying = true), otherAccess = true)
        settle()
        assertTrue("nothing moved under the finger: ${engine.calls}", engine.calls.isEmpty())
        transportNode("Next").performTouchInput { up() }
        settle()
        assertEquals("Your music was skipped, the other player was not: ${engine.calls}", listOf("next:${MusicSource.YOUR_MUSIC}"), engine.calls)
    }

    @Test
    fun `once the finger lifts the card follows the player that started`() {
        val yoursPlaying = yours.copy(isPlaying = true)
        mount(1.0f, MusicControlState(yours = yoursPlaying, other = spotify.copy(isPlaying = false), otherAccess = true))
        openPanel()
        transportNode("Next").performTouchInput { down(center) }
        engine.state.value = MusicControlState(yours = yoursPlaying.copy(isPlaying = false), other = spotify.copy(isPlaying = true), otherAccess = true)
        settle()
        transportNode("Next").performTouchInput { up() }
        settle()
        engine.calls.clear()
        transportNode("Next").performClick()
        settle()
        assertEquals("the next press is on the player that started: ${engine.calls}", listOf("next:${MusicSource.OTHER_PLAYER}"), engine.calls)
    }

    @Test
    fun `a press whose source has gone does nothing rather than act on the player now shown`() {
        val yoursPlaying = yours.copy(isPlaying = true)
        mount(1.0f, MusicControlState(yours = yoursPlaying, other = spotify.copy(isPlaying = false), otherAccess = true))
        openPanel()
        transportNode("Next").performTouchInput { down(center) }
        // Your music is gone altogether: the card that is shown changes in spite of the finger.
        engine.state.value = MusicControlState(other = spotify.copy(isPlaying = true), otherAccess = true)
        settle()
        transportNode("Next").performTouchInput { up() }
        settle()
        assertTrue("the release did nothing: ${engine.calls}", engine.calls.isEmpty())
        assertEquals("and wrote no receipt", 0, count("id=music-panel.next"))
    }

    @Test
    fun `the transport, the switch row and the equalizer act and each writes its receipt`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.SOURCE)
        compose.onNodeWithTag(MusicPanelSwitchRowTestTag, useUnmergedTree = true).performClick()
        settle()
        assertEquals(1, count("id=music-panel.switch"))
        assertTrue("the row played Your music: ${engine.calls}", "playPause:${MusicSource.YOUR_MUSIC}" in engine.calls)
        // After the switch the full card is Your music's (its transport is there under the same labels); the receipts name the control, not the player.
        engine.calls.clear()
        // The sheet covers the lower part of the player: the same tab closes it before the transport is pressed.
        tapTab(MusicPlayerTab.SOURCE)
        compose.onAllNodes(hasContentDescription("Next") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)[0].performClick()
        settle()
        assertEquals(1, count("id=music-panel.next"))
        assertEquals(1, engine.calls.count { it.startsWith("next:") })
        compose.onAllNodes(hasContentDescription("Previous") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)[0].performClick()
        settle()
        assertEquals(1, count("id=music-panel.previous"))
        compose.onAllNodes(
            (hasContentDescription("Pause") or hasContentDescription("Play")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            useUnmergedTree = true,
        )[0].performClick()
        settle()
        assertEquals(1, count("id=music-panel.play_pause"))
        assertEquals(1, engine.calls.count { it.startsWith("playPause:") })
        openSheet(MusicPlayerTab.EQUALIZER)
        compose.onNodeWithText("›", useUnmergedTree = true).performClick()
        settle()
        assertEquals(1, count("id=music-panel.equalizer"))
        assertTrue("the preset was applied: ${engine.calls}", "preset" in engine.calls)
    }

    @Test
    fun `a volume drag writes one receipt on the lift, a tap one, and a swipe across the track none`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.VOLUME)
        fun music() = compose.onNodeWithTag(CompanionMusicPanelMusicRowTag, useUnmergedTree = true)
        music().performTouchInput {
            val y = height - 21.dp.toPx()
            down(Offset(width * 0.2f, y))
            repeat(6) { moveBy(Offset(width * 0.08f, 0f), delayMillis = 40) }
            up()
        }
        settle()
        assertTrue("the drag moved the value: ${engine.calls}", engine.calls.count { it == "music" } >= 2)
        assertEquals("one receipt for one lift: ${receipts()}", 1, count("id=music-panel.music_volume"))
        music().performTouchInput {
            val y = height - 21.dp.toPx()
            down(Offset(width * 0.3f, y))
            repeat(4) { moveBy(Offset(-width * 0.05f, 0f), delayMillis = 40) }
            up()
        }
        settle()
        assertEquals("a second lift is a second receipt", 2, count("id=music-panel.music_volume"))
        val before = engine.calls.size
        music().performTouchInput {
            val y = height - 21.dp.toPx()
            down(Offset(width * 0.5f, y))
            repeat(4) { moveBy(Offset(0f, -30f), delayMillis = 40) }
            up()
        }
        settle()
        assertEquals("a swipe across the track moved nothing: ${engine.calls}", before, engine.calls.size)
        assertEquals("and wrote nothing", 2, count("id=music-panel.music_volume"))
        compose.onNodeWithTag(CompanionMusicPanelMediaRowTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.5f, height - 21.dp.toPx())) }
        settle()
        assertEquals("a tap on the media track writes one", 1, count("id=music-panel.media_volume"))
        assertTrue(engine.calls.any { it == "media" })
    }

    @Test
    fun `playback ending under the open panel leaves it up and says Stopped`() {
        mount()
        openPanel()
        engine.state.value = MusicControlState(otherAccess = true)
        settle()
        assertTrue("the panel stays", hasTag(CompanionMusicPanelTag))
        // The state word is chrome (capitals by intent), so it is read from the text or the description, whichever the semantics carries.
        val words = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        val stopped = words.any { it.startsWith("Stopped", ignoreCase = true) }
        assertTrue("the status line says Stopped: $words", stopped)
    }

    // ── motion ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun readersAt(scale: Float): Pair<Boolean, Boolean> {
        setAnimatorScale(scale)
        var panelReads = false
        var bottomReads = false
        compose.setContent {
            MimirTheme {
                panelReads = mimirReducedMotion()
                bottomReads = bottomUiReducedMotion()
            }
        }
        compose.waitForIdle()
        return panelReads to bottomReads
    }

    @Test
    fun `the panel's reduced-motion read and the Bottom's are one answer when animations are off`() {
        val (panel, bottom) = readersAt(0f)
        assertTrue("the panel's own read says reduced", panel)
        assertTrue("the Bottom's read says reduced", bottom)
    }

    @Test
    fun `the panel's reduced-motion read and the Bottom's are one answer when animations are on`() {
        val (panel, bottom) = readersAt(1f)
        assertFalse(panel)
        assertFalse(bottom)
    }

    @Test
    fun `under reduced motion the panel is the scene in the first frame after the tap`() {
        setAnimatorScale(0f)
        mount()
        compose.onNodeWithTag(ActivityTileLowerLeftTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.85f, 14.dp.toPx())) }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(48L)
        compose.waitForIdle()
        assertTrue("the panel is there at once", hasTag(CompanionMusicPanelTag))
        assertFalse("and the page is gone with no fade to wait for", hasTag(ActivityPageTag))
    }

    // ── the sideways drag ───────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the workspace swipe stays live on the panel's ground and a transport button keeps its own sideways drag`() {
        mount()
        openPanel()
        val root = compose.onRoot()
        // The page's free ground: the top row to the right of the heading.
        root.performTouchInput {
            down(Offset(width * 0.92f, 22.dp.toPx()))
            repeat(8) { moveBy(Offset(-width * 0.06f, 0f), delayMillis = 40) }
        }
        compose.mainClock.advanceTimeBy(600L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        root.performTouchInput { moveBy(Offset(-1f, 0f), delayMillis = 16) }
        compose.mainClock.advanceTimeBy(100L)
        compose.waitForIdle()
        assertTrue("the host was offered the finger leftward: ${drags.sum()}", drags.sum() < -100f)
        root.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200L)
        compose.waitForIdle()
        assertEquals("a lift is the release", 1, dragEnds)

        drags.clear()
        val playNode = compose.onAllNodes(
            (hasContentDescription("Pause") or hasContentDescription("Play")) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().single()
        val center = playNode.boundsInRoot.center
        root.performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-width * 0.04f, 0f), delayMillis = 40) }
        }
        compose.mainClock.advanceTimeBy(600L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        root.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200L)
        compose.waitForIdle()
        assertTrue("a sideways drag that began on Play/Pause is the button's own and never reached the strip: ${drags.sum()}", drags.isEmpty() || drags.sum() > -1f)
    }

    // ── runtime: the pad gate, the focus tree, the receipts that follow movement ──────────────────────────────────────────────────

    private fun holdSampled(frames: Int): List<Long> {
        val held = mutableListOf<Long>()
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
            if (MimirTransitionInputGate.holding) held += compose.mainClock.currentTime
        }
        return held
    }

    @Test
    fun `the process pad gate is never held while the panel opens, while it is up, or through a source switch swap`() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { compose.mainClock.currentTime }
        try {
            mount()
            compose.onNodeWithTag(ActivityTileLowerLeftTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.85f, 14.dp.toPx())) }
            val opening = holdSampled(90)
            assertTrue("the panel is the scene", hasTag(CompanionMusicPanelTag))
            assertTrue("the gate was held at $opening ms while the panel opened", opening.isEmpty())
            // The card swap inside the panel: a press on the switch row changes the full card on the swap tier, which holds the gate on Main.
            tapTab(MusicPlayerTab.SOURCE)
            compose.onNodeWithTag(MusicPanelSwitchRowTestTag, useUnmergedTree = true).performClick()
            val swapping = holdSampled(90)
            assertTrue("the source switch actually changed the card (the swap ran): ${engine.calls}", "playPause:${MusicSource.YOUR_MUSIC}" in engine.calls)
            assertTrue("the gate was held at $swapping ms through the source swap", swapping.isEmpty())
            assertFalse(MimirTransitionInputGate.holding)
        } finally {
            MimirTransitionInputGate.reset()
            MimirTransitionInputGate.clock = { android.os.SystemClock.uptimeMillis() }
        }
    }

    @Test
    fun `no Compose focus target exists anywhere in the Bottom panel with the equalizer row on`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.EQUALIZER)
        assertTrue("the equalizer row is on the panel", hasTag(CompanionMusicPanelEqualizerRowTag))
        val nodes = compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val focusTargets = nodes.filter { SemanticsProperties.Focused in it.config || SemanticsActions.RequestFocus in it.config }
        assertTrue("a focus target is planted beside the FocusAuthority: ${focusTargets.map { it.config }}", focusTargets.isEmpty())
        // The step chevrons are the touch-only activatables.
        val labels = nodes.mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.label }
        assertTrue("the preset steppers are pressables: $labels", "Previous Equalizer" in labels && "Next Equalizer" in labels)
    }

    @Test
    fun `no Compose focus target exists in the Library sheet, at the artists or down at the tracks`() {
        // The review of the check above never opened the Library sheet, so the one list the Bottom added was never looked at.
        mount(state = yoursOnlyState, library = MusicLibraryFixtures.view())
        openPanel()
        openSheet(MusicPlayerTab.LIBRARY)
        assertTrue("the Library list is on the sheet", hasTag(MusicBrowserListTestTag))
        fun focusTargets() = compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { SemanticsProperties.Focused in it.config || SemanticsActions.RequestFocus in it.config }
        assertTrue("a focus target is planted beside the FocusAuthority at the artists: ${focusTargets().map { it.config }}", focusTargets().isEmpty())

        compose.onNodeWithTag(MusicBrowserRowTestTag + "alpha", useUnmergedTree = true).performClick()
        settle()
        compose.onNodeWithTag(MusicBrowserRowTestTag + "first", useUnmergedTree = true).performClick()
        settle()
        assertTrue("the tracks are on the sheet", hasTag(MusicBrowserBackTestTag))
        assertTrue("a focus target is planted beside the FocusAuthority at the tracks: ${focusTargets().map { it.config }}", focusTargets().isEmpty())
        // The rows, the Back chip and All music are pressables, not focus targets.
        val labels = compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.label }
        assertTrue("the Back chip and the rows are pressables: $labels", "Back" in labels)
    }

    @Test
    fun `the focus-target detector sees a Compose clickable, so the check above is not vacuous`() {
        compose.setContent {
            Box(Modifier.requiredSize(60.dp).testTag("probe-clickable").clickable { })
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("probe-clickable", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("a clickable plants a focus target the detector reports", SemanticsProperties.Focused in node.config || SemanticsActions.RequestFocus in node.config)
    }

    @Test
    fun `a tap on the level a slider already has writes no receipt`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.VOLUME)
        engine.mediaVolume.value = 0.5f
        engine.musicVolume.value = 0.5f
        settle()
        compose.onNodeWithTag(CompanionMusicPanelMediaRowTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.5f, height - 21.dp.toPx())) }
        settle()
        compose.onNodeWithTag(CompanionMusicPanelMusicRowTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.5f, height - 21.dp.toPx())) }
        settle()
        assertTrue("the taps reached the sliders: ${engine.calls}", "media" in engine.calls && "music" in engine.calls)
        assertEquals("the same Game & media level is no movement: ${receipts()}", 0, count("id=music-panel.media_volume"))
        assertEquals("the same Music level is no movement: ${receipts()}", 0, count("id=music-panel.music_volume"))
    }

    @Test
    fun `a push against the cap that the engine refuses writes no receipt`() {
        mount()
        openPanel()
        openSheet(MusicPlayerTab.VOLUME)
        engine.mediaVolume.value = 0.3f
        engine.musicVolume.value = 0.3f
        settle()
        compose.onNodeWithTag(CompanionMusicPanelMusicRowTag, useUnmergedTree = true).performTouchInput {
            val y = height - 21.dp.toPx()
            down(Offset(width * 0.5f, y))
            repeat(5) { moveBy(Offset(width * 0.08f, 0f), delayMillis = 40) }
            up()
        }
        settle()
        assertFalse("the engine was never asked to move Music past the cap: ${engine.calls}", "music" in engine.calls)
        assertEquals("a refused push is not a change: ${receipts()}", 0, count("id=music-panel.music_volume"))
    }

    private companion object {
        const val DISPLAY = 4
        const val TakeoverProbeTag = "music-panel-test-takeover-probe"
    }
}
