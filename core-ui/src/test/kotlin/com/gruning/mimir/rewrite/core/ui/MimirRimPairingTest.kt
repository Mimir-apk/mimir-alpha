package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The rim trick's paired passes must keep sharing one geometry.**
 *
 *
 *
 * Three scenes now light a mass by drawing the SAME geometry twice — once displaced toward the light in
 * the light's colour, then again unoffset and dark, so a lit crescent survives exactly along the edge that
 * faces the source. Its fragile precondition is the word
 * SAME: if either pass is ever pointed at a different lobe table, or one half is retuned and the other is
 * not, the rim silently detaches from the mass it is supposed to be lighting.
 *
 * **Nothing else would catch that.** It compiles, it draws, no existing gate looks at it, and on a
 * screenshot a detached rim reads as a slightly odd highlight rather than as a defect — which is exactly
 * the class of error this file has shipped repeatedly. Hence a pin.
 *
 * Source-level, in the idiom `MimirWellGeometryTest` established for this file: the property is a wiring
 * fact between two call sites, and there is no runtime seam to assert it through without standing up the
 * whole painter.
 */
class MimirRimPairingTest {

    private val painter: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt")
        assertTrue(
            "could not locate MimirSceneAtmosphereV2.kt at ${file.absolutePath} — if it moved, move this " +
                "pin with it rather than deleting it.",
            file.exists(),
        )
        // Comments stripped: this file's KDoc discusses the technique by name, and a raw grep would match
        // the prose explaining it rather than the calls doing it.
        file.readLines().joinToString("\n") { it.substringBefore("//") }
    }

    private fun scene(name: String, next: String): String =
        painter.substringAfter("fun DrawScope.$name").substringBefore("internal fun DrawScope.$next")

    @Test
    fun `Freyr's rim and foreground passes read one lobe table`() {
        val body = scene("drawGoldenHallSceneV2", "drawForgeSceneV2")
        val tables = Regex("""freyrCloudPlane\(\s*Freyr\w+Plane,\s*(Freyr\w+Lobes)""")
            .findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("Freyr should draw more than one cloud plane; found $tables", tables.size >= 4)
        assertEquals(
            "The rim pass and the foreground pass must read the SAME lobe table. Freyr's planes are " +
                "$tables — if the rim no longer shares its geometry with the mass it lights, the lit " +
                "crescent detaches and lands somewhere the cloud is not.",
            tables.count { it == "FreyrRimLobes" },
            2,
        )
    }

    @Test
    fun `Mani draws its cloud bank twice from one lobe table`() {
        val body = scene("drawMoonlitSceneV2", "drawGoldenHallSceneV2")
        assertEquals(
            "Máni's bank is a rim pass followed by the dark mass, both iterating MoonCloudLobes. Two " +
                "loops are required: one alone is a bank with no rim, and two over different tables is a " +
                "rim that lights nothing.",
            2,
            Regex("""for\s*\(lobe in MoonCloudLobes\)""").findAll(body).count(),
        )
        assertTrue(
            "The rim pass must be lifted toward the moon by MoonRimLift — a rim with no offset is just " +
                "the mass drawn twice, and the crescent that makes it read as light disappears.",
            body.contains("MoonRimLift"),
        )
    }

    @Test
    fun `Baldr lights its clouds from the same geometry it darkens them with`() {
        val body = scene("drawSunlitSceneV2", "drawMoonlitSceneV2")
        val tables = Regex("""baldrCloudPlane\(\s*Baldr\w+Plane,\s*(Baldr\w+Lobes)""")
            .findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(
            "Baldr should draw a layered cloud stack; found $tables",
            tables.size >= 3,
        )
        assertEquals(
            "Baldr's rim pass and its foreground mass must read one lobe table. Planes found: $tables.",
            2,
            tables.count { it == "BaldrRimLobes" },
        )
    }

    @Test
    fun `the light source is drawn before the mass that occludes it`() {
        // The method doc's first rule, and the one that is cheapest to lose in a reorder: light drawn OVER
        // the thing it lights is a tint; light drawn UNDER it is a source. Each of these scenes depends on
        // its source appearing earlier in the painter than the clouds in front of it.
        val freyr = scene("drawGoldenHallSceneV2", "drawForgeSceneV2")
        assertTrue(
            "Freyr's backlight must be drawn before its cloud planes, or the clouds stop occluding it " +
                "and the scene flattens.",
            freyr.indexOf("FreyrLightX") in 0 until freyr.indexOf("freyrCloudPlane("),
        )
        val baldr = scene("drawSunlitSceneV2", "drawMoonlitSceneV2")
        assertTrue(
            "Baldr's sun must be drawn before its cloud planes. Having nothing in front of the sun is " +
                "precisely why that scene read flat before the stack was added.",
            baldr.indexOf("BaldrSunRadius") in 0 until baldr.indexOf("baldrCloudPlane("),
        )
    }
}
