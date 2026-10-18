package com.gruning.mimir.rewrite

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TrackpadSettingsLoadTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("mimir_trackpad", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        MimirTrackpadSettings.load(context)
    }

    @After fun cleanup() = reset()

    @Test fun `settings stay unavailable until saved preferences have loaded`() {
        prefs.edit().putBoolean("natural_scroll", false).putInt("speed_step", 5).commit()
        val frames = CopyOnWriteArrayList<Triple<Boolean, Boolean, Int>>()
        compose.setContent {
            val loaded by rememberTrackpadSettingsLoaded(context)
            val naturalScroll by MimirTrackpadSettings.naturalScroll.collectAsState()
            val speed by MimirTrackpadSettings.speedStep.collectAsState()
            frames += Triple(loaded, naturalScroll, speed)
        }
        assertFalse("no default settings model on the first composition", frames.first().first)
        compose.waitUntil(5_000L) { frames.any { it.first } }
        assertEquals(listOf(Triple(true, false, 5)), frames.filter { it.first }.distinct())
    }

    @Test fun `recomposition does not reload preferences over a later setting change`() {
        var tick by mutableStateOf(0)
        val frames = CopyOnWriteArrayList<Pair<Boolean, Int>>()
        compose.setContent {
            val loaded by rememberTrackpadSettingsLoaded(context)
            frames += loaded to tick
        }
        compose.waitUntil(5_000L) { frames.last().first }
        compose.runOnIdle {
            MimirTrackpadSettings.setSpeedStep(context, 4)
            // A different persisted value exposes an accidental reload on recomposition.
            prefs.edit().putInt("speed_step", 2).commit()
            tick++
        }
        compose.waitForIdle()
        assertEquals(4, MimirTrackpadSettings.speedStep.value)
        assertEquals(1, frames.last().second)
    }

    @Test fun `a live pad setting cannot be overwritten by an overlapping load`() {
        val readStarted = CountDownLatch(1)
        val resumeRead = CountDownLatch(1)
        val setterStarted = CountDownLatch(1)
        val setterFinished = CountDownLatch(1)
        val stored = prefs
        val gatedPrefs = object : SharedPreferences by stored {
            override fun getBoolean(key: String?, defaultValue: Boolean): Boolean {
                val value = stored.getBoolean(key, defaultValue)
                if (key == "natural_scroll") {
                    readStarted.countDown()
                    check(resumeRead.await(5, TimeUnit.SECONDS)) { "load was not released" }
                }
                return value
            }
        }
        val gatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = gatedPrefs
        }
        val loader = thread(name = "trackpad-load") { MimirTrackpadSettings.load(gatedContext) }
        var setter: Thread? = null
        try {
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            setter = thread(name = "trackpad-live-setter") {
                setterStarted.countDown()
                try {
                    MimirTrackpadSettings.setNaturalScroll(context, false)
                } finally {
                    setterFinished.countDown()
                }
            }
            assertTrue(setterStarted.await(5, TimeUnit.SECONDS))
            assertFalse("the setter must wait for the older read", setterFinished.await(150, TimeUnit.MILLISECONDS))
        } finally {
            resumeRead.countDown()
            loader.join(5_000L)
            setter?.join(5_000L)
        }
        assertFalse("the running pad keeps the newer setting", MimirTrackpadSettings.naturalScroll.value)
        assertFalse("and its saved preference agrees", prefs.getBoolean("natural_scroll", true))
    }
}
