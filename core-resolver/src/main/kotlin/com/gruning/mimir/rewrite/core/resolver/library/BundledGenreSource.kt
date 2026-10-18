package com.gruning.mimir.rewrite.core.resolver.library

import android.content.Context
import com.gruning.mimir.rewrite.core.model.library.GenreSource
import java.util.zip.GZIPInputStream

/**
 * The production [GenreSource]: an OFFLINE, LOCAL-ONLY genre lookup backed by a bundled map
 * (`assets/genre/genre-map.tsv.gz`, 22,328 entries — OpenVGDB + libretro + curated, zero network). Sibling of the compiled [com.gruning.mimir.rewrite.core.resolver.TitleCatalog];
 * genre is static reference data, so it stays an in-memory map — NOT a Room table (no migration, no seed,
 * no per-row DB round-trip).
 *
 * The 22K-entry map parses once, lazily + thread-safe, off the main thread (lookups run inside the Room
 * query dispatcher's `toGameRow` map). A missing/corrupt asset yields an empty map — genre just shows blank,
 * never a crash.
 *
 * Keying MUST match the bundled map, which was generated with the shipping app's `normalizeGenreKey`
 * ([normalizeGenreKey] below is an exact Kotlin port), so a game's title normalizes to the same key.
 */
class BundledGenreSource(private val context: Context) : GenreSource {

    private val map: Map<String, String> by lazy { load() }

    override fun genreFor(title: String): String? {
        val key = normalizeGenreKey(title)
        return if (key.isEmpty()) null else map[key]
    }

    private fun load(): Map<String, String> = runCatching {
        val out = HashMap<String, String>(24_000)
        GZIPInputStream(context.assets.open(ASSET)).bufferedReader().useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab > 0 && tab < line.length - 1) {
                    out[line.substring(0, tab)] = line.substring(tab + 1)
                }
            }
        }
        out
    }.getOrDefault(emptyMap())

    companion object {
        // A gzipped TSV — named `.tsvgz` (NOT `.gz`) on purpose: aapt auto-decompresses + renames `.gz`
        // assets, so we mirror the bundled-DAT `.datgz` convention and gunzip in code.
        private const val ASSET = "genre/genre-map.tsvgz"

        // Exact Kotlin port of the shipping app.js `normalizeGenreKey` (which keyed the bundled map):
        // strip extension, strip (…)/[…]/{…} groups, ROM article convention ("Zelda, The" → "the zelda"),
        // non-alphanumerics → space, collapse. Applied on the lowercased title so it matches the map's keys.
        private val EXTENSION = Regex("\\.[a-z0-9]{1,5}$")
        private val BRACKETS = Regex("[(\\[{][^)\\]}]*[)\\]}]")
        private val ARTICLE = Regex("^(.+?),\\s+(the|a|an)\\b")
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val WHITESPACE = Regex("\\s+")

        /** Normalize a game title to the bundled map's key. Public so tests can assert key parity. */
        fun normalizeGenreKey(title: String): String =
            title.lowercase()
                .replace(EXTENSION, "")
                .replace(BRACKETS, "")
                .replace(ARTICLE, "$2 $1")
                .replace(NON_ALNUM, " ")
                .trim()
                .replace(WHITESPACE, " ")
    }
}
