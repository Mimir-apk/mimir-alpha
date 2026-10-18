package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * the media-bed ENVIRONMENT holder: the two inputs of [MediaBedPolicy] that come from the live
 * [AudioManager] rather than a preference flow (external music activity + call interruption). Object +
 * StateFlow shape mirrors [MimirAudioFocus]; the backdrop composable collects this alongside
 * [AudioPreferences] and [MimirAudioFocus] and feeds everything through the pure policy.
 *
 * [refresh] samples each clip bind, while one process-wide playback callback continuously classifies
 * active configurations. A bed start claims only the single new MEDIA/MOVIE configuration correlated
 * to that start; grace applies only to that exact claimed configuration. Public Android APIs do not
 * expose client UID/session identity here, so any mixed delta, unowned configuration, or
 * `isMusicActive` signal without a configuration is deliberately ambiguous and blocks the bed.
 * This ambient bed observes the audio environment but never requests system audio focus, so it
 * cannot pause or duck another app before the courtesy gate reacts.
 *
 * One stranger is explained away: the game Mimir launched and left running behind itself. At the
 * return from a launch Mimir armed, the game-like streams it started are counted by signature and
 * discounted while it is not in front ([MimirKeptGameStreams]); a stream that starts later, or one
 * beyond that count, still blocks.
 */
internal class MimirMediaBedOwnAudioGrace(
    private val clockMs: () -> Long,
    private val graceMs: Long,
) {
    private var lastAudibleAtMs: Long? = null

    fun noteAudible() {
        lastAudibleAtMs = clockMs()
    }

    fun likelyOwnAudio(): Boolean = lastAudibleAtMs?.let { audibleAt ->
        (clockMs() - audibleAt) in 0..graceMs
    } ?: false
}

/**
 * Fail-closed environment sample. `isMusicActive` is never waived by time alone: every active
 * playback configuration must still be correlated to a process-owned bed player. An empty or
 * partially-owned configuration set while music is active is ambiguous and therefore blocked.
 */
internal data class MimirMediaBedEnvironmentSample(
    val musicActive: Boolean,
    val activePlaybackCount: Int,
    val ownedPlaybackCount: Int,
    val callInterrupted: Boolean,
    val audioFocusLost: Boolean,
)

internal fun mimirMediaBedEnvironmentBlocked(sample: MimirMediaBedEnvironmentSample): Boolean {
    if (sample.callInterrupted || sample.audioFocusLost) return true
    if (sample.activePlaybackCount > sample.ownedPlaybackCount) return true
    return sample.musicActive &&
        (sample.activePlaybackCount == 0 || sample.ownedPlaybackCount != sample.activePlaybackCount)
}

/**
 * SoundPool navigation/confirm cues are transient UI sonification, not a competing media session.
 * Several handheld vendor stacks retain their SoundPool playback configuration in STARTED state
 * after the cue ends; counting it as external music would mute every later Hero Motion indefinitely.
 */
internal fun mimirMediaBedCourtesyRelevantContent(contentType: Int): Boolean =
    contentType != AudioAttributes.CONTENT_TYPE_SONIFICATION

/**
 * `isMusicActive` is ambiguous when Android exposes no playback configuration and must still block.
 * It is not ambiguous when the only observed configuration is explicitly ignored UI sonification.
 */
internal fun mimirMediaBedMusicActiveForCourtesy(
    musicActive: Boolean,
    observedPlaybackCount: Int,
    relevantPlaybackCount: Int,
): Boolean = musicActive && !(observedPlaybackCount > 0 && relevantPlaybackCount == 0)

internal fun mimirMediaBedRetainOwnedStartGap(
    relevantPlaybackCount: Int,
    activeOwnedPlaybackCount: Int,
    gapElapsedMs: Long,
): Boolean =
    relevantPlaybackCount == 0 &&
        activeOwnedPlaybackCount > 0 &&
        gapElapsedMs in 0..MimirMediaBed.OwnActiveClaimGapGraceMs

/**
 * Whether a batch of newly-appeared playback configurations can be claimed as Mimir's own.
 *
 * Claim only when EVERY new configuration looks like our own bed and there is exactly one
 * outstanding start for each. Public Android APIs expose no client UID or session here, so any
 * count mismatch or unfamiliar configuration is genuinely unknowable and must leave the batch
 * unowned -- the courtesy gate then blocks, which is the safe direction to be wrong in.
 *
 * The n=1 case is the original rule. Supporting n>1 prevents the ambient bed disappearing while
 * hovering a game with Hero art or motion: Mimir runs a frontend bed AND Hero Motion, and while only one
 * start could be outstanding the second player left the first uncorrelated, so Mimir read its own
 * audio as a stranger's and hard-muted itself.
 */
