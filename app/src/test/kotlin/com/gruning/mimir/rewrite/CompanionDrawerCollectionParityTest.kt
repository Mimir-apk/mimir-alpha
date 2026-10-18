package com.gruning.mimir.rewrite

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thor Tabs parity pins for the two drawer hosts. These are source integration pins because both
 * drawers are Compose closures inside window hosts; a JVM test cannot click either host without
 * rebuilding the whole Presentation/overlay window. The collection-model pins were removed with the collection switch.
 */
class CompanionDrawerCollectionParityTest {

    private fun source(path: String): String =
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$path").readText()

    @Test
    fun `quick picker handoff raises its owner above instruments and keeps drawer highest`() {
        val presentation = source("MimirCompanionPresentation.kt")
        val surface = java.io.File(
            "../feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()

        assertTrue(
            "the picker must notify the Presentation synchronously",
            surface.contains("onPickerOpenChanged(true)") && surface.contains("onPickerOpenChanged(false)"),
        )
        assertTrue(
            "the picker-owning surface must rise above the Instruments sibling",
            presentation.replace(Regex("\\s+"), " ").contains(
                ".zIndex( if (quickPickerOpen || quickLaunchChoice != null) { CompanionQuickPickerLayerZ",
            ),
        )
        assertTrue(
            "the existing Instruments surface keeps its middle layer",
            presentation.contains("modifier = Modifier.zIndex(CompanionInstrumentsLayerZ)"),
        )
        assertTrue(
            "the existing drawer remains the topmost panel",
            presentation.contains("modifier = Modifier.zIndex(CompanionRecentsDrawerLayerZ)"),
        )
    }

    @Test
    fun `platform shelf stays below every mapped interactive panel`() {
        val presentation = source("MimirCompanionPresentation.kt")

        assertTrue(
            "the platform shelf must keep the contract base layer",
            presentation.contains("private const val CompanionPlatformShelfLayerZ = 1f"),
        )
        // The shelf's z is chosen per frame: the base layer while a platform is shown, the remnant
        // layer (above instruments and picker) only while a leaving shelf finishes its exit with no
        // destination behind it. The pin follows that expression rather than a bare constant, which is
        // what the previous literal missed after the remnant layer landed (33b1970f).
        assertTrue(
            "the platform shelf's z must fall back to the contract base layer",
            Regex(
                """val shelfZ = if \(shelfLeaving != null && platformDestination == null\) \{\s*""" +
                    """CompanionPlatformShelfRemnantLayerZ\s*\} else \{\s*CompanionPlatformShelfLayerZ\s*\}""",
            ).containsMatchIn(presentation) && presentation.contains(".zIndex(shelfZ)"),
        )
        assertTrue(
            "the remnant layer must sit above instruments and the picker but below the drawer",
            presentation.contains("private const val CompanionPlatformShelfRemnantLayerZ = 3.5f"),
        )
        assertTrue(
            "Instruments must be explicitly above the shelf",
            presentation.contains("private const val CompanionInstrumentsLayerZ = 2f"),
        )
        assertTrue(
            "the picker must remain above the shelf",
            presentation.contains("private const val CompanionQuickPickerLayerZ = 3f"),
        )
        assertTrue(
            "the drawer must remain the highest mapped panel",
            presentation.contains("private const val CompanionRecentsDrawerLayerZ = 4f"),
        )
    }

    @Test
    fun `both rails read the instruments seat from the same state`() {
        val presentation = source("MimirCompanionPresentation.kt")
        val seatStates = Regex("""instrumentsShown = ([^,\n]+),""").findAll(presentation).map { it.groupValues[1].trim() }.toList()
        assertTrue("expected the surface rail and the drawer to both pass a seat state, found $seatStates", seatStates.size >= 2)
        assertTrue(
            "the drawer's seat must count the Bottom Tweaker host exactly like the surface rail (surface map F3): $seatStates",
            seatStates.all { it == "secondaryTweakerVisible" },
        )
    }

    @Test
    fun `the overlay-hosted drawer passes catalog readiness like the Presentation-hosted rails`() {
        val overlayHost = source("CompanionRecentsOverlayHost.kt")
        assertTrue(
            "the overlay host must read the catalog sweep flag (surface map F4)",
            overlayHost.contains("val quickLaunchCatalogLoaded by CompanionQuickLaunchCatalog.hasLoaded.collectAsState()"),
        )
        assertTrue(
            "and hand it to the drawer instead of leaving the default of true",
            overlayHost.contains("quickLaunchCatalogLoaded = quickLaunchCatalogLoaded,"),
        )
    }
}
