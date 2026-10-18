package com.gruning.mimir.rewrite.core.companionnative

import org.json.JSONArray
import org.json.JSONObject

/** Durable tab metadata in opened order; restored rows make no claim about a live process. */
internal object SecondaryTabsCodec {
    fun encode(entries: List<AppEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("package", entry.packageName)
                put("label", entry.label)
                put("displayId", entry.displayId ?: JSONObject.NULL)
                put("lastSeenAtMs", entry.lastSeenAtMs)
                put("iconPackageName", entry.iconPackageName)
                // Written for every row, APP included, so a restored tab never has to infer its kind from
                // the presence of optional game fields.
                put("kind", entry.kind.name)
                put("gameTitle", entry.gameTitle ?: JSONObject.NULL)
                put("gameId", entry.gameId ?: JSONObject.NULL)
                put("gameArtRef", entry.gameArtRef ?: JSONObject.NULL)
            })
        }
    }.toString()

    fun decode(text: String): List<AppEntry> = runCatching {
        val rows = JSONArray(text)
        buildList {
            for (index in 0 until rows.length()) {
                val entry = runCatching {
                    val row = rows.getJSONObject(index)
                    val packageName = (row.get("package") as String).also { require(it.isNotBlank()) }
                    // A tab written before the kind existed decodes as APP, which is what it was.
                    val kind = row.optString("kind").takeIf { it.isNotBlank() }
                        ?.let { name -> SecondaryTaskKind.entries.firstOrNull { it.name == name } }
                        ?: SecondaryTaskKind.APP
                    fun gameField(key: String): String? = if (kind != SecondaryTaskKind.GAME || row.isNull(key)) {
                        null
                    } else {
                        (row.get(key) as? String)?.takeIf(String::isNotBlank)
                    }
                    AppEntry(
                        packageName = packageName,
                        label = row.get("label") as String,
                        displayId = if (row.isNull("displayId")) null else row.getInt("displayId"),
                        lastSeenAtMs = row.getLong("lastSeenAtMs"),
                        iconPackageName = row.get("iconPackageName") as String,
                        kind = kind,
                        gameTitle = gameField("gameTitle"),
                        gameId = gameField("gameId"),
                        gameArtRef = gameField("gameArtRef"),
                    )
                }.getOrNull()
                if (entry != null) add(entry)
            }
        }
    }.getOrDefault(emptyList())
}
