package com.gruning.mimir.rewrite.core.companionnative

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Observed on Thor: after `am force-stop com.google.android.youtube` (`pidof` exit 1),
 * ᛗ → TABS logged `entries=1` and drew YouTube with its ✕, and `tabs.v1` still held it. The fix asks the
 * privileged process-presence probe about each candidate tab. These cases pin which rows may be asked
 * about, and which answer alone may retire one.
 *
 * Also observed on Thor: Minish Cap's GAME row outlived RetroArch across restarts. The TABS
 * request logged `tabs liveness checked=1 retired=com.google.android.youtube unknown=0` -- only the APP row
 * was asked -- while `pidof com.retroarch.aarch64` was empty. A GAME row is asked now, unless its launch is
 * still starting.
 */
class SecondaryTabsLivenessTest {
    @Test
    fun `an app the probe found running keeps its tab, whitelisted Chrome included`() {
        // Thor firmware: `forceStopPackage() WhiteList packageName=com.android.chrome`. The stop exits 0 and
        // Chrome keeps running, so only the readback may speak for it -- and it says ALIVE.
        val asked = mutableListOf<String>()

        val retired = secondaryTabsToRetire(listOf(app(CHROME))) { pkg ->
            asked += pkg
            SecondaryPackageLiveness.ALIVE
        }

        assertEquals(emptyList<AppEntry>(), retired)
        assertEquals(listOf(CHROME), asked)
    }

    @Test
    fun `an unanswered probe keeps the tab`() {
        // No authority, no reply, no pidof, a timeout: "could not ask" is not "not running".
        val retired = secondaryTabsToRetire(listOf(app(YOUTUBE))) { SecondaryPackageLiveness.UNKNOWN }

        assertEquals(emptyList<AppEntry>(), retired)
    }

    @Test
    fun `an app tab whose process is absent is retired`() {
        val chrome = app(CHROME)
        val youtube = app(YOUTUBE)

        val retired = secondaryTabsToRetire(listOf(chrome, youtube)) { pkg ->
            if (pkg == YOUTUBE) SecondaryPackageLiveness.ABSENT else SecondaryPackageLiveness.ALIVE
        }

        assertEquals(listOf(youtube), retired)
    }

    @Test
    fun `a game tab whose emulator has no process is retired`() {
        // The Thor at 01:49: Minish Cap's row, no RetroArch process, and a Mimir process with no session for it.
        val asked = mutableListOf<String>()
        val minishCap = minishCap()

        val retired = secondaryTabsToRetire(listOf(minishCap)) { pkg ->
            asked += pkg
            SecondaryPackageLiveness.ABSENT
        }

        assertEquals(listOf(minishCap), retired)
        assertEquals(listOf(RETROARCH), asked)
    }

    @Test
    fun `a game tab whose emulator is running keeps its tab`() {
        val asked = mutableListOf<String>()

        val retired = secondaryTabsToRetire(listOf(minishCap())) { pkg ->
            asked += pkg
            SecondaryPackageLiveness.ALIVE
        }

        assertEquals(emptyList<AppEntry>(), retired)
        assertEquals(listOf(RETROARCH), asked)
    }

    @Test
    fun `an unanswered probe keeps a game tab`() {
        val asked = mutableListOf<String>()

        val retired = secondaryTabsToRetire(listOf(minishCap())) { pkg ->
            asked += pkg
            SecondaryPackageLiveness.UNKNOWN
        }

        assertEquals(emptyList<AppEntry>(), retired)
        assertEquals(listOf(RETROARCH), asked)
    }

    @Test
    fun `a game launch still starting is never asked about and keeps its tab`() {
        // The row is filed at dispatch, before the emulator has a process: a pidof miss then is a game starting.
        val asked = mutableListOf<String>()
        val starting = minishCap()
        val control = game(EMULATOR)

        val retired = secondaryTabsToRetire(
            rows = listOf(starting, control),
            bottomGameLaunchInFlight = { pkg -> pkg == RETROARCH },
        ) { pkg ->
            asked += pkg
            SecondaryPackageLiveness.ABSENT
        }

        // The control differs in one field -- no launch is starting for its package -- so it is asked and retired.
        assertEquals(listOf(control), retired)
        assertEquals(listOf(EMULATOR), asked)
    }

    @Test
    fun `an app tab never consults the in-flight read`() {
        val inFlightReads = mutableListOf<String>()
        val youtube = app(YOUTUBE)

        val retired = secondaryTabsToRetire(
            rows = listOf(youtube),
            bottomGameLaunchInFlight = { pkg ->
                inFlightReads += pkg
                true
            },
        ) { SecondaryPackageLiveness.ABSENT }

        assertEquals(listOf(youtube), retired)
        assertEquals(emptyList<String>(), inFlightReads)
    }

    @Test
    fun `a tab a close request already owns is never asked about`() {
        val asked = mutableListOf<String>()
        val rows = listOf(
            closing(app(YOUTUBE), SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED),
            closing(app(CHROME), SecondaryTaskCloseState.CLOSE_UNCONFIRMED),
            closing(minishCap(), SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED),
        )

        val retired = secondaryTabsToRetire(rows) { pkg ->
            asked += pkg
            SecondaryPackageLiveness.ABSENT
        }

        assertEquals(emptyList<AppEntry>(), retired)
        assertEquals(emptyList<String>(), asked)
    }

    private fun app(packageName: String) = AppEntry(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        displayId = 4,
        lastSeenAtMs = 100L,
    )

    private fun game(packageName: String) = AppEntry(
        packageName = packageName,
        label = "Emulator",
        displayId = 4,
        lastSeenAtMs = 100L,
        kind = SecondaryTaskKind.GAME,
        gameTitle = "Chrono Trigger",
    )

    /** The row `tabs.v1` held on the Thor; `lastSeenAtMs` is a test value (the evidence elides it). */
    private fun minishCap() = AppEntry(
        packageName = RETROARCH,
        label = "RetroArch (AArch64)",
        displayId = 4,
        lastSeenAtMs = 100L,
        kind = SecondaryTaskKind.GAME,
        gameTitle = "The Legend of Zelda : The Minish Cap",
    )

    private fun closing(row: AppEntry, state: SecondaryTaskCloseState) = row.copy(
        closeState = state,
        closeOperationId = UUID(0L, 81L),
        closeRequestedAtElapsedRealtimeMs = 1_000L,
    )

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val CHROME = "com.android.chrome"
        const val RETROARCH = "com.retroarch.aarch64"
        const val EMULATOR = "org.example.emulator"
    }
}
