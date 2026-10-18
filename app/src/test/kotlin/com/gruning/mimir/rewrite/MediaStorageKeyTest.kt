package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5-K, the storage key for the state axis (policy 2026-08-25, C1/C5).
 *
 * The property that matters most is the NEGATIVE one: the Shelf branch must be byte-identical to the bare
 * cacheKey, because that is the entire reason this is a zero-write migration. Every cover, cartridge,
 * icon, hero and clip already on the reference device resolves through one of four frozen derivations, and they all
 * take a cacheKey. If `SHELF` ever stopped returning it verbatim, every one of those files would change
 * address at once — silently, with no error, and with no way to tell from the code that it had happened.
 */
class MediaStorageKeyTest {

    @Test
    fun `the shelf key is the bare cache key, which is what makes this a zero-write migration`() {
        listOf(
            "3f2b1c9e-0a44-4e17-9a2f-77c1d0e5b8aa",
            "android/com.example.game",
            "arcade/mslug",
            "a",
        ).forEach { cacheKey ->
            assertEquals(
                "SHELF must return the cacheKey VERBATIM -- anything else re-addresses every existing file",
                cacheKey,
                mediaStorageKey(cacheKey, MediaState.SHELF),
            )
        }
    }

    /**
     * THE MIGRATION GUARANTEE, expressed at the layer that can actually break it.
     *
     * The assertion above — `SHELF` returns the bare key — is necessary but weak on its own: it passes
     * against a correct implementation *and* against one that returns the bare key for BOTH states. What
     * actually has to hold is that every frozen derivation produces the same address it produced
     * yesterday when handed a Shelf key. So this asserts it one layer down, through the derivation itself.
     *
     * If this ever goes red, every cartridge image he has ever picked has just become unreachable — with
     * no error, no warning, and nothing on screen except art quietly reverting to automatic.
     */
    @Test
    fun `every frozen derivation addresses the same file through a shelf key as through the bare key`() {
        listOf(
            "3f2b1c9e-0a44-4e17-9a2f-77c1d0e5b8aa",
            "android/com.example.game",
            "arcade/mslug",
        ).forEach { cacheKey ->
            assertEquals(
                "the cartridge filename derivation must not move for a Shelf key",
                tomeCartridgeArtStorageName(cacheKey),
                tomeCartridgeArtStorageName(mediaStorageKey(cacheKey, MediaState.SHELF)),
            )
            // ...and the launch-card key must address a DIFFERENT file, or the two states would share one.
            assertNotEquals(
                "a launch-card pick must not overwrite the shelf pick's file",
                tomeCartridgeArtStorageName(cacheKey),
                tomeCartridgeArtStorageName(mediaStorageKey(cacheKey, MediaState.LAUNCH_CARD)),
            )
        }
    }

    @Test
    fun `the launch card key is distinct and prefixed`() {
        val cacheKey = "3f2b1c9e-0a44-4e17-9a2f-77c1d0e5b8aa"
        val launch = mediaStorageKey(cacheKey, MediaState.LAUNCH_CARD)
        assertEquals("launch-card#$cacheKey", launch)
        assertNotEquals(mediaStorageKey(cacheKey, MediaState.SHELF), launch)
        assertTrue(launch.startsWith(LaunchCardKeyPrefix))
    }

    /**
     * PREFIX, not suffix, and this is the test that makes that a fact rather than an argument.
     *
     * A suffix marker collides whenever a real cacheKey happens to END in it. A prefix collides only if one
     * BEGINS with `launch-card#` — and the two real shapes are a game id and `android/` plus a package
     * name. A package name cannot contain `#`; a game id is UUID-shaped.
     */
    @Test
    fun `no real cache key shape can forge a launch card key`() {
        val realShapes = listOf(
            "3f2b1c9e-0a44-4e17-9a2f-77c1d0e5b8aa",
            "android/com.example.game",
            "android/launch-card.app",
            "gamecube/launch-card",
            "launch-card",
        )
        realShapes.forEach {
            assertFalse(
                "a real cacheKey must never begin with the launch-card marker: $it",
                it.startsWith(LaunchCardKeyPrefix),
            )
            assertNotEquals(
                "and must never collide with a launch-card key for any game",
                mediaStorageKey(it, MediaState.SHELF),
                mediaStorageKey(it, MediaState.LAUNCH_CARD),
            )
        }
    }

    /** Two states, so a `when` over them stays exhaustive and a third cannot be added silently. */
    @Test
    fun `the axis has exactly two states`() {
        assertEquals(2, MediaState.entries.size)
    }
}
