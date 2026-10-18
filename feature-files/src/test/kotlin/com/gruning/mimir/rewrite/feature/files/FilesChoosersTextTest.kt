package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.ExtractDestinationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The words of the action overlay, the Move/Copy chooser, the Extract chooser and the destination list in both languages (localization
 * step 4). A folder's own name, an archive's file name and a path are the player's and are never in a string; they arrive as arguments.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesChoosersTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    @Test
    fun `the action overlay's rows and eyebrow read in both languages`() {
        val expected = mapOf(
            FileBrowserActionOverlayRowKind.MOVE to ("Move into…" to "Mover a…"),
            FileBrowserActionOverlayRowKind.COPY to ("Copy into…" to "Copiar a…"),
            FileBrowserActionOverlayRowKind.RENAME to ("Rename…" to "Cambiar nombre…"),
            FileBrowserActionOverlayRowKind.EXTRACT to ("Extract…" to "Extraer…"),
            FileBrowserActionOverlayRowKind.DELETE to ("Delete" to "Eliminar"),
        )
        FileBrowserActionOverlayRowKind.entries.forEach { kind ->
            assertEquals(expected.getValue(kind).first, fileBrowserActionOverlayLabel(english, kind))
            assertEquals(expected.getValue(kind).second, fileBrowserActionOverlayLabel(spanish, kind))
        }
        assertEquals("Actions", english.getString(FileBrowserActionOverlayEyebrow))
        assertEquals("Acciones", spanish.getString(FileBrowserActionOverlayEyebrow))
        assertEquals("Held in the queue until you keep it", fileBrowserActionOverlayDetail(english, FileBrowserActionOverlayRowKind.DELETE))
        assertEquals("Queda en la cola hasta que lo dejes eliminado", fileBrowserActionOverlayDetail(spanish, FileBrowserActionOverlayRowKind.DELETE))
        assertEquals("", fileBrowserActionOverlayDetail(spanish, FileBrowserActionOverlayRowKind.MOVE))
    }

    @Test
    fun `the Move and Copy chooser keeps one eyebrow and two rows that each name their verb`() {
        assertEquals("Place", english.getString(FileBrowserTransferEyebrow))
        assertEquals("Colocar", spanish.getString(FileBrowserTransferEyebrow))
        assertEquals(listOf("Mover a…", "Copiar a…"), fileBrowserTransferOptions().map { spanish.getString(it.labelRes) })
    }

    @Test
    fun `the Extract chooser's rows put the platform or folder name in the one slot`() {
        val options = ExtractDestinationPolicy.options(
            archivePath = "/storage/emulated/0/Downloads/Golden Sun.zip",
            platformId = "gba",
            platformFolders = listOf("/roms/GBA"),
        )
        val rows = fileBrowserExtractOptions(options, folderPickerAvailable = true)
        val named = fileBrowserPlatformCode("gba") ?: "GBA"
        assertEquals(listOf("Extract here", "Extract in $named", "Extract in…"), rows.map { it.label(english) })
        assertEquals(listOf("Extraer aquí", "Extraer en $named", "Extraer en…"), rows.map { it.label(spanish) })
        assertEquals("Extract", english.getString(FileBrowserExtractEyebrow))
        assertEquals("Extraer", spanish.getString(FileBrowserExtractEyebrow))
        assertEquals("Extraer Golden Sun.zip en…", fileBrowserDestinationContext(spanish, "/storage/emulated/0/Downloads/Golden Sun.zip"))
    }

    @Test
    fun `the destination list draws the escape hatch from a string and keeps folder names and paths as they are`() {
        val browse = FileBrowserDestinationRow(FileBrowserDestinationRowKind.BROWSE, label = "", detail = "", path = null)
        val sameDrive = FileBrowserDestinationRow(FileBrowserDestinationRowKind.PLATFORM, "gba", "/storage/sd/Roms", "/storage/sd/Roms/gba")
        val otherDrive = sameDrive.copy(label = "ps2", detail = "/storage/usb/Roms/ps2", path = "/storage/usb/Roms/ps2", onOtherDrive = true)
        assertEquals("Browse for a folder instead…", fileBrowserDestinationRowLabel(english, browse))
        assertEquals("Explorar otra carpeta…", fileBrowserDestinationRowLabel(spanish, browse))
        assertEquals("gba", fileBrowserDestinationRowLabel(spanish, sameDrive))
        assertEquals("/storage/sd/Roms", fileBrowserDestinationRowDetail(spanish, sameDrive))
        assertEquals("On another drive · /storage/usb/Roms/ps2", fileBrowserDestinationRowDetail(english, otherDrive))
        assertEquals("En otra unidad · /storage/usb/Roms/ps2", fileBrowserDestinationRowDetail(spanish, otherDrive))
        assertEquals("Put it in", english.getString(FileBrowserDestinationEyebrow))
        assertEquals("En la biblioteca", spanish.getString(FileBrowserDestinationLibraryMark))
        assertFalse(spanish.getString(FileBrowserDestinationLibraryMark) == english.getString(FileBrowserDestinationLibraryMark))
    }
}
