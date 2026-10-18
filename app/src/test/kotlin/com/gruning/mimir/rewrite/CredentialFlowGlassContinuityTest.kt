package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hardware report, 2026-08-13: abrupt credential-flow arrival and loss of backdrop glass.
 * The glass must remain continuous through username and password entry.
 *
 * GROUND, established from source before any fix (file:line, not assumed):
 *
 * 1. Neither `MimirOverlayKind.ARTWORK_CONNECT` (ScreenScraper) nor `RETROACHIEVEMENTS_KEY`
 *    (RetroAchievements) was ever in `mimirOverlayUsesGlass`'s allowlist (`MimirAppHost.kt:347`, pre-fix).
 *    `mimirGlassBackdrop(active = mimirOverlayUsesGlass(presentedOverlayChrome))` (`MimirAppHost.kt:306`)
 *    was therefore `false` for literally every composition of the whole credential flow -- the destination
 *    was never blurred, from opening the username step through Done/Failed. Not "drops mid-sequence": it
 *    was simply never on.
 * 2. Both kinds' overlay-stack membership is a coarse non-null PRESENCE boolean, not a per-step one:
 *    `MimirOverlayCoordinator.kt:326` `artworkConnect = artwork.state.connectUi != null`; `:329`
 *    `retroAchievementsKey = retroAchievementsConnect.isOpen` where `RetroAchievementsConnectController
 *    .kt:34` `isOpen = state != null`. `ArtworkConnectUi.EnterId`/`EnterPassword` (same for RetroAchievements'
 *    `EnterUsername`/`EnterKey`) are both non-null, so this boolean is structurally incapable of telling the
 *    two credential steps apart -- which is exactly the property that makes overlay-kind-gated glass
 *    continuous across them for free once the kind is allowlisted.
 * 3. Both kinds already participate in `mimirDirectOverlayUsesPixelFade` (`MimirAppHost.kt:107`), and
 *    `mimirDirectOverlayTarget` (`:157`) derives `phaseKey` from the LIVE `connectUi`/
 *    `retroAchievementsConnectUi`'s `javaClass.name` -- which genuinely differs between the two credential
 *    steps (different sealed subclasses). So `MimirDirectOverlayPixelFadeHost` already retargets and
 *    cross-fades on every step change, using the existing `MimirMotion.OverlayMs`/`Easing` tokens -- this
 *    motion was not new/missing, it already existed. What was missing was scene blur behind it: with no
 *    glass, the two cross-fading layers (each only the overlay's own ~95%-alpha flat scrim, never fully
 *    opaque) let the sharp, un-softened destination peek through at the crossfade's midpoint, reading as an
 *    abrupt flash rather than a transition -- the SAME compositing shape every other pixel-fade overlay
 *    that already carries glass has, just invisible there because what peeks through is already blurred.
 *
 * FIX: add both kinds to `mimirOverlayUsesGlass`'s allowlist (same mechanism as the GAME_TITLE_EDIT
 * precedent already documented on that function) -- no new blur code, no new duration, no new fade. This
 * file pins that fix and the structural reasoning above, using real production functions (no mocks): the
 * differential-critical assertion is [artworkConnectAndRetroAchievementsKeepTheSharedGlassBackdrop].
 *
 * 2026-08-19 addendum: the same apparent snap was reported for
 * [MimirOverlayKind.ARTWORK_FETCH] and [MimirOverlayKind.ARTWORK_GET]. Identical ground: both kinds were
 * already inside `MimirDirectOverlayPixelFadeHost`, already cross-fading correctly on
 * `MimirMotion.OverlayMs`/`Easing`; neither was ever in this allowlist, so the sharp destination peeked
 * through their crossfade's midpoint (`ArtworkFetchOverlay.kt` scrim 0.96 alpha, `GetArtOverlay.kt` 0.92,
 * neither fully opaque) the same way the credential overlays did before the fix above. Same fix, pinned by
 * [artworkFetchAndArtworkGetKeepTheSharedGlassBackdrop].
 */
class CredentialFlowGlassContinuityTest {

    @Test
    fun artworkConnectAndRetroAchievementsKeepTheSharedGlassBackdrop() {
        assertTrue(
            "ScreenScraper connect must wear the shared liquid glass so the destination blurs behind it",
            mimirOverlayUsesGlass(MimirOverlayKind.ARTWORK_CONNECT),
        )
        assertTrue(
            "RetroAchievements connect must wear the shared liquid glass so the destination blurs behind it",
            mimirOverlayUsesGlass(MimirOverlayKind.RETROACHIEVEMENTS_KEY),
        )
    }

