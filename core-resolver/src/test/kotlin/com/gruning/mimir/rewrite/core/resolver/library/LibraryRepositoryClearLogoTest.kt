package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.model.library.PreviewVideoSource
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryRepositoryClearLogoTest {
    private val gameId = GameId("gba/metroid-fusion")
    private val managedPath = "/games/Metroid Fusion.gba"
    private val projection = GameRowProjection(
        gameId = gameId.value,
        title = "Metroid Fusion",
        platformId = PlatformId.GBA.value,
        duplicateGroupKind = null,
        memberCount = 1,
        managedPath = managedPath,
    )

    @Test
    fun `cover logo and hero receive the same refresh version`() = runBlocking {
        val artSource = RecordingArtSource()
        val repository = repository(artSource)

        val initial = repository.observeGames(PlatformId.GBA).first().single()
        assertEquals("file:///games/Metroid%20Fusion.png", initial.artUri)
        assertEquals("content://art/Metroid%20Fusion-logo.webp", initial.flatArtUri)
        assertEquals("content://art/Metroid%20Fusion-hero.webp", initial.heroArtUri)

        repository.refreshArt(gameId)
        val refreshed = repository.observeGames(PlatformId.GBA).first().single()

        assertEquals("file:///games/Metroid%20Fusion.png#mv=1", refreshed.artUri)
        assertEquals("content://art/Metroid%20Fusion-logo.webp#mv=1", refreshed.flatArtUri)
        assertEquals("content://art/Metroid%20Fusion-hero.webp#mv=1", refreshed.heroArtUri)
        assertEquals(listOf(null to managedPath), artSource.invalidations)
    }

    @Test
    fun `non-local render URIs are not exposed by the read model`() = runBlocking {
        val remoteArt = object : ArtSource {
            override fun artUriFor(sourceDocumentUri: String?, managedPath: String?) =
                "https://example.invalid/cover.png"

            override fun logoUriFor(sourceDocumentUri: String?, managedPath: String?) =
                "https://example.invalid/logo.png"

            override fun heroUriFor(sourceDocumentUri: String?, managedPath: String?) =
                "https://example.invalid/hero.png"
        }

        val row = repository(remoteArt).observeGames(PlatformId.GBA).first().single()

        assertNull(row.artUri)
        assertNull(row.flatArtUri)
        assertNull(row.heroArtUri)
    }

    // ── the launch sound reaches a GameRow only as launchAudioUri, only as a local URI ─────────────

    @Test
    fun `a saved sound surfaces as launchAudioUri and leaves previewVideoUri null`() = runBlocking {
        val sound = object : ArtSource {
            override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

            override fun audioUriFor(sourceDocumentUri: String?, managedPath: String?) =
                "file:///games/media_art/Metroid%20Fusion-audio.m4a"
        }

        val row = repository(sound).observeGames(PlatformId.GBA).first().single()

        assertEquals("file:///games/media_art/Metroid%20Fusion-audio.m4a", row.launchAudioUri)
        assertNull("the sound is not a clip; the moving backdrop stays retired", row.previewVideoUri)
    }

    @Test
    fun `the video source never feeds launchAudioUri and the audio source never feeds previewVideoUri`() = runBlocking {
        val noSound = ArtSource { _, _ -> null }
        val clipOnly = PreviewVideoSource { _, _ -> "file:///games/media_art/Metroid%20Fusion.mp4" }

        val row = repository(noSound, previewVideoSource = clipOnly).observeGames(PlatformId.GBA).first().single()

        assertNull(row.launchAudioUri)
        assertEquals("file:///games/media_art/Metroid%20Fusion.mp4", row.previewVideoUri)
    }

    /**
     * **URL safety.** ScreenScraper media URLs carry credentials. A provider URL can never become a row's sound: the
     * projection keeps only `file://` and `content://`, so the intro's log line and MediaPlayer never see one.
     */
    @Test
    fun `a provider URL is never exposed as launchAudioUri`() = runBlocking {
        listOf(
            "https://www.screenscraper.fr/medias/game-audio.m4a?devid=D&devpassword=P&ssid=U&sspassword=S",
            "http://example.invalid/game-audio.m4a",
            "ftp://example.invalid/game-audio.m4a",
            "/games/media_art/Metroid Fusion-audio.m4a",
        ).forEach { notLocal ->
            val remote = object : ArtSource {
                override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

                override fun audioUriFor(sourceDocumentUri: String?, managedPath: String?) = notLocal
            }

            val row = repository(remote).observeGames(PlatformId.GBA).first().single()

            assertNull("$notLocal must not reach the render model", row.launchAudioUri)
        }
    }

    @Test
    fun `the library's own SAF document is accepted and a refresh adds no version fragment to the sound`() = runBlocking {
        val soundUri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/opaque-sound-05"
        val saf = object : ArtSource {
            override fun artUriFor(sourceDocumentUri: String?, managedPath: String?): String? = null

            override fun audioUriFor(sourceDocumentUri: String?, managedPath: String?) = soundUri
        }
        val repository = repository(saf)
        assertEquals(soundUri, repository.observeGames(PlatformId.GBA).first().single().launchAudioUri)

        repository.refreshArt(gameId)

        assertEquals(
            "MediaPlayer gets the plain document URI; nothing decodes it through Coil, so there is no key to bust",
            soundUri,
            repository.observeGames(PlatformId.GBA).first().single().launchAudioUri,
        )
    }

    private fun repository(
        artSource: ArtSource,
        previewVideoSource: PreviewVideoSource = PreviewVideoSource { _, _ -> null },
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> flowOf(listOf(projection))
                "membersOfGame" -> listOf(member())
                else -> error("Unexpected GameDao call: $methodName")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            artSource = artSource,
            previewVideoSource = previewVideoSource,
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun member() = GameEntity(
        entryKey = "root::Metroid Fusion.gba",
        gameId = gameId.value,
        platformId = PlatformId.GBA.value,
        contentKind = "CARTRIDGE_ROM",
        displayName = "Metroid Fusion.gba",
        title = "Metroid Fusion",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = 1L,
        contentHash = null,
        sourceRootUri = "/games",
        sourceDocumentUri = null,
        managedPath = managedPath,
        relativePath = "Metroid Fusion.gba",
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private class RecordingArtSource : ArtSource {
        val invalidations = mutableListOf<Pair<String?, String?>>()

        override fun artUriFor(sourceDocumentUri: String?, managedPath: String?) =
            "file:///games/Metroid%20Fusion.png"

        override fun logoUriFor(sourceDocumentUri: String?, managedPath: String?) =
            "content://art/Metroid%20Fusion-logo.webp"

        override fun heroUriFor(sourceDocumentUri: String?, managedPath: String?) =
            "content://art/Metroid%20Fusion-hero.webp"

        override fun invalidate(sourceDocumentUri: String?, managedPath: String?) {
            invalidations += sourceDocumentUri to managedPath
        }
    }

    private inline fun <reified T> unusedProxy(): T = proxy { methodName ->
        error("Unexpected ${T::class.java.simpleName} call: $methodName")
    }

    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
