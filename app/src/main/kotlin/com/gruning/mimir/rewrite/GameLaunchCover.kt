package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.gruning.mimir.rewrite.core.companionnative.ActivityResumedObservation
import com.gruning.mimir.rewrite.core.launch.LaunchCoverRule
import com.gruning.mimir.rewrite.core.launch.launchCoverRuleFor
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val GameLaunchCoverLogTag = "MimirOpening"

/** The steps the cover's audio fade takes, the same count as the intro's own fades. */
internal const val GameLaunchCoverAudioFadeSteps = 20

/** What the controller needs of the cover's window. Main thread only. */
internal interface GameLaunchCoverWindow {
    /** Adds the window showing [snapshot]; false if it could not be added. [onRevealed] runs once, when it is first visible. */
    fun mount(snapshot: GameLaunchCoverSnapshot, onRevealed: () -> Unit): Boolean

    /** Starts the picture's exit over [glideMs]: it drifts and fades on one clock, and the window stays until [removeImmediate]. */
    fun beginGlide(glideMs: Long)

    /** Removes the window NOW, with `removeViewImmediate` and no exit animation. Safe when nothing is mounted. */
    fun removeImmediate()
}

/** The intro's audio player, handed to the cover at the yield. */
internal interface GameLaunchCoverAudio {
    /** Fades to silence over [durationMs], then keeps silence until [release]. */
    fun fadeOut(durationMs: Long)

    /** Stops and releases the player. Safe to call twice. */
    fun release()
}

/** Runs tasks on the main thread at a monotonic time (`SystemClock.uptimeMillis`). */
internal fun interface GameLaunchCoverScheduler {
    /** The returned handle cancels the task if it has not run. */
    fun postAt(uptimeMs: Long, task: () -> Unit): AutoCloseable
}

/**
 * The launch cover: a window over the emulator's own loading, between the intent and the game's screen.
 * [GameLaunchCoverMachine] decides; this carries it out and owns everything the machine may not: the window, the audio,
 * the wake-ups and the UsageStats subscription. Every method runs on the main thread ([GameLaunchCover] sees to that) and
 * every end goes through [finish], the one place a window is taken away, so nothing that ends the cover can skip the rest.
 *
 * [observeResumes] is [com.gruning.mimir.rewrite.core.companionnative.ActivityResumeObserver.observeActivityResumes]; its
 * callback arrives on the monitor's thread and is brought to the main thread by [postToMain]. Resumes of [ownPackageName]
 * are ignored: Mimir coming back is `MainActivity.onStart`'s to report, synchronously, and UsageStats is later than that.
 */
