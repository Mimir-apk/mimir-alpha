package com.gruning.mimir.rewrite.core.files

/**
 * MIMIR ADDITION. Not vendored: upstream's `LocalFileRepository.search` is a
 * bare name/regex walk with no scope model, no filter vocabulary, and no player-facing contract.
 *
 * **This file is the CONTRACT, and the contract is the feature's safety property.** The player-facing copy and this module enforce these clauses:
 *
 *  - **Names only.** [fileSearchNameMatches] reads [FileItem.name] and nothing else. No stream is
 *    opened, no byte of any file is read. Pinned by `FileSearchPolicyTest`.
 *  - **Current volume.** Scope is the root the caller hands [LocalFileRepository.search] — the volume
 *    the player is standing in, never `/` and never a device-wide sweep. The caller owns that choice;
 *    [FILE_SEARCH_SCOPE_COPY] is the sentence that tells the player so.
 *  - **Nothing leaves the device.** There is no network surface anywhere in this module (see
 *    `core-files/build.gradle.kts`: no network dependency, and the vendored network/cloud call sites
 *    were stripped during vendoring rather than disabled). Pinned by
 *    `FileSearchPolicyTest.noNetworkSurfaceExistsAnywhereInTheSearchPath`, which reads the real source
 *    files rather than trusting this comment.
 *
 * **Cost.** Directory traversal is performance-sensitive on this path, and the honest bound here
 * is structural rather than a magic number:
 *  - the walk is **volume-scoped**, so its ceiling is one drive, not the device;
 *  - [LocalListingPolicy.isPlumbingPath] prunes `/proc`-class pseudo-filesystems from the *traversal*,
 *    not merely the output (see `LocalFileRepository.search`'s own comment);
 *  - it is a cold `Flow` that emits as it finds, so a collector that stops — `take(n)`, a closed search
 *    screen, a new query — cancels the walk mid-directory instead of paying for the rest of it.
 *
 * No max-depth or max-results constant is defined here **on purpose**: both would be invented numbers
 * with no device measurement behind them, and a hard cap requires device measurement rather than an invented constant. If device evidence later shows the walk needs a hard cap,
 * that cap arrives with the measurement that justifies it.
 */

/** The Scope card's copy, verbatim from the design (`Mimir Files.dc.html:229`). Player-facing. */
const val FILE_SEARCH_SCOPE_COPY: String =
    "Names only, on the volume you are in. Mimir does not read file contents to search, and nothing is " +
        "sent anywhere."

/**
 * The five filter chips (`['All', 'Games', 'Archives', 'BIOS', 'Saves']`), as a type instead of five string literals a surface could typo.
 *
 * **[GAMES] and [BIOS] cannot be decided in this module, and that is deliberate rather than a gap.**
 * Platform recognition lives in `core-resolver` (`ExtensionPlatformClassifier`) and BIOS extensions in
 * `GameNameResolver.BIOS_EXTENSIONS`; a second table here would drift from the scanner's and the drift
 * would be invisible — the reasoning `core-storage`'s own `BrowserListing` already recorded when it made
 * `Entry.isBios` an injected field rather than a local set. So [fileSearchMatches] takes both as
 * injected predicates, supplied by `app/` (which already owns
 * `FileBrowserPlatformClassifier.forBrowser`).
 *
 * @property label the chip's own text, so the surface renders the product labels rather than an
 *   enum name.
 */
enum class FileSearchFilter(val label: String) {
    ALL("All"),
    GAMES("Games"),
    ARCHIVES("Archives"),
    BIOS("BIOS"),
    SAVES("Saves"),
}

