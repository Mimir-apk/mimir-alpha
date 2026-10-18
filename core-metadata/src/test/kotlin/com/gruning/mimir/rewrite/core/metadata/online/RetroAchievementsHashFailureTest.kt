package com.gruning.mimir.rewrite.core.metadata.online

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lookup that could not read the file, or could not run the disc reader, has learned nothing about the file.
 * It must never become RaLookup.Miss: the app renders that as "NO SET" and caches it for the whole process.
 */
class RetroAchievementsHashFailureTest {

    private val auth = RetroAchievementsApi.Auth("player", "key")

    private fun source(
        calls: MutableList<String>,
        nativeReaderAvailable: Boolean = true,
    ) = RetroAchievementsSource(
        gateOpen = { true },
        auth = { auth },
        httpGet = { url ->
            calls += url
            RaHttpResult.Body(
                if ("API_GetConsoleIDs" in url) {
                    """[{"ID":1,"Name":"Mega Drive"},{"ID":12,"Name":"PlayStation"}]"""
                } else {
                    "[]"
                },
            )
        },
        hashLibraryGet = { RaHashLibraryResult.Unavailable },
        nativeReaderAvailable = { nativeReaderAvailable },
    )

    @Test
    fun `a read that throws is file-unavailable, never a cacheable miss`() = runBlocking {
        val calls = mutableListOf<String>()
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("card removed")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("card removed")
        }

        val result = source(calls).lookup("genesis", "sonic.md", 2048L) { failing }

        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.FILE_UNAVAILABLE), result)
        assertTrue("no index search follows a failed read", calls.none { "API_GetGameList" in it })
    }

    @Test
    fun `a disc file without the native reader is identification-unavailable before any network`() = runBlocking {
        val calls = mutableListOf<String>()

        val result = source(calls, nativeReaderAvailable = false).lookup(
            platformId = "ps1",
            fileName = "game.cue",
            sizeBytes = 4_000_000L,
            localPath = "/storage/emulated/0/ROMs/ps1/game.cue",
            openStream = { error("a seek reader must not open the streaming seam") },
        )

        assertEquals(RaLookup.Unavailable(RaNoAnswerReason.IDENTIFICATION_UNAVAILABLE), result)
        assertTrue(calls.isEmpty())
    }
}
