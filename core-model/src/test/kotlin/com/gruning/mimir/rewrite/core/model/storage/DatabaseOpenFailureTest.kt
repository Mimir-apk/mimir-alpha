package com.gruning.mimir.rewrite.core.model.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DatabaseOpenFailureTest {

    @Test
    fun `newer schema refusal retains exact versions`() {
        val failure = DatabaseOpenFailure(
            reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
            foundSchemaVersion = 10,
            supportedSchemaVersion = 9,
        )

        assertEquals(DatabaseOpenFailureReason.NEWER_SCHEMA, failure.reason)
        assertEquals(10, failure.foundSchemaVersion)
        assertEquals(9, failure.supportedSchemaVersion)
    }

    @Test
    fun `same or older schema cannot masquerade as newer`() {
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseOpenFailure(
                reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
                foundSchemaVersion = 9,
                supportedSchemaVersion = 9,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseOpenFailure(
                reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
                foundSchemaVersion = 8,
                supportedSchemaVersion = 9,
            )
        }
    }

    @Test
    fun `readable legacy schema can be refused for unsafe physical identity`() {
        val failure = DatabaseOpenFailure(
            reason = DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE,
            foundSchemaVersion = 10,
            supportedSchemaVersion = 10,
        )

        assertEquals(DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE, failure.reason)
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseOpenFailure(
                reason = DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE,
                foundSchemaVersion = 11,
                supportedSchemaVersion = 10,
            )
        }
    }
}
