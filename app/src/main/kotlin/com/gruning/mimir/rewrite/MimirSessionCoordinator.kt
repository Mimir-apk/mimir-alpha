package com.gruning.mimir.rewrite

import android.util.Log
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.LaunchTargetPreflightContext
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryGameLaunchOperation
import com.gruning.mimir.rewrite.feature.library.LibraryGameSessionEvent
import com.gruning.mimir.rewrite.feature.library.LibraryGameSessionObserver
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.ParkedLeaseReleaseOutcome
import com.gruning.mimir.rewrite.feature.library.emulatorSentenceLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class MimirSessionPhase {
    PREPARING,
    DISPATCHED,
    FOREGROUND_PROVEN,
    YIELDED_TO_SECONDARY_TASK,
    RETURNING,
    RECOVERING,
    ENDED,
    FAILED,
    STALE,
}

internal enum class MimirAppPlacement {
    PRIMARY,
    SECONDARY,
}

/** Game placement is semantic session state so Main lifecycle callbacks cannot settle a Bottom game. */
internal enum class MimirGamePlacement {
    MAIN,
    BOTTOM,
}

@JvmInline
internal value class MimirGameSessionOperation(val value: String) {
    init {
        require(value.isNotBlank()) { "Game session operation is mandatory" }
    }
}

internal sealed interface MimirGameReturnAnchor {
    data class Library(val target: LibraryLaunchReturnTarget) : MimirGameReturnAnchor
    data class Home(val target: HomeLaunchReturnTarget) : MimirGameReturnAnchor
}

internal data class MimirGameSession(
    val operation: MimirGameSessionOperation,
    val sequence: Long,
    val gameId: GameId,
    /**
     * The library title, carried so a surface can NAME the running game without re-reading the shelf.
     *
     * Added for the Bottom Tabs entry : the tab manager projects this session, and a
     * tab labelled with the emulator's own label is the documented fallback, not the answer.
     */
    val title: String = "",
    /** The game's cover-art ref (`content:` / `file:` / `android.resource:`), or null when it has none. */
    val artRef: String? = null,
    val platformId: PlatformId,
    val returnAnchor: MimirGameReturnAnchor,
    val homeReturnHandle: HomeLaunchReturnHandle? = null,
    val libraryOperation: LibraryGameLaunchOperation? = null,
    val startedAtEpochMs: Long,
    val dispatchedAtEpochMs: Long? = null,
    val dispatchedAtElapsedMs: Long? = null,
    val preflight: LaunchTargetPreflightContext? = null,
    val targetPackageName: String? = null,
    /** The emulator's name as a sentence wants it, for "Starting <Emulator>…" and "<Emulator> closed unexpectedly". */
    val emulatorLabel: String? = null,
    val placement: MimirGamePlacement = MimirGamePlacement.MAIN,
    val dualScreen: Boolean,
    val phase: MimirSessionPhase,
    val foregroundProven: Boolean = false,
    val failureReason: String? = null,
)

internal data class MimirAppSession(
    val operationId: String,
    val sequence: Long,
    val packageName: String,
    val label: String,
    /**
     * The app's icon, carried so the Main card can be a pure view of this session (O7 / I3) instead of a second record
     * keeping its own copy of the app. Null when the launching surface had none.
     */
    val iconPath: String? = null,
    val placement: MimirAppPlacement,
    val returnAnchor: PrimaryReturnOrigin,
    val startedAtEpochMs: Long,
    val phase: MimirSessionPhase,
    val foregroundProven: Boolean = false,
    val failureReason: String? = null,
)

internal data class MimirSessionSnapshot(
    val revision: Long = 0L,
    val gameSession: MimirGameSession? = null,
    val primaryAppSession: MimirAppSession? = null,
    val secondaryAppSession: MimirAppSession? = null,
) {
    val appSession: MimirAppSession?
        get() = listOfNotNull(primaryAppSession, secondaryAppSession).maxByOrNull(MimirAppSession::sequence)

    fun permitsGamePresentation(gameId: GameId): Boolean {
        val game = gameSession ?: return primaryAppSession == null
        if (game.gameId != gameId || game.phase !in ActivePresentationPhases) return false
        return primaryAppSession?.sequence?.let { it < game.sequence } != false
    }
}

/**
 * A game that is dispatched and has not settled, on either placement: the phases in which the emulator
 * task exists and the session still owns it. RECOVERING is excluded on purpose — it is an admitted-session
 * mutex while cleanup runs, not a game the player can be handed back.
 */
private val LiveGamePhases = setOf(
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    MimirSessionPhase.RETURNING,
)

/** The live Bottom game session, or null when no game owns the bottom screen. */
internal fun MimirSessionSnapshot.liveBottomGame(): MimirGameSession? =
    gameSession?.takeIf { it.placement == MimirGamePlacement.BOTTOM && it.phase in LiveGamePhases }

/**
 * The live Bottom game session dispatched to [packageName], or null.
 *
 * `targetPackageName` is the emulator the launch actually reached (set from the settled
 * `LaunchReadiness.target.emulator.packageName`), so this is the ONE honest way to ask "is this tab's
 * package the game that is running", rather than inferring it from the tab's own kind.
 */
internal fun MimirSessionSnapshot.liveBottomGameFor(packageName: String): MimirGameSession? {
    val clean = packageName.trim().takeIf(String::isNotEmpty) ?: return null
    return liveBottomGame()?.takeIf { it.targetPackageName.equals(clean, ignoreCase = true) }
}

/** The live game dispatched to [packageName], Main or Bottom — what a Tabs row for that package closes. */
internal fun MimirSessionSnapshot.liveGameFor(packageName: String): MimirGameSession? {
    val clean = packageName.trim().takeIf(String::isNotEmpty) ?: return null
    return gameSession
        ?.takeIf { it.phase in LiveGamePhases }
        ?.takeIf { it.targetPackageName.equals(clean, ignoreCase = true) }
}

/** A Bottom game launch still starting: resolving its emulator, or sent to it and not yet seen on the Bottom. */
private val InFlightBottomGamePhases = setOf(MimirSessionPhase.PREPARING, MimirSessionPhase.DISPATCHED)

/**
 * Whether this process has a Bottom game launch still starting for [packageName]: the one session state a Tabs
 * liveness reconcile reads before it may retire a GAME row (Thor, 2026-09-12).
 *
 * The registry files a game's row at dispatch, before the emulator has a process, so a `pidof` miss then is a
 * game starting. A PREPARING session has not resolved its emulator (`targetPackageName` is set when the launch
 * settles), so it holds every package; a DISPATCHED one holds the emulator it was sent to, or every package when
 * it recorded none. FOREGROUND_PROVEN and later, a MAIN game, and no session at all hold nothing -- the last is
 * the Thor's case, where the process that dispatched Minish Cap had died.
 */
internal fun MimirSessionSnapshot.bottomGameLaunchInFlightFor(packageName: String): Boolean {
    val game = gameSession
        ?.takeIf { it.placement == MimirGamePlacement.BOTTOM && it.phase in InFlightBottomGamePhases }
        ?: return false
    val target = game.targetPackageName ?: return true
    return target.equals(packageName.trim(), ignoreCase = true)
}

/** Every phase a session holds the launch mutex in; [launchHoldFor] answers a hold for exactly these (O15). */
internal val ActivePresentationPhases = setOf(
    MimirSessionPhase.PREPARING,
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
    MimirSessionPhase.RETURNING,
    MimirSessionPhase.RECOVERING,
)

/**
 * Who actually occupies the main screen: this ledger's PRIMARY app session in any active presentation phase.
 *
 * The session ledger is authoritative: this ledger is the one record of what runs on Main.
 * SWAP planning and the exchange read it here, and the Main card (`CompanionRunningAppState.running`) is a read-only
 * view of it ([provenPrimaryOccupant]). Before the ruling that card had its own writer, the display-0 transfer host.
 * An app the player launched from the Bottom dock never crosses that host — it existed only as this
 * [primaryAppSession] — so a caller that read the card saw `null` and concluded the main screen was free. Measured on a test device 2026-09-11: Chrome dock-launched to Main with YouTube yielded on the Bottom logged
 * `swap plan=BOTTOM_TO_MAIN … mainRunning=null`.
 *
 * A dispatched launch counts: the Main mutex ([MimirSessionCoordinator.appPreparing]) already treats it as holding
 * Main. Pure read; no phase is mutated.
 */
