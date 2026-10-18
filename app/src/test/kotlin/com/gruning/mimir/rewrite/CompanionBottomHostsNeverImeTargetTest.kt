package com.gruning.mimir.rewrite

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/**
 * KBD-T (Thor 2026-10-06): the vendor build routes the system IME to display 4, and a focusable Mimir window there
 * was WindowManager's IME layering, input and control target, so a SHOW_SOFT_INPUT from Chrome on Main landed on it
 * and no keyboard appeared anywhere. The fix is FLAG_ALT_FOCUSABLE_IM on every focusable Mimir host window that never
 * needs a system keyboard; never FLAG_NOT_FOCUSABLE, because the floor's key-focus role must not change.
 *
 * AOSP 13 `WindowState.canBeImeTarget`: with `fl = flags & (NOT_FOCUSABLE | ALT_FOCUSABLE_IM)`, a window can be the
 * target only when fl is 0 or both bits. [canBeImeTarget] below is that rule. The floor is checked on a real
 * Robolectric window; the return host and the transfer trampoline are checked as source, as the other window-shape
 * pins in this module are (their onCreate fails closed without a live coordinator operation, but the flag is set
 * before the first early return, and the pin asserts that order).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionBottomHostsNeverImeTargetTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        CompanionReturnCoordinator.installRuntime(
            CompanionRuntimeCoordinator(CompanionRuntimeScheduler { _, _, _ -> RuntimeCancellation {} }),
        )
        CompanionState.set(CompanionPayload.idle())
    }

    @After
    fun tearDown() {
        CompanionState.set(CompanionPayload.idle())
        CompanionReturnCoordinator.resetTestOverrides()
    }

    /** AOSP 13 WindowState.canBeImeTarget's flag rule (application windows). */
    private fun canBeImeTarget(flags: Int): Boolean {
        val both = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        val fl = flags and both
        return fl == 0 || fl == both
    }

    @Test
    fun theRuleItselfIsWhatTheFlagRelies() {
        assertTrue("an ordinary focusable window is an IME target", canBeImeTarget(0))
        assertFalse("NOT_FOCUSABLE alone is not", canBeImeTarget(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
        assertFalse("ALT_FOCUSABLE_IM alone is not", canBeImeTarget(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM))
    }

    @Test
    fun theSecondaryHomeWindowIsFocusableButNeverTheImeTarget() {
        withFloor { floor ->
            val flags = floor.window.attributes.flags
            assertTrue("ALT_FOCUSABLE_IM set", flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0)
            assertEquals("NOT_FOCUSABLE NOT set", 0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            assertTrue("still untouchable", flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertFalse("so it cannot be the IME target", canBeImeTarget(flags))
        }
    }

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("$path must be readable", file.isFile)
        return file.readText().replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//.*"), "")
    }

    @Test
    fun theReturnHostAndTheTransferTrampolineSetItBeforeAnyEarlyReturn() {
        val returnHost = source("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnActivity.kt")
        val flag = "FLAG_ALT_FOCUSABLE_IM"
        val returnOnCreate = returnHost.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue(returnOnCreate.indexOf(flag) in 0 until returnOnCreate.indexOf("return\n"))
        assertFalse("never made non-focusable", "addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE" in returnHost)

        val transfer = source("src/main/kotlin/com/gruning/mimir/rewrite/PrimaryTransferActivity.kt")
        val transferOnCreate = transfer.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue(transferOnCreate.indexOf(flag) in 0 until 400)
    }

    @Test
    fun thePresentationAndOverlaysAreAlreadyNeverTargetsAndTheFloorStillHasNoNotFocusable() {
        // The Presentation is NOT_FOCUSABLE alone: canBeImeTarget() is false for it (and it cannot take typing).
        assertFalse(canBeImeTarget(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL))
        val floor = source("src/main/kotlin/com/gruning/mimir/rewrite/CompanionSecondaryHomeActivity.kt")
        assertFalse("FLAG_NOT_FOCUSABLE" in floor)
    }

    private fun withFloor(block: (Activity) -> Unit) {
        val base = RuntimeEnvironment.getApplication().baseContext
        val originalType = ReflectionHelpers.getField<Int>(base, "mContextType")
        ReflectionHelpers.setField(
            base,
            "mContextType",
            ReflectionHelpers.getStaticField<Int>(base.javaClass, "CONTEXT_TYPE_ACTIVITY"),
        )
        try {
            // P2-3: the floor ends itself on the default display, which is the only display Robolectric has.
            CompanionFloorDisplayGuard.refuseDefaultDisplay = false
            val controller = Robolectric.buildActivity(CompanionSecondaryHomeActivity::class.java).setup()
            try {
                compose.waitForIdle()
                block(controller.get())
            } finally {
                controller.pause().stop().destroy()
            }
        } finally {
            ReflectionHelpers.setField(base, "mContextType", originalType)
            CompanionFloorDisplayGuard.refuseDefaultDisplay = true
        }
    }
}
