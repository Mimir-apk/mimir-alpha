package com.gruning.mimir.rewrite

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The performance card's desired state, as a pure function of what is true right now.
 *
 * The card used to be wanted only for
 * `dispatchedGame`, the ONE game of the current session ledger. Launch A, return to Mimir (A's session
 * ENDS, A keeps running), launch B (the ledger now owns B), then switch back to A through Recents: A is in
 * front, A is not the ledger's game, so the card was not wanted and never came back for A. The ledger is a
 * single slot by design (one active game at a time); the card must not borrow that limit.
 *
 * So the card is state-driven. The inputs are the live foreground, the ledger's current game, and a small
 * bounded memory of the games Mimir has launched ([PerformanceOverlayGameRegistry]); the answer is a
 * `(wanted, target)` pair. Applying the same answer twice changes nothing, which is what
 * [PerformanceOverlayReconciler] reports and what keeps the log to one line per real change.
 */

/** How many launched games the overlay remembers; the least recently used one is dropped past this. */
internal const val PerformanceOverlayGameRegistryCap = 8

/** How many removed launch operations the registry remembers, so none of them can register again. */
private const val RetiredOperationCap = 64

/**
 * One launched game the registry remembers: the emulator (or app) package the launch reached, and the little
 * that identifies the launch. [operation] is the session key; [gameId] and [launchedAtEpochMs] are carried so
 * the memory can outlive the process ([LaunchedGameRegistryPersistence]) and still say which game it was.
 * Package names, opaque ids and a time; no URL, key or file path is ever held here.
 */
internal data class LaunchedGame(
    val packageName: String,
    val operation: String,
    val gameId: String? = null,
    val launchedAtEpochMs: Long? = null,
)

/**
 * A bounded, least-recently-used memory of the game packages Mimir launched, so an older game switched back
 * to is still recognised as a Mimir game after the session ledger has moved on to a newer one.
 *
 * **ENDED does not forget.** A session ends when the player returns to Mimir, and that leaves the game
 * running in Recents; forgetting it there is exactly the bug this registry exists to fix. A game is dropped
 * when it FAILED or went STALE (it never ran, or its display went away), when its Tabs row was closed or
 * its process proven gone ([forget], [forgetPackage]), and when it falls off the end of the cap. A stale
 * entry for a dead process is harmless: a dead package can never be the one in front.
 *
 * Keyed by package, valued by the launch operation that last touched it, so a late event for an old
 * launch cannot forget a newer launch of the same emulator. Package names only; nothing here is personal.
 *
 * **A removed launch never comes back.** The ledger republishes its unchanged game snapshot on every
 * unrelated publish (an app session starting, a refusal), so a registry that registered on every sight of
 * a live phase would resurrect a game the moment after its tab was closed. Every launch operation that is
 * removed, replaced or evicted is remembered as retired (a bounded set), and a retired operation cannot
 * register again; only a NEW launch operation for the package does. Seeing the same operation again,
 * in whatever phase, is a no-op.
 *
 * **It outlives the process.** [listener] is told the full list after every change that alters what is
 * remembered, and [restore] puts a remembered list back at process start. The registry itself does no IO.
 */
internal class PerformanceOverlayGameRegistry(private val cap: Int = PerformanceOverlayGameRegistryCap) {
    init {
        require(cap > 0) { "a registry that holds nothing cannot remember a game" }
    }

    /** Insertion-ordered: the first key is the least recently used, the last the most. */
    private val entries = LinkedHashMap<String, LaunchedGame>()

    /** Launch operations that were removed, replaced or evicted, oldest first; bounded. */
    private val retired = LinkedHashSet<String>()

    /**
     * Told the whole remembered list, least recently used first, after each change to it. Called with the
     * registry's lock held so the lists arrive in the order the changes happened; a listener must only hand
     * the list off (queue it), never do IO or take another lock. Not told about [touch] (it runs every beat
     * and only reorders) nor about [restore] (the restorer writes once, after it has merged).
     */
    @Volatile
    var listener: ((List<LaunchedGame>) -> Unit)? = null

    private fun changed() {
        listener?.invoke(entries.values.toList())
    }

