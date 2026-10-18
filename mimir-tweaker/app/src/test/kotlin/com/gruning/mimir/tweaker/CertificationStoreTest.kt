package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificationStoreTest {
    @Test
    fun `local certified report never unlocks a matching plan`() {
        val store = KeyedCalibrationReportStore()
        val capabilities = testCapabilities()
        val planner = TuningPlanner()
        val plan = planner.plan("balanced", "org.ppsspp.ppsspp", capabilities)!!
        val fingerprint = HardwareFingerprint.create(capabilities)
        store.save(legacyCertifiedReport("balanced", fingerprint, plan))
        val certification = CertificationStore(store)
        assertFalse(certification.isPlanCertified(plan, capabilities))
        assertTrue(certification.hasLocalCalibrationReport())
    }

    @Test
    fun `observed report does not certify plan`() {
        val store = KeyedCalibrationReportStore()
        val capabilities = testCapabilities()
        val planner = TuningPlanner()
        val plan = planner.plan("balanced", "org.ppsspp.ppsspp", capabilities)!!
        store.save(
            CalibrationReport(
                disposition = CalibrationDisposition.OBSERVED,
                profileId = "balanced",
                packageName = "org.ppsspp.ppsspp",
                hardwareFingerprint = HardwareFingerprint.create(capabilities),
                startedAtEpochMs = 1L,
                requestedDurationSeconds = 300,
                observedDurationSeconds = 300,
                sampleCount = 301,
                maxCpuTempC = 70,
                maxGpuTempC = 68,
                maxBatteryTempDeciC = 390,
                averageBatteryPowerMw = 3_000,
                maxThermalStatus = 0,
                chargingSampleCount = 0,
                attestedSampleCount = 301,
                frameSampleCount = 301,
                frameAttestedSampleCount = 301,
                medianFrameFpsMilli = 60_000,
                maximumP95FrameTimeUs = 17_000,
                averageSlow20FpsPercent = 0,
                averageSlow30FpsPercent = 0,
                frameLayerNames = emptyList(),
                candidateCpuMaxKhz = plan.cpuMaxKhz,
                candidateGpuMaxPowerLevel = plan.gpuMaxPowerLevel,
                candidateFanMode = plan.fanMode,
                evidence = CalibrationSafetyEvidence(),
                blockers = listOf("writes"),
                samples = emptyList(),
            ),
        )
        assertFalse(CertificationStore(store).isPlanCertified(plan, capabilities))
    }

    @Test
    fun `local certified injections never expose product profile ids`() {
        val store = KeyedCalibrationReportStore()
        val capabilities = testCapabilities()
        val fingerprint = HardwareFingerprint.create(capabilities)
        val planner = TuningPlanner()
        val quiet = planner.plan("quiet", "org.ppsspp.ppsspp", capabilities)!!
        val balanced = planner.plan("balanced", "org.ppsspp.ppsspp", capabilities)!!
        val performance = planner.plan("performance", "org.ppsspp.ppsspp", capabilities)!!
        store.save(legacyCertifiedReport("quiet", fingerprint, quiet))
        store.save(legacyCertifiedReport("balanced", fingerprint, balanced))
        store.save(legacyCertifiedReport("performance", fingerprint, performance))

        val certification = CertificationStore(store)
        assertEquals(emptySet<String>(), certification.certifiedProfileIds(fingerprint))
        assertFalse(certification.isProfileCertified(fingerprint, "quiet"))
        assertFalse(certification.isProfileCertified(fingerprint, "balanced"))
        assertFalse(certification.isProfileCertified(fingerprint, "performance"))
    }

    @Test
    fun `legacy latest certified report remains non-authoritative`() {
        val store = KeyedCalibrationReportStore()
        val capabilities = testCapabilities()
        val fingerprint = HardwareFingerprint.create(capabilities)
        val planner = TuningPlanner()
        val plan = planner.plan("performance", "org.ppsspp.ppsspp", capabilities)!!
        store.seedLatestOnly(legacyCertifiedReport("performance", fingerprint, plan))

        val certification = CertificationStore(store)
        assertFalse(certification.isProfileCertified(fingerprint, "performance"))
        assertEquals(emptySet<String>(), certification.certifiedProfileIds(fingerprint))
        assertTrue(certification.hasLocalCalibrationReport())
    }

    private fun legacyCertifiedReport(
        profileId: String,
        fingerprint: String,
        plan: TuningPlan,
    ) = CalibrationReport(
        disposition = CalibrationDisposition.CERTIFIED,
        profileId = profileId,
        packageName = "org.ppsspp.ppsspp",
        hardwareFingerprint = fingerprint,
        startedAtEpochMs = 1L,
        requestedDurationSeconds = 300,
        observedDurationSeconds = 300,
        sampleCount = 301,
        maxCpuTempC = 70,
        maxGpuTempC = 68,
        maxBatteryTempDeciC = 390,
        averageBatteryPowerMw = 3_000,
        maxThermalStatus = 0,
        chargingSampleCount = 0,
        attestedSampleCount = 301,
        frameSampleCount = 301,
        frameAttestedSampleCount = 301,
        medianFrameFpsMilli = 60_000,
        maximumP95FrameTimeUs = 17_000,
        averageSlow20FpsPercent = 0,
        averageSlow30FpsPercent = 0,
        frameLayerNames = emptyList(),
        candidateCpuMaxKhz = plan.cpuMaxKhz,
        candidateGpuMaxPowerLevel = plan.gpuMaxPowerLevel,
        candidateFanMode = plan.fanMode,
        evidence = CalibrationSafetyEvidence(
            workloadAttested = true,
            frameTelemetryAttested = true,
            writesExercised = true,
            rollbackAttempts = 3,
            rollbackSuccesses = 3,
            rebootRestoreVerified = true,
        ),
        blockers = emptyList(),
        samples = emptyList(),
    )

    private fun testCapabilities() = HardwareCapabilities(
        status = CapabilityStatus.CALIBRATION_REQUIRED,
        manufacturer = "Moorechip",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint = "rp5/test",
        deviceProfileId = "rp5",
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.GPU, TuningAxis.FAN),
        pServerAvailable = true,
        cpuPolicies = listOf(
            CpuPolicy(0, "/policy0", listOf(0), listOf(300_000, 1_804_800), 300_000, 1_804_800, 1_804_800),
            CpuPolicy(4, "/policy4", listOf(4), listOf(710_400, 2_419_200), 710_400, 2_419_200, 2_419_200),
            CpuPolicy(7, "/policy7", listOf(7), listOf(844_800, 2_841_600), 844_800, 2_841_600, 2_841_600),
        ),
        gpuFrequenciesKhz = listOf(305_000, 905_000),
        gpuNodeAvailable = true,
        fanNodeAvailable = true,
        refreshRatesHz = listOf(60),
        notes = emptyList(),
    )

    private class KeyedCalibrationReportStore : CalibrationReportStore {
        private var latest: CalibrationReport? = null
        private val keyed = mutableMapOf<String, CalibrationReport>()

        override fun load(): CalibrationReport? = latest

        override fun save(report: CalibrationReport): Boolean {
            latest = report
            if (report.disposition == CalibrationDisposition.CERTIFIED) {
                keyed[key(report.hardwareFingerprint, report.profileId)] = report
            }
            return true
        }

        override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? =
            keyed[key(hardwareFingerprint, profileId)]

        override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> =
            CertificationStore.TUNABLE_PROFILE_IDS.filterTo(mutableSetOf()) { profileId ->
                loadCertified(hardwareFingerprint, profileId)?.disposition == CalibrationDisposition.CERTIFIED
            }

        fun seedLatestOnly(report: CalibrationReport) {
            latest = report
        }

        private fun key(fingerprint: String, profileId: String) = "$fingerprint::$profileId"
    }
}
