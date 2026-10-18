package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the wave-2 Bottom glass lane (audit 2026-10-14, L7): the wiring that the render and receipt tests prove in `:app` and
 * `:feature-companion` is in the production files, at the sites the Thor frames named.
 */
class AuditW2L7SourcePinTest {

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    private fun source(path: String): String {
        val file = File(repoRoot(), path)
        assertTrue("missing $path", file.isFile)
        return file.readText()
    }

    private val presentation by lazy { source("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt") }
    private val surface by lazy { source("feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt") }

    /** RC-01 (B-52): the takeover's page is built from the last live session, while its open gate still follows the live one. */
    @Test
    fun `the takeover page is built from the last live session and its gate from the live one`() {
        assertTrue("the one function the dissolve test runs", presentation.contains("val takeoverPageGame = rememberCompanionTakeoverPageGame(workspaceGame)"))
        assertTrue(presentation.contains("    return companionTakeoverPageGame(live, lastLive[0])\n}"))
        assertTrue(presentation.contains("gameCardContent = takeoverPageGame\n                                ?.let { live ->"))
        assertTrue(presentation.contains("val retainedNowPlaying = takeoverPageGame?.gameId?.let(CompanionState::retainedNowPlayingPayload)"))
        assertTrue("the gate stays on the live session", presentation.contains("gameCardAvailableGameId = workspaceGame?.gameId,"))
        assertTrue("the gate stays on the live session", presentation.contains("gameCardSessionKey = workspaceGame?.let(::nowPlayingSessionKey),"))
    }

    /** RC-01 (B-09/B-60): under an override the scene host's target does not carry the payload mode. */
    @Test
    fun `the scene host's target is held while the Activity page is the override`() {
        assertTrue(
            surface.contains("targetState = if (sceneOverride != null) CompanionSceneTarget.Payload(CompanionMode.HOME) else requestedTarget,"),
        )
    }

    /**
     * Q7a (W2L7 review #2): the guard's ground is opaque and up at once, its content fades in on OverlayMs (a cut under reduced motion), it
     * finishes on mirror completion as it always has, and its EXIT is the Presentation's reclaim handoff cover, worn fresh on each reveal from
     * behind a host and fading on OverlayMs. The pixels are device-owed (X-06).
     */
    @Test
    fun `the return guard's content fades in over an opaque ground and the Presentation's reclaim cover is its exit`() {
        val guard = source("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnActivity.kt")
        assertEquals(MimirMotion.OverlayMs.toLong(), companionReturnGuardFadeMs(reducedMotion = false))
        assertEquals(0L, companionReturnGuardFadeMs(reducedMotion = true))
        assertTrue("the window's ground is opaque", guard.contains("setBackgroundDrawable(ColorDrawable(groundArgb))") && guard.contains("decorView?.setBackgroundColor(groundArgb)"))
        assertFalse("no translucent window", guard.contains("Color.TRANSPARENT"))
        assertTrue("the entry fades the content in from 0", guard.contains("guard.alpha = 0f\n            fadeGuard(guard, toAlpha = 1f, reducedMotion = reduced) {}"))
        assertTrue(
            "mirror completion finishes at once, as before: no exit fade keeps the guard's focus",
            !guard.contains("leaveRestoreGuard") && guard.contains("if (!isFinishing && !invalidOperation) finishWithoutTransition()"),
        )
        assertTrue(
            "a fade still running at the finish is cancelled, and its receipt says so",
            guard.contains("guardView?.animate()?.cancel()") && guard.contains("transitionEndIfRunning(CompanionReturnGuardFade, guardReceiptDisplay, \"cancelled\")"),
        )
        assertTrue("the receipt names the fade, or the cut", guard.contains("transition = if (reduced) null else CompanionReturnGuardFade,"))
        assertTrue(guard.contains(".setDuration(ms)\n            .setInterpolator(TimeInterpolator { fraction -> MimirMotion.Easing.transform(fraction) })"))
        // The exit: the reclaim handoff cover.
        assertTrue(companionReclaimCoverWorn(reveals = 1, reducedMotion = false, mountCoverUp = false, spent = 0))
        assertTrue(companionReclaimCoverWorn(reveals = 3, reducedMotion = false, mountCoverUp = false, spent = 0))
        assertFalse("never revealed from behind a host: no cover", companionReclaimCoverWorn(reveals = 0, reducedMotion = false, mountCoverUp = false, spent = 0))
        assertFalse("reduced motion cuts", companionReclaimCoverWorn(reveals = 1, reducedMotion = true, mountCoverUp = false, spent = 0))
        assertFalse("one cover at a time: not while the mount cover is up", companionReclaimCoverWorn(reveals = 1, reducedMotion = false, mountCoverUp = true, spent = 0))
        assertFalse("one cover per reveal: a reveal the mount cover covered is spent", companionReclaimCoverWorn(reveals = 1, reducedMotion = false, mountCoverUp = false, spent = 1))
        assertEquals("reclaim-cover.fade", CompanionReclaimCoverFade)
        val rebind = presentation.substringAfter("internal fun rebindRenderOperation(operationId: UUID): Boolean {").substringBefore("internal fun currentRenderOperationId()")
        assertTrue("counted only for a parked window", rebind.contains("if (parkedBehindSecondaryHost.value) {\n            reclaimReveals.value += 1"))
        assertTrue(
            "a revealed window's decor is shown from the composition that carries its cover, never from the rebind; a re-token shows at once",
            rebind.contains("} else if (decorRevealPendingFor == 0) {") &&
                presentation.contains("SideEffect { revealDecorIfPending(reclaimReveal, fallback = false) }"),
        )
        assertTrue(
            "worn only when the rule says so, one cover at a time",
            presentation.contains("if (companionReclaimCoverWorn(reveals = reclaimReveal, reducedMotion = animatorsOff, mountCoverUp = mountCoverUp, spent = revealsSpentByMountCover[0])) {"),
        )
        assertTrue("a fresh cover per reveal, held at full alpha", presentation.contains("key(reclaimReveal) {\n                        var reclaimCoverHeld by remember { mutableStateOf(true) }"))
        assertTrue("the handoff cover, dropped after the first revealed frames", presentation.contains("withFrameNanos { }\n                            withFrameNanos { }\n                            BottomUiReceipts.log.transitionBegin(CompanionReclaimCoverFade, receiptDisplayId)\n                            reclaimCoverHeld = false"))
        assertTrue(presentation.contains("CompanionMannazCover(\n                                visible = reclaimCoverHeld,"))
    }

