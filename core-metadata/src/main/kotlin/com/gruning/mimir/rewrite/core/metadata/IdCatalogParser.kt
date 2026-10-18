package com.gruning.mimir.rewrite.core.metadata

import java.io.BufferedReader
import java.io.InputStream

/**
 * Parses a bundled keyed-identity catalog: a compact UTF-8 TSV, one row per line —
 * `keyType<TAB>key<TAB>canonicalTitle` (keyType ∈ NAME|TITLE_ID|GAME_ID|SERIAL). Blank lines, `#` comments,
 * and malformed rows are skipped. This is the offline, hand-controlled format the fetch script emits from
 * the upstream sources (MAME name list, titledb, GameTDB) — far smaller than shipping those raw.
 */
object IdCatalogParser {

    fun parse(input: InputStream, platform: String): List<IdEntry> {
        val out = ArrayList<IdEntry>()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val tab1 = line.indexOf('\t')
                if (tab1 <= 0) continue
                val tab2 = line.indexOf('\t', tab1 + 1)
                if (tab2 <= tab1) continue
                val keyType = when (line.substring(0, tab1).trim().uppercase()) {
                    "NAME" -> IdKeyType.NAME
                    "TITLE_ID" -> IdKeyType.TITLE_ID
                    "GAME_ID" -> IdKeyType.GAME_ID
                    "SERIAL" -> IdKeyType.SERIAL
                    else -> continue
                }
                val key = line.substring(tab1 + 1, tab2).trim()
                val title = line.substring(tab2 + 1).trim()
                if (key.isEmpty() || title.isEmpty()) continue
                out += IdEntry(platform, keyType, key, title)
            }
        }
        return out
    }

    /** Convenience for a small in-memory catalog (tests / composition). */
    fun parse(reader: BufferedReader, platform: String): List<IdEntry> =
        parse(reader.readText().byteInputStream(Charsets.UTF_8), platform)
}
