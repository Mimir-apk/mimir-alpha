package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The browser's own chrome in both languages (localization step 4): the header titles, the section headings, the empty folder, the Return
 * row, the commit row and the image picker's words. English is the default folder, in sentence case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesChromeTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    @Test
    fun `the header names the browser and each picker in both languages`() {
        assertEquals("Files", fileBrowserHeaderTitle(english, FileBrowserMode.MANAGE))
        assertEquals("Archivos", fileBrowserHeaderTitle(spanish, FileBrowserMode.MANAGE))
        assertEquals("Archivos", fileBrowserHeaderTitle(spanish, FileBrowserMode.PICK))
        assertEquals("ᛗ · Elige una imagen", fileBrowserHeaderTitle(spanish, FileBrowserMode.PICK_IMAGE))
        assertEquals("ᛗ · Elige una carpeta de música", fileBrowserHeaderTitle(spanish, FileBrowserMode.PICK_MUSIC_FOLDER))
    }

    @Test
    fun `the section headings carry their count in the one slot`() {
        assertEquals("Folders · 3", english.getString(R.string.files_browse_folders_heading, 3))
        assertEquals("Files · 12", english.getString(R.string.files_browse_files_heading, 12))
        assertEquals("Carpetas · 3", spanish.getString(R.string.files_browse_folders_heading, 3))
        assertEquals("Archivos · 12", spanish.getString(R.string.files_browse_files_heading, 12))
    }

    @Test
    fun `the empty folder and the Return row read in both languages`() {
        assertEquals("Esta carpeta está vacía.", fileBrowserEmptyNote(spanish, hiddenCount = 0))
        assertEquals("No hay imágenes en esta carpeta.", fileBrowserEmptyNote(spanish, hiddenCount = 3))
        assertEquals("Volver", fileBrowserBackToOriginLabel(spanish, "/root/Downloads"))
        assertEquals("Return to Downloads", english.getString(R.string.files_browse_return_to, "Downloads"))
        assertEquals("Volver a Downloads", spanish.getString(R.string.files_browse_return_to, "Downloads"))
        assertEquals("Cola", spanish.getString(R.string.files_browse_queue_plate))
    }

    @Test
    fun `the commit row says what it does to this folder, and each purpose has its own words`() {
        val purposes = listOf(
            FileBrowserCommitPurpose.EXTRACT_DESTINATION,
            FileBrowserCommitPurpose.MOVE_DESTINATION,
            FileBrowserCommitPurpose.COPY_DESTINATION,
            FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER,
            FileBrowserCommitPurpose.CHOOSE_SOURCE,
        )
        assertEquals(
            listOf("Extraer" to "Extraer en esta carpeta", "Mover" to "Mover a esta carpeta", "Copiar" to "Copiar a esta carpeta", "Usar" to "Usar esta carpeta", "Agregar" to "Agregar esta carpeta"),
            purposes.map { fileBrowserCommitCopy(spanish, it).let { copy -> copy.eyebrow to copy.label } },
        )
        assertEquals(
            "a missing purpose is the add-a-source plate, as before",
            fileBrowserCommitCopy(spanish, FileBrowserCommitPurpose.CHOOSE_SOURCE),
            fileBrowserCommitCopy(spanish, null),
        )
        purposes.forEach { assertNotEquals(fileBrowserCommitCopy(english, it), fileBrowserCommitCopy(spanish, it)) }
    }

    @Test
    fun `the image picker's switch counts what it hides as a plural`() {
        assertEquals("All files · 1 hidden", fileBrowserCommitCopy(english, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, imagePickHiddenCount = 1).label)
        assertEquals("Todos · 1 oculto", fileBrowserCommitCopy(spanish, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, imagePickHiddenCount = 1).label)
        assertEquals("Todos · 7 ocultos", fileBrowserCommitCopy(spanish, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, imagePickHiddenCount = 7).label)
        assertEquals(
            "Solo imágenes",
            fileBrowserCommitCopy(spanish, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER, imagePickHiddenCount = 7, imagePickShowsAll = true).label,
        )
        assertEquals("Mostrar", fileBrowserCommitCopy(spanish, FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER).eyebrow)
        assertEquals("No es una imagen", fileBrowserImagePickNote(spanish, FileBrowserMode.PICK_IMAGE, nonImage()))
    }

    private fun nonImage() = FileBrowserTile(
        item = com.gruning.mimir.rewrite.core.files.FileItem(name = "disc.iso", path = "/root/disc.iso", isDirectory = false),
        kind = FileBrowserTileKind.FILE,
        machinery = false,
        platformIdValue = null,
    )
}
