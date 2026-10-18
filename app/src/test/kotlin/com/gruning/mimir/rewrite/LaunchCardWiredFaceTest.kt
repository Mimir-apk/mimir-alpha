package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [launchCardWiredFace] — the LAUNCH CARD state's law for a `GameRow` caller.
 *
 * It replaced `launchCardCartridgeArtUri` on the Library destination's card, which had three defects that
 * this file pins one test each, because each was independently able to put the wrong picture on screen:
 *
 *  1. **gated on Cartridge Hero** — an OFF switch vetoed a cartridge he had picked BY HAND, which is what
 *     C4 forbids and what the Home-side card is *"deliberately NOT gated"* on;
 *  2. **state-blind** — it read the store at the BARE cache key, so a `LAUNCH_CARD` pick, which lives at
 *     `mediaStorageKey(cacheKey, LAUNCH_CARD)`, could not be seen at all;
 *  3. **kind-blind** — it returned a bare `String?`, so `libraryLaunchCardUiState` defaulted
 *     `cartridgeArtIsPhysical = true` and a COVER arriving by the inherit tier was painted at the disc's
 *     square aspect. That parameter's own KDoc predicted this caller.
 *
 * These are JVM tests over real stores; what they cannot prove is the render. `AC-2`'s device walk is that.
 */
@RunWith(RobolectricTestRunner::class)
// Stub Application, this module's convention: the real MimirApplication boots WorkManager in
// onCreate and Robolectric has no initializer for it. These tests need a Context and stores,
// not the app's startup graph.
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LaunchCardWiredFaceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val key = "title:gamecube:resident evil usa"

    private fun writePng(target: File) {
        target.parentFile?.mkdirs()
        // A 1x1 PNG: the stores stat and header-decode, so the bytes must be a real image.
        target.writeBytes(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
                0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
                0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
                0x0D, 0x0A, 0x2D, 0xB4.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
            ),
        )
    }

    /**
     * Defect 1 + 2 together, and the pair is the point: a launch-card pick, with the switch OFF.
     *
     * The old resolver failed this twice over — it returned null on `!cartridgeArtEnabled` before reading
     * anything, and even with the switch ON it would have looked under the bare key and missed the file.
     */
    @Test
    fun `a launch-card pick wins with the switch OFF, which the old resolver could not even see`() {
        val storageKey = mediaStorageKey(key, MediaState.LAUNCH_CARD)
        writePng(TomeCartridgeArtStore.activeFile(context, storageKey))
        WiredFaceStore.record(context, key, MediaState.LAUNCH_CARD, WiredFaceKind.PHYSICAL)

        val face = launchCardWiredFace(
            context = context,
            cacheKey = key,
            providerPhysicalMediaUri = null,
            shelfArtworkUri = "file:///cover.png",
        )
        assertNotNull("his launch-card pick must survive an OFF switch — C4", face)
        assertTrue("and it must be reported as physical", face!!.physical)
        assertTrue(
            "the uri must come from the LAUNCH_CARD key, not the bare one",
            face.uri.contains(tomeCartridgeArtStorageName(storageKey).removeSuffix(".png")),
        )
    }

    /** L2: the article is the card's own default, in either switch position. */
    @Test
    fun `the article is not gated on the switch`() {
        listOf(true, false).forEach { enabled ->
            val face = launchCardWiredFace(
                context = context,
                cacheKey = "$key-article-$enabled",
                providerPhysicalMediaUri = "file:///disc.png",
                shelfArtworkUri = "file:///cover.png",
            )
            assertEquals(
                "the card's own default is the article; the switch rules the SHELVES (enabled=$enabled)",
                "file:///disc.png",
                face?.uri,
            )
            assertTrue("an article is physical", face?.physical == true)
        }
    }

    /**
     * Defect 3. With no article anywhere, L3 inherits the Shelf's resolved face — a COVER — and the kind
     * must travel with it. The old seam returned a bare uri and the card assumed `physical = true`, which
     * paints a tall box at the disc's square aspect.
     */
    @Test
    fun `an inherited cover is reported as a cover, not squared off as a disc`() {
        val face = launchCardWiredFace(
            context = context,
            cacheKey = "$key-cover-only",
            providerPhysicalMediaUri = null,
            shelfArtworkUri = "file:///cover.png",
        )
        assertEquals("file:///cover.png", face?.uri)
        assertFalse(
            "a cover reported as physical is the squared-off box defect its own KDoc predicted",
            face?.physical ?: true,
        )
    }

    /** No pick, no article, no shelf art: no face. Absence stays absence (FL-5). */
    @Test
    fun `nothing to show returns null rather than an empty face`() {
        assertNull(
            launchCardWiredFace(
                context = context,
                cacheKey = "$key-empty",
                providerPhysicalMediaUri = null,
                shelfArtworkUri = null,
            ),
        )
    }

    /** A blank cache key is not a lookup. */
    @Test
    fun `a blank cache key resolves to nothing`() {
        assertNull(
            launchCardWiredFace(
                context = context,
                cacheKey = "  ".trim(),
                providerPhysicalMediaUri = "file:///disc.png",
                shelfArtworkUri = null,
            ),
        )
    }
}
