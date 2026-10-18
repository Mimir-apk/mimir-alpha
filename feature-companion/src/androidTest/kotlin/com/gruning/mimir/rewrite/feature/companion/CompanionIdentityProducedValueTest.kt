package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionIdentityProducedValueTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun identityChangeCommitsDestinationFallbackBeforeDelayedDecode() {
        var identity by mutableStateOf("A")
        val gates = mapOf(
            "A" to CompletableDeferred<Unit>(),
            "B" to CompletableDeferred<Unit>(),
        )

        compose.setContent {
            val requestedIdentity = identity
            val decoded = rememberIdentityProducedValue(requestedIdentity) {
                gates.getValue(requestedIdentity).await()
                requestedIdentity
            }
            Text(decoded ?: "fallback:$requestedIdentity")
        }
        compose.onNodeWithText("fallback:A").assertExists()

        compose.runOnIdle { identity = "B" }
        compose.onNodeWithText("fallback:B").assertExists()
        compose.onNodeWithText("A").assertDoesNotExist()

        // Completing A after its composition group was disposed cannot leak it into B's surface.
        gates.getValue("A").complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("fallback:B").assertExists()
        compose.onNodeWithText("A").assertDoesNotExist()

        gates.getValue("B").complete(Unit)
        compose.waitUntil(2_000L) {
            compose.onAllNodesWithText("B").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithText("B").assertExists()
        compose.onNodeWithText("fallback:B").assertDoesNotExist()
    }
}
