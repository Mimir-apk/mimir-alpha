package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.ui.MimirMotion

class MimirAppHostMotionTest {
    @Test
    fun everyRealRootSurfaceChangeRetainsOneOutgoingLayer() {
        assertTrue(isRootSurfaceHandoff(MimirDestination.HOME, MimirDestination.LIBRARY))
        assertTrue(isRootSurfaceHandoff(MimirDestination.LIBRARY, MimirDestination.HOME))
        assertTrue(isRootSurfaceHandoff(MimirDestination.HOME, MimirDestination.SETTINGS))
        assertTrue(isRootSurfaceHandoff(MimirDestination.SETTINGS, MimirDestination.BROWSE))
        assertFalse(isRootSurfaceHandoff(MimirDestination.HOME, MimirDestination.HOME))
    }

    @Test
    fun rapidReversalReplacesTheSingleOutgoingLayer() {
        val homeToLibrary = nextRootShelfLayerFrame(
            currentPrimaryLayerIndex = 0,
            retainOutgoing = true,
        )
        assertEquals(1, homeToLibrary.primaryLayerIndex)
        assertEquals(0, homeToLibrary.outgoingLayerIndex)

        val libraryToHome = nextRootShelfLayerFrame(
            currentPrimaryLayerIndex = homeToLibrary.primaryLayerIndex,
            retainOutgoing = true,
        )
        assertEquals(0, libraryToHome.primaryLayerIndex)
        assertEquals(1, libraryToHome.outgoingLayerIndex)

        val settled = nextRootShelfLayerFrame(
            currentPrimaryLayerIndex = libraryToHome.primaryLayerIndex,
            retainOutgoing = false,
        )
        assertNull(settled.outgoingLayerIndex)
    }

    /**
     * **The lower bound is `FocusMs`, and it earned its rationale on 2026-09-03.**
     *
     * This was a bare `in 140..220` with nothing saying why. A proposed shorter Library ↔ Home hop
     * used a duration of 120 ms — derived honestly from the house
     * curve, since `OverlayDissolveCoverMs`'s own measurement puts a 180 ms fade at alpha .044 by
     * 130 ms and the same 72% of 160 ms is ~115 ms of visible fade. This bound refused it.
     *
     * It was right to. 140 is [MimirMotion.FocusMs], the shortest duration in the scale: taking a
     * whole-screen destination change below the time a focus ring needs to move one tile inverts the
     * motion hierarchy, making the largest change on screen the quickest thing on it. The band is
     * therefore expressed against the token rather than a literal, so it tracks the scale instead of
     * drifting from it — and a future edit that wants to go faster has to move the SCALE, in
     * `MimirMotion`, where every surface sees it.
     */
    @Test
    fun rootHandoffUsesOnlyABoundedOutgoingAlpha() {
        assertTrue(
            "the root shelf hop must not be quicker than a focus move -- that inverts the hierarchy",
            RootShelfOutgoingLayerDurationMs >= MimirMotion.FocusMs,
        )
        assertTrue(RootShelfOutgoingLayerDurationMs in 140..220)
        assertTrue(RootShelfOutgoingLayerStartAlpha > 0f)
        assertEquals(1f, RootShelfOutgoingLayerStartAlpha)
    }

