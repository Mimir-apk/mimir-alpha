package com.gruning.mimir.tweaker

import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PServerClientTest {
    @Test
    fun `executeRaw preserves exact transport bytes without string normalization`() {
        val source = byteArrayOf(0x20, 'n'.code.toByte(), 'u'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 0x0a, 0x00)
        val client = PServerClient(
            transport = PServerBinderTransport { _, captureOutput ->
                assertTrue(captureOutput)
                PServerTransactResult(
                    accepted = true,
                    output = "ignored",
                    bytes = source,
                )
            },
        )

        val reply = client.executeRaw("printf raw").getOrThrow()

        assertTrue(reply.accepted)
        assertTrue(source.contentEquals(reply.bytes))
        source[0] = 0x7f
        assertEquals(0x20, reply.bytes!![0].toInt())
    }

    @Test
    fun `executeRaw preserves false acceptance and absent bytes distinctly`() {
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(
                    accepted = false,
                    output = "null",
                    bytes = null,
                )
            },
        )

        val reply = client.executeRaw("false").getOrThrow()

        assertEquals(false, reply.accepted)
        assertEquals(null, reply.bytes)
    }

    @Test
    fun `executeRaw does not synthesize raw bytes from legacy string output`() {
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(accepted = true, output = "  null\\n")
            },
        )

        val reply = client.executeRaw("printf legacy").getOrThrow()

        assertEquals(null, reply.bytes)
    }

    @Test
    fun `executeRaw propagates transport failure without replay`() {
        var calls = 0
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                calls += 1
                PServerTransactResult(accepted = false, error = IllegalStateException("binder dead"))
            },
        )

        val result = client.executeRaw("echo once")

        assertTrue(result.isFailure)
        assertEquals("binder dead", result.exceptionOrNull()?.message)
        assertEquals(1, calls)
    }

    @Test
    fun `checked parse requires anchored nonce bound one line frame and zero exit`() {
        val nonce = "n1_abcdef12"
        val confirmed = PServerClient.parseExitMarker(
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:2:ok\n",
            nonce,
        )
        assertEquals(CheckedCommandStatus.CONFIRMED, confirmed.status)
        assertEquals(0, confirmed.exitCode)

        val failed = PServerClient.parseExitMarker(
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:7:0:",
            nonce,
        )
        assertEquals(CheckedCommandStatus.FAILED, failed.status)

        val missing = PServerClient.parseExitMarker("accepted without marker", nonce)
        assertEquals(CheckedCommandStatus.AMBIGUOUS, missing.status)

        val wrongNonce = PServerClient.parseExitMarker(
            "${PServerClient.EXIT_MARKER_PREFIX}other:0:0:",
            nonce,
        )
        assertEquals(CheckedCommandStatus.AMBIGUOUS, wrongNonce.status)
        assertTrue(missing.status != CheckedCommandStatus.CONFIRMED)
    }

    @Test
    fun `checked parse rejects hostile or length mismatched frames`() {
        val nonce = "n1_abcdef12"
        listOf(
            "prefix${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:2:ok",
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:1:ok",
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:3:ok",
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:2:o\nk",
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:2:oé",
            "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:2:ok${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:",
        ).forEach { output ->
            assertEquals(CheckedCommandStatus.AMBIGUOUS, PServerClient.parseExitMarker(output, nonce).status)
        }
    }

    @Test
    fun `a write never times out and a read does -- the same slow service, two different answers`() {
        val client = PServerClient(
            transport = PServerBinderTransport { command, captureOutput ->
                Thread.sleep(400)
                if (command.contains(PServerClient.EXIT_MARKER_PREFIX)) {
                    val prefix = PServerClient.EXIT_MARKER_PREFIX
                    val start = command.indexOf(prefix) + prefix.length
                    val nonce = command.substring(start, command.indexOf(':', start))
                    PServerTransactResult(accepted = true, output = "$prefix$nonce:0:0:")
                } else {
                    PServerTransactResult(accepted = true, output = if (captureOutput) "late" else null)
                }
            },
            readDeadlineMs = 50,
        )

        val read = client.execute("cat /sys/x", captureOutput = true)
        val write = client.executeChecked("echo 1 > /sys/x", nonce = "n1_abcdef12")
        val plainWrite = client.execute("settings put system x 1", captureOutput = false)

        assertTrue("the read lapsed at its deadline", read.exceptionOrNull() is PServerTransactTimeoutException)
        assertEquals("the write waited for the service", CheckedCommandStatus.CONFIRMED, write.status)
        assertTrue("the non-capturing write waited too", plainWrite.isSuccess)
    }

    @Test
    fun `executeChecked transport false is AMBIGUOUS`() {
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(accepted = false)
            },
        )
        val result = client.executeChecked("echo 1", nonce = "n1_abcdef12")
        assertEquals(CheckedCommandStatus.AMBIGUOUS, result.status)
        assertTrue(result.detail.contains("transact returned false"))
    }

    @Test
    fun `executeChecked transport exception is AMBIGUOUS`() {
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(accepted = false, error = IllegalStateException("binder dead"))
            },
        )
        val result = client.executeChecked("echo 1", nonce = "n1_abcdef12")
        assertEquals(CheckedCommandStatus.AMBIGUOUS, result.status)
        assertTrue(result.detail.contains("binder dead"))
    }

    @Test
    fun `executeChecked missing output is AMBIGUOUS`() {
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(accepted = true, output = null)
            },
        )
        val result = client.executeChecked("echo 1", nonce = "n1_abcdef12")
        assertEquals(CheckedCommandStatus.AMBIGUOUS, result.status)
        assertTrue(result.detail.contains("Missing PServer output"))
    }

    @Test
    fun `executeChecked wrong nonce is AMBIGUOUS`() {
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(
                    accepted = true,
                    output = "${PServerClient.EXIT_MARKER_PREFIX}other:0:0:",
                )
            },
        )
        val result = client.executeChecked("echo 1", nonce = "n1_abcdef12")
        assertEquals(CheckedCommandStatus.AMBIGUOUS, result.status)
    }

    @Test
    fun `executeChecked nonzero exit is FAILED`() {
        val nonce = "n1_abcdef12"
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                PServerTransactResult(
                    accepted = true,
                    output = "${PServerClient.EXIT_MARKER_PREFIX}$nonce:9:0:",
                )
            },
        )
        val result = client.executeChecked("echo 1", nonce = nonce)
        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals(9, result.exitCode)
    }

    @Test
    fun `executeChecked confirmed zero exit`() {
        val nonce = "n1_abcdef12"
        val client = PServerClient(
            transport = PServerBinderTransport { command, _ ->
                assertTrue(command.contains("echo 1"))
                assertTrue(command.contains(PServerClient.EXIT_MARKER_PREFIX))
                assertTrue(command.contains(":%s:%s"))
                PServerTransactResult(
                    accepted = true,
                    output = "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:2:ok",
                )
            },
        )
        val result = client.executeChecked("echo 1", nonce = nonce)
        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `executeChecked rejects an oversized wrapped command before transport`() {
        var calls = 0
        val client = PServerClient(
            transport = PServerBinderTransport { _, _ ->
                calls += 1
                PServerTransactResult(accepted = true)
            },
        )
        val result = client.executeChecked("x".repeat(PServerClient.MAX_CHECKED_COMMAND_BYTES), nonce = "n1_abcdef12")
        assertEquals(CheckedCommandStatus.FAILED, result.status)
        assertEquals(0, calls)
    }

    @Test
    fun `checked GPU max-frequency write fits the envelope at maximal generated counter`() {
        assertTrue(PServerClient.nextNonce().matches(Regex("n[0-9a-z]{1,13}_[0-9a-f]{8}")))
        val nonce = "n${ULong.MAX_VALUE.toString(36)}_abcdef12"
        val command = "echo 1100000000 > /sys/devices/platform/soc/3d00000.qcom,kgsl-3d0/devfreq/3d00000.qcom,kgsl-3d0/max_freq"
        var sent: String? = null
        val client = PServerClient(
            transport = PServerBinderTransport { wrapped, _ ->
                sent = wrapped
                PServerTransactResult(
                    accepted = true,
                    output = "${PServerClient.EXIT_MARKER_PREFIX}$nonce:0:0:",
                )
            },
        )

        val result = client.executeChecked(command, nonce = nonce)

        assertEquals(CheckedCommandStatus.CONFIRMED, result.status)
        assertTrue("the maximum-counter GPU write must be sent", sent != null)
        assertTrue(
            "the checked envelope remains capped at ${PServerClient.MAX_CHECKED_COMMAND_BYTES} bytes",
            sent!!.toByteArray(Charsets.UTF_8).size <= PServerClient.MAX_CHECKED_COMMAND_BYTES,
        )
    }

    @Test
    fun `executeChecked resolver unavailable then live without auto-replay`() {
        var resolves = 0
        val alive = AtomicBoolean(false)
        val client = PServerClient(
            binderResolver = PServerBinderResolver {
                resolves += 1
                when (resolves) {
                    1 -> null
                    2 -> {
                        alive.set(false)
                        Binder()
                    }
                    else -> {
                        alive.set(true)
                        Binder()
                    }
                }
            },
            binderAlive = { alive.get() },
            binderAttestor = PServerBinderAttestor { true },
        )
        val unavailable = client.executeChecked("echo 1", nonce = "n1_abcdef12")
        assertEquals(CheckedCommandStatus.FAILED, unavailable.status)
        assertEquals(1, resolves)
        assertEquals(null, client.acquireBinderForTest())
        assertEquals(2, resolves)
        val live = client.acquireBinderForTest()
        assertTrue(live != null)
        assertEquals(3, resolves)
        client.invalidateBinderForTest()
        assertTrue(client.acquireBinderForTest() != null)
        assertEquals(4, resolves)
    }

    @Test
    fun `service attestation rejects shell identity and every mismatched structural field`() {
        // Odin 3, device test device, digest re-measured 2026-08-22 via
        // `sha256sum /system/bin/pservice`. Kept as a realistic value, but note it is no longer
        // compared against anything: the gate is structure, not identity.
        val trusted = PServerServiceAttestation(
            protocolMarker = DefaultPServerBinderAttestor.PROTOCOL_MARKER,
            uid = "0",
            gid = "0",
            selinuxDomain = "u:r:pservice:s0",
            executablePath = "/system/bin/pservice",
            executableSha256 = "e1a4168f6045ce2c89a49bdc82cdcb8e2c9f6816308235a1c1c43d55bf5ed443",
        )

        assertTrue(trusted.isTrusted())
        assertTrue(!trusted.copy(uid = "2000").isTrusted())
        assertTrue(!trusted.copy(gid = "2000").isTrusted())
        assertTrue(!trusted.copy(selinuxDomain = "u:r:shell:s0").isTrusted())
        assertTrue(!trusted.copy(executablePath = "/system/bin/sh").isTrusted())
        assertTrue(!trusted.copy(protocolMarker = "spoofed").isTrusted())
        assertTrue(!trusted.copy(protocolMarker = null).isTrusted())

        // The digest must still be PRESENT and digest-shaped -- a service that cannot answer
        // `sha256sum` at all, or answers with a truncated / non-hex value, is refused.
        assertTrue(!trusted.copy(executableSha256 = null).isTrusted())
        assertTrue(!trusted.copy(executableSha256 = "").isTrusted())
        assertTrue(!trusted.copy(executableSha256 = "e1a4168f").isTrusted())
        assertTrue(!trusted.copy(executableSha256 = "z".repeat(64)).isTrusted())
        assertTrue(!trusted.copy(executableSha256 = "e1a4168f".repeat(9)).isTrusted())
    }

    @Test
    fun `both grounded handhelds attest with the values measured on hardware`() {
        // Not invented fixtures. Every field below was read off the physical device on
        // 2026-08-22 through the SAME six probes this attestor issues -- `/system/bin/id -u`,
        // `-g`, `cat /proc/self/attr/current`, `readlink /proc/$PPID/exe`,
        // `sha256sum /proc/$PPID/exe` -- and cross-checked against `sha256sum
        // /system/bin/pservice` over adb.
        //
        // These two cases are also the monotonicity proof for the removal. Both devices were IN
        // the deleted allowlist and attested true under it. The new predicate is the old one with
        // the digest-equality conjunct replaced by well-formedness, which equality already
        // implied, and no conjunct added -- so old => new, and neither device can regress.
        val odin3 = PServerServiceAttestation(
            protocolMarker = DefaultPServerBinderAttestor.PROTOCOL_MARKER,
            uid = "0",
            gid = "0",
            selinuxDomain = "u:r:pservice:s0",
            executablePath = "/system/bin/pservice",
            // Device test device, fingerprint
            // qti/sun/sun:15/AQ3A.250728.001/eng.Odin3.20260616.191954:user/dev-keys, SoC CQ8725S.
            executableSha256 = "e1a4168f6045ce2c89a49bdc82cdcb8e2c9f6816308235a1c1c43d55bf5ed443",
        )
        val nova = odin3.copy(
            // Device test unit, fingerprint
            // qti/kalama/kalama:13/TKQ1.231222.001/eng.RPN.20260722.081626:user/release-keys,
            // SoC QCS8550. This is the RC-14 device.
            executableSha256 = "8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4",
        )

        assertTrue(odin3.isTrusted())
        assertTrue(nova.isTrusted())
    }

    @Test
    fun `any well-formed digest is accepted -- the firmware allowlist is gone`() {
        // gate the SoC and the contract, never the device. The removed
        // `expectedPServerExecutableSha256` table refused every unlisted firmware outright, so
        // this is the assertion that would have failed before the change and is the whole point
        // of it: an unknown binary digest, on an unknown device, is admitted on structure alone.
        val base = PServerServiceAttestation(
            protocolMarker = DefaultPServerBinderAttestor.PROTOCOL_MARKER,
            uid = "0",
            gid = "0",
            selinuxDomain = "u:r:pservice:s0",
            executablePath = "/system/bin/pservice",
            executableSha256 = "e1a4168f6045ce2c89a49bdc82cdcb8e2c9f6816308235a1c1c43d55bf5ed443",
        )

        listOf(
            // Retroid Pocket Nova, measured on device test unit -- previously needed its own row.
            "8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4",
            // RP5.
            "f44773c8dbdc03904dc604d94c2a6837562d14c808eb6b52ff3b644b19817960",
            // An unknown handheld: the illustrative "Konkr Pocket Fir" case.
            "0".repeat(64),
            "abcdef0123456789".repeat(4),
        ).forEach { digest ->
            assertTrue(base.copy(executableSha256 = digest).isTrusted())
        }

        // Uppercase is normalized rather than refused -- `sha256sum` output casing is not a
        // trust signal, and refusing on it would be an accidental device pin.
        assertTrue(base.copy(executableSha256 = "A".repeat(8) + "b".repeat(56)).isTrusted())
    }

    @Test
    fun `unattested binder is never cached as available`() {
        var attestations = 0
        val binder = Binder()
        val client = PServerClient(
            binderResolver = PServerBinderResolver { binder },
            binderAlive = { true },
            binderAttestor = PServerBinderAttestor {
                attestations += 1
                false
            },
        )

        assertTrue(!client.isAvailable)
        assertEquals(null, client.acquireBinderForTest())
        assertEquals(2, attestations)
    }
}
