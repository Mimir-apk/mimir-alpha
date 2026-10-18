package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-34 — **the Tome hero must not change before the Tome has opened.**
 *
 * Two independent defects sat under that one report, and this file pins both:
 *
 * 1. **A presence test standing in for a settled test.** `tomeFolioActive` is `folio != null`, true on the
 *    first frame of activation, and it selected the hero directly. [homeShelfFolioStageHandedOff] splits
 *    presence from settlement so the hand-off can wait for the opening.
 * 2. **A hard cut with no animation to soften.** The collection hero and the Folio hero were two
 *    call sites of the same composable, so the swap disposed one composition group and mounted another.
 *    [homeShelfTomeStageMounted] is the merged mount condition, and the source pin below is what keeps
 *    the second call site from coming back — a defect no value-level test can see.
 *
 * ## What this file does NOT prove
 *
 * There is no Compose harness in this module, so nothing here renders a frame, and none of it can tell
 * you whether the sequence FEELS right — which is how the regression report described it. These pin the reducer and
 * the structure; the felt result is device-only, on both handhelds, in one theme each.
 */
class HomeTomeOpenStageHandoffTest {

    private val collectionArt = "file:///tomes/snes/cover-lead.png"
    private val memberArt = "file:///games/chrono-trigger/hero.png"

    // ── The gate: presence is not settlement ────────────────────────────────────────────────────────

    @Test
    fun `an active folio is not a handed-off stage until the opening settles`() {
        assertFalse(
            "This is RC-34 exactly: the session exists on the first frame of activation, while the Tome " +
                "is still opening. Handing the hero over on presence is the snap.",
            homeShelfFolioStageHandedOff(folioActive = true, folioOpenSettled = false),
        )
        assertTrue(
            homeShelfFolioStageHandedOff(folioActive = true, folioOpenSettled = true),
        )
    }

    @Test
    fun `settlement without a session never hands the stage over`() {
        assertFalse(
            "A settled flag with no live session is stale by definition — the burst case. Presence is " +
                "necessary even though it is not sufficient.",
            homeShelfFolioStageHandedOff(folioActive = false, folioOpenSettled = true),
        )
    }

