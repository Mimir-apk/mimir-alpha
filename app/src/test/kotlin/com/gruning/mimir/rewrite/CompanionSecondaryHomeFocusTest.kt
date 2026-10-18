package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the window shape and the input route that keep display 4 from ANR-ing the whole process.
 *
 * Measured on the AYN Thor 2026-09-11 09:34 (Mimir `468807603de9`): with
 * `CompanionSecondaryHomeActivity` resumed on display 4 and every Mimir window there non-focusable —
 * the floor, the `Presentation` (`FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL`) and the ᛗ overlay —
 * `dumpsys input` reported `FocusedDisplayId: 4` with `FocusedWindows:` empty. `RootWindowContainer`
 * makes a display with a focused app the top focused display regardless, so every key routed there and
 * waited: `"Waiting because no window has focus but ActivityRecord{… CompanionSecondaryHomeActivity}"`
 * → `"Pruning input queue …"` → `"ANR in com.gruning.mimir.rewrite … Input dispatching timed out
 * (Application does not have a focused window)"`. A trace of the same shape from 2026-09-10 23:15 is on
 * the device, so it recurs.
 *
 * Two facts hold the fix, and both are one token wide — a re-added flag or a dropped `Sink` call
 * restores the ANR with no test, no crash and no log to show for it. A JVM unit test cannot stand up a
 * real multi-display window stack (no device, no Robolectric focus model in this module), so the
 * production window is characterized as source — the same approach `MigrationImportContainerWiringTest`
 * uses for a window-boundary defect and `CompanionPrimaryResumePolicyTest` uses for `MainActivity`.
 */
class CompanionSecondaryHomeFocusTest {

    private fun codeOf(path: String): String {
        val file = File(path)
        assertTrue("$path must be readable", file.isFile)
        // Both flag names appear in the prose that explains the fix; only the code may be asserted on.
        return file.readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//.*"), "")
    }

    private fun floorSource(): String =
        codeOf("src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt")

    private fun sinkSource(): String =
        codeOf("src/main/kotlin/com/gruning/mimir/rewrite/MimirMainInputSink.kt")

    private fun mainActivitySource(): String = codeOf(
        requireNotNull(System.getProperty("mimir.mainActivitySource")) {
            "mimir.mainActivitySource system property missing — check app/build.gradle.kts testOptions"
        },
    )

