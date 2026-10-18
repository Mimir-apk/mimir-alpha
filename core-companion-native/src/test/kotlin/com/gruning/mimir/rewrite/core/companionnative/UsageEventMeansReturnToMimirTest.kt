package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventMeansReturnToMimirTest {
    private val appPackageName = "com.gruning.mimir.rewrite"
    private val ownHosts = setOf(
        "$appPackageName.CompanionReturnActivity",
        "$appPackageName.CompanionSecondaryHomeActivity",
        "$appPackageName.PrimaryTransferActivity",
    )

    @Test
    fun `foreign package does not mean return to Mimir`() {
        assertFalse(meansReturn("com.android.chrome", "com.google.android.apps.chrome.Main"))
    }

    @Test
    fun `own MainActivity means return to Mimir`() {
        assertTrue(meansReturn(appPackageName, "$appPackageName.MainActivity"))
    }

    @Test
    fun `own Bottom home does not mean return to Mimir`() {
        assertFalse(meansReturn(appPackageName, "$appPackageName.CompanionSecondaryHomeActivity"))
    }

    @Test
    fun `own package with no class still means return to Mimir`() {
        assertTrue(meansReturn(appPackageName, null))
    }

    @Test
    fun `own primary transfer host does not mean return to Mimir`() {
        assertFalse(meansReturn(appPackageName, "$appPackageName.PrimaryTransferActivity"))
    }

    private fun meansReturn(packageName: String, className: String?): Boolean = usageEventMeansReturnToMimir(
        packageName = packageName,
        className = className,
        appPackageName = appPackageName,
        ownHostActivityClassNames = ownHosts,
    )
}
