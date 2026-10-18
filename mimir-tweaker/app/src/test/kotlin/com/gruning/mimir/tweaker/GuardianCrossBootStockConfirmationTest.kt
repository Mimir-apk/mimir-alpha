package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A CPU/GPU-only plan that survives a REBOOT can never reach terminal settlement unless the
 * platform happens to boot the CPU/GPU ceilings back to the exact values captured as Stock.
 *
 * Traced from the Nova, 2026-08-30, which held at `availability=UNAVAILABLE` through three
 * daemon installs and every force-stop, reporting `STOCK_UNCONFIRMED`:
 *
 *  - `RestoreGuardianRunnerEngine:133` — a changed `bootIdentity` sets `persistentOnly = true`
 *  - `:175` — restore then writes only `entries.filter { it.isPersistentGuardianAxis() }`
 *  - `RestoreGuardianCrypto:132-133` — **only fan entries are persistent.** CPU and GPU are not.
 *    So for a CPU/GPU-only plan the cross-boot restore is a zero-write operation, which the code
 *    states outright at `:223-226`.
 *  - `:222` — `confirmStock` then evaluates the **complete** captured set, unfiltered, and
 *    `GuardianDoubleStockReadbackPolicy.evaluate` requires an exact readback of every entry.
 *
 * Nothing writes the CPU/GPU axes, and everything must still match them. Settlement is therefore
 * decided by the platform's boot defaults rather than by anything the Guardian did — and when they
 * differ, the record is unsettleable for the life of the device, which is exactly the observed
 * symptom.
 *
 * These tests do not assert that behaviour is *wrong*. The Guardian refusing to certify a stock it
 * cannot observe is the safety property working. They pin the **reachability gap**: that the same
 * inputs which produce a zero-write restore also demand a full-set match, so a mismatch has no
 * route to resolution.
 */
class GuardianCrossBootStockConfirmationTest {

    private val sessionPair = java.security.KeyPairGenerator.getInstance("EC")
        .apply { initialize(256) }.generateKeyPair()
    private val sessionKey = GuardianSessionPublicKey.fromPublicKey(sessionPair.public)

    /** A CPU ceiling — a sysfs axis, and per `isPersistentGuardianAxis` NOT persistent. */
    private fun cpuEntry() = RestoreGuardianSysfsEntry(
        2,
        "cpu:3",
        "/sys/devices/system/cpu/cpufreq/policy3/scaling_max_freq",
        RestoreGuardianStockState.PresentSysfs(2_707_200, "644", 0, 0),
        mutableListOf(2_400_000),
    )

    @Test
    fun `only fan axes are persistent, so a CPU-GPU plan has nothing to replay across a boot`() {
        // The load-bearing source fact, pinned so a future edit that reclassifies an axis has to
        // come past this test.
        assertTrue(
            "fan put must be persistent",
            RestoreGuardianFanPutEntry(
                applyOrdinal = 1,
                expectedStock = RestoreGuardianStockState.PresentFanMode(0),
                rendererAppliedIntermediates = mutableListOf(1),
            )
                .isPersistentGuardianAxis(),
        )
        assertTrue(
            "a CPU sysfs ceiling must NOT be persistent — this is what empties the cross-boot restore",
            !cpuEntry().isPersistentGuardianAxis(),
        )
    }

