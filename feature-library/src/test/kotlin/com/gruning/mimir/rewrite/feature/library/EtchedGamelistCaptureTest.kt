package com.gruning.mimir.rewrite.feature.library

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
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
 * A picture of the gamelist header update converted: the search bar (an unselected etched chip at the44dp height), the Favorites segment chips (the active one
 * warmed) and the Get Art chip under them, at the Odin's and the Thor's 897 x 505 dp (density 2), unfocused and with the search bar focused. The real [GamelistHeader] and
 * [GamelistMediaActionChip]. The PNGs land in `feature-library/build/btn265/` and are copied to `build/test-captures` when it exists. Pixel values are the JVM's; the pixels on a handheld
 * are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtchedGamelistCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()

    private fun capture(searchFocused: Boolean, chipFocused: Boolean = false, fileName: String) {
        val widthDp = 897f
        val heightDp = 505f
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(
                        Modifier.requiredSize(widthDp.dp, heightDp.dp).background(LocalMimirPalette.current.surfaceBase),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.fillMaxWidth()) {
                                GamelistHeader(
                                    title = "Favorites",
                                    metaLabel = "Favorites · 10 games",
                                    showSearch = true,
                                    searchQuery = "",
                                    searchPlaceholder = "Search games",
                                    authority = authority,
                                    searchControllerEnabled = true,
                                    searchFocused = searchFocused,
                                    onOpenSearch = {},
                                    onClearSearch = {},
                                    segments = listOf(FavoriteSegment.GAMES, FavoriteSegment.ANDROID_GAMES, FavoriteSegment.ANDROID_APPS),
                                    segmentCounts = mapOf(
                                        FavoriteSegment.GAMES to 10,
                                        FavoriteSegment.ANDROID_GAMES to 3,
                                        FavoriteSegment.ANDROID_APPS to 7,
                                    ),
                                    activeSegment = FavoriteSegment.GAMES,
                                    onSelectSegment = {},
                                )
                            }
                            GamelistMediaActionChip(
                                action = GamelistMediaAction.ENRICH,
                                focused = chipFocused,
                                enabled = true,
                                mediaContrastRail = false,
                                authority = authority,
                                onActivate = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
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

    @Test
    fun `the gamelist search bar, the favourites chips and the Get Art chip are etched chips`() = capture(searchFocused = false, fileName = "btn265-gamelist-search.png")

    @Test
    fun `the same with the search bar focused, its ring on the plate itself`() = capture(searchFocused = true, fileName = "btn265-gamelist-search-focused.png")

    @Test
    fun `the same with the Get Art chip focused, its ring on the plate itself`() = capture(searchFocused = false, chipFocused = true, fileName = "btn265-gamelist-chip-focused.png")

    @Test
    fun `the shelf's Add pill is the primary chip at the header band's height, at rest and focused`() {
        val widthDp = 897f
        val heightDp = 200f
        val focusedState = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(
                        Modifier.requiredSize(widthDp.dp, heightDp.dp).background(LocalMimirPalette.current.surfaceBase),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.padding(horizontal = 40.dp).fillMaxWidth().height(ShelfAddPillHeightDp.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ShelfAddPill(label = "Add games", focused = focusedState.value, authority = authority, compact = false, onActivate = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        save("btn265-shelf-add.png")
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { focusedState.value = true }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
        save("btn265-shelf-add-focused.png")
    }

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
}
