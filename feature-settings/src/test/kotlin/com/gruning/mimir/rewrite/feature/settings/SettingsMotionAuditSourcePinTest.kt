package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-10-14 audit's Settings fixes that a frame test cannot see, as the module's idiom has them (source pins, anchored so a rename reddens), and the pure rule behind RC-11.
 * The behaviour itself is in [SettingsRailRingLeadsPaneTest], [SettingsSubPageSwapTest] and [ControllerMappingFadeFrameTest].
 */
class SettingsMotionAuditSourcePinTest {

    private fun source(name: String): String = listOf(
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the pane holds until the ring has reached the weight the pane starts at, on clocks that already exist`() {
        assertEquals("the settle clock: the ring is at the pane's start alpha at about 104 ms", 104.0, settingsDetailCommitLeadMs(420).toDouble(), 2.0)
        assertEquals("the traverse clock: two frames", 35.0, settingsDetailCommitLeadMs(140).toDouble(), 2.0)
        listOf(140, 420).forEach { ringMs ->
            val lead = settingsDetailCommitLeadMs(ringMs)
            assertTrue(
                "at the lead the ring has reached the pane's start alpha ($ringMs ms ring, lead $lead)",
                MimirMotion.Easing.transform(lead / ringMs.toFloat()) >= SettingsDetailGlideStartAlpha,
            )
            assertTrue(
                "and a millisecond sooner it has not, so the lead is the first such moment",
                lead == 0 || MimirMotion.Easing.transform((lead - 1) / ringMs.toFloat()) < SettingsDetailGlideStartAlpha,
            )
        }
        assertTrue("the traverse's lead is shorter than the settle's", settingsDetailCommitLeadMs(140) < settingsDetailCommitLeadMs(420))
        assertEquals(0, settingsDetailCommitLeadMs(0))
    }

    @Test
    fun `the rail follows the focus at once and only the pane trails it`() {
        val home = source("SettingsHome.kt")
        val body = home.substringAfter("fun SettingsHome(").substringBefore("private fun SettingsRailPane(")
        assertTrue(body.contains("val paneCategory = rememberSettingsPaneCategory("))
        assertEquals("both layouts' panes show the held category", 2, Regex("SettingsDetailPane\\(\\s+category = paneCategory,").findAll(body).count())
        assertEquals("both layouts' rails keep the live one", 2, Regex("SettingsRailPane\\(\\s+activeCategory = renderedActiveCategory,").findAll(body).count())
        val hold = home.substringAfter("private fun rememberSettingsPaneCategory(").substringBefore("internal fun settingsDetailCommitLeadMs(")
        assertTrue("it snaps when the rail is not the one moving, and under reduced motion", hold.contains("railMoves && !reducedMotion"))
        assertTrue("the hold is the ring's own clock", hold.contains("settingsDetailCommitLeadMs(mimirFocusRingMs())"))
        assertTrue("and restarts on every hop", hold.contains("LaunchedEffect(target, holds)"))
        assertTrue("it waits on the animation clock, so it scales with the animator as the ring does", hold.contains("mimirHold(settingsDetailCommitLeadMs(mimirFocusRingMs()))"))
        assertFalse("not a coroutine delay", hold.contains("delay("))
        val doc = home.substringBefore("private fun rememberSettingsPaneCategory(")
        assertFalse("a held pad's cruise builds the panes it passes: the KDoc no longer says it does not", doc.contains("composes the pane it stops on and none it passes (this also keeps"))
        assertFalse("and does not claim the category tail", doc.contains("RC-17's category tail, unmeasured"))
    }

    @Test
    fun `the sub-pages and the compact panes swap on the swap tier instead of replacing each other in one frame`() {
        val components = source("SettingsComponents.kt")
        val swap = components.substringAfter("internal fun <S> SettingsSubPageSwap(").substringBefore("/**\n * Shared Settings building blocks")
        // Audit 2026-10-15, F5: ONE symmetric swap on the Library view swap's clock (OverlayMs, no lead, complementary, 8 dp), not the swap tier's lead forward and dissolve-over return.
        assertTrue(swap.contains("transitionSpec = { mimirCrossSwapTransform(reduced, fade = false, returning = returning, travelPx = travelPx) },"))
        assertTrue("the travel is the house swap distance", swap.contains("MimirMotion.SwapTravelDp.dp.roundToPx()"))
        assertFalse("no bare togetherWith, so no default spring on the size", swap.contains("togetherWith"))
        assertTrue(swap.contains("mimirCrossSwapFadeAlpha(reduced)"))
        assertFalse("no lead forward and no return clock: the two directions are one swap", swap.contains("mimirSwapTransform(") || swap.contains("mimirSwapFadeAlpha("))
        assertTrue("the pages fade themselves, ring-safe", swap.contains("Modifier.mimirRingSafeFade(pageAlpha)"))
        assertTrue("a press waits for the page it would act on", swap.contains("MimirSwapPageGate(pageAlpha)"))

        val music = source("MusicPanel.kt")
        assertTrue(music.contains("SettingsSubPageSwap(\n        target = subPage,\n        returning = subPage == MusicSubPage.NONE,"))
        assertFalse("the sub-page `when` is no longer the pane's direct child", music.contains("Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {\n        when (subPage)"))

        val detail = source("SettingsDetail.kt")
        assertTrue(detail.contains("target = sourceRemovalConfirmation?.rootUri,"))
        assertFalse("the source-removal confirmation is no longer an early return", detail.contains("        return\n    }\n    val libraryLoading"))

        val home = source("SettingsHome.kt")
        val compact = home.substringAfter("if (compactSquare) {\n            // RC-34").substringBefore("        } else {\n            Row(")
        assertTrue("the target is the latched answer, not the raw scope", compact.contains("targetState = compactOnRail,"))
        assertTrue(compact.contains("transitionSpec = { mimirSwapTransform(swapReduced, fade = false, returning = compactOnRail) },"))
        assertFalse("no hard `if (railFocused)` cut between the two panes", compact.contains("            if (railFocused) {"))
    }

    @Test
    fun `a status chip's wash and the colour dial's detent size are read after composition`() {
        val components = source("SettingsComponents.kt")
        val chip = components.substringAfter("private fun SettingsChip(").substringBefore("/**\n * A row that comes and goes")
        assertTrue(chip.contains(".drawBehind { drawRect(ink.copy(alpha = 0.16f)) }"))
        assertFalse("not a composition-phase background", chip.contains(".background(ink"))

        val dial = source("MimirColorDial.kt").substringAfter("private fun DialDetent(").substringBefore("private fun stepDial(")
        assertTrue("the size is a State read in layout", dial.contains("val sizeState = animateDpAsState(") && dial.contains("sizeState.value.roundToPx()"))
        assertFalse("not `.size(size)` in composition", dial.contains(".size(size)"))
    }

    @Test
    fun `the capture card holds its action name and the exit sheet's scope opens from the command`() {
        val overlay = source("ControllerMappingOverlay.kt")
        assertFalse("no blank line while the card fades", overlay.contains("pendingActionName.orEmpty()"))
        assertTrue(overlay.contains("text = retainedActionName,"))
        val effect = overlay.substringAfter("LaunchedEffect(exitConfirmationVisible) {").substringBefore("DisposableEffect(authority)")
        assertTrue(effect.contains("openControllerMappingExitScope(authority)"))
        assertFalse("the push no longer waits a frame", effect.contains("withFrameNanos"))
    }

