package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryReturnOriginTest {
    @Test
    fun `activity recreation restores exact current origin without changing ordinary cold start`() {
        val recreation = PrimaryReturnOrigin(
            destination = MimirDestination.SETTINGS.name,
            arguments = mapOf("focusKey" to "settings/detail/appearance/theme"),
            revision = 5L,
        )
        val pending = PrimaryReturnOrigin(MimirDestination.BROWSE.name, revision = 6L)

        assertEquals(
            recreation,
            selectPrimaryStartupOrigin(null, activityStateRestored = true, recreationOrigin = recreation),
        )
        assertEquals(
            MimirDestination.HOME.name,
            selectPrimaryStartupOrigin(null, activityStateRestored = false, recreationOrigin = recreation).destination,
        )
        assertEquals(
            pending,
            selectPrimaryStartupOrigin(pending, activityStateRestored = true, recreationOrigin = recreation),
        )
    }

    @Test
    fun `every exact return path blocks input behind the restoration cover`() {
        assertTrue(primaryReturnRestoring(native = true, library = false))
        assertTrue(primaryReturnRestoring(native = false, library = true))
        assertTrue(primaryReturnRestoring(native = false, library = false, home = true))
        assertFalse(primaryReturnRestoring(native = false, library = false))
    }

    @Test
    fun `persisted exact destination is restored outside first run`() {
        assertEquals(
            MimirDestination.SETTINGS,
            resolveStartupDestination(PrimaryReturnOrigin(MimirDestination.SETTINGS.name), firstRun = false),
        )
    }

    @Test
    fun `first run owns startup and malformed persisted destination falls back safely`() {
        assertEquals(
            MimirDestination.ONBOARDING,
            resolveStartupDestination(PrimaryReturnOrigin(MimirDestination.SETTINGS.name), firstRun = true),
        )
        assertEquals(
            MimirDestination.HOME,
            resolveStartupDestination(
                PrimaryReturnOrigin("removed-destination"),
                firstRun = false,
                finalShelfEnabled = true,
            ),
        )
    }

    @Test
    fun `a settings return never raises the rune cover`() {
        //  Library > START > Settings > back raised it every time.
        //
        // Pinned as an ABSENCE, deliberately: the old signature carried a `settings` arm, and the fix is
        // that no such input exists any more. If someone re-adds one, this file stops compiling -- which
        // is a louder failure than an assertion, and the right one for a capability that should not exist.
        //
        // The three surviving arms are LAUNCH returns: the app really was backgrounded and has state to
        // restore. A settings visit never left the process, so there was never anything to cover.
        assertFalse(
            "with every launch-return source quiet, nothing may raise the cover -- a settings return " +
                "is no longer one of its inputs at all",
            primaryReturnRestoring(native = false, library = false, home = false),
        )
    }
}
