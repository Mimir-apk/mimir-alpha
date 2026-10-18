package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wave 2, lane L8a (Main host glass): the timing halves of RC-25 / RC-09 / RC-16 / RC-31 / RC-50 on the real primitives under a paused
 * clock, and the wiring (which host uses what) as source, below the behaviour. Pixels (blur, the snapshot) are the sweep's: emulator at
 * animator scale 5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainHostGlassWave2Test {

    @get:Rule
    val compose = createComposeRule()

    private var active by mutableStateOf<MimirOverlayKind?>(null)
    private var card: () -> Float = { 1f }
    private var sheet: () -> Float = { 0f }

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
    }

    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    private fun mount(held: Boolean) {
        compose.setContent {
            val under = if (held) {
                rememberHomeLaunchCardSheetHold(active = active, cancelKey = 0L)
            } else {
                homeLaunchCardUnderSheet(active)
            }
            // The two things the host draws, on the specs it uses: the card at the held alpha (animateFloatAsState on OverlayMs) and the
            // sheet's own dissolve (a pixel fade on OverlayMs).
            val cardAlpha = animateFloatAsState(
                targetValue = if (under) HomeLaunchCardUnderSheetAlpha else 1f,
                animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
                label = "card",
            )
            val sheetAlpha = animateFloatAsState(
                targetValue = if (active != null) 1f else 0f,
                animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
                label = "sheet",
            )
            card = { cardAlpha.value }
            sheet = { sheetAlpha.value }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    /** Opens the sheet, lets everything settle, closes it, and returns (sheet, card) per 16 ms frame over the next [ms]. */
    private fun closeSheetAndRecord(ms: Long): List<Pair<Float, Float>> {
        write { active = MimirOverlayKind.HOME_TILE_MENU }
        repeat(40) { compose.mainClock.advanceTimeByFrame() }
        assertEquals("fixture: the card is held under the open sheet", HomeLaunchCardUnderSheetAlpha, card(), 0.001f)
        assertEquals("fixture: the sheet is up", 1f, sheet(), 0.001f)
        write { active = null }
        val frames = mutableListOf<Pair<Float, Float>>()
        var at = 0L
        while (at < ms) {
            compose.mainClock.advanceTimeByFrame()
            at += 16L
            frames += sheet() to card()
        }
        return frames
    }

    @Test
    fun `the launch card stays held until the sheet over it has dissolved, then rises (H-04g)`() {
        mount(held = true)
        val frames = closeSheetAndRecord(700L)
        val summary = frames.joinToString { "%.2f/%.2f".format(it.first, it.second) }
        frames.forEach { (sheetAlpha, cardAlpha) ->
            // The 08-15 law: on no frame are the sheet and the rising card both part-way. While the sheet is anything a player can see,
            // the card is still at its held alpha.
            if (sheetAlpha > 0.05f) assertEquals("card held while the sheet shows: $summary", HomeLaunchCardUnderSheetAlpha, cardAlpha, 0.001f)
        }
        assertEquals("and it does rise, whole: $summary", 1f, frames.last().second, 0.001f)
        assertTrue("it rose after the hold, not on the commit: $summary", frames.first().second <= HomeLaunchCardUnderSheetAlpha + 0.001f)
    }

    @Test
    fun `the harness sees the old rise on the commit as the sheet and the card both half-visible`() {
        mount(held = false)
        val frames = closeSheetAndRecord(300L)
        val overlap = frames.any { (sheetAlpha, cardAlpha) -> sheetAlpha > 0.1f && cardAlpha > HomeLaunchCardUnderSheetAlpha + 0.1f }
        assertTrue("the commit-time rise overlaps the sheet's dissolve: ${frames.joinToString { "%.2f/%.2f".format(it.first, it.second) }}", overlap)
    }

    @Test
    fun `the emulator fact never reads Automatic while the verdicts are resolving (RC-31)`() {
        assertEquals(HomeLaunchCardEmulatorLoadingLabel, homeLaunchCardEmulatorLabelWhileResolving(label = null, resolving = true))
        assertEquals("a name the card already has is never replaced", "Eden", homeLaunchCardEmulatorLabelWhileResolving("Eden", resolving = true))
        assertNull("once the verdicts are in an absent label is the truth", homeLaunchCardEmulatorLabelWhileResolving(null, resolving = false))
        val host = hostSource()
        assertTrue(host.contains("resolving = game != null && homeLaunchCardEmulatorResolving("))
        assertFalse("the global flag alone no longer decides it", host.contains("resolving = game != null && !bindings.settingsRead.platformStatusesLoaded"))
    }

    @Test
    fun `the loading copy is Checking, not a bare ellipsis`() {
        assertEquals("Checking…", HomeLaunchCardEmulatorLoadingLabel)
        assertEquals("Checking…", homeLaunchCardEmulatorLabelWhileResolving(label = null, resolving = true))
    }

    @Test
    fun `the emulator verdict is awaited per platform, so a rescan that adds one leaves the open card alone (review #8)`() {
        val nes = com.gruning.mimir.rewrite.core.model.PlatformId("nes")
        val snes = com.gruning.mimir.rewrite.core.model.PlatformId("snes")
        assertTrue("nothing resolved yet", homeLaunchCardEmulatorResolving(nes, emptySet(), statusesLoaded = false))
        assertFalse("its own verdict is in, though the library just grew a platform and the global flag fell", homeLaunchCardEmulatorResolving(nes, setOf(nes), statusesLoaded = false))
        assertTrue("the new platform's own card waits", homeLaunchCardEmulatorResolving(snes, setOf(nes), statusesLoaded = false))
        assertFalse("every verdict in: a platform the list never carries is not loading for ever", homeLaunchCardEmulatorResolving(snes, setOf(nes), statusesLoaded = true))
    }

    @Test
    fun `the card behind a child modal is a snapshot only at the glass's peak, by core's one rule (Q5)`() {
        val host = hostSource()
        // 2026-10-15: WHEN the card is asked to be held is the Library's one rule (homeLaunchCardSnapshotWanted -> libraryCardSnapshotWanted), not `{ true }`; core still freezes only at the peak.
        assertTrue(Regex("""Modifier\.fillMaxSize\(\)\.mimirGlassBackdropProgress\(\s*cardGlass\.radius,\s*cardGlass\.level,\s*snapshot = \{\s*homeLaunchCardSnapshotWanted\(""").containsMatchIn(host))
        assertFalse("not frozen whenever the glass is at its peak any more", host.contains("cardGlass.level, snapshot = { true })"))
        val glass = glassSource()
        assertFalse("core owns the rule, the card carries no freeze predicate of its own", glass.contains("homeLaunchCardGlassFrozen"))
        assertFalse(glass.contains("val frozen: () -> Boolean"))
    }

    @Test
    fun `a face the card wore is remembered per game, newest eight kept, and a resolve with no face forgets it (review #7)`() {
        val memory = HomeLaunchCardFaceMemory(capacity = 2)
        val a = "a"
        val b = "b"
        val c = "c"
        assertNull(memory[a])
        memory.record(a, MimirWiredFace("file:///a.png", physical = true))
        memory.record(b, MimirWiredFace("file:///b.png", physical = false))
        assertEquals("file:///a.png", memory[a]?.uri)
        memory.record(c, MimirWiredFace("file:///c.png", physical = true))
        assertNull("b was the least recently used", memory[b])
        assertEquals("file:///a.png", memory[a]?.uri)
        memory.record(a, null)
        assertNull("an answer with no face is not kept", memory[a])
        assertEquals("file:///c.png", memory[c]?.uri)
    }

    @Test
    fun `the launch card seeds its face from the host-level memory and writes it back after each resolve (review #7)`() {
        val host = hostSource()
        // The memory is made by the host wrapper, outside the layers' own `key(card.dissolveCancellationEpoch)` that the remount replaces.
        assertTrue("the memory outlives the dissolve-cancellation remount", host.contains("val faceMemory = remember { HomeLaunchCardFaceMemory() }\n    HomeLaunchCardLayers("))
        assertTrue("and the remount is inside the layers", host.substringAfter("internal fun HomeLaunchCardLayers(").contains("    key(card.dissolveCancellationEpoch) {"))
        assertTrue(host.contains("var launchCardFace by remember(active.row.itemId) { mutableStateOf(faceMemory[active.row.itemId]) }"))
        assertTrue(host.contains("faceMemory.record(row.itemId, launchCardFace)"))
    }

    @Test
    fun `a lingering page finishes its fade in the time it has left, in both pixel hosts (review #5)`() {
        val host = hostSource()
        assertTrue(host.contains("if (retainLingering) mimirLingeringDepartMs(full, outgoingAlpha.value) else full"))
        assertTrue(host.contains("tween(mimirLingeringDepartMs(HomeOverlayDissolveMs, outgoingAlpha.value), easing = MimirMotion.Easing)"))
    }

    @Test
    fun `the scene glass follows the stack on the composition that changes it, and a card held for Settings gets the hold (RC-09, H-04d)`() {
        val host = hostSource()
        assertTrue(host.contains("val glassChrome = chromeOverlay ?: presentedOverlayChrome"))
        assertTrue(host.contains("visible = mimirOverlayUsesGlass(glassChrome),"))
        val effect = host.substringAfter("LaunchedEffect(chromeOverlay, bindings.homeLaunchCard.closing) {").substringBefore("val outgoingDuration")
        assertTrue(
            "START to Settings (closing still false) is not an immediate drop",
            effect.contains("!bindings.homeLaunchCard.closing && !bindings.homeLaunchCard.suspendedForSettings"),
        )
    }

    @Test
    fun `the Tome Library scrim rides the whole presentation and the clear, not the active overlay (RC-25, H-10)`() {
        val host = hostSource()
        assertFalse(host.contains("if (activeOverlay == MimirOverlayKind.HOME_TOME_LIBRARY) {\n                            MimirGlassScrim("))
        assertTrue(host.contains("if (glassChrome == MimirOverlayKind.HOME_TOME_LIBRARY ||"))
        assertTrue(host.contains("(lastGlassChrome[0] == MimirOverlayKind.HOME_TOME_LIBRARY && sceneGlassClearing)"))
        assertTrue("a flag that flips once, not a read per frame", host.contains("val sceneGlassClearing by remember { derivedStateOf { glassProgress() > 0f } }"))
    }

    @Test
    fun `the launch card's face is resolved off the composing thread and kept across a re-key (RC-16)`() {
        val host = hostSource()
        val face = host.substringAfter("var launchCardFace by remember(active.row.itemId) { mutableStateOf(faceMemory[active.row.itemId]) }")
            .substringBefore("if (backEnabled) {")
        assertTrue("an effect on IO", face.contains("LaunchedEffect(") && face.contains("launchCardFace = withContext(Dispatchers.IO) {"))
        assertFalse("no store read in a remember", host.substringAfter("private fun HomeLaunchCardLiveContent(").contains("val launchCardFace = remember("))
        assertTrue("the same re-key set as before, the media epoch included", face.substringBefore("launchCardFace = withContext").contains("bindings.mediaApplyEpoch"))
        assertTrue("a different game starts from its own last face, never its predecessor's", host.contains("remember(active.row.itemId) { mutableStateOf(faceMemory[active.row.itemId]) }"))
    }

    @Test
    fun `the post-connect detail scope is pushed only when it is not already current (RC-50)`() {
        val host = hostSource()
        assertTrue(host.contains("if (core.focusAuthority.state.value.scope != DetailScope) core.focusAuthority.pushScope(DetailScope)"))
        assertFalse(host.contains("        core.focusAuthority.pushScope(DetailScope)\n        core.focusAuthority.requestFocus(detailKey(rowId))"))
    }

    @Test
    fun `the unused minimum radius is no longer warmed (RC-54)`() {
        val source = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassTransition.kt"),
            File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirGlassTransition.kt"),
        ).first(File::isFile).readText()
        val warmup = source.substringAfter("fun MimirGlassWarmup() {").substringBefore("\n}\n")
        assertFalse(warmup.contains("BlurDownsampledMinRadiusPx"))
        assertFalse(warmup.contains("val low"))
    }

    private fun glassSource(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/HomeLaunchCardGlass.kt"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeLaunchCardGlass.kt"),
    ).first(File::isFile).readText()

    private fun hostSource(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
    ).first(File::isFile).readText()
}
