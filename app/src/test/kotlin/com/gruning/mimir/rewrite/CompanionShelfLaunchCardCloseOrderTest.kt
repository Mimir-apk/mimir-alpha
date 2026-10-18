package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6-F-11: with the shelf launch card open, a Bottom swipe back used to run straight to
 * `platformDestination != null -> CompanionLibraryRoute.backToLattice()`, backing the whole platform
 * shelf out to the lattice instead of closing just the card sitting on top of it. The card's own open
 * state and its close are Compose-local (`CompanionPlatformShelf`'s `pending`), and the resolving cascade
 * lives inside a `@Composable`'s `LaunchedEffect` with no pure-function seam to unit-test directly — so,
 * like `CompanionPlatformShelfAdmissionTest`'s existing source-text pins in this same file's neighbourhood,
 * this pins the two facts a device pass can't cheaply re-check on every change: the card joins the
 * closable-layer union, and its arm in the close cascade is resolved before the shelf's own.
 */
class CompanionShelfLaunchCardCloseOrderTest {
    @Test
    fun `the shelf launch card closes before the platform shelf backs out`() {
        val presentation = presentationSource()
        assertTrue(
            "shelfLaunchCardOpen must join the closable-layer union feeding CompanionBottomFolderState",
            presentation.contains("shelfLaunchCardOpen ||"),
        )
        val cascade = presentation.substringAfter("val closedLayer = when {", "")
        assertTrue("the resolving cascade must exist", cascade.isNotEmpty())
        val cardArmIndex = cascade.indexOf("shelfLaunchCardOpen ->")
        val shelfArmIndex = cascade.indexOf("platformDestination != null ->")
        assertTrue("the card's own arm must exist in the cascade", cardArmIndex >= 0)
        assertTrue("the platform shelf's own arm must exist in the cascade", shelfArmIndex >= 0)
        assertTrue(
            "the card sits ON the shelf, so its arm must resolve before the shelf backs out",
            cardArmIndex < shelfArmIndex,
        )
    }

    private fun presentationSource(): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()
}
