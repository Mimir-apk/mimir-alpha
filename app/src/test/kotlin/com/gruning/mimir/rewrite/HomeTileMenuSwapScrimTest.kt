package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tile menu and its media picker share one veil over the shelf. With each page painting its own, the swap tier
 * (out 180 ms, in from the 130 ms lead) took the veil to ~5% at 130 ms: a bright pulse between the two panels, on
 * Home and in the Library platform tile menu. A white field stands in for the shelf; a corner outside every panel
 * must stay as dark as the settled menu's on every frame of both swaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTileMenuSwapScrimTest {
    @get:Rule
    val compose = createComposeRule()

    // A frame-by-frame capture of the veil stood here; under Robolectric the menu never goes idle, so
    // captureToImage timed out. The emulator slow-motion pass (animator scale 5) checks the veil instead.

    @Test
    fun `one veil on the surface-fade container, and neither page paints its own`() {
        val menuSource = source("HomeTileMenuOverlay.kt")
        val menu = menuSource.substringAfter("internal fun TileArtMenuOverlay(").substringBefore("private fun glassTextInset(")
        assertTrue(
            menu.contains("modifier = Modifier.fillMaxSize().background(mimirGlassScrimColor()),\n        label = \"tileMenuSurfaceFade\","),
        )
        // The only other veil in the menu is ClipPick's, over its own preview video.
        assertEquals(1, Regex("""Box\(Modifier\.fillMaxSize\(\)\.background\(mimirGlassScrimColor\(\)\)\)""").findAll(menu).count())
        assertEquals(2, Regex("""mimirGlassScrimColor\(\)""").findAll(menu).count())
        val pickerSource = source("HomeCustomEnrichPicker.kt")
        val pickerOverlay = pickerSource.substringAfter("internal fun HomeCustomEnrichPickerOverlay(").substringBefore("\n}\n")
        assertFalse("the picker is a page of the tile menu and must not paint its own veil", pickerOverlay.contains("mimirGlassScrimColor"))
    }

    @Test
    fun `the launch card is held back under the tile menu and the achievements sheet only`() {
        assertTrue(homeLaunchCardUnderSheet(MimirOverlayKind.HOME_TILE_MENU))
        assertTrue(homeLaunchCardUnderSheet(MimirOverlayKind.GAMELIST_ACHIEVEMENTS))
        assertFalse("the card itself on top stays whole", homeLaunchCardUnderSheet(MimirOverlayKind.HOME_LAUNCH_CARD))
        assertFalse("launch choice already blurs the card", homeLaunchCardUnderSheet(MimirOverlayKind.HOME_LAUNCH_CHOICE))
        assertFalse("Open with already blurs the card", homeLaunchCardUnderSheet(MimirOverlayKind.HOME_OPEN_WITH))
        assertFalse("title edit has its own blur", homeLaunchCardUnderSheet(MimirOverlayKind.GAME_TITLE_EDIT))
        assertFalse(homeLaunchCardUnderSheet(null))
        assertEquals(0.3f, HomeLaunchCardUnderSheetAlpha, 0f)
    }

    @Test
    fun `the held alpha is alpha only, on the overlay clock, read in the draw phase`() {
        val hostSource = source("MimirAppHost.kt")
        assertTrue(hostSource.contains("sheetOverCard = rememberHomeLaunchCardSheetHold("))
        val cardHost = hostSource.substringAfter("private fun HomeLaunchCardOverlayHost(").substringBefore("private fun HomeLaunchCardLiveContent(")
        assertTrue("the primary layer takes the held alpha", cardHost.contains("primaryLayer.alpha = alpha * underSheetAlpha.value"))
        assertTrue(
            "the dissolve ledger keeps the undimmed arrival alpha",
            cardHost.contains("displayedLayerAlphas[frame.primaryLayerIndex] = alpha\n"),
        )
        assertTrue(
            "scoped to the card, behind a function of its own (HomeLaunchCardOpenSequenceTest says why)",
            cardHost.contains("val underSheetAlpha = rememberHomeLaunchCardUnderSheetAlpha(\n            cardKey = target,\n            epoch = frame.epoch,"),
        )
        val fade = cardHost.substringAfter("private fun rememberHomeLaunchCardUnderSheetAlpha(").substringBefore("home-launch-card-under-sheet")
        assertTrue("held while a sheet is over the card", fade.contains("targetValue = if (sheetOverCard) HomeLaunchCardUnderSheetAlpha else 1f,"))
        assertTrue("a snap under reduced motion", fade.contains("if (reducedMotion) {") && fade.contains("snap<Float>()"))
        assertTrue("the existing overlay clock", fade.contains("tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)"))
        assertFalse("no new blur for the sheet", cardHost.contains("sheetOverCard -> MimirGlass"))
    }

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::exists)?.readText() ?: throw AssertionError("$name not reachable from the test working directory")

    private companion object {
        const val ShelfTag = "swap_scrim_shelf"
    }
}
