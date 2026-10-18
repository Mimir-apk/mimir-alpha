package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back from the file
 * browser, Utility is the page returned to: at rest from its first frame, the browser dissolving over it on the return
 * clock. It used to re-arrive from scratch (a 130 ms lead and a 540 ms arrival, presses held) under a browser already
 * gone at 180 ms: a near-empty screen after B.
 */
class UtilityReturnFromFilesSettledTest {

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `leaving the browser departs on the house overlay clock wherever it goes`() {
        assertEquals(MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(MimirOverlayKind.FOLDER_BROWSER))
    }

    @Test
    fun `the host knows what Utility replaced and tells it to come back settled`() {
        val host = source("MimirAppHost.kt")
        assertTrue(host.contains("val replacedKind = remember(active?.kind) { kindMemory.last.also { kindMemory.last = active?.kind } }"))
        assertTrue(host.contains("returnedFromFiles = replacedKind == MimirOverlayKind.FOLDER_BROWSER,"))
        assertTrue(host.contains("settledOnMount = returnedFromFiles,"))
        assertTrue(source("AppBrightnessCoordinator.kt").contains("settledOnMount = settledOnMount,"))
        assertTrue(source("AppBrightnessHost.kt").contains("settledOnMount = settledOnMount,"))
    }

    @Test
    fun `a settled Utility runs neither ramp, so nothing holds presses either`() {
        val overlay = source("BrightnessOverlay.kt")
        val settled = overlay.substringAfter("val settledOnReturn = remember { settledOnMount }")
            .substringBefore("val scrimColor = mimirGlassScrimColor()")
        // Both ramps sit behind the settled branch: a composable not called runs no LaunchedEffect and holds nothing.
        assertTrue(settled.contains("if (settledOnReturn) {\n        { 1f }\n    } else {\n        rememberMimirGlassProgress(visible = true)"))
        assertTrue("and the swap tier's pad hold sits behind it too", settled.contains("if (holdPad && !settledOnReturn) {"))
    }
}
