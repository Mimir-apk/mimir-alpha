package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfViewportEvidenceTest {

    @Test
    fun `aspect ratio is derived from the post-inset pixels`() {
        // The spec's own Thor primary observation: 1920x1025 post-inset landscape bounds at 369 dpi.
        val record = ShelfViewportEvidence.of(
            displayRole = DisplayRole.PRIMARY,
            postInsetWidthPx = 1920,
            postInsetHeightPx = 1025,
            density = 2.30625f,
            densityDpi = 369,
            xDpi = 318.976f,
            yDpi = 320.842f,
            refreshRateHz = 120.00001f,
        )

        assertEquals(1920f / 1025f, record.aspectRatio, 0.0001f)
    }

    @Test
    fun `zero height cannot divide by zero`() {
        val record = ShelfViewportEvidence.of(
            displayRole = DisplayRole.COMPANION,
            postInsetWidthPx = 1240,
            postInsetHeightPx = 0,
            density = 2.30625f,
            densityDpi = 369,
            xDpi = 318.976f,
            yDpi = 320.842f,
            refreshRateHz = 60f,
        )

        assertEquals(0f, record.aspectRatio, 0f)
    }

    @Test
    fun `evidence line carries every field and is grep-stable`() {
        val line = ShelfViewportEvidence.of(
            displayRole = DisplayRole.PRIMARY,
            postInsetWidthPx = 1920,
            postInsetHeightPx = 1025,
            density = 2.30625f,
            densityDpi = 369,
            xDpi = 318.976f,
            yDpi = 320.842f,
            refreshRateHz = 120f,
        ).evidenceLine()

        assertTrue(line.startsWith("shelf-viewport role=PRIMARY "))
        assertTrue("postInsetPx=1920x1025" in line)
        assertTrue("dpi=369" in line)
        assertTrue("refreshHz=120.0" in line)
    }
}
