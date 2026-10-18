package com.gruning.mimir.rewrite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.GameId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android-decoder proof for the picker preview hydration boundary. */
@RunWith(AndroidJUnit4::class)
class CustomHeroPreviewHydrationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "hero-preview-html.scratch")
    private val output = File(context.cacheDir, "hero-preview-html.jpg")

    @After
    fun cleanUp() {
        scratch.delete()
        output.delete()
    }

    @Test
    fun htmlBodyIsNeverAcceptedAsAStaticPickerPreview() {
        scratch.writeText("<html><body>not an image</body></html>")

        assertFalse(CustomHeroFetcher.hydrateBoundedStaticPreview(scratch, output))
        assertFalse(output.exists())
    }

    @Test
    fun animatedMotionWithoutPosterReopensBoundedAndRemapsByItsRealMediaType() = runBlocking {
        val suffix = System.nanoTime().toString()
        val from = GameId("animated-remap-from-$suffix")
        val to = GameId("animated-remap-to-$suffix")
        val original = File(context.cacheDir, "animated-remap-$suffix.gif").apply {
            writeBytes(
                (
                    "47494638396101000100800000000000ffffff" +
                        "21ff0b4e45545343415045322e300301000000" +
                        "21f904000a0000002c0000000001000100000202440100" +
                        "21f904000a0000002c00000000010001000002024c0100" +
                        "3b"
                    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
        }
        var assetId: String? = null
        try {
            val decodedMovie = android.graphics.Movie.decodeFile(original.absolutePath)
            assertTrue(decodedMovie != null && decodedMovie.duration() >= 100)
            val saved = EnrichSavedStore.save(
                context = context,
                kind = EnrichMediaKind.CLIP,
                cacheKey = from.value,
                source = EnrichSavedSource.STEAMGRIDDB,
                label = "Animated remap fixture",
                fullAsset = original,
                previewAsset = null,
                allowMissingPreview = true,
                mediaType = CustomHeroMediaType.ANIMATED_IMAGE,
                activate = false,
            ) as EnrichSaveOutcome.Saved
            assetId = saved.asset.assetId

            assertTrue(saved.asset.previewPath.isBlank())
            val reopened = EnrichSavedStore.load(context, EnrichMediaKind.CLIP, from.value)
                .entries
                .single { it.assetId == saved.asset.assetId }
            assertTrue(reopened.previewPath.isBlank())
            assertNull(HomeCustomEnrichAsset.Saved(reopened).previewPath)

            assertTrue(
                EnrichSavedStore.activate(
                    context,
                    EnrichMediaKind.CLIP,
                    from.value,
                    saved.asset.assetId,
                ),
            )
            assertTrue(
                GameIdentityMediaMutationGate.run(context, blocked = false) {
                    CustomHeroStore.remapGameId(
                        context = context,
                        fromGameId = from,
                        toGameId = to,
                        clipTransition = GameIdentityMediaSelection.SELECTED,
                    )
                },
            )
            val remapped = CustomHeroStore.assetFile(context, to, video = true)
            assertTrue(remapped.isFile)
            assertEquals(
                CustomHeroMediaType.ANIMATED_IMAGE,
                CustomHeroFetcher.detectSavedMediaType(remapped),
            )
        } finally {
            CustomHeroStore.clearPreviewClipAsAutomatic(context, from)
            CustomHeroStore.clearPreviewClipAsAutomatic(context, to)
            assetId?.let {
                EnrichSavedStore.activateAutomatic(context, EnrichMediaKind.CLIP, from.value)
                EnrichSavedStore.remove(context, EnrichMediaKind.CLIP, from.value, it)
            }
            original.delete()
        }
    }
}
