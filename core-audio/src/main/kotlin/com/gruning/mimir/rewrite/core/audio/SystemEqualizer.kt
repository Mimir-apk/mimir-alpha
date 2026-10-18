package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The global engine of the one Equalizer: Mimir's equalizer curve applied to the global audio mix (audio session 0) by a Mimir-owned
 * [DynamicsProcessing], so it reaches Mimir's own music, music apps and the games on the normal mixer. Normal privilege
 * (`MODIFY_AUDIO_SETTINGS`); no root. It is wanted exactly while the Equalizer's one switch ([EqualizerSettings.enabled]) is on.
 *
 * **It lives as long as the process, held here.** An effect on the mix is owned by the app that created it: when Mimir's process
 * ends, the platform drops the effect with it, so the equalizer cannot outlive the process and is re-attached by [start] at the next
 * one. [start] is called from `Application.onCreate`, the one entry that runs on every process start (a launcher restart, a media
 * button, a work wake), and this object holds the effect for as long as that process stands. The music service is not that
 * holder: it stops itself whenever Your music is not the source, and the effect must not go with it.
 *
 * **Coverage.** Games on the normal mixer are covered. A low-latency (FAST) track bypasses software effects in AudioFlinger, so a game
 * on one is not. The device vendor's own equalizer, where there is one, is a different effect: the two never contest control, both
 * apply, and they stack. Nothing here can switch the vendor's off.
 *
 * **Your music is not equalized twice.** From the moment the engine claims the mix (before it pushes the curve) until it stops
 * applying it, [AudioPreferences.equalizer] (what Your music's player applies) is the choice with its switch off;
 * [AudioPreferences.equalizerChoice] (what the editor edits) is untouched. When the engine cannot attach, is refused or loses control
 * the claim is retracted and the in-player equalizer is back, so the Equalizer still shapes Mimir's music: that is the one stated
 * fallback ([EqualizerReach.MIMIR_ONLY]).
 */
object SystemEqualizer {
    private const val Tag = "MimirSystemEq"

    private val failureHandler = CoroutineExceptionHandler { _, error -> Log.e(Tag, "system EQ work failed", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureHandler)

    private val controller = SystemEqualizerController(
        factory = DynamicsProcessingFactory(scope),
        onActiveChanged = { AudioPreferences.setSystemEqualizerActive(it) },
        log = { message, error -> if (error != null) Log.w(Tag, message, error) else Log.w(Tag, message) },
    )

    private val host = SystemEqualizerHost(
        controller = controller,
        scope = scope,
        // The one switch: the engine is wanted exactly while the Equalizer is on. Both flows come off the one stored choice.
        wanted = AudioPreferences.equalizerChoice.map { it.enabled }.distinctUntilChanged(),
        curve = AudioPreferences.equalizerChoice,
        // Each time Your music starts to play: the one moment to find out that the audio server restarted under the effect.
        // A provider, so MimirMusicControl is not touched (and its collectors not started) by Application.onCreate on Main.
        playing = { MimirMusicControl.state.map { it.yours?.isPlaying == true }.distinctUntilChanged().filter { it } },
    )

    /** What the editor and the Settings row show: the Equalizer's switch as asked for, and what the effect is honestly doing about it. */
    val state: StateFlow<SystemEqualizerState> get() = controller.state

    /**
     * Follows the stored Equalizer from now on (idempotent): reads it if nothing has, attaches the effect if its switch is on, and
     * keeps the effect on the curve. The work is off the main thread: creating an effect is a call into the audio server.
     */
    fun start(context: Context) {
        val app = context.applicationContext
        host.start(load = { AudioPreferences.ensureEqualizerLoaded(app) })
        if (!logging) {
            logging = true
            scope.launch {
                controller.state.collect { Log.i(Tag, "system EQ ${it.status} (wanted=${it.wanted})") }
            }
        }
    }

    @Volatile
    private var logging = false

    /**
     * Tries the effect again from scratch, off the main thread. For the editor's "try again" while Android has refused the effect: the
     * stored switch is already on, so there is no change for the host to see.
     */
    fun retry() {
        scope.launch { controller.retry() }
    }
}

/**
 * Feeds a [SystemEqualizerController] the stored switch and the curve, from the first start of the process on: [start] reads what
 * is stored ([load]) and then hands the controller the switch and the curve as they stand and as they change. This is what makes a
 * switch left on come back after the process died. [playing] emits each time Your music starts to play, and the controller checks
 * that its effect is still there. Idempotent.
 */
internal class SystemEqualizerHost(
    private val controller: SystemEqualizerController,
    private val scope: CoroutineScope,
    private val wanted: Flow<Boolean>,
    private val curve: Flow<EqualizerSettings>,
    private val playing: () -> Flow<Boolean>,
) {
    @Volatile
    private var started = false

    fun start(load: () -> Unit) {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            load()
            combine(wanted, curve) { switch, choice -> switch to choice }
                .collect { (switch, choice) -> controller.update(switch, choice) }
        }
        scope.launch { playing().collect { controller.verify() } }
    }
}

/**
 * Attaches a [DynamicsProcessing] to session 0, the global mix.
 *
 * The effect is made on a thread with no Looper, so the platform delivers its control callbacks on the main looper. The callback does
 * nothing there but hand the work to [scope]: the controller's answer to it (an apply and a read-back, dozens of calls into the audio
 * server) never runs on Main.
 */
internal class DynamicsProcessingFactory(private val scope: CoroutineScope) : SystemEqEffectFactory {
    override fun create(plan: SystemEqPlan, onControlChanged: () -> Unit): SystemEqEffect {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            SystemEqPlans.ChannelCount,
            /* preEqInUse = */ true,
            plan.bands.size,
            /* mbcInUse = */ false,
            0,
            /* postEqInUse = */ false,
            0,
            /* limiterInUse = */ true,
        ).build()
        val effect = DynamicsProcessing(Priority, OutputMixSession, config)
        effect.setControlStatusListener { _, _ -> scope.launch { onControlChanged() } }
        return DynamicsProcessingEffect(effect)
    }

    private companion object {
        /** Above the default (0), so a plain client of the same effect does not take it; another app asking higher still can. */
        const val Priority = 100

        /** Audio session 0 is the output mix: every track that is not on a session of its own. */
        const val OutputMixSession = 0
    }
}

