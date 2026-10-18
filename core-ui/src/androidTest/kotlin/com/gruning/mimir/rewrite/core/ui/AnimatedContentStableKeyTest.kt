package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Locks the Compose-version contract used by picker pages: index edits re-render without re-fading. */
@RunWith(AndroidJUnit4::class)
class AnimatedContentStableKeyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun samePageKeyStillPublishesTheLatestFocusedIndex() {
        var state by mutableStateOf(PickerPage(page = "art", focusedIndex = 0))
        compose.setContent {
            AnimatedContent(
                targetState = state,
                contentKey = PickerPage::page,
                label = "stablePickerPage",
            ) { presented ->
                Column {
                    repeat(2) { index ->
                        Text(
                            text = "Choice $index",
                            modifier = Modifier.semantics { selected = presented.focusedIndex == index },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Choice 0").assertIsSelected()
        compose.onNodeWithText("Choice 1").assertIsNotSelected()

        compose.runOnIdle { state = state.copy(focusedIndex = 1) }

        compose.onNodeWithText("Choice 0").assertIsNotSelected()
        compose.onNodeWithText("Choice 1").assertIsSelected()
    }

    private data class PickerPage(val page: String, val focusedIndex: Int)
}
