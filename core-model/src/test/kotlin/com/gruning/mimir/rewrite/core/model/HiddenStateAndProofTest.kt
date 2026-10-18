package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HiddenState restore-eligibility + LaunchProof locator-scoped failure lifetime. */
class HiddenStateAndProofTest {

    @Test
    fun emulatorProfileDefaultsToRecognitionOnly() {
        val profile = EmulatorProfile(
            id = EmulatorId("com.example.unproven"),
            packageName = "com.example.unproven",
            displayName = "Unproven",
        )

        assertEquals(EmulatorCapability.RECOGNIZE_ONLY, profile.capability)
    }

    @Test
    fun userDismissedGameIsRestorable() {
        val s = HiddenState(hidden = true, reason = HideReason.USER_DISMISSED, isPrimaryMember = true)
        assertTrue(s.eligibleForRestore)
    }

    @Test
    fun dedupLoserIsNeverRestorable() {
        // dedup-grouping-rulings: losers (duplicateGroupId && !isPrimaryMember) excluded from restore.
        val loser = HiddenState(
            hidden = true,
            reason = HideReason.DEDUP_NON_PRIMARY,
            duplicateGroupId = "g:chrono",
            isPrimaryMember = false,
        )
        assertFalse(loser.eligibleForRestore)
    }

    @Test
    fun visibleGameIsNotRestorable() {
        assertFalse(HiddenState.VISIBLE.eligibleForRestore)
        assertFalse(HiddenState.VISIBLE.hidden)
    }

    @Test
    fun launchProofFailureIsScopedToItsLocator() {
        // cert-disclaimer-gamelist-placed: a failure marker's lifetime is tied to the launch locator;
        // a re-placement / path change (new locator) drops the stale failure.
        val proof = LaunchProof(
            gameId = GameId("g:pkmn-red"),
            platformId = PlatformId.GBA,
            emulatorId = EmulatorId("com.retroarch.aarch64#mgba"),
            locator = "content://old/uri",
            outcome = LaunchOutcome.Failed(LaunchDispatchFailure.ROM_PATH_MISSING),
            timestampMillis = 1_700_000_000_000L,
        )
        assertFalse(proof.succeeded)
        assertTrue(proof.failureAppliesTo("content://old/uri"))
        // Moved / re-placed → new locator → stale failure no longer applies.
        assertFalse(proof.failureAppliesTo("content://new/uri"))
    }

    @Test
    fun intentSentProofSucceedsAndHasNoStandingFailure() {
        val proof = LaunchProof(
            gameId = GameId("g:mario64"),
            platformId = PlatformId.N64,
            emulatorId = EmulatorId("org.mupen64plusae.v3.fzurita"),
            locator = "/storage/SD/ROMs/n64/mario64.z64",
            outcome = LaunchOutcome.IntentSent,
            timestampMillis = 1_700_000_000_000L,
        )
        assertTrue(proof.succeeded)
        assertFalse(proof.failureAppliesTo("/storage/SD/ROMs/n64/mario64.z64"))
    }

    @Test
    fun emulatorCanServeIsConstrainedByPlatformSet() {
        val duck = EmulatorProfile(
            id = EmulatorId("com.retroarch.aarch64#swanstation"),
            packageName = "com.retroarch.aarch64",
            displayName = "DuckStation",
            retroArchCore = "swanstation",
            supportedPlatforms = setOf(PlatformId.PS1),
        )
        assertTrue(duck.canServe(PlatformId.PS1))
        assertFalse(duck.canServe(PlatformId.N64))
        assertTrue(duck.isRetroArchCore)

        // Empty support set = unconstrained (serves anything).
        val agnostic = duck.copy(supportedPlatforms = emptySet())
        assertTrue(agnostic.canServe(PlatformId.N64))
    }
}