internal fun MimirSessionSnapshot.primaryOccupant(): CompanionRunningApp? =
    activePrimaryAppSession()?.let(::mainOccupantOf)

/**
 * The Main app whose presence on Main the ledger has PROVEN: the Main card's read-only view of this ledger (O7 / I3).
 *
 * The card never claimed an app had reached Main before that was proven — a transfer is staged, then dispatched, and
 * only the display-0 host's observed coverage proves it — so the view keeps that line. A session still preparing or
 * dispatched holds Main for [primaryOccupant] and for the Main mutex, but is not yet shown as being on it.
 */
internal fun MimirSessionSnapshot.provenPrimaryOccupant(): CompanionRunningApp? =
    activePrimaryAppSession()?.takeIf(MimirAppSession::foregroundProven)?.let(::mainOccupantOf)

/** This ledger's PRIMARY app session while it is in an active presentation phase; null otherwise. */
internal fun MimirSessionSnapshot.activePrimaryAppSession(): MimirAppSession? =
    primaryAppSession?.takeIf { it.placement == MimirAppPlacement.PRIMARY && it.phase in ActivePresentationPhases }

private fun mainOccupantOf(session: MimirAppSession): CompanionRunningApp =
    CompanionRunningApp(packageName = session.packageName, label = session.label, iconPath = session.iconPath)

internal data class MimirGameReturnTicket(
    val operation: MimirGameSessionOperation,
    val targetPackageName: String?,
    val launchedAtEpochMs: Long,
    val dualScreen: Boolean,
)

internal data class MimirGameDispatchCleanup(
    val operation: MimirGameSessionOperation,
    val expectedPackageName: String,
    val homeReturnHandle: HomeLaunchReturnHandle?,
    val libraryOperation: LibraryGameLaunchOperation?,
    val dualScreen: Boolean,
)

/** A projection callback must never strand the semantic session in RECOVERING. */
internal fun applyLibraryDispatchCleanupSafely(
    operation: LibraryGameLaunchOperation?,
    cleanup: ((LibraryGameLaunchOperation) -> Boolean)?,
): Boolean = operation == null || cleanup == null || runCatching { cleanup(operation) }.getOrDefault(false)

internal data class MimirAppReturnTicket(
    val operationId: String,
    val targetPackageName: String,
    val launchedAtEpochMs: Long,
)

/**
 * R6-P0-02 S1: what a refused launch was and what it was held by, so the single trace line can say so
 * without every refusal branch growing its own logging call.
 */
internal data class MimirRefusalContext(
    val kind: String,
    val hold: GameLaunchSessionHold,
)