internal class GameLaunchCoverController(
    private val window: GameLaunchCoverWindow,
    private val observeResumes: (sinceEpochMs: Long, onResumed: (ActivityResumedObservation) -> Unit) -> AutoCloseable,
    private val scheduler: GameLaunchCoverScheduler,
    private val postToMain: (() -> Unit) -> Unit,
    private val uptimeMs: () -> Long,
    private val epochMs: () -> Long,
    private val ownPackageName: String,
    private val glideMs: () -> Long = { GameLaunchCoverGlideMs },
    private val timing: GameLaunchCoverTiming = GameLaunchCoverTiming(),
    private val log: (String) -> Unit = { line -> Log.i(GameLaunchCoverLogTag, line) },
) {
    private class Live(
        var state: GameLaunchCoverState,
        val snapshot: GameLaunchCoverSnapshot,
        val observation: AutoCloseable,
    ) {
        var wake: AutoCloseable? = null
        var audio: GameLaunchCoverAudio? = null

        /** The activity that ended the cover as a stranger, for the `cover end` line. */
        var foreignActivity: String? = null
    }

    private var live: Live? = null

    /** The game whose cover is up, readable from any thread: the cheap way for a caller to learn there is nothing to do. */
    @Volatile
    var liveGameIdValue: String? = null
        private set

    /**
     * Adds the window, before the intent. False (and nothing left behind) when it could not be added. A cover still up
     * from an earlier launch is ended first: one window, one launch.
     */
    fun arm(snapshot: GameLaunchCoverSnapshot, rule: LaunchCoverRule, onRevealed: () -> Unit): Boolean {
        live?.let { finish(it, GameLaunchCoverEnd.TEARDOWN) }
        if (!window.mount(snapshot, onRevealed)) {
            log("cover skip game=${snapshot.gameIdValue} profile=${rule.packageName} reason=mount-failed")
            return false
        }
        val nowMs = uptimeMs()
        val nowEpochMs = epochMs()
        val observation = runCatching {
            observeResumes(nowEpochMs) { observed -> postToMain { onResumed(observed) } }
        }.getOrElse { failure ->
            log("cover observe unavailable game=${snapshot.gameIdValue} reason=${failure.message ?: failure.javaClass.simpleName}")
            AutoCloseable { }
        }
        val entry = Live(GameLaunchCoverMachine.arm(rule, nowMs, nowEpochMs), snapshot, observation)
        live = entry
        liveGameIdValue = snapshot.gameIdValue
        log("cover start game=${snapshot.gameIdValue} profile=${rule.packageName} hero=${snapshot.heroUri}")
        schedule(entry, GameLaunchCoverMachine.settle(entry.state, nowMs, currentTiming()).wakeAtMs)
        return true
    }

    /**
     * Mimir stopped: the ceiling's clock starts. [audio], if the intro had any, is the cover's from here. True when a
     * cover is live for [gameIdValue] (the caller then leaves the audio alone); false, with [audio] released, when not.
     */
    fun yielded(gameIdValue: String, audio: GameLaunchCoverAudio?): Boolean {
        val entry = live?.takeIf { it.snapshot.gameIdValue == gameIdValue }
        if (entry == null) {
            audio?.release()
            return false
        }
        if (audio != null) {
            if (entry.audio == null) {
                entry.audio = audio
                // The glide may have begun before the yield (Dolphin's game screen is up before Mimir stops): join it.
                entry.state.glide?.let { glide ->
                    val remainingMs = glide.startedAtMs + currentTiming().glideMs - uptimeMs()
                    if (remainingMs > 0L) audio.fadeOut(remainingMs)
                }
            } else {
                audio.release()
            }
        }
        dispatch(GameLaunchCoverEvent.Yielded)
        return true
    }

    /** `MainActivity.onStop` for a stop that is not a configuration change: Mimir ceded the screen. */
    fun hostStopped() = dispatch(GameLaunchCoverEvent.Yielded)

    /** (a) `MainActivity.onStart`: the player came back. The window is gone before this returns. */
    fun hostStarted() = dispatch(GameLaunchCoverEvent.HostStarted)

    /** The launch of [gameIdValue] did not take Mimir's screen. */
    fun settled(gameIdValue: String) {
        if (live?.snapshot?.gameIdValue == gameIdValue) dispatch(GameLaunchCoverEvent.Settled)
    }

    /** The window's owner is being torn down. */
    fun teardown() = dispatch(GameLaunchCoverEvent.Teardown)

    private fun onResumed(observed: ActivityResumedObservation) {
        val entry = live ?: return
        if (observed.packageName == ownPackageName) return
        val rule = entry.state.rule
        val nowMs = uptimeMs()
        if (observed.className == rule.gameActivity && entry.state.gameSeenAtMs == null &&
            observed.eventAtMs >= entry.state.armedAtEpochMs
        ) {
            log(
                "cover game-resumed game=${entry.snapshot.gameIdValue} activity=${observed.packageName}/${observed.className} " +
                    "lagMs=${observed.observedAtMs - observed.eventAtMs} sinceStartMs=${nowMs - entry.state.armedAtMs} " +
                    "sinceYieldMs=${sinceYield(entry, nowMs)}",
            )
        }
        entry.foreignActivity = "${observed.packageName}/${observed.className}"
        dispatch(GameLaunchCoverEvent.ActivityResumed(observed.packageName, observed.className, observed.eventAtMs))
        if (live === entry) entry.foreignActivity = null
    }

    private fun dispatch(event: GameLaunchCoverEvent) {
        val entry = live ?: return
        val nowMs = uptimeMs()
        val step = GameLaunchCoverMachine.reduce(entry.state, event, nowMs, currentTiming())
        entry.state = step.state
        when (val action = step.action) {
            GameLaunchCoverAction.None -> Unit
            is GameLaunchCoverAction.BeginGlide -> {
                val durationMs = currentTiming().glideMs
                window.beginGlide(durationMs)
                entry.audio?.fadeOut(durationMs)
                log(
                    "cover glide game=${entry.snapshot.gameIdValue} via=${action.end.logName} " +
                        "elapsedMs=${nowMs - entry.state.armedAtMs} sinceYieldMs=${sinceYield(entry, nowMs)}",
                )
            }
            is GameLaunchCoverAction.Remove -> {
                finish(entry, action.end)
                return
            }
        }
        schedule(entry, step.wakeAtMs)
    }

    /** The one place a cover ends: window first, because it is what the player sees, then everything it held. */
    private fun finish(entry: Live, end: GameLaunchCoverEnd) {
        val nowMs = uptimeMs()
        if (live === entry) {
            live = null
            liveGameIdValue = null
        }
        runCatching { window.removeImmediate() }
            .onFailure { log("cover window removal failed game=${entry.snapshot.gameIdValue} reason=${it.message ?: it.javaClass.simpleName}") }
        runCatching { entry.wake?.close() }
        entry.wake = null
        runCatching { entry.observation.close() }
        runCatching { entry.audio?.release() }
        entry.audio = null
        log(
            "cover end game=${entry.snapshot.gameIdValue} via=${end.logName} elapsedMs=${nowMs - entry.state.armedAtMs} " +
                "sinceYieldMs=${sinceYield(entry, nowMs)}" +
                (if (end == GameLaunchCoverEnd.FOREIGN) " activity=${entry.foreignActivity}" else ""),
        )
    }

    private fun schedule(entry: Live, wakeAtMs: Long?) {
        runCatching { entry.wake?.close() }
        entry.wake = wakeAtMs?.let { at ->
            scheduler.postAt(at) { if (live === entry) dispatch(GameLaunchCoverEvent.Tick) }
        }
    }

    private fun currentTiming(): GameLaunchCoverTiming = timing.copy(glideMs = glideMs())

    private fun sinceYield(entry: Live, nowMs: Long): String =
        entry.state.yieldedAtMs?.let { (nowMs - it).toString() } ?: "none"
}

