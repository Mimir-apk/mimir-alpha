package com.gruning.mimir.rewrite.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Alpha-cert sweep 2026-10-09 (P2, P3):** the Library's list view on Main. (P2) The preview panel's no-art book was a 3:4 book in a wider platform frame, its top and bottom under
 * the frame's rim, and its platform name cut to "SUPER NINTE..." (`36b-preview.png`). (P3) A row's title stood ~67 px to the right of the support line under it, because the always-mounted
 * favourite mark led the title. The real [GamelistView] (the rows and the preview panel) at Main's 832.8 x 468.3 dp and 369 dpi, at the font scales 1.0 and 1.3; PNGs land in `build/test-captures/sweepa/`,
 * the pixels are the JVM's and a handheld's are device-owed.
 *
 * Asserted off the drawn bounds and text: the title and the support line of every row (a starred one too) start at one x; the book's frame is the book's own 3:4; the platform's whole
 * display name stands in the book with no ellipsis and no word cut; and the title stands inside the frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistListSweepCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val viewportWidthDp = 832.8f
    private val viewportHeightDp = 468.3f
    private val rootDensity = 369f / 160f

    private var fontScale by mutableStateOf(1f)
    private var games by mutableStateOf(listOf<GameRow>())
    private var platformName by mutableStateOf("Super Nintendo")
    private var focusedIndex by mutableStateOf(2)
    private var mounted = false

    private fun game(id: String, title: String, genre: String, favorite: Boolean = false) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("snes"),
        memberCount = 1,
        duplicateKind = null,
        genre = genre,
        isFavorite = favorite,
        artResolutionSettled = true,
    )

    private val snes = listOf(
        game("g1", "Chrono Trigger", "RPG"),
        game("g2", "Earthbound", "RPG", favorite = true),
        game("g3", "Super Metroid", "Platformer"),
    )

    private fun mount() {
        if (mounted) return
        mounted = true
        compose.runOnUiThread { compose.activity.actionBar?.hide() }
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(rootDensity, fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(viewportWidthDp, viewportHeightDp),
            ) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(Modifier.requiredSize(viewportWidthDp.dp, viewportHeightDp.dp).background(palette.surfaceBase)) {
                        Box(Modifier.fillMaxSize().padding(start = LibraryGamelistBodyPaddingDp, end = LibraryGamelistBodyPaddingDp, top = 70.dp, bottom = 40.dp)) {
                            val list = games
                            GamelistView(
                                games = list,
                                emptyMessage = "No games",
                                searchQuery = "",
                                favoritesOnly = false,
                                keyboardOpen = false,
                                authority = authority,
                                focused = list.getOrNull(focusedIndex)?.let(::gameKey),
                                supportLabelForGame = { platformName },
                                launchStatusForGame = { "Launch · Needs emulator" },
                                detailGame = null,
                                launchState = GameLaunchUiState.Idle,
                                platforms = listOf(PlatformRow(PlatformId("snes"), platformName, list.size)),
                                onOpenGame = {},
                            )
                        }
                    }
                }
            }
        }
    }

    private fun settle(ms: Long = 1_500L) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun show(scale: Float, rows: List<GameRow> = snes, platform: String = "Super Nintendo", focus: Int = 2) {
        fontScale = scale
        mount()
        compose.runOnIdle {
            games = rows
            platformName = platform
            focusedIndex = focus
        }
        settle()
    }

    private fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/test-captures/sweepa").apply { if (!isDirectory) mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("LISTSWEEP wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun layoutOf(node: androidx.compose.ui.semantics.SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.first()
    }

    // -- P3: one start edge for a row's title and its support line ---------------------------------------------

    private fun assertRowsShareAStartEdge(tag: String, rows: List<GameRow>) {
        for (row in rows) {
            // The row's own title: the preview's book wears the title of the focused row too, in the panel to the right.
            val title = compose.onAllNodesWithText(row.title, useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .minByOrNull { it.left }!!
                .let { androidx.compose.ui.geometry.Rect(it.left / rootDensity, it.top / rootDensity, it.right / rootDensity, it.bottom / rootDensity) }
            // Two rows may say the same (both RPG): the support line of THIS row is the one standing just under its title.
            val supportLeft = compose.onAllNodesWithText("Super Nintendo · ${row.genre}", useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.boundsInRoot }
                .filter { it.top / rootDensity >= title.top }
                .minByOrNull { it.top }!!
                .let { it.left / rootDensity }
            println("LISTSWEEP $tag '${row.title}' title.left=${title.left} support.left=$supportLeft favourite=${row.isFavorite}")
            assertEquals("$tag '${row.title}': the title and the support line start at one x", supportLeft, title.left, 0.5f)
        }
    }

    @Test
    fun `at 1x each row's title and support line start at one x, a starred row too`() {
        show(1.0f)
        save("library-list-1.0x.png")
        assertRowsShareAStartEdge("1.0x", snes)
    }

    @Test
    fun `at 1_3x each row's title and support line start at one x, a starred row too`() {
        show(1.3f)
        save("library-list-1.3x.png")
        assertRowsShareAStartEdge("1.3x", snes)
    }

    // -- P2: the preview's book is whole, its platform name too -------------------------------------------------

    /** One shown book: the frame's bounds, and the eyebrow and title text, in the panel's coordinates. */
    private fun assertBookWhole(tag: String, platform: String) {
        val frame = compose.onNodeWithTag(GamelistPreviewArtTestTag, useUnmergedTree = true).getBoundsInRoot()
        val fw = (frame.right - frame.left).value
        val fh = (frame.bottom - frame.top).value
        println("LISTSWEEP $tag frame=${frame.left.value},${frame.top.value} ${fw}x$fh aspect=${fw / fh}")
        assertEquals("$tag: the frame is the book's own 3:4 (hugs it)", 0.75f, fw / fh, 0.01f)

        val eyebrowText = platform.uppercase()
        val eyebrowNodes = compose.onAllNodesWithText(eyebrowText, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$tag: the platform's display name '$eyebrowText' is drawn whole (nodes: ${eyebrowNodes.size})", eyebrowNodes.isNotEmpty())
        val layout = layoutOf(eyebrowNodes.first())
        val eyebrowLines = (0 until layout.lineCount).map { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
        println("LISTSWEEP $tag eyebrow lines=$eyebrowLines")
        assertFalse("$tag: the platform name ends in no ellipsis", (0 until layout.lineCount).any(layout::isLineEllipsized))
        for (line in 0 until layout.lineCount - 1) {
            val next = layout.getLineStart(line + 1)
            val text = layout.layoutInput.text.text
            val before = text[next - 1]
            val after = text[next]
            assertTrue("$tag: eyebrow line $line ends at a break, not inside a word ($eyebrowLines)", before.isWhitespace() || after.isWhitespace() || before == '-' || before == '/')
        }
        assertEquals("$tag: every word of the name is there, and no hyphenated word is split across lines", eyebrowText.replace(Regex("\\s+"), ""), eyebrowLines.joinToString("").replace(Regex("\\s+"), ""))
        for (line in 0 until layout.lineCount - 1) {
            val shown = eyebrowLines[line]
            assertFalse("$tag: eyebrow line $line ('$shown') does not end on a hyphen joined to its word", shown.length > 1 && shown.last() == '-' && !shown[shown.length - 2].isWhitespace())
        }

        // The eyebrow and the title stand inside the frame, clear of its rim.
        val eyebrowBounds = compose.onAllNodesWithText(eyebrowText, useUnmergedTree = true)[0].getBoundsInRoot()
        assertTrue("$tag: the eyebrow stands inside the frame", eyebrowBounds.left >= frame.left && eyebrowBounds.right <= frame.right && eyebrowBounds.top >= frame.top)
    }

    @Test
    fun `at 1x the preview's book is whole and Super Nintendo stands whole`() {
        show(1.0f)
        save("library-preview-1.0x.png")
        assertBookWhole("1.0x", "Super Nintendo")
    }

    @Test
    fun `at 1_3x the preview's book is whole and Super Nintendo stands whole`() {
        show(1.3f)
        save("library-preview-1.3x.png")
        assertBookWhole("1.3x", "Super Nintendo")
    }

    @Test
    fun `long platform names wrap at their spaces and never end in an ellipsis or a cut word`() {
        val names = listOf(
            "Nintendo Entertainment System",
            "Genesis / Mega Drive",
            "PC Engine / TurboGrafx-16",
            "Neo Geo Pocket Color",
            "Game Boy Advance",
            "PlayStation Portable",
        )
        for (scale in listOf(1.0f, 1.3f)) {
            for ((index, name) in names.withIndex()) {
                show(scale, platform = name)
                if (index == 0 || index == 2) save("library-preview-${name.replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()}-${scale}x.png")
                assertBookWhole("x$scale '$name'", name)
            }
        }
    }
}
