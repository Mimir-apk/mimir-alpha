package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FanTransitionOrchestratorTest {
    @Test
    fun `rejected admission returns the rejection handler and never proceeds`() {
        val calls = mutableListOf<String>()
        val rejected = ApplyAdmissionResult.Rejected(
            "TELEMETRY_ADMISSION_FAILED",
            "Thermal admission rejected: THERMAL_ANDROID",
        )

        val result = FanTransitionOrchestrator.run(
            admit = { calls += "admit"; rejected },
            onRejected = { "rejected:${it.code}" },
            proceed = { calls += "proceed"; "proceeded" },
        )

        assertEquals(listOf("admit"), calls)
        assertEquals("rejected:TELEMETRY_ADMISSION_FAILED", result)
    }

    @Test
    fun `admitted transition runs admit before proceed`() {
        val calls = mutableListOf<String>()

        val result = FanTransitionOrchestrator.run(
            admit = { calls += "admit"; ApplyAdmissionResult.Admitted },
            onRejected = { calls += "onRejected"; "rejected" },
            proceed = { calls += "proceed"; "proceeded" },
        )

        assertEquals(listOf("admit", "proceed"), calls)
        assertEquals("proceeded", result)
    }

    @Test
    fun `the rejection handed to the handler is the exact admission result`() {
        val rejected = ApplyAdmissionResult.Rejected("TELEMETRY_ADMISSION_FAILED", "message")
        var handed: ApplyAdmissionResult.Rejected? = null

        FanTransitionOrchestrator.run(
            admit = { rejected },
            onRejected = { handed = it; "rejected" },
            proceed = { "proceeded" },
        )

        assertSame(rejected, handed)
    }

    /**
     * Previously, `transitionFanControlModeOnWorker`'s entry branch (deriving the manual
     * plan, handing the fan to vendor Smart at full PWM, sampling, and capturing the handoff) ran
     * BEFORE `FanTransitionOrchestrator.run(` was ever called -- admission (`admit`) therefore ran
     * AFTER the first hardware write, not before it. E3 moves the entry branch inside `proceed`,
     * so admission strictly precedes the first hardware write. Source-text pin (same style as
     * P2's own pins in `AdvancedFanControlTest`): the entry's hand-to-4 write
     * (`handFanToVendorSmartBound()`) must appear textually AFTER
     * `FanTransitionOrchestrator.run(` in `transitionFanControlModeOnWorker`'s own source.
     */
    @Test
    fun `transitionFanControlModeOnWorker's entry hand-to-4 write sits after FanTransitionOrchestrator run`() {
        val fn = sliceBetween(
            mainSourceText("MimirTweakerService.kt"),
            "private fun transitionFanControlModeOnWorker(",
            "private fun settleMimirFanOwnershipAfterProfileApplyOnWorker(",
        )
        val runIndex = fn.indexOf("FanTransitionOrchestrator.run(")
        val handToVendorIndex = fn.indexOf("handFanToVendorSmartBound()")
        assertTrue("FanTransitionOrchestrator.run(...) must be present", runIndex >= 0)
        assertTrue(
            "the entry's hand-to-4 write must be present",
            handToVendorIndex >= 0,
        )
        assertTrue(
            "admission must strictly precede the first hardware write -- the hand-to-4 write " +
                "must sit textually AFTER FanTransitionOrchestrator.run(, i.e. inside its " +
                "proceed lambda",
            handToVendorIndex > runIndex,
        )
    }

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
}
