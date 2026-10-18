package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
 * A regression exposed the ScreenScraper password showed in plain text in the keyboard's field while
 * the line above it showed dots. A secret keyboard never draws its characters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirKeyboardSecretTest {

    @get:Rule
    val compose = createComposeRule()

    private val sample = "tEst-v4lue"

    @Test
    fun `a secret keyboard draws a dot per character and marks the field as a password`() {
        compose.setContent {
            MimirTheme {
                MimirSecretFieldKeyboard(
                    owner = rememberMimirSecretStringOwner(readText = { sample }, writeText = {}),
                    onDone = {},
                )
            }
        }
        compose.onAllNodesWithText(sample, substring = true, useUnmergedTree = true).assertCountEquals(0)
        val passwordFields = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password), useUnmergedTree = true)
        passwordFields.assertCountEquals(1)
        val shown = passwordFields[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("one dot per character", sample.length, shown.length)
        assertTrue("no character of the value is drawn: $shown", shown.none { it in sample })
    }

    @Test
    fun `an ordinary keyboard still shows what is typed`() {
        compose.setContent {
            MimirTheme {
                MimirKeyboard(value = sample, onValueChange = {}, onDone = {})
            }
        }
        compose.onNodeWithText(sample, substring = true, useUnmergedTree = true).assertExists()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun `the ScreenScraper password step hands its mask to the keyboard`() {
        val source = listOf(
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkConnectOverlay.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkConnectOverlay.kt"),
        ).first(File::isFile).readText()
        val step = source.substringAfter("private fun KeyboardStep(")
        assertTrue(step.contains("if (mask)"))
        assertTrue(step.contains("MimirSecretFieldKeyboard("))
    }

    @Test
    fun `a secret longer than the bound draws the bound plus an overflow mark, never one dot per character`() {
        compose.setContent {
            MimirTheme {
                val long = "x".repeat(5_000)
                MimirSecretFieldKeyboard(
                    owner = rememberMimirSecretStringOwner(readText = { long }, writeText = {}),
                    onDone = {},
                )
            }
        }
        val passwordFields = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password), useUnmergedTree = true)
        passwordFields.assertCountEquals(1)
        val shown = passwordFields[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(MimirSecretDisplayBound + 1, shown.length)
        assertTrue(shown.none { it == 'x' })
    }
}
