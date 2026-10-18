package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file kind vocabulary, the inspector and the Checksum row in both languages (localization step 4b). One set of kind words feeds the tile's badge, the Search
 * rows and the inspector's tag; ZIP, 7Z, BIOS and a file's extension are format identifiers and read the same in Spanish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesKindTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    private fun file(name: String, size: Long = 0L, lastModified: Long = 0L, platformIdValue: String? = null, bios: Boolean = false) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", extension = name.substringAfterLast('.', ""), size = size, lastModified = lastModified),
        kind = FileBrowserTileKind.FILE,
        machinery = false,
        platformIdValue = platformIdValue,
        bios = bios,
    )

    private fun folder(name: String, childCount: Int? = null, platformIdValue: String? = null) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", isDirectory = true, childCount = childCount),
        kind = FileBrowserTileKind.DIRECTORY,
        machinery = false,
        platformIdValue = platformIdValue,
    )

    private fun archive(name: String) = FileBrowserTile(
        item = FileItem(name = name, path = "/root/$name", extension = name.substringAfterLast('.', "")),
        kind = FileBrowserTileKind.ARCHIVE,
        machinery = false,
        platformIdValue = null,
    )

    @Test
    fun `the badge says the kind in Spanish and keeps the format identifiers`() {
        val expected = listOf(
            file("Pokemon.srm") to "Guardado",
            file("library.dat") to "Datos",
            file("notes.txt") to "Doc",
            file("theme.mp3") to "Música",
            file("box.png") to "Imagen",
            file("snap.mp4") to "Video",
            file("tool.apk") to "App",
            file("mystery.xyz") to "Archivo",
            folder("Roms") to "Dir",
            archive("pack.zip") to "ZIP",
            archive("pack.7z") to "7Z",
            file("backup.tar") to "TAR",
            file("gba_bios.bin", bios = true) to "BIOS",
        )
        assertEquals(expected.map { it.second }, expected.map { fileBrowserBadgeLabel(spanish, it.first) })
    }

    @Test
    fun `the tile cover and the tile's second line read in Spanish`() {
        assertEquals("Carpeta", fileBrowserTomeEyebrow(spanish, folder("Roms")))
        assertEquals("Eliminando…", fileBrowserTomeEyebrow(spanish, folder("Held").let { it.copy(item = it.item.copy(isPendingDelete = true)) }))
        assertEquals("GBA · 512 B", fileBrowserTileMeta(spanish, file("Metroid.gba", size = 512L)))
        assertEquals(listOf("GBA · 512 B", "512 B"), fileBrowserTileMetaCandidates(spanish, file("Metroid.gba", size = 512L)))
        assertEquals("146 elementos", fileBrowserTileMeta(spanish, folder("Roms", childCount = 146)))
        assertEquals("1 elemento", fileBrowserTileMeta(spanish, folder("Roms", childCount = 1)))
        assertEquals("0 elementos", fileBrowserTileMeta(spanish, folder("Roms", childCount = 0)))
        assertEquals("a folder that cannot be read says nothing, never zero", "", fileBrowserTileMeta(spanish, folder("Roms", childCount = null)))
    }

    @Test
    fun `a folder's count reads as core-files wrote it in English`() {
        listOf(null, 0, 1, 2, 146).forEach { count ->
            val item = FileItem(name = "Roms", path = "/root/Roms", isDirectory = true, childCount = count)
            assertEquals("childCount=$count", item.displaySize, fileBrowserSizeText(english, item))
        }
        val plain = FileItem(name = "a.gba", path = "/root/a.gba", size = 3_100L)
        assertEquals(plain.displaySize, fileBrowserSizeText(english, plain))
    }

    @Test
    fun `the inspector names a folder, an archive and a game in Spanish`() {
        fun fields(tile: FileBrowserTile) = fileBrowserInspectorFields(spanish, tile, currentPath = "/root", rootPath = "/root", zone = ZoneOffset.UTC)

        val plainFolder = fields(folder("Roms", childCount = 3))
        assertEquals("Dir", plainFolder.tag)
        assertEquals("3 elementos", plainFolder.meta)
        assertEquals("Carpeta", plainFolder.chipLabel)
        assertEquals("No es una carpeta de plataforma", plainFolder.launches)

        val platformFolder = fields(folder("GBA", platformIdValue = "game-boy-advance"))
        assertEquals("Plataforma", platformFolder.chipLabel)
        assertEquals("Carpeta GBA", platformFolder.launches)

        val zip = fields(archive("bundle.zip"))
        assertEquals("ZIP", zip.tag)
        assertEquals("Comprimido", zip.chipLabel)
        assertEquals("Aún nada: primero extráelo", zip.launches)

        val game = fields(file("Metroid.gba", platformIdValue = "game-boy-advance"))
        assertEquals("Reconocido", game.chipLabel)
        assertEquals("Se ejecuta en GBA", game.launches)

        assertEquals("Sin plataforma reconocida", spanish.getString(FileBrowserNoPlatformLaunches))
        assertEquals("Los emuladores lo necesitan donde está", spanish.getString(FileBrowserBiosLaunches))
        assertEquals("BIOS", fields(file("gba_bios.bin", bios = true)).chipLabel)
        assertEquals("Medios", spanish.getString(FileBrowserMediaArtChip))
        assertEquals("Datos guardados", fields(file("Pokemon.srm")).chipLabel)
        assertEquals("Documento", fields(file("notes.txt")).chipLabel)
        assertEquals("No es contenido de juego", fields(file("mystery.xyz")).launches)
    }

    @Test
    fun `the modified date writes its month in the host's language and English keeps its form`() {
        val epochMillis = 1_783_892_280_000L
        val inEnglish = fileBrowserFormatModified(english, epochMillis, ZoneOffset.UTC)
        val inSpanish = fileBrowserFormatModified(spanish, epochMillis, ZoneOffset.UTC)
        assertTrue(inEnglish, Regex("""\d{1,2} [A-Z][a-z]{2} \d{4} · \d{2}:\d{2}""").matches(inEnglish))
        assertNotEquals("the month is written in Spanish", inEnglish, inSpanish)
        assertTrue(inSpanish, Regex("""\d{1,2} \S+ \d{4} · \d{2}:\d{2}""").matches(inSpanish))
        assertEquals("only the month word differs", inEnglish.substringAfter(' ').substringAfter(' '), inSpanish.substringAfter(' ').substringAfter(' '))
        assertEquals("Desconocido", fileBrowserFormatModified(spanish, 0L, ZoneOffset.UTC))
        assertEquals("Unknown", fileBrowserFormatModified(english, 0L, ZoneOffset.UTC))
    }

    @Test
    fun `the Checksum row and its buttons read in Spanish`() {
        val path = "/root/Zelda.gba"
        val idle = fileBrowserChecksumRow(spanish, FileBrowserChecksumState.NotChecked, path)
        assertEquals("Sin verificar", idle.status)
        assertEquals("Verificar", idle.verifyLabel)
        assertEquals("Leyendo…", fileBrowserChecksumRow(spanish, FileBrowserChecksumState.Checking(path), path).status)
        assertEquals("Leyendo", fileBrowserChecksumRow(spanish, FileBrowserChecksumState.Checking(path), path).verifyLabel)
        val checked = fileBrowserChecksumRow(spanish, FileBrowserChecksumState.Checked(path, "SHA-1", "a".repeat(40)), path)
        assertEquals("SHA-1", checked.status)
        assertEquals("De nuevo", checked.verifyLabel)
        assertEquals("Copiar", fileBrowserChecksumCopyLabel(spanish, checked, copiedDigest = null))
        assertEquals("Copiado", fileBrowserChecksumCopyLabel(spanish, checked, copiedDigest = "a".repeat(40)))
        assertEquals("Falló la verificación", fileBrowserChecksumRow(spanish, FileBrowserChecksumState.Failed(path), path).status)
        assertEquals(
            "No se pudo leer este archivo",
            fileBrowserChecksumRow(spanish, FileBrowserChecksumState.Failed(path, "Couldn't read this file"), path).status,
        )
        assertEquals(
            "a reason this module does not know is drawn as written",
            "Something else",
            fileBrowserChecksumRow(spanish, FileBrowserChecksumState.Failed(path, "Something else"), path).status,
        )
        assertEquals("La verificación no inició", fileBrowserVerifyRefusalRow(spanish, idle, refusedPath = path, focusedPath = path).status)
    }

    @Test
    fun `the unreadable-file reason is the sentence the app store writes`() {
        assertEquals("Couldn't read this file", FileBrowserChecksumUnreadableReason)
        assertEquals(FileBrowserChecksumUnreadableReason, english.getString(R.string.files_checksum_unreadable))
    }
}