    private fun retire(operation: String) {
        retired.remove(operation)
        retired.add(operation)
        while (retired.size > RetiredOperationCap) {
            val oldest = retired.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    private fun evictPastCap() {
        while (entries.size > cap) {
            val eldest = entries.entries.iterator()
            retire(eldest.next().value.operation)
            eldest.remove()
        }
    }

    /**
     * Registers a launch as the most recently used. A no-op for an operation already registered or already
     * retired, so only a genuinely new launch changes anything.
     */
    @Synchronized
    fun note(
        packageName: String,
        operation: String,
        gameId: String? = null,
        launchedAtEpochMs: Long? = null,
    ) {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || operation in retired || entries[pkg]?.operation == operation) return
        entries.remove(pkg)?.let { retire(it.operation) }
        entries[pkg] = LaunchedGame(pkg, operation, gameId, launchedAtEpochMs)
        evictPastCap()
        changed()
    }

    /** Marks an already registered game as just used, so the one in front is the last to be evicted. */
    @Synchronized
    fun touch(packageName: String) {
        val game = entries[packageName] ?: return
        if (entries.keys.last() == packageName) return
        entries.remove(packageName)
        entries[packageName] = game
    }

    /** Forgets [packageName] only if it is still registered under [operation]; a newer launch is left alone. */
    @Synchronized
    fun forget(packageName: String, operation: String): Boolean {
        retire(operation)
        val pkg = packageName.trim()
        if (entries[pkg]?.operation != operation) return false
        entries.remove(pkg)
        changed()
        return true
    }

    /**
     * Forgets whatever launch is registered for [packageName]: its tab was closed or its process proven gone,
     * and that is true of the package whichever launch the ledger's single slot happens to hold now.
     */
    @Synchronized
    fun forgetPackage(packageName: String): Boolean {
        val game = entries.remove(packageName.trim()) ?: return false
        retire(game.operation)
        changed()
        return true
    }

    /**
     * Puts back games remembered by an earlier process, least recently used first. They go BELOW anything this
     * process has already noted (a recovered session can publish before the restore lands): a package already
     * registered keeps its live launch, a retired operation stays retired, and the cap still holds, dropping
     * the oldest. Returns how many were taken. Nothing is announced to [listener]; the caller writes once.
     */
    @Synchronized
    fun restore(games: List<LaunchedGame>): Int {
        val older = LinkedHashMap<String, LaunchedGame>()
        for (game in games) {
            val pkg = game.packageName.trim()
            if (pkg.isEmpty() || game.operation.isBlank() || game.operation in retired || pkg in entries) continue
            older.remove(pkg)
            older[pkg] = game.copy(packageName = pkg)
        }
        if (older.isEmpty()) return 0
        val live = LinkedHashMap(entries)
        entries.clear()
        entries.putAll(older)
        entries.putAll(live)
        val taken = older.size
        evictPastCap()
        return taken
    }

    /**
     * Applies one session-ledger observation. [packageName] is the emulator the launch reached; a session
     * that has not dispatched yet has none and registers nothing.
     *
     * Registration happens on a launch becoming real (dispatched, or running) and only for an operation
     * not seen before. ENDED never registers: it is the player coming back to Mimir, the game was
     * registered when it dispatched, and a republished ENDED snapshot must not undo a later removal.
     */
    @Synchronized
    fun observeSession(
        operation: String,
        packageName: String?,
        phase: MimirSessionPhase,
        gameId: String? = null,
        launchedAtEpochMs: Long? = null,
    ) {
        val pkg = packageName?.trim()?.takeIf(String::isNotEmpty) ?: return
        when (phase) {
            MimirSessionPhase.DISPATCHED,
            MimirSessionPhase.FOREGROUND_PROVEN,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            MimirSessionPhase.RETURNING,
            -> note(pkg, operation, gameId, launchedAtEpochMs)
            MimirSessionPhase.FAILED,
            MimirSessionPhase.STALE,
            -> forget(pkg, operation)
            MimirSessionPhase.ENDED,
            MimirSessionPhase.PREPARING,
            MimirSessionPhase.RECOVERING,
            -> Unit
        }
    }

    @Synchronized
    fun contains(packageName: String): Boolean = packageName in entries

    /** A copy of the registered packages, least recently used first. */
    @Synchronized
    fun packages(): Set<String> = LinkedHashSet(entries.keys)

    /** A copy of the registered games, least recently used first. */
    @Synchronized
    fun games(): List<LaunchedGame> = entries.values.toList()

    val size: Int
        get() = synchronized(this) { entries.size }
}

/**
 * The process-wide registry. A process object rather than the overlay service's own field so that a game
 * launched while the overlay was off, or before the service restarted, is still known when it is switched
 * back to. Fed from [MimirSessionAuthority] on every ledger publish, so nothing has to be collecting.
 * Survives the process through [MimirLaunchedGamesPersistence], started from the Application.
 */
internal object MimirLaunchedGames {
    val registry = PerformanceOverlayGameRegistry()

