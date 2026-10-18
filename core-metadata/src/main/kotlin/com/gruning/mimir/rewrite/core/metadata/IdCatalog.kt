package com.gruning.mimir.rewrite.core.metadata

/**
 * How a game is keyed when a CONTENT HASH can't identify it — the identity axes for the platforms the DAT
 * path can't reach: MAME arcade (the rom-zip's shortname), Switch (the 16-hex Title ID), and GameCube/Wii
 * (the 6-char disc Game ID). Mirrors the shipping catalog's `key_type` model.
 */
enum class IdKeyType { NAME, TITLE_ID, GAME_ID, SERIAL }

/** One keyed identity row: e.g. (arcade, NAME, "robotbwl") -> "Robot Bowl". Metadata only — never content. */
data class IdEntry(
    val platform: String,
    val keyType: IdKeyType,
    val key: String,
    val canonicalTitle: String,
)

/**
 * An indexed, in-memory keyed catalog — the non-hash complement to [DatStore]. Built once from parsed
 * [IdEntry]s; look a title up by a `(platform, keyType, key)` extracted from the file (its shortname / Title
 * ID / disc Game ID). Case-insensitive keys. Fully offline; metadata only.
 */
class IdCatalog(entries: List<IdEntry>) {

    private val byKey: Map<String, IdEntry> = entries.associateBy { keyOf(it.platform, it.keyType, it.key) }

    val size: Int = entries.size

    fun lookup(platform: String, keyType: IdKeyType, key: String): IdEntry? =
        byKey[keyOf(platform, keyType, key)]

    private fun keyOf(platform: String, keyType: IdKeyType, key: String): String =
        "$platform|$keyType|${key.lowercase()}"
}