    /** RC-02: the scene overlay's glass target goes through the hold; its clock is unchanged. The stage's glows are cached brushes. */
    @Test
    fun `the Bottom scene overlay's glass holds through a panel's dissolve`() {
        assertTrue(surface.contains("val overlayBlur by rememberCompanionOverlayGlass(pickerOpen || quickLaunchChoice != null || panelCovered || quickLaunchExpanded)"))
        val blur = surface.substringAfter("internal fun rememberCompanionOverlayGlass(requested: Boolean): State<Float> {").substringBefore("label = \"overlay-glass\"")
        assertTrue(blur.contains("val overlayGlassHeld = rememberMimirGlassHold(requested)") && blur.contains("targetValue = if (overlayGlassHeld) 1f else 0f,"))
        val stage = surface.substringAfter("private fun CompanionStage(").substringBefore("content()")
        assertTrue("the glows' brushes are built in drawWithCache, not per redraw", stage.contains("Modifier.fillMaxSize().drawWithCache {") && !stage.contains("Canvas("))
    }

    /** RC-03: the Tome's blur has its own held clock, and `reveal` is read in draw and derived flags only. */
    @Test
    fun `the Tome's glass is split from its page and reveal is not read in composition`() {
        assertTrue(surface.contains("val tomeGlass = rememberMimirHeldGlassProgress(visible = bookOpen)"))
        assertTrue(surface.contains("val revealShowing by remember { derivedStateOf { reveal > 0.001f } }"))
        assertTrue(surface.contains("LaunchedEffect(Unit) { snapshotFlow { reveal <= 0.001f }.collect { revealAtRest[0] = it } }"))
        assertTrue(surface.contains("if (folder != null && (bookOpen || revealShowing)) {"))
        assertTrue(surface.contains(".then(if (bookOpen && !revealShowing) Modifier.mimirPageTouchShield() else Modifier),"))
        assertTrue("the old composition read is gone", !surface.contains("SideEffect { revealAtRest[0] = reveal <= 0.001f }"))
    }

