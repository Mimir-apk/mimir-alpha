package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.model.library.ShelfFace
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What [GameShelf] does with what it is handed, through the real composable on the JVM (Robolectric, 897 x 505 dp). The scene is
 * the one a second caller writes: it pushes its scope, registers its keys, and hands the shelf the selection. Each test moves one
 * thing the caller owns and reads what the shelf painted or took.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h520dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameShelfBehaviorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val authority = DefaultFocusAuthority()
    private val scope = FocusScope("behavior/game-shelf")
    private val memory = GameShelfWindowMemory()
    private val widthDp = 897f
    private val heightDp = 505f

    /** When set, the shelf is handed this snapshot of the authority in place of the live one. */
    private val handed = mutableStateOf<FocusState?>(null)

    /** The app's restoration cover: while it is up the shelf is inert. */
    private val inert = mutableStateOf(false)

    /** The names the shelf wears; Home's unless a test hands it others. */
    private var ids = GameShelfIds.Home

    private fun game(index: Int) = GameShelfItem.Game(
        focusKey = FocusKey("behavior/game-$index"),
        title = "Game $index",
        face = ShelfFace.None,
        platformIdValue = "gba",
    )

    private val items = List(4, ::game)

    private fun mount(items: List<GameShelfItem> = this.items) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(2f, 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) { Scene(items, handed) }
                }
            }
        }
        settle()
        authority.requestFocus(items.first().focusKey)
        settle()
    }

    @Composable
    private fun Scene(items: List<GameShelfItem>, handed: State<FocusState?>) {
        DisposableEffect(authority) {
            authority.pushScope(scope)
            onDispose { authority.popScope() }
        }
        RegisterVirtualFocusRow(authority = authority, scope = scope, keys = items.map { it.focusKey })
        val live by authority.state.collectAsState()
        val selectedKey = if (live.scope == scope) live.focused else authority.rememberedFocusIn(scope)
        val geometry = remember { solveHomeShelfGeometry(widthDp, heightDp) }
        MimirShelfBackground(modifier = Modifier.fillMaxSize(), showAtmosphericClouds = false) {
            val state = rememberGameShelfState(
                items = items,
                selectedKey = selectedKey,
                geometry = geometry,
                windowMemory = memory,
                windowMemoryKey = "behavior",
                surfaceEpoch = 0,
                reducedMotion = false,
                ids = ids,
            )
            GameShelf(
                scope = scope,
                authority = authority,
                focusState = handed.value ?: live,
                onRequestFocus = { key -> authority.requestFocus(key) },
                onActivate = {},
                emptyContent = {},
                inert = inert.value,
                state = state,
            )
        }
    }

    private fun settle(ms: Long = 1_500L) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun ringCount() = compose.onAllNodesWithTag(HomeShelfStationaryRingTestTag).fetchSemanticsNodes().size

    @Test
    fun `the ring reads the focus snapshot the shelf is handed, not a collector of its own`() {
        mount()
        assertEquals("the live authority has the scope lit, so the selected seat wears the ring", 1, ringCount())

        // The caller's snapshot says another scope owns input while the authority itself still shows this one lit: the shelf
        // follows the snapshot it was given, so the ring and the selection (derived from that snapshot) cannot disagree.
        Snapshot.withMutableSnapshot { handed.value = FocusState(scope = FocusScope("behavior/elsewhere"), focused = null) }
        settle()
        assertEquals("a handed snapshot in which this scope has lost input takes the ring with it", 0, ringCount())

        Snapshot.withMutableSnapshot { handed.value = null }
        settle()
        assertEquals("handing the live snapshot back lights it again", 1, ringCount())
    }

    @Test
    fun `an inert shelf draws no continuation runes either`() {
        val long = List(12, ::game)
        mount(long)
        authority.requestFocus(long[6].focusKey)
        settle()
        // A hidden rune clears its own semantics, so only a rune with somewhere to point can be found: mid-shelf, both do.
        compose.onNodeWithTag(HomeShelfLeftContinuationTestTag).assertContentDescriptionEquals("More items to the left")
        compose.onNodeWithTag(HomeShelfRightContinuationTestTag).assertContentDescriptionEquals("More items to the right")

        Snapshot.withMutableSnapshot { inert.value = true }
        settle()
        assertEquals("the restoration cover is up: no right rune", 0, count(HomeShelfRightContinuationTestTag))
        assertEquals("no left rune", 0, count(HomeShelfLeftContinuationTestTag))
        assertEquals("no ring", 0, ringCount())
        assertEquals("and no tile", 0, count(HomeShelfTileTestTagPrefix + long[6].focusKey.value))
    }

    @Test
    fun `a shelf handed other ids wears them and none of Home's`() {
        val library = GameShelfIds("library")
        ids = library
        val long = List(12, ::game)
        mount(long)
        authority.requestFocus(long[6].focusKey)
        settle()
        val tile = library.tilePrefix + long[6].focusKey.value

        assertEquals("the selected seat", 1, count(library.selectedSlotTag))
        assertEquals("the stationary ring", 1, count(library.ringTag))
        assertEquals("the selected tile", 1, count(tile))
        compose.onNodeWithTag(library.leftRuneTag).assertContentDescriptionEquals("More items to the left")
        compose.onNodeWithTag(library.rightRuneTag).assertContentDescriptionEquals("More items to the right")
        listOf(
            HomeShelfSelectedSlotTestTag,
            HomeShelfStationaryRingTestTag,
            HomeShelfTileTestTagPrefix + long[6].focusKey.value,
            HomeShelfLeftContinuationTestTag,
            HomeShelfRightContinuationTestTag,
        ).forEach { assertEquals("Home's tag $it is not worn by a library shelf", 0, count(it)) }

        val config = compose.onNodeWithTag(tile).fetchSemanticsNode().config
        assertEquals("the tile publishes its presentation under the shelf's own keys", 1f, config[library.presentationAlpha], 0.02f)
        assertNull("and not under Home's", config.getOrNull(HomeShelfPresentationAlphaSemantics))
    }

    @Test
    fun `a buffer seat painted at alpha 0 takes no tap, and the seats on screen still do`() {
        val long = List(12, ::game)
        mount(long)
        // Focus sits on the first game, so the window is 0 until visibleSlotCount - 1; the next identity is mounted just
        // past it (hydration warm, painted at alpha 0) and the neighbours before it are the seats on screen.
        val visible = solveHomeShelfGeometry(widthDp, heightDp).visibleSlotCount
        val bufferTag = ids.tilePrefix + long[visible].focusKey.value
        val seatTag = ids.tilePrefix + long[1].focusKey.value

        assertEquals("the buffer identity is mounted", 1, count(bufferTag))
        compose.onNodeWithTag(bufferTag).assertIsNotEnabled()
        compose.onNodeWithTag(seatTag).assertIsEnabled()

        compose.onNodeWithTag(seatTag).performClick()
        settle()
        assertEquals("a tap on a seat on screen still focuses it", long[1].focusKey, authority.state.value.focused)
    }
}
