package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * At 5x on the emulator both directions cut the hero on the press: the host's two
 * painters were drawn either/or, so the flip dropped the one showing and the other started from nothing. Going to a
 * Tome the scene then stayed bare until the Tome's art decoded, after the band had settled.
 *
 * Source-level, like [HomeBackdropSingleHostTest]: which layers are drawn across a flip is not observable from a JVM
 * test. The frames are the device's to judge.
 */
class HomeModeSwitchHeroHandoffTest {

    private val backdrop = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfBackdrop.kt")

    private fun source(): String {
        assertTrue("HomeShelfBackdrop.kt not found at ${backdrop.absolutePath}", backdrop.isFile)
        return backdrop.readText()
    }

    @Test
    fun `across a switch both composites are drawn, the Tome's over the shelf's`() {
        val host = source().substringAfter("internal fun HomeShelfStaticBackdrop(")
        val shelf = host.indexOf("if (!synchronizedTomeHandoff || presentedUri != null) {")
        val tome = host.indexOf("if (synchronizedTomeHandoff || synchronizedPresentedUri != null) {")
        assertTrue("the shelf's still is drawn while it is still presented", shelf >= 0)
        assertTrue("the Tome composite is drawn while it still holds a hero", tome >= 0)
        assertTrue("the Tome composite draws over the shelf's still", shelf < tome)
    }

    @Test
    fun `going to a Tome, the still on screen becomes the Tome composite's bed before the shelf lets it go`() {
        val host = source().substringAfter("internal fun HomeShelfStaticBackdrop(")
        assertTrue(host.contains("presentedUri.takeIf { synchronizedTomeHandoff && imageUri != null && artReveal.value >= 1f }"))
        assertTrue(host.contains("synchronizedPresentedUri = adoptedStill"))
        assertTrue("shown the frame the bed's painter has it", host.contains("onBedReady = { synchronizedBedShown = true },"))
        assertTrue(
            "the shelf's painter holds until then",
            host.contains("snapshotFlow { synchronizedBedShown && synchronizedPresence.value >= 1f }.first { it }"),
        )
    }

    @Test
    fun `coming back, the Tome's hero holds until the shelf's still has revealed beneath it`() {
        val host = source().substringAfter("internal fun HomeShelfStaticBackdrop(")
        val reverse = host.substringAfter("if (!synchronizedTomeHandoff) {").substringBefore("return@LaunchedEffect")
        assertTrue(reverse.contains("snapshotFlow { artReveal.value >= 1f }.first { it }"))
        assertTrue("and only then dissolves", reverse.indexOf("artReveal.value >= 1f") < reverse.indexOf("synchronizedPresence.animateTo("))
    }

    @Test
    fun `neither wait can hang the hero`() {
        assertEquals(HomeBackdropSettleMs + MimirPageMotion.SettleCapMs, HomeHeroHandoffCapMs)
        // The shelf waiting for the Tome to show the adopted still, the adoption itself, and the way back.
        assertEquals(3, Regex("withTimeoutOrNull\\(HomeHeroHandoffCapMs\\)").findAll(source()).count())
    }
}
