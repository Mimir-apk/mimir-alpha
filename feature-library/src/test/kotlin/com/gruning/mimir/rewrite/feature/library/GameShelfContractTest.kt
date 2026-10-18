package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a second caller may rely on in [GameShelf], pinned.
 *
 * The shelf is handed a [com.gruning.mimir.rewrite.core.model.library.ShelfFace] -- the picture and its shape together -- and
 * paints it. It must never look at which of a row's fields happened to be set: that re-derivation (`tileIconUri == null`) is what
 * painted a square pick as a box before. The source pin below keeps the shelf's files free of every field a face is derived from;
 * the row carries the face the resolver chose and there is no derivation left to keep (`legacyShelfFace` is deleted).
 */
class GameShelfContractTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()

    @Test
    fun `the shelf reads only the face, never the fields a face is derived from`() {
        listOf("GameShelf.kt", "GameShelfModels.kt").forEach { name ->
            val code = source(name)
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//[^\n]*"""), "")
            listOf("tileIconUri", "faceUri", "artworkUri", "faceIsPhysicalMedia", "physicalMediaUri", "HomeShelfRow").forEach { field ->
                assertFalse("$name must not read $field: the shape travels with the face", code.contains(field))
            }
        }
    }

    @Test
    fun `the shelf names no Home identity of its own`() {
        val code = gameShelfStripBody().replace(Regex("""//[^\n]*"""), "")
        assertFalse("the scope is a parameter", code.contains("HomeShelfScope"))
        assertFalse("the Folio's hold is a parameter", code.contains("tomeFolioActive"))
    }

    @Test
    fun `the shelf reads the focus snapshot it is handed and collects none of its own`() {
        val code = featureLibrarySource("GameShelf.kt").replace(Regex("""//[^\n]*"""), "")
        assertFalse("a collector of its own can be a recomposition off the selection's", code.contains("collectAsState"))
        assertTrue("the snapshot is a parameter", gameShelfStripBody().contains("focusState: FocusState,"))
        assertTrue(
            "Home hands the shelf the snapshot it derived its selection from",
            gameShelfCallInHome(homeShelfScreenBody()).contains("focusState = focusState,"),
        )
    }

    @Test
    fun `the shelf is shown its items, geometry, epoch, motion and names once, through its state`() {
        val parameters = gameShelfStripBody().sliceUntil("\n) {")
        listOf("items:", "selectedKey:", "geometry:", "windowMemory:", "windowMemoryKey:", "surfaceEpoch:", "reducedMotion:", "ids:")
            .forEach { assertFalse("$it is read from the state, not given a second time beside it", parameters.contains(it)) }
        assertTrue("the state is the one thing it is shown", parameters.contains("state: GameShelfState,"))

        val home = homeShelfScreenBody()
        assertEquals("Home builds its shelf state once", 1, Regex("rememberGameShelfState\\(").findAll(home).count())
        val call = gameShelfCallInHome(home)
        listOf("items = shelfItems", "selectedKey =", "windowMemory", "surfaceEpoch =", "reducedMotion =").forEach {
            assertFalse("Home does not hand the shelf $it beside the state it built from them", call.contains(it))
        }
    }

    @Test
    fun `the shelf names no Home tag, key or label of its own, only the ids it is handed`() {
        val offenders = featureLibrarySource("GameShelf.kt").lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
            .filter { it.contains("\"home") || it.contains("\"Home") }
            // Home's own tags, which GameShelfIds.Home derives from and its pins read, are the only place the word is spelled.
            .filterNot { it.trimStart().startsWith("internal const val HomeShelf") }
            .filterNot { it.trimStart().startsWith("internal val HomeShelf") }
        assertTrue("the shelf spells Home only in Home's declared tags and keys: $offenders", offenders.isEmpty())
    }

    @Test
    fun `each surface's window memory is its own, so one surface cannot displace another's seat`() {
        val home = GameShelfWindowMemory()
        val library = GameShelfWindowMemory()
        home.record("home", 6)
        library.record("library", 2)
        assertEquals("Home still finds its window after a Library visit", 6, home.seed("home"))
        assertEquals(2, library.seed("library"))
    }

    @Test
    fun `a memory seeds only the state it recorded`() {
        val memory = GameShelfWindowMemory()
        assertNull("nothing recorded yet", memory.seed("home"))
        memory.record("home", 4)
        assertEquals(4, memory.seed("home"))
        assertNull("another state of the same surface starts afresh", memory.seed("pinned"))
    }
}
