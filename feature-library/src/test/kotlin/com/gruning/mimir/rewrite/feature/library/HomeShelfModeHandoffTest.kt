package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home L1/R1 uses a page change: the old band leaves on180ms, gliding opposite the incoming band.
 * The new band glides from the pressed shoulder side after a130ms lead on540ms. Input waits
 * until first paint, and gleam begins after arrival. The former0.965scale is removed.
 * Header crossfades in place while the Tome mark and navigation hint stay still.
 * MimirPageHandoffTest exercises departure; this file pins Home wiring as source text.
 */
class HomeShelfModeHandoffTest {

    private val shelf: String by lazy { source("HomeShelfScreen.kt") }

    // The tiles, ring and runes moved out of HomeShelfScreen.kt into the shared strip (GameShelf.kt, 2026-10-10); Home's
    // band now hosts `GameShelf(`, so each pin on them is split into what Home hands the strip and what the strip does.
    private val strip: String by lazy { gameShelfStripBody() }

    @Test
    fun `the band and the header both hand over, and only the band travels`() {
        val band = shelf.substringAfter("// Motion audit batch D: the band (tiles, ring and runes) is one page.")
            .substringBefore("reducedMotion = reducedMotion,")
        assertTrue(band.contains("departs = modeHandoff,"))
        assertTrue(band.contains("exitTravelPx = MimirPageMotion.exitTravelPx(surfaceHandoffDirection, modeTravelPx),"))
        assertTrue(band.contains("incomingTravelPx = { bandArrivalFromPx * (1f - surfaceFadeHero()) },"))
        assertTrue(shelf.contains("val bandArrivalFromPx = MimirPageMotion.arrivalTravelPx(surfaceHandoffDirection, modeTravelPx)"))

        val header = shelf.substringAfter("// The header stays still and crossfades").substringBefore("HomeShelfTitle(")
        assertTrue(header.contains("exitTravelPx = 0f,"))
        assertTrue(header.contains("incomingTravelPx = { 0f },"))
    }

    @Test
    fun `the tiles, ring and runes ride the band's layer instead of each fading and gliding`() {
        val bandMarker = "// Motion audit batch D: the band (tiles, ring and runes) is one page."
        val bandStart = shelf.indexOf(bandMarker)
        assertTrue("band source marker exists", bandStart >= 0)
        val countStart = shelf.indexOf("if (orderedItems.isNotEmpty() && selectedIndex != null) {", startIndex = bandStart)
        assertTrue("count source marker follows band", countStart > bandStart)
        val band = shelf.substring(bandStart + bandMarker.length, countStart)
        assertTrue(band.contains("GameShelf("))
        assertTrue(strip.contains("GameShelfMovingSlot("))
        assertTrue(strip.contains("GameShelfStationaryFocusRing("))
        assertTrue(strip.contains("GameShelfContinuationRunes("))
        // A tile's alpha is its role's (the band's layer owns the switch's); a game that has left the Library's list multiplies it by its own
        // presence, and no other tile does.
        assertTrue(strip.contains("{ roleAlpha.value }"))
        assertTrue(strip.contains("{ roleAlpha.value * leavingPresence() }"))
        assertTrue(strip.contains("scale = { roleScale.value },"))
        assertFalse("no second fade inside the band", band.contains("surfaceFadeHero()") && band.contains("alpha = surfaceFadeHero()"))
        assertFalse("the strip has no fade of the switch's own", strip.contains("surfaceFadeHero"))
        assertFalse(shelf.contains("travelDp"))
    }

    @Test
    fun `the settle scale and the unsigned glide are retired`() {
        assertFalse(shelf.contains("homeShelfModeArrivalScale"))
        assertFalse(shelf.contains("HomeShelfModeArrivalStartScale"))
        assertFalse(shelf.contains("homeShelfModeArrivalTravelDp"))
        assertFalse(shelf.contains("contextFade"))
    }

    @Test
    fun `the arrival waits for the lead, runs on the slow clock and holds presses until it shows`() {
        val effect = shelf.substringAfter("val modeGateOwner = remember(surfaceEpoch) { Any() }")
            .substringBefore("val surfaceFadeHero")
        assertTrue(effect.contains("if (modeHandoff) MimirTransitionInputGate.hold(modeGateOwner)"))
        assertTrue(effect.contains("MimirPageMotion.LeadMs * 1_000_000L"))
        assertTrue(effect.contains("tween(if (modeHandoff) MimirPageMotion.ArriveMs else HomeShelfModeHandoffMs"))
        assertTrue(effect.contains("if (value > 0f) MimirTransitionInputGate.release(modeGateOwner)"))
        assertTrue("a cancelled switch never leaves the pad deaf", effect.contains("} finally {\n                MimirTransitionInputGate.release(modeGateOwner)"))
        assertTrue(shelf.contains("val modeHandoff = surfaceHandoffDirection != 0"))
    }

