package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **V-4, 2026-08-14 — the ghost-free identity rule, enforced in `core-ui`.**
 *

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
 * trail"* in its existing documentation. Two independent local cures, no stated rule — so the file browser
 * reintroduced it, and the keyboard had never been checked at all.
 *
 * A census in one module cannot stop that: each module runs its own gate. **This file is duplicated,
 * deliberately**, so a positional key fails a test in the module where it is written:
 *  - `core-ui/src/test/.../GhostFreeIdentityTest.kt` (this file)
 *  - `feature-library/src/test/.../GhostFreeIdentityTest.kt`
 *  - `feature-settings/src/test/.../GhostFreeIdentityTest.kt`
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
 * **Whether any trail remains is device-only.**
 */
class GhostFreeIdentityTest {

    private enum class Identity { CONTENT_KEYED, NO_FOCUS_STATE, FIXED_COLLECTION }

    private data class Site(val file: String, val line: String, val identity: Identity, val why: String)

    private val census = listOf(
        Site(
            "MimirSelectOverlay.kt", "items.forEachIndexed { index, item ->", Identity.CONTENT_KEYED,
            "V-4, disclosed then as belt-and-braces: its four callers of that day (AccentPicker, " +
                "BackdropPicker, PlatformEmulatorPicker, OpenWithPicker) pass a list of fixed order and " +
                "length and the overlay is mounted per open. There are five now, and LaunchChoicePicker is " +
                "not belt-and-braces: it builds its rows from `members` on every composition, and the " +
                "Library detail card collects gameDetailMembers live, so that list can change while the " +
                "menu is open. Keyed on item.key rather than on the whole " +
                "data class, because `current`/`enabled` change while the overlay is open and keying on " +
                "those would restart the ring animation on every preview step. The loop is indexed " +
                "(47276702) only to draw a BeamSeparator between centered-plain rows (AccentPicker, " +
                "BackdropPicker); the index gates that separator and must never reach the key.",
        ),
        Site(
            "MimirKeyboard.kt", "keyRows.forEach { row ->", Identity.CONTENT_KEYED,
            "the row band. Its caps are keyed one level down by keyId; the row itself holds no state.",
        ),
        Site(
            "MimirKeyboard.kt", "row.forEach { key ->", Identity.CONTENT_KEYED,
            "V-4, and the highest-reachability ghost in the product: `keyRows` is rebuilt by " +
                "`remember(shiftOn, characterSet)`, so cycling ALPHA -> SYMBOLS -> NUMBERS repopulates " +
                "every grid position with a different key. Unkeyed, the focused cap's mimirFocusRing " +
                "animated its gold border out on a key nobody chose. Keyed on keyId, which is derived " +
                "from the RAW layout value and is therefore shift-invariant -- so shift still recomposes " +
                "labels in place with the ring lit, and only a real set change disposes anything.",
        ),
        Site(
            "MimirRunicQuickRail.kt", "shown.forEachIndexed { index, action ->", Identity.CONTENT_KEYED,
            "V-4, a live case: the parameter defaults to the enum's entries but AppShelfQuickRail.kt:43 " +
                "passes libraryQuickRailActions(homeEnabled), so the rail gains and loses an entry and " +
                "every action behind the change shifts a slot. The rail sits on the Home shelf. Since the rail reorder " +
                "(2026-10-09) the loop runs over `shown`, the offered seats plus the ones still fading out, and " +
                "a leaving seat keeps its own key while it goes.",
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
                // not by review: MimirRunicQuickRail's loop is `forEachIndexed` (for `slotWidths[index]`), and
                // mutating `key(action)` to `key(index)` -- a positional identity, the exact ghost this file
                // exists for -- compiled and left every test here green. A key that reads a position fails.
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
     * parameter or local that says where something sits, such as `rowIndex` or `focusedIndex`), and the site's
     * own `forEachIndexed { <name>, ... ->` parameter, so renaming the index does not launder it. A name reached
     * through `.` is a property of the content, not a position, and is not counted.
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

    private fun moduleMainDir(): File {
        val relative = "src/main/kotlin"
        val candidates = listOf(File(relative), File("$MODULE/$relative"), File("../$MODULE/$relative"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("$MODULE sources not found from ${File(".").absolutePath}")
    }

    private companion object {
        const val MODULE = "core-ui"
    }
}
