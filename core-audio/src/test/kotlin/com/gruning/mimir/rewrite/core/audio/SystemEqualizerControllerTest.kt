package com.gruning.mimir.rewrite.core.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system equalizer's lifecycle against a fake effect: on applies the curve, off releases, a loss of control is reported as one,
 * a restart re-applies, and "on" is claimed only for an effect that read back as enabled and holding the curve.
 */
class SystemEqualizerControllerTest {
    /** An effect on a pretend mix. It reads back what it was given unless a test tells it to lie. */
    private class FakeEffect(val onControl: () -> Unit) : SystemEqEffect {
        val calls = mutableListOf<String>()
        var released = false
        var control = true
        var enabled = false
        var applied: SystemEqPlan? = null
        var refuseEnable = false
        var applyFailure: RuntimeException? = null
        var loseControlOnApply = false
        var dead = false

        /** Runs as the plan is pushed, to see what the rest of the app believes at that moment. */
        var beforeApply: () -> Unit = {}

        /** The platform's own answer to "is it enabled", which can say no behind our back. */
        var platformEnabled = true
        var readBackEnabled: Boolean? = null
        var readBackGainShiftDb = 0f
        var readBackLimiterOn = true

        override val hasControl: Boolean
            get() {
                if (dead) throw IllegalStateException("the effect is dead")
                return control
            }

        override fun apply(plan: SystemEqPlan) {
            calls += "apply"
            beforeApply()
            if (loseControlOnApply) {
                control = false
                throw IllegalStateException("not in control")
            }
            applyFailure?.let { throw it }
            applied = plan
        }

        override fun isEnabled(): Boolean {
            if (dead) throw IllegalStateException("the effect is dead")
            return enabled && platformEnabled
        }

        override fun setEnabled(enabled: Boolean): Boolean {
            calls += "enabled=$enabled"
            if (refuseEnable) return false
            this.enabled = enabled
            platformEnabled = true
            return true
        }

        override fun readBack(): SystemEqReadBack {
            calls += "readBack"
            val plan = applied ?: error("read before any plan")
            return SystemEqReadBack(
                enabled = readBackEnabled ?: enabled,
                channelBandGainsDb = List(SystemEqPlans.ChannelCount) { plan.bands.map { it.gainDb + readBackGainShiftDb } },
                inputGainDb = plan.inputGainDb,
                limiterEnabled = readBackLimiterOn,
                limiterThresholdDb = plan.limiter.thresholdDb,
            )
        }

        override fun release() {
            calls += "release"
            released = true
            enabled = false
        }

        fun takeControl() {
            control = false
            onControl()
        }

        fun giveControlBack() {
            control = true
            onControl()
        }
    }

    private class FakeFactory : SystemEqEffectFactory {
        val made = mutableListOf<FakeEffect>()
        var refuse = false
        var throwOnCreate: RuntimeException? = null
        var startWithoutControl = false
        var fireControlDuringCreate = false

        /** Runs on every effect as it is made, to give it a fault. */
        var tweak: (FakeEffect) -> Unit = {}
        val shapes = mutableListOf<SystemEqPlan>()

        /** Effects that are attached to the mix right now. */
        val attached: List<FakeEffect> get() = made.filter { !it.released }

        override fun create(plan: SystemEqPlan, onControlChanged: () -> Unit): SystemEqEffect? {
            throwOnCreate?.let { throw it }
            if (refuse) return null
            shapes += plan
            val effect = FakeEffect(onControlChanged)
            effect.control = !startWithoutControl
            tweak(effect)
            made += effect
            if (fireControlDuringCreate) onControlChanged()
            return effect
        }
    }

    private val factory = FakeFactory()
    private val activeCalls = mutableListOf<Boolean>()
    private val logged = mutableListOf<String>()

    private fun controller() =
        SystemEqualizerController(factory, onActiveChanged = { activeCalls += it }, log = { m, _ -> logged += m })

    private val rock = EqualizerSettings().withPreset(EqualizerPresetId.ROCK)
    private val rockPlan = SystemEqPlans.forCurve(rock)

    // ---- on ----

