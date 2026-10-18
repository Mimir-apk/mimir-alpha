package com.gruning.mimir.rewrite.core.launch

import android.content.Context
import com.gruning.mimir.rewrite.core.audio.MimirAudioFocus
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.launch.remotedefs.ActiveDefinitions
import com.gruning.mimir.rewrite.core.launch.remotedefs.EffectiveDefinitions
import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchProfile
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The end-to-end launch result the UI renders from: the resolved [readiness] plus, when a target was
 * dispatchable and dispatch fired, the synchronous [outcome].
 *
 * - No target ([LaunchReadiness.NeedsEmulator] / [MissingFile] / [Unsupported]) → [outcome] is null;
 *   the UI shows the honest reason and routes the user (settings / detail).
 * - [LaunchReadiness.Ready], or [NeedsBios] carrying a target (a
 *   confirmed-missing BIOS no longer blocks the handoff) → [outcome] is [LaunchOutcome.IntentSent]
 *   (show "Launching…", or for NeedsBios keep showing the honest BIOS reason — see
 *   `GameLaunchUiState.from`) or [LaunchOutcome.Failed] (show the typed dispatch reason).
 */
data class LaunchResult(
    val readiness: LaunchReadiness,
    val outcome: LaunchOutcome? = null,
    val preflight: LaunchTargetPreflightContext? = null,
) {
    /** True when the intent was actually sent (a dispatchable readiness + IntentSent). */
    val launched: Boolean get() = outcome is LaunchOutcome.IntentSent

    /** True when a dispatchable readiness's dispatch failed synchronously. */
    val dispatchFailed: Boolean get() = outcome is LaunchOutcome.Failed
}

/**
 * The target this readiness makes launchable RIGHT NOW: always for [LaunchReadiness.Ready], and also
 * for [LaunchReadiness.NeedsBios] once it resolved a full target despite the confirmed-missing BIOS
 * (see [LaunchReadiness.NeedsBios]). Every other state has nothing to
 * dispatch. Kept local to `core-launch` (not a model-layer concept): it is the launch orchestrator's
 * own "can I actually fire an intent for this" question, distinct from [LaunchReadiness.isReady]
 * (which stays strictly "no caveats at all").
 */
private val LaunchReadiness.dispatchableTarget: LaunchTarget?
    get() = when (this) {
        is LaunchReadiness.Ready -> target
        is LaunchReadiness.NeedsBios -> target
        else -> null
    }

/** Opaque identity shared only by the callbacks belonging to one exact dispatch attempt. */
@JvmInline
value class LaunchAttemptId private constructor(val value: String) {
    companion object {
        fun create(): LaunchAttemptId = LaunchAttemptId(UUID.randomUUID().toString())
        fun fromTestValue(value: String): LaunchAttemptId = LaunchAttemptId(value.trim().also {
            require(it.isNotEmpty()) { "Launch attempt identity must not be blank" }
        })
    }
}

/** One "Open with…" candidate emulator for a game's platform (the picker row model). */
data class OpenWithCandidate(
    val emulatorId: EmulatorId,
    /** Human label — the emulator's display name (already includes the RetroArch core, e.g. "RetroArch 64 / mGBA"). */
    val label: String,
    /** The emulator's Android package (the dispatch package — for RetroArch, the installed core host). Drives the
     *  picker's per-row app icon; never persisted (override is keyed on [emulatorId]). */
    val packageName: String,
    /** Installed on this device right now (a not-installed candidate shows as "Needs install", non-committable). */
    val installed: Boolean,
    /** This platform/emulator pair has a concrete local dispatch profile. */
    val dispatchSupported: Boolean = true,
    /** RetroArch host presence does not prove this exact core is installed or launch-certified. */
    val requiresManualCoreProof: Boolean = false,
    /** This emulator is the game's current stored override. */
    val isCurrentOverride: Boolean,
    /** This is the emulator Auto would launch right now (shown as the "Recommended" chip). */
    val isAutoPick: Boolean,
    /**
     * This is the emulator `launch()` would start for this game right now, resolved with its own precedence: the stored
     * override when it is usable, else Auto directly (a stored override means the platform default is never read);
     * with no override, the platform default, else Auto. At most one candidate carries it, and none when nothing
     * installed can carry the game. Read-only data for lines that say what a game opens with; the launch path does
     * not read it.
     */
    val isLaunchPick: Boolean = false,
    /**
     * Does this emulator's list of accepted files include the game's launch file? Information for a label, never a filter:
     * it changes no selectability, no Recommended chip and no Auto choice.
     *  - null: the emulator has no list for the platform (every file accepted, today's behaviour), or the launch file's name is unknown;
     *  - true: it has a list and the file is on it;
     *  - false: it has a list and the file is not on it.
     */
    val listsFile: Boolean? = null,
) {
    val selectable: Boolean get() = installed && dispatchSupported
}

