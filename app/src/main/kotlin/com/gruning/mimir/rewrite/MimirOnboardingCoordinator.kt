package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.files.StorageVolumeListing
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class OnboardingHomeRoleState(
    val available: Boolean,
    val held: Boolean,
)

internal fun onboardingHomeRoleState(context: Context): OnboardingHomeRoleState {
    val manager = context.getSystemService(RoleManager::class.java)
        ?: return OnboardingHomeRoleState(available = false, held = false)
    val available = manager.isRoleAvailable(RoleManager.ROLE_HOME)
    return OnboardingHomeRoleState(
        available = available,
        held = available && manager.isRoleHeld(RoleManager.ROLE_HOME),
    )
}

/** Rows grouped in the Library's platform order; a platform the order does not know goes last, stably. */
internal fun onboardingFavoriteCandidatesInPlatformOrder(platformOrder: List<PlatformId>, rows: List<GameRow>): List<GameRow> {
    val rank = platformOrder.withIndex().associate { (index, id) -> id to index }
    // Already in order -- the usual case, since the read sorts by platform too -- is the same list back, with no
    // 5,500-entry sort. A stable sort of an ordered list changes nothing, so the answer is identical.
    var previousRank = Int.MIN_VALUE
    var ordered = true
    for (row in rows) {
        val r = rank[row.platformId] ?: Int.MAX_VALUE
        if (r < previousRank) {
            ordered = false
            break
        }
        previousRank = r
    }
    if (ordered) return rows
    return rows.withIndex()
        .sortedWith(compareBy<IndexedValue<GameRow>> { rank[it.value.platformId] ?: Int.MAX_VALUE }.thenBy { it.index })
        .map { it.value }
}

internal fun onboardingAdvanceAllowed(
    step: OnboardingStep,
    checkpoint: OnboardingCheckpoint,
    durableReadAccess: Boolean,
): Boolean = when (step) {
    OnboardingStep.LIBRARY ->
        checkpoint.scanStatus == OnboardingScanStatus.COMPLETE && durableReadAccess
    OnboardingStep.SETTLING ->
        durableReadAccess && (
            checkpoint.settlementStatus == OnboardingSettlementStatus.READY ||
                checkpoint.settlementStatus == OnboardingSettlementStatus.LIMITED
            )
    else -> true
}

/** The scan failure written when the persisted folder grant is revoked; stored as written, see [OnboardingStoredText]. */
internal const val OnboardingScanAccessGoneReason = "Folder access is no longer available. Choose the folder again."

/** One tag for the settlement decision, which had none before QA-17. */
private const val OnboardingSettleLogTag = "MimirOnboardSettle"

/**
 * Settlement copy: never implies artwork was fetched, never implies identity finished when it did not.
 *
 * QA-25 : these said "Your local shelf is ready". They describe the LIBRARY
 * finishing its scan, and since QA-16 the Home shelf is provably empty at this moment -- the step that
 * fills it is two screens later. Missed on the first QA-25 pass because the inventory that found the
 * other sites printed a truncated list; the emulator walk caught them still on screen.
 *
 * Onboarding content pass (spec B3, 2026-09-29): the lines no longer repeat the title ("Your library is ready"), the term pass
 * is removed, and "only when you ask in Settings" was stale -- `offerAfterHash` offers art by itself.
 * "Mimir asks before downloading any artwork" holds for ScreenScraper (the hydration worker fetches text only) but NOT
 * with a SteamGridDB key: Home fetches the shelved games' tiles on its own (`HomeShelfCoordinator`, FUX-04). So with
 * a key the line says that instead ([steamGridDbKey]).
 */
internal fun settlementReasonForEnrichment(
    converged: Boolean,
    connectedDetails: Boolean,
    steamGridDbKey: Boolean = false,
): String {
    val art = if (steamGridDbKey) OnboardingSettleArtWithKeyLine else OnboardingSettleArtAskLine
    return when {
        !converged -> "Some games are still being identified. That finishes in the background."
        connectedDetails -> "Game details fill in after setup. $art"
        else -> art
    }
}

// These sentences are stored as written (the checkpoint's settlementReason), so they stay the keys [OnboardingStoredText] resolves to the
// player's language; the screen never paints them directly.
internal const val OnboardingSettleArtAskLine = "Mimir asks before downloading any artwork."
internal const val OnboardingSettleArtWithKeyLine = "With your SteamGridDB key, Home shelf art downloads by itself."


internal fun connectedMetadataHydrationAllowed(
    connectedAccount: String?,
    connectedGateOpen: Boolean,
): Boolean = !connectedAccount.isNullOrBlank() && connectedGateOpen

/**
 * App-only orchestration for first-run setup. Resolver, identity, artwork, and launcher authorities remain in
 * their existing owners; this coordinator persists and sequences their work before the first shelf reveal.
 */
