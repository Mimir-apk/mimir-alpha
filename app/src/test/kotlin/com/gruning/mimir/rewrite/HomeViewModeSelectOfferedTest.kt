package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2-01 — the Home shoulders keep the collection switch on both Odin and Thor (PR173).
 *
 * The rule is pinned here rather than at either call site, because there are TWO call sites reading two
 * different truths — the dispatch reads input truth, the badge reads render truth — and the defect this
 * guards against is them drifting apart. Gate one and the other keeps offering a control that is gone.
 */
class HomeViewModeSelectOfferedTest {

    @Test
    fun `a companion display keeps the Home shoulder switch`() {
        assertTrue(
            homeViewModeSelectOffered(
                companionDisplayActive = true,
                viewModeToggleAvailable = true,
                viewMode = HomeViewMode.PINNED_TOMES,
            ),
        )
    }

    @Test
    fun `both display topologies use the same Home predicate`() {
        assertTrue(
            homeViewModeSelectOffered(
                companionDisplayActive = false,
                viewModeToggleAvailable = true,
                viewMode = HomeViewMode.EDITORS_CHOICE,
            ),
        )
        assertTrue(
            "the PINNED_TOMES leg is the other half of the shelf's own rule and must survive",
            homeViewModeSelectOffered(
                companionDisplayActive = false,
                viewModeToggleAvailable = false,
                viewMode = HomeViewMode.PINNED_TOMES,
            ),
        )
        assertFalse(
            "and with neither reason, it is still not offered",
            homeViewModeSelectOffered(
                companionDisplayActive = false,
                viewModeToggleAvailable = false,
                viewMode = HomeViewMode.EDITORS_CHOICE,
            ),
        )
    }

    @Test
    fun `over every state, Home offers the switch exactly when a switch can succeed`() {
        // Moved here from CompanionCollectionSwitchTest when the app removed the Bottom
        // collection projection that file also pinned. Exhaustive rather than sampled: 2 (companion display)
        // x 2 (a pinned set exists) x 2 (which collection is showing) = 8 states, because the failure this
        // guards against is one uncovered combination.
        val states = listOf(true, false).flatMap { companionActive ->
            listOf(true, false).flatMap { toggleAvailable ->
                HomeViewMode.entries.map { mode -> Triple(companionActive, toggleAvailable, mode) }
            }
        }
        assertEquals(8, states.size)
        states.forEach { (companionActive, toggleAvailable, mode) ->
            assertEquals(
                "Home predicate: $companionActive/$toggleAvailable/$mode",
                homeViewModeSwitchPossible(toggleAvailable, mode),
                homeViewModeSelectOffered(companionActive, toggleAvailable, mode),
            )
        }
        assertEquals(
            "the states with no switch are exactly the empty-pinned-set-on-Editor's-Choice ones",
            setOf(
                Triple(true, false, HomeViewMode.EDITORS_CHOICE),
                Triple(false, false, HomeViewMode.EDITORS_CHOICE),
            ),
            states.filterNot { (companionActive, toggleAvailable, mode) ->
                homeViewModeSelectOffered(companionActive, toggleAvailable, mode)
            }.toSet(),
        )
    }

    @Test
    fun `the predicate is DISCOVERED, never a device name`() {
        // Standing, 2026-08-31: capability is discovered, never declared. The input is "a companion
        // display is active", which any device can answer about itself, and not "this is a Thor" --
        // which would be an allow-list with one entry and would be wrong on the next handheld.
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt",
        ).readText().substringAfter("internal fun homeViewModeSelectOffered")
            .substringBefore("private fun homeViewModeSelectAvailable")

        listOf("thor", "Thor", "odin", "Odin", "ro.product", "Build.MODEL").forEach { declared ->
            assertFalse("the rule must not name a device: $declared", source.contains(declared))
        }
    }
}
