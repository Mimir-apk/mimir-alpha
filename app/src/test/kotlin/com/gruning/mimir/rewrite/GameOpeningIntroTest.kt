package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOpeningIntroTest {
    @Test
    fun planRequiresEnabledMainAndHeroAsset() {
        val game = game()

        assertNull(gameOpeningIntroPlan(game, MimirGamePlacement.MAIN, enabled = false))
        assertNull(gameOpeningIntroPlan(game, MimirGamePlacement.BOTTOM, enabled = true))
        assertNull(gameOpeningIntroPlan(game(hero = null), MimirGamePlacement.MAIN, enabled = true))
        val logoOptionalPlan = gameOpeningIntroPlan(game(logo = " "), MimirGamePlacement.MAIN, enabled = true)
        assertNotNull(logoOptionalPlan)
        assertNull(logoOptionalPlan?.logoUri)
    }

    @Test
    fun blankPreviewAudioIsOmitted() {
        val plan = gameOpeningIntroPlan(
            game(previewVideoUri = "  "),
            MimirGamePlacement.MAIN,
            enabled = true,
        )

        assertNull(plan?.audioUri)
    }

    // ── the game's own sound : previewClipUri ?: launchAudioUri, local only ─────────

    private fun audioOf(game: GameRow): String? =
        gameOpeningIntroPlan(game, MimirGamePlacement.MAIN, enabled = true)?.audioUri

    @Test
    fun theSavedSoundPlaysWhenThePlayerChoseNoClip() {
        val sound = "file:///storage/ROMs/snes/media_art/Opening%20Game-audio.m4a"

        assertEquals(sound, audioOf(game(previewVideoUri = null, launchAudioUri = sound)))
        assertEquals(
            "a blank clip is no clip",
            sound,
            audioOf(game(previewVideoUri = "  ", launchAudioUri = sound)),
        )
    }

    @Test
    fun theLibrarysOwnSafDocumentPlaysToo() {
        val sound = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/opaque-sound-05"

        assertEquals(sound, audioOf(game(previewVideoUri = null, launchAudioUri = sound)))
    }

    @Test
    fun aVersionFragmentOnTheSoundPassesThroughUntouched() {
        val sound = "file:///storage/ROMs/snes/media_art/Opening%20Game-audio.m4a#mv=b3"

        assertEquals(sound, audioOf(game(previewVideoUri = null, launchAudioUri = sound)))
    }

    @Test
    fun thePlayersOwnClipOutranksTheSavedSound() {
        val clip = "file:///data/user/0/com.gruning.mimir.rewrite/files/hero/Opening%20Game.mp4#mv=7"
        val sound = "file:///storage/ROMs/snes/media_art/Opening%20Game-audio.m4a"

        assertEquals(clip, audioOf(game(previewVideoUri = clip, launchAudioUri = sound)))
    }

    /**
     * **URL safety.** ScreenScraper media URLs carry credentials and [playGameOpeningIntro] logs and plays the plan's
     * audio. The sound can only be a `file://` or the library's own `content://`: anything else, however it got onto the
     * row, yields no sound at all and the intro plays as it always has.
     */
    @Test
    fun aSoundThatIsNotLocalIsNeverPlayedOrLogged() {
        listOf(
            "https://www.screenscraper.fr/medias/video.mp4?devid=D&devpassword=P&ssid=U&sspassword=S",
            "http://example.invalid/sound.m4a",
            "HTTPS://example.invalid/sound.m4a",
            "ftp://example.invalid/sound.m4a",
            "/storage/ROMs/snes/media_art/Opening Game-audio.m4a",
            " file:///storage/ROMs/snes/media_art/Opening%20Game-audio.m4a",
            "",
            "   ",
        ).forEach { notLocal ->
            val plan = gameOpeningIntroPlan(
                game(previewVideoUri = null, launchAudioUri = notLocal),
                MimirGamePlacement.MAIN,
                enabled = true,
            )

            assertNotNull("the intro itself still plays", plan)
            assertNull("'$notLocal' must not become the intro's audio", plan?.audioUri)
        }
    }

    @Test
    fun theLocalOnlyGateSaysExactlyFileAndContent() {
        assertTrue(isLocalLaunchAudioUri("file:///a-audio.m4a"))
        assertTrue(isLocalLaunchAudioUri("FILE:///a-audio.m4a"))
        assertTrue(isLocalLaunchAudioUri("content://provider/tree/x/document/y"))
        assertFalse(isLocalLaunchAudioUri("https://example.invalid/a.m4a"))
        assertFalse(isLocalLaunchAudioUri("http://example.invalid/a.m4a"))
        assertFalse(isLocalLaunchAudioUri("file:/a-audio.m4a"))
        assertFalse(isLocalLaunchAudioUri("android.resource://pkg/1"))
    }

    @Test
    fun theStartLineNamesTheKindOfSoundNeverItsUri() {
        val path = "file:///storage/ROMs/snes/media_art/Opening%20Game-audio.m4a"
        val saf = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/opaque-sound-05"

        assertEquals("none", gameOpeningIntroAudioLogLabel(null))
        assertEquals("file", gameOpeningIntroAudioLogLabel(path))
        assertEquals("content", gameOpeningIntroAudioLogLabel(saf))
        // Even a value this gate would never have let through is reduced to a word, not echoed.
        assertEquals("other", gameOpeningIntroAudioLogLabel("https://www.screenscraper.fr/x.mp4?sspassword=S"))
        listOf(path, saf, "https://www.screenscraper.fr/x.mp4?sspassword=S").forEach { uri ->
            val label = gameOpeningIntroAudioLogLabel(uri)
            assertFalse(label, label.contains("/") || label.contains("http") || label.contains("Opening"))
        }
    }

    @Test
    fun theSourcesSeamLeavesTheSavedSoundAlone() {
        // gameOpeningIntroSources layers the player's chosen hero and clip over the row. It must not touch launchAudioUri,
        // because the row's projection is the only thing that decides what sound a game has saved.
        val source = java.io.File(
            listOf(
                "app/src/main/kotlin/com/gruning/mimir/rewrite/GameOpeningIntro.kt",
                "src/main/kotlin/com/gruning/mimir/rewrite/GameOpeningIntro.kt",
            ).first { java.io.File(it).isFile },
        ).readText()
        val sources = source.substringAfter("internal fun gameOpeningIntroSources(").substringBefore("internal fun gameOpeningIntroPlan(")

        assertFalse(sources.contains("launchAudioUri"))
        assertTrue(sources.contains("previewVideoUri = CustomHeroStore.previewClipUri(context, game.gameId) ?: game.previewVideoUri"))
    }

    @Test
    fun timelineUsesTheOwnerConceptBeats() {
        assertEquals(3_000L, GameOpeningIntroHoldMs)
        assertEquals(600, GameOpeningIntroEnterMs)
        assertEquals(12, GameOpeningIntroLogoOffsetDp)
        assertEquals(400, GameOpeningIntroExitMs)
        // The old blind 2.5 s grace is gone: the intro waits for the game to take the screen, then holds 0.2 s more
        // and the zoom keeps the pace the 3 s + 2.5 s + 0.4 s concept gave it.
        assertEquals(200L, GameOpeningIntroYieldHoldMs)
        assertEquals(5_900, GameOpeningIntroZoomMs)
        assertEquals(
            (GameOpeningIntroHoldMs + 2_500L + GameOpeningIntroExitMs).toInt(),
            GameOpeningIntroZoomMs,
        )
    }

    @Test
    fun theCapIsTheWatchdogsWholeWindowSoTheWatchdogSpeaksFirst() {
        // 3 s to a verdict plus 3 s to reap and settle. The watchdog's verdict lands before the cap whenever it can
        // land at all, so a launch that leaves Mimir resumed is faded at the verdict, not at the cap.
        assertEquals(GameDispatchPrimaryGraceMs + GameDispatchRecoveryDeadlineMs, GameOpeningIntroHandoffCapMs)
        assertEquals(6_000L, GameOpeningIntroHandoffCapMs)
        assertTrue(GameOpeningIntroHandoffCapMs > GameDispatchPrimaryGraceMs)
    }

    @Test
    fun presentationDismissalRequiresAnActiveNonLaunchingPlan() {
        assertFalse(gameOpeningPresentationShouldDismiss(null, launching = false))
        assertFalse(gameOpeningPresentationShouldDismiss("opening-game", launching = true))
        assertTrue(gameOpeningPresentationShouldDismiss("opening-game", launching = false))
    }

    @Test
    fun launchTransitionIsAllowedOnlyWithoutSecondaryDisplay() {
        assertTrue(gameOpeningLaunchTransitionAllowed(null))
        assertFalse(gameOpeningLaunchTransitionAllowed(4))
    }

    @Test
    fun skipReasonsPinTheLaunchGates() {
        assertEquals("disabled", gameOpeningIntroSkipReason(game(), MimirGamePlacement.MAIN, enabled = false))
        assertEquals("bottom-placement", gameOpeningIntroSkipReason(game(), MimirGamePlacement.BOTTOM, enabled = true))
        assertEquals("no-hero", gameOpeningIntroSkipReason(game(hero = null), MimirGamePlacement.MAIN, enabled = true))
        assertEquals(
            "secondary-display",
            gameOpeningIntroSkipReason(game(), MimirGamePlacement.MAIN, enabled = true, secondaryDisplayId = 4),
        )
    }

    @Test
    fun `intro uses the resolved hero when the row's own column is empty`() {
        val row = game(hero = null)

        val plan = gameOpeningIntroPlan(
            row.copy(heroArtUri = "file:///enrich/hero.img"),
            MimirGamePlacement.MAIN,
            enabled = true,
        )

        assertEquals("file:///enrich/hero.img", plan?.heroUri)
    }

    @Test
    fun `skip reason follows the resolved row`() {
        val row = game(hero = null)

        assertEquals(
            "no-hero",
            gameOpeningIntroSkipReason(row, MimirGamePlacement.MAIN, enabled = true),
        )
        assertNull(
            gameOpeningIntroSkipReason(
                row.copy(heroArtUri = "file:///enrich/hero.img"),
                MimirGamePlacement.MAIN,
                enabled = true,
            ),
        )
    }

    private fun game(
        hero: String? = "/local/game-hero.png",
        logo: String? = "/local/game-logo.png",
        previewVideoUri: String? = "/local/game.mp4",
        launchAudioUri: String? = null,
    ) = GameRow(
        gameId = GameId("opening-game"),
        title = "Opening Game",
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
        heroArtUri = hero,
        flatArtUri = logo,
        previewVideoUri = previewVideoUri,
        launchAudioUri = launchAudioUri,
    )
}
