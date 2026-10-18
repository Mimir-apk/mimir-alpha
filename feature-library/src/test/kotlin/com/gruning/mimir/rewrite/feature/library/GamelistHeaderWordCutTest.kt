package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The gamelist header on the RP5's 700 dp (2026-10-11): "Game Bo..." and "LOCAL LI..." were cuts INSIDE words, and the tag was cut with
 * the title. The header's title and count are shortened at a word like every other line (ellipsizeAtWord), the "Local library" tag is
 * measured first and is never cut, and a title that cannot fit beside it is what gives way. On the RP5 the title takes what the count
 * and Search leave, so a short name stands whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistHeaderWordCutTest {

    @get:Rule
    val compose = createComposeRule()

    private val authority = DefaultFocusAuthority()
    private val layouts = mutableMapOf<String, TextLayoutResult>()

    private fun mount(title: String, meta: String = "8 games", widthDp: Float = 700f) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, 350f),
                LocalMimirTextLayoutObserver provides { spoken, layout -> layouts[spoken] = layout },
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, 200.dp)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md)) {
                            GamelistHeader(
                                title = title,
                                metaLabel = meta,
                                showSearch = true,
                                searchQuery = "",
                                searchPlaceholder = "Search games",
                                authority = authority,
                                searchControllerEnabled = true,
                                searchFocused = false,
                                onOpenSearch = {},
                                onClearSearch = {},
                                segments = emptyList(),
                                segmentCounts = emptyMap(),
                                activeSegment = FavoriteSegment.GAMES,
                                onSelectSegment = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** What a text node of the header shows (the words it is set in, shortened or whole), by what it begins with. */
    private fun shown(startsWith: String): String =
        compose.onAllNodes(hasText(startsWith, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .first { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text }.startsWith(startsWith) }
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun assertWholeWords(full: String, shown: String, label: String) {
        if (shown == full) return
        assertTrue("$label is shortened with an ellipsis: <$shown>", shown.endsWith("…"))
        val head = shown.removeSuffix("…")
        assertTrue("$label: <$head> is the start of <$full>", full.startsWith(head))
        assertTrue("$label is cut between two words, not inside one: <$shown>", full[head.length].isWhitespace())
    }

    private fun assertTagWhole(label: String) {
        val layout = layouts["Local library"]
        assertNotNull("$label: the tag drew", layout)
        assertFalse("$label: the tag is never cut", layout!!.isLineEllipsized(0) || layout.hasVisualOverflow)
        val tag = compose.onNodeWithTag(GamelistLocalLibraryTagTestTag).fetchSemanticsNode().boundsInRoot
        assertTrue("$label: the tag is as wide as its words (${tag.width} >= ${layout.size.width})", tag.width >= layout.size.width)
    }

    @Test
    fun `on the RP5 a three-word name gives way at a word, never inside one, and the tag is whole`() {
        mount("Game Boy Advance")
        assertWholeWords("Game Boy Advance", shown("Game"), "the RP5's title")
        assertTagWhole("RP5, short name")
    }

    @Test
    fun `on a wider screen the same name stands whole beside the tag`() {
        mount("Game Boy Advance", widthDp = 833f)
        assertEquals("Game Boy Advance", shown("Game"))
        assertTagWhole("Odin, short name")
    }

    @Test
    fun `a count that fits is shown whole and one that does not is not cut mid-word`() {
        mount("Game Boy Advance", meta = "Favorites · 10 games", widthDp = 833f)
        assertEquals("Favorites · 10 games", shown("Favorites ·"))
    }

    @Test
    fun `a long name gives way at a word and the tag is never cut`() {
        val title = "Sony PlayStation Portable Collection Extended Edition Remastered"
        mount(title)
        assertWholeWords(title, shown("Sony"), "the title")
        assertTagWhole("RP5, long name")
    }

    @Test
    fun `a long count is shortened at a word`() {
        val meta = "Favorites · 1,234 games of every kind"
        mount("Favorites", meta = meta)
        assertWholeWords(meta, shown("Favorites ·"), "the count")
    }

    @Test
    fun `the compact header keeps the tag whole as well`() {
        val title = "Sony PlayStation Portable Collection Extended Edition"
        mount(title, widthDp = 480f)
        assertWholeWords(title, shown("Sony"), "the compact title")
        assertTagWhole("compact")
    }
}
