package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The RetroAchievements card's LAYOUT contract, on device.
 *
 * These are regression locks for four defects the regression report described on RP5 and that
 * `/tmp/mimir-ra-card-final.png` shows directly:
 *
 *  1. The honesty footer had been squeezed to zero height. The card's `Column` measured the unweighted
 *     `LazyColumn` first, it took every remaining pixel, and `EarnedInEmulator` — the one line stating
 *     that Mimir cannot unlock anything — was measured at `maxHeight = 0` and never drawn. On a 43-row
 *     set it was invisible on the device. That is an honesty defect, not a cosmetic one.
 *  2. Nothing signalled that the list continued below the fold.
 *  3. Long achievement copy lost its tail.
 *  4. The bottom of the card had no material at all.
 *
 * The continuation assertions are deliberately DIFFERENTIAL — the same band of the same card, before and
 * after a scroll — because the panel already carries a top-to-bottom gradient of its own, so comparing a
 * top strip against a bottom strip inside a single frame would measure the material rather than the cap.
 */
@RunWith(AndroidJUnit4::class)
class MimirAchievementCardLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A full set, in the geometry the card actually ships in: a bounded modal box. The footer must
     * survive it.
     */
    @Test
    fun honestyFooterSurvivesAFullSetOfAchievements() {
        mountCard(rows = achievementRows(count = 43))

        compose.onNodeWithText(MimirAchievementCopy.EarnedInEmulator).assertIsDisplayed()
        compose.onNodeWithText(LongSetTitle).assertIsDisplayed()
    }

    /**
     * The footer is the load-bearing honesty line, so it cannot be a property of the Loaded state alone.
     * Every state the reader can land on has to carry it.
     */
    @Test
    fun everyStateCarriesTheHonestyLine() {
        val states = listOf(
            MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking),
            MimirAchievementCardState.Unknown(MimirAchievementCopy.Unreachable),
            MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials),
            MimirAchievementCardState.Unknown(MimirAchievementCopy.SystemUnsupported),
            MimirAchievementCardState.Unknown(MimirAchievementCopy.IdentificationUnavailable),
            MimirAchievementCardState.NotMatched(MimirAchievementCopy.NoSetForGame),
            loadedState(emptyList()),
            loadedState(achievementRows(count = 43)),
        )
        // Driven through ONE composition rather than one per state: the rule permits a single
        // setContent, and swapping the state in place is also the truer test — it exercises the card
        // re-rendering across a state change, which is exactly what a live lookup does.
        var index by mutableStateOf(0)
        compose.setContent {
            MimirTheme(MimirThemeId.DEFAULT) {
                Box(
                    Modifier
                        .width(CardWidth + MimirSpacing.xl)
                        .height(CardHeight + MimirSpacing.xl)
                        .background(DefaultPalette.surfaceBase.copy(alpha = 0.92f))
                        .testTag(ScrimTag),
                    contentAlignment = Alignment.Center,
                ) {
                    MimirAchievementCard(state = states[index], reducedMotion = true)
                }
            }
        }
        states.indices.forEach { at ->
            compose.runOnIdle { index = at }
            compose.waitForIdle()
            compose.onNodeWithText(MimirAchievementCopy.EarnedInEmulator).assertIsDisplayed()
            compose.onNodeWithTag(ScrimTag).captureToImage().writeEvidencePng("ra-card-state-$at.png")
        }
    }

    /**
     * Localised achievement copy is the stress case: RA descriptions already carry full condition lists in
     * English, and a translation can run half again as long while stacking diacritics that a font-derived
     * line height clips. The card must absorb it without evicting its own chrome.
     */
    @Test
    fun veryLongLocalisedCopyDoesNotEvictTheChrome() {
        mountCard(
            rows = List(20) { index ->
                MimirAchievementRow(
                    id = index,
                    title = LongLocalisedTitle,
                    description = LongLocalisedDescription,
                    points = 100,
                    medal = if (index % 2 == 0) MimirMedalState.LOCKED else MimirMedalState.EARNED_HARDCORE,
                )
            },
        )

        compose.onNodeWithText(MimirAchievementCopy.EarnedInEmulator).assertIsDisplayed()
        compose.onNodeWithText(LongSetTitle).assertIsDisplayed()
        assertTrue(
            "the ledger must still paint with pathological copy",
            compose.onNodeWithTag(MimirAchievementLedgerTestTag).captureToImage().meanLuminance() > 0.0,
        )
    }

    /**
     * The core of the brief: the continuation affordance has to be TRUE, not decorative. At rest there is
     * content below and none above, so only the bottom edge is capped; scrolled to the end that reverses.
     */
    @Test
    fun continuationCapsFollowRealScrollPosition() {
        var scrollTo by mutableStateOf(-1)
        compose.setContent {
            val listState = rememberLazyListState()
            LaunchedEffect(scrollTo) { if (scrollTo >= 0) listState.scrollToItem(scrollTo) }
            MimirTheme(MimirThemeId.DEFAULT) {
                Box(
                    Modifier
                        .width(CardWidth + MimirSpacing.xl)
                        .height(CardHeight + MimirSpacing.xl)
                        .background(DefaultPalette.surfaceBase.copy(alpha = 0.92f))
                        .testTag(ScrimTag),
                    contentAlignment = Alignment.Center,
                ) {
                    MimirAchievementCard(
                        state = loadedState(achievementRows(count = 43)),
                        listState = listState,
                        // Snap the caps so the capture is not racing a 140ms fade.
                        reducedMotion = true,
                    )
                }
            }
        }
        compose.waitForIdle()

        val atTop = compose.onNodeWithTag(MimirAchievementLedgerTestTag).captureToImage()
        val topBandAtTop = atTop.bandLuminance(fromTop = true)
        val bottomBandAtTop = atTop.bandLuminance(fromTop = false)
        compose.onNodeWithTag(ScrimTag).captureToImage().writeEvidencePng("ra-card-at-rest.png")

        compose.runOnIdle { scrollTo = 42 }
        compose.waitForIdle()

        val atEnd = compose.onNodeWithTag(MimirAchievementLedgerTestTag).captureToImage()
        val topBandAtEnd = atEnd.bandLuminance(fromTop = true)
        val bottomBandAtEnd = atEnd.bandLuminance(fromTop = false)
        compose.onNodeWithTag(ScrimTag).captureToImage().writeEvidencePng("ra-card-scrolled-to-end.png")

        assertTrue(
            "the bottom edge must be capped while rows continue below it " +
                "(bottom band $bottomBandAtTop at rest vs $bottomBandAtEnd at the end)",
            bottomBandAtTop < bottomBandAtEnd - BandMargin,
        )
        assertTrue(
            "the top edge must cap once rows scroll above it " +
                "(top band $topBandAtTop at rest vs $topBandAtEnd at the end)",
            topBandAtEnd < topBandAtTop - BandMargin,
        )
    }

    /**
     * The other half of "truthful": a page whose rows all fit must NOT advertise more. A permanently-on
     * fade is exactly the decorative affordance this redesign is replacing.
     */
    @Test
    fun aPageThatFitsAdvertisesNothingBelowIt() {
        lateinit var shortState: LazyListState
        compose.setContent {
            shortState = rememberLazyListState()
            MimirTheme(MimirThemeId.DEFAULT) {
                Box(Modifier.width(CardWidth).height(CardHeight)) {
                    MimirAchievementCard(
                        state = loadedState(achievementRows(count = 2)),
                        listState = shortState,
                        reducedMotion = true,
                    )
                }
            }
        }
        compose.waitForIdle()

        assertTrue(
            "a list that fits must not be scrollable, so neither cap may be drawn",
            !shortState.canScrollForward && !shortState.canScrollBackward,
        )
        assertTrue(
            "a short page still has to draw its rows",
            compose.onNodeWithTag(MimirAchievementLedgerTestTag).captureToImage().meanLuminance() > 0.0,
        )
    }

    /**
     * NO ACHIEVEMENT TEXT IS EVER DISCARDED.
     *
     * The regression report described severe text clipping; raising the row caps (title 1→2, description 2→3) was not
     * a fix, only a longer leash — a condition list cut at "…destroying at least 5 aircraft …" has had the
     * actual requirement deleted. The row now carries no `maxLines` and no overflow at all, and page
     * length is the bounded viewport's job.
     *
     * This asserts the real [TextLayoutResult], NOT the semantics string. `onNodeWithText` would pass with
     * `maxLines = 3` still in place, because Compose keeps the full text in semantics and only truncates
     * the *drawing* — so a text-matching assertion would have pinned nothing at all. `hasVisualOverflow`
     * is the property that actually knows whether glyphs were dropped.
     */
    @Test
    fun noAchievementTextIsEverDiscarded() {
        mountCard(
            rows = listOf(
                MimirAchievementRow(
                    id = 0,
                    title = OverlongTitle,
                    description = OverlongDescription,
                    points = 25,
                    medal = MimirMedalState.EARNED,
                ),
            ),
        )

        val title = compose.onNodeWithText(OverlongTitle).textLayout()
        assertFalse("the achievement title dropped glyphs", title.hasVisualOverflow)
        assertTrue(
            "the fixture must actually exceed the old 2-line title cap, else this pins nothing " +
                "(measured ${title.lineCount} lines)",
            title.lineCount > 2,
        )

        compose.onNodeWithTag(MimirAchievementLedgerTestTag).captureToImage()
            .writeEvidencePng("ra-card-uncapped-row.png")

        val description = compose.onNodeWithText(OverlongDescription).textLayout()
        assertFalse("the achievement description dropped glyphs", description.hasVisualOverflow)
        assertTrue(
            "the fixture must actually exceed the old 3-line description cap, else this pins nothing " +
                "(measured ${description.lineCount} lines)",
            description.lineCount > 3,
        )
    }

    /**
     * The same no-loss rule under the case that motivated it: a whole set of overlong LOCALISED rows, in
     * a viewport far too small for one of them. The text must still be whole, and the fixed chrome must
     * still be there — length is the scroller's problem, not the sentence's.
     */
    @Test
    fun noLossHoldsForAFullSetOfOverlongLocalisedRows() {
        mountCard(
            rows = List(20) { index ->
                MimirAchievementRow(
                    id = index,
                    title = LongLocalisedTitle,
                    description = LongLocalisedDescription,
                    points = 100,
                    medal = if (index % 2 == 0) MimirMedalState.LOCKED else MimirMedalState.EARNED_HARDCORE,
                )
            },
        )

        // onAllNodes: the same string repeats down the set, so the first composed row is the sample.
        val first = compose.onAllNodesWithText(LongLocalisedDescription)[0].textLayout()
        assertFalse("a localised description dropped glyphs", first.hasVisualOverflow)
        compose.onNodeWithText(MimirAchievementCopy.EarnedInEmulator).assertIsDisplayed()
    }

    // ── harness ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The node's real text layout. Reaches for [SemanticsActions.GetTextLayoutResult] rather than any
     * assertion on the text string, because only the layout knows whether anything was actually cut.
     */
    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.firstOrNull() ?: error("no text layout result was produced")
    }

    private fun mountCard(rows: List<MimirAchievementRow>) {
        compose.setContent {
            MimirTheme(MimirThemeId.DEFAULT) {
                // The production mount is a centred modal inside a fillMaxSize box, which is height
                // BOUNDED. The card's ledger is weighted, and a weighted child of an unbounded column
                // resolves to zero — so a test that mounted this in a wrap-height column would be testing
                // a geometry the app never produces.
                Box(Modifier.width(CardWidth).height(CardHeight)) {
                    MimirAchievementCard(state = loadedState(rows), reducedMotion = true)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun loadedState(rows: List<MimirAchievementRow>) = MimirAchievementCardState.Loaded(
        gameTitle = LongSetTitle,
        unlocked = rows.count { it.medal != MimirMedalState.LOCKED },
        total = rows.size,
        earnedPoints = 0,
        totalPoints = rows.sumOf { it.points },
        rows = rows,
    )

    private fun achievementRows(count: Int) = List(count) { index ->
        MimirAchievementRow(
            id = index,
            title = "Achievement $index",
            description = "Complete Mission #$index on Ace difficulty while using the SI-40, without " +
                "using any special missiles or refueling, and while destroying at least 5 aircraft.",
            points = 10,
            medal = if (index == 0) MimirMedalState.EARNED else MimirMedalState.LOCKED,
        )
    }

    /**
     * Mean luminance of the outer [BandRows] pixel rows at one edge. Kept to the outermost rows because
     * that is where the cap gradient is at full strength and where row text almost never reaches — the
     * ledger's own content padding holds it clear.
     */
    private fun ImageBitmap.bandLuminance(fromTop: Boolean): Double {
        val pixels = toPixelMap()
        val rows = BandRows.coerceAtMost(height)
        val range = if (fromTop) 0 until rows else (height - rows) until height
        var total = 0.0
        var counted = 0
        for (y in range) {
            for (x in 0 until width) {
                val argb = pixels[x, y].toArgb()
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                total += 0.2126 * r + 0.7152 * g + 0.0722 * b
                counted++
            }
        }
        return if (counted == 0) 0.0 else total / counted
    }

    /** Writes a visual frame to the same capture sink as the medal evidence. */
    private fun ImageBitmap.writeEvidencePng(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ra-card-evidence").apply { mkdirs() }
        return File(directory, name).also { file ->
            FileOutputStream(file).use { output ->
                check(asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
            println("MIMIR_RA_CARD_EVIDENCE=${file.absolutePath}")
        }
    }

    private fun ImageBitmap.meanLuminance(): Double {
        val pixels = toPixelMap()
        var total = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixels[x, y].toArgb()
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                total += 0.2126 * r + 0.7152 * g + 0.0722 * b
            }
        }
        return total / (width * height)
    }

    /** The Default identity's real surface — the scrim the card is actually captured against. */
    private val DefaultPalette = MimirThemePalette.all.first { it.id == MimirThemeId.DEFAULT }

    private companion object {
        const val ScrimTag = "ra_card_scrim_evidence"
        val CardWidth = 520.dp
        val CardHeight = 460.dp

        /** How many pixel rows at an edge the band assertion samples. */
        const val BandRows = 6

        /**
         * The cap paints `coverBottom` at 0.94 alpha, so the real delta is large. The margin only has to
         * clear the noise from different row text scrolling through the band.
         */
        const val BandMargin = 1.5

        const val LongSetTitle = "Ace Combat Advance"

        /**
         * Past the old 2-line title cap at 520dp. Long RA titles like this are real — the set-authoring
         * convention allows a whole sentence — and the localisation of one is longer still.
         */
        const val OverlongTitle =
            "Complete Every Mission In The Usean Continental War Campaign On Ace Difficulty Without " +
                "Ever Refuelling, Losing A Wingman, Or Firing A Single Special Weapon"

        /** Past the old 3-line description cap: a full RA condition list, unabridged. */
        const val OverlongDescription =
            "Complete Mission #12 on Ace difficulty while using the Y-25C JSF, without refuelling at any " +
                "point during the sortie, while destroying at least 40 enemy aircraft including every " +
                "member of the escort wing, while keeping your own airframe above sixty percent " +
                "integrity for the whole engagement, and while finishing the boss encounter inside the " +
                "posted par time without ever dropping below the hard deck or using a special weapon."

        /** Vietnamese — stacked diacritics are what a font-derived line height clips first. */
        const val LongLocalisedTitle =
            "Hoàn thành nhiệm vụ với chiếc SI-40 mà không tiếp nhiên liệu"

        /** German compounding plus a full condition list — the realistic worst case for wrapping. */
        const val LongLocalisedDescription =
            "Schließe Mission #12 auf dem Schwierigkeitsgrad Ass ab, während du die Y-25C JSF " +
                "verwendest, ohne aufzutanken, und zerstöre dabei mindestens 40 Flugzeuge sowie den " +
                "Endgegner der Luftüberlegenheitsstaffel."
    }
}
