package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Ghost-free identity, enforced in `feature-settings`.**
 *
 * ## The rule
 *
 * **A composable that can paint a focus treatment must have a composition identity derived from its
 * CONTENT — never from its position in a collection.**
 *
 * Every focus treatment in this app is an `animate*AsState` on `MimirMotion.FocusMs`, held in a
 * `remember` and therefore owned by a composition SLOT. Give a slot a positional identity and a list that
 * is re-populated hands the incoming content the outgoing content's in-flight focus animation: the new
 * occupant animates *away from* a focus it never had — a ring fading out on an element the player never
 * selected. **That is the ghosting trail, and it is structural.** No ring-gating rule can reach it,
 * because the ghost is painted by a key that is not focused and never was.
 *
 * ## Why this test exists three times
 *
 * The invariant already existed in this codebase as **folklore, fixed locally twice and never written
 * down**: `HomeShelfScreen.kt:739` keys its tiles *"so a cold/error/no-art destination cannot expose a
 * previous identity underneath it"*, and `LibraryScreen.kt:2881` names *"the familiar focus/scroll ghost
 * trail"* as the same defect class. Two independent local cures, no stated rule — so the file browser
 * reintroduced it, and the keyboard had never been checked at all.
 *
 * A census in one module cannot stop that: lanes run their own module's gate. **This file is duplicated,
 * deliberately**, so a positional key fails a test in the module where it is written:
 *  - `core-ui/src/test/.../GhostFreeIdentityTest.kt`
 *  - `feature-library/src/test/.../GhostFreeIdentityTest.kt`
 *  - `feature-settings/src/test/.../GhostFreeIdentityTest.kt` (this file)
 *  - `feature-files/src/test/.../FileBrowserGhostFreeIdentityTest.kt` (the original, same shape)
 *
 * **They are the same SHAPE, not the same file, and the differences are deliberate — do not "align"
 * them.** `feature-settings` additionally asserts each FIXED_COLLECTION exemption's evidence, because
 * it is the only module whose exemptions rest on collections declared elsewhere. `feature-files`'
 * copy predates the transitive analyzer: it censuses EVERY collection site rather than only the
 * focus-reaching ones, which is a wider sweep reached by hand classification, and it is finished,
 * differentially-proven work. What must stay in step is the RULE and the failure message, so the
 * next author meets the same instruction wherever they land.
 *
 * ## What is checked
 *
 * A site is IN SCOPE when its brace-matched body reaches a focus treatment — directly, or through up to
 * three composable calls resolved inside this module. That transitive step is the whole point: the folio's
 * defect was `row.forEach { TomeTile(...) }`, whose body contains no marker at all because the marker is
 * one level down. Every in-scope site must carry a census row, and each row's claim is asserted.
 *
 * ## What this does NOT prove
 *
 * No module here configures a Compose harness, so nothing asserts a rendered ring, or that a trail is
 * absent on a device. This pins the STRUCTURAL property that makes the trail impossible, as source text.
 * **Whether a trail remains visible needs device evidence.**
 */
class GhostFreeIdentityTest {

    private enum class Identity { CONTENT_KEYED, NO_FOCUS_STATE, FIXED_COLLECTION }

    private data class Site(
        val file: String,
        val line: String,
        val identity: Identity,
        val why: String,
        /** For FIXED_COLLECTION: source lines evidencing that the collection cannot be re-populated.
         *  Matched as WHOLE trimmed lines -- see the evidence test for why `contains` was not enough. */
        val proof: List<String> = emptyList(),
        /** Which module those lines live in. A collection defined elsewhere is still checkable; it is
         *  simply checked against that module's sources rather than this one's. */
        val proofIn: String = MODULE,
    )