    @Test
    fun `touches wait with the pad until the band shows`() {
        // Review of 3cf42c7fd: the band's tiles hit-test at rest while it is hidden, and raw touch never goes
        // through MimirTransitionInputGate, so a tap on the departing band launched a tile nobody could see.
        assertTrue(shelf.contains("var modeShielding by remember(surfaceEpoch) { mutableStateOf(modeHandoff && !modeArrived) }"))
        val effect = shelf.substringAfter("val modeGateOwner = remember(surfaceEpoch) { Any() }")
            .substringBefore("val surfaceFadeHero")
        assertTrue(effect.contains("if (modeHandoff) MimirTransitionInputGate.hold(modeGateOwner)\n                modeShielding = modeHandoff"))
        assertTrue(effect.contains("if (value > 0f && modeShielding) modeShielding = false"))
        assertTrue(
            "a cancelled switch never leaves touch dead",
            effect.contains("MimirTransitionInputGate.release(modeGateOwner)\n                modeShielding = false\n            }"),
        )
        val band = shelf.sliceBetween("// Motion audit batch D: the band (tiles, ring and runes) is one page.", "GameShelf(")
        assertTrue(band.contains("shieldTouches = { modeShielding },"))
        // The strip's own touch gate is the same flag, read inside the strip.
        assertTrue(shelf.contains("touchEnabled = { !modeShielding },"))
        assertTrue(strip.contains("enabled = !inert && items.isNotEmpty() && touchEnabled(),"))
        val header = shelf.sliceBetween("// The header stays still and crossfades", "HomeShelfTitle(")
        assertFalse("the header holds no tiles", header.contains("shieldTouches"))
    }

    @Test
    fun `a list that lands after an empty arrival takes focus without a second handoff`() {
        // Review of 3cf42c7fd: Editor's Choice arrived empty on a fresh install, and the scan's first game restarted
        // the whole arrival, snapping the settled band invisible and holding the pad again with no press.
        val effect = shelf.substringAfter("val modeGateOwner = remember(surfaceEpoch) { Any() }")
            .substringBefore("val surfaceFadeHero")
        assertTrue(shelf.contains("var modeShownEmpty by remember(surfaceEpoch) { mutableStateOf(false) }"))
        val lateBranch = "} else if (modeShownEmpty) {"
        assertTrue(effect.contains(lateBranch))
        assertTrue("the late list is decided before the full handoff", effect.indexOf(lateBranch) < effect.indexOf("} else try {"))
        val late = effect.substringAfter(lateBranch).substringBefore("} else try {")
        assertTrue(late.contains("registerArrivalFocus()"))
        assertTrue(late.contains("modeArrived = true"))
        assertFalse("no held pad", late.contains("MimirTransitionInputGate.hold"))
        assertFalse("no touch shield", late.contains("modeShielding = modeHandoff"))
        assertFalse("no veil over a settled band", late.contains("snapTo(0f)"))
        assertFalse("no second lead", late.contains("LeadMs"))
        assertFalse("no second fade", late.contains("animateTo"))
        val full = effect.substringAfter("} else try {")
        assertTrue(full.contains("registerArrivalFocus()"))
        assertTrue("only a completed empty arrival skips the handoff", full.contains("modeShownEmpty = itemKeys.isEmpty()"))
        assertTrue(
            "the flag is set after the fade has run",
            full.indexOf("modeShownEmpty = itemKeys.isEmpty()") > full.indexOf("surfaceFade.animateTo("),
        )
    }

    @Test
    fun `a restore still fades in place on the ladder's mode clock`() {
        assertEquals(MimirMotion.ModeTransitionMs, HomeShelfModeHandoffMs)
        assertTrue(shelf.contains("internal const val HomeShelfModeHandoffMs = MimirMotion.ModeTransitionMs"))
    }

    @Test
    fun `the gleam starts once the band has arrived`() {
        assertTrue(shelf.contains("ringGleamArmed = { modeArrived },"))
        assertTrue(strip.contains("rememberMimirFocusGameGleam(selectedItem.focusKey.takeIf { ringGleamArmed() })"))
    }

