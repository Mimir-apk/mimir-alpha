package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Your music's embedded art on the music panel's cards : which card takes it, what the card says while it is on its way, and that a track without a
 * picture keeps exactly the card it had before tags were read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class YourMusicArtTest {
    private val cover: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun model(yours: YourMusicState? = YourMusicState(title = "Song", isPlaying = true, trackId = "track-1"), other: OtherPlayerSnapshot? = null) =
        musicPanelModel(
            control = MusicControlState(yours = yours, other = other, otherAccess = true),
            stopped = false,
            musicVolume = 0.4f,
            mediaVolume = 0.7f,
            mediaAvailable = true,
            mediaFixed = false,
            cappedByMedia = false,
            capPushed = false,
        )

    private fun art(id: String? = "track-1", hint: EmbeddedArtHint = EmbeddedArtHint.YES, bitmap: Bitmap? = null) = YourMusicArtState(id, hint, bitmap)

    @Test
    fun `the card knows which track is on air`() {
        assertEquals("track-1", model().top.trackId)
        assertNull("another player's card names no library track", model(yours = null, other = OtherPlayerSnapshot("T", "A", "App", artwork = null, isPlaying = true)).top.trackId)
        assertNull("a track the engine has not named yet", model(YourMusicState(title = "Song", isPlaying = true)).top.trackId)
    }

    @Test
    fun `a decoded picture is the card's art and the card keeps a seat for it`() {
        val card = model().top.withYoursArt(art(bitmap = cover))
        assertSame(cover, card.artwork)
        assertTrue(card.showArtwork)
        assertFalse("nothing is pending once it has arrived", card.artworkPending)
        assertFalse("and it is not a control: Your music's art does nothing", card.canOpenPlayer)
    }

    @Test
    fun `art known to exist but not yet decoded holds the seat blank, never the glyph`() {
        val card = model().top.withYoursArt(art(hint = EmbeddedArtHint.YES, bitmap = null))
        assertTrue(card.showArtwork)
        assertTrue("pending draws neither the picture nor the fallback glyph", card.artworkPending)
        assertNull(card.artwork)
    }

    @Test
    fun `a track with no picture keeps the card it had`() {
        val before = model().top
        val none = before.withYoursArt(art(hint = EmbeddedArtHint.NO))
        assertEquals(before, none)
        assertFalse(none.showArtwork)
        assertFalse(none.artworkPending)
        assertEquals("a track whose tags are not read yet shows no seat until a picture is found", before, before.withYoursArt(art(hint = EmbeddedArtHint.UNKNOWN)))
    }

    @Test
    fun `an unread track whose picture was found anyway gets its seat`() {
        val card = model().top.withYoursArt(art(hint = EmbeddedArtHint.UNKNOWN, bitmap = cover))
        assertTrue(card.showArtwork)
        assertSame(cover, card.artwork)
    }

    @Test
    fun `art for another track is not this card's`() {
        val card = model().top
        assertEquals(card, card.withYoursArt(art(id = "track-2", bitmap = cover)))
        assertEquals(card, card.withYoursArt(art(id = null, bitmap = cover)))
        assertEquals(card, card.withYoursArt(null))
    }

    @Test
    fun `another player's card is never given Your music's art`() {
        val other = model(yours = null, other = OtherPlayerSnapshot("T", "A", "App", artwork = null, isPlaying = true)).top
        assertEquals(other, other.withYoursArt(art(bitmap = cover)))
    }

    @Test
    fun `the panel's cards are laid over as one`() {
        val laid = model().withYoursArt(art(bitmap = cover))
        assertSame(cover, laid.top.artwork)
        assertEquals(model(), model().withYoursArt(null))
        assertEquals(YourMusicArtState.None.present, false)
    }
}
