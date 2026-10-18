package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **NO SURFACE RESERVES LAYOUT SPACE FOR THE PRIMARY M ORB. THIS IS THE GATE ON THAT.**
 *
 * No surface reserves layout space for the movable orb.
 *
 * ## Why this test exists at all, and why it is a source scan
 *
 * Earlier reservations failed in two different ways:
 *
 * 1. **RC-28** shipped a static 66dp lane (`56dp` orb + `10dp` clearance), reserved unconditionally by every
 *    wired surface.
 * 2. The orb is draggable with persisted position. Its default rest band is y 14–70dp, but on
 *    2026-08-21 it sat at
 *    y 402–452dp on the Nova and y 273–323dp on the Odin. Every surface was paying real content width to
 *    clear a corner neither device's orb was in.
 * 3. **S4** replaced the constant with a live rect resolved from the overlay's own persisted position, so a
 *    surface reserved wherever the orb *actually* was.
 * 4. Dynamic reservation made Settings' content width track the dragged orb without a cap:
 *    the inset was `contentEnd - (orbLeft - clearance)` and the detail pane used an any-band.
 *    Dragging left narrowed it by 819dp on Odin at x=0. Dynamic reservation was removed.
 *
 * The lesson under both reversals: **reserving layout space for a user-movable object makes layout
 * user-movable**, which is worse on a text pane than the overlap it avoids. The orb floats above content;
 * content ignores it, and the player moves the orb when it is in the way.
 *
 * A compile-time pin is impossible here — there is no API left to assert against, which is the point. So
 * this scans production source for the vocabulary of the retired contract. It cannot catch a
 * reservation re-implemented from scratch under entirely new names; it catches the far likelier failure,
 * which is this contract being restored, re-imported, or re-threaded through a surface parameter. That
 * limit is stated rather than papered over.
 *
 * A failing test means a retired reservation returned. Preserve the invariant until the layout
 * contract is deliberately revised; deleting the test would hide the regression.
 */
class PrimaryMReservationStaysRetiredTest {

    /**
     * The retired contract's vocabulary. Each entry was a real declaration, parameter or call site removed
     * when the reservation came out; any of them reappearing in production source means it is coming back.
     *
     * [suppressPrimaryMForLibraryTextEntry] is deliberately NOT here: it survived the removal because it
     * does double duty — it drives `MButtonTopology.setPrimaryUiSuppressed`, which actually HIDES the orb
     * for a full-width text-entry sheet. Hiding the orb is live behaviour; reserving space for it is not.
     */
    private val retiredVocabulary = listOf(
        // core-ui's shared contract, file and all
        "MimirPrimaryMAnchor",
        "mimirPrimaryMAnchorInset",
        "mimirPrimaryMAnchorCentredBudget",
        "MimirOrbRect",
        // the app-layer resolver and its collector
        "reservePrimaryMAnchor",
        "rememberPrimaryMOrb",
        "primaryMOrb",
        // the per-surface consumers
        "reservePrimaryMButtonAnchor",
        "homeLaunchCardMLaneInset",
        "mLaneInset",
        "libraryHintRailEndInset",
        "libraryHintRailBand",
        "LibraryBottomChromeHeightDp",
        "LibraryPrimaryMButtonAnchorDiameter",
        "librarySearchPrimaryMAnchorInsetDp",
        // the native seam that existed only to feed the resolver
        "primaryButtonPosition",
        "primaryMButtonPositionRatio",
        "CompanionOverlayButtonRatio",
        // the parity cells
        "mAnchorReservation",
    )

    @Test
    fun `no production source reserves layout space for the primary M orb`() {
        val sources = productionSources()

        // The instrument must be able to SEE the defect. A walk that finds nothing passes vacuously, which
        // is the exact false-green this whole programme keeps rediscovering, so prove the scan is real
        // before trusting its silence: a sane file count, and one sentinel file that must always be there.
        assertTrue(
            "scanned only ${sources.size} production .kt files -- the walk is broken, not the tree clean",
            sources.size > 300,
        )
        val sentinel = sources.keys.firstOrNull { it.endsWith("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt") }
        assertTrue("MimirAppHost.kt was not scanned -- the walk is not reaching production source", sentinel != null)
        // ...and that the sentinel's own text really is being read, not an empty file.
        assertTrue("MimirAppHost.kt scanned but empty", sources.getValue(sentinel!!).length > 10_000)

        val hits = sources.entries.flatMap { (path, code) ->
            code.lineSequence().withIndex().flatMap { (index, line) ->
                retiredVocabulary.filter { it in line }.map { symbol -> "$path:${index + 1}  [$symbol]  ${line.trim()}" }
            }
        }.sorted()

        assertEquals(
            "The primary-M reservation is retired. These production lines bring it " +
                "back:\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    /**
     * Every module's `src/main/kotlin`, comment-stripped, keyed by repo-relative path.
     *
     * Comments are stripped so the tree stays free to DISCUSS the retired reservation (this file does, at
     * length) without tripping the pin. The stripper is deliberately simple: block comments, then
     * line comments. A `//` inside a string literal would truncate the rest of that line, which can only
     * hide a symbol appearing after it on the same line -- a false negative, never a false positive.
     */
    private fun productionSources(): Map<String, String> {
        val root = repoRoot()
        return root.listFiles { f: File -> f.isDirectory }.orEmpty()
            .map { File(it, "src/main/kotlin") }
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .associate { file ->
                file.path.removePrefix(root.path).trimStart('/') to stripComments(file.readText())
            }
    }

    private fun stripComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence()
            .joinToString("\n") { it.substringBefore("//") }

    private fun repoRoot(): File {
        val candidates = listOf(File(".."), File("."), File("../.."))
        return candidates.map(File::getAbsoluteFile)
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?.let { File(it.path) }
            ?: error("repo root not found from ${File(".").absolutePath}")
    }
}
