package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.resolver.TitleCatalog

/**
 * Decomposes a Redump / No-Intro dump name — or a ROM's own file name — into the parts that decide whether
 * two names denote the SAME dump.
 *
 * ### Why this exists
 *
 * Identifying a multi-GB disc image by content SHA-1 means reading every byte of it. On the Thor
 * that is 19.5 GB for 15 games, and CHD is worse than raw: [ChdPayloadVerifier] decompresses the whole
 * image to confirm the header's claim. Competing frontends quote 60–100 games/minute because they do not
 * hash at all — they match the file name against the same curated DATs Mimir already ships.
 *
 * ### Why not simply compare the names
 *
 * Measured against a reference library (2026-09-17), exact basename equality resolves 58% of the
 * expensive files. Every one of the failures is a *parenthetical*, not a title:
 *
 * | file | shipped DAT | difference |
 * |---|---|---|
 * | `Chrono Cross (USA) (Disc 1)` | `Chrono Cross (USA, Canada) (Disc 1)` | region list |
 * | `Shadows of the Damned (USA) (En,Fr,De,Es,It)` | `Shadows of the Damned (USA, Asia) (…)` | region list |
 * | `Resident Evil 5 ~ Biohazard 5 (World) (…)` | `Resident Evil 5 (World) (…)` | retired dual title |
 * | `Sonic The Hedgehog (USA)` | `Sonic The Hedgehog (USA, Europe)` | region list |
 *
 * So the title stem and the disc number are compared for EQUALITY, while region is used only to NARROW an
 * otherwise ambiguous set. That lifts the same corpus to 91% with zero wrong resolutions; what remains
 * (`Metroid Prime (USA) (v1.02)` against `Metroid Prime (USA)` + `(Rev 2)`) is genuinely ambiguous and must
 * stay on the hash.
 *
 * ### One normalization dialect
 *
 * The stem is normalized by [TitleCatalog.normalizeKey], the project's single filename-normalization rule
 * (diacritic fold, `0350 - ` index prefix, embedded `v1.0.0`, smashed camelCase, punctuation). Tags are
 * stripped HERE first, before that call, because `normalizeKey` deliberately flattens every parenthetical —
 * which would collapse `(Disc 1)` and `(Disc 2)` onto one key. Both a file name and a DAT name go through
 * this identical path, so matching is symmetric by construction.
 *
 * A known, deliberate collision source comes with that reuse: `normalizeKey` strips a leading `0350 - `
 * index prefix, which also strips a title that genuinely begins with three to five digits and a dash
 * (`007 - Racing`, `2010 - The Graphic Action Game`). Two such titles differing only in that prefix land in
 * one bucket, become ambiguous, and fall through to the hash. That is the safe outcome and the regex must
 * not be "fixed" to avoid it — it is what lets `0350 - Pokemon Trozei! (USA).nds` key cleanly.
 *
 * ### Version 2: canonical names
 *
 * On an RG Rotate 5,529-game library, 916 SNES files still missed and were read end-to-end.
 * Canonical-name matching adds the measured safe normalizations below, retaining alternate
 * versions for launch-card selection:
 *  - a trailing bare article, `Addams Family The (Europe)`, keys like `Addams Family, The (Europe)`. No
 *    normalized stem in any shipped shard ends in "the", so this adds no collision;
 *  - a smashed region list, `(USAEurope)`, reads as the regions it names;
 *  - [tagSignature], every tag normalized for separators, order and smashed regions, lets a lookup that stays
 *    ambiguous after narrowing pick the one dump whose tags ARE the file's (`(En Fr De)` = `(En,Fr,De)`).
 * Measured on that library: 682 of the 916 resolve by name, none into a different collapse group.
 *
 * [VERSION] is stamped into the evidence artifact. Changing any rule below must bump it, so a stored
 * identity can be told apart from one this build would derive.
 */
