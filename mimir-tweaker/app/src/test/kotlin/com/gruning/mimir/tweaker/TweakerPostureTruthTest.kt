package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweakerPostureTruthTest {
    @Test
    fun `variant flags match the declared posture`() {
        assertTrue(TweakerPostureTruth.isKnownPosture(TweakerPostureTruth.posture))
        when (TweakerPostureTruth.posture) {
            TweakerPostureTruth.SAFE -> {
                assertFalse(TweakerPostureTruth.hardwareWritesCompiled)
                assertFalse(TweakerPostureTruth.diagnosticsCompiled)
                assertFalse(TweakerPostureTruth.signedManifestRequired)
            }
            TweakerPostureTruth.LAB -> {
                assertTrue(TweakerPostureTruth.hardwareWritesCompiled)
                assertTrue(TweakerPostureTruth.diagnosticsCompiled)
                assertFalse(TweakerPostureTruth.signedManifestRequired)
            }
            TweakerPostureTruth.PRODUCTION -> {
                assertTrue(TweakerPostureTruth.hardwareWritesCompiled)
                assertFalse(TweakerPostureTruth.diagnosticsCompiled)
                assertTrue(TweakerPostureTruth.signedManifestRequired)
            }
        }
    }

    @Test
    fun `requiring a signed manifest is not evidence that one exists`() {
        assertEquals(
            TweakerPostureTruth.QUALIFICATION_NONE,
            TweakerPostureTruth.qualificationSource(hasLocalCalibrationReport = false),
        )
        assertEquals(
            TweakerPostureTruth.QUALIFICATION_LOCAL_REPORT,
            TweakerPostureTruth.qualificationSource(hasLocalCalibrationReport = true),
        )
    }

    @Test
    fun `mutation remains unavailable until a signed qualified plan is loaded`() {
        assertFalse(
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.SUPPORTED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_NONE,
                qualifiedProfileIds = emptySet(),
            ),
        )
        assertFalse(
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.SUPPORTED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST,
                qualifiedProfileIds = emptySet(),
            ),
        )
        assertTrue(
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.SUPPORTED,
                pServerAvailable = true,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST,
                qualifiedProfileIds = setOf("quiet"),
            ),
        )
        assertFalse(
            TweakerPostureTruth.mutationAvailable(
                hardwareWritesCompiled = true,
                dryRun = false,
                capabilityStatus = CapabilityStatus.UNSUPPORTED,
                pServerAvailable = false,
                qualificationSource = TweakerPostureTruth.QUALIFICATION_SIGNED_MANIFEST,
                qualifiedProfileIds = setOf("quiet"),
            ),
        )
    }
}
