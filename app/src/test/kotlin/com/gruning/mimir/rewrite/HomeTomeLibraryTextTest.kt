package com.gruning.mimir.rewrite

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Tome Library's own words (localization step 3): the header's eyebrow and status, the create cell, the footer's controller legend,
 * and the Tome name editor's status, legend, keyboard target and failures. English is the default folder and reads as it always did,
 * in sentence case; Spanish is Latin American (`es-419`), with the glossary's Tomo, Biblioteca de Tomos and Inicio.
 * Controller tokens (A, B, X, Y) stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeTomeLibraryTextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val english: Resources = context.resources
    private val spanish: Resources = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("es-419")) },
    ).resources

    @Test
    fun `the header's eyebrow and create cell read in both languages`() {
        assertEquals("· Tome Library", english.getString(R.string.app_tome_library_eyebrow))
        assertEquals("· Biblioteca de Tomos", spanish.getString(R.string.app_tome_library_eyebrow))
        // The create cell keeps its two spaces after the plus, in both languages.
        assertEquals("+  Create Tome", english.getString(R.string.app_tome_library_create_action))
        assertEquals("+  Crear Tomo", spanish.getString(R.string.app_tome_library_create_action))
        assertEquals("Home shelf", english.getString(R.string.app_tome_home_shelf_title))
        assertEquals("Estante de Inicio", spanish.getString(R.string.app_tome_home_shelf_title))
    }

    @Test
    fun `every header status reads in both languages and none is stored in caps`() {
        val english9 = HomeTomeLibraryHeaderStatusCandidates.map { english.getString(it) }
        val spanish9 = HomeTomeLibraryHeaderStatusCandidates.map { spanish.getString(it) }
        assertEquals(
            listOf("Update failed", "Pinned to Home", "Unpinned from Home", "Home shelf", "Pinned", "Custom Tomes", "Platforms", "Create", "Tomes"),
            english9,
        )
        assertEquals(
            listOf("No se actualizó", "Fijado en Inicio", "Quitado de Inicio", "Estante de Inicio", "Fijados", "Tomos propios", "Plataformas", "Crear", "Tomos"),
            spanish9,
        )
        (english9 + spanish9).forEach { assertTrue(it, it != it.uppercase()) }
        assertEquals("a status is reserved once", HomeTomeLibraryHeaderStatusCandidates.size, HomeTomeLibraryHeaderStatusCandidates.toSet().size)
    }

    @Test
    fun `the footer legend is one whole line per case, with its tokens kept`() {
        assertEquals("A Open · B Back", english.getString(R.string.app_tome_library_hint_home))
        assertEquals("A Open · X Pin · Y Edit · B Back", english.getString(R.string.app_tome_library_hint_pin_edit))
        assertEquals("A Open · X Unpin · Y Edit · B Back", english.getString(R.string.app_tome_library_hint_unpin_edit))
        assertEquals("A Open · X Pin · Y Create · B Back", english.getString(R.string.app_tome_library_hint_pin_create))
        assertEquals("A Open · X Unpin · Y Create · B Back", english.getString(R.string.app_tome_library_hint_unpin_create))
        assertEquals("A Create · B Back", english.getString(R.string.app_tome_library_hint_create))
        assertEquals("A Abrir · B Atrás", spanish.getString(R.string.app_tome_library_hint_home))
        assertEquals("A Abrir · X Fijar · Y Editar · B Atrás", spanish.getString(R.string.app_tome_library_hint_pin_edit))
        assertEquals("A Abrir · X Desfijar · Y Editar · B Atrás", spanish.getString(R.string.app_tome_library_hint_unpin_edit))
        assertEquals("A Abrir · X Fijar · Y Crear · B Atrás", spanish.getString(R.string.app_tome_library_hint_pin_create))
        assertEquals("A Abrir · X Desfijar · Y Crear · B Atrás", spanish.getString(R.string.app_tome_library_hint_unpin_create))
        assertEquals("A Crear · B Atrás", spanish.getString(R.string.app_tome_library_hint_create))
    }

    @Test
    fun `the editor keeps its body status separate from its controller legend`() {
        assertEquals(
            HomeTomeEditorCopy("Delete this Tome?", "A Confirm · B Cancel"),
            homeTomeEditorCopy(english, null, deletePending = false, confirmDelete = true, editing = true),
        )
        assertEquals(
            HomeTomeEditorCopy("Up to $HomeTomeLabelMax characters", "Y Delete · B Cancel"),
            homeTomeEditorCopy(english, null, deletePending = false, confirmDelete = false, editing = true),
        )
        assertEquals(
            HomeTomeEditorCopy("Up to $HomeTomeLabelMax characters", "B Cancel"),
            homeTomeEditorCopy(english, null, deletePending = false, confirmDelete = false, editing = false),
        )
        assertEquals(
            HomeTomeEditorCopy("Deleting Tome…", null),
            homeTomeEditorCopy(english, null, deletePending = true, confirmDelete = false, editing = true),
        )
    }

    @Test
    fun `the editor speaks Spanish and keeps its tokens`() {
        assertEquals(
            HomeTomeEditorCopy("¿Eliminar este Tomo?", "A Confirmar · B Cancelar"),
            homeTomeEditorCopy(spanish, null, deletePending = false, confirmDelete = true, editing = true),
        )
        assertEquals(
            HomeTomeEditorCopy("Hasta $HomeTomeLabelMax caracteres", "Y Eliminar · B Cancelar"),
            homeTomeEditorCopy(spanish, null, deletePending = false, confirmDelete = false, editing = true),
        )
        assertEquals(
            HomeTomeEditorCopy("Hasta $HomeTomeLabelMax caracteres", "B Cancelar"),
            homeTomeEditorCopy(spanish, null, deletePending = false, confirmDelete = false, editing = false),
        )
        assertEquals("Eliminando Tomo…", homeTomeEditorCopy(spanish, null, deletePending = true, confirmDelete = false, editing = true).status)
        assertEquals("Editar Tomo", spanish.getString(R.string.app_tome_editor_target_edit))
        assertEquals("Nuevo Tomo", spanish.getString(R.string.app_tome_editor_target_new))
        assertEquals("Edit Tome", english.getString(R.string.app_tome_editor_target_edit))
        assertEquals("New Tome", english.getString(R.string.app_tome_editor_target_new))
    }

    @Test
    fun `the status and legend together are one format, so a language can order them`() {
        assertEquals("Delete this Tome? · A Confirm · B Cancel", english.getString(R.string.app_tome_editor_status_with_legend, "Delete this Tome?", "A Confirm · B Cancel"))
        assertEquals("¿Eliminar este Tomo? · B Cancelar", spanish.getString(R.string.app_tome_editor_status_with_legend, "¿Eliminar este Tomo?", "B Cancelar"))
    }

    @Test
    fun `a failure the store hands over in English is resolved to the player's language`() {
        assertEquals("Couldn’t save Tome", HomeTomeEditorStoredText.messageText(english, "Couldn’t save Tome"))
        assertEquals("No se pudo guardar el Tomo", HomeTomeEditorStoredText.messageText(spanish, "Couldn’t save Tome"))
        assertEquals("No se pudo eliminar el Tomo", HomeTomeEditorStoredText.messageText(spanish, "Couldn’t delete Tome"))
        assertEquals("Este Tomo ya no está disponible", HomeTomeEditorStoredText.messageText(spanish, "This Tome is no longer available"))
        assertEquals("Ya hay un Tomo con ese nombre", HomeTomeEditorStoredText.messageText(spanish, "That Tome name is already in use"))
        // The name rules come from the store's own check, with the limit in them.
        val empty = requireNotNull(homeTomeLabelError("", emptyList()))
        val long = requireNotNull(homeTomeLabelError("x".repeat(HomeTomeLabelMax + 1), emptyList()))
        assertEquals("Name a Tome first", HomeTomeEditorStoredText.messageText(english, empty))
        assertEquals("Primero ponle nombre al Tomo", HomeTomeEditorStoredText.messageText(spanish, empty))
        assertEquals("Keep it under $HomeTomeLabelMax characters", HomeTomeEditorStoredText.messageText(english, long))
        assertEquals("Usa $HomeTomeLabelMax caracteres como máximo", HomeTomeEditorStoredText.messageText(spanish, long))
        // A sentence it does not know is shown as it came.
        assertEquals("Disk full", HomeTomeEditorStoredText.messageText(spanish, "Disk full"))
    }

    @Test
    fun `every stored failure equals its English resource and is still what the code writes`() {
        HomeTomeEditorStoredText.messages.forEach { entry ->
            assertEquals(entry.stored, english.getString(entry.textRes, HomeTomeLabelMax))
        }
        // The producers still write the sentences the editor keys on (the two name rules are built by homeTomeLabelError itself).
        val producers = listOf("HomeCollectionStore.kt", "HomeShelfCoordinator.kt", "MimirAppCoordinator.kt").joinToString("\n") { sourceFile(it) }
        listOf("Couldn’t save Tome", "Couldn’t delete Tome", "This Tome is no longer available", "That Tome name is already in use").forEach { sentence ->
            assertTrue("no code writes \"$sentence\" any more", producers.contains("\"$sentence\""))
        }
        val keys = HomeTomeEditorStoredText.messages.map { it.stored }
        assertEquals("no two failures share a key", keys.size, keys.toSet().size)
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
