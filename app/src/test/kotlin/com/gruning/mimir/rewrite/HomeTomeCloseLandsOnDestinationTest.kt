package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-49 / H-03c (audit 2026-10-14): closing a Tome lands the ring on the destination in the same step as the pop.
 *
 * `restoreRootEntry` used to pop the Folio scope first and stamp the destination key after it. A pop restores the shelf's
 * remembered focus from BEFORE the Tome opened, so when the return resolves somewhere else (the Tome was deleted, the last pin
 * was removed, the mode fell back) the ring sat on the old tile, or on a neighbour when that tile was gone, for the one to eight
 * frames the ordered return needed, and then snapped. [leaveHomeTomeScopeLandingOn] stamps the destination first.
 *
 * The authority is the real one, so this is the law and not a model of it: the scope stack and its focus memory are what decide
 * where a pop lands.
 */
class HomeTomeCloseLandsOnDestinationTest {

    private val openedFrom = FocusKey("home/shelf/tome/opened-from")
    private val destination = FocusKey("home/shelf/game/destination")
    private val other = FocusKey("home/shelf/game/other")
    private val member = FocusKey("home/tome/folio/member-0")

    /** The shelf is the scope beneath, focused on the tile the Tome was opened from; the Folio is open on top of it. */
    private fun folioOpenOverShelf(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        listOf(openedFrom, destination, other).forEachIndexed { index, key ->
            authority.registerVirtual(key, HomeShelfScope, FocusBounds(index * 100f, 0f, index * 100f + 90f, 90f))
        }
        authority.requestFocus(openedFrom)
        authority.registerVirtual(member, HomeTomeFolioScope, FocusBounds(0f, 200f, 90f, 290f))
        authority.pushScope(HomeTomeFolioScope)
        assertEquals("the Folio owns input", HomeTomeFolioScope, authority.state.value.scope)
        return authority
    }

    @Test
    fun `stamping the destination before the pop lands the ring on it in the same step`() {
        val authority = folioOpenOverShelf()

        leaveHomeTomeScopeLandingOn(authority, HomeTomeFolioScope, landingKey = destination)

        assertEquals(HomeShelfScope, authority.state.value.scope)
        assertEquals("the pop itself landed on the destination, no frame later", destination, authority.state.value.focused)
    }

    @Test
    fun `the order it replaces left the ring on the old tile until a later step stamped the key`() {
        val authority = folioOpenOverShelf()

        // The old restoreRootEntry: pop first, stamp after.
        leaveHomeTomeScope(authority, HomeTomeFolioScope)
        authority.rememberFocusForScope(HomeShelfScope, destination)

        assertEquals("a stamp after the pop moves nothing: the ring is on the pre-Tome tile", openedFrom, authority.state.value.focused)
    }

    @Test
    fun `a destination the authority has not registered yet leaves the pop where it always landed`() {
        val authority = folioOpenOverShelf()
        val notYetRegistered = FocusKey("home/shelf/game/after-the-mode-switch")

        leaveHomeTomeScopeLandingOn(authority, HomeTomeFolioScope, landingKey = notYetRegistered)

        assertEquals(
            "a mode switch registers its keys after the epoch recomposes; the ordered return covers it, and until then the pop is unchanged",
            openedFrom,
            authority.state.value.focused,
        )
    }

    @Test
    fun `no destination at all is the plain pop`() {
        val authority = folioOpenOverShelf()

        leaveHomeTomeScopeLandingOn(authority, HomeTomeFolioScope, landingKey = null)

        assertEquals(openedFrom, authority.state.value.focused)
    }

    @Test
    fun `restoreRootEntry resolves its destination before it leaves a scope`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeShelfCoordinator.kt not found from ${File(".").absolutePath}")
        val restore = source.substringAfter("val restoreRootEntry: (VisualHomeShelfEntry?) -> Unit = { entry ->").substringBefore("val closeTome: () -> Boolean")
        assertTrue("the destination is resolved", restore.contains("val resolvedEntry ="))
        assertTrue(
            "and it is resolved before any scope is left",
            restore.indexOf("val resolvedEntry =") in 0 until restore.indexOf("leaveHomeTomeScopeLandingOn("),
        )
        assertTrue("both child scopes leave through the landing form", Regex("""leaveHomeTomeScopeLandingOn\(focusAuthority, HomeTome(Library|Folio)Scope, landingKey\)""").findAll(restore).count() == 2)
        assertTrue("never a bare pop here", !restore.contains("leaveHomeTomeScope(focusAuthority"))
    }
}