    private val census = listOf(
        Site(
            "MimirColorDial.kt", "stops.forEach { stop ->", Identity.FIXED_COLLECTION,
            "`val stops = MimirRingStop.entries` (MimirColorDial.kt:196) -- an enum's entries: fixed " +
                "order, fixed length, so no detent's slot can ever be reassigned.",
            proof = listOf("val stops = MimirRingStop.entries"),
        ),
        Site(
            "SettingsHome.kt", "SettingsCategory.rail.forEach { category ->", Identity.FIXED_COLLECTION,
            "`SettingsCategory.rail` is a companion-object val holding a literal listOf(...) " +
                "(SettingsIa.kt:38-39). Fixed at compile time, so no rail slot is ever reassigned.",
            proof = listOf(
                "val rail: List<SettingsCategory> =",
                // Seven since Settings › Music; still one literal, still fixed.
                "listOf(PROFILE, LIBRARY, EMULATORS, APPEARANCE, MUSIC, CONTROLS, ABOUT)",
            ),
        ),
        Site(
            "ThemeGallery.kt", "MimirThemePalette.all.chunked(3).forEach { rowThemes ->", Identity.FIXED_COLLECTION,
            "`MimirThemePalette.all` is a literal listOf(...) in core-ui (MimirThemePalette.kt:867) " +
                "and `.chunked(3)` is deterministic over it. The evidence is asserted against core-ui's " +
                "own sources rather than restated from the call line -- a proof that quotes the site it " +
                "is meant to justify proves nothing.",
            proof = listOf("val all: List<MimirThemePalette> = listOf("),
            proofIn = "core-ui",
        ),
        Site(
            "ThemeGallery.kt", "rowThemes.forEach { palette ->", Identity.FIXED_COLLECTION,
            "the inner half of the same fixed grid: `rowThemes` is one chunk of that same core-ui " +
                "list, so its fixity rests on the identical declaration.",
            proof = listOf("val all: List<MimirThemePalette> = listOf("),
            proofIn = "core-ui",
        ),
        Site(
            "AppearanceImagePicker.kt", "tiles.forEach { tile ->", Identity.CONTENT_KEYED,
            "the appearance pickers' tile row (concept A, 2026-09-30): the saved images change while the " +
                "picker is open (an import appends one, X removes one), so each tile is keyed on its own " +
                "FocusKey -- `key(tile.key)` -- never on its position in the row.",
        ),
        Site(
            "BackgroundPicker.kt", "MimirSceneGroundDim.entries.forEach { step ->", Identity.FIXED_COLLECTION,
            "an enum's entries (core-ui, MimirSceneGround.kt): fixed order, fixed length, so no Dim step's " +
                "slot can ever be reassigned. A greyed step registers no focus key at all.",
            proof = listOf("enum class MimirSceneGroundDim(val label: String, val alpha: Float) {"),
            proofIn = "core-ui",
        ),
        Site(
            "BackgroundPicker.kt", "MimirSceneGroundBlur.entries.forEach { step ->", Identity.FIXED_COLLECTION,
            "the Blur row: the same enum-entries fixity, declared in core-ui.",
            proof = listOf("enum class MimirSceneGroundBlur(val label: String, val downscale: Int, val radiusPx: Int) {"),
            proofIn = "core-ui",
        ),
        // Walk-32m: values and chips now animate a change in place, so a reused slot would play one row's
        // value into another's. Each emission below follows its row's identity.
        Site(
            "SettingsDetail.kt", "rows.forEachIndexed { i, a ->", Identity.CONTENT_KEYED,
            "the Emulators rows follow the library's platforms, which change as games are added; keyed on " +
                "`a.platformId ?: a.platform`, never on the index, which only routes sample rows.",
        ),
        Site(
            "SettingsDetail.kt", "controllerShortcutRows.forEach { row ->", Identity.CONTENT_KEYED,
            "the host's shortcut rows, keyed on `row.id`, the same id that routes their activation.",
        ),
        Site(
            "SettingsDetail.kt", "rows.filterNot { it.id == \"m_button\" }.forEach { row ->", Identity.CONTENT_KEYED,
            "the special-access rows are filtered and their grants flip while the pane is open; keyed on `row.id`.",
        ),
        Site(
            "ControllerMappingOverlay.kt", "rows.forEach { row ->", Identity.CONTENT_KEYED,
            "the mapping rows, keyed on `row.id`, the id their focus key and measurement already use.",
        ),
        Site(
            "LegalText.kt", "loaded.forEachIndexed { index, block ->", Identity.CONTENT_KEYED,
            "a text's reading blocks, keyed on the block's row id (text and position in a packaged, unchanging asset), the same id " +
                "its focus key uses; the list as a whole is keyed by the open text.",
        ),
    )

