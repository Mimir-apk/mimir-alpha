package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val StorageGrantPendingRootKey = "storageGrant.pendingRootUri"

internal data class StorageGrantSnapshot(val roots: List<StoredRoot>, val grants: List<PersistedGrant>)

internal suspend fun readStorageGrantSnapshot(context: Context): StorageGrantSnapshot = withContext(Dispatchers.IO) {
    val roots = MimirDatabaseFactory.get(context).sourceRootDao().allOnce().map {
        StoredRoot(it.rootUri, it.locationKind, it.friendlyLabel)
    }
    val grants = context.contentResolver.persistedUriPermissions.map {
        PersistedGrant(it.uri.toString(), it.isReadPermission)
    }
    StorageGrantSnapshot(roots, grants)
}

/** One Activity's recovery work. Lifecycle/picker events and state access run on the main thread. */
internal class StorageGrantRecovery(
    private val scope: CoroutineScope,
    private val readSnapshot: suspend () -> StorageGrantSnapshot,
    openPicker: (String) -> Unit,
    pendingRootUri: String? = null,
) {
    var pendingRootUri: String? = pendingRootUri
        private set
    private var auditJob: Job? = null
    private var closed = false
    private var openPicker: ((String) -> Unit)? = openPicker

    fun audit() {
        if (closed) return
        auditJob?.cancel()
        auditJob = scope.launch {
            try {
                val snapshot = readSnapshot()
                ensureActive()
                StorageGrantAuditCoordinator.publish(snapshot.roots, snapshot.grants)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A failed read must preserve the last verdict, never publish an all-clear.
                logStorageGrantRecovery("audit failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun requestRegrant(rootUri: String) {
        if (closed || pendingRootUri != null) return
        pendingRootUri = rootUri
        try {
            openPicker?.invoke(rootUri)
        } catch (failure: Exception) {
            pendingRootUri = null
            logStorageGrantRecovery("regrant picker failed: ${failure.javaClass.simpleName}")
        }
    }

    fun onPickerResult(pickedTreeUri: String?) {
        if (closed) return
        val pending = pendingRootUri ?: return
        pendingRootUri = null
        if (pickedTreeUri == null) {
            logStorageGrantRecovery("regrant cancelled")
        } else {
            val picked = safTreeIdentity(pickedTreeUri)
            val requested = safTreeIdentity(pending)
            val covers = picked != null && requested != null && safTreeCovers(picked, requested)
            logStorageGrantRecovery("regrant covers=$covers")
        }
        audit()
    }

    fun close() {
        closed = true
        auditJob?.cancel()
        auditJob = null
        pendingRootUri = null
        openPicker = null
    }
}

internal fun logStorageGrantRecovery(line: String) {
    // Plain JVM grant-policy tests use Android's throwing Log stub.
    runCatching { Log.i(StorageGrantLogTag, line) }
}
