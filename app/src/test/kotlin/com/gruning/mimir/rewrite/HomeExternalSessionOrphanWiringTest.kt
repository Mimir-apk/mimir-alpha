package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for the Rank 7c follow-up's wiring, which lives in Android-bound code no JVM test drives: Main's
 * return read (`HomeLaunchReturnStore.peek`), the elapsed store's locked finish, and the launch that records its
 * game's display. The decisions are tested behaviourally in [HomeExternalSessionOrphanTest].
 */
class HomeExternalSessionOrphanWiringTest {

    @Test
    fun `Main's return read finishes a dead process's open measurement only when it finds no record`() {
        val peek = sourceFile("HomeLaunchReturnStore.kt")
            .substringAfter("suspend fun peek(")
            .substringBefore("suspend fun acknowledge(")
        val read = peek.indexOf("val handle = mutationMutex.withLock { ledger(context).peek(finalShelfEnabled = true) }")
        val gate = peek.indexOf("if (handle == null)")
        val finish = peek.indexOf("HomeExternalSessionElapsedStore.finishOrphanedMainReturn(")

        assertTrue("the finish must follow a record read that found nothing", read >= 0 && gate > read && finish > gate)
        assertTrue("the read must still return exactly what the ledger found", peek.contains("\n        handle\n    }\n"))
    }

    @Test
    fun `the Main-return finish is the store's token-matched boot-checked reducer under its lock`() {
        val source = sourceFile("HomeExternalSessionElapsedStore.kt")
        assertTrue("the finish must exist", source.contains("suspend fun finishOrphanedMainReturn("))
        val finish = source
            .substringAfter("suspend fun finishOrphanedMainReturn(")
            .substringBefore("suspend fun cancel(")

        listOf(
            "mutationMutex.withLock",
            "homeExternalSessionOrphanToken(current, Process.getStartElapsedRealtime(), bootCount)",
            "finalizeHomeExternalSession(current, token, nowElapsedMillis, bootCount)",
        ).forEach { expected -> assertTrue("missing: $expected", finish.contains(expected)) }
    }

    @Test
    fun `a Home launch records the display its game owns when it begins measuring`() {
        val settle = sourceFile("HomeShelfCoordinator.kt")
            .substringAfter("internal suspend fun settleEmulatedHomeLaunch(")
            .substringBefore("internal fun androidGameLaunchProofFor(")

        assertTrue(settle.contains("placement = homeExternalSessionPlacement(destination),"))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
