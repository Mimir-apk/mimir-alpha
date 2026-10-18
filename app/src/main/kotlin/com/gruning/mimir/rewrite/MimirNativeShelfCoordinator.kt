package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.launch.LaunchStore
import com.gruning.mimir.rewrite.core.launch.RetroArchCoreCheck
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddCandidate
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddReason
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.nativeShelfKindOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class MimirNativeShelfLoadState(
    val snapshot: NativeShelfSnapshot = NativeShelfSnapshot(),
    val loaded: Boolean = false,
    /** The shelf revision the scan behind [snapshot] ran for; -1 until one has. A hide is judged by the scan for its own. */
    val revision: Int = -1,
)

/** Native package shelf state and effects, isolated from the verifier-sensitive app root. */
internal class MimirNativeShelfCoordinator(
    val libraryViewModel: LibraryViewModel,
    val loadState: MimirNativeShelfLoadState,
    val pendingHidePackage: String?,
    val addPickerMode: LibraryShelfMode?,
    val addCandidates: List<NativeShelfAddCandidate>,
    val addSearchQuery: String,
    val addKeyboardOpen: Boolean,
    val launchReturnRestoring: Boolean,
    val launchPackage: (String) -> Unit,
    val toggleFavorite: (String) -> Unit,
    val requestHidePackage: (String) -> Unit,
    val cancelHidePackage: () -> Unit,
    val confirmHidePackage: () -> Unit,
    val openAddPicker: (LibraryShelfMode) -> Unit,
    val moveAddPickerFocus: (ControllerAction) -> Boolean,
    val setAddSearchQuery: (String) -> Unit,
    val openAddSearch: () -> Unit,
    val closeAddSearch: () -> Unit,
    val chooseAddPackage: (String) -> Unit,
    val dismissAddPicker: () -> Unit,
    val scheduleRefreshOnResume: () -> Unit,
    val consumeLaunchReturnOnResume: () -> Unit,
    val supersedeLaunchReturn: () -> Unit,
) {
    val snapshot: NativeShelfSnapshot
        get() = loadState.snapshot

    fun rowsForMode(mode: LibraryShelfMode): List<NativeShelfRow> = snapshot.mimirRowsForMode(mode)
}

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
internal fun rememberMimirNativeShelfCoordinator(
    core: MimirAppCoreCoordinator,
    library: MimirLibraryCoordinator,
    navigation: MimirNavigationCoordinator,
): MimirNativeShelfCoordinator {
    val context = core.context
    val authority = core.focusAuthority
    val viewModel = core.libraryViewModel
    val androidGameProofs = remember(context) { LaunchStore.androidGameProofs(context) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val refreshHandle = remember { MimirNativeShelfRefreshHandle() }
    val refreshNow: () -> Unit = {
        refreshHandle.job?.cancel()
        refreshHandle.job = null
        revision += 1
    }
    val scheduleRefresh: () -> Unit = {
        refreshHandle.job?.cancel()
        refreshHandle.job = scope.launch {
            delay(MimirNativeShelfRefreshDebounceMs)
            revision += 1
            refreshHandle.job = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            refreshHandle.job?.cancel()
            refreshHandle.job = null
        }
    }

    var pendingHidePackage by remember { mutableStateOf<String?>(null) }
    var addPickerMode by remember { mutableStateOf<LibraryShelfMode?>(null) }
    var addSearchQuery by rememberSaveable { mutableStateOf("") }
    var addKeyboardOpen by remember { mutableStateOf(false) }
    var addReturnPackage by remember { mutableStateOf<String?>(null) }
    var addReturnRequest by remember { mutableStateOf<MimirNativeAddReturnRequest?>(null) }
    val initialLaunchReturnRequest = remember(context) { readMimirNativeLaunchReturn(context) }
    var launchReturnRequest by remember { mutableStateOf(initialLaunchReturnRequest) }
    var launchReturnRestoring by remember { mutableStateOf(initialLaunchReturnRequest != null) }
    var launchReturnRetryEpoch by remember { mutableStateOf(0L) }
    var launchReturnSuperseded by remember { mutableStateOf(false) }
    var lastPackageByMode by remember {
        mutableStateOf<Map<LibraryShelfMode, String>>(emptyMap())
    }
    // R01 S4 (SPINE-5's second half): TRUE only between a package-receiver event and the next completed
    // scan. onResume used to trigger an UNCONDITIONAL full PackageManager rescan on every resume, even
    // though this receiver already schedules one for every real change — the resume hook now rescans only
    // when a change is actually outstanding. A receiver event DURING a scan is safe: the receiver always
    // schedules its own refresh (revision bump), so clearing the flag on scan-complete can't lose changes.
    var packagesDirtySinceScan by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val appContext = context.applicationContext
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                packagesDirtySinceScan = true
                val packageName = intent?.data?.schemeSpecificPart
                if (packageName != null) {
                    RetroArchCoreCheck.invalidate(packageName)
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            evictNativeShelfIcon(appContext, packageName)
                        }
                        scheduleRefresh()
                    }
                } else {
                    scheduleRefresh()
                }
            }
        }
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(packageReceiver, packageFilter)
        }
        onDispose {
            runCatching { appContext.unregisterReceiver(packageReceiver) }
        }
    }

    val scannedState by produceState(
        initialValue = MimirNativeShelfLoadState(),
        context,
        revision,
    ) {
        val scanRevision = revision
        value = MimirNativeShelfLoadState(
            // R01 S2 (SPINE-5): the COLD load (revision 0) coalesces with the quick-launch catalog's
            // Application.onCreate scan — one PackageManager sweep instead of two racing ones. Every
            // event-driven reload (package receiver / resume / explicit refresh bumps revision) scans fresh.
            snapshot = withContext(Dispatchers.IO) {
                if (scanRevision == 0) loadNativeShelfSnapshotCoalesced(context) else loadNativeShelfSnapshot(context)
            },
            loaded = true,
            revision = scanRevision,
        )
        packagesDirtySinceScan = false
    }
    // Walk-32h: a hide the player confirmed leaves the shelf at once, and the scan behind it settles the account
    // ([NativeShelfOptimisticHides]). Everything downstream reads this snapshot, so the Library, the launch return and the
    // add picker all agree that the package is gone.
    var optimisticHides by remember { mutableStateOf(NativeShelfOptimisticHides()) }
    val loadState = remember(scannedState, optimisticHides) {
        val unjudged = optimisticHides.settledBy(scannedState.revision)
        scannedState.copy(snapshot = scannedState.snapshot.withoutOptimisticHides(unjudged))
    }
    LaunchedEffect(scannedState.revision) {
        optimisticHides = optimisticHides.settledBy(scannedState.revision)
    }
    val snapshot = loadState.snapshot
    val addCandidates = remember(addPickerMode, snapshot) {
        when (addPickerMode) {
            LibraryShelfMode.ANDROID_GAMES -> snapshot.addToGames
            else -> emptyList()
        }
    }
    val visibleAddCandidates = remember(addCandidates, addSearchQuery) {
        val query = addSearchQuery.trim()
        if (query.isBlank()) addCandidates else addCandidates.filter { it.matchesMimirNativeQuery(query) }
    }

    MimirTrackNativeShelfFocus(
        authority = authority,
        shelfMode = library.shelfMode,
        onFocusedPackage = { mode, packageName ->
            lastPackageByMode = lastPackageByMode + (mode to packageName)
        },
    )
    LaunchedEffect(addPickerMode, addReturnPackage) {
        if (addPickerMode == null) return@LaunchedEffect
        val target = addReturnPackage ?: return@LaunchedEffect
        authority.rememberFocusForScope(LibraryShelfScope, mimirNativeShelfFocusKey(target))
    }
    LaunchedEffect(addReturnRequest, snapshot) {
        val request = addReturnRequest ?: return@LaunchedEffect
        val rows = snapshot.mimirRowsForMode(request.mode)
        val targetPackage = selectNativeReturnPackage(request.packageName, rows)
        if (targetPackage != null) {
            val focusedNow = authority.state.value.focused
            if (authority.state.value.scope == LibraryShelfScope &&
                mimirNativePackageFromFocusKey(focusedNow) == null
            ) {
                authority.requestFocus(mimirNativeShelfFocusKey(targetPackage))
            }
        }
        addReturnRequest = null
    }

    val shelfModeForLaunch by rememberUpdatedState(library.shelfMode)
    val nativeRowsForLaunch by rememberUpdatedState(
        (snapshot.games + snapshot.apps).associateBy(NativeShelfRow::packageName),
    )
    val launchPackage: (String) -> Unit = remember(context, scope, androidGameProofs) {
        val appContext = context.applicationContext
        { packageName ->
            // A new launch owns a new return anchor and ends any prior explicit-navigation suppression.
            launchReturnSuperseded = false
            val returnMode = shelfModeForLaunch
            val returnRequest = if (authority.state.value.scope == LibraryShelfScope &&
                returnMode != LibraryShelfMode.EMULATION
            ) {
                MimirNativeLaunchReturnRequest(
                    mode = returnMode,
                    packageName = packageName,
                    token = UUID.randomUUID().toString(),
                )
            } else {
                null
            }
            scope.launch {
                val target = withContext(Dispatchers.IO) {
                    resolveNativeShelfLaunchIntent(appContext, packageName)
                } ?: run {
                    // N2: used to return
                    // here in total silence. Same sentence the identical no-launchable-intent arm of Quick
                    // Launch's primary-app path shows (`MimirAppCoordinator.kt:1217`).
                    showMimirMessage(context, "App couldn’t be launched")
                    return@launch
                }
                val row = nativeRowsForLaunch[packageName]
                withContext(NonCancellable) {
                    // O15: a refusal names what held it, read from the session that refused this admission.
                    var heldBy = GameLaunchSessionHold.STARTING
                    val operation = MimirSessionAuthority.preparePrimaryApp(
                        CompanionRunningApp(
                            packageName = packageName,
                            label = row?.label ?: packageName,
                            iconPath = row?.iconPath,
                        ),
                        onHeld = { heldBy = it },
                    ) ?: run {
                        // O-3 (2026-08-12): used to return here in total silence — this file has NO
                        // other message channel wired up at all (audit finding L5's mutex-specific arm;
                        // its three sibling failure paths in this same function remain silent and unresolved). Same sentence every other
                        // non-card launch site shows for this exact refusal.
                        showMimirMessage(context, gameLaunchSessionHeldMessage(heldBy))
                        return@withContext
                    }
                    val returnArmed = returnRequest?.let { request ->
                        withContext(Dispatchers.IO) {
                            runCatching { persistMimirNativeLaunchReturn(appContext, request) }.getOrDefault(false)
                        }
                    } ?: true
                    if (!returnArmed) {
                        acknowledgeMimirNativeLaunchReturnDurably(appContext, requireNotNull(returnRequest))
                        MimirSessionAuthority.appFailed(operation, "return_anchor_arm_failed")
                        // N2 (2026-08-12, spec §1.3 B2): used to return here in total silence -- this arm has
                        // no Quick Launch counterpart (Quick Launch never arms a return anchor), so there is
                        // no verbatim sibling line to reuse. The player's fact is the same one every other
                        // arm of this identical shape reports: the app did not launch. Consistency of this
                        // new refusal copy with sibling messages still needs copy review.
                        showMimirMessage(context, "App couldn’t be launched")
                        return@withContext
                    }
                    val started = startNativeShelfLaunchIntent(context, packageName, target)
                    if (started) {
                        MimirSessionAuthority.appDispatched(operation)
                        if (shouldRecordAndroidGameRecency(row)) {
                            recordAndroidGameDispatchRecency(
                                store = androidGameProofs,
                                packageName = packageName,
                                timestampMillis = System.currentTimeMillis(),
                            ).exceptionOrNull()?.let { error ->
                                Log.w(
                                    "MimirNativeShelf",
                                    "Unable to persist Android-game launch recency",
                                    error,
                                )
                            }
                        }
                    } else {
                        if (returnRequest != null) {
                            acknowledgeMimirNativeLaunchReturnDurably(appContext, returnRequest)
                        }
                        MimirSessionAuthority.appFailed(operation, "activity_not_started")
                        // N2 (2026-08-12, spec §1.3 B3): used to end here in total silence. Same sentence the
                        // identical !started arm of Quick Launch's primary-app path shows
                        // (`MimirAppCoordinator.kt:1244`).
                        showMimirMessage(context, "App couldn’t be launched")
                    }
                }
            }
        }
    }
    val toggleFavorite: (String) -> Unit = remember(context) {
        { packageName ->
            toggleNativeShelfFavorite(context, packageName)
            refreshNow()
        }
    }
    val requestHidePackage: (String) -> Unit = remember {
        { packageName -> pendingHidePackage = packageName }
    }
    val openAddPicker: (LibraryShelfMode) -> Unit = { mode ->
        val rows = snapshot.mimirRowsForMode(mode)
        val currentPackage = mimirNativePackageFromFocusKey(authority.state.value.focused)
        addReturnPackage = selectNativeAddReturnPackage(
            currentPackage = currentPackage,
            rememberedPackage = lastPackageByMode[mode],
            rows = rows,
        )
        addSearchQuery = ""
        addKeyboardOpen = false
        addPickerMode = mode
    }
    val dismissAddPicker: () -> Unit = {
        addPickerMode?.let { mode ->
            addReturnRequest = MimirNativeAddReturnRequest(mode, addReturnPackage)
        }
        addPickerMode = null
        addReturnPackage = null
        addSearchQuery = ""
        addKeyboardOpen = false
    }
    val openAddSearch: () -> Unit = { addKeyboardOpen = true }
    val closeAddSearch: () -> Unit = { addKeyboardOpen = false }
    val moveAddPickerFocus: (ControllerAction) -> Boolean = { action ->
        val ring = if (addPickerMode == null) emptyList() else nativeAddFocusRing(visibleAddCandidates)
        if (ring.isEmpty()) {
            false
        } else {
            val next = nextNativeAddFocus(ring, authority.state.value.focused, action)
            if (next == null) false else {
                authority.requestFocus(next)
                true
            }
        }
    }
    val chooseAddPackage: (String) -> Unit = { packageName ->
        val mode = addPickerMode
        mode?.nativeShelfKindOrNull()?.let { kind ->
            addNativeShelfPackageToKind(context, packageName, kind)
            addReturnRequest = MimirNativeAddReturnRequest(mode, addReturnPackage)
            addPickerMode = null
            addReturnPackage = null
            addSearchQuery = ""
            addKeyboardOpen = false
            refreshNow()
        }
    }
    val cancelHidePackage: () -> Unit = remember {
        { pendingHidePackage = null }
    }
    // The ONE write of a native hide. The Library calls it after its own beat (the card breaking, then the survivor's focus
    // hand-off: LibraryViewModel.hideNativePackage), so by the time it runs the focus already names a card that stays.
    // The package leaves the snapshot in this same turn, and the scan the refresh requests settles it.
    val confirmHidePackage: () -> Unit = remember(context) {
        {
            pendingHidePackage?.let { packageName ->
                setNativeShelfExcluded(context, packageName, excluded = true)
                pendingHidePackage = null
                refreshNow()
                optimisticHides = optimisticHides.hiding(packageName, revision)
            }
        }
    }

    LaunchedEffect(launchReturnRequest, loadState, launchReturnRetryEpoch) {
        val request = launchReturnRequest ?: return@LaunchedEffect
        if (!loadState.loaded) return@LaunchedEffect
        val durableRequest = withContext(Dispatchers.IO) { readMimirNativeLaunchReturn(context) }
        if (durableRequest != null && durableRequest != request) {
            launchReturnRequest = durableRequest
            launchReturnRestoring = true
            return@LaunchedEffect
        }
        launchReturnRestoring = true
        val rows = loadState.snapshot.mimirRowsForMode(request.mode)
        val targetPackage = request.packageName
            .takeIf { packageName -> rows.any { it.packageName == packageName } }
            ?: rows.firstOrNull()?.packageName
        navigation.destination = MimirDestination.LIBRARY
        viewModel.setShelfMode(request.mode)
        val targetKey = targetPackage?.let(::mimirNativeShelfFocusKey)
        if (targetKey != null) {
            repeat(MimirNativeLaunchReturnFocusAttempts) {
                withFrameNanos { }
                if (authority.state.value.scope == LibraryShelfScope) {
                    authority.requestFocus(targetKey)
                    if (authority.state.value.focused == targetKey) {
                        val acknowledgement = withContext(Dispatchers.IO) {
                            acknowledgeMimirNativeLaunchReturn(context, request)
                        }
                        when (acknowledgement) {
                            MimirNativeLaunchReturnAcknowledgement.CLEARED -> {
                                launchReturnRequest = null
                                launchReturnRestoring = false
                                return@LaunchedEffect
                            }
                            MimirNativeLaunchReturnAcknowledgement.SUPERSEDED -> {
                                launchReturnRequest = withContext(Dispatchers.IO) {
                                    readMimirNativeLaunchReturn(context)
                                }
                                launchReturnRestoring = launchReturnRequest != null
                                return@LaunchedEffect
                            }
                            MimirNativeLaunchReturnAcknowledgement.RETRY -> {
                                delay(MimirNativeLaunchReturnRetryDelayMs)
                                launchReturnRetryEpoch = launchReturnRetryEpoch.nextRetryEpoch()
                                return@LaunchedEffect
                            }
                        }
                    }
                }
            }
        } else {
            withFrameNanos { }
            val acknowledgement = withContext(Dispatchers.IO) {
                acknowledgeMimirNativeLaunchReturn(context, request)
            }
            when (acknowledgement) {
                MimirNativeLaunchReturnAcknowledgement.CLEARED -> {
                    launchReturnRequest = null
                    launchReturnRestoring = false
                }
                MimirNativeLaunchReturnAcknowledgement.SUPERSEDED -> {
                    launchReturnRequest = withContext(Dispatchers.IO) {
                        readMimirNativeLaunchReturn(context)
                    }
                    launchReturnRestoring = launchReturnRequest != null
                }
                MimirNativeLaunchReturnAcknowledgement.RETRY -> {
                    delay(MimirNativeLaunchReturnRetryDelayMs)
                    launchReturnRetryEpoch = launchReturnRetryEpoch.nextRetryEpoch()
                }
            }
            return@LaunchedEffect
        }
        delay(MimirNativeLaunchReturnRetryDelayMs)
        val requestAfterDelay = withContext(Dispatchers.IO) { readMimirNativeLaunchReturn(context) }
        if (requestAfterDelay == null || requestAfterDelay == request) {
            launchReturnRetryEpoch = launchReturnRetryEpoch.nextRetryEpoch()
        } else {
            launchReturnRequest = requestAfterDelay
            launchReturnRestoring = true
        }
    }

    return MimirNativeShelfCoordinator(
        libraryViewModel = viewModel,
        loadState = loadState,
        pendingHidePackage = pendingHidePackage,
        addPickerMode = addPickerMode,
        addCandidates = addCandidates,
        addSearchQuery = addSearchQuery,
        addKeyboardOpen = addKeyboardOpen,
        launchReturnRestoring = launchReturnRestoring,
        launchPackage = launchPackage,
        toggleFavorite = toggleFavorite,
        requestHidePackage = requestHidePackage,
        cancelHidePackage = cancelHidePackage,
        confirmHidePackage = confirmHidePackage,
        openAddPicker = openAddPicker,
        moveAddPickerFocus = moveAddPickerFocus,
        setAddSearchQuery = { addSearchQuery = it },
        openAddSearch = openAddSearch,
        closeAddSearch = closeAddSearch,
        chooseAddPackage = chooseAddPackage,
        dismissAddPicker = dismissAddPicker,
        // R01 S4 (SPINE-5): resume rescans only when the package receiver actually observed a change —
        // an ordinary resume no longer pays a full PackageManager sweep for nothing.
        scheduleRefreshOnResume = { if (packagesDirtySinceScan) scheduleRefresh() },
        consumeLaunchReturnOnResume = {
            if (!launchReturnSuperseded) {
                val request = readMimirNativeLaunchReturn(context)
                if (request != null) {
                    launchReturnRequest = request
                    launchReturnRestoring = true
                    launchReturnRetryEpoch = launchReturnRetryEpoch.nextRetryEpoch()
                } else if (launchReturnRequest == null) {
                    launchReturnRestoring = false
                }
            }
        },
        supersedeLaunchReturn = {
            launchReturnSuperseded = true
            val request = launchReturnRequest ?: readMimirNativeLaunchReturn(context)
            launchReturnRequest = null
            launchReturnRestoring = false
            if (request != null) {
                scope.launch {
                    acknowledgeMimirNativeLaunchReturnDurably(context, request)
                }
            }
        },
    )
}

