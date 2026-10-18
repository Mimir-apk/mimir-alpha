package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source characterization of how `:app` hands the ONE launch card to its two doors , in the idiom the neighbouring wiring tests use: the closures sit in large
 * composables with no harness, so what each door is given is pinned where it is written. The card's own behaviour is `LaunchCardDoorParityTest`'s (feature-library).
 */
class OneGameCardWiringTest {

    private fun source(name: String): String {
        val dir = "src/main/kotlin/com/gruning/mimir/rewrite/"
        return listOf(File("app/$dir$name"), File("$dir$name")).firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    /** Home builds the same facts and reads the same physical-face rule as the Library's adapter: one builder, one rule. */
    @Test
    fun `Home's door lists the shared facts and reads the shared physical-face rule`() {
        val host = source("MimirAppHost.kt")
        assertTrue(host.contains("cartridgeArtIsPhysical = launchCardFaceIsPhysical(launchCardFace?.physical),"))
        assertFalse("the old Home-only reading is gone", host.contains("launchCardFace?.physical == true"))
        val facts = host.substringAfter("extraFacts = launchCardFacts(").substringBefore("),\n        ),")
        assertTrue(facts.contains("game = game,"))
        assertTrue("the members the card already loads for its disc menu", facts.contains("bindings.homeLaunchCard.members"))
        assertTrue(facts.contains("libraryDetailDiscs("))
        assertTrue(facts.contains("launchCardSizeBytes("))
    }

    /** The Library's SESSION TIME is the elapsed store's total, read by the Library coordinator and handed down on the card's detail state. */
    @Test
    fun `the Library's card is handed the elapsed store's total for its game`() {
        assertTrue(source("MimirLibraryCoordinator.kt").contains("HomeExternalSessionElapsedStore.observe(core.context)"))
        assertTrue(
            source("MimirAppCoordinator.kt")
                .contains("state.copy(externalSessionElapsedMillis = library.externalSessionElapsedTotals[state.itemId])"),
        )
    }

    /**
     * Favourite lives in the Y menu on the Library's door, for an emulated game and an Android game alike: the menu is built removable for a favourite, and its Add favorite
     * is the shelf's own membership write, by the item identity the reducer passes (not the game row, which an Android game has none of).
     */
    @Test
    fun `the Library's menu carries favourite through the shelf's own membership write by item identity`() {
        assertTrue(source("MimirAppHomeArtHelpers.kt").contains("removable = game.isFavorite,"))
        assertTrue("an Android game's menu is removable when it is a favourite", source("MimirAppHomeArtHelpers.kt").contains("removable = isGame && favorite == true,"))
        assertTrue("and offers the favourite row only when it was given one", source("MimirAppHomeArtHelpers.kt").contains("offerFavorite = isGame && favorite != null,"))
        val handler = source("MimirAppCoordinator.kt")
            .substringAfter("val onHomeTileMenuAction: (ControllerAction) -> Unit = { action ->")
            .substringBefore("val openLibraryHeroPicker: (GameRow) -> Unit")
        val add = handler.substringAfter("onAddFavorite = {").substringBefore("onLoadArt = {")
        assertTrue(add.contains("itemId ->") && add.contains("homeShelf.addFavorite(itemId)"))
        assertFalse("no game row is needed", add.contains("sourceGame"))
        assertTrue(source("HomeShelfCoordinator.kt").contains("addFavorite = { setMembershipById(it, true) },"))
    }

    /** The Library's Y on an Android card opens that package's menu: the shelf's own, with the favourite state read live from the open card's action state. */
    @Test
    fun `the Android card's Y opens the package's tile menu with its live favourite state`() {
        val dispatch = source("MimirAppInputDispatch.kt")
        val opener = dispatch.substringAfter("onOpenNativeAppTileMenu = { packageName ->").substringBefore("onRequestHideNativePackage")
        assertTrue(opener.contains("bindings.detailActionState"))
        assertTrue(opener.contains("favorite = cardFavorite"))
    }

    /**
     * START from Home's launch card opens Settings and B returns to the card , as on the Library's. The card is an overlay and an overlay answers every press,
     * so the app takes START before the overlay layer, holds the card, and resumes it on B; the held card is neither drawn nor offered input while Settings owns the screen. The
     * achievement card's NEEDS SETUP, the other way into Settings from over the card, holds it the same way, and a card that is up over a destination it is not drawn over is
     * kept off the stack and closed: the dead pad of the final-round re-check.
     */
    @Test
    fun `START and NEEDS SETUP hold Home's card for Settings, B resumes it, and no other path leaves it over Settings`() {
        val dispatch = source("MimirAppInputDispatch.kt")
        val overlayDispatch = dispatch.indexOf("bindings.overlays.dispatch(action)?.let { return it }")
        val hold = dispatch.indexOf("bindings.homeLaunchCard.holdForSettingsVisit(navigation.destination)")
        assertTrue("the hold is taken before the overlay layer can swallow START", hold in 1 until overlayDispatch)
        val block = dispatch.substring(dispatch.lastIndexOf("if (action == ControllerAction.SYSTEM_START &&", hold), hold + 200)
        assertTrue(block.contains("bindings.overlays.top == MimirOverlayKind.HOME_LAUNCH_CARD"))
        assertTrue(block.contains("navigation.destination == MimirDestination.HOME"))
        val coordinator = source("MimirAppCoordinator.kt")
        val needsSetup = coordinator.substringAfter("onOpenRetroAchievementsSettings = {").substringBefore("core = core,")
        assertTrue("NEEDS SETUP holds the card before it opens Settings", needsSetup.indexOf("homeLaunchCard.holdForSettingsVisit(navigation.destination)") in 1 until needsSetup.indexOf("navigation.openRetroAchievementsSettings"))
        assertTrue(coordinator.contains("homeLaunchCardOverlayOpen("))
        assertTrue(coordinator.contains("destination = navigation.destination,"))
        assertTrue("the destination change settles the card", coordinator.contains("homeLaunchCard.settleForDestination(navigation.destination, navigation.homeLaunchCardReturnRequest)"))
        assertTrue(source("MimirAppHost.kt").contains("visible = bindings.homeLaunchCard.session != null && !bindings.homeLaunchCard.suspendedForSettings &&"))
        assertTrue(source("MimirNavigationCoordinator.kt").contains("homeLaunchCardReturnRequest = target == null"))
    }

    /** The Library card's Settings return can land on Restart, as on Play, Open with, Achievements and the description; whether it does is decided from the parked game. */
    @Test
    fun `the Library card's Settings return can land on Restart, decided from the parked game`() {
        val nav = source("MimirNavigationCoordinator.kt")
        assertTrue(nav.contains("LibraryDetailRestartKey, LibraryDetailOpenWithKey, LibraryDetailAchievementsKey ->"))
        assertTrue("Play at once for a game that is not parked", nav.contains("HomeLaunchCardResume.parkedGameId() !="))
        assertTrue("and the whole allowance for one that is", !nav.contains("LibraryDetailRestartRegistrationFrames"))
    }

    /** Home's Y menus (the card's, the shelf tile's, a Tome member's) all come from the one builder, which asks the game whether Enrich leads. */
    @Test
    fun `every Home Y menu is built from the game, by the one builder`() {
        for (name in listOf("MimirAppCoordinator.kt", "MimirAppInputDispatch.kt", "MimirAppHost.kt")) {
            assertFalse("$name builds no Home tile menu by hand", Regex("""(?<![A-Za-z])HomeTileMenuState\(\s*row""").containsMatchIn(source(name)))
        }
        assertTrue(source("MimirAppHomeArtHelpers.kt").contains("offerEnrich = game.tileMenuOffersEnrich(),"))
        assertTrue(source("MimirAppHomeArtHelpers.kt").contains("offerEnrich = game?.tileMenuOffersEnrich() == true,"))
    }

    /** The Library has one dispatch entry now: nothing in the app hands it a favourite-toggle callback any more. */
    @Test
    fun `no shelf-membership callback is handed to the Library's dispatcher`() {
        assertFalse(source("MimirAppInputDispatch.kt").contains("onSetLibraryDetailShelfMembership"))
    }
}