internal class MimirOnboardingCoordinator(
    val checkpoint: OnboardingCheckpoint,
    val access: OnboardingAccessUi,
    val settlement: OnboardingSettlementUi,
    val chooseFolder: () -> Unit,
    val advance: () -> Unit,
    val requestFilesAccess: () -> Unit,
    val requestUsageAccess: () -> Unit,
    // QA-10: Android's "Display over other apps" for Mimir.
    val requestOverlayAccess: () -> Unit,
    // QA-16: the discovered library for the FAVORITES step, and the one write that step makes.
    val favoriteGames: List<GameRow>,
    val favoritesIdentifying: OnboardingFavoritesIdentifying? = null,
    // Identification resolved games while the favourites step waited, so the Tome opens as it ends.
    val favoritesTomeOpensOnSettle: Boolean = false,
    // the wait hit its cap; the Tome is open to the player while identification carries on.
    val favoritesIdentifyingInBackground: Boolean = false,
    val toggleFavorite: (GameRow) -> Unit,
    // RC-13: write side of `checkpoint.accessOrientationSeen` — see [OnboardingStore.markAccessOrientationSeen].
    val markAccessOrientationSeen: () -> Unit,
    val requestHomeRole: () -> Unit,
    val openArtworkSetup: () -> Unit,
    val retrySettlement: () -> Unit,
    val continueLocally: () -> Unit,
    val refreshAccess: () -> Unit,
)

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
internal fun rememberMimirOnboardingCoordinator(
    core: MimirAppCoreCoordinator,
    navigation: MimirNavigationCoordinator,
    artwork: MimirArtworkCoordinator,
): MimirOnboardingCoordinator {
    val context = core.context
    val favoriteWriteScope = rememberCoroutineScope()
    // Favorite changes were slow to appear on RG Rotate. The write
    // queues in Room's serial transaction queue behind identification, so the star must not wait for it: a mark
    // shows at once from here, the writes run one after another in the order they were made, and each mark
    // retires once its write is done and a read agrees ([onboardingSettledFavoriteMarks]).
    val pendingFavoriteMarks = remember { mutableStateMapOf<GameId, OnboardingPendingFavorite>() }
    val favoriteWrites = remember { Mutex() }
    val checkpoint by OnboardingStore.checkpoints(context).collectAsState()
    val resolvingCount by core.repository.observeResolvingCount().collectAsState(initial = 0)
    // The Room unresolved-row count is a copy-only fallback when the per-run snapshot is unavailable.
    // It is never combined with a deduplicated visible-game count for progress arithmetic.
    val librarySummary by core.repository.observeSummary()
        .collectAsState(initial = LibrarySummary(gameCount = 0, platformCount = 0))
    // QA-16: every discovered game, for the FAVORITES step's list. GATED ON THE STEP for exactly the reason
    // the comment below gives about [identityProgress]: this coordinator is composed for the entire app
    // session, not just while onboarding is on screen, so an ungated collection here would observe every
    // game on every platform for the life of the process to serve one screen. There is no all-games flow on
    // LibraryRepository -- `observeGames` is per-platform -- so the list is composed from `observePlatforms`,
    // which is also what makes it arrive in the Library's own platform order rather than an invented one.
    // The favourites Tome waits until identification has settled. awaitSettled returns
    // once no sweep is pending and the unresolved count stops moving, so retryable rows cannot hold it forever.
    // It must also be bounded here, as awaitSettled's KDoc requires of every caller -- by a stall in the
    // unresolved count. A stalled sweep releases the Tome on what is identified so far.
    //
    // a stall bound is not enough. That library never
    // stalls -- it identified 2,650 in seven minutes and kept going -- so the Tome stayed locked for as long as a
    // player would wait. The wait is now ALSO capped by the clock at [OnboardingFavoritesWaitCapMillis]: when the
    // cap lands first, Open Tome unlocks on what is identified so far, identification keeps running in the
    // background, and the intro says so ([favoritesIdentifyingInBackground]) until it settles. The cap never
    // opens the Tome by itself; only a settle that identified games while the player waited does.
    //
    // The Tome then opens by itself only if games were identified while the player waited. A timer cannot
    // tell that apart from an already-identified library: on the emulator (2026-09-27) six files that never
    // resolve kept the line up for 1.7 s -- two re-try passes -- and a 1 s "watched" rule skipped the intro.
    // Both writes below land in one snapshot, so the page sees the flag in the frame the wait ends.
    var favoritesTomeOpensOnSettle by remember { mutableStateOf(false) }
    var favoritesIdentifyingInBackground by remember { mutableStateOf(false) }
    val favoritesIdentitySettled by produceState(initialValue = false, navigation.onboardingStep) {
        if (navigation.onboardingStep != OnboardingStep.FAVORITES) {
            favoritesTomeOpensOnSettle = false
            favoritesIdentifyingInBackground = false
            value = false
            return@produceState
        }
        val startedAtMillis = System.currentTimeMillis()
        IdentityEnrichmentWorker.enqueue(context)
        val outcome = awaitFavoritesIdentificationCapped {
            IdentityEnrichmentWorker.awaitSettledUnlessStalled(context)
        }
        if (outcome == null) {
            Log.i(
                OnboardingSettleLogTag,
                "favorites: identification capped after ${System.currentTimeMillis() - startedAtMillis}ms; " +
                    "Open Tome unlocks and identification continues in the background",
            )
            favoritesTomeOpensOnSettle = false
            favoritesIdentifyingInBackground = true
            value = true
            // Keep watching only to retire the background line; nothing opens when this returns.
            IdentityEnrichmentWorker.awaitSettledUnlessStalled(context)
            favoritesIdentifyingInBackground = false
            return@produceState
        }
        Log.i(
            OnboardingSettleLogTag,
            "favorites: identification ${if (outcome.settled) "settled" else "stalled"} after " +
                "${System.currentTimeMillis() - startedAtMillis}ms, ${outcome.identified} identified while waiting" +
                if (outcome.identified > 0) ", Tome opens" else "",
        )
        favoritesTomeOpensOnSettle = outcome.identified > 0
        value = true
    }
    val favoritesLiveProgress by produceState<IdentityEnrichmentLiveSnapshot?>(
        initialValue = null,
        navigation.onboardingStep,
    ) {
        if (navigation.onboardingStep != OnboardingStep.FAVORITES) {
            value = null
            return@produceState
        }
        // Between two slices of one sweep the probe is briefly null (WorkManager's backoff). Hold the last
        // count so the one bar never drops back to "no denominator" mid-sweep.
        // At most four repaints a second.
        // The worker publishes once per game, about 13 a second on the RG, and every publish recomposed this
        // app-wide coordinator. A StateFlow hands a slow collector only its latest value, so nothing is lost.
        IdentityEnrichmentLiveProgress.progress.collect { snapshot ->
            if (snapshot != null) value = snapshot
            delay(OnboardingLiveProgressRepaintMillis)
        }
    }
    val favoritesIdentifying = if (navigation.onboardingStep == OnboardingStep.FAVORITES && !favoritesIdentitySettled) {
        val fraction = favoritesLiveProgress?.let(::onboardingIdentifyingFraction)
        OnboardingFavoritesIdentifying(done = fraction?.done ?: 0, total = fraction?.total)
    } else {
        null
    }
    val favoriteGames by produceState(initialValue = emptyList<GameRow>(), navigation.onboardingStep) {
        if (navigation.onboardingStep != OnboardingStep.FAVORITES) {
            value = emptyList()
            return@produceState
        }
        // One light all-platform read, not a per-platform combine: the combine waited on every platform's
        // full read model, which on a 5529-game library under identity resolution never emitted (reported empty result, 2026-09-27). Platform order still follows the Library's own.
        combine(core.repository.observePlatforms(), core.repository.observeFavoriteCandidates()) { platforms, rows ->
            onboardingFavoriteCandidatesInPlatformOrder(platforms.map { it.platformId }, rows)
        }
            // Ordering 5,500 rows is not UI work. produceState collects on the UI thread; only the result lands there.
            .flowOn(Dispatchers.Default)
            .collect { value = it }
    }
    // Fine-grained, in-memory per-file progress published by the CURRENTLY RUNNING worker slice -- see
    // [IdentityEnrichmentLiveProgress]'s KDoc. Null whenever no worker run is actively publishing (not yet
    // started, between runs, or mid-GROUPING); [onboardingSettlementUi] then falls back to the DB-derived
    // `resolvingCount` alone, exactly as it did before this probe existed.
    //
    // Gated to the SAME step/status as [identityProgress] below, NOT a bare top-level `collectAsState()`:
    // this whole coordinator is composed for the entire app session (`MimirAppCoordinator` calls it
    // unconditionally, not only while onboarding is on screen -- see that file), and a post-onboarding
    // background sweep (`IdentityEnrichmentWorker.enqueueAfterSourceMutation`, e.g. a Settings library-add)
    // can publish once per file. An ungated collection would recompose this whole function that often for
    // the sweep's duration even though nothing renders it. `settlement`'s only consumer is the SETTLING
    // screen, so collecting only there costs nothing this ticket needs to pay for.
    val liveIdentityProgress by produceState<IdentityEnrichmentLiveSnapshot?>(
        initialValue = null,
        navigation.onboardingStep,
    ) {
        //  Settling READY/LIMITED releases the step; it no longer stops the probe.
        if (navigation.onboardingStep != OnboardingStep.SETTLING) {
            value = null
            return@produceState
        }
        // Sampled for the reason the FAVORITES probe above gives.
        IdentityEnrichmentLiveProgress.progress.collect {
            value = it
            delay(OnboardingLiveProgressRepaintMillis)
        }
    }
    val identityProgress by produceState<IdentityEnrichmentProgress?>(
        initialValue = null,
        context,
        navigation.onboardingStep,
    ) {
        //  Settling READY/LIMITED releases the step; it no longer stops the probe.
        if (navigation.onboardingStep != OnboardingStep.SETTLING) {
            value = null
            return@produceState
        }
        while (true) {
            value = withContext(Dispatchers.IO) { IdentityEnrichmentWorker.currentProgress(context) }
            delay(750L)
        }
    }
    // RC-30 (O-14): the access reads (role manager, AppOps, storage-manager and overlay grants, persisted URI permissions) are
    // binder calls, and they ran on Main at composition and again on EVERY foreground return, including the return from a game
    // while the cover is up, long after onboarding ended. They now run on IO and only while the onboarding destination is up; the
    // placeholder below is what the Access step shows for the one hop before the first read lands.
    val onboardingOnScreen = navigation.destination == MimirDestination.ONBOARDING
    var access by remember {
        mutableStateOf(
            OnboardingAccessUi(
                usageAvailable = false,
                usageGranted = false,
                homeRoleAvailable = false,
                homeRoleHeld = false,
            ),
        )
    }
    var tweakerCapabilityRefreshGeneration by remember { mutableStateOf(0L) }
    var accessReadGeneration by remember { mutableStateOf(0L) }

    /**
     * Lost folder access (the persisted grant was revoked) sends the flow back to the Library step. The reads and the two store
     * writes run on IO; only the step change lands on the calling (Main) context.
     */
    suspend fun reconcileLibraryAccess() {
        val lostAccess = withContext(Dispatchers.IO) {
            val current = OnboardingStore.checkpoint(context)
            val treeUri = current.selectedTreeUri ?: return@withContext false
            val treeAccess = currentOnboardingTreeAccess(context, treeUri)
            if (treeAccess.read || current.scanStatus == OnboardingScanStatus.NOT_SELECTED) return@withContext false
            current.scanGeneration?.let { generation ->
                OnboardingStore.markScanFailed(context, generation, OnboardingScanAccessGoneReason)
            }
            OnboardingStore.moveTo(context, OnboardingStep.LIBRARY)
        }
        if (lostAccess) navigation.onboardingStep = OnboardingStep.LIBRARY
    }

    val refreshAccess = {
        // Past onboarding nothing reads this: skip the whole re-read rather than pay for it on every resume.
        if (navigation.destination == MimirDestination.ONBOARDING) {
            tweakerCapabilityRefreshGeneration += 1L
            accessReadGeneration += 1L
        }
    }

    LaunchedEffect(onboardingOnScreen, accessReadGeneration) {
        if (!onboardingOnScreen) return@LaunchedEffect
        access = withContext(Dispatchers.IO) { readOnboardingAccess(core) }
        reconcileLibraryAccess()
    }

    LaunchedEffect(navigation.onboardingStep, tweakerCapabilityRefreshGeneration) {
        if (navigation.onboardingStep != OnboardingStep.ACCESS) return@LaunchedEffect
        access = withContext(Dispatchers.IO) {
            TweakerBridge.refreshCapabilities()
            readOnboardingAccess(core)
        }
    }

    val homeRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshAccess()
    }
    // The chosen folder, from either picker: Android's tree URI or a path from Mimir's own browser.
    fun beginLibraryScan(root: String, rootAccess: OnboardingTreeAccess) {
        if (!rootAccess.read) {
            navigation.onboardingLibrary.granted = false
            navigation.onboardingLibrary.failureReason =
                "Mimir could not retain access to that folder. Choose it again and allow file access."
            navigation.onboardingLibrary.status = OnboardingLibraryStatus.FAILED
            return
        }
        val generation = UUID.randomUUID().toString()
        if (OnboardingStore.beginScan(
                context = context,
                treeUri = root,
                generation = generation,
                writeAccessGranted = rootAccess.write,
            )
        ) {
            navigation.onboardingLibrary.granted = true
            navigation.onboardingLibrary.failureReason = null
            navigation.onboardingLibrary.status = OnboardingLibraryStatus.SCANNING
            OnboardingScanWorker.replace(context, root, generation)
        } else {
            navigation.onboardingLibrary.granted = false
            navigation.onboardingLibrary.failureReason =
                "Mimir could not save this setup step. Choose the folder again."
            navigation.onboardingLibrary.status = OnboardingLibraryStatus.FAILED
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) beginLibraryScan(treeUri.toString(), persistOnboardingTreeAccess(context, treeUri))
    }
    // Add games uses the app's picker ([addGamesRoute]). Mimir's own browser opens while All-files
    // access is held, with no app switch and so none of the picker's closing
    // animation under LIBRARY's glide. Android's picker is only the fallback for a player who said Not now to
    // All-files access, because Mimir's browser cannot see shared storage without it.
    val chooseLibraryFolder: () -> Unit = {
        val allFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        when (onboardingFolderPickRoute(allFilesAccess)) {
            AddGamesRoute.IN_APP_BROWSER -> FileBrowserStore.open(
                context = context,
                authority = core.focusAuthority,
                onChoose = { chosen ->
                    // The drive chooser is not a folder: scanning "/storage" would enrol every drive at once.
                    if (StorageVolumeListing.isVolumeRoot(chosen)) {
                        showMimirMessage(context, context.getString(R.string.app_onboarding_library_pick_inside_drive))
                    } else {
                        beginLibraryScan(chosen, currentOnboardingTreeAccess(context, chosen))
                    }
                },
            )
            else -> folderLauncher.launch(null)
        }
    }

    LaunchedEffect(
        checkpoint.selectedTreeUri,
        checkpoint.scanGeneration,
        checkpoint.scanStatus,
    ) {
        val treeAccess = checkpoint.selectedTreeUri
            ?.let { currentOnboardingTreeAccess(context, it) }
            ?: OnboardingTreeAccess(read = false, write = false)
        navigation.onboardingLibrary.granted = treeAccess.read
        navigation.onboardingLibrary.failureReason = checkpoint.scanFailure
        navigation.onboardingLibrary.status = when (checkpoint.scanStatus) {
            OnboardingScanStatus.NOT_SELECTED -> OnboardingLibraryStatus.IDLE
            OnboardingScanStatus.QUEUED,
            OnboardingScanStatus.RUNNING,
            -> OnboardingLibraryStatus.SCANNING
            OnboardingScanStatus.COMPLETE -> if (treeAccess.read) {
                OnboardingLibraryStatus.DONE
            } else {
                OnboardingLibraryStatus.FAILED
            }
            OnboardingScanStatus.FAILED -> OnboardingLibraryStatus.FAILED
        }
        if (
            checkpoint.selectedTreeUri != null &&
            checkpoint.scanStatus != OnboardingScanStatus.NOT_SELECTED &&
            !treeAccess.read
        ) {
            reconcileLibraryAccess()
            return@LaunchedEffect
        }
        if (
            treeAccess.read &&
            checkpoint.scanStatus in setOf(OnboardingScanStatus.QUEUED, OnboardingScanStatus.RUNNING)
        ) {
            val treeUri = checkpoint.selectedTreeUri
            val generation = checkpoint.scanGeneration
            if (treeUri != null && generation != null) {
                OnboardingScanWorker.ensureEnqueued(context, treeUri, generation)
            }
        }
    }

    LaunchedEffect(navigation.onboardingStep, checkpoint.scanStatus, resolvingCount) {
        val librarySelectionIsFinal = navigation.onboardingStep == OnboardingStep.METADATA ||
            navigation.onboardingStep == OnboardingStep.SETTLING
        if (
            librarySelectionIsFinal &&
            checkpoint.scanStatus == OnboardingScanStatus.COMPLETE &&
            resolvingCount > 0
        ) {
            IdentityEnrichmentWorker.enqueue(context)
        }
    }

    LaunchedEffect(
        navigation.onboardingStep,
        checkpoint.scanStatus,
        checkpoint.settlementStatus,
        artwork.account,
        artwork.canFetchArt,
    ) {
        if (navigation.onboardingStep != OnboardingStep.SETTLING) return@LaunchedEffect
        if (checkpoint.settlementStatus != OnboardingSettlementStatus.PENDING) return@LaunchedEffect
        if (checkpoint.scanStatus != OnboardingScanStatus.COMPLETE) return@LaunchedEffect

        // `resolvingCount` is deliberately NOT a key (P0-A): it arrives as `collectAsState(initial = 0)`, and
        // its first real value would cancel this effect mid-way. Count-driven enqueueing is the effect above.
        IdentityEnrichmentWorker.enqueue(context)

        // identification is ONE step, and it
        // is the favourites intro -- the page whose Tome needs it. Settling no longer waits for identification
        // or shows it: the scan is complete, so the library is ready, and identification carries on through
        // THEME. This replaces the bounded 90 s wait (P0-A) and the earlier update's "Continue while identifying"; neither
        // can hold first run any more, because nothing here waits. The favourites wait is bounded instead
        // (see `awaitSettledUnlessStalled`).
        val reason = settlementReasonForEnrichment(
            converged = true,
            connectedDetails = connectedMetadataHydrationAllowed(
                connectedAccount = artwork.account,
                connectedGateOpen = artwork.canFetchArt,
            ),
            steamGridDbKey = SteamGridDbKeyStore.key.value.isNotBlank() && MimirSteamGridDbGate.isOpen,
        )
        Log.i(OnboardingSettleLogTag, "settle: library ready; identification continues on the favourites step")
        OnboardingStore.markSettlement(context, OnboardingSettlementStatus.READY, reason)
    }

    val settlement = onboardingSettlementUi(
        res = onboardingResources(),
        checkpoint = checkpoint,
        resolvingCount = resolvingCount,
        // Settling no longer waits on identification, so nothing raises the finalizing surface.
        identityFinalizing = false,
        identityProgress = identityProgress,
        liveIdentityProgress = liveIdentityProgress,
    )

    val advance = {
        val current = OnboardingStore.checkpoint(context)
        val durableReadAccess = current.selectedTreeUri
            ?.let { currentOnboardingTreeAccess(context, it).read }
            ?: false
        val allowed = onboardingAdvanceAllowed(
            step = navigation.onboardingStep,
            checkpoint = current,
            durableReadAccess = durableReadAccess,
        )
        if (allowed) {
            // OR-5: no account on the accounts step answers the first-run artwork question.
            if (onboardingAccountsSkipped(navigation.onboardingStep, artwork.account)) {
                NewGameEnrichPromptState.stampOnboardingSkip(context)
            }
            when (
                val next = nextOnboardingStep(
                    navigation.onboardingStep,
                    // QA-16: no games means no step -- see [nextOnboardingStep]'s own note.
                    favoritesStepApplies = librarySummary.gameCount > 0,
                )
            ) {
                null -> if (OnboardingStore.markComplete(context)) {
                    navigation.destination = MimirDestination.HOME
                    if (connectedMetadataHydrationAllowed(artwork.account, artwork.canFetchArt)) {
                        ConnectedMetadataHydrationWorker.enqueue(context)
                    }
                }
                else -> if (OnboardingStore.moveTo(context, next)) {
                    navigation.onboardingStep = next
                }
            }
        }
    }

    val shownFavoriteGames = remember(favoriteGames, pendingFavoriteMarks.toMap()) {
        onboardingFavoritesWithPendingMarks(favoriteGames, pendingFavoriteMarks)
    }
    LaunchedEffect(favoriteGames) {
        onboardingSettledFavoriteMarks(favoriteGames, pendingFavoriteMarks).forEach(pendingFavoriteMarks::remove)
    }
    return MimirOnboardingCoordinator(
        checkpoint = checkpoint,
        access = access,
        settlement = settlement,
        chooseFolder = chooseLibraryFolder,
        advance = advance,
        requestFilesAccess = {
            // "All files access" system toggle — per-app screen when the OS offers it, the global list
            // otherwise. Never self-granted; [refreshAccess] re-reads isExternalStorageManager on return.
            if (!Environment.isExternalStorageManager()) {
                val perApp = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
                runCatching { context.startActivity(perApp) }.recoverCatching {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        },
        requestUsageAccess = { core.launchForegroundObserver?.requestUsageAccess() },
        // QA-10: the SHIPPED grant path, not a second one -- Settings' own "Draw over other apps" row
        // activates exactly this (MimirSettingsCoordinator.kt:630), so onboarding and Settings cannot drift
        // into two behaviours for one grant. The controller
        // is nullable (no companion-native on some hosts), so a local equivalent backs it up.
        favoriteGames = shownFavoriteGames,
        favoritesIdentifying = favoritesIdentifying,
        favoritesTomeOpensOnSettle = favoritesTomeOpensOnSettle,
        favoritesIdentifyingInBackground = favoritesIdentifyingInBackground,
        toggleFavorite = { game ->
            val marked = !(pendingFavoriteMarks[game.gameId]?.marked ?: game.isFavorite)
            pendingFavoriteMarks[game.gameId] = OnboardingPendingFavorite(marked = marked, written = false)
            favoriteWriteScope.launch {
                favoriteWrites.withLock { core.repository.setFavorite(game.gameId, marked) }
                if (pendingFavoriteMarks[game.gameId]?.marked == marked) {
                    pendingFavoriteMarks[game.gameId] = OnboardingPendingFavorite(marked = marked, written = true)
                }
            }
        },
        requestOverlayAccess = {
            core.companionOverlayController
                ?.let { runCatching { it.requestOverlayPermission() }.isSuccess }
                ?.takeIf { it } ?: openOnboardingOverlayAccess(context)
        },
        markAccessOrientationSeen = { OnboardingStore.markAccessOrientationSeen(context) },
        requestHomeRole = {
            val manager = context.getSystemService(RoleManager::class.java)
            if (
                manager != null &&
                manager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !manager.isRoleHeld(RoleManager.ROLE_HOME)
            ) {
                homeRoleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            }
        },
        openArtworkSetup = artwork.state::openConnectForOnboarding,
        retrySettlement = {
            val current = OnboardingStore.checkpoint(context)
            if (current.scanStatus == OnboardingScanStatus.FAILED) {
                val treeUri = current.selectedTreeUri
                val treeAccess = treeUri?.let { currentOnboardingTreeAccess(context, it) }
                if (treeUri != null && treeAccess?.read == true) {
                    val generation = UUID.randomUUID().toString()
                    if (OnboardingStore.beginScan(context, treeUri, generation, treeAccess.write)) {
                        navigation.onboardingStep = OnboardingStep.LIBRARY
                        OnboardingScanWorker.replace(context, treeUri, generation)
                    }
                } else if (OnboardingStore.moveTo(context, OnboardingStep.LIBRARY)) {
                    navigation.onboardingStep = OnboardingStep.LIBRARY
                }
            } else {
                OnboardingStore.markSettlement(context, OnboardingSettlementStatus.PENDING)
            }
        },
        continueLocally = {
            // Continuing while games are still being identified: the library is usable and hashing carries
            // on behind it, which is LIMITED's meaning -- READY would claim identity finished.
            OnboardingStore.markSettlement(
                context,
                OnboardingSettlementStatus.LIMITED,
                settlementReasonForEnrichment(converged = false, connectedDetails = false),
            )
        },
        refreshAccess = refreshAccess,
    )
}

/**
 * QA-10 fallback for hosts with no companion-native controller -- the primary path is
 * `CompanionOverlayController.requestOverlayPermission()`, the same call Settings' own row makes.
 *
 * Deliberately the same two-intent shape as [AppBrightnessCoordinator]'s `openDrawOverlayAccess`, which has
 * shipped against this exact grant: prefer Android's per-app overlay screen, fall back to plain Settings when
 * a ROM does not publish it, and fail quietly rather than crash if neither resolves.
 */
private fun openOnboardingOverlayAccess(context: Context) {
    val exact = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + context.packageName),
    )
    val fallback = Intent(Settings.ACTION_SETTINGS)
    val intent = listOf(exact, fallback).firstOrNull {
        it.resolveActivity(context.packageManager) != null
    } ?: return
    runCatching { context.startActivity(intent) }
        .onFailure { Log.w(OnboardingSettleLogTag, "overlay access screen did not open", it) }
}

