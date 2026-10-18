package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Music page (update: ten rows) and its Behaviour sub-page (the seven switches) on a real layout pass at font 1.0 and 2.0: each row is
 * there, in reading order, inside the pane's width, clear of its own value, and a tap on it reaches the same activation the pad's A press does.
 * The sub-page is the pane while [MusicBehaviourScope] is the active focus scope, so the tests open it the way the coordinator does, by pushing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w640dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicRowsRenderTest {
    @get:Rule
    val compose = createComposeRule()

    private val paneWidthDp = 560f
    private val activations = mutableListOf<String>()
    private val authority = DefaultFocusAuthority()

    /** The ten page rows by their title, with the row id a press of each must raise, in pane order. */
    private val pageRows = listOf(
        "Music" to MusicPlayerEnabledRowId,
        "Source" to MusicSourceRowId,
        "Order" to MusicOrderRowId,
        "Volume" to MusicVolumeRowId,
        "Equalizer" to MusicEqualizerRowId,
        "Behaviour" to MusicBehaviourRowId,
        "Music folder" to MusicFolderRowId,
        "Launch sound" to MusicLaunchSoundRowId,
        "Let other music play over games" to MusicKeepMusicInGamesRowId,
        "Download game music" to MusicGameDownloadRowId,
    )

    /** The seven switches of the Behaviour page by their title, in page order. */
    private val behaviourRows = listOf(
        "Song titles" to MusicSongTitlesRowId,
        "Fade between tracks" to MusicFadeRowId,
        "Music hours" to MusicHoursRowId,
        "Play outside Mimir" to MusicOutsideMimirRowId,
        "Pause when screen is off" to MusicScreenOffRowId,
        "Pause if headphones unplug" to MusicUnplugRowId,
        "Show other players" to MusicOtherPlayersRowId,
    )

    /** Rows that are gone: folded into their neighbours, or hidden until they ship. */
    private val goneRows = listOf("Now playing", "Output", "Rescan", "Launch sound volume", "Play on highlight")

    private fun model() = MusicReadModel(
        musicEnabled = true,
        nowPlaying = null,
        source = MusicSourceValue.MIMIR,
        customSourceAvailable = false,
        order = MusicOrderValue.SHUFFLE,
        volumePercent = 50,
        songTitlesEnabled = true,
        folder = null,
        launchSoundEnabled = true,
        launchSoundVolumePercent = 100,
        gameMusic = GameMusicDownloadReadModel.Unavailable,
        playOnHighlight = false,
    )

    private var fontScale by mutableStateOf(1f)

    private fun show(scale: Float, music: MusicReadModel?) {
        fontScale = scale
        activations.clear()
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MimirTheme {
                    Box(Modifier.width(paneWidthDp.dp).verticalScroll(rememberScrollState())) {
                        MusicPanel(authority = remember { authority }, onRowActivate = { activations += it }, music = music)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Opens the Behaviour page the way the coordinator does: DetailScope under it, the page's scope pushed over it. */
    private fun openBehaviour() {
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicBehaviourRowId))
            authority.pushScope(MusicBehaviourScope)
        }
        compose.waitForIdle()
    }

    /** Chrome text speaks its natural string as the content description (the visual is capitals), so that is what is matched. */
    private fun named(text: String) = hasContentDescription(text) or hasText(text)

    private fun node(text: String): SemanticsNodeInteraction = compose.onAllNodes(named(text), useUnmergedTree = true)[0]

    private fun exists(text: String): Boolean = compose.onAllNodes(named(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun top(text: String): Float = node(text).getUnclippedBoundsInRoot().top.value

    private fun assertRowsSound(rows: List<Pair<String, String>>, scale: Float) {
        rows.forEach { (title, _) ->
            assertTrue("$title is on the pane at font $scale", exists(title))
            val bounds = node(title).getUnclippedBoundsInRoot()
            assertTrue("$title has a size at font $scale", bounds.right.value > bounds.left.value && bounds.bottom.value > bounds.top.value)
            assertTrue("$title starts inside the pane at font $scale", bounds.left.value >= -TOLERANCE)
            assertTrue("$title ends inside the pane at font $scale: ${bounds.right.value}", bounds.right.value <= paneWidthDp + TOLERANCE)
        }
        val tops = rows.map { top(it.first) }
        assertEquals("the rows read down the pane in their order at font $scale", tops.sorted(), tops)
        assertEquals("no two rows share a line at font $scale", tops.size, tops.toSet().size)
    }

    private fun assertValueClear(title: String, value: String, scale: Float) {
        assertTrue("$title shows $value at font $scale", exists(value))
        val titleBounds = node(title).getUnclippedBoundsInRoot()
        val valueBounds = node(value).getUnclippedBoundsInRoot()
        assertTrue("$value ends inside the pane at font $scale", valueBounds.right.value <= paneWidthDp + TOLERANCE)
        assertTrue("$title stops before its value at font $scale", titleBounds.right.value <= valueBounds.left.value + TOLERANCE)
    }

    @Test
    fun `the page has exactly these ten rows, in this order, and registers exactly these ten focus stops`() {
        show(1f, model())
        assertRowsSound(pageRows, 1f)
        assertEquals(
            "the ten ids on the page, in the order the authority holds them",
            MusicDetailRowIds.map(::detailKey),
            authority.registeredKeysInScope(DetailScope),
        )
        assertEquals(pageRows.map { it.second }, MusicDetailRowIds)
        assertTrue("the Behaviour page's switches are not on the page", authority.registeredKeysInScope(MusicBehaviourScope).isEmpty())
        goneRows.forEach { assertFalse("$it is not on the page", exists(it)) }
        behaviourRows.forEach { (title, _) -> assertFalse("$title is on the Behaviour page, not here", exists(title)) }
    }

    @Test
    fun `the page's values sit clear of their titles at font 1_0 and 2_0`() {
        for (scale in listOf(1f, 2f)) {
            if (scale == 1f) show(scale, model()) else Snapshot.withMutableSnapshot { fontScale = scale }
            compose.waitForIdle()
            assertRowsSound(pageRows, scale)
            assertValueClear("Order", "Shuffle", scale)
            assertValueClear("Launch sound", "100%", scale)
            assertValueClear("Behaviour", "Open", scale)
        }
    }

    @Test
    fun `a tap on each page row raises the activation the pad's A press raises, at both fonts`() {
        for (scale in listOf(1f, 2f)) {
            if (scale != 1f) Snapshot.withMutableSnapshot { fontScale = scale }
            if (scale == 1f) show(scale, model())
            compose.waitForIdle()
            activations.clear()
            pageRows.forEach { (title, rowId) ->
                node(title).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals("a tap on $title at font $scale", rowId, activations.lastOrNull())
            }
            assertEquals("one activation per tap at font $scale", pageRows.map { it.second }, activations.toList())
        }
    }

    @Test
    fun `with the Behaviour scope active the pane is the seven switches, in order, at both fonts`() {
        show(1f, model())
        openBehaviour()
        for (scale in listOf(1f, 2f)) {
            if (scale != 1f) {
                Snapshot.withMutableSnapshot { fontScale = scale }
                compose.waitForIdle()
            }
            assertRowsSound(behaviourRows, scale)
            assertValueClear("Music hours", "All day", scale)
            assertValueClear("Song titles", "On", scale)
            listOf("Source", "Order", "Volume", "Equalizer", "Music folder", "Launch sound", "Download game music").forEach { title ->
                assertFalse("$title is on the page, not the Behaviour page (font $scale)", exists(title))
            }
        }
        assertEquals(
            "the seven switches register in their own scope, in page order",
            MusicBehaviourRowIds.map(::detailKey),
            authority.registeredKeysInScope(MusicBehaviourScope),
        )
        assertTrue("none of the ten page rows is left registered", authority.registeredKeysInScope(DetailScope).isEmpty())
        assertEquals("focus lands on the page's first switch", detailKey(MusicSongTitlesRowId), authority.state.value.focused)
        goneRows.forEach { assertFalse("$it is not on the Behaviour page", exists(it)) }
    }

    @Test
    fun `a tap on each switch raises its own activation`() {
        show(1f, model())
        openBehaviour()
        activations.clear()
        behaviourRows.forEach { (title, rowId) ->
            node(title).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("a tap on $title", rowId, activations.lastOrNull())
        }
        assertEquals(behaviourRows.map { it.second }, activations.toList())
    }

    @Test
    fun `B from the Behaviour page returns to the Music page with focus on the Behaviour row`() {
        show(1f, model())
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicBehaviourRowId))
        }
        compose.waitForIdle()
        assertEquals(detailKey(MusicBehaviourRowId), authority.state.value.focused)

        // The coordinator's entry: land on the first switch, then push.
        compose.runOnIdle {
            authority.rememberFocusForScope(MusicBehaviourScope, detailKey(MusicBehaviourRowIds.first()))
            authority.pushScope(MusicBehaviourScope)
        }
        compose.waitForIdle()
        assertTrue("the pane shows the switches", exists("Song titles"))
        assertFalse("and not the page", exists("Equalizer"))
        assertEquals(detailKey(MusicSongTitlesRowId), authority.state.value.focused)

        // Walk down a few switches first: the way back must not depend on where the walk ended.
        compose.runOnIdle {
            repeat(3) {
                dispatchToSettings(authority, ControllerAction.NAV_DOWN, onRailOpen = {}, onRowActivate = {}, onExit = {})
            }
        }
        assertEquals(detailKey(MusicOutsideMimirRowId), authority.state.value.focused)

        val handled = compose.runOnIdle {
            dispatchToSettings(authority, ControllerAction.CANCEL, onRailOpen = {}, onRowActivate = {}, onExit = { error("B on the sub-page must not leave Settings") })
        }
        assertTrue(handled)
        compose.waitForIdle()
        assertEquals("the Music page is back", DetailScope, authority.state.value.scope)
        assertTrue(exists("Equalizer"))
        assertFalse(exists("Song titles"))
        assertEquals("focus is on the Behaviour row", detailKey(MusicBehaviourRowId), authority.state.value.focused)
    }

    private fun assertLeavesBy(leave: ControllerAction) {
        show(1f, model())
        openBehaviour()
        compose.runOnIdle {
            assertTrue(dispatchToSettings(authority, ControllerAction.CONFIRM, onRailOpen = {}, onRowActivate = { activations += it }, onExit = {}))
        }
        assertEquals("A on the focused switch raises its activation", listOf(MusicSongTitlesRowId), activations.toList())
        compose.runOnIdle {
            assertTrue(dispatchToSettings(authority, leave, onRailOpen = {}, onRowActivate = {}, onExit = { error("must not leave Settings") }))
        }
        compose.waitForIdle()
        assertEquals("$leave returned to the page", DetailScope, authority.state.value.scope)
        assertEquals(detailKey(MusicBehaviourRowId), authority.state.value.focused)
    }

    @Test
    fun `Back leaves the Behaviour page for the Music page, and A on a switch activates it`() = assertLeavesBy(ControllerAction.SYSTEM_BACK)

    @Test
    fun `LEFT leaves the Behaviour page for the Music page`() = assertLeavesBy(ControllerAction.NAV_LEFT)

    // ── the fix round: Y forgets the folder, after a confirmation ─────────────────────────────────────

    private fun modelWithFolder() = model().copy(
        folder = MusicFolderReadModel(friendlyPath = "SD card › Music", trackCount = 1234, lastScanLabel = null, scanning = false, error = null),
    )

    /** Focus the Music folder row on the page, then press Y through the dispatch the way the pad does; [claims] stands for the coordinator's answer. */
    private fun pressYOnFolderRow(claims: Boolean): Boolean {
        compose.runOnIdle {
            if (authority.state.value.scope != DetailScope) authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicFolderRowId))
        }
        compose.waitForIdle()
        return compose.runOnIdle {
            dispatchToSettings(
                authority,
                ControllerAction.TERTIARY,
                onRailOpen = {},
                onRowActivate = {},
                onExit = {},
                onRowTertiary = { rowId ->
                    activations += "Y:$rowId"
                    claims
                },
            )
        }
    }

    @Test
    fun `Y reaches the row's handler with the focused row's id, and is claimed only when the row claims it`() {
        show(1f, modelWithFolder())
        assertTrue("the Music folder row claims Y", pressYOnFolderRow(claims = true))
        assertEquals(listOf("Y:$MusicFolderRowId"), activations.toList())
        assertFalse("a row that does not claim Y leaves it unrouted", pressYOnFolderRow(claims = false))
    }

    @Test
    fun `Y on a Music sub-page is not routed`() {
        show(1f, modelWithFolder())
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicFolderRowId))
            authority.rememberFocusForScope(MusicFolderForgetScope, detailKey(MusicFolderForgetCancelRowId))
            authority.pushScope(MusicFolderForgetScope)
        }
        compose.waitForIdle()
        val handled = compose.runOnIdle {
            dispatchToSettings(authority, ControllerAction.TERTIARY, onRailOpen = {}, onRowActivate = {}, onExit = {}, onRowTertiary = { error("Y must not route on the confirmation") })
        }
        assertFalse(handled)
    }

    private fun openForgetConfirmation() {
        compose.runOnIdle {
            authority.pushScope(DetailScope)
            authority.requestFocus(detailKey(MusicFolderRowId))
            authority.rememberFocusForScope(MusicFolderForgetScope, detailKey(MusicFolderForgetCancelRowId))
            authority.pushScope(MusicFolderForgetScope)
        }
        compose.waitForIdle()
    }

    @Test
    fun `the Forget folder confirmation takes the pane, Cancel first, and names the folder and that no file is touched`() {
        show(1f, modelWithFolder())
        openForgetConfirmation()
        assertTrue(exists(MusicFolderForgetTitle))
        assertTrue("the folder is named", exists("SD card › Music"))
        assertTrue("and what stays untouched", exists(MusicFolderForgetExplainerCopy))
        assertRowsSound(listOf("Cancel" to MusicFolderForgetCancelRowId, "Forget folder" to MusicFolderForgetConfirmRowId), 1f)
        assertEquals(MusicFolderForgetRowIds.map(::detailKey), authority.registeredKeysInScope(MusicFolderForgetScope))
        assertEquals("Cancel holds focus first", detailKey(MusicFolderForgetCancelRowId), authority.state.value.focused)
        assertTrue("none of the page rows is left registered", authority.registeredKeysInScope(DetailScope).isEmpty())
        listOf("Source", "Equalizer", "Launch sound", "Behaviour").forEach { assertFalse("$it is on the page, not the confirmation", exists(it)) }
        // A press on each row reaches its own activation.
        listOf("Cancel" to MusicFolderForgetCancelRowId, "Forget folder" to MusicFolderForgetConfirmRowId).forEach { (title, rowId) ->
            node(title).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(rowId, activations.lastOrNull())
        }
    }

    @Test
    fun `B from the Forget folder confirmation returns to the Music page with focus on the Music folder row`() {
        show(1f, modelWithFolder())
        openForgetConfirmation()
        // The way the pad walks it: DOWN to the destructive row first, and the way back must not depend on where the walk ended.
        compose.runOnIdle { dispatchToSettings(authority, ControllerAction.NAV_DOWN, onRailOpen = {}, onRowActivate = {}, onExit = {}) }
        assertEquals(detailKey(MusicFolderForgetConfirmRowId), authority.state.value.focused)
        val handled = compose.runOnIdle {
            dispatchToSettings(authority, ControllerAction.CANCEL, onRailOpen = {}, onRowActivate = {}, onExit = { error("B on the confirmation must not leave Settings") })
        }
        assertTrue(handled)
        compose.waitForIdle()
        assertEquals(DetailScope, authority.state.value.scope)
        assertTrue("the page is back", exists("Equalizer"))
        assertFalse(exists(MusicFolderForgetTitle))
        assertEquals("focus is back on the Music folder row", detailKey(MusicFolderRowId), authority.state.value.focused)
    }

    @Test
    fun `before the app has said anything the rows read Loading and still render`() {
        show(1f, null)
        pageRows.forEach { (title, _) -> assertTrue("$title renders while loading", exists(title)) }
        assertTrue(compose.onAllNodes(named("Loading…"), useUnmergedTree = true).fetchSemanticsNodes().size >= 6)
        openBehaviour()
        behaviourRows.forEach { (title, _) -> assertTrue("$title renders while loading", exists(title)) }
        assertNull("nothing of the page is registered under the sub-page", authority.registeredKeysInScope(DetailScope).firstOrNull())
    }

    private companion object {
        const val TOLERANCE = 1.5f
    }
}