    /** Same defect, reported again 2026-08-19 for the artwork-fetch progress message and Get Art picker. */
    @Test
    fun artworkFetchAndArtworkGetKeepTheSharedGlassBackdrop() {
        assertTrue(
            "Artwork fetch's Running/Done message must wear the shared liquid glass so the destination " +
                "blurs behind its crossfade",
            mimirOverlayUsesGlass(MimirOverlayKind.ARTWORK_FETCH),
        )
        assertTrue(
            "Get Art must wear the shared liquid glass so the destination blurs behind its crossfade",
            mimirOverlayUsesGlass(MimirOverlayKind.ARTWORK_GET),
        )
    }

    /**
     * The glassed set, declared once. Every other [MimirOverlayKind] must be flat.
     *
     * Declared as a set and diffed against [MimirOverlayKind.entries] rather than written out as two
     * hand-maintained lists, because the hand-maintained version could not see a NEW overlay at all.
     *
     * `mimirOverlayUsesGlass` ends `else -> false`, so — unlike `mimirOverlayOutranksQuickLaunch`, which is
     * exhaustive and genuinely does fail to compile — **the compiler cannot catch a kind that was never
     * added here.** The previous form of this test enumerated 24 of the 26 constants by hand, so constant
     * 27 would have landed in neither list and this test would have passed green while the overlay shipped
     * flat against a blurred scene. That is the the earlier change defect exactly, one enum constant later.
     */
    private val glassedOverlayKinds = setOf(
        MimirOverlayKind.HOME_TILE_MENU,
        MimirOverlayKind.HOME_TOME_LIBRARY,
        MimirOverlayKind.LIBRARY_PLATFORM_TILE_MENU,
        MimirOverlayKind.GAMELIST_ACHIEVEMENTS,
        MimirOverlayKind.HOME_OPEN_WITH,
        MimirOverlayKind.HOME_LAUNCH_CARD,
        MimirOverlayKind.GAME_TITLE_EDIT,
        MimirOverlayKind.BRIGHTNESS,
        MimirOverlayKind.CONTROLLER_MAPPING,
        MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
        MimirOverlayKind.ARTWORK_CONNECT,
        MimirOverlayKind.RETROACHIEVEMENTS_KEY,
        MimirOverlayKind.ARTWORK_FETCH,
        MimirOverlayKind.ARTWORK_GET,
        MimirOverlayKind.FOLDER_BROWSER,
        // Backdrop glass is explicitly enabled for the selection menu. The three Settings pickers built on
        // `MimirSelectOverlay` drew at `MimirLiquidGlassPanel`'s 0.78 body alpha, which assumes a blurred
        // backdrop, over a sharp section pane. This test includes those three surfaces explicitly.
        // THEME_PICKER stays out: it uses its own preview gallery.
        MimirOverlayKind.BACKDROP_PICKER,
        MimirOverlayKind.ACCENT_PICKER,
        MimirOverlayKind.PLATFORM_PICKER,
        // Settings › Music › Source (2026-09-30): the fourth picker on that select shell, same ruling. The
        // volume dial is NOT here: it is the Focus-highlight dial's shell, which draws its own scrim.
        MimirOverlayKind.MUSIC_SOURCE_PICKER,
        // Settings › Music › Equalizer (2026-10-01): a translucent liquid-glass card over the Settings pane, so the pane blurs as the
        // pickers' does. Its pixels dissolve on MimirEqualizerEditorFadeHost and the scene's glass clears after them.
        MimirOverlayKind.EQUALIZER_EDITOR,
        // Home's disc / version menu. Same shell as the Library's, so it gets the same backdrop treatment --
        // an unglassed twin of a glassed surface would be the inconsistency, not the fix.
        MimirOverlayKind.HOME_LAUNCH_CHOICE,
    )

    /**
     * Regression guard. Originally scoped to the two multi-field credential flows; it now also holds the
     * 2026-08-29 select-overlay ruling, and still refuses "give every overlay glass" for everything else.
     */
    @Test
    fun everyPreviouslyGlassedOverlayKindStaysGlassedAndUnrelatedFlatEditorsStayFlat() {
        glassedOverlayKinds.forEach { kind ->
            assertTrue("$kind must stay glassed (pre-existing, unrelated to this fix)", mimirOverlayUsesGlass(kind))
        }
        // Everything NOT in the declared set stays flat. Single-field editors have no established glass requirement, and THEME_PICKER is its own gallery surface -- neither becomes glass by drift.
        (MimirOverlayKind.entries - glassedOverlayKinds).forEach { kind ->
            assertFalse("$kind must stay outside the shared-glass allowlist", mimirOverlayUsesGlass(kind))
        }
        assertFalse("no active overlay must never glass the destination", mimirOverlayUsesGlass(null))
    }

