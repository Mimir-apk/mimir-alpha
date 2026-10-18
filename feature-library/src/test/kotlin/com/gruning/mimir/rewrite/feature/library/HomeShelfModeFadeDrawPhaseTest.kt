package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recomposition regression: Home read the mode switch's fade in composition, so every frame
 * of the switch recomposed the whole shelf (about ten times per 160 ms switch, each likely over a frame on a T618).
 * The fade is now a lambda read only in draw-phase and deferred blocks.
 */
class HomeShelfModeFadeDrawPhaseTest {

    private val shelf: String by lazy {
        listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt"),
        ).first(File::isFile).readText()
    }

    @Test
    fun `the switch's fade is read only through a lambda`() {
        assertTrue(shelf.contains("val surfaceFadeHero: () -> Float = remember(surfaceFade) { { surfaceFade.value } }"))
        // Two reads, and only one of them is the lambda's. The other is the arrival effect's, once, at the start of a
        // run (alpha audit F5): a coroutine asking what is painted, so a re-run carries the band on. It is not a
        // composition read, and the count below is what keeps a third from appearing.
        assertEquals("surfaceFade.value is read twice", 2, Regex("""surfaceFade\.value""").findAll(shelf).count())
        val effect = shelf.substringAfter("LaunchedEffect(surfaceEpoch, reducedMotion, itemKeys) {").substringBefore("val surfaceFadeHero")
        assertEquals("and the second is inside the arrival's effect", 1, Regex("""surfaceFade\.value""").findAll(effect).count())
        assertTrue(effect.contains("val carryOn = homeArrivalCarriesOn(modeReturningAlpha, surfaceFade.value)"))
        // Batch D: the title and the band hand over through MimirPageHandoff, which reads the lambda in its draw.
        assertEquals("both handoffs take the lambda", 2, Regex("incomingAlpha = surfaceFadeHero,").findAll(shelf).count())
    }

    @Test
    fun `every read of the fade sits in a graphicsLayer or a deferred lambda`() {
        val lines = shelf.lines()
        lines.forEachIndexed { index, line ->
            if (!line.contains("surfaceFadeHero()") || line.trim().startsWith("//")) return@forEachIndexed
            val context = lines.subList(maxOf(0, index - 4), index + 1).joinToString("\n")
            assertTrue(
                "line ${index + 1} reads the fade outside a draw or deferred block:\n$context",
                line.contains("= {") || line.contains("graphicsLayer {") || context.contains("graphicsLayer {"),
            )
        }
    }
}