    @Test
    fun `on creates the effect, applies the curve, enables it, reads it back, and only then says on`() {
        val c = controller()
        c.update(wanted = true, curve = rock)
        val effect = factory.made.single()
        assertEquals(listOf("apply", "enabled=true", "readBack"), effect.calls)
        assertEquals("the effect holds the in-app curve as a plan", rockPlan, effect.applied)
        assertTrue(effect.enabled)
        assertEquals(SystemEqualizerState(wanted = true, status = SystemEqStatus.ON), c.state.value)
        assertEquals("the bypass flag is told once, true", listOf(true), activeCalls)
    }

    @Test
    fun `nothing is attached and nothing said while the switch has never been on`() {
        val c = controller()
        c.update(wanted = false, curve = rock)
        assertTrue(factory.made.isEmpty())
        assertEquals(SystemEqualizerState(), c.state.value)
        assertTrue("no bypass for an equalizer that is not there", activeCalls.isEmpty())
    }

    @Test
    fun `a change of the curve while on re-applies it to the same effect, and a change of the in-app switch alone does nothing`() {
        val c = controller()
        c.update(true, rock)
        val effect = factory.made.single()
        c.update(true, rock.withBass(4f))
        assertEquals("still one effect", 1, factory.made.size)
        assertEquals(SystemEqPlans.forCurve(rock.withBass(4f)), effect.applied)
        assertEquals(SystemEqStatus.ON, c.state.value.status)

        val callsBefore = effect.calls.size
        c.update(true, rock.withBass(4f).withEnabled(true))
        assertEquals("the in-app switch is not in the plan, so the effect is not touched", callsBefore, effect.calls.size)
        assertEquals("and the bypass flag was not told again", listOf(true), activeCalls)
    }

    // ---- off ----

    @Test
    fun `off releases the effect and leaves nothing attached, flat or otherwise`() {
        val c = controller()
        c.update(true, rock)
        val effect = factory.made.single()
        c.update(false, rock)
        assertTrue(effect.released)
        assertTrue("no effect is left on the mix", factory.attached.isEmpty())
        assertEquals("release, with no flat curve pushed on the way out", listOf("apply", "enabled=true", "readBack", "release"), effect.calls)
        assertEquals(SystemEqualizerState(wanted = false, status = SystemEqStatus.OFF), c.state.value)
        assertEquals("Your music's own equalizer is told it may come back", listOf(true, false), activeCalls)
    }

    @Test
    fun `on again after off is a new effect, not the released one`() {
        val c = controller()
        c.update(true, rock)
        c.update(false, rock)
        c.update(true, rock)
        assertEquals(2, factory.made.size)
        assertNotSame(factory.made[0], factory.made[1])
        assertEquals(1, factory.attached.size)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }

    // ---- honest failure ----

