package com.gruning.mimir.tweaker

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class RestoreGuardianServiceLivenessTest {
    @Test
    fun `scheduled Stock finalization retries owned receipt and signals settlement once`() {
        val fixture = Fixture.create()
        val service = RestoreGuardianProcessService()
        attach(service)
        try {
            fixture.install(service)
            assertTrue(getPrivate<Any>(service, "terminalStore") === fixture.store)
            val installedActive = getPrivate<Any>(service, "active")
            assertTrue(getPrivate<Any>(installedActive, "engine") === fixture.engine)
            assertTrue(getPrivate<Any>(installedActive, "collector") === fixture.collector)
            fixture.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(fixture.envelope), 1, "boot")
            fixture.engine.admitReady(2)
            fixture.engine.beginWatching(3)
            fixture.engine.ownerCancelled()
            val stock = fixture.stockReadback()
            assertEquals(
                GuardianRunnerLifecycleState.STOCK_CONFIRMED,
                fixture.engine.confirmStock(stock, stock.copy(observedElapsedRealtimeMs = 175)).state,
            )
            assertTrue(fixture.store.loadUntil(0) is GuardianTerminalLoad.Unavailable)
            val remainingLease = fixture.envelope.hardDeadlineElapsedRealtimeMs - SystemClock.elapsedRealtime() + 1
            ShadowSystemClock.advanceBy(maxOf(remainingLease, 1), TimeUnit.MILLISECONDS)
            assertTrue(SystemClock.elapsedRealtime() > fixture.envelope.hardDeadlineElapsedRealtimeMs)

            val result = fixture.invokeExistingRecovery(service)
            assertFalse(result.getBoolean(RestoreGuardianProcessService.KEY_SUCCESS))
            assertTrue(getPrivate<Any>(service, "active") === installedActive)
            assertTrue(getPrivate<Any>(installedActive, "collector") === fixture.collector)
            assertTrue(
                "expected owned receipt pending, code=${result.getString(RestoreGuardianProcessService.KEY_CODE)}",
                fixture.collector.hasPendingTerminalReceipt(),
            )
            assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, fixture.engine.ownerCancelled().state)
            assertTrue(fixture.invokeAdmission(service))
            fixture.armStore.loadResult = GuardianArmStoreLoad.Corrupt
            assertFalse(fixture.invokeAdmission(service))
            fixture.armStore.loadResult = fixture.armStore.loaded

            assertTrue(fixture.brokenRoot.delete())
            assertTrue(fixture.brokenRoot.mkdirs())
            awaitUntil {
                fixture.store.loadUntil(0) is GuardianTerminalLoad.Received &&
                    fixture.engine.ownerCancelled().state == GuardianRunnerLifecycleState.EXITED &&
                    settlementCount() == 1
            }
            val settlements = Shadows.shadowOf(RuntimeEnvironment.getApplication()).allStartedServices.filter {
                it.action == MimirTweakerService.ACTION_GUARDIAN_SETTLEMENT
            }
            assertEquals(
                "app=${settlements.size}, service=${Shadows.shadowOf(service).allStartedServices.size}",
                1,
                settlements.size,
            )
            assertEquals(
                fixture.envelope.generation,
                settlements.single().getLongExtra(MimirTweakerService.EXTRA_RECOVERY_GENERATION, -1),
            )
            assertEquals(
                fixture.envelope.transactionId,
                settlements.single().getStringExtra(MimirTweakerService.EXTRA_RECOVERY_TRANSACTION_ID),
            )

            assertEquals(1, Shadows.shadowOf(RuntimeEnvironment.getApplication()).allStartedServices.count {
                it.action == MimirTweakerService.ACTION_GUARDIAN_SETTLEMENT
            })
        } finally {
            service.onDestroy()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertTrue("scheduled Guardian finalization did not complete", condition())
    }

    private fun settlementCount(): Int =
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).allStartedServices.count {
            it.action == MimirTweakerService.ACTION_GUARDIAN_SETTLEMENT
        }

    private class Fixture private constructor(
        val envelope: RestoreGuardianEnvelope,
        val expected: RestoreGuardianExpectedIdentity,
        val available: DirectBootKeyAuthorityTruth.Available,
        val sessionAuthority: GuardianProcessSessionAuthority,
        val sessionKey: GuardianSessionPublicKey,
        val armStore: MutableArmStore,
        val store: GuardianTerminalReceiptStore,
        val collector: GuardianProcessReceiptCollector,
        val engine: RestoreGuardianRunnerEngine,
        val brokenRoot: File,
        val identity: GenerationFenceIdentity,
    ) {
        fun install(service: RestoreGuardianProcessService) {
            setPrivate(service, "terminalStore", store)
            setPrivate(service, "mutationFence", GuardianProcessMutationFence(temporaryDirectory("fence")))
            val fence = getPrivate<GuardianProcessMutationFence>(service, "mutationFence")
            assertEquals(GuardianFenceDisposition.CONFIRMED, fence.stage(identity))
            val activeType = service.javaClass.declaredClasses.single { it.simpleName == "ActiveGuardianProcess" }
            val constructor = activeType.declaredConstructors.single().also { it.isAccessible = true }
            val active = constructor.newInstance(
                envelope,
                expected,
                armStore,
                available,
                engine,
                collector,
                sessionAuthority,
                sessionKey,
                null,
                null,
                true,
            )
            setPrivate(service, "active", active)
        }

        fun invokeExistingRecovery(service: RestoreGuardianProcessService): android.os.Bundle {
            val active = getPrivate<Any>(service, "active")
            val method = service.javaClass.getDeclaredMethod(
                "recoverExistingActive",
                active.javaClass,
                Int::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
            return method.invoke(service, active, 0) as android.os.Bundle
        }

        fun invokeAdmission(service: RestoreGuardianProcessService): Boolean {
            val active = getPrivate<Any>(service, "active")
            val method = service.javaClass.getDeclaredMethod("sameGenerationRetryAdmitted", active.javaClass)
                .also { it.isAccessible = true }
            return method.invoke(service, active) as Boolean
        }

        fun stockReadback(): GuardianStockReadback = GuardianStockReadback(
            mapOf(
                "cpu:0" to GuardianObservedState.Sysfs(100, "644", 0, 0),
                HardwareSnapshot.FAN_LOGICAL_ID to GuardianObservedState.FanMode(0),
            ),
            100,
        )

        companion object {
            fun create(): Fixture {
                val certificate = "a".repeat(64)
                val authority = GuardianTestKeyAuthority.create(certificate)
                val envelope = RestoreGuardianEnvelope(
                    helperBuildIdentity = "helper",
                    tweakerCertificateSha256 = certificate,
                    hardwareFingerprint = "hardware",
                    deviceProfile = "profile",
                    bootIdentity = "boot",
                    generation = 11,
                    requestId = "request-11",
                    transactionId = "transaction-11",
                    taskNonce = "b".repeat(32),
                    topologySha256 = "c".repeat(64),
                    snapshotSha256 = "d".repeat(64),
                    planSha256 = "e".repeat(64),
                    hardDeadlineElapsedRealtimeMs = 100_000,
                    restoreEntries = listOf(
                        RestoreGuardianSysfsEntry(
                            2,
                            "cpu:0",
                            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                            RestoreGuardianStockState.PresentSysfs(100, "644", 0, 0),
                            listOf(200),
                        ),
                        RestoreGuardianFanPutEntry(
                            1,
                            expectedStock = RestoreGuardianStockState.PresentFanMode(0),
                            rendererAppliedIntermediates = listOf(1),
                        ),
                    ),
                )
                val expected = RestoreGuardianExpectedIdentity(
                    envelope.helperBuildIdentity,
                    envelope.tweakerCertificateSha256,
                    envelope.hardwareFingerprint,
                    envelope.deviceProfile,
                    envelope.bootIdentity,
                    envelope.generation,
                    envelope.requestId,
                    envelope.transactionId,
                    envelope.taskNonce,
                    envelope.topologySha256,
                    envelope.snapshotSha256,
                    envelope.hardDeadlineElapsedRealtimeMs,
                )
                val sessionAuthority = checkNotNull(GuardianProcessSessionAuthority.generate())
                val sessionKey = sessionAuthority.sessionPublicKey
                val signedEnvelope = authority.available.signEnvelope(envelope)
                val ready = RestoreGuardianReadyReceipt(
                    helperBuildIdentity = envelope.helperBuildIdentity,
                    bootIdentity = envelope.bootIdentity,
                    generation = envelope.generation,
                    requestId = envelope.requestId,
                    transactionId = envelope.transactionId,
                    taskNonce = envelope.taskNonce,
                    envelopeSha256 = envelope.digestSha256(),
                    sessionPublicKey = sessionKey,
                    issuedElapsedRealtimeMs = 2,
                )
                val signedReady = SignedRestoreGuardianReadyReceipt(
                    ready,
                    checkNotNull(sessionAuthority.authenticate(GuardianReceiptType.READY, ready.canonicalBytes())),
                )
                val pin = RestoreGuardianSessionPin(
                    envelopeSha256 = envelope.digestSha256(),
                    sessionKeySha256 = sessionKey.sha256(),
                    readyReceiptSha256 = GuardianCanonicalCodec.sha256(ready.canonicalBytes()),
                    readyIssuedElapsedRealtimeMs = 2,
                    pinnedElapsedRealtimeMs = 3,
                )
                val loaded = GuardianArmStoreLoad.LoadedReady(
                    signedEnvelope,
                    sessionKey,
                    signedReady,
                    authority.available.signSessionPin(pin),
                )
                val armStore = MutableArmStore(loaded)
                val brokenRoot = temporaryFile("terminal")
                val store = GuardianTerminalReceiptStore(brokenRoot)
                val collector = GuardianProcessReceiptCollector(store)
                val engine = RestoreGuardianRunnerEngine(
                    receiptAuthentication = GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
                    receiptOutput = collector,
                    sessionKey = sessionKey,
                )
                return Fixture(
                    envelope,
                    expected,
                    authority.available,
                    sessionAuthority,
                    sessionKey,
                    armStore,
                    store,
                    collector,
                    engine,
                    brokenRoot,
                    GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(envelope),
                )
            }
        }
    }

    private class MutableArmStore(
        var loaded: GuardianArmStoreLoad.LoadedReady,
    ) : RestoreGuardianArmStore {
        var loadResult: GuardianArmStoreLoad = loaded

        override fun arm(signed: SignedRestoreGuardianEnvelope): GuardianArmStoreWrite = GuardianArmStoreWrite.Rejected
        override fun load(expected: RestoreGuardianExpectedIdentity): GuardianArmStoreLoad = loadResult
        override fun pinSessionKey(
            expected: RestoreGuardianExpectedIdentity,
            sessionKey: GuardianSessionPublicKey,
            signedReady: SignedRestoreGuardianReadyReceipt,
            nowElapsedRealtimeMs: Long,
        ): GuardianSessionPinWrite = GuardianSessionPinWrite.Rejected
        override fun presence(): GuardianArmStorePresence = GuardianArmStorePresence.PRESENT
        override fun clear(): Boolean = true
    }

    private companion object {
        fun attach(service: RestoreGuardianProcessService) {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .also { it.isAccessible = true }
                .invoke(service, RuntimeEnvironment.getApplication())
        }

        fun temporaryDirectory(name: String): File = kotlin.io.path.createTempDirectory("guardian-service-$name").toFile()

        fun temporaryFile(name: String): File = kotlin.io.path.createTempFile("guardian-service-$name").toFile()

        fun setPrivate(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> getPrivate(target: Any, name: String): T =
            target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target) as T
    }
}
