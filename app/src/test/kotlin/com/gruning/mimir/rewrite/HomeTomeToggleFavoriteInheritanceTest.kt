package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wide-scope Tome membership behavior: adding a game to a Tome must apply the
 * SAME favorite/shelf membership mutation the favorite path already uses — one path, not a parallel call
 * that merely agrees today.
 *
 * [homeTomeToggleShouldFavorite] is the pure policy this fix introduces and is tested behaviourally below.
 * [toggleTomeMembership] itself lives inside `rememberHomeShelfCoordinator`'s `@Composable` closure — no
 * JVM test can drive it directly, the same accepted gap `HomeTileMenuTomeCheckboxTruthTest`'s KDoc documents
 * for its neighbor (`onToggleCollection`'s wiring). It is pinned here as a source-characterization test
 * instead, following `SessionMutexRefusalWiringTest`'s precedent: grep the exact production wiring, so
 * reverting it (not just the pure policy function) turns this test red too.
 */
class HomeTomeToggleFavoriteInheritanceTest {

    private val tomeId = TomeId.Custom(UUID.nameUUIDFromBytes("Cabinet".toByteArray()))
    private val otherTomeId = TomeId.Custom(UUID.nameUUIDFromBytes("Other".toByteArray()))
    private val member = FavoriteMemberId.Emulated(GameId("game-1"))

    private fun document(vararg tomes: CustomHomeTome) = HomeTomeDocument(customTomes = tomes.toList())
    private fun tome(id: TomeId.Custom, vararg members: FavoriteMemberId) =
        CustomHomeTome(id = id, label = "T", members = members.toList())

    // ── The policy, pinned: constraint 1, "apply the favorite only on ADD" ─────────────────────────

    @Test
    fun `adding a non-favorite to a Tome inherits favorite state`() {
        val before = document(tome(tomeId))
        val after = document(tome(tomeId, member))

        assertTrue(
            "an ADD that lands a non-favorite in a Tome must trigger the inherited favorite write",
            homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite = false),
        )
    }

    // ── The policy, pinned: constraint 2, REMOVE must never un-favorite ────────────────────────────

    @Test
    fun `removing from a Tome never un-favorites, whether or not it currently is one`() {
        val before = document(tome(tomeId, member))
        val after = document(tome(tomeId))

        assertFalse(
            "a REMOVE direction must never trigger a favorite write for a game that is a favorite",
            homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite = true),
        )
        assertFalse(
            "a REMOVE direction must never trigger a favorite write even for a dormant non-favorite member",
            homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite = false),
        )
    }

    // ── Refinement beyond the letter of constraint 1: do not re-touch an existing Favorite's order ─

    @Test
    fun `adding an already-favorite game does not re-touch its favorite order`() {
        val before = document(tome(tomeId))
        val after = document(tome(tomeId, member))

        assertFalse(
            "a game already on the shelf must not be re-favorited just for entering a Tome " +
                "(would silently bump its favorited-at order, FAVORITE_GAME_ORDER_BY_SQL)",
            homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite = true),
        )
    }

    // ── Degenerate cases must all fail closed: skip the favorite write, never fire it spuriously ──

    @Test
    fun `a refused Tome write is a no-op and never triggers the favorite path`() {
        val before = document(tome(tomeId))
        // A refused persist() never updates HomeTomeStore's state.value, so before == after.
        assertFalse(
            "a refused Tome write (before == after) must not trigger a favorite write",
            homeTomeToggleShouldFavorite(before, before, tomeId, member, alreadyFavorite = false),
        )
    }

    @Test
    fun `a change to a different Tome does not trigger the favorite path`() {
        val before = document(tome(tomeId), tome(otherTomeId))
        val after = document(tome(tomeId), tome(otherTomeId, member))

        assertFalse(
            "the membership change belongs to a different Tome than the one just toggled",
            homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite = false),
        )
    }

    @Test
    fun `a Tome that no longer exists in either snapshot never triggers the favorite path`() {
        val before = document(tome(otherTomeId))
        val after = document(tome(otherTomeId))

        assertFalse(
            "a tomeId absent from both snapshots must read as no membership either side, not a crash",
            homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite = false),
        )
    }

    // ── Wiring: toggleTomeMembership must actually route through the shared, testable pieces ──────

    @Test
    fun `toggleTomeMembership derives direction and applies the shared mutation, not a copy of it`() {
        val source = homeShelfCoordinatorSource()
        val fn = source.substringAfter(
            "val toggleTomeMembership: (TomeId.Custom, HomeShelfItemId, (Boolean) -> Unit) -> Unit = " +
                "{ tomeId, itemId, done ->",
        ).substringBefore("val deleteTome: (TomeId.Custom, (Boolean) -> Unit) -> Unit = delete@{")

        assertTrue(
            "toggleTomeMembership must derive direction via homeTomeToggleShouldFavorite, not assume " +
                "it from the caller's own optimistic UI state",
            fn.contains("homeTomeToggleShouldFavorite("),
        )
        assertTrue(
            "toggleTomeMembership must apply the favorite through the SAME shared mutation the Favorite " +
                "star uses (applyShelfMembership), not a copy of its logic",
            fn.contains("applyShelfMembership("),
        )
        assertTrue(
            "the combined result — Tome write AND favorite write — must be reported through the " +
                "caller's done callback, not a fire-and-forget",
            fn.contains("done(tomeSaved && favoriteApplied)"),
        )
    }

    @Test
    fun `setMembershipById and toggleTomeMembership share one mutation, not two`() {
        val source = homeShelfCoordinatorSource()

        assertEqualsOne(
            "applyShelfMembership must be defined exactly once (the shared mutation, not duplicated)",
            source,
            "val applyShelfMembership: suspend (HomeShelfItemId, Boolean) -> Boolean = {",
        )
        assertTrue(
            "setMembershipById must call the shared mutation rather than inline its own copy",
            source.substringAfter("val setMembershipById: (HomeShelfItemId, Boolean) -> Unit = {")
                .substringBefore("val launch: (HomeShelfRow) -> Unit = launchAction@{")
                .contains("applyShelfMembership(itemId, shouldBeOnShelf)"),
        )
    }

    private fun assertEqualsOne(message: String, haystack: String, needle: String) {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            count++
            from = at + needle.length
        }
        assertTrue("$message (found $count occurrences)", count == 1)
    }

    private fun homeShelfCoordinatorSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("HomeShelfCoordinator.kt not found from ${File(".").absolutePath}")
    }
}
