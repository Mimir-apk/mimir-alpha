package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA-24: the ScreenScraper success card came back on its own,
 * long after the sign-in it reports.
 *
 * The cause is a coupling rather than a missing reset. `connectUi` IS the overlay's stack membership:
 * `MimirOverlayCoordinator.kt:346` reads `artworkConnect = artwork.state.connectUi != null`. So when a
 * higher-priority overlay takes the stack, this surface stops rendering with its state untouched — and
 * when that other overlay closes, ARTWORK_CONNECT becomes the active kind again and re-presents whatever
 * was left sitting in it.
 *
 * Both close paths were always correct (`BackHandler` and `onClose` each call `closeConnect()`); neither
 * runs when the overlay is displaced rather than dismissed, which is why reading only the close paths made
 * this look like a non-defect.
 */
class ArtworkConnectDisplacementTest {

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `a delivered outcome does not survive being displaced`() {
        assertTrue(
            "Done is an outcome already given to the player; it has nothing to resume",
            artworkConnectOutcomeIsTerminal(ArtworkConnectUi.Done("fixture-player", 100, 7)),
        )
        assertTrue(
            "Failed is equally delivered -- re-presenting an old error is the same defect wearing red",
            artworkConnectOutcomeIsTerminal(ArtworkConnectUi.Failed("ScreenScraper is busy right now.")),
        )
    }

    @Test
    fun `a half-finished sign-in does survive being displaced`() {
        // The reason this is scoped rather than "clear it whenever it is not on screen": a transient
        // displacement must not throw away a credential the player is part-way through entering.
        assertFalse(
            "the id step is a flow, not an outcome",
            artworkConnectOutcomeIsTerminal(ArtworkConnectUi.EnterId),
        )
        assertFalse(
            "and the password step holds the id the player already typed",
            artworkConnectOutcomeIsTerminal(ArtworkConnectUi.EnterPassword("fixture-player")),
        )
        assertFalse(
            "Connecting has a request in flight; dropping it would strand the result",
            artworkConnectOutcomeIsTerminal(ArtworkConnectUi.Connecting),
        )
    }

    @Test
    fun `every connect state is classified, so a new one cannot default to surviving`() {
        // `artworkConnectOutcomeIsTerminal` is an exhaustive `when` over the sealed interface, so this is
        // really a statement about the compiler doing the work. Asserted anyway, because the wrong default
        // here is exactly what the defect WAS: before the function existed, every state survived.
        val states = listOf(
            ArtworkConnectUi.EnterId,
            ArtworkConnectUi.EnterPassword("id"),
            ArtworkConnectUi.Connecting,
            ArtworkConnectUi.Done("acct", 1, 0),
            ArtworkConnectUi.Failed("why"),
        )
        val terminal = states.count(::artworkConnectOutcomeIsTerminal)
        assertTrue("exactly the two outcome states are terminal", terminal == 2)
    }

    @Test
    fun `the host clears a displaced outcome, keyed on the active overlay rather than on disposal`() {
        val host = source("MimirAppHost.kt")

        assertTrue(
            "the host must drop a displaced outcome when ARTWORK_CONNECT stops being active",
            host.contains("artwork.state.dismissDisplacedConnectOutcome()"),
        )
        assertTrue(
            "and it must be keyed on `active` -- a DisposableEffect would also fire on ordinary " +
                "recomposition teardown and eat the card while the player is still reading it",
            Regex("""LaunchedEffect\(active\)\s*\{[^}]*dismissDisplacedConnectOutcome""")
                .containsMatchIn(host),
        )
    }

    @Test
    fun `the coupling that caused this is still the coupling, so the fix stays necessary`() {
        // Differential in prose: if overlay membership ever stops being a bare non-null check, this fix can
        // be revisited. While it IS one, displacement cannot clear state on its own and the guard is load
        // bearing. This asserts the premise rather than the fix, so the day the premise changes, this fails
        // and someone re-reads the reasoning instead of inheriting it.
        val coordinator = source("MimirOverlayCoordinator.kt")
        assertTrue(
            "overlay membership is still `connectUi != null`; if that changed, re-derive the QA-24 fix",
            coordinator.contains("artworkConnect = artwork.state.connectUi != null"),
        )
    }
}
