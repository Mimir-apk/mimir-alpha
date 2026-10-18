package com.gruning.mimir.rewrite

import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * The chrome text in this app is `clearAndSetSemantics { contentDescription = spokenText }` over a visual Text (MimirIntentText),
 * so a label is reached by either its text or its description, and in the unmerged tree (a pill merges its children). These finders
 * match both, case-blind, and name how a miss is read.
 */
internal fun ComposeTestRule.labelled(text: String, exact: Boolean = true): SemanticsNodeInteractionCollection =
    onAllNodes(
        hasText(text, substring = !exact, ignoreCase = true) or hasContentDescription(text, substring = !exact, ignoreCase = true),
        useUnmergedTree = true,
    )

/** Whether anything carries the label: drawn, whatever its alpha. */
internal fun ComposeTestRule.shows(text: String, exact: Boolean = true): Boolean = labelled(text, exact).fetchSemanticsNodes().isNotEmpty()
