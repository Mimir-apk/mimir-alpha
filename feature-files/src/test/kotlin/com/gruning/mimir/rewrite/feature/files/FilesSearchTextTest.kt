package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FILE_SEARCH_SCOPE_COPY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Files Search screen's words in both languages (localization step 4b): the placeholder, the Scope card, the count line (plurals, with the volume's name in
 * one slot) and the three empty states. The layout code is unchanged; its widths were measured against the English words, and a device pass retunes them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesSearchTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    @Test
    fun `the Scope card's English is the engine's privacy sentence word for word, and the Spanish keeps all three promises`() {
        assertEquals(FILE_SEARCH_SCOPE_COPY, english.getString(R.string.files_search_scope_copy))
        val text = spanish.getString(R.string.files_search_scope_copy)
        assertTrue("names only: $text", text.startsWith("Solo nombres"))
        assertTrue("it does not read contents: $text", text.contains("no lee el contenido"))
        assertTrue("nothing is sent: $text", text.contains("no se envía nada"))
        assertEquals("Alcance", spanish.getString(R.string.files_search_scope_eyebrow))
    }

    @Test
    fun `the placeholder and the keyboard's label read in both languages`() {
        assertEquals("Search this volume", english.getString(FileBrowserSearchPlaceholder))
        assertEquals("Buscar en esta unidad", spanish.getString(FileBrowserSearchPlaceholder))
        assertEquals("Search files", english.getString(R.string.files_search_keyboard_label))
        assertEquals("Buscar archivos", spanish.getString(R.string.files_search_keyboard_label))
    }

    @Test
    fun `the count line is a plural with the volume in one slot and refuses to guess a name`() {
        assertEquals("3 resultados en SD Card", fileBrowserSearchStatusLabel(spanish, 3, FileBrowserSearchPhase.SETTLED, "SD Card"))
        assertEquals("1 resultado en SD Card", fileBrowserSearchStatusLabel(spanish, 1, FileBrowserSearchPhase.SETTLED, "SD Card"))
        assertEquals("0 resultados en esta unidad", fileBrowserSearchStatusLabel(spanish, 0, FileBrowserSearchPhase.SETTLED, null))
        assertEquals("1 resultado en esta unidad", fileBrowserSearchStatusLabel(spanish, 1, FileBrowserSearchPhase.SETTLED, "  "))
        assertEquals("Buscando en SD Card · 3 hasta ahora", fileBrowserSearchStatusLabel(spanish, 3, FileBrowserSearchPhase.RUNNING, "SD Card"))
        assertEquals("Buscando en esta unidad · 3 hasta ahora", fileBrowserSearchStatusLabel(spanish, 3, FileBrowserSearchPhase.RUNNING, null))
        assertEquals("Searching on this volume · 3 so far", fileBrowserSearchStatusLabel(english, 3, FileBrowserSearchPhase.RUNNING, null))
        assertNull(fileBrowserSearchStatusLabel(spanish, 0, FileBrowserSearchPhase.IDLE, "SD Card"))
    }

    @Test
    fun `the three empty states are three different sentences in each language`() {
        assertEquals("Escribe un nombre o elige un filtro.", fileBrowserSearchEmptyMessage(spanish, 0, FileBrowserSearchPhase.IDLE))
        assertEquals("Buscando…", fileBrowserSearchEmptyMessage(spanish, 0, FileBrowserSearchPhase.RUNNING))
        assertEquals("No hay nada con ese nombre.", fileBrowserSearchEmptyMessage(spanish, 0, FileBrowserSearchPhase.SETTLED))
        assertNull(fileBrowserSearchEmptyMessage(spanish, 2, FileBrowserSearchPhase.SETTLED))
        listOf(english, spanish).forEach { res ->
            val words = FileBrowserSearchPhase.entries.map { fileBrowserSearchEmptyMessage(res, 0, it) }
            assertEquals(words.toString(), 3, words.toSet().size)
            assertFalse(words.toString(), words.any { it.isNullOrBlank() })
        }
    }
}