    /** [into] is the registry the observation lands in; only a test passes anything but the process-wide one. */
    fun observe(session: MimirGameSession?, into: PerformanceOverlayGameRegistry = registry) {
        val game = session ?: return
        into.observeSession(
            operation = game.operation.value,
            packageName = game.targetPackageName,
            phase = game.phase,
            gameId = game.gameId.value,
            launchedAtEpochMs = game.startedAtEpochMs,
        )
    }
}

/**
 * A game's Tabs row was closed, or its process proven gone: the performance card must stop recognising it.
 *
 * Runs BEFORE the ledger is consulted, because the ledger holds one game and says nothing about the others.
 * Once A is ENDED, or B has replaced it, there is no live ledger game for A's package and the end path
 * returns early; the registry still holds A, and a later restart of A from outside Mimir would get a card.
 * With [expectedOperation] only that launch is forgotten (a newer launch of the same emulator is a different
 * operation and stays); without it, whichever launch is registered for the package goes.
 */
internal fun forgetLaunchedGameOnTabClose(
    packageName: String,
    expectedOperation: MimirGameSessionOperation?,
    registry: PerformanceOverlayGameRegistry = MimirLaunchedGames.registry,
): Boolean =
    if (expectedOperation != null) {
        registry.forget(packageName, expectedOperation.value)
    } else {
        registry.forgetPackage(packageName)
    }

/**
 * The sampling beat's pacing. The beat is one second; a foreground change may cut the WAIT short so the card
 * is reconciled when the change happens, but a sampling iteration (the UsageStats read, the privileged
 * frame-rate read, the telemetry read) never starts sooner than [beatMs] after the previous one started,
 * however many nudges arrive. A flapping foreground therefore costs exactly what a quiet one does.
 */
internal class PerformanceOverlayBeat(
    private val beatMs: Long = PerformanceTelemetryPollMs,
    private val nowMs: () -> Long,
) {
    private val nudges = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var iterationStartedAtMs: Long? = null

    /** The foreground changed. Conflated: any number of these before the next iteration count once. */
    fun nudge() {
        nudges.trySend(Unit)
    }

    /** Stamp the top of every sampling iteration; the floor is measured from it. */
    fun iterationStarted() {
        iterationStartedAtMs = nowMs()
    }

    /** The wait between iterations: a full beat, cut short by a nudge, but never below the floor. */
    suspend fun awaitNext() {
        val nudged = withTimeoutOrNull(beatMs) { nudges.receive() } != null
        if (!nudged) return
        val startedAt = iterationStartedAtMs ?: return
        val remaining = startedAt + beatMs - nowMs()
        if (remaining > 0L) delay(remaining)
    }
}

/**
 * The presentation after the card is rebound from one game to another. The frame rate is the one cell that
 * belongs to a game (the others are whole-device), so it is blanked to the placeholder dash until the new
 * game's first sample lands; the previous game's number must never read as the new game's.
 */
internal fun performanceOverlayReboundPresentation(
    presentation: PerformanceOverlayPresentation,
): PerformanceOverlayPresentation = when (presentation) {
    is PerformanceOverlayPresentation.Active ->
        presentation.copy(environment = presentation.environment.copy(foregroundFrameRate = null))
    PerformanceOverlayPresentation.Warming,
    PerformanceOverlayPresentation.Paused,
    -> presentation
}

/** What the card should be doing: drawn or not, and which game it is bound to (null when none, or hidden). */
internal data class PerformanceOverlayCardState(val wanted: Boolean, val target: String?) {
    companion object {
        val Hidden = PerformanceOverlayCardState(wanted = false, target = null)
    }
}

internal enum class PerformanceOverlayReconcileChange(val label: String) {
    ATTACH("attach"),
    SWITCH("switch"),
    DETACH("detach"),
}

/**
 * The signal the gate actually reads: a live renderer-owning foreground, proved on this beat, outranks the
 * debounced UsageStats tracker. Same rule the service applied inline before this was extracted.
 */
internal fun performanceOverlayGateSignal(
    rendererPackage: String?,
    foregroundSignal: PerformanceOverlayForegroundSignal,
): PerformanceOverlayForegroundSignal =
    if (rendererPackage != null) {
        PerformanceOverlayForegroundSignal.ForeignForeground(rendererPackage)
    } else {
        foregroundSignal
    }

/** The package a [PerformanceOverlayForegroundSignal] names as the foreground, or null when it names none. */
internal fun performanceOverlayForegroundPackageName(signal: PerformanceOverlayForegroundSignal): String? =
    (signal as? PerformanceOverlayForegroundSignal.ForeignForeground)?.packageName

/**
 * The game in front, if the foreground is one: a package the ledger dispatched, a package Mimir launched
 * earlier ([registeredGames]), the renderer-proven package when no session is active (the long-standing
 * fallback that lets an approved emulator in front show the card with no session at all), or GameNative
 * proven live. Anything else in front, and Mimir itself, is not a game.
 */
internal fun performanceOverlayGameInFront(
    dispatchedGamePackageName: String?,
    rendererPackage: String?,
    gateSignal: PerformanceOverlayForegroundSignal,
    registeredGames: Set<String>,
): String? {
    val foreground = performanceOverlayForegroundPackageName(gateSignal) ?: return null
    val isGame = foreground in registeredGames ||
        foreground == dispatchedGamePackageName ||
        (dispatchedGamePackageName == null && foreground == rendererPackage) ||
        (foreground == GAME_NATIVE_FRAME_RATE_PACKAGE && rendererPackage == GAME_NATIVE_FRAME_RATE_PACKAGE)
    return foreground.takeIf { isGame }
}

/**
 * The card's desired state for this instant. A pure function: the same inputs always give the same answer,
 * whatever came before, so re-applying it is a no-op.
 *
 * With an empty [registeredGames] this answers exactly what the gate answered before the registry existed
 * (`dispatched ?: renderer` fed to [performanceOverlayShouldPresentCard]); the registry only adds games to
 * the ones the foreground may be recognised as. [PerformanceOverlayForegroundSignal.UsageAccessMissing] and
 * [PerformanceOverlayForegroundSignal.AwaitingObservation] still trust dispatch truth alone, because the
 * foreground cannot be cross-checked then and that must never read as "not a game".
 */
internal fun performanceOverlayCardState(
    visibility: PerformanceOverlayVisibility,
    dispatchedGamePackageName: String?,
    rendererPackage: String?,
    gateSignal: PerformanceOverlayForegroundSignal,
    registeredGames: Set<String>,
): PerformanceOverlayCardState {
    val gameInFront = performanceOverlayGameInFront(
        dispatchedGamePackageName = dispatchedGamePackageName,
        rendererPackage = rendererPackage,
        gateSignal = gateSignal,
        registeredGames = registeredGames,
    )
    val effectiveGame = gameInFront ?: dispatchedGamePackageName ?: rendererPackage
    val wanted = performanceOverlayShouldPresentCard(
        visibility = visibility,
        dispatchedGamePackageName = effectiveGame,
        foregroundSignal = gateSignal,
        gameNativeForegroundProven = rendererPackage == GAME_NATIVE_FRAME_RATE_PACKAGE,
    )
    if (!wanted) return PerformanceOverlayCardState.Hidden
    val target = gameInFront
        ?: effectiveGame.takeIf { visibility == PerformanceOverlayVisibility.GAMES_ONLY }
    return PerformanceOverlayCardState(wanted = true, target = target)
}

/**
 * Holds the state the card was last reconciled to and says whether a new desired state is a change.
 * Idempotent by construction: the same state again returns null and logs nothing, so a one-second beat
 * costs no log line, no attach and no extra window.
 */
internal class PerformanceOverlayReconciler(private val log: (String) -> Unit) {
    private var applied = PerformanceOverlayCardState.Hidden

    @Synchronized
    fun reconcile(
        desired: PerformanceOverlayCardState,
        foregroundPackageName: String?,
        registeredCount: Int,
    ): PerformanceOverlayReconcileChange? {
        val previous = applied
        val change = when {
            !previous.wanted && desired.wanted -> PerformanceOverlayReconcileChange.ATTACH
            previous.wanted && !desired.wanted -> PerformanceOverlayReconcileChange.DETACH
            previous.wanted && desired.wanted && previous.target != desired.target ->
                PerformanceOverlayReconcileChange.SWITCH
            else -> null
        } ?: return null
        applied = desired
        log(
            "reconcile foreground=${foregroundPackageName ?: "none"} target=${desired.target ?: "none"} " +
                "registered=$registeredCount change=${change.label}",
        )
        return change
    }
}
