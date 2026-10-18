package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerRoutePolicyTest {

    @Test
    fun `paired tweaker opens without privileged certification`() {
        assertEquals(
            TweakerRouteDecision.OPEN,
            tweakerRouteDecision(tweakerPaired = true),
        )
    }

    @Test
    fun `an unpaired device is never prompted for authority it cannot use`() {
        assertEquals(TweakerRouteDecision.UNAVAILABLE, tweakerRouteDecision(tweakerPaired = false))
    }

    @Test
    fun `every decision carries a distinct value and supporting line`() {
        val values = TweakerRouteDecision.entries.map(::tweakerRouteValueLabel)
        val supporting = TweakerRouteDecision.entries.map(::tweakerRouteSupportingText)
        assertEquals("row values must not collide", values.size, values.toSet().size)
        assertEquals("supporting copy must not collide", supporting.size, supporting.toSet().size)
        assertTrue(values.none(String::isBlank))
        assertTrue(supporting.none(String::isBlank))
    }

}
