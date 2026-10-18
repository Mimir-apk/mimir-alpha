package com.gruning.mimir.rewrite.feature.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **V-4, 2026-08-14 — the enforcement half of [FileBrowserGhostFreeIdentity].** Content identity must prevent ghost trails across every open/return path.
 *
 * A rule stated only in a KDoc gets re-broken by the next change — this programme's own pattern, and the
 * reason five rounds of ring fixes each closed a real mechanism and device observations still showed the trail. So the rule is a
 * **census**: every place this module turns a collection into composables is enumerated below with a
 * classification, and **a site that is not on the list fails this test.** Adding a list to this module
 * therefore costs one entry and one decision, which is the whole point.
 *
 * ## What is actually checked
 *
 * The loop's body is brace-matched from its own opening `{`, so every assertion below is scoped to the
 * emission it is about rather than to a window of nearby lines:
 *  - **CONTENT_KEYED** — the body must contain a `key(` call (or, for a lazy list, the site line must
 *    carry a `key =` argument). This is the assertion that fails if someone deletes a `key(` wrapper.
 *    No `key(` argument may read a position either -- `index`, a bare name ending in `Index` such as the
 *    tile rows' `rowIndex`, or the loop's own index parameter. A lazy key needs no second check: it is
 *    written on the site line itself, so any change to it changes the census anchor and fails
 *    classification.
 *  - **NO_FOCUS_STATE** — the body must contain none of [FocusStateMarkers]: nothing that registers
 *    focus, paints a ring, or holds an `animate*AsState`. A slot with no focus animation in it has
 *    nothing to hand on and cannot ghost.
 *  - **FIXED_COLLECTION** — the collection is fixed at compile time (an `enum` `entries` list), so no
 *    slot can ever be reassigned to different content.
 *
 * Plus one module-wide rule with no exceptions: **no lazy-list key may be derived from an index.**
 *
 * ## What this does NOT prove
 *
 * `feature-files` configures no Compose harness (no Robolectric, no `createComposeRule` —
 * `build.gradle.kts`), so nothing here can assert that a ring is drawn, that a trail is absent, or that
 * an arrival looks right. This pins the STRUCTURAL property that makes the trail impossible, as source
 * text — this repo's established fallback, disclosed the same way `FileBrowserSupportDimRenderTest` does.
 * **A rendered trail needs device evidence.**
 */
class FileBrowserGhostFreeIdentityTest {

    private enum class Identity { CONTENT_KEYED, NO_FOCUS_STATE, FIXED_COLLECTION }

    private data class Site(
        val file: String,
        /** The exact source line, trimmed. A rename or a reformat fails the census and forces a re-read. */
        val line: String,
        val identity: Identity,
        val why: String,
    )