internal fun shouldRecordAndroidGameRecency(row: NativeShelfRow?): Boolean = row?.isGame == true

@Composable
private fun MimirTrackNativeShelfFocus(
    authority: FocusAuthority,
    shelfMode: LibraryShelfMode,
    onFocusedPackage: (LibraryShelfMode, String) -> Unit,
) {
    val focusState by authority.state.collectAsState()
    LaunchedEffect(focusState.scope, focusState.focused, shelfMode) {
        val packageName = mimirNativePackageFromFocusKey(focusState.focused)
        if (focusState.scope == LibraryShelfScope &&
            shelfMode != LibraryShelfMode.EMULATION &&
            packageName != null
        ) {
            onFocusedPackage(shelfMode, packageName)
        }
    }
}

private data class MimirNativeAddReturnRequest(
    val mode: LibraryShelfMode,
    val packageName: String?,
)

internal data class MimirNativeLaunchReturnRequest(
    val mode: LibraryShelfMode,
    val packageName: String,
    val token: String,
)

internal enum class MimirNativeLaunchReturnAcknowledgement {
    CLEARED,
    SUPERSEDED,
    RETRY,
}

private class MimirNativeShelfRefreshHandle {
    var job: Job? = null
}

private fun mimirNativePackageFromFocusKey(key: FocusKey?): String? {
    val value = key?.value ?: return null
    if (!value.startsWith(NativeShelfFocusPrefix)) return null
    return value.removePrefix(NativeShelfFocusPrefix).takeIf { it.isNotBlank() }
}

