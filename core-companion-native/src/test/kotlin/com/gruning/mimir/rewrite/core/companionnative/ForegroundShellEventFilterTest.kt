package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundShellEventFilterTest {
    private val shellPackages = setOf("com.android.launcher3")
    private val shellActivities = setOf("com.android.settings.FallbackHome")

    @Test
    fun `fallback home capability does not hide another activity in the same package`() {
        assertFalse(
            isNonForeignShellEvent(
                packageName = "com.android.settings",
                className = "com.android.settings.homepage.SettingsHomepageActivity",
                shellPackages = shellPackages,
                shellActivityClasses = shellActivities,
            ),
        )
    }

    @Test
    fun `actual home activity and known launcher package remain excluded`() {
        assertTrue(
            isNonForeignShellEvent(
                packageName = "com.android.settings",
                className = "com.android.settings.FallbackHome",
                shellPackages = shellPackages,
                shellActivityClasses = shellActivities,
            ),
        )
        assertTrue(
            isNonForeignShellEvent(
                packageName = "com.android.launcher3",
                className = "com.android.launcher3.QuickstepLauncher",
                shellPackages = shellPackages,
                shellActivityClasses = shellActivities,
            ),
        )
    }
}