private fun readOnboardingAccess(core: MimirAppCoreCoordinator): OnboardingAccessUi {
    val home = onboardingHomeRoleState(core.context)
    val usage = core.launchForegroundObserver?.usageAccessState()
    return OnboardingAccessUi(
        usageAvailable = usage != null,
        usageGranted = usage?.granted == true,
        homeRoleAvailable = home.available,
        homeRoleHeld = home.held,
        filesGranted = Environment.isExternalStorageManager(),
        // QA-10: read through the SAME authority the M button's own gate consults, so onboarding's
        // "Allowed" chip and `selectMButtonOverlayTarget` can never disagree (the QA-11 lesson). The two
        // are the identical expression today -- `hasOverlayPermission()` is
        // `SDK_INT < M || Settings.canDrawOverlays(...)` (AndroidCompanionOverlayController.kt:1009) --
        // and going through the controller keeps them identical if that ever stops being true.
        //
        // Live, and refreshed by the ordinary `refreshAccess()`/onResume path: unlike the IME picker,
        // Android's overlay screen IS an Activity that resumes back into Mimir's, so this needs no
        // observer or tick of its own.
        overlayGranted = core.companionOverlayController?.hasOverlayPermission()
            ?: Settings.canDrawOverlays(core.context),
    )
}

/**
 * An exact checked count exists only while one worker run publishes its own immutable denominator. Once all
 * hashes finish, identity writes/grouping remain active, so the bar returns to indeterminate.
 */
