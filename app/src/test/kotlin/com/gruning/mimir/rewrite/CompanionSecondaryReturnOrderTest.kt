package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-5 triage 4b, recording `219_RETURN_TO_SHELF_bottom.mp4`: the menu card was still drawn over the return guard
 * (f6-14) and the revealed shelf still wore the menu's blur (f30-40). RETURN TO SHELF completed its menu only after
 * `restore()`, and `restore()` launches the guard synchronously (`explicitReclaim` → `execute` → `launchRestoreGuard`
 * → `startActivity`).
 *
 * `completeQuickMenu` collapses from a main-looper post (pinned in core-companion-native's `OverlayScreenActivityTest`),
 * so moving the call above `restore()` would still launch the guard before the collapse runs. The handler posts the
 * reclaim behind the collapse instead. [secondaryReturnOrder] reads a SECONDARY_RETURN branch; its samples are the old
 * order, the swap that only looks fixed, and the posted form.
 */
class CompanionSecondaryReturnOrderTest {

    @Test
    fun `the reader tells the three shapes apart`() {
        assertEquals(
            SecondaryReturnOrder.RESTORE_BEFORE_COLLAPSE,
            secondaryReturnOrder(
                """
                val ok = companionController?.restore(reason = "r") == true
                companionOverlayController?.completeQuickMenu(action.requestId, accepted = ok)
                """,
            ),
        )
        assertEquals(
            SecondaryReturnOrder.RESTORE_INLINE_AFTER_POSTED_COLLAPSE,
            secondaryReturnOrder(
                """
                companionOverlayController?.completeQuickMenu(action.requestId, accepted = true)
                val ok = companionController?.restore(reason = "r") == true
                """,
            ),
        )
        assertEquals(
            SecondaryReturnOrder.RESTORE_POSTED_BEHIND_COLLAPSE,
            secondaryReturnOrder(
                """
                companionOverlayController?.completeQuickMenu(action.requestId, accepted = true)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val ok = companionController?.restore(reason = "r") == true
                }
                """,
            ),
        )
    }

    @Test
    fun `RETURN TO SHELF collapses the menu before the reclaim starts`() {
        assertEquals(SecondaryReturnOrder.RESTORE_POSTED_BEHIND_COLLAPSE, secondaryReturnOrder(returnBranch()))
    }

    @Test
    fun `a failed reclaim keeps its toast, reported from the posted reclaim`() {
        val branch = returnBranch()
        val post = branch.indexOf(".post {")
        val restore = branch.indexOf("companionController?.restore(")
        val toast = branch.indexOf("showCompanionFeedbackToast(\"Couldn’t restore the bottom screen\")")
        assertTrue("the reclaim must be posted", post >= 0)
        assertTrue("the failure toast stays, after the reclaim it reports on", restore in (post + 1) until toast)
        assertTrue(
            "the no-longer-available refusal still leaves the menu open with its own toast",
            branch.contains("completeQuickMenu(action.requestId, accepted = false)") &&
                branch.contains("showCompanionFeedbackToast(\"Return to shelf is no longer available.\")"),
        )
        assertTrue("the runtime log line survives", branch.contains("\"return requested host="))
    }

    private fun returnBranch(): String {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val branch = source.substringAfter("QuickMenuItem.SECONDARY_RETURN -> {", "")
            .substringBefore("QuickMenuItem.SECONDARY_SWAP -> {", "")
        check(branch.isNotEmpty()) { "SECONDARY_RETURN branch not found" }
        return branch
    }
}

internal enum class SecondaryReturnOrder {
    RESTORE_BEFORE_COLLAPSE,
    RESTORE_INLINE_AFTER_POSTED_COLLAPSE,
    RESTORE_POSTED_BEHIND_COLLAPSE,
    UNRECOGNIZED,
}

/** How a SECONDARY_RETURN branch orders the menu completion that collapses (`accepted = true`) and the reclaim. */
internal fun secondaryReturnOrder(branch: String): SecondaryReturnOrder {
    val restore = branch.indexOf("companionController?.restore(")
    if (restore < 0) return SecondaryReturnOrder.UNRECOGNIZED
    val collapse = branch.indexOf("completeQuickMenu(action.requestId, accepted = true)")
    if (collapse < 0 || collapse > restore) return SecondaryReturnOrder.RESTORE_BEFORE_COLLAPSE
    val post = branch.indexOf(".post {", startIndex = collapse)
    return if (post in collapse until restore) {
        SecondaryReturnOrder.RESTORE_POSTED_BEHIND_COLLAPSE
    } else {
        SecondaryReturnOrder.RESTORE_INLINE_AFTER_POSTED_COLLAPSE
    }
}
