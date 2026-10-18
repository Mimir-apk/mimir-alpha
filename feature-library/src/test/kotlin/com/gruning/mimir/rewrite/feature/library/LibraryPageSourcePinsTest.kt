package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library page's small fixes that are decided by where a call SITS rather than by a number, pinned the module's way (source text):
 *  - RC-46: the hero's URIs are resolved in a `remember`, not on every recomposition of a focus step;
 *  - W1A #3: the touch path that opens the Add picker lands on the picker's safe key, like the controller path;
 *  - L-11a (P3): a segment chip's animated ink is read in the one composable that paints it, not in the strip that holds every chip.
 */
class LibraryPageSourcePinsTest {

    private fun screen(): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    @Test
    fun `RC-46 the hero and clip uris are resolved once per focused game and media, never per recomposition`() {
        val code = screen()
        assertEquals("one call site for the still", 1, Regex("""libraryHeroBackdropUri\(""").findAll(code).count())
        assertEquals("one call site for the clip", 1, Regex("""libraryHeroMotionUri\(""").findAll(code).count())
        val hero = code.substringAfter("val liveBackdropHero = remember(").substringBefore("val switchHoldsBackdrop")
        assertTrue("the still's resolver sits inside the remember block", Regex("""\)\s*\{\s*libraryHeroBackdropUri\(""").containsMatchIn(hero))
        assertTrue("keyed on the focused game, the mode, the resolver and the media apply", hero.contains("backdropGame?.gameId,") && hero.contains("backdropMode,") && hero.contains("heroArtUriForGame,") && hero.contains("cartridgeArtUriForGame,"))
        val motion = code.substringAfter("val liveBackdropMotion = remember(").substringBefore("if (!switchHoldsBackdrop) backdropMemory.motion")
        assertTrue("the clip's resolver sits inside the remember block", Regex("""\)\s*\{\s*libraryHeroMotionUri\(""").containsMatchIn(motion))
        assertTrue("keyed likewise", motion.contains("backdropGame?.gameId,") && motion.contains("heroMotionUriForGame,") && motion.contains("cartridgeArtUriForGame,"))
        assertTrue("and the video backdrop still reads the clip after its resolver", code.indexOf("libraryHeroMotionUri(") < code.indexOf("VideoBackdrop("))
    }

    @Test
    fun `W1A 3 the touch path that opens the Add picker stamps the picker's safe key before the push`() {
        val code = screen()
        assertTrue(code.contains("pushScopeLandingOn(authority, NativeAddPickerScope, NativeAddPickerSearchKey)"))
        assertFalse("no bare push into the picker remains", Regex("""\bpushScope\(\s*NativeAddPickerScope""").containsMatchIn(code))
        assertFalse(Regex("""authority\.pushScope\(NativeAddPickerScope""").containsMatchIn(code))
        // The landing comes after the open request, so the picker is already asked for when its scope takes input.
        val site = code.substringAfter("onOpenNativeAddPicker(shelfMode)")
        assertTrue(site.take(400).contains("pushScopeLandingOn(authority, NativeAddPickerScope, NativeAddPickerSearchKey)"))
    }

    @Test
    fun `L-11a the segment chip's ink is read where it is painted, so the strip does not recompose every chip every frame`() {
        val code = screen()
        val strip = code.substringAfter("private fun FavoritesSegmentStrip(").substringBefore("private fun FavoritesSegmentLabel(")
        assertFalse("the strip reads no animated colour", strip.contains("animateColorAsState") || strip.contains("labelColor"))
        assertTrue("it hands the segment, the count and the selection to the label", strip.contains("FavoritesSegmentLabel(segment = segment, count = segmentCounts[segment] ?: 0, selected = selected)"))
        val label = code.substringAfter("private fun FavoritesSegmentLabel(").substringBefore("@Composable\nprivate fun NativeFavoritesRowDetailView(")
        assertTrue("the label owns the animation", label.contains("val labelColor by animateColorAsState("))
        assertTrue("on the focus clock", label.contains("tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)"))
        assertTrue("and reads it where it paints the words", label.contains("color = labelColor,"))
    }
}
