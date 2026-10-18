package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O13 : one Android notification-permission prompt, the first time a background feature is
 * turned on; never over a game or an app on Main; never again, whatever the answer.
 *
 * Every rule runs against [NotificationPermissionAsker] with the Android facts replaced by plain values, and every
 * test carries a control arm that flips exactly the fact under test, so each assertion is shown able to fail. The
 * Android half (the real ledger, the overlay's own enable path, a decline) is [NotificationPermissionAskRobolectricTest];
 * the call sites are pinned by [NotificationPermissionAskWiringTest].
 */
class NotificationPermissionAskTest {

    @Test
    fun `the first enable asks, and nothing asks without an enable`() {
        val rig = Rig()

        // Control: every condition for a dialog holds (Home in front on a free Main, permission missing, never asked)
        // but nothing was turned on. Resuming Home, or an unarmed M-button start, must not ask.
        assertNull(rig.asker.onHomeInFront())
        assertNull(rig.asker.onMButtonKeepAliveStarted())
        assertEquals(0, rig.host.launches)
        assertTrue(rig.lines.isEmpty())

        val decision = rig.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)

        assertEquals(NotificationAskOutcome.SHOWN, decision.outcome)
        assertEquals("the first enable launches Android's dialog exactly once", 1, rig.host.launches)
        assertTrue(rig.ledger.asked)
        assertEquals(NotificationAskFeature.PERFORMANCE_OVERLAY, rig.ledger.askedFeature)
        assertEquals(listOf("permission ask=shown reason=feature-on feature=performance-overlay"), rig.lines)
    }

    /**
     * Nova 2026-10-01 09:31:24: a D-pad step on the performance overlay row turned it on and asked for POST_NOTIFICATIONS, and a second
     * right press landed on the row underneath the dialog and moved Games only to Always. While the ask is pending the row that asked takes
     * no steps; the answer (either one) releases it.
     */
    @Test
    fun `the row that asked ignores steps until the permission answer returns`() {
        val rig = Rig()
        assertFalse(rig.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))

        rig.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)

        assertTrue("the dialog is up: the overlay row must ignore a second step", rig.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))
        // Control: only the row that asked is held.
        assertFalse(rig.asker.isPending(NotificationAskFeature.M_BUTTON))

        rig.asker.onPermissionResult(granted = false)
        assertFalse("the answer releases the row", rig.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))
    }

    @Test
    fun `a grant releases the row as a decline does, and a dialog that never launched never held it`() {
        val granted = Rig()
        granted.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        granted.asker.onPermissionResult(granted = true)
        assertFalse(granted.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))

        val notLaunched = Rig().also { it.host.onLaunch = { false } }
        notLaunched.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        assertFalse("nothing reached the screen, so there is nothing to wait for", notLaunched.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))
    }

    @Test
    fun `an answer that never comes back cannot wedge the row for the life of the process`() {
        val rig = Rig()
        rig.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        rig.nowMs = 119_000L
        assertTrue(rig.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))
        rig.nowMs = 121_000L
        assertFalse(rig.asker.isPending(NotificationAskFeature.PERFORMANCE_OVERLAY))
    }

    @Test
    fun `a second enable does not ask, whatever the first answer was`() {
        val ledger = MemoryLedger()
        val first = Rig(ledger = ledger)
        first.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        assertEquals("precondition: the first enable asked", 1, first.host.launches)
        first.asker.onPermissionResult(granted = false)

        // The permission is still missing, so Android would show its dialog again. Mimir must not: not for the same
        // feature, and not for the other one.
        assertEquals(
            NotificationAskOutcome.SKIPPED,
            first.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        first.asker.armMButton()
        assertEquals(NotificationAskOutcome.SKIPPED, first.asker.onMButtonKeepAliveStarted()?.outcome)
        assertEquals(1, first.host.launches)
        assertEquals(
            listOf(
                "permission ask=shown reason=feature-on feature=performance-overlay",
                "permission result=denied feature=performance-overlay",
                "permission ask=skipped reason=already-asked feature=performance-overlay",
                "permission ask=skipped reason=already-asked feature=m-button",
            ),
            first.lines,
        )

        // A later process reads the same ledger and still does not ask.
        val restarted = Rig(ledger = ledger)
        restarted.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        assertEquals(0, restarted.host.launches)

        // Control: the same enable over a FRESH ledger asks, so the silence above is the ledger's doing and not a host
        // that stopped launching.
        val fresh = Rig()
        fresh.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        assertEquals(1, fresh.host.launches)
    }

    @Test
    fun `the ask is recorded before the dialog opens, so no answer at all still counts`() {
        val rig = Rig()
        var askedAtLaunch: Boolean? = null
        rig.host.onLaunch = {
            askedAtLaunch = rig.ledger.asked
            true
        }

        rig.asker.onFeatureTurnedOn(NotificationAskFeature.M_BUTTON)

        assertEquals("the ledger must already say asked when the dialog launches", true, askedAtLaunch)
        // No result ever arrives (the dialog was dismissed, or the process died under it): a later process skips.
        val later = Rig(ledger = rig.ledger)
        assertEquals(
            NotificationAskOutcome.SKIPPED,
            later.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        assertEquals(0, later.host.launches)

        // Control: a request that could not even be launched put nothing on screen, so it is not an ask.
        val refused = Rig()
        refused.host.onLaunch = { false }
        assertEquals(
            NotificationAskOutcome.SKIPPED,
            refused.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        assertFalse(refused.ledger.asked)
        assertEquals(
            listOf("permission ask=skipped reason=request-not-launched feature=performance-overlay"),
            refused.lines,
        )
    }

    @Test
    fun `an enable while a game runs on Main defers, then asks at the next Home`() {
        val rig = Rig(session = game(MimirGamePlacement.MAIN, MimirSessionPhase.DISPATCHED))
        rig.host.inFront = false // the emulator covers MainActivity; the player used the Bottom Utility row

        val deferred = rig.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)

        assertEquals(NotificationAskOutcome.DEFERRED, deferred.outcome)
        assertEquals(0, rig.host.launches)
        assertEquals(NotificationAskFeature.PERFORMANCE_OVERLAY, rig.ledger.pending)
        assertEquals(NotificationAskFeature.PERFORMANCE_OVERLAY, rig.asker.pending.value)
        assertFalse("a deferral is not an ask", rig.ledger.asked)
        assertEquals(
            listOf("permission ask=deferred reason=main-occupied-by-game feature=performance-overlay"),
            rig.lines,
        )

        // Home resumes while the ledger still has the game RETURNING: no dialog, and no second deferral line.
        rig.host.inFront = true
        rig.session = game(MimirGamePlacement.MAIN, MimirSessionPhase.RETURNING)
        assertEquals(NotificationAskOutcome.DEFERRED, rig.asker.onHomeInFront()?.outcome)
        assertEquals(0, rig.host.launches)
        assertEquals(1, rig.lines.size)

        // The ledger ends the game with Home in front: the one ask happens now.
        rig.session = game(MimirGamePlacement.MAIN, MimirSessionPhase.ENDED)
        assertEquals(NotificationAskOutcome.SHOWN, rig.asker.onHomeInFront()?.outcome)
        assertEquals(1, rig.host.launches)
        assertNull(rig.ledger.pending)
        assertNull(rig.asker.pending.value)
        assertEquals("permission ask=shown reason=home-in-front feature=performance-overlay", rig.lines.last())

        // And never again at a later Home.
        assertNull(rig.asker.onHomeInFront())
        assertEquals(1, rig.host.launches)

        // Control: the same game placed on the BOTTOM leaves Main free, so the same enable asks at once.
        val bottom = Rig(session = game(MimirGamePlacement.BOTTOM, MimirSessionPhase.DISPATCHED))
        assertEquals(
            NotificationAskOutcome.SHOWN,
            bottom.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        assertEquals(1, bottom.host.launches)
    }

    @Test
    fun `an app on Main defers even with Home resumed, and so does anything that keeps Home out of front`() {
        // A dock-launched app on Main exists only as a PRIMARY session, and the ledger still names it.
        val app = Rig(session = mainApp())
        assertEquals(
            NotificationAskOutcome.DEFERRED,
            app.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        assertEquals(0, app.host.launches)
        assertEquals(
            listOf("permission ask=deferred reason=main-occupied-by-app feature=performance-overlay"),
            app.lines,
        )

        // Something the ledger does not know (a foreign app, an Android Settings screen) still stops MainActivity.
        val covered = Rig()
        covered.host.inFront = false
        covered.asker.armMButton()
        assertEquals(NotificationAskOutcome.DEFERRED, covered.asker.onMButtonKeepAliveStarted()?.outcome)
        assertEquals(0, covered.host.launches)
        assertEquals(listOf("permission ask=deferred reason=home-not-in-front feature=m-button"), covered.lines)

        // Control: the app's session is gone and Home is in front, so the deferred ask is shown.
        app.session = MimirSessionSnapshot()
        assertEquals(NotificationAskOutcome.SHOWN, app.asker.onHomeInFront()?.outcome)
        assertEquals(1, app.host.launches)
    }

    @Test
    fun `an already-granted permission never asks`() {
        // Control: the identical rig without the grant asks.
        val missing = Rig()
        missing.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        assertEquals(1, missing.host.launches)

        val granted = Rig(granted = true)
        assertEquals(
            NotificationAskOutcome.SKIPPED,
            granted.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        granted.asker.armMButton()
        assertEquals(NotificationAskOutcome.SKIPPED, granted.asker.onMButtonKeepAliveStarted()?.outcome)
        assertEquals(0, granted.host.launches)
        assertFalse("a skip records no ask", granted.ledger.asked)
        assertEquals(
            listOf(
                "permission ask=skipped reason=already-granted feature=performance-overlay",
                "permission ask=skipped reason=already-granted feature=m-button",
            ),
            granted.lines,
        )

        // Granted in Android Settings while a deferred ask waited for Home: dropped at Home, never shown.
        val waiting = Rig()
        waiting.host.inFront = false
        waiting.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        waiting.granted = true
        waiting.host.inFront = true
        assertEquals(NotificationAskOutcome.SKIPPED, waiting.asker.onHomeInFront()?.outcome)
        assertEquals(0, waiting.host.launches)
        assertNull(waiting.ledger.pending)
        assertEquals("permission ask=skipped reason=already-granted feature=performance-overlay", waiting.lines.last())
    }

    @Test
    fun `the M button asks at the first keep-alive start after it was turned on, and at no other start`() {
        val rig = Rig()

        // Control: a start with nothing armed, which is every resume and every cold restore of a button already on.
        assertNull(rig.asker.onMButtonKeepAliveStarted())
        assertEquals(0, rig.host.launches)
        assertTrue(rig.lines.isEmpty())

        rig.asker.armMButton()
        assertEquals(NotificationAskOutcome.SHOWN, rig.asker.onMButtonKeepAliveStarted()?.outcome)
        assertEquals(1, rig.host.launches)
        assertFalse("the start that used the arm spends it", rig.ledger.mButtonArmed)
        assertEquals(listOf("permission ask=shown reason=feature-on feature=m-button"), rig.lines)

        // Turned on, then off again before its service ever started: nothing is left to ask about.
        val disarmed = Rig()
        disarmed.asker.armMButton()
        disarmed.asker.disarmMButton()
        assertNull(disarmed.asker.onMButtonKeepAliveStarted())
        assertEquals(0, disarmed.host.launches)
    }

    @Test
    fun `a deferred ask for a feature turned off before Home is dropped, not shown`() {
        val rig = Rig()
        rig.host.inFront = false
        rig.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        rig.featuresOn -= NotificationAskFeature.PERFORMANCE_OVERLAY
        rig.host.inFront = true

        assertEquals(NotificationAskOutcome.SKIPPED, rig.asker.onHomeInFront()?.outcome)
        assertEquals(0, rig.host.launches)
        assertNull(rig.ledger.pending)
        assertFalse(rig.ledger.asked)
        assertEquals(
            "permission ask=skipped reason=feature-turned-off feature=performance-overlay",
            rig.lines.last(),
        )

        // Control: left on, the same deferred ask is shown at that Home.
        val kept = Rig()
        kept.host.inFront = false
        kept.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        kept.host.inFront = true
        assertEquals(NotificationAskOutcome.SHOWN, kept.asker.onHomeInFront()?.outcome)
        assertEquals(1, kept.host.launches)
    }

    @Test
    fun `below Android 13 there is no runtime permission to ask for`() {
        val old = Rig(runtime = false)
        assertEquals(
            NotificationAskOutcome.SKIPPED,
            old.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY).outcome,
        )
        assertEquals(0, old.host.launches)
        assertEquals(
            listOf("permission ask=skipped reason=not-a-runtime-permission feature=performance-overlay"),
            old.lines,
        )

        // Control: the same rig on Android 13 or later asks.
        val current = Rig(runtime = true)
        current.asker.onFeatureTurnedOn(NotificationAskFeature.PERFORMANCE_OVERLAY)
        assertEquals(1, current.host.launches)
    }

    private class MemoryLedger : NotificationAskLedger {
        override var asked: Boolean = false
            private set
        override var askedFeature: NotificationAskFeature? = null
            private set
        override var pending: NotificationAskFeature? = null
        override var mButtonArmed: Boolean = false

        override fun markAsked(feature: NotificationAskFeature) {
            asked = true
            askedFeature = feature
        }

        override fun clearAsked() {
            asked = false
            askedFeature = null
        }
    }

    private class FakeHost : NotificationAskHost {
        var inFront = true
        var launches = 0
        var onLaunch: () -> Boolean = { true }

        override fun inFrontOnMain(): Boolean = inFront

        override fun launchNotificationPermissionRequest(): Boolean {
            launches += 1
            return onLaunch()
        }
    }

    private class Rig(
        val ledger: MemoryLedger = MemoryLedger(),
        var granted: Boolean = false,
        var runtime: Boolean = true,
        var session: MimirSessionSnapshot = MimirSessionSnapshot(),
    ) {
        val host = FakeHost()
        val featuresOn = NotificationAskFeature.entries.toMutableSet()
        val lines = mutableListOf<String>()
        var nowMs = 0L
        val asker = NotificationPermissionAsker(
            ledger = ledger,
            runtimePermission = { runtime },
            permissionGranted = { granted },
            mainOccupant = { notificationAskMainOccupant(session) },
            featureOn = { it in featuresOn },
            host = { host },
            log = { lines += it },
            clockMs = { nowMs },
        )
    }

    private fun game(placement: MimirGamePlacement, phase: MimirSessionPhase) = MimirSessionSnapshot(
        revision = 1L,
        gameSession = MimirGameSession(
            operation = MimirGameSessionOperation("home:notify"),
            sequence = 1L,
            gameId = GameId("chrono"),
            platformId = PlatformId.SNES,
            returnAnchor = MimirGameReturnAnchor.Home(
                HomeLaunchReturnTarget(HomeShelfItemId.Emulated(GameId("chrono")), rank = 0L),
            ),
            startedAtEpochMs = 1_000L,
            placement = placement,
            dualScreen = false,
            phase = phase,
        ),
    )

    /** What the dock's Main route produces: a PRIMARY session with no transfer host behind it. */
    private fun mainApp(): MimirSessionSnapshot {
        val coordinator = MimirSessionCoordinator()
        check(
            coordinator.appPreparing(
                operationId = "primary:chrome",
                app = CompanionRunningApp("com.android.chrome", "Chrome"),
                placement = MimirAppPlacement.PRIMARY,
                returnAnchor = PrimaryReturnOrigin("HOME"),
            ),
        )
        check(coordinator.appDispatched("primary:chrome"))
        return coordinator.state.value
    }
}
