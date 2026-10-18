package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DEFAULT's well is ONE ellipse family.
 *
 *  Three
 * shapes were stacked on one centre — rings foreshortened at 0.42, a shaft built from `width * 0.46` by
 * `height * 0.33` (a different ratio at every panel aspect, 0.54 at 640x480), and a caustic that was a true
 * circle because `wash` draws circles. At one point in the field that reads as three unrelated objects.
 *
 * The property is not "the numbers are these numbers" — it is that every concentric feature shares one
 * foreshortening at every panel shape, which is what makes a tilted circle read as a tilted circle.
 *
 * The former pit created a dark halo around the sun. The pit (the sunk shaft this class used to pin) is gone; a body in space doesn't
 * need a hole under it. `theShaftEndsOnADrawnRingRatherThanNearOne` pinned the shaft's rim landing on a
 * drawn ring — with no shaft and no discrete rings left (the ring plane is a continuous particle band),
 * that property no longer exists to pin, so the test was removed rather than kept as a decoy asserting a
 * tautology. `everyConcentricFeatureSharesOneForeshortening` still covers every fraction that remains (the
 * caustic glow and the caustic core).
 */
class MimirWellGeometryTest {

    private val painterSource: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt")
        assertTrue("could not locate MimirSceneAtmosphereV2.kt", file.exists())
        // Comments stripped: this file's own KDoc quotes the shapes it removed, so a raw-source grep would
        // match the sentence explaining the fix. Same correction the visibility pin needed.
        file.readLines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun everyConcentricFeatureSharesOneForeshortening() {
        listOf(240f, 480f, 812f, 1080f).forEach { shortSide ->
            WellFeatureFractions.forEach { fraction ->
                val (rx, ry) = wellSemiAxes(shortSide, fraction)
                assertEquals(
                    "fraction $fraction at short side $shortSide is off the family's angle",
                    ry / rx,
                    wellSemiAxes(shortSide, WellFeatureFractions.first()).let { it.second / it.first },
                    1e-5f,
                )
            }
        }
    }

    @Test
    fun theSurfacesAreForeshortenedAndTheLightIsNot() {
        val painter = painterSource.substringAfter("fun DrawScope.drawWellSceneV2")
            .substringBefore("internal fun DrawScope.drawGoldenHall")
        // The obsolete `washOval(` pin was removed on 2026-09-02 with the small central glow. It asserted "the well's outer glow must be
        // foreshortened", on the reasoning that the glow belonged to the shaft and so had to share its
        // tilt. Both are now removed -- the shaft in round 3, the glow in the round that followed -- so
        // the assertion has no subject left. It is deleted rather than kept, because a pin that can only
        // ever pass is not a gate; the thing it guarded (a foreshortened feature drifting to a circle) is
        // covered for everything that remains by `everyConcentricFeatureSharesOneForeshortening`, which
        // now reads the ring band's two edges out of `WellFeatureFractions`.
        // 2026-09-02: the core moved to `novaDisc`, the Well's OWN round helper, to provide a
        // quasar-like diffusion that `washDisc`'s shared 3-stop ladder could not
        // give without changing it for the five other scenes that draw with it. `novaDisc` is a
        // `drawCircle` with a longer stop ladder -- still ROUND, which is the whole point of this pin.
        // Accepting either name keeps the assertion about GEOMETRY rather than about a function's name; the
        // oval check above and the `wash(` check below are untouched and still carry the rest.
        assertTrue(
            "the bright core must be round",
            Regex("""(washDisc|novaDisc|sunDisc)\(""").containsMatchIn(painter),
        )
        // `wash(` is the fraction-of-max(w,h) helper. Either replacement is fine; that one is not, because
        // its radius changes with the panel's ASPECT and would drift off the family's shared scale.
        assertTrue(
            "the core is back on max(w, h) and will drift with panel aspect",
            !Regex("""(?<![A-Za-z])wash\(""").containsMatchIn(painter),
        )
        // The full-width line is removed. A rule spanning the panel edge to edge competes with the
        // well for the full width of the field.
        assertTrue(
            "a full-bleed rule is back in the well",
            !painter.contains("Offset(size.width, size.height * 0.30f)"),
        )
    }
}
