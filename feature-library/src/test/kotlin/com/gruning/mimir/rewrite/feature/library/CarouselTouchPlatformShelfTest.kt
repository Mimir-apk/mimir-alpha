package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Library's platform shelf (the book ring) under a finger (CarouselTouchContract), on the real [ShelfCoverflow]: an odd ring
 * here, an even one in [CarouselTouchPlatformShelfEvenRingTest], whose far seam is a seat at rest that a running finger masks.
 * The recomposition probe is the shelf's per-platform art override map, which the tile loop reads once for each tile in the same
 * scope that reads the touch state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class CarouselTouchPlatformShelfTest : CarouselTouchContract() {

    protected open val platformCount: Int get() = 11

    private val platforms by lazy { List(platformCount) { index -> PlatformRow(PlatformId("platform$index"), "Platform $index", index + 1) } }
    private val keys by lazy { platforms.map { coverflowPlatformKey(it.platformId) } }
    private val shown by lazy { mutableStateOf(platforms) }
    private val lastCentred = mutableStateOf<FocusKey?>(null)
    private val artReads = ArtOverrideProbe(probeKey = "platform$startAt")

    @Before
    fun publishArtProbe() {
        PlatformTileArtStore.art.value = artReads
        PlatformTileArtStore.hydrated.value = true
    }

    @After
    fun clearArtProbe() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = false
    }

    override fun mount() {
        authority.pushScope(CoverflowShelfScope)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TouchFrame {
                val focus by authority.state.collectAsState()
                ShelfCoverflow(
                    platforms = shown.value,
                    favoritesCount = 0,
                    // As the screen gives it: the conserved key, which survives a scope pushed over the shelf.
                    focusedKey = authority.renderedFocusIn(CoverflowShelfScope, focus),
                    preferredCenterKey = lastCentred.value,
                    onCenter = { lastCentred.value = it },
                    authority = authority,
                    onOpenPlatform = { id -> opened += platforms.indexOfFirst { it.platformId == id } },
                    homeSeatCount = HomeShelfPrimarySeatCount,
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
        val own = shelfCoverflowTileTestTag(keys[index])
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
        shown.value = platforms.dropLast(1)
    }

    override fun probe(): Int = artReads.reads
}

/** The same checks on an even ring, whose antipode is a seat at rest that a running ring has to mask. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarouselTouchPlatformShelfEvenRingTest : CarouselTouchPlatformShelfTest() {
    override val platformCount: Int get() = 12

    /**
     * An even ring finishes a glide that crossed its seam with a short wait (a frame, a fade, a frame) before it snaps the
     * centre to the canonical seat of the item it settled on and puts the seam back. A finger that takes the ring in that wait
     * owns it: the whole sequence ends, nothing is snapped under the finger, and the ring stays engaged until the lift.
     */
    @Test
    fun `a drag that begins in the seam wait of a settling even ring is not snapped to the item the ring was settling on`() {
        mount()
        focusAndSettle(platformCount - 1)
        // Item 11 to item 0 (the pad's wrap is the screen's, so ask the authority): the centre runs on from 11 to 12, and is
        // made canonical (0) after the wait.
        requestFocusOn(0)
        compose.touchFrames(2)
        var frames = 0
        while (abs(seat(0)) > 0.0005f && frames < 120) {
            compose.touchFrames()
            frames++
        }
        assertTrue("the glide must end on the seat of item 0 ($frames frames)", abs(seat(0)) <= 0.0005f)

        val pitch = pitchPx(0)
        compose.touchDown(tileCenter(0))
        compose.touchMove(-(0.4f * pitch + slopPx))
        repeat(8) { frame ->
            compose.touchFrames()
            assertEquals("frame $frame: the ring is where the finger put it, not snapped to item 0's canonical seat", -0.4f, seat(0), 0.03f)
        }

        compose.touchRest()
        compose.touchUp()
        compose.touchSettle(2_000L)
        assertEquals("the short drag comes back to the item it began on", 0, focusedIndex())
        assertEquals(0f, seat(0), 0.01f)
    }
}

/**
 * A platform-art override map that counts how often the shelf asks it for one platform, and never has an answer. It is not
 * empty on purpose: the store is a state flow, and a flow holding the empty map takes any other empty map for the same value.
 */
private class ArtOverrideProbe(private val probeKey: String) : Map<String, String> by mapOf("__probe__" to "") {
    var reads = 0

    override fun get(key: String): String? {
        if (key == probeKey) reads++
        return null
    }
}
