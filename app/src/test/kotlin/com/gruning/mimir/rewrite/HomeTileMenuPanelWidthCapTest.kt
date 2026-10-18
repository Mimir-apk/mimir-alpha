package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Differential regression for site #1 of the seven-site `fillMaxWidth(fraction).widthIn(max=)` sweep:
 * `fillMaxWidth` fixes the panel's incoming min==max at the authored fraction of the available width,
 * so a trailing `widthIn(max=)` can only ever coerce UP to that already-fixed value — the authored cap
 * never bound (verified against the resolved `foundation-layout-android:1.7.3` bytecode).
 *
 * Hosted at the Odin reference viewport (1920x1080px @ density 369 => 832.5dp x 468.3dp): 90% of
 * that width is ~749dp, which blew straight through the authored 480dp cap before the fix. The fix
 * computes the panel width from the enclosing `BoxWithConstraints`'s own `maxWidth` instead of relying on
 * `fillMaxWidth` + `widthIn`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeTileMenuPanelWidthCapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tile menu panel width binds to the authored 480dp cap at the reference viewport`() {
        // density = 1 so 1dp == 1px: keeps the assertion's arithmetic exact instead of riding on
        // whatever device Robolectric simulates by default.
        val density = Density(density = 1f, fontScale = 1f)

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    // Odin reference viewport: 1920x1080px @ density 369 => 832.5dp x 468.3dp.
                    // requiredWidth/requiredHeight (enforceIncoming = false), not width/height: Robolectric's
                    // default root window is far narrower than this reference viewport, and plain
                    // width()/height() would silently coerce DOWN to that root instead of forcing this size —
                    // the same incoming-constraint trap the bug itself exploits.
                    Box(modifier = Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp)) {
                        TileArtMenuOverlay(
                            title = "Test Game",
                            options = listOf("Change icon"),
                            menuFocus = 0,
                            phase = HomeTileMenuPhase.Menu,
                            onMenuFocus = {},
                            onActivateMenu = {},
                            onPickFocus = {},
                            onActivatePick = {},
                        )
                    }
                }
            }
        }

        // useUnmergedTree: the scrim's click-catcher (a no-op `clickable` that only blocks pass-through
        // touches) merges descendant semantics upward, so the tagged node disappears from the default
        // merged tree even though it renders normally.
        val widthPx = compose.onNodeWithTag(HomeTileMenuPanelTestTag, useUnmergedTree = true)
            .fetchSemanticsNode().size.width
        val capPx = with(density) { 480.dp.toPx() }
        val toleranceDp = 1f

        assertTrue(
            "panel width was ${widthPx}px; must not exceed the authored 480dp cap " +
                "(+${toleranceDp}dp rounding tolerance = ${capPx + toleranceDp}px at density 1)",
            widthPx <= capPx + toleranceDp,
        )
    }
}
