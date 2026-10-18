package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Stock withdraws Mimir's ceiling request; it does not replay a remembered number.**
 *
 * Policy: *"Stock unpins the request"* — *"Stock stops replaying a remembered number and instead
 * withdraws Mimir's ceiling request entirely, letting the kernel's own maximum stand."*
 *
 * The number was the bug. [StockCeilingBaseline] documents the worked example: on the Thor,
 * 2026-09-19, a re-arm captured Quiet's own clamp as stock, and every later "Stock" wrote the clamp back
 * and confirmed it. Making the first capture of a boot authoritative fixed the common case and, by its
 * own admission, *cannot help a boot whose first capture already finds the hardware clamped*. Withdrawing
 * needs no evidence, so it has no wrong answer to give.
 *
 * What makes it safe to do here and nowhere else is measured, not assumed: the shared node is a PM QoS
 * requester whose read returns its own stored request, and on 2026-09-21 a max request below a live
 * vendor min stored, verified and GOVERNED on that node. Writing the sentinel therefore withdraws and
 * reads back as the sentinel, so every exact readback in the module still holds.
 */
class StockUnpinsTest {

    private fun node(
        axis: TuningAxis,
        path: String,
        captured: Int? = 1_017_600,
        capturedMode: String = UNHELD_CPU_QOS_MODE,
    ) = TopologyNodeState(
        logicalId = "cpu:0:scaling_max_freq",
        axis = axis,
        canonicalPath = path,
        originalValue = captured,
        originalMode = capturedMode,
        originalUid = 0,
        originalGid = 0,
        present = true,
    )

    @Test
    fun `a CPU ceiling on the shared QoS node restores to no request at all`() {
        val captured = node(TuningAxis.CPU, SHARED_CPU_QOS_CEILING_PATH, captured = 1_017_600)

        assertEquals(
            "the captured number is still recorded honestly -- capture does not lie, restore declines " +
                "to replay it",
            1_017_600,
            captured.originalValue,
        )
        assertEquals(
            "and the restore writes the unpinned sentinel instead",
            UNPINNED_CPU_CEILING,
            restoreWriteValue(captured),
        )
    }

    /**
     * The 2026-09-19 dead end, as a test. A capture taken while the device already carried Quiet's clamp
     * used to make that clamp the restore point forever; now the same poisoned capture restores clean.
     */
    @Test
    fun `a poisoned capture no longer poisons the restore`() {
        val quietsOwnClamp = node(TuningAxis.CPU, SHARED_CPU_QOS_CEILING_PATH, captured = 1_017_600)
        val trueStock = node(TuningAxis.CPU, SHARED_CPU_QOS_CEILING_PATH, captured = UNPINNED_CPU_CEILING)

        assertEquals(
            "a dirty capture and a clean one restore to the SAME place, which is the whole point -- the " +
                "restore no longer depends on having captured the right number",
            restoreWriteValue(trueStock),
            restoreWriteValue(quietsOwnClamp),
        )
    }

