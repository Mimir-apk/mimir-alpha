package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeExternalSessionElapsedStoreTest {
    private val bootCount = 7
    private val game = HomeShelfItemId.Emulated(GameId("game-1"))
    private val androidGame = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game"))
    private val androidApp = HomeShelfItemId.AndroidApp(AndroidPackageName("com.example.app"))

    @Test
    fun `document round trips totals active token and all typed identities`() {
        val document = HomeExternalSessionElapsedDocument(
            elapsedTotals = linkedMapOf(game to 10L, androidGame to 20L, androidApp to 30L),
            active = HomeExternalSession(androidGame, "token", 400L, bootCount),
        )

        assertEquals(
            document,
            parseHomeExternalSessionElapsedDocument(
                encodeHomeExternalSessionElapsedDocument(document),
            ),
        )
    }

    @Test
    fun `legacy v1 preserves totals and drops an active anchor without a boot discriminator`() {
        val legacy = """
            {
              "schemaVersion": 1,
              "totals": [
                {"kind":"EMULATED_GAME","id":"game-1","totalMillis":9001}
              ],
              "active": {
                "kind":"EMULATED_GAME",
                "id":"game-1",
                "returnToken":"legacy-token",
                "startedAtElapsedMillis":1234
              }
            }
        """.trimIndent()

        assertEquals(
            HomeExternalSessionElapsedDocument(
                elapsedTotals = mapOf(game to 9_001L),
                active = null,
            ),
            parseHomeExternalSessionElapsedDocument(legacy),
        )
    }

    @Test
    fun `first successful dispatch establishes typed zero and active session`() {
        val begun = beginHomeExternalSession(
            HomeExternalSessionElapsedDocument(),
            game,
            "token",
            1_000L,
            bootCount,
        )

        assertEquals(0L, begun.elapsedTotals[game])
        assertEquals(HomeExternalSession(game, "token", 1_000L, bootCount), begun.active)
    }

    @Test
    fun `missing boot discriminator retains typed zero without arming unsafe elapsed time`() {
        val begun = beginHomeExternalSession(
            HomeExternalSessionElapsedDocument(),
            game,
            "token",
            1_000L,
            null,
        )

        assertEquals(0L, begun.elapsedTotals[game])
        assertNull(begun.active)
    }

    @Test
    fun `threshold return clears active and retains typed zero`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 0L),
            active = HomeExternalSession(game, "token", 1_000L, bootCount),
        )

        val settled = finalizeHomeExternalSession(armed, "token", 4_000L, bootCount)

        assertNull(settled.active)
        assertEquals(0L, settled.elapsedTotals[game])
    }

    @Test
    fun `matching external session adds elapsed only when greater than threshold`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 0L),
            active = HomeExternalSession(game, "token", 1_000L, bootCount),
        )

        val settled = finalizeHomeExternalSession(armed, "token", 4_001L, bootCount)

        assertNull(settled.active)
        assertEquals(3_001L, settled.elapsedTotals[game])
        assertEquals(3_001L, settled.lastSessionElapsed[game])
    }

    @Test
    fun `invalid matching return clears anchor without replacing last session`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 8L),
            lastSessionElapsed = mapOf(game to 99L),
            active = HomeExternalSession(game, "token", 1_000L, bootCount),
        )

        val settled = finalizeHomeExternalSession(armed, "token", 4_000L, bootCount)

        assertNull(settled.active)
        assertEquals(99L, settled.lastSessionElapsed[game])
    }

    @Test
    fun `legacy document without optional last session map remains unavailable`() {
        val parsed = parseHomeExternalSessionElapsedDocument(
            """{"schemaVersion":1,"totals":[{"kind":"EMULATED_GAME","id":"game-1","totalMillis":4}]}""",
        )

        assertTrue(parsed.lastSessionElapsed.isEmpty())
    }

    @Test
    fun `id remap clears ambiguous last session collision while merging totals`() {
        val remapped = remapHomeExternalSessionGameId(
            HomeExternalSessionElapsedDocument(
                elapsedTotals = mapOf(game to 10L, HomeShelfItemId.Emulated(GameId("game-2")) to 20L),
                lastSessionElapsed = mapOf(game to 3L, HomeShelfItemId.Emulated(GameId("game-2")) to 4L),
            ),
            fromGameId = GameId("game-1"),
            toGameId = GameId("game-2"),
        )

        assertEquals(30L, remapped.elapsedTotals[HomeShelfItemId.Emulated(GameId("game-2"))])
        assertNull(remapped.lastSessionElapsed[HomeShelfItemId.Emulated(GameId("game-2"))])
    }

    @Test
    fun `mismatched token cannot finish or measure another session`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 55L),
            active = HomeExternalSession(game, "right", 1_000L, bootCount),
        )

        assertSame(armed, finalizeHomeExternalSession(armed, "wrong", 10_000L, bootCount))
    }

    @Test
    fun `watchdog cancellation clears only the exact active token and preserves totals`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 55L, androidGame to 20L),
            active = HomeExternalSession(game, "right", 1_000L, bootCount),
        )

        assertSame(armed, cancelHomeExternalSession(armed, "wrong"))
        assertEquals(
            armed.copy(active = null),
            cancelHomeExternalSession(armed, "right"),
        )
    }

    @Test
    fun `elapsed addition saturates instead of overflowing`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to Long.MAX_VALUE - 5L),
            active = HomeExternalSession(game, "token", 1_000L, bootCount),
        )

        assertEquals(
            Long.MAX_VALUE,
            finalizeHomeExternalSession(armed, "token", 5_000L, bootCount).elapsedTotals[game],
        )
    }

    @Test
    fun `negative elapsed fails closed and releases stale active session`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 100L),
            active = HomeExternalSession(game, "token", 50_000L, bootCount),
        )

        val settled = finalizeHomeExternalSession(armed, "token", 1_000L, bootCount)

        assertNull(settled.active)
        assertEquals(100L, settled.elapsedTotals[game])
    }

    @Test
    fun `positive uptime after a different boot cannot be measured`() {
        val armed = HomeExternalSessionElapsedDocument(
            elapsedTotals = mapOf(game to 100L),
            active = HomeExternalSession(game, "token", 1_000L, bootCount),
        )

        val settled = finalizeHomeExternalSession(
            document = armed,
            returnToken = "token",
            nowElapsedMillis = 5_000L,
            currentBootCount = bootCount + 1,
        )

        assertNull(settled.active)
        assertEquals(100L, settled.elapsedTotals[game])
    }

    @Test
    fun `corrupt and future documents fail closed without partial invented truth`() {
        assertEquals(
            HomeExternalSessionElapsedDocument(),
            parseHomeExternalSessionElapsedDocument("not json"),
        )
        assertEquals(
            HomeExternalSessionElapsedDocument(),
            parseHomeExternalSessionElapsedDocument("""{"schemaVersion":2}"""),
        )
        val partial = parseHomeExternalSessionElapsedDocument(
            """{"schemaVersion":1,"totals":[{"kind":"FUTURE","id":"x","totalMillis":3}]}""",
        )
        assertTrue(partial.elapsedTotals.isEmpty())
    }
}
