package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  the RetroAchievements MEDALS, rendered at real size on real hardware.
 *
 * Medals appear only in [MimirAchievementCardState.Loaded], which on a live device requires
 * an RA account. Synthetic data exercises the draw path without handling credentials.
 *
 * The way through is that [MimirAchievementCardState.Loaded] is a plain data class. Constructing it with
 * synthetic rows renders the REAL card and the REAL [MimirAchievementMedal] draw — no account, no network,
 * no stored key — while still executing on the device's own Skia at the device's own density. That is a
 * genuine render verification, not a mock: nothing about the drawing path is stubbed.
 *
 * Existing Compose UI-test infrastructure in this module supports these render checks.
 *
 * WHAT IS PINNED, all grounded in the component's own stated contract:
 *  1. The three medal states paint DISTINCTLY — [MimirMedalState] says hardcore is "a distinct strike,
 *     not a color swap", so a pixel-identical pair would break the documented design.
 *  2. Locked RECEDES but survives — `drawMimirMedal` gives locked `alpha = 0.38f` precisely so the user
 *     "can see the shape of what they have not earned yet".
 *  3. No SUCCESS-GREEN on the Default identity — the color law reserves green for success only, and an
 *     earned medal is an achievement, not a success toast. Scoped to Default deliberately: Yggdrasil's
 *     palette is legitimately green, so a global no-green assertion would be false rather than strict.
 */
@RunWith(AndroidJUnit4::class)
class MimirAchievementMedalVisualTest {

    @get:Rule
    val compose = createComposeRule()

    /** The Default identity's real surface — what the medals actually sit on in the shipped card. */
    private val DefaultPalette = MimirThemePalette.all.first { it.id == MimirThemeId.DEFAULT }

