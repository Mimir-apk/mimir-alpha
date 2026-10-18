package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NineThemeAtmosphereVisualTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nineThemesRenderDistinctAndRemainStaticAtIdle() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(
                Modifier
                    .width(600.dp)
                    .height(360.dp)
                    .testTag(EvidenceTag),
            ) {
                MimirThemePalette.all.chunked(3).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        row.forEach { palette ->
                            MimirTheme(palette.id) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    MimirShelfBackground(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag(AtmosphereTagPrefix + palette.id.storageKey),
                                        palette = palette,
                                    ) {}
                                    Text(
                                        text = palette.id.label,
                                        color = palette.textPrimary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                                    )
                                    Box(
                                        Modifier
                                            .align(Alignment.Center)
                                            .size(width = 74.dp, height = 48.dp)
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        palette.materials.hero.coverTop,
                                                        palette.materials.hero.coverMiddle,
                                                        palette.materials.hero.coverBottom,
                                                    ),
                                                ),
                                                RoundedCornerShape(6.dp),
                                            )
                                            .border(1.dp, palette.tileRing, RoundedCornerShape(6.dp)),
                                    )
                                    Row(
                                        Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Box(Modifier.size(5.dp).background(palette.accent, RoundedCornerShape(2.dp)))
                                        Box(
                                            Modifier.size(5.dp).background(
                                                palette.materials.counterAccent,
                                                RoundedCornerShape(2.dp),
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.mainClock.advanceTimeByFrame()
        val first = compose.onNodeWithTag(EvidenceTag).captureToImage()
        val regionHashes = first.regionHashes(columns = 3, rows = 3)
        assertEquals("every theme cell must paint a distinct result", 9, regionHashes.toSet().size)
        val atmosphereHashes = MimirThemePalette.all.map { palette ->
            compose.onNodeWithTag(AtmosphereTagPrefix + palette.id.storageKey).captureToImage().pixelHash()
        }
        assertEquals("every atmosphere must paint a distinct result", 9, atmosphereHashes.toSet().size)

        compose.mainClock.advanceTimeBy(4_000L)
        val afterIdle = compose.onNodeWithTag(EvidenceTag).captureToImage()
        assertEquals("idle atmosphere must be pixel-static", first.pixelHash(), afterIdle.pixelHash())
        MimirThemePalette.all.forEachIndexed { index, palette ->
            val afterIdleHash = compose
                .onNodeWithTag(AtmosphereTagPrefix + palette.id.storageKey)
                .captureToImage()
                .pixelHash()
            assertEquals("${palette.id} atmosphere changed while idle", atmosphereHashes[index], afterIdleHash)
        }

        val evidence = first.writeEvidencePng()
        assertTrue("evidence PNG was not written", evidence.isFile && evidence.length() > 0L)
    }

    private fun ImageBitmap.regionHashes(columns: Int, rows: Int): List<Int> {
        val pixels = toPixelMap()
        val cellWidth = width / columns
        val cellHeight = height / rows
        return List(columns * rows) { index ->
            val column = index % columns
            val row = index / columns
            var hash = 17
            for (y in row * cellHeight until (row + 1) * cellHeight) {
                for (x in column * cellWidth until (column + 1) * cellWidth) {
                    hash = 31 * hash + pixels[x, y].toArgb()
                }
            }
            hash
        }
    }

    private fun ImageBitmap.pixelHash(): Int {
        val pixels = toPixelMap()
        var hash = 17
        for (y in 0 until height) {
            for (x in 0 until width) hash = 31 * hash + pixels[x, y].toArgb()
        }
        return hash
    }

    private fun ImageBitmap.writeEvidencePng(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "theme-evidence").apply { mkdirs() }
        return File(directory, "nine-theme-atmosphere.png").also { file ->
            FileOutputStream(file).use { output ->
                check(asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
            println("MIMIR_THEME_EVIDENCE=${file.absolutePath}")
        }
    }

    private companion object {
        const val EvidenceTag = "nine_theme_atmosphere_evidence"
        const val AtmosphereTagPrefix = "atmosphere_"
    }
}
