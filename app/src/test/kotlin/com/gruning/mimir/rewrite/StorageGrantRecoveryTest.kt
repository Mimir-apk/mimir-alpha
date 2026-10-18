package com.gruning.mimir.rewrite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageGrantRecoveryTest {
    private val uri = "content://com.android.externalstorage.documents/tree/primary%3AROMs"
    private val root = StoredRoot(uri, SafTreeGrantLocationKind, "Games")
    private val missing = StorageGrantSnapshot(listOf(root), emptyList())
    private val granted = missing.copy(grants = listOf(PersistedGrant(uri, true)))

    @Before fun reset() = StorageGrantAuditCoordinator.resetForTest()

    @Test fun `resume rechecks live grants and a partial grant leaves the other root missing`() = runTest {
        val other = root.copy(rootUri = "$uri%2FOther", friendlyLabel = "Other")
        var snapshot = StorageGrantSnapshot(listOf(root, other), emptyList())
        val owner = StorageGrantRecovery(this, { snapshot }, {})
        owner.audit()
        runCurrent()
        assertEquals(listOf(root, other), StorageGrantAuditCoordinator.lostRoots.value)
        snapshot = snapshot.copy(grants = listOf(PersistedGrant(other.rootUri, true)))
        owner.audit()
        runCurrent()
        assertEquals(listOf(root), StorageGrantAuditCoordinator.lostRoots.value)
        snapshot = granted
        owner.audit()
        runCurrent()
        assertTrue(StorageGrantAuditCoordinator.lostRoots.value.isEmpty())
        owner.close()
    }

    @Test fun `a superseded noncooperative read cannot publish over a newer audit`() = runTest {
        val oldRead = CompletableDeferred<StorageGrantSnapshot>()
        var reads = 0
        val owner = StorageGrantRecovery(this, {
            if (reads++ == 0) withContext(NonCancellable) { oldRead.await() } else granted
        }, {})
        owner.audit()
        runCurrent()
        owner.audit()
        runCurrent()
        oldRead.complete(missing)
        runCurrent()
        assertTrue(StorageGrantAuditCoordinator.lostRoots.value.isEmpty())
        owner.close()
    }

    @Test fun `read failure preserves the last verdict and cancellation cleans up`() = runTest {
        StorageGrantAuditCoordinator.publish(missing.roots, missing.grants)
        var cancelled = false
        var fail = true
        val owner = StorageGrantRecovery(this, {
            if (fail) error("unavailable database")
            try { awaitCancellation() } finally { cancelled = true }
        }, {})
        owner.audit()
        runCurrent()
        assertEquals(listOf(root), StorageGrantAuditCoordinator.lostRoots.value)
        fail = false
        owner.audit()
        runCurrent()
        owner.close()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf(root), StorageGrantAuditCoordinator.lostRoots.value)
    }

    @Test fun `duplicate reconnect is ignored and cancellation allows retry without hiding the prompt`() = runTest {
        StorageGrantAuditCoordinator.publish(missing.roots, missing.grants)
        val launched = mutableListOf<String>()
        val owner = StorageGrantRecovery(this, { missing }, launched::add)
        owner.requestRegrant(uri)
        owner.requestRegrant(uri)
        assertEquals(listOf(uri), launched)
        owner.onPickerResult(null)
        runCurrent()
        assertNull(owner.pendingRootUri)
        assertEquals(root, storageGrantPromptRoot(StorageGrantAuditCoordinator.lostRoots.value, false))
        owner.requestRegrant(uri)
        assertEquals(listOf(uri, uri), launched)
        owner.onPickerResult("content://com.android.externalstorage.documents/tree/primary%3AUnrelated")
        runCurrent()
        assertEquals(listOf(root), StorageGrantAuditCoordinator.lostRoots.value)
        owner.close()
    }

    @Test fun `picker launch failure releases the request for retry`() = runTest {
        var attempts = 0
        val owner = StorageGrantRecovery(this, { missing }, { attempts++; error("no picker") })
        owner.requestRegrant(uri)
        assertNull(owner.pendingRootUri)
        owner.requestRegrant(uri)
        assertEquals(2, attempts)
        owner.close()
    }

    @Test fun `replacement host restores only pending identity and old cleanup cannot detach it`() = runTest {
        val firstLaunches = mutableListOf<String>()
        val first = StorageGrantRecovery(this, { missing }, firstLaunches::add)
        StorageGrantAuditCoordinator.attach(first)
        StorageGrantAuditCoordinator.requestRegrant(root)
        val savedIdentity = first.pendingRootUri
        val replacementLaunches = mutableListOf<String>()
        val replacement = StorageGrantRecovery(this, { granted }, replacementLaunches::add, savedIdentity)
        StorageGrantAuditCoordinator.attach(replacement)
        StorageGrantAuditCoordinator.detach(first)
        first.onPickerResult(uri)
        assertEquals(uri, replacement.pendingRootUri)
        replacement.onPickerResult(uri)
        runCurrent()
        assertTrue(StorageGrantAuditCoordinator.lostRoots.value.isEmpty())
        assertNull(replacement.pendingRootUri)
        assertTrue(replacementLaunches.isEmpty())
        StorageGrantAuditCoordinator.requestRegrant(root)
        assertEquals(listOf(uri), replacementLaunches)
        assertEquals(listOf(uri), firstLaunches)
        StorageGrantAuditCoordinator.detach(replacement)
        StorageGrantAuditCoordinator.requestRegrant(root)
        assertNull(replacement.pendingRootUri)
        assertEquals(listOf(uri), replacementLaunches)
    }

    @Test fun `close prevents late reads or picker results from changing state`() = runTest {
        val read = CompletableDeferred<StorageGrantSnapshot>()
        var launched = false
        val owner = StorageGrantRecovery(this, { withContext(NonCancellable) { read.await() } }, { launched = true })
        owner.audit()
        runCurrent()
        owner.close()
        read.complete(missing)
        owner.onPickerResult(uri)
        owner.requestRegrant(uri)
        owner.audit()
        runCurrent()
        assertTrue(StorageGrantAuditCoordinator.lostRoots.value.isEmpty())
        assertFalse(launched)
    }
}
