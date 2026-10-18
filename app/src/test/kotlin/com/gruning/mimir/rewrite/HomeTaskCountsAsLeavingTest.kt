package com.gruning.mimir.rewrite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTaskCountsAsLeavingTest {
    private val selfPackage = "com.gruning.mimir.rewrite"
    private val homePackages = setOf(selfPackage, "com.android.launcher3")
    private val ignoredOwnActivities = setOf("$selfPackage.CompanionReturnActivity")

    @Test
    fun `launcher3 counts as leaving`() {
        assertTrue(counts("com.android.launcher3", "com.android.launcher3.Launcher"))
    }

    @Test
    fun `MainActivity still counts as leaving`() {
        assertTrue(counts(selfPackage, "$selfPackage.MainActivity"))
    }

    @Test
    fun `ignored own Bottom host does not count as leaving`() {
        assertFalse(counts(selfPackage, "$selfPackage.CompanionReturnActivity"))
    }

    @Test
    fun `non-home app does not count as leaving`() {
        assertFalse(counts("com.android.chrome", "com.google.android.apps.chrome.Main"))
    }

    @Test
    fun `missing top activity does not count as leaving`() {
        assertFalse(counts(null, null))
    }

    private fun counts(topPackage: String?, topClass: String?): Boolean = homeTaskCountsAsLeaving(
        topPackage = topPackage,
        topClass = topClass,
        selfPackage = selfPackage,
        homePackages = homePackages,
        ignoredOwnActivities = ignoredOwnActivities,
    )
}
