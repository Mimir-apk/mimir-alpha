package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V11_GAME_AUDIO_FOCUS_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.TweakerGameAudioFocusResult
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A game Mimir launches cannot take audio focus, so the music already playing carries on.
 *
 * Policy 2026-10-01. Device evidence (Nova, 2026-10-01): an emulator taking audio focus at launch makes
 * YouTube Music pause and never resume. The Tweaker (API v11) sets the game's `TAKE_AUDIO_FOCUS` app-op to
 * `ignore` before the game starts, which makes that package's `requestAudioFocus` fail while its audio still
 * plays, and puts it back afterwards.
 *
 * **Degrades silently.** No Tweaker, an older Tweaker, a refusal, a timeout: nothing is held, the launch goes
 * ahead, and music behaves as it always did. A hold is never awaited past [HOLD_BUDGET_MS], never changes what
 * the launch does, and never raises.
 *
 * **Why its own binding.** The hold must outlive the launch's short bind-observe-unbind window: the daemon is
 * a bound service and is destroyed with its last client, and a destroyed daemon puts the package back. So the
 * transport stays bound from a confirmed hold until the release, independent of the Tweaker bridge's session
 * reservation (a hold is not a profile and takes nothing from it). The Binder token the transport sends is
 * the process's own, so this process dying releases the hold on the daemon's side with no help from here.
 *
 * Every state change is one log line with its result, under [LOG_TAG].
 */
internal class GameAudioFocusHolder(
    private val transport: GameAudioFocusTransport,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit,
    /** Where the blocking Binder calls run. Injected so a virtual-time test is not raced by a real thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()

    /** The package this process asked the daemon to hold and has not seen put back. Set BEFORE the call. */
    @Volatile
    var mayBeHeld: String? = null
        private set

    /**
     * Ask for the hold, before the game starts. Returns the outcome for the caller's diagnostics; the launch
     * does not depend on it.
     */
    suspend fun holdForLaunch(packageName: String): GameAudioFocusHoldOutcome {
        if (!enabled()) {
            // The setting may have been turned off while something was still held: put it back first.
            releaseAfterReturn("setting off")
            return outcome(packageName, GameAudioFocusHoldOutcome.SkippedSettingOff)
        }
        if (isMimirPackage(packageName)) return outcome(packageName, GameAudioFocusHoldOutcome.SkippedOwnPackage)
        val result = withTimeoutOrNull(HOLD_BUDGET_MS) { lock.withLock { holdLocked(packageName) } }
            ?: GameAudioFocusHoldOutcome.TimedOut
        return outcome(packageName, result)
    }

    private suspend fun holdLocked(packageName: String): GameAudioFocusHoldOutcome {
        if (!transport.mayBind()) return GameAudioFocusHoldOutcome.Unavailable("Tweaker pair may not bind")
        if (!transport.bind(BIND_TIMEOUT_MS)) {
            transport.unbind()
            return GameAudioFocusHoldOutcome.Unavailable("Tweaker did not bind")
        }
        val apiVersion = transport.apiVersion()
        if (apiVersion == null) {
            transport.unbind()
            return GameAudioFocusHoldOutcome.Unavailable("Tweaker named no API version")
        }
        if (apiVersion < TWEAKER_V11_GAME_AUDIO_FOCUS_MIN_API_VERSION) {
            transport.unbind()
            return GameAudioFocusHoldOutcome.Unsupported(apiVersion)
        }
        // Before the call, not after: a reply that never arrives must still be put back at the return.
        mayBeHeld = packageName
        val reply = withContext(io) { transport.hold(apiVersion, packageName) }
        return when (reply) {
            is TweakerGameAudioFocusResult.Held -> GameAudioFocusHoldOutcome.Held(reply.packageName)
            is TweakerGameAudioFocusResult.Refused -> {
                if (!reply.releaseDebt) mayBeHeld = null
                transport.unbind()
                GameAudioFocusHoldOutcome.Refused(reply.code, reply.releaseDebt)
            }
            else -> {
                // Timed out or unreadable: the daemon may or may not have held it. Stay bound and keep the
                // obligation; the return releases it.
                GameAudioFocusHoldOutcome.Unavailable(reply.describe())
            }
        }
    }

    /** Put the package back, if any may be held. Cheap and silent when none is. Never raises. */
    suspend fun releaseAfterReturn(reason: String) {
        if (mayBeHeld == null) return
        withContext(NonCancellable) {
            lock.withLock {
                val held = mayBeHeld ?: return@withLock
                val result = releaseLocked()
                if (result.settled) mayBeHeld = null
                log("release package=$held reason=$reason result=${result.label}")
            }
        }
    }

    private suspend fun releaseLocked(): ReleaseResult {
        try {
            if (!transport.mayBind()) return ReleaseResult("UNAVAILABLE(pair)", settled = false)
            if (!transport.bind(BIND_TIMEOUT_MS)) return ReleaseResult("UNAVAILABLE(bind)", settled = false)
            val apiVersion = transport.apiVersion()
                ?: return ReleaseResult("UNAVAILABLE(version)", settled = false)
            if (apiVersion < TWEAKER_V11_GAME_AUDIO_FOCUS_MIN_API_VERSION) {
                // The daemon was downgraded under a hold; nothing this process can ask.
                return ReleaseResult("UNSUPPORTED($apiVersion)", settled = true)
            }
            return when (val reply = withContext(io) { transport.release(apiVersion) }) {
                is TweakerGameAudioFocusResult.Released -> ReleaseResult("RELEASED", settled = true)
                TweakerGameAudioFocusResult.NothingHeld -> ReleaseResult("NOTHING_HELD", settled = true)
                is TweakerGameAudioFocusResult.Retained -> ReleaseResult("RETAINED(${reply.code})", settled = false)
                else -> ReleaseResult("UNAVAILABLE(${reply.describe()})", settled = false)
            }
        } finally {
            // Always unbound afterwards: the daemon's journal and its destroy-time release are the safety
            // net for anything left over, and a binding held for nothing keeps the daemon alive for nothing.
            transport.unbind()
        }
    }

    private fun outcome(packageName: String, outcome: GameAudioFocusHoldOutcome): GameAudioFocusHoldOutcome {
        log("hold package=$packageName result=${outcome.label}")
        return outcome
    }

    private fun TweakerGameAudioFocusResult.describe(): String = when (this) {
        is TweakerGameAudioFocusResult.Unavailable -> reason
        is TweakerGameAudioFocusResult.Refused -> code
        is TweakerGameAudioFocusResult.Retained -> code
        is TweakerGameAudioFocusResult.Held -> "HELD"
        is TweakerGameAudioFocusResult.Released -> "RELEASED"
        TweakerGameAudioFocusResult.NothingHeld -> "NOTHING_HELD"
    }

    private data class ReleaseResult(val label: String, val settled: Boolean)

    internal companion object {
        const val LOG_TAG = "MimirGameAudioFocus"

        /**
         * The whole hold, bind to readback. The daemon's reply is bounded at 3 s by the session; this caps the
         * sequence so a slow bind plus a slow call cannot eat the launch's 9.5 s pre-dispatch budget.
         */
        const val HOLD_BUDGET_MS = 4_500L
        const val BIND_TIMEOUT_MS = 1_500L

        fun isMimirPackage(packageName: String): Boolean =
            packageName == "com.gruning.mimir" || packageName.startsWith("com.gruning.mimir.")
    }
}

