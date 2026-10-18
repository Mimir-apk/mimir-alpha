package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A RetroAchievements Web API key (and any password) is case-exact and drawn as dots, so the keyboard must not
 * capitalise its first letter behind the player's back.
 */
class MimirKeyboardSecretShiftTest {

    @Test
    fun `an empty ordinary field opens shifted for sentence case`() {
        assertTrue(mimirKeyboardStartsShifted("", secret = false))
    }

    @Test
    fun `a secret field never opens shifted`() {
        assertFalse(mimirKeyboardStartsShifted("", secret = true))
        assertFalse(mimirKeyboardStartsShifted("abc", secret = true))
    }

    @Test
    fun `a field that already holds text opens unshifted`() {
        assertFalse(mimirKeyboardStartsShifted("abc", secret = false))
    }
}
