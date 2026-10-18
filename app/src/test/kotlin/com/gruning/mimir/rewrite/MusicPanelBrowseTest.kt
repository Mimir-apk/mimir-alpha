package com.gruning.mimir.rewrite

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicCatalogTrack
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicLibraryView
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.audio.buildMusicTree
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Artist / Album browser inside the music panel : when the card offers it, where the ring goes, what A and B do in it, and that it follows the library
 * without moving the player. The browser's own pure rules are in [MusicBrowseModelTest]; this is the controller and the panel model around it.
 */
class MusicPanelBrowseTest {
    private fun track(id: String, artist: String?, album: String?, title: String = id, number: Int? = null) = MusicCatalogTrack(
        id = id,
        fileName = "$id.mp3",
        folder = "",
        title = title,
        artist = artist,
        album = album,
        trackNumber = number,
        durationMs = 200_000L,
    )

    private fun view(vararg tracks: MusicCatalogTrack) = MusicLibraryView(buildMusicTree(tracks.toList()), tagsRead = tracks.size, trackCount = tracks.size, loaded = true)

    private val library = view(
        track("a1", "Alpha", "First", "Sweep", 1),
        track("a2", "Alpha", "First", "Tone", 2),
        track("a3", "Alpha", "Second", "Noise", 1),
        track("b1", "Beta", "Only", "Single", 1),
    )

    private class FakeEngine(library: MusicLibraryView) : MusicPanelEngine {
        override val state = MutableStateFlow(
            MusicControlState(
                yours = YourMusicState(title = "Sweep", isPlaying = true, canPrevious = true, canNext = true, trackId = "a1"),
                otherAccess = true,
            ),
        )
        override val musicVolume = MutableStateFlow(0.4f)
        override val mediaVolume = MutableStateFlow(0.5f)
        override val cappedByMedia = MutableStateFlow(false)
        override val equalizer = MutableStateFlow(EqualizerSettings())
        override val library = MutableStateFlow(library)
        val calls = mutableListOf<String>()

        override fun playPause(source: MusicSource) { calls += "playPause:$source" }
        override fun next(source: MusicSource) { calls += "next:$source" }
        override fun previous(source: MusicSource) { calls += "previous:$source" }
        override fun setMusicVolume(fraction: Float) { musicVolume.value = fraction }
        override fun setMediaVolume(fraction: Float) { mediaVolume.value = fraction }
        override fun applyEqualizerPreset(id: EqualizerPresetId) = Unit
        override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean = false
        override fun playFromLibrary(trackId: String) {
            calls += "playFromLibrary:$trackId"
            // As the real library does: the pressed track is the pick.
            library.value = library.value.copy(pickedTrackId = trackId)
        }

        override fun playAll() {
            calls += "playAll"
            library.value = library.value.copy(pickedTrackId = null)
        }
    }

    private class FakeMedia : MediaVolumeGateway {
        override fun isAvailable() = true
        override fun isFixedVolume() = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? = null
    }

    private val engine = FakeEngine(library)
    private val authority = DefaultFocusAuthority()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var closed = 0
    private lateinit var controller: MusicPanelController

    @Before
    fun setUp() {
        controller = MusicPanelController(engine, authority, scope, FakeMedia(), anchor = null, onClose = { closed++ })
        var top = 0f
        for (control in MusicPanelControl.entries) {
            authority.register(musicPanelFocusKey(control), MusicPanelScope, FocusBounds(0f, top, 100f, top + 10f))
            top += 20f
        }
    }

    @After
    fun tearDown() {
        controller.dispose()
        scope.cancel()
    }

    private fun focused() = musicPanelControlFor(authority.state.value.focused)

    private fun press(action: ControllerAction) = controller.dispatch(action)

