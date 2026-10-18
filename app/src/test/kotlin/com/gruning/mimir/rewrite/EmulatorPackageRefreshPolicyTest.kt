package com.gruning.mimir.rewrite

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorPackageRefreshPolicyTest {
    private val tracked = setOf(
        "org.dolphinemu.dolphinemu",
        "com.armsx2",
    )

    @Test
    fun exactTrackedPackageChangesRefreshEmulatorTruth() {
        listOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
        ).forEach { action ->
            assertTrue(
                shouldRefreshEmulatorPackages(
                    action = action,
                    packageName = "org.dolphinemu.dolphinemu",
                    trackedPackages = tracked,
                ),
            )
        }
    }

    @Test
    fun unrelatedOrMalformedBroadcastsDoNotInvalidateEmulatorTruth() {
        assertFalse(
            shouldRefreshEmulatorPackages(
                action = Intent.ACTION_PACKAGE_ADDED,
                packageName = "com.example.unrelated",
                trackedPackages = tracked,
            ),
        )
        assertFalse(
            shouldRefreshEmulatorPackages(
                action = Intent.ACTION_TIME_CHANGED,
                packageName = "com.armsx2",
                trackedPackages = tracked,
            ),
        )
        assertFalse(shouldRefreshEmulatorPackages(null, null, tracked))
    }
}
