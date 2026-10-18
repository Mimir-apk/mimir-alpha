package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.gruning.mimir.rewrite.core.audio.EmbeddedArtHint
import com.gruning.mimir.rewrite.core.audio.MimirMusicLibrary
import kotlinx.coroutines.flow.StateFlow

/**
 * Your music's embedded art for the track on air, as one surface draws it . The card models of the music panel are pure functions of the engine's
 * state and know nothing of pictures; each surface that shows a Your-music card asks for the art of its card's track at the size it draws it
 * ([rememberYourMusicArt]) and lays it over the card ([withYoursArt]).
 *
 * The panel's art law holds for it: art that is known to exist is never replaced by the fallback glyph, not even for a frame ([loading] holds the seat blank),
 * and a track with no art keeps exactly the look it had before tags were read (Main has no art seat; the Bottom shows its plate and glyph).
 */
internal data class YourMusicArtState(
    val trackId: String?,
    /** Whether the library knows the track has a picture; [EmbeddedArtHint.UNKNOWN] until its tags are read, when only a decoded bitmap proves there is art. */
    val hint: EmbeddedArtHint,
    val bitmap: Bitmap?,
) {
    /** The track has art, decoded or on its way: a card that draws art for Your music keeps a seat for it. */
    val present: Boolean get() = bitmap != null || hint == EmbeddedArtHint.YES

    /** Known to have art that has not arrived yet: the seat is drawn blank, with neither the picture nor the glyph. */
    val loading: Boolean get() = bitmap == null && hint == EmbeddedArtHint.YES

    companion object {
        val None = YourMusicArtState(trackId = null, hint = EmbeddedArtHint.NO, bitmap = null)
    }
}

/** This card with [art] laid over it, when it is Your music's card for the very track [art] belongs to; every other card is returned as it is. */
internal fun MusicPanelCardModel.withYoursArt(art: YourMusicArtState?): MusicPanelCardModel {
    if (card != MusicPanelCard.YOURS || art == null || trackId == null || art.trackId != trackId) return this
    return copy(showArtwork = art.present, artwork = art.bitmap, artworkPending = art.loading)
}

/** The panel with Your music's card carrying [art]; another player's card is untouched. */
internal fun MusicPanelModel.withYoursArt(art: YourMusicArtState?): MusicPanelModel =
    if (art == null) this else copy(cards = cards.map { it.withYoursArt(art) })

/**
 * Where a surface gets Your music's art from. The library in production ([MimirYourMusicArtSource]); a render test provides its own through
 * [LocalYourMusicArtSource], since the library is a process singleton with no folder behind it on the JVM.
 */
internal interface YourMusicArtSource {
    /** Moves when tags land, so a hint that has turned from unknown to yes is read again. */
    val revision: StateFlow<Long>

    fun hint(trackId: String): EmbeddedArtHint

    /** The decoded art if it is in memory already; never reads a file. */
    fun cached(trackId: String, px: Int): Bitmap?

    /** A decode already found no picture for this track. */
    fun knownMissing(trackId: String, px: Int): Boolean

    /** Decodes on IO and caches; null when the track has no picture. */
    suspend fun load(context: Context, trackId: String, px: Int): Bitmap?
}

internal object MimirYourMusicArtSource : YourMusicArtSource {
    override val revision: StateFlow<Long> get() = MimirMusicLibrary.tagsRevision

    override fun hint(trackId: String): EmbeddedArtHint = MimirMusicLibrary.artHint(trackId)

    override fun cached(trackId: String, px: Int): Bitmap? = MimirMusicLibrary.cachedEmbeddedArt(trackId, px)

    override fun knownMissing(trackId: String, px: Int): Boolean = MimirMusicLibrary.artKnownMissing(trackId, px)

    override suspend fun load(context: Context, trackId: String, px: Int): Bitmap? = MimirMusicLibrary.embeddedArt(context, trackId, px)
}

internal val LocalYourMusicArtSource = staticCompositionLocalOf<YourMusicArtSource> { MimirYourMusicArtSource }

/**
 * The embedded art of [trackId] for a square [size] wide, decoded on IO and cached by the library, or none. Composition-safe: a cache hit is answered on the
 * first frame, a miss decodes off the main thread and the surface recomposes when the picture lands. While the next track's art loads, the last art stays
 * (an album's tracks share a cover, and the seat would otherwise blink between them); a track known to have none clears it at once.
 */
@Composable
internal fun rememberYourMusicArt(trackId: String?, size: Dp): YourMusicArtState {
    val source = LocalYourMusicArtSource.current
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // Tags land after the music has started: the hint for the track on air can turn from unknown to yes while it plays.
    val revision by source.revision.collectAsState()
    val hint = remember(source, trackId, revision) { trackId?.let(source::hint) ?: EmbeddedArtHint.NO }
    var shown by remember { mutableStateOf(trackId?.let { source.cached(it, px) }) }
    LaunchedEffect(source, trackId, px, hint) {
        when {
            trackId == null || hint == EmbeddedArtHint.NO -> shown = null
            else -> {
                val cached = source.cached(trackId, px)
                shown = when {
                    cached != null -> cached
                    source.knownMissing(trackId, px) -> null
                    else -> source.load(context, trackId, px)
                }
            }
        }
    }
    return YourMusicArtState(trackId, hint, shown)
}
