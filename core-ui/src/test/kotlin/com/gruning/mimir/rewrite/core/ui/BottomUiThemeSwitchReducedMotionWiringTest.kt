package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A theme switch under reduced motion is a deliberate cut. The theme line says so (`transition=none reason=reduced_motion`), so the
 * backdrop must write no begin or end around a fade that never ran: those would be false receipts. The backdrop is a composable
 * drawing routine that a JVM test cannot drive, so the wiring is pinned as source, the way the module's other backdrop pins are.
 */
class BottomUiThemeSwitchReducedMotionWiringTest {
    private val code = File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirAtmosphere.kt").also {
        assertTrue("MimirAtmosphere.kt not found at ${it.absolutePath} -- move this pin with it", it.isFile)
    }.readText()

    @Test
    fun `the backdrop reports its crossfade only through the helper that skips a cut`() {
        val reported = Regex("""bottomUiTransition\("theme-switch\.crossfade"""").findAll(code).count()
        assertEquals("the one reporting site is the helper", 1, reported)
        val helper = code.substringAfter("private suspend fun runReportedSceneCrossfade(").substringBefore("\n}\n")
        assertTrue("the helper cuts without a receipt under reduced motion", helper.contains("if (reducedMotion) {"))
        assertTrue(
            "and reports the crossfade otherwise",
            helper.substringAfter("} else {").contains("bottomUiTransition(\"theme-switch.crossfade\", receiptDisplay)"),
        )
    }

    @Test
    fun `both places the backdrop fades go through that helper with the reduced-motion flag`() {
        val calls = Regex("""runReportedSceneCrossfade\(reducedMotion, receiptDisplay, swap, fadeFrameNanos\)""").findAll(code).count()
        assertEquals("the off-thread landing and the plain crossfade step", 2, calls)
    }
}
