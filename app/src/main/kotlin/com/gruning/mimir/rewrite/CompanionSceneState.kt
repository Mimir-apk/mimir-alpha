package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ordered route and primary-task truth consumed directly by the always-live companion window. */
internal data class CompanionSceneSnapshot(
    val revision: Long = 0L,
    val route: PrimaryReturnOrigin = PrimaryReturnOrigin("LIBRARY"),
    val foregroundTask: CompanionRunningApp? = null,
    val foregroundObservedAtMs: Long = 0L,
    val semanticOwnerPackageName: String? = null,
)

/**
 * Serializes route revisions with foreground observations without creating another focus authority.
 * The first foreign task after a NOW_PLAYING payload is its semantic owner; a later different task is a
 * real tab switch and temporarily replaces the stale now-playing card until that owner or Mimir returns.
 */
internal class CompanionSceneCoordinator(
    initialRoute: PrimaryReturnOrigin = PrimaryReturnOrigin("LIBRARY"),
) {
    private val mutableScene = MutableStateFlow(CompanionSceneSnapshot(route = initialRoute))
    val scene: StateFlow<CompanionSceneSnapshot> = mutableScene.asStateFlow()

    private var baseMode: CompanionMode = CompanionMode.IDLE
    private var semanticIdentity: String? = null

    /**
     * G-2: the dispatched game's emulator package, armed at dispatch (the session coordinator knows it as
     * MimirGameSession.targetPackageName) and kept across the whole away-session — including the
     * "superseded_by_app" stale and the RUNNING_APP payloads a quick-launch publishes. This is the
     * re-derive key: a foreground observation of THIS package means the game is back in front.
     */
    private var armedGameOwnerPackage: String? = null

    /**
     * E1, 2026-08-31: a dispatched game is out there and no foreign task has been observed for it yet.
     *
     * **`baseMode` alone cannot carry this fact.** On the HOME route the shelf coordinator cleared
     * `launchingRow` BEFORE settling the session, so the NOW_PLAYING seed was followed by an ordinary
     * HOME payload — `isLoading = false`, because a Home launch never writes the library's launch state
     * — which reset `baseMode` to HOME and re-opened the substitution branch below before
     * [armGameOwner] had run. Narrowing the loading guard in [onPayload] cannot reach that payload: it
     * is not a loading payload.
     *
     * **ONE-SHOT, which is this class's own documented contract:** the FIRST foreign task after a
     * NOW_PLAYING payload is its semantic owner. Consumed by the first foreign observation and cleared
     * by every signal that ends a game session. Sticky would buy nothing — if an intermediate task (a
     * launcher flash) is adopted first, a sticky flag still routes the emulator down the
     * `currentOwner != app` arm and substitutes it. Only [armGameOwner] covers that case, and it
     * already does.
     */
    private var pendingGameOwner: Boolean = false

    @Synchronized
    fun armGameOwner(packageName: String?) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        armedGameOwnerPackage = pkg
    }

    /** Mimir is foreground again (return settled / primary resumed) — the game session is over. */
    @Synchronized
    fun disarmGameOwner() {
        armedGameOwnerPackage = null
        pendingGameOwner = false
    }

    /** Exact-package terminal cleanup; a stale watchdog cannot disarm a newly armed emulator. */
    @Synchronized
    fun disarmGameOwner(expectedPackageName: String): Boolean {
        if (armedGameOwnerPackage != expectedPackageName) return false
        armedGameOwnerPackage = null
        pendingGameOwner = false
        return true
    }

    /** The retained hero card is dead truth: no game is out there to adopt an owner for. */
    @Synchronized
    fun clearPendingGameOwner() {
        pendingGameOwner = false
    }

    @Synchronized
    fun isArmedGameOwner(packageName: String): Boolean =
        armedGameOwnerPackage != null && armedGameOwnerPackage == packageName

    /**
     * **D1-R9, second half — the one that actually caused D-1 §6c.**
     *
     * The first guard below compares REVISIONS, and the primary mints a fresh origin revision on every
     * focus move: `CompanionProcessRuntime.update` treats a route as changed when `destination` or
     * `arguments` differ, and `arguments` carries `focusKey`. So moving focus along the Home rail
     * produced `focusKey=home/rail/home` → `home/rail/library` → back, each one a new revision, each
     * one spending a scene revision here.
     *
     * That scene revision is an input to `CompanionPresentationFrameToken`
     * (`MimirCompanionPresentation.kt:257-262`), the token keys a `LaunchedEffect`, and the effect
     * re-arms the second screen's frame commit — **so screen 1's focus moving between two rail buttons
     * drew a frame on screen 2.** Measured on the Thor test unit: 40 moves, 80 Presentation frames,
     * display-4 pixels byte-identical, and the scene revision climbing 7 → 46.
     *
     * **Nothing here consumes `arguments`.** `scene.route`'s only reader is
     * `MimirCompanionPresentation.kt:293-294`, which takes `destination` and `revision` into
     * `CompanionPrimaryRoute`. A revision is a change token for a destination; if the destination did
     * not change there is nothing for it to be a token for, so it must not move.
     *
     * The first fix attempt for D1-R9 deduplicated `_renderPayload` instead. That bump was also real
     * and is also fixed, but the device said it changed nothing — 80 frames before and after — which
     * is what sent the search here.
     */
    @Synchronized
    fun updateRoute(origin: PrimaryReturnOrigin): Boolean {
        val current = mutableScene.value
        if (origin.revision <= current.route.revision) return false
        // The `revision > 0` clause is not a special case, it is the floor: a fresh coordinator starts
        // at `PrimaryReturnOrigin("LIBRARY")` with revision 0, and the first real update must be able
        // to establish the durable revision floor even though it names the same destination. This is
        // deliberately the same shape and the same reason as `CompanionProcessRuntime.update`'s own
        // `if (sameLocation && previous.revision > 0L) return` -- one guard, mirrored, rather than a
        // second rule to keep in step with the first.
        if (origin.destination == current.route.destination && current.route.revision > 0L) return false
        mutableScene.value = current.copy(
            revision = nextRevision(current.revision),
            route = origin,
        )
        return true
    }

    /** Process recovery may replace the cold-start Library floor at the same durable revision. */
    @Synchronized
    fun adoptRecoveredRoute(origin: PrimaryReturnOrigin): Boolean {
        val current = mutableScene.value
        if (origin.revision < current.route.revision || origin == current.route) return false
        mutableScene.value = current.copy(
            revision = nextRevision(current.revision),
            route = origin,
        )
        return true
    }

    @Synchronized
    fun onPayload(payload: CompanionPayload) {
        // A reclaim hold is presentation state, not semantic task truth. It must not erase the emulator owner
        // or the latest task observed while a secondary host covers this window.
        //
        // **E1, 2026-08-31: the guard was strictly WIDER than the holds it was written for.** All three
        // of them publish IDLE *and* loading — the reclaim hold, the cold-start hold and the regression
        // test that pins them — but EVERY NOW_PLAYING payload production emits is also loading. So a
        // launch was dropped here, `baseMode` could never become NOW_PLAYING, and the owner-adoption arm
        // below that this class's KDoc describes was unreachable dead code.
        //
        // Correctness then rested entirely on `armGameOwner` winning a race against a 100/250 ms
        // UsageStats poll, with no fallback: a foregrounded emulator that simply sits there emits no new
        // observation, so a lost race pinned the EMULATOR'S label for the rest of the session. That is
        // the defect reported on 2026-08-31 — a dispatched "Robot Bowl" described on screen 2
        // as "RetroArch 64".
        if (payload.isLoading && payload.mode == CompanionMode.IDLE) return
        if (payload.mode == CompanionMode.NOW_PLAYING) pendingGameOwner = true
        val nextIdentity = when (payload.mode) {
            CompanionMode.NOW_PLAYING ->
                "game\u0000${payload.gameId?.value.orEmpty()}\u0000${payload.title.orEmpty()}"
            CompanionMode.RUNNING_APP -> payload.runningApp?.packageName?.let { "app\u0000$it" }
            else -> null
        }
        val semanticChanged = nextIdentity != semanticIdentity
        baseMode = payload.mode
        semanticIdentity = nextIdentity
        if (!semanticChanged) return

        val current = mutableScene.value
        val next = current.copy(
            revision = nextRevision(current.revision),
            foregroundTask = null,
            semanticOwnerPackageName = when (payload.mode) {
                CompanionMode.RUNNING_APP -> payload.runningApp?.packageName
                else -> null
            },
        )
        if (next != current) mutableScene.value = next
    }

    @Synchronized
    fun observeForeignForeground(app: CompanionRunningApp, observedAtMs: Long): Boolean {
        val current = mutableScene.value
        if (observedAtMs <= current.foregroundObservedAtMs) return false

        // G-2: the armed game owner back in foreground is an OWNER RETURN, never a generic foreign task —
        // it must not be substituted as a RUNNING_APP card over the (republished) NOW_PLAYING base, even
        // when a quick-launched app claimed semanticOwner in between.
        if (armedGameOwnerPackage != null && app.packageName == armedGameOwnerPackage) {
            mutableScene.value = current.copy(
                revision = nextRevision(current.revision),
                foregroundTask = null,
                foregroundObservedAtMs = observedAtMs,
                semanticOwnerPackageName = armedGameOwnerPackage,
            )
            return true
        }

        val currentOwner = current.semanticOwnerPackageName
        val nextOwner: String?
        val nextTask: CompanionRunningApp?
        // E1: `pendingGameOwner` survives a HOME payload published between the NOW_PLAYING seed and
        // `armGameOwner` (see the field's KDoc). Without it the dispatched game's OWN emulator takes the
        // `else` arm below and the panel states its application label instead of the game's title.
        // Consumed here whichever arm is taken — first foreign task, one shot.
        val adoptsForeignOwner = baseMode == CompanionMode.NOW_PLAYING ||
            baseMode == CompanionMode.RUNNING_APP ||
            pendingGameOwner
        pendingGameOwner = false
        if (adoptsForeignOwner) {
            when {
                currentOwner == null -> {
                    nextOwner = app.packageName
                    nextTask = null
                }
                currentOwner == app.packageName -> {
                    nextOwner = currentOwner
                    nextTask = null
                }
                else -> {
                    nextOwner = currentOwner
                    nextTask = app
                }
            }
        } else {
            nextOwner = null
            nextTask = app
        }

        mutableScene.value = current.copy(
            revision = nextRevision(current.revision),
            foregroundTask = nextTask,
            foregroundObservedAtMs = observedAtMs,
            semanticOwnerPackageName = nextOwner,
        )
        return true
    }

    @Synchronized
    fun observeMimirForeground(observedAtMs: Long): Boolean {
        val current = mutableScene.value
        if (observedAtMs < current.foregroundObservedAtMs) return false
        if (current.foregroundTask == null && current.semanticOwnerPackageName == null &&
            observedAtMs == current.foregroundObservedAtMs
        ) {
            return false
        }
        mutableScene.value = current.copy(
            revision = nextRevision(current.revision),
            foregroundTask = null,
            foregroundObservedAtMs = observedAtMs,
            semanticOwnerPackageName = null,
        )
        // Mimir itself is in front: whatever game was out there is not, so a pending adoption is dead
        // truth. Cleared beside the owner it belongs to rather than as a separate rule kept in step.
        pendingGameOwner = false
        return true
    }

    private fun nextRevision(current: Long): Long = if (current == Long.MAX_VALUE) current else current + 1L
}

