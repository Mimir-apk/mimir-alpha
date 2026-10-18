package com.gruning.mimir.rewrite.core.model

/**
 * Display-time title tidy — turns the STORED identity string into the clean label the gamelist shows.
 *
 * A row's shown title is `COALESCE(canonicalTitle, title)`: a DAT-matched game carries its raw No-Intro /
 * Redump canonical name (verbose, region- and language-tagged); an unmatched one falls back to the
 * filename-derived title (which can be a "smashed" camelCase name a poorly-named ROM produced). Both read
 * badly. DAT canonical values remain raw in storage for matching/audit, while filename-derived identities
 * reuse this tidy before persistence; read models call it again safely because the transform is idempotent.
 * Pure + offline.
 *
 * Three jobs, covering the ways a title arrives ugly:
 *  1. Strip a leading No-Intro numeric index plus REGION / LANGUAGE / REVISION tags a DAT name carries
 *     ("Sonic Adventure 2 (USA) (En,Ja,Fr,De,Es)" → "Sonic Adventure 2"), while KEEPING a substantive
 *     parenthetical like "(Disc 2)".
 *  2. Space out a smashed camelCase filename ("DevilMayCry2Disc2" → "Devil May Cry 2 Disc 2") that an
 *     un-DAT-matched ROM fell back to.
 */
object DisplayTitle {

    /**
     * The clean, user-facing title for a stored identity string. Never blank (falls back to [raw]).
     * IDEMPOTENT: clean(clean(x)) == clean(x) — every step no-ops on already-clean input, so a drifted name
     * always organizes into the same recognizable form.
     */
    fun clean(raw: String): String {
        var s = raw.trim()
        s = INDEX_PREFIX.replace(s, "")
        s = stripClutterTags(s)
        if (!s.contains(' ') && looksSmashed(s)) s = spaceCamelCase(s)
        s = normalizeArticles(s)
        return WHITESPACE.replace(s, " ").trim().ifBlank { raw.trim() }
    }

    /**
     * Resolve one filename region token through the same vocabulary [clean] uses to identify removable
     * region clutter. A null result means either "not a region" or the explicit `unknown` marker.
     */
    fun canonicalRegion(token: String): String? = REGION_CANON[token.trim().lowercase()]

    /**
     * Strip a disc/CD token from a title — for a **folded multi-disc GROUP row only** (a folded row represents ONE game, so "Chrono Cross (Disc 2)" renders "Chrono Cross").
     * A LONE disc file keeps its tag ([clean] deliberately preserves "(Disc 2)") — dropping it there would
     * lie about which disc the user has; the caller gates on the row's MULTI_DISC group kind.
     * Handles the two forms a disc token survives [clean] in: a parenthetical "(Disc 2)"/"(CD 1)"/"(Disk II)"
     * anywhere, and a bare TRAILING "… Disc 2" a smashed filename produces. Idempotent; never returns blank.
     */
    fun stripDiscTag(s: String): String {
        var out = PAREN_DISC.replace(s, " ")
        out = TRAILING_DISC.replace(out, " ")
        return WHITESPACE.replace(out, " ").trim().ifBlank { s.trim() }
    }

    /** Whether a title explicitly identifies one disc/CD. Used to prevent provider presentation data
     * from erasing the truthful marker on a lone physical disc; it never participates in grouping. */
    fun hasDiscTag(s: String): Boolean = PAREN_DISC.containsMatchIn(s) || TRAILING_DISC.containsMatchIn(s)

    /**
     * The canonical title safe to stamp on every member of a formed group. A folded multi-disc group is
     * one game, so one matched disc's token must not become the identity of every member. Other group kinds
     * — and a lone disc with no group kind — retain the DAT title verbatim.
     */
    fun canonicalForGroup(raw: String, duplicateGroupKind: DuplicateGroupKind?): String =
        if (duplicateGroupKind == DuplicateGroupKind.MULTI_DISC) stripDiscTag(raw) else raw