    @Test
    fun medalStatesRenderDistinctlyAndCarryNoSuccessGreen() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme(MimirThemeId.DEFAULT) {
                Row(
                    Modifier
                        .padding(12.dp)
                        .testTag(MedalRowTag),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Each medal sits on the app's real surface colour rather than the bare test window.
                    // That matters for the "recedes" assertion below: locked draws at alpha 0.38, so what
                    // it recedes TOWARD has to be the actual backdrop, not whatever the harness defaults to.
                    MimirMedalState.entries.forEach { state ->
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(DefaultPalette.surfaceBase)
                                .testTag(MedalTagPrefix + state.name),
                            contentAlignment = Alignment.Center,
                        ) {
                            MimirAchievementMedal(state = state)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        val byState = MimirMedalState.entries.associateWith { state ->
            compose.onNodeWithTag(MedalTagPrefix + state.name).captureToImage()
        }

        // 1. Three distinct strikes.
        val hashes = byState.mapValues { (_, image) -> image.pixelHash() }
        assertEquals(
            "each medal state must paint a distinct result — hardcore is a strike, not a colour swap",
            MimirMedalState.entries.size,
            hashes.values.toSet().size,
        )

        // 2. Locked RECEDES TOWARD THE BACKDROP, and is still drawn.
        //
        // "Recedes" is a statement about CONTRAST against the surface, not about absolute luminance. The
        // first cut of this test asserted `locked < earned` and failed on device with locked at 153 vs
        // earned at 119 — correctly, because locked's alpha 0.38 lets a light backdrop through and makes
        // the captured region BRIGHTER. The component was right and the assertion was wrong; this is the
        // corrected form, and it is why the medals now render on DefaultPalette.surfaceBase above.
        val backdrop = DefaultPalette.surfaceBase.luminance8Bit()
        val lockedLuma = byState.getValue(MimirMedalState.LOCKED).meanLuminance()
        val earnedLuma = byState.getValue(MimirMedalState.EARNED).meanLuminance()
        val lockedContrast = kotlin.math.abs(lockedLuma - backdrop)
        val earnedContrast = kotlin.math.abs(earnedLuma - backdrop)
        assertTrue(
            "locked must recede toward the surface: locked contrast $lockedContrast " +
                "must be below earned contrast $earnedContrast (backdrop $backdrop)",
            lockedContrast < earnedContrast,
        )
        assertTrue(
            "locked must still be DRAWN, not erased — it shows the shape of what is unearned " +
                "(contrast $lockedContrast)",
            lockedContrast > 0.0,
        )

        // 3. No success-green anywhere on the Default identity.
        byState.forEach { (state, image) ->
            val green = image.greenDominantPixelCount()
            assertEquals("$state medal must carry no success-green pixels", 0, green)
        }

        val evidence = compose.onNodeWithTag(MedalRowTag).captureToImage()
            .writeEvidencePng("ra-medals-three-states.png")
        assertTrue("medal evidence PNG was not written", evidence.isFile && evidence.length() > 0L)
    }

    /**
     * The medals in situ — the whole card in [MimirAchievementCardState.Loaded], which is the state the
     * device could not reach without an account. This captures the previously unverified loaded state.
     */
    @Test
    fun loadedAchievementCardRendersMedalsInSitu() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme(MimirThemeId.DEFAULT) {
                // Height-BOUNDED and on the real modal SCRIM, matching the actual mount (a centred card
                // in a fillMaxSize box over `surfaceBase` at 0.92). Both halves matter:
                //  - the card's ledger body is weighted so its fixed foot band can never be squeezed out
                //    again, and a weighted child of an unbounded column resolves to zero — so the
                //    wrap-height column this test used to mount measured a geometry the app never makes;
                //  - the panel body is translucent, so captured against the harness's bare window it
                //    washes out to grey and the evidence PNG misrepresents the material.
                Box(
                    Modifier
                        .width(560.dp)
                        .height(500.dp)
                        .background(DefaultPalette.surfaceBase.copy(alpha = 0.92f))
                        .testTag(CardTag),
                    contentAlignment = Alignment.Center,
                ) {
                    MimirAchievementCard(
                        state = MimirAchievementCardState.Loaded(
                            gameTitle = "Castlevania — Aria of Sorrow",
                            unlocked = 2,
                            total = 3,
                            earnedPoints = 310,
                            totalPoints = 1_200,
                            rows = listOf(
                                MimirAchievementRow(
                                    id = 1,
                                    title = "Soul Collector",
                                    description = "Absorb thirty distinct souls.",
                                    points = 10,
                                    medal = MimirMedalState.EARNED,
                                ),
                                MimirAchievementRow(
                                    id = 2,
                                    title = "Chaotic Realm",
                                    description = "Reach the Chaotic Realm without dying.",
                                    points = 25,
                                    medal = MimirMedalState.EARNED_HARDCORE,
                                ),
                                MimirAchievementRow(
                                    id = 3,
                                    title = "True Ending",
                                    description = "Finish the game with the true ending.",
                                    points = 50,
                                    medal = MimirMedalState.LOCKED,
                                ),
                            ),
                        ),
                        platformIdValue = "gba",
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        val card = compose.onNodeWithTag(CardTag).captureToImage()
        assertTrue("the Loaded card must actually paint", card.meanLuminance() > 0.0)
        assertEquals(
            "the Loaded card must carry no success-green — progress is not a success toast",
            0,
            card.greenDominantPixelCount(),
        )

        val evidence = card.writeEvidencePng("ra-card-loaded-with-medals.png")
        assertTrue("card evidence PNG was not written", evidence.isFile && evidence.length() > 0L)
    }

    /** Same 0-255 luminance space as [meanLuminance], so backdrop and capture are comparable. */
    private fun androidx.compose.ui.graphics.Color.luminance8Bit(): Double {
        val argb = toArgb()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun ImageBitmap.pixelHash(): Int {
        val pixels = toPixelMap()
        var hash = 17
        for (y in 0 until height) {
            for (x in 0 until width) hash = 31 * hash + pixels[x, y].toArgb()
        }
        return hash
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

    /**
     * Counts pixels where green genuinely dominates both other channels by a clear margin. The margin
     * matters: the book's gold hardware is high in red AND green, so a naive `g > r` test would flag every
     * gold pixel. Only a pixel that reads as green to the eye is counted.
     */
    private fun ImageBitmap.greenDominantPixelCount(): Int {
        val pixels = toPixelMap()
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = pixels[x, y].toArgb()
                val a = (argb shr 24) and 0xFF
                if (a < 8) continue
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (g > r + GreenMargin && g > b + GreenMargin) count++
            }
        }
        return count
    }

    private fun ImageBitmap.writeEvidencePng(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "ra-medal-evidence").apply { mkdirs() }
        return File(directory, name).also { file ->
            FileOutputStream(file).use { output ->
                check(asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
            println("MIMIR_RA_MEDAL_EVIDENCE=${file.absolutePath}")
        }
    }

    private companion object {
        const val MedalRowTag = "ra_medal_row_evidence"
        const val MedalTagPrefix = "ra_medal_"
        const val CardTag = "ra_card_loaded_evidence"
        const val GreenMargin = 24
    }
}
