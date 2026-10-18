package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.mimirCrossSwapSpec
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first-run audit's onboarding findings (2026-10-15), proved on stepped frames of the composed output: the verse plays
 * ALONE first (O-10), a step handover is an even cross-fade (O-02) in-step both ways and at the step boundary, and the
 * FILES picture's glyph arrives with its card. Paused clock, 16 ms frames, each rasterised through [shootWindow]. Pixels
 * are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingCrossFadeTest {
    private val res = onboardingTestResources()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun write(block: () -> Unit) = Snapshot.withMutableSnapshot(block)

    private fun step(ms: Long) {
        if (ms > 0L) compose.mainClock.advanceTimeBy(ms)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun gray(bitmap: Bitmap, x: Int, y: Int): Float = bitmap.gray(x, y)

    private fun Bitmap.peak(rect: Rect, pad: Int = 8): Float {
        var best = 0f
        for (y in (rect.top.toInt() - pad)..(rect.bottom.toInt() + pad) step 2) {
            for (x in (rect.left.toInt() - pad)..(rect.right.toInt() + pad) step 2) best = maxOf(best, gray(this, x, y))
        }
        return best
    }

    private fun Bitmap.range(rect: Rect): Float {
        var lo = 255f
        var hi = 0f
        for (y in rect.top.toInt()..rect.bottom.toInt() step 2) {
            for (x in rect.left.toInt()..rect.right.toInt() step 2) {
                val g = gray(this, x, y)
                lo = minOf(lo, g)
                hi = maxOf(hi, g)
            }
        }
        return hi - lo
    }

    /** The spread of a coarse grid over the whole frame outside [except]: 0 where nothing but a flat ground is showing. */
    private fun Bitmap.gridRange(except: Rect): Float {
        var lo = 255f
        var hi = 0f
        for (gy in 1..11) {
            for (gx in 1..15) {
                val x = width * gx / 16
                val y = height * gy / 12
                if (x in (except.left.toInt() - 24)..(except.right.toInt() + 24) &&
                    y in (except.top.toInt() - 24)..(except.bottom.toInt() + 24)
                ) {
                    continue
                }
                val g = gray(this, x, y)
                lo = minOf(lo, g)
                hi = maxOf(hi, g)
            }
        }
        return hi - lo
    }

    private fun nodeBounds(matcher: androidx.compose.ui.test.SemanticsMatcher): Rect =
        compose.onNode(matcher).fetchSemanticsNode().boundsInWindow

    @androidx.compose.runtime.Composable
    private fun Flow(
        step: OnboardingStep,
        glide: OnboardingStepGlideState,
        accessReview: OnboardingAccessReviewState,
        intro: OnboardingWelcomeIntroState,
    ) {
        CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
            MimirTheme {
                Box(Modifier.fillMaxSize()) {
                    OnboardingFlow(
                        authority = DefaultFocusAuthority(),
                        step = step,
                        profile = OnboardingProfileState(),
                        stepGlide = glide,
                        accessReview = accessReview,
                        focusIntent = OnboardingFocusIntentState(),
                        access = OnboardingAccessUi(
                            usageAvailable = true,
                            usageGranted = false,
                            homeRoleAvailable = true,
                            homeRoleHeld = false,
                        ),
                        library = OnboardingLibraryState(),
                        libraryGameCount = 0,
                        libraryPlatformCount = 0,
                        welcomeIntro = intro,
                        artworkAccount = null,
                        steamGridDbKeyPresent = false,
                        settlement = OnboardingSettlementUi(
                            phase = OnboardingSettlementPhase.READY,
                            title = "Ready",
                            detail = "Ready",
                        ),
                        currentThemeId = MimirThemeId.DEFAULT,
                        onAdvance = {}, onCommitName = {}, onRequestFilesAccess = {}, onRequestUsageAccess = {},
                        onRequestHomeRole = {}, onChooseFolder = {}, onOpenArtworkSetup = {}, onOpenSteamGridDbKey = {},
                        onRetrySettlement = {}, onContinueLocally = {}, onApplyTheme = {},
                    )
                }
            }
        }
    }

    // ── (a) O-10: the verse plays alone ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the verse plays alone first - Begin and the sky arrive with the rest, and the verse itself fades in`() {
        compose.mainClock.autoAdvance = false
        val intro = OnboardingWelcomeIntroState()
        compose.setContent {
            Flow(OnboardingStep.WELCOME, remember { OnboardingStepGlideState(OnboardingStep.WELCOME) }, remember { OnboardingAccessReviewState() }, intro)
        }
        step(0L)
        val verse = nodeBounds(hasText("And still", substring = true))
        val begin = nodeBounds(hasClickAction() and (hasText("Begin", ignoreCase = true) or hasContentDescription("Begin", ignoreCase = true)))

        val first = compose.activity.shootWindow()
        val verseDark = first.peak(verse)
        val presence = ArrayList<Float>()
        val beginRanges = ArrayList<Float>()
        val gridRanges = ArrayList<Float>()
        val peaks = ArrayList<Float>()
        var t = 0L
        while (t < 720L) {
            step(16L)
            t += 16L
            val shot = compose.activity.shootWindow()
            peaks += shot.peak(verse)
            beginRanges += shot.range(begin)
            gridRanges += shot.gridRange(verse)
        }
        // The hold: well inside the verse's 2.5 s, before the rest arrives.
        step(1_600L)
        val held = compose.activity.shootWindow()
        val verseWhole = held.peak(verse)
        beginRanges += held.range(begin)
        gridRanges += held.gridRange(verse)
        // Alone: until the hold ends nothing but the verse is on a flat ground -- no Begin pill, no stars, no planet.
        assertTrue("Begin is not painted during the hold: $beginRanges", beginRanges.all { it < 4f })
        assertTrue("nothing else is painted during the hold (sky, planet, lockup, copy): $gridRanges", gridRanges.all { it < 4f })
        assertTrue("the verse shows by the hold: $verseDark -> $verseWhole", verseWhole - verseDark > 40f)
        peaks.forEach { presence += ((it - verseDark) / (verseWhole - verseDark)).coerceIn(0f, 1.2f) }
        println("ONB-VERSE presence per 16 ms frame: ${presence.joinToString { "%.2f".format(it) }}")
        println("ONB-VERSE begin range: ${beginRanges.joinToString { "%.0f".format(it) }}")
        println("ONB-VERSE grid range outside the verse: ${gridRanges.joinToString { "%.0f".format(it) }}")

        // The verse fades in on its clock: it starts at nothing, rises over several frames, and never cuts in.
        assertTrue("it starts at nothing: ${presence.first()}", presence.first() < 0.15f)
        assertTrue("through a real fade: $presence", presence.count { it in 0.1f..0.9f } >= 3)
        assertTrue("it rises and never falls: $presence", presence.zipWithNext().all { (a, b) -> b >= a - 0.06f })
        assertTrue("with no frame carrying it from nothing to whole: $presence", presence.zipWithNext().all { (a, b) -> b - a < 0.5f })

        // Then the rest arrives, together: Begin, the lockup, the copy and the sky.
        step(OnboardingWelcomeVerseAloneMs - 720L - 1_600L + OnboardingIntroShineMs + 160L)
        val rest = compose.activity.shootWindow()
        println("ONB-VERSE after the hold: begin range ${rest.range(begin)}, grid range ${rest.gridRange(verse)}")
        assertTrue("Begin has arrived: ${rest.range(begin)}", rest.range(begin) > 20f)
        assertTrue("so has the rest of the screen: ${rest.gridRange(verse)}", rest.gridRange(verse) > 4f)
    }

    // ── (b) O-02: a handover is an even cross-fade ───────────────────────────────────────────────────────────

    /**
     * An in-step host exactly as the product builds them: [onboardingGlide] without its own fade, each page fading itself
     * on [onboardingRingSafeFadeAlpha]. A red page and a blue page on white: with the blue one on top, the red channel is
     * the blue page's gap and the green channel is what is left of the white ground, so both alphas read off one pixel.
     */
    private fun crossFadeProbe(forward: Boolean) {
        var page by mutableStateOf(if (forward) 0 else 1)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        AnimatedContent(
                            targetState = page,
                            transitionSpec = {
                                onboardingGlide(forward = targetState > initialState, travelPx = 22, reducedMotion = false, fade = false)
                            },
                            label = "probe",
                        ) { presented ->
                            val alpha = onboardingRingSafeFadeAlpha(false)
                            Box(
                                Modifier.fillMaxSize()
                                    .onboardingRingSafeFade(alpha)
                                    .background(if (presented == 0) Color.Red else Color.Blue),
                            )
                        }
                    }
                }
            }
        }
        step(0L)
        step(400L)
        val atRest = compose.activity.shootWindow().getPixel(416, 234)
        val restBlue = android.graphics.Color.blue(atRest)
        val restRed = android.graphics.Color.red(atRest)
        assertTrue("the first page is whole before the swap", if (forward) restRed > 250 else restBlue > 250)

        write { page = if (forward) 1 else 0 }
        step(0L)
        val coverage = ArrayList<Float>()
        val leaving = ArrayList<Float>()
        val arriving = ArrayList<Float>()
        var t = 0L
        var settledAt = -1L
        while (t < 400L) {
            step(16L)
            t += 16L
            val p = compose.activity.shootWindow().getPixel(416, 234)
            val r = android.graphics.Color.red(p) / 255f
            val g = android.graphics.Color.green(p) / 255f
            val b = android.graphics.Color.blue(p) / 255f
            // Forward the blue page is on top (its gap is the red channel); back the red one is (its gap is the blue channel).
            val topGap = if (forward) r else b
            val top = 1f - topGap
            coverage += 1f - g
            arriving += top
            leaving += if (top < 0.9f) 1f - g / (1f - top) else Float.NaN
            if (settledAt < 0 && top > 0.99f && g < 0.02f) settledAt = t
        }
        println("ONB-XFADE forward=$forward coverage: ${coverage.joinToString { "%.2f".format(it) }}")
        println("ONB-XFADE forward=$forward arriving: ${arriving.joinToString { "%.2f".format(it) }}")
        println("ONB-XFADE forward=$forward leaving: ${leaving.joinToString { "%.2f".format(it) }}")
        val label = if (forward) "forward" else "back"
        assertTrue("$label: the pane never empties (about 0.75 at the midpoint): min coverage ${coverage.min()}", coverage.min() >= 0.72f)
        assertTrue("$label: the page left is still whole on the first frame, not cut: ${leaving.first()}", leaving.first() > 0.7f)
        arriving.indices.filter { !leaving[it].isNaN() }.forEach {
            assertEquals("$label: complementary at frame $it", 1f, arriving[it] + leaving[it], 0.06f)
        }
        assertTrue(
            "$label: the two are readable together for a brief overlap",
            arriving.indices.count { !leaving[it].isNaN() && arriving[it] > 0.15f && leaving[it] > 0.15f } >= 2,
        )
        assertTrue("$label: settled by the swap clock (${MimirMotion.OverlayMs} ms), not later: $settledAt", settledAt in 1L..(MimirMotion.OverlayMs + 32L))
    }

    @Test
    fun `an in-step handover is an even cross-fade going forward`() = crossFadeProbe(forward = true)

    @Test
    fun `an in-step handover is the same even cross-fade going back`() = crossFadeProbe(forward = false)

    /** The real flow across its step boundary: the step left and the step arriving are both on screen, and it is done by the swap clock. */
    @Test
    fun `the step boundary cross-fades - both steps are composed together and the new one is whole by the swap clock`() {
        var navigation by mutableStateOf(OnboardingStep.PROFILE)
        val glide = OnboardingStepGlideState(OnboardingStep.PROFILE)
        compose.mainClock.autoAdvance = false
        compose.setContent { Flow(navigation, glide, remember { OnboardingAccessReviewState() }, OnboardingWelcomeIntroState()) }
        step(0L)
        step(600L)
        // PROFILE's second pill is the one that only it has; both steps have a Continue.
        val notNow = hasClickAction() and (hasText("Not now", ignoreCase = true) or hasContentDescription("Not now", ignoreCase = true))
        val continues = hasClickAction() and (hasText("Continue", ignoreCase = true) or hasContentDescription("Continue", ignoreCase = true))
        fun dump(why: String) = why + " | " + compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 14)
        if (compose.onAllNodes(notNow).fetchSemanticsNodes().isEmpty()) throw AssertionError(dump("the profile step is up"))
        assertEquals("alone: one Continue", 1, compose.onAllNodes(continues).fetchSemanticsNodes().size)

        write { navigation = OnboardingStep.ACCESS }
        step(0L)
        step(16L)
        step(16L)
        assertTrue("the step left is still there", compose.onAllNodes(notNow).fetchSemanticsNodes().isNotEmpty())
        if (compose.onAllNodes(continues).fetchSemanticsNodes().size < 2) {
            throw AssertionError(dump("and the step arriving is there from the first frames, not after the exit"))
        }
        var t = 32L
        while (glide.displayedStep != OnboardingStep.ACCESS && t < 600L) {
            step(16L)
            t += 16L
        }
        println("ONB-STEP-XFADE displayed step switched at $t ms")
        assertTrue("the step on screen is the new one by the swap clock: $t ms", t <= MimirMotion.OverlayMs + 48L)
        step(32L)
        assertTrue("the step left is gone", compose.onAllNodes(notNow).fetchSemanticsNodes().isEmpty())
        assertEquals("and the new one is there", 1, compose.onAllNodes(continues).fetchSemanticsNodes().size)
    }

    // ── (c) O-16: the FILES picture's glyph fades with its card ──────────────────────────────────────────────

    @Test
    fun `the FILES glyph fades in with its card, not after it`() {
        var run by mutableStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.fillMaxSize().background(Color(0xFF808080)), contentAlignment = Alignment.Center) {
                        key(run) {
                            // Run 0 is the page arriving on the swap clock with its picture playing; run 1 is the card alone, whole,
                            // with the picture never played.
                            val card = remember { Animatable(if (run == 0) 0f else 1f) }
                            LaunchedEffect(Unit) { if (run == 0) card.animateTo(1f, mimirCrossSwapSpec(false)) }
                            Box(Modifier.graphicsLayer { alpha = card.value }) {
                                OnboardingAccessCapabilityScreenBody(
                                    palette = palette,
                                    copy = onboardingAccessFilesCopy(res),
                                    granted = false,
                                    grantedStatusLabel = "Allowed",
                                    playing = run == 0,
                                )
                            }
                        }
                    }
                }
            }
        }
        step(0L)
        val frames = ArrayList<Bitmap>()
        var t = 0L
        while (t < MimirMotion.OverlayMs + 96L) {
            step(16L)
            t += 16L
            frames += compose.activity.shootWindow()
        }
        step(2_000L)
        val whole = compose.activity.shootWindow()
        write { run = 1 }
        step(0L)
        step(64L)
        val cardOnly = compose.activity.shootWindow()

        val ground = 128f
        val glyph = ArrayList<Pair<Int, Int>>()
        val card = ArrayList<Pair<Int, Int>>()
        for (y in 0 until cardOnly.height step 2) {
            for (x in 0 until cardOnly.width step 2) {
                val z = gray(cardOnly, x, y)
                val w = gray(whole, x, y)
                if (abs(w - z) > 20f) glyph += x to y else if (abs(z - ground) > 20f) card += x to y
            }
        }
        println("ONB-GLYPH mask ${glyph.size} px, card ${card.size} px")
        assertTrue("the picture has a glyph that plays (${glyph.size} px) on a card (${card.size} px)", glyph.size > 20 && card.size > 100)

        val cardAlpha = frames.map { f -> card.map { (x, y) -> (gray(f, x, y) - ground) / (gray(cardOnly, x, y) - ground) }.average().toFloat() }
        val glyphAlpha = frames.indices.map { i ->
            val c = cardAlpha[i]
            if (c < 0.2f) Float.NaN else glyph.map { (x, y) ->
                val z = gray(cardOnly, x, y)
                val w = gray(whole, x, y)
                (((gray(frames[i], x, y) - ground) / c + ground) - z) / (w - z)
            }.average().toFloat()
        }
        println("ONB-GLYPH card alpha per 16 ms frame: ${cardAlpha.joinToString { "%.2f".format(it) }}")
        println("ONB-GLYPH glyph alpha per 16 ms frame: ${glyphAlpha.joinToString { "%.2f".format(it) }}")
        val landed = cardAlpha.indexOfFirst { it >= 0.97f }
        assertTrue("the card lands inside its clock: $cardAlpha", landed in 0..(MimirMotion.OverlayMs / 16 + 1))
        // Its first book slides in as it fades, so it is whole a frame or two after the card is; before this change the picture
        // waited the card's whole arrival and then some (360 ms) before it began.
        assertTrue(
            "the glyph is in within three frames of its card landing (it popped in after the card): ${glyphAlpha[landed + 3]}",
            glyphAlpha[landed + 3] >= 0.4f,
        )
    }
}
