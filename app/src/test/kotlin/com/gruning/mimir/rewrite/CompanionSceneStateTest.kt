package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSceneStateTest {
    @Test
    fun `companion scene mutations cannot move primary focus`() {
        val primaryScope = FocusScope("primary/library")
        val primaryKey = FocusKey("primary/library/game-4")
        val authority = DefaultFocusAuthority().apply {
            pushScope(primaryScope)
            register(primaryKey, primaryScope, FocusBounds(0f, 0f, 100f, 100f))
            requestFocus(primaryKey)
        }
        val coordinator = CompanionSceneCoordinator()

        repeat(40) { revision ->
            coordinator.updateRoute(PrimaryReturnOrigin("LIBRARY", revision = revision + 1L))
            coordinator.onPayload(
                CompanionPayload(
                    mode = CompanionMode.NOW_PLAYING,
                    gameId = GameId("companion-$revision"),
                    title = "Companion $revision",
                ),
            )
        }

        assertEquals(primaryScope, authority.state.value.scope)
        assertEquals(primaryKey, authority.state.value.focused)
    }

    @Test
    fun `route ordering rejects stale and equal revisions`() {
        val coordinator = CompanionSceneCoordinator()
        val newest = PrimaryReturnOrigin("SETTINGS", mapOf("focus" to "appearance"), revision = 7L)

        assertTrue(coordinator.updateRoute(newest))
        assertFalse(coordinator.updateRoute(PrimaryReturnOrigin("BROWSE", revision = 6L)))
        assertFalse(coordinator.updateRoute(PrimaryReturnOrigin("TOOLS", revision = 7L)))
        assertEquals(newest, coordinator.scene.value.route)
        assertEquals(1L, coordinator.scene.value.revision)
    }

    @Test
    fun `recovery may replace an equal-revision route floor without accepting older truth`() {
        val coordinator = CompanionSceneCoordinator()
        assertTrue(coordinator.updateRoute(PrimaryReturnOrigin("LIBRARY", revision = 7L)))

        val recovered = PrimaryReturnOrigin("SETTINGS", mapOf("focus" to "appearance"), revision = 7L)
        assertTrue(coordinator.adoptRecoveredRoute(recovered))
        assertFalse(coordinator.adoptRecoveredRoute(PrimaryReturnOrigin("BROWSE", revision = 6L)))
        assertFalse(coordinator.adoptRecoveredRoute(recovered))
        assertEquals(recovered, coordinator.scene.value.route)
    }

    @Test
    fun `now-playing owner survives tab swap and restores on owner return`() {
        val coordinator = CompanionSceneCoordinator()
        coordinator.onPayload(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("game"),
                title = "Game",
            ),
        )
        val emulator = app("org.example.emulator", "Emulator")
        val browser = app("org.example.browser", "Browser")

        assertTrue(coordinator.observeForeignForeground(emulator, 10L))
        assertEquals(emulator.packageName, coordinator.scene.value.semanticOwnerPackageName)
        assertNull(coordinator.scene.value.foregroundTask)

        assertTrue(coordinator.observeForeignForeground(browser, 20L))
        assertEquals(browser, coordinator.scene.value.foregroundTask)
        assertFalse(coordinator.observeForeignForeground(app("stale", "Stale"), 19L))

        assertTrue(coordinator.observeForeignForeground(emulator, 30L))
        assertNull(coordinator.scene.value.foregroundTask)
        assertEquals(emulator.packageName, coordinator.scene.value.semanticOwnerPackageName)
    }

    @Test
    fun `foreground task overrides stale route payload but exact running-app payload wins`() {
        val coordinator = CompanionSceneCoordinator()
        val browser = app("org.example.browser", "Browser")
        coordinator.onPayload(CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight"))
        coordinator.observeForeignForeground(browser, 10L)

        val followed = effectiveCompanionPayload(
            CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight"),
            coordinator.scene.value,
        )
        assertEquals(CompanionMode.RUNNING_APP, followed.mode)
        assertEquals(browser, followed.runningApp)

        val exact = companionRunningAppPayload(app("org.example.exact", "Exact"), "midnight")
        coordinator.onPayload(exact)
        assertEquals(exact, effectiveCompanionPayload(exact, coordinator.scene.value))
        assertTrue(coordinator.observeMimirForeground(11L))
        assertNull(coordinator.scene.value.foregroundTask)
    }

    @Test
    fun `loading reclaim hold preserves semantic owner and latest tab truth`() {
        val coordinator = CompanionSceneCoordinator()
        coordinator.onPayload(
            CompanionPayload(mode = CompanionMode.NOW_PLAYING, gameId = GameId("game"), title = "Game"),
        )
        val emulator = app("org.example.emulator", "Emulator")
        val browser = app("org.example.browser", "Browser")
        coordinator.observeForeignForeground(emulator, 10L)
        coordinator.observeForeignForeground(browser, 20L)

        coordinator.onPayload(CompanionPayload(mode = CompanionMode.IDLE, isLoading = true))

        assertEquals(emulator.packageName, coordinator.scene.value.semanticOwnerPackageName)
        assertEquals(browser, coordinator.scene.value.foregroundTask)
        assertFalse(coordinator.observeForeignForeground(app("stale", "Stale"), 19L))
    }

    @Test
    fun `exact running app remains owner while later primary tabs follow latest wins`() {
        val coordinator = CompanionSceneCoordinator()
        val owner = app("org.example.owner", "Owner")
        val browser = app("org.example.browser", "Browser")
        val notes = app("org.example.notes", "Notes")
        val base = companionRunningAppPayload(owner, "midnight")
        coordinator.onPayload(base)

        assertEquals(owner.packageName, coordinator.scene.value.semanticOwnerPackageName)
        assertTrue(coordinator.observeForeignForeground(browser, 10L))
        assertTrue(coordinator.observeForeignForeground(notes, 20L))
        assertEquals(notes, coordinator.scene.value.foregroundTask)
        assertEquals(notes, effectiveCompanionPayload(base, coordinator.scene.value).runningApp)
        assertFalse(coordinator.observeForeignForeground(browser, 19L))

        assertTrue(coordinator.observeForeignForeground(owner, 30L))
        assertNull(coordinator.scene.value.foregroundTask)
        assertEquals(base, effectiveCompanionPayload(base, coordinator.scene.value))
    }

    // ── G-2: the armed game owner survives the whole away-session and returns as NOW_PLAYING truth ──

    @Test
    fun `armed game owner returning to foreground is an owner return, never a foreign-task card`() {
        val coordinator = CompanionSceneCoordinator()
        coordinator.armGameOwner("com.retroarch")
        // Quick-launch lane: a RUNNING_APP payload claims semanticOwner for the app in between.
        val quickApp = app("org.example.browser", "Browser")
        coordinator.onPayload(companionRunningAppPayload(quickApp, "midnight"))
        assertEquals(quickApp.packageName, coordinator.scene.value.semanticOwnerPackageName)

        // Tab-switch back to the game: the emulator must NOT be substituted as a RUNNING_APP card.
        assertTrue(coordinator.observeForeignForeground(app("com.retroarch", "RetroArch"), 50L))
        assertNull(coordinator.scene.value.foregroundTask)
        assertEquals("com.retroarch", coordinator.scene.value.semanticOwnerPackageName)
    }

    @Test
    fun `disarmed game owner falls back to the generic foreign-task branch`() {
        val coordinator = CompanionSceneCoordinator()
        coordinator.armGameOwner("com.retroarch")
        coordinator.disarmGameOwner()

        val emulator = app("com.retroarch", "RetroArch")
        assertTrue(coordinator.observeForeignForeground(emulator, 50L))
        // baseMode is IDLE → generic branch retains the task as a foreground card.
        assertEquals(emulator, coordinator.scene.value.foregroundTask)
    }

    @Test
    fun `isArmedGameOwner matches only the armed package and blank arms are ignored`() {
        val coordinator = CompanionSceneCoordinator()
        coordinator.armGameOwner("  ")
        assertFalse(coordinator.isArmedGameOwner("com.retroarch"))
        coordinator.armGameOwner("com.retroarch")
        assertTrue(coordinator.isArmedGameOwner("com.retroarch"))
        assertFalse(coordinator.isArmedGameOwner("org.example.browser"))
        coordinator.disarmGameOwner()
        assertFalse(coordinator.isArmedGameOwner("com.retroarch"))
    }

    @Test
    fun `exact watchdog disarm cannot clear a newer emulator owner`() {
        val coordinator = CompanionSceneCoordinator()
        coordinator.armGameOwner("com.retroarch")

        assertFalse(coordinator.disarmGameOwner("org.ppsspp.ppsspp"))
        assertTrue(coordinator.isArmedGameOwner("com.retroarch"))
        assertTrue(coordinator.disarmGameOwner("com.retroarch"))
        assertFalse(coordinator.isArmedGameOwner("com.retroarch"))
    }

    private fun app(packageName: String, label: String) = CompanionRunningApp(packageName, label)

    // ── a focus move on screen 1 must not spend a scene revision ──────────────────────────

    @Test
    fun `a same-destination route change spends no scene revision, whatever its arguments say`() {
        val coordinator = CompanionSceneCoordinator()
        assertTrue(coordinator.updateRoute(PrimaryReturnOrigin("HOME", revision = 1L)))
        val settled = coordinator.scene.value.revision

        // Exactly what the Thor produced: only `focusKey` differs, once per focus move, each with a
        // fresh revision because CompanionProcessRuntime.update treats changed arguments as a changed
        // route. Before this guard each of these spent a scene revision, and each scene revision
        // re-armed the second screen's frame commit.
        repeat(40) { i ->
            val moved = PrimaryReturnOrigin(
                "HOME",
                mapOf("focusKey" to if (i % 2 == 0) "home/rail/library" else "home/rail/home"),
                revision = 2L + i,
            )
            assertFalse("a focus-only move must not move the scene", coordinator.updateRoute(moved))
        }

        assertEquals("40 focus moves, zero scene revisions", settled, coordinator.scene.value.revision)
    }

    @Test
    fun `a real destination change still spends exactly one scene revision`() {
        val coordinator = CompanionSceneCoordinator()
        assertTrue(coordinator.updateRoute(PrimaryReturnOrigin("HOME", revision = 1L)))
        val before = coordinator.scene.value.revision

        assertTrue(coordinator.updateRoute(PrimaryReturnOrigin("SETTINGS", revision = 2L)))

        assertEquals(
            "suppressing a focus move must not suppress an actual route change -- that would leave " +
                "screen 2 showing the wrong destination",
            before + 1L,
            coordinator.scene.value.revision,
        )
        assertEquals("SETTINGS", coordinator.scene.value.route.destination)
    }

    @Test
    fun `the first update still establishes the floor even though it names the default destination`() {
        // A fresh coordinator sits at PrimaryReturnOrigin("LIBRARY") revision 0. The guard must not
        // swallow the update that turns that default into durable truth.
        val coordinator = CompanionSceneCoordinator()

        assertTrue(coordinator.updateRoute(PrimaryReturnOrigin("LIBRARY", revision = 7L)))
        assertEquals(7L, coordinator.scene.value.route.revision)
    }

    /**
     * **E1, and it is the defect reported on device rather than one imagined from source.**
     *
     * A library game was dispatched -- the launch probe recorded
     * `LAUNCH 'Robot Bowl' [arcade] launched=true outcome=IntentSent` -- and screen 2 described the
     * EMULATOR: *"RetroArch 64 · swap back from the tab menu"*.
     *
     * Two independent causes, and this pins both. The loading guard was strictly wider than the holds
     * it was written for, so every NOW_PLAYING payload was dropped and `baseMode` could never reach
     * NOW_PLAYING; and on the HOME route the shelf cleared its launching row before settling the
     * session, publishing an ordinary HOME payload that reset the base before the owner was armed.
     */
    @Test
    fun `a dispatched game is not described as its emulator`() {
        val scene = CompanionSceneCoordinator()

        // The seed a dispatch publishes. It is LOADING, which is what the old guard threw away.
        scene.onPayload(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("robot-bowl"),
                title = "Robot Bowl",
                isLoading = true,
            ),
        )
        // The HOME payload the shelf publishes straight after, before the owner is armed. Not loading:
        // a Home launch never writes the library's launch state. This is what re-opened the window.
        scene.onPayload(CompanionPayload(mode = CompanionMode.HOME))

        // The emulator comes to the foreground. It must be adopted as the game's OWNER, not described.
        scene.observeForeignForeground(app("com.retroarch.aarch64", "RetroArch 64"), 1_000L)

        assertEquals(
            "the dispatched game's emulator is its owner, not a running app to be named",
            "com.retroarch.aarch64",
            scene.scene.value.semanticOwnerPackageName,
        )
        assertNull(
            "and it must not be stated as a foreground task",
            scene.scene.value.foregroundTask,
        )
    }

    @Test
    fun `the pending owner is consumed once, not held across a later app launch`() {
        // ONE SHOT is the contract. A sticky flag would swallow a genuinely different app launched
        // afterwards and describe it as the game's owner too.
        val scene = CompanionSceneCoordinator()
        scene.onPayload(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("robot-bowl"),
                title = "Robot Bowl",
                isLoading = true,
            ),
        )
        scene.onPayload(CompanionPayload(mode = CompanionMode.HOME))
        scene.observeForeignForeground(app("com.retroarch.aarch64", "RetroArch 64"), 1_000L)

        scene.onPayload(CompanionPayload(mode = CompanionMode.HOME))
        scene.observeForeignForeground(app("com.android.chrome", "Chrome"), 2_000L)

        assertEquals(
            "a later foreign app on a HOME base is a running app, not the game's owner",
            "Chrome",
            scene.scene.value.foregroundTask?.label,
        )
    }

    @Test
    fun `a reclaim hold is still ignored, which is what the guard was written for`() {
        // The narrowing must not cost the guard its actual job. An IDLE+loading hold is presentation
        // state and must not erase the owner or the task observed while a secondary host covers us.
        val scene = CompanionSceneCoordinator()
        scene.onPayload(
            CompanionPayload(
                mode = CompanionMode.NOW_PLAYING,
                gameId = GameId("robot-bowl"),
                title = "Robot Bowl",
                isLoading = true,
            ),
        )
        scene.observeForeignForeground(app("com.retroarch.aarch64", "RetroArch 64"), 1_000L)
        val ownerBefore = scene.scene.value.semanticOwnerPackageName

        scene.onPayload(CompanionPayload(mode = CompanionMode.IDLE, isLoading = true))

        assertEquals(
            "a reclaim hold must not erase the emulator owner",
            ownerBefore,
            scene.scene.value.semanticOwnerPackageName,
        )
    }
}
