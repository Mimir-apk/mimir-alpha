package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.view.Display
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper

/**
 * The context a toast about something done on another display is made from ([companionFeedbackContext]). A text toast
 * appears on the context's `getDisplayId()`, which `Toast.show()` hands the notification service. That method is hidden
 * API, so these tests read it reflectively from the framework jar Robolectric runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class CompanionFeedbackContextTest {

    private lateinit var base: Context
    private var secondaryDisplayId = Display.INVALID_DISPLAY

    @Before
    fun setUp() {
        base = RuntimeEnvironment.getApplication()
        secondaryDisplayId = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        ShadowDisplayManager.removeDisplay(secondaryDisplayId)
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a secondary display gets a context on that display`() {
        val context = companionFeedbackContext(base, secondaryDisplayId)

        assertNotSame("a display context, not the process context", base, context)
        assertEquals(secondaryDisplayId, displayIdOf(context))
    }

    @Test
    fun `no display keeps the process context`() {
        assertSame(base, companionFeedbackContext(base, null))
    }

    @Test
    fun `the default display keeps the process context`() {
        assertSame(base, companionFeedbackContext(base, Display.DEFAULT_DISPLAY))
        assertEquals("the process context is already on the default display", Display.DEFAULT_DISPLAY, displayIdOf(base))
    }

    @Test
    fun `a display that has gone keeps the process context`() {
        val gone = ShadowDisplayManager.addDisplay("+land", 2)
        ShadowDisplayManager.removeDisplay(gone)
        ShadowLooper.idleMainLooper()

        assertSame(base, companionFeedbackContext(base, gone))
    }

    private fun displayIdOf(context: Context): Int =
        Context::class.java.getMethod("getDisplayId").invoke(context) as Int
}
