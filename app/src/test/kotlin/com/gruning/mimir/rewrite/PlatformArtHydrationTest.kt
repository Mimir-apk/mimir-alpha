package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformArtHydrationTest {
    @Test
    fun repositoryEmissionRefreshesEveryPlatformBeforeReleasingFallbackGate() = runBlocking {
        var hydrated = false
        val events = mutableListOf<String>()
        val rows = listOf(
            PlatformRow(PlatformId("snes"), "Super Nintendo", 5),
            PlatformRow(PlatformId("gba"), "Game Boy Advance", 7),
        )

        hydratePersistedPlatformArt(
            platforms = flow {
                assertFalse(hydrated)
                emit(rows)
            },
            refresh = { events += "refresh:${it.value}" },
            markHydrated = {
                hydrated = true
                events += "hydrated"
            },
        )

        assertTrue(hydrated)
        assertEquals(listOf("refresh:snes", "refresh:gba", "hydrated"), events)
    }

    @Test
    fun warmupReadsOverridesPublishedBeforeTheHydrationGate() = runBlocking {
        val hydrated = MutableStateFlow(false)
        val art = MutableStateFlow<Map<String, String>>(emptyMap())
        launch {
            delay(10L)
            art.value = mapOf("snes" to "file:///platform-snes.png")
            hydrated.value = true
        }

        assertEquals(
            mapOf("snes" to "file:///platform-snes.png"),
            awaitHydratedPlatformArt(hydrated, art, timeoutMs = 500L),
        )
    }

    @Test
    fun stalledRepositoryReleasesTheFallbackGateAtTheDeadline() = runBlocking {
        var hydrated = false
        val job = launch {
            hydratePersistedPlatformArt(
                platforms = flow { awaitCancellation() },
                refresh = {},
                markHydrated = { hydrated = true },
                initialDeadlineMs = 25L,
            )
        }

        withTimeout(500L) {
            while (!hydrated) delay(5L)
        }
        job.cancelAndJoin()

        assertTrue(hydrated)
    }
}
