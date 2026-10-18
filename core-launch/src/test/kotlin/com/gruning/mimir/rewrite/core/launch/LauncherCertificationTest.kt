package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherCertificationTest {
    @Test
    fun installedDispatchTargetDefaultsToNotCertified() {
        val platform = PlatformId.PS1
        val status = status(platform, standalone(platform))

        val document = LauncherCertificationProjector.project(
            platformIds = listOf(platform),
            statusFor = { status },
            updatedAtMillis = 100L,
        )
        val row = document.platforms.getValue(platform.value)

        assertEquals(LauncherCertificationByPlatform.SCHEMA_VERSION, document.schemaVersion)
        assertEquals(LauncherCertificationTruthLabel.NOT_CERTIFIED, row.truthLabel)
        assertFalse(row.certified)
        assertTrue(row.installed)
        assertTrue(row.launchDispatchReady)
        assertTrue(row.launchReady)
        assertTrue(row.gameLaunchReady)
    }

    @Test
    fun dispatchOnlyOrMismatchedEvidenceCannotPromoteCertification() {
        val platform = PlatformId.PS1
        val emulator = standalone(platform)
        val mismatched = LauncherCertificationEvidence(
            platformId = platform,
            emulatorId = EmulatorId("different-target"),
            route = route(platform, emulator),
            truthLabel = LauncherCertificationTruthLabel.VISUAL_CERTIFIED,
            proofStatus = "VISUAL_CERTIFIED",
            capturedAtMillis = 200L,
        )

        val row = LauncherCertificationProjector.project(
            platformIds = listOf(platform),
            statusFor = { status(platform, emulator) },
            evidence = listOf(mismatched),
            updatedAtMillis = 300L,
        ).platforms.getValue(platform.value)

        assertEquals(LauncherCertificationTruthLabel.NOT_CERTIFIED, row.truthLabel)
        assertFalse(row.certified)
    }

    @Test
    fun exactVisualEvidenceIsTheOnlyCertifiedPromotion() {
        val platform = PlatformId.PS1
        val emulator = standalone(platform)
        val evidence = LauncherCertificationEvidence(
            platformId = platform,
            emulatorId = emulator.id,
            route = route(platform, emulator),
            truthLabel = LauncherCertificationTruthLabel.VISUAL_CERTIFIED,
            proofStatus = "VISUAL_CERTIFIED",
            capturedAtMillis = 200L,
        )

        val row = LauncherCertificationProjector.project(
            platformIds = listOf(platform),
            statusFor = { status(platform, emulator) },
            evidence = listOf(evidence),
            updatedAtMillis = 300L,
        ).platforms.getValue(platform.value)

        assertTrue(row.certified)
        assertEquals("VISUAL_CERTIFIED", row.visualCertification)
    }

    @Test
    fun staleDispatchEnvelopeCannotPromoteCertification() {
        val platform = PlatformId.PS1
        val emulator = standalone(platform)
        val staleRoute = route(platform, emulator).copy(targetActivity = "old.removed.Activity")
        val evidence = LauncherCertificationEvidence(
            platformId = platform,
            emulatorId = emulator.id,
            route = staleRoute,
            truthLabel = LauncherCertificationTruthLabel.VISUAL_CERTIFIED,
            proofStatus = "VISUAL_CERTIFIED",
            capturedAtMillis = 200L,
        )

        val row = LauncherCertificationProjector.project(
            platformIds = listOf(platform),
            statusFor = { status(platform, emulator) },
            evidence = listOf(evidence),
            updatedAtMillis = 300L,
        ).platforms.getValue(platform.value)

        assertEquals(LauncherCertificationTruthLabel.NOT_CERTIFIED, row.truthLabel)
        assertFalse(row.certified)
    }

    @Test
    fun retroArchAndOpenOnlyPosturesStayExplicitlyNonCertified() {
        val gba = PlatformId.GBA
        val retroArch = EmulatorSeedRegistry.candidatesFor(gba) { true }
            .first { it.retroArchCore == "mgba" }
        val ps3 = PlatformId("ps3")
        val rpcsx = EmulatorSeedRegistry.candidatesFor(ps3) { true }
            .first { it.packageName == "net.rpcsx" }
        val statuses = mapOf(gba to status(gba, retroArch), ps3 to status(ps3, rpcsx))

        val rows = LauncherCertificationProjector.project(
            platformIds = statuses.keys,
            statusFor = statuses::getValue,
            updatedAtMillis = 400L,
        ).platforms

        val retroArchRow = rows.getValue(gba.value)
        assertTrue(retroArchRow.requiresManualCoreProof)
        assertTrue(retroArchRow.testLaunchReady)
        assertFalse(retroArchRow.gameLaunchReady)
        assertFalse(retroArchRow.certified)

        val openOnlyRow = rows.getValue(ps3.value)
        assertTrue(openOnlyRow.openOnly)
        assertEquals(LauncherCertificationTruthLabel.OPEN_EMULATOR_ONLY, openOnlyRow.truthLabel)
        assertFalse(openOnlyRow.gameLaunchReady)
        assertFalse(openOnlyRow.certified)
    }

    private fun standalone(platform: PlatformId) = EmulatorProfile(
        id = EmulatorId("com.github.stenzek.duckstation"),
        packageName = "com.github.stenzek.duckstation",
        displayName = "DuckStation",
        installed = true,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        locatorForm = LaunchLocatorForm.FILE_PATH,
        supportedPlatforms = setOf(platform),
    )

    private fun status(platform: PlatformId, emulator: EmulatorProfile) = PlatformEmulatorStatus(
        platformId = platform,
        candidates = listOf(emulator),
        defaultEmulatorId = null,
        resolved = emulator,
    )

    private fun route(platform: PlatformId, emulator: EmulatorProfile) = LauncherCertificationRouteIdentity(
        emulatorPackage = emulator.packageName,
        targetActivity = requireNotNull(ShippingLaunchProfiles.targetActivityFor(platform, emulator)),
        locatorForm = emulator.locatorForm,
        retroArchCore = emulator.retroArchCore.orEmpty(),
    )
}
