package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [mimirOutgoingChromeHoldMs] — the fix for the C2 teardown defect.
 *
 *  The perfetto
 * capture found that on Back the file manager's subtree is disposed in ONE frame while the scene's 18dp
 * blur was held for a further `MimirMotion.OverlayMs` waiting out a cross-fade that drew nothing — then
 * took another OverlayMs to clear. Measured on the Odin 3: the browser was gone at +30.7ms and the
 * blur did not begin unwinding until **+221.8ms**. After the fix, +37.9ms.
 *
 * Then a 60fps pixel + `tesseract` pass showed that releasing the hold instead put TWO complete UIs on
 * screen for ~50ms, establishing the dissolve-then-clear sequence. Three things are pinned here:
 *
 *  1. The browser HOLDS the chrome for its dissolve, so the backdrop stays uniform and no frame ever
 *     carries two detailed images. It keeps no special case — it falls through to the same `else` as
 *     everything else.
 *  2. It IS drawn by the pixel-fade host, so that dissolve exists at all. That arm's absence was the
 *     single root cause of the dead 180ms, the missing exit, and an empty animating layer.
 *  3. Every other overlay's hold is BIT-IDENTICAL to what it was before this function existed.
 *
 * (2) is not padding. The obvious general rule — "release unless the pixel-fade host draws it" — is wrong:
 * `HOME_TILE_MENU` is not drawn by that host either, yet it fades itself out over `HomeOverlayDissolveMs`
 * via `AnimatedVisibility`, so releasing the blur early would clear the scene out from under a still-
 * visible menu. That regression was caught before it shipped and this test is what keeps it caught.
 */
class OutgoingChromeHoldTest {

    @Test
    fun `the browser IS drawn by the pixel fade host, so its exit is pixels and not a cut`() {
        assertTrue(
            "MimirDirectOverlayContent now has a FOLDER_BROWSER arm, so membership is truthful: the host " +
                "records the browser's pixels and fades them out after its composition is disposed. " +
                "Between the C2 fix and that arm landing this was deliberately FALSE, because membership " +
                "without an arm is worse than absence -- an empty layer and a 180ms wait for nothing.",
            mimirDirectOverlayUsesPixelFade(MimirOverlayKind.FOLDER_BROWSER),
        )
    }

    @Test
    fun `the browser's return uses a 130 ms hold then a 180 ms clear`() {
        // A former exception cleared glass in one frame beneath a still-covering browser: the one close in the
        // product with no dissolve-then-clear. The browser holds the chrome for its dissolve like every other overlay, and the
        // glass clears on the house ramp after it.
        assertEquals(MimirMotion.OverlayDissolveCoverMs, mimirOutgoingChromeHoldMs(MimirOverlayKind.FOLDER_BROWSER))
        assertEquals(
            "the browser departs on the same clock as Utility (review 2026-10-15, Q3 siblings matched); the 130 ms cover was measured against 180",
            MimirMotion.OverlayMs,
            mimirDirectOverlayDepartMs(MimirOverlayKind.FOLDER_BROWSER),
        )
        // Utility and the browser close on the same glass: the same hold, the same (default) clear. The scene's clear has no
        // per-kind clock left, and the host feeds rememberMimirGlassProgress no closeMs of its own.
        assertEquals(mimirOutgoingChromeHoldMs(MimirOverlayKind.BRIGHTNESS), mimirOutgoingChromeHoldMs(MimirOverlayKind.FOLDER_BROWSER))
        val host = hostSource()
        assertFalse("no per-kind glass clear clock", host.contains("fun mimirSceneGlassCloseMs("))
        assertFalse(host.contains("closeMs = mimirSceneGlassCloseMs("))
        assertEquals(MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(MimirOverlayKind.HOME_TOME_LIBRARY))
        assertEquals(MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(null))
    }

    private fun hostSource(): String = listOf(
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
    ).first(java.io.File::isFile).readText()

    @Test
    fun `the tile menu keeps its hold - it fades ITSELF out and the blur must outlast that`() {
        assertEquals(
            "HOME_TILE_MENU is absent from the pixel-fade host too, but AnimatedVisibility's " +
                "exit = fadeOut(HomeOverlayDissolveMs) keeps its pixels on screen. Releasing the chrome " +
                "early would sharpen the scene under a menu that is still visible.",
            MimirMotion.OverlayDissolveCoverMs,
            mimirOutgoingChromeHoldMs(MimirOverlayKind.HOME_TILE_MENU),
        )
        assertFalse(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.HOME_TILE_MENU))
    }

    @Test
    fun `the launch card closes on the house clock and holds the chrome like every other overlay`() {
        // Motion audit batch F (2026-09-28): the 120 ms close retired. The card's return is the standard 311 ms:
        // dissolve 180, hold 130, then clear 180.
        assertEquals(MimirMotion.OverlayMs, HomeLaunchCardReturnDissolveMs)
        assertEquals(
            MimirMotion.OverlayDissolveCoverMs,
            mimirOutgoingChromeHoldMs(MimirOverlayKind.HOME_LAUNCH_CARD),
        )
    }

    @Test
    fun `an empty stack holds nothing`() {
        assertEquals(0, mimirOutgoingChromeHoldMs(null))
    }

    @Test
    fun `every other overlay kind keeps the flat OverlayMs hold it had before this function existed`() {
        // Batch F: no kind keeps a hold of its own any more; the launch card joined the flat hold.
        // The equalizer editor (2026-10-14) leaves on MimirEqualizerEditorFadeHost's pixel fade, so the scene stays blurred behind
        // it for the flat hold like every other overlay; it is not on the DIRECT host (it hosts itself).
        assertEquals(MimirMotion.OverlayDissolveCoverMs, mimirOutgoingChromeHoldMs(MimirOverlayKind.EQUALIZER_EDITOR))
        assertFalse(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.EQUALIZER_EDITOR))
        // Exhaustive over the enum, the same discipline CredentialFlowGlassContinuityTest uses: a new
        // overlay kind lands on the pre-existing default and this test says so out loud, rather than the
        // kind silently inheriting a hold nobody chose for it. FOLDER_BROWSER is in the set again under the swap-tier policy.
        MimirOverlayKind.entries.forEach { kind ->
            assertEquals(
                "$kind's chrome hold is the flat OverlayDissolveCoverMs, including FOLDER_BROWSER, " +
                    "which briefly held 0 until the pixel check showed the hold was earned rather than " +
                    "wasted. The cover is deliberately SHORTER than the OverlayMs dissolve it covers -- " +
                    "see that constant's KDoc for the alpha curve and the two instruments behind 130ms.",
                MimirMotion.OverlayDissolveCoverMs,
                mimirOutgoingChromeHoldMs(kind),
            )
        }
    }
}