    @Test
    fun `evaluate demands every captured entry, so an unwritten axis that boots differently can never confirm`() {
        val entries = listOf(cpuEntry())

        // After a reboot nobody wrote this axis: the platform brought it up at its own default,
        // which here differs from the value captured as Stock.
        val bootDefault = mapOf<String, GuardianObservedState>(
            "cpu:3" to GuardianObservedState.Sysfs(2_803_200, "644", 0, 0),
        )
        val first = GuardianStockReadback(bootDefault, 1_000)
        val second = GuardianStockReadback(bootDefault, 1_000 + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS)

        assertEquals(
            "a value the restore never wrote cannot satisfy an exact-match gate",
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            GuardianDoubleStockReadbackPolicy.evaluate(entries, first, second),
        )

        // CONTROL — the instrument can see the opposite. Same entries, same gap, boot default that
        // happens to equal the captured Stock: it confirms. So the failure above is the VALUE, not
        // the harness.
        val matching = mapOf<String, GuardianObservedState>(
            "cpu:3" to GuardianObservedState.Sysfs(2_707_200, "644", 0, 0),
        )
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries,
                GuardianStockReadback(matching, 1_000),
                GuardianStockReadback(matching, 1_000 + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS),
            ),
        )
    }

    @Test
    fun `a MISSING axis fails identically to a mismatched one`() {
        // `first.entries[id]?.let { ... } == true` — an unreadable node is absent from the map and
        // fails the same way a wrong value does. Worth pinning separately: the two have completely
        // different causes on a device (SELinux, a node that does not exist after boot) and the
        // decision cannot tell them apart.
        val entries = listOf(cpuEntry())
        val empty = GuardianStockReadback(emptyMap(), 1_000)
        assertEquals(
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries, empty,
                GuardianStockReadback(emptyMap(), 1_000 + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS),
            ),
        )
    }

    @Test
    fun `the settle gap is a separate decision and does not mask a value mismatch`() {
        val entries = listOf(cpuEntry())
        val stock = mapOf<String, GuardianObservedState>(
            "cpu:3" to GuardianObservedState.Sysfs(2_707_200, "644", 0, 0),
        )
        assertEquals(
            GuardianDoubleStockDecision.SETTLE_GAP_TOO_SHORT,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries,
                GuardianStockReadback(stock, 1_000),
                GuardianStockReadback(stock, 1_000 + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS - 1),
            ),
        )
        assertNotEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(
                entries,
                GuardianStockReadback(stock, 1_000),
                GuardianStockReadback(stock, 1_000 + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS - 1),
            ),
        )
    }

    // ---- the diagnostic that makes the refusal legible -------------------------------------

    @Test
    fun `describe separates an ABSENT axis from a WRONG one, which the decision alone cannot`() {
        val entries = listOf(cpuEntry())
        val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS

        val wrong = mapOf<String, GuardianObservedState>(
            "cpu:3" to GuardianObservedState.Sysfs(2_803_200, "644", 0, 0),
        )
        val wrongLine = GuardianDoubleStockReadbackPolicy.describe(
            entries, GuardianStockReadback(wrong, 1_000), GuardianStockReadback(wrong, 1_000 + gap),
        )
        assertTrue("must name the axis: $wrongLine", wrongLine.contains("cpu:3"))
        assertTrue("must carry the observed value: $wrongLine", wrongLine.contains("2803200"))
        assertTrue("must carry the EXPECTED side too, or the line cannot be diffed: $wrongLine",
            wrongLine.contains("2707200"))
        assertTrue("must not claim absent: $wrongLine", !wrongLine.contains("absent"))

        val absentLine = GuardianDoubleStockReadbackPolicy.describe(
            entries, GuardianStockReadback(emptyMap(), 1_000), GuardianStockReadback(emptyMap(), 1_000 + gap),
        )
        assertTrue("an unreadable node must say so: $absentLine", absentLine.contains("cpu:3=absent"))

        // Both are FIRST_READBACK_NOT_STOCK. The decision cannot tell them apart; the line must.
        assertNotEquals(wrongLine, absentLine)
    }

    @Test
    fun `describe never leaks a filesystem path`() {
        // The entry carries both a logicalId and a real sysfs path. Only the id may be logged --
        // same disclosure rule that redacts paths out of the restore log.
        val line = GuardianDoubleStockReadbackPolicy.describe(
            listOf(cpuEntry()),
            GuardianStockReadback(emptyMap(), 1_000),
            GuardianStockReadback(emptyMap(), 1_000 + GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS),
        )
        assertTrue("path leaked into a log line: $line", !line.contains("/sys/"))
    }

    @Test
    fun `describe stays quiet on success and names the settle-gap case distinctly`() {
        val entries = listOf(cpuEntry())
        val stock = mapOf<String, GuardianObservedState>(
            "cpu:3" to GuardianObservedState.Sysfs(2_707_200, "644", 0, 0),
        )
        val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED.name,
            GuardianDoubleStockReadbackPolicy.describe(
                entries, GuardianStockReadback(stock, 1_000), GuardianStockReadback(stock, 1_000 + gap),
            ),
        )
        val short = GuardianDoubleStockReadbackPolicy.describe(
            entries, GuardianStockReadback(stock, 1_000), GuardianStockReadback(stock, 1_000 + gap - 1),
        )
        assertTrue("a timing refusal must be distinguishable from a value one: $short",
            short.startsWith(GuardianDoubleStockDecision.SETTLE_GAP_TOO_SHORT.name))
    }

    @Test
    fun `the failing branch logs in PRODUCTION, not behind the diagnostics flag`() {
        val src = sequenceOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/tweaker/RestoreGuardianProcessService.kt"),
            java.io.File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/RestoreGuardianProcessService.kt"),
        ).firstOrNull(java.io.File::isFile) ?: throw AssertionError("service not found")
        val code = src.readText().lineSequence().map { it.substringBefore("//") }
            .filterNot { it.trimStart().startsWith("*") }.joinToString("\n")
        val branch = code.substringAfter("CODE_STOCK_UNCONFIRMED").let { code.substringBefore(it) }
        assertTrue(
            "the stock-unconfirmed branch must call describe()",
            code.contains("GuardianDoubleStockReadbackPolicy.describe("),
        )
        // The whole point: it must NOT be inside a DIAGNOSTICS_COMPILED guard.
        val idx = code.indexOf("Guardian stock unconfirmed")
        assertTrue("describe call site not found", idx > 0)
        val preceding = code.substring(maxOf(0, idx - 400), idx)
        assertTrue(
            "the one line that explains a terminal refusal must not be compiled out in PRODUCTION",
            !preceding.contains("DIAGNOSTICS_COMPILED"),
        )
    }

    @Test
    fun `a permissions-only mismatch is not reported as a value mismatch`() {
        // matchSysfs compares value AND mode AND uid AND gid. A line carrying only the value would
        // show a correct-looking number beside a mismatch that is really a mode change -- the exact
        // wrong-mechanism trap this diagnostic exists to prevent, reproduced inside it.
        val entries = listOf(cpuEntry())
        val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
        val sameValueDifferentMode = mapOf<String, GuardianObservedState>(
            "cpu:3" to GuardianObservedState.Sysfs(2_707_200, "444", 0, 0),
        )
        val line = GuardianDoubleStockReadbackPolicy.describe(
            entries,
            GuardianStockReadback(sameValueDifferentMode, 1_000),
            GuardianStockReadback(sameValueDifferentMode, 1_000 + gap),
        )
        assertTrue("must still be a mismatch: $line", line.contains("cpu:3"))
        assertTrue("must show the differing mode: $line", line.contains("444"))
        assertTrue("must show the expected mode beside it: $line", line.contains("644"))
    }

    // ---- narrow cross-boot confirmation to the restorable axes -------

    @Test
    fun `restore scope and confirmation scope come from ONE definition`() {
        // The defect was two call sites with independent copies of the same filter. Textual
        // equality was not the fix; sharing the definition is. If a future edit reintroduces a
        // second copy, this is the gate.
        //
        // ESCALATED 2026-09-05: the identical defect shape crossed the process boundary --
        // RestoreGuardianCrypto's verifier carried its OWN unfiltered copy (in fact, no filter at
        // all), so a cross-boot receipt digesting only the restorable axes could never match a
        // verifier that always digested the complete envelope. Proven live on the Odin.
        // The filter now lives in exactly one place --
        // `GuardianTerminalRestoreScope.eligibleRestoreEntries` (RestoreGuardianEnvelope.kt) --
        // and every consumer, on either side of the digest, calls it by name. This test now gates
        // both files, not just the one that broke first.
        fun read(name: String): String {
            val file = sequenceOf(
                java.io.File("src/main/kotlin/com/gruning/mimir/tweaker/$name"),
                java.io.File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            ).firstOrNull(java.io.File::isFile) ?: throw AssertionError("$name not found")
            return file.readText().lineSequence().map { it.substringBefore("//") }
                .filterNot { it.trimStart().startsWith("*") }.joinToString("\n")
        }
        val engineCode = read("RestoreGuardianRunnerEngine.kt")
        val cryptoCode = read("RestoreGuardianCrypto.kt")
        val envelopeCode = read("RestoreGuardianEnvelope.kt")

        assertEquals(
            "both planRestore and confirmStock must use the shared definition",
            2,
            Regex("axesThisRestoreMayTouch\\(envelope\\)").findAll(engineCode).count(),
        )
        assertEquals(
            "the engine's producer-side filter must go through the scope-keyed shared definition",
            1,
            Regex("\\.eligibleRestoreEntries\\(").findAll(engineCode).count(),
        )
        assertEquals(
            "the verifier must call the identical shared definition, not its own copy",
            1,
            Regex("\\.eligibleRestoreEntries\\(").findAll(cryptoCode).count(),
        )
        assertEquals(
            "the filter expression must exist exactly ONCE across the whole module",
            1,
            Regex("isPersistentGuardianAxis\\(\\)\\s*\\}").findAll(envelopeCode).count(),
        )
        assertEquals(
            "and it must not be duplicated back into either consumer file",
            0,
            Regex("isPersistentGuardianAxis\\(\\)\\s*\\}").findAll(engineCode).count() +
                Regex("isPersistentGuardianAxis\\(\\)\\s*\\}").findAll(cryptoCode).count(),
        )
    }

    @Test
    fun `a persistent axis is still restored AND still verified across a boot`() {
        // The cross-boot rule narrows what is CHECKED to what is RESTORABLE. It must not stop checking an
        // axis that restore was permitted to write -- that would be a real loss of safety rather
        // than the removal of an unsatisfiable demand.
        val fan = RestoreGuardianFanPutEntry(
            applyOrdinal = 1,
            expectedStock = RestoreGuardianStockState.PresentFanMode(0),
            rendererAppliedIntermediates = mutableListOf(1),
        )
        val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
        val wrongFan = mapOf<String, GuardianObservedState>(
            fan.logicalId to GuardianObservedState.FanMode(2),
        )
        assertEquals(
            "a persistent axis reading the wrong value must still refuse",
            GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK,
            GuardianDoubleStockReadbackPolicy.evaluate(
                listOf(fan),
                GuardianStockReadback(wrongFan, 1_000),
                GuardianStockReadback(wrongFan, 1_000 + gap),
            ),
        )
        val rightFan = mapOf<String, GuardianObservedState>(
            fan.logicalId to GuardianObservedState.FanMode(0),
        )
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(
                listOf(fan),
                GuardianStockReadback(rightFan, 1_000),
                GuardianStockReadback(rightFan, 1_000 + gap),
            ),
        )
    }

    @Test
    fun `an empty eligible set still enforces the settle gap, so settlement is not free`() {
        // What survives when the eligible set is empty: readbacksPostdateRestore (engine-side) and
        // this gap. Confirmation is narrowed, not abolished -- pin the part that remains.
        val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
        assertEquals(
            GuardianDoubleStockDecision.SETTLE_GAP_TOO_SHORT,
            GuardianDoubleStockReadbackPolicy.evaluate(
                emptyList(),
                GuardianStockReadback(emptyMap(), 1_000),
                GuardianStockReadback(emptyMap(), 1_000 + gap - 1),
            ),
        )
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(
                emptyList(),
                GuardianStockReadback(emptyMap(), 1_000),
                GuardianStockReadback(emptyMap(), 1_000 + gap),
            ),
        )
    }

    @Test
    fun `the Nova's exact record now settles, where before it could not`() {
        // The device's real values: captured Stock is every CPU unpinned (INT_MAX through the
        // shared-QoS transport); after a reboot two policies read their own maxima. Under the OLD
        // full-set rule this is unsatisfiable because nothing may write those axes. With the restorable-axis check, a CPU-only plan on a changed boot has an empty eligible set and settles.
        val cpu0 = RestoreGuardianSysfsEntry(
            1, "cpu:0:scaling_max_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            RestoreGuardianStockState.PresentSysfs(Int.MAX_VALUE, "644", 0, 0), mutableListOf(1_800_000),
        )
        val cpu3 = cpuEntry()
        val gap = GuardianDoubleStockReadbackPolicy.MIN_SETTLE_GAP_MS
        val postBoot = mapOf<String, GuardianObservedState>(
            "cpu:0:scaling_max_freq" to GuardianObservedState.Sysfs(2_016_000, "644", 0, 0),
            "cpu:3" to GuardianObservedState.Sysfs(2_707_200, "644", 0, 0),
        )
        // old behaviour, for contrast: the complete set can never match
        assertNotEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(
                listOf(cpu0, cpu3),
                GuardianStockReadback(postBoot, 1_000),
                GuardianStockReadback(postBoot, 1_000 + gap),
            ),
        )
        // new behaviour: neither is persistent, so on a changed boot neither is eligible
        assertTrue("a CPU ceiling must not be persistent", !cpu0.isPersistentGuardianAxis())
        assertEquals(
            GuardianDoubleStockDecision.STOCK_CONFIRMED,
            GuardianDoubleStockReadbackPolicy.evaluate(
                listOf(cpu0, cpu3).filter { it.isPersistentGuardianAxis() },
                GuardianStockReadback(postBoot, 1_000),
                GuardianStockReadback(postBoot, 1_000 + gap),
            ),
        )
    }
}
