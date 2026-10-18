package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shelf header's two chips ("‹ Shelf" and the density chip "4×2 ▾") are ordinary etched chips. A back control is an ordinary etched chip, identical to an unselected chip. The shelf's "‹ Shelf" pill had the filled look (`surfaceRaised` at 0.9 alpha); it now wears [mimirEtchedChipSurface] with the chip's padding, label style and text colour.
 *
 * Pinned two ways, at the Thor Bottom's 1240 x 1080 px at 369 dpi: the source of the pill (no fill, the etched surface, the chip's padding / style / colour) and the pixels (the pill's
 * plate against a reference unselected chip of the same make, drawn beside it, on the same ground: the rim rows and the bare ground inside the plate's left cap must be the same pixels
 * as the reference's, which a filled blob never is). The density chip is held the same way, to a reference built with its own content. The header is also saved as
 * `build/reports/shelf-header-1.0.png` in this module (the path is printed) for the eye. Pixels are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionShelfBackPillEtchedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369

    @Before
    fun setUp() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = true
    }

    @After
    fun tearDown() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = false
    }

    private val source: String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionShelfHeader.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionShelfHeader.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("the shelf header's source must be readable from the module dir")

    private fun backPillBody(): String {
        val start = source.indexOf("private fun CompanionShelfBackPill(")
        check(start >= 0) { "CompanionShelfBackPill moved: move this pin with it" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, index + 1)
            }
        }
        error("unbalanced body")
    }

    private fun densityChipBody(): String {
        val start = source.indexOf("private fun CompanionShelfDensityChip(")
        check(start >= 0) { "CompanionShelfDensityChip moved: move this pin with it" }
        val open = source.indexOf("{\n", source.indexOf(") {", start))
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, index + 1)
            }
        }
        error("unbalanced body")
    }

    @Test
    fun `the density chip's source is the same etched chip with the chip's padding, style and colour, and no fill`() {
        val body = densityChipBody()
        assertTrue("the etched chip surface, not warmed", body.contains(".mimirEtchedChipSurface(palette, warmed = false)"))
        assertFalse("no fill of its own", body.contains(".background("))
        assertFalse("no clip to a fill's shape", body.contains(".clip("))
        assertFalse("not the raised-surface blob", body.contains("surfaceRaised"))
        assertTrue("the chip's padding, md across and sm down", body.contains(".padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm)"))
        assertTrue("the chip's label style", body.contains("style = MimirTypeScale.SupportCompact"))
        assertTrue("semibold", body.contains("fontWeight = FontWeight.SemiBold"))
        assertEquals("the readout and the arrow are both in the unselected chip's text colour", 2, Regex("""color = palette\.materials\.hero\.onCover""").findAll(body).count())
        assertFalse("and not the accent or the muted text colour", body.contains("palette.accent") || body.contains("palette.textSecondary"))
        assertTrue("a 48 dp hit box", body.contains(".heightIn(min = CompanionShelfTargetDp.dp)") && body.contains(".widthIn(min = CompanionShelfTargetDp.dp)"))
    }

    @Test
    fun `the back pill's source is the etched chip with the chip's padding, style and colour, and no fill`() {
        val body = backPillBody()
        assertTrue("the etched chip surface, not warmed", body.contains(".mimirEtchedChipSurface(palette, warmed = false)"))
        assertFalse("no fill of its own", body.contains(".background("))
        assertFalse("no clip to a fill's shape", body.contains(".clip("))
        assertFalse("not the raised-surface blob", body.contains("surfaceRaised"))
        assertTrue("the chip's padding, md across and sm down", body.contains(".padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm)"))
        assertTrue("the chip's label style", body.contains("style = MimirTypeScale.SupportCompact"))
        assertTrue("semibold", body.contains("fontWeight = FontWeight.SemiBold"))
        assertTrue("in the unselected chip's text colour", body.contains("color = palette.materials.hero.onCover"))
        assertEquals("the chevron and the label are one Text call, so one colour", 1, Regex("""Text\(""").findAll(body).count())
        assertTrue("a 48 dp hit box", body.contains(".heightIn(min = CompanionShelfTargetDp.dp)") && body.contains(".widthIn(min = CompanionShelfTargetDp.dp)"))
    }

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Column(Modifier.background(palette.surfaceBase).padding(top = 10.dp)) {
                        Box(Modifier.requiredSize(hostWidthDp.dp, 64.dp).testTag("shelf-header-host")) {
                            CompanionShelfHeader(
                                platformIdValue = "genesis",
                                title = "Genesis",
                                gameCount = 43,
                                density = CompanionShelfDensity.STANDARD,
                                active = true,
                                onBack = {},
                                onDensityMenu = {},
                            )
                        }
                        // The reference for the density chip: the same chip with the density chip's own content.
                        Box(Modifier.padding(start = 16.dp, top = 8.dp)) {
                            Row(
                                Modifier
                                    .testTag("reference-density-chip")
                                    .mimirEtchedChipSurface(palette, warmed = false)
                                    .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                            ) {
                                Text(
                                    text = CompanionShelfDensity.STANDARD.readout,
                                    style = MimirTypeScale.SupportCompact,
                                    color = palette.materials.hero.onCover,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                MimirChromeText("▾", style = MimirTypeScale.RunicEyebrow, color = palette.materials.hero.onCover, maxLines = 1)
                            }
                        }
                        // The reference: an unselected chip, made the way every filter chip is made.
                        Box(Modifier.padding(start = 16.dp, top = 8.dp)) {
                            Row(
                                Modifier
                                    .testTag("reference-chip")
                                    .mimirEtchedChipSurface(palette, warmed = false)
                                    .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf("‹", "Shelf").forEach { word ->
                                    Text(
                                        text = word,
                                        style = MimirTypeScale.SupportCompact,
                                        color = palette.materials.hero.onCover,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun frame(): Bitmap {
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        return full
    }

    private class Px(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun px(tag: String): Px {
        val b = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        return Px(Math.round(b.left), Math.round(b.top), Math.round(b.width), Math.round(b.height))
    }

    private fun channelDistance(a: Int, b: Int): Int =
        maxOf(
            abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)),
            abs(android.graphics.Color.alpha(a) - android.graphics.Color.alpha(b)),
        )

    @Test
    fun `both chips in the header are the unselected chip's pixels, and the header is saved as a PNG`() {
        mount()
        val full = frame()
        assertPlateIsReferenceChip(full, "back", px(CompanionShelfBackPillPlateTag), px("reference-chip"))
        assertPlateIsReferenceChip(full, "density", px(CompanionShelfDensityChipPlateTag), px("reference-density-chip"))
        savePng(full)
    }

    private fun assertPlateIsReferenceChip(full: Bitmap, name: String, back: Px, reference: Px) {
        println("SHELF-CHIP $name plate=${back.width}x${back.height}@(${back.left},${back.top}) reference=${reference.width}x${reference.height}@(${reference.left},${reference.top})")
        assertEquals("the $name plate is the reference chip's width", reference.width.toFloat(), back.width.toFloat(), 2f)
        assertEquals("and its height", reference.height.toFloat(), back.height.toFloat(), 1f)
        val h = minOf(back.height, reference.height)
        val w = minOf(back.width, reference.width)
        // The rim rows (the top and bottom three pixel rows of the plate, away from the rounded ends) and the bare ground inside the left cap at mid height: no text there.
        val samples = mutableListOf<Pair<String, Pair<Int, Int>>>()
        for (dy in listOf(0, 1, 2, h - 3, h - 2, h - 1)) for (dx in (w * 3 / 10)..(w * 7 / 10) step 4) samples += "rim($dx,$dy)" to (dx to dy)
        // 6 dp in from the plate's left end: inside the cap's curve at these heights, and before the chevron (the padding is 12 dp).
        val capX = (6f * density369).toInt()
        for (dy in listOf(h / 3, h / 2, h * 2 / 3)) samples += "cap($capX,$dy)" to (capX to dy)
        var worst = 0
        val offenders = mutableListOf<String>()
        for ((name, at) in samples) {
            val a = full.getPixel(back.left + at.first, back.top + at.second)
            val b = full.getPixel(reference.left + at.first, reference.top + at.second)
            val d = channelDistance(a, b)
            worst = maxOf(worst, d)
            if (d > 3) offenders += "$name back=${Integer.toHexString(a)} chip=${Integer.toHexString(b)}"
        }
        println("SHELF-CHIP $name samples=${samples.size} worstChannelDistance=$worst offenders=$offenders")
        assertTrue("the $name plate is the unselected chip's pixels (a filled blob is not): $offenders", offenders.isEmpty())
    }

    /** The picture for the eye: the header band, as drawn, written under this module's build/reports (never an absolute machine path). */
    private fun savePng(full: Bitmap) {
        val host = px("shelf-header-host")
        val band = Bitmap.createBitmap(full, host.left, host.top, minOf(host.width, full.width - host.left), minOf(host.height, full.height - host.top))
        val out = File(File(System.getProperty("user.dir"), "build/reports"), "shelf-header-1.0.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { band.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHELF-HEADER-PNG ${out.absolutePath}")
        assertTrue("the PNG was written: ${out.absolutePath}", out.isFile && out.length() > 0)
    }
}
