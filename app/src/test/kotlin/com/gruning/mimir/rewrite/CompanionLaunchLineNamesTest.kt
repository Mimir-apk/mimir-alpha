package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * **The launch line speaks the player's language or it says nothing.**
 *
 * `publishLaunchLocked` used to end its fallback chain `?: cleanPackage`, so when neither the caller's
 * hint nor the swept catalog had a name, the line rendered `Opening com.foo.bar...`. The two earlier
 * rungs miss *together* in exactly one situation — the catalog sweep has not finished — which is the
 * start of a session, so the fallback fired precisely where it was least excusable.
 *
 * A package id is not a name. `LaunchPending.label` is nullable now and the unknown case renders as
 * `Opening...`, which is short, true, and has no dots in the middle of it.
 */
class CompanionLaunchLineNamesTest {
    @Test
    fun anUnknownAppOpensWithoutBeingNamedByItsPackageId() {
        val coordinator = coordinator()
        // Shown WITHOUT the package being launched: this is the cold window, where the sweep has not
        // reached the pin the player is pressing.
        coordinator.show(listOf(CompanionAppEntry("org.example.other", "Other", null)))

        val launched = coordinator.requestLaunch(SECRET_PACKAGE) { _, _ -> true }
        assertNotNull(launched)

        val message = coordinator.ui.value.launchMessage
        assertNotNull("a launch in flight must still say so", message)
        // **This assertion fails on the code this test was written against.** That is the point of it.
        assertFalse(
            "the launch line must never print a package id: <$message>",
            message!!.contains(SECRET_PACKAGE),
        )
        assertFalse("nor any fragment of one", message.contains("org.example"))
        assertEquals("Opening...", message)

        // The package is still KNOWN to the state — it is simply not spoken. Suppressing the name must
        // not cost the machinery the identity it dispatches and unlocks with.
        assertEquals(SECRET_PACKAGE, coordinator.ui.value.launchingPackageName)
        assertTrue(coordinator.ui.value.launchLocked)
    }

    @Test
    fun aCallerHintNamesTheAppEvenWhenTheSweepHasNotLanded() {
        val coordinator = coordinator()
        coordinator.show(emptyList())

        // This is what carrying `tile.label` through `onAppTap` buys: the tile on screen knows its own
        // name and cannot be un-ready, so a tap in the cold window is named anyway.
        val launched = coordinator.requestPreparedLaunch(SECRET_PACKAGE, label = "Example Game") {
            CompanionPreparedLaunch(UUID(0L, 77L), dispatch = { true })
        }
        assertNotNull(launched)

        assertEquals("Opening Example Game...", coordinator.ui.value.launchMessage)
    }

    @Test
    fun aSweptCatalogNamesTheAppWithNoHintAtAll() {
        val coordinator = coordinator()
        coordinator.show(listOf(CompanionAppEntry(SECRET_PACKAGE, "Example Game", null)))

        assertNotNull(coordinator.requestLaunch(SECRET_PACKAGE) { _, _ -> true })

        assertEquals("Opening Example Game...", coordinator.ui.value.launchMessage)
    }

    private fun coordinator() = CompanionRecentsCoordinator(
        elapsedRealtimeMs = { 1_000L },
        operationIdFactory = { UUID(0L, 1L) },
        // A `fun interface`: the deadline never has to fire for any of these, so the handle is inert.
        deadlineScheduler = LaunchDeadlineScheduler { _, _ -> LaunchDeadlineHandle {} },
    )

    private companion object {
        /** Deliberately shaped like a real package so a substring check is meaningful. */
        const val SECRET_PACKAGE = "org.example.unswept"
    }
}
