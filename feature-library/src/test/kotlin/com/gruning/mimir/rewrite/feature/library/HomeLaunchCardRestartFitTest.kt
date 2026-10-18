package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the Home launch card of a game that is parked behind Home paints a fourth button, Restart, beside Resume. This renders the REAL
 * [HomeLaunchCard] and proves the button row still fits: every button is at least the 48 dp touch floor wide, one ring reach (14 dp, the sweep 10-09 gap) from its neighbour
 * (and as far below the first row when the row stacks), inside the card, in the order Resume, Restart, Open with, Achievements, with each
 * label on one line and not cut by an ellipsis, at font scales 1.0 and 1.3, at Thor Main's 832.5 x 468.3 dp, a narrower standard card, the
 * 565 dp compact square and a width that stacks the row. The 320 x 300 compact floor is measured and printed, not asserted. Pixel values
 * are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardRestartFitTest {

    @get:Rule
    val compose = createComposeRule()

    private var fontScale by mutableStateOf(1f)

    @After
    fun unpark() {
        HomeLaunchCardResume.set(null)
    }

    private val rowKeys = listOf(
        HomeLaunchCardPlayKey,
        HomeLaunchCardRestartKey,
        HomeLaunchCardOpenWithKey,
        HomeLaunchCardAchievementsKey,
    )

    private val cardState = HomeLaunchCardUiState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Super Mario 64",
            supportingLabel = "n64",
        ),
        platformLabel = "Nintendo 64",
        emulatorLabel = "RetroArch",
        lastPlayedLabel = "Yesterday",
    )

    /** What the buttons' labels laid out as, by their spoken text: the button text hides its own layout from the semantics tree. */
    private val layouts = mutableMapOf<String, TextLayoutResult>()

    private val cardTag = "restart-fit-card"

    private fun mount(profile: MimirViewportProfile, widthDp: Float, heightDp: Float, parked: Boolean) {
        HomeLaunchCardResume.set(if (parked) GameId("game-1") else null)
        layouts.clear()
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalMimirViewportProfile provides profile,
                LocalMimirTextLayoutObserver provides { spoken: String, layout: TextLayoutResult -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp).testTag(cardTag)) {
                        HomeLaunchCard(
                            state = cardState,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The button's bounds in the card's own coordinates (the card is wider than the test root, so the root's are offset), or null when it is not on the card. */
    private fun bounds(key: FocusKey): Rect? {
        val card = compose.onAllNodes(hasTestTag(cardTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            ?: return null
        val button = compose.onAllNodes(hasTestTag(homeLaunchCardActionTestTag(key)), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot ?: return null
        return button.translate(-card.left, -card.top)
    }

    private fun labelOf(key: FocusKey): String = when (key) {
        HomeLaunchCardPlayKey -> cardState.primaryActionLabel
        HomeLaunchCardRestartKey -> HomeLaunchCardResume.RestartLabel
        HomeLaunchCardOpenWithKey -> cardState.openWithActionLabel
        else -> "Achievements"
    }

    /** How a button's label laid out: its line count and whether it was cut. */
    private fun labelReport(key: FocusKey): String {
        val layout = layouts[labelOf(key)] ?: return "unobserved(${labelOf(key)})"
        val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        return "lines=${layout.lineCount} ellipsized=$ellipsized overflowWidth=${layout.didOverflowWidth}"
    }

    private fun sweep(profile: MimirViewportProfile, widthDp: Float, heightDp: Float, name: String, assertFit: Boolean) {
        mount(profile, widthDp, heightDp, parked = true)
        for (font in listOf(1.0f, 1.3f)) {
            fontScale = font
            compose.waitForIdle()
            val rects = rowKeys.map { key -> bounds(key) }
            val report = rowKeys.indices.joinToString { index ->
                val r = rects[index]
                "${rowKeys[index].value.substringAfterLast('/')}=" +
                    (r?.let { "[${it.left},${it.top} ${it.width}x${it.height}]" } ?: "absent")
            }
            println("RESTART-FIT $name font=$font: $report :: labels " + rowKeys.joinToString { key -> runCatching { labelReport(key) }.getOrDefault("none") })
            if (!assertFit) continue

            val placed = rects.mapIndexed { index, rect ->
                requireNotNull(rect) { "$name font=$font: ${rowKeys[index].value} is not on the card" }
            }
            placed.forEachIndexed { index, rect ->
                val tag = "$name font=$font ${rowKeys[index].value.substringAfterLast('/')}"
                assertTrue("$tag is at least the 48 dp touch floor wide: ${rect.width}", rect.width >= 48f - 0.5f)
                assertTrue("$tag starts inside the card: ${rect.left}", rect.left >= -0.5f)
                assertTrue("$tag ends inside the card: ${rect.right} of $widthDp", rect.right <= widthDp + 0.5f)
                val label = labelReport(rowKeys[index])
                // All four labels are asserted whole. Achievements was once exempt because it ellipsized at 1.3 in its 136 dp button ;
                // the button's label now steps down to fit, so it stands to the same rule as the others.
                assertTrue("$tag label is one uncut line: $label", label.startsWith("lines=1 ") && label.contains("ellipsized=false"))
            }
            // the earlier update: one label size on the whole card, stacked rows included. Every label was laid out at the same sp.
            val sizes = rowKeys.map { key ->
                requireNotNull(layouts[labelOf(key)]) { "$name font=$font label ${labelOf(key)} was not laid out" }.layoutInput.style.fontSize.value
            }
            assertEquals("$name font=$font every button on the card wears one label size, not ${sizes.distinct()}", 1, sizes.distinct().size)
            println("RESTART-FIT $name font=$font label sizes: " + sizes.joinToString())
            // Reading order and the one-ring-reach gaps: within a row, left to right; below it, the next row.
            for (index in 1 until placed.size) {
                val before = placed[index - 1]
                val here = placed[index]
                val sameRow = kotlin.math.abs(before.top - here.top) < 1f
                if (sameRow) {
                    assertTrue("$name font=$font slot $index follows slot ${index - 1}", here.left >= before.right)
                    assertEquals("$name font=$font gap before slot $index", HomeLaunchCardActionGap.value, here.left - before.right, 0.5f)
                } else {
                    assertTrue("$name font=$font slot $index is on the row below", here.top > before.top)
                    assertEquals("$name font=$font the rows are one ring reach apart", HomeLaunchCardActionGap.value, here.top - maxOf(placed[0].bottom, placed[1].bottom), 0.5f)
                }
            }
            println("RESTART-FIT $name font=$font fits")
        }
    }

    @Test
    fun `at Thor Main the four buttons fit at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f, "thor-main", assertFit = true)
    }

    @Test
    fun `on a narrower standard card the four buttons fit at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 640f, 400f), 640f, 400f, "standard-640", assertFit = true)
    }

    @Test
    fun `on a card too narrow for one row the buttons stack in two and still fit at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 520f, 400f), 520f, 400f, "standard-520", assertFit = true)
    }

    @Test
    fun `on the compact square the four buttons fit at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 565f, 565f), 565f, 565f, "compact-565", assertFit = true)
    }

    @Test
    fun `the compact floor is measured`() {
        sweep(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 320f, 300f), 320f, 300f, "compact-floor", assertFit = false)
    }

    @Test
    fun `a card whose game is not parked paints the three buttons it always had`() {
        mount(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f, parked = false)

        assertEquals(null, bounds(HomeLaunchCardRestartKey))
        assertTrue(bounds(HomeLaunchCardPlayKey) != null)
        assertTrue(bounds(HomeLaunchCardOpenWithKey) != null)
        assertTrue(bounds(HomeLaunchCardAchievementsKey) != null)
    }

    @Test
    fun `the button appears when the game is parked and goes when the lease lets go`() {
        mount(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f, parked = false)
        assertEquals(null, bounds(HomeLaunchCardRestartKey))

        HomeLaunchCardResume.set(GameId("game-1"))
        compose.waitForIdle()
        assertTrue("Restart appears beside Resume", bounds(HomeLaunchCardRestartKey) != null)

        HomeLaunchCardResume.set(null)
        compose.waitForIdle()
        assertEquals("and goes again", null, bounds(HomeLaunchCardRestartKey))
    }
}