    // ── The stage art ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the collection hero holds for the whole opening`() {
        assertEquals(
            "While the Tome is opening the stage still belongs to the shelf's collection view. This is " +
                "the observed transition frame: the folio fading in over its own Tome's hero.",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(
                    folioActive = true,
                    folioOpenSettled = false,
                ),
                folioArt = memberArt,
                tomeLeadArt = collectionArt,
            ),
        )
    }

    /**
     * RESTATED 2026-08-25 — the OUTCOME is overturned, the mechanism it guards is not.
     *
     * This asserted that a settled folio hands the stage to the focused member's hero. The scene behind a Tome is static, so a
     * collection with lead art keeps it for the whole visit and the member hero never arrives.
     *
     * The settlement gate it was built on is untouched and still pinned above: RC-34 decided WHEN a
     * handoff may fire, and that answer is unchanged for the case where a handoff still happens — a
     * collection with no lead art of its own, below.
     */
    @Test
    fun `a collection with lead art keeps it for the whole visit`() {
        assertEquals(
            "the scene behind must not follow the member you are on",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(
                    folioActive = true,
                    folioOpenSettled = true,
                ),
                folioArt = memberArt,
                tomeLeadArt = collectionArt,
            ),
        )
    }

    /**
     * The member hero survives as the FALLBACK, and the settlement gate still rules it. Without it, a static stage would become empty for collections without lead art.
     */
    @Test
    fun `a collection with no lead art still waits for the open before showing a member`() {
        assertNull(
            "presence is not settlement, even for the fallback",
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(
                    folioActive = true,
                    folioOpenSettled = false,
                ),
                folioArt = memberArt,
                tomeLeadArt = null,
            ),
        )
        assertEquals(
            "and once open, an artless collection shows the member rather than nothing",
            memberArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(
                    folioActive = true,
                    folioOpenSettled = true,
                ),
                folioArt = memberArt,
                tomeLeadArt = null,
            ),
        )
    }

    /**
     * RESTATED 2026-08-25, and this one's REASONING is what the static-scene policy changed, not just its value.
     *
     * It argued that showing the collection hero while a member is focused is *"a lie about what is
     * selected"*. That was true while the stage CLAIMED to represent the focused member — under a
     * handoff, the picture behind is a statement about where you are. The static-scene policy withdraws the
     * claim: the scene is the collection you opened, and it says nothing about which member has focus.
     * With no claim there is no lie, and an empty stage is now simply a worse-looking version of the
     * same truth.
     *
     * What survives unchanged is the case that never had a collection hero to fall back to.
     */
    @Test
    fun `an artless member under an artless collection is still an honest empty stage`() {
        assertNull(
            "nothing to show and nothing invented",
            homeShelfTomeStageArt(
                folioStageHandedOff = true,
                folioArt = null,
                tomeLeadArt = null,
            ),
        )
        assertEquals(
            "but a collection hero is no longer withheld -- the scene is about the collection now",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = true,
                folioArt = null,
                tomeLeadArt = collectionArt,
            ),
        )
    }

    // ── Reversal: open, then back out mid-open ──────────────────────────────────────────────────────

    @Test
    fun `backing out mid-open never exposes the folio hero and leaves no residue`() {
        // Frame 1: activation. Frame 2: still opening. Frame 3: the player pressed back.
        val opening = listOf(true to false, true to false, false to false)
        val painted = opening.map { (active, settled) ->
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(active, settled),
                folioArt = memberArt,
                tomeLeadArt = collectionArt,
            )
        }
        assertEquals(
            "A reversal must be a no-op for the hero: the stage never left the collection art, so there " +
                "is nothing to restore and nothing to dissolve back from.",
            listOf(collectionArt, collectionArt, collectionArt),
            painted,
        )
    }

    @Test
    fun `backing out after settling returns the collection hero`() {
        assertEquals(
            "The reducer holds no latch, so losing the session is sufficient to hand the stage back. " +
                "Nothing can be left holding the Folio's art.",
            collectionArt,
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(
                    folioActive = false,
                    folioOpenSettled = true,
                ),
                folioArt = memberArt,
                tomeLeadArt = collectionArt,
            ),
        )
    }

    // ── Burst: open → close → open faster than the opening settles ──────────────────────────────────

    @Test
    fun `a burst cannot reuse the first open's settlement`() {
        // open (settles) → close → re-open, with the settled assertion not yet renewed.
        val burst = listOf(
            true to true, // first open, settled
            false to false, // closed — the caller invalidates on the authoritative change
            true to false, // second open, still opening
        )
        val painted = burst.map { (active, settled) ->
            homeShelfTomeStageArt(
                folioStageHandedOff = homeShelfFolioStageHandedOff(active, settled),
                folioArt = memberArt,
                tomeLeadArt = collectionArt,
            )
        }
        assertEquals(
            "RESTATED 2026-08-25: the first frame is now the collection hero too, because the stage no " +
                "longer hands over at all when the collection has its own art. The burst property this " +
                "pins is UNCHANGED and still worth pinning -- no frame may show a stale settlement. " +
                "This reducer is total in its " +
                "inputs and remembers nothing, so the only way a stale settlement can reach it is for the " +
                "caller to keep asserting one — which is the half of the contract HomeTomeFolio's " +
                "onFocusUnsettled already discharges for activation.",
            listOf(collectionArt, collectionArt, collectionArt),
            painted,
        )
    }

    // ── The merged stage ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `one stage spans the selected tome, the opening, and the open folio`() {
        assertTrue(
            "Selected on the shelf, no folio yet.",
            homeShelfTomeStageMounted(folioActive = false, tomeSelected = true),
        )
        assertTrue(
            "Both, which is every frame of an ordinary open — the shelf keeps its remembered Tome focus " +
                "while the Folio owns the focus scope. If the stage unmounted here the merge would buy " +
                "nothing.",
            homeShelfTomeStageMounted(folioActive = true, tomeSelected = true),
        )
        assertTrue(
            "A Folio with no shelf selection resolved still owns the stage.",
            homeShelfTomeStageMounted(folioActive = true, tomeSelected = false),
        )
        assertFalse(
            homeShelfTomeStageMounted(folioActive = false, tomeSelected = false),
        )
    }

    // ── Reduced motion ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `reduced motion suppresses every handoff clock on the tome stage`() {
        assertEquals(
            "Zero, not a shorter tween: the same grammar the Folio's own opening uses " +
                "(tween(if (reducedMotion) 0 else MimirMotion.SceneMs)) and that HomeTomeFace uses for " +
                "the cover plate. Before the two stages were merged, reduced motion got an instant swap " +
                "only because the branch flip REMOUNTED — the right answer for the wrong reason, and it " +
                "would have quietly become a crossfade the moment the remount went away.",
            0,
            homeBackdropHandoffMs(HomeTomePreviewFadeMs, reducedMotion = true),
        )
        assertEquals(0, homeBackdropHandoffMs(MimirMotion.OverlayMs, reducedMotion = true))
        assertEquals(
            HomeTomePreviewFadeMs,
            homeBackdropHandoffMs(HomeTomePreviewFadeMs, reducedMotion = false),
        )
        assertEquals(
            MimirMotion.OverlayMs,
            homeBackdropHandoffMs(MimirMotion.OverlayMs, reducedMotion = false),
        )
    }

    // ── Structural pins: the parts no value test can see ────────────────────────────────────────────

    private fun source(relativePath: String): String {
        val file = File(relativePath)
        assertTrue(
            "$relativePath not found at ${file.absolutePath} — if this file moved, move this pin with " +
                "it rather than deleting it.",
            file.exists(),
        )
        // Strip comments first: every claim below is about CODE, and the KDoc/comments deliberately
        // describe the defect in the same words a naive grep would match.
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `the tome hero has exactly one synchronized stage`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        // 2026-08-27: this used to count the LITERAL `synchronizedTomeHandoff = true`, which was a proxy
        // for "one synchronized stage" that held only while the tome stage had its own call site. The
        // device report identified the boundary that proxy could not see — entering and leaving the Tome still
        // moved the host between two branches — so the flag became a PARAMETER on a single host and the
        // literal disappeared. Counting the call site itself is the invariant the message below always
        // described; the literal was only ever standing in for it.
        val synchronizedStages = Regex("""HomeShelfStaticBackdrop\(""").findAll(code).count()
        assertTrue(
            "HomeShelfStaticBackdrop is not called here at all — this assertion has gone vacuous",
            synchronizedStages > 0,
        )
        assertTrue(
            "the hand-off must still be driven, not hardcoded away",
            code.contains("synchronizedTomeHandoff = "),
        )
        assertEquals(
            "Two call sites of HomeShelfStaticBackdrop are two composition groups, so flipping between " +
                "them DISPOSES one stage and MOUNTS another — and the incoming host's " +
                "synchronizedPresence is born at 1f for a non-null URI. That is a hard cut with nothing " +
                "to animate it, independent of when the swap is triggered. Gating the swap on settlement " +
                "does not fix it and would hide it: the cut would simply happen later. Select the URI, " +
                "never the call site.",
            1,
            synchronizedStages,
        )
    }

    @Test
    fun `the stage is never selected by the presence flag alone`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        assertFalse(
            "`if (tomeFolioActive)` choosing a backdrop is RC-34's exact shape. The hero follows " +
                "folioStageHandedOff; tomeFolioActive alone may only decide chrome that must track the " +
                "SESSION rather than the painted media.",
            Regex("""if\s*\(tomeFolioActive\)\s*\{\s*\n\s*HomeShelfStaticBackdrop""").containsMatchIn(code),
        )
        assertTrue(
            "The screen must derive the hand-off through the reducer rather than inlining the conjunction.",
            code.contains("homeShelfFolioStageHandedOff("),
        )
    }

    @Test
    fun `the opening is gated on a signal, never on a duration`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt")
        assertTrue(
            "The settled test must arrive as a parameter from the layer that owns the opening.",
            code.contains("tomeFolioOpenSettled"),
        )
        // **NARROWED 2026-09-03, in step with the identical pin in `HomeTomeLibraryOpenStageHandoffTest`
        // — both for one reason, so neither drifts from the other.** This forbade any
        // `delay(...Tome...)`. The opacity handoff requires that the Tome Library's hero should land a beat AFTER
        // its surface is opaque, and the constant that implements it is named `TomeLibraryArt…`, which
        // this regex catches by its first word.
        //
        // The hazard named above is a duration standing BESIDE the arrival as a rival source of truth —
        // a timer racing the tween, drifting under a dropped frame, handing off while the cover is still
        // translucent. A delay that starts FROM the settle latch is the opposite dependency: it cannot
        // fire before the thing it waits on, and it cannot drift when the opening's spec changes,
        // because it does not model the opening at all.
        //
        // The FOLIO hop this file owns is unchanged and still has no duration anywhere near it: that is
        // what the count below asserts. Renaming the new constant to dodge the regex would have been the
        // dishonest way past this, which is why the rule is restated rather than deleted.
        val settleEffect = Regex(
            """LaunchedEffect\(tomeLibraryOpenSettled\)\s*\{(.*?)\n {8}\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(code)?.groupValues?.get(1).orEmpty()
        val tomeDelays = Regex("""delay\([^)]*[Tt]ome""").findAll(code).count()
        val insideSettle = Regex("""delay\([^)]*[Tt]ome""").findAll(settleEffect).count()
        assertEquals(
            "every Tome-scoped delay must sit INSIDE the effect keyed on the arrival's own settle " +
                "signal. One outside it is the rival-clock defect this pin was written for -- and the " +
                "Folio hand-off this file owns must continue to have none at all.",
            tomeDelays,
            insideSettle,
        )
    }
}
