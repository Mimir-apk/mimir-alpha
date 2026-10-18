package com.gruning.mimir.rewrite.core.audio

import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the system equalizer honestly is right now. Only [ON] means the global mix is being equalized. */
enum class SystemEqStatus {
    /** Not asked for. No effect is attached. */
    OFF,

    /** The effect is attached, enabled, holding the curve, and read back as such. */
    ON,

    /** Asked for, and attached, but another app holds control of the effect: nothing of ours is applied. */
    NO_CONTROL,

    /** Asked for, but the effect could not be created, set or read back on this device. */
    UNAVAILABLE,
}

/**
 * The system equalizer as the Settings row shows it.
 *
 * @property wanted the player's choice (stored): the switch they set, whether or not it took.
 * @property status what is actually happening; the row claims "on" only for [SystemEqStatus.ON].
 */
data class SystemEqualizerState(
    val wanted: Boolean = false,
    val status: SystemEqStatus = SystemEqStatus.OFF,
) {
    val on: Boolean get() = status == SystemEqStatus.ON
}

/** What an attached effect reports when asked, for [SystemEqualizerController] to compare with what it asked for. */
internal data class SystemEqReadBack(
    val enabled: Boolean,
    /** Pre-EQ band gains per channel, lowest band first. */
    val channelBandGainsDb: List<List<Float>>,
    val inputGainDb: Float,
    val limiterEnabled: Boolean,
    val limiterThresholdDb: Float,
)

/** One attached effect on the global mix. [DynamicsProcessingEffect] in production; a fake in the behaviour tests. */
internal interface SystemEqEffect {
    /** Whether this handle, and not another app's, controls the effect. Throws if the effect is dead. */
    val hasControl: Boolean

    /** Whether the effect is enabled (a cheap read, for a check that the effect is still there). Throws if the effect is dead. */
    fun isEnabled(): Boolean

    /** Pushes [plan] into the effect. Throws if the platform refuses. */
    fun apply(plan: SystemEqPlan)

    /** Returns whether the platform accepted the change. */
    fun setEnabled(enabled: Boolean): Boolean

    fun readBack(): SystemEqReadBack

    /** Detaches the effect from the mix for good. */
    fun release()
}

internal fun interface SystemEqEffectFactory {
    /**
     * Attaches a new effect to the global mix, shaped for [plan] (not yet applied). [onControlChanged] is called, on any thread,
     * whenever this handle gains or loses control. Returns null, or throws, when the effect cannot be created.
     */
    fun create(plan: SystemEqPlan, onControlChanged: () -> Unit): SystemEqEffect?
}

/**
 * The system equalizer's lifecycle, with the platform behind [SystemEqEffectFactory] so every rule is a JVM test.
 *
 * - **On** creates the effect, pushes the plan, enables it, reads the enabled flag and the band gains back, and is
 *   [SystemEqStatus.ON] only if they are what was asked for.
 * - **Off** releases the effect. Nothing is left attached, flat or otherwise.
 * - **No control** (another app took the effect, or had it first): nothing is pushed, the status says so, and the handle is kept so
 *   that control coming back re-applies the plan.
 * - **Cannot create or read back**: [SystemEqStatus.UNAVAILABLE], the effect released.
 *
 * [onActiveChanged] is told whenever the answer to "does the engine have the global mix" changes. That is the producer of the bypass
 * flag: Your music's own equalizer stands down while it is true and comes back the moment it is false. It goes true when the engine
 * holds control and is about to push the plan (so the two never apply together), and false again if the push or the read-back fails,
 * control is lost, or the effect is released.
 *
 * Inputs arrive from any thread; they are handled one at a time.
 */
