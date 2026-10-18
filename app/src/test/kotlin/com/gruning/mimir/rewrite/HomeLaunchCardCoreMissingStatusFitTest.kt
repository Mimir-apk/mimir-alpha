package com.gruning.mimir.rewrite

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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardAchievementsKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.gameLaunchStatusMessage
import com.gruning.mimir.rewrite.feature.library.homeLaunchCardActionTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Home launch card's status band is a fixed two lines of 9sp type that ends in an ellipsis, so a sentence longer than
 * the band cuts the half that tells the player what to do (Fable review of the RetroArch core check, P2). The sentences are
 * short and lead with the action; this renders the REAL [HomeLaunchCard] with the longest catalog labels
 * (`RetroArchCoreCheckTest` pins which they are) and proves the band shows them whole -- no ellipsis, no overflow, at most
 * two lines -- at Thor Main's 832.5 x 468.3 dp, a narrower standard card and the 565 dp compact square, at font scales 1.0 and 1.3.
 * The 320 x 300 compact floor  cannot hold a core-missing sentence in two lines at font 1.3, so there the minimal card's band may take up to four: it is asserted whole (no
 * ellipsis, no overflow, at most four lines) and inside the card, ending above the buttons, at fonts 1.0 and 1.3. Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardCoreMissingStatusFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val cardTag = "status-fit-card"

    private var statusLine by mutableStateOf<String?>(null)
    private var fontScale by mutableStateOf(1f)

    private fun sentence(core: String, installed: String? = null): String = gameLaunchStatusMessage(
        GameLaunchUiState.DispatchFailed(LaunchDispatchFailure.RETROARCH_CORE_MISSING, core, installed),
    ) { "Nintendo 64" }!!

    private val longestNeeds = sentence("Mupen64Plus-Next GLES3")
    private val longestPair = sentence("Mupen64Plus-Next GLES2", installed = "Mupen64Plus-Next GLES3")
    private val widestOther = sentence("Genesis Plus GX Wide")

    private fun mount(profile: MimirViewportProfile, widthDp: Float, heightDp: Float) {
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalMimirViewportProfile provides profile,
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp).testTag(cardTag)) {
                        HomeLaunchCard(
                            state = HomeLaunchCardUiState(
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
                                runtimeStatusLine = statusLine,
                            ),
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

    /** What the band did with [line] at [font]: its line count, and whether it overflowed or ended in an ellipsis. */
    private fun measure(line: String, font: Float): String {
        fontScale = font
        statusLine = line
        compose.waitForIdle()
        val node = compose.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        val out = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        val layout = out.first()
        val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        return "lines=${layout.lineCount} ellipsized=$ellipsized overflowHeight=${layout.didOverflowHeight} " +
            "width=${node.boundsInRoot.width}"
    }

    private fun fits(report: String, maxLines: Int) =
        (1..maxLines).any { report.startsWith("lines=$it ") } &&
            report.contains("ellipsized=false") && report.contains("overflowHeight=false")

    /** A node's whole box (`boundsInRoot` is clipped by its ancestors, which would hide an overflow). */
    private fun SemanticsNode.full(): Rect =
        Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)

    /** Where the status band stood against the card and the buttons: empty when it is inside the card and ends above the first button. */
    private fun bandProblems(line: String): List<String> {
        val band = compose.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }.full()
        val card = compose.onAllNodes(hasTestTag(cardTag), useUnmergedTree = true).fetchSemanticsNodes().first().full()
        val problems = ArrayList<String>()
        if (band.left < card.left - 0.5f || band.right > card.right + 0.5f) {
            problems += "the band ${band.left}..${band.right} leaves the card ${card.left}..${card.right}"
        }
        if (band.bottom > card.bottom + 0.5f) problems += "the band bottom ${band.bottom} leaves the card bottom ${card.bottom}"
        val firstButtonTop = listOf(HomeLaunchCardPlayKey, HomeLaunchCardOpenWithKey, HomeLaunchCardAchievementsKey)
            .mapNotNull { key ->
                compose.onAllNodes(hasTestTag(homeLaunchCardActionTestTag(key)), useUnmergedTree = true)
                    .fetchSemanticsNodes().firstOrNull()?.full()?.top
            }
            .minOrNull()
        if (firstButtonTop == null) {
            problems += "no action button is on the card"
        } else if (band.bottom > firstButtonTop + 0.5f) {
            problems += "the band bottom ${band.bottom} runs into the buttons, which start at $firstButtonTop"
        }
        return problems
    }

    private fun sweep(
        profile: MimirViewportProfile,
        widthDp: Float,
        heightDp: Float,
        name: String,
        assertFit: Boolean,
        maxLines: Int = 2,
    ) {
        mount(profile, widthDp, heightDp)
        for (font in listOf(1.0f, 1.3f)) {
            for ((label, line) in listOf("needs-longest" to longestNeeds, "has-but-not-pair" to longestPair, "needs-widest-other" to widestOther)) {
                val report = measure(line, font)
                println("STATUS-FIT $name font=$font $label: $report :: $line")
                if (assertFit) {
                    assertTrue("$name font=$font $label does not fit the $maxLines-line band: $report :: $line", fits(report, maxLines))
                    if (maxLines > 2) {
                        val problems = bandProblems(line)
                        println("STATUS-FIT $name font=$font $label: band problems $problems")
                        assertTrue("$name font=$font $label: the band is not whole on the card: $problems", problems.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `the longest sentences are the ones this test renders`() {
        assertEquals(
            "RetroArch needs the Mupen64Plus-Next GLES3 core. Get it in RetroArch: Online Updater › Core Downloader.",
            longestNeeds,
        )
        assertEquals(
            "RetroArch has Mupen64Plus-Next GLES3 but not Mupen64Plus-Next GLES2. Pick it with Open with.",
            longestPair,
        )
    }

    @Test
    fun `at Thor Main the band shows the whole sentence at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f, "thor-main", assertFit = true)
    }

    @Test
    fun `on a narrower standard card the band shows the whole sentence at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 640f, 400f), 640f, 400f, "standard-640", assertFit = true)
    }

    @Test
    fun `on the compact square the band shows the whole sentence at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 565f, 565f), 565f, 565f, "compact-565", assertFit = true)
    }

    @Test
    fun `on the 320 x 300 compact floor the band shows the whole sentence inside the card at fonts 1 and 1_3`() {
        sweep(
            MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 320f, 300f),
            320f,
            300f,
            "compact-floor",
            assertFit = true,
            maxLines = 4,
        )
    }
}