    /** RC-04: the lattice's glass under the platform shelf has its own held clock; `shelfEnter` is read in draw and derived flags only. */
    @Test
    fun `the platform shelf's lattice glass is split from shelfEnter`() {
        assertTrue(
            presentation.contains(
                "val latticeGlass = rememberMimirHeldGlassProgress(\n                        visible = platformDestination != null && workspacePage == CompanionWorkspacePage.SHELF,\n                        settleOpen = { shelfEntrySnaps },\n                    )",
            ),
        )
        assertTrue(presentation.contains("maxOf(latticeGlass(), launchGlass())"))
        assertTrue("the scrim gate is derived", presentation.contains("if (latticeGlassUp || secondaryLaunchTransitionVisible || secondaryLaunchTransitionId != null) {"))
        assertTrue("the cover flag is derived", presentation.contains("derivedStateOf { workspacePage == CompanionWorkspacePage.SHELF && platformDestination != null && shelfEnter() >= 0.999f }"))
        assertTrue("no composition read of the value remains", !presentation.contains("maxOf(shelfEnter, launchGlass())") && !presentation.contains("shelfEnter > 0.001f"))
        assertTrue(
            "RC-59: the Bottom keeps its warm-up call site; once per process is MimirGlassWarmup's own (lane glassx), not a flag here",
            presentation.contains("                MimirGlassWarmup()\n") && !presentation.contains("companionGlassWarmedInProcess"),
        )
    }

    /** RC-05: the Bottom launch card's blur rides core-ui's plan under a hold, its own alpha on the swap tier; the Float overload is gone. */
    @Test
    fun `the Bottom launch card's glass is split from the card and the ramped Float overload is retired`() {
        val shelf = source("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionPlatformShelf.kt")
        val layer = source("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionLaunchCardLayer.kt")
        val glass = source("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirLiquidGlass.kt")
        assertTrue(shelf.contains("val cardGlass = rememberMimirHeldGlassProgress(visible = pending != null)"))
        assertTrue("Q5: the shelf under the card is a snapshot at the peak", shelf.contains(".mimirGlassBackdropProgress({ MimirGlass.backdropBlurRadius }, { cardGlass() }, snapshot = { true })"))
        assertTrue(shelf.contains("if (card != null && cardShowing) {"))
        assertTrue(shelf.contains("animationSpec = companionLaunchCardSpec(up = pending != null, reducedMotion = cardReducedMotion),"))
        assertTrue(layer.contains("val sceneGlass = rememberMimirHeldGlassProgress(visible = game != null)"))
        assertTrue("Q5: the page under the card is a snapshot at the peak", layer.contains(".mimirGlassBackdropProgress({ MimirGlass.backdropBlurRadius }, { sceneGlass() }, snapshot = { true })"))
        assertTrue("Q5: the scene under the overlay is a snapshot at the peak", surface.contains(".mimirGlassBackdropProgress({ CompanionTomeBackdropBlurDp.dp }, { overlayBlur }, snapshot = { true }),"))
        assertTrue(layer.contains("animationSpec = companionLaunchCardSpec(up = game != null, reducedMotion = reducedMotion),"))
        assertTrue(
            "the cards' one shared spec, the timeline test's too",
            layer.contains("internal fun companionLaunchCardSpec(up: Boolean, reducedMotion: Boolean): FiniteAnimationSpec<Float> =\n    if (up) mimirSwapEnterSpec(reducedMotion) else mimirSwapExitSpec(reducedMotion)"),
        )
        assertTrue(
            "core-ui's held progress, the function every scene-side site and the timeline tests call",
            glass.contains("fun rememberMimirHeldGlassProgress(visible: Boolean, identity: Any? = Unit, settleOpen: () -> Boolean = { false }): () -> Float =\n    rememberMimirGlassProgress(visible = rememberMimirGlassHold(visible, cancelKey = identity), identity = identity, settleOpen = settleOpen)"),
        )
        assertTrue("the ramped Float overload is deleted", !glass.contains("fun Modifier.mimirGlassBackdrop(progress: Float)"))
        assertTrue("and no Bottom caller is left", !shelf.contains("mimirGlassBackdrop(glass)") && !layer.contains("mimirGlassBackdrop(glass)"))
        assertTrue(
            "L9's rule, through L9's overload: the shelf's cold art is ready only for a Success whose request was for this art",
            shelf.contains("val reveal = rememberMimirColdArtReveal(identity = artRef, warm = warm, state = painter.state, request = request)") &&
                !shelf.contains("ready = landed"),
        )
    }

