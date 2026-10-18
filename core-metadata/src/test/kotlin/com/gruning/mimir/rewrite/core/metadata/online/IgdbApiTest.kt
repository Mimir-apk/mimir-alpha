package com.gruning.mimir.rewrite.core.metadata.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R6-P3-01 T1: [IgdbApi] builder + parser, pure JVM, no network. */
class IgdbApiTest {

    @Test
    fun `games query body is the exact Apicalypse literal`() {
        assertEquals(
            "search \"Blasphemous 2\"; fields name,summary,platforms,version_parent; " +
                "where platforms = (6) & version_parent = null; limit 10;",
            IgdbApi.gamesQueryBody("Blasphemous 2", 6),
        )
    }

    @Test
    fun `games query body sanitises quotes backslashes and control characters and caps length`() {
        val dirty = "Bl\"as\\phe\nmous" + "X".repeat(200)
        val body = IgdbApi.gamesQueryBody(dirty, 6)

        // The whole cleaned+capped title must appear as one quoted literal, at most 120 chars long,
        // with no quote/backslash/control character surviving inside it.
        val literal = body.substringAfter("search \"").substringBefore("\"; fields")
        assertTrue(literal.length <= 120)
        assertFalse(literal.contains('"'))
        assertFalse(literal.contains('\\'))
        assertFalse(literal.contains('\n'))
        assertTrue(literal.startsWith("Blasphemous"))
    }

    @Test
    fun `token form body carries the secret in the form, never a query string`() {
        val body = IgdbApi.tokenFormBody("client-id-1", "super-secret")

        assertTrue(body.contains("client_id=client-id-1"))
        assertTrue(body.contains("client_secret=super-secret"))
        assertTrue(body.contains("grant_type=client_credentials"))
    }

    @Test
    fun `api and token urls accept only the two exact routes`() {
        assertTrue(IgdbApi.apiUrlAllowed("https://api.igdb.com/v4/games"))
        assertTrue(IgdbApi.tokenUrlAllowed("https://id.twitch.tv/oauth2/token"))

        // The ScreenScraperTransportPolicyTest escape list, replayed for both IGDB routes.
        assertFalse(IgdbApi.apiUrlAllowed("http://api.igdb.com/v4/games"))
        assertFalse(IgdbApi.apiUrlAllowed("https://evil.com/v4/games"))
        assertFalse(IgdbApi.apiUrlAllowed("https://api.igdb.com:8443/v4/games"))
        assertFalse(IgdbApi.apiUrlAllowed("https://user@api.igdb.com/v4/games"))
        assertFalse(IgdbApi.apiUrlAllowed("https://api.igdb.com/v4/games#frag"))
        assertFalse(IgdbApi.apiUrlAllowed("https://api.igdb.com/v4/games?foo=bar"))
        assertFalse(IgdbApi.apiUrlAllowed("https://api.igdb.com/v4/unlisted"))

        assertFalse(IgdbApi.tokenUrlAllowed("http://id.twitch.tv/oauth2/token"))
        assertFalse(IgdbApi.tokenUrlAllowed("https://evil.com/oauth2/token"))
        assertFalse(IgdbApi.tokenUrlAllowed("https://id.twitch.tv:8443/oauth2/token"))
        assertFalse(IgdbApi.tokenUrlAllowed("https://user@id.twitch.tv/oauth2/token"))
        assertFalse(IgdbApi.tokenUrlAllowed("https://id.twitch.tv/oauth2/token#frag"))
        assertFalse(IgdbApi.tokenUrlAllowed("https://id.twitch.tv/oauth2/token?foo=bar"))
        assertFalse(IgdbApi.tokenUrlAllowed("https://id.twitch.tv/unlisted"))

        // Cross-route: the games URL must not satisfy the token check or vice versa.
        assertFalse(IgdbApi.tokenUrlAllowed("https://api.igdb.com/v4/games"))
        assertFalse(IgdbApi.apiUrlAllowed("https://id.twitch.tv/oauth2/token"))
    }

    @Test
    fun `parser extracts fields for a sequel a platform match a platform miss and a blank summary`() {
        val body = """
            [
              {"id": 1, "name": "Blasphemous", "summary": "The original.", "platforms": [6, 130]},
              {"id": 2, "name": "Blasphemous II", "summary": "The Penitent One returns.", "platforms": [6, 130]},
              {"id": 3, "name": "Blasphemous II", "summary": "Console-only entry.", "platforms": [130]},
              {"id": 4, "name": "Blasphemous II", "summary": "", "platforms": [6, 130]}
            ]
        """.trimIndent()

        val hits = IgdbApi.parseGames(body)

        assertEquals(4, hits.size)
        assertEquals("Blasphemous", hits[0].name)
        assertEquals("The original.", hits[0].summary)
        assertEquals(listOf(6, 130), hits[0].platforms)

        assertEquals("Blasphemous II", hits[1].name)
        assertEquals("The Penitent One returns.", hits[1].summary)
        assertTrue(6 in hits[1].platforms)
        // Numeral folding: "Blasphemous 2" (the sampled shortcut) and "Blasphemous II" (IGDB's own
        // spelling) name the same game -- ProviderTitleMatcher's own pinned behavior, reused here.
        assertTrue(IgdbApi.titleMatches("Blasphemous 2", hits[1].name))
        assertFalse(IgdbApi.titleMatches("Blasphemous", hits[1].name))

        assertEquals(listOf(130), hits[2].platforms)
        assertFalse(6 in hits[2].platforms)

        assertEquals(null, hits[3].summary)
    }
}
