package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Walk-32h, the native hide: the shelf's Android tiles are the real [NativeShelfCoverflow], given what the Library's shelf
 * gives it. The CENTRED tile draws its face through a capture layer and reports where that face is; the other tiles carry
 * nothing; when the centre moves the stage follows it; and when the shelf is one card or empty the stage holds nothing.
 * (What the shards look like, and how the survivor glides in, is for the device.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryShatterNativeShelfPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(
        NativeShelfRow("one.pkg", "One", null, isGame = true),
        NativeShelfRow("two.pkg", "Two", null, isGame = true),
        NativeShelfRow("three.pkg", "Three", null, isGame = true),
    )
    private val keys = rows.map { nativeShelfKey(it.packageName) }
    private val authority = DefaultFocusAuthority()
    private val focused = mutableStateOf(keys[1])
    private val stage = LibraryShatterStage()
    private val shown = mutableStateOf(rows)

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 600.dp)) {
                    NativeShelfCoverflow(
                        rows = shown.value,
                        focusedKey = focused.value,
                        authority = authority,
                        mode = LibraryShelfMode.ANDROID_GAMES,
                        onLaunchPackage = {},
                        tilePanel = { isCentered, packageName ->
                            rememberLibraryShatterPanel(stage, active = isCentered, gameId = packageName)
                        },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160L)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160L)
    }

    @Test
    fun `the centred tile is the one the stage can break, with its picture source and where its face is`() {
        host()

        assertEquals("two.pkg", stage.centreGameId)
        assertNotNull("a layer to read the picture from", stage.centreCapture)
        assertNotNull("the live place of the face, asked for again when the picture is taken", stage.centreCoordinates)
        val bounds = stage.centreBounds
        assertNotNull("where the face is, in the window", bounds)
        assertTrue("and it is a real face: $bounds", bounds!!.width > 0f && bounds.height > 0f)
    }

    @Test
    fun `when the centre moves the stage follows it, and only the new centre is named`() {
        host()
        val first = stage.centreBounds
        assertNotNull(first)

        focused.value = keys[2]
        settle()

        assertEquals("three.pkg", stage.centreGameId)
        assertNotNull(stage.centreCapture)
        assertNotNull(stage.centreBounds)
    }

    @Test
    fun `an empty shelf leaves the stage holding nothing`() {
        host()
        assertNotNull(stage.centreCapture)

        shown.value = emptyList()
        settle()

        assertNull(stage.centreCapture)
        assertNull(stage.centreBounds)
        assertNull(stage.centreCoordinates)
        assertNull(stage.centreGameId)
    }
}
