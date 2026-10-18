package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScreen
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
import org.robolectric.shadows.ShadowToast

/**
 * the earlier update against the REAL Home shelf, in layout rather than pixels (Home cannot be rasterised on the JVM: its page handoff records
 * graphics layers a software canvas cannot draw). At the Thor's Main, 832 x 468 dp, at font 1.0 and 1.3, the real five-seat shelf is mounted
 * under Main's message host (the app's, placed as it places it on Home, with a flag for liveness), the press's messages are posted the way
 * the coordinator posts them, and the rail's band is measured against Home's laid-out chrome, read by its test tags:
 *  - ZERO intersection with the quick rail (the dock) and a gap of at least the host's own [MimirMainMessageRailDockGap] above it;
 *  - zero intersection with the navigation hint and the selected seat, and the band's reach into the shelf's bottom printed;
 *  - the copy whole at font 1.3 for the longest messages (the not-connected refusal, a long-name failure), the rune seated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameMusicToastOverHomeLayoutTest {

    private companion object {
        /** How far a three-line card may reach above the shelf's bottom edge at font 1.3, in dp: the bloom Home keeps there, with a little margin. */
        const val MayReachDp = 16f
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val observed = mutableMapOf<String, TextLayoutResult>()

    @Before
    fun setUp() {
        MimirDisplayMessages.resetForTest()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        MimirDisplayMessages.resetForTest()
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms - ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun tagged(tag: String): Rect? =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow

    private fun rows() = listOf("Chrono Trigger", "Super Metroid", "EarthBound", "F-Zero", "Secret of Mana").mapIndexed { index, title ->
        HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("snes/game-$index")),
            rank = index.toLong(),
            addedAtMillis = 0L,
            title = title,
        )
    }

    private fun measure(fontScale: Float, notice: GameMusicNotice, mayReachIntoShelfDp: Float = 0f) {
        val rows = rows()
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density369, fontScale),
                LocalMimirTextLayoutObserver provides { spoken, layout -> observed[spoken] = layout },
            ) {
                MimirTheme {
                    Box(Modifier.fillMaxSize()) {
                        HomeShelfScreen(
                            rows = rows,
                            authority = authority,
                            onLaunch = {},
                            onOpenLibrary = {},
                            onQuickAction = {},
                            surfaceLabel = "Home",
                            reducedMotion = true,
                        )
                        TestMainMessageHost(clearsDock = true)
                    }
                }
            }
        }
        advance(500L)
        showMimirMessage(compose.activity, notice.text, notice.messageClass, notice.supportingText, holdMs = notice.holdMs)
        advance(800L)

        val nodes = compose.onAllNodes(railNode(notice), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the message is on Main's rail over the real Home, once", 1, nodes.size)
        assertNull("it is Mimir's own message, not a system toast", ShadowToast.getTextOfLatestToast())
        val band = nodes.single().boundsInWindow
        val decor = compose.activity.window.decorView
        assertTrue(
            "inside the window: $band of ${decor.width}x${decor.height}",
            band.left >= 0f && band.top >= 0f && band.right <= decor.width && band.bottom <= decor.height,
        )

        // The dock: zero intersection, and the host's gap above it.
        val quickRail = tagged("home_shelf_quick_rail")
        assertNotNull("Home's quick rail is laid out", quickRail)
        assertFalse("the rail stands clear of Home's quick rail: $band vs $quickRail", band.overlaps(quickRail!!))
        val gapPx = quickRail.top - band.bottom
        val wantedGapPx = MimirMainMessageRailDockGap.value * density369
        assertTrue("the rail keeps the dock gap above the quick rail: $gapPx px, wanted $wantedGapPx", gapPx >= wantedGapPx - 1f)

        // The rest of Home's chrome.
        tagged("home_shelf_navigation_hint")?.let { assertFalse("clear of the navigation hint: $band vs $it", band.overlaps(it)) }
        val slot = tagged("home_shelf_selected_slot")
        slot?.let { assertFalse("clear of the selected seat: $band vs $it", band.overlaps(it)) }
        // The shelf's bottom edge: how far the band reaches above it (negative: stands below it). Only a larger font's extra lines can.
        val reachDp = slot?.let { (it.bottom - band.top) / density369 } ?: Float.NEGATIVE_INFINITY
        assertTrue("the band reaches $reachDp dp into the shelf's bottom, more than the $mayReachIntoShelfDp dp this message may", reachDp <= mayReachIntoShelfDp)

        compose.assertRailShowsEverything(notice, band, observed, density369, fontScale)
        println(
            "MUSIC264-LAYOUT font=$fontScale text=${notice.text.take(24)} window=${decor.width}x${decor.height} railBand=$band " +
                "quickRail=$quickRail gapToQuickRailDp=${gapPx / density369} reachIntoShelfBottomDp=$reachDp " +
                "selectedSlot=$slot homeHintToastLane=${tagged("home_shelf_hint_toast")}",
        )
    }

    private val finding get() = gameMusicGameNotice(GameMusicPress.Started, "Chrono Trigger")!!

    private val notConnected get() = gameMusicGameNotice(GameMusicPress.Refused(GameMusicBlock.NotConnected), "Chrono Trigger")!!

    private val longFailure
        get() = gameMusicGameNotice(
            GameMusicPress.Ended(GameMusicTally(done = 1, total = 1, failed = 1)),
            "The Legend of Zelda: A Link to the Past and Four Swords",
        )!!

    @Test
    fun `finding music over the real Home clears the quick rail at font 1_0`() = measure(1.0f, finding)

    @Test
    fun `finding music over the real Home clears the quick rail at font 1_3`() = measure(1.3f, finding)

    @Test
    fun `the not-connected refusal over the real Home clears the quick rail at font 1_0`() = measure(1.0f, notConnected)

    /**
     * The longest messages at the larger font: the card grows to its three lines rather than cutting any, and its top reaches into the shelf's bottom
     * bloom, which Home keeps clear above the dock (11.7 dp on this viewport). The measured reach is printed; [MayReachDp] is the ceiling.
     */
    @Test
    fun `the not-connected refusal over the real Home is whole and clears the quick rail at font 1_3`() = measure(1.3f, notConnected, mayReachIntoShelfDp = MayReachDp)

    @Test
    fun `a long-name failure over the real Home clears the quick rail at font 1_0`() = measure(1.0f, longFailure)

    @Test
    fun `a long-name failure over the real Home is whole and clears the quick rail at font 1_3`() = measure(1.3f, longFailure, mayReachIntoShelfDp = MayReachDp)
}
