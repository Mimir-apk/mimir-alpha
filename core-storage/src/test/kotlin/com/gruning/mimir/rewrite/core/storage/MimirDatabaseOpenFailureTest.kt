package com.gruning.mimir.rewrite.core.storage

import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class MimirDatabaseOpenFailureTest {

    @Test
    fun `only a schema newer than the supported version is refused`() {
        assertNull(MimirDatabaseFactory.newerSchemaFailure(9, 10))
        assertNull(MimirDatabaseFactory.newerSchemaFailure(10, 10))

        val failure = MimirDatabaseFactory.newerSchemaFailure(11, 10)
        assertEquals(DatabaseOpenFailureReason.NEWER_SCHEMA, failure?.reason)
        assertEquals(11, failure?.foundSchemaVersion)
        assertEquals(10, failure?.supportedSchemaVersion)
    }

    @Test
    fun `exact missing downgrade message is classified through its cause chain`() {
        val error = RuntimeException(
            "outer",
            IllegalStateException(
                "A migration from 11 to 10 was required but not found. " +
                    "Please provide the necessary Migration path.",
            ),
        )

        val failure = MimirDatabaseFactory.classifyRoomOpenFailure(error, 10)

        assertEquals(DatabaseOpenFailureReason.NEWER_SCHEMA, failure?.reason)
        assertEquals(11, failure?.foundSchemaVersion)
    }

    @Test
    fun `missing upgrade and unrelated failures are not mislabeled`() {
        assertNull(
            MimirDatabaseFactory.classifyRoomOpenFailure(
                IllegalStateException("A migration from 9 to 10 was required but not found."),
                10,
            ),
        )
        assertNull(
            MimirDatabaseFactory.classifyRoomOpenFailure(
                IllegalStateException("database disk image is malformed"),
                10,
            ),
        )
    }

    @Test
    fun `supported schema version follows the migration ladder`() {
        // v14 (2026-08-30): source_reconcile_runs.skippedByReason, so a ledgered Rescan All can carry
        // the skip breakdown its receipt already carried counts for.
        assertEquals(14, MimirDatabaseFactory.supportedSchemaVersion())
    }

    @Test
    fun `first newer schema refusal permanently blocks every later borrower`() {
        val latch = DatabaseOpenRefusalLatch()
        val failure = MimirDatabaseFactory.newerSchemaFailure(11, 10)!!

        val discovered = assertThrows(DatabaseOpenRefusedException::class.java) {
            latch.refuse(failure)
        }
        val laterWorker = assertThrows(DatabaseOpenRefusedException::class.java) {
            latch.throwIfRefused()
        }

        assertSame(failure, discovered.failure)
        assertSame(failure, laterWorker.failure)
    }

    @Test
    fun `legacy identity refusal permanently blocks every later borrower`() {
        val latch = DatabaseOpenRefusalLatch()
        val failure = DatabaseOpenFailure(
            reason = DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE,
            foundSchemaVersion = 10,
            supportedSchemaVersion = 11,
        )

        val discovered = assertThrows(DatabaseOpenRefusedException::class.java) {
            latch.refuse(failure)
        }
        val laterWorker = assertThrows(DatabaseOpenRefusedException::class.java) {
            latch.throwIfRefused()
        }

        assertSame(failure, discovered.failure)
        assertSame(failure, laterWorker.failure)
    }
}
