package com.gruning.mimir.rewrite.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion payload crosses the primary↔companion display boundary (today a JSON string over the
 * bridge). It uses a stable typed wire format via kotlinx-serialization.
 * These tests lock the round-trip so the wire contract can't silently drift.
 */
class CompanionPayloadSerializationTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `now-playing payload round-trips`() {
        val payload = CompanionPayload(
            mode = CompanionMode.NOW_PLAYING,
            gameId = GameId("group:ps1:final-fantasy-vii"),
            title = "Final Fantasy VII",
            platformId = PlatformId.PS1,
            heroArtRef = "media_art/ps1/ff7.png",
            heroArtIsLogo = true,
            themeSigilRef = "sigil/yggdrasil.png",
            isLoading = false,
        )
        val decoded = json.decodeFromString(CompanionPayload.serializer(), json.encodeToString(CompanionPayload.serializer(), payload))
        assertEquals(payload, decoded)
    }

    @Test
    fun `idle payload round-trips and omits null fields`() {
        val payload = CompanionPayload.idle(themeSigilRef = "sigil/hel.png")
        val wire = json.encodeToString(CompanionPayload.serializer(), payload)
        // encodeDefaults=false → the wire stays compact (no null gameId/title spam).
        assertTrue("idle wire should not carry a gameId", !wire.contains("gameId"))
        assertEquals(payload, json.decodeFromString(CompanionPayload.serializer(), wire))
    }

    @Test
    fun `value-class ids serialize as their raw string (stable wire)`() {
        val wire = json.encodeToString(
            CompanionPayload.serializer(),
            CompanionPayload(mode = CompanionMode.HOME, platformId = PlatformId.GBA),
        )
        // PlatformId is an @Serializable @JvmInline value class → encodes as the bare "gba", not an object.
        assertTrue("platformId should be the raw string", wire.contains("\"gba\""))
    }

    /**
     * **Renamed 2026-08-30 (policy D1-R3).** This was called
     * `all companion modes remain display-only wire values`, which asserted the display-only doctrine
     * that `CompanionPayload`'s KDoc also asserted and that was false before it was stale. **The body
     * never checked it** — it checks a round-trip and the absence of a `gameDetail` field, and it did
     * that correctly the whole time. Only the name was making a claim.
     *
     * That is the third instance of one defect class found in this file family (with §6c's false
     * performance comment and `CompanionPayloadAdapter`'s VERBATIM claim), which is why the rename is
     * a correction to the test's claim. **The body is deliberately unchanged.**
     */
    @Test
    fun `every mode round-trips and no mode leaks a gameDetail field onto the wire`() {
        CompanionMode.entries.forEach { mode ->
            val payload = CompanionPayload(mode = mode)
            val wire = json.encodeToString(CompanionPayload.serializer(), payload)

            assertTrue(!wire.contains("GAME_DETAIL"))
            assertTrue(!wire.contains("gameDetail"))
            assertEquals(payload, json.decodeFromString(CompanionPayload.serializer(), wire))
        }
    }

    @Test
    fun `home payload carries shelf rows (legacy field) on the wire`() {
        val payload = CompanionPayload(
            mode = CompanionMode.HOME,
            themeId = "void",
            statsLabel = "17 platforms · 100 games",
            shelfRows = listOf(
                CompanionShelfRow(
                    id = "arcade",
                    code = "ARC",
                    name = "Arcade",
                    countLabel = "1 game",
                    detailLines = listOf("ARCADE"),
                ),
            ),
            selectedShelfId = "arcade",
        )
        val decoded = json.decodeFromString(
            CompanionPayload.serializer(),
            json.encodeToString(CompanionPayload.serializer(), payload),
        )
        assertEquals(payload, decoded)
        assertEquals("arcade", decoded.shelfRows.single().id)
    }

    @Test
    fun `an older payload without homeShelfDetail decodes with a null detail (FUX-E back-compat)`() {
        val decoded = json.decodeFromString(CompanionPayload.serializer(), """{"mode":"IDLE"}""")
        assertEquals(CompanionMode.IDLE, decoded.mode)
        assertNull(decoded.homeShelfDetail)
    }

}
