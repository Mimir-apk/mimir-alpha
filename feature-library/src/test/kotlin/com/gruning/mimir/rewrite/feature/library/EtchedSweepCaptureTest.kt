package com.gruning.mimir.rewrite.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures for update's second sweep (10-09), on Main at 853 x 480 dp (density 2), at rest and focused, one lit element at a time: Home's "Open library" action (the primary chip, not a
 * solid gold fill), and Library Search with its fields as etched capsules, its rows on one ground, rows and fields
 * sharing one left edge, no spine through the results. The real [HomeShelfEmptyAction] and [LibrarySearchOverlay] (its Genre/Platform picker layer left out
 * through the `drawsPickerLayer` seam: that layer draws through a RenderNode, which a software canvas cannot draw). API 30. PNGs land in `feature-library/build/btn265/` and are copied to
 * `build/test-captures` when it exists. Pixel values are the JVM's; a handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtchedSweepCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()

    private fun save(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/btn265").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("BTN265 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    @Test
    fun `Home's Open library action is the primary chip, ringed on its plate only while focused`() {
        val focused = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(853f, 480f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        HomeShelfEmptyAction(
                            authority = authority,
                            focused = focused.value,
                            geometry = solveHomeShelfGeometry(853f, 480f),
                            compactViewport = false,
                            onOpenLibrary = {},
                        )
                    }
                }
            }
        }
        settle()
        save("btn265-home-open-library.png")
        Snapshot.withMutableSnapshot { focused.value = true }
        settle()
        save("btn265-home-open-library-focused.png")
    }

    private val rpg = GameRow(
        gameId = GameId("g1"),
        title = "Chrono Trigger",
        platformId = PlatformId("snes"),
        memberCount = 1,
        duplicateKind = null,
        genre = "RPG",
    )

    // -- Library Search --

    private val games = listOf(
        rpg,
        rpg.copy(gameId = GameId("g2"), title = "Earthbound"),
        rpg.copy(gameId = GameId("g3"), title = "Super Metroid", genre = "Platformer"),
    )
    private val platforms = listOf(PlatformRow(PlatformId("snes"), "Super Nintendo", gameCount = 3))

    private fun mountSearch(focused: FocusKey?, criteria: LibrarySearchCriteria = LibrarySearchCriteria()) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(853f, 480f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase)) {
                        LibrarySearchOverlay(
                            session = LibrarySearchSession(
                                origin = LibrarySearchOrigin.PlatformGames(PlatformId("snes")),
                                originFocus = LibrarySearchOriginFocusIdentity.SearchLauncher,
                                criteria = criteria,
                            ),
                            criteria = criteria,
                            facets = LibrarySearchFacets(
                                genres = listOfNotNull(LibrarySearchGenreKey.from("RPG")?.let { LibrarySearchGenreOption(it, "RPG") }),
                                platforms = emptyList(),
                            ),
                            games = games,
                            platforms = platforms,
                            picker = null,
                            authority = authority,
                            focused = focused,
                            onOpenKeyboard = {},
                            onOpenPicker = {},
                            onSelectGenre = {},
                            onSelectPlatform = {},
                            onClear = {},
                            onActivateGame = {},
                            onDismissPicker = {},
                            drawsPickerLayer = false,
                        )
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun `Library Search at rest - capsule fields, rows on one ground, one left edge, no spine`() {
        mountSearch(focused = null)
        save("btn265-search-overlay.png")
    }

    @Test
    fun `Library Search with the query field focused - a ring on the capsule, no fill`() {
        mountSearch(focused = LibrarySearchQueryControlKey)
        save("btn265-search-overlay-query.png")
    }

    @Test
    fun `Library Search with the genre field focused`() {
        mountSearch(focused = LibrarySearchGenreFilterKey)
        save("btn265-search-overlay-genre.png")
    }

    @Test
    fun `Library Search with a result row focused - the house row focus on one ground`() {
        mountSearch(focused = librarySearchGameResultKey(GameId("g1")))
        save("btn265-search-overlay-row.png")
    }

    private val activeCriteria = LibrarySearchCriteria(genre = LibrarySearchGenreFilter.exact("RPG"))

    @Test
    fun `Library Search with a filter active shows the Clear filters capsule at rest`() {
        mountSearch(focused = null, criteria = activeCriteria)
        save("btn265-search-overlay-clear.png")
    }

    @Test
    fun `Library Search with the Clear filters capsule focused`() {
        mountSearch(focused = LibrarySearchClearKey, criteria = activeCriteria)
        save("btn265-search-overlay-clear-focused.png")
    }
}
