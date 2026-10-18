package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LibraryInjectionPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = closeStores()
    @After fun tearDown() {
        StorageGrantAuditCoordinator.resetForTest()
        closeStores()
    }

    @Test fun `production injection observes Room writes after reopening the database`() = runBlocking {
        val root = SourceRootEntity(
            rootId = "binding-test",
            rootUri = "content://com.android.externalstorage.documents/tree/primary%3ABindingTest",
            locationKind = "SAF_TREE_GRANT",
            friendlyLabel = "Binding test folder",
            isPrimarySource = true,
            lastSeenMillis = System.currentTimeMillis(),
        )
        val repository = LibraryInjection.repository(context)
        MimirDatabaseFactory.get(context).sourceRootDao().upsert(root)
        val before = withTimeout(10_000) { repository.observeSources().first() }.single()
        assertEquals(root.rootUri, before.rootUri)
        assertEquals(root.friendlyLabel, before.friendlyPath)
        assertEquals(true, before.isPrimary)

        closeStores()

        val reopened = LibraryInjection.repository(context)
        assertEquals(listOf(before), withTimeout(10_000) { reopened.observeSources().first() })
    }

    @Test fun `recovery reads persisted roots and current Android grants`() = runBlocking<Unit> {
        StorageGrantAuditCoordinator.resetForTest()
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ARecoveryTest"
        MimirDatabaseFactory.get(context).sourceRootDao().upsert(
            SourceRootEntity("recovery-test", uri, "SAF_TREE_GRANT", "Recovery folder", true, 1L),
        )
        val owner = StorageGrantRecovery(this, { readStorageGrantSnapshot(context) }, {})
        StorageGrantAuditCoordinator.attach(owner)
        try {
            owner.audit()
            withTimeout(10_000) { StorageGrantAuditCoordinator.lostRoots.first { it.singleOrNull()?.rootUri == uri } }
            owner.requestRegrant(uri)
            context.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            owner.onPickerResult(uri)
            withTimeout(10_000) { StorageGrantAuditCoordinator.lostRoots.first { it.isEmpty() } }
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            owner.audit()
            withTimeout(10_000) { StorageGrantAuditCoordinator.lostRoots.first { it.singleOrNull()?.rootUri == uri } }
        } finally {
            StorageGrantAuditCoordinator.detach(owner)
        }
    }

    // Match the real process caches, without introducing a production reset API for a test.
    private fun closeStores() {
        for (name in listOf("cached", "cachedHomeShelf")) {
            LibraryStore::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(LibraryStore, null)
        }
        MimirDatabaseFactory::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            (get(null) as? RoomDatabase)?.close()
            set(null, null)
        }
    }
}
