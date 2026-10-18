package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** locks the Y → change-platform-art menu reducer (mirror of the JVM-tested game reducer). */
class PlatformArtMenuReducerTest {

    private val base = PlatformArtMenuState(platformId = PlatformId("arcade"), label = "Arcade")
    private fun candidate(url: String) = SteamGridDbTileFetcher.Candidate(url = url, thumbUrl = "$url#t")

    private fun reduce(
        state: PlatformArtMenuState,
        action: ControllerAction,
        onLoadArt: () -> Unit = {},
        onApplyPick: (SteamGridDbTileFetcher.Candidate, Int) -> Unit = { _, _ -> },
        onRevert: () -> Unit = {},
    ) = reducePlatformArtMenu(state, action, onLoadArt, onApplyPick, onRevert)

    @Test
    fun `menu options are change-art then revert`() {
        assertEquals(listOf("Change icon art", "Revert to default"), platformArtMenuOptions())
    }

    @Test
    fun `platform Customize Media state is icon only and starts from the canonical search title`() {
        val platformId = PlatformId("snes")
        val picker = newPlatformCustomEnrichPicker(
            platformId = platformId,
            label = "SNES",
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
            catalogReady = true,
            sessionId = "test-platform-icon",
        )

        assertEquals(EnrichMediaKind.ICON, picker.kind)
        assertEquals(SteamGridDbTileFetcher.platformKey(platformId), picker.cacheKey)
        assertEquals("SNES", picker.gameTitle)
        assertEquals(
            SteamGridDbTileFetcher.platformArtSearchTitle(platformId.value, "SNES"),
            picker.queryDraft,
        )
        assertEquals(listOf("kind", "style", "size"), picker.filters.map(HomeCustomEnrichFilter::key))
    }

    @Test
    fun `nav cycles menu focus and wraps`() {
        val down = reduce(base, ControllerAction.NAV_DOWN)!!
        assertEquals(1, down.menuFocus)
        // wrap back to 0
        assertEquals(0, reduce(down, ControllerAction.NAV_DOWN)!!.menuFocus)
        // NAV_UP from 0 wraps to the last option
        assertEquals(1, reduce(base, ControllerAction.NAV_UP)!!.menuFocus)
    }

    @Test
    fun `confirm on change-art loads art and enters LoadingArt`() {
        var loaded = false
        val next = reduce(base.copy(menuFocus = 0), ControllerAction.CONFIRM, onLoadArt = { loaded = true })
        assertTrue(loaded)
        assertTrue(next?.phase is HomeTileMenuPhase.LoadingArt)
    }

    @Test
    fun `confirm on revert clears art and holds the menu open until the result is known`() {
        // a refused revert HOLDS THE MENU OPEN WITH A NOTE, so the reducer can
        // no longer close synchronously before onRevert()'s suspend clear even runs -- it must transition
        // to Applying (same as a real apply) and let the coordinator's onRevert() decide close vs note
        // once the actual result is known. Previously this asserted `assertNull(next)`.
        var reverted = false
        val next = reduce(base.copy(menuFocus = 1), ControllerAction.CONFIRM, onRevert = { reverted = true })
        assertTrue(reverted)
        assertTrue(next?.phase is HomeTileMenuPhase.Applying)
    }

    @Test
    fun `a refused revert explains itself and B returns to the top-level menu, not stranded or auto-closed`() {
        val unavailable = base.copy(phase = HomeTileMenuPhase.ProviderUnavailable("Couldn’t save this icon art. Try again."))

        val onNav = reduce(unavailable, ControllerAction.NAV_DOWN)
        assertEquals("a non-dismiss action must not close or otherwise move the dead end", unavailable, onNav)

        val onBack = reduce(unavailable, ControllerAction.SYSTEM_BACK)
        assertTrue("B must return to the menu, never close it out from under the player", onBack?.phase is HomeTileMenuPhase.Menu)
    }

    @Test
    fun `cancel on menu closes`() {
        assertNull(reduce(base, ControllerAction.CANCEL))
        assertNull(reduce(base, ControllerAction.SYSTEM_BACK))
    }

    @Test
    fun `art pick navigates, applies the focused candidate, and back returns to menu`() {
        val pick = base.copy(
            phase = HomeTileMenuPhase.ArtPick(
                // The platform menu has its own "Revert to default" row, so every slot is a candidate.
                choices = listOf(candidate("a"), candidate("b"), candidate("c"))
                    .map(HomeTileArtChoice::Art),
                focusedIndex = 0,
            ),
        )
        val moved = reduce(pick, ControllerAction.NAV_RIGHT)!!
        assertEquals(1, (moved.phase as HomeTileMenuPhase.ArtPick).focusedIndex)

        var appliedUrl: String? = null
        var appliedIndex = -1
        val applied = reduce(moved, ControllerAction.CONFIRM, onApplyPick = { c, i -> appliedUrl = c.url; appliedIndex = i })
        assertEquals("b", appliedUrl)
        assertEquals(1, appliedIndex)
        assertTrue(applied?.phase is HomeTileMenuPhase.Applying)

        // B from the pick strip returns to the menu (not close)
        assertTrue(reduce(pick, ControllerAction.SYSTEM_BACK)?.phase is HomeTileMenuPhase.Menu)
    }

    @Test
    fun `platform art has no upload footer lane`() {
        val pick = base.copy(
            phase = HomeTileMenuPhase.ArtPick(
                choices = listOf(HomeTileArtChoice.Art(candidate("a"))),
            ),
        )

        assertEquals(pick, reduce(pick, ControllerAction.NAV_DOWN))
        assertEquals(false, (pick.phase as HomeTileMenuPhase.ArtPick).footerFocused)
    }

    @Test
    fun `cancel during loading closes`() {
        assertNull(reduce(base.copy(phase = HomeTileMenuPhase.LoadingArt), ControllerAction.CANCEL))
    }
}
