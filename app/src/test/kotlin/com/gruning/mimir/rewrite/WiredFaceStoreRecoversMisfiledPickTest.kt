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
 * [resolveWiredFace] must find a pick's file **where it actually is**, and use [WiredFaceKind] only for the
 * SHAPE.
 *
 * **This is a repair for a pick that was already lost on the reference device.** The face picker offers boxes and the
 * physical article in one grid and saves both through `EnrichMediaKind.COVER`, which writes to
 * [CustomCoverStore] — while the record written beside it is derived from the provider's media type, so a
 * `support-…` asset is recorded `PHYSICAL`. A read that used the kind to choose the STORE then looked in
 * [TomeCartridgeArtStore], found nothing, and fell through as though nothing had ever been picked.
 *
 * Measured on the Odin 2026-08-26 by deriving all 21 storage names and listing both stores: exactly one
 * record was in that state — a LAUNCH CARD pick for *Tears of the Kingdom* — and it was being ignored.
 * Seventeen of the twenty-one have a file in BOTH stores, which is why the named store must still be tried
 * first rather than the lookup simply preferring one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class WiredFaceStoreRecoversMisfiledPickTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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

    /**
     * The observed case: a `PHYSICAL` record for the LAUNCH_CARD state whose file sits in the COVER store,
     * because that is where the merged picker writes everything it saves.
     */
    @Test
    fun `a PHYSICAL record whose file is in the cover store still resolves, instead of losing the pick`() {
        val key = "title:switch:tears of the kingdom"
        val storageKey = mediaStorageKey(key, MediaState.LAUNCH_CARD)
        writePng(CustomCoverStore.activeFile(context, storageKey))
        WiredFaceStore.record(context, key, MediaState.LAUNCH_CARD, WiredFaceKind.PHYSICAL)

        val face = resolveWiredFace(context, key, MediaState.LAUNCH_CARD)
        assertNotNull("the file exists; looking in only one store is what lost it", face)
        assertTrue(
            "the record says the picture IS a disc, so the SHAPE stays physical even though the file " +
                "lives in the cover store — those are two different facts",
            face!!.physical,
        )
    }

    /**
     * The reason the named store is still tried first. Both stores holding a file at one key is the COMMON
     * case on the reference device (17 of 21), and there the kind is the only thing that says which is the face.
     */
    @Test
    fun `when both stores hold a file, the record decides which one is the face`() {
        val key = "title:gamecube:resident evil"
        val shelfKey = mediaStorageKey(key, MediaState.SHELF)
        writePng(CustomCoverStore.activeFile(context, shelfKey))
        writePng(TomeCartridgeArtStore.activeFile(context, shelfKey))

        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)
        val cover = resolveWiredFace(context, key, MediaState.SHELF)
        assertEquals(CustomCoverStore.activeUri(context, shelfKey), cover?.uri)
        assertFalse("a COVER record must not be reported physical", cover?.physical ?: true)

        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.PHYSICAL)
        val physical = resolveWiredFace(context, key, MediaState.SHELF)
        assertEquals(TomeCartridgeArtStore.activeUri(context, shelfKey), physical?.uri)
        assertTrue("a PHYSICAL record must be reported physical", physical?.physical ?: false)
    }

    /** A COVER record misfiled into the cartridge store recovers the same way — the repair is symmetric. */
    @Test
    fun `a COVER record whose file is in the cartridge store also resolves`() {
        val key = "title:ps2:castlevania"
        val shelfKey = mediaStorageKey(key, MediaState.SHELF)
        writePng(TomeCartridgeArtStore.activeFile(context, shelfKey))
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.COVER)

        val face = resolveWiredFace(context, key, MediaState.SHELF)
        assertNotNull(face)
        assertFalse("shape follows the record, not the store it was found in", face!!.physical)
    }

    /** A record with no file anywhere is still honest absence — the fall-through must survive the repair. */
    @Test
    fun `a record with no file at all still resolves to nothing`() {
        val key = "title:wii:no files anywhere"
        WiredFaceStore.record(context, key, MediaState.SHELF, WiredFaceKind.PHYSICAL)
        assertNull(
            "a kind whose file is missing must still fall through honestly",
            resolveWiredFace(context, key, MediaState.SHELF),
        )
    }
}