    @Test
    fun `every other node still restores exactly what it captured`() {
        // Per-policy scaling_max_freq has no withdrawal primitive: the sentinel is clamped to the policy
        // maximum on write, and the readback then differs from what was written. Replaying the captured
        // value is the only thing that verifies there.
        assertEquals(
            1_017_600,
            restoreWriteValue(node(TuningAxis.CPU, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq")),
        )
        // GPU is deliberately out of scope: devfreq/max_freq is read-only even to root (2026-08-31) and
        // max_pwrlevel = 0 is a number like any other, not a withdrawal.
        assertEquals(
            1_017_600,
            restoreWriteValue(node(TuningAxis.GPU, "/sys/class/kgsl/kgsl-3d0/max_pwrlevel")),
        )
        assertEquals(
            1_017_600,
            restoreWriteValue(node(TuningAxis.FAN, HardwareSnapshot.FAN_SETTINGS_PATH)),
        )
        // A node with nothing captured stays null, so the callers' own "missing original value" errors
        // still fire rather than being masked by a sentinel.
        assertNull(
            restoreWriteValue(
                node(TuningAxis.CPU, "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq", captured = null),
            ),
        )
    }

    /**
     * **Stock is stock: it turns the Tweaker off, permissions included**.
     *
     * The value rule shipped without this and the device said so within the hour. A deliberately
     * poisoned install captured `originalMode = "444"` along with the clamp, and the restore replayed
     * it: the node came back correctly unpinned and still read-only, `session=Stock`, `held=false`,
     * nothing holding it. Half a withdrawal.
     *
     * The rule is narrower than the value's on purpose, and applies only where Mimir held the node. [HELD_CPU_QOS_MODE] is Mimir's own constant and nothing else on this node sets it, so
     * a capture that reads it back was taken while a session held the node — which is precisely a
     * capture that cannot be stock. A mode this code has never seen is left alone, because if Mimir was
     * not holding the node there is nothing of Mimir's to turn off.
     */
    @Test
    fun `Stock releases the node it was holding, and leaves any other mode alone`() {
        val heldWhenCaptured = node(
            TuningAxis.CPU,
            SHARED_CPU_QOS_CEILING_PATH,
            capturedMode = HELD_CPU_QOS_MODE,
        )
        assertEquals(
            "a capture taken while Mimir held the node restores to the unheld mode, not the hold",
            UNHELD_CPU_QOS_MODE,
            restoreWriteMode(heldWhenCaptured),
        )
        assertEquals(
            "a clean capture already reads the unheld mode, so this substitutes nothing on a healthy device",
            UNHELD_CPU_QOS_MODE,
            restoreWriteMode(node(TuningAxis.CPU, SHARED_CPU_QOS_CEILING_PATH)),
        )
        assertEquals(
            "a mode this code has never seen is left exactly as captured",
            "600",
            restoreWriteMode(node(TuningAxis.CPU, SHARED_CPU_QOS_CEILING_PATH, capturedMode = "600")),
        )
        assertEquals(
            "and the rule does not reach past the shared node onto per-policy paths",
            HELD_CPU_QOS_MODE,
            restoreWriteMode(
                node(
                    TuningAxis.CPU,
                    "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                    capturedMode = HELD_CPU_QOS_MODE,
                ),
            ),
        )
    }

    /**
     * **The two restore paths must not disagree.** The guardian restores from its own envelope, in a
     * separate process, and MGDE admits that envelope only when it matches the journalled node. If the
     * in-process restore withdrew while the envelope still expected the captured number, the guardian
     * would refuse its own restore — `FENCE_AMBIGUOUS`, which is exactly how run 14's stranded node came
     * about. Deriving all three from one function is what makes that disagreement unrepresentable.
     *
     * Source-text assertions because the property is "these call sites share a derivation", which no
     * single-path behavioural test can observe.
     */
    @Test
    fun `the restore, the guardian envelope and MGDE all derive from one rule`() {
        listOf(
            "AndroidTransactionPorts.kt" to 2,
            "RemoteGuardianProcessAuthority.kt" to 1,
            "mgde/MgdeRejection.kt" to 1,
        ).forEach { (name, expected) ->
            val source = sourceFile(name)
            // Matched with the paren, so the mgde sub-package's import lines are not counted.
            listOf("restoreWriteValue", "restoreWriteMode").forEach { rule ->
                assertEquals(
                    "$name must call $rule -- an inlined copy of the rule is how the two restore " +
                        "paths drift apart",
                    expected,
                    Regex(rule + "\\(").findAll(source).count(),
                )
            }
        }

        val ports = sourceFile("AndroidTransactionPorts.kt")
        assertTrue(
            "the restore MUTATION must not fall back to originalValue for sysfs axes",
            !ports.contains("val value = node.originalValue"),
        )
        assertTrue(
            "nor may it journal the captured MODE for a sysfs restore",
            !ports.contains("restoreMode = node.originalMode"),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            File("app/src/main/kotlin/com/gruning/mimir/tweaker/$name"),
            File("src/main/kotlin/com/gruning/mimir/tweaker/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
