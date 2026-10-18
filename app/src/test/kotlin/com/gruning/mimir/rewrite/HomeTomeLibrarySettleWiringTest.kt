package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two wiring sites that carry the Library's settle latch from the layer that owns the
 * opening to the shelf that consumes it.
 *
 * `HomeTomeLibraryOpenStageHandoffTest` (feature-library) already pins the BEHAVIOUR: with
 * `libraryStageHandedOff = false` the Library's art must not take stage priority. What no test covered is
 * that this host actually *supplies* that value.
 *
 * That gap is not cosmetic, and it is the reason this file exists. Both
 * consumers default to the pre-fix behaviour — `homeShelfTomeStageArt`'s `libraryStageHandedOff: Boolean =
 * true` and `HomeShelfScreen`'s `tomeLibraryOpenSettled: Boolean = true`. Those defaults are deliberate and
 * correct (an unwired caller must keep shipping behaviour rather than half a fix), but they mean a future
 * edit that drops either wiring site **silently restores the measured +31% backdrop flash**,
 * with every existing test still green. A defect that reappears with no red test is the expensive kind.
 *
 * Source-level pins, in the idiom `HomeTomeLibraryOpenStageHandoffTest` already established for this ticket:
 * the thing being asserted is a wiring fact, and there is no seam to assert it through at runtime without
 * standing up the whole host.
 */
class HomeTomeLibrarySettleWiringTest {

    private fun source(relativePath: String): String {
        val file = File(relativePath)
        assertTrue(
            "$relativePath not found at ${file.absolutePath} — if this file moved, move this pin with " +
                "it rather than deleting it.",
            file.exists(),
        )
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    private fun appHost(): String =
        source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt")

    @Test
    fun `the pixel-fade host reports the Library's arrival into the navigation latch`() {
        val code = appHost()
        assertTrue(
            "MimirDirectOverlayPixelFadeHost must pass onIncomingSettleChanged — without it the latch is " +
                "never written, homeShelfTomeStageArt falls back to its libraryStageHandedOff = true " +
                "default, and the backdrop flash returns with every test still green.",
            code.contains("onIncomingSettleChanged"),
        )
        assertTrue(
            "The settle callback must write bindings.navigation.homeTomeLibraryOpenSettled.",
            Regex("""homeTomeLibraryOpenSettled\s*=\s*settled""").containsMatchIn(code),
        )
        assertTrue(
            "The write must be gated on HOME_TOME_LIBRARY. CP01 ships beat 3 for exactly one kind; an " +
                "ungated write would latch on whichever overlay happened to arrive last.",
            Regex(
                """onIncomingSettleChanged[\s\S]{0,240}HOME_TOME_LIBRARY[\s\S]{0,240}""" +
                    """homeTomeLibraryOpenSettled""",
            ).containsMatchIn(code),
        )
    }

    @Test
    fun `the shelf is handed the latch rather than deriving its own`() {
        val code = appHost()
        assertTrue(
            "HomeShelfScreen must receive tomeLibraryOpenSettled from the navigation latch. If this call " +
                "site is dropped the parameter defaults to true and libraryArt regains unconditional stage " +
                "priority — the exact pre-CP01 behaviour, handing the shared stage over ~134 ms before the " +
                "surface that asked for it is visible.",
            Regex(
                """tomeLibraryOpenSettled\s*=\s*bindings\.navigation\.homeTomeLibraryOpenSettled""",
            ).containsMatchIn(code),
        )
    }
}
