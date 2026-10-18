package com.gruning.mimir.rewrite

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * P2-3 (code review of F12, 2026-10-08): the floor recreate must not put a floor over Main. A recreate is aimed at the reclaimed display, but on a
 * presentation display where the platform never places a secondary home the start could land on display 0. Two guards, both pinned here: the recreate
 * needs a display where the floor has been seen before (kept in preferences, which `install -r` keeps), and a floor created on the default display ends
 * itself before it claims or draws anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionFloorRecreateGuardsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun restoreGuard() {
        CompanionFloorDisplayGuard.refuseDefaultDisplay = true
    }

    @Test
    fun `a display the floor has run on is remembered in preferences, which an update keeps`() {
        assertFalse("nothing is recorded yet", CompanionFloorDisplays.seen(context, 4))
        CompanionFloorDisplays.record(context, 4)
        assertTrue(CompanionFloorDisplays.seen(context, 4))
        assertFalse("only the display it ran on", CompanionFloorDisplays.seen(context, 5))
        val onDisk = context.getSharedPreferences("mimir-floor-displays", Context.MODE_PRIVATE).getStringSet("seen", emptySet())
        assertEquals("it is in the app's preferences file, which install -r keeps with the rest of the data", setOf("4"), onDisk)
        CompanionFloorDisplays.record(context, 5)
        assertEquals("a second display adds to the set", setOf("4", "5"), context.getSharedPreferences("mimir-floor-displays", Context.MODE_PRIVATE).getStringSet("seen", emptySet()))
    }

    @Test
    fun `the default and unknown displays are never recorded as places the floor belongs`() {
        CompanionFloorDisplays.record(context, 0)
        CompanionFloorDisplays.record(context, -1)
        assertFalse(CompanionFloorDisplays.seen(context, 0))
        assertFalse(CompanionFloorDisplays.seen(context, -1))
    }

    @Test
    fun `a recreate on a display never seen is refused with the real store behind the plan`() {
        val seen = { display: Int -> CompanionFloorDisplays.seen(context, display) }
        assertEquals("floor_never_seen", planFloorFront(FloorTaskAbsentRefusal, 6, seen(6), FloorRecreateThrottle(), 1_000L).refusal)
        CompanionFloorDisplays.record(context, 6)
        assertTrue(planFloorFront(FloorTaskAbsentRefusal, 6, seen(6), FloorRecreateThrottle(), 1_000L).recreate)
    }

    @Test
    fun `a floor created on the default display ends at once and arms nothing`() {
        withFloorContext {
            val controller = Robolectric.buildActivity(CompanionSecondaryHomeActivity::class.java).create()
            try {
                val floor: Activity = controller.get()
                assertEquals("fixture: Robolectric's only display is the default one", 0, floor.display?.displayId)
                assertTrue("the floor on Main ends in onCreate", floor.isFinishing)
                assertFalse("it did not arm the cold-start handoff", CompanionColdStartHandoff.isArmed(0))
                assertFalse("and it recorded nothing as a place the floor belongs", CompanionFloorDisplays.seen(context, 0))
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun `the floor calls the guard and the store before it claims or draws anything`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt").readText()
        val guard = source.indexOf("CompanionFloorDisplayGuard.refuses(claimedDisplay)")
        val record = source.indexOf("CompanionFloorDisplays.record(applicationContext, claimedDisplay)")
        assertTrue(guard >= 0 && record > guard)
        assertTrue("before the window flags", record < source.indexOf("window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)"))
        assertTrue("before the cold-start handoff arms", record < source.indexOf("CompanionColdStartHandoff.floorCreated(floorDisplay)"))
        assertTrue("before setContent", record < source.indexOf("setContent {"))
        val ended = source.substring(guard, record)
        assertTrue("it finishes and returns", "finish()" in ended && "return" in ended)
        assertTrue("and logs why", "Log.w(TAG," in ended)
    }

    /** Robolectric attaches every Activity to a non-UI base context, which cannot answer `display`; the harness marks it an activity context for the call. */
    private fun withFloorContext(block: () -> Unit) {
        val base = RuntimeEnvironment.getApplication().baseContext
        val originalType = ReflectionHelpers.getField<Int>(base, "mContextType")
        ReflectionHelpers.setField(base, "mContextType", ReflectionHelpers.getStaticField<Int>(base.javaClass, "CONTEXT_TYPE_ACTIVITY"))
        try {
            block()
        } finally {
            ReflectionHelpers.setField(base, "mContextType", originalType)
        }
    }
}