internal data class OnboardingIdentifyingFraction(val total: Int?, val done: Int)

internal fun onboardingIdentifyingFraction(
    liveProgress: IdentityEnrichmentLiveSnapshot?,
): OnboardingIdentifyingFraction {
    val snapshot = liveProgress ?: return OnboardingIdentifyingFraction(total = null, done = 0)
    val total = snapshot.total.takeIf { it > 0 }
        ?: return OnboardingIdentifyingFraction(total = null, done = 0)
    val done = snapshot.attempted.coerceIn(0, total)
    return if (done < total) {
        OnboardingIdentifyingFraction(total = total, done = done)
    } else {
        OnboardingIdentifyingFraction(total = null, done = 0)
    }
}

internal fun onboardingSettlementUi(
    res: Resources,
    checkpoint: OnboardingCheckpoint,
    resolvingCount: Int,
    identityFinalizing: Boolean,
    identityProgress: IdentityEnrichmentProgress? = null,
    /** Exact attempted/total pair from one active hashing run, or null when unavailable. */
    liveIdentityProgress: IdentityEnrichmentLiveSnapshot? = null,
): OnboardingSettlementUi = onboardingSettlementGateUi(
    res = res,
    checkpoint = checkpoint,
    resolvingCount = resolvingCount,
    identityFinalizing = identityFinalizing,
    identityProgress = identityProgress,
    liveIdentityProgress = liveIdentityProgress,
).let { ui ->
    // hashing must not hold the UI, so identifying offers Continue at once. A settled
    // library no longer shows the hashing run: identification appears once on the favourites intro,
    // avoiding a second progress bar for the same work.
    if (ui.phase == OnboardingSettlementPhase.IDENTIFYING) ui.copy(canContinue = true) else ui
}

