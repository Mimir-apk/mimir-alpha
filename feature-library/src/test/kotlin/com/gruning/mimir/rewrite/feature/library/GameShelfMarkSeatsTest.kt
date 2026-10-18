package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirArtAvailability
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sweep 10-11 (vqa11), P1-2: where a game tile's two marks stand, by the face it wears. The favourite plate and the disc badge ride the TOP corners of a cover, a square tile and
 * an article, as they always did; on the runed BOOK (a game with no cover) the book sets its title at the top of the leather, so its marks ride the BOTTOM edge instead, the
 * favourite bottom-leading and the badge bottom-trailing. A tile whose art is not looked up yet paints no book, so its marks keep the top.
 *
 * The real [GameShelf] is mounted with `paintMarks`, as the Library does, over four faces (the article is a ledge painter's art too, and rides the cover's seat): a ledge cover, a square, the book and a pending tile. Every tile carries both marks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShelfMarkSeatsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val delivered = Collections.synchronizedList(mutableListOf<String>())
    private val tempFiles = mutableListOf<File>()
    private val widthDp = 897f
    private val heightDp = 505f

    @Before
    fun answerEveryArtRequestFromTheTestBitmaps() {
        MimirArtAvailability.clearForTest()
        MimirArtAvailability.useDispatcherForTest(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(
                        Interceptor { chain ->
                            val uri = chain.request.data.toString()
                            val bitmap = checkNotNull(bitmaps[uri]) { "no test bitmap for $uri" }
                            delivered += uri
                            SuccessResult(
                                drawable = BitmapDrawable(chain.request.context.resources, bitmap),
                                request = chain.request,
                                dataSource = DataSource.MEMORY,
                            )
                        },
                    )
                }
                .build(),
        )
    }

    @After
    fun tearDown() {
        Coil.reset()
        MimirArtAvailability.useDispatcherForTest(null)
        tempFiles.forEach { it.delete() }
        setCarouselAnimatorScale(1f)
    }

    private fun art(width: Int, height: Int, color: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        val file = File.createTempFile("sweepfix32-art", ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tempFiles += file
        val uri = file.toURI().toString().replaceFirst("file:/", "file:///")
        bitmaps[uri] = bitmap
        return uri
    }

    private val items: List<GameShelfItem.Game> by lazy {
        val marks = GameShelfMarks(favorite = true, collapseBadge = "2 discs")
        listOf(
            game(0, "Ledge Cover", ShelfFace(art(300, 420, AndroidColor.rgb(190, 45, 45)), ShelfFaceShape.LEDGE_COVER), marks),
            game(1, "Square Tile", ShelfFace(art(512, 512, AndroidColor.rgb(30, 170, 70)), ShelfFaceShape.SQUARE), marks),
            game(2, "No Art At All", ShelfFace.None, marks),
            game(3, "Pending Art", ShelfFace.None, marks, pending = true),
        )
    }

    private fun game(index: Int, title: String, face: ShelfFace, marks: GameShelfMarks, pending: Boolean = false) = GameShelfItem.Game(
        focusKey = com.gruning.mimir.rewrite.core.input.FocusKey("capture/marks/$index"),
        title = title,
        face = face,
        platformIdValue = "gba",
        marks = marks,
        artPending = pending,
    )

    private val shelfScope = FocusScope("capture/game-shelf-marks")
    private val windowMemory = GameShelfWindowMemory()

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { Scene() }
                }
            }
        }
        settle()
        authority.requestFocus(items[0].focusKey)
        val wanted = items.mapNotNull { it.face.uri }.toSet()
        repeat(200) {
            if (synchronized(delivered) { delivered.containsAll(wanted) }) {
                settle()
                return
            }
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50L)
        }
        throw AssertionError("the art never arrived: wanted $wanted, delivered $delivered")
    }

    @Composable
    private fun Scene() {
        DisposableEffect(authority) {
            authority.pushScope(shelfScope)
            onDispose { authority.popScope() }
        }
        RegisterVirtualFocusRow(authority = authority, scope = shelfScope, keys = items.map { it.focusKey })
        val focusState by authority.state.collectAsState()
        val selectedKey = if (focusState.scope == shelfScope) focusState.focused else authority.rememberedFocusIn(shelfScope)
        val geometry = remember { solveHomeShelfGeometry(widthDp, heightDp) }
        MimirShelfBackground(modifier = Modifier.fillMaxSize(), showAtmosphericClouds = false) {
            val state = rememberGameShelfState(
                items = items,
                selectedKey = selectedKey,
                geometry = geometry,
                windowMemory = windowMemory,
                windowMemoryKey = "marks",
                surfaceEpoch = 0,
                reducedMotion = false,
            )
            GameShelf(
                scope = shelfScope,
                authority = authority,
                focusState = focusState,
                onRequestFocus = { key -> authority.requestFocus(key) },
                onActivate = {},
                emptyContent = {},
                state = state,
                showShelfmark = false,
                paintMarks = true,
            )
        }
    }

    private fun settle(ms: Long = 1_500L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun tile(item: GameShelfItem.Game): Rect =
        compose.onAllNodes(hasTestTag(HomeShelfTileTestTagPrefix + item.focusKey.value), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInWindow

    private fun mark(item: GameShelfItem.Game, tag: String): Rect = compose.onAllNodes(
        hasTestTag(tag) and hasAnyAncestor(hasTestTag(HomeShelfTileTestTagPrefix + item.focusKey.value)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().single().boundsInWindow

    @Test
    fun `the marks ride the top of a cover, a square and an article, the bottom of a book, and the top of a tile whose art is pending`() {
        mount()
        val expectedOnBottom = mapOf(0 to false, 1 to false, 2 to true, 3 to false)
        items.forEachIndexed { index, item ->
            val box = tile(item)
            val middle = box.top + box.height / 2f
            val favourite = mark(item, GameShelfFavoriteMarkTestTag)
            val badge = mark(item, GameShelfCollapseBadgeTestTag)
            val bottom = expectedOnBottom.getValue(index)
            println("MARKSEAT '${item.title}' tile=${box.left},${box.top},${box.right},${box.bottom} favourite=${favourite.left},${favourite.top} badge=${badge.right},${badge.top}")
            val what = "'${item.title}'"
            if (bottom) {
                assertTrue("$what (a book): the favourite stands on the bottom half ($favourite in $box)", favourite.top >= middle)
                assertTrue("$what (a book): the badge stands on the bottom half ($badge in $box)", badge.top >= middle)
            } else {
                assertTrue("$what: the favourite stands on the top half ($favourite in $box)", favourite.bottom <= middle)
                assertTrue("$what: the badge stands on the top half ($badge in $box)", badge.bottom <= middle)
            }
            // The favourite is leading and the badge trailing on either edge: the plate never swaps sides with the badge.
            assertTrue("$what: the favourite is to the left of the badge ($favourite, $badge)", favourite.center.x < badge.center.x)
        }
    }
}