    /**
     * **The census.** Every collection-to-composable site in every `@Composable` file of this module.
     *
     * Rows marked CONTENT_KEYED in V-4 were the defect; the rest were already sound and are recorded so
     * that "already sound" is a checked claim rather than an assumption nobody re-tested.
     */
    private val census = listOf(
        // ── the folio, FileBrowserSurface.kt ─────────────────────────────────────────────────────────
        Site(
            "FileBrowserSurface.kt", "crumbs.forEachIndexed { index, crumb ->", Identity.NO_FOCUS_STATE,
            "the breadcrumb trail is Text + a tap gesture. It is deliberately NOT a focus participant " +
                "(see the header's own note on why the search icon is not one either), so no slot in it " +
                "holds a focus animation to hand on.",
        ),
        Site(
            "FileBrowserSurface.kt", "item(key = \"commit-affordance:\${controller.currentPath}\") {",
            Identity.CONTENT_KEYED,
            "V-4. The pinned commit row is a DIFFERENT row in every directory and carries " +
                "mimirFocusRing's four animations. Keyed by the bare literal it was the one slot that " +
                "outlived a navigation -- a return landing on a remembered tile left it fading its gold " +
                "border out in a directory the player had already arrived in.",
        ),
        Site(
            "FileBrowserSurface.kt", "item(key = \"empty-note\") {", Identity.NO_FOCUS_STATE,
            "one Text. Nothing focusable, nothing animated.",
        ),
        Site(
            "FileBrowserSurface.kt",
            "item(key = \"tomes-heading\") { SectionHeading(stringResource(R.string.files_browse_folders_heading, partition.tomes.size), palette) }",
            Identity.NO_FOCUS_STATE, "a section heading: SectionHeading is one Text.",
        ),
        Site(
            "FileBrowserSurface.kt",
            "itemsIndexed(tomeRows, key = { _, row -> \"tome-row:\" + row.first().path }) { rowIndex, row ->",
            Identity.CONTENT_KEYED,
            "V-4. Was \"tome-row-\$index\". Disposing a row also disposes the tiles inside it, so this " +
                "and TomeTileRow's key(tile.path) each close the reported ghost on their own; both are " +
                "here because this one removes the last positional identity from a file whose own rule " +
                "now forbids them.",
        ),
        Site(
            "FileBrowserSurface.kt",
            "item(key = \"files-heading\") { SectionHeading(stringResource(R.string.files_browse_files_heading, partition.files.size), palette) }",
            Identity.NO_FOCUS_STATE, "a section heading: SectionHeading is one Text.",
        ),
        Site(
            "FileBrowserSurface.kt",
            "itemsIndexed(fileRows, key = { _, row -> \"file-row:\" + row.first().path }) { rowIndex, row ->",
            Identity.CONTENT_KEYED, "V-4. Was \"file-row-\$index\" -- see the tome rows.",
        ),
        Site(
            "FileBrowserSurface.kt", "row.forEach { tile ->", Identity.CONTENT_KEYED,
            "V-4 ghost trail. TomeTileRow and FileTileRow both emit their tiles " +
                "through this line; unkeyed, a tile's identity was its grid cell, so a directory change " +
                "handed the outgoing tile's seven in-flight focus animations to whatever the incoming " +
                "listing put in the same position. Both rows are asserted -- the census counts two.",
        ),
        // Footer pieces are now content-keyed by core-ui's MimirSegmentedHintSwap;
        // there is no collection-to-composable loop left in this module.
        // ── the queue screen, FileBrowserQueueSurface.kt ──────────────────────────────────────────────
        Site(
            "FileBrowserQueueSurface.kt", "items(cards, key = { it.entryId }) { card ->",
            Identity.CONTENT_KEYED, "already correct before V-4: a queue card is keyed by its entry id.",
        ),
        Site(
            "FileBrowserQueueSurface.kt", "card.actions.forEach { action ->", Identity.CONTENT_KEYED,
            "V-4, and a LIVE case rather than a precaution. The card is content-keyed, but the BUTTONS " +
                "inside one card were positional and a card's action list is not fixed -- an entry that " +
                "finishes staging gains and loses buttons under a stationary player.",
        ),
        // ── the search screen, FileBrowserSearchSurface.kt ────────────────────────────────────────────
        Site(
            "FileBrowserSearchSurface.kt", "FileSearchFilter.entries.forEach { filter ->",
            Identity.FIXED_COLLECTION,
            "an enum's entries: fixed order, fixed length, so a chip's slot can never be reassigned.",
        ),
        Site(
            "FileBrowserSearchSurface.kt", "items(tiles, key = { it.path }) { tile ->",
            Identity.CONTENT_KEYED,
            "already correct before V-4, and the module's own precedent that the folio was the outlier " +
                "rather than the rule.",
        ),
        // ── the extract chooser, FileBrowserExtractSurface.kt ─────────────────────────────────────────
        Site(
            "FileBrowserExtractSurface.kt", "state.rows.forEach { option ->", Identity.CONTENT_KEYED,
            "V-4, belt and braces and disclosed as such: this screen is mounted fresh per open and its " +
                "rows are fixed for the life of one open, so it could not ghost today. Both exemptions " +
                "are properties of the CONTROLLER, either of which a later change could remove without " +
                "touching that file.",
        ),
        // ── the destination list, FileBrowserDestinationSurface.kt  ──────────
        Site(
            "FileBrowserDestinationSurface.kt",
            "itemsIndexed(state.rows, key = { _, row -> row.path ?: row.label }) { index, row ->",
            Identity.CONTENT_KEYED,
            "NOT belt and braces, unlike the two chooser entries below: this is the only list on a " +
                "chooser-class screen whose length is decided by the player's drives rather than by an " +
                "enum, and it is the only one that is LAZY -- so its slots are genuinely reused as the " +
                "list scrolls, and an index key really would hand one folder's ring animation to " +
                "another. Keyed by the folder's own path; the browse row has none and is keyed by its " +
                "label, which is a constant and unique in the list by construction.",
        ),
        // ── the transfer chooser, FileBrowserTransferSurface.kt (Move/Copy slice 1, G2) ───────────────
        Site(
            "FileBrowserTransferSurface.kt", "state.rows.forEach { option ->", Identity.CONTENT_KEYED,
            "Belt and braces, on FileBrowserExtractSurface.kt's own identical entry: this screen is " +
                "mounted fresh per open (composed only while FileBrowserController.transferChooser is " +
                "non-null) and state.rows is fixed for the life of one open (FileBrowserTransferChooser " +
                "is a snapshotted data class, and fileBrowserTransferOptions() takes no arguments), so it " +
                "could not ghost today either. Both exemptions are properties of the CONTROLLER, either " +
                "of which a later change (slice 2's smart rows) could remove without touching this file.",
        ),
        // ── the multi-disc warning, FileBrowserDiscSetSurface.kt  ────────────
        Site(
            "FileBrowserDiscSetSurface.kt", "state.rows.forEach { option ->", Identity.CONTENT_KEYED,
            "NOT purely belt and braces, unlike the transfer chooser's identical-looking entry. That " +
                "screen's rows come from fileBrowserTransferOptions(), which takes no arguments and " +
                "always answers the same two; these come from discSetMoveOptions(warning), whose FIRST " +
                "row exists only when the playlist can travel -- so two different sets really do produce " +
                "row lists of different lengths, and an index key would hand the ring animation from " +
                "'move all discs' to 'move just this one'. Keyed by the DiscSetMoveOption enum entry " +
                "itself, which is unique in the list by construction (discSetMoveOptions adds each at " +
                "most once).",
        ),
        // ── the SELECT action overlay, FileBrowserActionOverlaySurface.kt (F-1, 2026-08-18) ────────────
        Site(
            "FileBrowserActionOverlaySurface.kt", "state.rows.forEach { rowKind ->", Identity.CONTENT_KEYED,
            "NOT belt and braces, on the DiscSet warning's own reasoning rather than the transfer " +
                "chooser's: fileBrowserActionOverlayRows(kind, deleteAvailable, transferChoiceAvailable) " +
                "genuinely produces row lists of different lengths and different content for a DIRECTORY " +
                "(one row, Delete, until Rename ships), a FILE (up to three, Move/Copy/Delete) and an " +
                "ARCHIVE (up to two, Extract/Delete) -- an index key would hand Delete's ring, and its " +
                "danger-ink label, to whichever row happened to land in that slot for the next tile SELECT " +
                "opens on. Keyed by the FileBrowserActionOverlayRowKind enum entry itself, unique in the " +
                "list by construction (each kind appears at most once per fileBrowserActionOverlayRows " +
                "call).",
        ),
    )

