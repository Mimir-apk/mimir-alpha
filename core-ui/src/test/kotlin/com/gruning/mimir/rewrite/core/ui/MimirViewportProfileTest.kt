package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirViewportProfileTest {
    @Test
    fun `RG Rotate density variants resolve to the compact square posture`() {
        listOf(360f, 411f, 480f, 720f).forEach { side ->
            assertTrue("$side dp square", mimirViewportProfile(side, side).isCompactSquare)
        }
        assertTrue(mimirViewportProfile(360f, 384f).isCompactSquare)
    }

    @Test
    fun `certified landscape and large square surfaces retain standard geometry`() {
        listOf(
            700f to 350f,
            833f to 444f,
            538f to 444f,
            1080f to 1080f,
        ).forEach { (width, height) ->
            assertFalse("$width x $height", mimirViewportProfile(width, height).isCompactSquare)
        }
    }

    @Test
    fun `narrow portrait windows resolve compact portrait while wide portrait stays standard`() {
        assertTrue(mimirViewportProfile(360f, 640f).isCompact)
        assertTrue(mimirViewportProfile(360f, 640f).posture == MimirViewportPosture.COMPACT_PORTRAIT)
        assertFalse(mimirViewportProfile(600f, 1000f).isCompact)
        assertTrue(mimirViewportProfile(599f, 670f).isCompactSquare)
    }

    @Test
    fun `compact override preserves square identity and forces compact on a wide window`() {
        assertTrue(mimirViewportProfile(1080f, 1920f, MimirViewportMode.COMPACT).isCompact)
        assertTrue(mimirViewportProfile(1080f, 1920f, MimirViewportMode.COMPACT).isPortrait)
        assertTrue(mimirViewportProfile(1920f, 1080f, MimirViewportMode.COMPACT).isCompactSquare)
        assertFalse(mimirViewportProfile(1920f, 1080f, MimirViewportMode.AUTOMATIC).isCompact)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid viewport cannot silently select a profile`() {
        assertThrows(IllegalArgumentException::class.java) { mimirViewportProfile(0f, 360f) }
        mimirViewportProfile(Float.NaN, 360f)
    }
}