    /** Shared motion tokens govern these files; the detail-pane glide retains its own 280ms, 6dp values. */
    @Test
    fun `a value swap travels the house swap distance and no Settings scroll rides the default spring`() {
        val components = source("SettingsComponents.kt")
        assertTrue(components.contains("private val SettingsValueTravel = MimirMotion.SwapTravelDp.dp"))
        assertFalse("not the private 6 dp", components.contains("SettingsValueTravel = 6.dp"))
        listOf(
            "SettingsHome.kt", "SettingsDetailScrollBridge.kt", "ControllerMappingOverlay.kt", "MimirColorDial.kt",
        ).forEach { name ->
            val sites = source(name).lines().filter { it.contains(".animateScrollTo(") }
            assertTrue("$name animates a scroll", sites.isNotEmpty())
            sites.forEach { line ->
                assertTrue("$name: a scroll rides a token clock and the house ease, not the default spring: ${line.trim()}", line.contains("tween(MimirMotion."))
                assertTrue(line.contains("easing = MimirMotion.Easing"))
            }
        }
    }

    /** Fix round W1A #5: a foreign scope (Utility, a picker, the select overlays) never flips the compact pane or a Music sub-page. */
    @Test
    fun `the compact pane and the Music sub-page hold their last answer under a scope Settings does not own`() {
        val rail = SettingsRailScope
        val foreign = com.gruning.mimir.rewrite.core.input.FocusScope("test/utility")
        assertTrue("the rail shows the rail", settingsCompactOnRail(rail, railFocused = true, held = false))
        assertFalse("the detail shows the detail", settingsCompactOnRail(DetailScope, railFocused = false, held = true))
        assertFalse("a Music sub-page is the detail", settingsCompactOnRail(MusicBehaviourScope, railFocused = false, held = true))
        assertTrue("a foreign scope over the rail holds the rail", settingsCompactOnRail(foreign, railFocused = false, held = true))
        assertFalse("and over the detail holds the detail", settingsCompactOnRail(foreign, railFocused = false, held = false))

        assertEquals(MusicSubPage.BEHAVIOUR, musicSubPageUnder(MusicBehaviourScope, MusicSubPage.NONE))
        assertEquals(MusicSubPage.FORGET_FOLDER, musicSubPageUnder(MusicFolderForgetScope, MusicSubPage.NONE))
        assertEquals("B to the detail is the cards", MusicSubPage.NONE, musicSubPageUnder(DetailScope, MusicSubPage.BEHAVIOUR))
        assertEquals("and to the rail", MusicSubPage.NONE, musicSubPageUnder(SettingsRailScope, MusicSubPage.FORGET_FOLDER))
        assertEquals("a foreign scope holds the sub-page", MusicSubPage.BEHAVIOUR, musicSubPageUnder(foreign, MusicSubPage.BEHAVIOUR))
        assertEquals(MusicSubPage.NONE, musicSubPageUnder(foreign, MusicSubPage.NONE))
    }
}
