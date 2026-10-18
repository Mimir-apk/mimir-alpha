package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreGuardianRunnerTest {
    private val rp5Source = InstalledTweakerApkSource(
        // Read-only PackageManager observation from RP5 a06ee6b8 on 2026-07-29; fixture only.
        sourceDir = "/data/app/~~YVNPC3HPZB4E0d9-2C937Q==/com.gruning.mimir.tweaker-P1x25Fj8KMtUp0aC0qwNVQ==/base.apk",
        actualSigningCertificateSha256 = "a".repeat(64),
    )
    private val pinnedAuthority = PinnedTweakerApkAuthority("a".repeat(64))
    private val sysfs = RestoreGuardianSysfsEntry(
        applyOrdinal = 2,
        logicalId = "cpu:0:scaling_max_freq",
        canonicalSysfsPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        expectedStock = RestoreGuardianStockState.PresentSysfs(111, "644", 0, 0),
        rendererAppliedIntermediates = listOf(222),
    )
    private val fan = RestoreGuardianFanPutEntry(1, expectedStock = RestoreGuardianStockState.PresentFanMode(0), rendererAppliedIntermediates = listOf(1))

    @Test fun `all direct synchronous invocations use the RP5 Tweaker sourceDir and fit with headroom`() {
        val synchronous = listOf(
            RestoreGuardianRunnerMode.STAGE,
            RestoreGuardianRunnerMode.APPLY_UNDER_FENCE,
            RestoreGuardianRunnerMode.RESTORE_UNDER_FENCE,
            RestoreGuardianRunnerMode.TERMINALIZE,
            RestoreGuardianRunnerMode.CLEANUP,
        )
        synchronous.forEach { mode ->
            val invocation = requireNotNull(RestoreGuardianDirectLauncher.renderOrNull(rp5Source, pinnedAuthority, mode))
            assertFalse(invocation.detached)
            assertEquals("CLASSPATH='${rp5Source.sourceDir}' app_process /system/bin com.gruning.mimir.tweaker.G ${mode.argument}", invocation.statement)
            assertEquals(162, invocation.statement.toByteArray().size)
            assertEquals(36, RestoreGuardianDirectLauncher.RP5_PROVEN_STATEMENT_MAX_BYTES - invocation.statement.toByteArray().size)
            assertFalse(invocation.statement.contains("-Djava.class.path"))
            assertFalse(invocation.statement.contains("/data/local/tmp/mg"))
        }
    }

    @Test fun `only explicit guardian watch is detached and remains inside observed headroom`() {
        val invocation = requireNotNull(
            RestoreGuardianDirectLauncher.renderOrNull(rp5Source, pinnedAuthority, RestoreGuardianRunnerMode.GUARDIAN_WATCH),
        )
        assertTrue(invocation.detached)
        assertEquals("setsid env CLASSPATH='${rp5Source.sourceDir}' app_process /system/bin com.gruning.mimir.tweaker.G g &", invocation.statement)
        assertEquals(175, invocation.statement.toByteArray().size)
        assertEquals(23, RestoreGuardianDirectLauncher.RP5_PROVEN_STATEMENT_MAX_BYTES - invocation.statement.toByteArray().size)
    }

    @Test fun `certificate mismatch emits no invocation`() {
        val wrong = PinnedTweakerApkAuthority("b".repeat(64))
        assertNull(RestoreGuardianDirectLauncher.renderOrNull(rp5Source, wrong, RestoreGuardianRunnerMode.APPLY_UNDER_FENCE))
    }

    @Test fun `sourceDir is canonical ascii base apk only and rejects shell injection`() {
        listOf(
            "/data/app/x/base.apk;id",
            "/data/local/tmp/x/base.apk",
            "/data/app/x/split.apk",
            "/data/app/x/y/base.apk with space",
            "/data/app/x/y/base.apk'$(id)",
            "/data/app/x//base.apk",
        ).forEach { path -> assertRejected { InstalledTweakerApkSource(path, "a".repeat(64)) } }
    }

    @Test fun `G and RestoreGuardianMain have exact mode parity and remain inert`() {
        RestoreGuardianRunnerMode.entries.forEach { mode ->
            val direct = outcome { RestoreGuardianMain.main(arrayOf(mode.argument)) }
            val alias = outcome { G.main(arrayOf(mode.argument)) }
            assertEquals(direct, alias)
            assertEquals(IllegalStateException::class.java.name, alias.first)
            assertTrue(alias.second.contains("inert"))
        }
        assertEquals(outcome { RestoreGuardianMain.main(emptyArray()) }, outcome { G.main(emptyArray()) })
        assertEquals(outcome { RestoreGuardianMain.main(arrayOf("bad")) }, outcome { G.main(arrayOf("bad")) })
    }

    @Test fun `typed restore skips exact stock and demands all metadata`() {
        val exact = GuardianObservedState.Sysfs(111, "644", 0, 0)
        assertEquals(GuardianRestoreDecision.ALREADY_STOCK, GuardianTypedRestorePolicy.decide(sysfs, exact))
        assertEquals(GuardianRestoreDecision.WRITE_REQUIRED, GuardianTypedRestorePolicy.decide(sysfs, exact.copy(gid = 2000)))
        assertEquals(GuardianRestoreDecision.INVALID_OBSERVATION, GuardianTypedRestorePolicy.decide(sysfs, GuardianObservedState.FanMode(111)))
        assertEquals(GuardianRestoreDecision.ALREADY_STOCK, GuardianTypedRestorePolicy.decide(fan, GuardianObservedState.FanMode(0)))
    }

    @Test fun `terminal admission remains terminalized rather than becoming an identity failure`() {
        val envelope = RestoreGuardianEnvelope(
            helperBuildIdentity = "build", tweakerCertificateSha256 = "a".repeat(64),
            hardwareFingerprint = "rp5", deviceProfile = "rp5", bootIdentity = "boot",
            generation = 1, requestId = "request", transactionId = "transaction", taskNonce = "b".repeat(32),
            topologySha256 = "c".repeat(64), snapshotSha256 = "d".repeat(64), planSha256 = "e".repeat(64),
            hardDeadlineElapsedRealtimeMs = 1000, restoreEntries = listOf(sysfs, fan),
        )
        val derived = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(envelope)
        assertEquals(
            FenceApplyDecision.TERMINALIZED,
            GuardianRunnerFencePolicy.evaluate(envelope, FenceDiskState(true, derived, lockAcquired = true), "boot", true),
        )
        assertEquals(
            FenceApplyDecision.IDENTITY_MISMATCH,
            GuardianRunnerFencePolicy.evaluate(envelope, FenceDiskState(true, derived, lockAcquired = true), "other", false),
        )
    }

    @Test fun `double complete stock readback distinguishes a failed second read`() {
        val entries = listOf(sysfs, fan)
        val first = GuardianStockReadback(mapOf(sysfs.logicalId to GuardianObservedState.Sysfs(111, "644", 0, 0), fan.logicalId to GuardianObservedState.FanMode(0)), 100)
        val second = first.copy(observedElapsedRealtimeMs = 175)
        assertEquals(GuardianDoubleStockDecision.STOCK_CONFIRMED, GuardianDoubleStockReadbackPolicy.evaluate(entries, first, second))
        assertEquals(GuardianDoubleStockDecision.SETTLE_GAP_TOO_SHORT, GuardianDoubleStockReadbackPolicy.evaluate(entries, first, first.copy(observedElapsedRealtimeMs = 174)))
        assertEquals(GuardianDoubleStockDecision.SECOND_READBACK_NOT_STOCK, GuardianDoubleStockReadbackPolicy.evaluate(entries, first, second.copy(entries = second.entries + (fan.logicalId to GuardianObservedState.FanMode(1)))))
        assertFalse(GuardianTypedRestorePolicy.exactReadback(sysfs, GuardianObservedState.Sysfs(111, "600", 0, 0)))
    }

    private fun outcome(block: () -> Unit): Pair<String, String> = try {
        block(); "returned" to ""
    } catch (error: Throwable) {
        error.javaClass.name to (error.message ?: "")
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