    /**
     * Space a genuinely "smashed" single token at its word boundaries ("DevilMayCry3SE" → "Devil May Cry 3
     * SE"), else return it unchanged. Public so the IDENTITY matcher ([TitleCatalog.normalizeKey]) can reuse
     * the exact boundary rules the display path uses — a camelCase ROM filename then keys like its spaced form.
     * Idempotent: an already-spaced (or lowercase) token has no boundary to split, so it's returned as-is.
     */
    fun spaceIfSmashed(token: String): String =
        if (!token.contains(' ') && looksSmashed(token)) spaceCamelCase(token) else token

    // ── 3. article suffix → prefix ────────────────────────────────────────────────────────────────

    /**
     * No-Intro/Redump write a leading article as a trailing ", The" so titles sort under the real word
     * ("Legend of Zelda, The - Oracle of Ages"). That reads badly. Fold it back to the natural, recognizable
     * form ("The Legend of Zelda - Oracle of Ages"), per " - " subtitle segment. Idempotent: a title with no
     * ", <article>" suffix is returned unchanged.
     */
    private fun normalizeArticles(s: String): String =
        if (!s.contains(", ")) s else s.split(SUBTITLE).joinToString(" - ") { moveLeadingArticle(it.trim()) }

    private fun moveLeadingArticle(segment: String): String {
        val m = ARTICLE_SUFFIX.find(segment) ?: return segment
        if (m.groupValues[1].lowercase() !in ARTICLES) return segment
        val main = segment.substring(0, m.range.first).trim()
        return if (main.isEmpty()) segment else "${m.groupValues[1]} $main"
    }

    // ── 1. region / language / revision tag stripping ────────────────────────────────────────────

    private fun stripClutterTags(s: String): String {
        // [..] bracket tags are always dumper/format/title-id markers ([!], [T+Eng], [0100F4…]) — drop them.
        var out = BRACKET.replace(s, " ")
        // A bare version token a modern dump leaves loose ("… v1.0.0", "… v0"). Digit-anchored so a roman
        // numeral ("Final Fantasy V") or a word ("vs Capcom") is never touched.
        out = BARE_VERSION.replace(out, " ")
        // Drop only the (..) groups that are PURE region/language/revision clutter; keep substantive ones
        // like "(Disc 2)" or a real subtitle in parens.
        out = PAREN.replace(out) { m -> if (isClutter(m.groupValues[1])) " " else m.value }
        return out
    }

    /** A parenthetical is clutter when EVERY comma/plus-separated token is a region, language, or revision. */
    private fun isClutter(content: String): Boolean {
        val tokens = content.split(',', '+').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return tokens.all { tok ->
            val low = tok.lowercase()
            REGION_CANON.containsKey(low) || low in LANGS || low in MISC || REV.matches(tok)
        }
    }

    // ── 2. camelCase spacing ─────────────────────────────────────────────────────────────────────

    /** Only space a name that has NO spaces yet mixes upper + lower (a genuinely "smashed" filename). */
    private fun looksSmashed(s: String): Boolean =
        s.length >= 4 && s.any { it.isUpperCase() } && s.any { it.isLowerCase() }

    /**
     * Insert spaces at word boundaries in a smashed name, keeping acronym/number runs sensible:
     *  - lower/digit → Upper : "darkC"  → "dark C"    ("DarkCloud" → "Dark Cloud")
     *  - letter → digit      : "Cry2"   → "Cry 2"
     *  - digit → letter      : "2Disc"  → "2 Disc"
     *  - UPPER-run → Upper+lower (acronym end): "FESUndub" → "FES Undub"
     */
    private fun spaceCamelCase(s: String): String {
        val cs = s.toCharArray()
        val sb = StringBuilder(cs.size + 6)
        for (i in cs.indices) {
            val c = cs[i]
            if (i > 0) {
                val p = cs[i - 1]
                val boundary =
                    (p.isLowerCase() && c.isUpperCase()) ||
                        (p.isLetter() && c.isDigit()) ||
                        (p.isDigit() && c.isLetter()) ||
                        (i + 1 < cs.size && p.isUpperCase() && c.isUpperCase() && cs[i + 1].isLowerCase())
                if (boundary) sb.append(' ')
            }
            sb.append(c)
        }
        return sb.toString()
    }

    // ── token tables ─────────────────────────────────────────────────────────────────────────────

