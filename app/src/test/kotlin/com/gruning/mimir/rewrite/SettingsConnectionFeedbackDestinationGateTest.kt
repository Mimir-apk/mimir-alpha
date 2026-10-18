package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk 32f : a SteamGridDB key added during onboarding produced a "Connection successful!"
 * toast, with a focus jump to the SteamGridDB row, the first time Settings opened afterwards.
 *
 * The cause was that [SettingsConnectionFeedback] kept the toast and the focus row pending until the Settings
 * destination's rail delivered them, and a success recorded over onboarding (or over the Get-art card on the
 * Library or Home) had no Settings destination to deliver it. These pin that a success is recorded only when
 * Settings is the destination under the overlay, and that both halves obey the same gate.
 */
class SettingsConnectionFeedbackDestinationGateTest {

    private val steamGridDbRow = "appearance/enrichment/steamgriddb"
    private val screenScraperRow = "appearance/enrichment/screenscraper"
    private val retroAchievementsRow = "profile/retroachievements"

    /** The destination the app is on, and a feedback object reading it exactly as the settings state does. */
    private class AppAt(var destination: MimirDestination) {
        val feedback = SettingsConnectionFeedback(
            settingsIsDestination = { destination == MimirDestination.SETTINGS },
        )
    }

    @Test
    fun aSuccessRecordedOverOnboardingLeavesNoToastAndNoFocusRow() {
        for (row in listOf(steamGridDbRow, screenScraperRow, retroAchievementsRow)) {
            val app = AppAt(MimirDestination.ONBOARDING)

            app.feedback.succeeded(row)

            assertNull("no toast for $row over onboarding", app.feedback.toast)
            assertNull("no focus row for $row over onboarding", app.feedback.focusRowId)
        }
    }

    @Test
    fun aSuccessFromSettingsGivesBothTheToastAndTheFocusRow() {
        for (row in listOf(steamGridDbRow, screenScraperRow, retroAchievementsRow)) {
            val app = AppAt(MimirDestination.SETTINGS)

            app.feedback.succeeded(row)

            val toast = checkNotNull(app.feedback.toast) { "a toast for $row from Settings" }
            assertEquals(SettingsConnectionFeedback.ConnectionSuccessful, toast.text)
            assertEquals(row, app.feedback.focusRowId)
        }
    }

    @Test
    fun everyDestinationButSettingsRecordsNothing() {
        // The Get-art card opens the same two connect overlays from the Library and Home, so the gate is the
        // destination, not "is onboarding".
        for (destination in MimirDestination.entries.filter { it != MimirDestination.SETTINGS }) {
            val app = AppAt(destination)

            app.feedback.succeeded(steamGridDbRow)
            app.feedback.succeeded(screenScraperRow)

            assertNull("no toast over $destination", app.feedback.toast)
            assertNull("no focus row over $destination", app.feedback.focusRowId)
        }
    }

    @Test
    fun theFirstSettingsOpenAfterOnboardingShowsNoStaleSuccess() {
        val app = AppAt(MimirDestination.ONBOARDING)
        app.feedback.succeeded(steamGridDbRow)
        app.feedback.succeeded(screenScraperRow)
        app.feedback.succeeded(retroAchievementsRow)

        // Onboarding ends and the player opens Settings: the destination's rail and focus effect read exactly
        // these two fields when they compose.
        app.destination = MimirDestination.SETTINGS

        assertNull(app.feedback.toast)
        assertNull(app.feedback.focusRowId)
    }

    @Test
    fun theGateReadsTheDestinationAtTheMomentOfTheSuccessNotAtConstruction() {
        val app = AppAt(MimirDestination.SETTINGS)
        app.destination = MimirDestination.LIBRARY

        app.feedback.succeeded(steamGridDbRow)

        assertNull(app.feedback.toast)
        assertNull(app.feedback.focusRowId)
    }

    @Test
    fun clearPendingDropsBothHalvesAndALaterSuccessStillLands() {
        val app = AppAt(MimirDestination.SETTINGS)
        app.feedback.succeeded(steamGridDbRow)
        val first = checkNotNull(app.feedback.toast)

        app.feedback.clearPending()

        assertNull(app.feedback.toast)
        assertNull(app.feedback.focusRowId)

        app.feedback.succeeded(screenScraperRow)
        val second = checkNotNull(app.feedback.toast)
        assertTrue("a success after a clear is a new event", second.eventId > first.eventId)
        assertEquals(screenScraperRow, app.feedback.focusRowId)
    }

    @Test
    fun theSettingsStateHandsItsDestinationPredicateToTheFeedback() {
        var destination = MimirDestination.ONBOARDING
        val state = MimirSettingsUiState(settingsIsDestination = { destination == MimirDestination.SETTINGS })

        state.connectionFeedback.succeeded(steamGridDbRow)
        assertNull(state.connectionFeedback.toast)
        assertNull(state.connectionFeedback.focusRowId)

        destination = MimirDestination.SETTINGS
        state.connectionFeedback.succeeded(steamGridDbRow)
        assertNotNull(state.connectionFeedback.toast)
        assertEquals(steamGridDbRow, state.connectionFeedback.focusRowId)
    }

    // ---- wiring pins: the composables cannot run on the JVM, so these read the source, as the other pins do.

    private fun mainSource(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("$name not found at ${file.absolutePath}", file.exists())
        return file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun theThreeProducersAllRecordThroughTheOneGatedEntry() {
        val host = mainSource("MimirAppHost.kt")
        val settings = mainSource("MimirSettingsCoordinator.kt")

        assertTrue(host.contains("""connectionFeedback.succeeded("appearance/enrichment/steamgriddb")"""))
        assertTrue(host.contains("""connectionFeedback.succeeded("appearance/enrichment/screenscraper")"""))
        assertTrue(settings.contains("""connectionFeedback.succeeded("profile/retroachievements")"""))
    }

    @Test
    fun theSettingsStateIsBuiltOnTheLiveNavigationDestination() {
        val settings = mainSource("MimirSettingsCoordinator.kt")

        assertTrue(
            "MimirSettingsUiState must be given the LATEST navigation's `destination == MimirDestination.SETTINGS`.",
            settings.contains("val currentNavigation by rememberUpdatedState(navigation)") &&
                settings.contains(
                    "settingsIsDestination = { currentNavigation.destination == MimirDestination.SETTINGS }",
                ),
        )
    }

    @Test
    fun theSettingsDestinationDropsWhatItNeverDeliveredWhenItLeavesComposition() {
        val host = mainSource("MimirAppHost.kt")

        assertTrue(
            "MimirSettingsDestination must clear the pending feedback on dispose.",
            Regex("""DisposableEffect\(feedback\)\s*\{\s*onDispose\s*\{\s*feedback\.clearPending\(\)\s*\}\s*\}""")
                .containsMatchIn(host),
        )
    }
}
