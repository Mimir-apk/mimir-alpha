package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianEntryPosturePolicyTest {
    @Test fun `full posture by mode matrix is observation-only only for SAFE stage and watch`() {
        val postures = listOf<String?>(
            TweakerPostureTruth.SAFE,
            TweakerPostureTruth.LAB,
            TweakerPostureTruth.PRODUCTION,
            "UNKNOWN",
            null,
        )

        postures.forEach { posture ->
            RestoreGuardianRunnerMode.entries.forEach { mode ->
                val expected = when {
                    posture == TweakerPostureTruth.SAFE && mode == RestoreGuardianRunnerMode.STAGE ->
                        GuardianEntryPostureDisposition.SAFE_STAGE_OBSERVATION_ONLY
                    posture == TweakerPostureTruth.SAFE && mode == RestoreGuardianRunnerMode.GUARDIAN_WATCH ->
                        GuardianEntryPostureDisposition.SAFE_WATCH_OBSERVATION_ONLY
                    else -> GuardianEntryPostureDisposition.INERT
                }
                assertEquals("posture=$posture mode=$mode", expected, GuardianEntryPosturePolicy.decide(posture, mode))
            }
        }
    }

    @Test fun `mutation modes are inert under every posture`() {
        val mutationModes = listOf(
            RestoreGuardianRunnerMode.APPLY_UNDER_FENCE,
            RestoreGuardianRunnerMode.RESTORE_UNDER_FENCE,
            RestoreGuardianRunnerMode.TERMINALIZE,
            RestoreGuardianRunnerMode.CLEANUP,
        )
        val postures = listOf<String?>(
            TweakerPostureTruth.SAFE,
            TweakerPostureTruth.LAB,
            TweakerPostureTruth.PRODUCTION,
            "unknown",
            null,
        )

        postures.forEach { posture ->
            mutationModes.forEach { mode ->
                assertEquals(
                    "mutation posture=$posture mode=$mode",
                    GuardianEntryPostureDisposition.INERT,
                    GuardianEntryPosturePolicy.decide(posture, mode),
                )
            }
        }
    }

    @Test fun `policy is not consumed and existing live boundaries remain inert`() {
        val root = sequenceOf(
            File("src/main/kotlin/com/gruning/mimir/tweaker"),
            File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
        ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")
        val files = root.listFiles().orEmpty().filter { it.extension == "kt" }

        val consumers = files
            .filter { it.name != "GuardianEntryPosturePolicy.kt" }
            .filter { it.readText().contains("GuardianEntryPosturePolicy") }
        assertTrue("unexpected GuardianEntryPosturePolicy consumer: $consumers", consumers.isEmpty())

        val service = requireNotNull(files.firstOrNull { it.name == "MimirTweakerService.kt" }).readText()
        assertTrue("SAFE decode-null boundary regressed", service.contains("decode = { null }"))

        val runner = requireNotNull(files.firstOrNull { it.name == "RestoreGuardianRunner.kt" }).readText()
        assertTrue(
            "inert guardian entry boundary regressed",
            runner.contains("RestoreGuardianMain is inert until authenticated controller wiring is certified"),
        )
    }
}