    private val PAREN = Regex("""\(([^)]*)\)""")
    private val BRACKET = Regex("""\[[^\]]*\]""")
    // Disc-token forms for [stripDiscTag] (multi-disc GROUP rows only). Parenthetical anywhere; bare form
    // only when TRAILING so "Disc" inside a real name ("Discworld") is never touched.
    private val PAREN_DISC = Regex("""\(\s*(?:disc|disk|cd|side)\s*(?:[0-9ivx]+|[ab])\s*(?:of\s*[0-9ivx]+\s*)?\)""", RegexOption.IGNORE_CASE)
    private val TRAILING_DISC = Regex("""[\s\-–—]+(?:disc|disk|cd|side)\s*(?:[0-9ivx]+|[ab])$""", RegexOption.IGNORE_CASE)
    private val INDEX_PREFIX = Regex("""^\s*\d{3,4}\s*-\s*""")
    private val WHITESPACE = Regex("""\s+""")
    private val SUBTITLE = Regex("""\s+-\s+""")             // the " - " that separates title / subtitle
    private val ARTICLE_SUFFIX = Regex(""",\s+([A-Za-z]+)$""") // a trailing ", The" at a segment end
    // The articles No-Intro/Redump actually suffix. The ", <word>$" anchor makes these safe against normal
    // titles (a real name doesn't end in ", A"); casing is preserved from the source ("The", not "the").
    private val ARTICLES = setOf(
        "the", "a", "an", "la", "le", "les", "los", "las", "el", "der", "die", "das", "il", "lo", "gli",
        "het", "een", "os", "as", "um", "uma",
    )
    private val REV = Regex("""(?:rev|v|version)\.?\s?[0-9][0-9a-z.]*""", RegexOption.IGNORE_CASE)
    // A bare, loose version token ("v1.0.0", "v0") — digit-anchored so "Final Fantasy V" / "vs" are safe.
    private val BARE_VERSION = Regex("""\bv\d[\w.]*""", RegexOption.IGNORE_CASE)

    // One region vocabulary for BOTH display clutter and resolver extraction. Nullable value preserves the
    // explicit "unknown" marker as removable clutter without presenting "Unknown" as useful region metadata.
    private val REGION_CANON: Map<String, String?> = buildMap {
        fun aliases(canonical: String?, vararg tokens: String) = tokens.forEach { put(it, canonical) }
        aliases("USA", "usa", "us", "u")
        aliases("Europe", "europe", "eur", "e", "pal")
        aliases("Japan", "japan", "jpn", "jap", "j")
        aliases("World", "world", "w")
        aliases("Asia", "asia")
        aliases("Korea", "korea")
        aliases("China", "china")
        aliases("Taiwan", "taiwan")
        aliases("Hong Kong", "hong kong")
        aliases("Australia", "australia")
        aliases("Brazil", "brazil")
        aliases("Spain", "spain")
        aliases("France", "france")
        aliases("Germany", "germany")
        aliases("Italy", "italy")
        aliases("Netherlands", "netherlands")
        aliases("Sweden", "sweden")
        aliases("Norway", "norway")
        aliases("Denmark", "denmark")
        aliases("Finland", "finland")
        aliases("Russia", "russia")
        aliases("Poland", "poland")
        aliases("Canada", "canada")
        aliases("Mexico", "mexico")
        aliases("Scandinavia", "scandinavia")
        aliases("Latin America", "latin america")
        aliases("USA, Europe", "usa, europe")
        aliases(null, "unknown")
    }
    private val LANGS = setOf(
        "en", "fr", "de", "es", "it", "ja", "nl", "pt", "sv", "da", "no", "fi", "ko", "zh", "ru",
        "pl", "cs", "hu", "el", "tr", "he", "ar", "ca", "gl", "eu",
    )
    private val MISC = setOf(
        "psn", "eshop", "virtual console", "gamecube", "wiiware", "unl", "proto", "beta", "demo",
        "sample", "kiosk", "aftermarket", "pirate", "alt", "ntsc", "ntsc-u", "ntsc-j", "secret",
        "sgb enhanced", "ndsi enhanced", "enhanced", "rumble version", "collection",
    )
}
