package com.gruning.mimir.rewrite

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the Compose harness composes, lays out and reports node bounds on the JVM.
 *
 * It exists as its own test so that when a real UI gate fails, "the harness is broken" is ruled out
 * in one run rather than debugged inside the gate. If this is red, nothing else in a Compose test is
 * worth reading.
 */
/*
 * `application = Application::class` is deliberate: Robolectric would otherwise construct Mimir's own
 * Application, whose startup initialises WorkManager and the rest of the app graph. A UI gate should
 * compose the composable under test and nothing else — pulling app startup in makes it a slow
 * integration test that fails for reasons unrelated to what it asserts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ComposeHarnessSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the harness composes and can assert a node is displayed`() {
        compose.setContent { Text("mimir harness") }
        compose.onNodeWithText("mimir harness").assertIsDisplayed()
    }
}
