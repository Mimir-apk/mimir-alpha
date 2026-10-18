package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreExcludedCategory
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestorePlan
import com.gruning.mimir.rewrite.core.model.recovery.RecoveryRestoreResult
import com.gruning.mimir.rewrite.core.model.recovery.RecoverySourceRootHint
import com.gruning.mimir.rewrite.core.model.recovery.RestoreStorePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationImportRestoreStateTest {
    private val confirmKey = FocusKey("migration/confirm")
    private val closeKey = FocusKey("migration/close")

    @Test
    fun restoreReadyAndRestoreDoneAreInteractiveAndDismissible() {
        val authority = seededAuthority()
        val restoreReady = MigrationUiState.RestoreReady(samplePreparedRestore())
        val restoreDone = MigrationUiState.RestoreDone(sampleRestoreResult())

        listOf(restoreReady, restoreDone).forEach { state ->
            var closed = false
            dispatchMigration(
                authority = authority,
                state = state,
                action = ControllerAction.CANCEL,
                onConfirm = {},
                onClose = { closed = true },
            )
            assertTrue("$state should dismiss on cancel", closed)

            closed = false
            dispatchMigration(
                authority = authority,
                state = state,
                action = ControllerAction.SYSTEM_BACK,
                onConfirm = {},
                onClose = { closed = true },
            )
            assertTrue("$state should dismiss on system back", closed)
        }
    }

    @Test
    fun restoreReadyConfirmFiresOnlyWhenConfirmIsFocused() {
        val authority = seededAuthority()
        val state = MigrationUiState.RestoreReady(samplePreparedRestore())
        var confirmed = false
        var closed = false

        authority.requestFocus(closeKey)
        dispatchMigration(
            authority = authority,
            state = state,
            action = ControllerAction.CONFIRM,
            onConfirm = { confirmed = true },
            onClose = { closed = true },
        )
        assertFalse("CONFIRM without confirm focus must not apply", confirmed)
        assertTrue("CONFIRM on interactive non-confirm focus should close", closed)

        confirmed = false
        closed = false
        authority.requestFocus(confirmKey)
        dispatchMigration(
            authority = authority,
            state = state,
            action = ControllerAction.CONFIRM,
            onConfirm = { confirmed = true },
            onClose = { closed = true },
        )
        assertTrue("CONFIRM on migration/confirm should apply restore", confirmed)
        assertFalse("CONFIRM on migration/confirm must not close", closed)
    }

    @Test
    fun pendingScanRestoreCannotConfirmOrStageSafetyWork() {
        val authority = seededAuthority()
        val state = MigrationUiState.RestoreReady(
            samplePreparedRestore(sampleRestorePlan(pendingLibraryScan = true)),
        )
        var confirmed = false
        var closed = false

        authority.requestFocus(confirmKey)
        dispatchMigration(
            authority = authority,
            state = state,
            action = ControllerAction.CONFIRM,
            onConfirm = { confirmed = true },
            onClose = { closed = true },
        )

        assertFalse("pending-scan plan must not enter apply", confirmed)
        assertTrue("pending-scan confirmation should close the zero-write plan", closed)
    }

    @Test
    fun readingAndApplyingRemainNonDismissibleForRestoreFlow() {
        val authority = seededAuthority()
        listOf(
            MigrationUiState.Reading,
            MigrationUiState.Applying,
            MigrationUiState.RestoreApplying,
        ).forEach { state ->
            var closed = false
            dispatchMigration(
                authority = authority,
                state = state,
                action = ControllerAction.CANCEL,
                onConfirm = {},
                onClose = { closed = true },
            )
            assertFalse("$state must not dismiss mid-flight", closed)
        }
    }

    @Test
    fun restoreReadyBodyPostureIncludesMandatoryNotRestoredDisclosures() {
        val plan = sampleRestorePlan()
        assertEquals(
            RecoveryRestoreExcludedCategory.requiredDisclosures,
            plan.notRestored,
        )
        assertTrue(plan.notRestored.isNotEmpty())
    }

    @Test
    fun restoreDoneResultCarriesSourceRelinkHintsWithoutArchivedAuthority() {
        val result = sampleRestoreResult(
            sourceRootHints = listOf(
                RecoverySourceRootHint(friendlyLabel = "ROMs", isPrimary = true),
            ),
        )
        assertEquals(1, result.sourceRootHints.size)
        assertEquals("ROMs", result.sourceRootHints.single().friendlyLabel)
    }

    private fun seededAuthority(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.pushScope(MigrationScope)
        authority.register(
            confirmKey,
            MigrationScope,
            FocusBounds(0f, 0f, 1f, 1f),
        )
        authority.register(
            closeKey,
            MigrationScope,
            FocusBounds(1f, 0f, 2f, 1f),
        )
        return authority
    }

    private fun sampleRestorePlan(
        pendingLibraryScan: Boolean = false,
    ): RecoveryRestorePlan = RecoveryRestorePlan(
        favorites = RestoreStorePlan(decisions = emptyList()),
        hidden = RestoreStorePlan(decisions = emptyList()),
        overrides = RestoreStorePlan(decisions = emptyList()),
        shelf = RestoreStorePlan(decisions = emptyList()),
        sourceRootHints = emptyList(),
        notRestored = RecoveryRestoreExcludedCategory.requiredDisclosures,
        pendingLibraryScan = pendingLibraryScan,
        archiveRestoreRecordCount = 0,
    )

    private fun samplePreparedRestore(
        plan: RecoveryRestorePlan = sampleRestorePlan(),
    ): PreparedRecoveryRestore = PreparedRecoveryRestore(
        plan = plan,
        runId = "00000000-0000-0000-0000-000000000001",
        importAuthorizationId = "00000000-0000-0000-0000-000000000002",
        applyAuthorizationId = "00000000-0000-0000-0000-000000000003",
        manifestSha256 = "manifest",
    )

    private fun sampleRestoreResult(
        sourceRootHints: List<RecoverySourceRootHint> = emptyList(),
    ): RecoveryRestoreResult = RecoveryRestoreResult(
        favoritesRestored = 1,
        favoritesSkipped = 0,
        favoritesUnmatched = 0,
        hiddenRestored = 0,
        hiddenSkipped = 0,
        hiddenUnmatched = 0,
        overridesRestored = 0,
        overridesSkipped = 0,
        overridesUnmatched = 0,
        shelfRestored = 0,
        shelfSkipped = 0,
        shelfUnmatched = 0,
        sourceRootHints = sourceRootHints,
        notRestored = RecoveryRestoreExcludedCategory.requiredDisclosures,
        pendingLibraryScan = false,
    )
}
