package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RC-37.
 *
 * The mechanism is unchanged: the `LazyColumn` clips exactly on its scrolling axis, so whatever a focused row
 * paints outside its bounds is cut at the list's first and last row unless the content padding clears it.
 * Since the ring migration  the row's treatment is the one selection ring, which paints
 * [MimirFocusTileRingReach] outside the row -- the 1 dp lift and 8 dp shadow this file used to derive from are
 * gone -- so the reach is read from the family and the padding from the reach, never a nudge.
 *
 * Render behaviour is not JVM-observable and this module has no Compose test harness, so the arithmetic is
 * pinned directly and the wiring is characterised from source text.
 */
class GamelistListFocusClearanceTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `the reach is the ring's own, read from the family, not a number someone chose`() {
        assertEquals(MimirFocusTileRingReach, LibraryListRowFocusOutwardReach)
        assertEquals(12.dp, LibraryListRowCorner)
    }

    @Test
    fun `both clipped edges get the reach, and the existing inset is honoured rather than added to`() {
        val ltr = LayoutDirection.Ltr
        val reach = MimirFocusTileRingReach.value

        val noInset = libraryListContentPadding(0.dp)
        assertEquals("top must clear the focused first row", reach, noInset.calculateTopPadding().value, 0f)
        assertEquals("bottom must clear the focused LAST row too", reach, noInset.calculateBottomPadding().value, 0f)

        val withBand = libraryListContentPadding(96.dp)
        assertEquals("an inset taller than the reach is kept, never stacked", 96f, withBand.calculateBottomPadding().value, 0f)
        assertEquals("the top is unaffected by the bottom inset", reach, withBand.calculateTopPadding().value, 0f)
        assertEquals("a small inset is floored to the reach", reach, libraryListContentPadding(3.dp).calculateBottomPadding().value, 0f)

        assertEquals("horizontal padding is not this fix's business", 0f, noInset.calculateLeftPadding(ltr).value, 0f)
        assertEquals("horizontal padding is not this fix's business", 0f, noInset.calculateRightPadding(ltr).value, 0f)
    }

    /**
     * The row wears the ring and nothing else that reaches: no lift, no shadow, no scale. Differential: give the
     * row back an `offset` or a `mimirSurfaceShadow` and this fails, because the padding no longer describes it.
     */
    @Test
    fun `the gamelist row's only reach is the ring`() {
        val row = sourceOf("LibraryComponents.kt").substringAfter("\nfun GamelistRow(")
        assertTrue("search window missed GamelistRow's body", row.contains("mimirFocusable(authority, key, scope") && row.length > 1500)
        assertTrue(row.contains("shape = MimirRingShape.Row(LibraryListRowCorner)"))
        assertTrue("a game row gleams as the family's game rows do", row.contains("gleam = MimirRingGleam.Game"))
        assertTrue("the ring paints the leather, so the wash lands over it", row.contains("fill = rowFill"))
        assertTrue("the ring comes before the clip", row.indexOf(".mimirSelectionRing(") < row.indexOf(".clip(shape)"))
        assertFalse(row.contains(".offset(y = if (focused)"))
        assertFalse(row.contains(".mimirSurfaceShadow("))
        assertFalse(row.contains("mimirFocusRing"))
        assertFalse(row.contains("focusedScale"))
    }

    @Test
    fun `both library list views take their content padding from the derived reach`() {
        val code = librarySource()
        assertEquals(
            "GamelistView's and NativeFavoritesRowDetailView's LazyColumns carry ringed rows; both take the derived padding.",
            2,
            Regex("""libraryListContentPadding\(""").findAll(code).count(),
        )
        val favourite = code.substringAfter("private fun NativeFavoriteRow(").substringBefore("private fun NativeFavoriteIcon(")
        assertTrue(favourite.contains("shape = MimirRingShape.Row(LibraryListRowCorner)"))
        assertFalse(favourite.contains(".offset(y = if (focused)"))
        assertFalse(favourite.contains(".mimirSurfaceShadow("))
    }

    @Test
    fun `no library list row restates the lift or the elevation, and the rows sit the family's floor apart`() {
        val code = librarySource()
        assertFalse(code.contains("LibraryListRowFocusLift"))
        assertFalse(code.contains("LibraryListRowFocusElevation"))
        assertEquals(0, Regex("""offset\(y = if \(focused\)""").findAll(code).count())
        assertEquals("a 7 dp gap left neighbours inside a 14 dp reach at 0 gap; the floor is sm", 0, Regex("""spacedBy\(7\.dp\)""").findAll(code).count())
    }

    private fun librarySource(): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        ).first(File::isFile)
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }
}