internal object CompanionSceneState {
    private val coordinator = CompanionSceneCoordinator()
    val scene: StateFlow<CompanionSceneSnapshot> = coordinator.scene

    fun updateRoute(origin: PrimaryReturnOrigin): Boolean = coordinator.updateRoute(origin)
    fun adoptRecoveredRoute(origin: PrimaryReturnOrigin): Boolean = coordinator.adoptRecoveredRoute(origin)
    fun onPayload(payload: CompanionPayload) = coordinator.onPayload(payload)
    fun armGameOwner(packageName: String?) = coordinator.armGameOwner(packageName)
    fun clearPendingGameOwner() = coordinator.clearPendingGameOwner()
    fun disarmGameOwner() = coordinator.disarmGameOwner()
    fun disarmGameOwner(expectedPackageName: String): Boolean =
        coordinator.disarmGameOwner(expectedPackageName)
    fun isArmedGameOwner(packageName: String): Boolean = coordinator.isArmedGameOwner(packageName)
    fun observeForeignForeground(app: CompanionRunningApp, observedAtMs: Long): Boolean =
        coordinator.observeForeignForeground(app, observedAtMs)

    fun observeMimirForeground(observedAtMs: Long = System.currentTimeMillis()): Boolean =
        coordinator.observeMimirForeground(observedAtMs)
}

internal fun effectiveCompanionPayload(
    payload: CompanionPayload,
    scene: CompanionSceneSnapshot,
): CompanionPayload {
    val task = scene.foregroundTask ?: return payload
    return companionPayloadWithRetainedHomeLanes(
        activePayload = companionRunningAppPayload(task, payload.themeId, payload.themeSigilRef),
        retainedHomePayload = CompanionState.retainedHomePayload() ?: payload,
    )
}
