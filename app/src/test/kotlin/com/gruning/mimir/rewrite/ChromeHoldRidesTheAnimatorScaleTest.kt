package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N3 of the alpha audit (2026-09-30). The scene chrome's outgoing hold (`mimirOutgoingChromeHoldMs`, pinned by
 * OutgoingChromeHoldTest) waited on a coroutine `delay()`, which does not scale with the animator setting, so under a
 * slowed animator the blur cleared while the overlay's dissolve was still running. It is `mimirHold` now.
 */
class ChromeHoldRidesTheAnimatorScaleTest {

    @Test
    fun `the outgoing chrome hold is on the animation clock and keeps its per-overlay length`() {
        val host = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").readText()
        val effect = host.substringAfter("val outgoingDuration = mimirOutgoingChromeHoldMs(presentedOverlayChrome)")
            .substringBefore("presentedOverlayChrome = null")
        assertTrue("the hold is mimirHold", effect.contains("if (outgoingDuration > 0) mimirHold(outgoingDuration)"))
        assertFalse("and no coroutine delay", effect.contains("delay("))
        assertTrue("the import is there", host.contains("import com.gruning.mimir.rewrite.core.ui.mimirHold\n"))
    }
}
