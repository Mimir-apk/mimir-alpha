package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source pins for Rank 7c's three wiring points, each in code no JVM test can drive: the Bottom card's handle
 * creation (`MimirAppCoordinator.kt`'s `launchCompanionPickedGame`), the store's `arm` (Android SharedPreferences),
 * and Main's return path (`MainActivity.settleReturningGameSession`). The decisions they call are tested
 * behaviourally in [HomeLaunchReturnOriginTest].
 */
class HomeLaunchReturnOriginWiringTest {

    @Test
    fun `the Bottom card's origin comes from its own Home-row lookup`() {
        val body = sourceFile("MimirAppCoordinator.kt")
            .substringAfter("private suspend fun launchCompanionPickedGame(")
            .substringBefore("MimirSessionAuthority.prepareHomeGame(")
        val lookup = "homeRows.firstOrNull { it.itemId == itemId }"

        assertTrue("the return target must still come from the Home-row lookup", body.contains("val target = $lookup"))
        assertTrue(
            "the handle's origin must come from that same lookup, never from a rank",
            body.contains("origin = bottomCardHomeLaunchOrigin($lookup)"),
        )
    }

    @Test
    fun `arm consults the origin decision before it writes and logs one line`() {
        val source = sourceFile("HomeLaunchReturnStore.kt")
        val ledgerArm = source
            .substringAfter("fun arm(handle: HomeLaunchReturnHandle): Boolean")
            .substringBefore("fun peek(finalShelfEnabled: Boolean)")
        val decision = ledgerArm.indexOf("homeLaunchReturnArmDecision(handle.origin)")
        val write = ledgerArm.indexOf("persistence.replace(handle.toRecord())")
        assertTrue("the ledger must decide before it writes", decision >= 0 && write > decision)

        val storeArm = source
            .substringAfter("suspend fun arm(context: Context, handle: HomeLaunchReturnHandle): Boolean")
            .substringBefore("suspend fun peek(")
        assertTrue("the store's arm must log its decision", storeArm.contains("homeLaunchReturnArmLogMessage("))
        assertTrue("the log line keeps its grep-able shape", source.contains("\"home launch return \$verb identity="))
    }

    @Test
    fun `Main's return path finishes the returned session's elapsed measurement`() {
        val body = sourceFile("MainActivity.kt")
            .substringAfter("private fun settleReturningGameSession(")
            .substringBefore("private fun armGameDispatchWatchdog(")

        assertTrue(
            "the return path must take the token from the returned session",
            body.contains("primaryGameReturnElapsedToken(MimirSessionAuthority.state.value.gameSession, expectedOperation)"),
        )
        assertTrue(
            "the finish must be the store's token-matched finalize, not an additive write",
            body.contains("HomeExternalSessionElapsedStore.finalize("),
        )
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
