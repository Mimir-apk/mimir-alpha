package com.gruning.mimir.rewrite

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The instruments host's receipts (Utility / Tweaker on the Bottom) and the bar they are held to: logging only. The face is
 * read in an effect, so a change of face must not recompose the scope that hosts the receipt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionBottomUiInstrumentsReceiptTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = ArrayList<String>()
    private val log = BottomUiReceiptLog(
        sink = { line, _ -> lines += line },
        nowMs = { 0L },
        timer = BottomUiReceiptTimer { _, _ -> {} },
    )

    @Test
    fun openingFadesAndMovingBetweenTheFacesSwaps() {
        val receipts = BottomUiInstrumentsReceipts(log, 4)
        receipts.onFace(null)
        receipts.onFace(UtilitySurface.UTILITY)
        receipts.onFace(UtilitySurface.TWEAKER)
        receipts.onFace(null)
        assertEquals(
            listOf(
                "ev=surface.enter surface=utility display=4 from=none transition=instruments.fade",
                "ev=surface.exit surface=utility display=4 to=none transition=instruments.swap",
                "ev=surface.enter surface=tweaker display=4 from=none transition=instruments.swap",
                "ev=surface.exit surface=tweaker display=4 to=none transition=instruments.fade",
            ),
            lines,
        )
    }

    @Test
    fun aHostThatGoesAwayOpenWritesTheExit() {
        val receipts = BottomUiInstrumentsReceipts(log, 4)
        receipts.onFace(UtilitySurface.TWEAKER)
        receipts.end()
        assertEquals("ev=surface.exit surface=tweaker display=4 to=none transition=none", lines.last())
        receipts.end()
        assertEquals("a second end writes nothing", 2, lines.size)
    }

    @Test
    fun aChangeOfFaceDoesNotRecomposeTheScopeThatHostsTheReceipt() {
        var hostCompositions = 0
        var face by mutableStateOf<UtilitySurface?>(null)
        compose.setContent {
            SideEffect { hostCompositions += 1 }
            BottomUiInstrumentsReceipt(display = 4, log = log) { face }
        }
        compose.waitForIdle()
        assertEquals(1, hostCompositions)

        face = UtilitySurface.UTILITY
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        face = UtilitySurface.TWEAKER
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        face = null
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()

        assertEquals("the host scope composed once, however the face moved", 1, hostCompositions)
        assertTrue(lines.toString(), lines.any { it.startsWith("ev=surface.enter surface=tweaker") })
        assertTrue(lines.toString(), lines.last().startsWith("ev=surface.exit surface=tweaker"))
    }

    @Test
    fun theHarnessDoesSeeARecompositionWhenTheScopeReadsTheFaceItself() {
        // The negative control: the shape the first version had (the state read in the host's own scope).
        var hostCompositions = 0
        var face by mutableStateOf<UtilitySurface?>(null)
        compose.setContent {
            SideEffect { hostCompositions += 1 }
            val readInHost = face
            BottomUiInstrumentsReceipt(display = 4, log = log) { readInHost }
        }
        compose.waitForIdle()
        face = UtilitySurface.UTILITY
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertTrue("a read in the host's scope recomposes it", hostCompositions > 1)
    }

    @Test
    fun theCompositionLeavingWhileOpenWritesTheExit() {
        var show by mutableStateOf(true)
        var face by mutableStateOf<UtilitySurface?>(UtilitySurface.UTILITY)
        compose.setContent {
            if (show) BottomUiInstrumentsReceipt(display = 4, log = log) { face }
        }
        compose.waitForIdle()
        assertTrue(lines.toString(), lines.any { it.startsWith("ev=surface.enter surface=utility") })
        show = false
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertTrue(lines.toString(), lines.last().startsWith("ev=surface.exit surface=utility"))
    }
}
