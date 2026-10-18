package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * The apply itself always worked -- the file landed, and re-entering the gamelist showed it. What was missing
 * was any signal from the apply to the surfaces already on screen. Every apply flavour (ActivateSaved,
 * Automatic, ApplyResult, and both image-import legs) funnels through `refreshCustomEnrichOwner`, and that
 * function spoke only to the Home shelf's own `sgdbEpoch` and to platform tile art. The Library gamelist
 * paints `GameRow.wiredFace`, which `LibraryRepositoryImpl.toGameRow` reads from the filesystem through
 * `CoverOverrideSource` -- a projection that re-runs only when Room or `artVersion` emits.
 *
 * Introduced by `1ab6b96a` (2026-08-25), which gave the hand-picked face its own tier and its own projected
 * field. It shipped stale on day one rather than breaking later.
 *
 * Two states, two routes, and the tests below are split the same way:
 *  - SHELF travels by game id -- [CustomEnrichRefreshPlan.libraryGameId] re-projects the row.
 *  - LAUNCH_CARD has no game id and no repository field (its files live under `launch-card#<cacheKey>`), so
 *    it travels by the unconditional epoch bump, pinned here as a source contract because the state it
 *    bumps lives inside a composable.
 */
class CustomEnrichRefreshPlanTest {

    @Test
    fun `an emulated game's apply re-projects that game's library row`() {
        val gameId = GameId("gc-luigis-mansion")

        val plan = customEnrichRefreshPlan(CustomEnrichPickerOwner.Home(HomeShelfItemId.Emulated(gameId)))

        // The defect: this was null for every owner, so the gamelist tile kept its stale face until the
        // screen was disposed and re-entered.
        assertEquals(gameId, plan.libraryGameId)
        assertTrue("Home tiles must still refresh -- this is an addition, not a swap", plan.refreshHomeTiles)
        assertNull(plan.platformId)
    }

    @Test
    fun `an Android package has no library row to refresh`() {
        val plan = customEnrichRefreshPlan(
            CustomEnrichPickerOwner.Home(
                HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.game")),
            ),
        )

        // Not an oversight: a package name is not a game id, and asking the repository to resolve one would
        // send it hunting for a row that has never existed.
        assertNull("an Android package must not be sent to the library repository", plan.libraryGameId)
        assertTrue(plan.refreshHomeTiles)
    }

    @Test
    fun `a platform owner refreshes platform art and nothing else`() {
        val platformId = PlatformId("gamecube")

        val plan = customEnrichRefreshPlan(CustomEnrichPickerOwner.Platform(platformId))

        assertEquals(platformId, plan.platformId)
        assertNull(plan.libraryGameId)
        assertFalse("a platform apply must not repaint game tiles", plan.refreshHomeTiles)
    }

    /**
     * The epoch is bumped ABOVE the plan and outside every branch. A `when` arm would miss the reported case on the launch card: a LAUNCH_CARD pick leaves the repository row byte-identical, and
     * `games` is a StateFlow, which conflates an equal emission entirely.
     */
    @Test
    fun `every apply bumps the media epoch, before and outside the plan`() {
        val body = appCoordinatorSource().sliceBetween(
            "fun refreshCustomEnrichOwner(owner: CustomEnrichPickerOwner) {",
            "\n    fun updateHomeCustomEnrich(",
        ).withoutLineComments()

        val bump = body.indexOf("mediaApplyEpoch += 1L")
        val planned = body.indexOf("customEnrichRefreshPlan(owner)")
        assertTrue("the media epoch is never bumped on an apply", bump >= 0)
        assertTrue("the plan is not consulted", planned >= 0)
        assertTrue("the bump must precede the plan so no branch can skip it", bump < planned)
        assertTrue(
            "the plan's library game must actually reach the repository",
            body.contains("core.repository.refreshArt(gameId)"),
        )
    }

    /**
     * Both launch-card face producers must re-key on the epoch. Source-contract rather than behavioural:
     * these are `remember` key sets inside composables, and the key set is exactly what a unit test cannot
     * observe. Without this the Library card keeps painting the previous face while it is open.
     */
    @Test
    fun `both launch card face producers re-key on the media epoch`() {
        val host = appHostSource()

        val homeCard = host.sliceBetween("var launchCardFace by remember(", "launchCardFace = withContext(")
        assertTrue(
            "Home's launch card ignores the epoch, so a LAUNCH_CARD pick cannot reach an open card",
            homeCard.contains("bindings.mediaApplyEpoch"),
        )

        val libraryCard = host.sliceBetween(
            "val cartridgeArtUriForGame: (GameRow) -> MimirShelfFace? =",
            "val processServices = remember(core.context)",
        )
        assertTrue(
            "the Library destination's card ignores the refresh epoch",
            libraryCard.contains(
                "remember(core.context, bindings.mediaApplyEpoch)",
            ),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole input. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun String.withoutLineComments(): String =
        lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun appCoordinatorSource(): String = sourceFile("MimirAppCoordinator.kt")

    private fun appHostSource(): String = sourceFile("MimirAppHost.kt")

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
