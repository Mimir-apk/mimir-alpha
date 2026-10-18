package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.RetroArchCoreListing
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertifiedPrivilegeGatewayTest {
    @Test
    fun `vendor emulator renderer route stages one lease and reuses its live identity`() {
        var elapsed = 1_000L
        val target = EmulatorRenderFpsTargetIdentity(
            packageName = "org.dolphinemu.dolphinemu",
            uid = 10_123L,
            pid = 4_321,
            startTicks = 99_001L,
        )
        val transport = FakeVendorTransport().apply {
            emulatorTarget = target
            emulatorRecordCount = 60L
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val root = Files.createTempDirectory("mimir-render-fps-test").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream(byteArrayOf(0x7f))
        }
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            emulatorHelperStager = stager,
        )

        try {
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)
            elapsed += EMULATOR_RENDER_FPS_SESSION_LEASE_MS + 1L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(2, transport.emulatorStarts)
        } finally {
            gateway.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `renderer warmup keeps request through missing first record and rotates terminal loss`() {
        var elapsed = 1_000L
        val target = EmulatorRenderFpsTargetIdentity(
            packageName = "org.dolphinemu.dolphinemu",
            uid = 10_123L,
            pid = 4_321,
            startTicks = 99_001L,
        )
        val transport = FakeVendorTransport().apply {
            emulatorTarget = target
            emulatorRecordMissing = true
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val root = Files.createTempDirectory("mimir-render-fps-warmup").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream(byteArrayOf(0x7f))
        }
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            emulatorHelperStager = stager,
        )

        try {
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            val files = requireNotNull(stager.stage())
            assertTrue(files.request.isFile)
            assertEquals(1, transport.emulatorStarts)

            transport.emulatorRecordMissing = false
            elapsed += 100L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)

            transport.emulatorRecordStatus = "lost"
            transport.emulatorRecordCount = 0L
            elapsed += 100L
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertFalse(files.request.exists())

            transport.emulatorRecordStatus = "ok"
            transport.emulatorRecordCount = 60L
            elapsed += 100L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(2, transport.emulatorStarts)
        } finally {
            gateway.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `repeated missing records expire startup progress before a new generation`() {
        var elapsed = 1_000L
        val target = EmulatorRenderFpsTargetIdentity(
            packageName = "org.dolphinemu.dolphinemu",
            uid = 10_123L,
            pid = 4_321,
            startTicks = 99_001L,
        )
        val transport = FakeVendorTransport().apply {
            emulatorTarget = target
            emulatorRecordMissing = true
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val root = Files.createTempDirectory("mimir-render-fps-no-progress").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream(byteArrayOf(0x7f))
        }
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            emulatorHelperStager = stager,
        )

        try {
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            elapsed = 2_000L
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            elapsed = 4_501L
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(2, transport.emulatorStarts)
            assertTrue(requireNotNull(stager.stage()).request.isFile)

            elapsed = 8_002L
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(3, transport.emulatorStarts)
            transport.emulatorRecordMissing = false
            elapsed = 8_003L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(3, transport.emulatorStarts)
        } finally {
            gateway.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `foreign record identity fails closed and permits a later fresh generation`() {
        var elapsed = 1_000L
        val target = EmulatorRenderFpsTargetIdentity(
            packageName = "org.dolphinemu.dolphinemu",
            uid = 10_123L,
            pid = 4_321,
            startTicks = 99_001L,
        )
        val transport = FakeVendorTransport().apply { emulatorTarget = target }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val root = Files.createTempDirectory("mimir-render-fps-foreign").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream(byteArrayOf(0x7f))
        }
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            emulatorHelperStager = stager,
        )

        try {
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            transport.emulatorRecordPidOverride = target.pid + 1
            elapsed += 100L
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertFalse(requireNotNull(stager.stage()).request.isFile)

            transport.emulatorRecordPidOverride = null
            elapsed += 100L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(2, transport.emulatorStarts)
        } finally {
            gateway.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `unsupported native start is backed off and retried as a new generation`() {
        var elapsed = 1_000L
        val target = EmulatorRenderFpsTargetIdentity(
            packageName = "org.dolphinemu.dolphinemu",
            uid = 10_123L,
            pid = 4_321,
            startTicks = 99_001L,
        )
        val transport = FakeVendorTransport().apply {
            emulatorTarget = target
            emulatorRecordStatus = "unsupported"
            emulatorRecordCount = 0L
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val root = Files.createTempDirectory("mimir-render-fps-unsupported").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream(byteArrayOf(0x7f))
        }
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            emulatorHelperStager = stager,
        )

        try {
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)
            elapsed += 100L
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)

            elapsed += EMULATOR_RENDER_FPS_UNSUPPORTED_BACKOFF_MS + 1L
            transport.emulatorRecordStatus = "ok"
            transport.emulatorRecordCount = 60L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(2, transport.emulatorStarts)
        } finally {
            gateway.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `fresh zero-frame publication preserves collector until frames arrive`() {
        var elapsed = 1_000L
        val target = EmulatorRenderFpsTargetIdentity(
            packageName = "org.dolphinemu.dolphinemu",
            uid = 10_123L,
            pid = 4_321,
            startTicks = 99_001L,
        )
        val transport = FakeVendorTransport().apply {
            emulatorTarget = target
            emulatorRecordCount = 0L
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val root = Files.createTempDirectory("mimir-render-fps-zero-frame").toFile()
        val stager = EmulatorRenderFpsHelperStager(root) {
            ByteArrayInputStream(byteArrayOf(0x7f))
        }
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            emulatorHelperStager = stager,
        )

        try {
            assertNull(gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)
            assertTrue(requireNotNull(stager.stage()).request.isFile)
            elapsed += 100L
            transport.emulatorRecordCount = 60L
            assertEquals(60_000, gateway.readForegroundFrameRateMilli(target.packageName))
            assertEquals(1, transport.emulatorStarts)
        } finally {
            gateway.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `certified vendor shares one HUD snapshot between CPU and FPS reads`() {
        var elapsed = 1_000L
        val epoch = 1_700_000_000_000L
        val session = epoch - 1_000L
        val transport = FakeVendorTransport()
        transport.hudSnapshot = VendorHudRawSnapshot(
            procStatLine = "cpu 100 0 100 700 100 0 0 0",
            gameNativeSessionFileName = "metrics-$session.jsonl",
            gameNativeSessionFileSizeBytes = 2_048L,
            gameNativeTail = "{\"timestampMs\":${session + 500},\"fps\":60.0,\"totalFrameCount\":20}\n",
        )
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            nowEpochMs = { epoch },
        )

        assertNull(gateway.readDeviceUtilization()?.cpuBusyPercent)
        assertEquals(60_000, gateway.readForegroundFrameRateMilli(GAME_NATIVE_FRAME_RATE_PACKAGE))
        assertEquals(1, transport.hudReads)

        elapsed += 251L
        transport.hudSnapshot = transport.hudSnapshot?.copy(
            procStatLine = "cpu 110 0 100 710 100 0 0 0",
            gameNativeTail = "{\"timestampMs\":${session + 750},\"fps\":72.0,\"totalFrameCount\":20}\n",
        )
        assertEquals(50, gateway.readDeviceUtilization()?.cpuBusyPercent)
        assertEquals(72_000, gateway.readForegroundFrameRateMilli(GAME_NATIVE_FRAME_RATE_PACKAGE))
        assertEquals(2, transport.hudReads)
    }

    @Test
    fun `structural admission alone makes the vendor route READY with no consent record`() {
        //
        //
        // The empty consent store IS the assertion. This test previously asserted the opposite --
        // PERMISSION_REQUIRED until a `profileId|executableSha256|buildFingerprint` key was written
        // -- which meant any firmware update or swapped pservice silently demoted a working device
        // to the removed alternate authority. Nothing persisted may gate this route.
        val transport = FakeVendorTransport()
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(),
            evidenceReader = ::admittedEvidence,
        )

        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, gateway.posture().authority)
        assertTrue(gateway.posture().canWriteVendorSettings)
        // There is nothing left for a caller to request, and asking must not change the posture.
        assertFalse(gateway.requestAccess(PrivilegedUtilityStatus.PERMISSION_REQUIRED))
        assertFalse(gateway.requestAccess(PrivilegedUtilityStatus.SERVICE_STOPPED))
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
    }

    @Test
    fun `an admitted vendor route with a dead transport is CONNECTION_FAILED`() {
        val transport = FakeVendorTransport()
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(),
            evidenceReader = ::admittedEvidence,
        )
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)

        transport.alive = false

        val failed = gateway.posture()
        assertEquals(PrivilegedUtilityStatus.CONNECTION_FAILED, failed.status)
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, failed.authority)
        assertFalse(failed.canWriteVendorSettings)
    }

    @Test
    fun `a device that fails structural admission is Unsupported, never PERMISSION_REQUIRED`() {
        // Fail-closed is unchanged by the ruling: what was widened is what counts as *stale*, not
        // what counts as proof. A granted consent record must not rescue a failed probe either.
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = FakeVendorTransport(),
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = { admittedEvidence().copy(serviceAttestation = null) },
        )

        assertEquals(PrivilegedUtilityStatus.UNSUPPORTED_DEVICE, gateway.posture().status)
        assertFalse(gateway.writeVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT, "0.5"))
    }

    @Test
    fun `a changed pservice digest between posture reads stays READY without re-prompting`() {
        // This is the exact transition the retired consent key turned into a silent demotion: same
        // device, same firmware, new /system/bin/pservice. The gateway re-attests per call, so the
        // second read is decided on its own structural merits. The identity change is reported on
        // the "MimirPrivilege" log; `isReturnDefaultValues` makes android.util.Log inert under JUnit,
        // so this pins the posture and leaves the log line to device evidence.
        var digest = "f44773c8dbdc03904dc604d94c2a6837562d14c808eb6b52ff3b644b19817960"
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = FakeVendorTransport(),
            consent = FakeConsentStore(),
            evidenceReader = {
                admittedEvidence().copy(
                    serviceAttestation = rp5Attestation().copy(executableSha256 = digest),
                )
            },
        )

        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        val before = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                admittedEvidence().copy(
                    serviceAttestation = rp5Attestation().copy(executableSha256 = digest),
                ),
            ),
        )

        digest = "de".repeat(32)

        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        assertTrue(gateway.posture().canWriteVendorSettings)
        val after = requireNotNull(
            CertifiedVendorAdmissionPolicy.admit(
                admittedEvidence().copy(
                    serviceAttestation = rp5Attestation().copy(executableSha256 = digest),
                ),
            ),
        )
        // The diagnostic identity did change -- it is simply no longer an access decision.
        assertNotEquals(before.consentKey, after.consentKey)
    }

    @Test
    fun `vendor setting write snapshots verifies and rolls back a mismatch`() {
        val key = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val transport = FakeVendorTransport(values = mutableMapOf(key to "0.5"))
        val gateway = readyVendorGateway(transport)

        assertTrue(gateway.writeVendorSetting(key, "0.75"))
        assertEquals("0.75", transport.values[key])

        transport.mismatchNextWrite = true
        assertFalse(gateway.writeVendorSetting(key, "0.9"))
        assertEquals("0.75", transport.values[key])
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
    }

    @Test
    fun `settled vendor target is idempotent and performs no privileged write`() {
        val key = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val transport = FakeVendorTransport(values = mutableMapOf(key to "0.5"))
        val gateway = readyVendorGateway(transport)

        assertTrue(gateway.writeVendorSetting(key, "0.5"))
        assertEquals(0, transport.mutationWrites)
        assertEquals("0.5", transport.values[key])
    }

    @Test
    fun `uncertain vendor timeout demotes authority and never starts a second writer`() {
        val key = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val transport = FakeVendorTransport(values = mutableMapOf(key to "0.5"))
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = {
                admittedEvidence().copy(serviceAttestation = transport.attest())
            },
        )
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        transport.timeoutNextWrite = true

        assertFalse(gateway.writeVendorSetting(key, "0.75"))
        val failed = gateway.posture()
        assertEquals(PrivilegedUtilityStatus.CONNECTION_FAILED, failed.status)
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, failed.authority)
        assertEquals(0, transport.restoreWrites)
    }

    @Test
    fun `concurrent startup posture checks keep first attestation single writer`() {
        val transport = ConcurrencyPoisoningVendorTransport()
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = {
                admittedEvidence().copy(serviceAttestation = transport.attest())
            },
        )
        val callers = 8
        val start = CountDownLatch(1)
        val finished = CountDownLatch(callers)
        val statuses = ConcurrentLinkedQueue<PrivilegedUtilityStatus>()
        val workers = List(callers) { index ->
            thread(start = false, name = "vendor-posture-$index") {
                start.await()
                try {
                    statuses += gateway.posture().status
                } finally {
                    finished.countDown()
                }
            }
        }

        workers.forEach(Thread::start)
        start.countDown()

        assertTrue("concurrent posture checks did not finish", finished.await(5, TimeUnit.SECONDS))
        workers.forEach(Thread::join)
        assertEquals(callers, statuses.size)
        assertTrue(statuses.all { it == PrivilegedUtilityStatus.READY })
        assertEquals(
            PrivilegedLaunchTargetStop.REQUEST_ACCEPTED,
            gateway.forceStopLaunchTarget("com.retroarch.aarch64"),
        )
    }

    @Test
    fun `composite uses only admitted vendor capabilities`() {
        val vendor = FakeGateway(
            current = PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            ),
            writeResult = false,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertFalse(gateway.writeVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT, "0.5"))
        assertEquals(1, vendor.writeCalls)
        assertFalse(gateway.setSecondaryBrightness(2, 0.5f, 50))
        assertEquals(0, vendor.displayCalls)

        val posture = gateway.posture()
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, posture.authority)
        assertTrue(posture.canWriteVendorSettings)
        assertFalse(posture.canControlSecondaryBrightness)
    }

    @Test
    fun `vendor operations remain unavailable when admission is unavailable`() {
        val unavailableVendor = FakeGateway(PrivilegedUtilityPosture.Unsupported)
        val gateway = CertifiedPrivilegeGateway(unavailableVendor, unavailableVendor)
        assertNull(gateway.readVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT))
        assertFalse(gateway.writeVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT, "0.5"))
        assertEquals(PrivilegedUtilityAuthority.NONE, gateway.posture().authority)
    }

    @Test
    fun `vendor permission posture is preserved without a fallback authority`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            ),
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, gateway.posture().authority)
        assertEquals(PrivilegedUtilityStatus.PERMISSION_REQUIRED, gateway.posture().status)
        assertEquals(PrivilegedUtilityStatus.PERMISSION_REQUIRED, gateway.systemRecentsAccessStatus())
        assertFalse(gateway.requestAccess(PrivilegedUtilityStatus.PERMISSION_REQUIRED))
        assertEquals(1, vendor.accessRequests)
    }

    @Test
    fun `certified single-display authority has no supplemental access action`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            ),
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        val posture = gateway.posture()
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, posture.authority)
        assertNull(posture.supplementalAccessStatus)
        assertNull(posture.requestedAccessStatus)
        assertFalse(gateway.requestAccess(PrivilegedUtilityStatus.SERVICE_STOPPED))
        assertEquals(PrivilegedUtilityStatus.READY, gateway.systemRecentsAccessStatus())
        assertFalse(gateway.posture().canControlSecondaryBrightness)
    }

    @Test
    fun `vendor consent Recents request routes exact status only to vendor`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertTrue(gateway.requestSystemRecentsAccess(PrivilegedUtilityStatus.PERMISSION_REQUIRED))
        assertEquals(1, vendor.recentsRequestCalls)
        assertEquals(1, vendor.recentsActionCount)
        assertEquals(PrivilegedUtilityStatus.PERMISSION_REQUIRED, vendor.lastRecentsExpectedStatus)
    }

    @Test
    fun `M button built in consent stays unavailable when the vendor route is unavailable`() {
        val vendor = FakeGateway(PrivilegedUtilityPosture.Unsupported)
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertFalse(
            gateway.requestBuiltInSystemRecentsConsent(
                PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            ),
        )
        assertEquals(0, vendor.builtInRecentsConsentCalls)
    }

    @Test
    fun `M button built in consent routes only admitted vendor status`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertTrue(
            gateway.requestBuiltInSystemRecentsConsent(
                PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            ),
        )
        assertEquals(1, vendor.builtInRecentsConsentCalls)
    }

    @Test
    fun `stale Recents expected status starts no action`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertFalse(gateway.requestSystemRecentsAccess(PrivilegedUtilityStatus.SERVICE_STOPPED))
        assertEquals(1, vendor.recentsRequestCalls)
        assertEquals(0, vendor.recentsActionCount)
    }

    @Test
    fun `ready vendor Recents failure is terminal`() {
        val vendor = FakeGateway(
            current = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
            recentsOpenResult = false,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertFalse(gateway.openSystemRecents())
        assertEquals(1, vendor.recentsOpenCalls)
    }

    @Test
    fun `ready vendor owns frame and utilization snapshots`() {
        val vendor = FakeGateway(
            current = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
            frameRateResult = 59_500,
            utilizationResult = DeviceUtilizationSample(10L, 42, 37),
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertEquals(59_500, gateway.readForegroundFrameRateMilli("app.gamenative"))
        assertEquals(DeviceUtilizationSample(10L, 42, 37), gateway.readDeviceUtilization())
        assertEquals(1, vendor.frameRateCalls)
        assertEquals(1, vendor.utilizationCalls)
    }

    @Test
    fun `admitted vendor telemetry failure stays unavailable`() {
        val vendor = FakeGateway(
            current = PrivilegedUtilityPosture(PrivilegedUtilityStatus.READY),
            frameRateResult = null,
            utilizationResult = null,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertNull(gateway.readForegroundFrameRateMilli("app.gamenative"))
        assertNull(gateway.readDeviceUtilization())
        assertEquals(1, vendor.frameRateCalls)
        assertEquals(1, vendor.utilizationCalls)
    }

    @Test
    fun `composite can keep recovered vendor HUD while control posture remains failed`() {
        val vendor = FakeGateway(
            current = PrivilegedUtilityPosture(PrivilegedUtilityStatus.CONNECTION_FAILED),
            frameRateResult = 87_000,
            utilizationResult = DeviceUtilizationSample(10L, 44, null),
            hudReady = true,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertEquals(87_000, gateway.readForegroundFrameRateMilli(GAME_NATIVE_FRAME_RATE_PACKAGE))
        assertEquals(DeviceUtilizationSample(10L, 44, null), gateway.readDeviceUtilization())
        assertEquals(1, vendor.frameRateCalls)
        assertEquals(1, vendor.utilizationCalls)
    }

    @Test
    fun `HUD recovery replaces only the dead transport and reseeds CPU baseline`() {
        var elapsed = 1_000L
        val original = FakeVendorTransport()
        original.hudSnapshot = VendorHudRawSnapshot(
            procStatLine = "cpu 100 0 100 700 100 0 0 0",
            gameNativeSessionFileName = null,
            gameNativeSessionFileSizeBytes = null,
            gameNativeTail = null,
        )
        val replacement = FakeVendorTransport().apply {
            hudSnapshot = VendorHudRawSnapshot(
                procStatLine = "cpu 200 0 200 800 100 0 0 0",
                gameNativeSessionFileName = null,
                gameNativeSessionFileSizeBytes = null,
                gameNativeTail = null,
            )
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            hudTransportFactory = { replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertNull(gateway.readDeviceUtilization()?.cpuBusyPercent)
        elapsed += 251L
        original.hudSnapshot = original.hudSnapshot?.copy(
            procStatLine = "cpu 110 0 100 710 100 0 0 0",
        )
        assertEquals(50, gateway.readDeviceUtilization()?.cpuBusyPercent)

        original.alive = false
        original.hudBindingInvalidated = true
        assertNull(gateway.readDeviceUtilization()?.cpuBusyPercent)
        assertEquals(2, original.hudReads)
        assertEquals(1, replacement.hudReads)
        assertEquals(0, original.closeCalls)
        assertEquals(PrivilegedUtilityStatus.CONNECTION_FAILED, gateway.posture().status)
        assertFalse(gateway.writeVendorSetting(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT, "0.75"))
    }

    @Test
    fun `HUD recovery admits a replacement with no consent record and reuses it`() {
        // Was `HUD recovery latches a denied replacement without recreating it`. The latch it pinned
        // was consent *denial*, and the 2026-09-11 ruling removed consent from every admission in
        // this gateway -- it had to, because the only writer of that record was requestAccess on a
        // PERMISSION_REQUIRED posture, so leaving it would have made vendor telemetry unreachable on
        // every device forever while control posture read READY. The empty store now proves the
        // opposite of what it used to. Latching a candidate that fails *admission* is still pinned,
        // by `failed HUD attestation can recover only after that candidate gets a new binding`.
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val replacement = FakeVendorTransport()
        var factoryCalls = 0
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { factoryCalls++; replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertTrue(gateway.isHudTelemetryReady())
        assertTrue(gateway.isHudTelemetryReady())
        // One replacement, reused -- no factory churn, and a HUD swap never closes the control transport.
        assertEquals(1, factoryCalls)
        assertEquals(0, replacement.hudReads)
        assertEquals(0, original.closeCalls)
    }

    @Test
    fun `HUD recovery does not replace an alive binding after a failed read`() {
        val original = FakeVendorTransport().apply {
            hudSnapshot = null
        }
        val replacement = FakeVendorTransport()
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(
                mutableSetOf(requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence())).consentKey),
            ),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertTrue(gateway.isHudTelemetryReady())
        assertNull(gateway.readDeviceUtilization())
        assertEquals(0, replacement.hudReads)
    }

    @Test
    fun `HUD recovery does not replace a dead binding without invalidation proof`() {
        val original = FakeVendorTransport().apply { alive = false }
        val replacement = FakeVendorTransport()
        var factoryCalls = 0
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(
                mutableSetOf(requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence())).consentKey),
            ),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { factoryCalls++; replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertFalse(gateway.isHudTelemetryReady())
        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(0, factoryCalls)
        assertEquals(0, replacement.hudReads)
    }

    @Test
    fun `failed HUD attestation can recover only after that candidate gets a new binding`() {
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val failedCandidate = FakeVendorTransport()
        val validCandidate = FakeVendorTransport()
        var next = failedCandidate
        var replacementEvidence = admittedEvidence().copy(serviceAttestation = null)
        var factoryCalls = 0
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { factoryCalls++; next },
            replacementEvidenceReader = { _, _ -> replacementEvidence },
        )

        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(1, factoryCalls)
        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(1, factoryCalls)
        failedCandidate.hudBindingInvalidated = true
        next = validCandidate
        replacementEvidence = admittedEvidence()
        assertTrue(gateway.isHudTelemetryReady())
        assertEquals(2, factoryCalls)
        assertEquals(1, failedCandidate.closeCalls)
    }

    @Test
    fun `unbound HUD replacement is retained for bounded admission without factory churn`() {
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val unbound = FakeVendorTransport().apply {
            alive = false
            boundBinderForHud = false
        }
        var factoryCalls = 0
        var replacementEvidence = admittedEvidence().copy(serviceAttestation = null)
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { factoryCalls++; unbound },
            replacementEvidenceReader = { _, _ -> replacementEvidence },
        )

        assertFalse(gateway.isHudTelemetryReady())
        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(1, factoryCalls)
        assertEquals(0, unbound.closeCalls)
        unbound.alive = true
        replacementEvidence = admittedEvidence()
        assertTrue(gateway.isHudTelemetryReady())
        assertEquals(1, factoryCalls)
    }

    @Test
    fun `HUD-only recovery remains available after control mutation poison`() {
        val key = BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT
        val original = FakeVendorTransport(values = mutableMapOf(key to "0.5")).apply {
            hudSnapshot = VendorHudRawSnapshot(
                procStatLine = "cpu 100 0 100 700 100 0 0 0",
                gameNativeSessionFileName = null,
                gameNativeSessionFileSizeBytes = null,
                gameNativeTail = null,
            )
        }
        val replacement = FakeVendorTransport().apply {
            hudSnapshot = VendorHudRawSnapshot(
                procStatLine = "cpu 110 0 110 710 100 0 0 0",
                gameNativeSessionFileName = null,
                gameNativeSessionFileSizeBytes = null,
                gameNativeTail = null,
            )
        }
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        var factoryCalls = 0
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = {
                factoryCalls++
                original.hudBindingInvalidated = true
                replacement
            },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        original.timeoutNextWrite = true
        assertFalse(gateway.writeVendorSetting(key, "0.75"))
        assertEquals(PrivilegedUtilityStatus.CONNECTION_FAILED, gateway.posture().status)
        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(0, factoryCalls)

        original.hudBindingInvalidated = true
        assertTrue(gateway.isHudTelemetryReady())
        val recoveredSample = gateway.readDeviceUtilization()
        assertNotNull(recoveredSample)
        assertNull(recoveredSample?.cpuBusyPercent)
        assertFalse(gateway.writeVendorSetting(key, "0.75"))
        assertEquals(1, replacement.hudReads)
    }

    @Test
    fun `close prevents a pending dead-binding HUD recovery`() {
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val replacement = FakeVendorTransport()
        var factoryCalls = 0
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { factoryCalls++; replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        gateway.close()
        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(0, factoryCalls)
        assertEquals(1, original.closeCalls)
    }

    @Test
    fun `late replacement admission misses its absolute HUD deadline`() {
        var elapsed = 100L
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val replacement = FakeVendorTransport()
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            hudTransportFactory = { replacement },
            replacementEvidenceReader = { _, _ ->
                elapsed += 1_701L
                admittedEvidence()
            },
        )

        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(0, replacement.hudReads)
    }

    @Test
    fun `original HUD admission misses its deadline after evidence becomes available`() {
        var elapsed = 100L
        val original = FakeVendorTransport()
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = {
                elapsed += 1_701L
                admittedEvidence()
            },
            nowElapsedRealtimeMs = { elapsed },
        )

        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(0, original.hudReads)
    }

    @Test
    fun `HUD admission rejects a live candidate that crosses its deadline on final liveness`() {
        var elapsed = 100L
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val replacement = FakeVendorTransport()
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            nowElapsedRealtimeMs = { elapsed },
            hudTransportFactory = { replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertTrue(gateway.isHudTelemetryReady())
        replacement.onAliveCheck = { elapsed += 1_701L }
        assertFalse(gateway.isHudTelemetryReady())
        assertEquals(0, replacement.hudReads)
    }

    @Test
    fun `HUD read that observes transport invalidation cannot publish its old snapshot`() {
        val original = FakeVendorTransport().apply {
            hudSnapshot = VendorHudRawSnapshot(
                procStatLine = "cpu 100 0 100 700 100 0 0 0",
                gameNativeSessionFileName = null,
                gameNativeSessionFileSizeBytes = null,
                gameNativeTail = null,
            )
            invalidateDuringHudRead = true
        }
        val replacement = FakeVendorTransport()
        var factoryCalls = 0
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { factoryCalls++; replacement },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertNull(gateway.readDeviceUtilization())
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `HUD replacement closes the old HUD channel and final close closes every channel`() {
        var next = FakeVendorTransport()
        val original = FakeVendorTransport().apply {
            alive = false
            hudBindingInvalidated = true
        }
        val firstReplacement = next
        val secondReplacement = FakeVendorTransport()
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = original,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
            hudTransportFactory = { next },
            replacementEvidenceReader = { _, _ -> admittedEvidence() },
        )

        assertTrue(gateway.isHudTelemetryReady())
        firstReplacement.hudBindingInvalidated = true
        next = secondReplacement
        assertTrue(gateway.isHudTelemetryReady())
        assertEquals(1, firstReplacement.closeCalls)

        gateway.close()
        assertEquals(1, original.closeCalls)
        assertEquals(1, secondReplacement.closeCalls)
    }

    @Test
    fun `unavailable vendor does not open Recents`() {
        val vendor = FakeGateway(
            current = PrivilegedUtilityPosture.Unsupported,
            recentsOpenResult = true,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertFalse(gateway.openSystemRecents())
        assertEquals(0, vendor.recentsOpenCalls)
    }

    // -- The bottom screen's brightness uses the vendor route's display-manager call ----------------------

    private val thorNumbers = DisplayBrightnessTransactions(setBrightness = 35, getBrightness = 36)
    private val mirrorKey = BrightnessSettingsKeys.AYN_SECONDARY

    /** Display 4 is the Thor's bottom screen: at 0.65, with AYN's own level at 65, as run 11 read it. */
    private fun thorTransport() = FakeVendorTransport(values = mutableMapOf(mirrorKey to "65")).apply {
        panels[4] = 0.65f
    }

    private fun displayVendorGateway(
        transport: FakeVendorTransport,
        numbers: DisplayBrightnessTransactions? = thorNumbers,
        secondaryPanels: Set<Int> = setOf(4),
    ) = AndroidCertifiedVendorPrivilegeGateway(
        context = TestContext(),
        identity = retroidIdentity(),
        transport = transport,
        consent = FakeConsentStore(),
        evidenceReader = ::admittedEvidence,
        displayTransactionsReader = { numbers },
        isSecondaryPanel = { it in secondaryPanels },
    )

    @Test
    fun `the vendor route sets the bottom panel and the vendor level, and reads both back`() {
        val transport = thorTransport()
        val gateway = displayVendorGateway(transport)

        val posture = gateway.posture()
        assertEquals(PrivilegedUtilityStatus.READY, posture.status)
        assertTrue("the route claims the panel it can set", posture.canControlSecondaryBrightness)
        assertEquals(0.65f, gateway.readDisplayBrightness(4)!!, 0.0001f)

        assertTrue(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(0.4f, transport.panels.getValue(4), 0.0001f)
        assertEquals("40", transport.values[mirrorKey])
        assertEquals("one write to the panel, no put-back", listOf(4 to 0.4f), transport.displayWrites)
        assertEquals(0.4f, gateway.readDisplayBrightness(4)!!, 0.0001f)
        assertTrue(gateway.posture().canControlSecondaryBrightness)
    }

    @Test
    fun `a framework the reader does not trust leaves no display route, and the posture says so`() {
        val transport = thorTransport()
        val gateway = displayVendorGateway(transport, numbers = null)

        val posture = gateway.posture()
        assertEquals("everything else the route does is unaffected", PrivilegedUtilityStatus.READY, posture.status)
        assertTrue(posture.canWriteVendorSettings)
        assertFalse(posture.canControlSecondaryBrightness)
        assertNull(gateway.readDisplayBrightness(4))
        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertTrue("no display call is made without the framework's numbers", transport.displayReads.isEmpty())
        assertTrue(transport.displayWrites.isEmpty())
    }

    @Test
    fun `only a second screen this process vouches for is ever addressed`() {
        val transport = thorTransport().apply { panels[2] = 0.5f }
        val gateway = displayVendorGateway(transport, secondaryPanels = setOf(4))

        assertFalse("display 2 is not a presentation second screen", gateway.setSecondaryBrightness(2, 0.4f, 40))
        assertNull(gateway.readDisplayBrightness(2))
        assertFalse("the main screen never", gateway.setSecondaryBrightness(0, 0.4f, 40))
        assertFalse("a value outside the slider's contract never", gateway.setSecondaryBrightness(4, 0.01f, 1))
        assertFalse("nor a level that disagrees with it", gateway.setSecondaryBrightness(4, 0.4f, 60))
        assertTrue(transport.displayReads.isEmpty())
        assertTrue(transport.displayWrites.isEmpty())
        assertEquals("65", transport.values[mirrorKey])
    }

    @Test
    fun `a display manager refusal closes the route and leaves the panel unavailable`() {
        val transport = thorTransport().apply { displayReadAnswer = VendorDisplayRead.Refused }
        val gateway = displayVendorGateway(transport)
        assertTrue(gateway.posture().canControlSecondaryBrightness)

        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertTrue("nothing was written", transport.displayWrites.isEmpty())
        assertEquals("65", transport.values[mirrorKey])
        val after = gateway.posture()
        assertEquals("a refusal is not a broken route: READY, not poisoned", PrivilegedUtilityStatus.READY, after.status)
        assertFalse("the panel has no remaining route", after.canControlSecondaryBrightness)

        val readsSoFar = transport.displayReads.size
        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertNull(gateway.readDisplayBrightness(4))
        assertEquals("and the refusal is not retried on every slider step", readsSoFar, transport.displayReads.size)
    }

    @Test
    fun `a read the channel loses moves nothing and keeps the route`() {
        // An empty reply reads as Failed (VendorServiceCallReply): nothing came back, so nothing is known.
        val transport = thorTransport().apply { displayReadAnswer = VendorDisplayRead.Failed }
        val gateway = displayVendorGateway(transport)

        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertNull(gateway.readDisplayBrightness(4))
        assertTrue("nothing is set without a panel reading to put back", transport.displayWrites.isEmpty())
        assertEquals("65", transport.values[mirrorKey])
        val after = gateway.posture()
        assertEquals(PrivilegedUtilityStatus.READY, after.status)
        assertTrue("one lost reply is not a refusal: the panel stays the vendor route's", after.canControlSecondaryBrightness)

        transport.displayReadAnswer = null
        assertTrue("and the next step goes through", gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(0.4f, transport.panels.getValue(4), 0.0001f)
    }

    @Test
    fun `a refused write that moved nothing is not undone, and closes the route`() {
        val transport = thorTransport().apply { displayWriteRefused = true }
        val gateway = displayVendorGateway(transport)

        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(0.65f, transport.panels.getValue(4), 0.0001f)
        assertEquals("one attempt and no put-back, because nothing moved", 1, transport.displayWrites.size)
        assertEquals("the vendor level is only written after the panel holds", "65", transport.values[mirrorKey])
        assertEquals(0, transport.mutationWrites)
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        assertFalse(gateway.posture().canControlSecondaryBrightness)
    }

    @Test
    fun `a panel that settles somewhere else is put back and read back`() {
        val transport = thorTransport().apply { displayWriteLandsOnce = 0.9f }
        val gateway = displayVendorGateway(transport)

        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals("back where it was", 0.65f, transport.panels.getValue(4), 0.0001f)
        assertEquals(listOf(4 to 0.4f, 4 to 0.65f), transport.displayWrites)
        assertEquals("65", transport.values[mirrorKey])
        val after = gateway.posture()
        assertEquals("a verified put-back leaves the route healthy", PrivilegedUtilityStatus.READY, after.status)
        assertTrue("and the panel still the vendor route's", after.canControlSecondaryBrightness)
    }

    @Test
    fun `a put-back that cannot be read back poisons the route`() {
        // Reads 1 and 2 answer (the snapshot, then the mismatch); the put-back's read 3 does not.
        val transport = thorTransport().apply {
            displayWriteLandsOnce = 0.9f
            displayReadAnswer = VendorDisplayRead.Failed
            displayReadAnswerFrom = 3
        }
        val gateway = displayVendorGateway(transport)

        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(PrivilegedUtilityStatus.CONNECTION_FAILED, gateway.posture().status)
    }

    @Test
    fun `a level that will not take is undone on both, panel and level`() {
        val transport = thorTransport().apply { mismatchNextWrite = true }
        val gateway = displayVendorGateway(transport)

        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(0.65f, transport.panels.getValue(4), 0.0001f)
        assertEquals("65", transport.values[mirrorKey])
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
    }

    @Test
    fun `a level already where it should be is not written again`() {
        val transport = thorTransport().apply { values[mirrorKey] = "40" }
        val gateway = displayVendorGateway(transport)

        assertTrue(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(0, transport.mutationWrites)
        assertEquals(0.4f, transport.panels.getValue(4), 0.0001f)
    }

    @Test
    fun `when the vendor route can set the panel it is the only selected route`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                canControlSecondaryBrightness = true,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            ),
            displayResult = true,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        val posture = gateway.posture()
        assertEquals(PrivilegedUtilityAuthority.CERTIFIED_VENDOR, posture.authority)
        assertTrue(posture.canControlSecondaryBrightness)
        assertNull("no supplemental access is exposed", posture.supplementalAccessStatus)
        assertFalse(gateway.requestAccess(PrivilegedUtilityStatus.SERVICE_STOPPED))
        assertTrue(gateway.setSecondaryBrightness(4, 0.4f, 40))
        gateway.readDisplayBrightness(4)
        assertEquals(1, vendor.displayCalls)
        assertEquals(1, vendor.displayReadCalls)
    }

    @Test
    fun `when the vendor route gives the panel up, secondary brightness stays unavailable`() {
        val vendor = FakeGateway(
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                canControlSecondaryBrightness = true,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            ),
            displayResult = false,
        )
        val gateway = CertifiedPrivilegeGateway(vendor, vendor)

        assertFalse("a failed vendor write leaves the panel unavailable", gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(1, vendor.displayCalls)

        vendor.current = vendor.current.copy(canControlSecondaryBrightness = false)
        assertFalse(gateway.setSecondaryBrightness(4, 0.4f, 40))
        assertEquals(1, vendor.displayCalls)
        assertNull(gateway.posture().supplementalAccessStatus)
    }

    private fun readyVendorGateway(transport: FakeVendorTransport): AndroidCertifiedVendorPrivilegeGateway {
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        return AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
        )
    }

    @Test
    fun `vendor process presence answers only what its probe read back and refuses without a transaction`() {
        // Round-5 B1: a Tabs row is retired only on ABSENT, so this route must never manufacture one.
        val youtube = "com.google.android.youtube"
        val previousProvider = MimirLaunchTargetStopPolicy.sessionAllowedPackages
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(youtube) }
        val transport = FakeVendorTransport()
        val profile = requireNotNull(CertifiedVendorAdmissionPolicy.admit(admittedEvidence()))
        val gateway = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(mutableSetOf(profile.consentKey)),
            evidenceReader = ::admittedEvidence,
        )

        try {
            transport.presenceReply = "${VendorPServerOperation.ProcessAbsentMarker}\n"
            assertEquals(PrivilegedPackageLiveness.ABSENT, gateway.packageLiveness(youtube))
            transport.presenceReply = VendorPServerOperation.ProcessAliveMarker
            assertEquals(PrivilegedPackageLiveness.ALIVE, gateway.packageLiveness(youtube))
            // A failed or timed-out transaction, and a device whose guard found no pidof, are not answers.
            transport.presenceReply = null
            assertEquals(PrivilegedPackageLiveness.UNKNOWN, gateway.packageLiveness(youtube))
            transport.presenceReply = ""
            assertEquals(PrivilegedPackageLiveness.UNKNOWN, gateway.packageLiveness(youtube))
            // One package per transaction.
            assertEquals(List(4) { youtube }, transport.presenceRequests)

            // A package the stop policy refuses, and a route whose live probe fails, answer UNKNOWN without
            // reaching the transport at all.
            transport.presenceReply = VendorPServerOperation.ProcessAbsentMarker
            assertEquals(PrivilegedPackageLiveness.UNKNOWN, gateway.packageLiveness("com.example.unrelated"))
            transport.alive = false
            assertEquals(PrivilegedPackageLiveness.UNKNOWN, gateway.packageLiveness(youtube))
            assertEquals(4, transport.presenceRequests.size)
            assertEquals(0, transport.mutationWrites)
        } finally {
            gateway.close()
            MimirLaunchTargetStopPolicy.sessionAllowedPackages = previousProvider
        }
    }

    @Test
    fun `vendor RetroArch core listing is Known only for a clean reply from a live route and never reaches the shell otherwise`() {
        val marker = RetroArchCoreListing.REPLY_END_MARKER
        val transport = FakeVendorTransport()
        val gateway = readyVendorGateway(transport)
        try {
            transport.coresReply = "gambatte_libretro_android.so mgba_libretro_android.so $marker\n"
            assertEquals(
                RetroArchCoreListing.Known(
                    setOf("gambatte_libretro_android.so", "mgba_libretro_android.so"),
                ),
                gateway.listRetroArchCores(0, "com.retroarch.aarch64"),
            )
            // No reply, a shell complaint and a cut-short reply are not answers; none is an empty folder.
            for (reply in listOf(null, "", "ls: no such file or directory", "mgba_libretro_android.so")) {
                transport.coresReply = reply
                assertEquals(
                    RetroArchCoreListing.Unknown,
                    gateway.listRetroArchCores(0, "com.retroarch.aarch64"),
                )
            }
            assertEquals(5, transport.coresRequests.size)

            // The route being down answers Unknown WITHOUT a transaction: the launch goes ahead as before.
            transport.coresReply = marker
            transport.alive = false
            assertEquals(
                RetroArchCoreListing.Unknown,
                gateway.listRetroArchCores(0, "com.retroarch.aarch64"),
            )
            assertEquals(5, transport.coresRequests.size)
            assertEquals(0, transport.mutationWrites)
        } finally {
            gateway.close()
        }
        assertEquals(List(5) { 0 to "com.retroarch.aarch64" }, transport.coresRequests)
    }

    @Test
    fun `keyboard mutations re-attest the live service and refuse when its capabilities changed on the same binder`() {
        val imeId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
        fun gatewayFor(transport: FakeVendorTransport) = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(),
            evidenceReader = { admittedEvidence().copy(serviceAttestation = transport.attest()) },
            keyboardImeId = { imeId },
        )

        // Unchanged service: each mutation drops the cache first and goes ahead.
        val steady = FakeVendorTransport().apply { cacheAttestation = true }
        val steadyGateway = gatewayFor(steady)
        assertEquals(PrivilegedUtilityStatus.READY, steadyGateway.posture().status)
        val forgetsBefore = steady.forgetCalls
        steadyGateway.setMimirKeyboardDefault()
        assertEquals(listOf("enable", "select"), steady.keyboardMutations)
        assertEquals("a fresh attestation before EACH mutation", forgetsBefore + 2, steady.forgetCalls)

        // The same live Binder now answers as something else (not root): the cached READY would still admit it.
        val changed = FakeVendorTransport().apply { cacheAttestation = true }
        val changedGateway = gatewayFor(changed)
        assertEquals(PrivilegedUtilityStatus.READY, changedGateway.posture().status)
        changed.liveAttestation = { rp5Attestation().copy(uid = "10123") }
        changed.defaultKeyboard = VendorTransportRead.Value(imeId)
        assertEquals(
            "the cached attestation still reads READY -- that is the defect",
            PrivilegedUtilityStatus.READY,
            changedGateway.posture().status,
        )

        assertEquals(KeyboardDefaultResult.FAILED, changedGateway.setMimirKeyboardDefault())
        assertEquals(emptyList<String>(), changed.keyboardMutations)

        // The same for the reset, on a gateway that still holds the cached READY when it is asked.
        val resetTransport = FakeVendorTransport().apply {
            cacheAttestation = true
            defaultKeyboard = VendorTransportRead.Value(imeId)
            otherKeyboard = VendorTransportRead.Value("com.google.android.inputmethod.latin/.LatinIME")
        }
        val resetGateway = gatewayFor(resetTransport)
        assertEquals(PrivilegedUtilityStatus.READY, resetGateway.posture().status)
        resetTransport.liveAttestation = { rp5Attestation().copy(uid = "10123") }
        assertEquals(KeyboardDefaultResult.FAILED, resetGateway.resetKeyboardDefault())
        assertEquals(emptyList<String>(), resetTransport.keyboardMutations)
    }

    @Test
    fun `each keyboard mutation is attested, then cancel-checked, then sent, in that order`() {
        val imeId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
        fun gatewayFor(transport: FakeVendorTransport) = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(),
            evidenceReader = { admittedEvidence().copy(serviceAttestation = transport.attest()) },
            keyboardImeId = { imeId },
        )
        val transport = FakeVendorTransport().apply { cacheAttestation = true }
        val gateway = gatewayFor(transport)
        assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
        transport.events.clear()
        gateway.setMimirKeyboardDefault(shouldContinue = { transport.events += "check"; true })
        assertEquals(
            listOf("attest", "check", "send enable", "attest", "check", "send select"),
            transport.events,
        )

        // A cancel at the check stops that mutation and every later one: nothing is sent after it.
        val cancelled = FakeVendorTransport().apply { cacheAttestation = true }
        val cancelledGateway = gatewayFor(cancelled)
        assertEquals(PrivilegedUtilityStatus.READY, cancelledGateway.posture().status)
        cancelled.events.clear()
        var checks = 0
        cancelledGateway.setMimirKeyboardDefault(shouldContinue = { cancelled.events += "check"; ++checks < 2 })
        assertEquals(listOf("attest", "check", "send enable", "attest", "check"), cancelled.events)
        assertEquals(listOf("enable"), cancelled.keyboardMutations)
    }

    @Test
    fun `the switch turn-off attests then sends ime disable and never ime reset while another keyboard is enabled`() {
        val imeId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
        val latin = "com.google.android.inputmethod.latin/.LatinIME"
        fun gatewayFor(transport: FakeVendorTransport) = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(),
            evidenceReader = { admittedEvidence().copy(serviceAttestation = transport.attest()) },
            keyboardImeId = { imeId },
        )
        listOf(false, true).forEach { safetyRevert ->
            val transport = FakeVendorTransport().apply {
                cacheAttestation = true
                defaultKeyboard = VendorTransportRead.Value(imeId)
                otherKeyboard = VendorTransportRead.Value(latin)
            }
            val gateway = gatewayFor(transport)
            assertEquals(PrivilegedUtilityStatus.READY, gateway.posture().status)
            transport.events.clear()
            val result = if (safetyRevert) gateway.revertKeyboardDefaultForSafety() else gateway.resetKeyboardDefault()
            assertEquals("safetyRevert=$safetyRevert", KeyboardDefaultResult.APPLIED, result)
            assertEquals("safetyRevert=$safetyRevert", listOf("attest", "send disable"), transport.events)
            assertEquals("safetyRevert=$safetyRevert", listOf("disable"), transport.keyboardMutations)
        }
    }

    @Test
    fun `with no other keyboard the switch turn-off sends nothing and only the safety revert sends the reset`() {
        val imeId = "com.gruning.mimir.rewrite/.MimirInputMethodService"
        fun gatewayFor(transport: FakeVendorTransport) = AndroidCertifiedVendorPrivilegeGateway(
            context = TestContext(),
            identity = retroidIdentity(),
            transport = transport,
            consent = FakeConsentStore(),
            evidenceReader = { admittedEvidence().copy(serviceAttestation = transport.attest()) },
            keyboardImeId = { imeId },
        )
        fun onlyMimir() = FakeVendorTransport().apply {
            cacheAttestation = true
            defaultKeyboard = VendorTransportRead.Value(imeId)
            otherKeyboard = VendorTransportRead.Value(null)
        }

        val switchTransport = onlyMimir()
        val switchGateway = gatewayFor(switchTransport)
        assertEquals(PrivilegedUtilityStatus.READY, switchGateway.posture().status)
        switchTransport.events.clear()
        assertEquals(KeyboardDefaultResult.NO_OTHER_KEYBOARD, switchGateway.resetKeyboardDefault())
        assertEquals("not even an attestation: nothing is sent", emptyList<String>(), switchTransport.events)
        assertEquals(emptyList<String>(), switchTransport.keyboardMutations)

        val revertTransport = onlyMimir()
        val revertGateway = gatewayFor(revertTransport)
        assertEquals(PrivilegedUtilityStatus.READY, revertGateway.posture().status)
        revertTransport.events.clear()
        assertEquals(KeyboardDefaultResult.APPLIED, revertGateway.revertKeyboardDefaultForSafety())
        assertEquals(listOf("attest", "send reset"), revertTransport.events)
        assertEquals(listOf("reset"), revertTransport.keyboardMutations)
    }

    private fun admittedEvidence(): CertifiedVendorEvidence = CertifiedVendorEvidence(
        manufacturer = "Moorechip",
        brand = "Retroid",
        model = "Retroid Pocket 5",
        socModel = "SM8250",
        buildFingerprint =
            "qti/kona/kona:13/TKQ1.240621.001/eng.RP5.20251128.173135:user/release-keys",
        vendorSettingsPackage = VendorUtilityPackages.RETROID_SETTINGS,
        vendorPackageEnabled = true,
        vendorPackageIsSystem = true,
        vendorPackageMatchesPlatformSignature = true,
        vendorPackageSignerSha256 = setOf(
            "83f7d0dd6c66a14e39def325422336f6d14552f8a16dabbdaa195b3bd7146544",
        ),
        serviceAttestation = rp5Attestation(),
    )

    private fun retroidIdentity(): BrightnessDeviceIdentity = BrightnessDeviceIdentity(
        manufacturer = "Moorechip",
        brand = "Retroid",
        model = "Retroid Pocket 5",
        vendorSettingsPackage = VendorUtilityPackages.RETROID_SETTINGS,
    )
}

