package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Files footer legends in both languages (localization step 4). English is the default folder, in sentence case;
 * Spanish is Latin American (`es-419`). Every hint is a controller token and a capitalised label: the token is never translated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesFooterTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    private fun tile(name: String, kind: FileBrowserTileKind) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = kind == FileBrowserTileKind.DIRECTORY),
        kind = kind,
        machinery = false,
        platformIdValue = null,
    )

    @Test
    fun `the browse legend keeps its tokens and speaks Spanish`() {
        val folder = tile("Roms", FileBrowserTileKind.DIRECTORY)
        assertEquals(
            listOf("A" to "Open", "Y" to "Search", "X" to "Actions", "START" to "Queue · 2", "B" to "Close"),
            fileBrowserFooterHints(english, folder, atRoot = true, searchAvailable = true, actionsAvailable = true, queueAvailable = true, queueCount = 2),
        )
        assertEquals(
            listOf("A" to "Abrir", "Y" to "Buscar", "X" to "Acciones", "START" to "Cola · 2", "B" to "Cerrar"),
            fileBrowserFooterHints(spanish, folder, atRoot = true, searchAvailable = true, actionsAvailable = true, queueAvailable = true, queueCount = 2),
        )
    }

    @Test
    fun `the held-delete counts are plurals in both languages`() {
        fun bWord(res: android.content.res.Resources, count: Int) =
            fileBrowserFooterHints(res, null, atRoot = true, queueAvailable = true, heldDeleteCount = count).first { it.first == "B" }.second
        assertEquals("Review 1 delete", bWord(english, 1))
        assertEquals("Review 3 deletes", bWord(english, 3))
        assertEquals("Revisar 1 eliminación", bWord(spanish, 1))
        assertEquals("Revisar 3 eliminaciones", bWord(spanish, 3))
        fun closeWord(res: android.content.res.Resources, count: Int) =
            fileBrowserQueueFooterHints(res, FileBrowserQueueAction.UNDO, exitReview = true, heldDeleteCount = count).first { it.first == "B" }.second
        assertEquals("Close · 1 delete final", closeWord(english, 1))
        assertEquals("Cerrar · 1 eliminación definitiva", closeWord(spanish, 1))
        assertEquals("Cerrar · 2 eliminaciones definitivas", closeWord(spanish, 2))
    }

    @Test
    fun `the choosers, the overlay and Search keep B and name their own A`() {
        assertEquals(listOf("A" to "Elegir carpeta", "B" to "Atrás"), fileBrowserTransferFooterHints(spanish, FileBrowserTransferKind.MOVE))
        assertEquals(listOf("A" to "Extraer", "B" to "Atrás"), fileBrowserExtractFooterHints(spanish, FileBrowserExtractOptionKind.HERE))
        assertEquals(listOf("A" to "Elegir carpeta", "B" to "Atrás"), fileBrowserExtractFooterHints(spanish, FileBrowserExtractOptionKind.CHOOSE_FOLDER))
        assertEquals(listOf("A" to "Cambiar nombre", "B" to "Atrás"), fileBrowserActionOverlayFooterHints(spanish, FileBrowserActionOverlayRowKind.RENAME))
        assertEquals(listOf("A" to "Eliminar", "B" to "Atrás"), fileBrowserActionOverlayFooterHints(spanish, FileBrowserActionOverlayRowKind.DELETE))
        assertEquals(listOf("A" to "Ponerlo aquí", "B" to "Cancelar"), fileBrowserDestinationFooterHints(spanish, FileBrowserDestinationRowKind.PLATFORM))
        assertEquals(listOf("A" to "Ir al archivo", "B" to "Atrás"), fileBrowserSearchFooterHints(spanish, onQuery = false, onFilter = false, onResult = true))
    }

    @Test
    fun `the queue's buttons read in both languages and each queue legend word is the focused button's own`() {
        assertEquals("Keep both", english.getString(FileBrowserQueueAction.KEEP_BOTH.labelRes))
        assertEquals("Conservar ambos", spanish.getString(FileBrowserQueueAction.KEEP_BOTH.labelRes))
        assertEquals("Reemplazar", spanish.getString(FileBrowserQueueAction.REPLACE.labelRes))
        assertEquals("Omitir", spanish.getString(FileBrowserQueueAction.SKIP.labelRes))
        FileBrowserQueueAction.entries.forEach { action ->
            assertEquals(
                listOf("A" to spanish.getString(action.labelRes), "B" to "Atrás"),
                fileBrowserQueueFooterHints(spanish, action),
            )
            assertTrue(spanish.getString(action.labelRes).first().isUpperCase())
        }
        val spanishWords = FileBrowserQueueAction.entries.map { spanish.getString(it.labelRes) }
        assertEquals("one Spanish word for two buttons would blur Dismiss and Keep deleted", spanishWords.size, spanishWords.toSet().size)
    }
}