    @Test
    fun `an effect that cannot be created is unavailable, never on, and the choice stays as the player made it`() {
        factory.refuse = true
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE), c.state.value)
        assertTrue("no bypass: Your music keeps its own equalizer", activeCalls.isEmpty())

        factory.refuse = false
        c.update(true, rock.withBass(1f))
        assertEquals("the next change tries again", SystemEqStatus.ON, c.state.value.status)
    }

    @Test
    fun `a platform that throws on creation is unavailable too, and it is logged`() {
        factory.throwOnCreate = UnsupportedOperationException("no such effect")
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        assertEquals(1, logged.size)
    }

    @Test
    fun `an effect another app controls is no control, with nothing pushed into it, and it is not claimed on`() {
        factory.startWithoutControl = true
        val c = controller()
        c.update(true, rock)
        val effect = factory.made.single()
        assertEquals(SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL), c.state.value)
        assertTrue("nothing was pushed into an effect that is not ours to set", effect.calls.isEmpty())
        assertFalse(effect.enabled)
        assertTrue("Your music keeps its own equalizer", activeCalls.isEmpty())
    }

    @Test
    fun `control granted later applies the curve and turns it on`() {
        factory.startWithoutControl = true
        val c = controller()
        c.update(true, rock)
        factory.made.single().giveControlBack()
        assertEquals(SystemEqStatus.ON, c.state.value.status)
        assertEquals(rockPlan, factory.made.single().applied)
        assertEquals(listOf(true), activeCalls)
    }

    @Test
    fun `control lost while on is reported as lost, Your music's equalizer comes back, and regained control re-applies`() {
        val c = controller()
        c.update(true, rock)
        val effect = factory.made.single()
        effect.takeControl()
        assertEquals(SystemEqStatus.NO_CONTROL, c.state.value.status)
        assertEquals("true while ours, false the moment it is lost", listOf(true, false), activeCalls)

        // The other app changed the effect while it held it.
        effect.applied = null
        effect.giveControlBack()
        assertEquals(SystemEqStatus.ON, c.state.value.status)
        assertEquals("the plan is pushed again, not assumed", rockPlan, effect.applied)
        assertEquals(listOf(true, false, true), activeCalls)
    }

    @Test
    fun `turning it off while control is lost still releases the handle`() {
        val c = controller()
        c.update(true, rock)
        factory.made.single().takeControl()
        c.update(false, rock)
        assertTrue(factory.attached.isEmpty())
        assertEquals(SystemEqStatus.OFF, c.state.value.status)
    }

    @Test
    fun `a refusal that comes from losing control mid-push is no control, not a fault of the device`() {
        val c = controller()
        c.update(true, rock)
        val effect = factory.made.single()
        // The other app takes the effect just as the next push lands.
        effect.loseControlOnApply = true
        c.update(true, rock.withBass(3f))
        assertEquals(SystemEqStatus.NO_CONTROL, c.state.value.status)
        assertFalse("the handle is kept to hear control come back", effect.released)
        assertEquals("Your music's equalizer is back", listOf(true, false), activeCalls)
    }

    @Test
    fun `an effect that dies under us is unavailable and dropped, and the next change tries a fresh one`() {
        val c = controller()
        c.update(true, rock)
        val first = factory.made.single()
        first.dead = true
        c.update(true, rock.withBass(2f))
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        assertTrue("the dead handle is released", first.released)
        assertEquals("the bypass flag came down with it", listOf(true, false), activeCalls)

        c.update(true, rock.withBass(3f))
        assertEquals(2, factory.made.size)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }

    @Test
    fun `an effect that will not enable is unavailable and released`() {
        factory.tweak = { it.refuseEnable = true }
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE), c.state.value)
        assertTrue(factory.attached.isEmpty())
        assertEquals("claimed ahead of the push and retracted when it would not enable: Your music's own stage is back", listOf(true, false), activeCalls)
    }

    @Test
    fun `an effect that reads back disabled is not on`() {
        factory.tweak = { it.readBackEnabled = false }
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        assertTrue(factory.attached.isEmpty())
        assertEquals("the claim is retracted when the read-back says no", listOf(true, false), activeCalls)
    }

    @Test
    fun `an effect that reads back other gains than were set is not on`() {
        factory.tweak = { it.readBackGainShiftDb = 3f }
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        assertTrue(factory.attached.isEmpty())
    }

    @Test
    fun `a gain read back within the quantisation is the gain that was set`() {
        factory.tweak = { it.readBackGainShiftDb = 0.1f }
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }

    @Test
    fun `a limiter that did not take is not on`() {
        factory.tweak = { it.readBackLimiterOn = false }
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        assertTrue(factory.attached.isEmpty())
    }

    // ---- the claim: Your music's own stage is down before the plan lands ----

    @Test
    fun `the mix is claimed before the plan is pushed, so the music's own stage is down when the engine starts applying`() {
        val seenAtApply = mutableListOf<List<Boolean>>()
        factory.tweak = { effect -> effect.beforeApply = { seenAtApply += activeCalls.toList() } }
        val c = controller()
        c.update(true, rock)
        assertEquals("the bypass was already told true at the moment of the first apply", listOf(listOf(true)), seenAtApply)
        assertEquals("and it is told once", listOf(true), activeCalls)
        c.update(true, rock.withBass(4f))
        assertEquals("a later curve is pushed under the same claim", listOf(listOf(true), listOf(true)), seenAtApply)
        assertEquals(listOf(true), activeCalls)
    }

    @Test
    fun `a push that fails retracts the claim, and the music's own stage is the equalizer again`() {
        factory.tweak = { it.applyFailure = IllegalStateException("the platform refused the curve") }
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        assertEquals(listOf(true, false), activeCalls)
        assertEquals(EqualizerReach.MIMIR_ONLY, equalizerReach(enabled = true, engine = c.state.value.status))
    }

    @Test
    fun `no control means no claim at all, and neither a refused create`() {
        factory.startWithoutControl = true
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.NO_CONTROL, c.state.value.status)
        assertTrue("nothing is pushed into an effect another app holds, so nothing is claimed", activeCalls.isEmpty())

        factory.startWithoutControl = false
        factory.refuse = true
        val refused = SystemEqualizerController(factory, onActiveChanged = { activeCalls += it })
        refused.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, refused.state.value.status)
        assertTrue(activeCalls.isEmpty())
    }

    @Test
    fun `the one switch drives the engine through the host, and never leaves both stages on`() {
        val choice = MutableStateFlow(rock.withEnabled(false))
        val own = mutableListOf<Boolean>()
        // What the music player is handed after each change of either input: the choice with the claim applied, as AudioPreferences does.
        var claimed = false
        val c = SystemEqualizerController(factory, onActiveChanged = { claimed = it })
        val scope = CoroutineScope(Dispatchers.Unconfined)
        SystemEqualizerHost(
            c, scope, wanted = choice.map { it.enabled }, curve = choice, playing = { MutableStateFlow(false) },
        ).start(load = {})
        fun sample() { own += choice.value.forInPlayer(claimed).enabled }

        sample()
        assertEquals("off: the engine is not attached and the player's stage is off", listOf(false), own)
        assertTrue(factory.made.isEmpty())

        choice.value = choice.value.withEnabled(true)
        sample()
        assertEquals("on: one effect, claimed, the player's stage down", 1, factory.attached.size)
        assertEquals(false, own.last())

        factory.attached.single().takeControl()
        sample()
        assertEquals("control lost: the player's own stage shapes Mimir's music", true, own.last())

        factory.attached.single().giveControlBack()
        sample()
        assertEquals("control back: the engine again, the player's stage down", false, own.last())

        choice.value = choice.value.withEnabled(false)
        sample()
        assertTrue("off releases the engine", factory.attached.isEmpty())
        assertEquals("and the player's stage stays off with it", false, own.last())
    }

    // ---- restart ----

    @Test
    fun `a new process re-applies the stored switch and curve`() {
        val first = controller()
        first.update(true, rock)
        // The process dies: the platform drops the effect with it, and a new controller starts from what was stored.
        factory.made.forEach { it.released = true }
        val second = controller()
        second.update(wanted = true, curve = rock)
        assertEquals(2, factory.made.size)
        assertEquals(rockPlan, factory.made.last().applied)
        assertEquals(SystemEqStatus.ON, second.state.value.status)
    }

    @Test
    fun `a stored off stays off across a start`() {
        val c = controller()
        c.update(wanted = false, curve = rock)
        assertTrue(factory.made.isEmpty())
        assertEquals(SystemEqStatus.OFF, c.state.value.status)
    }

    // ---- the check when Your music starts, and the asking again ----

    @Test
    fun `a healthy effect passes the check untouched`() {
        val c = controller()
        c.update(true, rock)
        val effect = factory.made.single()
        val calls = effect.calls.toList()
        c.verify()
        assertEquals(1, factory.made.size)
        assertEquals("nothing was pushed or released", calls, effect.calls)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
        assertEquals(listOf(true), activeCalls)
    }

    @Test
    fun `an effect the audio server took with it is found by the check, made again, and the state is honest about it`() {
        val c = controller()
        c.update(true, rock)
        val first = factory.made.single()
        // The audio server restarts: no callback, and every call on the old handle now throws.
        first.dead = true
        c.verify()
        assertTrue("the dead handle is let go", first.released)
        assertEquals(2, factory.made.size)
        assertEquals(rockPlan, factory.made.last().applied)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
        assertEquals("the bypass held through it: it never said off and on", listOf(true), activeCalls)
    }

    @Test
    fun `an effect that reads disabled is made again`() {
        val c = controller()
        c.update(true, rock)
        factory.made.single().platformEnabled = false
        c.verify()
        assertEquals(2, factory.made.size)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }

    @Test
    fun `when the effect cannot be made again after the audio server went, the row says so and Your music's own equalizer is back`() {
        val c = controller()
        c.update(true, rock)
        factory.made.single().dead = true
        factory.refuse = true
        c.verify()
        assertEquals(SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE), c.state.value)
        assertEquals(listOf(true, false), activeCalls)
    }

    @Test
    fun `an effect that lost control to another app is looked at again when the music starts`() {
        val c = controller()
        c.update(true, rock)
        factory.made.single().control = false
        factory.startWithoutControl = true
        c.verify()
        // The old handle is let go and a new one asked for; the other app still has the effect, which the state says.
        assertEquals(SystemEqStatus.NO_CONTROL, c.state.value.status)
        assertEquals(listOf(true, false), activeCalls)
    }

    @Test
    fun `an effect that was refused is tried again when the music starts, and nothing is asked for when the switch is off`() {
        factory.refuse = true
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        factory.refuse = false
        c.verify()
        assertEquals(SystemEqStatus.ON, c.state.value.status)

        val off = controller()
        off.update(false, rock)
        off.verify()
        assertEquals("the switch is off: no new effect", 1, factory.made.size)
        assertEquals(SystemEqStatus.OFF, off.state.value.status)
    }

    @Test
    fun `retry asks again from scratch, and on a refused effect that is what a press does`() {
        factory.refuse = true
        val c = controller()
        c.update(true, rock)
        assertEquals(SystemEqStatus.UNAVAILABLE, c.state.value.status)
        factory.refuse = false
        c.retry()
        assertEquals(SystemEqualizerState(wanted = true, status = SystemEqStatus.ON), c.state.value)

        // On an effect that is up, retry replaces it: one is attached, never two.
        c.retry()
        assertEquals(1, factory.attached.size)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }

    @Test
    fun `retry with the switch off attaches nothing`() {
        val c = controller()
        c.update(false, rock)
        c.retry()
        assertTrue(factory.made.isEmpty())
    }

    // ---- platform callbacks ----

    @Test
    fun `a control callback that arrives while the effect is still being made does not make a second one`() {
        factory.fireControlDuringCreate = true
        val c = controller()
        c.update(true, rock)
        assertEquals(1, factory.made.size)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }
    // ---- the host: the stored switch and curve in, at start and on every change ----

    @Test
    fun `at process start the stored switch and curve are read and then re-applied`() {
        val order = mutableListOf<String>()
        val wanted = MutableStateFlow(false)
        val curve = MutableStateFlow(EqualizerSettings())
        val c = controller()
        val host = SystemEqualizerHost(c, CoroutineScope(Dispatchers.Unconfined), wanted, curve, playing = { MutableStateFlow(false) })
        assertTrue("nothing before start", factory.made.isEmpty())

        // What `ensureEqualizerLoaded` does: the stored choice lands in the flows.
        host.start(load = {
            order += "load"
            wanted.value = true
            curve.value = rock
        })

        assertEquals(listOf("load"), order)
        assertEquals("one effect, made after the load", 1, factory.made.size)
        assertEquals(rockPlan, factory.made.single().applied)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }

    @Test
    fun `after start a change of the curve is applied, and the switch going off releases the effect`() {
        val wanted = MutableStateFlow(true)
        val curve = MutableStateFlow(rock)
        val c = controller()
        SystemEqualizerHost(c, CoroutineScope(Dispatchers.Unconfined), wanted, curve, playing = { MutableStateFlow(false) }).start(load = {})
        val effect = factory.made.single()

        curve.value = rock.withTreble(-5f)
        assertEquals(SystemEqPlans.forCurve(rock.withTreble(-5f)), effect.applied)
        assertEquals("the same effect, re-pushed", 1, factory.made.size)

        wanted.value = false
        assertTrue(effect.released)
        assertTrue(factory.attached.isEmpty())
        assertEquals(SystemEqStatus.OFF, c.state.value.status)
    }

    @Test
    fun `a stored off creates nothing at start, and starting twice reads once`() {
        var loads = 0
        val c = controller()
        val host = SystemEqualizerHost(
            c, CoroutineScope(Dispatchers.Unconfined), MutableStateFlow(false), MutableStateFlow(rock), playing = { MutableStateFlow(false) },
        )
        host.start(load = { loads++ })
        host.start(load = { loads++ })
        assertEquals(1, loads)
        assertTrue(factory.made.isEmpty())
        assertEquals(SystemEqualizerState(), c.state.value)
    }
    @Test
    fun `each time Your music starts to play the host has the effect checked`() {
        val starts = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
        val c = controller()
        SystemEqualizerHost(
            c, CoroutineScope(Dispatchers.Unconfined), MutableStateFlow(true), MutableStateFlow(rock), playing = { starts },
        ).start(load = {})
        val first = factory.made.single()

        starts.tryEmit(true)
        assertEquals("healthy: nothing changed", 1, factory.made.size)

        first.dead = true
        starts.tryEmit(true)
        assertEquals("dead: made again", 2, factory.made.size)
        assertEquals(SystemEqStatus.ON, c.state.value.status)
    }
}
