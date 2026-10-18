package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.TomeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * O-7 : the Home tile menu's Tome checkbox used to
 * flip before its async save result was known — `HomeTileMenuOverlay.kt`'s `CollectionPick` CONFIRM arm
 * flips `isMember` synchronously, then fires `onToggleCollection`, whose only implementation
 * (`HomeShelfCoordinator.toggleTomeMembership` -> `HomeCollectionStore.toggleMember` -> `persist`) was
 * fire-and-forget with no result slot at all. A refused `persist()` (SharedPreferences `commit()`
 * returning false) left the box checked with nothing actually saved.
 *
 * The fix keeps the optimistic flip (persist() is a local disk commit, not network — instant feedback
 * for the common case is the right call, not a laggy "Applying" spinner for every checkbox tap) and adds
 * a revert-on-failure path. `homeTileMenuAfterToggleCollectionResult` is that revert, extracted as a pure
 * function (same idiom this codebase already uses for `loadCustomPreviewClipPhase`,
 * `librarySourceReadFailureMessage`) so it is genuinely behaviorally testable — not the source
 * characterization this file's neighbors fall back to for logic trapped inside a `@Composable` closure.
 * `MimirAppCoordinator.kt`'s `onToggleCollection` wiring that calls this function from inside
 * `HomeShelfCoordinator.toggleTomeMembership`'s callback is NOT separately behaviorally tested — no
 * Robolectric harness in this module reaches that `@Composable` closure's real `navigation.homeTileMenu`
 * mutable state, the same accepted gap as `MediaMenuHonestyCoordinatorWiringTest` and this file's other
 * neighbors. That wiring is reasoned from source (four lines, direct pass-through of already-tested
 * pieces), not test-pinned.
 */
class HomeTileMenuTomeCheckboxTruthTest {
    private fun tomeId(label: String) = TomeId.Custom(UUID.nameUUIDFromBytes(label.toByteArray()))

    private val itemId = HomeShelfItemId.Emulated(GameId("game-1"))
    private val otherItemId = HomeShelfItemId.Emulated(GameId("game-2"))
    private val row = HomeShelfRow(itemId = itemId, rank = 1L, addedAtMillis = 10L, title = "Conker's Bad Fur Day")
    private val rpgs = tomeId("RPGs")
    private val couch = tomeId("Couch")

    private fun collectionPickState(entries: List<HomeTileCollectionEntry>, rowItemId: HomeShelfItemId = itemId) =
        HomeTileMenuState(
            row = row.copy(itemId = rowItemId),
            removable = true,
            phase = HomeTileMenuPhase.CollectionPick(entries),
        )

    // ── The defect, pinned: a refused save must not leave the box checked ──────────────────────────

    @Test
    fun `a refused save reverts the optimistic flip and tells the player`() {
        // The reducer already flipped this optimistically to true before the save's result is known.
        val optimistic = collectionPickState(
            entries = listOf(
                HomeTileCollectionEntry("RPGs", isMember = true, id = rpgs),
                HomeTileCollectionEntry("Couch", isMember = false, id = couch),
            ),
        )

        val reconciled = homeTileMenuAfterToggleCollectionResult(
            latest = optimistic,
            itemId = itemId,
            tomeId = rpgs,
            saved = false,
        )

        val phase = (reconciled ?: error("expected a reverted state, got null")).phase as HomeTileMenuPhase.CollectionPick
        assertFalse("a refused save must not leave the box checked", phase.entries[0].isMember)
        assertEquals("Couldn’t save Tome", phase.note)
        // The other entry is untouched by this one tome's refusal.
        assertFalse(phase.entries[1].isMember)
    }

    // ── The healthy path, pinned unchanged: a successful toggle stays instant, no extra churn ──────

    @Test
    fun `a successful save leaves the optimistic flip alone`() {
        val optimistic = collectionPickState(
            entries = listOf(HomeTileCollectionEntry("RPGs", isMember = true, id = rpgs)),
        )

        val reconciled = homeTileMenuAfterToggleCollectionResult(
            latest = optimistic,
            itemId = itemId,
            tomeId = rpgs,
            saved = true,
        )

        assertNull("a successful save needs no reconciliation write at all", reconciled)
    }

    // ── Stale-write guards, matching every other async leg in MimirAppCoordinator ───────────────────

    @Test
    fun `a refused save for a tile whose menu has since moved to a different row is dropped`() {
        val optimistic = collectionPickState(
            entries = listOf(HomeTileCollectionEntry("RPGs", isMember = true, id = rpgs)),
            rowItemId = otherItemId,
        )

        val reconciled = homeTileMenuAfterToggleCollectionResult(
            latest = optimistic,
            itemId = itemId,
            tomeId = rpgs,
            saved = false,
        )

        assertNull("a stale save result for a different tile's menu must not clobber it", reconciled)
    }

    @Test
    fun `a refused save after the menu backed out of CollectionPick is dropped`() {
        val movedOn = HomeTileMenuState(row = row, removable = true, phase = HomeTileMenuPhase.Menu)

        val reconciled = homeTileMenuAfterToggleCollectionResult(
            latest = movedOn,
            itemId = itemId,
            tomeId = rpgs,
            saved = false,
        )

        assertNull("a save result arriving after the picker closed must not resurrect it", reconciled)
    }

    @Test
    fun `a fresh CONFIRM clears a stale note from a previous refusal`() {
        val withStaleNote = collectionPickState(
            entries = listOf(HomeTileCollectionEntry("RPGs", isMember = false, id = rpgs)),
        ).let { it.copy(phase = (it.phase as HomeTileMenuPhase.CollectionPick).copy(note = "Couldn’t save Tome")) }

        val next = reduceHomeTileMenu(
            state = withStaleNote,
            action = com.gruning.mimir.rewrite.core.input.ControllerAction.CONFIRM,
            onRemove = {},
            onLoadArt = {},
            onApplyPick = { _, _ -> },
        )!!

        val phase = next.phase as HomeTileMenuPhase.CollectionPick
        assertTrue("the fresh optimistic flip must still happen", phase.entries[0].isMember)
        assertNull("a new attempt must clear the previous refusal's note", phase.note)
    }
}
