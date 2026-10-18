package com.gruning.mimir.rewrite.core.model

/**
 * Display-time tidy for a game's description (its synopsis, the launch card's "About" text).
 *
 * A scraped synopsis can arrive with HTML character references still in it ("&quot;Pop-Up Duel.&quot;"), and the stored text stays exactly as the provider
 * sent it. Every surface that shows a description runs it through [decodeEntities] on its way to the screen, so the player never reads the reference.
 *
 * Only character references are decoded. Tags are not interpreted: "<b>" stays the five characters it is. Pure, offline, and it never logs.
 */
object DescriptionText {

    /**
     * [raw] with its HTML character references replaced by the characters they name: the named ones below and numeric "&#NNN;" / "&#xHH;" forms.
     *
     * ONE left-to-right pass over the original text, so a decoded character is never read as the start of another reference: "&amp;quot;" becomes "&quot;"
     * (the double-escaped text the provider sent), not a quotation mark. A reference this does not know, a numeric one with no valid code point (zero, a surrogate, past
     * U+10FFFF) and a stray "&" are all left exactly as written. "&nbsp;" becomes an ordinary space, so a long description still wraps where it would have.
     *
     * A numeric reference from 128 to 159 is read the way HTML5 reads it, as a Windows-1252 character: scraped text commonly comes from cp1252 sources, where
     * "&#146;" means the apostrophe U+2019, "&#150;" an en dash and "&#133;" an ellipsis, not the invisible C1 control the number names in Unicode. The five
     * slots cp1252 leaves undefined (129, 141, 143, 144, 157) have no character to show and stay as written.
     */
    fun decodeEntities(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        return REFERENCE.replace(raw) { match -> decode(match.groupValues[1]) ?: match.value }
    }

    private fun decode(body: String): String? {
        if (body.startsWith("#")) {
            val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
            val digits = body.substring(if (hex) 2 else 1)
            val codePoint = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
            if (codePoint < 1 || codePoint > MAX_CODE_POINT || codePoint in SURROGATES) return null
            if (codePoint in C1_CONTROLS) return CP1252_C1[codePoint - C1_CONTROLS.first].takeIf { it != UNDEFINED }?.toString()
            return String(Character.toChars(codePoint))
        }
        return NAMED[body]
    }

    // "&", then either a name or "#" and digits, then ";". The name and the digit run are capped, so a long run of text between an "&" and a ";" is not a reference.
    private val REFERENCE = Regex("""&(#[xX][0-9a-fA-F]{1,8}|#[0-9]{1,8}|[a-zA-Z][a-zA-Z0-9]{1,31});""")

    private const val MAX_CODE_POINT = 0x10FFFF
    private val SURROGATES = 0xD800..0xDFFF

    // The C1 range, whose numbers a numeric reference reads as Windows-1252 (the HTML5 table), one character per slot from 128 to 159. UNDEFINED marks the five it leaves open.
    private val C1_CONTROLS = 0x80..0x9F
    private const val UNDEFINED = '\u0000'
    private const val CP1252_C1: String =
        "\u20AC\u0000\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u0000\u017D\u0000" +
            "\u0000\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u0000\u017E\u0178"

    private val NAMED: Map<String, String> = mapOf(
        "quot" to "\"",
        "amp" to "&",
        "apos" to "'",
        "lt" to "<",
        "gt" to ">",
        "nbsp" to " ",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "ndash" to "–",
        "mdash" to "—",
        "hellip" to "…",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
    )
}