private class FakeConsentStore(
    private val granted: MutableSet<String> = mutableSetOf(),
) : CertifiedVendorConsentStore {
    override fun isGranted(consentKey: String): Boolean = consentKey in granted
    override fun grant(consentKey: String) {
        granted += consentKey
    }
}

private class FakeVendorTransport(
    val values: MutableMap<String, String> = mutableMapOf(),
) : CertifiedVendorTransport {
    var alive = true
    var mismatchNextWrite = false
    var timeoutNextWrite = false
    var restoreWrites = 0
    var mutationWrites = 0
    var hudReads = 0
    var hudBindingInvalidated = false
    var boundBinderForHud = true
    var closeCalls = 0
    var invalidateDuringHudRead = false
    var onAliveCheck: (() -> Unit)? = null
    var hudSnapshot: VendorHudRawSnapshot? = null
    var emulatorTarget: EmulatorRenderFpsTargetIdentity? = null
    var emulatorRecordCount = 60L
    var emulatorRecordStatus = "ok"
    var emulatorRecordMissing = false
    var emulatorRecordPidOverride: Int? = null
    var emulatorStarts = 0
    var presenceReply: String? = null
    val presenceRequests = mutableListOf<String>()
    var coresReply: String? = null
    val coresRequests = mutableListOf<Pair<Int, String>>()
    private var lastSnapshot: String? = null

    /** The display manager's brightness per display, as `service call display` would read it back. */
    val panels = mutableMapOf<Int, Float>()
    val displayReads = mutableListOf<Int>()
    val displayWrites = mutableListOf<Pair<Int, Float>>()
    /** Answers every display read instead of [panels], from the Nth read on (1-based). */
    var displayReadAnswer: VendorDisplayRead? = null
    var displayReadAnswerFrom = 1
    /** The display manager refuses setBrightness: a reply that is not a clean void result. */
    var displayWriteRefused = false
    /** The panel settles here instead of where it was asked to, once. */
    var displayWriteLandsOnce: Float? = null

    override fun readDisplayBrightness(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
    ): VendorDisplayRead {
        displayReads += displayId
        if (displayReads.size >= displayReadAnswerFrom) displayReadAnswer?.let { return it }
        if (!alive) return VendorDisplayRead.Failed
        return panels[displayId]?.let(VendorDisplayRead::Value) ?: VendorDisplayRead.NoBrightness
    }

    override fun writeDisplayBrightness(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
        fraction: Float,
    ): VendorTransportMutation {
        displayWrites += displayId to fraction
        if (!alive || displayWriteRefused) return VendorTransportMutation.FAILED
        panels[displayId] = displayWriteLandsOnce?.also { displayWriteLandsOnce = null } ?: fraction
        return VendorTransportMutation.APPLIED
    }

    override fun isAlive(): Boolean {
        onAliveCheck?.invoke()
        return alive
    }
    override fun isBindingInvalidatedForHud(): Boolean = hudBindingInvalidated
    override fun hasBoundBinderForHud(): Boolean = boundBinderForHud
    /** When set, attestation is CACHED like the real transport's, until [forgetAttestation] drops it. */
    var cacheAttestation = false
    var liveAttestation: () -> CertifiedVendorServiceAttestation = { rp5Attestation() }
    private var cachedAttestation: CertifiedVendorServiceAttestation? = null
    var forgetCalls = 0
    val keyboardMutations = mutableListOf<String>()
    val events = mutableListOf<String>()

    override fun attest(): CertifiedVendorServiceAttestation? {
        if (!alive) return null
        if (!cacheAttestation) return rp5Attestation()
        return cachedAttestation ?: liveAttestation().also { cachedAttestation = it }
    }

    override fun forgetAttestation() {
        forgetCalls += 1
        events += "attest"
        cachedAttestation = null
    }

    override fun enableKeyboard(imeId: String): VendorTransportMutation {
        keyboardMutations += "enable"
        events += "send enable"
        return VendorTransportMutation.APPLIED
    }

    override fun selectKeyboard(imeId: String): VendorTransportMutation {
        keyboardMutations += "select"
        events += "send select"
        return VendorTransportMutation.APPLIED
    }

    var defaultKeyboard: VendorTransportRead = VendorTransportRead.Failed

    override fun readDefaultKeyboard(): VendorTransportRead = defaultKeyboard

    override fun resetKeyboards(imeId: String): VendorTransportMutation {
        keyboardMutations += "reset"
        events += "send reset"
        defaultKeyboard = VendorTransportRead.Value(null)
        return VendorTransportMutation.APPLIED
    }

    /** Readback E: Value(null) is the real answer "no other keyboard is enabled". */
    var otherKeyboard: VendorTransportRead = VendorTransportRead.Value(null)

    override fun readOtherKeyboard(imeId: String): VendorTransportRead = otherKeyboard

    override fun disableKeyboard(imeId: String): VendorTransportMutation {
        keyboardMutations += "disable"
        events += "send disable"
        (otherKeyboard as? VendorTransportRead.Value)?.let { defaultKeyboard = it }
        return VendorTransportMutation.APPLIED
    }

    override fun readHudTelemetry(deadlineElapsedRealtimeMs: Long): VendorHudRawSnapshot? {
        hudReads += 1
        val snapshot = hudSnapshot.takeIf { alive }
        if (invalidateDuringHudRead) {
            invalidateDuringHudRead = false
            alive = false
            hudBindingInvalidated = true
        }
        return snapshot
    }

    override fun resolveEmulatorRenderFpsTarget(
        files: EmulatorRenderFpsHelperFiles,
        packageName: String,
        deadlineElapsedRealtimeMs: Long,
    ): EmulatorRenderFpsTargetIdentity? = emulatorTarget
        ?.takeIf { alive && it.packageName == packageName }

    override fun startEmulatorRenderFps(files: EmulatorRenderFpsHelperFiles): Boolean {
        if (!alive) return false
        emulatorStarts += 1
        return true
    }

    override fun readEmulatorRenderFpsRecord(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): String? {
        if (!alive) return null
        val request = parseEmulatorRenderFpsRequest(files.request.readText()) ?: return null
        val record = EmulatorRenderFpsRecord(
            token = request.token,
            packageName = request.packageName,
            uid = request.uid,
            pid = emulatorRecordPidOverride ?: request.pid,
            startTicks = request.startTicks,
            generation = request.generation,
            sampleBoottimeMs = request.leaseBoottimeMs - EMULATOR_RENDER_FPS_SESSION_LEASE_MS,
            intervalMs = request.intervalMs,
            count = emulatorRecordCount,
            status = emulatorRecordStatus,
        )
        val line = formatEmulatorRenderFpsRecord(record) ?: return null
        return Base64.getEncoder().encodeToString(line.toByteArray(Charsets.UTF_8))
    }

    override fun readEmulatorRenderFpsRecordResult(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): EmulatorRenderFpsRecordRead = if (emulatorRecordMissing) {
        EmulatorRenderFpsRecordRead.Missing
    } else {
        super.readEmulatorRenderFpsRecordResult(files, deadlineElapsedRealtimeMs)
    }

    override fun readSystemSetting(key: String): VendorTransportRead =
        if (alive) VendorTransportRead.Value(values[key]) else VendorTransportRead.Failed

    override fun writeSystemSetting(
        key: String,
        normalizedValue: String,
    ): VendorTransportMutation {
        mutationWrites += 1
        if (timeoutNextWrite) {
            timeoutNextWrite = false
            alive = false
            return VendorTransportMutation.TIMED_OUT
        }
        if (!alive) return VendorTransportMutation.FAILED
        val previous = values[key]
        if (lastSnapshot != null && normalizedValue == lastSnapshot) restoreWrites += 1
        if (mismatchNextWrite) {
            mismatchNextWrite = false
            lastSnapshot = previous
            values[key] = "0.25"
        } else {
            if (lastSnapshot != null && normalizedValue == previous) restoreWrites += 1
            values[key] = normalizedValue
        }
        return VendorTransportMutation.APPLIED
    }

    override fun deleteSystemSetting(key: String): VendorTransportMutation {
        values.remove(key)
        return if (alive) VendorTransportMutation.APPLIED else VendorTransportMutation.FAILED
    }

    override fun openSystemRecents(): VendorTransportMutation =
        if (alive) VendorTransportMutation.APPLIED else VendorTransportMutation.FAILED

    override fun forceStopAllowedPackage(packageName: String): VendorTransportMutation =
        if (alive) VendorTransportMutation.APPLIED else VendorTransportMutation.FAILED

    override fun processPresenceReply(packageName: String): String? {
        presenceRequests += packageName
        return presenceReply
    }

    override fun listRetroArchCoresReply(userId: Int, packageName: String): String? {
        coresRequests += userId to packageName
        return coresReply
    }

    override fun close() {
        closeCalls += 1
        alive = false
    }
}

