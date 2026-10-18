package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The art receipt: it says where wired art is (loading / loaded / failed) and whether a plate stands in for it, from an effect, and
 * it adds nothing to the tile that hosts it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BottomUiArtReceiptTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = ArrayList<String>()
    private val log = BottomUiReceiptLog(
        sink = { line, _ -> lines += line },
        nowMs = { 0L },
        timer = { _, _ -> {} },
    )

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun aFailedDecodeIsStateFailedWithThePlateAndABreachNotASilentNonBreach() {
        val probe = BottomUiArtProbe()
        compose.setContent {
            BottomUiArtReceipt(
                tile = "app.org.example.app", hasArt = true, loaded = false, plateVisible = true,
                probe = probe, display = 4, log = log,
            )
        }
        compose.waitForIdle()
        assertEquals("ev=art.plate tile=app.org.example.app display=4 hasArt=true state=loading plate=visible breach=true", lines.last())
        probe.settle(loaded = false)
        settle()
        assertEquals("ev=art.plate tile=app.org.example.app display=4 hasArt=true state=failed plate=visible breach=true", lines.last())
        assertEquals(2, lines.size)
    }

    @Test
    fun aLoadedBitmapIsStateLoadedWithNoPlate() {
        compose.setContent {
            BottomUiArtReceipt(
                tile = "platform.nes", hasArt = true, loaded = true, plateVisible = false,
                probe = BottomUiArtProbe(), display = 4, log = log,
            )
        }
        compose.waitForIdle()
        assertEquals(listOf("ev=art.plate tile=platform.nes display=4 hasArt=true state=loaded plate=hidden"), lines)
    }

    @Test
    fun anEntryThatGainsAnIconPathWhileMountedReportsAgain() {
        var hasArt by mutableStateOf(false)
        val probe = BottomUiArtProbe()
        compose.setContent {
            BottomUiArtReceipt(
                tile = "quick.org.example.app", hasArt = hasArt, loaded = false, plateVisible = true,
                probe = probe, display = 4, log = log,
            )
        }
        compose.waitForIdle()
        assertEquals("ev=art.plate tile=quick.org.example.app display=4 hasArt=false state=none plate=visible", lines.last())
        hasArt = true
        settle()
        assertEquals("ev=art.plate tile=quick.org.example.app display=4 hasArt=true state=loading plate=visible breach=true", lines.last())
    }

    @Test
    fun theProbeSettlingDoesNotRecomposeTheScopeThatHostsTheReceipt() {
        var hostCompositions = 0
        val probe = BottomUiArtProbe()
        compose.setContent {
            SideEffect { hostCompositions += 1 }
            BottomUiArtReceipt(
                tile = "app.org.example.app", hasArt = true, loaded = false, plateVisible = true,
                probe = probe, display = 4, log = log,
            )
        }
        compose.waitForIdle()
        probe.settle(loaded = false)
        settle()
        assertEquals("a failed decode must not recompose the tile", 1, hostCompositions)
        assertTrue(lines.toString(), lines.last().contains("state=failed"))
    }

    @Test
    fun aTileOutsideTheDecodeWindowAndATileOffTheBottomStaySilent() {
        compose.setContent {
            BottomUiArtReceipt(
                tile = "app.a", hasArt = true, loaded = false, plateVisible = true, enabled = false, display = 4, log = log,
            )
            BottomUiArtReceipt(
                tile = "app.b", hasArt = true, loaded = false, plateVisible = true,
                display = BottomUiReceiptFormat.NO_DISPLAY, log = log,
            )
        }
        compose.waitForIdle()
        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun aTileTheCallerAlreadyKnowsHasFailedNeedsNoProbe() {
        compose.setContent {
            BottomUiArtReceipt(
                tile = "activity.media", hasArt = true, loaded = false, failed = true, plateVisible = true, display = 4, log = log,
            )
        }
        compose.waitForIdle()
        assertEquals("ev=art.plate tile=activity.media display=4 hasArt=true state=failed plate=visible breach=true", lines.single())
    }
}