    @Test
    fun achievementLedgerUsesTheSharedGlassBackdrop() {
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.GAMELIST_ACHIEVEMENTS))
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.HOME_TOME_LIBRARY))
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.HOME_LAUNCH_CARD))
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.HOME_OPEN_WITH))
        assertFalse(mimirOverlayUsesGlass(MimirOverlayKind.RETURN_COVER))
    }

    /**
     * The Home shelf must remain obscured behind the launch card. GAME_TITLE_EDIT is a child overlay of
     * HOME_LAUNCH_CARD and must keep the SAME destination blur its parent already gets, for the whole
     * time it is open — otherwise the destination blur switches off the instant it becomes the active
     * overlay (see the KDoc on `mimirOverlayUsesGlass`).
     */
    @Test
    fun gameTitleEditKeepsTheParentLaunchCardsGlassBackdrop() {
        assertTrue(mimirOverlayUsesGlass(MimirOverlayKind.GAME_TITLE_EDIT))
    }

    /**
     * Liquid-glass diffusion. Pins that the Home launch
     * card's blur-while-editing reuses GamelistBackdrop's exact 34dp family (not MimirGlass's separate
     * 18dp scene-blur radius), animates only via the existing MimirMotion.OverlayMs/Easing tokens, and is
     * gated on the coarse GAME_TITLE_EDIT boolean rather than any per-frame/per-keystroke value -- the
     * PERF-F trap GamelistBackdrop.kt already documents hitting once in this programme.
     */
    @Test
    fun titleEditDiffusionReusesGamelistBackdropsBlurFamilyAndTheSharedMotionTokens() {
        val host = sourceFile("MimirAppHost.kt")

        val radius = host.sliceBetween(
            "private val HomeLaunchCardTitleEditBlurRadius",
            "private fun HomeLaunchCardOverlayHost(",
        )
        assertTrue(radius.contains("34.dp"))

        val overlayHost = host.sliceBetween(
            "private fun HomeLaunchCardOverlayHost(",
            "private fun HomeLaunchCardLiveContent(",
        )
        // To avoid blinking during launch-option changes, the radius is no
        // longer a Dp read in composition. The card wears `rememberHomeLaunchCardGlass`, a draw-phase level on the house tokens
        // (HomeLaunchCardGlass.kt), applied by `mimirGlassBackdropProgress` at the ceiling below.
        val glass = sourceFile("HomeLaunchCardGlass.kt")
        assertTrue(glass.contains("MimirMotion.OverlayMs"))
        assertTrue(glass.contains("MimirMotion.Easing"))
        assertTrue(Regex("""mimirGlassBackdropProgress\(\s*cardGlass\.radius,\s*cardGlass\.level,\s*snapshot = \{\s*homeLaunchCardSnapshotWanted\(""").containsMatchIn(overlayHost))
        // The gate is COARSE open/close boolean parameters, never `bindings.homeLaunchCard`'s own
        // per-frame launch/description/scroll state -- those are read further down in this same function
        // by the UNTOUCHED incoming/outgoing dissolve machinery, not by the glass above.
        //
        // Widened 2026-08-29: the card also softens behind its CHILD MODALS (the disc/version menu and Open-With), because
        // Home's card is a sibling host composed ABOVE the destination and the destination blur never reaches it -- the
        // same reason `titleEditActive` had to exist at all. Both gates are coarse booleans, and neither is per-frame state.
        assertTrue(overlayHost.contains("titleEditActive = titleEditActive,"))
        assertTrue(overlayHost.contains("childModalActive = childModalActive,"))
        // 2026-10-14 (review): the level always runs 0 to 1 and the RADIUS carries the difference, so a child modal is never left at a fractional level over a sharp layer.
        assertTrue(glass.contains("if (titleEditActive) titleEditRadius else childRadius"))
        assertTrue(glass.contains("val target = if (held) 1f else 0f"))
        assertTrue(glass.contains("if (requested && level.value <= 0.001f)"))
        // A child modal is an ordinary modal over a surface, so it uses the product's standard backdrop
        // radius, NOT title-edit's heavier deliberate push-back.
        assertTrue(overlayHost.contains("childRadius = MimirGlass.backdropBlurRadius,"))
        assertFalse(overlayHost.contains("childRadius = HomeLaunchCardTitleEditBlurRadius"))

        val appOverlayHost = host.sliceBetween(
            "private fun MimirAppOverlayHost(",
            "val directTarget = active",
        )
        assertTrue(appOverlayHost.contains("titleEditActive = active == MimirOverlayKind.GAME_TITLE_EDIT"))
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading to the whole
     * remaining file, unlike a bare `substringAfter`/`substringBefore`. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    /**
     *  Root
     * mechanism: `b7d65987` added `titleEditBlurRadius` as a BARE, un-keyed `animateDpAsState` directly
     * under `HomeLaunchCardOverlayHost`'s `key(dissolveCancellationEpoch)` block — unlike every other
     * piece of per-session state in that function (`frame`, `incomingAlpha`, `outgoingAlpha`,
     * `outgoingVisible`, all `remember(target, frame.epoch)`-scoped), so a blur transition left mid-flight
     * by a PRIOR session (e.g. Edit -> Cancel, card closed before the un-blur settles) kept animating on
     * its own clock while a NEW session's content dissolve played over it — two motions, decoupled, on the
     * same visual. This pins that the blur radius is now scoped to the SAME `(target, frame.epoch)` tuple
     * as the rest of the per-session state, via `key(...)`, so a new session always starts its blur fresh
     * at the coarse boolean's current value (0.dp outside an active title-edit) instead of inheriting a
     * stale in-flight animation. `HomeLaunchCardBlurScopeMechanismTest` separately proves, by actually
     * running Compose, that `key(scope) { animateDpAsState(...) }` is what makes that reset happen.
     */
    @Test
    fun titleEditBlurRadiusIsScopedToTheSameSessionTupleAsTheRestOfTheDissolveState() {
        val host = sourceFile("MimirAppHost.kt")

        val overlayHost = host.sliceBetween(
            "private fun HomeLaunchCardOverlayHost(",
            "private fun HomeLaunchCardLiveContent(",
        )
        val radiusSlice = overlayHost.sliceBetween(
            "val cardGlass",
            "LaunchedEffect(target, frame.epoch) {",
        )
        // The key is INSIDE a function of its own (2026-10-15): written inline in the host, it restarted every effect declared after it at the next recomposition,
        // the arrival's among them (HomeLaunchCardOpenSequenceTest). The scope is the same (target, frame.epoch) tuple.
        assertTrue(
            "the card's glass must be scoped to (target, frame.epoch), like incomingAlpha/outgoingAlpha, so it cannot outlive a session boundary",
            radiusSlice.contains("rememberHomeLaunchCardGlassPerCard(\n            cardKey = target,\n            epoch = frame.epoch,"),
        )
        assertFalse("and not by an inline key(...) in the host's own group", radiusSlice.contains("key(target, frame.epoch)"))
        val perCard = overlayHost.sliceBetween("private fun rememberHomeLaunchCardGlassPerCard(", "private fun rememberHomeLaunchCardUnderSheetAlpha(")
        val keyIndex = perCard.indexOf("key(cardKey, epoch)")
        val glassIndex = perCard.indexOf("rememberHomeLaunchCardGlass(")
        assertTrue(
            "key(cardKey, epoch) must wrap the rememberHomeLaunchCardGlass call, not merely appear nearby",
            keyIndex in 0 until glassIndex,
        )
    }

    @Test
    fun directMenusAndPickersUseTheSharedPixelFadeWithoutDelayingInput() {
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.THEME_PICKER))
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.PLATFORM_PICKER))
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.GAMELIST_ACHIEVEMENTS))
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU))
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.HOME_TOME_LIBRARY))
        assertFalse(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.RETURN_COVER))
        assertFalse(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.HOME_TILE_MENU))
        assertEquals(com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs, HomeOverlayDissolveMs)
        // Motion audit batch F (2026-09-28): the card's close joined the house overlay clock; 120 ms retired.
        assertEquals(com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs, HomeLaunchCardReturnDissolveMs)
        assertEquals(HomeOverlayDissolveMs, HomeLaunchCardReturnDissolveMs)
    }

    @Test
    fun rapidOverlayRetargetKeepsTheAlphaAlreadyPaintedByTheOutgoingLayer() {
        val artworkPage = MimirDirectOverlayTarget(MimirOverlayKind.ARTWORK_GET, "pick")
        val settingsPage = MimirDirectOverlayTarget(MimirOverlayKind.THEME_PICKER)

        assertEquals(
            0.46f,
            mimirIncomingLayerStartAlpha(
                target = artworkPage,
                cachedLayerTarget = artworkPage,
                cachedLayerAlpha = 0.46f,
                hasOutgoing = true,
            ),
        )
        assertEquals(
            0f,
            mimirIncomingLayerStartAlpha(
                target = settingsPage,
                cachedLayerTarget = artworkPage,
                cachedLayerAlpha = 0.46f,
                hasOutgoing = true,
            ),
        )
        assertEquals(
            1f,
            mimirIncomingLayerStartAlpha(
                target = settingsPage,
                cachedLayerTarget = null,
                cachedLayerAlpha = 0f,
                hasOutgoing = false,
            ),
        )
    }

    /**
     * the glass read as a blurred-then-sharp flicker.
     *
     * `Modifier.blur` is `graphicsLayer { renderEffect = if (radius > 0) Blur else null }` — the
     * graphics layer is applied even at 0.dp, so an unconditional `.blur()` rasterises the card's
     * text through an offscreen render node on EVERY frame of the common path, then snaps sharp when
     * the layer stops applying. The modifier must be attached only while there is something to blur.
     */
    @Test
    fun titleEditBlurModifierIsAttachedOnlyWhileTheRadiusIsNonZero() {
        // 2026-10-14: the same principle by a different mechanism. A conditional `Modifier.blur` toggled its layer on the first frame
        // past 0 dp and off at 0 (the "blink" on every option switch); the glass is now a CONSTANT modifier whose level is read at
        // draw time, and below ~0.1 px it draws the sharp layer alone with no render effect, so the common path is still layer-free
        // and nothing is added or removed mid-transition. Pinned against the code, not the comments that explain the old shape.
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val card = source.substringAfter("private fun HomeLaunchCardOverlayHost(").substringBefore("private fun HomeLaunchCardLiveContent(")
        assertFalse(
            "no Modifier.blur on the card: at 0.dp it still installs a graphicsLayer, which softens the card's text and then snaps it sharp",
            Regex("""\.blur\(""").containsMatchIn(card),
        )
        assertFalse("no conditional blur layer to toggle mid-transition", card.contains("blurring"))
        assertTrue(
            "the glass modifier is built once per progress and never branches on the level",
            card.contains("val cardGlassModifier = remember(cardGlass, incomingAlpha) {") &&
                Regex("""Modifier\.fillMaxSize\(\)\.mimirGlassBackdropProgress\(\s*cardGlass\.radius,\s*cardGlass\.level,\s*snapshot = \{\s*homeLaunchCardSnapshotWanted\(""").containsMatchIn(card) &&
                card.contains("Box(cardGlassModifier) {"),
        )
    }

}