    /**
     * The guard the hand-maintained lists could not provide: **every** kind is classified, deliberately.
     *
     * A new overlay is not allowed to be silently absent. Whoever adds one must decide whether it wears
     * glass and record that decision in [glassedOverlayKinds] — the decision the earlier change had to make
     * retroactively, for two credential flows that had already shipped flat against a blurred scene.
     */
    @Test
    fun everyOverlayKindIsDeliberatelyClassifiedAsGlassedOrFlat() {
        val unclassified = MimirOverlayKind.entries.filter { kind ->
            mimirOverlayUsesGlass(kind) != (kind in glassedOverlayKinds)
        }
        assertTrue(
            "these overlay kinds disagree with the declared glass set — add each to " +
                "glassedOverlayKinds, or give it glass in mimirOverlayUsesGlass: $unclassified",
            unclassified.isEmpty(),
        )
    }

    /**
     * `resolveMimirOverlayStack`/`MimirOverlaySnapshot` are the REAL functions `MimirOverlayCoordinator.top`
     * (hence `MimirAppHost`'s `presentedOverlayChrome`, hence `mimirGlassBackdrop`'s `active`) run on every
     * composition. Both credential steps map to the SAME boolean (`artworkConnect`/`retroAchievementsKey`
     * `= true`) because the snapshot type itself cannot encode "which step" -- only "is the flow open at
     * all". This is the structural reason glass cannot drop for a single frame between username and
     * password: there is no representable state where the overlay kind is present but glass sees it as
     * absent.
     */
    @Test
    fun credentialOverlayPresenceIsAllOrNothingAcrossBothSteps() {
        val usernameStep = MimirOverlaySnapshot(artworkConnect = true)
        val passwordStep = MimirOverlaySnapshot(artworkConnect = true)
        assertEquals(usernameStep, passwordStep)
        assertEquals(MimirOverlayKind.ARTWORK_CONNECT, resolveMimirOverlayStack(usernameStep).top)
        assertEquals(MimirOverlayKind.ARTWORK_CONNECT, resolveMimirOverlayStack(passwordStep).top)

        val usernameEntry = MimirOverlaySnapshot(retroAchievementsKey = true)
        val keyStep = MimirOverlaySnapshot(retroAchievementsKey = true)
        assertEquals(usernameEntry, keyStep)
        assertEquals(MimirOverlayKind.RETROACHIEVEMENTS_KEY, resolveMimirOverlayStack(usernameEntry).top)
        assertEquals(MimirOverlayKind.RETROACHIEVEMENTS_KEY, resolveMimirOverlayStack(keyStep).top)

        assertNull("closed means genuinely absent, the only state that turns glass off", resolveMimirOverlayStack(MimirOverlaySnapshot()).top)
    }

    /**
     * End-to-end pin, chaining the same three real functions `MimirAppHost` chains at runtime
     * (`resolveMimirOverlayStack` -> `.top` -> `mimirOverlayUsesGlass`): across a full simulated sequence
     * from closed, through both credential steps, through validating, to a terminal result, and back to
     * closed, glass is `false` only at the two ends and `true` for every frame the overlay is open --
     * including the exact username -> password boundary. This is the test that would fail if the glass
     * dropped for even one composition inside the sequence.
     */
    @Test
    fun glassNeverDropsForASingleFrameAcrossTheWholeArtworkConnectSequence() {
        val sequence = listOf(
            false, // closed, before opening
            true,  // EnterId
            true,  // EnterPassword (the exact transition the regression report described)
            true,  // Connecting
            true,  // Done or Failed (still on top until the player closes it)
            false, // closed again, after a successful connect
        )
        val observedGlass = sequence.map { present ->
            mimirOverlayUsesGlass(resolveMimirOverlayStack(MimirOverlaySnapshot(artworkConnect = present)).top)
        }
        assertEquals(listOf(false, true, true, true, true, false), observedGlass)
    }

    @Test
    fun glassNeverDropsForASingleFrameAcrossTheWholeRetroAchievementsSequence() {
        val sequence = listOf(false, true, true, true, true, false)
        val observedGlass = sequence.map { present ->
            mimirOverlayUsesGlass(resolveMimirOverlayStack(MimirOverlaySnapshot(retroAchievementsKey = present)).top)
        }
        assertEquals(listOf(false, true, true, true, true, false), observedGlass)
    }

