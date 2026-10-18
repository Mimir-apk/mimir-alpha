package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailure
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseRecoveryGateTest {

    @Test
    fun `owner copy names exact versions and never offers destructive recovery`() {
        val detail = databaseRecoveryDetail(
            DatabaseOpenFailure(
                reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
                foundSchemaVersion = 10,
                supportedSchemaVersion = 9,
            ),
        )
        val allCopy = listOf(
            DatabaseRecoveryEyebrow,
            DatabaseRecoveryTitle,
            detail,
            DatabaseRecoveryInstruction,
            DatabaseRecoveryClose,
        ).joinToString(" ")

        assertTrue(detail.contains("DATABASE 10"))
        assertTrue(detail.contains("DATABASE 9"))
        assertTrue(allCopy.contains("NOTHING WAS CHANGED"))
        assertTrue(allCopy.contains("DO NOT CLEAR APP STORAGE"))
        assertFalse(allCopy.contains("RESET"))
        assertFalse(allCopy.contains("DELETE"))
        assertFalse(allCopy.contains("START FRESH"))
    }

    @Test
    fun `confirm and exit intents close while every other mapped action is contained`() {
        listOf(
            ControllerAction.CONFIRM,
            ControllerAction.CANCEL,
            ControllerAction.SYSTEM_BACK,
            ControllerAction.SYSTEM_HOME,
        ).forEach { action ->
            assertEquals(DatabaseRecoveryCommand.CLOSE, databaseRecoveryCommand(action))
        }
        ControllerAction.entries
            .filterNot {
                it == ControllerAction.CONFIRM ||
                    it == ControllerAction.CANCEL ||
                    it == ControllerAction.SYSTEM_BACK ||
                    it == ControllerAction.SYSTEM_HOME
            }
            .forEach { action ->
                assertEquals(DatabaseRecoveryCommand.CONSUME, databaseRecoveryCommand(action))
            }
    }

    @Test
    fun `runtime intents stay quarantined for the full refusal lifecycle`() {
        val failure = DatabaseOpenFailure(
            reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
            foundSchemaVersion = 10,
            supportedSchemaVersion = 9,
        )

        assertFalse(databaseRecoveryAllowsRuntimeIntents(failure))
        assertTrue(databaseRecoveryAllowsRuntimeIntents(null))
    }

    @Test
    fun `unsafe legacy identity refusal is byte preserving and offers no guess`() {
        val failure = DatabaseOpenFailure(
            reason = DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE,
            foundSchemaVersion = 10,
            supportedSchemaVersion = 10,
        )
        val detail = databaseRecoveryDetail(failure)
        val instruction = databaseRecoveryInstruction(failure)

        assertTrue(detail.contains("LEGACY FILE LOCATION"))
        assertTrue(detail.contains("NOTHING WAS CHANGED"))
        assertTrue(instruction.contains("KEEP APP STORAGE INTACT"))
        assertFalse("$detail $instruction".contains("RESET"))
        assertFalse("$detail $instruction".contains("DELETE"))
    }
}
