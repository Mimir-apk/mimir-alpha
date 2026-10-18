package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A callback handed to a process-lifetime singleton must be taken back in `onDestroy`.
 *
 * `ControllerMappingStore` is a Kotlin `object`, so `onApplied` outlives every Activity. The value
 * assigned to it is `inputRouter::reset`, and `inputRouter`'s own closures capture `this@MainActivity`
 * (`Toast.makeText(this, …)`, `ControllerShortcutStore.capture(this, …)`). Leaving it set therefore pins
 * the destroyed Activity, its window and its whole Compose tree until the next `onCreate` overwrites the
 * field — and on these handhelds an Activity recreation is not exotic: attaching or detaching the second
 * screen does it.
 *
 * `onDestroy` already releases eleven other listeners by hand. This one was missed, which is exactly why
 * the rule is asserted here rather than trusted to review: the next singleton callback added to
 * `onCreate` should fail this test until it is released too.
 *
 * **The identity guard is the load-bearing half.** `onCreate` of a new instance can run before
 * `onDestroy` of the old one, so a teardown that clears unconditionally would silently unregister the
 * LIVE Activity's callback and leave controller remaps not reaching the router. `LaunchStore`'s
 * `dualScreenLaunchListener` in the same method is the shape being followed.
 */
class MainActivitySingletonListenerReleaseTest {

    private val source: String
        get() = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()

    private val onDestroyBody: String
        get() {
            val start = source.indexOf("override fun onDestroy()")
            assertTrue("onDestroy not found in MainActivity", start >= 0)
            return source.substring(start, minOf(start + 4000, source.length))
        }

    @Test
    fun `the controller-mapping applied callback is released in onDestroy`() {
        assertTrue(
            "MainActivity still hands ControllerMappingStore.onApplied to a process-lifetime object; " +
                "if that assignment is gone this test should be deleted, not weakened",
            source.contains("ControllerMappingStore.onApplied ="),
        )
        assertTrue(
            "onDestroy must take ControllerMappingStore.onApplied back, or the destroyed Activity stays " +
                "reachable from a singleton for the life of the process",
            onDestroyBody.contains("ControllerMappingStore.onApplied"),
        )
    }

    @Test
    fun `the release is guarded on identity, so a newer Activity is not unregistered`() {
        val guarded = Regex("""if\s*\(\s*ControllerMappingStore\.onApplied\s*===""")
        assertTrue(
            "clear it only when the singleton still holds THIS Activity's callback — onCreate of a new " +
                "instance can run before onDestroy of the old one, and an unguarded clear would " +
                "unregister the live one (see LaunchStore.dualScreenLaunchListener in the same method)",
            guarded.containsMatchIn(onDestroyBody),
        )
    }
}