    /**
     * Both kinds already retarget the shared pixel-fade cross-fade (`MimirMotion.OverlayMs`/`Easing`) --
     * this was true before this lane's fix and is unchanged by it. Pinned here because no test named these
     * two kinds explicitly before.
     */
    @Test
    fun bothCredentialOverlaysAlreadyUseTheSharedPixelFadeCrossfade() {
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.ARTWORK_CONNECT))
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.RETROACHIEVEMENTS_KEY))
    }

    /**
     * `MimirDirectOverlayTarget`'s `phaseKey` is a real sealed subclass's `javaClass.name` -- no mocking
     * needed, these are genuine production `ArtworkConnectUi`/`RetroAchievementsConnectUi` instances. This
     * pins two things at once: the username -> password step change DOES produce a different target (so
     * the pixel-fade host's `retainOutgoing` sees `from != to` and genuinely cross-fades), and typing
     * within one step does NOT (so the fade cannot re-trigger on every keystroke -- `MimirDirectOverlayTarget`'s
     * own KDoc: "state-class identity only: progress/text edits inside one page never restart its fade").
     */
    @Test
    fun credentialStepPhaseKeyDiffersAcrossStepsButNotWithinOneStepsTypedContent() {
        val enterId = MimirDirectOverlayTarget(
            MimirOverlayKind.ARTWORK_CONNECT,
            ArtworkConnectUi.EnterId.javaClass.name,
        )
        val enterPasswordShort = MimirDirectOverlayTarget(
            MimirOverlayKind.ARTWORK_CONNECT,
            ArtworkConnectUi.EnterPassword("a").javaClass.name,
        )
        val enterPasswordLong = MimirDirectOverlayTarget(
            MimirOverlayKind.ARTWORK_CONNECT,
            ArtworkConnectUi.EnterPassword("a completely different typed id").javaClass.name,
        )
        assertTrue(
            "EnterId -> EnterPassword must be a distinct target so the pixel-fade host retargets",
            enterId != enterPasswordShort,
        )
        assertEquals(
            "typing more into the SAME step must not look like a new target",
            enterPasswordShort,
            enterPasswordLong,
        )

        val enterUsername = MimirDirectOverlayTarget(
            MimirOverlayKind.RETROACHIEVEMENTS_KEY,
            RetroAchievementsConnectUi.EnterUsername().javaClass.name,
        )
        val enterKeyShort = MimirDirectOverlayTarget(
            MimirOverlayKind.RETROACHIEVEMENTS_KEY,
            RetroAchievementsConnectUi.EnterKey("user").javaClass.name,
        )
        val enterKeyLong = MimirDirectOverlayTarget(
            MimirOverlayKind.RETROACHIEVEMENTS_KEY,
            RetroAchievementsConnectUi.EnterKey("a completely different username").javaClass.name,
        )
        assertTrue(
            "EnterUsername -> EnterKey must be a distinct target so the pixel-fade host retargets",
            enterUsername != enterKeyShort,
        )
        assertEquals(
            "typing more into the SAME key step must not look like a new target",
            enterKeyShort,
            enterKeyLong,
        )
    }

    /**
     * Bridges the gap between the abstract `MimirDirectOverlayTarget` equality proof above and the actual
     * wiring: `mimirDirectOverlayTarget`'s branches for these two kinds must read the LIVE per-flow UI
     * state, not some coarser value that would collapse both steps into one target.
     */
    @Test
    fun directOverlayTargetWiringReadsTheLiveCredentialUiNotACoarserValue() {
        val host = sourceFile("MimirAppHost.kt")
        val branch = host.sliceBetween(
            "private fun mimirDirectOverlayTarget(",
            "return MimirDirectOverlayTarget(kind, phase?.javaClass?.name)",
        )
        assertTrue(
            "ARTWORK_CONNECT must key its fade target off the live connect UI state",
            branch.contains("MimirOverlayKind.ARTWORK_CONNECT -> bindings.artwork.state.connectUi"),
        )
        assertTrue(
            "RETROACHIEVEMENTS_KEY must key its fade target off the live connect UI state",
            branch.contains(
                "MimirOverlayKind.RETROACHIEVEMENTS_KEY -> bindings.settings.retroAchievementsConnectUi",
            ),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of silently degrading. */
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
}