internal sealed interface GameAudioFocusHoldOutcome {
    val label: String

    data class Held(val packageName: String) : GameAudioFocusHoldOutcome {
        override val label get() = "HELD"
    }

    data class Refused(val code: String, val releaseDebt: Boolean) : GameAudioFocusHoldOutcome {
        override val label get() = "REFUSED($code${if (releaseDebt) ",debt" else ""})"
    }

    data class Unsupported(val apiVersion: Int) : GameAudioFocusHoldOutcome {
        override val label get() = "UNSUPPORTED(api=$apiVersion)"
    }

    data class Unavailable(val reason: String) : GameAudioFocusHoldOutcome {
        override val label get() = "UNAVAILABLE($reason)"
    }

    data object TimedOut : GameAudioFocusHoldOutcome {
        override val label get() = "TIMED_OUT"
    }

    data object SkippedSettingOff : GameAudioFocusHoldOutcome {
        override val label get() = "SKIPPED(setting off)"
    }

    data object SkippedOwnPackage : GameAudioFocusHoldOutcome {
        override val label get() = "SKIPPED(own package)"
    }
}

/** The holder's whole view of the Tweaker. A seam so sequencing is host-testable. */
internal interface GameAudioFocusTransport {
    /** Only the signed pair may be bound; the bridge's own gate. */
    fun mayBind(): Boolean
    suspend fun bind(timeoutMs: Long): Boolean
    suspend fun apiVersion(): Int?
    fun hold(apiVersion: Int, packageName: String): TweakerGameAudioFocusResult
    fun release(apiVersion: Int): TweakerGameAudioFocusResult
    fun unbind()
}

internal class SessionGameAudioFocusTransport(
    private val session: TweakerSession,
    private val pairMayBind: () -> Boolean,
) : GameAudioFocusTransport {
    override fun mayBind(): Boolean = pairMayBind()
    override suspend fun bind(timeoutMs: Long): Boolean = session.bind(timeoutMs)
    override suspend fun apiVersion(): Int? = session.getApiVersionAsync()
    override fun hold(apiVersion: Int, packageName: String) = session.holdGameAudioFocusV11(apiVersion, packageName)
    override fun release(apiVersion: Int) = session.releaseGameAudioFocusV11(apiVersion)
    override fun unbind() = session.unbind()
}
