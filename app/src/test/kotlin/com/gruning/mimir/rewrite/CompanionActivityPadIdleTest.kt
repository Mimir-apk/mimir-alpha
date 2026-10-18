package com.gruning.mimir.rewrite

import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.companionnative.BottomPadInput
import com.gruning.mimir.rewrite.core.companionnative.IdleWakeSamples
import com.gruning.mimir.rewrite.core.companionnative.PAD_WAKE_LATCH_MS
import com.gruning.mimir.rewrite.core.companionnative.PadIdleGate
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bottom's Activity page pad and the screen-idle rule . The page's route sits AHEAD of Main's sink on the floor, so its presses never reached
 * [MimirScreenIdleController]: the Bottom could dim during pad-only use and a press did not wake it. [PadIdleGate] is Main's own rule, now the ONE rule for Main's lane, this
 * page's pad and the ᛗ menu's pad; these drive it through the REAL idle controller (a fake clock and scheduler), so what is asserted is what the controller does with the presses the
 * lane hands it. The controller is the only thing that dims or wakes: nothing here (or in the gate) writes a brightness. The wake press is eaten for its own events only: its key up
 * (or the input back at rest, in any sample of a batch), a quiet spell, or a change of pad ownership each end it, and while it is held every other key is eaten with it (Main's rule).
 */
class CompanionActivityPadIdleTest {

    @After
    fun releaseSink() {
        MimirMainInputSink.interaction = null
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { System.nanoTime() / 1_000_000 }
    }

    private class FakeIdleScheduler : ScreenIdleSchedule {
        class Task(val dueAtMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

        var nowMs = 0L
            private set
        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, onDeadline: () -> Unit): () -> Unit {
            val task = Task(nowMs + delayMs, onDeadline)
            tasks += task
            return { task.cancelled = true }
        }

        /** Moves the virtual clock, firing each deadline that falls due on the way, in due order. */
        fun advanceBy(ms: Long) {
            val target = nowMs + ms
            while (true) {
                val next = tasks.filterNot { it.cancelled }.filter { it.dueAtMs <= target }.minByOrNull(Task::dueAtMs) ?: break
                nowMs = next.dueAtMs
                next.cancelled = true
                next.action()
            }
            nowMs = target
        }
    }

    private val dimmed = mutableListOf<Boolean>()
    private val scheduler = FakeIdleScheduler()
    private var wakes = 0
    private val routed = mutableListOf<String>()
    private var gateClock = 0L
    private var owner = 0L

    /** Time passes for the idle controller and for the gate's own clock alike. */
    private fun pass(ms: Long) {
        scheduler.advanceBy(ms)
        gateClock += ms
    }

    private fun controller(): MimirScreenIdleController =
        MimirScreenIdleController(
            readTimeoutSeconds = { 60 },
            applyDimmed = dimmed::add,
            applyKeepScreenOn = {},
            scheduler = scheduler,
        ).also { it.onResume() }

    private fun gateOn(controller: MimirScreenIdleController) =
        PadIdleGate(interaction = controller::onUserInteraction, onWake = { wakes += 1 }, ownership = { owner }, clock = { gateClock })

    private fun PadIdleGate.press(keyCode: Int = KeyA, name: String = "down") = key(down = true, up = false, keyCode = keyCode) {
        routed += name
        true
    }

    private fun PadIdleGate.lift(keyCode: Int = KeyA, name: String = "up") = key(down = false, up = true, keyCode = keyCode) {
        routed += name
        true
    }

    /** What the gate let through for each motion event it routed (null: all of its samples). */
    private val permittedSeen = mutableListOf<List<Boolean>?>()

    private fun PadIdleGate.hat(vararg samples: Boolean, name: String = "hat") = motion(samples) { permitted ->
        routed += name
        permittedSeen += permitted?.toList()
        true
    }

    /** A dimmed screen woken by the pad's A: the gate holds the wake press. */
    private fun wokenByA(): Pair<MimirScreenIdleController, PadIdleGate> {
        val controller = controller()
        val gate = gateOn(controller)
        pass(60_000L)
        assertTrue(controller.isDimmed)
        assertTrue("the wake press is consumed", gate.press())
        assertFalse("and woke the screen", controller.isDimmed)
        assertEquals(emptyList<String>(), routed)
        return controller to gate
    }

    @Test
    fun `pad-only presses every thirty seconds for three minutes never dim`() {
        val controller = controller()
        val gate = gateOn(controller)
        repeat(6) {
            pass(30_000L)
            assertTrue(gate.press())
            assertTrue(gate.lift())
        }
        assertFalse("six presses over three minutes, a minute's timeout: never dimmed", controller.isDimmed)
        assertEquals(emptyList<Boolean>(), dimmed)
        assertEquals("and every press reached the page", 12, routed.size)
    }

    @Test
    fun `without the gate the same presses do not reach the controller and the screen dims at a minute`() {
        val controller = controller()
        pass(30_000L)
        pass(30_000L)
        assertTrue("the defect the gate closes: pad-only use alone dims at the timeout", controller.isDimmed)
    }

    @Test
    fun `a press while dimmed wakes the screen and is eaten as the wake press with its key up, and the next press goes to the page`() {
        val (controller, gate) = wokenByA()
        assertEquals(listOf(true, false), dimmed)
        assertEquals("the pad router is reset, as Main resets its own", 1, wakes)
        assertTrue("its key up is eaten", gate.lift())
        assertEquals(emptyList<String>(), routed)
        assertTrue(gate.press())
        assertEquals("the next press is the page's", listOf("down"), routed)
        assertFalse(controller.isDimmed)
    }

    @Test
    fun `the menu takes the key up, is dismissed, and the next A press acts`() {
        val (_, gate) = wokenByA()
        // The wake press's key up went to the menu, which a touch opened and dismissed: pad ownership changed twice, and the gate never saw the up.
        owner += 2
        pass(100L)
        assertTrue(gate.press())
        assertEquals("the next A acts", listOf("down"), routed)
    }

    @Test
    fun `a lost key up ends the latch after a quiet spell, and a held key's repeats keep it`() {
        val (_, gate) = wokenByA()
        // Held: the repeats are the wake press's own, however long the key is down.
        pass(500L)
        assertTrue(gate.key(down = true, up = false, keyCode = KeyA) { routed += "repeat"; true })
        pass(500L)
        assertTrue(gate.key(down = true, up = false, keyCode = KeyA) { routed += "repeat"; true })
        assertEquals("a second after the wake, still eaten", emptyList<String>(), routed)
        assertTrue(gate.lift())
        assertEquals(emptyList<String>(), routed)
        // Lost up: nothing from the key for longer than the latch, and the next press acts.
        val (_, second) = wokenByAFresh()
        pass(PAD_WAKE_LATCH_MS + 100L)
        assertTrue(second.press())
        assertEquals(listOf("down"), routed)
    }

    private fun wokenByAFresh(): Pair<MimirScreenIdleController, PadIdleGate> {
        routed.clear()
        dimmed.clear()
        return wokenByA()
    }

    @Test
    fun `while the wake key is held another key's down and up are eaten too, Main's rule, and only the wake key's own up ends it`() {
        // Main's lane before the gate: every key event was eaten from the wake press until the wake key's own up, a second key's down and up included.
        val (_, gate) = wokenByA()
        assertTrue(gate.press(keyCode = 97, name = "other"))
        assertTrue(gate.lift(keyCode = 97, name = "other up"))
        assertEquals("nothing else reaches the lane while the wake key is down", emptyList<String>(), routed)
        assertTrue("the wake key's own up is eaten and ends it", gate.lift())
        assertEquals(emptyList<String>(), routed)
        assertTrue("the next press, of any key, is the lane's", gate.press(keyCode = 97, name = "other"))
        assertEquals(listOf("other"), routed)
    }

    @Test
    fun `another key's events neither refresh nor end the wake latch`() {
        val (_, gate) = wokenByA()
        pass(PAD_WAKE_LATCH_MS - 100L)
        assertTrue(gate.press(keyCode = 97, name = "other"))
        pass(200L)
        assertTrue("the wake key went quiet past the latch: the next press acts, whoever pressed it before", gate.press(keyCode = 97, name = "other again"))
        assertEquals(listOf("other again"), routed)
    }

    @Test
    fun `a hat press while dimmed wakes and is eaten until it is let go, and a resting hat does not count`() {
        var interactions = 0
        val controller = controller()
        val gate = PadIdleGate(interaction = { interactions += 1; controller.onUserInteraction() }, onWake = { wakes += 1 }, ownership = { owner }, clock = { gateClock })
        assertTrue(gate.hat(false, name = "rest"))
        assertEquals("a resting sample never reaches the idle controller", 0, interactions)
        pass(60_000L)
        assertTrue(controller.isDimmed)
        assertTrue(gate.hat(true, name = "wake"))
        assertFalse(controller.isDimmed)
        assertEquals(1, wakes)
        assertEquals(1, interactions)
        assertTrue("still held: eaten", gate.hat(true, name = "held"))
        assertEquals("but still activity: a held wake press tells the idle controller too", 2, interactions)
        assertTrue("let go: eaten, and it resets nothing", gate.hat(false, name = "let go"))
        assertEquals("the router is reset once, at the wake", 1, wakes)
        assertEquals("and a release is no press", 2, interactions)
        assertTrue("the next press is the page's", gate.hat(true, name = "next"))
        assertEquals(listOf("rest", "next"), routed)
    }

    @Test
    fun `a held wake key keeps the screen awake for a timeout and a half, its repeats eaten and each still activity`() {
        val (controller, gate) = wokenByA()
        repeat(180) {
            pass(500L)
            assertTrue(gate.key(down = true, up = false, keyCode = KeyA) { routed += "repeat"; true })
        }
        assertFalse("held for a minute and a half on a minute's timeout: its repeats re-armed the deadline", controller.isDimmed)
        assertEquals("the one dim was before the wake", listOf(true, false), dimmed)
        assertTrue(gate.lift())
        assertEquals("every repeat was eaten", emptyList<String>(), routed)
    }

    @Test
    fun `a held wake hat keeps the screen awake for a timeout and a half, its samples eaten and each still activity`() {
        val controller = controller()
        val gate = gateOn(controller)
        pass(60_000L)
        assertTrue(gate.hat(true, name = "wake"))
        repeat(180) {
            pass(500L)
            assertTrue(gate.hat(true, name = "held"))
        }
        assertFalse(controller.isDimmed)
        assertEquals("the one dim was before the wake", listOf(true, false), dimmed)
        assertEquals(emptyList<String>(), routed)
    }

    /** One motion event whose single sample is held in the quantised [direction] (a hat or stick direction; 0 is rest). */
    private fun PadIdleGate.stick(direction: Int, name: String = "stick") = motion(IdleWakeSamples.ofDirections(direction)) { permitted ->
        routed += name
        permittedSeen += permitted?.toList()
        true
    }

    /** Offers [offer] every [stepMs] for [forMs] and returns when the screen first dimmed (ms on the gate's clock), or null if it never did. */
    private fun dimmedWhile(controller: MimirScreenIdleController, forMs: Long, stepMs: Long = 500L, offer: (Int) -> Unit): Long? {
        var dimmedAt: Long? = null
        repeat((forMs / stepMs).toInt()) { step ->
            pass(stepMs)
            offer(step)
            if (controller.isDimmed && dimmedAt == null) dimmedAt = gateClock
        }
        return dimmedAt
    }

    @Test
    fun `a held wake key stops earning credit at the cap like any hold, and the wake latch is untouched`() {
        val (controller, gate) = wokenByA()
        // The wake press began the hold at the minute; its repeats come every half second for three minutes.
        val dimmedAt = dimmedWhile(controller, 180_000L) {
            assertTrue(gate.key(down = true, up = false, keyCode = KeyA) { routed += "repeat"; true })
        }
        assertNotNull("capped: the screen dimmed with the key still held", dimmedAt)
        assertTrue("two minutes of credit plus the minute's timeout after the wake press, not before: $dimmedAt", dimmedAt!! - 60_000L in 179_000L..181_000L)
        assertEquals(listOf(true, false, true), dimmed)
        assertEquals("every repeat was still eaten by the latch, and nothing was reset again", emptyList<String>(), routed)
        assertEquals(1, wakes)
        assertTrue("and its up ends the latch as before", gate.lift())
        assertEquals(emptyList<String>(), routed)
    }

    @Test
    fun `a key held with repeats for three minutes dims at two minutes plus the timeout, not before, and every repeat still reaches the page`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.press())
        val dimmedAt = dimmedWhile(controller, 180_000L) { assertTrue(gate.press(name = "repeat")) }
        assertNotNull("a stuck key can no longer hold the screen awake", dimmedAt)
        assertTrue("dimmed at about two minutes plus the minute's timeout: $dimmedAt", dimmedAt!! in 179_000L..181_000L)
        assertEquals("dispatch is untouched: the press and all its repeats reached the page", 361, routed.size)
        assertEquals("and a capped repeat is no wake", listOf(true), dimmed)
        assertEquals(0, wakes)
        assertTrue(gate.lift())
        assertTrue("a fresh press after the cap is the wake press, as ever", gate.press())
        assertFalse(controller.isDimmed)
        assertEquals(1, wakes)
    }

    @Test
    fun `a stick held right for three minutes dims at two minutes plus the timeout, not before, and every sample still reaches the page`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.stick(StickRight))
        val dimmedAt = dimmedWhile(controller, 180_000L) { assertTrue(gate.stick(StickRight)) }
        assertNotNull(dimmedAt)
        assertTrue("dimmed at about two minutes plus the minute's timeout: $dimmedAt", dimmedAt!! in 179_000L..181_000L)
        assertEquals("every sample was the lane's, whole", 361, routed.size)
        assertEquals(List(361) { null }, permittedSeen)
        assertEquals(listOf(true), dimmed)
    }

    @Test
    fun `the same key pressed again after its up earns credit after the cap and starts a cap of its own`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.press())
        repeat(260) { pass(500L); gate.press(name = "repeat") }
        assertFalse("two minutes in, the hold's last credit was at 119.5 s and the dim is due at 179.5 s", controller.isDimmed)
        assertTrue(gate.lift())
        assertTrue("the same key after its up is a new hold", gate.press())
        repeat(100) { pass(500L); gate.press(name = "repeat") }
        assertFalse("at three minutes: the new hold re-armed the deadline the first one would have dimmed at", controller.isDimmed)
    }

    @Test
    fun `another key pressed after the cap earns credit and starts a cap of its own`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.press())
        repeat(260) { pass(500L); gate.press(name = "repeat") }
        assertFalse(controller.isDimmed)
        assertTrue(gate.press(keyCode = 97, name = "other"))
        repeat(100) { pass(500L); gate.press(keyCode = 97, name = "other repeat") }
        assertFalse("another key's down earns credit again", controller.isDimmed)
    }

    @Test
    fun `a key whose up never arrived is a new press after a quiet spell, and its old hold does not cap it`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.press())
        repeat(260) { pass(500L); gate.press(name = "repeat") }
        // The up went to someone else (the menu, a lost focus): nothing from the key for longer than a repeat takes, then the key is pressed again.
        pass(PAD_WAKE_LATCH_MS + 100L)
        assertTrue(gate.press(name = "again"))
        pass(50_000L)
        assertFalse("the press after the quiet spell earned credit: the old hold's dim, due at 179.5 s, did not happen", controller.isDimmed)
    }

    @Test
    fun `another key's down ends the hold in progress, so the first key's next repeat after the cap earns credit again`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.press())
        repeat(260) { pass(500L); gate.press(name = "repeat") }
        assertFalse("130 s in: the hold is capped, its last credit was at 119.5 s and the dim is due at 179.5 s", controller.isDimmed)
        assertTrue(gate.press(keyCode = 97, name = "other"))
        assertTrue(gate.lift(keyCode = 97, name = "other up"))
        // The other key's tap alone would hold the screen only until 190 s: the first key's repeats are what carry it past that.
        repeat(160) { pass(500L); gate.press(name = "repeat") }
        assertFalse("210 s in: the repeats after the other key's tap are a new hold with a cap of their own", controller.isDimmed)
    }

    private fun PadIdleGate.axis(x: Float, name: String = "axis") = motion(IdleWakeSamples.ofAxes(mapOf(MotionEvent.AXIS_X to x))) { permitted ->
        routed += name
        permittedSeen += permitted?.toList()
        true
    }

    @Test
    fun `an axis alternating across the edge of the dead zone is one hold capped at two minutes, not a new press with every sample`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.axis(0.26f))
        val dimmedAt = dimmedWhile(controller, 180_000L) { step -> gate.axis(if (step % 2 == 0) 0.24f else 0.26f) }
        assertNotNull("the hover must not hold the screen awake for ever", dimmedAt)
        assertTrue("dimmed at about two minutes plus the minute's timeout: $dimmedAt", dimmedAt!! in 178_000L..181_000L)
        assertEquals("dispatch is untouched: the hat at 0.24 is a rest and the lane's, as ever", 361, routed.size)
        assertEquals(listOf(true), dimmed)
    }

    @Test
    fun `a rest below the release magnitude and a new deflection after the cap earns credit, a dip that stays inside the band does not`() {
        val released = controller()
        val gate = gateOn(released)
        assertTrue(gate.axis(0.26f))
        repeat(300) { pass(500L); gate.axis(0.26f) }
        assertFalse("150 s in: capped, the dim is due at 179.5 s", released.isDimmed)
        pass(500L)
        assertTrue("a sample under 0.20 is a real rest", gate.axis(0.19f, name = "rest"))
        repeat(100) { pass(500L); gate.axis(0.26f) }
        assertFalse("and the deflection after it is a new hold", released.isDimmed)
    }

    @Test
    fun `a dip that stays inside the band under the dead zone does not release the hold`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.axis(0.26f))
        repeat(300) { pass(500L); gate.axis(0.26f) }
        pass(500L)
        assertTrue(gate.axis(0.20f, name = "dip"))
        repeat(100) { pass(500L); gate.axis(0.26f) }
        assertTrue("0.20 is not below the release magnitude: the same hold, still capped, dimmed at 179.5 s", controller.isDimmed)
    }

    /** Main's gate as MainActivity builds it: pad ownership and the Activity route's hold boundary are the Bottom's two epochs. */
    private fun mainGate(controller: MimirScreenIdleController) =
        PadIdleGate(
            interaction = controller::onUserInteraction,
            onWake = { wakes += 1 },
            ownership = { BottomPadInput.ownerEpoch },
            clock = { gateClock },
            holdBoundary = { BottomPadInput.holdEpoch },
        )

    @Test
    fun `the Activity page's route taking and giving back the pad leaves Main's hat wake latch intact, so the next held sample is still eaten`() {
        val controller = controller()
        val main = mainGate(controller)
        pass(60_000L)
        assertTrue(controller.isDimmed)
        assertTrue(main.hat(true, name = "wake"))
        assertEquals(1, wakes)
        val ownerBefore = BottomPadInput.ownerEpoch
        val keys: (android.view.KeyEvent) -> Boolean = { true }
        val motion: (MotionEvent) -> Boolean = { true }
        CompanionActivityPadRoute.register(keys, motion)
        pass(100L)
        assertTrue("held on across the route taking the pad: still the wake press, eaten", main.hat(true, name = "held"))
        CompanionActivityPadRoute.release(keys, motion)
        pass(100L)
        assertTrue("and across it giving the pad back", main.hat(true, name = "held again"))
        assertEquals("who owns the pad never changed", ownerBefore, BottomPadInput.ownerEpoch)
        assertEquals("nothing reached the lane, so nothing navigated", emptyList<String>(), routed)
        assertEquals("and the router was not reset again", 1, wakes)
    }

    @Test
    fun `the Activity page's route taking and giving back the pad leaves Main's wake key latch intact too`() {
        val controller = controller()
        val main = mainGate(controller)
        pass(60_000L)
        assertTrue(main.press())
        assertEquals(1, wakes)
        val keys: (android.view.KeyEvent) -> Boolean = { true }
        val motion: (MotionEvent) -> Boolean = { true }
        CompanionActivityPadRoute.register(keys, motion)
        CompanionActivityPadRoute.release(keys, motion)
        pass(100L)
        assertTrue("the wake key's repeat is still the wake press's", main.press(name = "repeat"))
        assertTrue("and so is another key's down", main.press(keyCode = 97, name = "other"))
        assertEquals(emptyList<String>(), routed)
        assertEquals(1, wakes)
    }

    @Test
    fun `the Activity page's route taking and giving back the pad ends Main's hold, so Main's same direction earns credit again`() {
        val controller = controller()
        val main = mainGate(controller)
        assertTrue(main.stick(StickRight))
        repeat(260) { pass(500L); main.stick(StickRight) }
        assertFalse("130 s in: Main's hold is capped and the dim is due at 119.5 s plus the minute", controller.isDimmed)
        val keys: (android.view.KeyEvent) -> Boolean = { true }
        val motion: (MotionEvent) -> Boolean = { true }
        CompanionActivityPadRoute.register(keys, motion)
        CompanionActivityPadRoute.release(keys, motion)
        repeat(100) { pass(500L); main.stick(StickRight) }
        assertFalse("180 s in: Main's same direction after the route's turn is a new hold", controller.isDimmed)
    }

    @Test
    fun `the Activity page's route taking and giving back the pad ends Main's key hold too`() {
        val controller = controller()
        val main = mainGate(controller)
        assertTrue(main.press())
        repeat(260) { pass(500L); main.press(name = "repeat") }
        assertFalse(controller.isDimmed)
        val keys: (android.view.KeyEvent) -> Boolean = { true }
        val motion: (MotionEvent) -> Boolean = { true }
        CompanionActivityPadRoute.register(keys, motion)
        CompanionActivityPadRoute.release(keys, motion)
        repeat(100) { pass(500L); main.press(name = "repeat") }
        assertFalse("180 s in: the same key after the route's turn is a new hold", controller.isDimmed)
    }

    @Test
    fun `a change of direction after the cap earns credit and starts a cap of its own`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.stick(StickRight))
        repeat(300) { pass(500L); gate.stick(StickRight) }
        assertFalse("150 s in: the hold's last credit was at 119.5 s and the dim is due at 179.5 s", controller.isDimmed)
        // A real change of direction, with no rest between: a new hold.
        repeat(100) { pass(500L); gate.stick(StickLeft) }
        assertFalse("200 s in: the change of direction re-armed the deadline", controller.isDimmed)
    }

    @Test
    fun `a return to rest and a new deflection after the cap earns credit and starts a cap of its own`() {
        val controller = controller()
        val gate = gateOn(controller)
        assertTrue(gate.stick(StickRight))
        repeat(300) { pass(500L); gate.stick(StickRight) }
        assertFalse(controller.isDimmed)
        pass(500L)
        assertTrue("a rest is the lane's, and ends the hold", gate.stick(0, name = "rest"))
        repeat(100) { pass(500L); gate.stick(StickRight) }
        assertFalse("a return to rest and a new deflection is a new hold", controller.isDimmed)
    }

    @Test
    fun `another key's press under the wake latch is eaten and still tells the idle controller`() {
        var interactions = 0
        val controller = controller()
        val gate = PadIdleGate(interaction = { interactions += 1; controller.onUserInteraction() }, onWake = { wakes += 1 }, ownership = { owner }, clock = { gateClock })
        pass(60_000L)
        assertTrue(gate.press())
        assertEquals(1, interactions)
        assertTrue(gate.press(keyCode = 97, name = "other"))
        assertEquals("eaten, and still activity", 2, interactions)
        assertTrue(gate.lift(keyCode = 97, name = "other up"))
        assertEquals("a release is no press", 2, interactions)
        assertEquals(emptyList<String>(), routed)
    }

    @Test
    fun `a batch of a rest then a new press after a hat wake releases the wake and the press moves`() {
        val controller = controller()
        val gate = gateOn(controller)
        pass(60_000L)
        assertTrue(gate.hat(true, name = "wake"))
        assertEquals(1, wakes)
        // One MotionEvent carrying a historical rest sample and then RIGHT: the rest ends the wake press, RIGHT is a press of its own.
        assertTrue(gate.hat(false, true, name = "batch"))
        assertEquals("the page got the batch", listOf("batch"), routed)
        assertEquals("but only the press, not the rest that released the wake", listOf(listOf(false, true)), permittedSeen)
        assertEquals("the rest released the wake press and reset nothing: the router was reset once, at the wake", 1, wakes)
        // A press that was held through the whole batch is still the wake press.
        routed.clear()
        pass(60_000L)
        assertTrue(controller.isDimmed)
        assertTrue(gate.hat(true, name = "wake again"))
        assertTrue(gate.hat(true, true, name = "still held"))
        assertEquals(emptyList<String>(), routed)
    }

    @Test
    fun `a wake batch that ends at rest latches nothing, and a hat latch is dropped by ownership and by a quiet spell`() {
        val controller = controller()
        val gate = gateOn(controller)
        pass(60_000L)
        assertTrue("press and release inside one batch", gate.hat(true, false, name = "wake batch"))
        assertEquals(emptyList<String>(), routed)
        assertTrue(gate.hat(true, name = "next"))
        assertEquals("nothing was left latched", listOf("next"), routed)
        routed.clear()
        pass(60_000L)
        assertTrue(gate.hat(true, name = "wake"))
        owner += 1
        assertTrue(gate.hat(true, name = "after the menu"))
        assertEquals("the menu took the release: a new press acts", listOf("after the menu"), routed)
        routed.clear()
        pass(60_000L)
        assertTrue(gate.hat(true, name = "wake"))
        pass(PAD_WAKE_LATCH_MS + 100L)
        assertTrue(gate.hat(true, name = "after a quiet spell"))
        assertEquals(listOf("after a quiet spell"), routed)
    }

    // The router the page's route feeds, replaying only what the gate permits (InputRouter.onMotionEvent(event, permitted) does the same over a real MotionEvent).
    private val actions = mutableListOf<ControllerAction>()

    private fun replayingRouter(): InputRouter {
        MimirDirectionalPace.reset()
        MimirDirectionalPace.clock = { gateClock }
        return InputRouter(interactionRoot = InteractionRoot.COMPANION, inputAuthority = DefaultInputAuthority(clock = { gateClock })).also {
            it.handler = { action ->
                actions += action
                true
            }
        }
    }

    private val right = mapOf(MotionEvent.AXIS_HAT_X to 1f, MotionEvent.AXIS_HAT_Y to 0f)
    private val rest = mapOf(MotionEvent.AXIS_HAT_X to 0f, MotionEvent.AXIS_HAT_Y to 0f)

    /** One MotionEvent of these samples through the gate to the router: [held] samples are RIGHT, the others rest. */
    private fun PadIdleGate.batchTo(router: InputRouter, vararg held: Boolean): Boolean {
        val axes = held.map { if (it) right else rest }
        val times = held.indices.map { gateClock + it * 10L }
        return motion(held) { permitted ->
            val keep = axes.indices.filter { permitted == null || permitted[it] }
            router.onMotionSamples(keep.map { axes[it] }, keep.map { times[it] })
        }
    }

    @Test
    fun `a dimmed hat wake then a rest and a press moves exactly once, and a wake let go in its own batch moves nothing`() {
        val router = replayingRouter()
        var asleep = true
        val gate = PadIdleGate(interaction = { asleep.also { asleep = false } }, onWake = {}, ownership = { owner }, clock = { gateClock })
        assertTrue(gate.batchTo(router, true))
        pass(500L)
        assertTrue(gate.batchTo(router, false, true))
        assertEquals("[rest, RIGHT] after the wake: one RIGHT", listOf(ControllerAction.NAV_RIGHT), actions)
        actions.clear()
        router.onMotionSamples(listOf(rest), listOf(gateClock))
        // The wake sample is part of the SAME batch as the rest and the press: it is not replayed.
        asleep = true
        pass(500L)
        assertTrue(gate.batchTo(router, true, false, true))
        assertEquals("[RIGHT(wake), rest, RIGHT]: the wake sample moves nothing, the press moves once", listOf(ControllerAction.NAV_RIGHT), actions)
        actions.clear()
        router.onMotionSamples(listOf(rest), listOf(gateClock))
        asleep = true
        pass(500L)
        assertTrue(gate.batchTo(router, true, false))
        assertEquals("[RIGHT(wake), rest]: no move", emptyList<ControllerAction>(), actions)
    }

    @Test
    fun `a press the page declines is still activity, and not eaten`() {
        val controller = controller()
        val gate = gateOn(controller)
        pass(59_000L)
        assertFalse(gate.key(down = true, up = false, keyCode = KeyA) { false })
        pass(59_000L)
        assertFalse("the declined press re-armed the idle deadline", controller.isDimmed)
    }

    @Test
    fun `Main's sink hands out the idle answer by identity, and none means nothing is done`() {
        assertNull(MimirMainInputSink.interaction)
        assertFalse(MimirMainInputSink.noteInteraction())
        val first: () -> Boolean = { true }
        val second: () -> Boolean = { false }
        MimirMainInputSink.registerInteraction(first)
        assertTrue(MimirMainInputSink.noteInteraction())
        // A successor registers before the outgoing instance releases: the release does not unplug it.
        MimirMainInputSink.registerInteraction(second)
        MimirMainInputSink.releaseInteraction(first)
        assertSame(second, MimirMainInputSink.interaction)
        assertFalse(MimirMainInputSink.noteInteraction())
        MimirMainInputSink.releaseInteraction(second)
        assertNull(MimirMainInputSink.interaction)
    }

    @Test
    fun `a batch of held samples tells the idle controller once, as Main's lane always did`() {
        var interactions = 0
        val gate = PadIdleGate(interaction = { interactions += 1; false }, onWake = {}, clock = { gateClock })
        assertTrue(gate.hat(true, true, true, name = "held batch"))
        assertEquals("one re-arm per event, however many held samples it carries", 1, interactions)
        assertEquals(listOf("held batch"), routed)
        assertEquals("every sample was the lane's", listOf<List<Boolean>?>(null), permittedSeen)
    }

    private fun source(name: String): String =
        (File("src/main/kotlin/com/gruning/mimir/rewrite/$name").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name")).readText()

    /** A file of core-companion-native, which Gradle's test directory (app/) reaches by its sibling path. */
    private fun coreSource(name: String): String {
        val path = "core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/$name"
        return (File("../$path").takeIf { it.isFile } ?: File(path)).readText()
    }

    @Test
    fun `the three lanes run the one gate, Main registers its idle controller with the sink and the menu, and nothing writes a brightness`() {
        val pad = source("CompanionActivityPad.kt")
        assertTrue(pad.contains("PadIdleGate(interaction = MimirMainInputSink::noteInteraction, onWake = router::reset, ownership = { BottomPadInput.ownerEpoch })"))
        assertTrue(pad.contains("idle.key("))
        assertTrue(pad.contains("idle.motion(event.idleWakeSamples())"))
        val main = source("MainActivity.kt")
        assertTrue(main.contains("MimirMainInputSink.registerInteraction(secondaryDisplayInteraction)"))
        assertTrue(main.contains("MimirMainInputSink.releaseInteraction(secondaryDisplayInteraction)"))
        assertTrue(main.contains("{ screenIdleController?.onUserInteraction() == true }"))
        assertFalse("Main and the pad routes share one definition of what counts as activity", main.contains("fun MotionEvent.hasIdleWakeMotion"))
        // Main's own lane is the gate too: no latch of its own, the sample-ordered motion rule, the permitted samples to the router, and the latch cleared when it loses focus.
        assertTrue(
            "and under the Bottom's pad ownership, so the menu taking the release clears Main's latch",
            main.contains(
                "PadIdleGate(\n        interaction = secondaryDisplayInteraction,\n        onWake = { inputRouter.reset() },\n        ownership = { BottomPadInput.ownerEpoch },\n        holdBoundary = { BottomPadInput.holdEpoch },\n    )",
            ),
        )
        assertTrue(main.contains("padIdleGate.key("))
        assertTrue(main.contains("return padIdleGate.motion(samples) { permitted ->"))
        assertTrue(main.contains("inputRouter.onMotionEvent(event, permitted)"))
        assertTrue(main.contains("padIdleGate.reset()"))
        listOf("idleWakeKeyCode", "idleWakeMotionLatched", "hasCurrentIdleWakeMotion").forEach {
            assertFalse("Main keeps no wake latch of its own ($it)", main.contains(it))
        }
        // The ᛗ menu: its pad is gated by the same class under the pad's ownership, and the app hands it Main's idle controller through the sink.
        val menu = coreSource("AndroidCompanionOverlayController.kt")
        assertTrue(menu.contains("private val padIdleGate = PadIdleGate("))
        assertTrue(menu.contains("ownership = { BottomPadInput.ownerEpoch }"))
        assertTrue(menu.contains("padIdleGate.key("))
        assertTrue(menu.contains("padIdleGate.motion(event.idleWakeSamples()) { permitted ->"))
        assertTrue(menu.contains("applyPadMotion(event, permitted)"))
        assertTrue(source("MButtonRuntime.kt").contains("overlay.setPadIdleInteraction(MimirMainInputSink::noteInteraction)"))
        assertFalse("the gate lives once, in core-companion-native", File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityPadIdle.kt").exists())
        val gate = coreSource("PadIdleGate.kt")
        listOf("brightness", "Brightness", "applyDimmed", "Settings.System", "setSystemBrightness").forEach {
            assertFalse("the gate never touches display state ($it)", gate.contains(it))
        }
    }

    private companion object {
        const val KeyA = 96

        /** Two quantised directions of one stick (any distinct non-zero codes will do; the real ones are built from the axes in the gate). */
        const val StickRight = 4
        const val StickLeft = 8
    }
}
