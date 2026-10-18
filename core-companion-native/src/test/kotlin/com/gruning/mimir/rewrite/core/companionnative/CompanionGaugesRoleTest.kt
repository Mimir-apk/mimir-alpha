package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Bottom shelf owns Tweaker; the native secondary M menu does not duplicate that action. */
class CompanionGaugesRoleTest {

    @Test
    fun `the secondary menu leaves Tweaker to the Bottom shelf`() {
        val secondary = companionOverlayMenuRows(
            CompanionOverlayRole.SECONDARY_COMPANION,
            secondaryReturnAvailable = true,
        ).map { it.item }
        val single = companionOverlayMenuRows(CompanionOverlayRole.PRIMARY_SINGLE).map { it.item }
        assertFalse("the bottom M menu does not duplicate Tweaker", QuickMenuItem.TWEAKER in secondary)
        assertTrue("the single-screen menu keeps Tweaker", QuickMenuItem.TWEAKER in single)
    }

    @Test
    fun `its row names the primary destination`() {
        val primaryRow = companionOverlayMenuRows(CompanionOverlayRole.PRIMARY_SINGLE)
            .first { it.item == QuickMenuItem.TWEAKER }
        assertEquals(R.string.companion_menu_tweaker, primaryRow.titleRes)
    }

    @Test
    fun `return row is fail closed until a foreign host lease is present`() {
        assertFalse(
            companionOverlayMenuRows(CompanionOverlayRole.SECONDARY_COMPANION)
                .any { it.item == QuickMenuItem.SECONDARY_RETURN },
        )
        assertTrue(
            companionOverlayMenuRows(
                CompanionOverlayRole.SECONDARY_COMPANION,
                secondaryReturnAvailable = true,
            ).any { it.item == QuickMenuItem.SECONDARY_RETURN },
        )
    }
}