    /** The method body from its header to the first closing brace at class-member indentation. */
    private fun memberBody(source: String, header: String): String {
        val start = source.indexOf(header)
        assertTrue("$header not found", start >= 0)
        val end = source.indexOf("\n    }", start)
        assertTrue("$header has no member-level close", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the floor is untouchable and is NOT non-focusable`() {
        val source = floorSource()
        assertTrue(
            "the floor must keep FLAG_NOT_TOUCHABLE: touches on display 4 belong to the Presentation " +
                "above it, including the ones it declines via NOT_TOUCH_MODAL",
            "window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)" in source,
        )
        assertFalse(
            "CompanionSecondaryHomeActivity must NOT set FLAG_NOT_FOCUSABLE. It is the only window Mimir " +
                "puts on display 4 that can hold key focus; without it display 4 has a focused app and no " +
                "focused window, and InputDispatcher ANRs the process instead of delivering a key " +
                "(Thor 2026-09-11 09:34).",
            "FLAG_NOT_FOCUSABLE" in source,
        )
    }

    @Test
    fun `both dispatch methods route display 4's events to the resumed Main`() {
        val source = floorSource()
        assertTrue(
            "the floor must override dispatchKeyEvent — a focusable window that does not is where the " +
                "keys stop",
            "override fun dispatchKeyEvent(event: KeyEvent): Boolean" in source,
        )
        assertTrue("keys go to the sink", "MimirMainInputSink.routeKey(event)" in source)
        assertTrue(
            "the floor must override dispatchGenericMotionEvent too: the Thor's D-pad arrives as " +
                "HAT-axis MotionEvents, so a key-only floor forwards buttons and swallows every direction",
            "override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean" in source,
        )
        assertTrue("motion goes to the sink", "MimirMainInputSink.routeMotion(event)" in source)
    }

    @Test
    fun `the floor asks the Bottom pad owner before Main's sink, for keys and for motion`() {
        // R9a hardening : a pad event that falls to the floor while the menu owns the Bottom's pad must
        // still reach the menu. Order is the whole pin: owner first, then Main's sink, then (never) super.
        val source = floorSource()
        val keyOwner = source.indexOf("BottomPadInput.routeKey(event)")
        val keySink = source.indexOf("MimirMainInputSink.routeKey(event)")
        assertTrue("the floor consults the owner for keys", keyOwner >= 0)
        assertTrue("and Main's sink still follows", keySink > keyOwner)
        val motionOwner = source.indexOf("BottomPadInput.routeMotion(event)")
        val motionSink = source.indexOf("MimirMainInputSink.routeMotion(event)")
        assertTrue("the floor consults the owner for motion", motionOwner >= 0)
        assertTrue("and Main's sink still follows", motionSink > motionOwner)
        assertTrue(
            "a system key still goes to super before the owner is asked: the framework owns volume and power",
            source.indexOf("if (isMimirSystemKey(event.keyCode)) return super.dispatchKeyEvent(event)") < keyOwner,
        )
        assertTrue("a consumed event returns true", "if (BottomPadInput.routeKey(event)) return true" in source)
        assertTrue("same for motion", "if (BottomPadInput.routeMotion(event)) return true" in source)
        assertFalse(
            "the floor's own focusability is untouched by R9a",
            "isFocusable" in source || "FLAG_NOT_FOCUSABLE" in source,
        )
    }

    @Test
    fun `nothing but a system key may fall through to super on the floor`() {
        val source = floorSource()
        assertEquals(
            "super.dispatchKeyEvent may appear exactly once on the floor — the volume/power line. " +
                "Anything else falling through lands on a window with nothing to receive it, which is " +
                "the wait this change exists to remove.",
            1,
            Regex("super\\.dispatchKeyEvent").findAll(source).count(),
        )
        assertTrue(
            "and that one occurrence must be gated on isMimirSystemKey",
            "if (isMimirSystemKey(event.keyCode)) return super.dispatchKeyEvent(event)" in source,
        )
        assertFalse(
            "generic motion has no system-owned subset, so it never reaches super",
            "super.dispatchGenericMotionEvent" in source,
        )
    }

    @Test
    fun `an absorbed event is reported once, not never and not per frame`() {
        val source = floorSource()
        assertTrue(
            "the absorbed-key log line is the only evidence that display 4 swallowed input with no " +
                "resumed Main; losing it makes the state indistinguishable from working",
            "Log.i(TAG, \"absorbed \$describe (Main not resumed)\")" in source,
        )
        assertTrue("keyed by keycode", "\"key=\${event.keyCode}\"" in source)
        assertTrue(
            "deduplicated — a held D-pad repeats at the input rate",
            "if (!seen.add(token)) return" in source,
        )
    }

    @Test
    fun `routeKey and routeMotion are the delegations the sink's unit test stands in for`() {
        val source = sinkSource()
        assertTrue(
            "MimirMainInputSinkTest exercises route() with a stand-in event because KeyEvent cannot be " +
                "built in a JVM test. That only covers the key lane while routeKey stays a one-line " +
                "delegation to it.",
            "fun routeKey(event: KeyEvent): MimirSecondaryInputRoute = route(keys, event)" in source,
        )
        assertTrue(
            "same for motion",
            "fun routeMotion(event: MotionEvent): MimirSecondaryInputRoute = route(motion, event)" in source,
        )
    }

    @Test
    fun `display 4 and display 0 enter MainActivity's authority at the same point`() {
        val source = mainActivitySource()
        assertTrue(
            "display 0's override must delegate to the shared body, or the two displays drift",
            "if (routeKeyEventThroughAuthority(event)) true else super.dispatchKeyEvent(event)" in source,
        )
        assertTrue(
            "and the lambda handed to the sink must call that same body",
            "{ routeKeyEventThroughAuthority(it) }" in source,
        )
        assertTrue(
            "same for motion, display 0",
            "if (routeGenericMotionEventThroughAuthority(event)) true else super.dispatchGenericMotionEvent(event)"
                in source,
        )
        assertTrue(
            "same for motion, display 4",
            "{ routeGenericMotionEventThroughAuthority(it) }" in source,
        )
        assertEquals(
            "MainActivity may keep exactly one super.dispatchKeyEvent — the one in the override above. A " +
                "second would mean the shared body kept a fallback the floor has no window for.",
            1,
            Regex("super\\.dispatchKeyEvent").findAll(source).count(),
        )
        assertEquals(
            "and exactly one super.dispatchGenericMotionEvent",
            1,
            Regex("super\\.dispatchGenericMotionEvent").findAll(source).count(),
        )
    }

    @Test
    fun `the sink is registered while Main is resumed and released only by its own instance`() {
        val source = mainActivitySource()
        assertTrue(
            "registration must be inside onResume, and before the database-recovery early return: " +
                "display 0's dispatchKeyEvent is not gated on that failure either",
            "MimirMainInputSink.register(keys = secondaryDisplayKeySink, motion = secondaryDisplayMotionSink)"
                in memberBody(source, "override fun onResume() {"),
        )
        assertTrue(
            "release must be inside onPause",
            "MimirMainInputSink.release(keys = secondaryDisplayKeySink, motion = secondaryDisplayMotionSink)"
                in memberBody(source, "override fun onPause() {"),
        )
        assertTrue(
            "the registered lambdas must be instance vals: a fresh ::method reference is a new object " +
                "every time, so the identity guard in release() would never match and a successor's " +
                "registration would be cleared by its predecessor's onPause",
            "private val secondaryDisplayKeySink: (KeyEvent) -> Boolean" in source &&
                "private val secondaryDisplayMotionSink: (MotionEvent) -> Boolean" in source,
        )
    }
}
