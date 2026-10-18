package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The small fixes of the 2026-10-14 audit's lane L2 that have no frame to render in a JVM test (a layer's compositing, a cached
 * brush, an easing argument) or are proved elsewhere by behaviour and need only their wiring held. Each reads the production source
 * and would have failed on the code the audit found.
 */
class AuditL2SmallFixesSourcePinTest {

    private fun source(vararg candidates: String): String =
        candidates.map(::File).first(File::isFile).readText().lineSequence().joinToString("\n") { it.substringBefore("//") }

    private val carousel by lazy {
        source(
            "src/main/kotlin/com/gruning/mimir/rewrite/OnboardingHintCarousel.kt",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/OnboardingHintCarousel.kt",
        )
    }

    private val bar by lazy {
        source(
            "../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirIndeterminateBar.kt",
            "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirIndeterminateBar.kt",
        )
    }

    @Test
    fun `O-18 the usage-access faces fade on ModulateAlpha, so the centre ring is not cut mid-fade`() {
        val row = carousel.substringAfter("OnboardingHintScene.ACCESS_USAGE -> Box(").substringBefore("OnboardingHintPanel(")
        assertTrue(
            "the Row that holds the three faces fades per draw, not through an offscreen layer",
            Regex("""graphicsLayer \{\s*alpha = demo\(\)\s*compositingStrategy = CompositingStrategy\.ModulateAlpha\s*\}""").containsMatchIn(row),
        )
        assertTrue("and the centre face still wears its ring", row.contains("mimirFocusTileRing(progress = demo"))
    }

    @Test
    fun `O-15a the peek fade builds its brush once per size, not once per frame`() {
        val fade = carousel.substringAfter("private fun Modifier.onboardingHintPeekFade(").substringBefore("/** How many pages")
        assertTrue(fade.contains(".drawWithCache {"))
        val cache = fade.substringAfter(".drawWithCache {").substringBefore("onDrawWithContent {")
        assertTrue("the brush is built in the cache block", cache.contains("Brush.horizontalGradient("))
        val draw = fade.substringAfter("onDrawWithContent {")
        assertFalse("and none is built in the draw block", draw.contains("Brush.") || draw.contains("listOf("))
        assertFalse(fade.contains("drawWithContent {"))
    }

    @Test
    fun `X-02 O-25 S-51 the indeterminate bar caches its brush and builds no list or brush per frame`() {
        assertTrue(bar.contains("drawWithCache {"))
        val cache = bar.substringAfter("drawWithCache {").substringBefore("onDrawBehind {")
        assertTrue("the gradient and its colour list live in the cache block", cache.contains("Brush.horizontalGradient(") && cache.contains("listOf("))
        val draw = bar.substringAfter("onDrawBehind {").substringBefore("/**\n * Shared with")
        assertFalse("nothing is allocated in the draw block", draw.contains("Brush.") || draw.contains("listOf(") || draw.contains("Color.Transparent"))
        assertTrue("each frame only translates the cached brush", draw.contains("translate(left = x - half, top = 0f)"))
        assertTrue("the reduced-motion guard still comes first", bar.indexOf("if (reducedMotion) {") < bar.indexOf("rememberInfiniteTransition("))
    }

    @Test
    fun `X-20 the performance HUD fades on the house easing both ways`() {
        val hud = source(
            "src/main/kotlin/com/gruning/mimir/rewrite/PerformanceOverlayPresenter.kt",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/PerformanceOverlayPresenter.kt",
        )
        assertTrue(hud.contains("enter = fadeIn(tween(if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)),"))
        assertTrue(hud.contains("exit = fadeOut(tween(if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)),"))
    }

    @Test
    fun `X-70 Q6 the trackpad pointer's idle fade is the overlay clock on the house curve`() {
        val service = source(
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt",
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirTrackpadAccessibilityService.kt",
        )
        assertTrue("the clock is a token, not 200", service.contains("const val FadeMs = MimirMotion.OverlayMs.toLong()"))
        assertFalse(service.contains("FadeMs = 200L"))
        val fade = service.substringAfter("private val fadeOut = Runnable {").substringBefore("\n    }\n")
        assertTrue(fade.contains("setDuration(if (reducedMotion()) 0L else FadeMs)"))
        assertTrue("and the curve is the house one, not the platform default", fade.contains("setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f))"))
    }

    @Test
    fun `L-40c X-36 Browse's keyboard is a sheet, composed through its exit and wired only while open`() {
        val browse = source(
            "../feature-browse/src/main/kotlin/com/gruning/mimir/rewrite/feature/browse/BrowseScreen.kt",
            "feature-browse/src/main/kotlin/com/gruning/mimir/rewrite/feature/browse/BrowseScreen.kt",
        )
        val screen = browse.substringBefore("fun dispatchToBrowse(")
        assertFalse("the keyboard is not mounted by an `if`", screen.contains("if (keyboardOpen) {\n            val host"))
        val sheet = screen.substringAfter("MimirSheetHost(")
        assertTrue(sheet.contains("visible = keyboardOpen,"))
        assertTrue(sheet.contains("reducedMotion = mimirReducedMotion(),"))
        assertTrue("the host is null once closed, so a leaving keyboard has nothing wired", screen.indexOf("rememberBrowseMimirKeyboardHost(authority, keyboardOpen)") < screen.indexOf("MimirSheetHost("))
        assertEquals(1, Regex("""MimirKeyboard\(""").findAll(screen).count())
    }

    @Test
    fun `X-65 X-73 the consent card and the key rows are one AnimatedContent, with the size held`() {
        val keyboard = source(
            "../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt",
            "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirKeyboard.kt",
        )
        assertTrue(keyboard.contains("targetState = consentShown,"))
        assertTrue(keyboard.contains("SizeTransform(clip = false) { _, _ -> snap() }"))
        assertTrue("departing rows register nothing", keyboard.contains("controller = if (live) controller else null,") && keyboard.contains("keyDecorator = if (live) keyDecorator else null,"))
        assertTrue("the ghost-free identity census still finds its sites", keyboard.contains("keyRows.forEach { row ->") && keyboard.contains("row.forEach { key ->"))
    }
}
