package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [mimirIncomingOverlayHoldMs] — CP01
 * , the open-direction mirror
 * of [mimirOutgoingChromeHoldMs] pinned by [OutgoingChromeHoldTest].
 *
 *  [MimirOverlayKind.HOME_TOME_LIBRARY]
 * paints its own opaque ground (`HomeTomeLibraryOverlay.kt`'s `VideoBackdrop` floor) while wearing the
 * glass-panel open grammar on its other two channels, so the unheld open ran the scene's blur ramp and
 * the panel's alpha ramp concurrently -- measured on Thor as a double exposure (sharpness 6.1-8.0x below
 * settled; `tesseract` reading BOTH the shelf's and the Library's vocabulary in one frame).
 *
 * **Ships with exactly ONE non-zero member**, deliberately: [mimirOutgoingChromeHoldMs]'s own kdoc
 * records that guessing this per-kind from list membership "is what put this defect here in the first
 * place." This test is exhaustive over the enum for the same reason `CredentialFlowGlassContinuityTest`
 * and `OutgoingChromeHoldTest` are: a new overlay kind must land on the explicit default (0, no hold) and
 * this test must say so out loud, rather than silently inheriting a hold nobody chose for it.
 */
class IncomingOverlayHoldTest {

    @Test
    fun `the Tome Library holds its incoming pixels at alpha 0 while the scene softens`() {
        assertEquals(
            "310 = OverlayDissolveCoverMs (130) + OverlayMs (180), the mirror of the reference 311ms " +
                "return (DEPART 180 - HOLD 130 concurrent - CLEAR 180). Symmetric starting value, per " +
                "spec section 3 -- not itself measured for this direction.",
            MimirMotion.OverlayDissolveCoverMs,
            mimirIncomingOverlayHoldMs(MimirOverlayKind.HOME_TOME_LIBRARY),
        )
    }

    @Test
    fun `an empty stack holds nothing`() {
        assertEquals(0, mimirIncomingOverlayHoldMs(null))
    }

    @Test
    fun `every other overlay kind holds nothing -- a per-kind predicate, not a general rule`() {
        val changed = setOf(MimirOverlayKind.HOME_TOME_LIBRARY)
        (MimirOverlayKind.entries - changed).forEach { kind ->
            assertEquals(
                "$kind must keep NO incoming hold. CP01 ships one member; which other glass-allowlist " +
                    "kinds paint their own ground rather than borrowing the scene is an OWED audit, not " +
                    "something this function may guess at.",
                0,
                mimirIncomingOverlayHoldMs(kind),
            )
        }
    }
}
