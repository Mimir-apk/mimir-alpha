package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * * pins [parseSteamStoreAppDetails] against the ACTUAL observed shapes of the storefront endpoint, called
 * live before this was built rather than assumed. The load-bearing distinction: `success:false` is an
 * HONEST empty (never a provider failure).
 */
class SteamStoreDescriptionParsingTest {

    @Test fun `a successful response yields the plain-text short_description verbatim`() {
        // Observed shape, called against Blasphemous 2 (appid 2114740) on 2026-08-19.
        val body = """
            {"2114740":{"success":true,"data":{
                "short_description":"The Penitent One awakens as Blasphemous 2 joins him once again in an endless struggle against The Miracle.",
                "detailed_description":"<p>Some <strong>HTML</strong> the About block must never see.</p>"
            }}}
        """.trimIndent()

        val outcome = parseSteamStoreAppDetails(2_114_740, body)

        assertTrue(outcome is SteamStoreDescriptionOutcome.Found)
        assertEquals(
            "The Penitent One awakens as Blasphemous 2 joins him once again in an endless struggle " +
                "against The Miracle.",
            (outcome as SteamStoreDescriptionOutcome.Found).shortDescription,
        )
    }

    @Test fun `success false is an honest empty, never a provider failure`() {
        // Observed shape, called with an unknown/delisted app id (999999999) on 2026-08-19: HTTP 200,
        // success:false, NO data object.
        val outcome = parseSteamStoreAppDetails(999_999_999, """{"999999999":{"success":false}}""")

        assertEquals(SteamStoreDescriptionOutcome.NoDescription, outcome)
    }

    @Test fun `success true with no data object is still an honest empty, never Unreachable`() {
        val outcome = parseSteamStoreAppDetails(440, """{"440":{"success":true}}""")

        assertEquals(SteamStoreDescriptionOutcome.NoDescription, outcome)
    }

    @Test fun `success true with a blank short_description is an honest empty`() {
        val outcome = parseSteamStoreAppDetails(440, """{"440":{"success":true,"data":{"short_description":"   "}}}""")

        assertEquals(SteamStoreDescriptionOutcome.NoDescription, outcome)
    }

    @Test fun `success true with no short_description field at all is an honest empty`() {
        val outcome = parseSteamStoreAppDetails(
            440,
            """{"440":{"success":true,"data":{"name":"Team Fortress 2"}}}""",
        )

        assertEquals(SteamStoreDescriptionOutcome.NoDescription, outcome)
    }

    @Test fun `malformed JSON is Unreachable, never silently a description miss`() {
        assertEquals(SteamStoreDescriptionOutcome.Unreachable, parseSteamStoreAppDetails(440, "not json"))
        assertEquals(SteamStoreDescriptionOutcome.Unreachable, parseSteamStoreAppDetails(440, ""))
    }

    @Test fun `a response keyed to a different app id than requested is Unreachable`() {
        // The response is keyed by app id as a STRING (observed U4) -- a mismatched key is an unexpected
        // shape, not the documented success:false honest empty.
        val outcome = parseSteamStoreAppDetails(440, """{"999":{"success":true,"data":{"short_description":"x"}}}""")

        assertEquals(SteamStoreDescriptionOutcome.Unreachable, outcome)
    }
}
