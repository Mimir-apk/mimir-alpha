package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mimir removed display-rate switching on 2026-09-15 and nothing sends `setDisplayRateSettingV8`, so the daemon no
 * longer performs it: the AIDL declaration stays (later transaction codes keep their numbers) and the body refuses
 * every call and never writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RetiredDisplayRateWriteTest {
    @Test
    fun `the refusal is a failed bundle that names the retirement`() {
        val bundle = RetiredDisplayRateWrite.refusal(apiVersion = 10)

        assertEquals(false, bundle.getBoolean("success", true))
        assertEquals(RetiredDisplayRateWrite.CODE, bundle.getString("code"))
        assertEquals(CheckedCommandStatus.FAILED.name, bundle.getString("status"))
        assertEquals(RetiredDisplayRateWrite.MESSAGE, bundle.getString("message"))
        assertEquals(RetiredDisplayRateWrite.MESSAGE, bundle.getString("detail"))
        assertEquals(10, bundle.getInt("apiVersion"))
    }

    @Test
    fun `the service body only refuses and reaches no writer, transport or settings provider`() {
        val service = sourceText("MimirTweakerService.kt")
        val body = service.substringAfter("override fun setDisplayRateSettingV8(key: String?, value: String?): Bundle = guarded {")
            .substringBefore("\n        }\n")

        assertTrue(body.contains("RetiredDisplayRateWrite.refusal(API_VERSION)"))
        listOf("pServer", "executeChecked", "execute(", "Settings.", "key", "value", "putString(").forEach { forbidden ->
            assertFalse("the retired write must not touch `$forbidden`: $body", body.contains(forbidden))
        }
    }

    @Test
    fun `the display-rate writer and its supported-Hz read are gone`() {
        assertFalse(File(mainRoot(), "DisplayRateWritePort.kt").exists())
        val service = sourceText("MimirTweakerService.kt")
        assertFalse(service.contains("DisplayRateWritePort"))
        assertFalse(service.contains("AndroidDisplayRateSupportedHzPort"))
        assertFalse(service.contains("displayRateWritePort"))
    }

    private fun mainRoot(): File = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
    ).firstOrNull(File::isDirectory) ?: throw AssertionError("main source root unavailable")

    private fun sourceText(name: String): String = File(mainRoot(), name).readText()
}
