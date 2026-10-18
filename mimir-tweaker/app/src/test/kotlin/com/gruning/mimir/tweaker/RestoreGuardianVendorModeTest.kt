package com.gruning.mimir.tweaker

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Guardian's side of the vendor `performance_mode` (D3 of the 2026-10-13 vendor-mode design): the
 * signed restore entry, its canonical bytes and digest, the persistent cross-boot scope, the
 * three-way rule as the Guardian's own policy, the double readback and the engine's plan.
 *
 * Deliberately built from vendor and fan entries ONLY. A sysfs entry canonicalises its path through
 * the host filesystem, which refuses `/sys/...` on a Windows host, so a fixture holding one turns a
 * whole class red on this machine for a reason that has nothing to do with the rule under test.
 * The wire, the engine and the readback policy are axis-agnostic; the sysfs shape is covered by the
 * suites that already own it.
 */
class RestoreGuardianVendorModeTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signer = EcdsaP256Signer(keyPair.private)
    private val trusted = GuardianTestKeyAuthority.create(sha("certificate"), keyPair).available.envelopeVerifier()
    private val sessionPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)

    // ---- the entry ----------------------------------------------------------------------------------

    @Test
    fun `the entry carries both the origin and the applied mode through the typed slots`() {
        val entry = vendor(ordinal = 1, origin = 0, applied = 2)

        assertEquals(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID, entry.logicalId)
        assertEquals(RestoreGuardianStockState.PresentVendorMode(0), entry.expectedStock)
        assertEquals(listOf(2), entry.rendererAppliedIntermediates)
        assertTrue("the vendor mode outlives a reboot, so it is a persistent axis", entry.isPersistentGuardianAxis())
        assertEquals(entry, entry.copy())
    }

    @Test
    fun `a plan that targets the mode the player is already on still gets an entry`() {
        // Quiet at stock mode 0: the apply writes nothing and holds no record, but the snapshot still has
        // the node, so the envelope must still name it. origin == applied is therefore legal.
        val entry = vendor(origin = 0, applied = 0)
        assertEquals(0, entry.origin)
        assertEquals(0, entry.applied)
        assertEquals(entry, entry.copy())
        envelope(listOf(entry)) // constructs
    }

    @Test
    fun `an entry outside the known modes, the wrong id or a negative ordinal is refused`() {
        assertRejected { vendor(origin = 3, applied = 1) }
        assertRejected { vendor(origin = -1, applied = 1) }
        assertRejected { vendor(origin = 0, applied = 3) }
        assertRejected { vendor(origin = 0, applied = -1) }
        assertRejected { vendor(ordinal = -1) }
        assertRejected { RestoreGuardianVendorModePutEntry(1, "fan:mode", 0, 1) }
        assertRejected { RestoreGuardianStockState.PresentVendorMode(7).validate() }
    }

    // ---- canonical bytes, digest, signature ------------------------------------------------------------

    @Test
    fun `an envelope without the vendor entry keeps exactly the bytes it always had`() {
        val value = envelope(listOf(fan(0)))
        assertTrue(
            "a fan-only envelope must serialise byte-for-byte as before the vendor entry existed",
            referenceBytes(value, listOf { out -> out.fanPut(0, 4, listOf(1)) }).contentEquals(value.canonicalBytes()),
        )
    }

    @Test
    fun `the vendor entry has its own canonical record and the digest covers it`() {
        val value = envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))
        val expected = referenceBytes(
            value,
            listOf({ out -> out.vendorPut(1, 0, 2) }, { out -> out.fanPut(0, 4, listOf(1)) }),
        )
        assertTrue(expected.contentEquals(value.canonicalBytes()))

        val base = value.digestSha256()
        listOf(
            value.copy(restoreEntries = listOf(vendor(ordinal = 1, origin = 1, applied = 2), fan(0))),
            value.copy(restoreEntries = listOf(vendor(ordinal = 1, origin = 0, applied = 1), fan(0))),
            value.copy(restoreEntries = listOf(vendor(ordinal = 2, origin = 0, applied = 2), fan(0))),
            value.copy(restoreEntries = listOf(fan(0))),
        ).forEachIndexed { index, altered ->
            assertNotEquals("alteration $index escaped the digest", base, altered.digestSha256())
        }
    }

    @Test
    fun `a signature over an envelope does not survive a changed origin or applied mode`() {
        val value = envelope(listOf(vendor(origin = 0, applied = 2)))
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(signed, trusted))

        listOf(
            value.copy(restoreEntries = listOf(vendor(origin = 1, applied = 2))),
            value.copy(restoreEntries = listOf(vendor(origin = 0, applied = 1))),
        ).forEach { altered ->
            assertFalse(RestoreGuardianCrypto.verifyEnvelope(signed.copy(envelope = altered), trusted))
        }
    }

    @Test
    fun `restore order accepts the vendor entry between compute and fan, and the signed copy is pinned`() {
        // apply: fan(0), vendor(1); restore: vendor, fan -- strictly the reverse.
        envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))
        assertRejected { envelope(listOf(fan(0), vendor(ordinal = 1, origin = 0, applied = 2))) }
        assertRejected { envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), vendor(ordinal = 0, origin = 0, applied = 2))) }

        val signed = RestoreGuardianCrypto.signEnvelope(envelope(listOf(vendor(origin = 0, applied = 2))), signer)
        assertEquals(vendor(origin = 0, applied = 2), signed.envelope.restoreEntries.single())
        assertTrue(RestoreGuardianCrypto.verifyEnvelope(signed, trusted))
    }

    // ---- the durable arm record ---------------------------------------------------------------------------

    @Test
    fun `the durable arm record round trips a vendor entry and fails closed on any tamper`() {
        // Not in the hand-over list, and it compiles without a change: the arm store decodes the durable
        // record with a tag switch of its own. Without a "vendor_put" arm a signed vendor envelope would
        // load Corrupt, the arm would fail, and every vendor-mode apply would be refused at runtime.
        val value = envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))
        val signed = RestoreGuardianCrypto.signEnvelope(value, signer)
        val expected = RestoreGuardianExpectedIdentity(
            helperBuildIdentity = value.helperBuildIdentity,
            tweakerCertificateSha256 = value.tweakerCertificateSha256,
            hardwareFingerprint = value.hardwareFingerprint,
            deviceProfile = value.deviceProfile,
            bootIdentity = value.bootIdentity,
            generation = value.generation,
            requestId = value.requestId,
            transactionId = value.transactionId,
            taskNonce = value.taskNonce,
            topologySha256 = value.topologySha256,
            snapshotSha256 = value.snapshotSha256,
            hardDeadlineElapsedRealtimeMs = value.hardDeadlineElapsedRealtimeMs,
        )

        val bytes = GuardianArmRecordCodec.encodeStaged(signed, trusted)
        val loaded = GuardianArmRecordCodec.decodeAndVerify(bytes, expected, trusted)

        assertTrue(loaded.toString(), loaded is GuardianArmStoreLoad.LoadedStaged)
        assertEquals(value, (loaded as GuardianArmStoreLoad.LoadedStaged).signed.envelope)
        assertEquals(value.digestSha256(), loaded.signed.envelope.digestSha256())
        // Every strict prefix is corrupt, and so is the envelope with its origin or applied byte flipped.
        for (length in 0 until bytes.size) {
            assertEquals("truncation=$length", GuardianArmStoreLoad.Corrupt, GuardianArmRecordCodec.decodeAndVerify(bytes.copyOf(length), expected, trusted))
        }
        val tag = "present_vendor".toByteArray()
        val at = bytes.indexOfSubsequence(tag)
        assertTrue(at > 0)
        val originByte = at + tag.size + 3 // the low byte of the big-endian origin int
        assertEquals(0, bytes[originByte].toInt())
        assertEquals(
            GuardianArmStoreLoad.Corrupt,
            GuardianArmRecordCodec.decodeAndVerify(bytes.copyOf().also { it[originByte] = 1 }, expected, trusted),
        )
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int =
        indices.firstOrNull { start ->
            start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
        } ?: -1

    // ---- persistence across a reboot -----------------------------------------------------------------

    @Test
    fun `the cross boot scope keeps the vendor entry and attests to exactly the persistent set`() {
        val value = envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))

        assertEquals(value.restoreEntries, GuardianTerminalRestoreScope.SAME_BOOT_FULL.eligibleRestoreEntries(value.restoreEntries))
        assertEquals(
            value.restoreEntries,
            GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK.eligibleRestoreEntries(value.restoreEntries),
        )
        // The vendor entry ALONE is a settleable cross-boot scope (a compute-only plan has none).
        val vendorOnly = envelope(listOf(vendor(origin = 0, applied = 2)))
        assertNull(
            RestoreGuardianCrypto.terminalScopeRefusal(
                vendorOnly,
                "boot.2",
                GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK,
                GuardianRestoreEntrySetDigest.of(vendorOnly.restoreEntries),
            ),
        )
    }

    // ---- the three-way rule as the Guardian's own policy -------------------------------------------------

    @Test
    fun `decide applies the three way rule to the entry's own origin and applied`() {
        val entry = vendor(origin = 0, applied = 2)
        fun decide(live: Int?) = GuardianTypedRestorePolicy.decide(entry, GuardianObservedState.VendorMode(live))

        assertEquals("Mimir's value still live: write the origin", GuardianRestoreDecision.WRITE_REQUIRED, decide(2))
        assertEquals("already restored", GuardianRestoreDecision.ALREADY_STOCK, decide(0))
        assertEquals("the player moved it: their choice stands", GuardianRestoreDecision.PLAYER_CHANGED, decide(1))
        assertEquals("unreadable is not a verdict", GuardianRestoreDecision.INVALID_OBSERVATION, decide(null))
        // A fan read can never satisfy a vendor entry.
        assertEquals(
            GuardianRestoreDecision.INVALID_OBSERVATION,
            GuardianTypedRestorePolicy.decide(entry, GuardianObservedState.FanMode(0)),
        )

        assertTrue(GuardianTypedRestorePolicy.exactReadback(entry, GuardianObservedState.VendorMode(0)))
        assertFalse(GuardianTypedRestorePolicy.exactReadback(entry, GuardianObservedState.VendorMode(1)))
        // A restore LANDED when the origin is back, or when the player's own choice was left alone;
        // it did not land while Mimir's value is live or the key is unreadable.
        assertTrue(GuardianTypedRestorePolicy.restoreLanded(entry, GuardianObservedState.VendorMode(0)))
        assertTrue(GuardianTypedRestorePolicy.restoreLanded(entry, GuardianObservedState.VendorMode(1)))
        assertFalse(GuardianTypedRestorePolicy.restoreLanded(entry, GuardianObservedState.VendorMode(2)))
        assertFalse(GuardianTypedRestorePolicy.restoreLanded(entry, GuardianObservedState.VendorMode(null)))
    }

    @Test
    fun `an entry whose applied mode is its origin can never ask for a write`() {
        val entry = vendor(origin = 0, applied = 0)
        fun decide(live: Int?) = GuardianTypedRestorePolicy.decide(entry, GuardianObservedState.VendorMode(live))

        assertEquals(GuardianRestoreDecision.ALREADY_STOCK, decide(0))
        assertEquals(GuardianRestoreDecision.PLAYER_CHANGED, decide(2))
        assertTrue("the origin is checked first, so this is a landed restore", GuardianTypedRestorePolicy.restoreLanded(entry, GuardianObservedState.VendorMode(0)))
    }

    // ---- the double readback ------------------------------------------------------------------------------

    private fun readback(vendor: Int?, fanMode: Int = 4, at: Long = 1_000L) = GuardianStockReadback(
        mapOf(
            HardwareSnapshot.VENDOR_MODE_LOGICAL_ID to GuardianObservedState.VendorMode(vendor),
            HardwareSnapshot.FAN_LOGICAL_ID to GuardianObservedState.FanMode(fanMode),
        ),
        at,
    )

    private val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
    private val entries = listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0))

    private fun decision(first: GuardianStockReadback, second: GuardianStockReadback) =
        GuardianDoubleStockReadbackPolicy.evaluate(entries, first, second)

    @Test
    fun `stock confirms when the origin is back`() {
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            decision(readback(0), readback(0, at = 1_000L + gap)),
        )
        assertEquals(
            "the clean case says nothing more",
            GuardianDoubleStockDecision.STOCK_CONFIRMED.name,
            GuardianDoubleStockReadbackPolicy.describe(entries, readback(0), readback(0, at = 1_000L + gap)),
        )
    }

    @Test
    fun `stock is never confirmed while Mimir's own mode is still live or the key cannot be read`() {
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            decision(readback(2), readback(2, at = 1_000L + gap)),
        )
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            decision(readback(null), readback(0, at = 1_000L + gap)),
        )
        assertEquals(
            GuardianDoubleStockDecision.SECOND_READBACK_NOT_STOCK,
            decision(readback(0), readback(2, at = 1_000L + gap)),
        )
        // And a vendor entry the readback never mentioned is not vouched for either.
        val withoutVendor = GuardianStockReadback(mapOf(HardwareSnapshot.FAN_LOGICAL_ID to GuardianObservedState.FanMode(4)), 1_000L)
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            decision(withoutVendor, withoutVendor.copy(observedElapsedRealtimeMs = 1_000L + gap)),
        )

        val line = GuardianDoubleStockReadbackPolicy.describe(entries, readback(2), readback(2, at = 1_000L + gap))
        assertTrue(line, line.contains("${HardwareSnapshot.VENDOR_MODE_LOGICAL_ID}=vmode2!=vmode0"))
        assertTrue(
            GuardianDoubleStockReadbackPolicy.describe(entries, readback(null), readback(null, at = 1_000L + gap))
                .contains("${HardwareSnapshot.VENDOR_MODE_LOGICAL_ID}=vmodenull!=vmode0"),
        )
    }

    @Test
    fun `a mode the player moved does not veto and is named, not hidden`() {
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            decision(readback(1), readback(1, at = 1_000L + gap)),
        )
        val line = GuardianDoubleStockReadbackPolicy.describe(entries, readback(1), readback(1, at = 1_000L + gap))
        assertTrue(line, line.startsWith(GuardianDoubleStockDecision.STOCK_CONFIRMED.name))
        assertTrue(
            "the confirmation must say the player's mode was left alone: $line",
            line.contains("unheld=${HardwareSnapshot.VENDOR_MODE_LOGICAL_ID}=vmode1!=vmode0"),
        )
    }

    @Test
    fun `a wrong fan still refuses beside a restored vendor mode`() {
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            decision(readback(0, fanMode = 6), readback(0, fanMode = 6, at = 1_000L + gap)),
        )
    }

    // ---- the engine -----------------------------------------------------------------------------------------

    private data class Rig(val engine: RestoreGuardianRunnerEngine, val receipts: MutableList<GuardianAuthenticatedReceipt>)

    private fun rig(): Rig {
        val receipts = mutableListOf<GuardianAuthenticatedReceipt>()
        return Rig(
            RestoreGuardianRunnerEngine(
                GuardianReceiptAuthenticationStrategy { _, _ -> byteArrayOf(1) },
                GuardianReceiptOutputPort { receipts += it; GuardianReceiptOutputDisposition.CONFIRMED },
                sessionKey,
            ),
            receipts,
        )
    }

    private fun restoring(value: RestoreGuardianEnvelope): Rig = rig().also {
        it.engine.stage(GuardianEnvelopeVerificationOutcome.Verified(value), 1, "boot")
        it.engine.admitReady(2)
        it.engine.beginWatching(3)
    }

    private fun sameBootEvidence(value: RestoreGuardianEnvelope): GuardianRestoreFenceEvidence {
        val identity = GuardianRunnerFencePolicy.identityFromAuthenticatedEnvelope(value)
        return GuardianRestoreFenceEvidence.SameBoot(FenceDiskState(true, identity, identity, true))
    }

    private fun observed(vendor: Int?, fanMode: Int = 4): Map<String, GuardianObservedState> = mapOf(
        HardwareSnapshot.VENDOR_MODE_LOGICAL_ID to GuardianObservedState.VendorMode(vendor),
        HardwareSnapshot.FAN_LOGICAL_ID to GuardianObservedState.FanMode(fanMode),
    )

    @Test
    fun `the engine plans a vendor write only while Mimir's own mode is live and restores it before the fan`() {
        val value = envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))

        val crashed = restoring(value).also { it.engine.ownerCancelled() }
        val intents = crashed.engine.planRestore("boot", sameBootEvidence(value), observed(vendor = 2, fanMode = 1)).restoreIntents
        assertEquals(
            listOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID, HardwareSnapshot.FAN_LOGICAL_ID),
            intents.map { it.entry.logicalId },
        )
        assertEquals(vendor(ordinal = 1, origin = 0, applied = 2), intents.first().entry)

        val alreadyRestored = restoring(value).also { it.engine.ownerCancelled() }
        assertTrue(
            alreadyRestored.engine.planRestore("boot", sameBootEvidence(value), observed(vendor = 0)).restoreIntents.isEmpty(),
        )

        val playerMoved = restoring(value).also { it.engine.ownerCancelled() }
        assertTrue(
            "the player's mode is not Mimir's to touch",
            playerMoved.engine.planRestore("boot", sameBootEvidence(value), observed(vendor = 1)).restoreIntents.isEmpty(),
        )

        val unreadable = restoring(value).also { it.engine.ownerCancelled() }
        assertTrue(unreadable.engine.planRestore("boot", sameBootEvidence(value), observed(vendor = null)).restoreIntents.isEmpty())
    }

    @Test
    fun `after a reboot the cross boot replay restores the origin and attests to the vendor entry`() {
        val value = envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))
        val f = restoring(value)
        f.engine.ownerDecision(GuardianOwnerDecision.BOOT_MISMATCH)
        val proof = GuardianRestoreFenceEvidence.DifferentBootPersistent(
            "newboot",
            GuardianPersistentRestoreFenceProof(true, true, true),
        )

        // The reboot left Mimir's mode on the device: nothing else would ever put it back. The fan
        // reads stock, but SystemUI couples it to the mode on this boot like any other, so the mode's
        // write is followed by the fan's final put (2026-10-13), eligible here because both persist.
        val intents = f.engine.planRestore("newboot", proof, observed(vendor = 2, fanMode = 4)).restoreIntents
        assertEquals(
            listOf(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID, HardwareSnapshot.FAN_LOGICAL_ID),
            intents.map { it.entry.logicalId },
        )
        assertEquals(listOf(false, true), intents.map { it.afterVendorMode })

        val first = GuardianStockReadback(observed(vendor = 0), 100)
        val second = first.copy(observedElapsedRealtimeMs = 100 + gap)
        assertEquals(GuardianRunnerLifecycleState.STOCK_CONFIRMED, f.engine.confirmStock(first, second).state)
        assertEquals(GuardianRunnerLifecycleState.EXITED, f.engine.exit(300).state)

        val stock = f.receipts.first { it.type == GuardianReceiptType.STOCK_CONFIRMED }
        val parsed = checkNotNull(GuardianTerminalWireCodec.signedStock(stock.canonicalPayload, stock.authentication))
        assertEquals(GuardianTerminalRestoreScope.CROSS_BOOT_PERSISTENT_REPLAY_FULL_STOCK, parsed.receipt.restoreScope)
        assertEquals("newboot", parsed.receipt.recoveryBootIdentity)
        assertEquals(
            GuardianRestoreEntrySetDigest.of(value.restoreEntries),
            parsed.receipt.verifiedStockEntrySetSha256,
        )
        assertNull(
            "the verifier's own recomputation of the scope digest agrees with the producer's",
            RestoreGuardianCrypto.terminalScopeRefusal(
                value,
                parsed.receipt.recoveryBootIdentity,
                parsed.receipt.restoreScope,
                parsed.receipt.verifiedStockEntrySetSha256,
            ),
        )
    }

    @Test
    fun `a cross boot replay will not settle on Mimir's mode`() {
        val value = envelope(listOf(vendor(origin = 0, applied = 2)))
        val f = restoring(value)
        f.engine.ownerDecision(GuardianOwnerDecision.BOOT_MISMATCH)
        val proof = GuardianRestoreFenceEvidence.DifferentBootPersistent(
            "newboot",
            GuardianPersistentRestoreFenceProof(true, true, true),
        )
        f.engine.planRestore("newboot", proof, observed(vendor = 2))
        val stuck = GuardianStockReadback(observed(vendor = 2), 100)
        assertNotEquals(
            GuardianRunnerLifecycleState.STOCK_CONFIRMED,
            f.engine.confirmStock(stuck, stuck.copy(observedElapsedRealtimeMs = 100 + gap)).state,
        )
        assertTrue(f.receipts.none { it.type == GuardianReceiptType.STOCK_CONFIRMED })
    }

    @Test
    fun `the engine's deep copy of a vendor entry is an equal value`() {
        val value = envelope(listOf(vendor(ordinal = 1, origin = 0, applied = 2), fan(0)))
        assertEquals(value, value.deepCopy())
        assertEquals(vendor(ordinal = 1, origin = 0, applied = 2), vendor(ordinal = 1, origin = 0, applied = 2).deepCopy())
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private fun vendor(ordinal: Int = 0, origin: Int = 0, applied: Int = 2) =
        RestoreGuardianVendorModePutEntry(applyOrdinal = ordinal, origin = origin, applied = applied)

    private fun fan(ordinal: Int) = RestoreGuardianFanPutEntry(
        applyOrdinal = ordinal,
        expectedStock = RestoreGuardianStockState.PresentFanMode(4),
        rendererAppliedIntermediates = listOf(1),
    )

    private fun envelope(entries: List<RestoreGuardianRestoreEntry>) = RestoreGuardianEnvelope(
        helperBuildIdentity = "helper.1",
        tweakerCertificateSha256 = sha("certificate"),
        hardwareFingerprint = "hardware.1",
        deviceProfile = "profile.1",
        bootIdentity = "boot",
        generation = 7,
        requestId = "request.1",
        transactionId = "transaction.1",
        taskNonce = "a".repeat(32),
        topologySha256 = sha("topology"),
        snapshotSha256 = sha("snapshot"),
        planSha256 = sha("plan"),
        hardDeadlineElapsedRealtimeMs = 10_000,
        restoreEntries = entries,
    )

    /** The documented length-prefixed layout, written independently of [GuardianCanonicalCodec]. */
    private fun referenceBytes(value: RestoreGuardianEnvelope, entries: List<(DataOutputStream) -> Unit>): ByteArray {
        val sink = ByteArrayOutputStream()
        DataOutputStream(sink).use { out ->
            out.str("MIMIR_RESTORE_GUARDIAN_ENVELOPE")
            out.writeInt(value.schemaVersion)
            out.str(value.helperBuildIdentity)
            out.str(value.tweakerCertificateSha256)
            out.str(value.hardwareFingerprint)
            out.str(value.deviceProfile)
            out.str(value.bootIdentity)
            out.writeLong(value.generation)
            out.str(value.requestId)
            out.str(value.transactionId)
            out.str(value.taskNonce)
            out.str(value.topologySha256)
            out.str(value.snapshotSha256)
            out.str(value.planSha256)
            out.writeLong(value.hardDeadlineElapsedRealtimeMs)
            out.writeInt(entries.size)
            entries.forEach { it(out) }
        }
        return sink.toByteArray()
    }

    private fun DataOutputStream.str(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.fanPut(ordinal: Int, stock: Int, intermediates: List<Int>) {
        str("fan_put"); writeInt(ordinal); str(HardwareSnapshot.FAN_LOGICAL_ID)
        writeInt(intermediates.size); intermediates.forEach(::writeInt)
        str("present_fan"); writeInt(stock)
    }

    private fun DataOutputStream.vendorPut(ordinal: Int, origin: Int, applied: Int) {
        str("vendor_put"); writeInt(ordinal); str(HardwareSnapshot.VENDOR_MODE_LOGICAL_ID)
        writeInt(1); writeInt(applied)
        str("present_vendor"); writeInt(origin)
    }

    private fun sha(value: String) = GuardianCanonicalCodec.sha256(value.toByteArray())

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
