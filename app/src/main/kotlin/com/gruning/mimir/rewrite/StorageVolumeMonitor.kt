package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Detects SD-card swap/ejection live.
 *
 * Listens to [StorageManager]'s volume callbacks and reconciles library availability against the
 * set of mounted volumes: games whose source root lives on an absent volume flip to
 * `STORAGE_UNAVAILABLE` (never deleted — the design constraint), and flip back
 * to `AVAILABLE` the moment the same card returns. Home membership + recently-played already
 * filter on `availability = 'AVAILABLE'`, so shelves react through their existing Flows.
 *
 * A start-time reconcile covers the swap-while-Mimir-was-dead case (no callback ever fired).
 * A different/unknown card mounting is only ever an INTAKE OFFER (quiet notice pointing at
 * ADD GAMES) — never an auto-scan of a stranger's card.
 */
object StorageVolumeMonitor {

    enum class NoticeKind { CARD_REMOVED, CARD_SWAPPED }

    /** What the quiet banner should say; null = nothing to say (steady state). */
    data class StorageNotice(val kind: NoticeKind, val unavailableCount: Int)

    private val _notice = MutableStateFlow<StorageNotice?>(null)
    val notice: StateFlow<StorageNotice?> = _notice.asStateFlow()

    private val started = AtomicBoolean(false)
    /** Volume callbacks are process work; contain reconcile failures at the monitor boundary. */
    internal val scopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(TAG, "background volume reconcile failed in $context", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + scopeFailureHandler)

    // Conflated trigger: a mount storm (checking→mounted, multi-partition cards) collapses into
    // one trailing reconcile instead of queueing N identical passes. replay = 1 so the startup
    // emit below is NOT lost against the just-launched collector — that emit is the app-was-dead
    // swap reconcile, so dropping it would miss exactly the case start-time reconcile exists for.
    private val kick = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Remembers which volumes mounted since the last reconcile, so the art memo is dropped when one did. */
    private val artRefresh = StorageMountArtRefresh()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val storage = app.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        scope.launch {
            kick.collect { runCatching { reconcile(app, storage) }.onFailure { Log.e(TAG, "reconcile failed", it) } }
        }
        storage.registerStorageVolumeCallback(
            ContextCompat.getMainExecutor(app),
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    artRefresh.onVolumeState(volume.state)
                    kick.tryEmit(Unit)
                }
            },
        )
        kick.tryEmit(Unit) // startup pass: catch a swap that happened while Mimir was off
    }

    /** Touch-dismiss from the banner; the next real volume event may re-raise it. */
    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun reconcile(app: Context, storage: StorageManager) {
        val db = MimirDatabaseFactory.get(app)
        val roots = db.sourceRootDao().allOnce()
        val mountedUuids = storage.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { it.uuid?.uppercase() }
            .toSet()
        val (present, absent) = roots.partition { VolumeRootMatcher.rootPresent(it.rootUri, mountedUuids) }
        val nowMillis = System.currentTimeMillis()
        val absentRootUris = absent.map { it.rootUri }
        val presentRootUris = present.map { it.rootUri }
        val availability = ProcessSourceRootMutations.coordinator.applyPresenceSnapshot(
            absentRootUris = absentRootUris,
            presentRootUris = presentRootUris,
            rootPresentAtSnapshot = { rootUri ->
                VolumeRootMatcher.rootPresent(rootUri, mountedUuids)
            },
        ) {
            db.gameIdentityDao().reconcileRootAvailability(
                absentRootUris = absentRootUris,
                presentRootUris = presentRootUris,
                nowMillis = nowMillis,
            )
        }
        val flipped = availability.markedUnavailable
        val restored = availability.restoredAvailable
        val unavailable = db.gameDao().storageUnavailableCount()
        val knownCardUuids = roots.mapNotNull { VolumeRootMatcher.volumeUuidOf(it.rootUri)?.uppercase() }.toSet()
        val strangerMounted = mountedUuids.any { it !in knownCardUuids }
        _notice.value = when {
            unavailable > 0 && strangerMounted -> StorageNotice(NoticeKind.CARD_SWAPPED, unavailable)
            unavailable > 0 -> StorageNotice(NoticeKind.CARD_REMOVED, unavailable)
            else -> null // everything home again (or nothing card-bound) — banner clears itself
        }
        if (flipped > 0 || restored > 0) {
            Log.i(TAG, "reconcile: $flipped unavailable, $restored restored, $unavailable total absent")
        }
        // A card coming back: covers probed while it was away were memoised as absent. Drop the memo AND bump the
        // library's media epoch (refreshAllMedia does both) so the shelves repaint without a restart.
        if (artRefresh.afterReconcile(restored) { LibraryInjection.repository(app).refreshAllMedia() }) {
            Log.i(TAG, "reconcile: volume back, art memo dropped")
        }
    }

    private const val TAG = "MimirStorage"
}
