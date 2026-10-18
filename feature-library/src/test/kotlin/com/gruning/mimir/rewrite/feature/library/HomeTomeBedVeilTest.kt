package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP01-b : the rotation
 * dissolve holds ONE layer opaque. `Crossfade` faded both children, and two `src-over` layers at
 * complementary alpha leave `e + (1−e)²` of the art — bottoming at 0.75 — with the ground carrying the
 * missing quarter (the book leaf under the plate; the transparent stage under the paired hero). The
 * corrected structure is [HomeTomeBedVeil]: the committed art is a bed that draws at alpha 1 always,
 * the incoming art is a veil ramping above it, and the commit moves veil → bed in one state write.
 *
 * These pin the STRUCTURE — the ledger's transitions and the ramp clock. They deliberately do not
 * prove the composite: whether the Thor's frames now stay inside their endpoints' luma envelope is the
 * spec's G1/G3 job, on device, on the lossless arm. See `[[tested-is-not-working]]`.
 *
 * What would fail without the fix: this file does not compile against the pre-CP01-b tree (the ledger
 * did not exist — the Crossfade owned the structure), and the transition pins below fail against any
 * regression that lets the bed change outside a commit, drops it, or re-times reduced motion.
 */
class HomeTomeBedVeilTest {

    private val x = HomeTomePreview(
        artUri = "file:///art/x.png",
        itemId = HomeShelfItemId.Emulated(GameId("game-x")),
    )
    private val y = HomeTomePreview(
        artUri = "file:///art/y.png",
        itemId = HomeShelfItemId.Emulated(GameId("game-y")),
    )
    private val z = HomeTomePreview(
        artUri = "file:///art/z.png",
        itemId = HomeShelfItemId.Emulated(GameId("game-z")),
    )

    private fun HomeTomeBedVeil<HomeTomePreview>.arrive(p: HomeTomePreview) =
        arrive(p) { it.itemId to it.artUri }

    // ── The invariant: one opaque bed ───────────────────────────────────────────────────────────────

    @Test
    fun `the bed draws fully opaque -- the named constant IS one`() {
        assertEquals(
            "HomeTomeBedAlpha is the alpha both surfaces hand their bed layer. Anything under 1f " +
                "re-opens the wash-out: the ground carries whatever the layers give up.",
            1f,
            HomeTomeBedAlpha(),
        )
    }

    @Test
    fun `an arriving rotation goes to the veil and the bed does not move`() {
        val settled = HomeTomeBedVeil<HomeTomePreview>().arrive(x)
        val rotating = settled.arrive(y)
        assertSame(
            "The outgoing art must stay exactly where it is, at full presence, while the incoming " +
                "ramps over it. A structure that touches the bed on arrival is a crossfade again.",
            x,
            rotating.bed,
        )
        assertSame(y, rotating.veil)
    }

    @Test
    fun `no sequence of arrivals ever changes an existing bed -- only commit does`() {
        var layers = HomeTomeBedVeil<HomeTomePreview>().arrive(x)
        listOf(y, z, y, x, z).forEach { incoming ->
            val before = layers.bed
            layers = layers.arrive(incoming)
            assertSame(
                "arrive() moved the bed ($before -> ${layers.bed}) — the outgoing art just lost its " +
                    "full presence mid-fade, which is the measured defect wearing a new vehicle.",
                before,
                layers.bed,
            )
        }
    }

    @Test
    fun `after the first arrival the seat is never unowned`() {
        var layers = HomeTomeBedVeil<HomeTomePreview>().arrive(x)
        listOf(y, z, x).forEach { incoming ->
            layers = layers.arrive(incoming)
            assertTrue("something fully opaque must own the seat at every instant", layers.bed != null)
            layers = layers.commit()
            assertTrue(layers.bed != null)
        }
    }

    // ── The commit ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `commit moves the veil into the bed in one value`() {
        val committed = HomeTomeBedVeil(bed = x, veil = y).commit()
        assertSame(
            "bed := veil and veil := empty must be ONE returned value the caller writes once — a " +
                "two-step commit shows the bed alone for a frame, at the exact moment the player is " +
                "already looking.",
            y,
            committed.bed,
        )
        assertNull(committed.veil)
    }

    @Test
    fun `commit with no veil is a no-op, not a clear`() {
        val settled = HomeTomeBedVeil<HomeTomePreview>(bed = x)
        assertEquals(
            "A stray commit (effect restart, race) must never empty the seat.",
            settled,
            settled.commit(),
        )
    }

    // ── Interruption — the Aurora interruption rule, reused verbatim ───────────────────────────────────────────

    @Test
    fun `a retarget mid-ramp replaces the veil and keeps the committed bed`() {
        val interrupted = HomeTomeBedVeil(bed = x, veil = y).arrive(z)
        assertSame(
            "Generic Crossfade retains every interrupted target until it settles; Aurora reveals " +
                "only the latest renderable. The half-arrived art vanishes at the swap — the bed stays.",
            x,
            interrupted.bed,
        )
        assertSame(z, interrupted.veil)
    }

    @Test
    fun `arriving back at the bed drops the veil instead of ramping the bed over itself`() {
        val returned = HomeTomeBedVeil(bed = x, veil = y).arrive(x)
        assertSame(x, returned.bed)
        assertNull(
            "The latest renderable already owns the seat at full opacity — and this is also what " +
                "keeps the two key()ed sibling layers from ever sharing an identity.",
            returned.veil,
        )
    }

    @Test
    fun `bed and veil never hold the same identity, whatever arrives`() {
        val identityOf = { p: HomeTomePreview -> p.itemId to p.artUri }
        var layers = HomeTomeBedVeil<HomeTomePreview>()
        listOf(x, x, y, y, x, z, x, y, z, z).forEachIndexed { step, incoming ->
            layers = layers.arrive(incoming)
            val bed = layers.bed
            val veil = layers.veil
            if (bed != null && veil != null) {
                assertNotEquals(
                    "step $step: duplicate identity across the two key()ed sibling layers",
                    identityOf(bed),
                    identityOf(veil),
                )
            }
            if (step % 3 == 2) layers = layers.commit()
        }
    }

    @Test
    fun `an arrival that only refreshes the veil payload keeps the same value`() {
        val ramping = HomeTomeBedVeil(bed = x, veil = y)
        assertSame(
            "Same identity re-arriving must not produce a new value — the caller keys its ramp " +
                "effect on the veil identity, and a spurious value change would still be absorbed, " +
                "but a same-value arrival must not even recompose the guard.",
            ramping,
            ramping.arrive(y),
        )
    }

    // ── The clock — spec §5 and §6 ──────────────────────────────────────────────────────────────────

    @Test
    fun `reduced motion is duration zero -- the one-frame cut, not a shorter fade`() {
        assertEquals(0, homeTomeVeilRampMs(reducedMotion = true))
    }

    @Test
    fun `full motion keeps the exact token -- the overlay clock`() {
        assertEquals(
            "Spec §6: cost is zero — no new duration, no new curve. The fix is layering, and " +
                "layering is free.",
            HomeTomePreviewFadeMs,
            homeTomeVeilRampMs(reducedMotion = false),
        )
        assertEquals(MimirMotion.OverlayMs, HomeTomePreviewFadeMs)
    }
}
