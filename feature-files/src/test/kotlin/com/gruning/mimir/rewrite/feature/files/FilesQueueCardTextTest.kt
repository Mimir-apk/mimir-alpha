package com.gruning.mimir.rewrite.feature.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Queue cards' titles and second lines in both languages (localization step 4b, item d). The store writes them in English and the screen maps them on display
 * (`FilesQueueText.kt`). Every sample below is a sentence the app store (`FileBrowserStore`, `FileBrowserJobControl`, `FileBrowserCompanionCards`) writes, exactly as it
 * writes it; `FileBrowserQueueCopyDriftTest` in the app module pins the store's side of the same list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FilesQueueCardTextTest {
    private val english = filesEnglish()
    private val spanish = filesSpanish()

    /** (title, the line the store wrote, the line in Spanish). */
    private val cards = listOf(
        Triple("Zelda.gba", "Delete", "Eliminar"),
        Triple("Zelda.gba", "Delete · saves and states kept", "Eliminar · se conservan guardados y estados"),
        Triple(
            "3 files",
            "Delete · saves and states kept · The 3 discs it lists stay.",
            "Eliminar · se conservan guardados y estados · Los 3 discos de su lista se quedan.",
        ),
        Triple("Disc", "Delete · The disc they list stays.", "Eliminar · El disco de sus listas se queda."),
        Triple("Zelda.gba", "Renamed Zelda.gba to Link.gba", "Se cambió el nombre de Zelda.gba a Link.gba"),
        Triple(
            "Final Fantasy to the max.gba",
            "Renamed Final Fantasy to the max.gba to Mario.gba",
            "Se cambió el nombre de Final Fantasy to the max.gba a Mario.gba",
        ),
        Triple(
            "Game.chd",
            "Renamed Game.chd to Better.chd · Game.sbi kept its old name",
            "Se cambió el nombre de Game.chd a Better.chd · Game.sbi conservó su nombre anterior",
        ),
        Triple("3 files", "Moved from Downloads to PS2", "Movido de Downloads a PS2"),
        Triple("3 files", "Copied from Downloads to PS2", "Copiado de Downloads a PS2"),
        Triple("Zelda.gba", "Moved Zelda.gba to ps2", "Se movió Zelda.gba a ps2"),
        Triple("Zelda.gba", "Copied Zelda.gba to ps2", "Se copió Zelda.gba a ps2"),
        Triple("3 files", "Moved 3 files to PS2", "Se movieron 3 archivos a PS2"),
        Triple("12 files", "Copied 12 files to PS2", "Se copiaron 12 archivos a PS2"),
        Triple(
            "Zelda.gba",
            "Moved Zelda.gba to ps2 · It was saved under a new name, so your library will re-add it on the next scan.",
            "Se movió Zelda.gba a ps2 · Se guardó con un nombre nuevo, así que tu biblioteca lo volverá a agregar en el próximo escaneo.",
        ),
        Triple(
            "Track 2.bin",
            "Moved from Downloads to PS2 · That folder isn't one of your game sources, so it left your library. · Track 2.sbi stayed behind: it only moves under its disc's name",
            "Movido de Downloads a PS2 · Esa carpeta no es una de tus fuentes de juegos, así que salió de tu biblioteca. · Track 2.sbi se quedó atrás: solo se mueve con el nombre de su disco",
        ),
        Triple("Zelda.gba", "A file named Zelda.gba is already there.", "Ya hay un archivo llamado Zelda.gba."),
        Triple("game.zip", "Extract → Roms", "Extraer → Roms"),
    )

    /** (the stopped card's sentence, in Spanish). */
    private val stopped = listOf(
        "Nothing to delete" to "No hay nada que eliminar",
        "Nothing was changed." to "No se cambió nada.",
        "Nothing was moved. Everything is still where it was." to "No se movió nada. Todo sigue donde estaba.",
        "Nothing was copied. The unfinished copy was removed." to "No se copió nada. Se eliminó la copia sin terminar.",
        "Too late to stop: everything was already moved to gba." to "Demasiado tarde para detenerlo: todo ya se había movido a gba.",
        "Too late to stop: everything was already copied to gba." to "Demasiado tarde para detenerlo: todo ya se había copiado a gba.",
        "Too late to stop: everything you picked was already moved to gba. The 1 file that goes with it is still where it was." to
            "Demasiado tarde para detenerlo: todo lo que elegiste ya se había movido a gba. El 1 archivo que lo acompaña sigue donde estaba.",
        "Too late to stop: everything you picked was already copied to gba. The 12 files that go with it are still where they were." to
            "Demasiado tarde para detenerlo: todo lo que elegiste ya se había copiado a gba. Los 12 archivos que lo acompañan siguen donde estaban.",
        "834 of 900 files were moved to gba and kept. The rest are still where they were." to
            "Se movieron 834 de 900 archivos a gba y se conservaron. El resto sigue donde estaba.",
        "3 of 8 files were copied to gba and kept. The rest were not copied." to
            "Se copiaron 3 de 8 archivos a gba y se conservaron. El resto no se copió.",
        "Too late to stop: everything was already extracted." to "Demasiado tarde para detenerlo: todo ya se había extraído.",
        "Nothing was extracted." to "No se extrajo nada.",
    )

    private val notes = listOf(
        "The disc it lists stays." to "El disco de su lista se queda.",
        "The 12 discs they list stay." to "Los 12 discos de sus listas se quedan.",
        "Zelda.sbi stayed behind: its cue lists it under that name" to "Zelda.sbi se quedó atrás: su cue lo lista con ese nombre",
        "The file is back. Your library will re-add the game the next time it scans." to
            "El archivo volvió. Tu biblioteca volverá a agregar el juego en el próximo escaneo.",
        "Your library doesn't list it as that platform's game any more." to "Tu biblioteca ya no lo lista como un juego de esa plataforma.",
        "Mimir couldn't update your library — it'll catch up next time it scans." to
            "Mimir no pudo actualizar tu biblioteca; se pondrá al día en el próximo escaneo.",
    )

    @Test
    fun `in English every sentence the store writes is drawn word for word as written`() {
        cards.forEach { (title, line, _) -> assertEquals(line, fileBrowserQueueLineText(english, line, title)) }
        (stopped + notes).forEach { (line, _) -> assertEquals(line, fileBrowserQueueLineText(english, line)) }
        listOf("Zelda.gba", "3 files", "12 files").forEach { assertEquals(it, fileBrowserQueueTitleText(english, it)) }
    }

    @Test
    fun `in Spanish a card's second line is one sentence per frame with its names and counts in the slots`() {
        cards.forEach { (title, line, expected) -> assertEquals(line, expected, fileBrowserQueueLineText(spanish, line, title)) }
    }

    @Test
    fun `a stopped card's sentence and the notes read in Spanish`() {
        (stopped + notes).forEach { (line, expected) -> assertEquals(line, expected, fileBrowserQueueLineText(spanish, line)) }
    }

    @Test
    fun `a title that is a count is a plural and any other title is the player's own name`() {
        assertEquals("3 archivos", fileBrowserQueueTitleText(spanish, "3 files"))
        assertEquals("120 archivos", fileBrowserQueueTitleText(spanish, "120 files"))
        assertEquals("Zelda.gba", fileBrowserQueueTitleText(spanish, "Zelda.gba"))
        assertEquals("Pokémon Esmeralda.gba", fileBrowserQueueTitleText(spanish, "Pokémon Esmeralda.gba"))
    }

    @Test
    fun `a sentence this module does not know is drawn as written, never lost`() {
        listOf(
            "Password-protected. Mimir can't open archives that need a password.",
            "Stopped. 3 files already placed couldn’t be removed and were left in Roms.",
            "Mimir couldn't finish this.",
            "",
        ).forEach { assertEquals(it, fileBrowserQueueLineText(spanish, it, "game.zip")) }
        assertEquals(
            "a known line keeps an unknown note as written",
            "Se movió Zelda.gba a ps2 · Something new the store says",
            fileBrowserQueueLineText(spanish, "Moved Zelda.gba to ps2 · Something new the store says", "Zelda.gba"),
        )
    }

    @Test
    fun `a note is never swallowed into the destination's name`() {
        val line = "Moved Zelda.gba to ps2 · Zelda.sbi kept its old name"
        val text = fileBrowserQueueLineText(spanish, line, "Zelda.gba")
        assertEquals("Se movió Zelda.gba a ps2 · Zelda.sbi conservó su nombre anterior", text)
        assertNotEquals(fileBrowserQueueLineText(english, line), text)
    }

    @Test
    fun `counts are written the way the host's language writes them and English keeps its comma`() {
        assertEquals(
            "1,234 of 2,000 files were moved to gba and kept. The rest are still where they were.",
            fileBrowserQueueLineText(english, "1,234 of 2,000 files were moved to gba and kept. The rest are still where they were."),
        )
        val spanishText = fileBrowserQueueLineText(spanish, "1,234 of 2,000 files were moved to gba and kept. The rest are still where they were.")
        assertEquals(true, spanishText.startsWith("Se movieron 1"))
        assertEquals(true, spanishText.contains("234 de 2") && spanishText.contains("000 archivos"))
    }
}
