package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/**
 * [MusicTagReader] over `MediaMetadataRetriever`: title, artist, album, album artist, track and disc number and duration, and whether the file carries an
 * embedded picture. One retriever per file, released at once; blocking, so the caller reads on IO ([MusicTagPass]).
 *
 * **Nothing here logs a path.** The retriever's exception messages can carry the file's path (a personal music folder), so only the exception's class
 * is logged. A [SecurityException] (the folder grant is gone) is [MusicTagResult.Unavailable] and not a verdict on the file; every other failure is
 * [MusicTagResult.Failed] for this file.
 */
internal class AndroidMusicTagReader(context: Context) : MusicTagReader {
    private val appContext = context.applicationContext

    override fun read(source: MusicTagSource): MusicTagResult {
        val retriever = MediaMetadataRetriever()
        return try {
            when (source) {
                is MusicTagSource.Path -> retriever.setDataSource(source.absolutePath)
                is MusicTagSource.Document -> retriever.setDataSource(appContext, Uri.parse(source.uri))
            }
            val tags = MusicTagParse.tags { key -> retriever.extractMetadata(key) }
            // The picture's bytes are dropped at once: only whether there is one is kept. The art itself is decoded later, at the size it is drawn at.
            val hasArt = try {
                retriever.embeddedPicture?.isNotEmpty() == true
            } catch (_: RuntimeException) {
                false
            }
            MusicTagResult.Read(tags, hasArt)
        } catch (_: SecurityException) {
            MusicTagResult.Unavailable
        } catch (error: Exception) {
            Log.w(Tag, "no tags for a track (${error.javaClass.simpleName})")
            MusicTagResult.Failed
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // Nothing to release: the retriever never got a source.
            }
        }
    }

    private companion object {
        const val Tag = "MimirMusicTags"
    }
}
