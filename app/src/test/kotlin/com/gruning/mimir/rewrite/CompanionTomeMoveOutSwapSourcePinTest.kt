package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A5 (C1): the Bottom Tome's move-out confirm is a page swap, not a cut. `CompanionFolderPage` is private and lives in a
 * Compose closure inside the Presentation, so these are source pins: the leaves and the confirm sit under ONE
 * `mimirSwapTransform` keyed by the content id, with no page-level `if (moving ...)` composable branch left to cut between them.
 */
class CompanionTomeMoveOutSwapSourcePinTest {
    private fun surfaceSource(): String = java.io.File(
        "../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
    ).readText()

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The function, from its signature to the next top-level function. */
    private fun folderPage(): String {
        val source = surfaceSource()
        val start = source.indexOf("private fun CompanionFolderPage(")
        assertTrue("CompanionFolderPage must exist", start >= 0)
        // The leaves were extracted whole into `CompanionTomeLeaves` (V6, so the Tome's ring renders against the real leaves), which now follows the page.
        val end = source.indexOf("internal fun ColumnScope.CompanionTomeLeaves(", start)
        assertTrue("and be followed by its leaves", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `the confirm and the leaves are two pages of one swap keyed by the content id`() {
        val page = squash(folderPage())
        assertTrue("one AnimatedContent over the child id", page.contains("AnimatedContent( targetState = movingId,"))
        assertEquals("exactly one swap holds both pages", 1, Regex("AnimatedContent\\(").findAll(page).count())
        assertTrue(
            "the swap tier's own transform, the long-press forward and every exit a return, with the specified 8 dp travel",
            page.contains("val swapReturning = movingId == null") &&
                page.contains("mimirSwapTransform( swapReduced, fade = false, returning = swapReturning, returnTravelPx = swapPx, enterTravelPx = swapPx, )") &&
                page.contains("val swapPx = with(density) { MimirMotion.SwapTravelDp.dp.roundToPx() }"),
        )
        assertTrue(
            "the leaves page fades itself, ring-safe, not through the transform's layer (RC-51, audit 2026-10-14)",
            page.contains("fade = false,") && page.contains(".mimirRingSafeFade(pageFade)"),
        )
        assertTrue("identity is the content id", page.contains("val movingId = movingChildId?.takeIf"))
    }

    @Test
    fun `no page level composable branch on the confirm is left`() {
        val body = squash(folderPage())
        for (needle in listOf("if (moving != null)", "if (moving == null)", "if (movingChild == null)", "if (movingChild != null)", "movingChild =")) {
            assertFalse("the page must not cut between the pages with `$needle`", body.contains(needle))
        }
        val leaves = body.indexOf("when (moving) { null -> {")
        val removeTome = body.indexOf("\"Everything inside returns to the shelf.\"")
        val confirm = body.indexOf("else -> {", leaves)
        val moveToShelf = body.indexOf("MimirChromeText(\"Move to shelf\"")
        val cancelHint = body.indexOf("\"Tap outside to cancel\"")
        assertTrue(
            "the leaves page holds its footer, the confirm page its own: $leaves $removeTome $confirm $moveToShelf $cancelHint",
            leaves in 0 until removeTome && removeTome < confirm && confirm < moveToShelf && moveToShelf < cancelHint,
        )
        val closeHint = Regex("\"Tap outside to close\"").findAll(body).map { it.range.first }.toList()
        assertEquals("the empty Tome keeps its one hint and the leaves page carries the other", 2, closeHint.size)
        assertTrue("the leaves page's hint is before the confirm page", closeHint.last() in removeTome until confirm)
    }

    @Test
    fun `the pad gate is never held and the Bottom takes no key or back handling`() {
        val body = folderPage()
        assertTrue("the page gate is the touch half only", squash(body).contains("holdPad = false"))
        for (needle in listOf("MimirTransitionInputGate", "holdMimirInputUntilShown", "BackHandler", "onKeyEvent", "onPreviewKeyEvent", "focusable(", "focusTarget(")) {
            assertFalse("the Bottom is touch-only: no `$needle` in the Tome page", body.contains(needle))
        }
    }

    @Test
    fun `the surface receipt and the probe name the swap and a cut says why`() {
        val body = squash(folderPage())
        assertTrue(
            "the surface is the move-out confirm, nested under tome-open",
            body.contains("surface = BottomSurface.TOME_MOVE_OUT, visible = movingId != null,"),
        )
        assertTrue(
            "the edge names the swap, or under reduced motion writes a cut with its reason and no transition",
            body.contains("transition = if (swapReduced) null else if (movingId != null) \"tome-move-out.enter\" else \"tome-move-out.exit\"") &&
                body.contains("reason = if (swapReduced) BottomUiReceiptLog.REASON_REDUCED_MOTION else null"),
        )
        assertTrue(
            "the probe runs inside the current page, and never under a cut",
            body.contains("if (!swapReduced && transition.targetState == EnterExitState.Visible) { BottomUiTransitionProbe(" +
                "if (swapReturning) \"tome-move-out.exit\" else \"tome-move-out.enter\", transition) }"),
        )
        assertTrue("the surface id is the one the receipts name", surfaceId() == "tome-move-out")
    }

    @Test
    fun `tome-open stays entered through the confirm and the three exits are unchanged`() {
        val source = surfaceSource()
        assertTrue(
            "the Home's tome-open receipt still follows the open Tome only, not the confirm",
            squash(source).contains("surface = BottomSurface.TOME_OPEN, visible = openFolderId != null,"),
        )
        val body = squash(folderPage())
        // EdgeBack: the confirm first, then the Tome, behind the per-mount floor.
        val floor = body.indexOf("val initialCloseRequest = remember(folder.id) { closeRequest }")
        val edgeConfirm = body.indexOf("latestMovingId != null -> { movingChildId = null")
        val edgeTome = body.indexOf("else -> { onClose()")
        assertTrue("the floor, then the confirm, then the Tome: $floor $edgeConfirm $edgeTome", floor in 0 until edgeConfirm && edgeConfirm < edgeTome)
        // Outside tap: the confirm cancels first, else the Tome closes.
        assertTrue(
            "outside tap cancels the confirm before it closes the Tome",
            body.contains("movingId != null -> movingChildId = null else -> onClose()"),
        )
        // Move to shelf: clear, then move out.
        assertTrue("Move to shelf clears the confirm and moves the child", body.contains("movingChildId = null onMoveOut(moving.id)"))
        // A long-press is the one forward edge.
        assertTrue("long-press opens the confirm", body.contains("onLongPress = { movingChildId = it.id }"))
    }

    @Test
    fun `the swap reads the animator setting at its own edge, like the dock, and not once per mount`() {
        val body = squash(folderPage())
        assertTrue(
            "keyed on the swap target, through the receipts' own live read",
            body.contains("val swapReduced = remember(movingId) { bottomUiReducedMotion() }"),
        )
        assertFalse("not the per-mount cache", body.contains("val swapReduced = mimirReducedMotion()"))
    }

    @Test
    fun `the open Tome is keyed on its folder id and not reset by every republish of the tile list`() {
        val source = squash(surfaceSource())
        assertFalse("the reset the move-out republish tripped is gone", source.contains("var openFolderId by remember(tiles)"))
        assertTrue(
            "the open Tome's identity is the requested folder id, resolved against the live tiles",
            source.contains("var openFolderRequest by remember { mutableStateOf<String?>(null) }") &&
                source.contains("val openFolderId = companionOpenTomeId(openFolderRequest, tiles, openTomeWasFilled[0])"),
        )
    }

    private fun surfaceId(): String {
        val format = java.io.File(
            "../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/BottomUiReceiptFormat.kt",
        ).readText()
        return Regex("const val TOME_MOVE_OUT = \"([^\"]+)\"").find(format)?.groupValues?.get(1).orEmpty()
    }
}
