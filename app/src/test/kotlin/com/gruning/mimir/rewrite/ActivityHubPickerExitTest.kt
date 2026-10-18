package com.gruning.mimir.rewrite

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Bottom achievements hub's pickers and keyboard leave instead of vanishing (audit B-87, B-88, B-90, RC-35). The Show picker, the console picker and the search keyboard were
 * composed only while open, so on the commit frame of a close their panel and their 0.52 scrim (each picker carried its own) left the screen while the page behind was still
 * blurred and unwinding: a scrim luminance step under a blurred page, a panel cut off, a keyboard that appeared and vanished in one frame.
 *
 * Now ONE scrim stands at page level on the page's glass; the picker's panel dissolves on the sheet clock ([MimirMotion.OverlayMs]); the glass holds the dissolve lead
 * ([MimirMotion.OverlayDissolveCoverMs], the 08-15 ruling: the modal dissolves fully, then the glass clears); and the keyboard is a sheet that slides and fades out. Here a
 * bare ground pixel at the page's corner reads the scrim, per stepped frame, so a luminance step shows. The glass progress is read from the hub's own probe. Blur is not drawn
 * by the JVM: the blur half is device-owed. Pixels are the JVM's.
 *
 * The class runs as API 30, the level at which the platform cannot blur ([com.gruning.mimir.rewrite.core.ui.MimirGlass.supportsBlur]): the page's glass wrapper otherwise records
 * a GraphicsLayer, which the software canvas the pixels are read from cannot draw ("Software rendering doesn't support drawRenderNode").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityHubPickerExitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun game(n: Int, title: String, console: String, awarded: Long) = CompanionActivityAchievementSnapshot(
        gameId = "g$n",
        account = "me",
        unlocked = 5,
        total = 40,
        gameTitle = title,
        checkedAtEpochMillis = 1L,
        rowsKnown = false,
        raGameId = 100 + n,
        consoleName = console,
        lastAwardedAtMillis = awarded,
    )

    private val games = listOf(
        game(1, "Sonic the Hedgehog 2", "Genesis/Mega Drive", now - 2 * day),
        game(2, "Super Metroid", "SNES", now - 40 * day),
        game(3, "Pokemon Red", "Game Boy", now - 5 * day),
    )

    private var glass: () -> Float = { 0f }

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme { Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).background(Color.White)) { hub() } }
            }
        }
        step(0L)
        step(1_000L)
    }

    @Composable
    private fun hub() {
        CompanionActivityAchievementHub(
            snapshots = games,
            unknownState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
            navigationEnabled = true,
            onBack = {},
            details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap()),
            requestDetail = {},
            filterHolder = ActivityAchievementFilterHolder(),
            nowMillis = { now },
            glassProbe = { glass = it },
        )
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun click(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        step(16L)
    }

    /** The grey of the page's bare top-left corner pixel: only the scrim (and the white ground) are there. */
    private fun corner(): Float = compose.activity.shootWindow().gray(2, 2)

    @Test
    fun `the scrim clears with the blur it darkens, the panel dissolves first, and the glass holds the dissolve lead`() {
        mount()
        val rest = corner()
        click(AchievementFilterShowTag)
        step(1_000L)
        assertTrue("the picker is open", exists(AchievementFilterShowListTag))
        val open = corner()
        assertTrue("the open scrim darkens the corner (rest=$rest open=$open)", rest - open > 40f)
        assertEquals("and the glass is whole", 1f, glass(), 0.001f)

        click(AchievementFilterShowScrimTag)
        val gray = ArrayList<Float>()
        val glassSeries = ArrayList<Float>()
        val panel = ArrayList<Boolean>()
        // The click already stepped one frame; record that one as t = 16 ms.
        gray += corner(); glassSeries += glass(); panel += exists(AchievementFilterShowListTag)
        var t = 16L
        while (t < 480L) {
            step(16L)
            t += 16L
            gray += corner(); glassSeries += glass(); panel += exists(AchievementFilterShowListTag)
        }
        println("PICKER-EXIT corner grey per 16 ms from the close: ${gray.joinToString { "%.0f".format(it) }}")
        println("PICKER-EXIT glass per 16 ms: ${glassSeries.joinToString { "%.2f".format(it) }}")
        println("PICKER-EXIT panel composed per 16 ms: ${panel.joinToString { if (it) "1" else "0" }}")

        // No luminance step on the commit frame: the scrim is still on the corner.
        assertTrue("the first frame after the close is still scrimmed: ${gray.first()} vs open $open", kotlin.math.abs(gray.first() - open) < 8f)
        // The glass holds the dissolve lead, then clears.
        val heldFrames = ((MimirMotion.OverlayDissolveCoverMs - 16L) / 16L).toInt()
        assertTrue("the glass holds whole for the dissolve lead: ${glassSeries.take(heldFrames)}", glassSeries.take(heldFrames).all { it > 0.99f })
        assertTrue("then clears to nothing: ${glassSeries.last()}", glassSeries.last() < 0.01f)
        assertTrue("the corner is clear again at the end: ${gray.last()} vs rest $rest", kotlin.math.abs(gray.last() - rest) < 4f)
        // The corner follows the glass: it is lighter only as the glass falls (no step ahead of it).
        assertTrue(
            "the scrim and the glass fall together: $gray",
            gray.indices.all { i -> gray[i] <= rest - (rest - open) * glassSeries[i] + 12f },
        )
        // The panel is composed through its dissolve and gone after it.
        assertTrue("the panel is still composed 100 ms after the close", panel[(100 / 16)])
        assertFalse("and gone after the sheet clock", panel[((MimirMotion.OverlayMs + 48L) / 16L).toInt()])
        // And what the panel has faded to by the time the glass starts to clear is nearly nothing (08-15: dissolve fully, then clear).
        val alphaAtClear = 1f - MimirMotion.Easing.transform(MimirMotion.OverlayDissolveCoverMs / MimirMotion.OverlayMs.toFloat())
        assertTrue("the panel has all but dissolved when the glass begins to clear: $alphaAtClear", alphaAtClear < 0.15f)
    }

    @Test
    fun `the search keyboard slides and fades out instead of vanishing in a frame`() {
        mount()
        click(AchievementFilterSearchTag)
        step(600L)
        assertTrue("the keyboard is up", exists(AchievementFilterKeyboardTag))
        val up = compose.onNodeWithTag(AchievementFilterKeyboardTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        // A tap on the Show chip closes the keyboard (the two are never open together; the chip opens its own list as it does).
        click(AchievementFilterShowTag)
        val composed = ArrayList<Boolean>()
        val tops = ArrayList<Float>()
        var t = 16L
        while (t < 400L) {
            val nodes = compose.onAllNodesWithTag(AchievementFilterKeyboardTag, useUnmergedTree = true).fetchSemanticsNodes()
            composed += nodes.isNotEmpty()
            tops += nodes.firstOrNull()?.boundsInRoot?.top ?: Float.NaN
            step(16L)
            t += 16L
        }
        println("KEYBOARD-EXIT composed per 16 ms: ${composed.joinToString { if (it) "1" else "0" }}")
        println("KEYBOARD-EXIT top per 16 ms: ${tops.joinToString { "%.0f".format(it) }}")
        assertTrue("composed on the frame after the close (not cut): $composed", composed.first())
        assertTrue("composed for most of the sheet clock: $composed", composed.take(((MimirMotion.OverlayMs - 40L) / 16L).toInt()).all { it })
        assertFalse("gone after it", composed.last())
        val moving = tops.filter { !it.isNaN() }
        assertTrue("it slides away from where it stood (top $up): $moving", moving.zipWithNext().any { (a, b) -> b > a + 0.5f })
    }
}
