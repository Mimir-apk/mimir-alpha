package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.LaunchChoiceScope
import com.gruning.mimir.rewrite.feature.library.OpenWithScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closing the launch card must release EVERY scope the card can own, including one it is not standing on.
 *
 * The card owns three: [HomeLaunchCardScope] underneath, and either [OpenWithScope] or
 * [LaunchChoiceScope] above it. Both close paths — `close()` and `finishClose()` — released the first
 * two with guarded pops and **never mentioned `LaunchChoiceScope` at all**, so a close that arrived
 * while the disc/version menu was up stranded two scopes at once: `LaunchChoiceScope` because nothing
 * released it, and `HomeLaunchCardScope` because `exitHomeLaunchCard`'s own guard saw the orphan on top
 * and declined.
 *
 * This is reachable with no user dismissal anywhere in it. `MimirAppCoordinator` closes the card when
 * the shelf row behind it disappears (`:507`, and `:2195` on an id match) — a rescan landing, or the
 * game being hidden, while the player has the Play menu open. `launchChoiceOpen` was not reset by
 * either close path either, so the coordinator also came back believing the menu was still open.
 */
class HomeLaunchCardScopeReleaseTest {

    private val homeShelf = FocusScope("home/shelf")

    @Test
    fun `releasing the card's scopes clears the launch choice menu it is standing on`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(homeShelf)
        authority.pushScope(HomeLaunchCardScope)
        authority.pushScope(LaunchChoiceScope)

        releaseHomeLaunchCardScopes(authority)

        assertEquals(
            "the launch-choice menu was left on the stack; exitHomeLaunchCard will now decline too",
            HomeLaunchCardScope,
            authority.state.value.scope,
        )
    }

    @Test
    fun `it clears the Open With sheet from underneath a launch choice menu`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(homeShelf)
        authority.pushScope(HomeLaunchCardScope)
        authority.pushScope(OpenWithScope)
        authority.pushScope(LaunchChoiceScope)

        releaseHomeLaunchCardScopes(authority)

        assertEquals(HomeLaunchCardScope, authority.state.value.scope)
    }

    @Test
    fun `it is a no-op when the card owns no scope above its own`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(homeShelf)
        authority.pushScope(HomeLaunchCardScope)

        releaseHomeLaunchCardScopes(authority)

        assertEquals(
            "releasing scopes the card does not hold must not pop the card itself",
            HomeLaunchCardScope,
            authority.state.value.scope,
        )
    }
}
