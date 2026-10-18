package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The gamelist→shelf return carries no scene blur, to avoid the measured frame cost.**
 *
 * A removed behaviour needs its own gate or the next refactor restores it for a tidy-looking reason —
 * and this one is especially easy to restore, because the primitive it used
 * (`rememberMimirGlassReturnProgress`) is still in the tree and still correct for the Folio return.
 *
 * ## Why it went
 *
 * The original glassdrop return: the shelf was born at full 18 dp blur, held while the outgoing gamelist dissolved,
 * then resolved into focus. Each timing reduction was
 * measured on the Thor, n = 5 interleaved, from `screenrecord` frame timestamps:
 *
 * | gamelist → shelf | mean | sd | |
 * |---|---|---|---|
 * | as it stood | 390.6 ms | 7.6 | |
 * | both beats trimmed to their measured invisible points | 303.0 ms | 6.9 | −88 ms |
 * | trimmed further, into visible motion | 296.6 ms | 4.9 | **t = −1.33 — no gain** |
 * | **no glassdrop** | **201.0 ms** | 10.4 | **t = −18.26** |
 * | *Home → Library shelf, which never had one* | *211.6 ms* | *11.1* | |
 *
 * **Trimming was exhausted before the blur was.** Forty-two milliseconds of nominal moved the transition
 * by 3.6 ms, because the tail is not animation: the end of it is a pure edge-outline difference —
 * blur→sharp — arriving after a **51 ms gap in which no frame is presented at all.** The blur was
 * costing dropped frames, not duration. The same session measured an 18 dp full-screen `BlurEffect` at
 * ~7 ms of RenderThread per frame whenever the layer beneath it changes, and during a return it changes
 * constantly.
 *
 * The return now costs what every other path to this shelf costs.
 *
 * ## What is NOT being said
 *
 * The Folio return still uses this material. On this path it cost a third of the transition;
 * restoring it would need a new performance measurement.
 */
class LibraryReturnGlassAbsentTest {

    private val source: String by lazy {
        val f = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt")
        assertTrue(
            "could not locate ${f.absolutePath} — if the file moved, move this pin with it rather than " +
                "deleting it.",
            f.isFile,
        )
        f.readText()
    }

    /** Comments stripped, so this file's own account of the removal cannot satisfy the check. */
    private fun code(): List<String> = source.lines()
        .map { it.substringBefore("//") }
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }

    @Test
    fun `the library surface swap arms no return glass`() {
        val offenders = code()
            .mapIndexed { i, l -> i + 1 to l }
            .filter { (_, l) ->
                l.contains("rememberMimirGlassReturnProgress") || l.contains("glassReturnArmed")
            }
            .map { (n, l) -> "line $n: ${l.trim()}" }
        assertEquals(
            "a return glass is back on the library surface swap. It was removed on " +
                "2026-09-04 after being measured at 102 ms of a 303 ms transition — a third of it — and " +
                "after a shorter version measured no better (t = −1.33), because the cost was dropped " +
                "frames rather than duration. Re-arming it needs a design review AND a new measurement, not " +
                "a revert. The Folio return keeps the same material and is untouched.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The scene's OWN glass — the detail card and search blurring the gamelist behind them — is a
     * different mechanism on a different trigger, and it stays. Deleting it while removing the return
     * blur would be the easy over-correction.
     */
    @Test
    fun `the detail and search scene glass is untouched`() {
        assertTrue(
            "the scene's own glass must survive: it blurs the gamelist behind the detail card and the " +
                "search overlay, is driven by rememberMimirGlassProgress (not the RETURN primitive), and " +
                "has nothing to do with the surface swap.",
            source.contains("val sceneGlassProgress = rememberMimirGlassProgress(") &&
                source.contains(".mimirGlassBackdropProgress(sceneGlassProgress)"),
        )
    }

    /**
     * The outgoing gamelist still dissolves. Removing the blur must not turn the return into a hard cut —
     * the pixel-frozen outgoing layer is what keeps it a transition at all.
     */
    @Test
    fun `the outgoing layer still dissolves over its own duration`() {
        assertTrue(
            "the outgoing layer's dissolve is the whole of the return's motion now that the blur is " +
                "gone. Without it the shelf would hard-cut in.",
            source.contains("internal const val LibraryOutgoingLayerDurationMs = MimirMotion.ModeTransitionMs") &&
                source.contains("durationMillis = LibraryOutgoingLayerDurationMs"),
        )
    }
}
