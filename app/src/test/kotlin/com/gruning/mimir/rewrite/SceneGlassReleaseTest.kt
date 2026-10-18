package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The scene blur is released under the Tome Library's opaque ground — and nowhere else.**
 *
 *  The return lands on the Tome Library,
 * so a capture of the return is mostly a capture of that surface's STANDING cost. Measured on the Thor
 * with nothing moving at all, `dumpsys gfxinfo` over 10 s:
 *
 * | idle surface | jank | p50 | RenderThread `issue` |
 * |---|---|---|---|
 * | Home shelf | 0.00% | 5 ms | 1.5 ms |
 * | Folio | 0.00% | 5 ms | — |
 * | Tome Library | **56–64%** | **14–15 ms** | **8.5 ms** |
 * | Tome Library, blur released | **1.78%** | **5 ms** | **1.6 ms** |
 *
 * The UI thread is not involved (0.3 ms across input + anim + layout + record); the cost is entirely the
 * RenderThread re-rendering an 18 dp full-screen `BlurEffect` it cannot cache, because the layer under
 * it keeps invalidating. The identical blur under `HOME_LAUNCH_CARD` and `FOLDER_BROWSER`, over the same
 * Home shelf, measured 0.00% — there the scene beneath is static and the layer is reused.
 *
 * **Why releasing it is invisible.** `VideoBackdrop`'s floor is opaque and unconditional, and its own
 * comment says it *"is the only thing that takes the shelf to zero"*, fully hiding the Home shelf. Verified by capture rather than by that argument: against a
 * noise floor of exactly zero (two shipping captures across a reinstall and a cold start are
 * byte-identical), the released build differs on **0.223% of pixels, mean delta 1.59/255, all inside one
 * 97×98 box** — a sampling variation on a cartridge thumbnail, indistinguishable at 3×. Blur bleed would
 * be broad and full-screen.
 *
 * This pin exists because both halves of the predicate are load-bearing and each fails silently and in a
 * different direction — see the two tests below.
 */
class SceneGlassReleaseTest {

    @Test
    fun `the blur is released only once the tome library has landed`() {
        assertTrue(
            "a settled Tome Library paints its own opaque ground, so the scene blur beneath it is work " +
                "with no pixels to show for it — 8.5 ms of RenderThread issue per frame on the Thor",
            mimirSceneGlassSuppressed(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                tomeLibraryOpenSettled = true,
                arrivalEpoch = 4L,
                epochAtActivation = 3L,
            ),
        )
        assertFalse(
            "DURING the arrival the ground is still translucent, so the scene is genuinely visible and " +
                "the blur is the retained CP01 arrival beat. Suppressing it here would " +
                "delete the arrival transition, and the frame cost being removed is not even present " +
                "yet — the whole win is in the SETTLED window.",
            mimirSceneGlassSuppressed(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                tomeLibraryOpenSettled = false,
                arrivalEpoch = 4L,
                epochAtActivation = 3L,
            ),
        )
    }

    /**
     * `homeTomeLibraryOpenSettled` is deliberately NOT reset on close (its own kdoc: resetting it would
     * un-gate the stage priority during the close-hold and reintroduce a flash on the RETURN). So the
     * latch alone still reads `true` while the overlay is dissolving, and only `activeOverlay` — which
     * flips at CANCEL, ahead of `presentedOverlayChrome` — can restore the blur for that window.
     */
    @Test
    fun `the blur returns for the close dissolve, when the latch alone still says settled`() {
        assertFalse(
            "at CANCEL the overlay is no longer active but its settle latch still reads true. Keeping " +
                "the blur suppressed here would leave the destination SHARP under a dissolving overlay " +
                "— two detailed images compositing, the double exposure mimirOutgoingChromeHoldMs was " +
                "written to prevent.",
            mimirSceneGlassSuppressed(
                activeOverlay = null,
                tomeLibraryOpenSettled = true,
                arrivalEpoch = 4L,
                epochAtActivation = 3L,
            ),
        )
    }