/**
 * Models the production transport's fail-closed response to overlapping first-attestation probes.
 * The gateway must prevent the overlap; recovering this fake after poison would hide the bug.
 */
private class ConcurrencyPoisoningVendorTransport : CertifiedVendorTransport {
    private val attesting = AtomicBoolean(false)
    private val poisoned = AtomicBoolean(false)

    override fun isAlive(): Boolean = !poisoned.get()

    override fun attest(): CertifiedVendorServiceAttestation? {
        if (!attesting.compareAndSet(false, true)) {
            poisoned.set(true)
            return null
        }
        return try {
            Thread.sleep(40)
            rp5Attestation().takeUnless { poisoned.get() }
        } finally {
            attesting.set(false)
        }
    }

    override fun readSystemSetting(key: String): VendorTransportRead = VendorTransportRead.Failed

    override fun writeSystemSetting(
        key: String,
        normalizedValue: String,
    ): VendorTransportMutation = VendorTransportMutation.FAILED

    override fun deleteSystemSetting(key: String): VendorTransportMutation =
        VendorTransportMutation.FAILED

    override fun openSystemRecents(): VendorTransportMutation =
        if (isAlive()) VendorTransportMutation.APPLIED else VendorTransportMutation.FAILED

    override fun forceStopAllowedPackage(packageName: String): VendorTransportMutation =
        if (isAlive()) VendorTransportMutation.APPLIED else VendorTransportMutation.FAILED