internal fun mimirNativeShelfFocusKey(packageName: String): FocusKey =
    FocusKey("$NativeShelfFocusPrefix$packageName")

private fun NativeShelfSnapshot.mimirRowsForMode(mode: LibraryShelfMode): List<NativeShelfRow> = when (mode) {
    LibraryShelfMode.EMULATION -> emptyList()
    LibraryShelfMode.ANDROID_GAMES -> games
}

private fun NativeShelfAddCandidate.matchesMimirNativeQuery(query: String): Boolean =
    listOf(
        label,
        packageName,
        rowKindLabel ?: sourceKind.rowKindLabel,
        targetKind.shelfLabel,
        when (reason) {
            NativeShelfAddReason.HIDDEN_HERE -> "Hidden restore"
            NativeShelfAddReason.MOVE_FROM_OTHER_SHELF -> {
                if (isHidden) "Hidden add ${targetKind.shelfLabel}" else "Add ${targetKind.shelfLabel}"
            }
        },
    ).any { it.contains(query, ignoreCase = true) }

@SuppressLint("ApplySharedPref")
internal fun persistMimirNativeLaunchReturn(context: Context, request: MimirNativeLaunchReturnRequest): Boolean =
    synchronized(MimirNativeLaunchReturnLock) {
        val committed = context.applicationContext
            .getSharedPreferences(MimirNativeLaunchReturnPrefs, Context.MODE_PRIVATE)
            .edit()
            .putString(MimirNativeLaunchReturnModeKey, request.mode.name)
            .putString(MimirNativeLaunchReturnPackageKey, request.packageName)
            .putString(MimirNativeLaunchReturnTokenKey, request.token)
            .commit()
        // A failed commit leaves the file in an unknown state: read it again next time.
        launchReturnMirrorLoaded = committed
        launchReturnMirror = request.takeIf { committed }
        committed
    }