    /**
     * The release is scoped to ONE kind on purpose. Every other overlay on [mimirOverlayUsesGlass] is
     * translucent to some degree, so the blurred scene is part of its settled look — and two of them
     * were measured idling at 0.00 % jank with the blur live, so there is nothing to win and a look to
     * lose. A new kind must be classified deliberately, not inherit this.
     */
    @Test
    fun `no other overlay kind releases the scene glass`() {
        val offenders = MimirOverlayKind.entries
            .filter { it != MimirOverlayKind.HOME_TOME_LIBRARY }
            .filter {
                mimirSceneGlassSuppressed(
                    it,
                    tomeLibraryOpenSettled = true,
                    arrivalEpoch = 4L,
                    epochAtActivation = 3L,
                )
            }
        assertEquals(
            "only HOME_TOME_LIBRARY paints an unconditional opaque ground. For any other kind the " +
                "blurred scene is visible through the panel and IS its settled appearance: releasing it " +
                "there is a look change nobody asked for, on surfaces already measured at 0.00% jank.",
            emptyList<MimirOverlayKind>(),
            offenders,
        )
    }

    /**
     * **The folio return, which is where gating on the latch alone was visibly wrong.**
     *
     * `homeTomeLibraryOpenSettled` is deliberately never reset on close, so between this overlay
     * becoming active again and its arrival effect firing its own false-edge, it still reads `true` from
     * before the Folio was opened. Suppressing on that reading put the blur off *during* the return
     * crossfade — the sharp-destination peek the `mimirOverlayUsesGlass` allowlist records as a
     * twice-reported defect, arriving through a new door. The window is one composition-to-effect
     * gap, so about one frame of a 180 ms crossfade.
     *
     * **How visible that frame was is unmeasured, deliberately stated.** A burst capture of the return
     * differed across arms on 95.7 % of pixels on frame one — and the same burst run twice on the SAME
     * build differs from itself on 96.7 %, because `screencap` does not land on a fixed phase of a
     * running crossfade. The cross-arm reading was inside its own noise floor. The settled frames, where
     * the instrument is reliable, are byte-identical to ship (0.000 % against a 0.223 % floor).
     *
     * This pin therefore asserts the LOGIC, which is where the defect is decidable: a latch documented
     * as surviving close must not, on its own, switch off a blur.
     *
     * The fence is the epoch, captured on the activation edge — which is exactly the use its own kdoc
     * prescribes.
     */
    @Test
    fun `a stale settle from before the folio was opened cannot suppress the return's blur`() {
        assertFalse(
            "the return re-activates the overlay while the settle latch still reads true from the " +
                "PREVIOUS arrival, and the arrival epoch has not advanced past what activation captured. " +
                "Suppressing here switches the blur off in the middle of the return crossfade and the " +
                "outgoing Folio shows through sharp.",
            mimirSceneGlassSuppressed(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                tomeLibraryOpenSettled = true,
                arrivalEpoch = 3L,
                epochAtActivation = 3L,
            ),
        )
        assertTrue(
            "once this arrival's own false-edge has bumped the epoch AND it has since settled, the " +
                "ground is opaque and the blur is invisible work",
            mimirSceneGlassSuppressed(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                tomeLibraryOpenSettled = true,
                arrivalEpoch = 4L,
                epochAtActivation = 3L,
            ),
        )
    }

    /**
     * A cold open starts from epoch 0 with nothing captured before it — the fence must not make the
     * release unreachable on the ordinary path, which is the way a safety condition usually fails.
     */
    @Test
    fun `a cold open still reaches the release`() {
        assertFalse(
            "arriving: the epoch has advanced but it has not settled yet",
            mimirSceneGlassSuppressed(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                tomeLibraryOpenSettled = false,
                arrivalEpoch = 1L,
                epochAtActivation = 0L,
            ),
        )
        assertTrue(
            "landed: a first-ever open captures epoch 0, its false-edge bumps to 1, and it settles",
            mimirSceneGlassSuppressed(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                tomeLibraryOpenSettled = true,
                arrivalEpoch = 1L,
                epochAtActivation = 0L,
            ),
        )
    }
}