    /** Anything that holds or paints a focus animation. A body containing none of these cannot ghost. */
    private val focusStateMarkers = listOf(
        "mimirFocusable(",
        "mimirFocusRing(",
        "mimirSelectionRing(",
        "mimirFocusTileRing(",
        "MimirNorseBookCover(",
        "animateFloatAsState",
        "animateColorAsState",
        "animateDpAsState",
    )

    private val siteShape = Regex("""\bitemsIndexed\s*\(|\bitems\s*\(|\bitem\s*\(\s*key|\.forEachIndexed\s*\{|\.forEach\s*\{""")

    // ────────────────────────────────────────────────────────────────────────── the census ──

    @Test
    fun `every collection-to-composable site in this module is classified`() {
        val found = composableSources().flatMap { (name, source) ->
            source.lines().map(String::trim)
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { siteShape.containsMatchIn(it) }
                .map { name to it }
        }.toSet()
        val declared = census.map { it.file to it.line }.toSet()

        assertEquals(
            "FileBrowserGhostFreeIdentity: a collection-to-composable site in this module is not " +
                "classified. Every list that can be re-populated hands its composition slots -- and the " +
                "focus animations inside them -- to whatever fills them next, which is the ghosting " +
                "trail. Add a row to this census naming the site's identity (CONTENT_KEYED / " +
                "NO_FOCUS_STATE / FIXED_COLLECTION) and why, then make the code satisfy it. See " +
                "FileBrowserGhostFreeIdentity's KDoc in FileBrowserSurfaceMotion.kt.\n" +
                "  unclassified: ${(found - declared).sortedBy { it.second }}\n" +
                "  declared but gone (rename? reformat? re-read it): ${(declared - found).sortedBy { it.second }}",
            declared,
            found,
        )
    }

