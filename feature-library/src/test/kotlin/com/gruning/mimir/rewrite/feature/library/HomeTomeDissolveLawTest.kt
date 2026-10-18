package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP01-b's wiring, pinned as source contracts — the vehicle and its threading are Compose placement
 * this module's JVM tests cannot reach (ring appearance, timing, settlement and compositing need device evidence). The ledger's TRANSITIONS are
 * pinned for real in [HomeTomeBedVeilTest]; this file pins that the two surfaces actually ride it.
 *
 * What would fail without the fix: every test here is RED on the pre-CP01-b tree — both files imported
 * and called `androidx.compose.animation.Crossfade`, whose two children each get their own
 * `graphicsLayer` alpha, and two `src-over` layers at complementary alpha leave `e + (1−e)²` of the
 * art (bottom 0.75) with the ground carrying the rest. Measured on the Thor: plate art weight 0.775
 * mean, 9 of 10 lossless mid-fade frames BELOW their own endpoints' luma envelope.
 *
 * SCOPE, stated so a later hand does not widen it off this file: this bans `Crossfade` on these TWO
 * surfaces, where the measurement was made — not tree-wide. `MimirHintToastRail.kt` carries the same
 * arithmetic over a different ground in a different moment and is owed a measurement before changing it
 * (spec §4.2). The onboarding hashing poem was the other such site until it was retired (2026-09-18).
 */
class HomeTomeDissolveLawTest {

    private val face: String by lazy { source("HomeTomeFace.kt") }
    private val backdrop: String by lazy { source("HomeShelfBackdrop.kt") }
    private val ledger: String by lazy { source("HomeTomeBedVeil.kt") }

    // ── The primitive is gone from the two measured surfaces ────────────────────────────────────────

    @Test
    fun `the plate no longer imports or calls Crossfade`() {
        // The whole file, comments included: HomeTomeFace has no business even narrating Crossfade
        // once the import is gone, and pinning the import is what makes this differentially red.
        assertFalse(
            "HomeTomeFace.kt must not import androidx.compose.animation.Crossfade",
            face.contains("import androidx.compose.animation.Crossfade"),
        )
        assertFalse(
            "no Crossfade call may return to the plate: it fades BOTH children by construction, and " +
                "no parameter holds one at alpha 1",
            withoutComments(face).contains("Crossfade("),
        )
    }

    @Test
    fun `the paired hero no longer imports or calls Crossfade`() {
        assertFalse(
            "HomeShelfBackdrop.kt must not import androidx.compose.animation.Crossfade",
            backdrop.contains("import androidx.compose.animation.Crossfade"),
        )
        assertFalse(
            "no Crossfade call may return to the Home backdrop host",
            withoutComments(backdrop).contains("Crossfade("),
        )
    }

    // ── Both halves of the beat ride ONE vehicle ────────────────────────────────────────────────────

    @Test
    fun `plate and hero both ride the bed-veil ledger`() {
        // Spec §4.1: the two surfaces begin the same 240 ms handoff off one decode-ready snapshot.
        // Fixing one and not the other leaves the two halves of a single beat speaking different
        // dialects — the exact failure the design lane exists to prevent.
        listOf("HomeTomeFace.kt" to face, "HomeShelfBackdrop.kt" to backdrop).forEach { (name, text) ->
            val body = withoutComments(text)
            assertTrue("$name must hold a HomeTomeBedVeil", body.contains("HomeTomeBedVeil<"))
            assertTrue("$name must apply arrivals through the ledger", body.contains(".arrive("))
            assertTrue("$name must commit through the ledger", body.contains(".commit()"))
            assertTrue(
                "$name must hand its bed layer the named opaque alpha — an inline literal is how " +
                    "two surfaces drift",
                body.contains("else HomeTomeBedAlpha"),
            )
            assertTrue(
                "$name must gate the veil ramp on the DRAWING painter (spec §3.4): the probe is a " +
                    "different painter instance and Coil cannot make a fresh one ready synchronously",
                body.contains("snapshotFlow { veilArtReady }.first { it }"),
            )
        }
    }

    @Test
    fun `commit continuity rides one keyed loop, not two role slots`() {
        // At commit the veil entry becomes the bed entry of the SAME list, so Compose moves the keyed
        // group and the layer's painter survives. Two fixed role-slots would remount the painter on
        // the commit frame — Empty for >=1 frame regardless of cache warmth — flashing the ground at
        // the exact moment the fade completes.
        listOf("HomeTomeFace.kt" to face, "HomeShelfBackdrop.kt" to backdrop).forEach { (name, text) ->
            val body = withoutComments(text)
            assertTrue(
                "$name must render the layers from one ordered stack",
                body.contains("listOfNotNull(arrived.bed, arrived.veil)"),
            )
            assertTrue(
                "$name must key each layer by identity inside the loop",
                body.contains("stack.forEachIndexed { index, "),
            )
        }
    }

    // ── One clock, threaded — never a second one ────────────────────────────────────────────────────

    @Test
    fun `the plate's ramp is the one reduced-motion decision, threaded`() {
        assertTrue(
            "the plate's clock is homeTomeVeilRampMs(reducedMotion) — the bed/veil form of the old " +
                "snap()/tween(240) pair",
            withoutComments(face).contains("homeTomeVeilRampMs(reducedMotion)"),
        )
    }