internal class SystemEqualizerController(
    private val factory: SystemEqEffectFactory,
    private val onActiveChanged: (Boolean) -> Unit = {},
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var wanted = false
    private var curve = EqualizerSettings()
    private var effect: SystemEqEffect? = null

    /** The plan the attached effect holds and was read back as holding; null until one has been applied. */
    private var applied: SystemEqPlan? = null
    private var active = false
    private var busy = false
    private var again = false

    private val _state = MutableStateFlow(SystemEqualizerState())
    val state: StateFlow<SystemEqualizerState> = _state.asStateFlow()

    /** The stored choice and the curve, as they stand. Called at start and on every change of either. */
    fun update(wanted: Boolean, curve: EqualizerSettings) {
        synchronized(lock) {
            this.wanted = wanted
            this.curve = curve
            reconcile()
        }
    }

    private fun onControlChanged() {
        synchronized(lock) {
            // Control that came back may find another app's settings in the effect: apply and read back afresh.
            applied = null
            reconcile()
        }
    }

    /**
     * Asks again, from scratch: the attached effect (if any) is released and a new one is made, applied and read back. For a press on
     * a switch that says the device refused the effect, where nothing about the inputs has changed to make [update] try again.
     */
    fun retry() {
        synchronized(lock) {
            release()
            reconcile()
        }
    }

    /**
     * Checks that what the state says is still true, for a moment when it may not be (Your music starting to play): the audio server
     * can restart and take an effect with it without a word, which leaves a row saying "On" and Your music bypassed for nothing. An
     * attached effect that throws, has lost control or reads back disabled is dropped and made again, and the state is whatever that
     * attempt honestly gives. An effect that was refused at the last try is tried again. Nothing asked for means nothing to check.
     */
    fun verify() {
        synchronized(lock) {
            if (!wanted) return
            val held = effect
            if (held != null) {
                val sound = try {
                    held.hasControl && held.isEnabled()
                } catch (error: Exception) {
                    log("the system EQ did not answer", error)
                    false
                }
                if (sound) return
                release()
            }
            reconcile()
        }
    }

    // A platform callback can arrive while the effect is still being created; it is folded into the pass already running.
    private fun reconcile() {
        if (busy) {
            again = true
            return
        }
        busy = true
        try {
            do {
                again = false
                guardedStep()
            } while (again)
        } finally {
            busy = false
        }
    }

    // A dead effect (the audio server restarted under it) throws from any call. That must end as an honest "unavailable" with the
    // handle dropped, never as an exception that stops the inputs from being followed.
    private fun guardedStep() {
        try {
            step()
        } catch (error: Exception) {
            log("the system EQ failed", error)
            release()
            publish(if (wanted) SystemEqStatus.UNAVAILABLE else SystemEqStatus.OFF)
        }
    }

    private fun step() {
        if (!wanted) {
            release()
            publish(SystemEqStatus.OFF)
            return
        }
        val plan = SystemEqPlans.forCurve(curve)
        var live = effect
        if (live == null) {
            live = try {
                factory.create(plan, this::onControlChanged)
            } catch (error: Exception) {
                log("the system EQ was not created", error)
                null
            }
            if (live == null) {
                publish(SystemEqStatus.UNAVAILABLE)
                return
            }
            effect = live
            applied = null
        }
        if (!live.hasControl) {
            applied = null
            publish(SystemEqStatus.NO_CONTROL)
            return
        }
        if (applied == plan) {
            publish(SystemEqStatus.ON)
            return
        }
        // The engine is about to shape the mix: Mimir's own stage stands down before the plan lands, so no sound is equalized twice.
        // Anything but ON below retracts the claim, and the own stage is back (the one stated fallback).
        claim()
        try {
            live.apply(plan)
            check(live.setEnabled(true)) { "the platform refused to enable the effect" }
        } catch (error: Exception) {
            failed(live, "the system EQ was not set", error)
            return
        }
        val problem = mismatch(plan, live.readBack())
        if (problem != null) {
            failed(live, "the system EQ read back wrong: $problem", null)
            return
        }
        applied = plan
        publish(SystemEqStatus.ON)
    }

    /** A refusal while another app holds control is not a fault of the device: it is "no control". */
    private fun failed(live: SystemEqEffect, message: String, error: Throwable?) {
        log(message, error)
        applied = null
        if (!live.hasControl) {
            publish(SystemEqStatus.NO_CONTROL)
            return
        }
        release()
        publish(SystemEqStatus.UNAVAILABLE)
    }

    private fun release() {
        val held = effect ?: return
        effect = null
        applied = null
        try {
            held.release()
        } catch (error: Exception) {
            log("the system EQ did not release cleanly", error)
        }
    }

    /** Tells [onActiveChanged] the engine has the mix, ahead of the read-back that makes it [SystemEqStatus.ON]. */
    private fun claim() {
        if (active) return
        active = true
        onActiveChanged(true)
    }

    private fun publish(status: SystemEqStatus) {
        val isActive = status == SystemEqStatus.ON
        _state.value = SystemEqualizerState(wanted = wanted, status = status)
        if (isActive != active) {
            active = isActive
            onActiveChanged(isActive)
        }
    }

    /** Null when the effect holds [plan]; otherwise what it does not. */
    private fun mismatch(plan: SystemEqPlan, back: SystemEqReadBack): String? {
        if (!back.enabled) return "not enabled"
        if (back.channelBandGainsDb.isEmpty()) return "no channels"
        back.channelBandGainsDb.forEachIndexed { channel, gains ->
            if (gains.size != plan.bands.size) return "channel $channel has ${gains.size} bands"
            plan.bands.forEachIndexed { band, wantedBand ->
                if (abs(gains[band] - wantedBand.gainDb) > GainToleranceDb) {
                    return "channel $channel band $band is ${gains[band]} dB, not ${wantedBand.gainDb}"
                }
            }
        }
        if (abs(back.inputGainDb - plan.inputGainDb) > GainToleranceDb) return "preamp is ${back.inputGainDb} dB"
        if (!back.limiterEnabled) return "limiter off"
        if (abs(back.limiterThresholdDb - plan.limiter.thresholdDb) > GainToleranceDb) return "limiter at ${back.limiterThresholdDb} dB"
        return null
    }

    companion object {
        /** The effect stores floats and may quantise; a gain within this of the request is the request. */
        const val GainToleranceDb = 0.25f
    }
}