internal class DynamicsProcessingEffect(private val effect: DynamicsProcessing) : SystemEqEffect {
    override val hasControl: Boolean get() = effect.hasControl()

    override fun apply(plan: SystemEqPlan) {
        val eq = DynamicsProcessing.Eq(/* inUse = */ true, /* enabled = */ true, plan.bands.size)
        plan.bands.forEachIndexed { index, band ->
            eq.setBand(index, DynamicsProcessing.EqBand(/* enabled = */ true, band.cutoffHz, band.gainDb))
        }
        effect.setPreEqAllChannelsTo(eq)
        effect.setInputGainAllChannelsTo(plan.inputGainDb)
        val limiter = plan.limiter
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse = */ true,
                /* enabled = */ true,
                /* linkGroup = */ 0,
                limiter.attackMs,
                limiter.releaseMs,
                limiter.ratio,
                limiter.thresholdDb,
                limiter.postGainDb,
            ),
        )
    }

    override fun isEnabled(): Boolean = effect.getEnabled()

    override fun setEnabled(enabled: Boolean): Boolean = effect.setEnabled(enabled) == AudioEffect.SUCCESS

    override fun readBack(): SystemEqReadBack {
        val channels = effect.channelCount
        val limiter = effect.getLimiterByChannelIndex(0)
        return SystemEqReadBack(
            enabled = effect.getEnabled(),
            channelBandGainsDb = (0 until channels).map { channel ->
                val eq = effect.getPreEqByChannelIndex(channel)
                (0 until eq.bandCount).map { band -> eq.getBand(band).gain }
            },
            inputGainDb = effect.getInputGainByChannelIndex(0),
            limiterEnabled = limiter.isEnabled,
            limiterThresholdDb = limiter.threshold,
        )
    }

    override fun release() = effect.release()
}