    @Test
    fun `every content-keyed site actually carries a key`() {
        census.filter { it.identity == Identity.CONTENT_KEYED }.forEach { site ->
            bodiesOf(site).forEachIndexed { occurrence, body ->
                val keyed = site.line.contains("key = ") || body.contains("key(")
                assertTrue(
                    "FileBrowserGhostFreeIdentity: '${site.line}' in ${site.file} (occurrence " +
                        "${occurrence + 1}) is declared CONTENT_KEYED but neither the site nor its body " +
                        "carries a key. ${site.why}",
                    keyed,
                )
                // `keyed` is presence, not the CONTENT_KEYED claim. Found by differential on 2026-09-12:
                // TomeTileRow and FileTileRow receive `rowIndex`, and mutating `key(tile.path)` to
                // `key(rowIndex)` -- the tile ghost this census was written for, back through a parameter --
                // compiled and left every test here green. A key that reads a position fails.
                keyArguments(body).forEach { args ->
                    val reads = positionalReads(site.line, args)
                    assertTrue(
                        "FileBrowserGhostFreeIdentity: '${site.line}' in ${site.file} (occurrence " +
                            "${occurrence + 1}) is declared CONTENT_KEYED but keys by position: " +
                            "`key($args)` reads $reads. A slot keyed by where it sits is handed to whatever " +
                            "sits there next -- that is the ghosting trail. Key by the content's own " +
                            "identity. ${site.why}",
                        reads.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `every site declared free of focus state really is`() {
        census.filter { it.identity == Identity.NO_FOCUS_STATE }.forEach { site ->
            bodiesOf(site).forEachIndexed { occurrence, body ->
                focusStateMarkers.forEach { marker ->
                    assertTrue(
                        "FileBrowserGhostFreeIdentity: '${site.line}' in ${site.file} (occurrence " +
                            "${occurrence + 1}) is exempt because it holds no focus state, and it now " +
                            "emits '$marker'. Either key it by content or re-classify it. ${site.why}",
                        !body.contains(marker),
                    )
                }
            }
        }
    }

    @Test
    fun `every site declared a fixed collection really iterates one`() {
        census.filter { it.identity == Identity.FIXED_COLLECTION }.forEach { site ->
            assertTrue(
                "FileBrowserGhostFreeIdentity: '${site.line}' in ${site.file} is exempt because its " +
                    "collection is fixed at compile time, which is only true of an enum's `entries`. " +
                    "${site.why}",
                site.line.contains("entries"),
            )
        }
    }

    /**
     * The rule with no exceptions. `"tome-row-$index"` was this module's own violation and it is what
     * made the folio the outlier against `feature-library`'s gamelist (`items(games, key = {
     * it.gameId.value })`, `LibraryScreen.kt:2742`) — the very list whose page transition
     * `fileBrowserArrivalGlide` restates.
     */
    @Test
    fun `no lazy list key is derived from an index`() {
        composableSources().forEach { (name, source) ->
            source.lines().map(String::trim)
                .filterNot { it.startsWith("//") || it.startsWith("*") }
                .filter { it.contains("key = {") }
                .forEach { line ->
                    assertTrue(
                        "FileBrowserGhostFreeIdentity: $name has a lazy key derived from a position: " +
                            "'$line'. A positional key is what lets a re-populated list hand a slot's " +
                            "focus animation to different content. Key by the content's own identity.",
                        !Regex("""\bindex\b""").containsMatchIn(line.substringAfter("key = {").substringBefore("}")),
                    )
                }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────── plumbing ──

    /**
     * Every occurrence of [Site.line]'s body, brace-matched from the opening `{` on the site line.
     *
     * Brace-matched rather than windowed so each assertion is scoped to the emission it is about — a
     * neighbouring focusable must not launder an exemption, and a `key(` on a different loop must not
     * satisfy this one. `//` comments are stripped first so a brace inside prose cannot unbalance the
     * count; a `${'$'}{...}` interpolation is balanced and needs no special case. Presence-checked: a site
     * that no longer appears fails here rather than silently asserting over nothing.
     */
    private fun bodiesOf(site: Site): List<String> {
        val source = composableSources().toMap()[site.file] ?: error("missing source: ${site.file}")
        val lines = source.lines()
        val starts = lines.indices.filter { lines[it].trim() == site.line }
        assertTrue("census anchor missing in ${site.file}: '${site.line}'", starts.isNotEmpty())
        return starts.map { start ->
            val body = StringBuilder()
            var depth = 0
            var opened = false
            for (i in start until lines.size) {
                val line = lines[i].substringBefore("//")
                body.append(line).append('\n')
                for (ch in line) {
                    if (ch == '{') { depth++; opened = true }
                    if (ch == '}') depth--
                }
                if (opened && depth <= 0) break
            }
            body.toString()
        }
    }

    /**
     * The POSITIONAL names a `key(...)` argument reads: a bare `index`, any bare name ending in `Index` (a
     * parameter or local that says where something sits -- TomeTileRow and FileTileRow receive `rowIndex`), and
     * the site's own `forEachIndexed { <name>, ... ->` parameter, so renaming the index does not launder it. A
     * name reached through `.` is a property of the content, not a position, and is not counted.
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

    /** Every `@Composable`-bearing source file in this module, by simple name. */
    private fun composableSources(): List<Pair<String, String>> =
        moduleMainDir().listFiles { f: File -> f.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }
            .map { it.name to it.readText() }
            .filter { (_, source) -> source.contains("@Composable") }

    private fun moduleMainDir(): File {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isDirectory)
            ?: error("feature-files sources not found from ${File(".").absolutePath}")
    }
}
