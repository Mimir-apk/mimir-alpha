package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Crash-loop safety for [MimirInputMethodService] now that it can be the system default keyboard.
 *
 * A try/catch cannot see an exception thrown inside Compose's composition or draw: composition starts after the
 * view is attached, on the main looper, and takes the whole process down. A default keyboard that does that on
 * every show would leave the player with none. So the service leaves a PERSISTENT trail:
 *
 *  1. Before a keyboard is shown it writes an "armed" marker (which keyboard, and which process).
 *  2. Once the keyboard has drawn a frame and stayed up a short while ([StableMillis]), or on ANY orderly teardown
 *     (finish, hide, destroy -- drawn or not: the process did not die), the marker is cleared.
 *  3. A service that starts and finds a marker from a DIFFERENT process still set knows that process died while
 *     the keyboard was showing. A Compose keyboard that died forces the plain fallback keyboard for this and every
 *     later session until the next app update; a fallback that died, or a Compose keyboard that has died twice,
 *     also turns Mimir's own "default keyboard" preference off and asks for the fixed revert (op D' `ime disable`,
 *     or op D `ime reset` when Mimir is the only enabled keyboard), so Android's own keyboard returns.
 *  4. That demand is itself written down ([ImeSafetyState.revertDemanded]) in the same commit, BEFORE the revert
 *     runs, and stays on disk until a durable OFF has landed (D38): a revert whose OFF commit failed twice must
 *     not let a later process apply the keyboard again just because the stored preference still says ON.
 *
 * What this cannot cover (said plainly, because it is the limit of the design): a crash before the marker is
 * written (the service's own start-up, before `onCreateInputView`); a crash after the marker was cleared (more
 * than [StableMillis] into a session -- the next show re-arms and would then catch a repeat); a crash that kills
 * the process but happens before this process's first persisted write reaches disk (the arm uses a synchronous
 * commit, so only an immediate kernel-level kill can lose it); and the system's own policy for a keyboard process
 * that keeps dying, which is outside this app.
 *
 * Everything decided here is a pure function of [ImeSafetyState] so it is unit-tested without Android.
 */
internal enum class ImeMode { COMPOSE, FALLBACK }

internal data class ImeSafetyState(
    /** Identifies the installed build: crash flags are about THIS build, so an update starts fresh. */
    val versionStamp: Long = 0L,
    /** Which keyboard was showing when the marker was written, or null when nothing is armed. */
    val armedMode: ImeMode? = null,
    /** The process that armed it; a marker from the SAME process is a live session, not a crash. */
    val armedProcess: String = "",
    /** Compose keyboards found dead. Survives an app update on purpose: a crash that returns is the second. */
    val composeCrashes: Int = 0,
    /** Use the fallback keyboard until the next app update. */
    val fallbackForced: Boolean = false,
    /**
     * A safety revert was demanded and its durable OFF has not been confirmed yet (D38). A debt, not a crash flag:
     * like [composeCrashes] it survives an app update, unlike [fallbackForced]. Set with the revert, in the commit
     * that precedes it; cleared only by [ImeSafetyStore.clearRevertDemand], after the OFF is on disk.
     */
    val revertDemanded: Boolean = false,
)

internal data class ImeSafetyDecision(
    val mode: ImeMode,
    val state: ImeSafetyState,
    /** Turn the default-keyboard preference off and run the fixed reset: the keyboard cannot be trusted. */
    val revertDefault: Boolean,
)

/** Called when the service creates its input view: reads the trail a dead process left, and chooses the keyboard. */
internal fun imeSafetyAtCreate(
    state: ImeSafetyState,
    versionStamp: Long,
    processToken: String,
): ImeSafetyDecision {
    var s = if (state.versionStamp == versionStamp) {
        state
    } else {
        // A new build: the fallback lock lifts and any stale marker is moot, but the count of Compose deaths
        // is kept, so the same crash coming back after an update is the SECOND one.
        state.copy(versionStamp = versionStamp, fallbackForced = false, armedMode = null, armedProcess = "")
    }
    var revert = false
    val armed = s.armedMode
    if (armed != null && s.armedProcess != processToken) {
        when (armed) {
            ImeMode.COMPOSE -> {
                s = s.copy(composeCrashes = s.composeCrashes + 1, fallbackForced = true)
                if (s.composeCrashes >= 2) revert = true
            }
            ImeMode.FALLBACK -> {
                s = s.copy(fallbackForced = true)
                revert = true
            }
        }
        s = s.copy(armedMode = null, armedProcess = "")
    }
    // The demand rides the same commit as the rest of the verdict: it is on disk before the revert starts.
    if (revert) s = s.copy(revertDemanded = true)
    return ImeSafetyDecision(
        mode = if (s.fallbackForced) ImeMode.FALLBACK else ImeMode.COMPOSE,
        state = s,
        revertDefault = revert,
    )
}

