package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B during a Tome open's glide: the shelf comes back from the alpha and travel it was painted at, on the return's pace,
 * never popping to full brightness at its seat. The host reads what it last drew, and Robolectric runs no draw pass
 * mid-animation, so this pins the branch; the emulator slow-motion pass (animator scale 5) checks the frames.
 */
class LibraryPageReturnReversalTest {

    private fun host(): String = listOf(
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText().substringAfter("internal fun <T> ImmediateLibrarySnapshotTransition(")

    @Test
    fun `a return onto a layer still leaving starts from its painted alpha and travel`() {
        val start = host().substringAfter("val incomingStart = remember(frame) {").substringBefore("\n    }\n")
        assertTrue(
            start.contains(
                "reversal && (entry != null || pageMotion is LibraryPageMotion.Return) ->\n" +
                    "                displayedLayerAlphas[frame.primaryLayerIndex] to displayedLayerTravel[frame.primaryLayerIndex]",
            ),
        )
    }

    @Test
    fun `and animates home instead of snapping`() {
        val body = host()
        assertTrue(body.contains("launch { incomingTravel.animateTo(0f, spec) }"))
        assertTrue(body.contains("launch { incomingAlpha.animateTo(1f, spec) }"))
    }
}