internal fun mimirMediaBedClaimsWholeDelta(
    deltaCount: Int,
    ownCandidateCount: Int,
    pendingClaimCount: Int,
): Boolean =
    pendingClaimCount > 0 &&
        deltaCount == ownCandidateCount &&
        ownCandidateCount == pendingClaimCount

/** What one playback-configuration callback settles: what it claims, and what it leaves outstanding. */
internal data class MimirMediaBedBatchOutcome<T>(
    /** Configurations to claim now, positionally paired with the outstanding starts, oldest first. */
    val claimed: List<T>,
    /** Starts that remain outstanding for a later callback to explain. */
    val stillPending: Int,
)

/**
 * Settle one batch of newly-appeared playback configurations against the outstanding own starts.
 *
 * Split out of [MimirMediaBed] because SEQUENCING is what broke and sequencing had no test seam:
 * Android's `AudioPlaybackConfiguration` is final and cannot be constructed in a host test, so logic
 * phrased directly in terms of it was unreachable from a unit test no matter how carefully it was
 * written. [mimirMediaBedClaimsWholeDelta] judges one batch in isolation and stayed green for the
 * whole life of the defect; what a single-batch predicate structurally cannot see is what becomes of
 * the starts a batch could NOT account for.
 *
 * [stillPending] is returned rather than left to the caller precisely because that is the decision
 * that was wrong, twice. A batch that explains nothing returns those starts UNSPENT: unclaimable
 * means "not yet", never "give up". Our two players' configurations routinely arrive in SEPARATE
 * callbacks, so the first batch holds one configuration against two outstanding starts — unclaimable
 * alone, and perfectly explicable one callback later. Measuring the delta from the OLDEST
 * outstanding baseline is what lets that second look succeed, and it only pays off if the first look
 * preserved what it could not yet explain.
 */
internal fun <T> mimirMediaBedSettleBatch(
    relevant: Collection<T>,
    oldestBaseline: Collection<T>,
    pendingClaimCount: Int,
    isOwnCandidate: (T) -> Boolean,
): MimirMediaBedBatchOutcome<T> {
    val unexplained = MimirMediaBedBatchOutcome<T>(claimed = emptyList(), stillPending = pendingClaimCount)
    val delta = relevant.filterNot { it in oldestBaseline }
    if (delta.isEmpty()) return unexplained
    val ownCandidates = delta.filter(isOwnCandidate)
    if (!mimirMediaBedClaimsWholeDelta(delta.size, ownCandidates.size, pendingClaimCount)) {
        return unexplained
    }
    return MimirMediaBedBatchOutcome(
        claimed = ownCandidates,
        stillPending = pendingClaimCount - ownCandidates.size,
    )
}

/**
 * Re-key own claims after Android hands back new configuration objects for the same players.
 *
 * `AudioPlaybackConfiguration.equals()` folds in the player's STATE and device id, not just its
 * identity, so the same player compares unequal the instant it changes state or is re-routed. A
 * claim map keyed by that value therefore un-owns our own bed for reasons that have nothing to do
 * with ownership. Measured on an emulator at commit 73e50467: the frontend bed correlated
 * (`relevant=1 owned=1`), and one millisecond later the next callback dropped the claim
 * (`relevant=1 owned=0`) with the same USAGE_MEDIA/CONTENT_TYPE_MUSIC configuration still present.
 * `activePlaybackCount > ownedPlaybackCount` then held, the courtesy gate called our own music a
 * stranger, and the bed paused and never came back.
 *
 * Public Android APIs expose no stable player id here — `getPlayerInterfaceId()` is not in the SDK —
 * so the only durable handle is the audio attributes, which do not change for a player's lifetime.
 * Matching on attributes alone would be far too loose (any app playing music matches our bed), so
 * a re-bind additionally requires all of:
 *
 *  - the claim is ACTIVE — the engine still believes that player is running. A claim whose player is
 *    stopping must not migrate onto whatever else happens to be playing music.
 *  - the candidate is UNCLAIMED by an exact survivor, so two claims can never collapse onto one.
 *  - the relevant set did not GROW. If something new appeared in the same batch, we genuinely cannot
 *    tell our churned player from the newcomer, so everything unmatched is dropped and the gate
 *    blocks — the safe direction, and the same conservatism the correlation rule already keeps.
 *
 * Returns the new key per claim, positionally; `null` drops that claim.
 */