private fun onboardingSettlementGateUi(
    res: Resources,
    checkpoint: OnboardingCheckpoint,
    resolvingCount: Int,
    identityFinalizing: Boolean,
    identityProgress: IdentityEnrichmentProgress?,
    liveIdentityProgress: IdentityEnrichmentLiveSnapshot?,
): OnboardingSettlementUi = when {
    checkpoint.settlementStatus == OnboardingSettlementStatus.READY -> OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.READY,
        // QA-25 : was "Your shelf is ready", which meant the LIBRARY finished
        // scanning but reads as the Home shelf. QA-16 made that actively false -- the Home shelf is
        // provably empty at this moment, because the step that fills it is two screens later. Naming the
        // library is both what happened and what the next screens build on.
        title = res.getString(R.string.app_onboarding_settling_ready_title),
        detail = OnboardingStoredText.settlementReasonText(res, checkpoint.settlementReason ?: OnboardingSettleArtAskLine),
        canContinue = true,
    )
    checkpoint.settlementStatus == OnboardingSettlementStatus.LIMITED -> OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.LIMITED,
        title = res.getString(R.string.app_onboarding_settling_limited_title),
        detail = checkpoint.settlementReason?.let { OnboardingStoredText.settlementReasonText(res, it) }
            ?: res.getString(R.string.app_onboarding_settling_limited_detail),
        canContinue = true,
    )
    checkpoint.scanStatus == OnboardingScanStatus.FAILED -> OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.FAILED,
        title = res.getString(R.string.app_onboarding_settling_failed_title),
        detail = checkpoint.scanFailure?.let { OnboardingStoredText.scanFailureText(res, it) }
            ?: res.getString(R.string.app_onboarding_settling_failed_detail),
        canRetry = true,
    )
    checkpoint.scanStatus != OnboardingScanStatus.COMPLETE -> OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.INDEXING,
        title = res.getString(R.string.app_onboarding_settling_indexing_title),
        // Content review 2026-08-21 (A7): "indexing" is internal resolver vocabulary; also brings this line
        // into agreement with the flow's established reassurance phrasing (OnboardingFlow.kt "nothing is
        // moved or copied", appearing verbatim elsewhere in the flow).
        detail = res.getString(R.string.app_onboarding_library_building_promise),
    )
    identityFinalizing -> {
        // Grouping has no exact denominator. Only the same live worker snapshot can supply checked/total.
        val grouping = identityProgress?.stage == IdentityEnrichmentStage.GROUPING
        val fraction = if (grouping) null else onboardingIdentifyingFraction(liveIdentityProgress)
        OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.IDENTIFYING,
            title = res.getString(
                if (grouping) R.string.app_onboarding_settling_grouping_title else R.string.app_onboarding_settling_identifying_title,
            ),
            detail = res.getString(
                when {
                    grouping -> R.string.app_onboarding_settling_grouping_detail
                    fraction?.total != null -> R.string.app_onboarding_settling_identifying_detail
                    liveIdentityProgress != null -> R.string.app_onboarding_settling_identifying_live_detail
                    else -> R.string.app_onboarding_settling_identifying_detail
                },
            ),
            done = fraction?.done ?: identityProgress?.attempted ?: 0,
            total = fraction?.total,
            progressRunId = liveIdentityProgress?.runId?.takeUnless { grouping },
        )
    }
    identityProgress?.stage == IdentityEnrichmentStage.GROUPING -> OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.IDENTIFYING,
        title = res.getString(R.string.app_onboarding_settling_grouping_title),
        detail = res.getString(R.string.app_onboarding_settling_grouping_detail),
        done = identityProgress.attempted,
        total = null,
    )
    liveIdentityProgress != null -> {
        val fraction = onboardingIdentifyingFraction(liveIdentityProgress)
        OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.IDENTIFYING,
            title = res.getString(R.string.app_onboarding_settling_identifying_title),
            detail = res.getString(
                if (fraction.total != null) {
                    R.string.app_onboarding_settling_identifying_detail
                } else {
                    R.string.app_onboarding_settling_identifying_live_detail
                },
            ),
            done = fraction.done,
            total = fraction.total,
            progressRunId = liveIdentityProgress.runId,
        )
    }
    resolvingCount > 0 -> {
        OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.IDENTIFYING,
            title = res.getString(R.string.app_onboarding_settling_identifying_title),
            detail = res.getQuantityString(R.plurals.app_onboarding_settling_games_to_check, resolvingCount, resolvingCount),
        )
    }
    else -> OnboardingSettlementUi(
        phase = OnboardingSettlementPhase.IDENTIFYING,
        // Spec B3: "shelf" breached QA-25 (the Home shelf is empty here); the title already says what is happening.
        title = res.getString(R.string.app_onboarding_settling_finishing_title),
        detail = res.getString(R.string.app_onboarding_settling_identifying_detail),
    )
}