    /** Phase F: RC-41, RC-42, RC-43, RC-51, RC-59 and the Q6 tokens. */
    @Test
    fun `the Bottom's remaining audit sites are fixed at their lines`() {
        assertTrue("RC-41", surface.contains("contentKey = { choice -> choice?.requestId },"))
        assertTrue("RC-42, review #3: the rename's glass is held, keyed on the session", surface.contains("val renameGlass = rememberMimirHeldGlassProgress(visible = renamingFolder != null, identity = renameSessionEpoch)"))
        assertTrue("RC-42", surface.contains("val renameGlassUp by remember(renameSessionEpoch) { derivedStateOf { renameGlass() > 0f } }"))
        assertTrue(
            "review #3: the keyboard plate dissolves on its own OverlayMs clock over the held glass",
            surface.contains("val renamePlateShowing by remember { derivedStateOf { renamePlate > 0.001f } }") &&
                surface.contains("if (renamingFolder != null || renamePlateShowing) {") &&
                surface.contains(".graphicsLayer { alpha = renamePlate }") &&
                surface.contains("if (renamingFolder != null || renameGlassUp) {"),
        )
        assertTrue("RC-42: no composition read of the rename glass", !surface.contains("renameGlass() > 0f)"))
        val moveOut = surface.substringAfter("label = \"tome-move-out-swap\"").substringBefore("when (moving) {")
        assertTrue("RC-51: the move-out pages fade themselves", moveOut.contains(".mimirRingSafeFade(pageFade)"))
        assertTrue("RC-51", surface.substringBefore("label = \"tome-move-out-swap\"").endsWith("fade = false,\n                                returning = swapReturning,\n                                returnTravelPx = swapPx,\n                                enterTravelPx = swapPx,\n                            )\n                        },\n                        "))
        assertTrue("RC-59", surface.contains("val alphaUp by remember { derivedStateOf { alpha > 0f } }") && surface.contains("if (visible || alphaUp) {"))
        assertTrue("Q6: the settle is FocusMs", surface.contains("internal const val CompanionDragSettleMillis = MimirMotion.FocusMs"))
        assertTrue("Q6: no EaseOutCubic, no 170 ms lift, no 140 ms linear disarm", !surface.contains("EaseOutCubic") && !surface.contains("tween(170") && !surface.contains("else 140,"))
        assertTrue("Q6: the disarm is on the house clock", surface.contains("easing = if (hoverFolderId != null) LinearEasing else MimirMotion.Easing,"))
        assertTrue("Q6: the arm fill is named a timer (chooser: keep)", surface.contains("The arm is the merge dwell's TIMER fill"))
        assertTrue("RC-43: the launch glass waits no wall clock after the drawer's close", !presentation.contains("let it finish before the launch glass begins"))
    }

    /** RC-36 residual (W1B #4): the resting layout's feeds are collected by the window, above the scene that is mounted per entry. */
    @Test
    fun `the Activity feeds are held above the scene`() {
        val scene = presentation.indexOf("val activitySceneContent: @Composable () -> Unit = {")
        assertTrue(scene > 0)
        for (feed in listOf("val activityLaunched: List<GameRow>? by remember(libraryRepository) {", "val activityGameCount: Int? by remember(libraryRepository) {", "val activityWeek:")) {
            val at = presentation.indexOf(feed)
            assertTrue("$feed is declared", at > 0)
            assertTrue("$feed is declared above the scene", at < scene)
        }
        assertTrue("the pool keeps its last answer across re-keys", presentation.contains(".collectAsStateWhileLive(lastActivityShelfPool[0])"))
        assertTrue(
            "review #6: the feeds collect only while the window is live, keeping their last value",
            presentation.contains(".collectAsStateWhileWindowLive(LocalDate.now(), windowLive)") &&
                presentation.split(".collectAsStateWhileWindowLive(null, windowLive)").size - 1 == 3 &&
                presentation.contains("live.liveFlow.collectLatest { on -> if (on) upstream.collect { state.value = it } }"),
        )
    }
}