    @Test
    fun `the Tome mark and the navigation hint hold still, the count arrives with the band`() {
        val tomeMarks = Regex("""\.clearAndSetSemantics \{\s*contentDescription = tomeShortcutDescription""").findAll(shelf).toList()
        assertEquals(2, tomeMarks.size)
        tomeMarks.forEach { mark ->
            val before = shelf.substring(maxOf(0, mark.range.first - 200), mark.range.first)
            assertFalse("the Tome mark does not fade", before.substringAfterLast(".height(").contains("surfaceFadeHero"))
        }
        val hint = shelf.substringAfter(".height(geometry.navigationHintBand.height.dp)").substringBefore("contentAlignment")
        assertFalse(hint.contains("surfaceFadeHero"))
        val countMarker = "if (orderedItems.isNotEmpty() && selectedIndex != null) {"
        val countStart = shelf.indexOf(countMarker)
        assertTrue("count source marker exists", countStart >= 0)
        val countEnd = shelf.indexOf("HomeShelfGameCount(", startIndex = countStart)
        assertTrue("count call follows count condition", countEnd > countStart)
        val count = shelf.substring(countStart, countEnd)
        assertTrue(count.contains(".graphicsLayer { alpha = surfaceFadeHero() }"))
    }

    @Test
    fun `a quick reversal carries the returning band on from where it was painted`() {
        // Review 2026-09-29: R1 then L1 inside the 180 ms exit blinked the band out and brought it in again after the
        // lead; the Library's strip carried the same gesture on. The band's handoff now knows its mode.
        assertTrue(shelf.contains("val bandHandoff = remember { MimirPageHandoffState() }"))
        assertTrue(shelf.contains("bandHandoff.returningAlpha(surfaceLabel)"))
        assertTrue(shelf.contains("1f else modeReturningAlpha ?: 0f)"))
        val band = shelf.substringAfter("// Motion audit batch D: the band (tiles, ring and runes) is one page.")
            .substringBefore("reducedMotion = reducedMotion,")
        assertTrue(band.contains("target = surfaceLabel,"))
        assertTrue(band.contains("state = bandHandoff,"))
        val effect = shelf.substringAfter("val modeGateOwner = remember(surfaceEpoch) { Any() }")
            .substringBefore("val surfaceFadeHero")
        // Alpha audit F5: a band this arrival was already painting carries on the same way, so the snap to 0 and the
        // lead are skipped for either, decided once from homeArrivalCarriesOn.
        assertTrue(effect.contains("val carryOn = homeArrivalCarriesOn(modeReturningAlpha, surfaceFade.value)"))
        assertTrue(effect.contains("if (!carryOn) surfaceFade.snapTo(0f)"))
        assertTrue(effect.contains("if (modeHandoff && !carryOn) {"))
    }

    /**
     * F5 (alpha audit 2026-09-30). A change to `itemKeys` inside the arrival, about 700 ms, restarted the effect in the
     * same epoch: it snapped the painted band to 0, held the pad again and played the lead and the fade a second time (a
     * finished library scan during R1 does it). Now the re-run finds the band already painting and carries on from it.
     */
    @Test
    fun `a list that changes while the band is arriving carries the band on instead of replaying the arrival`() {
        // `itemKeys` stays a key: the empty arrival's late games are decided by this same re-run.
        assertTrue(shelf.contains("LaunchedEffect(surfaceEpoch, reducedMotion, itemKeys) {"))
        val effect = shelf.substringAfter("val modeGateOwner = remember(surfaceEpoch) { Any() }")
            .substringBefore("val surfaceFadeHero")
        val full = effect.substringAfter("} else try {")
        val carryOn = full.indexOf("val carryOn = homeArrivalCarriesOn(modeReturningAlpha, surfaceFade.value)")
        assertTrue("decided first, before anything moves", carryOn in 0 until full.indexOf("MimirTransitionInputGate.hold(modeGateOwner)"))
        assertTrue(carryOn < full.indexOf("surfaceFade.snapTo(0f)"))
        assertTrue(carryOn < full.indexOf("MimirPageMotion.LeadMs * 1_000_000L"))
        // The tween then runs from the painted alpha, as a reversal's does: no snap between the decision and the fade.
        assertEquals(1, Regex("""surfaceFade\.snapTo\(0f\)""").findAll(full).count())
        // The branches above it are untouched: no carry-on decision, no lead, and no snap to 0 in either.
        val before = effect.substringBefore("} else try {")
        assertFalse(before.contains("carryOn"))
        assertFalse(before.contains("snapTo(0f)"))
    }

    @Test
    fun `an arrival carries on from a band already on screen and starts hidden only from nothing`() {
        assertFalse("a fresh switch shows nothing", homeArrivalCarriesOn(returningAlpha = null, painted = 0f))
        assertTrue("a list changed under an arrival that was painting", homeArrivalCarriesOn(returningAlpha = null, painted = 0.4f))
        assertTrue("the shoulder turned back to a band still leaving", homeArrivalCarriesOn(returningAlpha = 0.5f, painted = 0f))
        assertTrue("both at once", homeArrivalCarriesOn(returningAlpha = 0.5f, painted = 0.5f))
        assertTrue("the smallest painted alpha still carries on", homeArrivalCarriesOn(returningAlpha = null, painted = Float.MIN_VALUE))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
