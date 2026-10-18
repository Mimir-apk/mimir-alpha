package com.gruning.mimir.rewrite

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.feature.library.launchChoiceFor
import com.gruning.mimir.rewrite.feature.library.LaunchChoice
import com.gruning.mimir.rewrite.feature.library.LaunchChoiceScope
import com.gruning.mimir.rewrite.feature.library.launchChoiceAvailable
import kotlinx.coroutines.flow.first
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardRestartKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardDescriptionKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollCommand
import com.gruning.mimir.rewrite.feature.library.HomeLaunchDescriptionScrollState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.OpenWithAutoKey
import com.gruning.mimir.rewrite.feature.library.OpenWithScope
import com.gruning.mimir.rewrite.feature.library.dispatchHomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.enterHomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.exitHomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.openWithFocusKey
import com.gruning.mimir.rewrite.feature.library.openWithOptionsAfterPick
import com.gruning.mimir.rewrite.feature.library.registerHomeLaunchCardActions
import com.gruning.mimir.rewrite.feature.library.unregisterHomeLaunchCardActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Frames the held card waits for its saved focus key to register after it draws again (the Library's return allows the same). */
private const val ResumeFocusFrames = 8

internal data class HomeLaunchCardSession(
    val row: HomeShelfRow,
    val game: GameRow?,
    /**
     * WHERE this card was opened from. Carried because the card's face now depends on it: policy
     * 2026-08-25 (OWED-1) puts the disc on a launch card opened from a Tome gamelist and the COVER on one
     * opened from the Home shelf, and until this field existed the card could not tell the two apart.
     *
     * [open] already receives the origin; retaining it lets the card choose its face.
     */
    val originScope: FocusScope = HomeShelfScope,
) {
    val emulated: Boolean get() = row.itemId is HomeShelfItemId.Emulated
}

/*
 * `launchCardShowsPhysicalMedia(originScope)` stood here and is DELETED (C1, 2026-08-25). It derived the
 * launch card's face from where the card was opened, which is exactly the unpredictability C1 replaces
 * with an explicit LAUNCH CARD state.
 *
 * [HomeLaunchCardSession.originScope] SURVIVES that deletion and must not be removed with it: it also
 * carries the focus-return contract -- where focus goes when the card closes -- which has nothing to do
 * with art.
 */
internal fun homeLaunchCardOriginIsCurrent(
    focusState: FocusState,
    originScope: FocusScope,
    originFocusKey: FocusKey,
): Boolean = focusState.scope == originScope && focusState.focused == originFocusKey

/**
 * App-owned transient session for the Home Launch Card. It is intentionally not saveable: process recovery
 * restores the Home context and exact tile, never a stale launch confirmation surface.
 */
@Stable
internal class HomeLaunchCardCoordinator(
    private val authority: FocusAuthority,
    private val launchController: LaunchController,
    private val coroutineScope: CoroutineScope,
    /** Read-only: the open card's group members, for the disc / version menu on Play. */
    private val repository: LibraryRepository? = null,
    /**
     * The last options loaded for each game this process has shown a card for (newest 32). A card seeds its options from here when it opens, so the
     * EMULATOR fact names the emulator at once instead of falling to "Automatic" (or the platform's default, for a game with an override) until the
     * load lands. The load still runs and replaces them. A parameter so that a test can start a card with its options.
     */
    private val openWithCache: HomeLaunchCardOptionsCache = HomeLaunchCardOptionsCache(),
) {
    var session by mutableStateOf<HomeLaunchCardSession?>(null)
        private set

    var openWithOptions by mutableStateOf<OpenWithOptions?>(null)
        private set

    /** Bumped by every load and by an optimistic pick: a load that started before either is stale when it lands and is dropped. */
    private var openWithLoadSerial = 0

    var openWithOpen by mutableStateOf(false)
        private set

    var openWithLoading by mutableStateOf(false)
        private set

    var openWithError by mutableStateOf<String?>(null)
        private set

    /**
     * The open card game's physical members, for the disc / version menu on Play.
     *
     *  The Library detail
     * card has offered the menu since it was built; Home's never did, because Home never observed a
     * game's members — only the companion payload path did. Same menu, same shell, same two seams
     * (a DISC crosses as a number, a VERSION as an EntryKey).
     */
    var members by mutableStateOf<List<GameMemberRow>>(emptyList())
        private set

    var launchChoiceOpen by mutableStateOf(false)
        private set

    var closing by mutableStateOf(false)
        private set

    var dissolveCancellationEpoch by mutableStateOf(0L)
        private set

    var descriptionScrollState by mutableStateOf(HomeLaunchDescriptionScrollState())
        private set

    var descriptionScrollCommand by mutableStateOf(HomeLaunchDescriptionScrollCommand())
        private set

    var launchState by mutableStateOf<GameLaunchUiState>(GameLaunchUiState.Idle)
        private set

    /**
     * True while START has sent the player from this card to Settings and the card's session is held for their return (START opens Settings from the card on
     * both doors, and B returns to the card). The card is neither drawn nor offered input while it is held, so Settings owns both; the session, its scopes and the key it
     * was left on are untouched, and [resumeAfterSettings] brings it back on that key. Only a card opened from the Home shelf is held: a Tome's or the compact browser's
     * has an origin Home does not rebuild on its own, and START stays swallowed there.
     */
    var suspendedForSettings by mutableStateOf(false)
        private set

    private var suspendedFocus: FocusKey? = null
    private var suspendedScroll: HomeLaunchDescriptionScrollState? = null
    private var openWithRequested = false
    private var closeJob: Job? = null

    fun open(
        row: HomeShelfRow,
        game: GameRow?,
        originScope: FocusScope = HomeShelfScope,
        originFocusKey: FocusKey = row.focusKey,
    ): Boolean {
        if (row.itemId is HomeShelfItemId.AndroidApp) return false
        if (!homeLaunchCardOriginIsCurrent(authority.state.value, originScope, originFocusKey)) {
            return false
        }
        if (row.itemId is HomeShelfItemId.Emulated && game == null) return false
        if (session != null) finishClose()
        // A new card is never a held one: the hold belongs to the session it was taken for.
        suspendedForSettings = false
        suspendedFocus = null
        suspendedScroll = null
        val next = HomeLaunchCardSession(row, game, originScope)
        session = next
        openWithOptions = game?.let { openWithCache[it.gameId] }
        openWithOpen = false
        openWithLoading = false
        openWithError = null
        openWithRequested = false
        members = emptyList()
        launchChoiceOpen = false
        closing = false
        launchState = GameLaunchUiState.Idle
        descriptionScrollState = HomeLaunchDescriptionScrollState()
        descriptionScrollCommand = HomeLaunchDescriptionScrollCommand()
        loadMembers(next)
        enterHomeLaunchCard(authority, emulated = next.emulated)
        if (next.emulated) loadOpenWith(next, openWhenReady = false)
        return true
    }

    fun close() {
        // A held card is not on screen and owns no scope: closing it is dropping it, at once, with its hold.
        if (suspendedForSettings) {
            dropSuspended()
            return
        }
        releaseHomeLaunchCardScopes(authority)
        launchChoiceOpen = false
        openWithOpen = false
        openWithRequested = false
        if (session == null || closing) return
        closing = true
        // Logical ownership returns to Home immediately. The session stays alive only long enough for
        // HomeLaunchCardOverlayHost to retain and dissolve its outgoing pixels; it must not hold focus or
        // swallow carousel input for the duration of that animation.
        restoreHomeCarouselForLaunchCardClose(authority)
        closeJob?.cancel()
        closeJob = coroutineScope.launch {
            // The animation clock, not the wall clock: the hold is as long as the dissolve it covers at any animator scale (audit RC-49).
            mimirHold(HomeLaunchCardReturnDissolveMs)
            closeJob = null
            finishClose()
        }
    }

    /** The EMULATOR fact's name: the emulator the launch would start, else [fallback] exactly as before (round 5b). */
    fun currentOpenWithLabel(fallback: String? = null, overridePending: Boolean = false): String? =
        homeLaunchCardEmulatorLabel(openWithOptions, fallback, overridePending)

    fun setLaunchState(state: GameLaunchUiState, gameId: GameId) {
        if (session?.game?.gameId == gameId) launchState = state
    }

    fun dispatch(
        action: ControllerAction,
        onPlay: (HomeShelfRow) -> Unit,
        onPlayEmulated: (HomeShelfRow, GameRow) -> Unit,
        onEdit: (HomeShelfRow) -> Unit,
        onEditTitle: (GameRow) -> Unit,
        onAchievements: (GameRow) -> Unit,
    ): Boolean {
        val active = session ?: return false
        if (closing) {
            // R6-P0-02 G1 (house rule: log every silent decline).
            Log.i("MimirHomeShelf", "play declined guard=closing item=${active.row.itemId}")
            return false
        }
        return dispatchHomeLaunchCard(
            authority = authority,
            emulated = active.emulated,
            action = action,
            onPlay = {
                val row = active.row
                val game = active.game
                if (active.emulated && game != null) {
                    if (launchState != GameLaunchUiState.Launching &&
                        launchState != GameLaunchUiState.IntentSent
                    ) {
                        onPlayEmulated(row, game)
                    } else {
                        // R6-P0-02 G2 (house rule: log every silent decline).
                        Log.i("MimirHomeShelf", "play declined guard=launchState item=${row.itemId}")
                    }
                } else {
                    close()
                    onPlay(row)
                }
            },
            onEdit = { onEdit(active.row) },
            // Gated to emulated sessions only (matches dispatchHomeLaunchCard's own `if (emulated)` guard
            // on this key) — open() rejects Emulated rows with a null game (line 106 above), so `game` is
            // never null on this path.
            onEditTitle = { active.game?.let(onEditTitle) },
            onAchievements = { active.game?.let(onAchievements) },
            onOpenWith = { requestOpenWith() },
            onClose = ::close,
            descriptionScrollState = descriptionScrollState,
            onDescriptionScroll = ::requestDescriptionScroll,
        )
    }

    /** Holds the open card for Settings (see [suspendedForSettings]). False, and nothing changed, when this card is not one that can be held. */
    fun suspendForSettings(): Boolean {
        val active = session ?: return false
        if (closing || suspendedForSettings || active.originScope != HomeShelfScope) return false
        // A child of the card (Open with, the disc / version menu) owns the controller: the card is not held out from under it.
        if (launchChoiceOpen || openWithOpen) return false
        if (authority.state.value.scope == OpenWithScope || authority.state.value.scope == LaunchChoiceScope) return false
        suspendedFocus = if (authority.state.value.scope == HomeLaunchCardScope) {
            authority.state.value.focused
        } else {
            authority.rememberedFocusIn(HomeLaunchCardScope)
        }
        suspendedScroll = descriptionScrollState
        suspendedForSettings = true
        // Its scope leaves the stack now, with its keys, so Settings (and the Home beneath it) are never above a card scope, and each round trip re-enters exactly one.
        authority.discardScope(HomeLaunchCardScope)
        unregisterHomeLaunchCardActions(authority)
        return true
    }

    /**
     * Settings gave the screen back by B: the card draws again at once and focus goes back to the key it was left on. Settings pops its own scopes as it leaves, and Home's
     * mount may have touched the stack under it, so the card's scope is re-entered cleanly rather than trusted; the saved key (Restart, say) is asked for once the card has
     * registered it, and the card keeps Play when it is not there (the game came back while Settings had the screen).
     */
    fun resumeAfterSettings() {
        if (!suspendedForSettings) return
        val active = session ?: return dropSuspended()
        val saved = suspendedFocus
        val scroll = suspendedScroll
        suspendedForSettings = false
        suspendedFocus = null
        suspendedScroll = null
        authority.discardScope(HomeLaunchCardScope)
        enterHomeLaunchCard(authority, emulated = active.emulated)
        // The card remounts with its description at the top, and the last paging command still stands in [descriptionScrollCommand] (the remounted description has settled
        // nothing yet, so it would run it again, a page further on). A restore command, one sequence on, replaces it and puts the text back where the player left it.
        if (scroll != null) {
            descriptionScrollCommand = HomeLaunchDescriptionScrollCommand(
                sequence = descriptionScrollCommand.sequence + 1,
                restoreOffset = scroll.offset,
                expectedRevision = scroll.revision,
            )
        }
        if (saved == null || saved == HomeLaunchCardPlayKey) return
        // Restart is there only while its game is parked: if it is not, Play at once. Any other key waits for the card to register it.
        val restartParked = saved == HomeLaunchCardRestartKey && active.game?.gameId.let { it != null && HomeLaunchCardResume.parkedGameId() == it }
        if (saved == HomeLaunchCardRestartKey && !restartParked) return
        // On the resume frame (audit H-04d): the keys the entry registered are there now, and a parked game's Restart is registered here, as the card would a frame later, so the
        // saved key lands in this command and Play is never the key the card is first drawn on. The card's own registration replaces the nominal bounds.
        if (restartParked) registerHomeLaunchCardActions(authority, active.emulated, hasExtraAction = true)
        authority.rememberFocusForScope(HomeLaunchCardScope, saved)
        authority.requestFocus(saved)
        if (authority.state.value.focused == saved) return
        // A key only the remounted card registers (the description, say) is asked for once it has.
        coroutineScope.launch {
            repeat(ResumeFocusFrames) {
                withFrameNanos { }
                if (session !== active || authority.state.value.scope != HomeLaunchCardScope) return@launch
                authority.requestFocus(saved)
                if (authority.state.value.focused == saved) return@launch
            }
        }
    }

    /** The player left Settings some way other than B (an M-button, a trigger): the held card is over, exactly as if it had been closed. */
    fun dropSuspended() {
        if (!suspendedForSettings) return
        finishClose()
    }

    /** A new command owns the live Home scope, so stale outgoing card pixels yield immediately. */
    fun cancelOutgoingDissolveOnInteraction() {
        if (!closing) return
        dissolveCancellationEpoch = if (dissolveCancellationEpoch == Long.MAX_VALUE) {
            0L
        } else {
            dissolveCancellationEpoch + 1L
        }
        finishClose()
    }

    fun updateDescriptionScrollState(state: HomeLaunchDescriptionScrollState) {
        descriptionScrollState = state
        if (!state.scrollable && authority.state.value.focused == HomeLaunchCardDescriptionKey) {
            authority.requestFocus(com.gruning.mimir.rewrite.feature.library.HomeLaunchCardPlayKey)
        }
    }

    private fun requestDescriptionScroll(direction: Int) {
        descriptionScrollCommand = HomeLaunchDescriptionScrollCommand(
            sequence = descriptionScrollCommand.sequence + 1,
            direction = direction.coerceIn(-1, 1),
        )
    }

    fun dispatchOpenWith(action: ControllerAction): Boolean {
        if (!openWithOpen || authority.state.value.scope != OpenWithScope) return false
        return when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                authority.moveFocus(action)
                true
            }
            ControllerAction.CONFIRM -> {
                val options = openWithOptions
                when (val focused = authority.state.value.focused) {
                    OpenWithAutoKey -> chooseAutomatic()
                    else -> options?.candidates
                        ?.firstOrNull { openWithFocusKey(it.emulatorId.value) == focused && it.selectable }
                        ?.let(::choose)
                }
                true
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK, ControllerAction.NAV_LEFT -> {
                dismissOpenWith()
                true
            }
            else -> true
        }
    }

    fun dismissOpenWith() {
        openWithRequested = false
        openWithOpen = false
        if (authority.state.value.scope == OpenWithScope) authority.popScope()
    }

    /**
     * Load the open game's members so Play can offer the disc / version menu.
     *
     * Read-only and best-effort: a repository that is not wired (previews, tests) simply leaves
     * [members] empty, and an empty member list means no menu — Play behaves exactly as it always has.
     * `first()` rather than a subscription because a card is a transient surface and its group does not
     * change underneath it; the Library side collects because its card is long-lived.
     */
    private fun loadMembers(active: HomeLaunchCardSession) {
        val repo = repository ?: return
        val game = active.game ?: return
        coroutineScope.launch {
            val loaded = runCatching { repo.observeGameMembers(game.gameId).first() }.getOrNull().orEmpty()
            // Guard against a card that closed or changed game while the read was in flight.
            if (session?.game?.gameId == game.gameId) members = loaded
        }
    }

    /**
     * True when the open game offers a choice, in which case Play must ask rather than guess.
     *
     * The emptiness of the shared [launchChoiceItems] IS the test — the same law the Library side uses,
     * so the two surfaces cannot disagree about whether a menu exists.
     */
    fun hasLaunchChoice(): Boolean = launchChoiceAvailable(members)

    fun openLaunchChoice(): Boolean {
        if (session?.emulated != true || !hasLaunchChoice()) return false
        launchChoiceOpen = true
        authority.pushScope(LaunchChoiceScope)
        return true
    }

    /**
     * Set by the app layer, which is where the Home launch plumbing lives (return-target arming and the
     * Tweaker pre/post hooks). The card decides WHETHER to ask and closes the menu; it does not launch.
     */
    var onLaunchChoiceCommitted: ((LaunchChoice) -> Unit)? = null

    fun commitLaunchChoice(choice: LaunchChoice) {
        if (!launchChoiceOpen) return
        if (launchState == GameLaunchUiState.Launching || launchState == GameLaunchUiState.IntentSent) return
        dismissLaunchChoice()
        onLaunchChoiceCommitted?.invoke(choice)
    }

    /** Controller CONFIRM inside the menu — resolves the focused row to a choice and commits it. */
    fun commitFocusedLaunchChoice(): Boolean {
        val choice = launchChoiceFor(authority.state.value.focused) ?: return false
        commitLaunchChoice(choice)
        return true
    }

    fun dismissLaunchChoice() {
        if (!launchChoiceOpen) return
        launchChoiceOpen = false
        if (authority.state.value.scope == LaunchChoiceScope) authority.popScope()
    }

    fun choose(candidate: OpenWithCandidate) {
        val game = session?.game ?: return
        dismissOpenWith()
        // The row names the pick at once; the reload after the write replaces it with the truth. A load that started before the pick is dropped.
        openWithOptions?.let { current ->
            openWithLoadSerial++
            openWithOptions = openWithOptionsAfterPick(current, candidate).also { openWithCache[game.gameId] = it }
        }
        coroutineScope.launch {
            launchController.setEmulatorOverride(game.gameId, candidate.emulatorId)
            session?.takeIf { it.game?.gameId == game.gameId }?.let { loadOpenWith(it, false) }
        }
    }

    fun chooseAutomatic() {
        val game = session?.game ?: return
        dismissOpenWith()
        coroutineScope.launch {
            launchController.clearEmulatorOverride(game.gameId)
            session?.takeIf { it.game?.gameId == game.gameId }?.let { loadOpenWith(it, false) }
        }
    }

    private fun requestOpenWith() {
        val active = session?.takeIf { it.emulated } ?: return
        val options = openWithOptions
        if (options == null) {
            openWithRequested = true
            if (!openWithLoading) loadOpenWith(active, openWhenReady = true)
        } else {
            openOpenWithScope(options)
        }
    }

    private fun loadOpenWith(active: HomeLaunchCardSession, openWhenReady: Boolean) {
        val game = active.game ?: return
        openWithLoading = true
        openWithError = null
        val serial = ++openWithLoadSerial
        coroutineScope.launch {
            val loaded = try {
                launchController.openWithOptions(game.gameId, game.platformId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (session?.game?.gameId != game.gameId) return@launch
            // A newer load (or a pick) owns the options now; this one started before it and says what was true before it.
            if (serial != openWithLoadSerial) return@launch
            openWithLoading = false
            // A failed reload keeps the options the card already has (never writes null over them).
            openWithOptions = reloadedOpenWithOptions(openWithOptions, loaded)
            if (loaded != null) {
                openWithCache[game.gameId] = loaded
                if (openWhenReady || openWithRequested) {
                    openWithRequested = false
                    openOpenWithScope(loaded)
                }
            } else if (openWithOptions == null) {
                openWithRequested = false
                openWithError = "Couldn’t load emulator choices. Try again."
            }
        }
    }

    private fun finishClose() {
        closeJob?.cancel()
        closeJob = null
        releaseHomeLaunchCardScopes(authority)
        // The card's scope leaves the stack wherever it sits: on top (the ordinary close), or buried under a Settings visit that began while the card was up. Its keys go with it.
        authority.discardScope(HomeLaunchCardScope)
        unregisterHomeLaunchCardActions(authority)
        suspendedForSettings = false
        suspendedFocus = null
        suspendedScroll = null
        session = null
        // Cleared here as well as in close(): a card that came back believing its menu was still open
        // would suppress the next openLaunchChoice().
        launchChoiceOpen = false
        openWithOptions = null
        openWithOpen = false
        openWithLoading = false
        openWithError = null
        openWithRequested = false
        closing = false
        launchState = GameLaunchUiState.Idle
        descriptionScrollState = HomeLaunchDescriptionScrollState()
        descriptionScrollCommand = HomeLaunchDescriptionScrollCommand()
    }

    private fun openOpenWithScope(options: OpenWithOptions) {
        if (authority.state.value.scope != HomeLaunchCardScope || session == null) return
        val target = options.candidates.firstOrNull { it.isCurrentOverride }
            ?.let { openWithFocusKey(it.emulatorId.value) }
            ?: OpenWithAutoKey
        authority.rememberFocusForScope(OpenWithScope, target)
        openWithOpen = true
        authority.pushScope(OpenWithScope)
    }
}

/**
 * Whether Home's launch card is on the overlay stack: it has a session that is not closing and not held for Settings, and the destination is one the card is drawn over (Home, or Browse, which
 * the deleted compact browser once opened it over and which stays allowed). Over any other destination (Settings, say, reached by a path that did not hold the card) it must not be: a card on top of the stack
 * answers every press, fails its own scope check, and the player is left with a pad that does nothing, B included.
 */
internal fun homeLaunchCardOverlayOpen(
    hasSession: Boolean,
    closing: Boolean,
    held: Boolean,
    destination: MimirDestination,
): Boolean = hasSession && !closing && !held &&
    (destination == MimirDestination.HOME || destination == MimirDestination.BROWSE)

/**
 * Holds Home's launch card for a Settings visit the player is about to make from over it (START, or the achievement card's NEEDS SETUP) and arms the return, when the card is
 * one that can be held. False, nothing held, when it is not (no card, a child of it open, a card from a Tome or the compact browser): the visit then goes ahead without it,
 * and [settleForDestination] closes the card rather than leave it over Settings.
 */
internal fun HomeLaunchCardCoordinator.holdForSettingsVisit(destination: MimirDestination, arm: () -> Unit): Boolean {
    if (destination != MimirDestination.HOME || !suspendForSettings()) return false
    arm()
    return true
}

internal enum class HomeCardNavigationOutcome { UNCHANGED, STILL_HELD, RESUMED, DROPPED, CLOSED }

/**
 * What the app does with Home's launch card when the destination changes. A held card stays held while Settings is up, comes back when B returns to Home ([returnRequested]),
 * and is dropped when the player leaves some other way. A card that was NOT held and is now over a destination it is not drawn over is closed.
 */
internal fun HomeLaunchCardCoordinator.settleForDestination(
    destination: MimirDestination,
    returnRequested: Boolean,
): HomeCardNavigationOutcome {
    if (session == null) return HomeCardNavigationOutcome.UNCHANGED
    if (suspendedForSettings) {
        return when {
            destination == MimirDestination.SETTINGS -> HomeCardNavigationOutcome.STILL_HELD
            destination == MimirDestination.HOME && returnRequested -> {
                resumeAfterSettings()
                HomeCardNavigationOutcome.RESUMED
            }
            else -> {
                dropSuspended()
                HomeCardNavigationOutcome.DROPPED
            }
        }
    }
    if (!closing && destination != MimirDestination.HOME && destination != MimirDestination.BROWSE) {
        close()
        return HomeCardNavigationOutcome.CLOSED
    }
    return HomeCardNavigationOutcome.UNCHANGED
}

/**
 * Release every scope the launch card can own ABOVE its own, wherever each one currently sits.
 *
 * The card stacks either [OpenWithScope] or [LaunchChoiceScope] over [HomeLaunchCardScope]. Both close
 * paths used to release only Open With, with a guarded pop, and never mentioned the launch-choice menu —
 * so a close arriving while the disc/version menu was up stranded BOTH: the menu because nothing
 * released it, and the card scope beneath because `exitHomeLaunchCard`'s guard then saw the orphan on
 * top and declined. That needs no user dismissal to reach: `MimirAppCoordinator` closes the card when
 * the shelf row behind it disappears (`:507`, `:2195`), which a rescan or a hide can do while the menu
 * is open.
 *
 * Total on purpose. `discardScope` removes a buried scope without emitting, so the order of these two
 * lines cannot matter and neither can whatever the player left on top.
 */
internal fun releaseHomeLaunchCardScopes(authority: FocusAuthority) {
    authority.discardScope(LaunchChoiceScope)
    authority.discardScope(OpenWithScope)
}

/** Frame-zero half of launch-card close; the retained Compose layer owns the later pixel dissolve. */
internal fun restoreHomeCarouselForLaunchCardClose(authority: FocusAuthority) {
    if (authority.state.value.scope == OpenWithScope) authority.popScope()
    if (authority.state.value.scope == HomeLaunchCardScope) exitHomeLaunchCard(authority)
}

@Composable
internal fun rememberHomeLaunchCardCoordinator(
    authority: FocusAuthority,
    launchController: LaunchController,
    repository: LibraryRepository? = null,
): HomeLaunchCardCoordinator {
    val scope = rememberCoroutineScope()
    return remember(authority, launchController, scope, repository) {
        HomeLaunchCardCoordinator(authority, launchController, scope, repository)
    }
}
