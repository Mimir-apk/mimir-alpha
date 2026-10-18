package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 *  Every "+" makes a NEW Tome: the store no longer hands back an existing empty Book
 * (which made the second "+" only jump to the first). Main's Tome creation is a different store (HomeTomeStore) and is not touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CompanionOrderStoreCreateTomeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("mimir_companion_shelf", Context.MODE_PRIVATE).edit().clear().commit()
        CompanionOrderStore.load(app)
    }

    @Test
    fun `creating twice makes two Tomes, both in the order, and the second is not the first`() {
        val tiles = listOf("a", "b", "c")
        val first = CompanionOrderStore.createFolder(app, tiles)
        val second = CompanionOrderStore.createFolder(app, tiles + first)
        assertNotEquals("the second + made a new Tome", first, second)
        val folders = CompanionOrderStore.folders.value
        assertEquals(listOf(first, second), folders.map { it.id })
        assertTrue("both are empty and unrenamed", folders.all { it.children.isEmpty() && it.name == DefaultFolderName })
        assertEquals("both sit after the tiles, in the order made", listOf("a", "b", "c", first, second), CompanionOrderStore.order.value)
        assertEquals("the cursor is on the newest, so the view reveals it", second, CompanionOrderStore.cursor.value)
    }

    @Test
    fun `discarding one of several Tomes leaves the others`() {
        val first = CompanionOrderStore.createFolder(app, listOf("a"))
        val second = CompanionOrderStore.createFolder(app, listOf("a", first))
        CompanionOrderStore.discardFolder(app, listOf("a", first, second), first)
        assertEquals(listOf(second), CompanionOrderStore.folders.value.map { it.id })
        assertEquals(listOf("a", second), CompanionOrderStore.order.value)
    }

    @Test
    fun `the store keeps no reuse of an empty Book`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionOrderStore.kt").readText()
        val create = source.substringAfter("fun createFolder(").substringBefore("fun discardFolder")
        assertTrue("no lookup of an existing empty default-named Tome", !create.contains("firstOrNull") && !create.contains("children.isEmpty()"))
    }
}
