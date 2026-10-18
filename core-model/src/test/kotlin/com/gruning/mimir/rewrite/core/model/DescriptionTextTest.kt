package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The display decode for a scraped synopsis (Thor drive 2026-10-08, D43: "&quot;Pop-Up Duel.&quot;" on the launch card). */
class DescriptionTextTest {

    private fun decode(raw: String) = DescriptionText.decodeEntities(raw)

    @Test
    fun `the named references decode`() {
        assertEquals("\"Pop-Up Duel.\"", decode("&quot;Pop-Up Duel.&quot;"))
        assertEquals("Tom & Jerry", decode("Tom &amp; Jerry"))
        assertEquals("Link's Awakening", decode("Link&apos;s Awakening"))
        assertEquals("a < b > c", decode("a &lt; b &gt; c"))
        assertEquals("one two", decode("one&nbsp;two"))
        assertEquals("“quoted” – dash — more…", decode("&ldquo;quoted&rdquo; &ndash; dash &mdash; more&hellip;"))
    }

    @Test
    fun `the numeric references decode in decimal and hex, in either case`() {
        assertEquals("Link's", decode("Link&#39;s"))
        assertEquals("Link's", decode("Link&#x27;s"))
        assertEquals("Link's", decode("Link&#X27;s"))
        assertEquals("\"", decode("&#34;"))
        assertEquals("A", decode("&#65;"))
        assertEquals("A", decode("&#0065;"))
        // Past the BMP: a supplementary code point becomes its surrogate pair, one character to the reader.
        assertEquals("🎮", decode("&#x1F3AE;"))
        assertEquals("🎮", decode("&#127918;"))
    }

    /** HTML5's table for the C1 range: a numeric reference of 128 to 159 is the Windows-1252 character, and the five undefined slots stay as written. */
    @Test
    fun `a numeric reference in the C1 range is the Windows-1252 character`() {
        val cp1252 = mapOf(
            128 to "\u20AC", 130 to "\u201A", 131 to "\u0192", 132 to "\u201E", 133 to "\u2026", 134 to "\u2020", 135 to "\u2021", 136 to "\u02C6",
            137 to "\u2030", 138 to "\u0160", 139 to "\u2039", 140 to "\u0152", 142 to "\u017D", 145 to "\u2018", 146 to "\u2019", 147 to "\u201C",
            148 to "\u201D", 149 to "\u2022", 150 to "\u2013", 151 to "\u2014", 152 to "\u02DC", 153 to "\u2122", 154 to "\u0161", 155 to "\u203A",
            156 to "\u0153", 158 to "\u017E", 159 to "\u0178",
        )
        for ((number, expected) in cp1252) {
            assertEquals("&#$number;", expected, decode("&#$number;"))
            assertEquals("&#x${number.toString(16)};", expected, decode("&#x${number.toString(16)};"))
        }
        // The cases scraped text actually carries.
        assertEquals("Link\u2019s Awakening", decode("Link&#146;s Awakening"))
        assertEquals("1\u20132 players", decode("1&#150;2 players"))
        assertEquals("and more\u2026", decode("and more&#133;"))
        assertEquals("\u201CPop-Up Duel.\u201D", decode("&#147;Pop-Up Duel.&#148;"))
        // No slot in the range is left as a control character.
        assertEquals(0, (128..159).filter { decode("&#$it;").let { text -> text.length == 1 && text[0].code in 128..159 } }.size)
    }

    @Test
    fun `the five slots Windows-1252 leaves undefined stay as written`() {
        for (number in listOf(129, 141, 143, 144, 157)) {
            assertEquals("&#$number;", "&#$number;", decode("&#$number;"))
            assertEquals("&#x${number.toString(16)};", "&#x${number.toString(16)};", decode("&#x${number.toString(16)};"))
        }
        // The edges of the range are the ordinary code points: 127 is DEL and 160 is the no-break space.
        assertEquals("\u007F", decode("&#127;"))
        assertEquals("\u00A0", decode("&#160;"))
    }

    @Test
    fun `a double-escaped reference decodes once, never twice`() {
        assertEquals("&quot;", decode("&amp;quot;"))
        assertEquals("&amp;", decode("&amp;amp;"))
        assertEquals("&#39;", decode("&amp;#39;"))
        assertEquals("&lt;b&gt;", decode("&amp;lt;b&amp;gt;"))
        // Decoding the result again would change it; the display path runs it once.
        assertEquals("\"", decode(decode("&amp;quot;")))
    }

    @Test
    fun `what is not a reference is left exactly as written`() {
        for (text in listOf(
            "plain text",
            "",
            "Q&A and R&D",
            "Rock & Roll;",
            "AT&T; the phone company",
            "&unknown; stays",
            "&QUOT; stays",
            "&amp stays without its semicolon",
            "& ;",
            "&;",
            "<b>not a tag</b>",
        )) {
            assertEquals(text, text, decode(text))
        }
    }

    @Test
    fun `a numeric reference with no valid code point is left as written`() {
        for (text in listOf(
            "&#0;",
            "&#x0;",
            "&#xD800;",
            "&#xDFFF;",
            "&#55357;",
            "&#x110000;",
            "&#1114112;",
            "&#99999999999;",
            "&#x;",
            "&#;",
            "&#12a;",
        )) {
            assertEquals(text, text, decode(text))
        }
    }

    @Test
    fun `references mix with text, repeat and sit side by side`() {
        assertEquals("\"A\" & \"B\"", decode("&quot;A&quot; &amp; &quot;B&quot;"))
        assertEquals("\"\"\"", decode("&quot;&quot;&quot;"))
        assertEquals("&&", decode("&amp;&amp;"))
        assertEquals("& & &quot;x", decode("& &amp; &amp;quot;x"))
        // A decoded character is not a new reference's opening: "&amp;" then "lt;" is "&lt;" text, not "<".
        assertEquals("&lt;", decode("&amp;lt;"))
    }

    @Test
    fun `text with no ampersand comes back as the same string`() {
        val plain = "A synopsis with no references at all."
        assertSame(plain, decode(plain))
    }

    @Test
    fun `tags are not interpreted, only references`() {
        assertEquals("<b>Bold</b> & <i>not</i>", decode("<b>Bold</b> &amp; <i>not</i>"))
    }
}
