package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
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
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast

/**
 * the earlier update as pixels: the answer to "Download music for this game" on Main's one message rail (the real rail in the real host
 * composable; [TestMainMessageHost] differs from the app's only in how liveness is asked), at the Thor's Main (1920 x 1080 px at
 * 369 dpi = 832 x 468 dp), at font 1.0 and 1.3. Drawn the way this repo rasterises (the view hierarchy into a software bitmap;
 * `captureToImage` waits on a redraw the paused looper never delivers). The PNGs land in `build/test-captures/music264-*.png` when that folder exists.
 *
 * The backdrop is a STAND-IN for Home, not the real shelf: Home paints through hand-recorded graphics layers (MimirPageHandoff), which
 * a software canvas cannot draw ("Software rendering doesn't support drawRenderNode"), so the real shelf cannot be rasterised on the
 * JVM. The stand-in is the theme's own ground with five tile seats and the quick rail's three coins at Home's geometry for this
 * viewport, so the rail is seen where it would be over them. Where the rail sits against the real Home's laid-out chrome is
 * [GameMusicToastOverHomeLayoutTest]'s measurement. Pixel values are the JVM's; a handheld's are device-owed.
 *
 * The class runs as API 30, the level at which the platform cannot blur: nothing in the rail needs it, and a software canvas cannot
 * draw the RenderNode layers a blur records.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameMusicToastRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val gameName = "Chrono Trigger"
    private val observed = mutableMapOf<String, TextLayoutResult>()

    @Before
    fun setUp() {
        MimirDisplayMessages.resetForTest()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        setAnimatorScale(1f)
        MimirDisplayMessages.resetForTest()
    }

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms - ms / 2L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun save(frame: Bitmap, name: String) {
        val dir = File("build/test-captures")
        if (!dir.isDirectory) return
        File(dir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home's ground, five seats and the quick rail's three coins, at the geometry Home solves for 832 x 468 dp (coins 64 dp, 35 dp up). */
    @Composable
    private fun HomeStandIn() {
        val palette = LocalMimirPalette.current
        Box(Modifier.fillMaxSize().background(palette.surfaceBase)) {
            Row(
                modifier = Modifier.align(Alignment.Center).padding(bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(5) { seat ->
                    Box(Modifier.size(if (seat == 2) 150.dp else 104.dp).clip(RoundedCornerShape(14.dp)).background(palette.tileFill))
                }
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = StandInCoinsBottomDp.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                repeat(3) { Box(Modifier.size(StandInCoinDp.dp).clip(CircleShape).background(palette.surfaceHigh)) }
            }
        }
    }

    /**
     * Mounts the stand-in under Main's host (as Home's, with the rail above the dock), asks for [notice] the way the coordinator does, and
     * saves the frame with the rail up. Asserts the rail is ONE live-region node wholly inside the window and clear of the stand-in's coins,
     * that every word of it is shown and its rune seated, that no system toast was made, and that pixels really changed under it.
     */
    private fun renderOverHome(fontScale: Float, notice: GameMusicNotice, png: String) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density369, fontScale),
                LocalMimirTextLayoutObserver provides { spoken, layout -> observed[spoken] = layout },
            ) {
                MimirTheme {
                    Box(Modifier.fillMaxSize()) {
                        HomeStandIn()
                        // Main's host, mounted after the screen and the overlays at the app host's root.
                        TestMainMessageHost()
                    }
                }
            }
        }
        advance(1_000L)
        val before = frame()
        assertEquals("nothing is on the rail before the press", 0, compose.onAllNodes(railNode(notice), useUnmergedTree = true).fetchSemanticsNodes().size)

        showMimirMessage(compose.activity, notice.text, notice.messageClass, notice.supportingText, holdMs = notice.holdMs)
        advance(1_000L)

        // The picture is kept before anything is asserted about it, so a failed check still leaves the frame to look at.
        val after = frame()
        save(after, png)
        val nodes = compose.onAllNodes(railNode(notice), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("the message is on Main's rail, once", 1, nodes.size)
        assertNull("it is Mimir's own message, not a system toast", ShadowToast.getTextOfLatestToast())

        val bounds = nodes.single().boundsInWindow
        assertTrue("inside the window on the left: $bounds", bounds.left >= 0f)
        assertTrue("inside the window on the right: $bounds", bounds.right <= after.width.toFloat())
        assertTrue("inside the window at the bottom: $bounds", bounds.bottom <= after.height.toFloat())
        assertTrue("the rail is a band, not a slab: $bounds", bounds.height < after.height / 3f)
        val coinsTop = after.height - (StandInCoinsBottomDp + StandInCoinDp) * density369
        assertTrue("the rail stands above the dock's coins (they begin at $coinsTop px): $bounds", bounds.bottom <= coinsTop)
        compose.assertRailShowsEverything(notice, bounds, observed, density369, fontScale)

        // Pixels changed inside the band: something was drawn there that was not on the screen a moment before.
        val x0 = bounds.left.toInt().coerceAtLeast(0)
        val x1 = bounds.right.toInt().coerceAtMost(after.width)
        val y0 = bounds.top.toInt().coerceAtLeast(0)
        val y1 = bounds.bottom.toInt().coerceAtMost(after.height)
        var sum = 0L
        var count = 0L
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 step 2) {
                val a = before.getPixel(x, y)
                val b = after.getPixel(x, y)
                sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                    abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                    abs((a and 0xFF) - (b and 0xFF))
                count += 3
            }
        }
        val meanChange = sum.toDouble() / count
        println("MUSIC264 font=$fontScale png=$png band=$bounds meanChange=$meanChange size=${after.width}x${after.height}")
        assertTrue("the band is drawn: mean channel change $meanChange", meanChange > 2.0)
    }

    private val finding get() = gameMusicGameNotice(GameMusicPress.Started, gameName)!!

    private val notConnected get() = gameMusicGameNotice(GameMusicPress.Refused(GameMusicBlock.NotConnected), gameName)!!

    private val longFailure
        get() = gameMusicGameNotice(
            GameMusicPress.Ended(GameMusicTally(done = 1, total = 1, failed = 1)),
            "The Legend of Zelda: A Link to the Past and Four Swords",
        )!!

    @Test
    fun `finding music over Home at font 1_0`() = renderOverHome(1.0f, finding, "music264-toast-1.0.png")

    @Test
    fun `finding music over Home at font 1_3`() = renderOverHome(1.3f, finding, "music264-toast-1.3.png")

    @Test
    fun `the not-connected refusal over Home at font 1_0`() = renderOverHome(1.0f, notConnected, "music264-notconnected-1.0.png")

    @Test
    fun `the not-connected refusal over Home at font 1_3`() = renderOverHome(1.3f, notConnected, "music264-notconnected-1.3.png")

    @Test
    fun `a long game name in the failure message at font 1_0`() = renderOverHome(1.0f, longFailure, "music264-failed-long-1.0.png")

    @Test
    fun `a long game name in the failure message at font 1_3`() = renderOverHome(1.3f, longFailure, "music264-failed-long-1.3.png")

    // ── How long the note stays: the real rail, the real host, the clock moved a note's hold at a time ─────────────────────────────

    /** Whether the Compose clock carries Robolectric's SystemClock (the rail times a card on the second and delays on the first). */
    private var clocksCoupled = false

    private fun mountRail() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    Box(Modifier.fillMaxSize()) {
                        HomeStandIn()
                        TestMainMessageHost()
                    }
                }
            }
        }
        val before = SystemClock.uptimeMillis()
        advance(1_000L)
        clocksCoupled = SystemClock.uptimeMillis() - before >= 900L
    }

    /** Both clocks forward by [ms], the system one first: the card's expiry is read from it the moment the Compose clock's delay ends. */
    private fun advanceTogether(ms: Long) {
        if (!clocksCoupled) ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        advance(ms)
    }

    private fun post(notice: GameMusicNotice) =
        showMimirMessage(compose.activity, notice.text, notice.messageClass, notice.supportingText, holdMs = notice.holdMs)

    private fun shown(notice: GameMusicNotice): Int = compose.onAllNodes(railNode(notice), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun railNodes(): Int = compose.onAllNodes(
        hasContentDescription("Guidance. ", substring = true) or hasContentDescription("Confirmation. ", substring = true) or
            hasContentDescription("Warning. ", substring = true),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    /** The note is up well inside its hold, past the old 2.4 s window, and gone shortly after the hold ends. */
    private fun assertStaysItsHold(notice: GameMusicNotice) {
        mountRail()
        post(notice)
        advanceTogether(500L)
        assertEquals("up at once", 1, shown(notice))
        advanceTogether(notice.holdMs - 1_100L)
        assertEquals("still up 600 ms before its ${notice.holdMs} ms hold ends (the old 2.4 s window ended long ago)", 1, shown(notice))
        advanceTogether(1_300L)
        assertEquals("and gone 700 ms after it", 0, shown(notice))
        assertEquals("with nothing left on the rail", 0, railNodes())
    }

    @Test
    fun `the finding note stays its 4 s, well past the old 2_4 s window`() = assertStaysItsHold(finding)

    @Test
    fun `a saved note stays its 6 s`() = assertStaysItsHold(gameMusicGameNotice(GameMusicPress.Ended(GameMusicTally(done = 1, total = 1, written = 1)), gameName)!!)

    @Test
    fun `the not-connected refusal stays its 7 s`() = assertStaysItsHold(notConnected)

    @Test
    fun `a result replaces the finding note in place on the one rail and keeps its own hold from its arrival`() {
        mountRail()
        val found = finding
        val saved = gameMusicGameNotice(GameMusicPress.Ended(GameMusicTally(done = 1, total = 1, written = 1)), gameName)!!
        post(found)
        advanceTogether(1_500L)
        assertEquals("the finding note is up", 1, shown(found))

        post(saved)
        advanceTogether(600L)
        assertEquals("the result took its place", 1, shown(saved))
        assertEquals("the finding note is not drawn under it", 0, shown(found))
        assertEquals("one rail, never two", 1, railNodes())

        advanceTogether(saved.holdMs - 1_200L)
        assertEquals("the result stays its 6 s from ITS arrival (the finding note's timer is not the rail's)", 1, shown(saved))
        advanceTogether(1_400L)
        assertEquals("and goes after it", 0, railNodes())
    }
}

private const val StandInCoinsBottomDp = 35
private const val StandInCoinDp = 64

/** The rail's one live-region node for [notice]: "<class>. <text>[. <supporting>]". */
internal fun railNode(notice: GameMusicNotice): SemanticsMatcher {
    val label = when (notice.messageClass) {
        MimirMessageClass.Guidance -> "Guidance"
        MimirMessageClass.Confirmation -> "Confirmation"
        MimirMessageClass.Warning -> "Warning"
        MimirMessageClass.Blocking -> "Action blocked"
    }
    return hasContentDescription(label + ". " + notice.text + (notice.supportingText?.let { ". $it" } ?: ""))
}

private fun runeFor(messageClass: MimirMessageClass): String = when (messageClass) {
    MimirMessageClass.Guidance -> "ᚨ"
    MimirMessageClass.Confirmation -> "ᛃ"
    MimirMessageClass.Warning -> "ᚦ"
    MimirMessageClass.Blocking -> "ᛉ"
}

/**
 * nothing on the rail is ever cut, and the rune is seated. [band] is the rail's own node. The copy's laid-out text did not overflow
 * (it is heard through [LocalMimirTextLayoutObserver], the one way to ask a real line whether it was cut) and its node, the supporting line's and the
 * rune's all stand inside the band, which is the card: a card that kept a fixed height with copy that wrapped would hold nodes below its own edge. The
 * rune stands at least the card's own edge padding, its 2 dp and the 6 dp seat inside its left end, so the pill's curve cannot reach it.
 */
internal fun SemanticsNodeInteractionsProvider.assertRailShowsEverything(
    notice: GameMusicNotice,
    band: Rect,
    observed: Map<String, TextLayoutResult>,
    density: Float,
    fontScale: Float,
) {
    val layout = observed[notice.text] ?: throw AssertionError("the copy's layout was never heard: ${observed.keys}")
    assertFalse("the copy was cut (visual overflow) at font $fontScale: ${notice.text}", layout.hasVisualOverflow)
    val textNodes = onAllNodes(hasContentDescription(notice.text), useUnmergedTree = true).fetchSemanticsNodes()
    assertTrue("the copy's own node is found", textNodes.isNotEmpty())
    val text = textNodes.first().boundsInWindow
    assertInside("the copy", text, band)
    val supporting = notice.supportingText?.let { onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow }
    supporting?.let { assertInside("the supporting line", it, band) }
    // The path is the one thing that never wraps : ONE line at this font, and the whole of it inside the width the card gives it. Asked of the
    // line's own final layout, heard through the observer (the rail's card reports it as it does the copy).
    notice.supportingText?.let { path ->
        val line = observed[path] ?: throw AssertionError("the supporting line's layout was never heard: ${observed.keys}")
        println(
            "MUSIC264-PATH font=$fontScale lines=${line.lineCount} lineEnd=${line.getLineEnd(0)} of ${path.length} lineRight=${line.getLineRight(0)} " +
                "width=${line.size.width} overflowWidth=${line.didOverflowWidth} overflowHeight=${line.didOverflowHeight} visual=${line.hasVisualOverflow}",
        )
        assertEquals("the supporting line is ONE line at font $fontScale: $path", 1, line.lineCount)
        assertEquals("all of the path is on that line at font $fontScale: $path", path.length, line.getLineEnd(0))
        assertFalse("the path is not cut at its right end at font $fontScale: $path", line.didOverflowWidth)
        assertTrue(
            "the line ends inside the width it was given at font $fontScale: ${line.getLineRight(0)} of ${line.size.width}",
            line.getLineRight(0) <= line.size.width + 1f,
        )
    }
    val rune = onAllNodes(hasText(runeFor(notice.messageClass)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow
    rune?.let {
        assertInside("the rune", it, band)
        val seated = (4f + 2f + 6f) * density
        assertTrue("the rune is seated inside the pill's curve: ${it.left - band.left} px in, wanted $seated", it.left - band.left >= seated - 1f)
    }
    println(
        "MUSIC264-FIT font=$fontScale band=$band lines=${layout.lineCount} textNode=$text supportingNode=$supporting runeNode=$rune " +
            "bandHeightDp=${band.height / density}",
    )
}

private fun assertInside(what: String, inner: Rect, band: Rect) {
    assertTrue(
        "$what stands inside the rail's band, nothing hangs outside it: $inner vs $band",
        inner.top >= band.top - 1f && inner.bottom <= band.bottom + 1f && inner.left >= band.left - 1f && inner.right <= band.right + 1f,
    )
}

/**
 * Main's message host as the app mounts it on Home ([MimirActivityMessageHost] with `clearsDock`): the same rail and the same placement
 * (the real [MimirMainMessageHost]), registered for Main's display. Only the window's answer to "is anyone looking" differs: here a plain
 * flag that is up, where the app asks the Activity's lifecycle (RESUMED), because the Robolectric Activity's lifecycle is not what
 * these pictures are about. The lifecycle path itself is pinned by MimirDisplayMessagesTest.
 */
@Composable
internal fun TestMainMessageHost(clearsDock: Boolean = true) {
    val liveness = remember { MimirHostLiveness.of(MutableStateFlow(true)) }
    MimirMainMessageHost(liveness = liveness, clearsDock = clearsDock)
}
