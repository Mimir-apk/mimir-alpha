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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
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
 * on the Home launch card the ACHIEVEMENTS button (136 dp nominal) ended in an ellipsis at font scale 1.3. The buttons' widths are fixed (the focus bounds and the row
 * solver read them), so the label now gives: it is measured as drawn and stepped down from 12 sp only as far as it needs to stand whole. This renders the REAL [HomeLaunchCard] and reads
 * each button label's laid-out line: ONE line, not ellipsized and not clipped (its line ends inside its box), for Play, Open with and Achievements, at Thor Main's 832.5 x 468.3 dp, a
 * narrower standard card, the 565 dp compact square and the 320 x 300 compact floor (where the row stacks), at font scales 1.0 and 1.3. `ACTION-LABEL-FIT` prints the size each label took.
 * Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLaunchCardActionLabelFitTest {

    @get:Rule
    val compose = createComposeRule()

    private var fontScale by mutableStateOf(1f)

    /** The labels' laid-out lines, by spoken text: a button's text hides its own layout from the semantics tree. */
    private val layouts = mutableMapOf<String, TextLayoutResult>()

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

    private fun mount(profile: MimirViewportProfile, widthDp: Float, heightDp: Float) {
        layouts.clear()
        compose.setContent {
            val authority = remember { DefaultFocusAuthority().apply { pushScope(HomeLaunchCardScope) } }
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalMimirViewportProfile provides profile,
                LocalMimirTextLayoutObserver provides { spoken: String, layout: TextLayoutResult -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
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

    private fun check(name: String, font: Float, label: String) {
        val layout = requireNotNull(layouts[label]) { "$name font=$font: the '$label' button label was laid out (heard: ${layouts.keys})" }
        assertEquals("$name font=$font: '$label' is ONE line", 1, layout.lineCount)
        assertFalse(
            "$name font=$font: '$label' is not ellipsized",
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) },
        )
        assertTrue(
            "$name font=$font: '$label' ends inside its box, not clipped: ${layout.getLineRight(0)} vs ${layout.size.width}",
            layout.getLineRight(0) <= layout.size.width + 0.5f,
        )
        assertFalse("$name font=$font: '$label' does not overflow its width", layout.didOverflowWidth)
        println(
            "ACTION-LABEL-FIT $name font=$font '$label': ${layout.layoutInput.style.fontSize} " +
                "line ${layout.getLineRight(0)} of ${layout.size.width} px",
        )
    }

    private fun sweep(profile: MimirViewportProfile, widthDp: Float, heightDp: Float, name: String) {
        mount(profile, widthDp, heightDp)
        for (font in listOf(1.0f, 1.3f)) {
            fontScale = font
            compose.waitForIdle()
            check(name, font, cardState.primaryActionLabel)
            check(name, font, cardState.openWithActionLabel)
            check(name, font, "Achievements")
        }
    }

    @Test
    fun `at Thor Main the three button labels are whole at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 832.5f, 468.3f), 832.5f, 468.3f, "thor-main")
    }

    @Test
    fun `on a narrower standard card the three button labels are whole at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.STANDARD, 640f, 400f), 640f, 400f, "standard-640")
    }

    @Test
    fun `on the compact square the three button labels are whole at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 565f, 565f), 565f, 565f, "compact-565")
    }

    @Test
    fun `on the compact floor, where the row stacks, the three button labels are whole at fonts 1 and 1_3`() {
        sweep(MimirViewportProfile(MimirViewportPosture.COMPACT_SQUARE, 320f, 300f), 320f, 300f, "compact-floor")
    }
}
