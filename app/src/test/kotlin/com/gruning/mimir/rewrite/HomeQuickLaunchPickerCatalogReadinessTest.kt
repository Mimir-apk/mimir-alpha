package com.gruning.mimir.rewrite

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * the "No apps found." window.
 *
 * `items.isEmpty()` alone cannot distinguish "the catalog genuinely has zero apps" from "the catalog
 * hasn't reported yet" ([CompanionQuickLaunchCatalog.entries] starts every process empty). A fast
 * dock→"All apps" press could otherwise mount this picker before that catalog's own `refresh()` -- kicked
 * off when the dock opened, `MimirAppCoordinator.kt`'s `LaunchedEffect(navigation.homeQuickLaunchOpen)` --
 * has landed, painting "No apps found." for a moment before popping to the grid once the real list
 * arrives. That is a false statement shown to the player, briefly.
 *
 * The fix (`HomeQuickLaunchPickerOverlay.kt`'s `catalogReportedEmpty` block) only shows the message once
 * `CompanionQuickLaunchCatalog.refresh()` has actually completed and its result confirms emptiness. This
 * file drives that decision through the composable's `reportCatalogEmpty` parameter -- a TEST SEAM ONLY,
 * defaulted to the real refresh-and-read in production. It exists because
 * `CompanionQuickLaunchCatalog`/`NativeShelfPackages.kt`'s `coalescedSnapshot` are module-globals this
 * file cannot reset between tests in isolation -- driving the real `CompanionQuickLaunchCatalog.refresh()` here would flake exactly the way
 * `HomeQuickLaunchPickerIconWarmStartTest`'s kdoc documents `withContext(Dispatchers.IO)` already did on
 * this harness: `waitForIdle()` does not await it, and another test's scan could warm the coalescing window
 * from underneath this one.
 *
 * MATCHER NOTE, discovered writing this file: `MimirChromeText`/`MimirIntentText`
 * (`core-ui/.../MimirTextIntent.kt`) render via `Text(..., modifier = modifier.clearAndSetSemantics {
 * contentDescription = resolved.spokenText })` -- the Text/EditableText semantics property is WIPED, and
 * `StaticChrome`'s on-screen glyphs are upper-cased besides. `onNodeWithText("No apps found.")` therefore
 * matches nothing; this file uses [hasContentDescription] on the natural-case spoken string, the same
 * seam [HomeQuickLaunchPickerIconWarmStartTest] already documented for the picker tile's label.
 *
 * Every wait below is bounded and guarded against vacuity: the "stays suppressed" tests prove their
 * `reportCatalogEmpty` actually ran (a `started`/`observed` signal) before asserting the negative, so a
 * test that trivially never triggers the effect cannot pass by accident.
 *
 * DIFFERENTIAL REPLAY: this whole file fails to COMPILE against the pre-fix `HomeQuickLaunchPickerOverlay`
 * (the `reportCatalogEmpty` parameter does not exist there) -- a compilation-level differential, since there is no way for these tests to pass vacuously against the unfixed composable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeQuickLaunchPickerCatalogReadinessTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an unresolved catalog report suppresses the empty message`() {
        val pending = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        setPickerContent(reportCatalogEmpty = {
            started.complete(Unit)
            pending.await()
        })

        // Proves the effect really is in flight (not merely never triggered) before trusting the absence.
        compose.waitUntil(WaitMs) { started.isCompleted }
        compose.waitForIdle()
        noAppsFoundNode().assertDoesNotExist()
    }

    @Test
    fun `resolving a pending report from unresolved to confirmed-empty flips the message on`() {
        val pending = CompletableDeferred<Boolean>()
        setPickerContent(reportCatalogEmpty = { pending.await() })
        noAppsFoundNode().assertDoesNotExist()

        // The exact race this item closes: a moment later, the catalog actually reports.
        pending.complete(true)
        compose.waitUntil(WaitMs) { noAppsFoundNodes().fetchSemanticsNodes().isNotEmpty() }
        noAppsFoundNode().assertExists()
    }

    @Test
    fun `a report that confirms empty shows the existing message directly`() {
        setPickerContent(reportCatalogEmpty = { true })

        compose.waitUntil(WaitMs) { noAppsFoundNodes().fetchSemanticsNodes().isNotEmpty() }
        noAppsFoundNode().assertExists()
    }

    @Test
    fun `a report that finds apps never shows the empty message`() {
        val observed = CompletableDeferred<Unit>()
        setPickerContent(reportCatalogEmpty = {
            observed.complete(Unit)
            false
        })

        compose.waitUntil(WaitMs) { observed.isCompleted }
        compose.waitForIdle()
        noAppsFoundNode().assertDoesNotExist()
    }

    @Test
    fun `a failed catalog report stays suppressed rather than asserting emptiness`() {
        val observed = CompletableDeferred<Unit>()
        setPickerContent(reportCatalogEmpty = {
            observed.complete(Unit)
            throw IllegalStateException("boom")
        })

        // Unknown must not render as "there is nothing" any more than "not loaded yet" should.
        compose.waitUntil(WaitMs) { observed.isCompleted }
        compose.waitForIdle()
        noAppsFoundNode().assertDoesNotExist()
    }

    /** Single-node interaction for exists/doesNotExist assertions. */
    private fun noAppsFoundNode() =
        compose.onNode(hasContentDescription(NoAppsFound), useUnmergedTree = true)

    /** Collection interaction for the bounded polls -- `fetchSemanticsNodes()` needs the plural query. */
    private fun noAppsFoundNodes() =
        compose.onAllNodes(hasContentDescription(NoAppsFound), useUnmergedTree = true)

    private fun setPickerContent(reportCatalogEmpty: suspend () -> Boolean) {
        val authority = DefaultFocusAuthority()
        compose.setContent {
            MimirTheme {
                HomeQuickLaunchPickerOverlay(
                    authority = authority,
                    mode = HomeQuickLaunchPickerMode.General,
                    items = emptyList(),
                    presentationEpoch = 1L,
                    initialGeneralPackageName = null,
                    onGeneralFocusChanged = {},
                    onPresentationReadyChanged = {},
                    onChoose = {},
                    onDismiss = {},
                    reportCatalogEmpty = reportCatalogEmpty,
                )
            }
        }
    }

    private companion object {
        const val NoAppsFound = "No apps found."

        /** Generous: bounds a suspended effect, and a slow CI box must not turn it into a flake. */
        const val WaitMs = 10_000L
    }
}
