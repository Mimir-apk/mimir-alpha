package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice B1 of the Thor Bottom revamp, pinned in production source where a paused-clock test cannot reach: the Presentation's own hosts (the
 * instruments panel, Tabs, the platform shelf, the display chooser), the overlay Tabs host, the Tome, the dock's rail, the pin actions and the
 * four retired clocks. Each site names the spec it runs; `MimirOverlayMotionTest` (core-ui) measures what those specs come to, and
 * `BottomOverlayTiersTest` drives the picker and the launch prompt.
 *
 * Expected receipt lengths after B1 (names unchanged): picker.*, launch-prompt.*, pin-actions.*, display-chooser.* 310 / 180;
 * tabs.* 670 / 180 (both hosts); instruments.fade 670 / 180; tome-open.* 670 / 180; platform-shelf.enter 670 (from the target flip, after
 * `shelfHasFrame`) / platform-shelf.exit 160.
 */
class BottomOverlayTiersSourcePinTest {

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
        assertTrue("$path must be readable from the repository root", file.isFile)
        return file.readText()
    }

    private val surfacePath = "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt"
    private val presentationPath = "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt"
    private val overlayHostPath = "app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionRecentsOverlayHost.kt"

    /** The code, with comments blanked: a KDoc that quotes a retired clock is prose. */
    private fun code(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun between(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("`$from` not found", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("`$to` not found after `$from`", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `the picker, the launch prompt and the pin actions arrive on the swap tier and take no pad gate`() {
        val surface = code(source(surfacePath))
        val layer = between(surface, "AnimatedContent(\n            targetState = pickerSlot,", "private fun CompanionQuickLaunchRow(")
        for (label in listOf("companion-quick-picker-transition", "companion-quick-choice-transition", "companion-pin-actions-transition")) {
            assertTrue("$label is on the swap overlay transform", layer.contains("transitionSpec = { mimirSwapOverlayTransform(overlayReducedMotion, arriving = targetState != null) },\n            label = \"$label\""))
        }
        assertEquals("three swap overlays in the layer", 3, Regex("""mimirSwapOverlayTransform\(""").findAll(layer).count())
        assertTrue("the picker gates at 15%, touch only", layer.contains("MimirSwapPageGate(mimirSwapFadeAlpha(overlayReducedMotion), MimirPageMotion.ShownAlpha, holdPad = false)"))
        assertTrue("the launch prompt gates from its first painted frame, touch only", layer.contains("MimirSwapPageGate(fade, holdPad = false)"))
        assertTrue("the pin actions gate from their first painted frame, touch only", layer.contains("MimirSwapPageGate(mimirSwapFadeAlpha(overlayReducedMotion), holdPad = false)"))
        assertFalse("none of the three keeps the old 180 ms crossfade", layer.contains("fadeIn(tween(MimirMotion.OverlayMs"))
        for (receipt in listOf("picker.enter", "picker.exit", "launch-prompt.enter", "launch-prompt.exit", "pin-actions.enter", "pin-actions.exit")) {
            assertTrue("receipt $receipt keeps its name", layer.contains("\"$receipt\""))
        }
    }

    @Test
    fun `the display chooser arrives on the swap tier with a gate that never holds the pad`() {
        val presentation = code(source(presentationPath))
        val chooser = between(presentation, "targetState = gameDisplayChoice,", "CompanionGameDisplayChoiceDialog(")
        assertTrue(chooser.contains("transitionSpec = { mimirSwapOverlayTransform(chooserReducedMotion, arriving = targetState != null) },"))
        assertTrue(chooser.contains("MimirSwapPageGate(chooserFade, holdPad = false)"))
        assertTrue("both receipts keep their names", chooser.contains("\"display-chooser.enter\"") && chooser.contains("\"display-chooser.exit\""))
        assertFalse(chooser.contains("tween("))
    }

    @Test
    fun `the instruments host and Tabs run the page tier's overlay clocks, and the Bottom does not use Main's pixel-fade host`() {
        val presentation = code(source(presentationPath))
        val instruments = between(presentation, "targetState = instrumentsPanel,", "{ presentedPanel ->")
        assertTrue("instruments arrive on the overlay content transform", instruments.contains("mimirOverlayContentTransform(instrumentsReducedMotion, arriving = targetState != null)"))
        assertTrue("and keep their receipt", presentation.contains("BottomUiTransitionProbe(\"instruments.fade\", transition, receiptDisplayId)"))
        assertTrue("the departing panel's input shield is still there", presentation.contains("val outgoingInput = if (!outgoing) {"))
        val tabs = between(presentation, "val tabsEnterSpec = mimirOverlayArriveSpec(tabsReducedMotion)", "CompanionRecentsDrawer(")
        assertTrue(tabs.contains("val tabsExitSpec = mimirOverlayLeaveSpec()"))
        assertTrue(tabs.contains("enter = fadeIn(tabsEnterSpec),") && tabs.contains("exit = fadeOut(tabsExitSpec),"))
        assertTrue(tabs.contains("MimirSwapPageGate(mimirRingSafeFadeAlpha(tabsEnterSpec, tabsExitSpec), holdPad = false)"))

        val overlayHost = code(source(overlayHostPath))
        assertTrue("the overlay-hosted Tabs run the same specs", overlayHost.contains("enter = fadeIn(tabsEnterSpec),") && overlayHost.contains("exit = fadeOut(tabsExitSpec),"))
        assertTrue(overlayHost.contains("val tabsEnterSpec = mimirOverlayArriveSpec(tabsReducedMotion)") && overlayHost.contains("val tabsExitSpec = mimirOverlayLeaveSpec()"))
        assertTrue(overlayHost.contains("MimirSwapPageGate(mimirRingSafeFadeAlpha(tabsEnterSpec, tabsExitSpec), holdPad = false)"))
        assertFalse("no ModeTransition 160 ms tween is left on either host", overlayHost.contains("ModeTransitionMs") || presentation.contains("CompanionPresentationTransitionMs"))

        // Main's host holds MimirTransitionInputGate on a cold arrival, so the Bottom cannot take it as it is; it stays private to Main.
        assertFalse("the Presentation does not use MimirPixelFadeHost", presentation.contains("MimirPixelFadeHost"))
        val appHost = source("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt")
        assertTrue("MimirPixelFadeHost is Main's, unchanged and private", appHost.contains("private fun <T : Any> MimirPixelFadeHost("))
        assertTrue("and it does hold the pad gate on a cold arrival", appHost.substringAfter("private fun <T : Any> MimirPixelFadeHost(").substringBefore("private fun MimirDirectOverlayPixelFadeHost(").contains("holdMimirInputUntilShown(incomingAlpha)"))
    }

    @Test
    fun `the platform shelf arrives on the overlay clock and returns on the return clock, with its settle gating untouched`() {
        val presentation = code(source(presentationPath))
        val shelf = between(presentation, "val shelfEnterAnimated by animateFloatAsState(", "label = \"companion-shelf-enter\"")
        assertTrue(shelf.contains("shelfEntrySnaps -> snap()"))
        assertTrue(shelf.contains("shelfEnterTarget >= 1f && shelfAtRest[0] -> mimirOverlayArriveSpec(shelfReducedMotion)"))
        assertTrue(shelf.contains("else -> tween(MimirPageMotion.ReturnMs, easing = MimirMotion.Easing)"))
        assertTrue(
            "shelfHasFrame and its 400 ms fallback are as they were (the fallback named by the audit 2026-10-14)",
            presentation.contains("delay(CompanionShelfFirstFrameFallbackMs)\n                                shelfHasFrame = true"),
        )
        assertEquals(400L, CompanionShelfFirstFrameFallbackMs)
        assertTrue("the entry decision is as it was", presentation.contains("val shelfEntrySnaps = shelfEntryHistory.snaps(libraryDestination, platformDestination?.platformIdValue)"))
        assertTrue("receipts keep their names", presentation.contains("\"platform-shelf.enter\"") && presentation.contains("\"platform-shelf.exit\""))
    }

    @Test
    fun `the Tome opens on the Folio clock with no scale, and the page is admitted from the tap`() {
        val surface = code(source(surfacePath))
        val reveal = between(surface, "val reveal by animateFloatAsState(", "label = \"book-open\"")
        assertTrue(reveal.contains("!bookOpen -> mimirOverlayLeaveSpec()"))
        assertTrue(reveal.contains("revealAtRest[0] -> mimirOverlayArriveSpec(revealReducedMotion)"))
        assertTrue("a reopen mid-close carries on without the lead", reveal.contains("else -> tween(MimirPageMotion.ReturnMs, easing = MimirMotion.Easing)"))
        val page = between(surface, "if (folder != null && (bookOpen || revealShowing)) {", "CompositionLocalProvider(LocalCompanionLatticeArmed provides tomeArmed)")
        assertFalse("no scale", page.contains("scaleX") || page.contains("scaleY"))
        assertTrue("the rise is the swap tier's travel", page.contains("translationY = (1f - reveal) * MimirMotion.SwapTravelDp.dp.toPx()"))
        assertTrue("the page takes no touch through the lead", page.contains("Modifier.mimirPageTouchShield()"))
        // Audit 2026-10-14 (RC-03): the lattice's blur under the Tome has its own clock, held through the page's leave (rememberMimirGlassHold),
        // so the page's `reveal` no longer drives the blur.
        assertTrue("the glassdrop blur rides the Tome's own glass clock", surface.contains(".mimirGlassBackdropProgress(CompanionTomeBackdropBlurDp.dp) { tomeGlass() }"))
        assertTrue(surface.contains("val tomeGlass = rememberMimirHeldGlassProgress(visible = bookOpen)"))
        assertTrue("the arming reads the live cover and the reveal", surface.contains("latticeArmed() && reveal >= 1f && !tomeCovered"))
        assertTrue("both receipts keep their names", surface.contains("\"tome-open.enter\"") && surface.contains("\"tome-open.exit\""))
    }

    @Test
    fun `the rail under Tabs mirrors the drawer, and the scene blur keeps Main's clock`() {
        val surface = code(source(surfacePath))
        assertTrue(surface.contains("animationSpec = if (railCovered) mimirOverlayArriveSpec(railReducedMotion) else mimirOverlayLeaveSpec(),\n        label = \"quick-rail-covered\""))
        assertTrue("the surface's overlay blur is the one production function", surface.contains("val overlayBlur by rememberCompanionOverlayGlass("))
        val blur = between(surface, "internal fun rememberCompanionOverlayGlass(requested: Boolean): State<Float> {", "label = \"overlay-glass\"")
        assertTrue("the scene softens on OverlayMs, Main's own ramp", blur.contains("animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),"))
    }

    @Test
    fun `the four retired clocks are gone from production source`() {
        val retired = Regex("""\b(BookOpenMs|BookCloseMs|CompanionPresentationTransitionMs|CompanionOverlayGlassMs)\b""")
        val offenders = mutableListOf<String>()
        File(repoRoot(), ".").walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != ".agents" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .forEach { file ->
                code(file.readText()).lines().forEachIndexed { index, line ->
                    if (retired.containsMatchIn(line)) offenders += "${file.name}:${index + 1}: $line"
                }
            }
        assertEquals("a retired Bottom clock is back: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `no Bottom page takes the pad gate by default`() {
        val bottomFiles = listOf(
            surfacePath,
            "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionChoiceDialog.kt",
            "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionRecentsDrawer.kt",
            presentationPath,
            overlayHostPath,
            "app/src/main/kotlin/com/gruning/mimir/rewrite/BottomQuickLaunchPanelHost.kt",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionWorkspaceSurface.kt",
            "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionPlatformShelf.kt",
        )
        var calls = 0
        bottomFiles.forEach { path ->
            val text = code(source(path))
            assertFalse("$path holds the process-wide pad gate", text.contains("MimirTransitionInputGate") || text.contains("holdMimirInputUntilShown") || text.contains("rememberMimirGlassPanelProgress"))
            var from = 0
            while (true) {
                val at = text.indexOf("MimirSwapPageGate(", from)
                if (at < 0) break
                var depth = 0
                var end = at + "MimirSwapPageGate".length
                do {
                    when (text[end]) { '(' -> depth++; ')' -> depth-- }
                    end++
                } while (depth > 0)
                val call = text.substring(at, end)
                assertTrue("$path: a Bottom MimirSwapPageGate must pass holdPad = false: $call", call.contains("holdPad = false"))
                calls++
                from = end
            }
        }
        assertTrue("the scan found the Bottom's gates ($calls)", calls >= 10)
    }
}