internal data class DatNameKey(
    /** Normalized title with every tag removed. Compared for equality. */
    val stem: String,
    /** Disc number when the name carries one. Part of identity: disc 1 and disc 2 are different dumps. */
    val disc: Int?,
    /** Lower-case region words, when a tag is made ENTIRELY of them. Narrowing only, never equality. */
    val regions: Set<String>,
    /** A beta / demo / prototype / sample dump. Never silently matched by an unqualified name. */
    val qualified: Boolean,
    /**
     * Every tag, normalized for separators, order and smashed regions, then sorted. Equal signatures name the
     * same dump; used only to break a tie that narrowing left (version 2).
     */
    val tagSignature: List<String> = emptyList(),
) {
    /** True when the key carries nothing to match on — a bare `sonic` or an empty name. */
    val isBlank: Boolean get() = stem.isBlank()

    companion object {
        /** Bump on ANY rule change below. Stamped into the evidence artifact. */
        const val VERSION = 2

        private val TAG = Regex("""[\(\[]([^)\]]*)[)\]]""")
        private val DISC = Regex("""^disc\s*(\d{1,2})$""", RegexOption.IGNORE_CASE)
        private val TRAILING_ARTICLE = Regex(""",\s*(The|A|An|Les|Le|La|El|Los|Das|Der|Die)\s*$""")
        private val LEADING_ARTICLE = Regex("""^(the|a|an|les|le|la|el|los|das|der|die)\s+""")
        /** `Addams Family The` -- the trailing article with its comma lost (version 2). */
        private val TRAILING_BARE_THE = Regex("""\sthe$""")
        /** `USAEurope` -> `USA`, `Europe` (version 2). */
        private val SMASHED_REGIONS = Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])""")
        private val TAG_TOKENS = Regex("""[\s,+]+""")

        /**
         * Tags that disqualify a dump from answering for an untagged file. Substring-matched against the
         * whole tag text, so `(Beta) (2009-01-07)` and `(Demo)` are both caught.
         */
        private val DISQUALIFYING = listOf(
            "beta", "demo", "proto", "sample", "taikenban", "kiosk", "trial", "alpha",
            "not for resale", "debug", "preview", "promo", "review code", "test program",
        )

        /**
         * Region words. A tag qualifies as a region list only when EVERY comma-separated part is one of
         * these, which is what keeps `(En,Fr,De,Es,It)` (a language list) from being read as regions.
         */
        private val REGION_WORDS = setOf(
            "usa", "europe", "japan", "world", "canada", "asia", "australia", "korea", "china",
            "brazil", "france", "germany", "spain", "italy", "netherlands", "sweden", "norway",
            "denmark", "finland", "russia", "taiwan", "hong kong", "uk", "latin america",
            "scandinavia", "greece", "portugal", "poland", "austria", "belgium", "switzerland",
            "ireland", "india", "israel", "mexico", "argentina", "south africa", "new zealand",
            "unknown",
        )

        /** Parses a DAT dump name or a ROM base name (extension already removed) into its key. */
        fun parse(name: String): DatNameKey {
            val tags = TAG.findAll(name).map { it.groupValues[1] }.toList()
            var disc: Int? = null
            val regions = mutableSetOf<String>()
            var qualified = false
            tags.forEach { tag ->
                DISC.find(tag.trim())?.let { disc = it.groupValues[1].toIntOrNull() }
                regionsOf(tag)?.let { regions += it }
                val lowered = tag.lowercase()
                if (DISQUALIFYING.any { it in lowered }) qualified = true
            }
            return DatNameKey(
                stem = normalizeStem(TAG.replace(name, " ")),
                disc = disc,
                regions = regions,
                qualified = qualified,
                tagSignature = tags.map(::normalizedTag).filter { it.isNotEmpty() }.sorted(),
            )
        }

        /**
         * The regions a tag names, when it names nothing else: a comma list (`USA, Europe`) or, since version 2,
         * a smashed one (`USAEurope`). Null for anything else, which keeps `(En,Fr,De)` from reading as regions.
         */
        private fun regionsOf(tag: String): Set<String>? {
            val listed = tag.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (listed.isNotEmpty() && listed.all { it in REGION_WORDS }) return listed.toSet()
            val smashed = tag.trim().split(SMASHED_REGIONS).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            return smashed.takeIf { it.size > 1 && it.all(REGION_WORDS::contains) }?.toSet()
        }

        /** One tag in the form both a file name and a dump name reduce to: `En Fr De` = `En,Fr,De`. */
        private fun normalizedTag(tag: String): String =
            regionsOf(tag)?.sorted()?.joinToString(" ", prefix = "region:")
                ?: tag.lowercase().split(TAG_TOKENS).filter { it.isNotEmpty() }.sorted().joinToString(" ")

        /**
         * Normalizes an already-tag-free stem.
         *
         * Two rules run before [TitleCatalog.normalizeKey] because they are DAT-corpus conventions rather
         * than general filename hygiene: Redump's retired `Title ~ Alternate Title` dual heading, and its
         * trailing-article form (`Legend of Zelda, The`). The leading article is then dropped on BOTH sides
         * so the two conventions agree. Two distinct games that differ only by a leading article therefore
         * collide — and collide into ambiguity, which falls through to the hash rather than guessing.
         */
        private fun normalizeStem(raw: String): String {
            val dual = raw.split(" ~ ").first()
            val article = TRAILING_ARTICLE.find(dual)
            val ordered = if (article != null) {
                article.groupValues[1] + " " + dual.removeRange(article.range)
            } else {
                dual
            }
            val stem = LEADING_ARTICLE.replace(TitleCatalog.normalizeKey(ordered), "").trim()
            return TRAILING_BARE_THE.replace(stem, "")
        }
    }
}