    @Test
    fun `the hero's ramp is presenceFadeMs and it re-derives nothing`() {
        val host = withoutComments(backdrop)
        assertTrue(
            "the hero's bed/veil takes exactly the clock the Crossfade took",
            host.contains("rampMs = presenceFadeMs"),
        )
        // The synchronized bed/veil composable must not take its own reducedMotion: presenceFadeMs
        // already answers 0 for reduced motion AND at-rest upstream (homeTomeStageHandoffMs, pinned
        // by HomeTomeReturnAtRestTest). A second decision here is the two-clocks defect returning.
        val bedVeil = host.sliceBetween(
            after = "private fun HomeSynchronizedTomeBedVeil(",
            before = "\nprivate fun HomeSynchronizedTomeBackdropImage(",
        )
        assertFalse(
            "HomeSynchronizedTomeBedVeil must not re-derive reduced motion; found its own branch",
            bedVeil.contains("reducedMotion"),
        )
    }

    @Test
    fun `the ledger itself stays clockless and compose-free`() {
        assertFalse(
            "HomeTomeBedVeil.kt is a value type and two transitions — no composition, no clock; " +
                "that is what keeps its transitions honestly pinnable from a JVM test",
            ledger.contains("import androidx.compose"),
        )
    }

    // ── What stays exactly as it was ────────────────────────────────────────────────────────────────

    @Test
    fun `the publish gate still reads the committed pair, never a layer`() {
        // Spec §3.4 closing rule: the paired-presentation gate and publish-from-the-committed-pair
        // are load-bearing and untouched. Presentation truth comes from the decode-ready snapshot
        // that advanced BOTH consumers — never from whichever layer happens to be mounted.
        val body = withoutComments(face)
        assertTrue(body.contains("if (publishPresentation) pairedPresentation?.let { onPresented?.invoke(it) }"))
        assertTrue(
            "the probe still advances plate and backdrop off one ready snapshot",
            body.contains("if (plateReady && backdropReady) onReady(preview)"),
        )
    }

    @Test
    fun `the rotation loop's reduced-motion early return survives`() {
        // Spec §5 suppression 1 — the load-bearing one: a plate that never rotates cannot snap.
        assertTrue(
            withoutComments(face).contains("if (reducedMotion || previews.size <= 1)"),
        )
    }

    @Test
    fun `the unmeasured Crossfade site is left alone`() {
        // Measurement boundary: one measurement per kind is OWED before this one moves
        // . This test documents the boundary so
        // the ban above cannot be read as tree-wide. It asserted two sites until the onboarding poem, the
        // other one, was retired from the product . The spec's third listed neighbour,
        // GamelistCarousel.kt:849, is an alpha tween, not a Crossfade, and has nothing to pin.
        val toastRail = source("MimirHintToastRail.kt", coreUi = true)
        assertTrue(
            "MimirHintToastRail still owns its Crossfade until its own measurement rules on it",
            withoutComments(toastRail).contains("Crossfade("),
        )
    }

    // ── The alpha vehicle — one sampling chain for ramping and settled art ──────────────────────────

    @Test
    fun `art alpha modulates the draw op and never promotes a compositing layer`() {
        // AFTER-run reconciliation (2026-09-02): G1's residual was NOT ground bleed — the implied
        // ground sat at 121-204 luma against a leaf of 14.5, and G3 showed zero below-envelope dips.
        // The mechanism: with the default Auto strategy, alpha < 1 promotes the art node to a
        // compositing layer rasterized at LAYOUT size, magnified by the cover's ancestor focus zoom;
        // at alpha == 1 the layer is dropped and the art rasterizes direct. Two sampling chains of
        // one source — the ramping art measurably differs from its own settled render, and the
        // pixel-level fit reads that as lost art weight plus a bright constant. ModulateAlpha folds
        // alpha into the single painter draw op: no buffer, one chain in both states.
        listOf("HomeTomeFace.kt" to face, "HomeShelfBackdrop.kt" to backdrop).forEach { (name, text) ->
            val body = withoutComments(text)
            val alphaBlock = body.sliceBetween(after = "this.alpha = alpha()", before = "}")
            assertTrue(
                "$name must pair the art-alpha read with CompositingStrategy.ModulateAlpha in the " +
                    "same graphicsLayer block — Auto re-opens the two-chain sampling divergence",
                alphaBlock.contains("compositingStrategy = CompositingStrategy.ModulateAlpha"),
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", substringAfter(after).contains(before))
        return substringAfter(after).substringBefore(before)
    }

    /** Strips BOTH comment forms — kdoc/block and line — so historical prose cannot trip a call pin. */
    private fun withoutComments(source: String): String =
        source
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun source(name: String, coreUi: Boolean = false): String {
        val module = if (coreUi) "core-ui" else "feature-library"
        val pkgDir = if (coreUi) "core/ui" else "feature/library"
        val candidates = listOf(
            File("$module/src/main/kotlin/com/gruning/mimir/rewrite/$pkgDir/$name"),
            File("../$module/src/main/kotlin/com/gruning/mimir/rewrite/$pkgDir/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$pkgDir/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
