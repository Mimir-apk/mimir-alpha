package com.gruning.mimir.rewrite.core.ui

/**
 * MIMIR-REWRITE :core-ui — the shipping Norse theme identities.
 *
 * Each id is a distinct visual personality carried over from the shipping product's ratified theme
 * system . The internal id is STABLE — it is the persisted
 * `themePreference` key and the CompositionLocal selector — while the user-visible label is a short
 * Norse-god name (renaming affects only the display label; the persisted id stays unchanged).
 *
 * Legacy web id  →  rewrite id      →  user label
 *   default       →  DEFAULT         →  "Default"   (the ratified gold/dark baseline)
 *   gold          →  FREYR           →  "Freyr"     (Golden Hall — hearth gold)
 *   crimson       →  SURTR           →  "Surtr"     (the Forge — molten ember)
 *   midnight      →  MANI            →  "Máni"      (Máni's Vigil — moonlit blue)
 *   nightmare     →  HEL             →  "Hel"       (the Dread — blood + bone-ash)
 *   void          →  NIFLHEIM        →  "Niflheim"  (the primordial mist-abyss)
 *   terra         →  YGGDRASIL       →  "Yggdrasil" (the world tree — bronze + verdant)
 *   love          →  FOLKVANGR       →  "Fólkvangr" (Freyja's field of the slain — abyss grey)
 *   baldr         →  BALDR           →  "Baldr"     (summer-sky azure — dark metallic)
 *
 * ALL NINE THEMES ARE DARK. Folkvangr and Baldr were LIGHT in the retired shipping WebView and were
 * re-authored here as dark METALLIC (`metallicPresentation`, `MimirThemePalette.kt`); the `isLight`
 * flag they turned on is retired. Anything still calling them light describes a codebase that no
 * longer ships — including the design system's own `tokens/colors.css`, ported 1:1 from that WebView,
 * which still carries `--mm-is-light: 1` and pale backgrounds for both. Do not port those values.
 */
enum class MimirThemeId(
    /** The stable persisted key (matches the shipping product's `themePreference` string). */
    val storageKey: String,
    /** The user-visible label — a short Norse-god name (never the internal id). */
    val label: String,
) {
    DEFAULT("default", "Default"),
    FREYR("gold", "Freyr"),
    SURTR("crimson", "Surtr"),
    MANI("midnight", "Máni"),
    HEL("nightmare", "Hel"),
    NIFLHEIM("void", "Niflheim"),
    YGGDRASIL("terra", "Yggdrasil"),
    FOLKVANGR("love", "Fólkvangr"),
    BALDR("baldr", "Baldr");

    companion object {
        private val legacyAliases = mapOf("evil" to "nightmare")

        /** Resolve a persisted key back to an id, falling back to [DEFAULT] for unknown/legacy values. */
        fun fromStorageKey(key: String?): MimirThemeId =
            entries.firstOrNull { it.storageKey == canonicalStorageKey(key) } ?: DEFAULT

        /** True for current shipping keys plus legacy aliases exported by older Mimir builds. */
        fun isKnownStorageKey(key: String?): Boolean =
            entries.any { it.storageKey == canonicalStorageKey(key) }

        private fun canonicalStorageKey(key: String?): String? = legacyAliases[key] ?: key
    }
}
