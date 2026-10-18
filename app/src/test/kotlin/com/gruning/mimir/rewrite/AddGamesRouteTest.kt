package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class AddGamesRouteTest {

    @Test
    fun `the in-app browser is the path whenever Mimir can read storage itself`() {
        assertEquals(
            AddGamesRoute.IN_APP_BROWSER,
            addGamesRoute(sourceOperationRunning = false, allFilesAccessGranted = true),
        )
    }

    @Test
    fun `the system picker is reached only when Mimir cannot enumerate storage`() {
        assertEquals(
            "All-files access is user-revocable at any time, so this path is live, not theoretical — " +
                "deleting it would leave a player who revoked it unable to add games at all",
            AddGamesRoute.SYSTEM_PICKER,
            addGamesRoute(sourceOperationRunning = false, allFilesAccessGranted = false),
        )
    }

    @Test
    fun `busy outranks both, because it decides whether any chooser may open`() {
        assertEquals(
            AddGamesRoute.BUSY,
            addGamesRoute(sourceOperationRunning = true, allFilesAccessGranted = true),
        )
        assertEquals(
            AddGamesRoute.BUSY,
            addGamesRoute(sourceOperationRunning = true, allFilesAccessGranted = false),
        )
    }

    @Test
    fun `granted access never routes to the system picker`() {
        // The whole point: every entry into documentsui costs the player a fan surge, so the fallback
        // must never be chosen while the in-app path is available.
        assertEquals(
            AddGamesRoute.IN_APP_BROWSER,
            addGamesRoute(sourceOperationRunning = false, allFilesAccessGranted = true),
        )
    }
}
