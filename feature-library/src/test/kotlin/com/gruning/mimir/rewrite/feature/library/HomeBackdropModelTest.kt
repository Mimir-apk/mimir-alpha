package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUX-F F5: the Home mode-collapse table and the pure idle-sleep reducer. The composable is intentionally
 * thin — every timing race the plan flags (fade-then-null ordering, launch/wake churn, stale fade) is
 * decided here and pinned off-device.
 */
class HomeBackdropModelTest {

    // ── F4/F5: mode collapse ─────────────────────────────────────────────────────────────────────────

    @Test
    fun homeCollapsesBothBifrostModesAndNeverUsesAurora() {
        assertTrue(homeBackdropActive(GamelistBackdropMode.BIFROST))
        assertTrue(homeBackdropActive(GamelistBackdropMode.BIFROST_BACKDROP))
        assertFalse(homeBackdropActive(GamelistBackdropMode.OFF))
        // F0 binding: never Aurora on Home (the blurred-cover wash is a gamelist-only eval engine).
        assertFalse(homeBackdropActive(GamelistBackdropMode.AURORA))
    }

    @Test
    fun everyModeHasAHomeDecision() {
        // A future GamelistBackdropMode fails to compile in homeBackdropActive (no `else`); this backstops
        // the runtime side — every current value returns without throwing.
        GamelistBackdropMode.values().forEach { homeBackdropActive(it) }
    }

    // ── F5: idle-sleep reducer ───────────────────────────────────────────────────────────────────────

    // An arbitrary SystemClock.uptimeMillis()-style baseline (device uptime is large and non-zero).
    private val t0 = 5_000_000L

    @Test
    fun freshStateIsAwakeVisibleAndRequestLive() {
        val s = HomeBackdropSleep(lastInputMillis = t0)
        assertEquals(HomeBackdropSleep.Phase.AWAKE, s.phase)
        assertEquals(1f, s.alphaTarget, 0f)
        assertFalse(s.requestReleased)
    }

    @Test
    fun idleBelowTheDeadlineStaysAwake() {
        val s = HomeBackdropSleep(lastInputMillis = t0)
            .onIdleElapsed(t0 + HomeBackdropIdleTimeoutMs - 1)
        assertEquals(HomeBackdropSleep.Phase.AWAKE, s.phase)
    }

    @Test
    fun aStillHeroNeverSleepsNoMatterHowLongTheShelfSitsUntouched() {
        // Policy 2026-09-17, after walking it: the hero art "goes after a while", and it should not.
        // The timeout exists to release a DECODER, which a still image does not have -- so for a still all
        // it buys is a Home screen that blanks while the player is reading it.
        //
        // A day of idling, not a second past the deadline: a rule that only holds near the boundary is the
        // kind that can disappear after an overnight idle period.
        var s = HomeBackdropSleep(lastInputMillis = t0)
        listOf(1L, HomeBackdropIdleTimeoutMs, HomeBackdropIdleTimeoutMs * 10, 86_400_000L).forEach { dt ->
            s = s.onIdleElapsed(t0 + dt, sleepEligible = false)
            assertEquals("a still hero must still be awake ${dt}ms in", HomeBackdropSleep.Phase.AWAKE, s.phase)
            assertEquals(1f, s.alphaTarget, 0f)
            assertFalse("and its request must never be released", s.requestReleased)
        }
    }

    @Test
    fun aClipStillSleepsBecauseItIsTheThingTheTimeoutWasWrittenFor() {
        // The other direction, so the fix above cannot be "solved" by disabling the sleep everywhere: the
        // clip path is unchanged and still tears its decoder down.
        val s = HomeBackdropSleep(lastInputMillis = t0)
            .onIdleElapsed(t0 + HomeBackdropIdleTimeoutMs, sleepEligible = true)
        assertEquals(HomeBackdropSleep.Phase.SLEEPING, s.phase)
    }

    @Test
    fun fadeReachesZeroBeforeTheRequestIsReleased() {
        // AWAKE → (deadline) → SLEEPING: the fade begins (alpha target 0) but the request stays LIVE so the
        // decoder is not torn down in front of the viewer.
        val sleeping = HomeBackdropSleep(lastInputMillis = t0)
            .onIdleElapsed(t0 + HomeBackdropIdleTimeoutMs)
        assertEquals(HomeBackdropSleep.Phase.SLEEPING, sleeping.phase)
        assertEquals(0f, sleeping.alphaTarget, 0f)
        assertFalse("request must stay live while the layer is still fading", sleeping.requestReleased)

        // SLEEPING → (fade complete at 0) → ASLEEP: only now is the request released.
        val asleep = sleeping.onFadeComplete()
        assertEquals(HomeBackdropSleep.Phase.ASLEEP, asleep.phase)
        assertEquals(0f, asleep.alphaTarget, 0f)
        assertTrue(asleep.requestReleased)
    }

