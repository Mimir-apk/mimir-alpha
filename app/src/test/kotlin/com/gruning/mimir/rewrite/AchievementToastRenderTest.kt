package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Achievement earned" toast as the Bottom draws it : at the Bottom's 537.7 x 468.3 dp, standing in the control-free band the page showing offers (never over a
 * control), about five seconds, never taking focus or a touch, several at once as one line. The layer is tested with bands handed to it, the host with bands pages report (and with
 * none: the header's centre). Where the real pages' bands are, and that the toast crosses none of their controls, is `CompanionBottomHeaderSlotRenderTest`. Pixel values are the
 * JVM's; the toast over a real page on the handheld is device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AchievementToastRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private val one = AchievementToast(key = 7, raGameId = 1_234, gameTitle = "Chrono Cross", count = 1, title = "First Blood", points = 10, badgePath = "/Badge/00002.png")
    private val three = one.copy(key = 8, count = 3, title = "Second Wind", points = 30)

    private var shown by mutableStateOf<AchievementToast?>(one)
    private val done = ArrayList<Long>()
    private var underTaps = 0

    @Before
    fun clear() {
        AchievementToasts.resetForTest()
        AchievementToastBands.resetForTest()
    }

    @After
    fun clearAfter() {
        AchievementToasts.resetForTest()
        AchievementToastBands.resetForTest()
    }

    /** A band in the host's pixels, from dp. */
    private fun band(leftDp: Float, topDp: Float, widthDp: Float, heightDp: Float, vertical: AchievementToastVertical = AchievementToastVertical.Center) =
        AchievementToastBand(Rect(leftDp * density369, topDp * density369, (leftDp + widthDp) * density369, (topDp + heightDp) * density369), vertical)

    private fun mountLayer(fontScale: Float, band: AchievementToastBand) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        // Something clickable under the whole screen: a touch that goes through the toast reaches it.
                        Box(Modifier.fillMaxSize().clickable { underTaps += 1 }.testTag("under"))
                        AchievementToastLayer(toast = shown, badgeSource = NoActivityBadgeArt, onDone = { done += it }, band = band)
                    }
                }
            }
        }
        advance(300L)
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rect(tag: String): Rect = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot

    private fun dp(px: Float) = px / density369

    private fun texts(): List<String> =
        compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().let { roots ->
            val out = ArrayList<String>()
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
                node.children.forEach(::walk)
            }
            roots.forEach(::walk)
            out
        }

    // ── the layer: it stands in the band it is given ────────────────────────────────────────────────────────────────────────────

    private fun assertInBand(fontScale: Float) {
        // A header's gap: 300 dp wide, 48 dp tall, off-centre, as a page would offer.
        val given = band(leftDp = 150f, topDp = 10f, widthDp = 300f, heightDp = 48f)
        mountLayer(fontScale, given)
        assertTrue("the toast is up", exists(AchievementToastTag))
        val toast = rect(AchievementToastTag)
        println("TOAST font=$fontScale ${dp(toast.left)},${dp(toast.top)} ${dp(toast.width)}x${dp(toast.height)} dp in band ${dp(given.rect.left)}..${dp(given.rect.right)} x ${dp(given.rect.top)}..${dp(given.rect.bottom)}")
        assertEquals("centred on the band's middle", dp(given.rect.center.x), dp(toast.center.x), 1f)
        assertEquals("and on its height", dp(given.rect.center.y), dp(toast.center.y), 1f)
        assertTrue("inside the band's width: ${dp(toast.left)}..${dp(toast.right)}", toast.left >= given.rect.left - 0.5f && toast.right <= given.rect.right + 0.5f)
        assertTrue("no wider than ${AchievementToastMaxWidth.value} dp: ${dp(toast.width)}", dp(toast.width) <= AchievementToastMaxWidth.value + 0.5f)
        assertTrue("a small plate, not a page: ${dp(toast.height)} dp", dp(toast.height) <= 96f)
        val badge = rect(AchievementToastBadgeTag)
        assertTrue(
            "the badge is inside the toast and ${AchievementToastBadgeSize.value} dp: ${dp(badge.width)}",
            badge.left >= toast.left && badge.right <= toast.right && kotlin.math.abs(dp(badge.width) - AchievementToastBadgeSize.value) < 0.6f,
        )
        assertTrue("Achievement earned" in texts())
        assertTrue("First Blood" in texts())
        assertTrue(" · 10 points" in texts())
    }

    @Test
    fun `the toast stands centred in the band it is given at font 1_0`() = assertInBand(1.0f)

    @Test
    fun `the toast stands centred in the band it is given at font 1_3`() = assertInBand(1.3f)

    @Test
    fun `a band that hangs the toast from its top puts the toast's top on the band's top`() {
        val given = band(leftDp = 100f, topDp = 70f, widthDp = 340f, heightDp = 20f, vertical = AchievementToastVertical.Top)
        mountLayer(1.0f, given)
        val toast = rect(AchievementToastTag)
        assertEquals("its top is the band's top", dp(given.rect.top), dp(toast.top), 1f)
        assertEquals("centred across the band", dp(given.rect.center.x), dp(toast.center.x), 1f)
    }

    @Test
    fun `a band narrower than the toast squeezes it to the band, so nothing stands past the band's edges`() {
        val given = band(leftDp = 120f, topDp = 10f, widthDp = 170f, heightDp = 48f)
        mountLayer(1.3f, given)
        val toast = rect(AchievementToastTag)
        println("TOAST narrow ${dp(toast.left)}..${dp(toast.right)} dp in ${dp(given.rect.left)}..${dp(given.rect.right)}")
        assertTrue("never wider than the band: ${dp(toast.width)} vs ${dp(given.rect.width)}", toast.width <= given.rect.width + 0.5f)
        assertTrue("and inside it", toast.left >= given.rect.left - 0.5f && toast.right <= given.rect.right + 0.5f)
        val badge = rect(AchievementToastBadgeTag)
        assertTrue("the badge still stands in the plate", badge.left >= toast.left && badge.right <= toast.right)
    }

    @Test
    fun `several unlocks are one toast with the count and the game in its headline`() {
        shown = three
        mountLayer(1.0f, band(100f, 10f, 340f, 48f))
        assertTrue("the headline: ${texts()}", "3 achievements earned · Chrono Cross" in texts())
        assertTrue("the total: ${texts()}", " · 30 points" in texts())
        assertFalse("not the single-unlock headline", "Achievement earned" in texts())
    }

    @Test
    fun `the toast goes after about five seconds and tells its owner which showing ran out`() {
        mountLayer(1.0f, band(100f, 10f, 340f, 48f)) // 0.3 s in
        advance(4_600L)
        assertTrue("still up at 4.9 s", exists(AchievementToastTag))
        assertTrue(done.isEmpty())
        advance(200L)
        assertEquals("handed back at 5.1 s", listOf(7L), done)
        shown = null
        advance(100L)
        assertTrue("the plate leaves on the overlay clock, so it is still fading 100 ms after the toast is cleared", exists(AchievementToastTag))
        advance(com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs.toLong())
        assertFalse("and gone once the fade has finished", exists(AchievementToastTag))
    }

    @Test
    fun `a newer toast replaces the one up and gets its own five seconds`() {
        mountLayer(1.0f, band(100f, 10f, 340f, 48f))
        advance(3_000L)
        shown = three
        advance(100L)
        assertTrue("3 achievements earned · Chrono Cross" in texts())
        advance(4_000L)
        assertTrue("the new one has run only 4 s: the old one's timer did not take it down", done.isEmpty())
        advance(1_100L)
        assertEquals(listOf(8L), done)
    }

    @Test
    fun `the toast takes no touch and no focus, and says itself once to a screen reader`() {
        mountLayer(1.0f, band(100f, 10f, 340f, 48f))
        val node = compose.onAllNodesWithTag(AchievementToastTag, useUnmergedTree = true).fetchSemanticsNodes().first()
        assertFalse("no click action on it", node.config.contains(SemanticsActions.OnClick))
        assertNull("not focusable: it never takes focus", node.config.getOrNull(SemanticsProperties.Focused))
        assertEquals(
            "one phrase for a screen reader",
            "Achievement earned, First Blood, 10 points, Chrono Cross",
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        )
        assertTrue("a polite live region", node.config.contains(SemanticsProperties.LiveRegion))
        // A touch on the toast reaches what is under it.
        val toast = rect(AchievementToastTag)
        compose.onNodeWithTag("under", useUnmergedTree = true).performTouchInput { click(Offset(toast.center.x, toast.center.y)) }
        advance(100L)
        assertEquals("the tap went through", 1, underTaps)
    }

    @Test
    fun `no toast draws nothing at all`() {
        shown = null
        mountLayer(1.0f, band(100f, 10f, 340f, 48f))
        assertFalse(exists(AchievementToastTag))
        assertNotEquals("the host is mounted", 0, compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `the process-wide toast stamps each showing, takes down only the showing it is told of, and a newer one stays`() {
        AchievementToasts.show(one)
        val first = AchievementToasts.current.value!!
        AchievementToasts.show(three)
        val second = AchievementToasts.current.value!!
        assertNotEquals("each showing has its own key", first.key, second.key)
        AchievementToasts.dismiss(first.key)
        assertEquals("the older showing running out leaves the newer one", second, AchievementToasts.current.value)
        AchievementToasts.dismiss(second.key)
        assertNull(AchievementToasts.current.value)
    }

    // ── the host: the band the page showing reports, else the header's centre ───────────────────────────────────────────────────

    private val flow = MutableStateFlow<AchievementToast?>(one)
    private var pageB by mutableStateOf(false)

    /** A page's band node: the real modifier the pages use, on a box at ([leftDp], [topDp]) of the given size. */
    @androidx.compose.runtime.Composable
    private fun PageBand(leftDp: Float, topDp: Float, widthDp: Float, heightDp: Float, vertical: AchievementToastVertical = AchievementToastVertical.Center) {
        Box(Modifier.offset(leftDp.dp, topDp.dp).size(widthDp.dp, heightDp.dp).achievementToastBand(vertical))
    }

    private fun mountHost(fontScale: Float, pages: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) {
                        pages()
                        AchievementToastHost(toasts = flow, onDone = { done += it }, badgeSource = NoActivityBadgeArt)
                    }
                }
            }
        }
        advance(300L)
        advance(300L)
    }

    @Test
    fun `with no page offering a band the toast stands at the header's centre, clear of the screen's bottom`() {
        mountHost(1.0f) { }
        assertTrue(exists(AchievementToastTag))
        val toast = rect(AchievementToastTag)
        println("TOAST fallback ${dp(toast.left)},${dp(toast.top)} ${dp(toast.width)}x${dp(toast.height)} dp")
        assertEquals("centred across the screen", hostWidthDp / 2f, dp(toast.center.x), 1f)
        assertTrue("at the top: ${dp(toast.top)}", dp(toast.top) in 0f..ActivityPageTopDp + 48f)
    }

    @Test
    fun `the toast stands in the band a page reports, and follows the newest page, and back when that page leaves`() {
        mountHost(1.0f) {
            PageBand(leftDp = 90f, topDp = 100f, widthDp = 300f, heightDp = 48f)
            if (pageB) PageBand(leftDp = 150f, topDp = 220f, widthDp = 260f, heightDp = 48f)
        }
        var toast = rect(AchievementToastTag)
        assertEquals("A's band: centred on x 240", 240f, dp(toast.center.x), 1.5f)
        assertEquals("and on y 124", 124f, dp(toast.center.y), 1.5f)

        pageB = true
        advance(300L)
        advance(300L)
        toast = rect(AchievementToastTag)
        assertEquals("B is the newest page: x 280", 280f, dp(toast.center.x), 1.5f)
        assertEquals("y 244", 244f, dp(toast.center.y), 1.5f)

        pageB = false
        advance(300L)
        advance(300L)
        toast = rect(AchievementToastTag)
        assertEquals("B left: A's band again", 240f, dp(toast.center.x), 1.5f)
        assertEquals(124f, dp(toast.center.y), 1.5f)
    }

    @Test
    fun `a page parked off the screen is not the page showing, so its band is not used`() {
        mountHost(1.0f) {
            PageBand(leftDp = hostWidthDp + 40f, topDp = 100f, widthDp = 300f, heightDp = 48f)
        }
        val toast = rect(AchievementToastTag)
        assertTrue("the toast is on the screen, at the header's centre: ${dp(toast.left)}..${dp(toast.right)}", toast.left >= 0f && toast.right <= 1240f + 0.5f)
        assertEquals("centred across the screen, the fallback", hostWidthDp / 2f, dp(toast.center.x), 1f)
    }
}
