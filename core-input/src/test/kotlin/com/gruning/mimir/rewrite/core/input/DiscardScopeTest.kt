package com.gruning.mimir.rewrite.core.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [FocusAuthority.discardScope] — removes a scope from the stack without transferring focus, used by `MimirKeyboardFocusBinding.exit()` (`MimirKeyboardFocusTest.kt`'s
 * `exitDiscardsTheKeyboardScopeEvenWhenAnotherScopeWasPushedOverIt`). These tests pin the primitive
 * itself, independent of the keyboard call site.
 */
class DiscardScopeTest {

    private fun box(l: Float, t: Float): FocusBounds = FocusBounds(l, t, l + 100f, t + 100f)

    @Test
    fun discardingAScopeBuriedUnderAnActiveScopeEmitsNoStateChange() {
        val fa = DefaultFocusAuthority()
        val buried = FocusScope("buried")
        val covering = FocusScope("covering")
        fa.pushScope(buried)
        fa.register(FocusKey("buried/key"), buried, box(0f, 0f))
        fa.pushScope(covering)
        val coveringKey = FocusKey("covering/key")
        fa.register(coveringKey, covering, box(0f, 0f))
        fa.requestFocus(coveringKey)

        val before = fa.state.value

        // Unconfined + single-threaded JUnit: collect() runs synchronously up to its first suspension,
        // so this actually observes every emission discardScope causes before the next line runs -- not
        // a fire-and-forget collector that might miss one. A collector-based count is the strict test:
        // an emit-then-restore-the-same-instance implementation would pass assertSame below but is
        // EXACTLY the transient-state shape this method exists to avoid (the rejected peel/push-back
        // workaround was one), so it must fail this assertion instead.
        val emissions = mutableListOf<FocusState>()
        val collectorJob = CoroutineScope(Job() + Dispatchers.Unconfined).launch {
            fa.state.collect { emissions += it }
        }

        fa.discardScope(buried)
        collectorJob.cancel()

        assertEquals(
            "removing a non-active scope must publish ZERO emissions -- not the same value twice",
            listOf(before),
            emissions,
        )
        // Reference equality, not just equal fields: proves _state.value was never reassigned at all,
        // not merely reassigned to an equal value. The covering scope must not see so much as a no-op
        // "transition" for a scope it never knew was underneath it.
        assertSame(
            "removing a non-active scope must not publish any focus transition to observers",
            before,
            fa.state.value,
        )
    }

    @Test
    fun discardingABuriedScopeRemovesItSoALaterPopNeverLandsBackOnIt() {
        val fa = DefaultFocusAuthority()
        val buried = FocusScope("buried")
        val covering = FocusScope("covering")
        fa.pushScope(buried)
        fa.pushScope(covering)
        fa.register(FocusKey("covering/key"), covering, box(0f, 0f))
        fa.requestFocus(FocusKey("covering/key"))

        fa.discardScope(buried)
        fa.popScope() // simulates the covering surface (e.g. Brightness) closing normally

        assertEquals(
            "the discarded scope must be gone from the stack, not merely skipped once",
            FocusScope.Root,
            fa.state.value.scope,
        )
    }

    @Test
    fun discardingTheActiveScopeBehavesExactlyLikePopScope() {
        val fa = DefaultFocusAuthority()
        val shelf = FocusKey("shelf/ps1")
        fa.register(shelf, FocusScope.Root, box(0f, 0f))
        fa.requestFocus(shelf)

        val picker = FocusScope("picker")
        fa.pushScope(picker)
        fa.register(FocusKey("picker/opt0"), picker, box(0f, 0f))

        fa.discardScope(picker)

        assertEquals(FocusScope.Root, fa.state.value.scope)
        assertEquals("popScope's own focus-memory restoration must still run", shelf, fa.state.value.focused)
    }

    @Test
    fun discardingAScopeNotOnTheStackIsANoOp() {
        val fa = DefaultFocusAuthority()
        fa.register(FocusKey("root/a"), FocusScope.Root, box(0f, 0f))
        fa.requestFocus(FocusKey("root/a"))
        val before = fa.state.value

        // Models exit() racing a DisposableEffect teardown and running twice against a scope that is
        // no longer, or never was, on the stack.
        fa.discardScope(FocusScope("never-pushed"))

        assertSame(
            "a scope that was never pushed (or already removed) must not touch state at all",
            before,
            fa.state.value,
        )
    }

    @Test
    fun discardingRootIsRefused() {
        val fa = DefaultFocusAuthority()
        val other = FocusScope("other")
        fa.pushScope(other)

        fa.discardScope(FocusScope.Root)
        fa.popScope() // pops `other`
        fa.popScope() // would leave the deque empty (or throw) if Root had actually been removed

        assertEquals(FocusScope.Root, fa.state.value.scope)
    }

    /** Pins the INTERFACE DEFAULT body, for implementations that don't override [FocusAuthority.discardScope]. */
    @Test
    fun interfaceDefaultFallsBackToPopScopeOnlyWhenActive() {
        var popCount = 0
        val authority = object : FocusAuthority {
            private val _state = MutableStateFlow(FocusState(FocusScope.Root, null))
            override val state = _state
            override fun register(key: FocusKey, scope: FocusScope, bounds: FocusBounds) {}
            override fun unregister(key: FocusKey) {}
            override fun requestFocus(key: FocusKey) {}
            override fun moveFocus(direction: ControllerAction): Boolean = false
            override fun pushScope(scope: FocusScope) {
                _state.value = FocusState(scope, null)
            }

            override fun popScope() {
                popCount += 1
                _state.value = FocusState(FocusScope.Root, null)
            }
        }

        authority.pushScope(FocusScope("covering"))
        authority.discardScope(FocusScope("buried-this-double-cannot-model-a-real-stack"))
        assertEquals("not active -> default body must not call popScope", 0, popCount)

        authority.discardScope(FocusScope("covering"))
        assertEquals("active -> default body delegates to popScope", 1, popCount)
    }
}
