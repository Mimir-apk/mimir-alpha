package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

/** OR-4 : the Library's "Connect to enrich" page lands on Not now, never on Connect. */
class GetArtLandingTest {

    @Test
    fun `every artwork page lands on Not now, including Connect to enrich`() {
        listOf(
            GetArtUi.Working,
            GetArtUi.Found,
            GetArtUi.NotFound,
            GetArtUi.NotSearchable,
            GetArtUi.Failed,
            GetArtUi.NeedsAccount,
            GetArtUi.ProviderUnavailable,
        ).forEach { state ->
            assertEquals(state.toString(), GetArtDismissKey, getArtLandingKey(state))
        }
    }
}