/**
 * The durable launch-return request, read from disk ONCE per process and answered from memory after that (RC-30, audit
 * 2026-10-14). The resume hook asks on every foreground return, including the one from a game while the cover is up, and a
 * `SharedPreferences` lookup under the lock waits behind any in-flight `commit()` on another thread. This file is the only
 * reader and writer of the preferences and every writer holds [MimirNativeLaunchReturnLock], so the mirror cannot go stale: the
 * write sites below ([persistMimirNativeLaunchReturn], [acknowledgeMimirNativeLaunchReturn]) update it in the same critical section.
 */
internal fun readMimirNativeLaunchReturn(context: Context): MimirNativeLaunchReturnRequest? =
    synchronized(MimirNativeLaunchReturnLock) {
        if (!launchReturnMirrorLoaded) {
            launchReturnMirror = readMimirNativeLaunchReturn(
                context.applicationContext.getSharedPreferences(MimirNativeLaunchReturnPrefs, Context.MODE_PRIVATE),
            )
            launchReturnMirrorLoaded = true
        }
        launchReturnMirror
    }

/** Drops the in-memory copy so the next read goes to disk: for tests that swap the preferences underneath it. */
internal fun forgetMimirNativeLaunchReturnMirror() = synchronized(MimirNativeLaunchReturnLock) {
    launchReturnMirror = null
    launchReturnMirrorLoaded = false
}

