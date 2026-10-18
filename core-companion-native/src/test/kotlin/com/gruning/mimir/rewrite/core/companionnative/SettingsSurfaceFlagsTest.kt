package com.gruning.mimir.rewrite.core.companionnative

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay and Usage Access Settings screens must not get their own Recents card (device-observed
 * 2026-10-01). From Mimir's own Activity they join its task; the application-context start stays the
 * fallback and still needs NEW_TASK.
 */
class SettingsSurfaceFlagsTest {
    private val newTask = Intent.FLAG_ACTIVITY_NEW_TASK

    @Test
    fun `from an Activity the screen joins Mimir's task`() {
        assertEquals(0, settingsSurfaceFlags(newTask, hasHostActivity = true))
    }

    @Test
    fun `only NEW_TASK is dropped, other flags survive`() {
        val other = Intent.FLAG_ACTIVITY_NO_ANIMATION
        assertEquals(other, settingsSurfaceFlags(newTask or other, hasHostActivity = true))
    }

    @Test
    fun `with no Activity the old application-context behaviour keeps NEW_TASK`() {
        assertTrue(settingsSurfaceFlags(0, hasHostActivity = false) and newTask != 0)
        assertTrue(settingsSurfaceFlags(newTask, hasHostActivity = false) and newTask != 0)
    }

    @Test
    fun `no Activity is tracked until one resumes`() {
        assertNull(ResumedHostActivity.current())
    }
}
