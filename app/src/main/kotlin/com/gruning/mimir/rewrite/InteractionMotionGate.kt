package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class InteractionRoot {
    PRIMARY,
    COMPANION,
    NATIVE_WINDOW,
}

enum class InteractionSource {
    CONTROLLER,
    TOUCH,
    POINTER,
    WHEEL,
    NATIVE_M_BUTTON,
    NATIVE_MENU,
    NATIVE_EDGE_BACK,
    NATIVE_OVERLAY_PANEL,
    THEME_CHANGE,
}

interface InteractionMotionGate {
    val phase: StateFlow<AmbientMotionPhase>

    /**
     * @param root which display the interaction came from. Defaulted so a caller that genuinely does not
     *   know (a synthetic pulse like a theme change) stays honest by passing nothing rather than guessing
     *   PRIMARY — a wrong root here would silently mis-gate the companion's ambient motion.
     */
    fun pulse(source: InteractionSource, root: InteractionRoot? = null)

    fun hold(root: RootLease, source: InteractionSource): InteractionLease

    fun registerRoot(kind: InteractionRoot, initiallyVisible: Boolean): RootLease
}

interface RootLease : AutoCloseable {
    val id: UUID

    fun setVisible(visible: Boolean)

    override fun close()
}

fun interface InteractionLease : AutoCloseable {
    override fun close()
}

fun interface MonotonicClock {
    fun nowMillis(): Long
}

data class InteractionMotionLogEvent(
    val phase: AmbientMotionPhase,
    val atMillis: Long,
    val source: InteractionSource?,
    val root: InteractionRoot?,
)

fun interface InteractionMotionLogger {
    fun log(event: InteractionMotionLogEvent)
}

/**
 * Process-scoped ambient-motion gate. The owner supplies a process [CoroutineScope]; root lifetimes are
 * represented independently by UUID leases, so stale Activity/window teardown cannot affect replacements.
 */