    private val focusMarkers = listOf(
        "mimirFocusRing(", "mimirSelectionRing(", "mimirFocusTileRing(", "mimirFocusable(", "MimirNorseBookCover(",
        "animateFloatAsState", "animateColorAsState", "animateDpAsState", "animateIntAsState",
        "animateValueAsState", "updateTransition(", "rememberInfiniteTransition(",
    )

    private val siteShape =
        Regex("""\bitemsIndexed\s*\(|\bitems\s*\(|\bitem\s*\(\s*key|\.forEachIndexed\s*\{|\.forEach\s*\{""")
    private val callShape = Regex("""\b([A-Z][A-Za-z0-9_]*)\s*\(""")
    private val funShape =
        Regex("""^[ \t]*(?:@Composable[ \t]*)?(?:internal |private |public )*fun\s+(?:[A-Za-z0-9_<>., ?]+\.)?([A-Za-z0-9_]+)\s*\(""", RegexOption.MULTILINE)

    // ─────────────────────────────────────────────────────────────────────────── the census ──

    @Test
    fun `every focus-painting collection site in this module is classified`() {
        val found = inScopeSites().map { it.first to it.second }.toSet()
        val declared = census.map { it.file to it.line }.toSet()
        assertEquals(
            "GHOST-FREE IDENTITY: a collection site in this module emits something that can paint a " +
                "focus treatment, and it is not classified. A re-populated list hands its composition " +
                "slots -- and the focus animations inside them -- to whatever fills them next; that is " +
                "the ghosting trail. Wrap the emission in `key(<content identity>)`, then add a census " +
                "row here (CONTENT_KEYED / NO_FOCUS_STATE / FIXED_COLLECTION) saying why.\n" +
                "  unclassified: ${(found - declared).sortedBy { it.second }}\n" +
                "  declared but gone (renamed? reformatted? re-read it): ${(declared - found).sortedBy { it.second }}",
            declared,
            found,
        )
    }

