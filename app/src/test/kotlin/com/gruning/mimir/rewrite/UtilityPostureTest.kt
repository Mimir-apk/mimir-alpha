package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UtilityPostureTest {
    @Test
    fun utilityPermissionsStayExplicitAndDoNotTripDiagnosticsPosture() {
        val permissions = listOf(
            PostureReadout.PERMISSION_VIBRATE,
            PostureReadout.PERMISSION_WRITE_SETTINGS,
            PostureReadout.PERMISSION_MODIFY_AUDIO,
        )
        val posture = PostureReadout(
            appId = "com.gruning.mimir.rewrite",
            versionName = "test",
            declaredPermissions = permissions,
            modules = emptyList(),
        )

        assertEquals(emptyList<String>(), posture.unexpectedPermissions)
        permissions.forEach { permission ->
            assertFalse(posture.purposeOf(permission).startsWith("UNEXPECTED"))
        }
        assertEquals(
            "User-approved main-display brightness changes from Utility.",
            posture.purposeOf(PostureReadout.PERMISSION_WRITE_SETTINGS),
        )
    }
}
