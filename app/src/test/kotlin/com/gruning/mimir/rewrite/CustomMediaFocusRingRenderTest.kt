package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Observed on Thor: on Customize media the focused Default tile's ring and halo were cut at the top and the left. The page sits under the
 * glass-backdrop layer, which clips to its own bounds on every device that blurs, and inside the strip's scroll host, which clips its own axis; the
 * ring reaches [MimirFocusTileRingReach] past its tile. So the REAL page is mounted on the Thor's Main screen (1920 x 1080 px at 369 dpi,
 * 832.5 x 468.3 dp) and on the 360 x 360 compact, with Default focused, and every tile's ring rect is held inside both clips' bounds, read from
 * the tagged nodes. A software canvas cannot draw the layer (API 30 draws none of it), so the pictures cannot show the cut; the bounds are the same
 * on every API, and the pictures show the page whole, in `build/test-captures/hintring/`. Drawn the way [CustomMediaPageCaptureTest] draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomMediaFocusRingRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val density = 369f / 160f
    private val authority = DefaultFocusAuthority()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("gba/metroid-fusion")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "Metroid Fusion",
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)

    private fun saved(n: Int) = EnrichSavedAsset(
        assetId = "saved-$n",
        source = EnrichSavedSource.SCREENSCRAPER,
        label = "ScreenScraper · 600×800",
        localPath = "",
        previewPath = "",
        width = 600,
        height = 800,
        bytes = 1L,
        addedAt = n.toLong(),
    )

    private fun stateFor(label: String): HomeCustomEnrichState {
        val type = types.first { it.label == label }
        val merged = type.kind == EnrichMediaKind.ICON && type.mediaState == MediaState.SHELF
        return HomeCustomEnrichState(
            sessionId = "ring-$label",
            kind = type.kind,
            filters = if (merged) homeCustomEnrichIconArtFilters() else homeCustomEnrichDefaultFilters(type.kind),
            shelfIconArt = merged,
            cacheKey = mediaStorageKey("gba/metroid-fusion", type.mediaState),
            gameTitle = "Metroid Fusion",
            queryDraft = "Metroid Fusion",
            saved = listOf(saved(1), saved(2)),
            results = emptyList(),
            searchStatus = HomeCustomEnrichSearchStatus.Idle,
            defaultArt = HomeCustomEnrichDefaultArt.ConsoleLineArt("gba"),
            mediaState = type.mediaState,
            mediaTypes = types,
        )
    }

    private fun settleFrame() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(1_000L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    private fun render(
        label: String,
        font: Float,
        fileName: String,
        widthDp: Float = 832.5f,
        heightDp: Float = 468.3f,
        compactTypography: Boolean = false,
    ) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        val state = stateFor(label)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, font)) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        HomeCustomEnrichPickerOverlay(
                            authority = authority,
                            state = state,
                            onAction = {},
                            onQueryChange = {},
                            onKeyboardDone = {},
                        )
                    }
                }
            }
        }
        settleFrame()
        settleFrame()
        authority.requestFocus(homeCustomEnrichAssetKey(state, HomeCustomEnrichAsset.Automatic))
        settleFrame()
        settleFrame()
        val decor = compose.activity.window.decorView
        val image = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        decor.draw(android.graphics.Canvas(image))
        val outDir = File("build/test-captures/hintring").apply { mkdirs() }
        val out = File(outDir, fileName)
        out.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("RING-RENDER wrote ${out.absolutePath} (${image.width}x${image.height} px)")
        assertTrue(out.isFile && out.length() > 1_000L)

        fun bounds(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInWindow
        val layer = bounds(HomeCustomEnrichBackdropLayerTag)
        val host = bounds(CustomMediaStripHostTag)
        val reach = MimirFocusTileRingReach.value * density
        annotate(
            image,
            File(outDir, fileName.replace(".png", "-clips.png")),
            first = bounds(customMediaTileTag("auto")),
            layer = layer,
            host = host,
            reach = reach,
            bleedPx = customMediaBackdropBleed(compactTypography).value * density,
            verticalBleedPx = EnrichGalleryRingVerticalBleedDp * density,
        )
        // The ring's reach is 14 dp; every clip is laid out in whole pixels, so the check stands a pixel's rounding either way.
        val slack = 1f
        var checked = 0
        listOf("auto", "saved-1", "saved-2").forEach { id ->
            val tile = bounds(customMediaTileTag(id))
            println("RING-RENDER $label font=$font $id tile=$tile layer=$layer host=$host reach=$reach px")
            // A tile past the strip's visible end (a compact page, where the third tile is scrolled to) has no ring to hold: the strip follows focus to it.
            if (tile.right + reach > host.right + slack) return@forEach
            checked += 1
            assertTrue("$id: the ring's left stays inside the glass layer", tile.left - reach >= layer.left - slack)
            assertTrue("$id: the ring's top stays inside the glass layer", tile.top - reach >= layer.top - slack)
            assertTrue("$id: the ring's right stays inside the glass layer", tile.right + reach <= layer.right + slack)
            assertTrue("$id: the ring's bottom stays inside the glass layer", tile.bottom + reach <= layer.bottom + slack)
            assertTrue("$id: the ring's left stays inside the strip's scroll host", tile.left - reach >= host.left - slack)
            assertTrue("$id: the ring's top stays inside the strip's scroll host", tile.top - reach >= host.top - slack)
            assertTrue("$id: the ring's bottom stays inside the strip's scroll host", tile.bottom + reach <= host.bottom + slack)
        }
        assertTrue("the first tile and at least one beside it were held: $checked", checked >= 2)
    }

    /**
     * The same picture with the clips drawn on it, for the eye (a software canvas cannot draw the glass layer, so the cut itself cannot be shown): the first tile's
     * ring reach in white; the glass layer was the RED box (the panel less its padding) and is now the GREEN one (the panel's whole interior); the strip's scroll
     * host was the YELLOW box (the band, 6 dp of air above and below) and is now the CYAN one (the band less the ring's reach).
     */
    private fun annotate(
        image: android.graphics.Bitmap,
        out: File,
        first: androidx.compose.ui.geometry.Rect,
        layer: androidx.compose.ui.geometry.Rect,
        host: androidx.compose.ui.geometry.Rect,
        reach: Float,
        bleedPx: Float,
        verticalBleedPx: Float,
    ) {
        val canvas = android.graphics.Canvas(image)
        fun box(rect: androidx.compose.ui.geometry.Rect, color: Int) {
            val paint = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2f
                this.color = color
            }
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, paint)
        }
        box(androidx.compose.ui.geometry.Rect(first.left - reach, first.top - reach, first.right + reach, first.bottom + reach), 0xFFFFFFFF.toInt())
        box(androidx.compose.ui.geometry.Rect(layer.left + bleedPx, layer.top + bleedPx, layer.right - bleedPx, layer.bottom - bleedPx), 0xFFFF3030.toInt())
        box(layer, 0xFF30FF30.toInt())
        box(androidx.compose.ui.geometry.Rect(host.left, host.top + verticalBleedPx, host.right, host.bottom - verticalBleedPx), 0xFFFFE030.toInt())
        box(host, 0xFF30E0FF.toInt())
        out.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("RING-RENDER wrote ${out.absolutePath}")
    }

    @Test
    fun `icon art font 1_0`() { render("Icon art", 1.0f, "ring-icon-1.0.png") }

    @Test
    fun `icon art font 1_3`() { render("Icon art", 1.3f, "ring-icon-1.3.png") }

    @Test
    fun `hero art on the compact 360 by 360 at font 1_3`() { render("Hero art", 1.3f, "ring-hero-compact-1.3.png", 360f, 360f, compactTypography = true) }

    /** The bleeds are the ring's reach less what is already there, at both paddings: they cannot drift from the constant the ring is sized by. */
    @Test
    fun `the glass layer and the strip hold a ring's whole reach, at both paddings`() {
        listOf(true, false).forEach { compact ->
            // The layer's edge to a tile on the column: the padding the layer now covers, plus the side air.
            assertEquals(
                "layer edge to column ($compact)",
                MimirFocusTileRingReach,
                customMediaBackdropBleed(compact) + customMediaPanelSideAir(compact),
            )
            assertEquals(homeCustomEnrichPanelPadding(compact), customMediaBackdropBleed(compact))
        }
        // The strip's scroll host above and below the tile: the band's air plus the bleed, which is the ring's reach.
        assertEquals(EnrichRingClearanceDp, EnrichGalleryVerticalAirDp + EnrichGalleryRingVerticalBleedDp)
        assertEquals(MimirFocusTileRingReach.value.toInt(), EnrichGalleryVerticalAirDp + EnrichGalleryRingVerticalBleedDp)
        assertEquals(MimirSpacing.md, homeCustomEnrichPanelPadding(compactTypography = false))
        val picker = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeCustomEnrichPicker.kt"),
        ).first(File::isFile).readText()
        val chain = picker.substringAfter(".customMediaBackdropBleedBy(backdropBleed)").substringBefore("verticalArrangement")
        assertTrue(
            "the layer bleeds, is tagged, then blurs, then the content is padded back",
            chain.indexOf(".testTag(HomeCustomEnrichBackdropLayerTag)") in 0 until chain.indexOf(".mimirGlassBackdropProgress { filterGlass() }"),
        )
        assertTrue(chain.contains(".padding(horizontal = panelSideAir + backdropBleed, vertical = backdropBleed)"))
        assertTrue(picker.contains(".enrichStripBleed(EnrichRingClearanceDp.dp, EnrichGalleryRingVerticalBleedDp.dp)"))
        assertTrue(picker.contains("vertical = (EnrichGalleryVerticalAirDp + EnrichGalleryRingVerticalBleedDp).dp,"))
    }
}
