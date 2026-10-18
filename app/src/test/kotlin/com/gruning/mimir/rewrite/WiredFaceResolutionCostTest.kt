package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two performance changes on the wired-face read path, pinned by BEHAVIOUR rather than by timing.
 *
 * 1. `resolveWiredFace` asked each store twice per game — once through `hasOverride`, once for the URI.
 * 2. `TomeCartridgeArtStore.isValid` ran a `BitmapFactory` bounds decode on every call.
 *
 * Neither may change which file a game's face resolves to. That is the whole risk: this path is where a
 * hand-picked cover was silently lost on the Odin once already (see
 * [WiredFaceStoreRecoversMisfiledPickTest]), and a "harmless" speed-up that moved a lookup would lose it
 * again with no error. So these assert the resolved outcome across the state matrix, and that the decode
 * cache still SEES a file change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class WiredFaceResolutionCostTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A 1x1 PNG that BitmapFactory will report bounds for. */
    private fun writePng(target: File) {
        target.parentFile?.mkdirs()
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

    @Test
    fun `a cover-only pick still resolves to the cover file`() {
        val key = "resolution-cost-cover"
        writePng(CustomCoverStore.activeFile(context, key))
        val face = resolveWiredFace(context, key, MediaState.SHELF)
        assertNotNull("a cover pick must still resolve after the dedup", face)
        assertEquals(false, face!!.physical)
    }

    @Test
    fun `a cartridge-only pick still resolves to the cartridge file, and as physical`() {
        val key = "resolution-cost-cartridge"
        writePng(TomeCartridgeArtStore.activeFile(context, key))
        val face = resolveWiredFace(context, key, MediaState.SHELF)
        assertNotNull("a cartridge pick must still resolve after the dedup", face)
        assertEquals(true, face!!.physical)
    }

    @Test
    fun `a file in BOTH stores resolves to the cover, deterministically`() {
        val key = "resolution-cost-both"
        writePng(CustomCoverStore.activeFile(context, key))
        writePng(TomeCartridgeArtStore.activeFile(context, key))
        val face = resolveWiredFace(context, key, MediaState.SHELF)
        assertNotNull("two legacy picks and no record must still resolve to something", face)
        assertEquals(
            "C5-F3 with nothing left to arbitrate the tie: it resolves to the COVER, which is the answer " +
                "the Cartridge Hero switch already gave in its shipped OFF position",
            false,
            face!!.physical,
        )
    }

    @Test
    fun `no pick at all still resolves to nothing`() {
        assertNull(resolveWiredFace(context, "resolution-cost-none", MediaState.SHELF))
    }

    /**
     * THE CACHE'S SAFETY PROPERTY, pinned where it can actually fail.
     *
     * Robolectric's `BitmapFactory` reports bounds for any bytes, so a test here cannot tell a valid PNG
     * from garbage and cannot observe the decode being skipped at all. Asserting through the store would
     * be a test that passes whatever the cache does. The rule is therefore pinned as a pure function,
     * and the store-level test below asserts only what Robolectric CAN see: the cheap `isFile`/length
     * checks run before the cache is ever consulted, so a file that goes away stops resolving.
     */
    @Test
    fun `a decode verdict is reusable only while the file is byte-for-byte the one it was taken about`() {
        assertEquals(
            "same size, same mtime: reusable",
            true,
            tomeCartridgeArtDecodeCacheHits(cachedLength = 120L, cachedModified = 5_000L, length = 120L, modified = 5_000L),
        )
        assertEquals(
            "rewritten to a different size: must decode again",
            false,
            tomeCartridgeArtDecodeCacheHits(cachedLength = 120L, cachedModified = 5_000L, length = 121L, modified = 5_000L),
        )
        assertEquals(
            "same size but touched: must decode again",
            false,
            tomeCartridgeArtDecodeCacheHits(cachedLength = 120L, cachedModified = 5_000L, length = 120L, modified = 6_000L),
        )
        assertEquals(
            "nothing remembered is never a hit",
            false,
            tomeCartridgeArtDecodeCacheHits(cachedLength = null, cachedModified = null, length = 120L, modified = 5_000L),
        )
    }

    @Test
    fun `a cached file that goes away stops resolving`() {
        val key = "resolution-cost-vanish"
        val file = TomeCartridgeArtStore.activeFile(context, key)
        writePng(file)
        assertNotNull("first read must resolve", TomeCartridgeArtStore.activeUri(context, key))
        assertNotNull("second read (cache populated) must agree", TomeCartridgeArtStore.activeUri(context, key))
        file.delete()
        assertNull(
            "a positive verdict must not outlive the file — the isFile check runs before the cache",
            TomeCartridgeArtStore.activeUri(context, key),
        )
    }
}