/**
 * The favourites Tome's wait, capped by the clock : [await] if it finishes within
 * [capMillis], or null when the cap lands first. Cancelling [await] stops only the waiting -- the identification
 * work itself runs in WorkManager and carries on.
 */
internal suspend fun <T> awaitFavoritesIdentificationCapped(
    capMillis: Long = OnboardingFavoritesWaitCapMillis,
    await: suspend () -> T,
): T? = withTimeoutOrNull(capMillis) { await() }

/** A favourite mark the player made that the database may not show yet . */
internal data class OnboardingPendingFavorite(val marked: Boolean, val written: Boolean)

/** [rows] with every pending mark shown; the same list when nothing is pending. */
internal fun onboardingFavoritesWithPendingMarks(
    rows: List<GameRow>,
    pending: Map<GameId, OnboardingPendingFavorite>,
): List<GameRow> {
    if (pending.isEmpty()) return rows
    return rows.map { row ->
        val mark = pending[row.gameId] ?: return@map row
        if (mark.marked == row.isFavorite) row else row.copy(isFavorite = mark.marked)
    }
}

/**
 * The marks that can retire: written, and shown by [rows] as marked. A mark whose write is still queued stays,
 * even where [rows] happens to agree -- an older write for the same game may still be in the queue ahead of it.
 */
internal fun onboardingSettledFavoriteMarks(
    rows: List<GameRow>,
    pending: Map<GameId, OnboardingPendingFavorite>,
): Set<GameId> {
    if (pending.isEmpty()) return emptySet()
    val written = pending.filterValues { it.written }
    if (written.isEmpty()) return emptySet()
    return rows.asSequence()
        .filter { row -> written[row.gameId]?.marked == row.isFavorite }
        .map { it.gameId }
        .toSet()
}

/** How often onboarding repaints identification's live count . */
internal const val OnboardingLiveProgressRepaintMillis = 250L
