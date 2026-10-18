package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Product review 2026-09-28 (F2): the keyboard's controller legend used to read "B DONE" in every host, including
 * the RetroAchievements editor, where B now leaves (F1). The word after B is the host's to choose.
 *
 * The legend's word is chrome text, so it reaches semantics as a content description. The Done key also carries
 * "Done" as its description, so the counts below are taken relative to the same keyboard with the default word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirKeyboardControllerLegendTest {

    @get:Rule
    val compose = createComposeRule()

    private fun described(text: String): Int =
        compose.onAllNodesWithContentDescription(text, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun `the legend word follows the host parameter`() {
        var legendAction by mutableStateOf("Done")
        compose.setContent {
            MimirTheme {
                MimirKeyboard(value = "", onValueChange = {}, onDone = {}, controllerLegendAction = legendAction)
            }
        }
        compose.waitForIdle()
        val doneWithDoneLegend = described("Done")
        assertEquals("no Back anywhere while the legend says Done", 0, described("Back"))

        compose.runOnIdle { legendAction = "Back" }
        compose.waitForIdle()

        assertEquals("the legend now says Back", 1, described("Back"))
        assertEquals("only the legend's Done went away, the Done key stays", doneWithDoneLegend - 1, described("Done"))
    }

    @Test
    fun `a host that prints its own legend can hide the keyboard's`() {
        compose.setContent {
            MimirTheme {
                MimirKeyboard(
                    value = "",
                    onValueChange = {},
                    onDone = {},
                    showControllerLegend = false,
                    controllerLegendAction = "Back",
                )
            }
        }
        compose.waitForIdle()
        assertEquals(0, described("Back"))
    }

    @Test
    fun `the RetroAchievements editor says Back and the Tome editor drops the duplicate`() {
        val raModal = source("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/RetroAchievementsKeyEditModal.kt")
        assertTrue(raModal.contains("controllerLegendAction = " + '"' + "Back" + '"'))
        val tome = source("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeTomeLibraryOverlay.kt")
        val tomeKeyboard = tome.substringAfter("targetLabel = stringResource(if (editing)").substringBefore("modifier = Modifier")
        assertTrue(tomeKeyboard.contains("showControllerLegend = false"))
    }

    private fun source(path: String): String =
        listOf(File("../" + path), File(path)).first(File::isFile).readText()
}
