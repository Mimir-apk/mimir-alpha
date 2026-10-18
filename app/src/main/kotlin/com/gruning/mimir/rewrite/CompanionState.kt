package com.gruning.mimir.rewrite

import android.util.Log
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single process-scoped bridge for what the SECONDARY display shows — the primary (Compose UI,
 * [MimirApp]) writes a [CompanionPayload] snapshot here, and the [CompanionDisplayController]'s mounted
 * [MimirCompanionPresentation] reads it. Decoupled through a [StateFlow] so the two windows (primary
 * Activity + secondary Presentation) never hold a direct reference .
 *
 * **The direction is one-way and that part is still true:** this state only ever flows primary →
 * companion; the companion emits COMMANDS separately ([CompanionCommandState]), never payloads.
 *
 * This paragraph used to open *"The companion is DISPLAY-ONLY "*. **Retired 2026-08-30 by policy D1-R3**, which struck the
 * same claim from `CompanionPayload`'s KDoc — it was false before it was stale, since `HOME` already
 * ships drag-to-reorder, quick-launch, folder open, create-Tome, rename and discard, and a platform
 * tile tap already reaches the primary display. Missed in R3's own commit and caught here; a
 * one-way payload channel is not the same claim as a screen that never acts.
 */
object CompanionState {
    private val _payload = MutableStateFlow(CompanionPayload.idle())

    internal data class RenderPayload(
        val revision: Long,
        val payload: CompanionPayload,
    )

    private val _renderPayload = MutableStateFlow(RenderPayload(0L, _payload.value))

    /** The current companion read-model. The mounted Presentation collects this and re-renders live. */
    val payload: StateFlow<CompanionPayload> = _payload.asStateFlow()

    /** Atomic payload + revision used by compositor acknowledgements; ordinary readers keep [payload]. */
    internal val renderPayload: StateFlow<RenderPayload> = _renderPayload.asStateFlow()

    /**
     * TRUE while a DS/3DS game owns the primary display. This is a lifecycle marker retained for compatibility
     * with existing cleanup; companion action routing uses the live runtime/session authorities below rather
     * than treating this marker as an ownership or input guard.
     */
    @Volatile
    var dualScreenGameActive: Boolean = false

    // ── The reclaim hold, and why it must expire itself ──────────────────────────────────────────────
    //  the hold is PRODUCED here but used to be CLEARED only by the
    // payload-push LaunchedEffect inside MainActivity's composition. While a game covers the primary,
    // that Activity drops below STARTED and its Recomposer stops running frames — the push never fires
    // and screen 2 renders "Reconnecting…" forever. The hold now carries its own deadline, honoured by
    // this object, which the always-visible Presentation reads directly.

    /** Fired when a hold hit its deadline with no fresh push — the CR-1 backstop actually doing work. */
    @Volatile
    var onHoldExpired: (() -> Unit)? = null

    /** Overridable in unit tests; production runs the hold's deadline on the main thread. */
    internal var holdScopeOverride: CoroutineScope? = null
    internal var holdTimeoutMs: Long = 3_000L

