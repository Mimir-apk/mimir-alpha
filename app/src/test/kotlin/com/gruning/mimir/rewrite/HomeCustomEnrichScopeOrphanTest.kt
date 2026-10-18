package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the Customize-media picker's scope must remove it, not merely decline to.
 *
 * Found while wiring the Upload action to Mimir's own browser (2026-09-11): `FOLDER_BROWSER` ranks above
 * `HOME_TILE_MENU` in `resolveMimirOverlayStack`, so opening it fades the tile menu out and disposes
 * `HomeCustomEnrichPickerOverlay` UNDER the browser's scope. Its `onDispose` was a guarded pop -- the
 * exact shape `HomeTomeLeaveScopeOrphanTest` retired for the Folio -- which declines when covered and
 * strands `HomeCustomEnrichScope` beneath the browser. The scenario below is the whole return path of an
 * upload, step by step, so the defect it would have produced is named rather than implied.
 */
class HomeCustomEnrichScopeOrphanTest {

    /** The PICK_IMAGE browser's own scope stands in for `FileBrowserFocus.DefaultScope` (internal to feature-files). */
    private val browser = FocusScope("test/folder-browser")

    @Test
    fun `disposing under the PICK_IMAGE browser removes the picker scope instead of stranding it`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        authority.pushScope(HomeCustomEnrichScope) // the Customize-media picker is open
        authority.pushScope(browser) // Upload: the browser mounts on top and pushes its scope
        assertEquals(browser, authority.state.value.scope)

        leaveHomeCustomEnrichScope(authority) // the tile menu fades out and disposes the picker

        // The browser is untouched: the picker leaving must not disturb what covers it.
        assertEquals(browser, authority.state.value.scope)

        authority.popScope() // the browser closes -- on a pick or on a cancel

        assertEquals(
            "focus must land on the shelf, NOT on a picker scope whose composable is gone",
            HomeShelfScope,
            authority.state.value.scope,
        )

        // The tile menu re-shows and the picker re-enters: exactly ONE copy, which its own leave removes.
        authority.pushScope(HomeCustomEnrichScope)
        leaveHomeCustomEnrichScope(authority)
        assertEquals(HomeShelfScope, authority.state.value.scope)
    }

    @Test
    fun `the ordinary close is unchanged -- leaving the active picker scope is a plain pop`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        authority.pushScope(HomeCustomEnrichScope)

        leaveHomeCustomEnrichScope(authority)

        assertEquals(HomeShelfScope, authority.state.value.scope)
    }

    @Test
    fun `leaving a scope that is not on the stack changes nothing`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)

        leaveHomeCustomEnrichScope(authority)

        assertEquals(HomeShelfScope, authority.state.value.scope)
    }

    /**
     * The retired shape, replayed, so the repair cannot be reverted as a "simplification": a guarded pop
     * under a covering scope leaves the picker scope on the deque, the browser's pop lands on it, and the
     * re-entering picker doubles it -- after which its own pop still leaves one copy covering Home.
     */
    @Test
    fun `the guarded pop this replaces would have doubled the scope and buried Home`() {
        val authority = DefaultFocusAuthority()
        authority.pushScope(HomeShelfScope)
        authority.pushScope(HomeCustomEnrichScope)
        authority.pushScope(browser)

        if (authority.state.value.scope == HomeCustomEnrichScope) authority.popScope() // declines
        authority.popScope() // browser closes
        assertEquals("stranded: the stale picker scope is what the browser's pop lands on", HomeCustomEnrichScope, authority.state.value.scope)
        authority.pushScope(HomeCustomEnrichScope) // re-entry doubles it
        authority.popScope() // the picker's eventual close
        assertEquals("Home stays buried under the duplicate", HomeCustomEnrichScope, authority.state.value.scope)
    }

    @Test
    fun `the picker's onDispose routes through the total leave and no longer carries the guarded pop`() {
        fun read(name: String): String = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        ).firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("$name not reachable from the test working directory")
        // The picker's dispose goes through its scope hold (audit 2026-10-14, RC-50), which holds the one total leave.
        val picker = read("HomeCustomEnrichPicker.kt")
        assertTrue("the dispose releases through the hold", picker.contains("scopeHold.release()"))
        val source = picker + System.lineSeparator() + read("HomeCustomEnrichScopeHold.kt")

        assertTrue(source.contains("leaveHomeCustomEnrichScope(authority)"))
        assertFalse(
            "the guarded pop is the defect; it must not come back beside the total leave",
            source.contains("if (authority.state.value.scope == HomeCustomEnrichScope) authority.popScope()"),
        )
        assertTrue(
            "the leave must be discardScope -- a pop when active, a silent removal when buried",
            source.contains("authority.discardScope(HomeCustomEnrichScope)"),
        )
    }
}