/**
 * Whether [item]'s NAME matches [query]. The only matching this feature does.
 *
 * **A one-shot check — folds and splits [query] on every call.** A caller checking many candidates
 * against the SAME query must not call this once per candidate: [FileBrowserStore.search]'s
 * re-applied filter used to do exactly that (via [fileSearchMatches]), and `LocalFileRepository`'s raw
 * walk used to do it too, so a 30k-entry card paid two fresh `Normalizer.normalize` calls, three regex
 * matcher allocations and about six strings PER ENTRY for a query that never changed between them
 * (an estimated extra 0.2-0.6s of IO CPU per debounced walk).
 * Both hot loops now call [fileSearchQueryTokens] once per search instead and match every candidate
 * with [fileNameMatchesQueryTokens] / [fileSearchMatchesTokens] against that one token list — see
 * those functions' KDoc for folding and multi-token rules. A blank query matches everything, so an empty search box with a filter chip
 * selected still browses that kind — the same blank-query behavior.
 *
 * **Regex is deliberately not offered.** Upstream's `regex: Boolean` flag compiles a caller-supplied
 * `Regex` per search; a handheld player typing into an on-screen keyboard cannot usefully author one,
 * and a pathological pattern over a whole volume is a real stall. The parameter still exists on
 * [LocalFileRepository.search] (vendored surface, unchanged) and simply is not used by this path.
 */
fun fileSearchNameMatches(item: FileItem, query: String): Boolean =
    fileSearchQueryMatches(item.name, query)

/** One-shot form of [fileNameMatchesQueryTokens] — folds and splits [query] itself every call. See
 * [fileSearchNameMatches]'s KDoc for why a per-entry loop must call [fileSearchQueryTokens] once
 * instead of calling this once per entry. */
fun fileSearchQueryMatches(name: String, query: String): Boolean =
    fileNameMatchesQueryTokens(name, fileSearchQueryTokens(query))

/**
 * Folds and splits [query] into its match tokens ONCE. Call this once per search — beside `pattern`
 * in `LocalFileRepository.search`, and beside `classify` in `FileBrowserStore.search` — never once per
 * candidate; see [fileSearchNameMatches]'s KDoc for the cost of getting that wrong. Every candidate is
 * then checked against the SAME returned list with [fileNameMatchesQueryTokens] /
 * [fileSearchMatchesTokens].
 */
fun fileSearchQueryTokens(query: String): List<String> =
    foldSearchText(query).split(SEARCH_QUERY_WHITESPACE).filter(String::isNotEmpty)

/**
 * The ONE name matcher for this feature — whether
 * [name] contains every one of [queryTokens] (from [fileSearchQueryTokens]), once [name] is folded to
 * the same case- and diacritic-insensitive form. Called from both plain-match sites so they can never
 * disagree: [fileSearchNameMatches] above, and `LocalFileRepository.search`'s raw walk
 * (`LocalFileRepository.kt`, the `else` arm beside its regex branch). Before this, each did its own
 * plain `.contains(query, ignoreCase = true)`, so "Pokemon" found neither "Pokémon" nor a name split
 * across two words.
 *
 * **Folds diacritics the way `core-resolver`'s `TitleCatalog.foldDiacritics` folds ROM titles**
 * (`core-resolver/.../TitleCatalog.kt:54-55`: NFD-decompose, then drop combining marks) — so
 * "pokemon" finds "Pokémon". Restated here rather than depended on: `core-files` cannot depend on
 * `core-resolver` (see `core-files/build.gradle.kts`'s own dependency list — a vendored,
 * dependency-light module). `TitleCatalog`'s own version has no ASCII fast path; [foldSearchText]
 * below carries its own, added for the same review finding that made this take pre-split tokens.
 *
 * **Every token must appear somewhere in the folded name, independently** — "zelda link" finds
 * "The Legend of Zelda - A Link to the Past.sfc" this way, rather than the whole query matching as one
 * literal phrase. Empty [queryTokens] (from a blank, or all-whitespace, query) matches everything,
 * preserving [fileSearchNameMatches]'s existing contract for an empty search box, and a single-token
 * plain-ASCII query behaves exactly as the old `.contains(query, ignoreCase = true)` did.
 */
