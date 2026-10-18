package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounds the reactive wait so a broken write path fails fast with a message instead of hanging the suite. */
private const val RenameSettleTimeoutMs = 5_000L

/**
 * THE acceptance test owed by the rename spec (the rename-to-picker regression case): rename a game, then open Customize Media, and assert the search draft equals
 * the RENAMED title — not a screenshot of the launch card.
 *
 * Why this test and not a card-render assertion: `game.title` is the ONE seam both surfaces read
 * (`HomeShelfCoordinator.kt:2440/2448` pass `gameTitle = game.title` into `newHomeCustomEnrichState`, whose
 * `queryDraft = gameTitle` is a pure pass-through — see `HomeCustomEnrichPicker.kt:844-862`). A fix applied
 * at a render site (patching a launch card's `Text()` to show the override) would make the CARD look
 * correct while `game.title` returned by the repository/ViewModel stays the WRONG detected name — this test
 * would catch exactly that, because it reads `GameRow.title` from [LibraryViewModel] the same way
 * `HomeShelfCoordinator` does, never from a UI composable.
 */
class GameRenameCustomizeMediaSeedTest {

    @Test
    fun renamedTitlePrimesCustomizeMediaSearchDraft() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        viewModel.openPlatform(PlatformId.PS1)
        val original = viewModel.games.first { it.isNotEmpty() }.first()
        assertTrue("fixture must start undetected-name", !original.hasTitleOverride)

        val renamedTitle = "Chrono Cross: The Radical Dreamers Edition"
        viewModel.setTitleOverride(original.gameId, renamedTitle)

        // Read back exactly what HomeShelfCoordinator reads: the reactive GameRow, never a UI snapshot.
        // setTitleOverride dispatches the repository write on Dispatchers.Default (fire-and-forget from the
        // caller's perspective), so the predicate waits for the RENAMED emission rather than the first one
        // (which would just be the pre-rename list — a race that would make this test pass for the wrong
        // reason, exactly the kind of false-positive the acceptance test must not be).
        val renamed = withTimeout(RenameSettleTimeoutMs) {
            viewModel.games
                .first { rows -> rows.any { it.gameId == original.gameId && it.title == renamedTitle } }
                .first { it.gameId == original.gameId }
        }
        assertEquals(renamedTitle, renamed.title)
        assertTrue(renamed.hasTitleOverride)

        // The exact production call shape (HomeShelfCoordinator.kt:2440/2448).
        val state = newHomeCustomEnrichState(
            sessionId = "session-1",
            kind = EnrichMediaKind.ICON,
            cacheKey = renamed.gameId.value,
            gameTitle = renamed.title,
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
        )

        assertEquals(renamedTitle, state.queryDraft)
    }

    @Test
    fun resetToDetectedNameFallsBackForTheSameSearchSeed() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        viewModel.openPlatform(PlatformId.PS1)
        val original = viewModel.games.first { it.isNotEmpty() }.first()
        val detectedTitle = original.title

        viewModel.setTitleOverride(original.gameId, "A Bad Manual Rename")
        val renamed = withTimeout(RenameSettleTimeoutMs) {
            viewModel.games
                .first { rows -> rows.any { it.gameId == original.gameId && it.title == "A Bad Manual Rename" } }
                .first { it.gameId == original.gameId }
        }
        assertEquals("A Bad Manual Rename", renamed.title)

        viewModel.clearTitleOverride(original.gameId)
        val reset = withTimeout(RenameSettleTimeoutMs) {
            viewModel.games.first { rows ->
                rows.any { it.gameId == original.gameId && !it.hasTitleOverride }
            }.first { it.gameId == original.gameId }
        }

        assertEquals(detectedTitle, reset.title)
        assertTrue(!reset.hasTitleOverride)

        val state = newHomeCustomEnrichState(
            sessionId = "session-2",
            kind = EnrichMediaKind.ICON,
            cacheKey = reset.gameId.value,
            gameTitle = reset.title,
            catalog = EnrichSavedCatalog(activeAssetId = null, entries = emptyList()),
        )
        assertEquals(detectedTitle, state.queryDraft)
    }
}
