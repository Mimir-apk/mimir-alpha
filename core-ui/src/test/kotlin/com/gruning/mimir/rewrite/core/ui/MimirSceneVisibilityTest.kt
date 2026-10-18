package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The pin that would have caught the v2 scenes being invisible.**
 *
 * The scene-visibility measurement showed a
 * maximum change of 7/255 per channel on a set of painters using alphas up to 0.96. The design review
 * found two causes, and both are testable here without rendering a single frame:
 *
 * 1. **Structure drawn in TEXTURE materials.** `shelfSheen` and `shelfThread` carry a baked 2–5% alpha
 *    because they are grain and thread, not architecture. Used raw — without `.copy(alpha = …)` — they
 *    draw at 8–12/255 and vanish. The Well's rim and the Golden Hall's entire roof were doing exactly
 *    that, and the rim's ~7/255 line was, on measurement, the loudest thing that scene drew.
 * 2. **Headline alphas spent on a colour equal to the field.** Hel's veils were
 *    `scene.shadow.copy(alpha = 0.86f)` where `shadow`, `surfaceBase`, `signatureTop` and `fieldFooter`
 *    are ALL `#020102`. 0.86 of a colour over itself is a delta of exactly zero.
 *
 * A render test would catch both, but needs Robolectric plus a Compose harness for something that is
 * fundamentally arithmetic. These are the same two properties, asserted where they actually live.
 */
class MimirSceneVisibilityTest {

    private val painterSource: String by lazy {
        val f = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt")
        assertTrue("could not locate MimirSceneAtmosphereV2.kt from ${File(".").absolutePath}", f.exists())
        // Comments are stripped before matching. The fix's own KDoc EXPLAINS the banned pattern and so
        // contains it verbatim — a grep pin reading raw source fails on the sentence describing the
        // fix. Same correction the launch-probe pin and the gallery caption pin both needed.
        f.readLines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun noSceneDrawsStructureInARawTextureMaterial() {
        // `shelfSheen` / `shelfThread` are grain and thread: their alpha is BAKED LOW on purpose. Any
        // use that is not immediately followed by an explicit alpha is a structural element being drawn
        // at 8–12/255, which is the exact defect that made these scenes invisible.
        val offenders = Regex("""scene\.(shelfSheen|shelfThread)(?!\s*\.copy\s*\(\s*alpha)""")
            .findAll(painterSource)
            .map { it.value }
            .toList()
        assertTrue(
            "a scene painter uses a texture material raw, at its baked 2-5% alpha: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun everySceneHasAStructuralInkItCanActuallyBeSeenIn() {
        // If a theme's structural inks collapse onto its own field, no painter written in them can read
        // — the Hel case, generalised. Every identity must offer at least one ink with real separation
        // from the surface it is drawn on.
        MimirThemeId.entries.forEach { id ->
            val palette = MimirThemePalette.forId(id)
            val field = palette.surfaceBase
            val inks = listOf(
                "accent" to palette.accent,
                "accentBright" to palette.accentBright,
                "highlight" to palette.materials.scene.highlight,
                "particle" to palette.materials.scene.particle,
            )
            val best = inks.maxOf { (_, c) -> channelDistance(c, field) }
            assertTrue(
                "$id has no structural ink separated from its own field (best delta $best/255) — " +
                    "any scene painted in these would be invisible",
                best >= 40,
            )
        }
    }

    @Test
    fun helIsTheCaseThatProvesTheRule() {
        // Recorded as a FACT, not a complaint. Hel's shadow really is its field, so a painter that
        // fills with `shadow` there draws nothing at all — and this is the arithmetic that made the
        // file's boldest alpha the least visible thing in it.
        val hel = MimirThemePalette.forId(MimirThemeId.entries.first { it.name.contains("HEL", true) })
        assertEquals(
            "Hel's shadow and field are the same colour; filling with shadow there is a no-op",
            0,
            channelDistance(hel.materials.scene.shadow, hel.surfaceBase),
        )
        // And its structural warmth is genuinely separated, which is why the wound had to carry it.
        assertTrue(channelDistance(hel.accent, hel.surfaceBase) >= 40)
    }

    /** Largest per-channel distance, ignoring alpha — the thing a screenshot diff measures. */
    private fun channelDistance(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Int {
        fun ch(v: Float) = (v * 255f).toInt()
        return maxOf(
            kotlin.math.abs(ch(a.red) - ch(b.red)),
            kotlin.math.abs(ch(a.green) - ch(b.green)),
            kotlin.math.abs(ch(a.blue) - ch(b.blue)),
        )
    }
}
