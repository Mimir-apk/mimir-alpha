package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabBuildConfigSafetyTest {
    @Test
    fun `lab variant is a credential-free separate application identity`() {
        assertEquals("com.gruning.mimir.rewrite.lab", BuildConfig.APPLICATION_ID)
        assertEquals("", BuildConfig.STEAMGRIDDB_API_KEY)
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.FINAL_SHELF_ENABLED)
    }
}
