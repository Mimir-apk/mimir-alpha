package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hero **motion** in the gamelist, 2026-08-14: wired
 * in the library gamelist as well as on Home.
 *
 * **This completes Bifröst rather than introducing a mode.** [GamelistBackdropMode.BIFROST_BACKDROP]'s own
 * KDoc has always described it as *"the see-through preview VIDEO veiled by the theme gradient"*, and
 * [VideoBackdrop]'s defaults are literally the gamelist's ("every existing (gamelist) caller stays
 * byte-identical"). The gamelist has been drawing the still half of a mode specified with video in it.
 *
 * **What these tests pin:** the eligibility gate — which sources may reach a `MediaPlayer`, and under which
 * backdrop modes. **What they cannot pin:** that a clip renders, plays, loops, settles, or falls back to the
 * still when a decoder refuses it. `feature-library` has no Compose harness, and video playback is a device
 * fact. That half needs device observation.
 */
class GamelistHeroMotionGateTest {

    private val clip = "file:///storage/emulated/0/Mimir/heroes/sonic.mp4"

    @Test
    fun `a local clip plays when Bifrost is on`() {
        assertEquals(clip, libraryHeroMotionUri(GamelistBackdropMode.BIFROST, clip))
        assertEquals(clip, libraryHeroMotionUri(GamelistBackdropMode.BIFROST_BACKDROP, clip))
    }

    @Test
    fun `a backdrop the player switched off shows no clip`() {
        assertNull(
            "OFF draws no hero at all, so it must draw no motion either -- a video playing behind a " +
                "backdrop the player turned off is the loudest possible way to ignore a setting",
            libraryHeroMotionUri(GamelistBackdropMode.OFF, clip),
        )
    }

    @Test
    fun `AURORA shows no clip either`() {
        assertNull(
            "AURORA is the blurred cover wash and its own KDoc says 'no video' in as many words; it is a " +
                "different engine, not a dimmer Bifrost",
            libraryHeroMotionUri(GamelistBackdropMode.AURORA, clip),
        )
    }

    @Test
    fun `a remote source is refused, because a clip would keep reading it`() {
        listOf(
            "https://cdn.example.com/sonic.mp4",
            "http://cdn.example.com/sonic.mp4",
            "rtsp://example.com/sonic",
        ).forEach { remote ->
            assertNull(
                "NO NETWORK: a remote scheme reaching MediaPlayer would be a network read on a surface " +
                    "this programme forbids one on -- and unlike a still it would keep reading for as " +
                    "long as the clip loops. Refused: $remote",
                libraryHeroMotionUri(GamelistBackdropMode.BIFROST, remote),
            )
        }
    }

    @Test
    fun `blank and absent sources are simply no motion`() {
        assertNull(libraryHeroMotionUri(GamelistBackdropMode.BIFROST, null))
        assertNull(libraryHeroMotionUri(GamelistBackdropMode.BIFROST, "   "))
    }

    /**
     * The still and the clip must answer the same question the same way. If they ever drift, a game's hero
     * and its motion obey different rules — visible only on device, and only for whichever games happen to
     * sit on the wrong side of the difference.
     */
    /**
     * The CALL SITE, not the gate.
     *
     * Every test above passes with `LibraryScreen` never composing a `VideoBackdrop` at all — the gate is a
     * pure function and cannot tell whether anyone asks it. That is the hole this programme has now hit
     * four times in three PRs, most expensively when three unwired lines would have shipped a whole feature
     * dead with a green suite. `feature-library` has no Compose harness, so the wiring gets a source pin.
     *
     * **Proves the call exists. Proves nothing about playback** — not that a frame appears, not that it
     * loops, not that a refused decoder falls back to the still.
     */
    @Test
    fun `the gamelist actually composes a VideoBackdrop for hero motion`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt")
            .takeIf { it.isFile }
            ?: java.io.File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt")
        val text = source.readText()

        val resolved = text.indexOf("libraryHeroMotionUri(")
        org.junit.Assert.assertTrue(
            "LibraryScreen must resolve the motion candidate through libraryHeroMotionUri -- without it " +
                "the gate above is pinned over a function nothing calls",
            resolved >= 0,
        )
        val composed = text.indexOf("VideoBackdrop(", resolved)
        org.junit.Assert.assertTrue(
            "...and it must then COMPOSE a VideoBackdrop. Resolving the clip and rendering the still " +
                "anyway is exactly what the gamelist did before this change, and every test in this file " +
                "would still be green.",
            composed > resolved,
        )
        val artUri = text.substring(composed, minOf(composed + 400, text.length))
        org.junit.Assert.assertTrue(
            "SAFETY: the gamelist must pass the still as `artUri`, unlike Home which bans a static cover " +
                "under its video. Battery saver skips the decode outright and a refused decoder renders " +
                "nothing, so artUri = null here would leave the player staring at a bare gradient where " +
                "their art used to be. Call was:\n$artUri",
            artUri.contains("artUri = backdropHero"),
        )
    }

    @Test
    fun `the motion gate and the still gate agree on every source`() {
        val sources = listOf(
            clip,
            "content://media/external/video/media/42",
            "android.resource://com.gruning.mimir.rewrite/raw/demo",
            "https://cdn.example.com/sonic.mp4",
            "ftp://example.com/sonic.mp4",
            "  ",
            null,
        )
        GamelistBackdropMode.entries.forEach { mode ->
            sources.forEach { source ->
                assertEquals(
                    "still and motion must agree for mode=$mode source=$source -- they share " +
                        "libraryLocalBackdropUri precisely so a scheme added to one cannot be silently " +
                        "refused by the other",
                    libraryHeroBackdropUri(mode, source) != null,
                    libraryHeroMotionUri(mode, source) != null,
                )
            }
        }
    }
}