internal fun <C> mimirMediaBedRekeyClaims(
    claimKeys: List<C>,
    claimActive: List<Boolean>,
    relevant: List<C>,
    previousRelevantCount: Int,
    signature: (C) -> Any,
): List<C?> {
    val available = relevant.toMutableList()
    val result = MutableList<C?>(claimKeys.size) { null }
    // Exact survivors first, so a still-equal configuration always keeps its own claim.
    claimKeys.forEachIndexed { index, key ->
        val at = available.indexOfFirst { it == key }
        if (at >= 0) result[index] = available.removeAt(at)
    }
    if (relevant.size > previousRelevantCount) return result
    claimKeys.forEachIndexed { index, key ->
        if (result[index] != null || !claimActive[index]) return@forEachIndexed
        val at = available.indexOfFirst { signature(it) == signature(key) }
        if (at >= 0) result[index] = available.removeAt(at)
    }
    return result
}

/**
 * How long to wait before re-reading the environment while it is blocked, or null to stop.
 *
 * The courtesy gate is driven entirely by events — playback-configuration callbacks, audio mode
 * changes, and [MimirMediaBed.refresh] at app foreground. That leaves states it cannot leave on its
 * own, and both were observed on an Odin 3 as PERMANENT silence:
 *
 *  - `isMusicActive` true with ZERO configurations. There is no configuration left to change, so no
 *    callback can ever fire. Measured: the bed sat blocked with nothing playing anywhere on the
 *    device, and only an unrelated app switch — which happens to call `refresh()` — brought it back.
 *  - a configuration that is present but unowned. It just sits there; nothing changes, so nothing
 *    is published, so nothing re-evaluates.
 *
 * A backstop poll is the honest fix for "no event source can clear this", but it must not become a
 * wakeup treadmill: the delays back off and then stop. Real external audio still ends with a
 * configuration callback, so giving up after the ladder costs nothing there — this exists purely to
 * let a STALE reading clear itself.
 */
internal fun mimirMediaBedRecheckDelayMs(blocked: Boolean, attempt: Int): Long? {
    if (!blocked || attempt < 0 || attempt >= MimirMediaBedRecheckDelaysMs.size) return null
    return MimirMediaBedRecheckDelaysMs[attempt]
}

/**
 * Backing-off backstop: ~60 s total, then the gate waits for a real event again. Every playback change and every foreground
 * edge starts it over. The tail is for the Nova (10-01), where `isMusicActive` stayed true with nothing playing for up to
 * ~34 s after Dolphin closed.
 */
internal val MimirMediaBedRecheckDelaysMs = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

/**
 * Own players whose claim just vanished while the player itself is still running.
 *
 * A dropped claim has two very different causes and they must not be treated alike: the player
 * genuinely stopped, or its configuration merely churned. Measured on an Odin 3 during a hero-motion
 * to hero-motion pass, at 17:47:03 — the motion player's configuration disappeared (`relevant=1
 * owned=1`) and reappeared 119 ms later (`relevant=2 owned=1 pending=0`), unowned and with nothing
 * outstanding to correlate it against. `activePlaybackCount > ownedPlaybackCount` then held, the bed
 * was silenced, and only the backstop poll eventually lifted it — a 4.6 s hole in the music.
 *
 * A player that is still in [MimirMediaBed.activeOwnPlaybackTokens] has not gone anywhere, so its
 * next configuration is owed a chance to be recognised. Re-arming it as outstanding is what gives it
 * one. Tokens that are genuinely gone are not re-armed, and the claim rule is unchanged, so this
 * cannot pull a stranger in: correlation still demands an all-own delta with matching counts.
 */
internal fun <T> mimirMediaBedTokensToRearm(
    droppedTokens: List<T>,
    activeTokens: Set<T>,
    alreadyPending: Set<T>,
): List<T> = droppedTokens.distinct().filter { it in activeTokens && it !in alreadyPending }

/** The two process-owned ambient players: Hero Motion (MOVIE) and Home music (MUSIC). */
internal fun mimirOwnBedContentType(contentType: Int): Boolean =
    contentType == AudioAttributes.CONTENT_TYPE_MOVIE ||
        contentType == AudioAttributes.CONTENT_TYPE_MUSIC

/**
 * The audio mode the environment sample reads for "is a call interrupting?". `AudioManager.getMode()` is a blocking binder call, and the sample is published on the main
 * thread on every playback change, refresh and recheck; where the platform tells us the mode ([AudioManager.OnModeChangedListener], Android 12+) the mode is kept here
 * and the sample reads it for free. Where it does not (Android 11) there is nothing to keep it fresh, so [current] reads the live mode exactly as it always did.
 */
internal class MimirMediaBedModeCache {
    @Volatile
    private var mode: Int = AudioManager.MODE_NORMAL

    @Volatile
    private var listenerBacked: Boolean = false

    /** Seeds the cache with the mode read once at attach, and says whether a listener will keep it fresh from now on. */
    fun seed(initialMode: Int, listenerBacked: Boolean) {
        mode = initialMode
        this.listenerBacked = listenerBacked
    }

    /** The platform's report of a mode change. */
    fun onModeChanged(newMode: Int) {
        mode = newMode
    }

