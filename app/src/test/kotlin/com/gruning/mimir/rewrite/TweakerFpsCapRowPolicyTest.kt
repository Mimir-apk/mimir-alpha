package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerFpsCapRowPolicyTest {

    private val options = listOf(30, 60, 90, 120)

    @Test
    fun `no foreground game means no row at all`() {
        // The row must never exist without a subject. TweakerBridge retired the app-wide slot
        // precisely because a cap that names no game gets promoted onto whatever launches next.
        listOf(null, "", "   ").forEach { candidate ->
            assertEquals(
                "foregroundPackage=<$candidate>",
                TweakerFpsCapRow.NoSubject,
                tweakerFpsCapRow(candidate, options, selectedHz = 60),
            )
        }
    }

    @Test
    fun `a cap is always attributed to the foreground package`() {
        val row = tweakerFpsCapRow("com.example.game", options, selectedHz = 60)
        assertEquals(
            TweakerFpsCapRow.Available("com.example.game", 60, options),
            row,
        )
    }

    @Test
    fun `no offered limits reports unavailable rather than an empty stepper`() {
        assertEquals(
            TweakerFpsCapRow.Unavailable("com.example.game"),
            tweakerFpsCapRow("com.example.game", emptyList(), selectedHz = null),
        )
    }

    @Test
    fun `a stored cap the device no longer offers is not displayed`() {
        // Launch will not honour it, so showing it would claim a cap that never applies -- the same
        // fake-value trap the profile row avoids.
        val row = tweakerFpsCapRow("com.example.game", listOf(30, 60), selectedHz = 120)
        assertEquals(TweakerFpsCapRow.Available("com.example.game", null, listOf(30, 60)), row)
    }

    @Test
    fun `options outside the engine's own bound are refused`() {
        // TweakerGameFpsPolicy requires 30..120; anything else would be rejected on persist, so it
        // must never reach the stepper.
        val row = tweakerFpsCapRow("com.example.game", listOf(15, 30, 240), selectedHz = null)
        assertEquals(TweakerFpsCapRow.Available("com.example.game", null, listOf(30)), row)
    }

    @Test
    fun `options are deduplicated and ordered`() {
        val row = tweakerFpsCapRow("com.example.game", listOf(120, 30, 60, 60), selectedHz = null)
        assertEquals(listOf(30, 60, 120), (row as TweakerFpsCapRow.Available).optionsHz)
    }

    @Test
    fun `stepping walks off through every option and clamps at both ends`() {
        val row = tweakerFpsCapRow("com.example.game", options, selectedHz = null)
            as TweakerFpsCapRow.Available

        // Position 0 is "no cap", so unmodified behaviour is always reachable.
        assertNull(stepTweakerFpsCap(row, -1))
        assertEquals(30, stepTweakerFpsCap(row, 1))

        var current: Int? = null
        listOf(30, 60, 90, 120).forEach { expected ->
            current = stepTweakerFpsCap(row.copy(selectedHz = current), 1)
            assertEquals(expected, current)
        }
        // Clamps at the top rather than wrapping to off, so a press cannot silently remove a cap.
        assertEquals(120, stepTweakerFpsCap(row.copy(selectedHz = 120), 1))
        assertEquals(90, stepTweakerFpsCap(row.copy(selectedHz = 120), -1))
    }

    @Test
    fun `stepping by zero holds the selection`() {
        val row = tweakerFpsCapRow("com.example.game", options, selectedHz = 60)
            as TweakerFpsCapRow.Available
        assertEquals(60, stepTweakerFpsCap(row, 0))
    }

    @Test
    fun `every state has a distinct value label`() {
        val labels = listOf(
            tweakerFpsCapValueLabel(TweakerFpsCapRow.NoSubject),
            tweakerFpsCapValueLabel(TweakerFpsCapRow.Unavailable("com.example.game")),
            tweakerFpsCapValueLabel(TweakerFpsCapRow.Available("com.example.game", null, options)),
            tweakerFpsCapValueLabel(TweakerFpsCapRow.Available("com.example.game", 60, options)),
        )
        assertEquals(labels.size, labels.toSet().size)
        assertTrue(labels.none(String::isBlank))
        assertEquals("60 FPS", labels.last())
    }

    @Test
    fun `the supporting line names the game the cap belongs to`() {
        val row = TweakerFpsCapRow.Available("com.example.game", 60, options)
        assertTrue(tweakerFpsCapSupportingText(row, "Tears of the Kingdom").contains("Tears of the Kingdom"))
        // Falling back to the package is still naming a subject; a generic line would not be.
        assertTrue(tweakerFpsCapSupportingText(row, null).contains("com.example.game"))
        assertTrue(tweakerFpsCapSupportingText(row, "  ").contains("com.example.game"))
    }
}
