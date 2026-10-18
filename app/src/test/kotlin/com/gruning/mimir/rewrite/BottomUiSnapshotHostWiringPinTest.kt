package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 re-check P3 (Quick Launch inside Tabs), read as source because neither composition can run in a unit test: the Tabs drawer
 * composes the quick-launch layer above its own panel, so it must say so, and the layer must hand that host to the three receipts
 * whose place in the stack snapshot depends on it. The sort itself is core-ui's `BottomUiReceiptLogTest`.
 */
class BottomUiSnapshotHostWiringPinTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first(File::isFile).readText()

    @Test
    fun `the Tabs drawer composes its quick-launch layer as hosted by Tabs`() {
        val drawer = source("feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionRecentsDrawer.kt")
        assertEquals(1, Regex("""CompanionQuickLaunchLayer\(""").findAll(drawer).count())
        assertTrue("receiptHost = BottomSurface.TABS," in drawer)
    }

    @Test
    fun `the quick menu's window composes its quick-launch layer as hosted by the menu`() {
        val panel = source("app/src/main/kotlin/com/gruning/mimir/rewrite/BottomQuickLaunchPanelHost.kt")
        assertEquals(1, Regex("""CompanionQuickLaunchLayer\(""").findAll(panel).count())
        assertTrue("receiptHost = BottomSurface.M_MENU," in panel)
    }

    @Test
    fun `the receipt names the host on both edges and when it is torn down with its window`() {
        val receipts = source("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/BottomUiReceipts.kt")
        assertTrue("LaunchedEffect(surface, display, visible, host)" in receipts)
        assertTrue("DisposableEffect(surface, display, host)" in receipts)
        assertTrue("log.isSurfaceVisible(surface, display, host)" in receipts)
        assertEquals("the enter and the exit both pass the host", 2, Regex("""host = host,""").findAll(receipts).count())
    }

    @Test
    fun `the layer reports its picker, pin actions and launch prompt with that host`() {
        val surface = source("feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt")
        for (name in listOf("PICKER", "PIN_ACTIONS", "LAUNCH_PROMPT")) {
            val call = Regex("""surface = BottomSurface\.$name,[\s\S]*?\n    \)""").find(surface)?.value.orEmpty()
            assertTrue("$name's receipt must pass host = receiptHost: $call", "host = receiptHost," in call)
        }
        // The Presentation's own composition of the layer passes nothing, so it stays the ordinary surface-group host.
        val presentation = source("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt")
        assertTrue("receiptHost" !in presentation)
    }
}
