package com.gruning.mimir.rewrite.core.companionnative

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryTabsCodecTest {
    @Test
    fun `round trip preserves opened order and fields`() {
        val entries = listOf(
            AppEntry("org.example.first", "First", 4, 100L, "org.example.first.icons"),
            AppEntry("org.example.second", "Second", 5, 200L, "org.example.second.icons"),
        )
        assertEquals(entries, SecondaryTabsCodec.decode(SecondaryTabsCodec.encode(entries)))
    }

    @Test
    fun `null display is preserved`() {
        val entries = listOf(AppEntry("org.example.app", "App", null, 100L))
        assertEquals(entries, SecondaryTabsCodec.decode(SecondaryTabsCodec.encode(entries)))
    }

    @Test
    fun `garbage input is empty`() {
        listOf("", "garbage", "{}", "[", "[null, 12, {}, {\"package\":\"\"}]").forEach {
            assertTrue(SecondaryTabsCodec.decode(it).isEmpty())
        }
    }

    @Test
    fun `bad rows are skipped without losing valid neighbors`() {
        val entry = AppEntry("org.example.app", "App", 4, 100L)
        val row = SecondaryTabsCodec.encode(listOf(entry)).removePrefix("[").removeSuffix("]")
        assertEquals(listOf(entry), SecondaryTabsCodec.decode("[null,{},$row,42]"))
    }

    @Test
    fun `a game tab survives a restart with its kind and identity`() {
        val entries = listOf(
            AppEntry(
                packageName = "org.example.emulator",
                label = "Emulator",
                displayId = 4,
                lastSeenAtMs = 100L,
                kind = SecondaryTaskKind.GAME,
                gameTitle = "Chrono Trigger",
                gameId = "game-7",
                gameArtRef = "content://art/7",
            ),
        )
        assertEquals(entries, SecondaryTabsCodec.decode(SecondaryTabsCodec.encode(entries)))
    }

    /** A tab persisted before the kind existed was, by definition, an app. */
    @Test
    fun `a row written without a kind decodes as an app`() {
        val legacy = """[{"package":"org.example.app","label":"App","displayId":4,""" +
            """"lastSeenAtMs":100,"iconPackageName":"org.example.app"}]"""
        assertEquals(
            listOf(AppEntry("org.example.app", "App", 4, 100L)),
            SecondaryTabsCodec.decode(legacy),
        )
    }

    @Test
    fun `decoded entries are OPEN without close operations`() {
        val closing = AppEntry(
            "org.example.app", "App", 4, 100L,
            closeState = SecondaryTaskCloseState.CLOSE_UNCONFIRMED,
            closeOperationId = UUID(0L, 1L),
            closeRequestedAtElapsedRealtimeMs = 200L,
        )
        assertEquals(
            listOf(AppEntry("org.example.app", "App", 4, 100L)),
            SecondaryTabsCodec.decode(SecondaryTabsCodec.encode(listOf(closing))),
        )
    }
}