    @Test
    fun `every content-keyed site actually carries a key`() {
        census.filter { it.identity == Identity.CONTENT_KEYED }.forEach { site ->
            bodiesOf(site).forEachIndexed { occurrence, body ->
                assertTrue(
                    "GHOST-FREE IDENTITY: '${site.line}' in ${site.file} (occurrence ${occurrence + 1}) " +
                        "is declared CONTENT_KEYED but neither the site nor its body carries a key. " +
                        site.why,
                    site.line.contains("key = ") || body.contains("key("),
                )
                // A `key(` being present is not the CONTENT_KEYED claim. Found by differential on 2026-09-12,
                // not by review: once MimirSelectOverlay's loop became `forEachIndexed`, mutating
                // `key(item.key)` to `key(index)` -- a positional identity, the exact ghost this file exists
                // for -- compiled and left every test here green. The first cut knew only `index` and the
                // loop's own parameter, and `key(focusedIndex)` -- in scope at this same key -- still passed
                // it, so any bare `*Index` name counts too. A key that reads a position fails.
                keyArguments(body).forEach { args ->
                    val reads = positionalReads(site.line, args)
                    assertTrue(
                        "GHOST-FREE IDENTITY: '${site.line}' in ${site.file} (occurrence ${occurrence + 1}) " +
                            "is declared CONTENT_KEYED but keys by position: `key($args)` reads $reads. A " +
                            "slot keyed by where it sits is handed to whatever sits there next -- that is the " +
                            "ghosting trail. Key by the content's own identity. ${site.why}",
                        reads.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `every site declared free of focus state really is`() {
        census.filter { it.identity == Identity.NO_FOCUS_STATE }.forEach { site ->
            bodiesOf(site).forEach { body ->
                focusMarkers.forEach { marker ->
                    assertTrue(
                        "GHOST-FREE IDENTITY: '${site.line}' in ${site.file} is exempt because it holds " +
                            "no focus state, and it now emits '$marker'. Key it, or re-classify. ${site.why}",
                        !body.contains(marker),
                    )
                }
            }
        }
    }

    /**
     * A FIXED_COLLECTION exemption must name evidence, and the evidence must still be there.
     *
     * **Honest limit:** when the collection is defined in ANOTHER module (ThemeGallery's palettes live in
     * core-ui) this can only re-assert the call text, and the row says so. The sibling census in the
     * defining module is what would actually catch a change there.
     */
    @Test
    fun `every fixed-collection exemption still has its evidence`() {
        // **WHOLE-LINE equality, not `contains`, and that correction was found by differential rather
        // than by review.** The first draft asserted `corpus.contains(token)`; changing
        // `val stops = MimirRingStop.entries` to `val stops = MimirRingStop.entries.filter { … }` -- a
        // collection becoming dynamic, which is EXACTLY what this test exists to catch -- left the token
        // as a substring and the test stayed green. A prefix match cannot see an expression being
        // extended, and extension is the mutation that breaks fixity.
        val corpusByModule = mutableMapOf<String, Set<String>>()
        census.filter { it.identity == Identity.FIXED_COLLECTION }.forEach { site ->
            val corpusLines = corpusByModule.getOrPut(site.proofIn) {
                mainDirOf(site.proofIn).walkTopDown().filter { it.extension == "kt" }
                    .flatMap { it.readText().lines() }.map(String::trim).toSet()
            }
            assertTrue(
                "GHOST-FREE IDENTITY: '${site.line}' in ${site.file} claims a compile-time-fixed " +
                    "collection but names no evidence.",
                site.proof.isNotEmpty(),
            )
            site.proof.forEach { token ->
                assertTrue(
                    "GHOST-FREE IDENTITY: '${site.line}' in ${site.file} is exempt because its " +
                        "collection is fixed, and the evidence for that is gone: no line in ${site.proofIn} now " +
                        "reads exactly '$token'. If the collection became dynamic -- an added `filter`, " +
                        "`sortedBy`, or a computed source -- key the emission instead. ${site.why}",
                    token in corpusLines,
                )
            }
        }
    }


    @Test
    fun `no lazy list key is derived from an index`() {
        composableSources().forEach { (name, source) ->
            source.lines().map(String::trim)
                .filterNot { it.startsWith("//") || it.startsWith("*") }
                .filter { it.contains("key = {") }
                .forEach { line ->
                    assertTrue(
                        "GHOST-FREE IDENTITY: $name has a lazy key derived from a position: '$line'. " +
                            "Key by the content's own identity.",
                        !Regex("""\bindex\b""")
                            .containsMatchIn(line.substringAfter("key = {").substringBefore("}")),
                    )
                }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────── plumbing ──

    /** Sites whose body reaches a focus treatment: (file, trimmed line). */
    private fun inScopeSites(): List<Pair<String, String>> {
        val table = functionTable()
        return composableSources().flatMap { (name, source) ->
            val lines = source.lines()
            lines.indices.mapNotNull { i ->
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@mapNotNull null
                if (!siteShape.containsMatchIn(trimmed.substringBefore("//"))) return@mapNotNull null
                if (!reachesFocus(braceBody(lines, i), table, 0, mutableSetOf())) return@mapNotNull null
                name to trimmed
            }
        }
    }

    /**
     * Does [body] reach a focus treatment directly, or through up to three composable calls resolved in
     * this module? The transitive step is load-bearing: the emission that started this whole item —
     * `row.forEach { TomeTile(...) }` — contains no marker of its own.
     */
    private fun reachesFocus(body: String, table: Map<String, List<String>>, depth: Int, seen: MutableSet<String>): Boolean {
        if (focusMarkers.any { body.contains(it) }) return true
        if (depth >= 3) return false
        return callShape.findAll(body).map { it.groupValues[1] }.distinct().any { call ->
            if (call in seen) return@any false
            seen += call
            table[call].orEmpty().take(3).any { reachesFocus(it, table, depth + 1, seen) }
        }
    }

    private fun functionTable(): Map<String, List<String>> {
        val table = mutableMapOf<String, MutableList<String>>()
        allSources().forEach { (_, source) ->
            val lines = source.lines()
            funShape.findAll(source).forEach { m ->
                val startLine = source.substring(0, m.range.first).count { it == '\n' }
                table.getOrPut(m.groupValues[1]) { mutableListOf() }.add(braceBody(lines, startLine))
            }
        }
        return table
    }

    /**
     * The brace-matched body starting at the first `{` at or after [start]. Scoped rather than windowed,
     * so a neighbouring focusable cannot launder an exemption and a `key(` on a different loop cannot
     * satisfy this one. `//` comments are stripped so prose braces cannot unbalance the count.
     */
    private fun braceBody(lines: List<String>, start: Int): String {
        val out = StringBuilder()
        var depth = 0
        var opened = false
        for (i in start until minOf(lines.size, start + 400)) {
            val line = lines[i].substringBefore("//")
            out.append(lines[i]).append('\n')
            line.forEach { ch ->
                if (ch == '{') { depth++; opened = true } else if (ch == '}') depth--
            }
            if (opened && depth <= 0) break
        }
        return out.toString()
    }

    private fun bodiesOf(site: Site): List<String> {
        val source = composableSources().toMap()[site.file] ?: error("missing source: ${site.file}")
        val lines = source.lines()
        val starts = lines.indices.filter { lines[it].trim() == site.line }
        assertTrue("census anchor missing in ${site.file}: '${site.line}'", starts.isNotEmpty())
        return starts.map { braceBody(lines, it) }
    }

    /**
     * The POSITIONAL names a `key(...)` argument reads: a bare `index`, any bare name ending in `Index` (a
     * parameter or local that says where something sits -- `focusedIndex` is in scope at this module's own
     * key), and the site's own `forEachIndexed { <name>, ... ->` parameter, so renaming the index does not
     * launder it. A name reached through `.` is a property of the content, not a position, and is not counted.
     */
    private fun positionalReads(siteLine: String, args: String): List<String> {
        val loopIndex = Regex("""\.forEachIndexed\s*\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*,""")
            .find(siteLine)?.groupValues?.get(1)
        return Regex("""(?<![\w.])[A-Za-z_][A-Za-z0-9_]*""").findAll(args).map { it.value }
            .filter { it == "index" || it.endsWith("Index") || it == loopIndex }
            .distinct()
            .toList()
    }

    /** The argument text of every `key(...)` call in [body], paren-balanced, with `//` comments stripped first. */
    private fun keyArguments(body: String): List<String> {
        val code = body.lines().joinToString("\n") { it.substringBefore("//") }
        return Regex("""\bkey\(""").findAll(code).map { call ->
            val start = call.range.last + 1
            var depth = 1
            var end = start
            while (end < code.length && depth > 0) {
                when (code[end]) { '(' -> depth++; ')' -> depth-- }
                end++
            }
            code.substring(start, if (depth == 0) end - 1 else end)
        }.toList()
    }

    private fun composableSources(): List<Pair<String, String>> =
        allSources().filter { (_, source) -> source.contains("@Composable") }

    private fun allSources(): List<Pair<String, String>> =
        moduleMainDir().walkTopDown().filter { it.extension == "kt" }.sortedBy { it.name }
            .map { it.name to it.readText() }.toList()

    private fun moduleMainDir(): File = mainDirOf(MODULE)

    /** Any module's main sources, from this module's working directory or from the repo root. */
    private fun mainDirOf(module: String): File {
        val relative = "src/main/kotlin"
        val candidates = if (module == MODULE) {
            listOf(File(relative), File("$module/$relative"), File("../$module/$relative"))
        } else {
            listOf(File("../$module/$relative"), File("$module/$relative"))
        }
        return candidates.firstOrNull(File::isDirectory)
            ?: error("$module sources not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val MODULE = "feature-settings"
    }
}
