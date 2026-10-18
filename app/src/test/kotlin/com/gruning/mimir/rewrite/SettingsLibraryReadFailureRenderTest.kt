package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.settings.LibraryReadModel
import com.gruning.mimir.rewrite.feature.settings.SettingsCategory
import com.gruning.mimir.rewrite.feature.settings.SettingsDetailPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The earlier coordinator change made
 * `MimirLibraryCoordinator.sourceReadFailureMessage` honest but nothing rendered it -- the field fed
 * nobody. This is the render wiring's own differential, at the actual player-visible surface
 * (`SettingsDetailPanel`'s LIBRARY pane, reached from Settings -> Library, the same pane the "Choose
 * Mimir ROM folder" / "Add Library folder" pickers that produce this message live in).
 *
 * Real behavioural test, not source characterization: unlike `MimirLibraryCoordinator` itself (no
 * Robolectric harness in `:app` can drive its `@Composable` closure with a real
 * `MimirAppCoreCoordinator` -- see `MimirLibraryCoordinatorSourceHonestyTest`'s class doc), the render
 * layer is a plain composable taking a `String?` parameter directly, with no
 * coordinator/ViewModel dependency, so a real Compose semantics tree is queried here.
 *
 * Differential, actually run and captured (revert restored after): removing the `if
 * (sourceReadFailureMessage != null) { ... Text(...) }` block from `LibraryPanel`
 * (`feature-settings/SettingsDetail.kt`) turned both `the read-failure message renders on the Library
 * pane when present` and `the note shown after a failed pick clears on the next recomposition after a
 * successful one` red with the identical exact failure (captured from the JUnit XML, byte for byte):
 * `java.lang.AssertionError: Failed: assertExists.
 * Reason: Expected exactly '1' node but could not find any node that satisfies: (Text + EditableText
 * contains 'Couldn’t read this folder.' (ignoreCase: false))`
 * -- restored, green again, file byte-identical to before the revert (diffed against a pre-revert copy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsLibraryReadFailureRenderTest {

    @get:Rule
    val compose = createComposeRule()

    /** Reused verbatim from `MimirLibraryCoordinator.LIBRARY_SOURCE_READ_FAILURE_MESSAGE` -- not retyped;
     *  this is the exact production string literal (typographic apostrophe). */
    private val readFailureMessage = "Couldn’t read this folder."

    private val libraryFixture = LibraryReadModel(
        summary = LibrarySummary(gameCount = 12, platformCount = 3),
        sources = emptyList(),
    )

    @Test
    fun `the read-failure message renders on the Library pane when present`() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(400.dp).requiredHeight(900.dp)) {
                    SettingsDetailPanel(
                        category = SettingsCategory.LIBRARY,
                        authority = DefaultFocusAuthority(),
                        onRowActivate = {},
                        library = libraryFixture,
                        sourceReadFailureMessage = readFailureMessage,
                    )
                }
            }
        }

        compose.onNodeWithText(readFailureMessage).assertExists()
    }

    @Test
    fun `the healthy path renders no read-failure note at all`() {
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(400.dp).requiredHeight(900.dp)) {
                    SettingsDetailPanel(
                        category = SettingsCategory.LIBRARY,
                        authority = DefaultFocusAuthority(),
                        onRowActivate = {},
                        library = libraryFixture,
                        sourceReadFailureMessage = null,
                    )
                }
            }
        }

        compose.onNodeWithText(readFailureMessage).assertDoesNotExist()
    }

    /**
     * Pins the clearing behaviour named in the brief as "the half most likely to be wrong": a failure
     * shown, then a successful rescan/addSource (the coordinator overwrites the field with null -- see
     * `MimirLibraryCoordinator.runSourceOperation`), then the note must be gone on that recomposition --
     * not just absent on a fresh compose. Drives one real Compose recomposition through a state flip
     * rather than comparing two independent compositions, so this exercises the actual
     * mount-then-clear sequence, not merely "both ends look right in isolation."
     */
    @Test
    fun `the note shown after a failed pick clears on the next recomposition after a successful one`() {
        var message by mutableStateOf<String?>(readFailureMessage)
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredWidth(400.dp).requiredHeight(900.dp)) {
                    SettingsDetailPanel(
                        category = SettingsCategory.LIBRARY,
                        authority = DefaultFocusAuthority(),
                        onRowActivate = {},
                        library = libraryFixture,
                        sourceReadFailureMessage = message,
                    )
                }
            }
        }
        compose.onNodeWithText(readFailureMessage).assertExists()

        // The coordinator's own clearing mechanism: `runSourceOperation` overwrites
        // `sourceReadFailureMessage` on every completed rescan/addSource, unconditionally --
        // `librarySourceReadFailureMessage(ScanOutcome.Healthy) == null` (pinned separately in
        // `MimirLibraryCoordinatorSourceHonestyTest`). Simulated here at the render boundary by flipping
        // the same parameter this pane actually receives from that field.
        message = null
        compose.waitForIdle()

        compose.onNodeWithText(readFailureMessage).assertDoesNotExist()
    }
}
