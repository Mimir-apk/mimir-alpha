package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionReturnHostContractTest {
    @Test
    fun activityClassNameTargetsRewriteReturnHost() {
        assertEquals(
            "com.gruning.mimir.rewrite.CompanionReturnActivity",
            CompanionReturnHostContract.activityClassName("com.gruning.mimir.rewrite"),
        )
    }

    @Test
    fun launchModesMatchShippingReturnHostVocabulary() {
        assertEquals("secondary_app", CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP)
        assertEquals("screen_transfer", CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER)
        assertEquals("restore_only", CompanionReturnHostContract.LAUNCH_MODE_RESTORE_ONLY)
        assertEquals("home_reclaim", CompanionReturnHostContract.LAUNCH_MODE_HOME_RECLAIM)
    }
}
