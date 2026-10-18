package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Log evidence (Thor build 5fa4c795, route on gbc):
 * - an edge-swipe reclaim printed `destination=none … effectiveMode=IDLE` at `ExplicitReclaim`, re-admitted the list
 *   about 60 ms after `PresentationShown`, then ramped it in;
 * - a SWAP dropped it the same way under IDLE, brought it back under HOME for about 100 ms, then dropped it under
 *   RUNNING_APP once YouTube was on Main.
 *
 * That IDLE is `CompanionState.holdForReclaim`, published by `CompanionDisplayController.prepareForReclaim`.
 */
class CompanionPlatformShelfAdmissionTest {

    private val gbc = CompanionLibraryRoute.Destination.Platform("gbc", "Game Boy Color")
    private val stable = CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight", statsLabel = "6 consoles")
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        CompanionState.holdScopeOverride = scope
        CompanionState.holdTimeoutMs = 60_000L
        CompanionState.dualScreenGameActive = false
        CompanionState.set(CompanionPayload.idle())
    }

    @After
    fun tearDown() {
        scope.cancel()
        CompanionState.holdScopeOverride = null
        CompanionState.holdTimeoutMs = 3_000L
        CompanionState.set(CompanionPayload.idle())
    }

    @Test
    fun `HOME and an app on Main keep the list, a game and a plain IDLE take it`() {
        assertEquals(gbc, companionPlatformShelfDestination(gbc, CompanionMode.HOME, reclaimHold = false))
        assertEquals(gbc, companionPlatformShelfDestination(gbc, CompanionMode.RUNNING_APP, reclaimHold = false))
        assertNull(companionPlatformShelfDestination(gbc, CompanionMode.NOW_PLAYING, reclaimHold = false))
        assertNull(companionPlatformShelfDestination(gbc, CompanionMode.IDLE, reclaimHold = false))
    }

    @Test
    fun `the reclaim hold keeps the list and admits nothing else`() {
        assertEquals(gbc, companionPlatformShelfDestination(gbc, CompanionMode.IDLE, reclaimHold = true))
        assertNull(
            "a game still takes screen 2 wholesale",
            companionPlatformShelfDestination(gbc, CompanionMode.NOW_PLAYING, reclaimHold = true),
        )
    }

    @Test
    fun `the lattice admits nothing`() {
        for (mode in CompanionMode.values()) {
            for (hold in listOf(false, true)) {
                assertNull(companionPlatformShelfDestination(CompanionLibraryRoute.Destination.Lattice, mode, hold))
                assertNull(companionPlatformShelfDestination(null, mode, hold))
            }
        }
    }

    @Test
    fun `only the payload the reclaim hold left on screen is the reclaim hold`() {
        CompanionState.set(stable)
        assertFalse(CompanionState.isReclaimHold(rendered()))

        CompanionState.holdForReclaim()
        assertTrue("the hold on screen is the reclaim hold", CompanionState.isReclaimHold(rendered()))

        CompanionState.set(stable)
        assertFalse("a push replaces it", CompanionState.isReclaimHold(rendered()))

        CompanionState.holdForColdStart(timeoutMs = 60_000L)
        val coldStart = rendered()
        assertTrue(coldStart.isLoading && coldStart.mode == CompanionMode.IDLE)
        assertFalse(
            "the cold-start hold has the same value and is not the reclaim hold",
            CompanionState.isReclaimHold(coldStart),
        )

        // publish() conflates an equal payload, so this reclaim hold leaves the cold-start instance on screen.
        CompanionState.holdForReclaim()
        assertTrue(
            "whatever instance the reclaim hold left on screen is the reclaim hold",
            CompanionState.isReclaimHold(rendered()),
        )
    }

    @Test
    fun `a mode round trip to the platform last shown snaps, a navigation ramps`() {
        assertTrue(companionShelfEntrySnaps(lastShownPlatform = "gbc", destinationPlatform = "gbc", routeWrittenSinceShown = false))
        assertFalse("a route write since is a navigation", companionShelfEntrySnaps("gbc", "gbc", routeWrittenSinceShown = true))
        assertFalse("another platform ramps", companionShelfEntrySnaps("3ds", "gbc", routeWrittenSinceShown = false))
        assertFalse("a first open ramps", companionShelfEntrySnaps(null, "gbc", routeWrittenSinceShown = false))
        assertFalse("nothing admitted, nothing to snap", companionShelfEntrySnaps("gbc", null, routeWrittenSinceShown = false))
    }

    @Test
    fun `a snapping entry is opaque in the composition its target reaches 1`() {
        assertEquals(1f, companionShelfEnterValue(entrySnaps = true, target = 1f, animated = 0.3f))
        assertEquals(
            "before its first frame it waits like any entry",
            0f,
            companionShelfEnterValue(entrySnaps = true, target = 0f, animated = 0f),
        )
        assertEquals(
            "a ramping entry draws the animation",
            0.3f,
            companionShelfEnterValue(entrySnaps = false, target = 1f, animated = 0.3f),
        )
        assertEquals(
            "an exit draws the animation",
            0.6f,
            companionShelfEnterValue(entrySnaps = false, target = 0f, animated = 0.6f),
        )
    }

    @Test
    fun `the Presentation admits through one rule, composed and in every live re-read`() {
        val presentation = presentationSource()
        assertTrue(
            "the composed admission must use the rule",
            presentation.contains("val platformDestination = companionPlatformShelfDestination("),
        )
        val shelfBlock = presentation.substringAfter("val platformDestination = companionPlatformShelfDestination(", "")
            .substringBefore("AnimatedContent(", "")
        assertFalse(
            "no closure on the list may still require HOME: an admitted list with a HOME-only Back or PLAY is inert",
            shelfBlock.contains("== CompanionMode.HOME"),
        )
        for (closure in listOf("onArranged = { ids ->", "onBack = {", "onLaunch = { game ->")) {
            val body = shelfBlock.substringAfter(closure, "").take(1_200)
            assertTrue(
                "$closure must re-read the admission live",
                body.contains("companionLivePlatformShelfDestination()"),
            )
        }
    }

    @Test
    fun `the shelf enter block applies the entry decision`() {
        val presentation = presentationSource()
        assertTrue(
            "the drawn value goes through the entry decision",
            presentation.contains("val shelfEnter: () -> Float = { companionShelfEnterValue(shelfEntrySnaps, shelfEnterTarget, shelfEnterAnimated) }"),
        )
        // cc025007c ("on Main's motion tiers instead of their own 160 to 300 ms curves") replaced the one tween with three arms: the
        // snap stays exactly the snapping entry's, an entry from rest takes Main's arrive spec, and every other write (an exit, a
        // shelf asked for again while it leaves) takes the return tween. Only the snapping arm may snap.
        assertTrue(
            "a snapping entry animates with snap(); an entry from rest arrives on Main's overlay spec; every other write is the return tween",
            presentation.contains(
                "animationSpec = when {\n" +
                    "                            shelfEntrySnaps -> snap()\n" +
                    "                            shelfEnterTarget >= 1f && shelfAtRest[0] -> mimirOverlayArriveSpec(shelfReducedMotion)\n" +
                    "                            else -> tween(MimirPageMotion.ReturnMs, easing = MimirMotion.Easing)\n" +
                    "                        },",
            ),
        )
        assertEquals("snap() appears in no arm after the snapping one", 0,
            presentation.substringAfter("animationSpec = when {\n                            shelfEntrySnaps -> snap()", "")
                .substringBefore("label = \"companion-shelf-enter\",")
                .let { Regex("""\bsnap\(\)""").findAll(it).count() })
        val reset = presentation.substringAfter("LaunchedEffect(platformDestination) {", "").substringBefore("val exitPending", "")
        assertTrue(
            "a shelf still composed for this platform keeps the one frame it reported",
            reset.contains("if (shelfLeaving?.platformIdValue != platformDestination.platformIdValue) shelfHasFrame = false"),
        )
        val finished = presentation.substringAfter("label = \"companion-shelf-enter\",", "")
            .substringBefore("val shelfEnter: () -> Float = ", "")
        assertTrue(
            "the shelf that leaves composition takes its frame with it",
            finished.contains("shelfHasFrame = false"),
        )
    }

    @Test
    fun `the IDLE admitted is the one prepareForReclaim publishes`() {
        // Premise pin: the log's reclaim IDLE comes from this call.
        val controller = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionDisplayController.kt").readText()
        assertTrue(
            controller.substringAfter("override fun prepareForReclaim(", "")
                .substringBefore("override fun ", "")
                .contains("CompanionState.holdForReclaim()"),
        )
    }

    private fun rendered(): CompanionPayload = CompanionState.renderPayload.value.payload

    private fun presentationSource(): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
}
