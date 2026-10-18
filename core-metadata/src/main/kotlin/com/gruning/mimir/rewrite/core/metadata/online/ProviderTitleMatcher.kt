package com.gruning.mimir.rewrite.core.metadata.online

import java.text.Normalizer

/**
 * Conservative cross-provider title identity for explicit connected-media lookups.
 *
 * Name-only search is weak evidence, so two titles match only when their ordered canonical tokens are
 * identical. Two sole relaxations remain: a small, explicit set of edition suffixes after a real ` - `
 * boundary, and exactly one optional leading licensed-brand alias token sequence (initially only the
 * canonical tokens for possessive `Disney's`). That keeps useful results such as
 * `Resident Evil 2 - DualShock` → `Resident Evil 2` and `Aladdin` ↔ `Disney's Aladdin`, while a shared
 * long prefix can never turn `Soul Hackers` into `Soul Hackers 2`, bare `Disney` / Capcom-style publishers
 * into aliases, or sequels/subtitles into the base title. Exact provider ids and content hashes remain
 * the stronger paths and do not use this matcher.
 */
object ProviderTitleMatcher {
    private val removableEditionSuffixes = setOf(
        "dualshock",
        "dual shock",
        "dualshock version",
        "director s cut",
        "directors cut",
        "definitive edition",
        "deluxe edition",
        "complete edition",
        "special edition",
        "collector s edition",
        "collectors edition",
        "remastered",
        "remaster",
        "enhanced edition",
        "greatest hits",
        "game of the year edition",
        "goty edition",
        "anniversary edition",
    )

    /** Canonical leading alias sequences only; never bare brand tokens or arbitrary publisher prefixes. */
    private val licensedBrandAliasPrefixes = listOf(
        listOf("disney", "s"),
    )

    fun matches(requested: String, providerTitle: String): Boolean {
        val requestedTokens = identityTokens(requested)
        val providerTokens = identityTokens(providerTitle)
        if (requestedTokens.isEmpty() || providerTokens.isEmpty()) return false
        if (requestedTokens == providerTokens) return true
        return licensedBrandAliasMatch(requestedTokens, providerTokens)
    }

    /**
     * Symmetrically allow exactly one leading licensed-brand alias prefix when the non-empty remaining
     * ordered tokens are identical. Never strips non-leading occurrences, multiple prefixes, or an
     * empty remainder (e.g. bare `Disney's`).
     */
    private fun licensedBrandAliasMatch(left: List<String>, right: List<String>): Boolean {
        for (prefix in licensedBrandAliasPrefixes) {
            val leftRemainder = stripLeadingAliasPrefix(left, prefix)
            if (leftRemainder != null && leftRemainder.isNotEmpty() && leftRemainder == right) return true
            val rightRemainder = stripLeadingAliasPrefix(right, prefix)
            if (rightRemainder != null && rightRemainder.isNotEmpty() && rightRemainder == left) return true
        }
        return false
    }

    private fun stripLeadingAliasPrefix(tokens: List<String>, prefix: List<String>): List<String>? {
        if (tokens.size < prefix.size) return null
        if (tokens.take(prefix.size) != prefix) return null
        return tokens.drop(prefix.size)
    }

    /**
     * The title as it is written, tokenised and nothing more: diacritics folded, case folded, punctuation dropped, and the sequel
     * numerals folded as everywhere else. Unlike [identityTokens] it keeps every parenthesised word (so `Doom (1993)` and `Doom (2016)`
     * stay different titles) and strips no edition suffix and no licensed-brand prefix. For callers that must be strict, today the PC
     * logo fallback; [matches] and [identityTokens] are untouched.
     */
    internal fun strictTokens(value: String): List<String> = canonicalTokens(value)

    internal fun identityTokens(value: String): List<String> {
        val withoutTags = value
            .replace(Regex("""\([^)]*\)|\[[^]]*\]"""), " ")
            .trim()
        val segments = withoutTags.split(Regex("""\s+-\s+""")).toMutableList()
        // No-Intro/Redump catalogues sort a leading English article after the base title (for example,
        // `Legend of Zelda, The - The Minish Cap`), while connected providers normally return
        // `The Legend of Zelda: The Minish Cap`. Normalize only that explicit comma+article convention;
        // token order remains strict everywhere else, so sequels and unrelated shared prefixes still fail.
        if (segments.isNotEmpty()) segments[0] = restoreSortableEnglishArticle(segments[0])
        val identitySegments = segments.toMutableList()
        while (identitySegments.size > 1) {
            val suffix = canonicalTokens(identitySegments.last()).joinToString(" ")
            if (suffix !in removableEditionSuffixes) break
            identitySegments.removeAt(identitySegments.lastIndex)
        }
        return canonicalTokens(identitySegments.joinToString(" - "))
    }

    private fun restoreSortableEnglishArticle(segment: String): String {
        val match = Regex("""^(.+),\s*(the|an|a)\s*$""", RegexOption.IGNORE_CASE)
            .matchEntire(segment.trim())
            ?: return segment
        val stem = match.groupValues[1].trim()
        val article = match.groupValues[2].lowercase()
        return "$article $stem"
    }

    private fun canonicalTokens(value: String): List<String> = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .split(Regex("""\s+"""))
        .filter(String::isNotBlank)
        .map { sequelNumeralAliases[it] ?: it }

    /**
     * Sequel numerals in Roman and Arabic form name the same game: a sampled shortcut says
     * `Blasphemous 2`, SteamGridDB and Steam say `Blasphemous II` (2026-09-11, Library ENRICH found
     * "no match" for a title the Home picker showed). Only the unambiguous numerals are folded: `i`,
     * `v` and `x` stay as they are because they are also words and letters — `Mega Man X` is not
     * `Mega Man 10`, and a title's stray "I" must not become a "1".
     */
    private val sequelNumeralAliases = mapOf(
        "ii" to "2",
        "iii" to "3",
        "iv" to "4",
        "vi" to "6",
        "vii" to "7",
        "viii" to "8",
        "ix" to "9",
    )
}
