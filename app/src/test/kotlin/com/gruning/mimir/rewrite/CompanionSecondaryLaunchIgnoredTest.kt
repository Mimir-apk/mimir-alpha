package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA D17 (2026-09-11, P3-1): the second touch of a fast double tap on a rail pin reached the Bottom (ACTION_DOWN
 * 13:48:16.955, 28 ms after the first) and left no line, because the launch lock disables the seat. Every host that
 * renders a lockable seat now logs each dropped tap once, on the runtime tag, with the launch it was waiting for.
 *
 * The three hosts are windows a JVM test cannot compose, so their wiring is pinned as source (whitespace-insensitive,
 * so a reformat is not a failure). That the seats call back once per tap, and not on an unlocked tap, is exercised on
 * the real composables in `CompanionLaunchLockedTapTest`.
 */
class CompanionSecondaryLaunchIgnoredTest {

    @Test
    fun `the line names the target, the reason and the pending operation`() {
        // P3-1's own operation id.
        val op = "13aba890-48c2-4f9f-81ca-e9fc00a13c2e"
        assertEquals(
            "secondary launch ignored target=com.google.android.youtube reason=launch_locked op=$op",
            secondaryLaunchIgnoredLogMessage("com.google.android.youtube", op),
        )
        assertEquals(
            "secondary launch ignored target=all_apps reason=launch_locked op=null",
            secondaryLaunchIgnoredLogMessage("all_apps", null),
        )
    }

    private fun normalizedSource(name: String): String {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/$name")
        assertTrue("$name must be readable (${file.absolutePath})", file.isFile)
        return file.readText().filterNot(Char::isWhitespace)
    }

    private fun wiring(pendingOp: String): String =
        "onQuickLaunchIgnored={target->" +
            "Log.i(\"MimirCompanionRuntime\",secondaryLaunchIgnoredLogMessage(target,$pendingOp))},"

    @Test
    fun `the surface and the Presentation-hosted drawer log with the Presentation's pending launch`() {
        val source = normalizedSource("MimirCompanionPresentation.kt")
        assertTrue(
            "the surface rail must report its dropped taps",
            "quickLaunchMessage=recents.launchMessage,${wiring("observedSecondaryLaunchId")})" in source,
        )
        assertTrue(
            "the Presentation-hosted drawer must report its dropped taps",
            "onDismiss={CompanionRecentsState.hide()},${wiring("observedSecondaryLaunchId")})" in source,
        )
    }

    @Test
    fun `the overlay-hosted drawer logs with its own pending launch`() {
        val source = normalizedSource("CompanionRecentsOverlayHost.kt")
        assertTrue(
            "the overlay-hosted drawer must report its dropped taps",
            "onDismiss={CompanionRecentsState.hide()},${wiring("pendingLaunchOperationId")})" in source,
        )
        assertTrue(
            "and name the launch its lock is holding for",
            ("valpendingLaunchOperationId=(recents.launchStateas?CompanionDrawerLaunchState.LaunchPending)" +
                "?.operationId?.toString()") in source,
        )
    }
}