private fun readMimirNativeLaunchReturn(prefs: SharedPreferences): MimirNativeLaunchReturnRequest? {
    val modeName = prefs.getString(MimirNativeLaunchReturnModeKey, null)
    val packageName = prefs.getString(MimirNativeLaunchReturnPackageKey, null)?.takeIf { it.isNotBlank() }
    val mode = LibraryShelfMode.values().firstOrNull { it.name == modeName }
    if (mode == null || mode == LibraryShelfMode.EMULATION || packageName == null) return null
    val token = prefs.getString(MimirNativeLaunchReturnTokenKey, null)
        ?.takeIf { it.isNotBlank() }
        ?: "legacy:${mode.name}:$packageName"
    return MimirNativeLaunchReturnRequest(mode, packageName, token)
}

@SuppressLint("ApplySharedPref")
internal fun acknowledgeMimirNativeLaunchReturn(
    context: Context,
    expected: MimirNativeLaunchReturnRequest,
): MimirNativeLaunchReturnAcknowledgement = synchronized(MimirNativeLaunchReturnLock) {
    val prefs = context.applicationContext
        .getSharedPreferences(MimirNativeLaunchReturnPrefs, Context.MODE_PRIVATE)
    val current = readMimirNativeLaunchReturn(prefs)
    if (current != null && !canAcknowledgeMimirNativeLaunchReturn(expected, current)) {
        launchReturnMirror = current
        launchReturnMirrorLoaded = true
        return@synchronized MimirNativeLaunchReturnAcknowledgement.SUPERSEDED
    }
    if (prefs.edit().clear().commit()) {
        launchReturnMirror = null
        launchReturnMirrorLoaded = true
        MimirNativeLaunchReturnAcknowledgement.CLEARED
    } else {
        launchReturnMirrorLoaded = false
        MimirNativeLaunchReturnAcknowledgement.RETRY
    }
}

