package com.gruning.mimir.rewrite.core.companionnative

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

internal class SecondaryDisplayAvailabilityObserver(
    initialDisplayId: Int?,
    private val displayIdSource: () -> Int?,
    private val publishDisplayId: (Int?) -> Unit,
) {
    private var publishedDisplayId = initialDisplayId

    fun refresh(): Boolean {
        val displayId = displayIdSource()
        if (displayId == publishedDisplayId) return false
        publishedDisplayId = displayId
        publishDisplayId(displayId)
        return true
    }
}

/**
 * Display-targeted Android package launcher + launched-by-Mimir session registry.
 *
 * Important truth boundary: Android does not expose a reliable third-party "all recent apps per display"
 * API without special appops/OEM hooks. This registry therefore tracks apps Mimir launches/moves and labels
 * the state honestly. That is enough for the rewrite FE drawer to expose its launch/swap request path without
 * hallucinating OS-global recents.
 */
class AndroidSecondaryTaskRegistry(
    context: Context,
    private val secondaryDisplaySelector: SecondaryDisplaySelector,
    private val onSecondaryHostLaunchStarting: (packageName: String, displayId: Int, launchMode: String) -> String? =
        { _, _, _ -> null },
    private val onSecondaryHostLaunchFailed: (
        packageName: String,
        displayId: Int,
        operationId: String?,
        reason: String,
    ) -> Unit = { _, _, _, _ -> },
    private val closeUnconfirmedTimeoutMs: Long = DEFAULT_CLOSE_UNCONFIRMED_TIMEOUT_MS,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val operationIdFactory: () -> UUID = UUID::randomUUID,
    /**
     * A certified privileged force-stop whose liveness is read back by the authority that ran it.
     *
     * It replaced a `(String) -> Boolean` "was it accepted" seam after the AYN Thor incident of
     * 2026-09-11: the firmware whitelists some packages, so an accepted force-stop can leave the app
     * running, visible in Android Recents, and re-openable from there.
     */
    private val privilegedStop: (packageName: String) -> SecondaryPrivilegedCloseOutcome = {
        SecondaryPrivilegedCloseOutcome.UNAVAILABLE
    },
    /** Privileged eviction of one tracked task id; true only when the authority reported success. */
    private val privilegedRemoveTask: (taskId: Int) -> Boolean = { false },
    /**
     * Privileged lookup of the task a package holds on a display, for a close that has no tracked id.
     * Null means "could not be proved", which the close reads as "no task to remove".
     */
    private val privilegedResolveTaskId: (packageName: String, displayId: Int) -> Int? = { _, _ -> null },
    /**
     * Privileged process presence for ONE package, read back by the authority that ran the probe.
     *
     * Only [SecondaryPackageLiveness.ABSENT] is evidence; UNKNOWN (no authority, no reply, no
     * `pidof`, a timeout) and ALIVE leave the tab exactly as it is. Blocking: one privileged transaction
     * per call, so [reconcileLiveness] calls it off the main thread.
     */
    private val privilegedLiveness: (packageName: String) -> SecondaryPackageLiveness = {
        SecondaryPackageLiveness.UNKNOWN
    },
    /**
     * Whether THIS process has a Bottom game launch still starting for one package.
     *
     * The registry files a game's row at dispatch, before the emulator has a process, so a `pidof` miss for a
     * launch that is only starting is not a game that has gone. A GAME row this answers true for is neither
     * asked about nor retired by [reconcileLiveness]. It is read before the probe and again on the main thread
     * at retirement; a read that throws counts as true. APP rows never consult it.
     */
    private val bottomGameLaunchInFlight: (packageName: String) -> Boolean = { false },
    /**
     * Settles what a GAME row's ✕ settles besides the row itself, for the GAME rows one [reconcileLiveness] retired:
     * the Bottom game session this process still holds for each emulator, if it holds one, and otherwise the
     * open play-time measurement a dead process left for that game. Called on the main thread once per reconcile that
     * retired a GAME row, after the rows are gone and `tabs.v1` is written, with every GAME row it retired and every
     * row still tracked: a game two tabs name cannot be attributed to either emulator.
     */
    private val onGameTabsRetired: (retired: List<AppEntry>, stillTracked: List<AppEntry>) -> Unit = { _, _ -> },
    /**
     * R6-P0-02 S4: fires once, unconditionally, at the end of every completed [reconcileLiveness] call --
     * whether or not it retired any row.
     *
     * A Main-placed parked DS/3DS game has no Tabs row at all (it never went through the secondary
     * display), so [onGameTabsRetired] above never runs for it; this is the signal a caller reuses to
     * separately ask whether that game's OWN task/process is still alive, without adding a new poll.
     */
    private val onLivenessReconciled: () -> Unit = {},
    private val persistTabs: Boolean = true,
) : SecondaryTaskRegistry {
    private val appContext = context.applicationContext
    private val tabsPrefs = if (persistTabs) {
        appContext.getSharedPreferences("mimir_secondary_tabs", Context.MODE_PRIVATE)
    } else {
        null
    }
    private val packageManager = appContext.packageManager
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ledger = SecondaryTaskLedger(operationIdFactory)
    private val closeTimeouts = mutableMapOf<UUID, Runnable>()
    private val _state = MutableStateFlow(buildState(secondaryDisplayId()))
    private val displayAvailability = SecondaryDisplayAvailabilityObserver(
        initialDisplayId = _state.value.secondaryDisplayId,
        displayIdSource = ::secondaryDisplayId,
        publishDisplayId = ::publishDisplayAvailability,
    )
    private val packageRemovalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_PACKAGE_REMOVED ||
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) {
                return
            }
            val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
            observeClose(SecondaryCloseObservation.PackageRemoved(packageName))
        }
    }

    init {
        require(closeUnconfirmedTimeoutMs > 0L) { "closeUnconfirmedTimeoutMs must be positive" }
        tabsPrefs?.let { prefs ->
            ledger.restore(
                SecondaryTabsCodec.decode(prefs.getString("tabs.v1", null).orEmpty()).filter { entry ->
                    entry.packageName != appContext.packageName &&
                        runCatching { packageManager.getPackageInfo(entry.packageName, 0) }.isSuccess
                },
            )
            Log.i(TAG, "restored ${ledger.ordered().size} Bottom tabs")
            publish()
        }
        // The process-owned selector is the one DisplayManager listener. Reconcile immediately after
        // subscription so the registry starts from the same sampled snapshot as every other consumer.
        secondaryDisplaySelector.addListener { displayAvailability.refresh() }
        val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply { addDataScheme("package") }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(packageRemovalReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(packageRemovalReceiver, packageFilter)
            }
        }.onFailure { Log.w(TAG, "failed to observe package-removal close evidence", it) }
        mainHandler.post { displayAvailability.refresh() }
    }

    override val state: StateFlow<SecondaryDisplayState> = _state.asStateFlow()

    override fun launchOnSecondary(packageName: String): Boolean {
        val displayId = secondaryDisplayId() ?: return false
        // Reported 2026-07-10: opening an app from Tabs could hang on its loading screen. The old RET
        // defense no-op'd a relaunch of any still-TRACKED package — but tracked ≠ foreground. After an
        // edge-back the app is backgrounded yet still tracked, so a deliberate re-tap launched NOTHING and
        // the drawer's "Opening …" lock never cleared (device-confirmed: no "launch target" log on the
        // wedged tap). A user tap ALWAYS launches now — the SECONDARY_APP host brings an existing task
        // forward, so re-launching a visible app is harmless; the RET feedback loop that guard once patched
        // is fixed at its true source (the return monitor's own-host className filter).
        return launchOnDisplay(packageName, displayId, CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP)
    }

    /** Dispatch a launch whose process coordinator operation was prepared before the drawer lock published. */
    fun launchOnSecondary(
        packageName: String,
        operationId: UUID,
        expectedDisplayId: Int,
        primaryOriginDestination: String,
        primaryOriginArguments: Map<String, String>,
    ): Boolean {
        if (operationId == UUID(0L, 0L)) return false
        if (primaryOriginDestination.isBlank()) {
            onSecondaryHostLaunchFailed(
                packageName,
                expectedDisplayId,
                operationId.toString(),
                "secondary_launch_origin_invalid",
            )
            return false
        }
        val displayId = secondaryDisplayId()
        if (displayId == null || displayId != expectedDisplayId) {
            onSecondaryHostLaunchFailed(
                packageName,
                expectedDisplayId,
                operationId.toString(),
                "secondary_display_unavailable",
            )
            return false
        }
        return launchOnDisplay(
            packageName = packageName,
            displayId = displayId,
            launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
            preparedOperationId = operationId,
            primaryOriginDestination = primaryOriginDestination,
            primaryOriginArguments = primaryOriginArguments,
        )
    }

    /**
     * Launch an already-built ROM Intent through the exact secondary return host.
     *
     * The library identity is optional because the caller that owns the Intent does not always own the
     * library row; a GAME tab with none still appears, named by its emulator. [noteGameTab] supplies it
     * afterwards for a caller that resolves the row later.
     */
    fun launchOnSecondary(
        targetIntent: Intent,
        operationId: UUID,
        expectedDisplayId: Int,
        primaryOriginDestination: String,
        primaryOriginArguments: Map<String, String>,
        gameTitle: String? = null,
        gameId: String? = null,
        gameArtRef: String? = null,
    ): Boolean {
        if (operationId == UUID(0L, 0L)) return false
        val packageName = (targetIntent.component?.packageName ?: targetIntent.`package`)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return false
        if (primaryOriginDestination.isBlank()) {
            onSecondaryHostLaunchFailed(packageName, expectedDisplayId, operationId.toString(), "secondary_launch_origin_invalid")
            return false
        }
        val displayId = secondaryDisplayId()
        if (displayId == null || displayId != expectedDisplayId) {
            onSecondaryHostLaunchFailed(packageName, expectedDisplayId, operationId.toString(), "secondary_display_unavailable")
            return false
        }
        return launchOnDisplay(
            packageName = packageName,
            displayId = displayId,
            launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME,
            preparedOperationId = operationId,
            primaryOriginDestination = primaryOriginDestination,
            primaryOriginArguments = primaryOriginArguments,
            targetIntent = Intent(targetIntent),
            gameTitle = gameTitle,
            gameId = gameId,
            gameArtRef = gameArtRef,
        )
    }

    /**
     * Attach library identity to the GAME tab for [packageName], after the dispatch that created it.
     *
     * the Bottom game had no tab at all. The row is now created at dispatch from
     * the Intent alone, so the title the player reads can arrive a moment later — from the session that
     * resolved it — without the row waiting on it. Returns false when no GAME tab holds that package.
     */
    fun noteGameTab(
        packageName: String,
        title: String? = null,
        gameId: String? = null,
        artRef: String? = null,
    ): Boolean {
        val clean = packageName.trim().takeIf(String::isNotEmpty) ?: return false
        if (!ledger.noteGameIdentity(clean, title, gameId, artRef)) return false
        Log.i(TAG, "game tab identity pkg=$clean game=${gameId ?: "unknown"}")
        publish()
        return true
    }

    override fun swapToPrimary(packageName: String, operationId: UUID): SecondarySwapResult? =
        requestSwap(packageName, Display.DEFAULT_DISPLAY, operationId)

    override fun swapToSecondary(packageName: String): SecondarySwapResult? =
        secondaryDisplayId()?.let { displayId -> requestSwap(packageName, displayId) }

    override fun closeAll(): SecondaryCloseResult {
        return requestClose(ledger.values().map(AppEntry::packageName))
    }

    override fun closeOne(packageName: String): SecondaryCloseResult = requestClose(listOf(packageName.trim()))

    override fun observeClose(observation: SecondaryCloseObservation): Boolean {
        val operationId = ledger.entry(observation.packageName)?.closeOperationId
        val accepted = ledger.observe(observation)
        if (!accepted) return false
        operationId?.let(::cancelCloseTimeout)
        publish()
        return true
    }

    override fun forget(packageName: String): Boolean {
        val entry = ledger.entry(packageName) ?: return true
        val operationId = entry.closeOperationId
        val forgotten = ledger.forget(packageName)
        if (!forgotten) return false
        operationId?.let(::cancelCloseTimeout)
        publish()
        return true
    }

    override fun recentAppIcons(limit: Int): List<AppEntry> =
        ledger.values().sortedByDescending { it.lastSeenAtMs }.take(limit.coerceAtLeast(0))

    /**
     * Record the secondary-display task id observed for a tracked package, so a close that survives
     * the privileged force-stop can evict that exact task.
     *
     * **Nothing calls this yet, and that is stated rather than hidden.** Android gives a non-privileged
     * caller only its OWN tasks from `getRunningTasks`, so the launched app's task id cannot be read in
     * this process; until a privileged resolver supplies one, [SecondaryCloseRequest.taskId] stays null
     * and a surviving package is logged `close unverified reason=no_task_id`.
     */
    fun noteSecondaryTaskId(packageName: String, taskId: Int): Boolean {
        val noted = ledger.noteTaskId(packageName.trim(), taskId)
        if (noted) Log.i(TAG, "tracked secondary task pkg=${packageName.trim()} taskId=$taskId")
        return noted
    }

    /**
     * Retires every OPEN tab whose package the privileged authority proved has no live process.
     *
     * Observed on Thor: an app stopped outside Mimir -- `am force-stop`, a Recents swipe, a
     * crash -- produces none of the close evidence this registry otherwise accepts, so its row stayed
     * OPEN, stayed in `tabs.v1`, and came back after every restart. Each candidate is asked once through
     * [privilegedLiveness]; only ABSENT retires it, through [SecondaryCloseObservation.ProcessAbsentVerified].
     * ALIVE and UNKNOWN change nothing.
     *
     * GAME rows are asked too (Thor, 2026-09-12). Minish Cap's row outlived RetroArch: Mimir's process died
     * under the game, the process that saw the quit owned no session to settle, and this reconcile never
     * asked about a GAME row. A GAME row whose launch is still starting ([bottomGameLaunchInFlight]) is not
     * asked, and the GAME rows a reconcile retires get what their ✕ settles besides the rows ([onGameTabsRetired]).
     *
     * Candidates are the published [state]'s rows (the drawer's own 24-row window), filtered by
     * [secondaryTabsToRetire] BEFORE anything is asked, so rows a close already owns are never probed. The
     * probes run on [Dispatchers.IO]. The ledger is not synchronized, so the retirement is written on the
     * main thread, like the task-id notes, by [SecondaryTaskLedger.retireProbedAbsent]: a row a launch
     * re-filed after it was probed is not retired by that older answer, and neither is a GAME row whose
     * launch started since. There is no polling: the callers are the Tabs request and the one-time publish
     * of the close authority. Logs one line per call that completes; a call cancelled before it completes
     * logs nothing.
     */
    suspend fun reconcileLiveness() {
        val rows = _state.value.recents
        val answers = LinkedHashMap<String, SecondaryPackageLiveness>()
        val launchInFlight: (String) -> Boolean = { packageName ->
            runCatching { bottomGameLaunchInFlight(packageName) }.getOrDefault(true)
        }
        val absent = withContext(Dispatchers.IO) {
            secondaryTabsToRetire(rows, launchInFlight) { packageName ->
                (runCatching { privilegedLiveness(packageName) }.getOrNull() ?: SecondaryPackageLiveness.UNKNOWN)
                    .also { answers[packageName] = it }
            }
        }
        val retired = withContext(Dispatchers.Main.immediate) {
            val gone = ledger.retireProbedAbsent(absent, launchInFlight)
            if (gone.isNotEmpty()) publish()
            gone.filter { it.kind == SecondaryTaskKind.GAME }
                .takeIf { it.isNotEmpty() }
                ?.let { games -> runCatching { onGameTabsRetired(games, ledger.values()) } }
            gone
        }
        Log.i(
            LIVENESS_LOG_TAG,
            "tabs liveness checked=${answers.size} " +
                "retired=${retired.joinToString(",") { it.packageName }.ifEmpty { "none" }} " +
                "unknown=${answers.values.count { it == SecondaryPackageLiveness.UNKNOWN }} " +
                "retiredKinds=${retired.joinToString(",") { it.kind.name }.ifEmpty { "none" }}",
        )
        runCatching { onLivenessReconciled() }
    }

    private fun requestSwap(
        packageName: String,
        targetDisplayId: Int,
        primaryOperationId: UUID? = null,
    ): SecondarySwapResult? {
        val clean = packageName.trim().takeIf(String::isNotEmpty) ?: return null
        // A reverse Main→Bottom swap is authorized by the process-owned Main session at the caller,
        // so a package opened directly on Main may have no secondary ledger row yet. Keep the stricter
        // ledger requirement for Bottom→Main, where the row is the display-2 ownership evidence.
        if (targetDisplayId == Display.DEFAULT_DISPLAY) {
            val tracked = ledger.entry(clean) ?: run {
                // P13: an untracked package (no secondary ledger row) refused silently beside the
                // GAME-kind refusal two lines below, which already has its own line.
                Log.i(TAG, "swap refused pkg=$clean reason=not_tracked")
                return null
            }
            // Moving an emulator to Main is a bare LAUNCHER resume: it discards the ROM payload the game
            // was dispatched with. The caller's swap menu blocks this from the lease's launch mode, but a
            // game REOPENED from its tab holds a `secondary_app` lease -- the reopen path is an ordinary
            // app relaunch on purpose -- so the lease can no longer carry the whole answer. The row's KIND
            // can, and it is the same fact at the layer that owns it.
            if (tracked.kind == SecondaryTaskKind.GAME) {
                Log.i(TAG, "swap refused pkg=$clean reason=game_tab_cannot_resume_as_launcher")
                return null
            }
        }
        val launchMode = if (targetDisplayId == Display.DEFAULT_DISPLAY) {
            CompanionReturnHostContract.LAUNCH_MODE_RESUME
        } else {
            CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER
        }
        val operationId = swapOperationId(
            targetDisplayId = targetDisplayId,
            localOperationId = { primaryOperationId ?: operationIdFactory() },
            prepareSecondaryOperation = {
                onSecondaryHostLaunchStarting(clean, targetDisplayId, launchMode)
            },
        ) ?: run {
            // P13: a null ledger.entry/root mismatch inside swapOperationId refused silently.
            Log.i(TAG, "swap refused pkg=$clean reason=operation_unavailable")
            return null
        }
        val accepted = launchOnDisplay(
            packageName = clean,
            displayId = targetDisplayId,
            launchMode = launchMode,
            preparedOperationId = operationId,
        )
        Log.i(
            TAG,
            "swap dispatch operation=$operationId pkg=$clean targetDisplay=$targetDisplayId accepted=$accepted confirmed=false",
        )
        return SecondarySwapResult(clean, operationId, targetDisplayId, accepted)
    }

    private fun launchOnDisplay(
        packageName: String,
        displayId: Int,
        launchMode: String = CompanionReturnHostContract.LAUNCH_MODE_RESUME,
        preparedOperationId: UUID? = null,
        primaryOriginDestination: String? = null,
        primaryOriginArguments: Map<String, String> = emptyMap(),
        targetIntent: Intent? = null,
        gameTitle: String? = null,
        gameId: String? = null,
        gameArtRef: String? = null,
    ): Boolean {
        val clean = packageName.trim()
        if (clean.isEmpty()) {
            if (displayId != Display.DEFAULT_DISPLAY && preparedOperationId != null) {
                onSecondaryHostLaunchFailed(
                    packageName,
                    displayId,
                    preparedOperationId.toString(),
                    "secondary_launch_package_invalid",
                )
            }
            return false
        }
        val operationId = preparedOperationId?.toString() ?: if (displayId == Display.DEFAULT_DISPLAY) {
            null
        } else {
            onSecondaryHostLaunchStarting(clean, displayId, launchMode) ?: return false
        }
        val intent = if (displayId == Display.DEFAULT_DISPLAY) {
            if (resolveLaunchIntentForPackage(clean) == null) return false
            primaryTransferHostIntent(clean, preparedOperationId ?: return false)
        } else {
            val gamePayload = if (launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
                val payloadPackage = targetIntent?.component?.packageName ?: targetIntent?.`package`
                targetIntent?.takeIf { payloadPackage == clean }
            } else {
                null
            }
            if (launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME && gamePayload == null) {
                onSecondaryHostLaunchFailed(clean, displayId, operationId, "secondary_game_payload_invalid")
                return false
            }
            if (launchMode != CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME &&
                resolveLaunchIntentForPackage(clean) == null
            ) {
                onSecondaryHostLaunchFailed(
                    clean,
                    displayId,
                    operationId,
                    "secondary_launch_not_found",
                )
                return false
            }
            companionReturnHostIntent(
                packageName = clean,
                launchMode = launchMode,
                operationId = operationId ?: return false,
                primaryOriginDestination = primaryOriginDestination,
                primaryOriginArguments = primaryOriginArguments,
                targetIntent = gamePayload,
            )
        }
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
        return runCatching {
            appContext.startActivity(intent, options.toBundle())
            // startActivity success is dispatch-only. A transfer preserves the last tracked placement until
            // certified operation evidence arrives; it never claims "On main screen" from dispatch alone.
            val transfer = launchMode == CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER ||
                displayId == Display.DEFAULT_DISPLAY
            // A game launch used to be excluded from this ledger entirely, to stop Tabs offering a bare
            // launcher swap for the ROM host. The exclusion cost the player the only way back to a game
            // they swiped away from , so the row exists now and carries its KIND —
            // which is what keeps the two apart where it matters, instead of hiding one of them.
            noteLaunchDispatched(
                packageName = clean,
                displayId = displayId,
                preserveTrackedDisplay = transfer,
                kind = secondaryLedgerKindFor(launchMode),
                gameTitle = gameTitle,
                gameId = gameId,
                gameArtRef = gameArtRef,
            )
            true
        }.onFailure {
            Log.w("MimirCompanionNative", "display launch failed pkg=$clean display=$displayId", it)
            if (displayId != Display.DEFAULT_DISPLAY) {
                onSecondaryHostLaunchFailed(clean, displayId, operationId, "secondary_launch_failed")
            }
        }.getOrDefault(false)
    }

    private fun companionReturnHostIntent(
        packageName: String,
        launchMode: String,
        operationId: String,
        primaryOriginDestination: String?,
        primaryOriginArguments: Map<String, String>,
        targetIntent: Intent? = null,
    ): Intent = Intent().setClassName(
            appContext.packageName,
            CompanionReturnHostContract.activityClassName(appContext.packageName),
        ).putExtra(CompanionReturnHostContract.EXTRA_TARGET_PACKAGE, packageName)
            .putExtra(CompanionReturnHostContract.EXTRA_LAUNCH_MODE, launchMode)
            .putExtra(CompanionReturnHostContract.EXTRA_OPERATION_ID, operationId)
            .apply { targetIntent?.let { putExtra(CompanionReturnHostContract.EXTRA_TARGET_INTENT, Intent(it)) } }
            .putExtra(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_DESTINATION, primaryOriginDestination)
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_KEYS,
                ArrayList(primaryOriginArguments.keys),
            )
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_VALUES,
                ArrayList(primaryOriginArguments.values),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    private fun primaryTransferHostIntent(packageName: String, operationId: UUID): Intent =
        Intent()
            .setClassName(
                appContext.packageName,
                PrimaryTransferHostContract.activityClassName(appContext.packageName),
            )
            .putExtra(PrimaryTransferHostContract.EXTRA_TARGET_PACKAGE, packageName)
            .putExtra(PrimaryTransferHostContract.EXTRA_OPERATION_ID, operationId.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    private fun resolveLaunchIntentForPackage(packageName: String): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let { return it }
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(query, 0)
        val activityName = matches.firstOrNull()?.activityInfo?.name ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            component = ComponentName(packageName, activityName)
        }
    }

    private fun noteLaunchDispatched(
        packageName: String,
        displayId: Int,
        preserveTrackedDisplay: Boolean,
        kind: SecondaryTaskKind = SecondaryTaskKind.APP,
        gameTitle: String? = null,
        gameId: String? = null,
        gameArtRef: String? = null,
    ) {
        ledger.entry(packageName)?.closeOperationId?.let(::cancelCloseTimeout)
        val existing = ledger.entry(packageName)
        ledger.noteLaunched(
            AppEntry(
                packageName = packageName,
                label = existing?.label ?: labelFor(packageName),
                displayId = if (preserveTrackedDisplay) existing?.displayId else displayId,
                lastSeenAtMs = System.currentTimeMillis(),
                kind = kind,
                gameTitle = gameTitle?.trim()?.takeIf(String::isNotEmpty),
                gameId = gameId?.trim()?.takeIf(String::isNotEmpty),
                gameArtRef = gameArtRef?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
        publish()
    }

    private fun requestClose(packageNames: List<String>): SecondaryCloseResult {
        val names = packageNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        val trackedRequests = names.mapNotNull { ledger.requestClose(it, elapsedRealtimeMs()) }
        val trackedPackages = trackedRequests.mapTo(HashSet()) { it.packageName }
        val requests = trackedRequests + names.filterNot { it in trackedPackages }.map {
            SecondaryCloseRequest(it, UUID.randomUUID())
        }
        if (requests.isEmpty()) {
            return SecondaryCloseResult(
                requestedPackages = emptyList(),
                operationIdsByPackage = emptyMap(),
            )
        }

        // Publish CLOSE_REQUESTED_UNVERIFIED for tracked requests before dispatch. Dispatch success,
        // process-list absence, and UsageStats background never advance this ledger to a terminal state.
        publish()
        requests.forEach { request ->
            val resolution = resolveSecondaryClose(
                request = request,
                stop = { packageName ->
                    runCatching { privilegedStop(packageName) }
                        .getOrDefault(SecondaryPrivilegedCloseOutcome.UNAVAILABLE)
                },
                removeTask = { taskId -> runCatching { privilegedRemoveTask(taskId) }.getOrDefault(false) },
                resolveTaskId = ::resolveSecondaryTaskId,
                killBackground = { packageName ->
                    runCatching { activityManager?.killBackgroundProcesses(packageName) }
                },
            )
            val evidence = if (resolution.closeVerified) "verified" else "unverified"
            val trackedTask = request.taskId?.toString() ?: "none"
            Log.i(
                TAG,
                "close package=${request.packageName} taskId=$trackedTask $evidence reason=${resolution.reason}",
            )
            if (request.packageName !in trackedPackages) return@forEach
            // A verified close is terminal evidence and retires the row; everything weaker keeps the
            // existing CLOSE_REQUESTED_UNVERIFIED -> CLOSE_UNCONFIRMED timeout exactly as it was.
            if (resolution.closeVerified && ledger.markCloseVerified(request.operationId)) {
                publish()
            } else {
                scheduleCloseUnconfirmed(request)
            }
        }
        return SecondaryCloseResult(
            requestedPackages = requests.map(SecondaryCloseRequest::packageName),
            operationIdsByPackage = requests.associate { it.packageName to it.operationId },
        )
    }

    /**
     * Asks the privileged authority which task this package holds on the secondary display, and
     * remembers the answer so a retry or a second close does not pay for the lookup again.
     *
     * With no secondary display there is no display-4 task to ask about, so nothing is asked.
     */
    private fun resolveSecondaryTaskId(packageName: String): Int? {
        val displayId = secondaryDisplayId() ?: return null
        return runCatching { privilegedResolveTaskId(packageName, displayId) }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?.also { ledger.noteTaskId(packageName, it) }
    }

    private fun scheduleCloseUnconfirmed(request: SecondaryCloseRequest) {
        val timeout = Runnable {
            closeTimeouts.remove(request.operationId)
            if (ledger.markCloseUnconfirmed(request.operationId)) publish()
        }
        closeTimeouts[request.operationId] = timeout
        mainHandler.postDelayed(timeout, closeUnconfirmedTimeoutMs)
    }

    private fun cancelCloseTimeout(operationId: UUID) {
        closeTimeouts.remove(operationId)?.let(mainHandler::removeCallbacks)
    }

    private fun labelFor(packageName: String): String =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    private fun publish() {
        tabsPrefs?.let { prefs ->
            prefs.edit().putString("tabs.v1", ledger.persistedTabsDocument()).apply()
        }
        _state.value = buildState(_state.value.secondaryDisplayId)
    }

    private fun publishDisplayAvailability(displayId: Int?) {
        _state.value = buildState(displayId)
    }

    private fun buildState(displayId: Int?): SecondaryDisplayState =
        SecondaryDisplayState(
            secondaryDisplayId = displayId,
            // No current certified display-aware observation source. Tracked target display is useful drawer
            // metadata, but it is not foreground ownership evidence.
            foregroundByDisplay = emptyMap(),
            recents = recentAppIcons(24),
        )

    private fun secondaryDisplayId(): Int? =
        secondaryDisplaySelector.snapshot.value.selectedId

    private companion object {
        const val TAG = "MimirCompanionNative"

        /** The reconcile line sits on the runtime tag beside `tabs requested`, so one filter reads both. */
        const val LIVENESS_LOG_TAG = "MimirCompanionRuntime"
        const val DEFAULT_CLOSE_UNCONFIRMED_TIMEOUT_MS = 5_000L
    }
}

/**
 * What a privileged stop proved, in the vocabulary this module can act on.
 *
 * The app module's privileged gateways carry the richer result (not allowed / no authority / failed);
 * all of those arrive here as [UNAVAILABLE], because the registry's decision is the same for each: try
 * the best-effort kill and claim nothing.
 */
enum class SecondaryPrivilegedCloseOutcome {
    /** Verified by the authority: no live process for the package remains. */
    STOPPED,

    /** The stop ran and the package is still alive -- a whitelisted force-stop looks exactly like this. */
    STILL_ALIVE,

    /** Accepted by an authority with no liveness probe. Proves dispatch only. */
    REQUEST_UNVERIFIED,

    /** No privileged authority served the stop. */
    UNAVAILABLE,
}

/**
 * What a privileged process-presence probe proved about ONE package.
 *
 * Only [ABSENT] is evidence a tab may act on. [UNKNOWN] covers every way a probe can fail to answer --
 * no authority, a package the stop policy refuses, no reply, a device without `pidof`, a timeout -- and
 * is never read as absence.
 */
enum class SecondaryPackageLiveness {
    /** The probe found a live process under the package name. */
    ALIVE,

    /** The probe ran `pidof` and found no process under the package name. */
    ABSENT,

    /** No answer. Keeps the tab exactly as it is. */
    UNKNOWN,
}

/** The close escalation's outcome, and the evidence word that goes in the log line. */
internal enum class SecondaryCloseResolution(val closeVerified: Boolean, val reason: String) {
    STOPPED(closeVerified = true, reason = "process_absent"),
    TASK_REMOVED(closeVerified = true, reason = "task_removed"),
    TASK_REMOVAL_FAILED(closeVerified = false, reason = "task_removal_failed"),
    NO_TASK_ID(closeVerified = false, reason = "no_task_id"),
    ACCEPTED_UNVERIFIED(closeVerified = false, reason = "accepted_unverified"),
    BEST_EFFORT(closeVerified = false, reason = "authority_unavailable"),
}

/**
 * The close sequence, as one pure decision so it can be tested without an Android registry.
 *
 * STOPPED finishes. A survivor is evicted by task id -- the tracked one, or the one the privileged
 * authority can still resolve for that package on the secondary display -- which is the only step
 * that removes a whitelisted app from display 4 and from Android Recents. [resolveTaskId] is asked
 * ONLY on the survivor path: a lookup costs a privileged transaction, and a close that already
 * proved the process is gone has nothing to evict. With no id from either source there is nothing to
 * remove, so the close falls back to the best-effort background kill and says so.
 */
internal fun resolveSecondaryClose(
    request: SecondaryCloseRequest,
    stop: (String) -> SecondaryPrivilegedCloseOutcome,
    removeTask: (Int) -> Boolean,
    resolveTaskId: (String) -> Int?,
    killBackground: (String) -> Unit,
): SecondaryCloseResolution = when (stop(request.packageName)) {
    SecondaryPrivilegedCloseOutcome.STOPPED -> SecondaryCloseResolution.STOPPED
    SecondaryPrivilegedCloseOutcome.STILL_ALIVE -> {
        val taskId = request.taskId ?: resolveTaskId(request.packageName)
        when {
            taskId == null -> {
                killBackground(request.packageName)
                SecondaryCloseResolution.NO_TASK_ID
            }
            removeTask(taskId) -> SecondaryCloseResolution.TASK_REMOVED
            else -> {
                killBackground(request.packageName)
                SecondaryCloseResolution.TASK_REMOVAL_FAILED
            }
        }
    }
    SecondaryPrivilegedCloseOutcome.REQUEST_UNVERIFIED -> SecondaryCloseResolution.ACCEPTED_UNVERIFIED
    SecondaryPrivilegedCloseOutcome.UNAVAILABLE -> {
        killBackground(request.packageName)
        SecondaryCloseResolution.BEST_EFFORT
    }
}

/**
 * The Tabs rows a liveness reconcile retires, as one pure decision so it can be tested without Android.
 *
 * Only OPEN rows are ASKED about: a row a close request already owns keeps its own evidence path. A GAME row
 * whose Bottom game launch is still starting ([bottomGameLaunchInFlight]) is not asked either, because its
 * emulator may not have a process yet; an APP row never consults that read. Only an ABSENT answer retires a
 * row. ALIVE keeps it -- including a package the firmware refused to force-stop, which is exactly why no
 * stop's exit code is consulted here -- and UNKNOWN keeps it too, because "could not ask" is not "not
 * running". Each package is asked at most once.
 */
internal fun secondaryTabsToRetire(
    rows: List<AppEntry>,
    bottomGameLaunchInFlight: (packageName: String) -> Boolean = { false },
    liveness: (packageName: String) -> SecondaryPackageLiveness,
): List<AppEntry> = rows
    .asSequence()
    .filter { it.closeState == SecondaryTaskCloseState.OPEN }
    .filterNot { it.kind == SecondaryTaskKind.GAME && bottomGameLaunchInFlight(it.packageName) }
    .distinctBy(AppEntry::packageName)
    .filter { liveness(it.packageName) == SecondaryPackageLiveness.ABSENT }
    .toList()

/**
 * Which kind of tab a dispatch in this launch mode becomes.
 *
 * Replaces `shouldRecordSecondaryLaunchInAppLedger`, which answered "does this become a tab at all" and
 * said no for a game. Every Bottom dispatch is a tab; the mode decides only what the tab IS.
 */
internal fun secondaryLedgerKindFor(launchMode: String): SecondaryTaskKind =
    if (launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
        SecondaryTaskKind.GAME
    } else {
        SecondaryTaskKind.APP
    }

internal fun swapOperationId(
    targetDisplayId: Int,
    localOperationId: () -> UUID,
    prepareSecondaryOperation: () -> String?,
): UUID? {
    val operationId = if (targetDisplayId == Display.DEFAULT_DISPLAY) {
        localOperationId()
    } else {
        prepareSecondaryOperation()?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
    }
    return operationId?.takeUnless { it == UUID(0L, 0L) }
}