    override fun close() {
        poisoned.set(true)
    }
}

private fun rp5Attestation(): CertifiedVendorServiceAttestation =
    CertifiedVendorServiceAttestation(
        serviceName = CertifiedVendorAdmissionPolicy.ServiceName,
        binderDescriptor = "",
        protocolId = CertifiedVendorAdmissionPolicy.ProtocolId,
        uid = "0",
        gid = "0",
        selinuxDomain = "u:r:pservice:s0",
        executablePath = "/system/bin/pservice",
        executableSha256 =
            "f44773c8dbdc03904dc604d94c2a6837562d14c808eb6b52ff3b644b19817960",
    )

private class FakeGateway(
    var current: PrivilegedUtilityPosture,
    private val readResult: String? = null,
    private val writeResult: Boolean = false,
    private val displayResult: Boolean = false,
    private val recentsRequestResult: Boolean = true,
    private val recentsOpenResult: Boolean = false,
    private val frameRateResult: Int? = null,
    private val utilizationResult: DeviceUtilizationSample? = null,
    private val hudReady: Boolean = true,
) : PrivilegedUtilityGateway, MButtonSystemRecentsGateway {
    var writeCalls = 0
    var displayCalls = 0
    var accessRequests = 0
    var postureCalls = 0
    var recentsStatusCalls = 0
    var recentsRequestCalls = 0
    var builtInRecentsConsentCalls = 0
    var recentsActionCount = 0
    var recentsOpenCalls = 0
    var frameRateCalls = 0
    var utilizationCalls = 0
    var lastRecentsExpectedStatus: PrivilegedUtilityStatus? = null

    override fun posture(): PrivilegedUtilityPosture {
        postureCalls += 1
        return current
    }

    override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
        accessRequests += 1
        return false
    }

    override fun readVendorSetting(key: String): String? = readResult

    override fun writeVendorSetting(key: String, value: String): Boolean {
        writeCalls += 1
        return writeResult
    }

    var displayReadCalls = 0

    override fun readDisplayBrightness(displayId: Int): Float? {
        displayReadCalls += 1
        return null
    }

    override fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean {
        displayCalls += 1
        return displayResult
    }

    override fun forceStopLaunchTarget(packageName: String): PrivilegedLaunchTargetStop =
        PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE

    override fun readForegroundFrameRateMilli(packageName: String): Int? {
        frameRateCalls += 1
        return frameRateResult
    }

    override fun isHudTelemetryReady(): Boolean = hudReady

    override fun readDeviceUtilization(): DeviceUtilizationSample? {
        utilizationCalls += 1
        return utilizationResult
    }

    override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus {
        recentsStatusCalls += 1
        return current.status
    }

    override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
        recentsRequestCalls += 1
        lastRecentsExpectedStatus = expectedStatus
        if (current.status != expectedStatus) return false
        recentsActionCount += 1
        return recentsRequestResult
    }

    override fun requestBuiltInSystemRecentsConsent(
        expectedStatus: PrivilegedUtilityStatus,
    ): Boolean {
        builtInRecentsConsentCalls += 1
        if (current.status != expectedStatus ||
            expectedStatus != PrivilegedUtilityStatus.PERMISSION_REQUIRED
        ) {
            return false
        }
        recentsActionCount += 1
        return recentsRequestResult
    }

    override fun openSystemRecents(): Boolean {
        recentsOpenCalls += 1
        return recentsOpenResult
    }

    override fun close() = Unit
}

private class TestContext : android.content.ContextWrapper(null) {
    override fun getApplicationContext(): android.content.Context = this
}
