package com.gruning.mimir.rewrite.core.companionnative

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable

/**
 * One sampled view of the currently eligible secondary displays.
 *
 * [selectedId] is for the companion Presentation/overlay/launch surfaces. [soleEligibleId] is a
 * deliberately stricter value for vendor brightness: it is non-null only when exactly one eligible
 * candidate exists, so a deterministic companion fallback can never retarget vendor panel I/O.
 */
data class SecondaryDisplaySnapshot(
    val eligibleIds: List<Int>,
    val selectedId: Int?,
    val soleEligibleId: Int?,
) {
    init {
        require(eligibleIds == eligibleIds.sorted().distinct()) {
            "eligibleIds must be sorted and unique"
        }
        require(selectedId == null || selectedId in eligibleIds) {
            "selectedId must be eligible"
        }
        require(soleEligibleId == eligibleIds.singleOrNull()) {
            "soleEligibleId must represent exactly one eligible display"
        }
    }
}

data class SecondaryDisplayCandidate(
    val displayId: Int,
    val isPresentation: Boolean,
    val isValid: Boolean,
)

/**
 * Stateful, Android-free part of the selector. State and listener registration are synchronized;
 * callbacks are serialized and always observe a snapshot that has already been published.
 */
internal class SecondaryDisplaySelectionModel(
    private val onListenerFailure: (Throwable) -> Unit = {},
) {
    private data class PendingNotification(
        val generation: Long,
        val snapshot: SecondaryDisplaySnapshot,
    )

    private val listeners = linkedSetOf<(SecondaryDisplaySnapshot) -> Unit>()
    private val pendingNotifications = ArrayDeque<PendingNotification>()
    private var generation = 0L
    private var dispatching = false
    private var closed = false
    private val snapshotMutable = MutableStateFlow(
        selectSecondaryDisplaySnapshot(null, emptyList()),
    )

    val snapshot: SecondaryDisplaySnapshot
        get() = snapshotMutable.value

    val flow: StateFlow<SecondaryDisplaySnapshot> = snapshotMutable.asStateFlow()

    @Synchronized
    fun addListener(listener: (SecondaryDisplaySnapshot) -> Unit): Closeable {
        if (closed) return Closeable {}
        listeners += listener
        notifyListener(listener, snapshot)
        return Closeable {
            synchronized(this) {
                listeners -= listener
            }
        }
    }

    @Synchronized
    fun update(candidates: List<SecondaryDisplayCandidate>): Boolean {
        if (closed) return false
        val next = selectSecondaryDisplaySnapshot(snapshot.selectedId, candidates)
        if (next == snapshot) return false
        snapshotMutable.value = next
        generation += 1
        pendingNotifications += PendingNotification(generation, next)
        dispatchPendingNotificationsLocked()
        return true
    }

    @Synchronized
    fun close() {
        closed = true
        listeners.clear()
        pendingNotifications.clear()
    }

    private fun dispatchPendingNotificationsLocked() {
        if (dispatching) return
        dispatching = true
        try {
            while (pendingNotifications.isNotEmpty()) {
                val notification = pendingNotifications.removeFirst()
                // A callback may synchronously publish a newer snapshot. Stop delivering the
                // stale copy; the queued newer notification will visit the current listeners.
                for (listener in listeners.toList()) {
                    if (notification.generation != generation) break
                    if (listener !in listeners) continue
                    notifyListener(listener, notification.snapshot)
                }
            }
        } finally {
            dispatching = false
        }
    }

    private fun notifyListener(listener: (SecondaryDisplaySnapshot) -> Unit, value: SecondaryDisplaySnapshot) {
        runCatching { listener(value) }.onFailure { error ->
            runCatching { onListenerFailure(error) }
        }
    }
}

/** Pure policy seam; the Android adapter below is the only display-list owner. */
internal fun selectSecondaryDisplaySnapshot(
    currentSelectedId: Int?,
    candidates: List<SecondaryDisplayCandidate>,
): SecondaryDisplaySnapshot {
    val eligibleIds = candidates
        .asSequence()
        .filter { candidate ->
            candidate.displayId != Display.DEFAULT_DISPLAY &&
                candidate.isPresentation &&
                candidate.isValid
        }
        .map(SecondaryDisplayCandidate::displayId)
        .distinct()
        .sorted()
        .toList()
    val selectedId = currentSelectedId?.takeIf { it in eligibleIds } ?: eligibleIds.firstOrNull()
    return SecondaryDisplaySnapshot(
        eligibleIds = eligibleIds,
        selectedId = selectedId,
        soleEligibleId = eligibleIds.singleOrNull(),
    )
}

/**
 * Process-owned DisplayManager adapter. It samples once per framework callback and publishes the
 * snapshot before notifying consumers. Activity listeners must close their subscription on stop;
 * the process owner remains registered for the life of the process owner.
 */
class SecondaryDisplaySelector(context: Context) {
    private companion object {
        const val TAG = "MimirSecondaryDisplay"
    }

    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val model = SecondaryDisplaySelectionModel(
        onListenerFailure = { error -> Log.e(TAG, "secondary-display consumer failed", error) },
    )
    private var registered = false
    @Volatile private var closed = false

    val snapshot: StateFlow<SecondaryDisplaySnapshot> = model.flow

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        runCatching {
            displayManager?.registerDisplayListener(displayListener, mainHandler)
            registered = displayManager != null
        }.onFailure { error ->
            Log.w(TAG, "failed to observe secondary-display availability", error)
        }
        refresh()
    }

    /**
     * Register a consumer and immediately give it the current snapshot. Listener failures are
     * isolated so one Activity or native adapter cannot starve the other consumers.
     */
    fun addListener(listener: (SecondaryDisplaySnapshot) -> Unit): Closeable {
        return model.addListener(listener)
    }

    /** Re-sample the current list on [mainHandler]; callbacks are serialized by the model. */
    fun refresh() {
        if (closed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::refresh)
            return
        }
        val candidates = runCatching {
            displayManager
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.map { display ->
                    SecondaryDisplayCandidate(
                        displayId = display.displayId,
                        isPresentation = display.flags and Display.FLAG_PRESENTATION != 0,
                        isValid = display.isValid,
                    )
                }
                .orEmpty()
        }.getOrElse { error ->
            Log.w(TAG, "failed to sample secondary-display availability", error)
            emptyList()
        }
        model.update(candidates)
    }

    /** Process lifetime normally ends with the process; this is useful for deterministic host tests. */
    fun close() {
        closed = true
        if (registered) {
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
            registered = false
        }
        model.close()
    }
}
