package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7 (product review 2026-09-28; nova-9, rg-rotate-9, thor-4): the Home row's no-emulator toast is laid out over
 * the rail's two lines instead of being clipped inside one message. The wording is the ruled sentence, unchanged.
 * Whether it now fits is a pixel question for the emulator walk at 640x480dp, 720x720 and 1920x1080.
 */
class HomeShelfLaunchFailureToastTest {

    private val needsEmulator = LaunchResult(readiness = LaunchReadiness.NeedsEmulator(PlatformId("snes")))

    @Test
    fun `the no-emulator toast splits at its sentence break and keeps every word`() {
        val toast = homeShelfLaunchFailureToast(
            result = needsEmulator,
            gameId = GameId("g1"),
            platformLabelFor = { "Super Nintendo" },
        )
        assertEquals("No installed emulator for Super Nintendo.", toast.text)
        assertEquals("Open Settings › Emulators to install one.", toast.supportingText)
        assertEquals(
            "the two lines together are exactly the sentence the launch card shows",
            homeShelfLaunchFailureMessage(needsEmulator, GameId("g1")) { "Super Nintendo" },
            toast.text + " " + toast.supportingText,
        )
    }

    @Test
    fun `every other failure keeps its single message line`() {
        val missingFile = homeShelfLaunchFailureToast(
            result = LaunchResult(readiness = LaunchReadiness.MissingFile(GameId("g1"))),
            gameId = GameId("g1"),
            platformLabelFor = { "PlayStation" },
        )
        assertNull(missingFile.supportingText)
        assertEquals(
            "Mimir can’t reach this game file. Restore it or reconnect its library folder, then try again.",
            missingFile.text,
        )
        val noResult = homeShelfLaunchFailureToast(result = null, gameId = GameId("g1")) { "PlayStation" }
        assertEquals(HomeShelfLaunchFailureFallback, noResult.text)
        assertNull(noResult.supportingText)
    }

    @Test
    fun `the Home row sends the split toast with its supporting line`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeShelfCoordinator.kt not found")
        val failureArm = source.substringAfter("onFailureToast = { result ->").substringBefore("launchResult?.let")
        assertTrue(failureArm.contains("homeShelfLaunchFailureToast("))
        assertTrue(failureArm.contains("failure.supportingText"))
    }
}
