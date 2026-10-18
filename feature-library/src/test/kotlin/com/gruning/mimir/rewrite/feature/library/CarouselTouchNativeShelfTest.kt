package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library's Android shelf under a finger (CarouselTouchContract), on the real [NativeShelfCoverflow]. The recomposition
 * probe is the tile panel the shelf asks of its caller for each tile in the same scope that reads the touch state, so it runs
 * once for each time that scope recomposes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarouselTouchNativeShelfTest : CarouselTouchContract() {

    private val rows = List(12) { index -> NativeShelfRow("pkg.$index", "App $index", null, isGame = true) }
    private val keys = rows.map { nativeShelfKey(it.packageName) }
    private val shown = mutableStateOf(rows)
    private val panelRuns = mutableMapOf<String, Int>()

    override fun mount() {
        authority.pushScope(LibraryShelfScope)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TouchFrame {
                val focus by authority.state.collectAsState()
                NativeShelfCoverflow(
                    rows = shown.value,
                    // As the screen gives it: the conserved key, which survives a scope pushed over the shelf.
                    focusedKey = authority.renderedFocusIn(LibraryShelfScope, focus),
                    authority = authority,
                    mode = LibraryShelfMode.ANDROID_GAMES,
                    onLaunchPackage = { packageName -> opened += rows.indexOfFirst { it.packageName == packageName } },
                    tilePanel = { _, packageName ->
                        SideEffect { panelRuns[packageName] = (panelRuns[packageName] ?: 0) + 1 }
                        Modifier
                    },
                )
            }
        }
        compose.touchFrames(3)
        focusAndSettle(startAt)
    }

    override fun focusedIndex(): Int? = keys.indexOf(authority.state.value.focused).takeIf { it >= 0 }

    override fun requestFocusOn(index: Int) = authority.requestFocus(keys[index])

    /** The centred tile carries the selected-slot tag instead of its own. */
    override fun tile(index: Int): SemanticsNodeInteraction {
        val own = nativeShelfCoverflowTileTestTag(rows[index].packageName)
        return if (compose.onAllNodesWithTag(own).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(own)
        } else {
            compose.onNodeWithTag(LibraryShelfSelectedSlotTestTag)
        }
    }

    override fun seat(index: Int): Float = tile(index).fetchSemanticsNode().config[CoverflowVisualPositionSemantics]

    override fun pad(right: Boolean) {
        authority.moveFocus(if (right) ControllerAction.NAV_RIGHT else ControllerAction.NAV_LEFT)
        compose.touchFrames(2)
    }

    override fun rebuildItems() {
        shown.value = rows.dropLast(1)
    }

    override fun probe(): Int = panelRuns[rows[startAt].packageName] ?: 0
}
