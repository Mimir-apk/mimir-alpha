package com.gruning.mimir.rewrite

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression coverage for an animated-clip preview:
 * an ANIMATED_IMAGE clip is stored as `.webp` and can serve as its own preview, but the `previewTarget`
 * selection in `EnrichSavedStore.saveLocked` previously fell through to `null` for it, saving an empty
 * `previewPath` on the record. A valid poster asset must still take precedence over the fallback.
 *
 * The selection logic lives in `saveLocked`, a private function. Its only callers (`save`,
 * `migrateLegacy`) first resolve a Room-backed identity alias via `GameIdentityAliasResolver`, which no
 * unit test in this module stands up. This pins `saveLocked` directly via reflection under Robolectric
 * (the same harness `AppBrightnessCoordinatorProfileDraftTest` uses for Context-requiring tests) rather
 * than bringing up a database for what is otherwise a pure preview-path regression.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class EnrichSavedStoreAnimatedClipPreviewTest {

    @get:Rule val folder = TemporaryFolder()

    private fun asset(name: String): File =
        folder.newFile(name).apply { writeBytes(ByteArray(32) { it.toByte() }) }

    /** Invokes the private `saveLocked(...)` directly; see class doc for why. */
    private fun saveClip(
        cacheKey: String,
        fullAsset: File,
        previewAsset: File?,
        allowMissingPreview: Boolean,
        mediaType: CustomHeroMediaType?,
    ): EnrichSaveOutcome {
        val method = EnrichSavedStore::class.java.getDeclaredMethod(
            "saveLocked",
            Context::class.java,
            EnrichMediaKind::class.java,
            String::class.java,
            EnrichSavedSource::class.java,
            String::class.java,
            File::class.java,
            File::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            java.lang.Long::class.java,
            CustomHeroMediaType::class.java,
            String::class.java,
            // providerMediaType, added 2026-08-25 so apply can record WHICH kind of face was chosen.
            // This reflective call is signature-coupled by construction -- it is why the test failed
            // loudly with NoSuchMethodException rather than silently invoking a stale overload.
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            EnrichSavedStore,
            RuntimeEnvironment.getApplication(),
            EnrichMediaKind.CLIP,
            cacheKey,
            EnrichSavedSource.LEGACY,
            "Existing selection",
            fullAsset,
            previewAsset,
            allowMissingPreview,
            0,
            0,
            null,
            mediaType,
            null,
            // providerMediaType: a CLIP has no face kind to record.
            null,
            true,
            1_000L,
            1_000L,
        ) as EnrichSaveOutcome
    }

    @Test
    fun `animated image clip with no poster serves its own full asset as the preview`() {
        val outcome = saveClip(
            cacheKey = "game-animated-no-poster",
            fullAsset = asset("clip-a.webp"),
            previewAsset = null,
            allowMissingPreview = true,
            mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
        )

        val saved = outcome as EnrichSaveOutcome.Saved
        assertTrue("previewPath must not be empty", saved.asset.previewPath.isNotEmpty())
        assertEquals(saved.asset.localPath, saved.asset.previewPath)
    }

    @Test
    fun `animated image clip with a valid poster still prefers the poster`() {
        val outcome = saveClip(
            cacheKey = "game-animated-with-poster",
            fullAsset = asset("clip-b.webp"),
            previewAsset = asset("clip-b-poster-source.jpg"),
            allowMissingPreview = false,
            mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
        )

        val saved = outcome as EnrichSaveOutcome.Saved
        assertTrue(
            "poster must still win: was ${saved.asset.previewPath}",
            saved.asset.previewPath.endsWith(".poster.jpg"),
        )
        assertNotEquals(saved.asset.localPath, saved.asset.previewPath)
    }

    @Test
    fun `non animated clip with no poster still saves an empty preview path`() {
        val outcome = saveClip(
            cacheKey = "game-video-no-poster",
            fullAsset = asset("clip-c.mp4"),
            previewAsset = null,
            allowMissingPreview = true,
            mediaType = CustomHeroMediaType.VIDEO,
        )

        val saved = outcome as EnrichSaveOutcome.Saved
        assertEquals("", saved.asset.previewPath)
    }
}