    /** The mode to judge a call by: the kept one when a listener keeps it, else [live] (the blocking read). */
    fun current(live: () -> Int): Int = if (listenerBacked) mode else live()
}

object MimirMediaBed {
    private data class OwnedPlaybackClaim(
        val token: Long,
        val active: Boolean,
    )

    private data class PendingPlaybackClaim(
        val token: Long,
        val baseline: Set<AudioPlaybackConfiguration>,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var audioManager: AudioManager? = null

    private val _environmentBlocked = MutableStateFlow(false)
    private val _callInterrupted = MutableStateFlow(false)
    private val _activeOwnPlaybackTokens = MutableStateFlow<Set<Long>>(emptySet())
    private val _audibleOwnPlaybackTokens = MutableStateFlow<Set<Long>>(emptySet())
    // This belongs to the process, not a composable/player instance. Grace can retain an EXACT
    // claimed configuration through its release tail, but can never claim an unknown configuration.
    private val ownAudioGrace = MimirMediaBedOwnAudioGrace(
        clockMs = SystemClock::uptimeMillis,
        graceMs = OwnBedAudioGraceMs,
    )
    private var playbackConfigurations: Set<AudioPlaybackConfiguration> = emptySet()
    private var observedPlaybackCount = 0

    /** The game Mimir launched and left running behind itself: its streams are not another app's music (see [MimirKeptGameStreams]). */
    private val keptGame = MimirKeptGameStreams<AudioPlaybackConfiguration>(
        gameLikeSignature = { configuration ->
            mimirKeptGameSignature(configuration.audioAttributes.usage, configuration.audioAttributes.contentType)
        },
        isRealMedia = { configuration ->
            isRealMediaPlayback(configuration.audioAttributes.usage, configuration.audioAttributes.contentType)
        },
    )
    private var ownedPlaybackClaims: Map<AudioPlaybackConfiguration, OwnedPlaybackClaim> = emptyMap()
    /**
     * Own players awaiting correlation, oldest first.
     *
     * INVARIANT: a start stays here until it is correlated to a configuration or its player stops.
     * Nothing else may drop it — least of all a batch that merely failed to explain it.
     *
     * This was a single slot, which caused the ambient bed to disappear when hovering a game
     * with Hero art or motion. Mimir runs two of its own players -- the frontend bed and
     * Hero Motion -- and the second [noteOwnPlaybackStarting] overwrote the first's claim before it
     * could be correlated. That player stayed unowned, so `activePlaybackCount > ownedPlaybackCount`
     * held, and [mimirMediaBedEnvironmentBlocked] concluded something EXTERNAL was playing. Mimir
     * counted itself as a stranger and hard-muted its own music: the courtesy gate is binary, so the
     * duck at FrontendMusicPolicy's `competingOwnedBed` never got a chance to run.
     *
     * Widening the slot to a list is necessary but NOT sufficient, which is the whole reason the
     * invariant above is written as one. Android delivers our two players in separate callbacks far
     * more often than in one, and a list that is emptied whenever a batch cannot be explained
     * reproduces the identical end state — every start discarded on the first callback, nothing left
     * to correlate on the second, both players unowned, the bed muted by its own courtesy gate.
     */
    private var pendingPlaybackClaims: List<PendingPlaybackClaim> = emptyList()
    private var nextPlaybackToken = 0L
    private var lastAudibleTokenLogged: Long? = null
    private var lastPublishedDiagnostic: String? = null
    private var activeClaimGapStartedAtMs: Long? = null
    private val refreshOwnedStartGap = Runnable { refresh() }
    /** Backstop poll for blocked states that no callback can lift. Reset the moment we unblock. */
    private var blockedRecheckAttempt = 0
    private val recheckBlockedEnvironment = Runnable { refresh() }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            synchronized(lock) {
                // A stale reading follows a change, so every change earns a fresh ladder: one used up while a paused
                // emulator sat in the background left nothing to re-read after it closed (Nova, 10-01, minutes of silence).
                blockedRecheckAttempt = 0
                acceptPlaybackConfigurationsLocked(configs.toSet())
                publishEnvironmentLocked()
            }
        }
    }

    private var modeChangedListener: AudioManager.OnModeChangedListener? = null

    /** The audio mode, kept from [modeChangedListener] so the main thread never makes the blocking `getMode()` binder call while publishing. */
    private val modeCache = MimirMediaBedModeCache()

    /** True while the bind-time environment forbids ambient audio (external music, or an active call). */
    val environmentBlocked: StateFlow<Boolean> = _environmentBlocked.asStateFlow()

    /**
     * True while a call interrupts. It is a part of [environmentBlocked], published on its own because the theme bed's deliberate start
     * ([MimirMusicControl.bedTakeover]) waives the courtesy to other music but never the call, and the bed has no other way to hear one
     * (the call watch runs only for Your music).
     */
    val callInterrupted: StateFlow<Boolean> = _callInterrupted.asStateFlow()

    /** Active process-owned media demands. The Home composition yields whenever another token appears. */
    val activeOwnPlaybackTokens: StateFlow<Set<Long>> = _activeOwnPlaybackTokens.asStateFlow()

    /** Process-owned players that are both running and presently audible. Preparing and silent
     * players deliberately stay out so the frontend composition ducks only for a real media bed. */
    val audibleOwnPlaybackTokens: StateFlow<Set<Long>> = _audibleOwnPlaybackTokens.asStateFlow()

    /** Cache the [AudioManager] once (idempotent; application context only). */
    fun attach(context: Context) {
        synchronized(lock) {
            if (audioManager != null) return
            val manager =
                context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return
            audioManager = manager
            runCatching { manager.registerAudioPlaybackCallback(playbackCallback, mainHandler) }
            var listenerBacked = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = AudioManager.OnModeChangedListener { newMode ->
                    synchronized(lock) {
                        modeCache.onModeChanged(newMode)
                        publishEnvironmentLocked()
                    }
                }
                modeChangedListener = listener
                listenerBacked = runCatching {
                    manager.addOnModeChangedListener(context.applicationContext.mainExecutor, listener)
                }.isSuccess
            }
            // The one read of the mode: after the listener is registered, so no change falls between this read and the first report. Only a listener-backed cache is
            // trusted afterwards; without one every sample still reads the live mode, as it always did.
            modeCache.seed(runCatching { manager.mode }.getOrDefault(AudioManager.MODE_NORMAL), listenerBacked)
            acceptPlaybackConfigurationsLocked(
                runCatching { manager.activePlaybackConfigurations.toSet() }.getOrDefault(emptySet()),
            )
            publishEnvironmentLocked()
        }
    }

    /** Unique process token; controller-local generations are not unique after conditional disposal. */
    fun nextOwnPlaybackToken(): Long = synchronized(lock) {
        nextPlaybackToken = if (nextPlaybackToken == Long.MAX_VALUE) 1L else nextPlaybackToken + 1L
        nextPlaybackToken
    }

    /**
     * Correlates the next new MOVIE-or-MUSIC/MEDIA configuration to this exact start. Configurations
     * already active at the baseline remain unowned, so external playback begun during grace blocks.
     * A simultaneous ambiguous delta is never guessed: it stays unowned and therefore fail-closed.
     */
    fun noteOwnPlaybackStarting(token: Long) {
        synchronized(lock) {
            _activeOwnPlaybackTokens.value = _activeOwnPlaybackTokens.value + token
            val resumed = ownedPlaybackClaims.filterValues { it.token == token }
            if (resumed.isNotEmpty()) {
                ownedPlaybackClaims = ownedPlaybackClaims.mapValues { (configuration, claim) ->
                    if (configuration in resumed) claim.copy(active = true) else claim
                }
                pendingPlaybackClaims = pendingPlaybackClaims.filterNot { it.token == token }
            } else if (pendingPlaybackClaims.none { it.token == token }) {
                // Append. Overwriting is what let one own player erase another's claim.
                //
                // And KEEP the earliest baseline for a token that is already outstanding, rather
                // than re-taking it. Callers legitimately announce the same start twice -- Hero
                // Motion registers before MediaPlayer construction, because some handheld stacks
                // publish a MEDIA/MOVIE configuration at prepare, then registers again at start().
                // Re-baselining on that second call captures a set that ALREADY CONTAINS the
                // player's own configuration, so it can never appear as a delta and the player
                // stays permanently unowned -- read as external audio forever. That is exactly the
                // hazard the early registration exists to avoid, and re-taking the baseline here
                // silently undid it.
                pendingPlaybackClaims = pendingPlaybackClaims +
                    PendingPlaybackClaim(token, playbackConfigurations)
            }
            publishEnvironmentLocked()
        }
    }

    /** Marks only this player token as a bounded release tail; unrelated playback is never covered. */
    fun noteOwnPlaybackStopped(token: Long) {
        synchronized(lock) {
            _activeOwnPlaybackTokens.value = _activeOwnPlaybackTokens.value - token
            _audibleOwnPlaybackTokens.value = _audibleOwnPlaybackTokens.value - token
            ownAudioGrace.noteAudible()
            ownedPlaybackClaims = ownedPlaybackClaims.mapValues { (_, claim) ->
                if (claim.token == token) claim.copy(active = false) else claim
            }
            if (lastAudibleTokenLogged == token) lastAudibleTokenLogged = null
            pendingPlaybackClaims = pendingPlaybackClaims.filterNot { it.token == token }
            publishEnvironmentLocked()
        }
    }

    /** Publishes the exact audible state of a process-owned player. Ownership is established by
     * [noteOwnPlaybackStarting]; a silent, paused, preparing, or audio-less player removes itself. */
    fun noteOwnBedLevel(token: Long, audible: Boolean, volume: Float) {
        synchronized(lock) {
            val isAudible = audible && volume > AudibleFloor && token in _activeOwnPlaybackTokens.value
            _audibleOwnPlaybackTokens.value = if (isAudible) {
                _audibleOwnPlaybackTokens.value + token
            } else {
                _audibleOwnPlaybackTokens.value - token
            }
            if (isAudible) {
                ownAudioGrace.noteAudible()
                if (lastAudibleTokenLogged != token) {
                    lastAudibleTokenLogged = token
                    Log.d(TAG, "audible-owned token=$token volume=${"%.3f".format(volume)}")
                }
            }
        }
    }

    /** Compatibility name for callers that already proved their player is audible. */
    fun noteOwnBedAudible(token: Long, volume: Float) = noteOwnBedLevel(token, true, volume)

    /** [refresh] at Mimir's foreground edge, with a fresh re-read ladder: the return from a game is when a stale reading shows. */
    fun refreshOnForeground() {
        synchronized(lock) { blockedRecheckAttempt = 0 }
        refresh()
    }

    /**
     * Mimir armed a game launch ([MimirAudioFocus.onGameLaunchArmed]). The game-like streams already playing are not the game's; the game's
     * own are told apart from them at [noteReturnedFromGame]. See [MimirKeptGameStreams].
     */
    fun noteGameLaunchArmed() {
        synchronized(lock) {
            keptGame.onLaunchArmed(unownedPlaybackLocked(), SystemClock.uptimeMillis())
            publishEnvironmentLocked()
        }
    }

    /**
     * Control is back with Mimir after a launch it armed ([MimirAudioFocus.onReturnedToMimir]). Reads the live streams, takes the ones the
     * game left running behind Mimir out of "another app's music", and publishes. [strangerSessionPlaying]: another app's media session is
     * playing (notification access), which keeps the gate shut when nothing real-media tagged explains it ([MimirKeptGameStreams.onReturned]).
     */
    fun noteReturnedFromGame(strangerSessionPlaying: Boolean) {
        synchronized(lock) {
            audioManager?.let { manager ->
                acceptPlaybackConfigurationsLocked(
                    runCatching { manager.activePlaybackConfigurations.toSet() }.getOrDefault(playbackConfigurations),
                )
            }
            val kept = keptGame.onReturned(unownedPlaybackLocked(), strangerSessionPlaying, SystemClock.uptimeMillis())
            Log.i(
                TAG,
                "returned from a Mimir launch: $kept stream(s) of the game left running are not other music " +
                    "(relevant=${playbackConfigurations.size} strangerSession=$strangerSessionPlaying)",
            )
            blockedRecheckAttempt = 0
            publishEnvironmentLocked()
        }
    }

    /**
     * The music control's word on whether a game is on screen, read at each of its passes ([MimirKeptGameStreams.setFront]): a game fronted from
     * its tab, from Recents or by the quick switch has no launch edge (a Return or a HOME Resume of a Main game arms one when Main stops). While it
     * is on screen its stream is not discounted, so the bed meets the courtesy gate again.
     */
    fun noteGameFront(front: Boolean) {
        synchronized(lock) {
            if (!keptGame.setFront(front)) return
            blockedRecheckAttempt = 0
            publishEnvironmentLocked()
        }
    }

    /**
     * A restart: the app has proved [keptTasks] games Mimir launched are still running (see [MimirKeptGameStreams.seed]). The game-like streams playing
     * now, up to that many, are not other music. Reads the live streams first, discounts nothing once a launch or a return has been seen in this
     * process, and publishes. Returns how many streams are discounted now (0: nothing seeded). [strangerSessionPlaying] is as at a return.
     */
    fun seedKeptGame(keptTasks: Int, strangerSessionPlaying: Boolean): Int {
        synchronized(lock) {
            audioManager?.let { manager ->
                acceptPlaybackConfigurationsLocked(
                    runCatching { manager.activePlaybackConfigurations.toSet() }.getOrDefault(playbackConfigurations),
                )
            }
            val seeded = keptGame.seed(unownedPlaybackLocked(), keptTasks, strangerSessionPlaying, SystemClock.uptimeMillis())
            Log.i(
                TAG,
                "restart with a Mimir game still running: $seeded stream(s) of it are not other music " +
                    "(tasks=$keptTasks relevant=${playbackConfigurations.size} strangerSession=$strangerSessionPlaying)",
            )
            blockedRecheckAttempt = 0
            publishEnvironmentLocked()
            return seeded
        }
    }

    /** The relevant streams that are not claimed as Mimir's own. */
    private fun unownedPlaybackLocked(): List<AudioPlaybackConfiguration> =
        playbackConfigurations.filter { it !in ownedPlaybackClaims }

    /** Re-read the live environment. Calls and unowned/ambiguous playback always block. */
    fun refresh() {
        synchronized(lock) {
            val manager = audioManager
            if (manager == null) {
                _environmentBlocked.value = false
                return
            }
            acceptPlaybackConfigurationsLocked(
                runCatching { manager.activePlaybackConfigurations.toSet() }
                    .getOrDefault(playbackConfigurations),
            )
            publishEnvironmentLocked()
        }
    }

    private fun acceptPlaybackConfigurationsLocked(
        current: Set<AudioPlaybackConfiguration>,
    ) {
        val relevant = current.filterTo(linkedSetOf()) { configuration ->
            mimirMediaBedCourtesyRelevantContent(configuration.audioAttributes.contentType)
        }
        val activeOwnedCount = ownedPlaybackClaims.values.count(OwnedPlaybackClaim::active)
        if (relevant.isEmpty() && activeOwnedCount > 0) {
            val now = SystemClock.uptimeMillis()
            val gapStartedAt = activeClaimGapStartedAtMs ?: now.also {
                activeClaimGapStartedAtMs = it
            }
            if (
                mimirMediaBedRetainOwnedStartGap(
                    relevantPlaybackCount = relevant.size,
                    activeOwnedPlaybackCount = activeOwnedCount,
                    gapElapsedMs = now - gapStartedAt,
                )
            ) {
                // Odin publishes one empty callback immediately after the exact owned player is
                // correlated, then exposes the same started player through the next live sample.
                // Retain only that exact active claim for a bounded gap and force a fresh sample;
                // unrelated MEDIA/MOVIE configurations still replace it and fail closed below.
                observedPlaybackCount = maxOf(current.size, playbackConfigurations.size)
                mainHandler.removeCallbacks(refreshOwnedStartGap)
                mainHandler.postDelayed(refreshOwnedStartGap, OwnActiveClaimRefreshMs)
                return
            }
        }
        activeClaimGapStartedAtMs = null
        mainHandler.removeCallbacks(refreshOwnedStartGap)
        observedPlaybackCount = current.size
        val previousRelevantCount = playbackConfigurations.size
        playbackConfigurations = relevant
        keptGame.onPlayback(relevant, SystemClock.uptimeMillis())
        // NOT `filterKeys(relevant::contains)`. AudioPlaybackConfiguration.equals() folds in the
        // player's STATE and device id, so Android hands back an unequal object for the very same
        // player the moment it changes state — and a map keyed by that value silently un-owns our
        // own bed. See mimirMediaBedRekeyClaims for the measured failure this fixes.
        val claimEntries = ownedPlaybackClaims.entries.toList()
        val rekeyed = mimirMediaBedRekeyClaims(
            claimKeys = claimEntries.map { it.key },
            claimActive = claimEntries.map { it.value.active },
            relevant = relevant.toList(),
            previousRelevantCount = previousRelevantCount,
            signature = ::ownBedSignature,
        )
        ownedPlaybackClaims = buildMap {
            claimEntries.forEachIndexed { index, entry ->
                rekeyed[index]?.let { key -> put(key, entry.value) }
            }
        }
        // Re-arm any player we just un-claimed that is still running. Its configuration churned
        // rather than went away, and without an outstanding start its NEXT configuration would be
        // read as a stranger's -- which is exactly how a hero-motion hand-off used to silence the
        // bed. Baselined on what is present now, so only something genuinely new can satisfy it.
        val rearm = mimirMediaBedTokensToRearm(
            droppedTokens = claimEntries.filterIndexed { index, _ -> rekeyed[index] == null }
                .map { it.value.token },
            activeTokens = _activeOwnPlaybackTokens.value,
            alreadyPending = pendingPlaybackClaims.mapTo(mutableSetOf()) { it.token },
        )
        if (rearm.isNotEmpty()) {
            pendingPlaybackClaims = pendingPlaybackClaims +
                rearm.map { token -> PendingPlaybackClaim(token, relevant) }
        }

        // A start stays outstanding until it is either correlated or its player stops. Claims for
        // tokens that are no longer running would only inflate the count the exact-match rule has to
        // satisfy, and could wedge a later legitimate correlation permanently.
        pendingPlaybackClaims = pendingPlaybackClaims.filter { it.token in _activeOwnPlaybackTokens.value }
        if (pendingPlaybackClaims.isEmpty()) return
        // Measure against the OLDEST outstanding start, so a delta spanning several of our own
        // players is still seen whole rather than one start at a time.
        //
        // The n=1 rule generalised, deliberately keeping its conservatism: claim only when EVERY
        // newly-appeared configuration looks like our own bed AND there is exactly one outstanding
        // start for each. Anything else -- an extra config, an unfamiliar one, a count mismatch --
        // is unknowable on public Android APIs (client UID and session are not exposed here), so
        // every candidate stays unowned and the courtesy gate blocks, which is the safe direction.
        val outcome = mimirMediaBedSettleBatch(
            relevant = relevant,
            oldestBaseline = pendingPlaybackClaims.first().baseline,
            pendingClaimCount = pendingPlaybackClaims.size,
            isOwnCandidate = ::hasOwnBedAttributes,
        )
        // Pairing is positional and may mis-match WHICH of our own tokens owns which configuration.
        // That is tolerable and deliberate: the environment gate reasons about COUNTS, and the
        // audible-token set that drives ducking is published directly by noteOwnBedLevel rather than
        // derived from this map, so a swap between two of our own players changes nothing
        // observable. It is never allowed to pair a foreign configuration, which is the error that
        // would actually matter.
        ownedPlaybackClaims = ownedPlaybackClaims +
            outcome.claimed.zip(pendingPlaybackClaims) { configuration, claim ->
                configuration to OwnedPlaybackClaim(claim.token, active = true)
            }
        // Only ever what the batch actually settled. An unexplained batch returns its starts unspent
        // and they stay outstanding here — the caller has no say, which is the point: deciding this
        // locally is what got it wrong both times.
        pendingPlaybackClaims = pendingPlaybackClaims.takeLast(outcome.stillPending)
    }

    /**
     * The part of a configuration that survives a state change: what it is playing, not how it is.
     * Deliberately NOT the whole `AudioAttributes` — that carries flags a player may revise.
     */
    private fun ownBedSignature(configuration: AudioPlaybackConfiguration): Any =
        configuration.audioAttributes.usage to configuration.audioAttributes.contentType

    private fun hasOwnBedAttributes(configuration: AudioPlaybackConfiguration): Boolean =
        configuration.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
            mimirOwnBedContentType(configuration.audioAttributes.contentType)

    private fun publishEnvironmentLocked() {
        val manager = audioManager
        if (manager == null) {
            _environmentBlocked.value = false
            _callInterrupted.value = false
            return
        }
        val graceActive = ownAudioGrace.likelyOwnAudio()
        val ownedCount = ownedPlaybackClaims.count { (configuration, claim) ->
            configuration in playbackConfigurations && (claim.active || graceActive)
        }
        val callInterrupted =
            callInterruptionStateForMode(modeCache.current { manager.mode }) == CallInterruptionState.Interrupted
        val keptDiscount = keptGame.discount(playbackConfigurations)
        val baseSample = mimirMediaBedEnvironmentSample(
            musicActive = manager.isMusicActive,
            observedPlaybackCount = observedPlaybackCount,
            relevantPlaybackCount = playbackConfigurations.size,
            keptGameStreams = keptDiscount,
            ownedPlaybackCount = ownedCount,
            callInterrupted = callInterrupted,
        )
        _callInterrupted.value = callInterrupted
        val blocked = mimirMediaBedEnvironmentBlocked(baseSample)
        _environmentBlocked.value = blocked
        // Blocking on a stale reading must never be terminal. Unblocking resets the ladder, so a
        // genuine external session that ends normally never pays for this.
        if (!blocked) {
            blockedRecheckAttempt = 0
            mainHandler.removeCallbacks(recheckBlockedEnvironment)
        } else {
            mimirMediaBedRecheckDelayMs(blocked = true, attempt = blockedRecheckAttempt)?.let { delay ->
                blockedRecheckAttempt += 1
                mainHandler.removeCallbacks(recheckBlockedEnvironment)
                mainHandler.postDelayed(recheckBlockedEnvironment, delay)
            }
        }
        val diagnostic =
            "blocked=$blocked music=${baseSample.musicActive} observed=$observedPlaybackCount " +
                "relevant=${playbackConfigurations.size} owned=${baseSample.ownedPlaybackCount} " +
                "call=${baseSample.callInterrupted} pending=${pendingPlaybackClaims.size} kept=$keptDiscount"
        if (diagnostic != lastPublishedDiagnostic) {
            lastPublishedDiagnostic = diagnostic
            Log.d(TAG, diagnostic)
        }
    }

    internal const val OwnBedAudioGraceMs = 2500L
    internal const val OwnActiveClaimGapGraceMs = 1000L
    private const val AudibleFloor = 0.01f
    private const val OwnActiveClaimRefreshMs = 80L
    private const val TAG = "MimirMediaBed"
}