    @Test
    fun requestIsNeverReleasedWithoutAFullFade() {
        // ASLEEP (the only request-released phase) is unreachable except through SLEEPING → onFadeComplete.
        val awake = HomeBackdropSleep(lastInputMillis = t0)
        assertSame(awake, awake.onFadeComplete()) // fade completion while awake is a no-op
        assertFalse(awake.onFadeComplete().requestReleased)
    }

    @Test
    fun inputWhileSleepingWakesAndKeepsTheRequestLive() {
        val woken = HomeBackdropSleep(lastInputMillis = t0)
            .onIdleElapsed(t0 + HomeBackdropIdleTimeoutMs)
            .onInput(t0 + HomeBackdropIdleTimeoutMs + 5)
        assertEquals(HomeBackdropSleep.Phase.AWAKE, woken.phase)
        assertEquals(1f, woken.alphaTarget, 0f)
        assertFalse(woken.requestReleased)
    }

    @Test
    fun inputWhileAsleepWakesAndReArmsTheRequest() {
        val asleep = HomeBackdropSleep(lastInputMillis = t0)
            .onIdleElapsed(t0 + HomeBackdropIdleTimeoutMs)
            .onFadeComplete()
        assertTrue(asleep.requestReleased)

        val woken = asleep.onInput(t0 + HomeBackdropIdleTimeoutMs + 100)
        assertEquals(HomeBackdropSleep.Phase.AWAKE, woken.phase)
        assertFalse(woken.requestReleased)
        assertEquals(t0 + HomeBackdropIdleTimeoutMs + 100, woken.lastInputMillis)
    }

    @Test
    fun inputChurnKeepsResettingTheDeadlineSoItNeverTripsEarly() {
        var s = HomeBackdropSleep(lastInputMillis = t0)
        // Ticks that reach the deadline arrive, but each is preceded by input that pushed the deadline out.
        for (i in 1..10) {
            val now = t0 + i * (HomeBackdropIdleTimeoutMs - 1)
            s = s.onIdleElapsed(now)
            assertEquals("churn must never let the deadline trip", HomeBackdropSleep.Phase.AWAKE, s.phase)
            s = s.onInput(now)
        }
    }

    @Test
    fun aStaleFadeCompletionAfterWakeIsIgnored() {
        // The fade started (SLEEPING), the user woke it (AWAKE), then the cancelled animation's completion
        // signal still arrives — it must NOT drop the request out from under a now-awake backdrop.
        val afterStaleFade = HomeBackdropSleep(lastInputMillis = t0)
            .onIdleElapsed(t0 + HomeBackdropIdleTimeoutMs) // SLEEPING
            .onInput(t0 + HomeBackdropIdleTimeoutMs + 1)    // AWAKE (wake cancels the fade)
            .onFadeComplete()                               // stale completion of the cancelled fade
        assertEquals(HomeBackdropSleep.Phase.AWAKE, afterStaleFade.phase)
        assertFalse(afterStaleFade.requestReleased)
    }

    @Test
    fun repeatedInputAtTheSameInstantDoesNotChurnState() {
        val s = HomeBackdropSleep(lastInputMillis = t0)
        assertSame(s, s.onInput(t0))
    }

    // ── F5: mount eligibility ladder (homeBackdropMountClip) — each rung → null ───────────────────────

