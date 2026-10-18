package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the Default sun moves below the text column on 4:3 and square fields. Home and Library bake
 * the same scene onboarding does, and the three surfaces must agree on the sun's place on one device, or the orb jumps
 * on the way from onboarding's last page to Home. Pinned as source, the way the quiet light's wiring is read.
 */
class ShelfSceneLightBelowTextWiringTest {

    @Test
    fun `Home and Library pass the 4x3-or-square decision to the shelf background`() {
        listOf("HomeShelfScreen.kt", "LibraryScreen.kt").forEach { name ->
            val source = source(name)
            // The call, not the import or a comment: the call is the one occurrence followed by a newline.
            val call = source.substringAfter("MimirShelfBackground(\n", missingDelimiterValue = "")
                .substringBefore(") {")
            assertTrue("$name: the MimirShelfBackground call was not found", call.isNotEmpty())
            assertTrue("$name must pass sceneLightBelowText", "sceneLightBelowText = sceneLightBelowText," in call)
            assertTrue("$name must keep the quiet light", "quietSceneLight = " in call)
            assertTrue(
                "$name must decide from the viewport profile, never a device model",
                "Profile.sceneLightBelowText" in source,
            )
        }
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.first(File::isFile).readText()
    }
}