class DefaultInteractionMotionGate(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val delayMillis: suspend (Long) -> Unit = { durationMillis -> delay(durationMillis) },
    private val logger: InteractionMotionLogger = InteractionMotionLogger {},
) : InteractionMotionGate {
    private val lock = Any()
    private val mutablePhase = MutableStateFlow(AmbientMotionPhase.IDLE)
    private val roots = mutableMapOf<UUID, RootHandle>()
    private val activeGestures = mutableSetOf<GestureHandle>()

    private var deadlineGeneration = 0L
    private var deadlineJob: Job? = null
    private var recentDeadlineMillis: Long? = null

    /** The last wake of either kind, a root becoming visible included. The log line names it. */
    private var lastWake: WakeRecord? = null

    override val phase: StateFlow<AmbientMotionPhase> = mutablePhase.asStateFlow()

    override fun pulse(source: InteractionSource, root: InteractionRoot?) {
        synchronized(lock) {
            val nowMillis = clock.nowMillis()
            noteInteractionLocked(nowMillis, source, root)
            wakeLocked(nowMillis)
        }
    }

    override fun hold(root: RootLease, source: InteractionSource): InteractionLease {
        val handle = root as? RootHandle ?: return ClosedInteractionLease
        synchronized(lock) {
            if (roots[handle.id] !== handle || !handle.visibilityState) return ClosedInteractionLease

            val gesture = GestureHandle(root = handle, source = source)
            handle.gestures += gesture
            activeGestures += gesture
            noteInteractionLocked(clock.nowMillis(), source, handle.kind)
            cancelDeadlineLocked()
            setPhaseLocked(AmbientMotionPhase.INTERACTING)
            return gesture
        }
    }

    override fun registerRoot(kind: InteractionRoot, initiallyVisible: Boolean): RootLease {
        synchronized(lock) {
            var id: UUID
            do {
                id = UUID.randomUUID()
            } while (roots.containsKey(id))

            val root = RootHandle(id = id, kind = kind, visibilityState = initiallyVisible)
            roots[id] = root
            if (initiallyVisible) {
                visibilityWakeLocked(root, clock.nowMillis())
            }
            return root
        }
    }

    /**
     * An interaction: a pulse, a held gesture, or the release that ends the last one. It is the last wake the log line names,
     * with the root it came from.
     */
    private fun noteInteractionLocked(
        nowMillis: Long,
        source: InteractionSource,
        rootKind: InteractionRoot?,
    ) {
        lastWake = WakeRecord(nowMillis, source, rootKind)
    }

    /**
     * a root becoming visible wakes the gate for the full window, but it is not an interaction. The log line names
     * the root that woke the gate (O18, a restart is not someone driving the companion).
     */
    private fun visibilityWakeLocked(root: RootHandle, nowMillis: Long) {
        lastWake = WakeRecord(nowMillis, source = null, rootKind = root.kind)
        wakeLocked(nowMillis)
    }

    private fun wakeLocked(nowMillis: Long) {
        if (activeGestures.isNotEmpty()) {
            cancelDeadlineLocked()
            setPhaseLocked(AmbientMotionPhase.INTERACTING)
        } else {
            enterRecentLocked(nowMillis)
        }
    }

    private fun enterRecentLocked(nowMillis: Long) {
        recentDeadlineMillis = nowMillis + IDLE_TIMEOUT_MILLIS
        setPhaseLocked(AmbientMotionPhase.RECENT)
        if (deadlineJob != null) return

        deadlineGeneration += 1L
        val generation = deadlineGeneration
        deadlineJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            awaitDeadline(generation)
        }
    }

    private suspend fun awaitDeadline(generation: Long) {
        while (true) {
            val remainingMillis = synchronized(lock) {
                val deadlineMillis = recentDeadlineMillis
                if (
                    generation != deadlineGeneration ||
                    activeGestures.isNotEmpty() ||
                    mutablePhase.value != AmbientMotionPhase.RECENT ||
                    deadlineMillis == null
                ) {
                    null
                } else {
                    val remainingMillis = deadlineMillis - clock.nowMillis()
                    if (remainingMillis <= 0L) {
                        recentDeadlineMillis = null
                        deadlineJob = null
                        setPhaseLocked(AmbientMotionPhase.IDLE)
                    }
                    remainingMillis
                }
            } ?: return

            if (remainingMillis <= 0L) return
            delayMillis(remainingMillis)
        }
    }

    private fun cancelDeadlineLocked() {
        deadlineGeneration += 1L
        recentDeadlineMillis = null
        deadlineJob?.cancel()
        deadlineJob = null
    }

    private fun setPhaseLocked(phase: AmbientMotionPhase) {
        if (mutablePhase.value == phase) return
        mutablePhase.value = phase
        val wake = lastWake
        runCatching {
            logger.log(
                InteractionMotionLogEvent(
                    phase = phase,
                    atMillis = clock.nowMillis(),
                    source = wake?.source,
                    root = wake?.rootKind,
                ),
            )
        }
    }

    private fun setRootVisible(root: RootHandle, visible: Boolean) {
        synchronized(lock) {
            if (roots[root.id] !== root || root.visibilityState == visible) return
            root.visibilityState = visible
            if (visible) {
                visibilityWakeLocked(root, clock.nowMillis())
            } else {
                releaseRootGesturesLocked(root)
            }
        }
    }

    private fun closeRoot(root: RootHandle) {
        synchronized(lock) {
            if (roots[root.id] !== root) return
            roots.remove(root.id)
            root.visibilityState = false
            releaseRootGesturesLocked(root)
        }
    }

    private fun releaseRootGesturesLocked(root: RootHandle) {
        if (root.gestures.isEmpty()) return
        activeGestures.removeAll(root.gestures)
        val released = root.gestures.last()
        root.gestures.clear()
        if (activeGestures.isEmpty()) {
            val nowMillis = clock.nowMillis()
            noteInteractionLocked(nowMillis, released.source, root.kind)
            enterRecentLocked(nowMillis)
        }
    }

    private fun releaseGesture(gesture: GestureHandle) {
        synchronized(lock) {
            if (!activeGestures.remove(gesture)) return
            gesture.root.gestures.remove(gesture)
            if (activeGestures.isEmpty()) {
                val nowMillis = clock.nowMillis()
                noteInteractionLocked(nowMillis, gesture.source, gesture.root.kind)
                enterRecentLocked(nowMillis)
            }
        }
    }

    private inner class RootHandle(
        override val id: UUID,
        val kind: InteractionRoot,
        var visibilityState: Boolean,
    ) : RootLease {
        val gestures = mutableSetOf<GestureHandle>()

        override fun setVisible(visible: Boolean) {
            this@DefaultInteractionMotionGate.setRootVisible(this, visible)
        }

        override fun close() {
            this@DefaultInteractionMotionGate.closeRoot(this)
        }
    }

    private inner class GestureHandle(
        val root: RootHandle,
        val source: InteractionSource,
    ) : InteractionLease {
        override fun close() {
            releaseGesture(this)
        }
    }

    private data class WakeRecord(
        val atMillis: Long,
        val source: InteractionSource?,
        val rootKind: InteractionRoot?,
    )

    companion object {
        const val IDLE_TIMEOUT_MILLIS = 12_000L
    }
}

private object ClosedInteractionLease : InteractionLease {
    override fun close() = Unit
}

private object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000L
}
