package com.gruning.mimir.rewrite.core.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerPerGameLaunchDecisionTest {
    @Test
    fun `inherit is mutation-free only from Stock or exact active system truth`() {
        listOf(
            stockSession(),
            activeSystemSession("balanced"),
        ).forEach { session ->
            assertEquals(
                PerGameTweakerDecision.NoMutation,
                decidePerGameTweakerLaunch(
                    selection = PerGameTweakerSelection.INHERIT,
                    workloadPackageName = null,
                    capabilities = null,
                    session = session,
                    tweakerReachable = false,
                ),
            )
        }
    }

    @Test
    fun `inherit reports busy and uncertain ownership without creating a mutation`() {
        assertRejected(
            PerGameTweakerDecision.Rejected.Reason.SESSION_BUSY,
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.INHERIT,
                workloadPackageName = null,
                capabilities = null,
                session = TweakerV4SessionAssessment.Blocked(
                    activeTruth(TweakerSessionScope.LAUNCH, "quiet"),
                ),
            ),
        )
        listOf<TweakerV4SessionAssessment?>(
            null,
            TweakerV4SessionAssessment.Uncertain("missing"),
        ).forEach { session ->
            assertRejected(
                PerGameTweakerDecision.Rejected.Reason.SESSION_UNCERTAIN,
                decidePerGameTweakerLaunch(
                    selection = PerGameTweakerSelection.INHERIT,
                    workloadPackageName = null,
                    capabilities = null,
                    session = session,
                ),
            )
        }
    }

    @Test
    fun `each certified explicit profile schedules only from authoritative Stock`() {
        val expected = mapOf(
            PerGameTweakerSelection.POWER_SAVING to "quiet",
            PerGameTweakerSelection.BALANCED to "balanced",
            PerGameTweakerSelection.MAX to "performance",
        )

        expected.forEach { (selection, profileId) ->
            assertEquals(
                PerGameTweakerDecision.ScheduleV4(
                    profileId = profileId,
                    workloadPackageName = PACKAGE_NAME,
                ),
                decidePerGameTweakerLaunch(
                    selection = selection,
                    workloadPackageName = "  $PACKAGE_NAME  ",
                    capabilities = productionCapabilities(),
                    session = stockSession(),
                ),
            )
        }
    }

    @Test
    fun `same active system profile is an exact no-op`() {
        assertEquals(
            PerGameTweakerDecision.NoMutation,
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.BALANCED,
                workloadPackageName = PACKAGE_NAME,
                capabilities = productionCapabilities(),
                session = activeSystemSession("balanced"),
            ),
        )
    }

    @Test
    fun `different active system profile wins and blocks per-game mutation`() {
        assertEquals(
            PerGameTweakerDecision.BlockedBySystemProfile(
                requestedProfileId = "performance",
                activeSystemProfileId = "quiet",
            ),
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.MAX,
                workloadPackageName = PACKAGE_NAME,
                capabilities = productionCapabilities(),
                session = activeSystemSession("quiet"),
            ),
        )
    }

    @Test
    fun `launch-scoped or unsettled mutation is busy and never replaced`() {
        val activeLaunch = activeTruth(scope = TweakerSessionScope.LAUNCH, profileId = "quiet")
        val applying = activeTruth(
            scope = TweakerSessionScope.LAUNCH,
            profileId = null,
            state = TweakerV4SessionState.APPLYING,
        )

        listOf(activeLaunch, applying).forEach { truth ->
            assertRejected(
                PerGameTweakerDecision.Rejected.Reason.SESSION_BUSY,
                decidePerGameTweakerLaunch(
                    selection = PerGameTweakerSelection.MAX,
                    workloadPackageName = PACKAGE_NAME,
                    capabilities = productionCapabilities(),
                    session = TweakerV4SessionAssessment.Blocked(truth),
                ),
            )
        }
    }

    @Test
    fun `missing or malformed session truth fails closed`() {
        listOf<TweakerV4SessionAssessment?>(
            null,
            TweakerV4SessionAssessment.Uncertain("malformed"),
        ).forEach { session ->
            assertRejected(
                PerGameTweakerDecision.Rejected.Reason.SESSION_UNCERTAIN,
                decidePerGameTweakerLaunch(
                    selection = PerGameTweakerSelection.MAX,
                    workloadPackageName = PACKAGE_NAME,
                    capabilities = productionCapabilities(),
                    session = session,
                ),
            )
        }
    }

    @Test
    fun `invalid package unresolved helper and uncertified profile each fail closed`() {
        assertRejected(
            PerGameTweakerDecision.Rejected.Reason.PACKAGE_UNRESOLVED,
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.MAX,
                workloadPackageName = "not-a-package",
                capabilities = productionCapabilities(),
                session = stockSession(),
            ),
        )
        assertRejected(
            PerGameTweakerDecision.Rejected.Reason.TWEAKER_UNAVAILABLE,
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.MAX,
                workloadPackageName = PACKAGE_NAME,
                capabilities = productionCapabilities(),
                session = stockSession(),
                tweakerReachable = false,
            ),
        )
        assertRejected(
            PerGameTweakerDecision.Rejected.Reason.PROFILE_NOT_CERTIFIED,
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.MAX,
                workloadPackageName = PACKAGE_NAME,
                capabilities = productionCapabilities(certified = setOf("quiet", "balanced")),
                session = stockSession(),
            ),
        )
    }

    @Test
    fun `non-production capabilities cannot authorize a per-game profile`() {
        val localOnly = productionCapabilities().copy(
            posture = TweakerPosture.LAB,
            qualificationSource = TweakerQualificationSource.LOCAL_REPORT,
        )

        assertRejected(
            PerGameTweakerDecision.Rejected.Reason.PROFILE_NOT_CERTIFIED,
            decidePerGameTweakerLaunch(
                selection = PerGameTweakerSelection.MAX,
                workloadPackageName = PACKAGE_NAME,
                capabilities = localOnly,
                session = stockSession(),
            ),
        )
    }

    @Test
    fun `package validator matches v4 apply identity rules`() {
        assertTrue(isValidTweakerWorkloadPackageName("org.dolphinemu.dolphinemu"))
        assertEquals(false, isValidTweakerWorkloadPackageName("org"))
        assertEquals(false, isValidTweakerWorkloadPackageName("3ds.emulator"))
        assertEquals(false, isValidTweakerWorkloadPackageName("org.bad-package"))
    }

    private fun assertRejected(
        reason: PerGameTweakerDecision.Rejected.Reason,
        actual: PerGameTweakerDecision,
    ) {
        assertEquals(PerGameTweakerDecision.Rejected(reason), actual)
    }

    private fun productionCapabilities(
        certified: Set<String> = setOf("quiet", "balanced", "performance"),
    ): TweakerCapabilities = TweakerCapabilities(
        status = TweakerCapabilityStatus.SUPPORTED,
        deviceProfileId = "ayn-odin-3",
        hardwareFingerprint = "ayn/odin3/test",
        supportedAxes = setOf("cpu", "gpu"),
        certifiedProfileIds = certified,
        notes = emptyList(),
        posture = TweakerPosture.PRODUCTION,
        mutationAvailable = true,
        dryRun = false,
        usageAccessGranted = true,
        qualificationSource = TweakerQualificationSource.SIGNED_MANIFEST,
    )

    private fun stockSession(): TweakerV4SessionAssessment.Stock =
        TweakerV4SessionAssessment.Stock(
            TweakerV4SessionTruth(
                apiVersion = 5,
                state = TweakerV4SessionState.STOCK,
                mutationMayBeActive = false,
                requestId = null,
                generation = null,
                transactionId = null,
                requestDeadlineElapsedRealtimeMs = null,
                leaseDeadlineElapsedRealtimeMs = null,
                hardLeaseDeadlineElapsedRealtimeMs = null,
                leaseRenewable = false,
                scope = TweakerSessionScope.NONE,
            ),
        )

    private fun activeSystemSession(profileId: String): TweakerV4SessionAssessment.Blocked =
        TweakerV4SessionAssessment.Blocked(
            activeTruth(scope = TweakerSessionScope.SYSTEM, profileId = profileId),
        )

    private fun activeTruth(
        scope: TweakerSessionScope,
        profileId: String?,
        state: TweakerV4SessionState = TweakerV4SessionState.ACTIVE,
    ): TweakerV4SessionTruth = TweakerV4SessionTruth(
        apiVersion = 5,
        state = state,
        mutationMayBeActive = true,
        requestId = "owner-request",
        generation = 7L,
        transactionId = "tx-7",
        requestDeadlineElapsedRealtimeMs = 2_000L,
        leaseDeadlineElapsedRealtimeMs = 10_000L,
        hardLeaseDeadlineElapsedRealtimeMs = 10_000L,
        leaseRenewable = scope == TweakerSessionScope.LAUNCH,
        scope = scope,
        activeProfileId = profileId,
    )

    private companion object {
        const val PACKAGE_NAME = "org.dolphinemu.dolphinemu"
    }
}
