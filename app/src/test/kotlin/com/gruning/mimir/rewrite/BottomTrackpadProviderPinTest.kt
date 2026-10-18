package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pad4 : the Trackpad plate is on EVERY Bottom header, so the gateway is provided ONCE, around
 * everything the Presentation draws: the lattice (CompanionSurface), the shelf (CompanionPlatformShelf) and the Activity page
 * (CompanionWorkspaceSurface) all sit below it. A source pin, because the Presentation cannot be composed on the JVM.
 */
class BottomTrackpadProviderPinTest {

    private val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt").readText()

    @Test
    fun theGatewayIsProvidedOnceAndEverySurfaceThatOwnsAHeaderIsBelowIt() {
        val provides = "CompositionLocalProvider(LocalBottomTrackpadOpen provides openTrackpad)"
        assertEquals("one provider, not one per surface", 1, Regex(Regex.escape(provides)).findAll(source).count())
        val at = source.indexOf(provides)
        val setContent = source.indexOf("setContent {\n                val context = LocalContext.current")
        assertTrue("it is the first thing inside the window's content", setContent in 0 until at && at - setContent < 1_200)
        listOf("CompanionWorkspaceSurface(", "CompanionPlatformShelf(", "CompanionSurface(").forEach { surface ->
            val calls = Regex(Regex.escape(surface)).findAll(source).map { it.range.first }.toList()
            assertTrue("$surface is composed in this file", calls.isNotEmpty())
            assertTrue("every $surface call is below the provider: $calls vs $at", calls.all { it > at })
        }
    }

    @Test
    fun theGatewayIsTheMenuRowsGatewayAndOnlyWhereTheDeviceCanHostAPad() {
        assertTrue(source.contains("BottomTrackpadMenuGateway(context).open()"))
        assertTrue(source.contains("if (MimirTrackpadRuntime.capability(context)) open else null"))
        assertFalse("the per-surface local is gone", source.contains("LocalCompanionTrackpadOpen"))
    }

    @Test
    fun everyBottomHeaderOffersThePlateThroughTheSameLocal() {
        val root = "../"
        val lattice = File(root + "feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt").readText()
        val shelf = File(root + "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionShelfHeader.kt").readText()
        val activity = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityBento.kt").readText()
        listOf("lattice" to lattice, "shelf" to shelf, "activity" to activity).forEach { (name, text) ->
            assertTrue("the $name header reads the gateway", text.contains("LocalBottomTrackpadOpen.current"))
            assertTrue("the $name header draws the shared plate", text.contains("BottomTrackpadPlate("))
        }
    }
}
