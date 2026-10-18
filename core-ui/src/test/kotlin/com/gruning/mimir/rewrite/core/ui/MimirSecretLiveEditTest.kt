package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the in-app secret keyboard takes no text; the OWNER's own state is read and written at
 * call time for every op. A held backspace invokes the same key action many times between recompositions and one
 * space drag emits many caret ops in one gesture event; a caret or text captured at composition made the repeats
 * collapse into one (red before this round: only one character moved or went).
 *
 * Red-before is shown with the previous API shape (`MimirKeyboard(value, secret = true)`): five repeats deleted one
 * character and a five-step drag moved one. These are the same scenarios on the owner-based API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirSecretLiveEditTest {

    @get:Rule
    val compose = createComposeRule()

    /** The plaintext is drawn, never a node: no node's text, description or editable text may hold it, merged or not. */
    private fun assertNoNodeHoldsTheSecret(secret: String) {
        compose.onAllNodes(
            SemanticsMatcher("a node whose text or description holds the secret") { node ->
                val config = node.config
                config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(secret) } == true ||
                    config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(secret) } == true ||
                    config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(secret) == true
            },
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun fiveHeldBackspaceRepeatsDeleteFiveCharacters() {
        var text by mutableStateOf("hunter2abcdef")
        compose.setContent {
            MimirTheme {
                MimirSecretFieldKeyboard(
                    owner = rememberMimirSecretStringOwner(readText = { text }, writeText = { text = it }),
                    onDone = {},
                )
            }
        }
        val action = compose.onNodeWithContentDescription("Backspace")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // The held key repeats between frames: five invocations, no recomposition in between.
        compose.runOnUiThread { repeat(5) { action() } }
        compose.waitForIdle()
        assertEquals("hunter2a", text)
    }

    @Test
    fun aFiveStepSpaceDragMovesTheCaretFiveCharacters() {
        var text by mutableStateOf("abcdefghij")
        compose.setContent {
            MimirTheme {
                MimirSecretFieldKeyboard(
                    owner = rememberMimirSecretStringOwner(readText = { text }, writeText = { text = it }),
                    onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Space").performTouchInput {
            down(center)
            moveBy(Offset(-(16f * 5f + 2f) * density, 0f))
            up()
        }
        compose.onNodeWithContentDescription("x").performClick()
        assertEquals("the caret moved five places left of the end, then x went in", "abcdexfghij", text)
    }

    @Test
    fun typedKeysLandAtTheCaretAndShowHideRevealsOnlyWhilePressed() {
        var text by mutableStateOf("")
        compose.setContent {
            MimirTheme {
                MimirSecretFieldKeyboard(
                    owner = rememberMimirSecretStringOwner(readText = { text }, writeText = { text = it }),
                    onDone = {},
                )
            }
        }
        listOf("p", "w", "d").forEach { compose.onNodeWithContentDescription(it).performClick() }
        assertEquals("pwd", text)

        // Hidden: the caption is dots only; the secret text is nowhere in the tree.
        compose.onAllNodesWithText("pwd", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password), useUnmergedTree = true)
            .assertCountEquals(1)
        // Shown: the OWNER's text is drawn, outside the keyboard; the keyboard's own caption stays dots.
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Password shown").assertExists()
        assertNoNodeHoldsTheSecret("pwd")
        val caption = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password), useUnmergedTree = true)
        caption.assertCountEquals(1)
        assertEquals(
            "the keyboard caption never shows the secret, revealed or not",
            "•••",
            caption[0].fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
        // Hidden again: gone from the tree.
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.onAllNodesWithText("pwd", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Password shown").assertCountEquals(0)
        // A key typed after the toggle still lands at the caret, read live.
        compose.onNodeWithContentDescription("z").performClick()
        assertEquals("pwdz", text)
    }

    @Test
    fun aTextChangedBehindTheKeyboardsBackMovesTheCaretToItsEnd() {
        var text by mutableStateOf("abc")
        compose.setContent {
            MimirTheme {
                MimirSecretFieldKeyboard(
                    owner = rememberMimirSecretStringOwner(readText = { text }, writeText = { text = it }),
                    onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("x").performClick()
        assertEquals("abcx", text)
        text = "restored"
        compose.waitForIdle()
        compose.onNodeWithContentDescription("y").performClick()
        assertEquals("restoredy", text)
    }

    @Test
    fun theRevealIsHiddenAgainWhenTheKeyboardClosesAndReopens() {
        var text by mutableStateOf("secret")
        var open by mutableStateOf(true)
        compose.setContent {
            MimirTheme {
                val owner = rememberMimirSecretStringOwner(readText = { text }, writeText = { text = it })
                if (open) MimirSecretFieldKeyboard(owner = owner, onDone = {})
            }
        }
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Password shown").assertExists()
        assertNoNodeHoldsTheSecret("secret")
        open = false
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Password shown").assertCountEquals(0)
        open = true
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Password shown").assertCountEquals(0)
        assertNoNodeHoldsTheSecret("secret")
        compose.onNodeWithContentDescription("Show password").assertExists()
    }
}
