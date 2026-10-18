package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import com.gruning.mimir.rewrite.core.ui.MimirCalmShineSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Utility's Calm shine switch, the durable half. It is off on a fresh install,
 * turning it on is written to the appearance file the ring stops already use, and a new process loads it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalmShineSettingTest {

    private lateinit var app: Application

    private fun appearanceFile() = app.getSharedPreferences("mimir_rewrite_appearance", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        appearanceFile().edit().clear().commit()
        MimirCalmShineSettings.enabled = false
    }

    @After
    fun tearDown() {
        MimirCalmShineSettings.enabled = false
        // The store writes Compose snapshot state outside any composition, and Compose applies such writes from work it
        // posts to the main looper, which this non-Compose test never idles (CompanionThemeSeedTest's teardown too).
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a fresh install has calm shine off and loading says so`() {
        MimirCalmShineSettings.enabled = true // whatever a stale holder held
        AppearanceOverrideStore.load(app)
        assertFalse("nothing stored means the shipped shine", MimirCalmShineSettings.enabled)
    }

    @Test
    fun `turning calm shine on is written to the appearance file and a fresh load reads it back`() {
        AppearanceOverrideStore.setCalmShine(app, true)
        assertTrue(MimirCalmShineSettings.enabled)
        assertTrue(appearanceFile().getBoolean("calmShine", false))

        // A new process: the holder starts over and the load restores what was stored.
        MimirCalmShineSettings.enabled = false
        AppearanceOverrideStore.load(app)
        assertTrue("the switch survives a relaunch", MimirCalmShineSettings.enabled)
    }

    @Test
    fun `turning it off again is remembered too`() {
        AppearanceOverrideStore.setCalmShine(app, true)
        AppearanceOverrideStore.setCalmShine(app, false)
        assertFalse(MimirCalmShineSettings.enabled)
        assertFalse(appearanceFile().getBoolean("calmShine", true))

        MimirCalmShineSettings.enabled = true // a stale holder
        AppearanceOverrideStore.load(app)
        assertFalse(MimirCalmShineSettings.enabled)
    }

    @Test
    fun `the switch does not tick the appearance revision that re-pushes the overlay theme`() {
        val before = AppearanceOverrideStore.revision.value
        AppearanceOverrideStore.setCalmShine(app, true)
        assertEquals(before, AppearanceOverrideStore.revision.value)
    }

    @Test
    fun `calm shine shares the appearance file, so its prefetch and its process start load already cover it`() {
        val store = appSource("AppearanceOverrideStore.kt")
        assertTrue(store.contains("private const val PREFS = \"mimir_rewrite_appearance\""))
        assertTrue(store.contains("private const val KEY_CALM_SHINE = \"calmShine\""))
        assertTrue(appSource("MimirPrefsPrefetch.kt").contains("\"mimir_rewrite_appearance\""))
        val seed = appSource("MimirApplication.kt").substringAfter("fun seedCompanionThemeAtProcessStart(")
        assertTrue("process start loads the appearance file", seed.contains("AppearanceOverrideStore.load(context)"))
    }

    private fun appSource(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