internal fun canAcknowledgeMimirNativeLaunchReturn(
    expected: MimirNativeLaunchReturnRequest,
    durable: MimirNativeLaunchReturnRequest?,
): Boolean = expected == durable

private suspend fun acknowledgeMimirNativeLaunchReturnDurably(
    context: Context,
    expected: MimirNativeLaunchReturnRequest,
) {
    while (true) {
        val acknowledgement = withContext(Dispatchers.IO) {
            acknowledgeMimirNativeLaunchReturn(context, expected)
        }
        if (acknowledgement != MimirNativeLaunchReturnAcknowledgement.RETRY) return
        delay(MimirNativeLaunchReturnRetryDelayMs)
    }
}

private const val MimirNativeLaunchReturnPrefs = "mimir_native_launch_return"
private const val MimirNativeLaunchReturnModeKey = "mode"
private const val MimirNativeLaunchReturnPackageKey = "packageName"
private const val MimirNativeLaunchReturnTokenKey = "token"
private const val MimirNativeShelfRefreshDebounceMs = 180L
private const val MimirNativeLaunchReturnFocusAttempts = 120
private const val MimirNativeLaunchReturnRetryDelayMs = 500L
private val MimirNativeLaunchReturnLock = Any()

/** The in-memory copy of the durable request (guarded by [MimirNativeLaunchReturnLock]); see [readMimirNativeLaunchReturn]. */
private var launchReturnMirror: MimirNativeLaunchReturnRequest? = null
private var launchReturnMirrorLoaded = false

private fun Long.nextRetryEpoch(): Long = if (this == Long.MAX_VALUE) 0L else this + 1L
