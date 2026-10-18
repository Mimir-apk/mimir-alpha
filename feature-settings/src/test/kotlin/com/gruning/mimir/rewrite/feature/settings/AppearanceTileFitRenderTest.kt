package com.gruning.mimir.rewrite.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The appearance pickers' tiles at360dp and font1.3: the state word (DEFAULT, CURRENT, SAVED) stands in its own band UNDER a
 * mark's art and never on it; DEFAULT is whole on the Rune tile and on the Atmosphere photo; a name wider than its tile breaks at a syllable hyphen ("Atmo-" / "sphere") and never runs
 * past the tile. The real overlay with the real fonts and the Compose coin as the art; a PNG of each case lands in `MIMIR_RENDER_DIR` (default `build/test-captures/smallfit`) when that folder exists.
 *
 * The host ships no hyphenation patterns, so the syllable break of "Atmosphere" is the dictionary this test provides ([LocalAppearanceNameBreaks]); the handheld's is its own platform's.
 */
abstract class AppearanceTileFitRenderBase {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val scope = FocusScope("test/appearanceTiles")

    protected abstract val screen: String

    /** The tile "Atmosphere" is wider than on this screen, so it breaks (a 59 dp tile); on a wide tile it stands whole. */
    protected abstract val atmosphereBreaks: Boolean

    private val tiles = listOf(
        AppearancePickerTile.Default(FocusKey("test/tile/default"), "Atmosphere"),
        AppearancePickerTile.Saved(FocusKey("test/tile/a"), "a", "Your mark 1", "/none/a.png", current = true),
        AppearancePickerTile.Saved(FocusKey("test/tile/b"), "b", "Your mark 2", "/none/b.png", current = false),
        AppearancePickerTile.Saved(FocusKey("test/tile/c"), "c", "Your mark 3", "/none/c.png", current = false),
        AppearancePickerTile.Add(FocusKey("test/tile/add")),
    )