/** The per-game Open-With options: whether an override is set (vs Auto) + the platform's candidates. */
data class OpenWithOptions(
    val hasOverride: Boolean,
    val candidates: List<OpenWithCandidate>,
    /**
     * The launch file's real extension (lowercase, no dot) for the "Doesn't list .xyz files" lines; null when the name is unknown,
     * has no extension, or carries one that is not a plain run of letters and digits (a label never prints arbitrary file-name text).
     */
    val fileExtension: String? = null,
) {
    /**
     * True when the launch file is on none of the lists of the emulators that are installed: there is at least one installed
     * emulator, and every one of them has a list that leaves the file out. Play stays as it is; this only lets the picker say so.
     */
    val noInstalledEmulatorListsFile: Boolean
        get() {
            val installed = candidates.filter { it.installed }
            return installed.isNotEmpty() && installed.all { it.listsFile == false }
        }
}

/** The extension [OpenWithOptions.fileExtension] carries for a launch file named [fileName], or null (see there). */
internal fun openWithDisplayExtension(fileName: String?): String? =
    fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        ?.takeIf { it.length in 1..10 && it.all { c -> c in 'a'..'z' || c in '0'..'9' } }

/**
 * Orchestrates a real launch end-to-end:
 *
 *  1. Snapshot the device facts through the Room gatherers + the [PackageManagerEmulatorProvider]
 *     (installed emulators, per-game Open-With override, BIOS presence, the chosen member's locator).
 *  2. Run the pure [LaunchReadinessEngine] over that snapshot. The engine picks the emulator (Auto
 *     default-to-installed, or the override), checks BIOS, resolves the locator.
 *  3. If [LaunchReadiness.Ready] → dispatch the intent via [IntentLaunchDispatcher] and record a durable
 *     [com.gruning.mimir.rewrite.core.model.LaunchProof]. Otherwise return the readiness reason (nothing
 *     dispatched).
 *
 * Dispatch-only: the outcome is intent-sent-or-failed,
 * never a session-liveness verdict.
 *
 * All Room reads are `suspend`; call [launch] from a coroutine (the ViewModel does, off the UI thread).
 */
