package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue bar's ramp is DECORATION, not a reading — policy 2026-08-31.
 *
 * A source pin rather than a render test, because the defect this guards is structural: the bar used
 * to be a fill `Box` sized `fillMaxWidth(fraction)`, and a gradient on that `Box` would span the
 * FRACTION rather than the track. At 10% the whole ramp squeezes into a tenth of the bar and the ink
 * at the leading edge changes as the job advances — a colour that moves with a value, which is §4's
 * threshold CLAIM by another route. The shape invited it; the shape is gone.
 */
class FileBrowserQueueProgressBarTest {

    private val source =
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserQueueSurface.kt")
            .readText()
            .substringAfter("private fun FileBrowserQueueProgressBar")
            .substringBefore("\n}\n")

    @Test
    fun `the ramp spans the track, never the fraction`() {
        assertTrue("the fill must carry a gradient", source.contains("Brush.horizontalGradient"))
        assertTrue("spanning the TRACK's width", source.contains("endX = size.width"))
        assertFalse(
            "a gradient bounded by the fraction would make its ink a verdict on the value",
            Regex("(startX|endX)\\s*=\\s*[^,\\n]*fraction").containsMatchIn(source),
        )
    }

    @Test
    fun `the fill is no longer a Box whose width is the fraction`() {
        // The old shape is what made the fraction-bounded gradient the obvious next edit.
        assertFalse(
            "sizing the fill by fillMaxWidth(fraction) invites a gradient across the fraction",
            source.contains("fillMaxWidth(fraction"),
        )
    }

    @Test
    fun `nothing here animates forever`() {
        listOf("infiniteRepeatable", "rememberInfiniteTransition").forEach {
            assertFalse("$it belongs to the indeterminate bar, not to a determinate one", source.contains(it))
        }
    }
}
