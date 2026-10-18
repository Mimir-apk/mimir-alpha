package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.settings.AboutLegalSourceRowId
import com.gruning.mimir.rewrite.feature.settings.MimirSourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsExternalAttributionTest {
    @Test
    fun `only the visible GameTDB credit opens the required project link`() {
        assertEquals(GameTdbAttributionUrl, settingsExternalUrlFor("about/credits/gametdb"))
        assertNull(settingsExternalUrlFor("trouble/gametdb"))
        assertNull(settingsExternalUrlFor("trouble/posture"))
        assertNull(settingsExternalUrlFor("https://attacker.invalid"))
    }

    @Test
    fun `the source code row opens the source address and no other legal row opens a link`() {
        assertEquals("https://github.com/Mimir-apk/mimir-alpha", MimirSourceUrl)
        assertEquals(MimirSourceUrl, settingsExternalUrlFor(AboutLegalSourceRowId))
        assertNull(settingsExternalUrlFor("about/legal/notice"))
        assertNull(settingsExternalUrlFor("about/legal/license"))
        assertNull(settingsExternalUrlFor("about/legal/notice/3"))
    }
}