class LaunchController internal constructor(
    private val emulatorProvider: InstalledEmulatorProvider,
    private val locatorResolver: suspend (GameId, EntryKey?, Int, LaunchLocatorForm) -> LaunchLocatorResult?,
    private val overrideResolver: suspend (GameId) -> EmulatorId?,
    private val overrideSetter: suspend (GameId, EmulatorId) -> Unit,
    private val overrideClearer: suspend (GameId) -> Unit,
    private val biosResolver: suspend (PlatformId, EmulatorId, String) -> List<BiosProfile>,
    private val proofRecorder: suspend (LaunchProof) -> Unit,
    private val dispatcher: LaunchDispatcher,
    /** The user's per-PLATFORM default emulator — applied BELOW a per-game override, ABOVE Auto; null = none. */
    private val platformDefault: suspend (PlatformId) -> EmulatorId? = { null },
    /**
     * HP-2: fired after a SUCCESSFUL dispatch for a dual-screen platform (DS/3DS) — those emulators take
     * the SECOND screen, so Mimir's companion must yield BEFORE their window lands there. null = no-op
     * (core-launch stays pure; the app wires it, mirroring [platformDefault]).
     */
    private val dualScreenLaunchNotifier: ((String) -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /** Test seams pin the suppression-sensitive success ordering without changing production behavior. */
    private val confirmLaunchCue: () -> Unit = { MimirUiSounds.play(MimirSoundEvent.ConfirmLaunch) },
    private val gameLaunchArmed: () -> Unit = MimirAudioFocus::onGameLaunchArmed,
    private val biosSeedBeforeRead: suspend (PlatformId, EmulatorId, String) -> BiosSeedSnapshot? =
        { _, _, _ -> null },
    private val biosGameAssignmentChanged: suspend (GameId, EmulatorId?) -> Unit = { _, _ -> },
    private val biosPlatformAssignmentChanged: suspend (PlatformId, EmulatorId?) -> Unit = { _, _ -> },
    private val onBiosLibraryRevisionChanged: suspend () -> Unit = {},
    private val gameIdResolver: suspend (GameId) -> GameId = { it },
    /**
     * The file NAMES Play could hand the emulator, the default launch member's first: with a chosen member or disc just that one,
     * else every available member of the group (Play can start any of them, e.g. one region of a group). Each is the member's own
     * name, never the generated `.m3u` a multi-disc launch is given; empty when none can be read. Only [openWithOptions] reads it,
     * to label which emulators list the file.
     */
    private val launchMemberFileNames: suspend (GameId, EntryKey?, Int) -> List<String> = { _, _, _ -> emptyList() },
    /** The definitions in force right now (the compiled tables plus any verified overlay); a seam so tests need no process-wide state. */
    private val definitions: () -> EffectiveDefinitions = { ActiveDefinitions.current },
) {

    /** Production adapter constructor retained for the Room + PackageManager wiring in [LaunchStore]. */
    constructor(
        emulatorProvider: PackageManagerEmulatorProvider,
        locatorGatherer: RoomLaunchLocatorGatherer,
        overrideGatherer: RoomOverrideGatherer,
        biosGatherer: RoomBiosGatherer,
        proofRecorder: RoomLaunchProofRecorder,
        dispatcher: LaunchDispatcher,
        platformDefault: suspend (PlatformId) -> EmulatorId? = { null },
        dualScreenLaunchNotifier: ((String) -> Unit)? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clockMillis: () -> Long = System::currentTimeMillis,
        biosSeedBeforeRead: suspend (PlatformId, EmulatorId, String) -> Unit = { _, _, _ -> },
        biosGameAssignmentChanged: suspend (GameId, EmulatorId?) -> Unit = { _, _ -> },
        biosPlatformAssignmentChanged: suspend (PlatformId, EmulatorId?) -> Unit = { _, _ -> },
        biosLibraryRevisionChanged: suspend () -> Unit = {},
        gameIdResolver: suspend (GameId) -> GameId = { it },
    ) : this(
        emulatorProvider = emulatorProvider,
        locatorResolver = locatorGatherer::resolve,
        overrideResolver = overrideGatherer::resolve,
        overrideSetter = overrideGatherer::set,
        overrideClearer = overrideGatherer::clear,
        biosResolver = biosGatherer::resolve,
        proofRecorder = proofRecorder::record,
        dispatcher = dispatcher,
        platformDefault = platformDefault,
        dualScreenLaunchNotifier = dualScreenLaunchNotifier,
        ioDispatcher = ioDispatcher,
        clockMillis = clockMillis,
        biosSeedBeforeRead = { platformId, emulatorId, packageName ->
            biosSeedBeforeRead(platformId, emulatorId, packageName)
            null
        },
        biosGameAssignmentChanged = biosGameAssignmentChanged,
        biosPlatformAssignmentChanged = biosPlatformAssignmentChanged,
        onBiosLibraryRevisionChanged = biosLibraryRevisionChanged,
        gameIdResolver = gameIdResolver,
        launchMemberFileNames = locatorGatherer::launchMemberFileNames,
    )

    /**
     * Resolve the launch state without dispatching, recording proof, or notifying the companion.
     * This is the pre-Play read for setup states such as missing BIOS or an unavailable emulator.
     */
    suspend fun resolveReadiness(gameId: GameId, platformId: PlatformId, preferredDisc: Int = 0): LaunchReadiness =
        withContext(ioDispatcher) {
            val currentGameId = gameIdResolver(gameId)
            resolveReadiness(
                currentGameId,
                platformId,
                launchMemberEntryKey = null,
                preferredDisc = preferredDisc,
                overrideEmulatorId = null,
            )
        }

    /**
     * Resolve readiness and, if Ready, dispatch + record. [gameId] is the dedup group; [platformId] its
     * platform; [launchMemberEntryKey] optionally pins the exact disc/member (null = the group default).
     * [overrideEmulatorId] optionally forces the Open-With override for THIS call (else the stored
     * per-game override is used).
     */
    suspend fun launch(
        gameId: GameId,
        platformId: PlatformId,
        launchMemberEntryKey: EntryKey? = null,
        preferredDisc: Int = 0,
        overrideEmulatorId: EmulatorId? = null,
        /**
         * Advisory cross-app preparation (the Tweaker profile apply) run just before dispatch. It is
         * contained and time-bounded — see [runContainedPreDispatch] — so it can never fail or wedge a
         * launch. Ordering ahead of dispatch is guaranteed only when it completes inside its budget.
         */
        preDispatchHook: suspend (LaunchReadiness.Ready, LaunchAttemptId) -> Unit = { _, _ -> },
        preDispatchTimeoutMillis: Long = DEFAULT_PRE_DISPATCH_TIMEOUT_MILLIS,
        /** Notified (after dispatch) when [preDispatchHook] threw or was abandoned, so the UI can be honest. */
        onPreDispatchDegraded: suspend (LaunchAttemptId, PreDispatchOutcome) -> Unit = { _, _ -> },
        /**
         * One-way success handoff executed only after IntentSent. It cannot change the launch result;
         * Mimir Tweaker uses it to start its foreground-owned post-dispatch quiescence wait.
         */
        postDispatchHook: suspend (LaunchReadiness.Ready, LaunchAttemptId) -> Unit = { _, _ -> },
        /**
         * App-owned best-effort unwind for a synchronous dispatch failure. This remains contained and
         * bounded like preparation; the V4 Tweaker owner contract will later replace best-effort restore
         * with exact request/generation cancellation.
         */
        onDispatchFailed: suspend (LaunchReadiness.Ready, LaunchOutcome.Failed, LaunchAttemptId) -> Unit =
            { _, _, _ -> },
        destination: LaunchDispatchDestination = LaunchDispatchDestination.Main,
    ): LaunchResult = withContext(ioDispatcher) {
        val currentGameId = gameIdResolver(gameId)
        val readiness = resolveReadiness(
            currentGameId,
            platformId,
            launchMemberEntryKey,
            preferredDisc,
            overrideEmulatorId,
        )

        // Dispatch on Ready, and also on a NeedsBios readiness that still resolved a full target —
        // a confirmed-missing BIOS no longer blocks the handoff to an
        // installed emulator, so [readiness.dispatchableTarget] covers both. `readiness` itself is
        // returned UNCHANGED below, so the caller still gets the honest NeedsBios reason even though
        // dispatch was attempted — see [LaunchResult]. Record a durable proof for every dispatch (success or
        // typed failure); every other not-ready state, and a RetroArch core refusal below, dispatches nothing
        // and records none.
        val dispatchTarget = readiness.dispatchableTarget ?: return@withContext LaunchResult(readiness)
        val ready = LaunchReadiness.Ready(dispatchTarget)
        // Before the reap below can stop a running RetroArch: a core that is provably not installed would only show black.
        RetroArchCoreCheck.refusalFor(ready.target)?.let { return@withContext LaunchResult(readiness, it) }
        val launchAttemptId = LaunchAttemptId.create()
        // Intent construction and exact-package reap own the first half of the launch ceremony. A
        // package-bound Tweaker profile must never be applied and immediately invalidated by that reap.
        val prepared = dispatcher.prepare(ready.target, destination)
        // Device report 2026-08-11: aPS3e boots a game cold but not warm. Its EmulatorActivity is
        // launchMode="singleTask" in its own ":emu" process with no `onNewIntent` override (confirmed by
        // decompiling the installed APK), so when its prior task is still alive, Android routes a second
        // launch to that missing callback instead of onCreate and the new game's payload is silently
        // dropped — the player just sees the already-open emulator back in front. aPS3e's own
        // "REENTRY_QUISK_START" action does not help (it lives on a different, unrelated activity and
        // never reaches EmulatorActivity), and the CLEAR_TASK flag already in ResidentLaunchFlags targets
        // onNewIntent/onCreate routing, not this — the only remaining lever on Mimir's side is making
        // sure the prior process is actually gone before redispatching. The reap just above already
        // requests that (privileged force-stop, falling back to killBackgroundProcesses), but that OS
        // call is asynchronous and returns before the target process is actually torn down, so an
        // immediate startActivity can still find the stale task. This closes that race with a short,
        // bounded wait — ONLY for a profile that declares it needs one
        // ([EmulatorProfile.residentTaskWithoutIntentRefresh]) and only when the reap attempted SOMETHING
        // (skipped on [LaunchTargetReapResult.NOT_ALLOWED], where nothing was killed, so there is nothing
        // to wait for). It cannot fix a device where the reap authority is unavailable AND the
        // best-effort `killBackgroundProcesses` fallback declines to touch the target (both are outside
        // Mimir's control), and the wait length itself is a starting estimate, not a device-measured one.
        if (
            ready.target.emulator.residentTaskWithoutIntentRefresh &&
            prepared.preflight?.result != null &&
            prepared.preflight.result != LaunchTargetReapResult.NOT_ALLOWED
        ) {
            delay(RESIDENT_TASK_REAP_SETTLE_MILLIS)
        }
        // The hook reaches into ANOTHER app over Binder. That call can throw, and it can fail to return at
        // all — neither is allowed to cost the player the game they pressed. Contain and time-bound it; on
        // the healthy path it still completes strictly after target reap and before startActivity.
        val preDispatch = if (prepared.dispatchable) {
            runContainedPreDispatch(ioDispatcher, preDispatchTimeoutMillis) {
                preDispatchHook(ready, launchAttemptId)
            }
        } else {
            PreDispatchOutcome.Completed
        }
        val outcome = prepared.dispatch()
        // Prepared launch carries exact preflight truth across the suspendable Tweaker hook. Retain the
        // legacy read only for source-compatible fake dispatchers.
        val preflight = prepared.preflight ?: dispatcher.preflightContext()
        if (outcome is LaunchOutcome.Failed) {
            runContainedPreDispatch(ioDispatcher, preDispatchTimeoutMillis) {
                onDispatchFailed(ready, outcome, launchAttemptId)
            }
        }
        if (outcome == LaunchOutcome.IntentSent && preDispatch is PreDispatchOutcome.Completed) {
            runContainedPreDispatch(ioDispatcher, POST_DISPATCH_HOOK_TIMEOUT_MILLIS) {
                postDispatchHook(ready, launchAttemptId)
            }
        }
        if (outcome == LaunchOutcome.IntentSent) {
            // Dispatch audio: the confirm cue rides SUCCESSFUL dispatch only
            // (R-6.4 — failure paths belong to Error), fires AFTER dispatch so it can never delay it
            // (R-6.1; SoundPool.play is non-blocking), and MUST precede onGameLaunchArmed() below —
            // arming flips MimirAudioPolicy to Suspended, which would silently suppress this very cue
            // (trap verified live during audio integration).
            confirmLaunchCue()
            // The #1 audio QA gate (charter §6): games own audio once launched. Runs after dispatch,
            // never blocking it (R-6.1). Mimir never formally requests AudioManager focus for its
            // one-shot SONIFICATION sfx, so there's nothing to truncate here (R-6.2 doesn't apply to
            // this architecture) — this only gates Mimir's OWN future play() calls.
            gameLaunchArmed()
            // DS/3DS emulators may claim the secondary display as soon as their Activity lands. Yield
            // companion ownership in the same immediate handoff, before any potentially slow degraded
            // Tweaker presentation.
            if (destination is LaunchDispatchDestination.Main &&
                (ready.target.platformId == PlatformId.DS || ready.target.platformId == PlatformId.THREE_DS)
            ) {
                dualScreenLaunchNotifier?.invoke(ready.target.emulator.packageName)
            }
        }
        // A timed-out hook continues on its detached IO worker until that worker returns. Retire its
        // exact attempt after the successful dispatch's immediate audio handoff but before any later
        // launch can consume it. Post-dispatch Tweaker activation is deliberately skipped above.
        if (preDispatch !is PreDispatchOutcome.Completed) {
            runContainedPreDispatch(ioDispatcher, preDispatchTimeoutMillis) {
                onPreDispatchDegraded(launchAttemptId, preDispatch)
            }
        }
        proofRecorder(
            LaunchProof(
                gameId = ready.target.gameId,
                platformId = ready.target.platformId,
                emulatorId = ready.target.emulator.id,
                locator = ready.target.locator,
                outcome = outcome,
                timestampMillis = clockMillis(),
            ),
        )
        LaunchResult(readiness, outcome, preflight)
    }

    private companion object {
        const val POST_DISPATCH_HOOK_TIMEOUT_MILLIS = 1_500L
        /**
         * Starting estimate for the reap-to-redispatch settle window, not a device-measured value — see
         * the comment above its one use site in [launch]. The verification plan should confirm whether
         * this is long enough (or needlessly long) on real hardware.
         */
        const val RESIDENT_TASK_REAP_SETTLE_MILLIS = 250L
    }

    /**
     * The per-game "Open with…" options for a platform: the installable candidate emulators (most-
     * compatible-first), each stamped with `installed`, whether it is the current override, and whether
     * it is the emulator Auto would pick right now. The UI shows an "Automatic" choice (clear override)
     * plus these rows. Reads live device state (PackageManager) + the stored override; sends nothing.
     */
    suspend fun openWithOptions(
        gameId: GameId,
        platformId: PlatformId,
        /** The member the launch would use; null leaves it open (the group's default one, or any other member Play can start). */
        launchMemberEntryKey: EntryKey? = null,
        preferredDisc: Int = 0,
    ): OpenWithOptions = withContext(ioDispatcher) {
        val currentGameId = gameIdResolver(gameId)
        val candidates = emulatorProvider.candidatesFor(platformId)
        // The launch files' own names, for the "Doesn't list .xyz files" labels. Read once, labels only: nothing below
        // chooses, hides or reorders by it, so Auto and the launch pick are exactly what they were. They are read only when
        // some candidate has a list to check them against, so with no list anywhere (every emulator today) this does no extra I/O.
        val defs = definitions()
        val launchFileNames = if (candidates.any { defs.hasAcceptanceList(EmulatorSeedRegistry.acceptanceKeyFor(it), platformId) }) {
            launchMemberFileNames(currentGameId, launchMemberEntryKey, preferredDisc)
        } else {
            emptyList()
        }
        val current = overrideResolver(currentGameId)
        // "Automatic" clears the per-game override, so it resolves the PLATFORM DEFAULT (else Auto) — the exact
        // launch() precedence (override ?: platformDefault ?: Auto). Resolve the auto-pick THAT way so the picker's
        // "Uses <emulator>" hint and the Recommended chip name the emulator Automatic actually launches, not raw
        // registry-Auto (truth labels — local-only policy).
        val autoDefault = platformDefault(platformId)
        val autoProfile = LaunchProfile(
            gameId = currentGameId,
            platformId = platformId,
            overrideEmulatorId = autoDefault,
            launchMemberEntryKey = null,
            requiresBios = false,
        )
        val autoPick = buildEngine(autoDefault, emptyList(), null).resolveEmulator(autoProfile, candidates)
        // The emulator launch() would start right now, from the facts read above and with no further I/O. It mirrors
        // resolveReadiness's `override ?: platformDefault` preference, so a stored override that is not installed or
        // not dispatchable goes straight to Auto and never falls back to the platform default.
        val launchPreference = current ?: autoDefault
        val launchPick = buildEngine(launchPreference, emptyList(), null)
            .resolveEmulator(autoProfile.copy(overrideEmulatorId = launchPreference), candidates)
        OpenWithOptions(
            hasOverride = current != null,
            candidates = candidates.map { c ->
                val dispatchSupported = ShippingLaunchProfiles.isCandidateDispatchSupported(platformId, c)
                OpenWithCandidate(
                    // displayName already carries the pretty RetroArch core (" / mGBA"); the raw core is redundant.
                    emulatorId = c.id,
                    label = c.displayName,
                    packageName = c.packageName,
                    installed = c.installed,
                    dispatchSupported = dispatchSupported,
                    requiresManualCoreProof = c.isRetroArchCore,
                    isCurrentOverride = current != null && c.id == current,
                    isAutoPick = dispatchSupported && autoPick != null && c.id == autoPick.id,
                    isLaunchPick = launchPick != null && c.id == launchPick.id,
                    listsFile = defs.listsAnyFile(EmulatorSeedRegistry.acceptanceKeyFor(c), platformId, launchFileNames),
                )
            },
            fileExtension = openWithDisplayExtension(launchFileNames.firstOrNull()),
        )
    }

    /** Set the game's Open-With override to [emulatorId] (persists; the next launch uses it). */
    suspend fun setEmulatorOverride(gameId: GameId, emulatorId: EmulatorId) =
        withContext(ioDispatcher) {
            val currentGameId = gameIdResolver(gameId)
            overrideSetter(currentGameId, emulatorId)
            biosGameAssignmentChanged(currentGameId, emulatorId)
        }

    /** Clear the game's Open-With override → back to Auto (default-to-installed). */
    suspend fun clearEmulatorOverride(gameId: GameId) =
        withContext(ioDispatcher) {
            val currentGameId = gameIdResolver(gameId)
            overrideClearer(currentGameId)
            biosGameAssignmentChanged(currentGameId, null)
        }

    /** Triggered after a completed library revision, never from the scanner or a row mapper. */
    suspend fun biosLibraryRevisionChanged() = withContext(ioDispatcher) { onBiosLibraryRevisionChanged() }

    /** Triggered when Settings changes a platform-level emulator assignment. */
    suspend fun biosEmulatorAssignmentChanged(platformId: PlatformId, emulatorId: EmulatorId?) =
        withContext(ioDispatcher) { biosPlatformAssignmentChanged(platformId, emulatorId) }

    /** Snapshot asynchronous facts and run the shared pure readiness decision. Never dispatches. */
    private suspend fun resolveReadiness(
        gameId: GameId,
        platformId: PlatformId,
        launchMemberEntryKey: EntryKey?,
        preferredDisc: Int,
        overrideEmulatorId: EmulatorId?,
    ): LaunchReadiness {
        // Emulator precedence: this-call override → game override → platform default → Auto.
        val storedOverride = overrideEmulatorId ?: overrideResolver(gameId) ?: platformDefault(platformId)
        val candidates = emulatorProvider.candidatesFor(platformId)
        val probeProfile = LaunchProfile(
            gameId = gameId,
            platformId = platformId,
            overrideEmulatorId = storedOverride,
            launchMemberEntryKey = launchMemberEntryKey,
            preferredDisc = preferredDisc,
            requiresBios = false,
        )
        val resolvedForSeed = buildEngine(storedOverride, emptyList(), locatorSnapshot = null)
            .resolveEmulator(probeProfile, candidates)
        val seedSnapshot = resolvedForSeed?.let {
            biosSeedBeforeRead(platformId, it.id, it.packageName)
        }
        val useExactSnapshot = when {
            resolvedForSeed == null || seedSnapshot == null -> false
            else -> matchesExactSnapshot(seedSnapshot, platformId, resolvedForSeed)
        }
        val platformRequiresBios: Boolean
        val biosProfiles: List<BiosProfile>
        if (useExactSnapshot) {
            platformRequiresBios = seedSnapshot!!.requiresBios
            biosProfiles = if (platformRequiresBios) seedSnapshot.profiles else emptyList()
        } else if (resolvedForSeed != null) {
            platformRequiresBios = EmulatorSeedRegistry.requiresBios(resolvedForSeed.packageName)
            biosProfiles = if (platformRequiresBios) {
                biosResolver(platformId, resolvedForSeed.id, resolvedForSeed.packageName)
            } else {
                emptyList()
            }
        } else {
            platformRequiresBios = false
            biosProfiles = emptyList()
        }
        val profile = LaunchProfile(
            gameId = gameId,
            platformId = platformId,
            overrideEmulatorId = storedOverride,
            launchMemberEntryKey = launchMemberEntryKey,
            preferredDisc = preferredDisc,
            requiresBios = platformRequiresBios,
        )

        // The locator read is suspend and its form depends on the emulator selected by the engine.
        val engine = buildEngine(storedOverride, biosProfiles, locatorSnapshot = null)
        val resolvedEmulator = engine.resolveEmulator(profile, candidates)
        val locatorResult = resolvedEmulator?.let {
            if (it.locatorForm == LaunchLocatorForm.NONE) {
                LaunchLocatorResult("", LaunchLocatorForm.NONE)
            } else {
                locatorResolver(gameId, launchMemberEntryKey, preferredDisc, it.locatorForm)
            }
        }
        return buildEngine(storedOverride, biosProfiles, locatorResult).resolve(profile)
    }

    /**
     * Build a pure engine over a fact snapshot. [locatorSnapshot] is the pre-fetched locator the
     * synchronous locator port returns (null for the emulator-resolution pass, before the form is known).
     */
    private fun matchesExactSnapshot(
        snapshot: BiosSeedSnapshot,
        platformId: PlatformId,
        resolvedEmulator: EmulatorProfile,
    ): Boolean {
        if (snapshot.platformId != platformId) return false
        if (snapshot.packageName != resolvedEmulator.packageName) return false
        if (snapshot.emulatorId != resolvedEmulator.id) return false
        return snapshot.profiles.all { profile ->
            profile.platformId == platformId &&
                profile.emulatorId == resolvedEmulator.id &&
                profile.packageName == resolvedEmulator.packageName
        }
    }

    private fun buildEngine(
        storedOverride: EmulatorId?,
        biosProfiles: List<BiosProfile>,
        locatorSnapshot: LaunchLocatorResult?,
    ): LaunchReadinessEngine = LaunchReadinessEngine(
        emulators = emulatorProvider,
        overrides = { storedOverride },
        bios = { _, resolvedEmulator -> biosProfiles.filter { it.appliesTo(resolvedEmulator) } },
        // LaunchLocatorProvider is a regular interface (member key + form matter for real resolvers),
        // so an anonymous object over the pre-fetched snapshot, not a SAM lambda.
        locators = object : LaunchLocatorProvider {
            override fun locatorFor(
                gameId: GameId,
                launchMemberEntryKey: EntryKey?,
                preferredDisc: Int,
                preferredForm: LaunchLocatorForm,
            ): LaunchLocatorResult? = locatorSnapshot
        },
    )
}

/**
 * DI entry point for a wired [LaunchController] — the launch analogue of
 * `com.gruning.mimir.rewrite.core.resolver.library.LibraryStore.repository(context)`. Hides the Room
 * DAO wiring, the PackageManager provider, and the real intent dispatcher behind one call.
 *
 * Usage (app):
 * ```kotlin
 * val controller = LaunchStore.controller(context)
 * val result = controller.launch(gameId, platformId)   // suspend
 * ```
 *
 * Process-cached (wraps the singleton DB). NO network (posture manifest).
 */
object LaunchStore {

    @Volatile
    private var cached: LaunchController? = null

    @Volatile
    private var cachedAndroidGameProofs: AndroidGameLaunchProofStore? = null

    /**
     * The app sets this to the per-PLATFORM default emulator lookup (its SharedPreferences-backed store).
     * core-launch can't depend on the app, so it's injected here; the built controller reads it live at each
     * launch (so setting it after the controller is built still applies). null = every platform on Auto.
     */
    @Volatile
    var platformDefaultProvider: (suspend (PlatformId) -> EmulatorId?)? = null

    /**
     * HP-2 (app-wired, like [platformDefaultProvider]): notified with the emulator package after every
     * successful DS/3DS dispatch, so the companion yields screen 2 before the emulator claims it.
     */
    @Volatile
    var dualScreenLaunchListener: ((String) -> Unit)? = null

    /**
     * App-owned resident-target preflight. Install before the singleton controller is first built.
     * The default preserves best-effort launch behavior in host and unit contexts.
     */
    @Volatile
    var launchTargetReaper: (String) -> LaunchTargetReapResult = {
        LaunchTargetReapResult.AUTHORITY_UNAVAILABLE
    }

    /** The wired [LaunchController] for this process (created on first call, then cached). */
    fun controller(context: Context): LaunchController {
        val appContext = context.applicationContext
        return cached ?: synchronized(this) {
            cached ?: build(appContext).also { cached = it }
        }
    }

    /** Process-cached explicit Android-package proof authority used by the Home native-game lane. */
    fun androidGameProofs(context: Context): AndroidGameLaunchProofStore {
        val appContext = context.applicationContext
        return cachedAndroidGameProofs ?: synchronized(this) {
            cachedAndroidGameProofs
                ?: RoomAndroidGameLaunchProofStore(MimirDatabaseFactory.get(appContext).launchProofDao())
                    .also { cachedAndroidGameProofs = it }
        }
    }

    private fun build(appContext: Context): LaunchController {
        val db = MimirDatabaseFactory.get(appContext)
        val emulatorProvider = PackageManagerEmulatorProvider(appContext)
        val biosSeeder = BiosProfileSeeder(
            rootLister = AndroidBiosLibraryRootLister(appContext),
            persistence = RoomBiosSeedPersistence(
                biosDao = db.biosProfileDao(),
                gameDao = db.gameDao(),
                sourceRootDao = db.sourceRootDao(),
                // LG01 slice 2 (tier 3). Read per seed rather than captured: the user can grant or revoke
                // between seeds, and BiosGrantStore re-checks the permission is still held on every read.
                biosGrantUris = { BiosGrantStore.grantUris(appContext) },
            ),
            emulators = emulatorProvider,
            platformDefault = { platformId -> platformDefaultProvider?.invoke(platformId) },
            // The emulators' BIOS folders sit under this user's /storage/emulated/<id>, not always the owner's 0.
            androidUserId = androidUserIdOf(android.os.Process.myUid()),
        )
        val locatorGatherer = RoomLaunchLocatorGatherer(
            gameDao = db.gameDao(),
            generatedM3uWriter = GeneratedM3uWriter(
                java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOCUMENTS,
                    ),
                    "Mimir/Playlists",
                ),
            ),
        )
        val overrideGatherer = RoomOverrideGatherer(
            db.emulatorOverrideDao(),
            db.gameIdentityDao(),
        )
        return LaunchController(
            emulatorProvider = emulatorProvider,
            locatorResolver = locatorGatherer::resolve,
            overrideResolver = overrideGatherer::resolve,
            overrideSetter = overrideGatherer::set,
            overrideClearer = overrideGatherer::clear,
            biosResolver = RoomBiosGatherer(db.biosProfileDao())::resolve,
            proofRecorder = RoomLaunchProofRecorder(
                db.launchProofDao(),
                db.gameIdentityDao(),
            )::record,
            dispatcher = IntentLaunchDispatcher(
                context = appContext,
                preDispatchReap = { packageName -> launchTargetReaper(packageName) },
            ),
            platformDefault = { platformId -> platformDefaultProvider?.invoke(platformId) },
            dualScreenLaunchNotifier = { pkg -> dualScreenLaunchListener?.invoke(pkg) },
            biosSeedBeforeRead = biosSeeder::ensureSeeded,
            biosGameAssignmentChanged = biosSeeder::emulatorAssignmentChanged,
            biosPlatformAssignmentChanged = biosSeeder::emulatorAssignmentChanged,
            onBiosLibraryRevisionChanged = biosSeeder::libraryRevisionChanged,
            gameIdResolver = { gameId ->
                GameId(db.gameIdentityDao().resolveCurrentGameId(gameId.value))
            },
        )
    }
}