fun fileNameMatchesQueryTokens(name: String, queryTokens: List<String>): Boolean {
    if (queryTokens.isEmpty()) return true
    val foldedName = foldSearchText(name)
    return queryTokens.all { foldedName.contains(it) }
}

/**
 * Case- and diacritic-fold, the same approach as `core-resolver`'s `TitleCatalog.foldDiacritics`:
 * NFD-decompose, drop combining marks, then lower-case — `"Pokémon"` -> `"pokemon"`.
 *
 * **ASCII fast path**: a plain-ASCII string has no character to decompose
 * and no combining mark to drop, so `Normalizer.normalize` and the regex replace below are a
 * guaranteed no-op on one — skipping straight to `lowercase()` changes no result, only the cost of
 * getting it, and plain ASCII is the common case for both filenames and queries.
 */
private fun foldSearchText(s: String): String {
    if (s.all { it.code < 0x80 }) return s.lowercase()
    return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(SEARCH_COMBINING_MARKS, "")
        .lowercase()
}

private val SEARCH_QUERY_WHITESPACE = Regex("""\s+""")
private val SEARCH_COMBINING_MARKS = Regex("""\p{Mn}+""")

/**
 * The full per-row decision: name match AND filter chip.
 *
 * **A one-shot check** — see [fileSearchNameMatches]'s KDoc for why a per-entry loop over one query
 * must call [fileSearchMatchesTokens] instead ([FileBrowserStore.search]'s re-applied filter is
 * exactly that loop; R6-P2-07 review finding 1).
 *
 * @param platformOf recognises a row as a platform, or null. Injected — see [FileSearchFilter].
 * @param isBios whether a row is a BIOS payload. Injected, same reason.
 */
fun fileSearchMatches(
    item: FileItem,
    query: String,
    filter: FileSearchFilter,
    platformOf: (FileItem) -> String? = { null },
    isBios: (FileItem) -> Boolean = { false },
): Boolean = fileSearchMatchesTokens(item, fileSearchQueryTokens(query), filter, platformOf, isBios)

/**
 * [fileSearchMatches], taking an already-tokenized query ([fileSearchQueryTokens]) instead of folding
 * and splitting it again for every candidate — see [fileSearchNameMatches]'s KDoc for why that matters
 * on a volume-wide walk. [FileBrowserStore.search] computes the tokens once, then calls this for every
 * entry its raw walk turned up.
 *
 * A DIRECTORY survives only [FileSearchFilter.ALL]: every other chip names a kind of *file*, and a
 * folder called `Games` is not a game. Kept explicit rather than falling out of [LocalListingPolicy]'s
 * classification, because "which chips can a tome appear under" is a search-surface question, not a
 * classification one.
 *
 * [FileSearchFilter.GAMES] means recognised game content — the same standard the folio's own tiles use
 * (`FileBrowserTile.platformIdValue`), so a row that draws a platform glyph is exactly a row the Games
 * chip finds. Recognition is checked before the archive/support branches for the same reason
 * `BrowserListing.row` checks it before its own support branch.
 */
fun fileSearchMatchesTokens(
    item: FileItem,
    queryTokens: List<String>,
    filter: FileSearchFilter,
    platformOf: (FileItem) -> String? = { null },
    isBios: (FileItem) -> Boolean = { false },
): Boolean {
    if (!fileNameMatchesQueryTokens(item.name, queryTokens)) return false
    if (filter == FileSearchFilter.ALL) return true
    if (item.isDirectory) return false
    return when (filter) {
        FileSearchFilter.ALL -> true
        FileSearchFilter.ARCHIVES -> LocalListingPolicy.isExtractableArchive(item)
        FileSearchFilter.BIOS -> isBios(item)
        FileSearchFilter.GAMES -> !isBios(item) && platformOf(item) != null
        FileSearchFilter.SAVES ->
            LocalListingPolicy.classifyContent(item) == FileContentClass.SUPPORT && !isBios(item)
    }
}