    /** Reclaim expiry is process work; contain an uncaught child failure at this boundary. */
    internal val mainScopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirCompanionState", "background reclaim work failed in $context", throwable)
    }

    private val mainScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + mainScopeFailureHandler)
    }
    private val holdScope: CoroutineScope get() = holdScopeOverride ?: mainScope

    private var holdJob: Job? = null

    /** The last payload that wasn't a loading hold — what the companion falls back to if a push never lands. */
    private var lastStable: CompanionPayload? = null

    /** The shelf/detail payload behind a RUNNING_APP card, restored immediately when the app leaves Main. */
    private var lastNonRunningStable: CompanionPayload? = null

    /** The last complete HOME shelf, retained while an app projection owns the active payload. */
    @Volatile
    private var lastHomePayload: CompanionPayload? = null

    /**
     *  every NOW_PLAYING payload the adapter produces is stamped isLoading (mode is derived
     * from launch state), so the stable-snapshot slots above can never retain it — captured here REGARDLESS
     * of isLoading so the away-running hero card can be re-derived when the game returns to foreground.
     */
    private var lastNowPlaying: CompanionPayload? = null

    /** The primary updates what the second screen shows (now-playing hero, home shelf, or idle). */
    fun set(payload: CompanionPayload) {
        // A real push always wins: cancel any pending expiry so the backstop can't revert fresh state.
        holdJob?.cancel()
        holdJob = null
        if (payload.mode == CompanionMode.HOME && payload.homeLanes.any { it.tiles.isNotEmpty() }) {
            lastHomePayload = payload
        }
        if (payload.mode == CompanionMode.NOW_PLAYING) lastNowPlaying = payload
        if (!payload.isLoading) {
            lastStable = payload
            if (payload.mode != CompanionMode.RUNNING_APP) lastNonRunningStable = payload
        }
        publish(payload)
    }

    /** The last non-empty HOME payload for effective companion projections. */
    internal fun retainedHomePayload(): CompanionPayload? = lastHomePayload

    /** True only while the retained hero belongs to this exact game session. */
    internal fun hasRetainedNowPlaying(gameId: GameId): Boolean = lastNowPlaying?.gameId == gameId

    internal fun lastNowPlayingGameId(): GameId? = lastNowPlaying?.gameId

    /** The retained card payload, scoped to the exact live game id for Activity detail gating. */
    internal fun retainedNowPlayingPayload(gameId: GameId): CompanionPayload? =
        lastNowPlaying?.takeIf { it.gameId == gameId }

    /**
     * Round-5 triage 4a: the payload instance [holdForReclaim] left on screen. Recorded as rendered, not as built:
     * [publish] keeps an equal payload's existing instance, and [holdForColdStart] publishes one of exactly this value.
     */
    @Volatile
    private var reclaimHoldPayload: CompanionPayload? = null

    /** Round-5 triage 4a: true only for the payload [holdForReclaim] left on screen; its cold-start twin is not it. */
    internal fun isReclaimHold(payload: CompanionPayload): Boolean = payload === reclaimHoldPayload

    /**
     * Republish the retained NOW_PLAYING as a STABLE card (G-2): called by the process runtime when the
     * armed game-owner package is observed foreground again (and on its first foreground after dispatch).
     * Publishing through [set] also seeds [lastStable]/[lastNonRunningStable], so the RUNNING_APP
     * clear path ([clearRunningApp]) restores the hero card instead of the stale pre-launch detail.
     * The in-flight "Launching…" status is dropped — the away-running card makes no stale claim.
     */
    fun republishRetainedNowPlaying(): Boolean {
        val retained = lastNowPlaying ?: return false
        val current = _payload.value
        val alreadyShowing = !current.isLoading &&
            current.mode == CompanionMode.NOW_PLAYING &&
            current.gameId == retained.gameId
        if (alreadyShowing) return false
        set(retained.copy(isLoading = false, statusLabel = null, statusReady = false))
        return true
    }

    /**
     * Round 5b: land a launch seed's launcher label that resolved AFTER the seed published. An Open-With override
     * needs a launch-controller read, and a seed may not wait on one ([CompanionSeedLauncherLine]).
     *
     * The retained card gets it first, because that is the card [republishRetainedNowPlaying] brings back once the
     * emulator is in front, and by then the push that published the seed may have moved on (a Home launch
     * publishes HOME as soon as it settles). The stable snapshots get it when they hold that card, so a running-app
     * clear or an expired hold restores it with the line. The card on screen gets it when it is that game's
     * NOW_PLAYING.
     *
     * Refused (false) unless the retained card is [gameId]'s. Once the session is over ([clearRetainedNowPlaying]) or
     * another game holds the card, a late read can neither bring a card back nor describe the wrong game.
     */
    internal fun stampRetainedNowPlayingLauncherLabel(gameId: GameId, launcherLabel: String): Boolean {
        val retained = lastNowPlaying?.takeIf { it.gameId == gameId } ?: return false
        lastNowPlaying = retained.copy(launcherLabel = launcherLabel)
        lastStable = lastStable?.withNowPlayingLauncherLabel(gameId, launcherLabel)
        lastNonRunningStable = lastNonRunningStable?.withNowPlayingLauncherLabel(gameId, launcherLabel)
        val current = _payload.value
        if (current.mode == CompanionMode.NOW_PLAYING && current.gameId == gameId) {
            set(current.copy(launcherLabel = launcherLabel))
        }
        return true
    }

    private fun CompanionPayload.withNowPlayingLauncherLabel(gameId: GameId, launcherLabel: String): CompanionPayload =
        if (mode == CompanionMode.NOW_PLAYING && this.gameId == gameId) copy(launcherLabel = launcherLabel) else this

    /** The game session is over (Mimir foreground / return settled): the retained hero card is dead truth. */
    fun clearRetainedNowPlaying() {
        lastNowPlaying = null
        // E1, 2026-08-31: and so is the scene's pending game owner. The callers of this function are
        // exactly the "no game is out there" facts, so the flag is bounded by the same truth the
        // retained card is rather than by a second rule that has to be kept in step with it.
        CompanionSceneState.clearPendingGameOwner()
    }

    /** Publish before the primary Activity stops; its Compose payload producer may sleep behind the app. */
    fun showRunningApp(app: CompanionRunningApp) {
        val current = _payload.value
        set(companionRunningAppPayload(app, current.themeId, current.themeSigilRef))
    }

    /** Restore the last non-running read model immediately; the resumed Compose producer then converges it. */
    fun clearRunningApp() {
        holdJob?.cancel()
        holdJob = null
        val current = _payload.value
        val restored = lastNonRunningStable
            ?: CompanionPayload.idle(themeSigilRef = current.themeSigilRef, themeId = current.themeId)
        lastStable = restored
        publish(restored)
    }

    private val _reclaimEpoch = MutableStateFlow(0)

    /**
     * Monotonic reclaim-settle counter (HP-3): bumped once per reclaim settle so the payload push re-keys
     * and rebuilds even when the payload VALUE is unchanged. Still useful on the happy path — but it can
     * never be the ONLY way out of a hold (CR-1): it runs in a composition that may be asleep.
     */
    val reclaimEpoch: StateFlow<Int> = _reclaimEpoch.asStateFlow()

    /** Called by the display controller at every reclaim settle (direct, host-landing, or timeout). */
    fun noteReclaimSettled() {
        _reclaimEpoch.value += 1
    }

    /**
     * Park a loading hold for the reclaim window (F9 — "loading wins on return"): the re-attaching
     * Presentation renders "Reconnecting…" instead of a stale pre-launch snapshot. The hold EXPIRES on its
     * own after [holdTimeoutMs] — restoring the last stable payload — so it can never outlive its flight
     * even if the primary's composition never wakes to push a fresh one (CR-1).
     */
    fun holdForReclaim() {
        holdJob?.cancel()
        val loading = CompanionPayload(
            mode = CompanionMode.IDLE,
            themeId = _payload.value.themeId,
            isLoading = true,
        )
        publish(loading)
        reclaimHoldPayload = _renderPayload.value.payload
        holdJob = holdScope.launch {
            delay(holdTimeoutMs)
            if (_payload.value.isLoading) {
                // Observable without dragging android.util.Log into this pure object (it is not stubbed on
                // the JVM, and this class is unit-tested): the host installs a listener.
                onHoldExpired?.invoke()
                val restored = lastStable ?: CompanionPayload.idle(themeId = _payload.value.themeId)
                publish(restored)
            }
        }
    }

    /**
     * Cold-start loading cover: the COLD-START gate for screen 2. The companion window
     * mounts in onCreate — entirely OUTSIDE the primary's welcome mask — and used to hydrate in visible
     * stages (adversarial review comp-cold-01/02). This parks the same themed loading cover the reclaim path
     * uses until the first real seeded payload publishes over it; per the companion-hold posture it SELF-
     * EXPIRES (longer than the reclaim hold — the cold window is bounded by the welcome cap, not a reclaim
     * flight), so it can never wedge screen 2 even if the primary's composition never pushes.
     */
    fun holdForColdStart(timeoutMs: Long = 5_000L) {
        holdJob?.cancel()
        publish(
            CompanionPayload(
                mode = CompanionMode.IDLE,
                themeId = _payload.value.themeId,
                isLoading = true,
            ),
        )
        holdJob = holdScope.launch {
            delay(timeoutMs)
            if (_payload.value.isLoading) {
                onHoldExpired?.invoke()
                publish(lastStable ?: CompanionPayload.idle(themeId = _payload.value.themeId))
            }
        }
    }

    /**
     * Round 5c: the theme a fresh process's payload carries before Main's first push.
     *
     * `_payload` starts as [CompanionPayload.idle], whose theme key is `""`, and every reader before Main's first push
     * copied that key and resolved Default: the display-4 floor's cover, [holdForColdStart] and [holdForReclaim], the
     * running-app projections, and `CompanionReturnActivity`'s one read for the restore guard's ground. A fresh process
     * follows a death, an install or a boot, and the platform restarts the floor before any MainActivity exists; in one
     * recovery log the Presentation showed about 2 s after the floor claimed display 4.
     *
     * `seedCompanionThemeAtProcessStart` (MimirApplication.kt) calls this from `Application.onCreate` with [ThemeStore]'s
     * key, so the payload carries the store's theme, not a second copy of it. It fills in only a payload that has no
     * theme and keeps the rest of that payload, so Main's pushes always win: a push after the seed replaces it, and a
     * seed after a push that carries a theme changes nothing. Synchronized with [publish], so no push lands between the
     * check and the publish. Returns whether it published.
     */
    @Synchronized
    fun seedTheme(themeStorageKey: String): Boolean {
        val current = _payload.value
        if (current.themeId.isNotEmpty() || themeStorageKey.isEmpty()) return false
        publish(current.copy(themeId = themeStorageKey))
        return true
    }

    /**
     * **D1-R9, 2026-08-30: a revision is only spent when the payload actually changed.**
     *
     * `_payload` is a [MutableStateFlow] and has always conflated an equal value, so an unchanged
     * payload never reached ordinary readers. `_renderPayload` could not: every call minted a new
     * revision, so its value differed even when its payload did not, and it emitted every time.
     *
     * That was not a cosmetic asymmetry. The revision feeds `CompanionPresentationFrameToken`
     * (`MimirCompanionPresentation.kt:257-262`), the token keys a `LaunchedEffect`, and that effect
     * calls `armFrameCommit` — so **every publish re-armed the second screen's frame-commit handshake
     * and drew a frame, whether or not a single pixel differed.** `CompanionDisplayController`'s
     * `handleFrameCommitted` then dropped the result when no attempt was pending.
     *
     * **Measured on the Thor test unit before this fix**: 40 focus moves on screen 1 produced 80
     * frames on display 4 with its pixels byte-identical, against a 12-second idle control and a
     * 50-press unmapped-key control that each produced zero.
     *
     * **Why suppressing the bump cannot strand a pending attempt.** An attempt is armed to confirm a
     * frame for NEW content. When the payload is identical there is no new content to confirm, and the
     * token's other three inputs — `operationId`, `sceneRevision`, `commitEpoch` — still change on
     * their own, so a genuine re-render still re-arms. [CompanionSceneState.onPayload] is still called
     * first, unconditionally, because a scene transition is a separate signal that may legitimately
     * move `scene.revision` even when the payload does not change.
     */
    @Synchronized
    private fun publish(payload: CompanionPayload) {
        CompanionSceneState.onPayload(payload)
        if (_renderPayload.value.payload == payload) return
        val currentRevision = _renderPayload.value.revision
        val nextRevision = if (currentRevision == Long.MAX_VALUE) currentRevision else currentRevision + 1L
        _renderPayload.value = RenderPayload(nextRevision, payload)
        _payload.value = payload
    }
}
