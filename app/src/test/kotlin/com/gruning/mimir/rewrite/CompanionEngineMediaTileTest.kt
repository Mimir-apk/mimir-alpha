package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.session.MediaSession
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * W2e: Your music reaches the Bottom. The Activity page's lower-left tile is the media tile whenever Main's music panel would have a card, built from the
 * panel's own model ([musicTileCard]); another player's bridge track keeps its tile. On the real [CompanionWorkspaceSurface] at the Presentation's
 * 537.7 x 468.3 dp, paused clock, with a fake engine (the production one is a process singleton).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionEngineMediaTileTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private class FakeEngine(initial: MusicControlState) : MusicPanelEngine {
        override val state = MutableStateFlow(initial)
        override val musicVolume = MutableStateFlow(0.4f)
        override val mediaVolume = MutableStateFlow(0.7f)
        override val cappedByMedia = MutableStateFlow(false)
        override val equalizer = MutableStateFlow(EqualizerSettings())
        val calls = mutableListOf<String>()
        override fun playPause(source: MusicSource) { calls += "playPause:$source" }
        override fun next(source: MusicSource) { calls += "next:$source" }
        override fun previous(source: MusicSource) { calls += "previous:$source" }
        override fun setMusicVolume(fraction: Float) { musicVolume.value = fraction }
        override fun setMediaVolume(fraction: Float) { mediaVolume.value = fraction }
        override fun applyEqualizerPreset(id: EqualizerPresetId) = Unit
        override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean = true
    }

    private class FakeMedia : MediaVolumeGateway {
        override fun isAvailable() = true
        override fun isFixedVolume() = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? = null
    }

    private val yoursPlaying = YourMusicState(title = "Dreams of the Hedgehog", isPlaying = true, canPrevious = true, canNext = true)
    private val yoursPaused = yoursPlaying.copy(isPlaying = false)
    private fun spotify(playing: Boolean, art: Bitmap? = null, pending: Boolean = false) = OtherPlayerSnapshot(
        title = "Spotify Song",
        artist = "Some Artist",
        appLabel = "Spotify",
        artwork = art,
        isPlaying = playing,
        canPrevious = true,
        canNext = true,
        artworkPending = pending,
    )

    /** The engine's other player for [track], by the production mapping (what the bridge's state becomes in the music facade). */
    private fun engineOf(track: CompanionMediaTrack): OtherPlayerSnapshot = otherPlayerSnapshotFor(CompanionMediaUiState.Active(track))!!

    private var engine = FakeEngine(MusicControlState())
    private val mediaActions = mutableListOf<CompanionMediaAction>()

    @Before
    fun setUp() {
        ShadowLog.clear()
        BottomUiReceipts.log.resetWindow(DISPLAY, BottomSurface.PRESENTATION_OWNED)
        // The log rate-limits a control id to a few lines a second, process-wide: tests that open the panel one after another would starve each other of receipts.
        (com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog::class.java.getDeclaredField("controlWindows").apply { isAccessible = true }.get(BottomUiReceipts.log) as MutableMap<*, *>).clear()
    }

    private fun bridgeTrack(
        state: CompanionMediaPlaybackState = CompanionMediaPlaybackState.PLAYING,
        canPrevious: Boolean = true,
        title: String = "Bridge Title",
        appLabel: String = "Player",
        packageName: String = "example.player",
    ): CompanionMediaTrack {
        val token = MediaSession(ApplicationProvider.getApplicationContext<Application>(), "engine-tile").sessionToken
        return CompanionMediaTrack(
            title = title,
            artist = "Anonymous",
            appLabel = appLabel,
            packageName = packageName,
            canPrevious = canPrevious,
            canPlayPause = true,
            canNext = true,
            sessionIdentity = token,
            trackIdentity = "track-1",
            playbackState = state,
            positionMs = 61_000L,
            durationMs = 240_000L,
            canSeek = true,
        )
    }

    private val row = GameRow(gameId = GameId("g1"), title = "Sonic the Hedgehog 2", platformId = PlatformId("genesis"), memberCount = 1, duplicateKind = null)

    private var navEnabled by androidx.compose.runtime.mutableStateOf(true)
    private var mediaState by androidx.compose.runtime.mutableStateOf<CompanionMediaUiState>(CompanionMediaUiState.NoActiveMedia)

    private fun mount(state: MusicControlState, media: CompanionMediaUiState = CompanionMediaUiState.NoActiveMedia) {
        mediaState = media
        navEnabled = true
        engine = FakeEngine(state)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f), LocalBottomUiDisplay provides DISPLAY) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = navEnabled,
                            recentApp = null,
                            game = null,
                            media = mediaState,
                            onRecentApp = {},
                            onReturnToGame = null,
                            onMediaAccess = {},
                            onMediaAction = { action, _, _ -> mediaActions += action },
                            bento = ActivityBentoData(
                                gameCount = 3,
                                launched = listOf(row),
                                shelfPool = emptyList(),
                                week = null,
                                liveGameId = null,
                                liveTitle = null,
                                liveRow = null,
                                nowMillis = 0L,
                                zone = ZoneId.of("UTC"),
                            ),
                            musicPanelEngine = engine,
                            musicPanelMediaVolume = FakeMedia(),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun receipts(): List<String> = ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }

    private fun count(fragment: String) = receipts().count { fragment in it }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Every word the semantics carries (text and descriptions), chrome in capitals included. */
    private fun words(): List<String> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }

    private fun saysAnything(fragment: String) = words().any { fragment.lowercase() in it.lowercase() }

    private fun hasPressable(label: String) = compose.onAllNodes(
        hasContentDescription(label) or SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().any { SemanticsActions.OnClick in it.config }

    private fun hasOpenPanel() = compose.onAllNodes(
        SemanticsMatcher("opens the music panel") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open music panel" },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    private fun tapTile() {
        compose.onNodeWithTag(ActivityTileLowerLeftTag, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.85f, 14.dp.toPx())) }
        settle()
    }

    private fun tap(label: String) {
        compose.onAllNodes(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true)[0].performClick()
        settle()
    }

    // ── the model ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the tile's card is the panel model's top card for every mix of sources`() {
        val other = spotify(playing = true)
        val states = listOf(
            MusicControlState(),
            MusicControlState(yours = yoursPlaying),
            MusicControlState(yours = yoursPaused),
            MusicControlState(other = other),
            MusicControlState(yours = yoursPlaying, other = other.copy(isPlaying = false)),
            MusicControlState(yours = yoursPaused, other = other),
            MusicControlState(yours = yoursPaused, other = other.copy(isPlaying = false), lastPlayed = MusicSource.OTHER_PLAYER),
            MusicControlState(yours = yoursPaused, other = other.copy(isPlaying = false), lastPlayed = MusicSource.YOUR_MUSIC),
        )
        for (state in states) {
            val panel = if (state.present) {
                musicPanelModel(state, stopped = false, musicVolume = 0.9f, mediaVolume = 0.2f, mediaAvailable = true, mediaFixed = false, cappedByMedia = true, capPushed = true).top
            } else {
                null
            }
            assertEquals("the tile reads the card the panel puts on top for $state", panel, musicTileCard(state))
        }
        assertNull("with no card, the tile is Been a while's", musicTileCard(MusicControlState()))
        assertEquals(MusicPanelCard.YOURS, musicTileCard(states[4])?.card)
        assertEquals(MusicPanelCard.OTHER, musicTileCard(states[5])?.card)
    }

    // ── Your music on the tile ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Your music alone is the media tile with its title, the plate and glyph for art, and it opens the panel`() {
        mount(MusicControlState(yours = yoursPlaying, otherAccess = true))
        assertTrue("the tile names the track: ${words()}", saysAnything("Dreams of the Hedgehog"))
        assertTrue("the art seat is there", hasTag(ActivityMediaArtTag))
        assertTrue("Your music has no art: the plate's glyph, never a blank", hasTag(MusicPanelArtFallbackTestTag))
        assertEquals("the tile's surface entered", 1, receipts().count { it.startsWith("ev=surface.enter surface=activity-media display=$DISPLAY") })
        tapTile()
        assertTrue("the panel is the scene", hasTag(CompanionMusicPanelTag))
        assertEquals(1, count("id=activity-media.open_panel"))
        assertTrue("the panel shows the same track: ${words()}", saysAnything("Dreams of the Hedgehog"))
    }

    @Test
    fun `the tile's transport drives the engine's own actions for the card's source and writes the tile's receipts`() {
        mount(MusicControlState(yours = yoursPlaying, otherAccess = true))
        tap("Pause")
        assertEquals(listOf("playPause:${MusicSource.YOUR_MUSIC}"), engine.calls)
        assertEquals(1, count("id=activity-media.play_pause"))
        tap("Next")
        tap("Previous")
        assertEquals(
            listOf("playPause:${MusicSource.YOUR_MUSIC}", "next:${MusicSource.YOUR_MUSIC}", "previous:${MusicSource.YOUR_MUSIC}"),
            engine.calls,
        )
        assertEquals(1, count("id=activity-media.next"))
        assertEquals(1, count("id=activity-media.previous"))
        assertFalse("a press on a button did not open the panel", hasTag(CompanionMusicPanelTag))
        assertTrue("no bridge action was sent for Your music", mediaActions.isEmpty())
    }

    @Test
    fun `a paused Your music says Paused and offers Play`() {
        mount(MusicControlState(yours = yoursPaused, otherAccess = true))
        assertTrue(hasPressable("Play"))
        assertTrue(saysAnything("Paused"))
    }

    @Test
    fun `Your music wins the tile over the access guidance, since the panel has a card`() {
        mount(MusicControlState(yours = yoursPlaying), media = CompanionMediaUiState.AccessRequired)
        assertTrue(saysAnything("Dreams of the Hedgehog"))
        assertFalse(saysAnything("Connect your media"))
        tapTile()
        assertTrue(hasTag(CompanionMusicPanelTag))
    }

    // ── another player, both, nothing ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `another player alone with the bridge's track keeps the bridge's tile, its seek lane and its transport`() {
        mount(MusicControlState(other = engineOf(bridgeTrack()), otherAccess = true), media = CompanionMediaUiState.Active(bridgeTrack()))
        assertTrue("the bridge's title", saysAnything("Bridge Title"))
        assertTrue("the seek lane is the bridge's", saysAnything("Seek playback"))
        tap("Pause")
        assertEquals("the bridge's transport, not the engine's", listOf(CompanionMediaAction.PLAY_PAUSE), mediaActions)
        assertTrue(engine.calls.isEmpty())
        tapTile()
        assertTrue("and it opens the panel, which has the card", hasTag(CompanionMusicPanelTag))
    }

    @Test
    fun `during a player switch the tile and the panel agree on the engine's player until the bridge catches up`() {
        // Spotify -> YouTube: the bridge has already moved to YouTube while the engine's top card is still Spotify.
        val youtube = bridgeTrack(title = "Video Song", appLabel = "YouTube Music")
        mount(MusicControlState(other = spotify(playing = true), otherAccess = true), media = CompanionMediaUiState.Active(youtube))
        assertTrue("the tile shows the engine's player, Spotify: ${words()}", saysAnything("Spotify Song"))
        assertFalse("not the bridge's YouTube track the panel does not have", saysAnything("Video Song"))
        assertFalse("the bridge's seek lane is not on a tile that is not the bridge's track", saysAnything("Seek playback"))
        tapTile()
        assertTrue(hasTag(CompanionMusicPanelTag))
        assertTrue("the opened panel shows Spotify too: ${words()}", saysAnything("Spotify Song"))
        assertFalse("and never YouTube", saysAnything("Video Song"))
    }

    @Test
    fun `once the engine moves to the bridge's player the bridge's tile returns`() {
        val youtube = bridgeTrack(title = "Video Song", appLabel = "YouTube Music")
        mount(MusicControlState(other = spotify(playing = true), otherAccess = true), media = CompanionMediaUiState.Active(youtube))
        assertTrue(saysAnything("Spotify Song"))
        engine.state.value = MusicControlState(other = engineOf(youtube), otherAccess = true)
        settle()
        assertTrue("the bridge's tile is back: ${words()}", saysAnything("Video Song") && saysAnything("Seek playback"))
        assertFalse(saysAnything("Spotify Song"))
        tapTile()
        assertTrue(hasTag(CompanionMusicPanelTag))
        assertTrue("and the panel agrees: ${words()}", saysAnything("Video Song"))
    }

    @Test
    fun `the same package is the same player whatever the track or the label, a different package is not`() {
        val first = bridgeTrack(title = "First Song")
        assertTrue(bridgeTrackIsEnginePlayer(first, engineOf(first)))
        assertTrue("a new track by the same package is the same player", bridgeTrackIsEnginePlayer(bridgeTrack(title = "Second Song"), engineOf(first)))
        assertTrue("a relabelled session of the same package is the same player", bridgeTrackIsEnginePlayer(bridgeTrack(appLabel = "Other Label"), engineOf(first)))
        assertFalse("a different package is not", bridgeTrackIsEnginePlayer(bridgeTrack(packageName = "example.other"), engineOf(first)))
        assertFalse(
            "two packages with the same label are not one player",
            bridgeTrackIsEnginePlayer(bridgeTrack(packageName = "example.other", appLabel = "Player"), engineOf(bridgeTrack(appLabel = "Player"))),
        )
        assertFalse(
            "two packages with a blank label are not one player",
            bridgeTrackIsEnginePlayer(bridgeTrack(packageName = "example.other", appLabel = ""), engineOf(bridgeTrack(appLabel = ""))),
        )
    }

    @Test
    fun `an engine snapshot with no package never matches`() {
        val track = bridgeTrack()
        assertFalse(bridgeTrackIsEnginePlayer(track, engineOf(track).copy(packageName = null)))
        assertNull("a blank package is carried as none", engineOf(bridgeTrack(packageName = " ")).packageName)
    }

    @Test
    fun `two packages with the same label are not one player, so the engine's tile and the panel agree`() {
        // Same label "Player" on both sides, different packages: the bridge is a different app's session from the engine's card.
        val bridge = bridgeTrack(title = "Other App Song", packageName = "example.other")
        mount(MusicControlState(other = engineOf(bridgeTrack(title = "Engine Song")), otherAccess = true), media = CompanionMediaUiState.Active(bridge))
        assertTrue("the tile is the engine's card: ${words()}", saysAnything("Engine Song"))
        assertFalse(saysAnything("Other App Song"))
        assertFalse(saysAnything("Seek playback"))
        tapTile()
        assertTrue("and the panel shows the same card: ${words()}", hasTag(CompanionMusicPanelTag) && saysAnything("Engine Song") && !saysAnything("Other App Song"))
    }


    @Test
    fun `a track skip within one app keeps the bridge's tile and its seek lane on every frame while the engine catches up`() {
        val first = bridgeTrack(title = "First Song")
        mount(MusicControlState(other = engineOf(first), otherAccess = true), media = CompanionMediaUiState.Active(first))
        assertTrue(saysAnything("First Song") && saysAnything("Seek playback"))
        // The bridge skips; the engine still holds the previous track.
        mediaState = CompanionMediaUiState.Active(bridgeTrack(title = "Second Song"))
        repeat(40) { frame ->
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(16L)
            compose.waitForIdle()
            assertTrue("the bridge's seek lane is on screen at frame $frame of the skip", saysAnything("Seek playback"))
        }
        assertTrue("the tile names the new track: ${words()}", saysAnything("Second Song"))
        // The engine follows; nothing changes under the finger.
        engine.state.value = MusicControlState(other = engineOf(bridgeTrack(title = "Second Song")), otherAccess = true)
        settle()
        assertTrue(saysAnything("Second Song") && saysAnything("Seek playback"))
        tapTile()
        assertTrue("and the panel opens on the new track: ${words()}", hasTag(CompanionMusicPanelTag) && saysAnything("Second Song"))
    }


    @Test
    fun `another player the bridge has not delivered still gets the tile, built from the panel's card`() {
        mount(MusicControlState(other = spotify(playing = true), otherAccess = true))
        assertTrue(saysAnything("Spotify Song"))
        assertTrue(saysAnything("Some Artist"))
        assertFalse("no seek lane without a bridge track", saysAnything("Seek playback"))
        tap("Pause")
        assertEquals(listOf("playPause:${MusicSource.OTHER_PLAYER}"), engine.calls)
    }

    @Test
    fun `both sources show the playing one on the tile and the panel shows both`() {
        mount(MusicControlState(yours = yoursPlaying, other = spotify(playing = false), otherAccess = true), media = CompanionMediaUiState.Active(bridgeTrack()))
        assertTrue("Your music is playing, so it is the tile", saysAnything("Dreams of the Hedgehog"))
        assertFalse("not the paused bridge track", saysAnything("Bridge Title"))
        tapTile()
        assertTrue(hasTag(CompanionMusicPanelTag))
        assertTrue("the panel's card is Your music", saysAnything("Dreams of the Hedgehog"))
        // The switch row lives under the player's Source tab (W3e).
        tap("Source tab")
        assertTrue("and the other player is its switch row", hasTag(MusicPanelSwitchRowTestTag) && saysAnything("Spotify"))
    }

    @Test
    fun `both sources with the other player playing show the bridge's tile`() {
        mount(MusicControlState(yours = yoursPaused, other = engineOf(bridgeTrack()), otherAccess = true), media = CompanionMediaUiState.Active(bridgeTrack()))
        assertTrue(saysAnything("Bridge Title"))
        assertFalse(saysAnything("Dreams of the Hedgehog"))
    }

    @Test
    fun `nothing playing and no card leaves Been a while where it was`() {
        mount(MusicControlState())
        assertFalse(hasTag(ActivityMediaArtTag))
        assertFalse(hasOpenPanel())
        assertTrue("the lower-left tile is still there", hasTag(ActivityTileLowerLeftTag))
        assertEquals(0, receipts().count { it.startsWith("ev=surface.enter surface=activity-media") })
    }

    @Test
    fun `a bridge tile the panel has no card for is shown but does not open the panel`() {
        mount(MusicControlState(), media = CompanionMediaUiState.Active(bridgeTrack()))
        assertTrue(saysAnything("Bridge Title"))
        tapTile()
        assertFalse(hasTag(CompanionMusicPanelTag))
        assertEquals(0, count("id=activity-media.open_panel"))
    }

    // ── one model ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a change in the engine updates the tile, and the open panel, and the card leaving returns the tile to Been a while`() {
        mount(MusicControlState(yours = yoursPlaying, otherAccess = true))
        engine.state.value = MusicControlState(yours = yoursPlaying.copy(title = "Second Song"), otherAccess = true)
        settle()
        assertTrue("the tile follows the engine: ${words()}", saysAnything("Second Song"))
        assertFalse(saysAnything("Dreams of the Hedgehog"))
        tapTile()
        assertTrue(hasTag(CompanionMusicPanelTag))
        assertTrue("the panel shows the same card", saysAnything("Second Song"))
        engine.state.value = MusicControlState(yours = yoursPlaying.copy(title = "Third Song"), otherAccess = true)
        settle()
        assertTrue("and the panel follows the same engine", saysAnything("Third Song"))
    }

    @Test
    fun `when the last card leaves the tile is Been a while again`() {
        mount(MusicControlState(yours = yoursPlaying, otherAccess = true))
        assertTrue(hasOpenPanel())
        engine.state.value = MusicControlState(otherAccess = true)
        settle()
        assertFalse(hasOpenPanel())
        assertFalse(hasTag(ActivityMediaArtTag))
    }

    // ── the art law ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `another player's art on the engine tile is drawn, nothing is drawn while it loads, and the glyph is for no art`() {
        mount(MusicControlState(other = spotify(playing = true, art = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)), otherAccess = true))
        assertTrue("the art", hasTag(MusicPanelArtImageTestTag))
        assertFalse(hasTag(MusicPanelArtFallbackTestTag))
    }

    @Test
    fun `art still loading draws neither the art nor the glyph`() {
        mount(MusicControlState(other = spotify(playing = true, pending = true), otherAccess = true))
        assertFalse(hasTag(MusicPanelArtImageTestTag))
        assertFalse("no fallback for wired art that has not arrived (R3, R8)", hasTag(MusicPanelArtFallbackTestTag))
    }

    // ── entry is the panel's card, and a disabled button is its own ────────────────────────────────────────────────────────────────

    @Test
    fun `a stopped external session with Your music off offers no entry to the panel`() {
        mount(MusicControlState(), media = CompanionMediaUiState.Active(bridgeTrack(state = CompanionMediaPlaybackState.STOPPED)))
        assertTrue("the bridge's honest Stopped tile is there", saysAnything("Bridge Title"))
        assertFalse("but it offers no entry", hasOpenPanel())
        tapTile()
        assertFalse("and a tap on it opens nothing", hasTag(CompanionMusicPanelTag))
        assertEquals(0, count("id=activity-media.open_panel"))
    }

    private fun tapDisabled(label: String) {
        compose.onAllNodes(hasContentDescription("$label unavailable"), useUnmergedTree = true)[0].performTouchInput { click() }
        settle()
    }

    @Test
    fun `a tap on a disabled transport button on the engine tile does nothing and does not open the panel`() {
        mount(MusicControlState(yours = yoursPlaying.copy(canPrevious = false), otherAccess = true))
        tapDisabled("Previous")
        assertFalse("the disabled button took its own tap", hasTag(CompanionMusicPanelTag))
        assertTrue("no action: ${engine.calls}", engine.calls.isEmpty())
        assertEquals(0, count("id=activity-media.open_panel"))
        assertEquals(0, count("id=activity-media.previous"))
    }

    @Test
    fun `a tap on a disabled transport button on the bridge's tile does nothing and does not open the panel`() {
        mount(MusicControlState(other = engineOf(bridgeTrack(canPrevious = false)), otherAccess = true), media = CompanionMediaUiState.Active(bridgeTrack(canPrevious = false)))
        tapDisabled("Previous")
        assertFalse(hasTag(CompanionMusicPanelTag))
        assertTrue(mediaActions.isEmpty())
        assertEquals(0, count("id=activity-media.open_panel"))
    }

    // ── the settle must not restyle the buttons ───────────────────────────────────────────────────────────────────────────────────

    /** The transport's semantics: label and whether it is a live pressable, "unavailable" being the disabled look. */
    private fun transportLooks(): List<String> = listOf("Previous", "Next").flatMap { label ->
        compose.onAllNodes(hasContentDescription(label) or hasContentDescription("$label unavailable"), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() + ":" + (SemanticsActions.OnClick in it.config) }
    } + compose.onAllNodes(
        hasContentDescription("Pause") or hasContentDescription("Pause unavailable") or hasContentDescription("Play") or hasContentDescription("Play unavailable"),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty() + ":" + (SemanticsActions.OnClick in it.config) }

    @Test
    fun `during the strip's settle the engine tile's buttons keep their enabled look and a tap does nothing, and after it a tap works`() {
        mount(MusicControlState(yours = yoursPlaying, otherAccess = true))
        val resting = transportLooks()
        assertTrue("the resting look is the lit one: $resting", resting.all { "unavailable" !in it && it.endsWith(":true") })
        navEnabled = false
        settle()
        assertEquals("the settle does not restyle the buttons", resting, transportLooks())
        compose.onAllNodes(hasContentDescription("Pause"), useUnmergedTree = true)[0].performClick()
        settle()
        assertTrue("a tap during the settle does nothing: ${engine.calls}", engine.calls.isEmpty())
        assertEquals("and writes no receipt", 0, count("id=activity-media.play_pause"))
        assertFalse("nor does it open the panel", hasTag(CompanionMusicPanelTag))
        navEnabled = true
        settle()
        tap("Pause")
        assertEquals("after the settle a tap works", listOf("playPause:${MusicSource.YOUR_MUSIC}"), engine.calls)
        assertEquals(1, count("id=activity-media.play_pause"))
    }

    @Test
    fun `during the strip's settle the bridge tile's buttons keep their enabled look and a tap does nothing, and after it a tap works`() {
        val track = bridgeTrack()
        mount(MusicControlState(other = engineOf(track), otherAccess = true), media = CompanionMediaUiState.Active(track))
        val resting = transportLooks()
        assertTrue("the resting look is the lit one: $resting", resting.all { "unavailable" !in it && it.endsWith(":true") })
        navEnabled = false
        settle()
        assertEquals("the settle does not restyle the buttons", resting, transportLooks())
        compose.onAllNodes(hasContentDescription("Next"), useUnmergedTree = true)[0].performClick()
        settle()
        assertTrue("a tap during the settle sent nothing: $mediaActions", mediaActions.isEmpty())
        navEnabled = true
        settle()
        tap("Next")
        assertEquals(listOf(CompanionMediaAction.NEXT), mediaActions)
    }

    @Test
    fun `a button the player disables still draws disabled during and after the settle`() {
        mount(MusicControlState(yours = yoursPlaying.copy(canPrevious = false), otherAccess = true))
        assertTrue(hasPressable("Previous unavailable") || compose.onAllNodes(hasContentDescription("Previous unavailable"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        navEnabled = false
        settle()
        assertTrue(compose.onAllNodes(hasContentDescription("Previous unavailable"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue("the others are still lit", compose.onAllNodes(hasContentDescription("Next"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        navEnabled = true
        settle()
        assertTrue(compose.onAllNodes(hasContentDescription("Previous unavailable"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ── the tile is the hub's stage in small: the same plates in the same order, Play on the tile's centre line ─────────────────────

    private data class Spot(val x: Float, val y: Float, val w: Float, val h: Float)

    private fun spotOf(node: androidx.compose.ui.semantics.SemanticsNode) =
        Spot(node.positionInRoot.x / density369, node.positionInRoot.y / density369, node.size.width / density369, node.size.height / density369)

    private fun pressableSpot(label: String) = spotOf(
        compose.onAllNodes(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick), useUnmergedTree = true).fetchSemanticsNodes().single(),
    )

    private fun assertTileTransport(where: String) {
        val tile = spotOf(compose.onNodeWithTag(ActivityTileLowerLeftTag, useUnmergedTree = true).fetchSemanticsNode())
        val previous = pressableSpot("Previous")
        val play = pressableSpot("Pause")
        val next = pressableSpot("Next")
        println("TILE-ZONES $where tile=$tile previous=$previous play=$play next=$next")
        assertEquals("Play's centre is the tile's centre at $where", tile.x + tile.w / 2f, play.x + play.w / 2f, 1f)
        assertEquals("Play is 52 dp at $where", 52f, play.w, 0.6f)
        assertEquals("Play is 52 dp tall at $where", 52f, play.h, 0.6f)
        assertEquals("a skip's target is 48 dp at $where", 48f, previous.w, 0.6f)
        assertEquals("a skip's target is 48 dp at $where", 48f, next.w, 0.6f)
        assertTrue("Previous, Play, Next in that order at $where", previous.x + previous.w <= play.x + 0.6f && play.x + play.w <= next.x + 0.6f)
        // The plates themselves: the skips' 40 dp inside their 48 dp targets, Play's 52 dp the whole of its own.
        val plates = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, MediaTransportPlateTag), useUnmergedTree = true)
            .fetchSemanticsNodes().map { spotOf(it) }.sortedBy { it.x }
        assertEquals("three plates at $where", 3, plates.size)
        assertEquals("the skip plate is 40 dp at $where", 40f, plates[0].w, 0.6f)
        assertEquals("the Play plate is 52 dp at $where", 52f, plates[1].w, 0.6f)
        assertEquals("the skip plate is 40 dp at $where", 40f, plates[2].w, 0.6f)
        assertEquals("a skip plate is centred in its target at $where", previous.x + previous.w / 2f, plates[0].x + plates[0].w / 2f, 0.6f)
    }

    @Test
    fun `the engine tile's transport is three metal plates with Play on the tile's centre line`() {
        mount(MusicControlState(yours = yoursPlaying, otherAccess = true))
        assertTileTransport("engine tile")
    }

    @Test
    fun `the bridge tile's transport is the same three plates with Play on the tile's centre line`() {
        val track = bridgeTrack()
        mount(MusicControlState(other = engineOf(track), otherAccess = true), media = CompanionMediaUiState.Active(track))
        assertTileTransport("bridge tile")
    }

    private companion object {
        const val DISPLAY = 4
    }
}