/**
 * The intro's audio as the cover holds it. The fade is stepped on the main handler, as long as the cover's own glide.
 */
internal class MediaPlayerCoverAudio(
    private val player: MediaPlayer,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    /**
     * The level the intro was playing at: the fade starts from here, so a launch sound dialled below full never jumps back
     * up as the cover takes the player, and keeps the same shape (a straight ramp to silence) at any level.
     */
    private val startVolume: Float = 1f,
) : GameLaunchCoverAudio {
    private var released = false
    private var ramp: Runnable? = null

    override fun fadeOut(durationMs: Long) {
        if (released) return
        if (durationMs <= 0L) {
            release()
            return
        }
        ramp?.let(handler::removeCallbacks)
        val stepMs = (durationMs / GameLaunchCoverAudioFadeSteps).coerceAtLeast(1L)
        val next = object : Runnable {
            private var step = 0

            override fun run() {
                if (released) return
                step += 1
                val volume = gameLaunchCoverAudioVolume(step, GameLaunchCoverAudioFadeSteps, startVolume)
                runCatching { player.setVolume(volume, volume) }
                if (step < GameLaunchCoverAudioFadeSteps) handler.postDelayed(this, stepMs)
            }
        }
        ramp = next
        handler.postDelayed(next, stepMs)
    }

    override fun release() {
        if (released) return
        released = true
        ramp?.let(handler::removeCallbacks)
        ramp = null
        runCatching { player.release() }
    }
}

/**
 * The volume after [step] of [steps] of a straight fade from [startVolume] to silence. [startVolume] is the level the intro
 * was playing at (full by default), so the same shape runs from any level and the first step is always below it.
 */
internal fun gameLaunchCoverAudioVolume(step: Int, steps: Int, startVolume: Float = 1f): Float =
    startVolume.coerceIn(0f, 1f) * (1f - step.coerceIn(0, steps) / steps.toFloat()).coerceIn(0f, 1f)

/**
 * The process's one launch cover. Armed before the intent ([armGameLaunchCoverBeforeDispatch]), ended by
 * `MainActivity.onStart` ([hostStarted]), told of the yield by `MainActivity.onStop` ([hostStopped]) and by the intro's
 * finish step ([GameLaunchCoverHandoff]). It builds its controller (and so touches no Android window service) only when
 * a cover is first armed, so a build with no cover-rule launch never loads any of it.
 */
internal object GameLaunchCover : GameLaunchCoverHandoff {
    @Volatile
    private var controller: GameLaunchCoverController? = null

    private val mainHandler by lazy(LazyThreadSafetyMode.PUBLICATION) { Handler(Looper.getMainLooper()) }

    private fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun runOnMain(block: () -> Unit) {
        if (onMainThread()) block() else mainHandler.post(block)
    }

    /** Main thread. False, with nothing left behind, if the window could not be added. */
    internal fun arm(
        appContext: Context,
        snapshot: GameLaunchCoverSnapshot,
        rule: LaunchCoverRule,
        onRevealed: () -> Unit,
    ): Boolean = controllerFor(appContext).arm(snapshot, rule, onRevealed)

