package com.gruning.mimir.rewrite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDatCatalogueLatchTest {

    @Test
    fun `matching stamp is a no-op`() = runBlocking {
        val events = mutableListOf<String>()

        val result = reconcileBundledDatCatalogue(
            storedStamp = "same",
            currentStamp = "same",
            resetUnmatchedIdentity = { events += "reset"; 7 },
            persistStamp = { events += "persist"; true },
        )

        assertFalse(result.changed)
        assertEquals(0, result.clearedUnmatched)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `missing or changed stamp resets before it persists`() = runBlocking {
        var stored: String? = null
        val events = mutableListOf<String>()

        val first = reconcileBundledDatCatalogue(
            storedStamp = stored,
            currentStamp = "new",
            resetUnmatchedIdentity = { events += "reset"; 5 },
            persistStamp = { stamp -> events += "persist:$stamp"; stored = stamp; true },
        )
        val second = reconcileBundledDatCatalogue(
            storedStamp = stored,
            currentStamp = "new",
            resetUnmatchedIdentity = { events += "reset-again"; 0 },
            persistStamp = { events += "persist-again"; true },
        )

        assertTrue(first.changed)
        assertEquals(5, first.clearedUnmatched)
        assertFalse(second.changed)
        assertEquals(listOf("reset", "persist:new"), events)
    }

    @Test
    fun `reset failure leaves the stamp unpersisted for retry`() = runBlocking {
        var persisted = false
        val failure = runCatching {
            reconcileBundledDatCatalogue(
                storedStamp = "old",
                currentStamp = "new",
                resetUnmatchedIdentity = { error("database unavailable") },
                persistStamp = { persisted = true; true },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(persisted)
    }

    @Test
    fun `persistence failure remains retryable after reset`() = runBlocking {
        var resets = 0
        val failure = runCatching {
            reconcileBundledDatCatalogue(
                storedStamp = "old",
                currentStamp = "new",
                resetUnmatchedIdentity = { resets++; 3 },
                persistStamp = { false },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(1, resets)
    }

    @Test
    fun `shared catalogue reset clears before enqueuing trailing work`() = runBlocking {
        val events = mutableListOf<String>()

        val cleared = resetUnmatchedIdentityForCatalogueChange(
            clearUnmatchedIdentity = { events += "clear"; 11 },
            enqueueTrailingPass = { events += "enqueue" },
        )

        assertEquals(11, cleared)
        assertEquals(listOf("clear", "enqueue"), events)
    }

    @Test
    fun `asynchronous enqueue failure leaves catalogue stamp retryable`() = runBlocking {
        var persisted = false
        val events = mutableListOf<String>()

        val failure = runCatching {
            reconcileBundledDatCatalogue(
                storedStamp = "old",
                currentStamp = "new",
                resetUnmatchedIdentity = {
                    resetUnmatchedIdentityForCatalogueChange(
                        clearUnmatchedIdentity = { events += "clear"; 4 },
                        enqueueTrailingPass = {
                            events += "enqueue-await"
                            error("WorkManager insertion failed")
                        },
                    )
                },
                persistStamp = { persisted = true; true },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("clear", "enqueue-await"), events)
        assertFalse(persisted)
    }
}