    private fun mount(scale: Float, artFillsTile: Boolean) {
        authority.pushScope(scope)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, scale),
                // The host's stand-in for the platform's hyphenator: where "Atmosphere" may break (At-mo-sphere, after the "mo").
                LocalAppearanceNameBreaks provides { word -> if (word == "Atmosphere") listOf(4) else null },
            ) {
                MimirTheme {
                    AppearanceImagePickerOverlay(
                        authority = authority,
                        scope = scope,
                        title = "ᛗ · TILES",
                        subtitle = "The tiles at this font",
                        tiles = tiles,
                        defaultIsCurrent = false,
                        initialFocusKey = tiles[1].key,
                        note = null,
                        onActivateTile = {},
                        onRemoveTile = {},
                        onDismiss = {},
                        stage = {},
                        defaultTile = {
                            if (artFillsTile) {
                                Box(Modifier.fillMaxSize().testTag("art-default").background(Color(0xFF1B2A3A)))
                            } else {
                                Box(Modifier.fillMaxSize().testTag("art-default"), contentAlignment = Alignment.Center) {
                                    MarkCoin(path = null, diameter = 36.dp, modifier = Modifier.testTag("coin-default"))
                                }
                            }
                        },
                        thumbnail = { tile ->
                            if (artFillsTile) {
                                Box(Modifier.fillMaxSize().testTag("art-${tile.id}").background(Color(0xFF2E6AA8)))
                            } else {
                                Box(Modifier.fillMaxSize().testTag("art-${tile.id}"), contentAlignment = Alignment.Center) {
                                    MarkCoin(path = null, diameter = 36.dp, modifier = Modifier.testTag("coin-${tile.id}"))
                                }
                            }
                        },
                        presenceManagedExternally = true,
                        artFillsTile = artFillsTile,
                    )
                }
            }
        }
        repeat(8) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
    }

    private val density get() = compose.activity.resources.displayMetrics.density

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val asked = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results) ?: false
        assertTrue("the node reports its layout", asked && results.isNotEmpty())
        return results.first()
    }

    /** The Text whose characters, soft hyphens aside, are [name]. */
    private fun nameNode(name: String): SemanticsNode = compose.onNode(
        SemanticsMatcher("text without soft hyphens is $name") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace("\u00AD", "") == name } == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNode()

    private fun shoot(name: String) {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val folder = File(System.getenv("MIMIR_RENDER_DIR") ?: "build/test-captures/smallfit")
        if (folder.isDirectory) File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The state word [word] on tile [id]: exactly one, whole, and the box it draws in. */
    private fun wordOn(where: String, id: String, word: String): androidx.compose.ui.geometry.Rect {
        val art = bounds("art-$id")
        val onTile = compose.onAllNodes(hasText(word), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.boundsInRoot.center.x in art.left..art.right }
        assertEquals("$where: exactly one $word on the $id tile (not left off)", 1, onTile.size)
        val box = onTile.first().boundsInRoot
        assertTrue("$where: $word ($box) is whole inside the tile's width ($art)", box.left >= art.left - 0.5f && box.right <= art.right + 0.5f)
        return box
    }

    private fun assertNamesInside(where: String) {
        val artWidth = bounds("art-default").width
        val layout = layoutOf(nameNode("Atmosphere"))
        val text = layout.layoutInput.text.text
        val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        assertTrue(
            "$where: Atmosphere is inside its tile's ${artWidth / density} dp, not ${widest / density} dp wide, on ${layout.lineCount} lines: '${text.replace('\u00AD', '~')}'",
            widest <= artWidth + 0.5f,
        )
        assertTrue("$where: and it is not cut", !layout.didOverflowHeight && (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
        if (atmosphereBreaks) {
            assertEquals("$where: 'Atmo-' over 'sphere'", 2, layout.lineCount)
            val end = layout.getLineEnd(0, visibleEnd = false)
            assertTrue("$where: the break is at the soft hyphen, not inside the word", text[end - 1] == '\u00AD')
        } else {
            assertEquals("$where: a wide tile holds the whole word on one line", 1, layout.lineCount)
        }
        for (name in listOf("Your mark 1", "Your mark 2", "Your mark 3")) {
            val other = layoutOf(nameNode(name))
            assertTrue("$where: $name stands whole on at most two lines", other.lineCount <= 2 && !other.didOverflowHeight && (0 until other.lineCount).none { other.isLineEllipsized(it) })
            for (line in 0 until other.lineCount - 1) {
                val end = other.getLineEnd(line, visibleEnd = false)
                assertTrue("$where: $name breaks at its space", name[end - 1].isWhitespace() || name[end].isWhitespace())
            }
        }
    }

    private fun coinCase(scale: Float) {
        val where = "$screen font $scale (coin)"
        mount(scale, artFillsTile = false)
        for ((id, word) in listOf("default" to "DEFAULT", "a" to "CURRENT", "b" to "SAVED", "c" to "SAVED")) {
            val coin = bounds("coin-$id")
            val art = bounds("art-$id")
            assertEquals("$where: the coin is its 36 dp ($id)", 36f, coin.height / density, 0.6f)
            assertTrue("$where: the coin ($coin) is inside the art box ($art)", coin.top >= art.top - 0.5f && coin.bottom <= art.bottom + 0.5f)
            val box = wordOn(where, id, word)
            assertTrue("$where: $word ($box) stands under the art ($art), clear of the coin ($coin)", box.top >= art.bottom - 1f && box.top >= coin.bottom - 0.5f)
        }
        assertNamesInside(where)
        shoot("tilefit-$screen-font$scale-coin")
    }

    private fun photoCase(scale: Float) {
        val where = "$screen font $scale (photo)"
        mount(scale, artFillsTile = true)
        for ((id, word) in listOf("default" to "DEFAULT", "a" to "CURRENT", "b" to "SAVED")) wordOn(where, id, word)
        assertNamesInside(where)
        shoot("tilefit-$screen-font$scale-photo")
    }

    @Test
    fun theStateWordsMeasure() {
        // What the words are across, at each token, so the room a tile gives them is read off numbers.
        val out = StringBuilder()
        compose.setContent {

            for (scale in listOf(1f, 1.3f, 2f)) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    MimirTheme {
                        val d = LocalDensity.current
                        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                        for ((name, style) in listOf("MicroStatus" to com.gruning.mimir.rewrite.core.ui.theme.MimirType.MicroStatus, "MicroStatusCompact" to com.gruning.mimir.rewrite.core.ui.theme.MimirType.MicroStatusCompact)) {
                            for (word in listOf("DEFAULT", "CURRENT", "SAVED")) {
                                val px = measurer.measure(androidx.compose.ui.text.AnnotatedString(word), style = style, softWrap = false, maxLines = 1).size.width
                                out.append("STATEWORD font $scale $name $word = ${with(d) { px.toDp().value }} dp\n")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        println(out)
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun coinAtFont1() = coinCase(1.0f)

    @Test
    fun coinAtFont13() = coinCase(1.3f)

    @Test
    fun photoAtFont1() = photoCase(1.0f)

    @Test
    fun photoAtFont13() = photoCase(1.3f)
}

/** The compact square: 360 x 360 dp, the RG Rotate's 720 px at 320 dpi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h360dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceTileFitCompactRenderTest : AppearanceTileFitRenderBase() {
    override val screen = "compact"
    override val atmosphereBreaks = true
}

/** The Thor's Main: 1920 x 1080 px at 369 dpi = 832 x 468 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceTileFitThorRenderTest : AppearanceTileFitRenderBase() {
    override val screen = "thor"
    override val atmosphereBreaks = false
}