/** Semantic lifecycle owner. Native task/display ledgers remain evidence providers, not competing writers. */
internal class MimirSessionCoordinator(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val trace: (String, MimirSessionSnapshot, MimirRefusalContext?) -> Unit = { _, _, _ -> },
) {
    private val mutableState = MutableStateFlow(MimirSessionSnapshot())
    val state: StateFlow<MimirSessionSnapshot> = mutableState.asStateFlow()

    private var sequence = 0L

    /**
     * G-2: fires with the emulator package on every successful game dispatch — the host arms the companion
     * scene's game-owner key with it so a later foreground observation of that package re-derives the
     * NOW_PLAYING card. A hook (not a direct CompanionSceneState call) so coordinator tests stay pure.
     */
    var onGameDispatched: ((String) -> Unit)? = null

    /**
     * Fires, after the DISPATCHED to YIELDED edge is published, with the session that yielded: the emulator has
     * taken the screen, so "Starting <Emulator>…" is over. A hook so coordinator tests stay pure.
     */
    var onGameYielded: ((MimirGameSession) -> Unit)? = null

    /**
     * Fires, after the dispatch watchdog has finished cleaning up and published FAILED, with that session: the intent
     * was sent and the emulator never took the window from Mimir (see [LaunchEarlyClose]). The launch is still
     * counted; this only lets a surface say what happened.
     */
    var onEmulatorClosedEarly: ((MimirGameSession) -> Unit)? = null

    /**
     * [onHeld] hears what held a refused launch (O15), read from the snapshot this refusal was decided on. It runs under
     * this lock, so a caller only records the value.
     */
    @Synchronized
    fun prepareHomeGame(
        game: GameRow,
        returnTarget: HomeLaunchReturnTarget,
        returnHandle: HomeLaunchReturnHandle? = null,
        startedAtEpochMs: Long = System.currentTimeMillis(),
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
        onHeld: (GameLaunchSessionHold) -> Unit = {},
    ): MimirGameSessionOperation? {
        val current = mutableState.value
        val activeGame = current.gameSession?.phase?.let { it in ActivePresentationPhases } == true
        val activePrimaryApp = current.primaryAppSession?.phase?.let { it in ActivePresentationPhases } == true
        // A Bottom game owns the other display and may coexist with the primary app. Main retains the
        // historical single-display exclusion; both placements still retain the one-game guard.
        if (activeGame || (placement == MimirGamePlacement.MAIN && activePrimaryApp)) {
            refuse("game", current, includePrimaryApp = placement == MimirGamePlacement.MAIN, onHeld = onHeld)
            return null
        }
        val operation = MimirGameSessionOperation("home:${OperationId.create()}")
        return operation.takeIf {
            gamePreparing(
                operation = operation,
                game = game,
                returnAnchor = MimirGameReturnAnchor.Home(returnTarget),
                homeReturnHandle = returnHandle,
                libraryOperation = null,
                startedAtEpochMs = startedAtEpochMs,
                placement = placement,
            )
        }
    }

    @Synchronized
    fun gamePreparing(
        event: LibraryGameSessionEvent.Preparing,
        onHeld: (GameLaunchSessionHold) -> Unit = {},
    ): Boolean = gamePreparing(
        operation = libraryGameSessionOperation(event.operation),
        game = event.game,
        returnAnchor = MimirGameReturnAnchor.Library(event.returnTarget),
        homeReturnHandle = null,
        libraryOperation = event.operation,
        startedAtEpochMs = event.startedAtEpochMs,
        onHeld = onHeld,
    )

    /** [onHeld] as in [prepareHomeGame]: what held a refused launch, from this refusal's snapshot, under this lock (O15). */
    @Synchronized
    fun gamePreparing(
        operation: MimirGameSessionOperation,
        game: GameRow,
        returnAnchor: MimirGameReturnAnchor,
        homeReturnHandle: HomeLaunchReturnHandle? = null,
        libraryOperation: LibraryGameLaunchOperation? = null,
        startedAtEpochMs: Long,
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
        onHeld: (GameLaunchSessionHold) -> Unit = {},
    ): Boolean {
        val current = mutableState.value
        if (current.gameSession?.operation == operation) return true
        val activeGame = current.gameSession?.phase?.let { it in ActivePresentationPhases } == true
        val activePrimaryApp = current.primaryAppSession?.phase?.let { it in ActivePresentationPhases } == true
        if (activeGame || (placement == MimirGamePlacement.MAIN && activePrimaryApp)) {
            refuse("game", current, includePrimaryApp = placement == MimirGamePlacement.MAIN, onHeld = onHeld)
            return false
        }
        val nextSequence = nextSequence()
        return publish(
            "game_preparing",
            current.copy(
                gameSession = MimirGameSession(
                    operation = operation,
                    sequence = nextSequence,
                    gameId = game.gameId,
                    title = game.title,
                    artRef = gameSessionArtRef(game),
                    platformId = game.platformId,
                    returnAnchor = returnAnchor,
                    homeReturnHandle = homeReturnHandle,
                    libraryOperation = libraryOperation,
                    startedAtEpochMs = startedAtEpochMs,
                    dualScreen = game.platformId == PlatformId.DS || game.platformId == PlatformId.THREE_DS,
                    phase = MimirSessionPhase.PREPARING,
                    placement = placement,
                ),
                primaryAppSession = if (placement == MimirGamePlacement.MAIN) {
                    current.primaryAppSession?.staleIfActive("superseded_by_game")
                } else {
                    current.primaryAppSession
                },
            ),
        )
    }

    @Synchronized
    fun gameSettled(event: LibraryGameSessionEvent.Settled): Boolean = gameSettled(
        operation = libraryGameSessionOperation(event.operation),
        gameId = event.game.gameId,
        result = event.result,
    )

    @Synchronized
    fun gameSettled(
        operation: MimirGameSessionOperation,
        gameId: GameId,
        result: LaunchResult,
    ): Boolean {
        val current = mutableState.value
        val game = current.gameSession ?: return false
        if (game.operation != operation || game.gameId != gameId) return false
        if (game.phase != MimirSessionPhase.PREPARING) return false
        // `result.launched` already means "the intent was actually sent" — LaunchController only ever
        // populates `outcome` after a real dispatch — so it is sufficient on its own. It used to be
        // gated on `readiness is Ready`, which silently mis-marked a real dispatch as `game_failed`
        // for the missing-BIOS dispatch case: a confirmed-missing BIOS keeps `readiness` at
        // NeedsBios (so the honest reason still shows) even though the emulator got the intent.
        val launched = result.launched
        val dispatchedTarget = when (val readiness = result.readiness) {
            is LaunchReadiness.Ready -> readiness.target
            is LaunchReadiness.NeedsBios -> readiness.target
            else -> null
        }
        val published = publish(
            if (launched) "game_dispatched" else "game_failed",
            current.copy(
                gameSession = game.copy(
                    targetPackageName = dispatchedTarget?.emulator?.packageName,
                    emulatorLabel = emulatorSentenceLabel(dispatchedTarget?.emulator?.displayName),
                    dispatchedAtEpochMs = clockMillis().takeIf { launched },
                    dispatchedAtElapsedMs = monotonicMillis().takeIf { launched },
                    preflight = result.preflight?.takeIf { launched },
                    phase = if (launched) MimirSessionPhase.DISPATCHED else MimirSessionPhase.FAILED,
                    failureReason = if (launched) {
                        null
                    } else {
                        (result.outcome as? LaunchOutcome.Failed)?.reason?.name
                            ?: result.readiness::class.java.simpleName
                    },
                ),
            ),
        )
        if (published && launched) {
            dispatchedTarget?.emulator?.packageName?.let { pkg -> onGameDispatched?.invoke(pkg) }
        }
        return published
    }

    @Synchronized
    fun gameFailed(event: LibraryGameSessionEvent.Failed): Boolean = gameFailed(
        operation = libraryGameSessionOperation(event.operation),
        gameId = event.game.gameId,
        reason = event.reason,
    )

    @Synchronized
    fun gameFailed(
        operation: MimirGameSessionOperation,
        gameId: GameId,
        reason: String,
    ): Boolean {
        val current = mutableState.value
        val game = current.gameSession ?: return false
        if (game.operation != operation || game.gameId != gameId) return false
        if (game.phase != MimirSessionPhase.PREPARING) return false
        return publish(
            "game_failed",
            current.copy(
                gameSession = game.copy(
                    phase = MimirSessionPhase.FAILED,
                    failureReason = reason,
                ),
            ),
        )
    }

    /**
     * First-party resumed-host watchdog. Exact operation + DISPATCHED phase prevents a stale timeout
     * from ending a newer launch or a normal return. RECOVERING remains an admitted-session mutex until
     * process-global cleanup has finished; it is not foreground/playtime proof.
     */
    @Synchronized
    fun gameDispatchWatchdogExpired(
        operation: MimirGameSessionOperation,
        expectedPackageName: String,
        reason: String = "target_never_left_primary",
    ): MimirGameDispatchCleanup? {
        val current = mutableState.value
        val game = current.gameSession ?: return null
        if (game.operation != operation ||
            game.phase != MimirSessionPhase.DISPATCHED ||
            game.targetPackageName != expectedPackageName
        ) {
            return null
        }
        val cleanup = MimirGameDispatchCleanup(
            operation = game.operation,
            expectedPackageName = expectedPackageName,
            homeReturnHandle = game.homeReturnHandle,
            libraryOperation = game.libraryOperation,
            dualScreen = game.dualScreen,
        )
        return cleanup.takeIf {
            publish(
                "game_dispatch_watchdog_expired",
                current.copy(
                    gameSession = game.copy(
                        phase = MimirSessionPhase.RECOVERING,
                        failureReason = reason,
                    ),
                ),
            )
        }
    }

    @Synchronized
    fun gameDispatchWatchdogCleanupComplete(
        operation: MimirGameSessionOperation,
        expectedPackageName: String,
    ): Boolean {
        val current = mutableState.value
        val game = current.gameSession ?: return false
        if (game.operation != operation ||
            game.phase != MimirSessionPhase.RECOVERING ||
            game.targetPackageName != expectedPackageName
        ) {
            return false
        }
        val published = publish(
            "game_dispatch_watchdog_failed",
            current.copy(gameSession = game.copy(phase = MimirSessionPhase.FAILED)),
        )
        if (published) {
            mutableState.value.gameSession?.let { failed -> runCatching { onEmulatorClosedEarly?.invoke(failed) } }
        }
        return published
    }

    @Synchronized
    fun gameForegroundProven(operation: MimirGameSessionOperation): Boolean =
        mutateGame(operation, "game_foreground_proven") { game ->
            if (game.phase !in setOf(MimirSessionPhase.DISPATCHED, MimirSessionPhase.RETURNING)) return@mutateGame null
            game.copy(phase = MimirSessionPhase.FOREGROUND_PROVEN, foregroundProven = true)
        }

    @Synchronized
    fun gameYielded(operation: MimirGameSessionOperation): Boolean {
        val yielded = mutateGame(operation, "game_yielded") { game ->
            // MainActivity calls this only at the DISPATCHED hand-off edge. A watchdog may atomically
            // claim RECOVERING between the activity's read and this synchronized mutation; never let that
            // stale lifecycle signal reopen admission while exact-package cleanup is still running.
            if (game.phase != MimirSessionPhase.DISPATCHED) null
            else game.copy(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        }
        if (yielded) {
            mutableState.value.gameSession?.let { game -> runCatching { onGameYielded?.invoke(game) } }
        }
        return yielded
    }

    @Synchronized
    fun beginCurrentGameReturn(): MimirGameReturnTicket? =
        mutableState.value.gameSession?.operation?.let(::beginGameReturn)

    @Synchronized
    fun beginGameReturn(expectedOperation: MimirGameSessionOperation): MimirGameReturnTicket? {
        val current = mutableState.value
        val game = current.gameSession ?: return null
        if (game.operation != expectedOperation) return null
        if (game.phase !in setOf(
                MimirSessionPhase.DISPATCHED,
                MimirSessionPhase.FOREGROUND_PROVEN,
                MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
                MimirSessionPhase.RETURNING,
            )
        ) {
            return null
        }
        val newerPrimaryApp = current.primaryAppSession?.sequence?.let { it > game.sequence } == true
        if (newerPrimaryApp && game.placement == MimirGamePlacement.MAIN) return null
        if (game.phase != MimirSessionPhase.RETURNING) {
            publish("game_returning", current.copy(gameSession = game.copy(phase = MimirSessionPhase.RETURNING)))
        }
        return MimirGameReturnTicket(
            operation = game.operation,
            targetPackageName = game.targetPackageName,
            launchedAtEpochMs = game.dispatchedAtEpochMs ?: game.startedAtEpochMs,
            dualScreen = game.dualScreen,
        )
    }

    @Synchronized
    fun completeGameReturn(
        operation: MimirGameSessionOperation,
        foregroundProven: Boolean,
        failureReason: String? = null,
    ): Boolean = mutateGame(operation, if (failureReason == null) "game_ended" else "game_failed_on_return") { game ->
        if (game.phase != MimirSessionPhase.RETURNING) return@mutateGame null
        game.copy(
            phase = if (failureReason == null) MimirSessionPhase.ENDED else MimirSessionPhase.FAILED,
            foregroundProven = game.foregroundProven || foregroundProven,
            failureReason = failureReason,
        )
    }

    /**
     * End the live game dispatched to [packageName] because its Tabs row was closed or retired: the same
     * return the reclaim path runs, for either placement. Returns the session as it was before ending, or
     * null when no live game was dispatched to that package (or a newer Main app blocks its return).
     */
    @Synchronized
    fun endGameForTabClose(packageName: String, expectedOperation: MimirGameSessionOperation? = null): MimirGameSession? {
        // [expectedOperation] pins the session a caller meant to end: a later launch of the same emulator is a different operation, and a
        // settlement that began for the old one must never end it (matching the package alone cannot tell them apart).
        val game = mutableState.value.liveGameFor(packageName)
            ?.takeIf { expectedOperation == null || it.operation == expectedOperation }
            ?: return null
        if (beginGameReturn(game.operation) == null) return null
        completeGameReturn(game.operation, foregroundProven = game.foregroundProven)
        return game
    }

    /** [onHeld] as in [prepareHomeGame]: what held a refused launch, from this refusal's snapshot, under this lock (O15). */
    @Synchronized
    fun preparePrimaryApp(
        app: CompanionRunningApp,
        returnAnchor: PrimaryReturnOrigin,
        startedAtEpochMs: Long = System.currentTimeMillis(),
        onHeld: (GameLaunchSessionHold) -> Unit = {},
    ): String? {
        val current = mutableState.value
        val activeGame = current.gameSession?.phase?.let { it in ActivePresentationPhases } == true
        val activePrimaryApp = current.primaryAppSession?.phase?.let { it in ActivePresentationPhases } == true
        if (activeGame || activePrimaryApp) {
            refuse("app", current, includePrimaryApp = true, onHeld = onHeld)
            return null
        }
        val operationId = "primary-native:${OperationId.create()}"
        return operationId.takeIf {
            appPreparing(operationId, app, MimirAppPlacement.PRIMARY, returnAnchor, startedAtEpochMs)
        }
    }

    @Synchronized
    fun appPreparing(
        operationId: String,
        app: CompanionRunningApp,
        placement: MimirAppPlacement,
        returnAnchor: PrimaryReturnOrigin,
        startedAtEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val cleanOperation = operationId.trim()
        val cleanPackage = app.packageName.trim()
        if (cleanOperation.isEmpty() || cleanPackage.isEmpty()) return false
        val current = mutableState.value
        val currentPlacementSession = when (placement) {
            MimirAppPlacement.PRIMARY -> current.primaryAppSession
            MimirAppPlacement.SECONDARY -> current.secondaryAppSession
        }
        if (currentPlacementSession?.operationId == cleanOperation) return true
        if (placement == MimirAppPlacement.PRIMARY) {
            val activeGame = current.gameSession?.phase?.let { it in ActivePresentationPhases } == true
            val activePrimaryApp =
                current.primaryAppSession?.phase?.let { it in ActivePresentationPhases } == true
            if (activeGame || activePrimaryApp) {
                refuse("app", current, includePrimaryApp = true)
                return false
            }
        }
        val nextSequence = nextSequence()
        val nextApp = MimirAppSession(
            operationId = cleanOperation,
            sequence = nextSequence,
            packageName = cleanPackage,
            label = app.label.ifBlank { cleanPackage },
            iconPath = app.iconPath?.takeIf(String::isNotBlank),
            placement = placement,
            returnAnchor = returnAnchor,
            startedAtEpochMs = startedAtEpochMs,
            phase = MimirSessionPhase.PREPARING,
        )
        val next = when (placement) {
            MimirAppPlacement.PRIMARY -> current.copy(
                primaryAppSession = nextApp,
            )
            MimirAppPlacement.SECONDARY -> current.copy(secondaryAppSession = nextApp)
        }
        return publish(
            "app_preparing",
            next,
        )
    }

    @Synchronized
    fun appDispatched(operationId: String): Boolean =
        mutateApp(operationId, "app_dispatched") { app ->
            if (app.phase != MimirSessionPhase.PREPARING) null
            else app.copy(phase = MimirSessionPhase.DISPATCHED)
        }

    @Synchronized
    fun appForegroundProven(operationId: String): Boolean =
        mutateApp(operationId, "app_foreground_proven") { app ->
            if (app.phase !in setOf(MimirSessionPhase.PREPARING, MimirSessionPhase.DISPATCHED)) null
            else app.copy(phase = MimirSessionPhase.FOREGROUND_PROVEN, foregroundProven = true)
        }

    @Synchronized
    fun appYielded(operationId: String): Boolean =
        mutateApp(operationId, "app_yielded") { app ->
            if (app.phase !in ActivePresentationPhases) null
            else app.copy(phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK)
        }

    @Synchronized
    fun appReturning(operationId: String): Boolean =
        mutateApp(operationId, "app_returning") { app ->
            if (app.phase !in ActivePresentationPhases) null
            else app.copy(phase = MimirSessionPhase.RETURNING)
        }

    @Synchronized
    fun appEnded(operationId: String): Boolean =
        mutateApp(operationId, "app_ended") { app ->
            if (app.phase !in ActivePresentationPhases) null else app.copy(phase = MimirSessionPhase.ENDED)
        }

    @Synchronized
    fun appFailed(operationId: String, reason: String): Boolean =
        mutateApp(operationId, "app_failed") { app ->
            if (app.phase !in ActivePresentationPhases) null
            else app.copy(phase = MimirSessionPhase.FAILED, failureReason = reason)
        }

    /**
     * The app now owns the Bottom screen, so its Main session is over (2026-09-11 exchange work).
     *
     * A Main app moved down by `secondaryTaskRegistry.swapToSecondary` is settled through its exact armed
     * return only when the display-0 transfer host confirmed it — `confirmCompanionRunningAppSecondaryReturn`
     * needs a proven transfer session in this ledger. An app the player launched from the Bottom dock has no
     * such host: its PRIMARY session would sit in DISPATCHED forever, and [appPreparing] refuses every
     * later Main launch while a PRIMARY session is in an active presentation phase. Ending it here is
     * what makes a second Main launch — the other half of an exchange — admissible at all. The exchange
     * applies this settlement itself on the landing it observed (O7 / I3, `settleCompanionMainAppLandedOnBottom`);
     * it is idempotent, so whichever of the exchange and the process collector runs second changes nothing.
     *
     * @return true only when a PRIMARY session for [packageName] was active and has been ended.
     */
    @Synchronized
    fun endPrimaryAppForSecondaryMove(packageName: String): Boolean {
        val cleanPackage = packageName.trim()
        if (cleanPackage.isEmpty()) return false
        val app = mutableState.value.primaryAppSession ?: return false
        if (!app.packageName.trim().equals(cleanPackage, ignoreCase = true)) return false
        if (app.phase !in ActivePresentationPhases) return false
        appReturning(app.operationId)
        return appEnded(app.operationId)
    }

    @Synchronized
    fun beginCurrentPrimaryAppReturn(): MimirAppReturnTicket? =
        mutableState.value.primaryAppSession?.operationId?.let(::beginPrimaryAppReturn)

    @Synchronized
    fun beginPrimaryAppReturn(expectedOperationId: String): MimirAppReturnTicket? {
        val current = mutableState.value
        val app = current.primaryAppSession ?: return null
        if (app.operationId != expectedOperationId) return null
        if (app.phase !in setOf(
                MimirSessionPhase.DISPATCHED,
                MimirSessionPhase.FOREGROUND_PROVEN,
                MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
                MimirSessionPhase.RETURNING,
            )
        ) {
            return null
        }
        if (app.phase != MimirSessionPhase.RETURNING) {
            publish(
                "app_returning",
                current.copy(primaryAppSession = app.copy(phase = MimirSessionPhase.RETURNING)),
            )
        }
        return MimirAppReturnTicket(
            operationId = app.operationId,
            targetPackageName = app.packageName,
            launchedAtEpochMs = app.startedAtEpochMs,
        )
    }

    @Synchronized
    fun completePrimaryAppReturn(
        operationId: String,
        foregroundProven: Boolean,
        failureReason: String? = null,
    ): Boolean = mutateApp(
        operationId,
        if (failureReason == null) "app_ended" else "app_failed_on_return",
    ) { app ->
        if (app.phase != MimirSessionPhase.RETURNING || app.placement != MimirAppPlacement.PRIMARY) {
            return@mutateApp null
        }
        app.copy(
            phase = if (failureReason == null) MimirSessionPhase.ENDED else MimirSessionPhase.FAILED,
            foregroundProven = app.foregroundProven || foregroundProven,
            failureReason = failureReason,
        )
    }

    @Synchronized
    fun staleCurrentSecondary(reason: String): Boolean {
        val current = mutableState.value
        val app = current.secondaryAppSession ?: return false
        if (app.phase !in ActivePresentationPhases) return false
        return publish(
            "app_stale",
            current.copy(secondaryAppSession = app.copy(phase = MimirSessionPhase.STALE, failureReason = reason)),
        )
    }

    private fun mutateGame(
        operation: MimirGameSessionOperation,
        event: String,
        mutation: (MimirGameSession) -> MimirGameSession?,
    ): Boolean {
        val current = mutableState.value
        val game = current.gameSession ?: return false
        if (game.operation != operation) return false
        val next = mutation(game) ?: return false
        return publish(event, current.copy(gameSession = next))
    }

    private fun mutateApp(
        operationId: String,
        event: String,
        mutation: (MimirAppSession) -> MimirAppSession?,
    ): Boolean {
        val current = mutableState.value
        val cleanOperation = operationId.trim()
        val app = when (cleanOperation) {
            current.primaryAppSession?.operationId -> current.primaryAppSession
            current.secondaryAppSession?.operationId -> current.secondaryAppSession
            else -> null
        } ?: return false
        val next = mutation(app) ?: return false
        val updated = when (app.placement) {
            MimirAppPlacement.PRIMARY -> current.copy(primaryAppSession = next)
            MimirAppPlacement.SECONDARY -> current.copy(secondaryAppSession = next)
        }
        return publish(event, updated)
    }

    private fun publish(event: String, next: MimirSessionSnapshot): Boolean {
        val updated = next.copy(revision = mutableState.value.revision + 1L)
        mutableState.value = updated
        trace(event, updated, null)
        return true
    }

    /**
     * R6-P0-02 S1: the one place every admission refusal is logged. Not a [publish] — the snapshot this
     * refusal was decided on is unchanged (no revision bump; nothing here was admitted) — so it calls
     * [trace] directly with that snapshot and the refusal's own kind/hold, then (when present) tells
     * [onHeld] the same hold, exactly as each branch already did before this refuse() existed.
     */
    private fun refuse(
        kind: String,
        current: MimirSessionSnapshot,
        includePrimaryApp: Boolean,
        onHeld: ((GameLaunchSessionHold) -> Unit)? = null,
    ): GameLaunchSessionHold {
        val hold = current.refusedLaunchHold(includePrimaryApp = includePrimaryApp)
        onHeld?.invoke(hold)
        trace("launch_refused", current, MimirRefusalContext(kind, hold))
        return hold
    }

    private fun nextSequence(): Long {
        sequence += 1L
        return sequence
    }
}

internal fun libraryGameSessionOperation(operation: LibraryGameLaunchOperation): MimirGameSessionOperation =
    MimirGameSessionOperation("library:${operation.value}")

private fun MimirGameSession.staleIfActive(reason: String): MimirGameSession =
    if (phase in ActivePresentationPhases) copy(phase = MimirSessionPhase.STALE, failureReason = reason) else this

private fun MimirAppSession.staleIfActive(reason: String): MimirAppSession =
    if (phase in ActivePresentationPhases) copy(phase = MimirSessionPhase.STALE, failureReason = reason) else this

/**
 * The art a game's session carries: the cover- or disc-shaped face the resolver gave the row when it has one, else its
 * automatic cover.
 *
 * Policy 2026-10-10 (one shelf face) moved a `pc_games` game's automatic SteamGridDB portrait out of
 * `GameRow.artUri` (where the Library projection rewrote it) into `GameRow.wiredFace`. Reading `artUri` alone sent a
 * Library-launched PC game to the Bottom's game tab and the Now Playing card with no art. A SQUARE is the Icon-art tile,
 * not a cover, so it is only the LAST fallback: a game whose one picture is a square (a PC game with only its SteamGridDB
 * square) used to carry that square through `artUri` and keeps carrying it, but a cover of any kind outranks it.
 */
internal fun gameSessionArtRef(game: GameRow): String? =
    (
        game.wiredFace?.takeIf { it.shape == ShelfFaceShape.LEDGE_COVER }?.uri?.takeIf(String::isNotBlank)
            ?: game.artUri?.takeIf(String::isNotBlank)
            ?: game.wiredFace?.takeIf { it.shape == ShelfFaceShape.SQUARE }?.uri
        )
        ?.trim()
        ?.takeIf(String::isNotEmpty)

/** Names the Bottom's tab for the game [session] resolved, once its emulator [packageName] is dispatched. */
internal fun noteBottomGameTab(session: MimirGameSession?, packageName: String) {
    session
        ?.takeIf { it.placement == MimirGamePlacement.BOTTOM }
        ?.let { game ->
            runCatching {
                MimirBottomGameTabs.noteGameTab(packageName, game.title, game.gameId.value, game.artRef)
            }
        }
}

internal object MimirSessionAuthority : LibraryGameSessionObserver {
    private const val Tag = "MimirSession"
    // MainActivity evaluates this absolute dispatch deadline with SystemClock.elapsedRealtime(). Android's
    // elapsedRealtime includes suspend; System.nanoTime does not. Mixing them made a slept device calculate
    // zero remaining grace and reap a freshly relaunched emulator before it could take focus.
    private val coordinator = MimirSessionCoordinator(
        monotonicMillis = { android.os.SystemClock.elapsedRealtime() },
        trace = { event, snapshot, refusal ->
            // The performance card's memory of the games Mimir launched. The ledger holds ONE game, so a game
            // launched earlier is forgotten here the moment a newer one takes the slot; this is what lets the
            // card still recognise it when the player switches back. Never allowed to cost the log line below.
            runCatching { MimirLaunchedGames.observe(snapshot.gameSession) }
            runCatching {
                // `failureReason` is carried on the session and was never printed, so a failed launch
                // logged `game=FAILED` and nothing else -- the one field that says WHY. Measured on an Odin test device 2026-09-06: a Switch title went PREPARING -> FAILED in 3 ms with no
                // reason anywhere, on a device where the emulator was installed and the ROM was present.
                // Three hypotheses died before this line existed. It is not diagnostics-only: a launch
                // that fails silently is a launch the player cannot act on.
                val gameReason = snapshot.gameSession?.failureReason
                val appReason = snapshot.primaryAppSession?.failureReason
                    ?: snapshot.secondaryAppSession?.failureReason
                // P11: the game's placement (MAIN/BOTTOM) was never printed, so §1.15 could not tell
                // which screen a game was actually sent to from this line alone.
                // R6-P0-02 S1: a refusal (event == "launch_refused") was the one accepted transition this
                // line never covered -- refuse() never bumps the revision, so this is still that same
                // revision's snapshot, and `refusedKind`/`hold` say what was refused and by what, while
                // `parked` says whether a parked Main game -- distinct from an ordinary running one -- is
                // what is holding it (spec-R6-P0-02 §4 S1).
                Log.i(
                    Tag,
                    "rev=${snapshot.revision} event=$event " +
                        (refusal?.let { "refusedKind=${it.kind} hold=${it.hold} " } ?: "") +
                        "game=${snapshot.gameSession?.phase} " +
                        "primaryApp=${snapshot.primaryAppSession?.phase} " +
                        "secondaryApp=${snapshot.secondaryAppSession?.phase} " +
                        "placement=${snapshot.gameSession?.placement}" +
                        (gameReason?.let { " gameReason=$it" } ?: "") +
                        (appReason?.let { " appReason=$it" } ?: "") +
                        (refusal?.let { " parked=${MainGameParkingState.current() != null}" } ?: ""),
                )
            }
        },
    ).apply {
        // G-2: arm the companion scene's game-owner key at dispatch — the emulator package is the truth a
        // later foreground observation re-derives NOW_PLAYING from, and it must survive the quick-launch
        // "superseded_by_app" stale (the session phase stales; the re-derive key must not).
        onGameYielded = { game -> game.libraryOperation?.let { operation -> libraryYieldObserver?.invoke(operation) } }
        onEmulatorClosedEarly = { game ->
            if (LaunchEarlyClose.isUnexpectedClose(game)) earlyCloseNotifier?.invoke(game)
        }
        onGameDispatched = { packageName ->
            CompanionSceneState.armGameOwner(packageName)
            // a session no Home launch opened a play-time measurement for (a Library launch) opens its own here.
            GameSessionPlayTime.onDispatched(state.value.gameSession)
            // The Bottom tab for this game was created at dispatch from the Intent alone, so it knows the
            // emulator and nothing else. Name it now, from the session that resolved the library row.
            noteBottomGameTab(state.value.gameSession, packageName)
        }
    }

    val state: StateFlow<MimirSessionSnapshot> = coordinator.state
    @Volatile private var libraryDispatchCleanup: ((LibraryGameLaunchOperation) -> Boolean)? = null

    /** O15: what held each Library launch this ledger refused, for [holdAfterRefusal]. */
    private val libraryRefusals = LibraryLaunchRefusals()

    fun installLibraryDispatchCleanup(cleanup: (LibraryGameLaunchOperation) -> Boolean) {
        libraryDispatchCleanup = cleanup
    }

    @Volatile private var libraryYieldObserver: ((LibraryGameLaunchOperation) -> Boolean)? = null
    @Volatile private var earlyCloseNotifier: ((MimirGameSession) -> Unit)? = null

    /** F2: the Library hears that its launch's emulator has taken the screen, so "Starting <Emulator>…" can go. */
    fun installLibraryYieldObserver(observer: (LibraryGameLaunchOperation) -> Boolean) {
        libraryYieldObserver = observer
    }

    /** F1: told, once per launch, that the emulator never took the window (see [LaunchEarlyClose]). */
    fun installEarlyCloseNotifier(notifier: (MimirGameSession) -> Unit) {
        earlyCloseNotifier = notifier
    }

    override fun onEvent(event: LibraryGameSessionEvent): Boolean =
        when (event) {
            is LibraryGameSessionEvent.Preparing -> coordinator.gamePreparing(event) { hold ->
                libraryRefusals.record(event.operation, hold)
            }
            is LibraryGameSessionEvent.Settled -> coordinator.gameSettled(event)
            is LibraryGameSessionEvent.Failed -> coordinator.gameFailed(event)
        }

    /** O15: the hold recorded when this ledger refused that exact Library launch; STARTING for any other. */
    override fun holdAfterRefusal(event: LibraryGameSessionEvent.Preparing): GameLaunchSessionHold =
        libraryRefusals.holdFor(event.operation)

    /**
     * Library has no Context of its own, so this falls back to
     * CompanionProcessRuntime's own cached process context -- the same probe the Home admission path's own
     * retry calls with its Compose context. The shared refusal copy (`:app`-owned, via
     * [parkedGameStillPausedMessage]/[parkedGameDisplayTitle]) travels back inside
     * [ParkedLeaseReleaseOutcome.StillParked] rather than feature-library reaching into `:app` for it.
     */
    override suspend fun releaseParkedLeaseAfterRefusal(
        event: LibraryGameSessionEvent.Preparing,
    ): ParkedLeaseReleaseOutcome {
        val lease = MainGameParkingState.current() ?: return ParkedLeaseReleaseOutcome.NotParked
        val released = CompanionProcessRuntime.releaseParkedLeaseForPress(context = null, stage = "play")
        return if (released) {
            ParkedLeaseReleaseOutcome.Released
        } else {
            libraryParkedRefusalOutcome(lease, event.game)
        }
    }

    fun beginCurrentGameReturn(): MimirGameReturnTicket? = coordinator.beginCurrentGameReturn()

    fun beginGameReturn(operation: MimirGameSessionOperation): MimirGameReturnTicket? =
        coordinator.beginGameReturn(operation)

    fun completeGameReturn(
        operation: MimirGameSessionOperation,
        foregroundProven: Boolean,
        failureReason: String? = null,
    ): Boolean = coordinator.completeGameReturn(operation, foregroundProven, failureReason)

    fun endGameForTabClose(packageName: String, expectedOperation: MimirGameSessionOperation? = null): MimirGameSession? =
        coordinator.endGameForTabClose(packageName, expectedOperation)

    fun prepareHomeGame(
        game: GameRow,
        returnTarget: HomeLaunchReturnTarget,
        returnHandle: HomeLaunchReturnHandle? = null,
        startedAtEpochMs: Long = System.currentTimeMillis(),
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
        onHeld: (GameLaunchSessionHold) -> Unit = {},
    ): MimirGameSessionOperation? =
        coordinator.prepareHomeGame(game, returnTarget, returnHandle, startedAtEpochMs, placement, onHeld)

    fun settleHomeGame(
        operation: MimirGameSessionOperation?,
        game: GameRow,
        result: LaunchResult,
    ): Boolean = operation?.let { coordinator.gameSettled(it, game.gameId, result) } ?: false

    fun failHomeGame(
        operation: MimirGameSessionOperation?,
        game: GameRow,
        reason: String,
    ): Boolean = operation?.let { coordinator.gameFailed(it, game.gameId, reason) } ?: false

    fun gameFailed(
        operation: MimirGameSessionOperation,
        gameId: GameId,
        reason: String,
    ): Boolean = coordinator.gameFailed(operation, gameId, reason)

    fun gameForegroundProven(operation: MimirGameSessionOperation): Boolean =
        coordinator.gameForegroundProven(operation)

    fun gameDispatchWatchdogExpired(
        operation: MimirGameSessionOperation,
        expectedPackageName: String,
        reason: String = "target_never_left_primary",
    ): MimirGameDispatchCleanup? =
        coordinator.gameDispatchWatchdogExpired(operation, expectedPackageName, reason)

    fun applyLibraryDispatchWatchdogCleanup(cleanup: MimirGameDispatchCleanup): Boolean =
        applyLibraryDispatchCleanupSafely(cleanup.libraryOperation, libraryDispatchCleanup)

    fun gameDispatchWatchdogCleanupComplete(cleanup: MimirGameDispatchCleanup): Boolean =
        coordinator.gameDispatchWatchdogCleanupComplete(
            operation = cleanup.operation,
            expectedPackageName = cleanup.expectedPackageName,
        )

    fun gameYielded(operation: MimirGameSessionOperation): Boolean = coordinator.gameYielded(operation)

    fun preparePrimaryApp(
        app: CompanionRunningApp,
        returnAnchor: PrimaryReturnOrigin = CompanionPrimaryOriginStore.current(),
        startedAtEpochMs: Long = System.currentTimeMillis(),
        onHeld: (GameLaunchSessionHold) -> Unit = {},
    ): String? = coordinator.preparePrimaryApp(app, returnAnchor, startedAtEpochMs, onHeld)

    /** The app now owns the Bottom screen, so its Main session is over (2026-09-11 exchange work). */
    fun endPrimaryAppForSecondaryMove(packageName: String): Boolean =
        coordinator.endPrimaryAppForSecondaryMove(packageName)

    fun beginCurrentPrimaryAppReturn(): MimirAppReturnTicket? = coordinator.beginCurrentPrimaryAppReturn()

    fun beginPrimaryAppReturn(operationId: String): MimirAppReturnTicket? =
        coordinator.beginPrimaryAppReturn(operationId)

    fun completePrimaryAppReturn(
        operationId: String,
        foregroundProven: Boolean,
        failureReason: String? = null,
    ): Boolean = coordinator.completePrimaryAppReturn(operationId, foregroundProven, failureReason)

    fun appPreparing(
        operationId: String,
        app: CompanionRunningApp,
        placement: MimirAppPlacement,
        returnAnchor: PrimaryReturnOrigin,
        startedAtEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = coordinator.appPreparing(operationId, app, placement, returnAnchor, startedAtEpochMs)

    fun appDispatched(operationId: String): Boolean = coordinator.appDispatched(operationId)
    fun appForegroundProven(operationId: String): Boolean = coordinator.appForegroundProven(operationId)
    fun appYielded(operationId: String): Boolean = coordinator.appYielded(operationId)
    fun appReturning(operationId: String): Boolean = coordinator.appReturning(operationId)
    fun appEnded(operationId: String): Boolean = coordinator.appEnded(operationId)
    fun appFailed(operationId: String, reason: String): Boolean = coordinator.appFailed(operationId, reason)
    fun staleCurrentSecondary(reason: String): Boolean = coordinator.staleCurrentSecondary(reason)
}

/**
 * The one seam the session ledger uses to name a Bottom game's tab in the native task registry.
 *
 * A function rather than the registry itself: the session authority is a pure process object and must not
 * acquire a Context or a native dependency to add a label. MainActivity installs the real writer when the
 * companion runtime exists; before that it is a no-op and the tab keeps its emulator label — which is the
 * documented fallback, not a missing feature.
 */
internal object MimirBottomGameTabs {
    @Volatile
    var noteGameTab: (packageName: String, title: String?, gameId: String?, artRef: String?) -> Unit =
        { _, _, _, _ -> }

    /**
     * Drop the tab for a game that FAILED — the launch never reached display 4, or the display went away
     * with every task on it. Deliberately NOT called when a game settles normally: a player who swiped back
     * to the shelf left a live emulator task behind, and that tab is the only way back to it.
     */
    @Volatile
    var forgetGameTab: (packageName: String) -> Unit = { }
}

internal fun primaryAppSessionOperation(operationId: java.util.UUID): String = "primary:$operationId"

/**
 * The display-0 transfer UUID behind a [primaryAppSessionOperation] id; null for any other operation, a dock launch's
 * `primary-native:` id included. It round-trips through [primaryAppSessionOperation], so the two cannot drift apart.
 */
internal fun primaryAppSessionTransferId(operationId: String): java.util.UUID? =
    runCatching { java.util.UUID.fromString(operationId.removePrefix("primary:")) }
        .getOrNull()
        ?.takeIf { primaryAppSessionOperation(it) == operationId }

internal fun secondaryAppSessionOperation(operationId: OperationId): String = "secondary:$operationId"

/** Converts accepted native runtime transitions into semantic app-session evidence. */
internal object MimirCompanionRuntimeSessionBridge : CompanionRuntimeSessionObserver {
    private val reclaimParents = mutableMapOf<OperationId, OperationId>()
    private val launchModes = mutableMapOf<OperationId, String>()
    private val gameOperations = mutableMapOf<OperationId, MimirGameSessionOperation>()
    /** Explicit Main-game handoffs own no secondary app/game session in this bridge. */
    private val mainGameHandoffs = mutableSetOf<OperationId>()
    private val failedSettlements = mutableSetOf<OperationId>()
    private val cleanedGames = mutableSetOf<MimirGameSessionOperation>()

    /**
     * Reclaim operations the secondary-home floor issued under [BottomHomeParkReclaimReason] over a Bottom game it is parking. The
     * session is the park's to keep, so neither the reclaim's start nor its landing returns it.
     */
    private val bottomParkedReclaims = mutableSetOf<OperationId>()

    private fun isGame(operation: OperationId): Boolean =
        launchModes[operation] == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME

    /**
     * Record the lease's launch mode, and never downgrade an operation a game has already claimed.
     *
     * A game reclaimed from its Bottom tab arrives as a `secondary_app` lease — that IS the reopen path —
     * and is adopted below. Every later snapshot for that operation still reads `secondary_app` off the
     * lease, so a plain write would hand the game's own Yielded back to the app branch one event later.
     */
    private fun noteLaunchMode(operation: OperationId, launchMode: String) {
        if (isGame(operation)) return
        launchModes[operation] = launchMode
    }

    private fun gameOperation(hostOperation: OperationId): MimirGameSessionOperation? =
        gameOperations[hostOperation]

    private fun failGame(operation: MimirGameSessionOperation, reason: String) {
        val game = MimirSessionAuthority.state.value.gameSession?.takeIf { it.operation == operation } ?: return
        // A failed game has no task on display 4 -- the launch never landed, or the display took every task
        // with it -- so its tab goes with the session. A game that merely settled keeps its tab: the player
        // swiped back to the shelf and the emulator is still running behind the mirror.
        if (game.placement == MimirGamePlacement.BOTTOM) {
            game.targetPackageName?.let { pkg -> runCatching { MimirBottomGameTabs.forgetGameTab(pkg) } }
        }
        if (game.phase == MimirSessionPhase.PREPARING) {
            MimirSessionAuthority.gameFailed(operation, game.gameId, reason)
            // A secondary game can fail before LaunchController settles its prepared return. Release
            // the process-wide away-game state here as well; the cleanup is operation-deduped below and
            // its optional target/handle data must never suppress audio/Tweaker settlement.
            if (game.placement == MimirGamePlacement.BOTTOM && cleanedGames.add(operation)) {
                CompanionProcessRuntime.settleBottomGameCleanup(game)
            }
            return
        }
        if (game.phase in setOf(
                MimirSessionPhase.DISPATCHED,
                MimirSessionPhase.FOREGROUND_PROVEN,
                MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
                MimirSessionPhase.RETURNING,
            )
        ) {
            MimirSessionAuthority.beginGameReturn(operation)
            MimirSessionAuthority.completeGameReturn(operation, foregroundProven = game.foregroundProven, failureReason = reason)
            if (cleanedGames.add(operation)) CompanionProcessRuntime.settleBottomGameCleanup(game)
        }
    }

    @Synchronized
    override fun onRuntimeEvent(event: String, snapshot: CompanionRuntimeSnapshot) {
        snapshot.launchSettlement
            ?.takeIf { it.kind == LaunchSettlementKind.FAILED && failedSettlements.add(it.operationId) }
            ?.let { settlement ->
                if (isGame(settlement.operationId)) {
                    gameOperation(settlement.operationId)?.let { failGame(it, "secondary_launch_failed") }
                    gameOperations.remove(settlement.operationId)
                    launchModes.remove(settlement.operationId)
                } else {
                    MimirSessionAuthority.appFailed(
                        secondaryAppSessionOperation(settlement.operationId),
                        "secondary_launch_failed",
                    )
                }
            }

        when (val disposition = snapshot.disposition) {
            is SecondaryDisplayDisposition.LaunchPending -> {
                val lease = disposition.lease
                noteLaunchMode(lease.operationId, lease.launchMode)
                // A tap on the game's Bottom tab reopens it through the ORDINARY secondary-app path, because
                // relaunching the emulator package is exactly what brings its existing task -- with the
                // running game on top -- back to display 4. The launch mode therefore says "app" while the
                // thing being reopened is a game, and the session that owns it must claim the operation here:
                // otherwise this mints an app_* session for the emulator and the game's own session is left
                // with no host operation to return through.
                if (event == "LaunchRequested" && !isGame(lease.operationId)) {
                    MimirSessionAuthority.state.value.liveBottomGameFor(lease.packageName)?.let { game ->
                        launchModes[lease.operationId] = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME
                        gameOperations[lease.operationId] = game.operation
                        runCatching {
                            Log.i(
                                "MimirSession",
                                "game reclaimed to bottom game=${game.gameId.value} package=${lease.packageName}",
                            )
                        }
                        return
                    }
                }
                if (isGame(lease.operationId)) {
                    if (event == "LaunchRequested") {
                        val game = MimirSessionAuthority.state.value.gameSession
                        if (game?.placement == MimirGamePlacement.BOTTOM &&
                            (game.targetPackageName == null || game.targetPackageName == lease.packageName)
                        ) {
                            gameOperations[lease.operationId] = game.operation
                        }
                    }
                } else {
                    val operation = secondaryAppSessionOperation(lease.operationId)
                    if (event == "LaunchRequested") {
                        MimirSessionAuthority.appPreparing(
                            operationId = operation,
                            app = CompanionRunningApp(lease.packageName, lease.packageName),
                            placement = MimirAppPlacement.SECONDARY,
                            returnAnchor = lease.primaryOrigin,
                        )
                    }
                    if (disposition.phase == LaunchPhase.TARGET_DISPATCHED) {
                        MimirSessionAuthority.appDispatched(operation)
                    }
                }
            }
            is SecondaryDisplayDisposition.Yielded -> if (disposition.evidence == YieldEvidence.USER_REQUESTED_GAME_RETURN) {
                disposition.lease?.operationId?.let(mainGameHandoffs::add)
            } else {
                disposition.lease?.let { lease ->
                noteLaunchMode(lease.operationId, lease.launchMode)
                if (isGame(lease.operationId)) {
                    gameOperation(lease.operationId)?.let { gameOperation ->
                        if (disposition.evidence == YieldEvidence.HOST_COVERED) {
                            MimirSessionAuthority.gameForegroundProven(gameOperation)
                        }
                        MimirSessionAuthority.gameYielded(gameOperation)
                        // The Bottom game is in front again (its tab, or the card's Resume), so its HOME park is over.
                        if (event == "HostCovered") MainGameHomePark.bottomGameFronted(gameOperation)
                    }
                    return@let
                }
                val operation = secondaryAppSessionOperation(lease.operationId)
                if (disposition.evidence == YieldEvidence.HOST_COVERED) {
                    MimirSessionAuthority.appForegroundProven(operation)
                }
                MimirSessionAuthority.appYielded(operation)
                }
            }
            is SecondaryDisplayDisposition.ReclaimPending -> disposition.lease.parentHostOperationId?.let { parent ->
                if (mainGameHandoffs.remove(parent)) return@let
                reclaimParents[disposition.lease.operationId] = parent
                if (isGame(parent)) {
                    if (disposition.lease.reason == BottomHomeParkReclaimReason) {
                        bottomParkedReclaims += disposition.lease.operationId
                    } else {
                        gameOperation(parent)?.let(MimirSessionAuthority::beginGameReturn)
                    }
                } else {
                    MimirSessionAuthority.appReturning(secondaryAppSessionOperation(parent))
                }
            }
            is SecondaryDisplayDisposition.MirrorPresented -> {
                reclaimParents.remove(disposition.attempt.parentDispositionId)?.let { parent ->
                    if (isGame(parent)) {
                        val parked = bottomParkedReclaims.remove(disposition.attempt.parentDispositionId)
                        gameOperation(parent)?.takeUnless { parked }?.let { gameOperation ->
                            val game = MimirSessionAuthority.state.value.gameSession
                                ?.takeIf { it.operation == gameOperation }
                            if (game != null &&
                                MimirSessionAuthority.completeGameReturn(
                                    game.operation,
                                    foregroundProven = game.foregroundProven,
                                ) &&
                                cleanedGames.add(game.operation)
                            ) {
                                CompanionProcessRuntime.settleBottomGameCleanup(game)
                            }
                        }
                        gameOperations.remove(parent)
                        launchModes.remove(parent)
                    } else {
                        MimirSessionAuthority.appEnded(secondaryAppSessionOperation(parent))
                    }
                }
            }
            is SecondaryDisplayDisposition.RecoveryNeeded -> {
                bottomParkedReclaims.remove(disposition.operationId)
                val parent = reclaimParents.remove(disposition.operationId) ?: disposition.operationId
                if (isGame(parent)) {
                    gameOperation(parent)?.let { failGame(it, disposition.failure.code.name.lowercase()) }
                    gameOperations.remove(parent)
                    launchModes.remove(parent)
                } else {
                    MimirSessionAuthority.appFailed(
                        secondaryAppSessionOperation(parent),
                        disposition.failure.code.name.lowercase(),
                    )
                }
            }
            SecondaryDisplayDisposition.NoDisplay -> {
                MimirSessionAuthority.staleCurrentSecondary("display_removed")
                launchModes.filterValues { it == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME }
                    .keys
                    .forEach { operationId ->
                        gameOperation(operationId)?.let { failGame(it, "display_removed") }
                        launchModes.remove(operationId)
                        gameOperations.remove(operationId)
                }
                bottomParkedReclaims.clear()
                reclaimParents.clear()
                mainGameHandoffs.clear()
            }
            is SecondaryDisplayDisposition.Presenting -> Unit
        }
    }

    /**
     * End the game dispatched to that package, Main or Bottom, whose tab the player just closed with ✕.
     *
     * The ✕ removes the tab and reaps the emulator; that is a native close and it settles nothing semantic.
     * A game session carries process-wide posture — Tweaker, audio focus, the retained NOW_PLAYING card and
     * the Home return arm — so closing its tab has to run the SAME return the reclaim path runs, or the
     * player is back on the shelf with a game's posture still applied and no session left to release it.
     *
     * A Tabs liveness reconcile that retires a GAME row, because the emulator was proven to have no process,
     * runs this too, with [endedBy] `"tabs liveness"` (Thor, 2026-09-12): the row and its ✕ are gone, and this
     * is what that ✕ would have ended. [endedBy] only names the cause in the log line.
     *
     * Deduped against [failGame] through `cleanedGames`: one accepted operation gets exactly one cleanup.
     * Returns the session it ended, or null when no live game owned that package.
     */
    @Synchronized
    fun endBottomGameForTabClose(
        packageName: String,
        endedBy: String = "tab close",
        expectedOperation: MimirGameSessionOperation? = null,
    ): MimirGameSession? {
        // Its Tabs row was closed or its process proven gone, so the performance card has no game to recognise
        // there any more. Before the ledger lookup on purpose: the ledger holds one game, and once this game is
        // ENDED, or a newer one has replaced it, the lookup below finds nothing and returns early. The session
        // merely ENDING does not forget a game; this does.
        forgetLaunchedGameOnTabClose(packageName, expectedOperation)
        val game = MimirSessionAuthority.endGameForTabClose(packageName, expectedOperation) ?: return null
        if (cleanedGames.add(game.operation)) CompanionProcessRuntime.settleBottomGameCleanup(game)
        gameOperations.entries.removeAll { it.value == game.operation }
        runCatching {
            Log.i(
                "MimirSession",
                "game ended by $endedBy game=${game.gameId.value} package=${packageName.trim()} placement=${game.placement}",
            )
        }
        // Receipt: the game's session ended by a Bottom-side cause (tab close, tabs liveness, parked task gone).
        BottomUiReceipts.log.session(
            "exit",
            game.operation.value,
            companionBottomDisplayId(),
            endedBy.trim().replace(' ', '_'),
            packageName.trim(),
        )
        return game
    }

    /** A fast host can reach Yielded before the caller settles LaunchController's accepted dispatch. */
    @Synchronized
    fun reconcileGameDispatch() {
        val snapshot = CompanionReturnCoordinator.runtime().snapshot.value
        val yielded = snapshot.disposition as? SecondaryDisplayDisposition.Yielded ?: return
        if (yielded.evidence == YieldEvidence.USER_REQUESTED_GAME_RETURN) return
        val lease = yielded.lease ?: return
        if (!isGame(lease.operationId)) return
        gameOperation(lease.operationId)?.let {
            // Yielded is also used for a best-effort/native task observation. Only a host-covered
            // yield proves the game foregrounded; the semantic session still advances to yielded so
            // Return can settle without manufacturing foreground evidence.
            if (yielded.evidence == YieldEvidence.HOST_COVERED) {
                MimirSessionAuthority.gameForegroundProven(it)
            }
            MimirSessionAuthority.gameYielded(it)
        }
    }
}
