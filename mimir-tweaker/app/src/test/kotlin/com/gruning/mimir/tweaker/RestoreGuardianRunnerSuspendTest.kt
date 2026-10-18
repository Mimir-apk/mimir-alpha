package com.gruning.mimir.tweaker

import java.io.File
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fan-fence fix F3 (`build/test-captures/fanfence/ROOT_CAUSE.md`, 2026-10-13 15:24 on the Odin): the
 * SoC slept ~32 s, the Guardian's watchdog (on CLOCK_BOOTTIME) expired 6 s after wake, the host's
 * executor heartbeat (on CLOCK_MONOTONIC) never had a chance. Staleness is now measured on the
 * CPU-awake clock; the hard deadline stays on elapsed realtime. Each entry point's `nowUptimeMs`
 * defaults to `now`, so every single-clock caller keeps the stricter old semantics.
 */
class RestoreGuardianRunnerSuspendTest {
    private val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)
    private val fan = RestoreGuardianFanPutEntry(
        1,
        expectedStock = RestoreGuardianStockState.PresentFanMode(0),
        rendererAppliedIntermediates = mutableListOf(1),
    )
    private val envelope = RestoreGuardianEnvelope(
        helperBuildIdentity = "build",
        tweakerCertificateSha256 = "a".repeat(64),
        hardwareFingerprint = "rp5",
        deviceProfile = "rp5",
        bootIdentity = "boot",
        generation = 1,
        requestId = "request",
        transactionId = "transaction",
        taskNonce = "b".repeat(32),
        topologySha256 = "c".repeat(64),
        snapshotSha256 = "d".repeat(64),
        planSha256 = "e".repeat(64),
        hardDeadlineElapsedRealtimeMs = 120_000,
        restoreEntries = mutableListOf(fan),
    )

    @Test
    fun `a 32 s suspend with no CPU-awake time stays WATCHING`() {
        val engine = watching()
        // +32 s boottime, +0 uptime: the SoC was asleep; nobody could have heartbeat.
        assertEquals(GuardianRunnerLifecycleState.WATCHING, engine.watchTick(3 + 32_000, nowUptimeMs = 3).state)
        // And it keeps watching for as long as the host is not actually awake and silent (and the
        // 120 s hard deadline, which stays on boottime, has not passed).
        assertEquals(GuardianRunnerLifecycleState.WATCHING, engine.watchTick(3 + 100_000, nowUptimeMs = 3 + 29_999).state)
    }

    @Test
    fun `30 s of awake time without a heartbeat still restores`() {
        val engine = watching()
        assertEquals(
            GuardianRunnerLifecycleState.RESTORING,
            engine.watchTick(3 + 32_000, nowUptimeMs = 3 + 30_000).state,
        )
    }

    @Test
    fun `an accepted heartbeat refreshes the awake-time origin`() {
        val engine = watching()
        assertEquals(
            GuardianRunnerLifecycleState.WATCHING,
            engine.heartbeat(heartbeat(counter = 1, elapsed = 10), signatureValid = true, now = 10, nowUptimeMs = 10).state,
        )
        assertEquals(GuardianRunnerLifecycleState.WATCHING, engine.watchTick(10 + 50_000, nowUptimeMs = 10 + 29_999).state)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, engine.watchTick(10 + 50_001, nowUptimeMs = 10 + 30_000).state)
    }

    @Test
    fun `the hard deadline stays on elapsed realtime through a suspend`() {
        val engine = watching()
        assertEquals(GuardianRunnerLifecycleState.WATCHING, engine.watchTick(119_999, nowUptimeMs = 3).state)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, engine.watchTick(120_000, nowUptimeMs = 3).state)
    }

    @Test
    fun `READY is watchdog-guarded on awake time too`() {
        val engine = engine()
        engine.stage(GuardianEnvelopeVerificationOutcome.Verified(envelope), 1, "boot")
        assertEquals(GuardianRunnerLifecycleState.READY, engine.admitReady(2, nowUptimeMs = 2).state)
        assertEquals(GuardianRunnerLifecycleState.READY, engine.watchTick(2 + 40_000, nowUptimeMs = 2 + 29_999).state)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, engine.watchTick(2 + 40_001, nowUptimeMs = 2 + 30_000).state)
    }

    @Test
    fun `a single-clock caller keeps the old semantics`() {
        val engine = watching()
        assertEquals(GuardianRunnerLifecycleState.WATCHING, engine.watchTick(3 + 29_999).state)
        assertEquals(GuardianRunnerLifecycleState.RESTORING, engine.watchTick(3 + 30_000).state)
    }

    @Test
    fun `the guardian service passes both clocks at every engine call site`() {
        val source = stripLineComments(mainSourceText("RestoreGuardianProcessService.kt"))
        assertTrue(
            "watchTick must measure staleness on uptime and the deadline on boottime",
            source.contains("engine.watchTick(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())"),
        )
        assertTrue(
            "every origin-setting call must carry the uptime clock",
            source.contains("engine.admitReady(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())") &&
                source.contains("engine.beginWatching(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())") &&
                Regex(Regex.escape("nowUptimeMs = SystemClock.uptimeMillis()")).findAll(source).count() == 2,
        )
    }

    @Test
    fun `the host publishes a heartbeat on SCREEN_ON through the heartbeat executor`() {
        val service = stripLineComments(mainSourceText("MimirTweakerService.kt"))
        val receiver = sliceBetween(
            service,
            "private fun registerPowerTransitionRecovery(",
            "private fun unregisterPowerTransitionRecovery()",
        )
        assertTrue(receiver.contains("Intent.ACTION_SCREEN_ON -> {"))
        assertTrue(receiver.contains("remoteGuardianAuthority?.publishHeartbeatNow()"))
        assertTrue(receiver.contains("addAction(Intent.ACTION_SCREEN_ON)"))

        val authority = stripLineComments(mainSourceText("RemoteGuardianProcessAuthority.kt"))
        val now = sliceBetween(authority, "fun publishHeartbeatNow()", "private sealed interface HeartbeatStep")
        assertTrue(
            "the extra beat must be submitted to the single heartbeat scheduler, never run inline",
            now.contains("scheduler.execute { publishHeartbeat() }") && !now.contains("publishHeartbeat()\n"),
        )
    }

    // -- Helpers --------------------------------------------------------------------------------

    private fun engine() = RestoreGuardianRunnerEngine(
        receiptAuthentication = GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
        receiptOutput = GuardianReceiptOutputPort { GuardianReceiptOutputDisposition.CONFIRMED },
        sessionKey = sessionKey,
    )

    private fun watching(): RestoreGuardianRunnerEngine = engine().also {
        it.stage(GuardianEnvelopeVerificationOutcome.Verified(envelope), 1, "boot")
        assertEquals(GuardianRunnerLifecycleState.READY, it.admitReady(2, nowUptimeMs = 2).state)
        assertEquals(GuardianRunnerLifecycleState.WATCHING, it.beginWatching(3, nowUptimeMs = 3).state)
    }

    private fun heartbeat(counter: Long, elapsed: Long) = RestoreGuardianHeartbeat(
        helperBuildIdentity = envelope.helperBuildIdentity,
        bootIdentity = envelope.bootIdentity,
        generation = envelope.generation,
        requestId = envelope.requestId,
        transactionId = envelope.transactionId,
        taskNonce = envelope.taskNonce,
        envelopeSha256 = envelope.digestSha256(),
        counter = counter,
        elapsedRealtimeMs = elapsed,
        hardDeadlineElapsedRealtimeMs = envelope.hardDeadlineElapsedRealtimeMs,
    )

    private fun mainSourceText(name: String): String {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val file = File(root, name)
        if (!file.isFile) throw AssertionError("$name not found under $root")
        return file.readText()
    }

    private fun sliceBetween(text: String, startMarker: String, endMarker: String): String {
        val start = text.indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = text.indexOf(endMarker, start)
        require(end > start) { "end marker not found after start: $endMarker" }
        return text.substring(start, end)
    }

    private fun stripLineComments(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
}
