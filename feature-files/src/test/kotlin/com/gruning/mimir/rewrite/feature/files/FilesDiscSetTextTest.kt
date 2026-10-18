package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.DiscSetMoveOption
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The multi-disc warning in both languages (localization step 4b). The English stands word for word, apart from
 * the sentence-case eyebrow; its Spanish keeps what each sentence claims about the game.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesDiscSetTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    private fun warning(
        discNumber: Int = 2,
        discCount: Int = 3,
        canTravel: Boolean = true,
        hasPlaylist: Boolean = true,
        spansFolders: Boolean = false,
    ) = DiscSetWarning(
        title = "Final Fantasy IX",
        discNumber = discNumber,
        discCount = discCount,
        subjectPath = "/sd/ps1/FF9/FF9 (Disc 2).chd",
        // spansFolders is derived from the paths: two folders make it true.
        setPaths = if (spansFolders) {
            listOf("/sd/ps1/a/FF9 (Disc 1).chd", "/sd/ps1/b/FF9 (Disc 2).chd")
        } else {
            listOf("/sd/ps1/FF9/FF9.m3u", "/sd/ps1/FF9/FF9 (Disc 2).chd")
        },
        hasPlaylist = hasPlaylist,
        wholeSetCanTravel = canTravel,
    )

    @Test
    fun `the eyebrow is sentence case in both languages`() {
        assertEquals("Multi-disc game", english.getString(FileBrowserDiscSetEyebrow))
        assertEquals("Juego de varios discos", spanish.getString(FileBrowserDiscSetEyebrow))
    }

    @Test
    fun `the headline is one sentence with the disc, the count and the title in its slots`() {
        assertEquals("Disco 2 de 3 — Final Fantasy IX", fileBrowserDiscSetHeadline(spanish, warning()))
        assertEquals("Uno de 4 discos — Final Fantasy IX", fileBrowserDiscSetHeadline(spanish, warning(discNumber = 0, discCount = 4)))
        assertEquals("Disc 2 of 3 — Final Fantasy IX", fileBrowserDiscSetHeadline(english, warning()))
    }

    @Test
    fun `each of the four warnings says in Spanish what it says in English`() {
        val cases = listOf(
            warning(hasPlaylist = false, spansFolders = true) to ("carpetas distintas" to "Moverlos juntos"),
            warning(hasPlaylist = false) to ("se quedan en esta carpeta" to "el juego se detiene"),
            warning() to ("por nombre" to "deja de funcionar"),
            warning(canTravel = false) to ("ubicaciones fijas" to "no puede repararlo"),
        )
        cases.forEach { (warning, words) ->
            val text = fileBrowserDiscSetDetail(spanish, warning)
            assertTrue("${words.first} in: $text", text.contains(words.first))
            assertTrue("${words.second} in: $text", text.contains(words.second))
            assertTrue("a different sentence from English", text != fileBrowserDiscSetDetail(english, warning))
        }
        assertEquals(4, cases.map { fileBrowserDiscSetDetail(spanish, it.first) }.toSet().size)
    }

    @Test
    fun `the three answers and their consequences read in Spanish`() {
        assertEquals("Mover los 4 discos", fileBrowserDiscSetLabel(spanish, DiscSetMoveOption.MOVE_WHOLE_SET, warning(discCount = 4)))
        assertEquals("Mover solo este", fileBrowserDiscSetLabel(spanish, DiscSetMoveOption.MOVE_SUBJECT_ONLY, warning()))
        assertEquals("Dejarlo donde está", fileBrowserDiscSetLabel(spanish, DiscSetMoveOption.CANCEL, warning()))
        assertEquals(
            "Incluye la lista de reproducción, así que el juego sigue funcionando",
            fileBrowserDiscSetDetail(spanish, DiscSetMoveOption.MOVE_WHOLE_SET, warning()),
        )
        assertEquals(
            "Los mantiene juntos, así que el juego sigue funcionando",
            fileBrowserDiscSetDetail(spanish, DiscSetMoveOption.MOVE_WHOLE_SET, warning(hasPlaylist = false)),
        )
        assertEquals(
            "El juego dejará de funcionar hasta que muevas el resto",
            fileBrowserDiscSetDetail(spanish, DiscSetMoveOption.MOVE_SUBJECT_ONLY, warning()),
        )
        assertEquals("", fileBrowserDiscSetDetail(spanish, DiscSetMoveOption.MOVE_SUBJECT_ONLY, warning(canTravel = false)))
        assertEquals("", fileBrowserDiscSetDetail(spanish, DiscSetMoveOption.CANCEL, warning()))
    }
}