    private fun controllerFor(appContext: Context): GameLaunchCoverController =
        controller ?: synchronized(this) {
            controller ?: GameLaunchCoverController(
                window = GameLaunchCoverPresenter(appContext),
                observeResumes = { sinceEpochMs, onResumed ->
                    CompanionProcessRuntime.services(appContext).foregroundMonitor
                        .observeActivityResumes(sinceEpochMs, onResumed)
                },
                scheduler = { uptimeMs, task ->
                    val runnable = Runnable(task)
                    mainHandler.postAtTime(runnable, uptimeMs)
                    AutoCloseable { mainHandler.removeCallbacks(runnable) }
                },
                postToMain = { block -> mainHandler.post(block) },
                uptimeMs = SystemClock::uptimeMillis,
                epochMs = System::currentTimeMillis,
                ownPackageName = appContext.packageName,
                // Reduced motion (animators off) has no glide: the cover is simply taken away, like every other motion here.
                glideMs = { if (ValueAnimator.areAnimatorsEnabled()) GameLaunchCoverGlideMs else 0L },
            ).also { controller = it }
        }

    override fun yielded(gameIdValue: String, startVolume: Float, takePlayer: () -> MediaPlayer?): Boolean {
        val current = controller ?: return false
        if (current.liveGameIdValue != gameIdValue) return false
        // Taken here, on the caller's thread and before it returns: the caller releases the player it still holds next.
        val audio = runCatching(takePlayer).getOrNull()?.let { MediaPlayerCoverAudio(it, startVolume = startVolume) }
        runOnMain { current.yielded(gameIdValue, audio) }
        return true
    }

    override fun settled(gameIdValue: String) {
        val current = controller ?: return
        if (current.liveGameIdValue != gameIdValue) return
        runOnMain { current.settled(gameIdValue) }
    }

    /** `MainActivity.onStop`, not for a configuration change. */
    fun hostStopped() {
        val current = controller ?: return
        if (current.liveGameIdValue == null) return
        runOnMain { current.hostStopped() }
    }

    /** `MainActivity.onStart`: synchronous on the main thread, so the window is gone before Mimir's first frame. */
    fun hostStarted() {
        val current = controller ?: return
        if (current.liveGameIdValue == null) return
        runOnMain { current.hostStarted() }
    }

    /** A dispatch that failed after the cover was armed. */
    fun dispatchFailed(gameIdValue: String) = settled(gameIdValue)

    /** Ends any cover, for a process-wide teardown. */
    fun teardown() {
        val current = controller ?: return
        if (current.liveGameIdValue == null) return
        runOnMain { current.teardown() }
    }
}

/**
 * Adds the launch cover for [game]'s launch of [target], if it gets one. Runs in the launch's pre-dispatch hook, FIRST, ahead
 * of the Tweaker's status overlay (which must draw above it) and before `prepared.dispatch()` fires the intent, so the cover is
 * up before the emulator's own window is.
 *
 * Every gate is [gameLaunchCoverSkipReason]'s; a launch with a rule that is refused logs why under `MimirOpening`, and one
 * without a rule says nothing. Never throws: the hook is contained, but a cover is not worth a launch. After the window is
 * added it waits, bounded by [GameLaunchCoverRevealWaitMs], for the cover's first visible frame, so the intent does not fire
 * while the cover is still transparent; past the bound the launch goes and the cover reveals itself when it can.
 */
internal suspend fun armGameLaunchCoverBeforeDispatch(context: Context, game: GameRow, target: LaunchTarget) {
    runCatching {
        val appContext = context.applicationContext
        val gameIdValue = game.gameId.value
        val rule = launchCoverRuleFor(target)
        val runtime = if (rule != null) CompanionProcessRuntime.services(appContext) else null
        val snapshot = if (rule != null) GameOpeningIntroState.coverSnapshotFor(gameIdValue) else null
        val skip = gameLaunchCoverSkipReason(
            hasRule = rule != null,
            secondaryDisplayId = runtime?.taskRegistry?.state?.value?.secondaryDisplayId,
            introRunning = GameOpeningIntroState.current.value?.gameIdValue == gameIdValue,
            canDrawOverlays = Settings.canDrawOverlays(appContext),
            usageAccessGranted = runtime?.foregroundMonitor?.usageAccessState()?.granted == true,
            heroLoaded = snapshot != null,
        )
        if (skip != null) {
            if (skip != GameLaunchCoverSkip.NO_RULE) {
                Log.i(
                    GameLaunchCoverLogTag,
                    "cover skip game=$gameIdValue profile=${rule?.packageName} reason=${skip.logName}",
                )
            }
            return@runCatching
        }
        if (rule == null || snapshot == null) return@runCatching
        val revealed = CompletableDeferred<Unit>()
        val armed = withContext(Dispatchers.Main.immediate) {
            GameLaunchCover.arm(appContext, snapshot, rule) { revealed.complete(Unit) }
        }
        if (armed) withTimeoutOrNull(GameLaunchCoverRevealWaitMs) { revealed.await() }
    }.onFailure { failure ->
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        Log.w(GameLaunchCoverLogTag, "cover unavailable reason=${failure.message ?: failure.javaClass.simpleName}")
    }
}