/**
 * May an AUTOMATIC apply of the keyboard run? READ-ONLY: the verdict [imeSafetyAtCreate] would reach for a service
 * starting in this process, never written. No while a revert is still owed or being demanded, while the fallback is
 * forced, and while a dead process's armed marker is still on disk (that marker is the crash the next service start
 * will count). The Settings switch is the player's explicit choice and is not gated by this.
 */
internal fun imeKeyboardTrusted(state: ImeSafetyState, versionStamp: Long, processToken: String): Boolean {
    val decision = imeSafetyAtCreate(state, versionStamp, processToken)
    return !decision.state.revertDemanded && !decision.revertDefault && decision.mode == ImeMode.COMPOSE
}

/** Written before a keyboard is shown. */
internal fun imeSafetyArm(state: ImeSafetyState, mode: ImeMode, processToken: String): ImeSafetyState =
    state.copy(armedMode = mode, armedProcess = processToken)

/** Written once the keyboard has stayed up; only the process that armed it may clear it. */
internal fun imeSafetyDisarm(state: ImeSafetyState, processToken: String): ImeSafetyState =
    if (state.armedProcess == processToken) state.copy(armedMode = null, armedProcess = "") else state

/** Where the trail lives. Synchronous on purpose: the arm must reach disk before the first frame. */
internal interface ImeSafetyStore {
    fun read(): ImeSafetyState

    /** One synchronous write of the whole state: true only when it reached storage. */
    fun write(state: ImeSafetyState): Boolean

    /**
     * Clears [ImeSafetyState.revertDemanded] and nothing else: true only when it reached storage. The default is the
     * whole-state write; the real store writes just that key, so a controller thread clearing it cannot clobber a
     * marker the keyboard arms on the main thread at the same moment.
     */
    fun clearRevertDemand(): Boolean {
        val state = read()
        return !state.revertDemanded || write(state.copy(revertDemanded = false))
    }

    /**
     * Writes only [ImeSafetyState.armedMode] and [ImeSafetyState.armedProcess]: true only when it reached storage. The
     * default is the whole-state write; the real store writes just those keys, so arming or disarming on the main thread
     * cannot write back a stale [ImeSafetyState.revertDemanded] that a controller thread has just cleared.
     */
    fun writeArmed(state: ImeSafetyState): Boolean = write(state)
}

internal class SharedPrefsImeSafetyStore(context: Context) : ImeSafetyStore {
    private val prefs = context.applicationContext.getSharedPreferences("mimir_ime_safety", Context.MODE_PRIVATE)

    override fun read(): ImeSafetyState = ImeSafetyState(
        versionStamp = prefs.getLong("version", 0L),
        armedMode = prefs.getString("armedMode", null)?.let { name -> ImeMode.entries.firstOrNull { it.name == name } },
        armedProcess = prefs.getString("armedProcess", "").orEmpty(),
        composeCrashes = prefs.getInt("composeCrashes", 0),
        fallbackForced = prefs.getBoolean("fallbackForced", false),
        revertDemanded = prefs.getBoolean("revertDemanded", false),
    )

    override fun write(state: ImeSafetyState): Boolean {
        val written = prefs.edit()
            .putLong("version", state.versionStamp)
            .putString("armedMode", state.armedMode?.name)
            .putString("armedProcess", state.armedProcess)
            .putInt("composeCrashes", state.composeCrashes)
            .putBoolean("fallbackForced", state.fallbackForced)
            .putBoolean("revertDemanded", state.revertDemanded)
            .commit()
        if (!written) Log.w("MimirImeSafety", "safety trail write did not reach storage")
        return written
    }

    override fun clearRevertDemand(): Boolean {
        val written = prefs.edit().putBoolean("revertDemanded", false).commit()
        if (!written) Log.w("MimirImeSafety", "safety trail revert-demand clear did not reach storage")
        return written
    }

    override fun writeArmed(state: ImeSafetyState): Boolean {
        val written = prefs.edit()
            .putString("armedMode", state.armedMode?.name)
            .putString("armedProcess", state.armedProcess)
            .commit()
        if (!written) Log.w("MimirImeSafety", "safety trail arm write did not reach storage")
        return written
    }
}

/** One token per process: a marker carrying it is a live session, any other is a process that died. */
internal object ImeProcess {
    val token: String = UUID.randomUUID().toString()

    /** How long a drawn keyboard must stay up before its marker is cleared. */
    const val StableMillis = 3_000L

    /** The installed build's identity: its last-update time, so an update lifts the fallback lock. */
    fun versionStamp(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
}
