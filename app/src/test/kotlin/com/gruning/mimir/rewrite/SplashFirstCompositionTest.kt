package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import com.gruning.mimir.rewrite.feature.library.WarmupLedger
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

/**
 * splash-first composition in [MimirColdStartWelcome] .
 *
 * On the splash path the first composition holds the splash and NOT the app content; the content composes only when the
 * mount gate fires, under the splash and with the input hold still armed. Every other start composes the content with
 * the first composition, and the beat, the ledger linger and the hold release are unchanged.
 *
 * The gate is a fake here. What it stands for on a device, the frame-commit handshake that lets the draw report go first,
 * is pinned as a sequence in SplashFirstDrawHandshakeTest and SplashFirstDrawPortsTest. This harness's frame clock is not
 * the device's draw report, so the real order is device-owed.
 *
 * Not pinned here: the [WelcomeWarmDeadlineMs] cap. Under this harness the beat's `delay` follows the hand-driven clock
 * but the linger's `withTimeoutOrNull` did not (an unsettled ledger still held the splash 13 s in), so a cap test would
 * only measure the harness. The cap's budget stays pinned by WelcomeHoldContractTest, and its code is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SplashFirstCompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeGate : SplashContentMountGate {
        var armed = 0
        var cancelled = 0
        private var onMount: ((SplashFirstMountReason) -> Unit)? = null

        override fun arm(onMount: (SplashFirstMountReason) -> Unit): () -> Unit {
            armed++
            this.onMount = onMount
            return { cancelled++ }
        }

        fun mount() {
            onMount?.invoke(SplashFirstMountReason.HANDSHAKE)
        }
    }

    private var contentFirstCompositions = 0
    private var holdBefore = false
    private var settledBefore = false

    @Before
    fun setUp() {
        holdBefore = WelcomeHold.active
        settledBefore = WarmupLedger.settled.value
        WarmupLedger.settled.value = false
        // The beat runs on the harness clock, driven by hand below (the house pattern: CompanionLaunchLockedTapTest).
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        WelcomeHold.active = holdBefore
        WarmupLedger.settled.value = settledBefore
    }

    private fun welcome(returningUser: Boolean, gate: SplashContentMountGate?) {
        // MainActivity arms the input hold before the first frame on the splash path only (MainActivity.kt:870).
        WelcomeHold.active = returningUser
        compose.setContent {
            MimirColdStartWelcome(returningUser = returningUser, contentMountGate = gate) {
                remember {
                    contentFirstCompositions++
                    true
                }
                Text("app content", Modifier.testTag(ContentTag))
            }
        }
    }

    /**
     * Fires the gate, then lets the state write reach a recomposition. The idle runs Robolectric's main looper, which
     * carries the snapshot apply notification to the recomposer; the frames on the hand-driven clock recompose.
     */
    private fun mountThroughGate(gate: FakeGate) {
        compose.runOnUiThread { gate.mount() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(MountSettleMs)
        compose.waitForIdle()
    }

    private fun splashShown() =
        compose.onAllNodesWithText(SplashLockup, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun contentShown() =
        compose.onAllNodesWithTag(ContentTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** True when the content sits below the splash in the root's z-ordered children (content first, splash on top). */
    private fun contentBelowSplash(): Boolean {
        fun SemanticsNode.has(predicate: (SemanticsNode) -> Boolean): Boolean =
            predicate(this) || children.any { it.has(predicate) }
        val children = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().children
        val contentIndex = children.indexOfFirst { child ->
            child.has { it.config.getOrNull(SemanticsProperties.TestTag) == ContentTag }
        }
        val splashIndex = children.indexOfFirst { child ->
            child.has { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == SplashLockup } == true }
        }
        return contentIndex >= 0 && splashIndex > contentIndex
    }

    @Test
    fun `returning user - the first composition composes the splash and not the content`() {
        val gate = FakeGate()
        welcome(returningUser = true, gate = gate)

        assertEquals("the content must not compose in the splash's first composition", 0, contentFirstCompositions)
        assertFalse(contentShown())
        assertTrue(splashShown())
        assertEquals("the first composition arms the mount gate once", 1, gate.armed)

        compose.mainClock.advanceTimeBy(500)

        assertEquals("frames alone do not mount the content; only the gate does", 0, contentFirstCompositions)
        assertEquals(1, gate.armed)
    }

    @Test
    fun `returning user - the content composes when the gate mounts it, under the splash and the input hold`() {
        val gate = FakeGate()
        welcome(returningUser = true, gate = gate)
        assertEquals(0, contentFirstCompositions)
        assertEquals(1, gate.armed)

        mountThroughGate(gate)

        assertEquals(1, contentFirstCompositions)
        assertTrue(contentShown())
        assertTrue("the splash still covers the content", splashShown())
        assertTrue("the content is below the splash, not over it", contentBelowSplash())
        assertTrue("the input hold stays armed through the mount", WelcomeHold.active)
        assertEquals("the gate leaves composition with the mount", 1, gate.cancelled)
    }

    @Test
    fun `first run - the content composes in the first composition and no gate is armed`() {
        val gate = FakeGate()
        welcome(returningUser = false, gate = gate)

        assertEquals(1, contentFirstCompositions)
        assertTrue(contentShown())
        assertFalse("onboarding owns the first run: no splash", splashShown())
        assertEquals(0, gate.armed)
    }

    @Test
    fun `first run cold start - the first frame is empty and the content mounts through the gate, with no splash`() {
        val gate = FakeGate()
        WelcomeHold.active = false
        compose.setContent {
            MimirColdStartWelcome(returningUser = false, contentMountGate = gate, firstRunColdStart = true) {
                remember {
                    contentFirstCompositions++
                    true
                }
                Text("app content", Modifier.testTag(ContentTag))
            }
        }

        assertEquals("the first frame composes nothing of the app", 0, contentFirstCompositions)
        assertFalse(contentShown())
        assertFalse("a first run has no splash", splashShown())
        assertEquals("the first composition arms the mount gate once", 1, gate.armed)

        mountThroughGate(gate)

        assertEquals(1, contentFirstCompositions)
        assertTrue(contentShown())
        assertFalse(splashShown())
        assertFalse("a first run never arms the input hold", WelcomeHold.active)
        assertEquals("the gate leaves composition with the mount", 1, gate.cancelled)
    }

    @Test
    fun `no frame-commit handshake - a returning user's content still composes in the first composition`() {
        welcome(returningUser = true, gate = null)

        assertEquals(1, contentFirstCompositions)
        assertTrue(contentShown())
        assertTrue(splashShown())
        assertTrue(contentBelowSplash())
    }

    @Test
    fun `hold - the 3_2 s beat is still the floor, and the input hold is released only when the splash layer goes`() {
        WarmupLedger.settled.value = true
        val gate = FakeGate()
        welcome(returningUser = true, gate = gate)
        mountThroughGate(gate) // t = 100 ms on the harness clock

        compose.mainClock.advanceTimeBy(2_900) // t = 3.0 s
        assertTrue("before the beat ends the splash holds", splashShown())
        assertTrue(WelcomeHold.active)

        // t = 3.26 s: the beat ended with the ledger settled and the 180 ms fade is running.
        compose.mainClock.advanceTimeBy(260)
        assertTrue("the splash's outgoing pixels are still composed", splashShown())
        assertTrue("the input hold outlives the fade", WelcomeHold.active)

        compose.mainClock.advanceTimeBy(700) // t = 3.96 s
        assertFalse(splashShown())
        assertFalse("the hold is released when the splash layer is disposed", WelcomeHold.active)
        assertTrue(contentShown())
    }

    @Test
    fun `hold - an unsettled ledger keeps the splash past the beat until it settles`() {
        val gate = FakeGate()
        welcome(returningUser = true, gate = gate)
        mountThroughGate(gate)

        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("past the beat, an unsettled ledger keeps the splash", splashShown())
        assertTrue(WelcomeHold.active)

        compose.runOnUiThread { WarmupLedger.settled.value = true }
        compose.mainClock.advanceTimeBy(800)

        assertFalse(splashShown())
        assertFalse(WelcomeHold.active)
    }

    private companion object {
        const val ContentTag = "splash-first-content"
        const val SplashLockup = "M I M I R"
        const val MountSettleMs = 100L
    }
}