    private fun pressA() {
        controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 0) { press(ControllerAction.CONFIRM) }
        controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = false, repeatCount = 0) { true }
    }

    private fun pressB() {
        controller.onKey(KeyEvent.KEYCODE_BUTTON_B, down = true, repeatCount = 0) { press(ControllerAction.CANCEL) }
        controller.onKey(KeyEvent.KEYCODE_BUTTON_B, down = false, repeatCount = 0) { true }
    }

    /** What the card does when the browser opens: the ring moves to the list (the card's own effect, which the controller test has no composition for). */
    private fun openBrowser() {
        controller.requestFocus(MusicPanelControl.BROWSE)
        pressA()
        assertNotNull("the browser is open", controller.model.browse)
        controller.requestFocus(MusicPanelControl.BROWSE_LIST)
    }

    /** The browser open with the cursor on Alpha (the first artist): All music is the row above it. */
    private fun openBrowserAtAlpha() {
        openBrowser()
        press(ControllerAction.NAV_DOWN)
        assertEquals("alpha", controller.model.browse!!.selectedKey)
    }

    // ---- the chip ----

    @Test
    fun `Your music's card offers the Library chip when the library holds music`() {
        assertTrue(controller.model.top.canBrowse)
        assertNull(controller.model.browse)
    }

    @Test
    fun `no music to browse, a stopped player, or another player's card offers no chip`() {
        val empty = MusicPanelController(FakeEngine(MusicLibraryView()), DefaultFocusAuthority(), scope, FakeMedia(), anchor = null, onClose = {})
        assertFalse(empty.model.top.canBrowse)
        empty.dispose()

        val noLibraryAtAll = musicPanelModel(
            control = MusicControlState(yours = YourMusicState(title = "x", isPlaying = true)),
            stopped = false, musicVolume = 0.4f, mediaVolume = 0.5f, mediaAvailable = true, mediaFixed = false, cappedByMedia = false, capPushed = false,
        )
        assertFalse("a model with no library offers none", noLibraryAtAll.top.canBrowse)

        val stopped = musicPanelModel(
            control = MusicControlState(yours = YourMusicState(title = "x", isPlaying = true)),
            stopped = true, musicVolume = 0.4f, mediaVolume = 0.5f, mediaAvailable = true, mediaFixed = false, cappedByMedia = false, capPushed = false,
            library = library,
        )
        assertFalse(stopped.top.canBrowse)

        val other = musicPanelModel(
            control = MusicControlState(
                other = OtherPlayerSnapshot(title = "T", artist = null, appLabel = "Spotify", artwork = null, isPlaying = true, canPrevious = true, canNext = true),
                otherAccess = true,
            ),
            stopped = false, musicVolume = 0.4f, mediaVolume = 0.5f, mediaAvailable = true, mediaFixed = false, cappedByMedia = false, capPushed = false,
            library = library,
        )
        assertEquals(MusicPanelCard.OTHER, other.top.card)
        assertFalse(other.top.canBrowse)
    }

    @Test
    fun `the chip is a row of its own above the transport, on the heading, and up from Play lands on it`() {
        val rows = musicPanelFocusRows(controller.model)
        assertEquals(
            listOf(
                listOf(MusicPanelControl.BROWSE),
                listOf(MusicPanelControl.YOURS_PREVIOUS, MusicPanelControl.YOURS_PLAY_PAUSE, MusicPanelControl.YOURS_NEXT),
                listOf(MusicPanelControl.MEDIA_VOLUME),
                listOf(MusicPanelControl.MUSIC_VOLUME),
            ),
            rows,
        )
        assertEquals(MusicPanelControl.YOURS_PLAY_PAUSE, focused())
        press(ControllerAction.NAV_UP)
        assertEquals(MusicPanelControl.BROWSE, focused())
        press(ControllerAction.NAV_DOWN)
        assertEquals("down from the chip is Play again", MusicPanelControl.YOURS_PLAY_PAUSE, focused())
        press(ControllerAction.NAV_DOWN)
        assertEquals(MusicPanelControl.MEDIA_VOLUME, focused())
    }

    @Test
    fun `while the browser is open the list is the panel's one control, and it is where the ring starts`() {
        openBrowser()
        assertEquals(listOf(listOf(MusicPanelControl.BROWSE_LIST)), musicPanelFocusRows(controller.model))
        assertEquals(MusicPanelControl.BROWSE_LIST, musicPanelInitialControl(controller.model))
        assertEquals(MusicPanelControl.BROWSE_LIST, focused())
    }

    // ---- the controller in the browser ----

    @Test
    fun `A on the chip opens the browser at the artists with the cursor on the first row, All music`() {
        openBrowser()
        val ui = controller.model.browse!!
        assertEquals(MusicBrowseLevel.ARTISTS, ui.level)
        assertEquals(listOf("All music", "Alpha", "Beta"), ui.rows.map { it.title })
        assertEquals(MusicBrowseAllKey, ui.selectedKey)
    }

    @Test
    fun `up and down move the cursor a row at a time and stop at the ends`() {
        openBrowser()
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.NAV_DOWN)
        assertEquals("beta", controller.model.browse!!.selectedKey)
        press(ControllerAction.NAV_DOWN)
        assertEquals("no wrap", "beta", controller.model.browse!!.selectedKey)
        press(ControllerAction.NAV_UP)
        assertEquals("alpha", controller.model.browse!!.selectedKey)
        press(ControllerAction.NAV_UP)
        press(ControllerAction.NAV_UP)
        assertEquals("no wrap at the top either: All music is the first row", MusicBrowseAllKey, controller.model.browse!!.selectedKey)
        assertEquals("the ring stays on the list", MusicPanelControl.BROWSE_LIST, focused())
    }

    @Test
    fun `A opens an artist then an album, and on a track plays its album from there`() {
        openBrowserAtAlpha()
        pressA()
        assertEquals(MusicBrowseLevel.ALBUMS, controller.model.browse!!.level)
        assertEquals("Alpha", controller.model.browse!!.context)
        pressA()
        assertEquals(MusicBrowseLevel.TRACKS, controller.model.browse!!.level)
        assertEquals("the album that holds the track on air opens on it", "a1", controller.model.browse!!.selectedKey)
        press(ControllerAction.NAV_DOWN)
        pressA()
        assertEquals(listOf("playFromLibrary:a2"), engine.calls)
        assertEquals("the browser stays on the album", MusicBrowseLevel.TRACKS, controller.model.browse!!.level)
        assertEquals("a2", controller.model.browse!!.selectedKey)
    }

    @Test
    fun `a tap on a row presses it, whatever the cursor was on`() {
        openBrowser()
        controller.onBrowsePress("beta")
        assertEquals("Beta", controller.model.browse!!.context)
        controller.onBrowsePress("only")
        controller.onBrowsePress("b1")
        assertEquals(listOf("playFromLibrary:b1"), engine.calls)
    }

    @Test
    fun `B goes up one level at a time, from the top leaves the browser, and then closes the panel`() {
        openBrowserAtAlpha()
        pressA()
        pressA()
        assertEquals(MusicBrowseLevel.TRACKS, controller.model.browse!!.level)
        pressB()
        assertEquals(MusicBrowseLevel.ALBUMS, controller.model.browse!!.level)
        assertEquals("first", controller.model.browse!!.selectedKey)
        pressB()
        assertEquals(MusicBrowseLevel.ARTISTS, controller.model.browse!!.level)
        assertEquals("alpha", controller.model.browse!!.selectedKey)
        assertEquals(0, closed)
        pressB()
        assertNull("from the top B leaves the browser", controller.model.browse)
        assertEquals(0, closed)
        pressB()
        assertEquals("and the next B closes the panel", 1, closed)
    }

    @Test
    fun `the browser opens where it was left`() {
        openBrowserAtAlpha()
        pressA()
        pressA()
        controller.onBrowseExit()
        assertNull(controller.model.browse)
        controller.onBrowse()
        assertEquals(MusicBrowseLevel.TRACKS, controller.model.browse!!.level)
        assertEquals("First", controller.model.browse!!.context)
    }

    @Test
    fun `the Bottom's Back goes up a level and says whether it did`() {
        controller.onBrowse()
        controller.onBrowsePress("alpha")
        assertTrue(controller.onBrowseBack())
        assertEquals(MusicBrowseLevel.ARTISTS, controller.model.browse!!.level)
        assertFalse("from the top it leaves the browser", controller.onBrowseBack())
        assertNull(controller.model.browse)
        assertFalse("with none open it does nothing", controller.onBrowseBack())
    }

    // ---- All music, and the picked row (the policy, 10-09) ----

    @Test
    fun `A on All music clears the pick and plays everything, and the browser stays at the artists`() {
        openBrowser()
        pressA()
        assertEquals(listOf("playAll"), engine.calls)
        val ui = controller.model.browse!!
        assertEquals(MusicBrowseLevel.ARTISTS, ui.level)
        assertEquals(MusicBrowseAllKey, ui.selectedKey)
    }

    @Test
    fun `with no pick All music is the selected row`() {
        openBrowser()
        assertEquals(listOf("All music" to true, "Alpha" to false, "Beta" to false), controller.model.browse!!.rows.map { it.title to it.picked })
    }

    @Test
    fun `pressing a track makes its artist and album the selected rows, and All music lets go of the selection`() {
        openBrowserAtAlpha()
        pressA()
        pressA()
        press(ControllerAction.NAV_DOWN)
        pressA()
        assertEquals(listOf("playFromLibrary:a2"), engine.calls)
        assertEquals("the pick is the pressed track", "a2", engine.library.value.pickedTrackId)

        pressB()
        assertEquals("the album that is the queue", listOf("First" to true, "Second" to false), controller.model.browse!!.rows.map { it.title to it.picked })
        pressB()
        assertEquals("the artist that holds it", listOf("All music" to false, "Alpha" to true, "Beta" to false), controller.model.browse!!.rows.map { it.title to it.picked })
    }

    @Test
    fun `A on All music after a pick puts the selection back on All music`() {
        controller.onBrowse()
        controller.onBrowsePress("alpha")
        controller.onBrowsePress("first")
        controller.onBrowsePress("a2")
        assertEquals("a2", engine.library.value.pickedTrackId)
        assertTrue(controller.onBrowseBack())
        assertTrue(controller.onBrowseBack())
        controller.onBrowsePress(MusicBrowseAllKey)
        assertEquals(listOf("playFromLibrary:a2", "playAll"), engine.calls)
        assertNull(engine.library.value.pickedTrackId)
        assertEquals(listOf("All music" to true, "Alpha" to false, "Beta" to false), controller.model.browse!!.rows.map { it.title to it.picked })
    }

    @Test
    fun `the library dropping a pick under the browser moves the selection back to All music`() {
        controller.onBrowse()
        controller.onBrowsePress("alpha")
        controller.onBrowsePress("first")
        controller.onBrowsePress("a2")
        assertTrue(controller.onBrowseBack())
        assertTrue(controller.onBrowseBack())
        assertEquals("alpha", controller.model.browse!!.rows.single { it.picked }.key)
        // A rescan took the album away: the library has no pick any more.
        engine.library.value = view(track("b1", "Beta", "Only", "Single", 1))
        assertEquals(MusicBrowseAllKey, controller.model.browse!!.rows.single { it.picked }.key)
    }

    // ---- following the library and the player ----

    @Test
    fun `the library changing under the browser keeps the cursor on its row and pops a vanished group`() {
        openBrowser()
        controller.onBrowsePress("alpha")
        controller.onBrowsePress("second")
        assertEquals("a3", controller.model.browse!!.selectedKey)

        // Tags arrive: a new artist sorts in ahead of Alpha; the cursor and the level stay where they were.
        engine.library.value = view(
            track("z0", "Aardvark", "New", "Zero", 1),
            track("a1", "Alpha", "First", "Sweep", 1),
            track("a2", "Alpha", "First", "Tone", 2),
            track("a3", "Alpha", "Second", "Noise", 1),
            track("b1", "Beta", "Only", "Single", 1),
        )
        assertEquals(MusicBrowseLevel.TRACKS, controller.model.browse!!.level)
        assertEquals("a3", controller.model.browse!!.selectedKey)

        // A rescan removes the album: the browser goes back up to what still exists.
        engine.library.value = view(track("a1", "Alpha", "First", "Sweep", 1), track("b1", "Beta", "Only", "Single", 1))
        assertEquals(MusicBrowseLevel.ALBUMS, controller.model.browse!!.level)
        assertEquals("Alpha", controller.model.browse!!.context)

        // The folder is forgotten: nothing to browse, and the browser closes.
        engine.library.value = MusicLibraryView(loaded = true)
        assertNull(controller.model.browse)
        assertFalse(controller.model.top.canBrowse)
    }

    @Test
    fun `the other player taking the card closes the browser`() {
        val other = OtherPlayerSnapshot(title = "T", artist = null, appLabel = "Spotify", artwork = null, isPlaying = false, canPrevious = true, canNext = true)
        val bothEngine = FakeEngine(library).also {
            it.state.value = MusicControlState(yours = YourMusicState(title = "Sweep", isPlaying = true, trackId = "a1"), other = other, otherAccess = true)
        }
        val both = MusicPanelController(bothEngine, DefaultFocusAuthority(), scope, FakeMedia(), anchor = null, onClose = {})
        try {
            both.onBrowse()
            assertNotNull(both.model.browse)
            // Spotify starts and Your music pauses: the full card follows the player that is playing.
            bothEngine.state.value = MusicControlState(
                yours = YourMusicState(title = "Sweep", isPlaying = false, trackId = "a1"),
                other = other.copy(isPlaying = true),
                otherAccess = true,
            )
            assertEquals(MusicPanelCard.OTHER, both.model.top.card)
            assertNull(both.model.browse)
        } finally {
            both.dispose()
        }
    }

    @Test
    fun `a volume tick does not rebuild the browser's rows`() {
        openBrowser()
        val before = controller.model.browse
        engine.musicVolume.value = 0.7f
        assertSame("the same rows: nothing they are built from changed", before, controller.model.browse)
        engine.state.value = engine.state.value.copy(yours = engine.state.value.yours!!.copy(trackId = "a2"))
        assertTrue("the track on air moved, so the rows are built again", before !== controller.model.browse)
    }

    @Test
    fun `a press that is not in a browser does nothing`() {
        controller.onBrowsePress("alpha")
        controller.onBrowseExit()
        assertNull(controller.model.browse)
        assertEquals(emptyList<String>(), engine.calls)
    }
}
