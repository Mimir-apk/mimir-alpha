package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolio
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioMode
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T-2 wired, not merely decided.
 *
 * `HomeTomeFolioDepartingChannelsTest` (feature-library) pins the two predicates. This drives the REAL
 * `HomeTomeFolio` composition to pin that `departing` actually reaches the surface and reaches the right
 * half of it — because the failure mode T-2 exists to prevent is not "the predicate is wrong", it is "the
 * predicate is right and nothing reads it", and a pure-function test cannot tell those apart.
 *
 * The instrument is the footer's focused-member title, which is the STATE channel's one semantically
 * visible consumer. The RING channel has no semantics to read (`mimirFocusTileRing` is paint), so it stays
 * pinned at decision level here and by spec criterion **C** on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeTomeFolioDepartingFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private val first = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("game-1")),
        title = "Chrono Trigger",
    )
    private val second = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("game-2")),
        title = "Secret of Mana",
    )

    private fun host(authority: DefaultFocusAuthority, departing: Boolean) {
        val density = Density(density = 1f, fontScale = 1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    Box(modifier = Modifier.requiredWidth(832.5.dp).requiredHeight(468.3.dp)) {
                        HomeTomeFolio(
                            state = HomeTomeFolioUiState(
                                tome = HomeShelfTome(
                                    tomeId = TomeId.Custom(
                                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                                    ),
                                    title = "Favourites",
                                    previews = emptyList(),
                                    memberCount = 2,
                                ),
                                members = listOf(first, second),
                                focusedIndex = 0,
                                mode = HomeTomeFolioMode.BROWSE,
                                focusSettled = true,
                            ),
                            authority = authority,
                            onFocusMember = {},
                            onOpenMember = {},
                            onArrangeAction = {},
                            onFirstInteraction = {},
                            departing = departing,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun armed(): DefaultFocusAuthority = DefaultFocusAuthority().apply {
        pushScope(HomeTomeFolioScope)
    }

    /**
     * The frame that gets recorded must be the frame the player was already looking at. `restoreRootEntry`
     * pops the scope in the same synchronous lambda that ends the session, so on the departing frame the
     * Folio no longer owns input — and if the footer read that directly, the recorded pixels would lose
     * the focused member's title and the departure would carry an unintended content pop.
     */
    @Test
    fun `the departing frame keeps the focused member the player was looking at`() {
        val authority = armed()
        host(authority, departing = true)

        compose.runOnIdle { authority.popScope() }
        compose.waitForIdle()

        footerNamesFirst(true)
    }

    /**
     * The other side of the same wire, and the reason `departing` is not just `true`. A Folio that loses
     * input WITHOUT departing — a tile menu or a launch card opening over it — is still live and on
     * screen, and there the footer correctly stops naming a focused member. Unchanged behaviour.
     */
    @Test
    fun `a folio that merely loses input stops naming a focused member`() {
        val authority = armed()
        host(authority, departing = false)

        compose.runOnIdle { authority.popScope() }
        compose.waitForIdle()

        footerNamesFirst(false)
    }

    /** Baseline: while the Folio owns input the footer names its member, departing or not. */
    @Test
    fun `a live folio names its focused member`() {
        val authority = armed()
        host(authority, departing = false)

        footerNamesFirst(true)
    }

    /**
     * The fixture's members have no art, so each one's book already shows its title (product review F13). The footer
     * naming the focused member is the second copy of it.
     */
    private fun footerNamesFirst(named: Boolean) {
        compose.onAllNodesWithText(first.title).assertCountEquals(if (named) 2 else 1)
    }
}
