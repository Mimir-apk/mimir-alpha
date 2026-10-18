package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thor Bottom revamp B2/N8: the native ᛗ menu's swap runs on Main's swap tier, and `core-companion-native` has no core-ui
 * dependency, so its three clocks are local constants. This reads that module's source and holds each equal to the token it
 * mirrors, so a change to a token is a red test here rather than a menu that quietly keeps an old beat.
 *
 *  - the outgoing root fades, and the replacement fades in, over `MimirMotion.OverlayMs`;
 *  - the hand-over is at `MimirMotion.OverlayDissolveCoverMs`, where the house curve has the outgoing at alpha 0.044;
 *  - the replacement's rise is `MimirMotion.SwapTravelDp`.
 */
class CompanionOverlaySwapSourcePinTest {

    private val source: String = listOf(
        File("../core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
        File("core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("the native overlay controller's source must be readable from the module dir")

    private fun constant(name: String): Long {
        val match = Regex("""const val $name\s*=\s*(\d+)L?""").find(source)
        return checkNotNull(match) { "`$name` is not declared in the native controller" }.groupValues[1].toLong()
    }

    @Test
    fun `the native swap's fade is the overlay clock`() {
        assertEquals(MimirMotion.OverlayMs.toLong(), constant("CompanionOverlaySwapDurationMs"))
    }

    @Test
    fun `the native swap's hand-over is the dissolve cover`() {
        assertEquals(MimirMotion.OverlayDissolveCoverMs.toLong(), constant("CompanionOverlaySwapHandoffMs"))
    }

    @Test
    fun `the native replacement's rise is the swap travel`() {
        assertEquals(MimirMotion.SwapTravelDp.toLong(), constant("CompanionOverlaySwapRiseDp"))
    }

    @Test
    fun `the native swap runs the overlay beat and a handoff in all, about 310 ms`() {
        assertEquals(
            "OverlayDissolveCoverMs + OverlayMs, the swap tier's receipt length",
            (MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs).toLong(),
            constant("CompanionOverlaySwapHandoffMs") + constant("CompanionOverlaySwapDurationMs"),
        )
        assertTrue(source.contains("setDuration(CompanionOverlaySwapDurationMs)"))
    }
}