    private val emulatedId = HomeShelfItemId.Emulated(GameId("game-1"))
    private val androidId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))
    private val androidAppId = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app"))
    private val localClip = "file:///preview.mp4"

    private fun mount(
        mode: GamelistBackdropMode = GamelistBackdropMode.BIFROST,
        reducedMotion: Boolean = false,
        itemId: HomeShelfItemId? = emulatedId,
        clip: String? = localClip,
        powerSave: Boolean = false,
    ) = homeBackdropMountClip(mode, reducedMotion, itemId, clip, powerSave)

    @Test
    fun mountsForASettledEmulatedRowWithALocalClip() {
        assertEquals(localClip, mount())
        assertEquals("content://mimir/preview", mount(clip = "content://mimir/preview"))
    }

    @Test
    fun modeOffIsTheOnlyOffSwitchNowThatTheBuildKillSwitchIsGone() {
        // HOME_BACKDROP_ENABLED is retired and Settings is the sole authority, so
        // Backdrop `Off` (mode OFF) has to be a complete off-state on its own.
        assertNull(mount(mode = GamelistBackdropMode.OFF))
    }

    @Test
    fun offAndAuroraModesNeverMountButBothBifrostModesDo() {
        assertNull(mount(mode = GamelistBackdropMode.OFF))
        assertNull(mount(mode = GamelistBackdropMode.AURORA))
        assertEquals(localClip, mount(mode = GamelistBackdropMode.BIFROST))
        assertEquals(localClip, mount(mode = GamelistBackdropMode.BIFROST_BACKDROP))
    }

    @Test
    fun neitherAndroidKindInheritsAnAutomaticClip() {
        assertNull(mount(itemId = androidId))
        assertNull(mount(itemId = androidAppId))
    }

    // ── UXP-01: the per-game CHOSEN hero  ──────────────────────────────────────

    private fun mountChosen(
        chosenHeroUri: String? = null,
        chosenPreviewVideoUri: String? = null,
        mode: GamelistBackdropMode = GamelistBackdropMode.BIFROST,
        reducedMotion: Boolean = false,
        itemId: HomeShelfItemId? = emulatedId,
        clip: String? = localClip,
        powerSave: Boolean = false,
    ) = homeBackdropMount(
        mode, reducedMotion, itemId, clip, powerSave, chosenHeroUri, chosenPreviewVideoUri,
    )

    // ── the automatic Hero Art rung  ───────────────────────────────────

    private fun mountAuto(
        clip: String? = null,
        heroArt: String? = null,
        chosenHeroUri: String? = null,
        chosenPreviewVideoUri: String? = null,
        mode: GamelistBackdropMode = GamelistBackdropMode.BIFROST,
        reducedMotion: Boolean = false,
        itemId: HomeShelfItemId? = emulatedId,
        powerSave: Boolean = false,
    ) = homeBackdropMount(
        mode = mode,
        reducedMotion = reducedMotion,
        focusedItemId = itemId,
        focusedPreviewVideoUri = clip,
        powerSave = powerSave,
        chosenHeroUri = chosenHeroUri,
        chosenPreviewVideoUri = chosenPreviewVideoUri,
        focusedHeroUri = heroArt,
    )

    /**
     * **Home hero-art regression:** Library showed hero art while Home did not.
     *
     * Until 2026-09-17 this ladder ended at the automatic-clip rung, so the only automatic backdrop Home
     * could mount was a clip. A game with Hero Art and no clip -- which is EVERY game now that foreign
     * `.mp4` files beside the ROM are no longer adopted -- mounted nothing at all, while the Library kept
     * drawing the same hero from `GameRow.heroArtUri`.
     */
    @Test
    fun automaticHeroArtMountsWhenThereIsNoClip() {
        assertEquals(
            HomeBackdropMount.Static("file:///media_art/sonic-hero.png"),
            mountAuto(clip = null, heroArt = "file:///media_art/sonic-hero.png"),
        )
    }

    /** The policy in order: "Hero Motion when it has one, otherwise Hero Art." Motion still wins. */
    @Test
    fun anAutomaticClipStillOutranksAutomaticHeroArt() {
        assertEquals(
            HomeBackdropMount.Video(localClip),
            mountAuto(clip = localClip, heroArt = "file:///media_art/sonic-hero.png"),
        )
    }

    /** A chosen hero outranks automatic art, the same way it already outranks the automatic clip. */
    @Test
    fun aChosenHeroStillOutranksAutomaticHeroArt() {
        assertEquals(
            HomeBackdropMount.Static("file:///chosen.png"),
            mountAuto(heroArt = "file:///automatic-hero.png", chosenHeroUri = "file:///chosen.png"),
        )
    }

    /** Off must still mean off: the new rung must not become a way to draw a backdrop the player declined. */
    @Test
    fun automaticHeroArtIsStillSuppressedWhenTheBackdropIsOff() {
        assertNull(
            mountAuto(heroArt = "file:///media_art/sonic-hero.png", mode = GamelistBackdropMode.OFF),
        )
    }

    /** An Android row takes only its own explicit Home Hero and never inherits a game's art. */
    @Test
    fun anAndroidRowDoesNotInheritAutomaticHeroArt() {
        assertNull(mountAuto(heroArt = "file:///media_art/sonic-hero.png", itemId = androidId))
    }

    @Test
    fun aChosenStaticHeroWinsOverTheAutomaticClipAndRendersAsAStill() {
        val mount = mountChosen(chosenHeroUri = "file:///chosen.png")

        assertEquals(HomeBackdropMount.Static("file:///chosen.png"), mount)
    }

    @Test
    fun decodePresentedTomeArtUsesItsPairedNonphysicalBackdrop() {
        val preview = HomeTomePreview(
            artUri = "file:///chrono-tile.png",
            platformId = PlatformId("snes"),
            backdropArtUri = "file:///chrono-hero.png",
        )

        val lead = homeTomeBackdropLeadArt(preview)

        assertEquals("file:///chrono-hero.png", lead)
        assertEquals(
            HomeBackdropMount.Static("file:///chrono-hero.png"),
            mountChosen(chosenHeroUri = lead, itemId = null, clip = null),
        )
        assertNull(homeTomeBackdropLeadArt(preview.copy(backdropArtUri = "https://example.test/art.png")))
        assertNull(homeTomeBackdropLeadArt(preview.copy(shape = ShelfFaceShape.PHYSICAL, backdropArtUri = null)))
    }

    @Test
    fun aChosenPreviewClipRoutesToTheVideoEngine() {
        val mount = mountChosen(chosenPreviewVideoUri = "file:///chosen.mp4")

        assertEquals(HomeBackdropMount.Video("file:///chosen.mp4"), mount)
    }

    @Test
    fun withNoOverrideTheAutomaticClipStillMountsExactlyAsBefore() {
        assertEquals(HomeBackdropMount.Video(localClip), mountChosen())
    }

    @Test
    fun powerSaveSuppressesAChosenVideoButNotAChosenStill() {
        // A chosen clip does not license a decoder the battery mode excludes; it degrades to the
        // plain scene rather than to the automatic clip, which the user did not choose.
        assertNull(mountChosen(chosenPreviewVideoUri = "file:///c.mp4", powerSave = true))
        // A still costs no decoder, so power save leaves it alone.
        assertEquals(
            HomeBackdropMount.Static("file:///c.png"),
            mountChosen(chosenHeroUri = "file:///c.png", powerSave = true),
        )
    }

    @Test
    fun chosenClipAndHeroCoexistAndTheStillIsThePowerSaveFallback() {
        assertEquals(
            HomeBackdropMount.Video("file:///clip.mp4"),
            mountChosen(chosenHeroUri = "file:///hero.png", chosenPreviewVideoUri = "file:///clip.mp4"),
        )
        assertEquals(
            HomeBackdropMount.Static("file:///hero.png"),
            mountChosen(
                chosenHeroUri = "file:///hero.png",
                chosenPreviewVideoUri = "file:///clip.mp4",
                powerSave = true,
            ),
        )
    }

    @Test
    fun everyGateStillOutranksAChosenHero() {
        // Backdrop Off and reduced motion still suppress an explicitly chosen Hero.
        assertNull(mountChosen(chosenHeroUri = "file:///c.png", mode = GamelistBackdropMode.OFF))
        assertNull(mountChosen(chosenHeroUri = "file:///c.png", reducedMotion = true))
    }

    @Test
    fun AndroidPackageRowsMountOnlyTheirExplicitLocalHomeHero() {
        listOf(androidId, androidAppId).forEach { itemId ->
            assertEquals(
                HomeBackdropMount.Static("file:///android-hero.png"),
                mountChosen(
                    chosenHeroUri = "file:///android-hero.png",
                    itemId = itemId,
                    clip = "file:///must-not-inherit.mp4",
                ),
            )
            assertNull(mountChosen(itemId = itemId, clip = "file:///must-not-inherit.mp4"))
        }
    }

    @Test
    fun theLegacyClipHelperStillReportsOnlyVideoMounts() {
        // homeBackdropMountClip is kept for callers that only care about the clip; a chosen STILL must
        // not leak out of it as if it were a video uri.
        assertNull(
            homeBackdropMountClip(
                GamelistBackdropMode.BIFROST, false, emulatedId, null, false,
            ),
        )
        assertEquals(
            localClip,
            homeBackdropMountClip(GamelistBackdropMode.BIFROST, false, emulatedId, localClip, false),
        )
    }

    @Test
    fun reducedMotionLeavesThePlainScene() {
        assertNull(mount(reducedMotion = true))
    }

    @Test
    fun androidFocusedRowNeverGetsVideoEvenWithAClip() {
        // Belt-and-suspenders: even if some path populated a clip on an Android row, it must not mount.
        assertNull(mount(itemId = androidId))
        assertNull(mount(itemId = androidId, clip = localClip))
    }

    @Test
    fun powerSaveUnmountsTheLayer() {
        assertNull(mount(powerSave = true))
    }

    @Test
    fun noClipOrRemoteClipNeverMounts() {
        assertNull(mount(clip = null))
        assertNull(mount(clip = ""))
        assertNull(mount(clip = "https://example.invalid/preview.mp4"))
        assertNull(mount(clip = "http://example.invalid/preview.mp4"))
    }

    @Test
    fun nullFocusedRowNeverMounts() {
        assertNull(mount(itemId = null, clip = null))
    }
}
