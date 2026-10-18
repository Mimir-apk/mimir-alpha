package com.gruning.mimir.rewrite

import coil.imageLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.launch.AndroidGameLaunchProofStore
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchRecency
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.launch.DEFAULT_PRE_DISPATCH_TIMEOUT_MILLIS
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.launch.LaunchDispatchDestination
import com.gruning.mimir.rewrite.core.launch.LaunchAttemptId
import com.gruning.mimir.rewrite.core.launch.LaunchResult
import com.gruning.mimir.rewrite.core.launch.LaunchStore
import com.gruning.mimir.rewrite.core.launch.PreDispatchOutcome
import com.gruning.mimir.rewrite.core.launch.runContainedPreDispatch
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry as DurableHomeShelfEntry
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfMembership
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.primePlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.requiresFailureFeedback
import com.gruning.mimir.rewrite.feature.library.GameLaunchSessionHold
import com.gruning.mimir.rewrite.feature.library.gameLaunchSessionHeldMessage
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardResume
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.resolveHomeLaunchCardStatusLine
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry as VisualHomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeShelfTomeReturn
import com.gruning.mimir.rewrite.feature.library.HomeShelfTomeReturnState
import com.gruning.mimir.rewrite.feature.library.HomeShelfTomeReturnOutcome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioMode
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioFocusTarget
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioFocusMemory
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioPageSize
import com.gruning.mimir.rewrite.feature.library.homeTomeMemberFocusKey
import com.gruning.mimir.rewrite.feature.library.homeTomeFolioFocusTarget
import com.gruning.mimir.rewrite.feature.library.homeTomeFolioRestoreFocusIndex
import com.gruning.mimir.rewrite.feature.library.homeTomeSeatFocusKey
import com.gruning.mimir.rewrite.feature.library.HomeTomeArrangeState
import com.gruning.mimir.rewrite.feature.library.HomeTomeArrangeIntent
import com.gruning.mimir.rewrite.feature.library.beginHomeTomeArrangeSlots
import com.gruning.mimir.rewrite.feature.library.reduceHomeTomeArrange
import com.gruning.mimir.rewrite.feature.library.reconcileHomeTomeArrange
import com.gruning.mimir.rewrite.feature.library.retargetHomeTomePageTransition
import com.gruning.mimir.rewrite.feature.library.cancelHomeTomePageResidue
import com.gruning.mimir.rewrite.feature.library.HomeTomePageTransition
import com.gruning.mimir.rewrite.feature.library.HomeTomeNavigationUpdate
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioActionRequest
import com.gruning.mimir.rewrite.feature.library.routeHomeTomeFolioAction
import com.gruning.mimir.rewrite.feature.library.beginHomeShelfTomeReturn
import com.gruning.mimir.rewrite.feature.library.cancelHomeShelfTomeReturn
import com.gruning.mimir.rewrite.feature.library.finishHomeShelfTomeReturn
import com.gruning.mimir.rewrite.feature.library.deferHomeShelfTomeReturnAction
import com.gruning.mimir.rewrite.feature.library.isHomeShelfTomeDeferredContentAction
import com.gruning.mimir.rewrite.feature.library.canActivateHomeShelfContent
import com.gruning.mimir.rewrite.feature.library.homeShelfTomeFocusKey
import com.gruning.mimir.rewrite.feature.library.homeShelfFocusKey
import com.gruning.mimir.rewrite.feature.library.homeShelfEntryForKey
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryDetailActionState
import com.gruning.mimir.rewrite.feature.library.LaunchChoice
import com.gruning.mimir.rewrite.feature.library.LibraryDetailTarget
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.TomeId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** Native launch lookup stays separate from Favorite/Tome projection; Android Apps never enter Home. */
internal data class NativeHomeLookup(
    val androidGamesByPackage: Map<String, NativeShelfRow>,
)

internal data class HomeShelfTomeReplay(
    val generation: Long,
    val actions: List<ControllerAction>,
)

internal enum class HomeTomeOrigin { HOME_ROOT, TOME_LIBRARY }

/** App-owned navigation seam; the visual folio may enrich scroll/page state without owning identity. */
internal data class HomeTomeSession(
    val tomeId: TomeId,
    val origin: HomeTomeOrigin,
    val rootEntry: VisualHomeShelfEntry?,
    val rootMode: HomeViewMode = HomeViewMode.EDITORS_CHOICE,
    val rootFallbackEntry: VisualHomeShelfEntry? = null,
    val browserFocusedIndex: Int? = null,
    val browserFirstVisibleIndex: Int? = null,
    /** Monotonic navigation epoch; an older durable launch return may never resurrect a closed Folio. */
    val generation: Long = 0L,
) {
    init {
        require(generation >= 0L) { "Tome session generation must be non-negative" }
    }
}

internal fun nextHomeTomeSessionGeneration(current: Long): Long =
    if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L

internal fun restorableHomeTomeSession(session: HomeTomeSession?, minimumGeneration: Long): HomeTomeSession? =
    session?.takeIf { it.generation >= minimumGeneration }

private data class PendingTomeProjection(
    val tomeId: TomeId,
    val pinned: Boolean,
    val returnEntry: VisualHomeShelfEntry?,
    val onSettled: () -> Unit,
)

/** The projection bridge is single-slot, so pin/delete writes are intentionally one-at-a-time. */
internal class HomeTomeProjectionMutationGate {
    private var pendingTomeId: TomeId? = null

    fun tryBegin(tomeId: TomeId): Boolean {
        if (pendingTomeId != null) return false
        pendingTomeId = tomeId
        return true
    }

    fun finish(tomeId: TomeId) {
        if (pendingTomeId == tomeId) pendingTomeId = null
    }
}

private data class PendingTomeOrderCommit(
    val generation: Long,
    val transactionId: Long,
    val tomeId: TomeId,
    val slots: List<HomeShelfItemId?>,
    val rollbackSlots: List<HomeShelfItemId?>,
    val rollbackFocusIndex: Int,
    val writeSucceeded: Boolean = false,
)

internal data class PendingFolioArrangeActivation(
    val target: HomeTomeFolioFocusTarget,
    val action: ControllerAction,
    val mode: HomeTomeFolioMode,
    val carriedItemId: HomeShelfItemId?,
    val transactionId: Long,
)

internal fun pendingFolioArrangeActivationIsCurrent(
    pending: PendingFolioArrangeActivation,
    liveTarget: HomeTomeFolioFocusTarget?,
    liveMode: HomeTomeFolioMode,
    liveCarriedItemId: HomeShelfItemId?,
    liveActiveTransactionId: Long?,
    liveNextTransactionId: Long,
    scopeIsFolio: Boolean,
    focusedKey: FocusKey?,
): Boolean {
    val liveTransactionId = liveActiveTransactionId ?: liveNextTransactionId
    return pending.action == ControllerAction.CONFIRM &&
        pending.target == liveTarget &&
        pending.mode == liveMode &&
        pending.carriedItemId == liveCarriedItemId &&
        pending.transactionId == liveTransactionId &&
        scopeIsFolio &&
        focusedKey == pending.target.key
}

internal fun homeTomeFolioTargetIsSettledForActivation(
    liveTarget: HomeTomeFolioFocusTarget?,
    settledTarget: HomeTomeFolioFocusTarget?,
    scopeIsFolio: Boolean,
    focusedKey: FocusKey?,
): Boolean = liveTarget != null &&
    settledTarget == liveTarget &&
    scopeIsFolio &&
    focusedKey == liveTarget.key

internal fun canActivateFavoriteItem(
    itemId: HomeShelfItemId,
    membershipIds: Set<HomeShelfItemId>,
    pendingRemovalIds: Set<HomeShelfItemId>,
): Boolean = itemId in membershipIds && itemId !in pendingRemovalIds

/**
 * Adding a game to a Tome inherits favorite state. `HomeTomeStore.toggleMember` only reports
 * persist success, never which way it
 * flipped, so [toggleTomeMembership] derives direction itself from the actual before/after Tome document
 * — never assumed from the caller's own optimistic UI state — and this function is the resulting policy:
 * true only when the write demonstrably ADDED [member] to [tomeId] (present after, absent before) AND the
 * game is not already on the shelf.
 *
 * The `!alreadyFavorite` guard is deliberate: re-applying Favorite
 * to a game that already has it would still be "correct" but silently bumps its favorited-at order
 * (`FAVORITE_GAME_ORDER_BY_SQL`, `core-storage/.../Daos.kt`), which is precisely the kind of shelf-state
 * side effect nobody asked for — the same reasoning [toggleTomeMembership] applies to the REMOVE direction
 * (never un-favorite). Precedent already in this tree for skip-if-unchanged: `setNativeShelfFavoriteInteractive`
 * only writes `if (changed)`, and `LibraryRepository.promoteFavoriteIfAbsent`'s KDoc requires it "preserving
 * every existing Favorite timestamp".
 *
 * Every degenerate case (tome deleted mid-flight, a refused persist leaving before == after, or a stale/
 * aliased [member] this function cannot resolve) degrades to "skip the favorite" — never to a spurious
 * write. See [toggleTomeMembership] for the one known narrow gap this leaves: identity aliasing
 * (`GameIdentityAliasResolver`, `HomeCollectionStore.kt`) is resolved inside `toggleMember` itself, which
 * this file cannot see or duplicate.
 */
internal fun homeTomeToggleShouldFavorite(
    before: HomeTomeDocument,
    after: HomeTomeDocument,
    tomeId: TomeId.Custom,
    member: FavoriteMemberId,
    alreadyFavorite: Boolean,
): Boolean {
    val wasMember = before.customTomes.firstOrNull { it.id == tomeId }?.members.orEmpty().contains(member)
    val isMemberNow = after.customTomes.firstOrNull { it.id == tomeId }?.members.orEmpty().contains(member)
    val addedToTome = !wasMember && isMemberNow
    return addedToTome && !alreadyFavorite
}

/**
 * F8 (product review 2026-09-28; nova-8, rg-rotate-2, thor-1): what to tell the player when Add to Tome lands, or
 * null when nothing was added.
 *
 * Before copy-like membership a Tome member left Editor's Choice and this said it had moved.
 * Now the game stays where it was, so nothing moved and the toast only confirms the copy. Only a
 * demonstrated ADD speaks; a removal, or a game already in this Tome, says nothing.
 */
internal fun homeTomeAddedMessage(
    before: HomeTomeDocument,
    after: HomeTomeDocument,
    tomeId: TomeId.Custom,
    member: FavoriteMemberId,
): String? {
    val tome = after.customTomes.firstOrNull { it.id == tomeId } ?: return null
    if (member !in tome.members) return null
    val wasMember = before.customTomes.firstOrNull { it.id == tomeId }?.members.orEmpty().contains(member)
    return if (wasMember) null else "Added to ${tome.label}"
}

/**
 * F9 (product review 2026-09-28; nova-6, thor-11, eu-thor-18): R1 with no pinned Tome used to do nothing at all.
 * X opens the Tome library on Home, which is where a Tome is made and pinned. Guidance, not Warning: nothing
 * failed, and Warning plays the error sound.
 */
internal const val HomePinnedTomesRefusalHint = "No pinned Tomes yet, X to make one"

internal val HomePinnedTomesRefusalClass = MimirMessageClass.Guidance

internal fun reconcilePendingFavoriteRemovals(
    pendingRemovalIds: Set<HomeShelfItemId>,
    membershipIds: Set<HomeShelfItemId>,
): Set<HomeShelfItemId> = pendingRemovalIds.filterTo(linkedSetOf(), membershipIds::contains)

internal fun visibleFavoriteHomeGames(
    durableGames: List<FavoriteHomeGame>,
    pendingRemovalIds: Set<HomeShelfItemId>,
): List<FavoriteHomeGame> = durableGames.filterNot {
    it.id.toHomeShelfItemId() in pendingRemovalIds
}

/** Keeps committed pixels stable while current Favorite truth filters removals and appends additions. */
internal fun visibleOptimisticTomeOrder(
    committedOrder: List<HomeShelfItemId>,
    durableVisibleOrder: List<HomeShelfItemId>,
): List<HomeShelfItemId> {
    val visible = durableVisibleOrder.toSet()
    return committedOrder.filter(visible::contains).distinct() +
        durableVisibleOrder.filterNot(committedOrder::contains)
}

/** Sparse counterpart: removals become empty wells and newly visible Favorites fill the first free well. */
internal fun visibleOptimisticTomeSlots(
    committedSlots: List<HomeShelfItemId?>,
    durableVisibleSlots: List<HomeShelfItemId?>,
): List<HomeShelfItemId?> {
    val durableOrder = durableVisibleSlots.filterNotNull().distinct()
    if (durableOrder.isEmpty()) return emptyList()
    val visible = durableOrder.toSet()
    val seen = linkedSetOf<HomeShelfItemId>()
    val capacity = maxOf(committedSlots.size, durableVisibleSlots.size, HomeTomeFolioPageSize)
    val slots = MutableList<HomeShelfItemId?>(capacity) { index ->
        committedSlots.getOrNull(index)?.takeIf { it in visible && seen.add(it) }
    }
    durableOrder.forEach { identity ->
        if (seen.add(identity)) {
            val empty = slots.indexOfFirst { it == null }
            if (empty >= 0) slots[empty] = identity else slots += identity
        }
    }
    val used = slots.indexOfLast { it != null } + 1
    val paddedSize = ((used - 1) / HomeTomeFolioPageSize + 1) * HomeTomeFolioPageSize
    return slots.take(paddedSize)
}

internal data class RestoredHomeTomePosition(
    val focusedIndex: Int,
    val transition: HomeTomePageTransition,
)

internal fun restoredHomeTomePosition(targetIndex: Int): RestoredHomeTomePosition {
    val safe = targetIndex.coerceAtLeast(0)
    return RestoredHomeTomePosition(
        focusedIndex = safe,
        transition = HomeTomePageTransition(settledPage = safe / HomeTomeFolioPageSize),
    )
}

internal data class AuthoritativeHomeTomeFocusCapture(
    val tomeId: TomeId,
    val memory: HomeTomeFolioFocusMemory,
)

/**
 * Close-time capture does not wait for the composable settlement latch: once FocusAuthority owns the
 * exact reducer target, that identity/seat is the user's authoritative last position.
 */
internal fun authoritativeHomeTomeFocusCapture(
    state: HomeTomeFolioUiState?,
    scopeIsFolio: Boolean,
    focusedKey: FocusKey?,
): AuthoritativeHomeTomeFocusCapture? {
    val live = state ?: return null
    if (!scopeIsFolio || focusedKey == null) return null
    val authoritativeSeat = live.memberSlots.indices.firstOrNull { index ->
        val seatKey = live.memberSlots[index]?.focusKey
            ?: homeTomeSeatFocusKey(live.tome.tomeId, index)
        seatKey == focusedKey
    } ?: return null
    val itemId = live.memberSlots.getOrNull(authoritativeSeat)?.itemId
    return AuthoritativeHomeTomeFocusCapture(
        tomeId = live.tome.tomeId,
        memory = HomeTomeFolioFocusMemory(
            itemId = itemId,
            seatIndex = authoritativeSeat,
            wasEmptySeat = itemId == null,
        ),
    )
}

internal fun openingHomeTomeFocusIndex(
    memory: HomeTomeFolioFocusMemory?,
    memberSlots: List<HomeShelfItemId?>,
    preferredIndex: Int,
): Int {
    if (memberSlots.isEmpty()) return 0
    return memory?.let { homeTomeFolioRestoreFocusIndex(it, memberSlots) }
        ?: preferredIndex.coerceIn(0, memberSlots.lastIndex)
}

internal fun nativeHomeLookup(
    nativeGameRows: List<NativeShelfRow>,
): NativeHomeLookup = NativeHomeLookup(
    androidGamesByPackage = nativeGameRows.associateBy(NativeShelfRow::packageName),
)

internal const val HomeSurfacePersistenceSettleMs = 180L
private const val HomeFavoriteProjectionKey = "favorite"

internal data class HomeInputProjection(
    val mode: HomeViewMode,
    val entries: List<VisualHomeShelfEntry>,
    val focusKey: FocusKey?,
    val epoch: Int,
)

internal data class HomeAlphabetJumpNotice(val letter: String, val epoch: Long)

/** Latest-wins transient presentation state for L2/R2 alphabet travel. */
@Stable
internal class HomeAlphabetJumpFeedback {
    var notice: HomeAlphabetJumpNotice? by mutableStateOf(null)
        private set
    var lastLetter: String? by mutableStateOf(null)
        private set

    fun publish(letter: String) {
        val nextEpoch = (notice?.epoch ?: 0L).let { if (it == Long.MAX_VALUE) 1L else it + 1L }
        lastLetter = letter
        notice = HomeAlphabetJumpNotice(letter, nextEpoch)
    }

    fun expire(epoch: Long) {
        if (notice?.epoch == epoch) notice = null
    }
}

/** Input-visible projection swaps synchronously; Compose materializes the same epoch on the next frame. */
internal class HomeInputProjectionAuthority {
    private val state = AtomicReference(
        HomeInputProjection(HomeViewMode.EDITORS_CHOICE, emptyList(), null, 0),
    )

    fun snapshot(): HomeInputProjection = state.get()

    fun publish(mode: HomeViewMode, entries: List<VisualHomeShelfEntry>, focusKey: FocusKey?, epoch: Int) {
        val keys = entries.map(VisualHomeShelfEntry::focusKey)
        state.set(HomeInputProjection(mode, entries, focusKey?.takeIf { it in keys } ?: keys.firstOrNull(), epoch))
    }

    fun reconcile(mode: HomeViewMode, entries: List<VisualHomeShelfEntry>, focusKey: FocusKey?, epoch: Int) {
        while (true) {
            val current = state.get()
            if (current.epoch > epoch) return
            val keys = entries.map(VisualHomeShelfEntry::focusKey)
            val desired = if (current.epoch == epoch) {
                current.focusKey?.takeIf { it in keys } ?: focusKey?.takeIf { it in keys } ?: keys.firstOrNull()
            } else {
                focusKey?.takeIf { it in keys } ?: keys.firstOrNull()
            }
            val next = HomeInputProjection(mode, entries, desired, epoch)
            if (state.compareAndSet(current, next)) return
        }
    }

    fun updateFocus(focusKey: FocusKey) {
        while (true) {
            val current = state.get()
            if (current.entries.none { it.focusKey == focusKey }) return
            if (state.compareAndSet(current, current.copy(focusKey = focusKey))) return
        }
    }
}

/** Live root shelf for the active Home view mode; never use composition-frozen visible lists in callbacks. */
internal fun homeRootEntriesForViewMode(
    mode: HomeViewMode,
    editorsChoiceEntries: List<VisualHomeShelfEntry>,
    pinnedTomesEntries: List<VisualHomeShelfEntry>,
): List<VisualHomeShelfEntry> = when (mode) {
    HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntries
    HomeViewMode.PINNED_TOMES -> pinnedTomesEntries
}

/** Sprint H01: the pinned Home mode is custom-collection truth only; stale platform pins stay hidden. */
internal fun customPinnedHomeTomeEntries(
    entries: List<VisualHomeShelfEntry>,
): List<VisualHomeShelfEntry> = entries.filterIsInstance<VisualHomeShelfEntry.Tome>()
    .filter { it.tomeId is TomeId.Custom }

/** An old rail callback cannot consume a newer Home event; an exact delivery removes remount replay state. */
internal fun consumeDeliveredHomeMessage(
    message: MimirMessage?,
    deliveredEventId: Long,
): MimirMessage? = message?.takeUnless { it.eventId == deliveredEventId }

/**
 * [leaveHomeTomeScope] that lands focus on [landingKey] in ONE step (RC-49, H-03c).
 *
 * A pop restores the remembered focus of the scope beneath, which for the shelf is where it stood BEFORE the Tome opened. When
 * the return is somewhere else in the same mode (the Tome was deleted) that pop puts the ring on the wrong tile, and the key
 * stamped after it needed a frame (up to eight, for the ordered return) to correct it: a visible jump.
 * Stamping the shelf's memory FIRST makes the pop itself land on the destination. It works because the shelf's keys are
 * registered virtually, so a destination in the current mode is already known to the authority. A key it does not know yet (a mode
 * switch registers after its epoch recomposes) is ignored by the pop exactly as before, and the ordered return covers it.
 * So a close whose mode falls back (the last pin removed) still lands on the pre-Tome key first and then moves to the ordered
 * return's key: a known residual (W1B #5), not fixed here.
 *
 * @return what [leaveHomeTomeScope] returns: whether the scope is off the stack and focus rests on the shelf.
 */
internal fun leaveHomeTomeScopeLandingOn(authority: FocusAuthority, scope: FocusScope, landingKey: FocusKey?): Boolean {
    // Only a key the authority already knows: the pop ignores one it does not, and an unknown key would send the pop to the first
    // registered tile instead of where it landed before.
    landingKey?.takeIf { it in authority.registeredKeysInScope(HomeShelfScope) }
        ?.let { authority.rememberFocusForScope(HomeShelfScope, it) }
    return leaveHomeTomeScope(authority, scope)
}

/** Identity-aware neighbor selection; never invents the first item as an unrelated fallback. */
internal fun nearestSurvivingHomeEntry(
    before: List<VisualHomeShelfEntry>,
    removed: VisualHomeShelfEntry,
): VisualHomeShelfEntry? {
    val removedIndex = before.indexOf(removed)
    if (removedIndex < 0) return null
    return before.getOrNull(removedIndex + 1) ?: before.getOrNull(removedIndex - 1)
}

/**
 * App-owned Home shelf state holder. The root sees one object while durable membership, projection,
 * exact return, launch, and mutation feedback remain coordinated here.
 */
@Stable
internal class HomeShelfCoordinator internal constructor(
    val rows: List<HomeShelfRow>,
    val entries: List<VisualHomeShelfEntry> = rows.map { VisualHomeShelfEntry.Game(it.itemId) },
    val tomes: Map<TomeId, HomeShelfTome> = emptyMap(),
    val toast: MimirMessage?,
    val consumeDeliveredMessage: (Long) -> Unit = {},
    val launchReturnFocusKey: FocusKey?,
    val launchReturnRestoring: Boolean = false,
    /** True for either exact external-launch return or cold process-death Home-surface restoration. */
    val surfaceRestorationPending: Boolean = false,
    val viewMode: HomeViewMode = HomeViewMode.EDITORS_CHOICE,
    val viewModeToggleAvailable: Boolean = false,
    val toggleViewMode: () -> Boolean = { false },
    /** F9: R1 was refused because no Tome is pinned yet; say so instead of doing nothing. */
    val refusePinnedTomesSwitch: () -> Unit = {},
    /** L2/R2 bucket travel is root-only and delegates visual feedback to the app-owned projection. */
    val jumpLooseHomeAlphabetBucket: (forward: Boolean) -> Boolean = { false },
    val alphabetJumpFeedback: HomeAlphabetJumpFeedback = HomeAlphabetJumpFeedback(),
    private val inputProjection: HomeInputProjectionAuthority = HomeInputProjectionAuthority(),
    val surfaceLabel: String,
    /** OR-1: the focused game's Tome mark ([homeTomeMarkLabel]); null off Editor's Choice or outside any Tome. */
    val tomeMarkFor: (HomeShelfItemId) -> String? = { null },
    val surfaceEpoch: Int,
    /**
     * Which way the last surface change went (motion audit batch D): +1 when R1 brought Pinned Tomes in from the right,
     * -1 when L1 brought Editor's Choice in from the left, 0 for a restore or return that no press caused.
     */
    val surfaceHandoffDirection: Int = 0,
    val rootProjectionSettled: Boolean = false,
    val contentSettled: Boolean = true,
    val canActivateHomeContent: () -> Boolean = { true },
    val tomeDocument: HomeTomeDocument = HomeTomeDocument(),
    val availableTomes: Map<TomeId, HomeShelfTome> = emptyMap(),
    val homeOverviewTome: HomeShelfTome = HomeShelfTome(
        tomeId = TomeId.HomeOverview,
        title = HomeShelfTomeFallbackTitle,
        previews = emptyList(),
        memberCount = 0,
    ),
    val tomeSession: HomeTomeSession? = null,
    val tomeFolioState: HomeTomeFolioUiState? = null,
    val tomeFolioRows: Map<HomeShelfItemId, HomeShelfRow> = emptyMap(),
    val focusTomeMember: (Int) -> Unit = {},
    val settleTomeFolioFocus: (HomeTomeFolioFocusTarget) -> Unit = {},
    val unsettleTomeFolioFocus: () -> Unit = {},
    val navigateTomeFolio: (HomeTomeNavigationUpdate) -> Unit = {},
    val routeTomeFolioAction: (ControllerAction) -> HomeTomeFolioActionRequest? = { null },
    val enterTomeArrange: () -> Unit = {},
    val dispatchTomeArrange: (ControllerAction) -> Unit = {},
    val dispatchTouchTomeArrange: (HomeTomeArrangeIntent) -> Unit = {},
    val cancelTomePageResidue: () -> Unit = {},
    val createTome: (String, (HomeTomeEditResult) -> Unit) -> Unit = { _, done ->
        done(HomeTomeEditResult.Failed("Couldn’t save Tome"))
    },
    val renameTome: (TomeId.Custom, String, (HomeTomeEditResult) -> Unit) -> Unit = { _, _, done ->
        done(HomeTomeEditResult.Failed("Couldn’t save Tome"))
    },
    val toggleTomeMembership: (TomeId.Custom, HomeShelfItemId, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    val deleteTome: (TomeId.Custom, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
    val setTomePinned: (TomeId, Boolean, (Boolean) -> Unit) -> Unit = { _, _, done -> done(true) },
    val openTome: (TomeId, HomeTomeOrigin, Int?, Int?) -> Unit = { _, _, _, _ -> },
    val openHomeOverview: (HomeTomeOrigin, Int?, Int?) -> Boolean = { _, _, _ -> false },
    val restoreRootEntry: (VisualHomeShelfEntry?) -> Unit = {},
    val closeTome: () -> Boolean = { false },
    /**
     * CP02 : the argument is the
     * arrival-epoch fence the caller captured SYNCHRONOUSLY, before this call, from
     * `navigation.homeTomeLibraryOpenArrivalEpoch` — or `null` when there is no incoming Library arrival
     * to sequence against (the deleted-tome auto-close below, which never republishes
     * `bindings.navigation.homeTomeLibrary`). `null` clears the session immediately; a real fence defers
     * the clear to [tomeLibraryReturnPendingFence] / [completeTomeLibraryReturn], resolved by
     * `HomeTomeLibraryReturnReleaseEffect` (`MimirAppCoordinator.kt`) rather than by a coroutine captured
     * in this composable — see that function's kdoc for why the wait moved out of here.
     */
    val closeTomeToLibrary: (arrivalFence: Long?) -> Boolean = { false },
    /**
     * CP02: non-null while a Folio-return teardown is waiting for the Library's own arrival to settle
     * (or for the browser to be torn down out from under it — the reversal escape,
     * `HomeTomeLibraryReturnReleaseEffect`'s `browserGone` arm). The fence value itself, so the release
     * effect can require `epoch > fence` without re-deriving which arrival it is waiting for.
     */
    val tomeLibraryReturnPendingFence: Long? = null,
    /**
     * CP02: releases the pending Folio teardown armed by [closeTomeToLibrary]. Guarded on fence equality
     * so a stale completion (should one ever race a newer pending fence) is a no-op rather than clearing
     * the wrong session.
     */
    val completeTomeLibraryReturn: (fence: Long) -> Unit = {},
    val tomeReturn: HomeShelfTomeReturn? = null,
    val isTomeReturnCurrent: (Long) -> Boolean = { false },
    val finishTomeReturn: (Long, HomeShelfTomeReturnOutcome) -> Unit = { _, _ -> },
    val cancelTomeReturn: () -> Unit = {},
    val deferTomeReturnAction: (ControllerAction) -> Boolean = { false },
    val tomeReplay: HomeShelfTomeReplay? = null,
    val consumeTomeReplay: (Long) -> List<ControllerAction> = { emptyList() },
    private val membershipIds: Set<HomeShelfItemId>,
    private val mutationIds: Set<HomeShelfItemId>,
    private val pendingRemovalIds: Set<HomeShelfItemId>,
    private val canActivateItem: (HomeShelfItemId) -> Boolean = { itemId ->
        canActivateFavoriteItem(itemId, membershipIds, pendingRemovalIds)
    },
    private val mutationRevision: Long,
    val launch: (HomeShelfRow) -> Unit,
    /** Emulated launch from the Home launch card — keeps the card open on failure and reports [GameLaunchUiState]. */
    /** Last parameter is the owner's disc / version pick, or [LaunchChoice.GroupDefault] for plain Play. */
    val launchFromLaunchCard: (
        HomeShelfRow,
        GameRow,
        (GameLaunchUiState) -> Unit,
        () -> Unit,
        LaunchChoice,
    ) -> Unit,
    val gameForAchievements: (HomeShelfRow) -> GameRow? = { null },
    val setMembership: (LibraryDetailTarget, Boolean) -> Unit,
    /** FUX-E: the row whose Home launch is seeding a NOW_PLAYING(loading) card, or null. */
    val launchingRow: HomeShelfRow? = null,
    /** FUX-10 tile menu: direct membership removal + tile-cache resync after an explicit art pick. */
    val removeFavorite: (HomeShelfItemId) -> Unit = {},
    /** The tile menu's Add favorite: the same membership write the Library's Favorite button used, by item identity. */
    val addFavorite: (HomeShelfItemId) -> Unit = {},
    val refreshTiles: () -> Unit = {},
) {
    val inputEntries: List<VisualHomeShelfEntry> get() = inputProjection.snapshot().entries
    val inputFocusKey: FocusKey? get() = inputProjection.snapshot().focusKey
    val inputViewMode: HomeViewMode get() = inputProjection.snapshot().mode

    fun updateInputFocus(focusKey: FocusKey) = inputProjection.updateFocus(focusKey)

    /** FUX-10: the tile menu offers Remove only for rows that are actual durable members. */
    fun isMember(itemId: HomeShelfItemId): Boolean = itemId in membershipIds

    /** A pending Favorite removal fails closed before the repository projection catches up. */
    fun canActivate(itemId: HomeShelfItemId): Boolean = canActivateItem(itemId)

    fun detailActionState(target: LibraryDetailTarget?): LibraryDetailActionState? = target?.let {
        LibraryDetailActionState(
            target = it,
            isOnShelf = it.itemId in membershipIds,
            revision = mutationRevision,
        )
    }
}

/**
 * Hero and Hero Motion remain independently saved, but the user's most recent explicit choice owns
 * presentation. Selecting Hero Art therefore restores the still immediately; selecting a saved
 * Hero Motion again advances its intent and makes motion the winner without destroying either asset.
 */
internal fun homeChosenMotionWins(
    hero: GameIdentityMediaSelectionRecord,
    motion: GameIdentityMediaSelectionRecord?,
): Boolean = motion?.selection == GameIdentityMediaSelection.SELECTED &&
    (
        hero.selection != GameIdentityMediaSelection.SELECTED ||
            motion.changedAtMillis > hero.changedAtMillis
        )

/**
 * F2's rewarm bound. 400 ms: long enough for a memory-cache-warm window to complete instantly and for a
 * post-trim re-decode to land most of the visible covers, short enough that it can never be felt as a hang
 * on the return beat. Subject to tuning by eye; it is one constant.
 */
private const val HomeReturnRewarmBudgetMs = 400L

/** What [rememberHomeShelfDurableSources] hands the later pieces; a name ending in `State` is the live state object. */
private class HomeShelfDurableSources(
    val libraryRepository: LibraryRepository,
    val migrationCompleteState: MutableState<Boolean?>,
    val emulatedFavoritesState: State<List<GameRow>?>,
    val platformsState: State<List<PlatformRow>?>,
    val androidGameProofs: AndroidGameLaunchProofStore,
    val androidGameRecencyState: State<List<AndroidGameLaunchRecency>?>,
    val tomeDocumentState: State<HomeTomeDocument>,
    val tomeStoreReadyState: MutableState<Boolean>,
    val externalSessionElapsedTotalsState: State<Map<HomeShelfItemId, Long>>,
    val nativeFavoriteOverridesState: MutableState<Map<String, Boolean>>,
    val requiredPromotedEmulatedState: MutableState<Set<GameId>>,
    val effectiveNativeGames: List<NativeShelfRow>,
    val pendingLaunchReturnState: MutableState<HomeLaunchReturnHandle?>,
    val launchReturnNeedsRouteState: MutableState<Boolean>,
    val pendingSurfaceRestoreState: MutableState<HomeSurfaceSnapshot?>,
    val restorationTruthReadyState: MutableState<Boolean>,
    val homeViewModeState: MutableState<HomeViewMode>,
    val editorsChoiceRootState: MutableState<HomeRootEntryRecord?>,
    val pinnedTomesRootState: MutableState<HomeRootEntryRecord.Tome?>,
    val homeSurfaceEpochState: MutableState<Int>,
    val homeSurfaceHandoffDirectionState: MutableState<Int>,
    val inputProjection: HomeInputProjectionAuthority,
    val alphabetJumpFeedback: HomeAlphabetJumpFeedback,
    val pendingRemovalIdsState: MutableState<Set<HomeShelfItemId>>,
)

/**
 * First piece of [rememberHomeShelfCoordinator]: the durable Favorite / Tome / launch-proof sources, the
 * legacy-migration flow, and the Home surface's restore and view-mode state.
 *
 * `rememberHomeShelfCoordinator` was one composable of about 17,400 dex code units. ART's optimizing
 * compiler refuses any method of 16,383 units or more ("Method exceeds compiler instruction limit"), so
 * every recomposition of it ran in the interpreter. It is now this piece and the pieces after it, each
 * a small non-restartable composable that RETURNS a holder: no new recompose scope appears, the
 * `remember`/`LaunchedEffect` calls keep their original order and keys, and the holders carry the very
 * same [MutableState] / [State] objects (re-bound under their old names), so closure capture and lambda
 * memoization are unchanged.
 */
@Composable
private fun rememberHomeShelfDurableSources(
    context: Context,
    persistedLaunchReturn: HomeLaunchReturnHandle?,
    persistedLaunchReturnReady: Boolean,
    nativeGameRows: List<NativeShelfRow>,
    nativeRowsLoaded: Boolean,
): HomeShelfDurableSources {
    val legacyRepository = remember(context) { LibraryInjection.homeShelfRepository(context) }
    val libraryRepository = remember(context) { LibraryInjection.repository(context) }
    val migrationCompleteState = remember(context) { mutableStateOf<Boolean?>(null) }
    var migrationComplete by migrationCompleteState
    LaunchedEffect(context) {
        migrationComplete = HomeFavoritesMigration.isComplete(context)
    }
    val legacyEntriesFlow: Flow<List<DurableHomeShelfEntry>> = remember(legacyRepository, migrationComplete) {
        if (migrationComplete == false) legacyRepository.observe() else flowOf(emptyList())
    }
    val legacyEntries by legacyEntriesFlow.collectAsState(initial = null)
    val legacyMembershipsFlow: Flow<List<HomeShelfMembership>> = remember(legacyRepository, migrationComplete) {
        if (migrationComplete == false) legacyRepository.observeMemberships() else flowOf(emptyList())
    }
    val legacyMemberships by legacyMembershipsFlow.collectAsState(initial = null)
    val favoritesFlow = remember(libraryRepository) { libraryRepository.observeFavorites() }
    val emulatedFavoritesState = favoritesFlow.collectAsState(initial = null)
    val platformsFlow = remember(libraryRepository) { libraryRepository.observePlatforms() }
    val platformsState = platformsFlow.collectAsState(initial = null)
    val androidGameProofs = remember(context) { LaunchStore.androidGameProofs(context) }
    // Editor's Choice is one mixed Favorite lane, so Android and emulated launches must share the same
    // successful-dispatch clock. Hold the first projection until Room has emitted rather than painting the
    // Android tail and visibly reordering it one frame later.
    val androidGameRecencyFlow = remember(androidGameProofs) {
        androidGameProofs.observeRecentlyPlayed(Int.MAX_VALUE)
    }
    val androidGameRecencyState = androidGameRecencyFlow.collectAsState(initial = null)
    val tomeDocumentFlow = remember { HomeTomeStore.document }
    val tomeDocumentState = tomeDocumentFlow.collectAsState()
    val tomeStoreReadyState = remember(context) { mutableStateOf(false) }
    var tomeStoreReady by tomeStoreReadyState
    LaunchedEffect(context) {
        HomeTomeStore.load(context)
        tomeStoreReady = true
    }
    val externalSessionElapsedFlow = remember(context) {
        HomeExternalSessionElapsedStore.observe(context)
    }
    val externalSessionElapsedTotalsState = externalSessionElapsedFlow.collectAsState()
    val nativeFavoriteOverridesState = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var nativeFavoriteOverrides by nativeFavoriteOverridesState
    val requiredPromotedEmulatedState = remember { mutableStateOf<Set<GameId>>(emptySet()) }
    var requiredPromotedEmulated by requiredPromotedEmulatedState
    LaunchedEffect(
        migrationComplete,
        legacyEntries,
        legacyMemberships,
        nativeGameRows,
        nativeRowsLoaded,
    ) {
        if (migrationComplete != false || legacyEntries == null || legacyMemberships == null || !nativeRowsLoaded) {
            return@LaunchedEffect
        }
        val plan = planLegacyHomeFavoritePromotion(
            memberships = legacyMemberships.orEmpty(),
            resolvedEntries = legacyEntries.orEmpty(),
            provenNativeGames = nativeGameRows,
        )
        requiredPromotedEmulated = plan.emulatedFavorites.mapTo(hashSetOf()) { it.gameId }
        if (HomeFavoritesMigration.run(context, plan, libraryRepository)) {
            nativeFavoriteOverrides = nativeFavoriteOverrides + plan.androidGamePackages.associateWith { true }
            migrationComplete = true
        }
    }
    LaunchedEffect(nativeGameRows) {
        nativeFavoriteOverrides = nativeFavoriteOverrides.filter { (packageName, favorite) ->
            nativeGameRows.firstOrNull { it.packageName == packageName }?.isFavorite != favorite
        }
    }
    val effectiveNativeGames = remember(nativeGameRows, nativeFavoriteOverrides) {
        nativeGameRows.map { row ->
            nativeFavoriteOverrides[row.packageName]?.let { row.copy(isFavorite = it) } ?: row
        }
    }
    val pendingLaunchReturnState = remember(context) { mutableStateOf<HomeLaunchReturnHandle?>(null) }
    var pendingLaunchReturn by pendingLaunchReturnState
    val launchReturnNeedsRouteState = remember(context) { mutableStateOf(false) }
    var launchReturnNeedsRoute by launchReturnNeedsRouteState
    val pendingSurfaceRestoreState = remember(context) { mutableStateOf<HomeSurfaceSnapshot?>(null) }
    var pendingSurfaceRestore by pendingSurfaceRestoreState
    val restorationTruthReadyState = remember(context) { mutableStateOf(false) }
    var restorationTruthReady by restorationTruthReadyState
    val homeViewModeState = remember(context) { mutableStateOf(HomeViewMode.EDITORS_CHOICE) }
    val editorsChoiceRootState = remember(context) { mutableStateOf<HomeRootEntryRecord?>(null) }
    val pinnedTomesRootState = remember(context) { mutableStateOf<HomeRootEntryRecord.Tome?>(null) }
    val homeSurfaceEpochState = remember(context) { mutableStateOf(0) }
    val homeSurfaceHandoffDirectionState = remember(context) { mutableStateOf(0) }
    val inputProjection = remember(context) { HomeInputProjectionAuthority() }
    val alphabetJumpFeedback = remember(context) { HomeAlphabetJumpFeedback() }
    val pendingRemovalIdsState = remember { mutableStateOf<Set<HomeShelfItemId>>(emptySet()) }
    LaunchedEffect(context, persistedLaunchReturnReady, persistedLaunchReturn?.token) {
        if (!persistedLaunchReturnReady || restorationTruthReady) return@LaunchedEffect
        pendingLaunchReturn = persistedLaunchReturn
        launchReturnNeedsRoute = persistedLaunchReturn != null
        pendingSurfaceRestore = if (persistedLaunchReturn == null) {
            HomeSurfaceStateStore.read(context)
        } else {
            null
        }
        restorationTruthReady = true
    }

    return HomeShelfDurableSources(
        libraryRepository = libraryRepository,
        migrationCompleteState = migrationCompleteState,
        emulatedFavoritesState = emulatedFavoritesState,
        platformsState = platformsState,
        androidGameProofs = androidGameProofs,
        androidGameRecencyState = androidGameRecencyState,
        tomeDocumentState = tomeDocumentState,
        tomeStoreReadyState = tomeStoreReadyState,
        externalSessionElapsedTotalsState = externalSessionElapsedTotalsState,
        nativeFavoriteOverridesState = nativeFavoriteOverridesState,
        requiredPromotedEmulatedState = requiredPromotedEmulatedState,
        effectiveNativeGames = effectiveNativeGames,
        pendingLaunchReturnState = pendingLaunchReturnState,
        launchReturnNeedsRouteState = launchReturnNeedsRouteState,
        pendingSurfaceRestoreState = pendingSurfaceRestoreState,
        restorationTruthReadyState = restorationTruthReadyState,
        homeViewModeState = homeViewModeState,
        editorsChoiceRootState = editorsChoiceRootState,
        pinnedTomesRootState = pinnedTomesRootState,
        homeSurfaceEpochState = homeSurfaceEpochState,
        homeSurfaceHandoffDirectionState = homeSurfaceHandoffDirectionState,
        inputProjection = inputProjection,
        alphabetJumpFeedback = alphabetJumpFeedback,
        pendingRemovalIdsState = pendingRemovalIdsState,
    )
}

/** The shelf's display rows and, beside them, the Tome wells' wired faces from the same resolutions. */
private class TiledHomeRows(val rows: List<HomeShelfRow>, val folioFaces: Map<HomeShelfItemId, MimirWiredFace>)

/** One row's file-state resolution: his chosen hero, the chosen clip that wins over it, and the faces; see [HomeRowTileMemo]. */
private class HomeRowTile(val chosenHero: String?, val chosenClip: String?, val faces: ResolvedShelfFaces)

/**
 * Resolves one Home row's tile from the files and prefs behind it. Everything it reads comes from [input] (the row's identity and
 * its own art), which is why [HomeRowTileMemo] can key on it. File stats and prefs reads: call it from the memo, or off the main thread.
 */
private fun resolveHomeRowTile(context: Context, input: HomeRowTileInputs): HomeRowTile {
    val key = when (val id = input.itemId) {
        is HomeShelfItemId.Emulated -> SteamGridDbTileFetcher.emulatedKey(id.gameId)
        // Both Android kinds share one tile-art key on purpose: the art belongs to the
        // PACKAGE, so re-categorising a package between the Games and Apps lanes keeps the
        // user's chosen icon. This cannot double-render — `pinOverrides` puts a package in
        // exactly one of the snapshot's games/apps lists, so at most one row projects.
        is HomeShelfItemId.AndroidGame -> SteamGridDbTileFetcher.androidKey(id.packageName.value)
        is HomeShelfItemId.AndroidApp -> SteamGridDbTileFetcher.androidKey(id.packageName.value)
    }
    // The chosen Hero belongs to the Home row identity. Android packages use the same durable
    // cache key as their Home Customize Media picker; clips remain emulated-game-only.
    val emulatedId = (input.itemId as? HomeShelfItemId.Emulated)?.gameId
    val mediaStoreId = homeTileHeroStoreId(input.itemId)
    val chosenHero = CustomHeroStore.overrideUri(context, mediaStoreId)
    val canonicalClip = emulatedId?.let {
        CustomHeroStore.previewClipUri(context, mediaStoreId)
    }
    // Pre-Saved clip picks used the raw game id. Keep that read-through until opening Customize
    // Media migrates the asset into the canonical Home cache key.
    val legacyClip = emulatedId?.let {
        CustomHeroStore.previewClipUri(context, it)
    }
    val clipStoreId = if (canonicalClip != null) mediaStoreId else emulatedId
    val clipUri = canonicalClip ?: legacyClip
    val chosenClip = if (clipUri == null || chosenHero == null) {
        clipUri
    } else {
        val heroSelection = effectiveGameIdentityMediaSelectionRecord(
            context,
            EnrichMediaKind.HERO,
            mediaStoreId.value,
        )
        val clipSelection = clipStoreId?.let {
            effectiveGameIdentityMediaSelectionRecord(context, EnrichMediaKind.CLIP, it.value)
        }
        clipUri.takeIf {
            homeChosenMotionWins(heroSelection, clipSelection)
        }
    }
    // ONE resolution decides every face this row can wear (one shelf face, one
    // "Icon art"): his pick by recorded kind, the automatic cover, the automatic SteamGridDB art only
    // without a cover, the row's own art, nothing. Resolved HERE rather than in the composable because
    // this is where the pick stores are read; threading a Context and file stats down into a tile would
    // have meant new work on every layer between. The URI is versioned with the file's mtime (FUX-10) and
    // built with `Uri.fromFile` (R1 fix) inside [resolveShelfFaces].
    val faces = resolveShelfFaces(
        context = context,
        cacheKey = key,
        hasAutomaticCover = input.hasAutomaticCover,
        automaticCoverUri = input.artworkUri,
    )
    return HomeRowTile(chosenHero, chosenClip, faces)
}

/** What [rememberHomeShelfProjection] hands the later pieces; a name ending in `State` is the live state object. */
private class HomeShelfProjectionParts(
    val homeFavoriteTruthReady: Boolean,
    val durableMembershipIds: LinkedHashSet<HomeShelfItemId>,
    val platformLabels: Map<PlatformId, String>,
    val durableRootProjection: FavoriteHomeProjection,
    val rootProjection: FavoriteHomeProjection,
    val editorsChoiceEntries: List<VisualHomeShelfEntry>,
    val orderedLooseGames: List<FavoriteHomeGame>,
    val pinnedTomesEntries: List<VisualHomeShelfEntry>,
    val visibleRootEntries: List<VisualHomeShelfEntry>,
    val tomeCatalog: FavoriteHomeTomeCatalog,
    val nativeProjection: NativeHomeLookup,
    val membershipIds: LinkedHashSet<HomeShelfItemId>,
    val tomeSessionState: MutableState<HomeTomeSession?>,
    val tomeLibraryReturnPendingFenceState: MutableState<Long?>,
    val tomeSessionGenerationState: MutableState<Long>,
    val tomeReturnStateState: MutableState<HomeShelfTomeReturnState>,
    val tomeReplayState: MutableState<HomeShelfTomeReplay?>,
    val pendingTomeProjectionState: MutableState<PendingTomeProjection?>,
    val tomeProjectionMutationGate: HomeTomeProjectionMutationGate,
    val contextRowsOrNull: List<HomeShelfRow>?,
    val sgdbEpochState: MutableState<Int>,
    val tiledDisplayRows: List<HomeShelfRow>,
    val folioFacesById: Map<HomeShelfItemId, MimirWiredFace>,
    val tomeModels: Map<TomeId, HomeShelfTome>,
    val homeOverviewSlots: List<FavoriteMemberId?>,
    val homeOverviewModel: HomeShelfTome,
    val visualEntries: List<VisualHomeShelfEntry>,
    val launchGamesById: Map<GameId, GameRow>,
)

/**
 * Second piece of [rememberHomeShelfCoordinator]: Favorite truth, the root projection, the Tome catalog,
 * the Tome-session / return state, the SteamGridDB tile effect and the tiled display rows. See
 * [rememberHomeShelfDurableSources] for why the composable is split.
 */
@Composable
private fun rememberHomeShelfProjection(
    context: Context,
    nativeGameRows: List<NativeShelfRow>,
    durableSources: HomeShelfDurableSources,
): HomeShelfProjectionParts {
    // Values the earlier pieces own, re-bound under their original names so the code below is unchanged.
    var migrationComplete by durableSources.migrationCompleteState
    val emulatedFavorites by durableSources.emulatedFavoritesState
    val platforms by durableSources.platformsState
    val androidGameRecency by durableSources.androidGameRecencyState
    val tomeDocument by durableSources.tomeDocumentState
    var tomeStoreReady by durableSources.tomeStoreReadyState
    val externalSessionElapsedTotals by durableSources.externalSessionElapsedTotalsState
    var requiredPromotedEmulated by durableSources.requiredPromotedEmulatedState
    val effectiveNativeGames = durableSources.effectiveNativeGames
    var restorationTruthReady by durableSources.restorationTruthReadyState
    var homeViewMode by durableSources.homeViewModeState
    var pendingRemovalIds by durableSources.pendingRemovalIdsState

    val homeFavoriteTruthReady = migrationComplete == true && tomeStoreReady && restorationTruthReady &&
        emulatedFavorites != null && androidGameRecency != null &&
        emulatedFavorites.orEmpty().mapTo(hashSetOf(), GameRow::gameId).containsAll(requiredPromotedEmulated)
    val nativeRecencyByPackage = remember(androidGameRecency) {
        androidGameRecency.orEmpty().associate { it.packageName to it.lastPlayedMillis }
    }
    val durableFavoriteGames = remember(
        emulatedFavorites,
        effectiveNativeGames,
        nativeRecencyByPackage,
        homeFavoriteTruthReady,
    ) {
        if (!homeFavoriteTruthReady) emptyList()
        else favoriteHomeGames(
            emulatedFavorites.orEmpty(),
            effectiveNativeGames,
            nativeRecencyByPackage,
        )
    }
    val durableMembershipIds = remember(durableFavoriteGames) {
        durableFavoriteGames.mapTo(linkedSetOf()) { it.id.toHomeShelfItemId() }
    }
    val favoriteGames = remember(durableFavoriteGames, pendingRemovalIds) {
        visibleFavoriteHomeGames(durableFavoriteGames, pendingRemovalIds)
    }
    val platformLabels = remember(platforms) {
        platforms.orEmpty().associate { it.platformId to it.label }
    }
    val durableRootProjection = remember(durableFavoriteGames, tomeDocument, platformLabels) {
        projectFavoriteHome(durableFavoriteGames, tomeDocument, platformLabels)
    }
    val rootProjection = remember(favoriteGames, durableRootProjection.document, platformLabels) {
        projectFavoriteHome(favoriteGames, durableRootProjection.document, platformLabels)
    }
    // Editor's Choice is the canonical Favorite lane only. Pinned Tomes have their own authored
    // mode and must never collapse, remove, or reorder the user's Favorite games here.
    val editorsChoiceEntries = remember(rootProjection.games, rootProjection.document) {
        favoriteEditorChoiceEntries(rootProjection)
    }
    val orderedLooseGames = remember(rootProjection.games, rootProjection.document) {
        orderedLooseHomeGames(rootProjection)
    }
    val pinnedTomesEntries = remember(rootProjection.entries) {
        customPinnedHomeTomeEntries(rootProjection.entries)
    }
    val visibleRootEntries = when (homeViewMode) {
        HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntries
        HomeViewMode.PINNED_TOMES -> pinnedTomesEntries
    }
    // A rendered projection is read-only. In particular, transient Favorite truth may omit a smart
    // platform Tome, but that must never write an implicit unpin back into the user's document.
    val tomeCatalog = remember(durableFavoriteGames, durableRootProjection.document, platformLabels) {
        favoriteHomeTomeCatalog(durableFavoriteGames, durableRootProjection.document, platformLabels)
    }
    val nativeProjection = remember(nativeGameRows) {
        nativeHomeLookup(nativeGameRows)
    }
    val membershipIds = remember(rootProjection.eligibleIds) {
        rootProjection.eligibleIds.mapTo(linkedSetOf(), FavoriteMemberId::toHomeShelfItemId)
    }
    val tomeSessionState = remember { mutableStateOf<HomeTomeSession?>(null) }
    // CP02: the arrival-epoch fence a Folio-return teardown is waiting on, or null when none is pending.
    // Exposed on HomeShelfCoordinator (a plain class -- cheap) so HomeTomeLibraryReturnReleaseEffect
    // (MimirAppCoordinator.kt) can do the actual wait OUTSIDE this composable, which must not grow a
    // navigation-coordinator PARAMETER again -- see closeTomeToLibrary's kdoc.
    val tomeLibraryReturnPendingFenceState = remember { mutableStateOf<Long?>(null) }
    val tomeSessionGenerationState = remember { mutableStateOf(0L) }
    val tomeReturnStateState = remember { mutableStateOf(HomeShelfTomeReturnState()) }
    val tomeReplayState = remember { mutableStateOf<HomeShelfTomeReplay?>(null) }
    val pendingTomeProjectionState = remember { mutableStateOf<PendingTomeProjection?>(null) }
    val tomeProjectionMutationGate = remember { HomeTomeProjectionMutationGate() }
    val contextRowsOrNull = if (!homeFavoriteTruthReady) null else rootProjection.rows
    val rowsHolder = remember { RowsHolder() }
    val displayRows = contextRowsOrNull?.also {
        rowsHolder.last = it
    } ?: rowsHolder.last
    // RC-48: Home is where every session starts, so the Library shelf's console-art manifest is parsed here, off the main thread,
    // instead of by the first platform tile on the composition thread. Local to this surface; nothing in the app warmup is involved.
    LaunchedEffect(context) { withContext(Dispatchers.IO) { primePlatformConsoleArt(context) } }
    // FUX-04 SGDB tile TEST: fetch missing square tiles for SHELVED emulated games only,
    // behind a verified user SteamGridDB key and [MimirSteamGridDbGate]. ScreenScraper opt-in is unrelated.
    // Cache is app-internal (filesDir/sgdb2) — delete it and the ScreenScraper covers return.
    val sgdbEpochState = remember { mutableStateOf(0) }
    var sgdbEpoch by sgdbEpochState
    val sgdbKey by SteamGridDbKeyStore.key.collectAsState()
    // The effect is keyed on WHAT IT FETCHES FOR -- the cache key -> title map and the set of PC games whose Steam app id it
    // looks up -- and not on the favourites row list. The tile it installs changes `GameRow.wiredFace`, the repository
    // re-emits the list, and an effect keyed on the list re-ran on its own publish (review item, 2026-10-10). Both keys are
    // structural, so a re-emission that changes no title and adds no PC game leaves the effect alone.
    val sgdbTitles = remember(emulatedFavorites) {
        buildMap { emulatedFavorites.orEmpty().forEach { put(SteamGridDbTileFetcher.emulatedKey(it.gameId), it.title) } }
    }
    val sgdbPcGameIds = remember(emulatedFavorites) {
        emulatedFavorites.orEmpty().filter { it.platformId == PlatformId.PC_GAMES }.mapTo(linkedSetOf(), GameRow::gameId)
    }
    LaunchedEffect(sgdbTitles, sgdbPcGameIds, sgdbKey) {
        if (sgdbKey.isBlank() || !MimirSteamGridDbGate.isOpen) return@LaunchedEffect
        val titles = sgdbTitles
        if (titles.isEmpty()) return@LaunchedEffect
        // a favourited PC game already carries its Steam app id — the exact
        // SteamGridDB identity R-23 already uses for heroes — so this automatic tile fetch is given the
        // same id instead of falling back to title search (declined by R-23 for exactly this reason:
        // sequels/editions are where a fuzzy title match goes wrong). Filtered to PC_GAMES before the
        // resolve call: pcGameSteamAppId self-guards for other platforms but still costs a DAO read plus
        // a file parse, which is wasted IO on a Compose effect for every non-PC favourite.
        val steamAppIds = buildMap {
            emulatedFavorites.orEmpty()
                .filter { it.gameId in sgdbPcGameIds }
                .forEach { game ->
                    pcGameSteamAppId(context, game)?.let { appId ->
                        put(SteamGridDbTileFetcher.emulatedKey(game.gameId), appId)
                    }
                }
        }
        if (SteamGridDbTileFetcher.fetchMissing(context, sgdbKey, titles, steamAppIds) > 0) {
            sgdbEpoch++
        }
    }
    // RC-48 (audit 2026-10-14): the per-row file-state resolution below is memoised per row, under the epoch. It used to run for
    // every row (about 10-15 stats and prefs reads each) on the composition thread at every change of `displayRows`, and a game's
    // return reorders them all. A row's resolution depends on its identity and its own art alone, so a reorder resolves nothing;
    // `sgdbEpoch` (a tile write, or any Customize Media apply) drops the memo, so a fresh pick is never cached away. A miss still
    // resolves right here, so the first frame is correct and nothing is handed off.
    val tileMemo = remember { HomeRowTileMemo<HomeRowTile>() }
    val tiledRows = remember(
        displayRows,
        sgdbEpoch,
        externalSessionElapsedTotals,
    ) {
        // The Tome wells' "wired" face, taken from the SAME resolution that feeds each row below, so opening a folio costs
        // no resolver call (each is file stats on the UI thread). It is built and invalidated together with the rows.
        val folioFaces = HashMap<HomeShelfItemId, MimirWiredFace>()
        val tiles = tileMemo.resolve(
            sgdbEpoch,
            displayRows.map { row -> HomeRowTileInputs(row.itemId, row.hasOwnCoverArt(), row.artworkUri) },
        ) { input -> resolveHomeRowTile(context, input) }
        val rows = displayRows.mapIndexed { index, row ->
            val tile = tiles[index]
            val faces = tile.faces
            // The row still carries the shape in two legacy fields, fed from this ONE result by [withShelfFaces]: a
            // square rides `tileIconUri` (the Switch tile) and everything else rides `faceUri` with its kind. The
            // shape is never re-derived from which of them is set -- that re-derivation painted a square pick as a
            // box once.
            row.copy(
                chosenHeroUri = tile.chosenHero,
                chosenPreviewVideoUri = tile.chosenClip,
                externalSessionElapsedMillis = externalSessionElapsedTotals[row.itemId],
            ).withShelfFaces(faces).also {
                // Emulated games only: an Android row's tile is its launcher icon, never a picked box.
                if (row.itemId is HomeShelfItemId.Emulated) {
                    faces.folioWiredFace()?.let { folioFaces[row.itemId] = it }
                }
            }
        }
        TiledHomeRows(rows, folioFaces)
    }
    val tiledDisplayRows = tiledRows.rows
    val tiledRowsById = remember(tiledDisplayRows) { tiledDisplayRows.associateBy(HomeShelfRow::itemId) }
    val tomeModels = remember(tomeCatalog, tiledRowsById, sgdbEpoch) {
        tomeCatalog.tomes.mapValues { (id, tome) ->
            val preview = tomeCatalog.eligibleMembersByTome[id].orEmpty()
                .mapNotNull { member -> tiledRowsById[member.toHomeShelfItemId()] }
                .mapNotNull { row ->
                    // The row already carries the one resolved face (`faceUri`, `faceIsPhysicalMedia`,
                    // `tileIconUri`), so the Tome's preview paints what Home paints; the cartridge store is no
                    // longer read separately here.
                    row.toHomeTomePreviewOrNull(selectedCartridgeUri = null)
                }
                .take(4)
            tome.copy(previews = preview)
        }
    }
    val homeOverviewMemberIds = remember(editorsChoiceEntries) {
        editorsChoiceEntries.mapNotNull { entry ->
            (entry as? VisualHomeShelfEntry.Game)?.itemId?.toFavoriteMemberIdOrNull()
        }
    }
    val homeOverviewSlots = remember(homeOverviewMemberIds, tomeDocument.homeShelfSlots) {
        projectSparseTomeSlots(
            eligibleOrder = homeOverviewMemberIds,
            storedSlots = tomeDocument.homeShelfSlots,
        )
    }
    // The Home shelf's Tome wears the player's language wherever a Tome is drawn; the title is a key of the remembered model, so a language change rebuilds it.
    val homeShelfTitle = appResources().getString(R.string.app_tome_home_shelf_title)
    val homeOverviewModel = remember(homeOverviewMemberIds, tiledRowsById, homeShelfTitle) {
        homeOverviewTome(homeShelfTitle, homeOverviewMemberIds, tiledRowsById)
    }
    val visualEntriesHolder = remember(homeViewMode) { HomeEntriesHolder() }
    val visualEntries = visibleRootEntries.takeIf { homeFavoriteTruthReady }?.also {
        visualEntriesHolder.last = it
    } ?: visualEntriesHolder.last
    // RowsHolder can keep the previous visible lane during a cold/restarting flow. Keep its exact
    // GameRow lookup in lockstep; otherwise the tile stays visible but RA count/open and launch become
    // impossible until the source re-emits (the Pass 2 video captured both RA symptoms at once).
    val gameRowsHolder = remember { HomeGameRowsHolder() }
    val currentGameRows: Map<GameId, GameRow>? = remember(emulatedFavorites, migrationComplete) {
        if (migrationComplete != true || emulatedFavorites == null) null
        else emulatedFavorites.orEmpty().associateBy(GameRow::gameId)
    }
    val launchGamesById = gameRowsHolder.resolve(currentGameRows)

    return HomeShelfProjectionParts(
        homeFavoriteTruthReady = homeFavoriteTruthReady,
        durableMembershipIds = durableMembershipIds,
        platformLabels = platformLabels,
        durableRootProjection = durableRootProjection,
        rootProjection = rootProjection,
        editorsChoiceEntries = editorsChoiceEntries,
        orderedLooseGames = orderedLooseGames,
        pinnedTomesEntries = pinnedTomesEntries,
        visibleRootEntries = visibleRootEntries,
        tomeCatalog = tomeCatalog,
        nativeProjection = nativeProjection,
        membershipIds = membershipIds,
        tomeSessionState = tomeSessionState,
        tomeLibraryReturnPendingFenceState = tomeLibraryReturnPendingFenceState,
        tomeSessionGenerationState = tomeSessionGenerationState,
        tomeReturnStateState = tomeReturnStateState,
        tomeReplayState = tomeReplayState,
        pendingTomeProjectionState = pendingTomeProjectionState,
        tomeProjectionMutationGate = tomeProjectionMutationGate,
        contextRowsOrNull = contextRowsOrNull,
        sgdbEpochState = sgdbEpochState,
        tiledDisplayRows = tiledDisplayRows,
        folioFacesById = tiledRows.folioFaces,
        tomeModels = tomeModels,
        homeOverviewSlots = homeOverviewSlots,
        homeOverviewModel = homeOverviewModel,
        visualEntries = visualEntries,
        launchGamesById = launchGamesById,
    )
}

/** What [rememberHomeShelfFolio] hands the later pieces; a name ending in `State` is the live state object. */
private class HomeShelfFolioParts(
    val mutationIdsState: MutableState<Set<HomeShelfItemId>>,
    val mutationRevisionState: MutableState<Long>,
    val toastState: MutableState<MimirMessage?>,
    val scope: CoroutineScope,
    val emitMessageWithSupport: (MimirMessageClass, String, String?) -> Unit,
    val emitMessage: (MimirMessageClass, String) -> Unit,
    val consumeDeliveredMessage: (Long) -> Unit,
    val folioFocusedIndexState: MutableState<Int>,
    val tomeFolioFocusMemoryState: MutableState<Map<TomeId, HomeTomeFolioFocusMemory>>,
    val folioTransitionState: MutableState<HomeTomePageTransition>,
    val resetFolioFocusSettlement: () -> Unit,
    val tomeArrangeStateState: MutableState<HomeTomeArrangeState?>,
    val folioRows: List<HomeShelfRow>,
    val tomeFolioState: HomeTomeFolioUiState?,
    val focusTomeMember: (Int) -> Unit,
    val settleTomeFolioFocus: (HomeTomeFolioFocusTarget) -> Unit,
    val unsettleTomeFolioFocus: () -> Unit,
    val captureAuthoritativeTomeFolioFocus: () -> Unit,
    val navigateTomeFolio: (HomeTomeNavigationUpdate) -> Unit,
    val routeTomeFolioAction: (ControllerAction) -> HomeTomeFolioActionRequest?,
    val enterTomeArrange: () -> Unit,
    val dispatchTomeArrange: (ControllerAction) -> Unit,
    val dispatchTouchTomeArrange: (HomeTomeArrangeIntent) -> Unit,
    val cancelTomeResidue: () -> Unit,
    val launchingRowState: MutableState<HomeShelfRow?>,
)

/**
 * Third piece of [rememberHomeShelfCoordinator]: mutation and toast state, the Folio's derived rows, slots
 * and focus state, and the Folio's focus / navigation / Arrange actions. See
 * [rememberHomeShelfDurableSources] for why the composable is split.
 */
@Composable
private fun rememberHomeShelfFolio(
    context: Context,
    focusAuthority: FocusAuthority,
    durableSources: HomeShelfDurableSources,
    shelfProjection: HomeShelfProjectionParts,
): HomeShelfFolioParts {
    // Values the earlier pieces own, re-bound under their original names so the code below is unchanged.
    var pendingRemovalIds by durableSources.pendingRemovalIdsState
    val homeFavoriteTruthReady = shelfProjection.homeFavoriteTruthReady
    val durableMembershipIds = shelfProjection.durableMembershipIds
    val tomeCatalog = shelfProjection.tomeCatalog
    var tomeSession by shelfProjection.tomeSessionState
    val tiledDisplayRows = shelfProjection.tiledDisplayRows
    val folioFacesById = shelfProjection.folioFacesById
    val tomeModels = shelfProjection.tomeModels
    val homeOverviewSlots = shelfProjection.homeOverviewSlots
    val homeOverviewModel = shelfProjection.homeOverviewModel
    val launchGamesById = shelfProjection.launchGamesById

    val mutationIdsState = remember { mutableStateOf<Set<HomeShelfItemId>>(emptySet()) }
    val mutationRevisionState = remember { mutableStateOf(0L) }
    var toastEventId by remember { mutableStateOf(0L) }
    val toastState = remember { mutableStateOf<MimirMessage?>(null) }
    var toast by toastState
    val scope = rememberCoroutineScope()
    // F7: the optional supporting line is the rail's own second line (MimirMessage.supportingText).
    val emitMessageWithSupport: (MimirMessageClass, String, String?) -> Unit =
        { messageClass, text, supportingText ->
            toastEventId += 1L
            toast = MimirMessage(
                eventId = toastEventId,
                text = text,
                messageClass = messageClass,
                supportingText = supportingText,
            )
            if (messageClass.requiresFailureFeedback && !WelcomeHold.active) {
                MimirUiSounds.play(MimirSoundEvent.Error)
            }
        }
    val emitMessage: (MimirMessageClass, String) -> Unit = { messageClass, text ->
        emitMessageWithSupport(messageClass, text, null)
    }
    val consumeDeliveredMessage: (Long) -> Unit = { deliveredEventId ->
        toast = consumeDeliveredHomeMessage(toast, deliveredEventId)
    }
    LaunchedEffect(pendingRemovalIds, durableMembershipIds, homeFavoriteTruthReady) {
        if (homeFavoriteTruthReady) {
            pendingRemovalIds = reconcilePendingFavoriteRemovals(pendingRemovalIds, durableMembershipIds)
        }
    }
    val folioFocusedIndexState = remember { mutableStateOf(0) }
    var folioFocusedIndex by folioFocusedIndexState
    // Session-local, per-Tome focus memory. It is written only by the composable's authoritative
    // settlement callback, never by speculative directional/touch reducer state.
    val tomeFolioFocusMemoryState = remember { mutableStateOf<Map<TomeId, HomeTomeFolioFocusMemory>>(emptyMap()) }
    var tomeFolioFocusMemory by tomeFolioFocusMemoryState
    val folioTransitionState = remember { mutableStateOf(HomeTomePageTransition()) }
    var folioTransition by folioTransitionState
    var folioRequestedFocusTarget by remember { mutableStateOf<HomeTomeFolioFocusTarget?>(null) }
    var folioSettledFocusTarget by remember { mutableStateOf<HomeTomeFolioFocusTarget?>(null) }
    var pendingFolioArrangeActivation by remember { mutableStateOf<PendingFolioArrangeActivation?>(null) }
    val folioFocusState by focusAuthority.state.collectAsState()
    val resetFolioFocusSettlement = {
        folioRequestedFocusTarget = null
        folioSettledFocusTarget = null
        pendingFolioArrangeActivation = null
    }
    val tomeArrangeStateState = remember { mutableStateOf<HomeTomeArrangeState?>(null) }
    var tomeArrangeState by tomeArrangeStateState
    var tomeArrangeTransactionSequence by remember { mutableStateOf(1L) }
    var tomeOrderCommitGeneration by remember { mutableStateOf(0L) }
    var pendingTomeOrderCommit by remember { mutableStateOf<PendingTomeOrderCommit?>(null) }
    val durableFolioSlots = remember(tomeSession?.tomeId, tomeCatalog, homeOverviewSlots) {
        when (val folioId = tomeSession?.tomeId) {
            TomeId.HomeOverview -> homeOverviewSlots
            null -> emptyList()
            else -> tomeCatalog.eligibleMemberSlotsByTome[folioId].orEmpty()
        }.map { it?.toHomeShelfItemId() }
    }
    val visibleFolioSlots = remember(
        tomeSession?.tomeId,
        durableFolioSlots,
        tomeArrangeState,
        pendingTomeOrderCommit,
    ) {
        tomeArrangeState?.workingSlots
            ?: pendingTomeOrderCommit
                ?.takeIf { it.tomeId == tomeSession?.tomeId }
                ?.let { visibleOptimisticTomeSlots(it.slots, durableFolioSlots) }
            ?: durableFolioSlots
    }
    val folioSlotRows = remember(tiledDisplayRows, visibleFolioSlots) {
        val byId = tiledDisplayRows.associateBy(HomeShelfRow::itemId)
        visibleFolioSlots.map { itemId -> itemId?.let(byId::get) }
    }
    val folioRows = remember(folioSlotRows) { folioSlotRows.filterNotNull() }
    val pendingCommitDurableSlots = remember(
        pendingTomeOrderCommit?.tomeId,
        tomeCatalog,
        homeOverviewSlots,
    ) {
        when (val folioId = pendingTomeOrderCommit?.tomeId) {
            TomeId.HomeOverview -> homeOverviewSlots
            null -> emptyList()
            else -> tomeCatalog.eligibleMemberSlotsByTome[folioId].orEmpty()
        }.map { it?.toHomeShelfItemId() }
    }
    LaunchedEffect(pendingTomeOrderCommit, pendingCommitDurableSlots) {
        val pending = pendingTomeOrderCommit ?: return@LaunchedEffect
        if (pending.writeSucceeded &&
            visibleOptimisticTomeSlots(pending.slots, pendingCommitDurableSlots) == pendingCommitDurableSlots
        ) {
            pendingTomeOrderCommit = null
        }
    }
    // **A folio open resolves no face.** measured 31 ms of UI-thread work per open, ~95% of it a
    // per-row face read (file stats on three stores). The wells' wired face is now taken from the resolution each row
    // already got when `tiledRows` was built ([folioFacesById]), so it is computed once per game per projection and an
    // open reads a map. Deliberately NOT a cache inside `resolveShelfFaces`: that is read by the launch card and by
    // `WiredFaceStore`'s fallbacks, where a stale entry would be a visibly wrong cover on surfaces nobody measured.
    //
    // No new PARAMETER, deliberately: this function compiles to ~275 dex registers and a signature change has
    // VerifyError'd twice (2026-09-02, 2026-09-03). One local replaces the memo local, so the count does not move.
    val folioMemberSlots = remember(
        folioSlotRows,
        launchGamesById,
        folioFacesById,
    ) {
        folioSlotRows.map { row ->
            row?.let { resolvedRow ->
                val memberGame = (resolvedRow.itemId as? HomeShelfItemId.Emulated)
                    ?.let { launchGamesById[it.gameId] }
                // C4, closed here on 2026-08-25 the same way it was closed for the Home shelf: this read
                // only the CARTRIDGE store, so a cover he picked by hand could never appear in a Tome
                // well. It also now carries the KIND, because the well used to infer it by comparing
                // URIs -- which said "cover" to every downloaded disc and would have said "disc" to the
                // picked cover this line makes reachable.
                val memberFace = folioFacesById[resolvedRow.itemId]
                HomeTomeMember(
                    itemId = resolvedRow.itemId,
                    title = resolvedRow.title,
                    artworkUri = resolvedRow.tileIconUri ?: resolvedRow.artworkUri,
                    platformId = memberGame?.platformId,
                    physicalMediaUri = memberFace?.uri,
                    wiredFaceShape = if (memberFace?.physical == false) ShelfFaceShape.LEDGE_COVER else ShelfFaceShape.PHYSICAL,
                    // Walk-32c: the hero art (chosen, then downloaded) before the cover, as the Tome's preview does.
                    backdropArtUri = resolvedRow.chosenHeroUri ?: resolvedRow.heroArtUri ?: resolvedRow.artworkUri,
                )
            }
        }
    }
    val folioMembers = remember(folioMemberSlots) { folioMemberSlots.filterNotNull() }
    val folioMemberIds = remember(folioMembers) {
        folioMembers.mapTo(hashSetOf(), HomeTomeMember::itemId)
    }
    LaunchedEffect(folioMembers.map(HomeTomeMember::itemId), visibleFolioSlots) {
        tomeArrangeState = tomeArrangeState?.let { arrange ->
            reconcileHomeTomeArrange(arrange, folioMembers.mapTo(linkedSetOf(), HomeTomeMember::itemId))
        }?.takeIf { it.workingSlots.any { slot -> slot != null } }
        if (tomeArrangeState == null) {
            folioFocusedIndex = if (visibleFolioSlots.isEmpty()) 0 else {
                folioFocusedIndex.coerceIn(0, visibleFolioSlots.lastIndex)
            }
        }
    }
    val tomeFolioState = tomeSession?.let { session ->
        (if (session.tomeId == TomeId.HomeOverview) homeOverviewModel else tomeModels[session.tomeId])?.let { tome ->
            val mode = if (tomeArrangeState == null) HomeTomeFolioMode.BROWSE else HomeTomeFolioMode.ARRANGE
            val focusedIndex = tomeArrangeState?.focusedIndex ?: folioFocusedIndex
            val expectedFocusTarget = homeTomeFolioFocusTarget(
                tomeId = tome.tomeId,
                memberSlots = folioMemberSlots,
                focusedIndex = focusedIndex,
                mode = mode,
            )
            HomeTomeFolioUiState(
                tome = tome,
                members = folioMembers,
                memberSlots = folioMemberSlots,
                focusedIndex = focusedIndex,
                mode = mode,
                transition = tomeArrangeState?.pageTransition ?: folioTransition,
                carriedItemId = tomeArrangeState?.carriedItemId,
                insertionIndex = tomeArrangeState?.focusedIndex?.takeIf {
                    tomeArrangeState?.carriedItemId != null
                },
                arrangeTransactionId = tomeArrangeState?.activeTransactionId,
                nextArrangeTransactionId = tomeArrangeState?.nextTransactionId
                    ?: tomeArrangeTransactionSequence,
                focusSettled = expectedFocusTarget != null &&
                    folioSettledFocusTarget == expectedFocusTarget,
                settledFocusTarget = folioSettledFocusTarget,
            )
        }
    }
    val requestFolioSeatFocus: (Int, List<HomeShelfItemId?>) -> Unit = seat@{ index, slots ->
        val key = slots.getOrNull(index)?.takeIf(folioMemberIds::contains)?.let(::homeTomeMemberFocusKey)
            ?: if (index in slots.indices) {
                tomeSession?.tomeId?.let { tomeId -> homeTomeSeatFocusKey(tomeId, index) }
            } else {
                null
            }
            ?: return@seat
        val target = HomeTomeFolioFocusTarget(index, key)
        // A carried identity can keep the same FocusKey while moving seats. Invalidate only when the
        // exact seat+key target changes; mode entry, lift/drop, duplicate retargets and edge no-ops
        // retain their already-painted settlement.
        if (folioRequestedFocusTarget != target) {
            folioRequestedFocusTarget = target
            folioSettledFocusTarget = null
            pendingFolioArrangeActivation = null
        }
        focusAuthority.rememberFocusForScope(HomeTomeFolioScope, key)
        focusAuthority.requestFocus(key)
    }
    val requestFolioFocus: (Int) -> Unit = { index ->
        requestFolioSeatFocus(index, visibleFolioSlots)
    }
    val focusTomeMember: (Int) -> Unit = { index ->
        val safe = if (visibleFolioSlots.isEmpty()) 0 else index.coerceIn(0, visibleFolioSlots.lastIndex)
        val oldPage = tomeFolioState?.pageIndex ?: 0
        folioFocusedIndex = safe
        tomeArrangeState = tomeArrangeState?.copy(focusedIndex = safe)
        requestFolioFocus(safe)
        val newPage = if (folioMemberSlots.isEmpty()) 0 else safe / HomeTomeFolioPageSize
        if (newPage != oldPage) folioTransition = retargetHomeTomePageTransition(folioTransition, newPage)
    }
    val settleTomeFolioFocus: (HomeTomeFolioFocusTarget) -> Unit = { target ->
        if (focusAuthority.state.value.scope == HomeTomeFolioScope &&
            focusAuthority.state.value.focused == target.key &&
            tomeFolioState?.focusTarget == target
        ) {
            folioRequestedFocusTarget = target
            folioSettledFocusTarget = target
            tomeFolioState?.let { settled ->
                tomeFolioFocusMemory = tomeFolioFocusMemory + (
                    settled.tome.tomeId to HomeTomeFolioFocusMemory(
                        itemId = settled.memberSlots.getOrNull(target.seatIndex)?.itemId,
                        seatIndex = target.seatIndex,
                        wasEmptySeat = settled.memberSlots.getOrNull(target.seatIndex) == null &&
                            target.seatIndex in settled.memberSlots.indices,
                    )
                )
            }
        }
    }
    val unsettleTomeFolioFocus: () -> Unit = {
        folioSettledFocusTarget = null
    }
    val captureAuthoritativeTomeFolioFocus: () -> Unit = {
        authoritativeHomeTomeFocusCapture(
            state = tomeFolioState,
            scopeIsFolio = focusAuthority.state.value.scope == HomeTomeFolioScope,
            focusedKey = focusAuthority.state.value.focused,
        )?.let { capture ->
            tomeFolioFocusMemory = tomeFolioFocusMemory + (capture.tomeId to capture.memory)
        }
    }
    val navigateTomeFolio: (HomeTomeNavigationUpdate) -> Unit = { update ->
        folioFocusedIndex = update.targetIndex
        folioTransition = update.transition
        requestFolioFocus(update.targetIndex)
    }
    val routeTomeFolioAction: (ControllerAction) -> HomeTomeFolioActionRequest? = route@{ action ->
        // Any later input supersedes a buffered A. In particular, direction/back must never allow an
        // unsettled drop to replay at the next destination or after Arrange is exited and re-entered.
        if (action != ControllerAction.CONFIRM) pendingFolioArrangeActivation = null
        val session = tomeSession ?: return@route null
        val tome = if (session.tomeId == TomeId.HomeOverview) {
            homeOverviewModel
        } else {
            tomeModels[session.tomeId]
        } ?: return@route null
        val currentArrange = tomeArrangeState
        val currentMemberSlots = currentArrange?.workingSlots?.let { slots ->
            val byId = folioMembers.associateBy(HomeTomeMember::itemId)
            slots.map { itemId -> itemId?.let(byId::get) }
        } ?: folioMemberSlots
        val currentMembers = currentMemberSlots.filterNotNull()
        val currentMode = if (currentArrange == null) HomeTomeFolioMode.BROWSE else HomeTomeFolioMode.ARRANGE
        val currentFocusedIndex = currentArrange?.focusedIndex ?: folioFocusedIndex
        if (pendingTomeOrderCommit != null &&
            currentMode == HomeTomeFolioMode.ARRANGE &&
            action == ControllerAction.CONFIRM
        ) {
            return@route HomeTomeFolioActionRequest.Consumed
        }
        val currentFocusTarget = homeTomeFolioFocusTarget(
            tomeId = tome.tomeId,
            memberSlots = currentMemberSlots,
            focusedIndex = currentFocusedIndex,
            mode = currentMode,
        )
        routeHomeTomeFolioAction(
            HomeTomeFolioUiState(
                tome = tome,
                members = currentMembers,
                memberSlots = currentMemberSlots,
                focusedIndex = currentFocusedIndex,
                mode = currentMode,
                transition = currentArrange?.pageTransition ?: folioTransition,
                carriedItemId = currentArrange?.carriedItemId,
                insertionIndex = currentArrange?.focusedIndex?.takeIf {
                    currentArrange?.carriedItemId != null
                },
                arrangeTransactionId = currentArrange?.activeTransactionId,
                nextArrangeTransactionId = currentArrange?.nextTransactionId
                    ?: tomeArrangeTransactionSequence,
                focusSettled = currentFocusTarget != null &&
                    folioSettledFocusTarget == currentFocusTarget,
                settledFocusTarget = folioSettledFocusTarget,
            ),
            action,
        )
    }
    val enterTomeArrange: () -> Unit = {
        if (folioMembers.isNotEmpty() && tomeArrangeState == null) {
            val entered = beginHomeTomeArrangeSlots(
                visibleFolioSlots,
                folioFocusedIndex,
                tomeArrangeTransactionSequence,
            )
            tomeArrangeState = entered
            requestFolioSeatFocus(entered.focusedIndex, entered.workingSlots)
        } else if (tomeArrangeState?.carriedItemId == null) {
            val leaving = tomeArrangeState
            val settled = leaving?.focusedIndex
            if (settled != null) {
                folioFocusedIndex = settled
                requestFolioSeatFocus(settled, leaving.workingSlots)
            }
            tomeArrangeState = null
        }
    }
    val persistTomeOrder: (
        TomeId,
        Long,
        List<HomeShelfItemId?>,
        List<HomeShelfItemId?>,
        Int,
    ) -> Unit = { tomeId, transactionId, committed, rollbackSlots, rollbackFocusIndex ->
        tomeOrderCommitGeneration = if (tomeOrderCommitGeneration == Long.MAX_VALUE) {
            Long.MIN_VALUE
        } else {
            tomeOrderCommitGeneration + 1L
        }
        val generation = tomeOrderCommitGeneration
        pendingTomeOrderCommit = PendingTomeOrderCommit(
            generation = generation,
            transactionId = transactionId,
            tomeId = tomeId,
            slots = committed,
            rollbackSlots = rollbackSlots,
            rollbackFocusIndex = rollbackFocusIndex,
        )
        scope.launch {
            val persisted = try {
                when (tomeId) {
                    is TomeId.Custom -> HomeTomeStore.commitCustomMemberSlots(
                        context,
                        tomeId,
                        committed.map { it?.toFavoriteMemberIdOrNull() },
                    )
                    is TomeId.Platform -> HomeTomeStore.setPlatformSlotOverlay(
                        context,
                        tomeId.platformId,
                        committed.map { (it as? HomeShelfItemId.Emulated)?.gameId },
                    )
                    TomeId.HomeOverview -> HomeTomeStore.commitHomeShelfSlots(
                        context,
                        committed.map { it?.toFavoriteMemberIdOrNull() },
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
            val current = pendingTomeOrderCommit?.takeIf { it.generation == generation }
                ?: return@launch
            if (persisted) {
                pendingTomeOrderCommit = current.copy(writeSucceeded = true)
            } else {
                pendingTomeOrderCommit = null
                if (tomeSession?.tomeId == current.tomeId) {
                    val rollbackFocus = if (current.rollbackSlots.isEmpty()) 0 else {
                        current.rollbackFocusIndex.coerceIn(0, current.rollbackSlots.lastIndex)
                    }
                    folioFocusedIndex = rollbackFocus
                    tomeArrangeState = tomeArrangeState?.let {
                        beginHomeTomeArrangeSlots(
                            current.rollbackSlots,
                            rollbackFocus,
                            tomeArrangeTransactionSequence,
                        )
                    }
                    requestFolioSeatFocus(rollbackFocus, current.rollbackSlots)
                }
                emitMessage(MimirMessageClass.Warning, "Tome order not saved")
            }
        }
    }
    val dispatchTomeArrange: (ControllerAction) -> Unit = arrange@{ action ->
        if (pendingTomeOrderCommit != null && action == ControllerAction.CONFIRM) return@arrange
        val current = tomeArrangeState ?: return@arrange
        // Key bursts can route HAT then A before Compose publishes a new tomeFolioState. Derive the
        // activation target from the reducer state in this same dispatch, never from captured UI state.
        val liveMembersById = folioMembers.associateBy(HomeTomeMember::itemId)
        val liveMemberSlots = current.workingSlots.map { itemId -> itemId?.let(liveMembersById::get) }
        val liveTarget = homeTomeFolioFocusTarget(
            tomeId = tomeSession?.tomeId ?: return@arrange,
            memberSlots = liveMemberSlots,
            focusedIndex = current.focusedIndex,
            mode = HomeTomeFolioMode.ARRANGE,
        )
        val liveSettled = homeTomeFolioTargetIsSettledForActivation(
            liveTarget = liveTarget,
            settledTarget = folioSettledFocusTarget,
            scopeIsFolio = folioFocusState.scope == HomeTomeFolioScope,
            focusedKey = folioFocusState.focused,
        )
        if (action == ControllerAction.CONFIRM && !liveSettled) {
            val target = liveTarget ?: return@arrange
            pendingFolioArrangeActivation = PendingFolioArrangeActivation(
                target = target,
                action = action,
                mode = HomeTomeFolioMode.ARRANGE,
                carriedItemId = current.carriedItemId,
                transactionId = current.activeTransactionId ?: current.nextTransactionId,
            )
            return@arrange
        }
        if (action != ControllerAction.CONFIRM) pendingFolioArrangeActivation = null
        val update = reduceHomeTomeArrange(current, action)
        tomeArrangeTransactionSequence = update.state.nextTransactionId
        tomeArrangeState = if (update.exitRequested) null else update.state
        folioFocusedIndex = update.state.focusedIndex
        requestFolioSeatFocus(
            update.state.focusedIndex,
            update.state.workingSlots,
        )
        update.navigation?.let { folioTransition = it.transition }
        if (update.navigation?.moved == true) MimirNavFeedback.consumed()
        val committed = update.committedSlots ?: return@arrange
        val transactionId = update.committedTransactionId ?: return@arrange
        val rollbackSlots = update.rollbackSlots ?: return@arrange
        val rollbackFocusIndex = update.rollbackFocusIndex ?: return@arrange
        val tomeId = tomeSession?.tomeId ?: return@arrange
        persistTomeOrder(tomeId, transactionId, committed, rollbackSlots, rollbackFocusIndex)
    }
    val dispatchTouchTomeArrange: (HomeTomeArrangeIntent) -> Unit = touch@{ intent ->
        pendingFolioArrangeActivation = null
        if (pendingTomeOrderCommit != null && intent is HomeTomeArrangeIntent.Lift) return@touch
        var working = tomeArrangeState
        if (intent is HomeTomeArrangeIntent.Lift && working == null && folioMembers.isNotEmpty()) {
            working = beginHomeTomeArrangeSlots(
                visibleFolioSlots,
                folioFocusedIndex,
                tomeArrangeTransactionSequence,
            )
        }
        val tomeId = tomeSession?.tomeId ?: return@touch
        val current = working ?: return@touch
        val update = reduceHomeTomeArrange(current, intent)
        tomeArrangeTransactionSequence = update.state.nextTransactionId
        working = if (update.exitRequested) null else update.state
        folioFocusedIndex = update.state.focusedIndex
        requestFolioSeatFocus(update.state.focusedIndex, update.state.workingSlots)
        update.navigation?.let { folioTransition = it.transition }
        if (update.navigation?.moved == true) MimirNavFeedback.consumed()
        val committed = update.committedSlots
        val transactionId = update.committedTransactionId
        val rollbackSlots = update.rollbackSlots
        val rollbackFocusIndex = update.rollbackFocusIndex
        if (committed != null && transactionId != null && rollbackSlots != null && rollbackFocusIndex != null) {
            persistTomeOrder(tomeId, transactionId, committed, rollbackSlots, rollbackFocusIndex)
        }
        tomeArrangeState = working
    }
    LaunchedEffect(
        pendingFolioArrangeActivation,
        tomeFolioState?.focusTarget,
        tomeFolioState?.focusSettledForActivation,
        tomeArrangeState?.carriedItemId,
        tomeArrangeState?.activeTransactionId,
        tomeArrangeState?.nextTransactionId,
        folioFocusState.scope,
        folioFocusState.focused,
    ) {
        val pending = pendingFolioArrangeActivation ?: return@LaunchedEffect
        val live = tomeFolioState ?: return@LaunchedEffect
        val arrange = tomeArrangeState
        val current = arrange != null && pendingFolioArrangeActivationIsCurrent(
            pending = pending,
            liveTarget = live.focusTarget,
            liveMode = live.mode,
            liveCarriedItemId = arrange.carriedItemId,
            liveActiveTransactionId = arrange.activeTransactionId,
            liveNextTransactionId = arrange.nextTransactionId,
            scopeIsFolio = folioFocusState.scope == HomeTomeFolioScope,
            focusedKey = folioFocusState.focused,
        )
        if (!current) {
            pendingFolioArrangeActivation = null
            return@LaunchedEffect
        }
        if (live.focusSettledForActivation) {
            pendingFolioArrangeActivation = null
            dispatchTomeArrange(pending.action)
        }
    }
    val cancelTomeResidue: () -> Unit = {
        folioTransition = cancelHomeTomePageResidue(folioTransition)
        tomeArrangeState = tomeArrangeState?.let { arrange ->
            arrange.copy(pageTransition = cancelHomeTomePageResidue(arrange.pageTransition))
        }
    }
    // FUX-E G-2: the Home row currently seeding a NOW_PLAYING(loading) card while its launch is in flight
    // (null when nothing is launching). Read by CompanionPayloadPush to ride the normal now-playing pipeline.
    val launchingRowState = remember { mutableStateOf<HomeShelfRow?>(null) }

    return HomeShelfFolioParts(
        mutationIdsState = mutationIdsState,
        mutationRevisionState = mutationRevisionState,
        toastState = toastState,
        scope = scope,
        emitMessageWithSupport = emitMessageWithSupport,
        emitMessage = emitMessage,
        consumeDeliveredMessage = consumeDeliveredMessage,
        folioFocusedIndexState = folioFocusedIndexState,
        tomeFolioFocusMemoryState = tomeFolioFocusMemoryState,
        folioTransitionState = folioTransitionState,
        resetFolioFocusSettlement = resetFolioFocusSettlement,
        tomeArrangeStateState = tomeArrangeStateState,
        folioRows = folioRows,
        tomeFolioState = tomeFolioState,
        focusTomeMember = focusTomeMember,
        settleTomeFolioFocus = settleTomeFolioFocus,
        unsettleTomeFolioFocus = unsettleTomeFolioFocus,
        captureAuthoritativeTomeFolioFocus = captureAuthoritativeTomeFolioFocus,
        navigateTomeFolio = navigateTomeFolio,
        routeTomeFolioAction = routeTomeFolioAction,
        enterTomeArrange = enterTomeArrange,
        dispatchTomeArrange = dispatchTomeArrange,
        dispatchTouchTomeArrange = dispatchTouchTomeArrange,
        cancelTomeResidue = cancelTomeResidue,
        launchingRowState = launchingRowState,
    )
}

/** What [rememberHomeShelfReturnWiring] hands the later pieces. */
private class HomeShelfSurfaceParts(
    val switchHomeViewMode: (HomeViewMode) -> Boolean,
    val toggleHomeViewMode: () -> Boolean,
    val jumpLooseHomeAlphabetBucket: (Boolean) -> Boolean,
)

/**
 * Fourth piece of [rememberHomeShelfCoordinator]: the launch-return and surface-restore effects, the
 * lifecycle observers, the persisted surface snapshot, and the view-mode switch, toggle and alphabet jump.
 * See [rememberHomeShelfDurableSources] for why the composable is split.
 */
@Composable
private fun rememberHomeShelfReturnWiring(
    context: Context,
    persistedLaunchReturn: HomeLaunchReturnHandle?,
    focusAuthority: FocusAuthority,
    destination: MimirDestination,
    onNavigate: (MimirDestination) -> Unit,
    durableSources: HomeShelfDurableSources,
    shelfProjection: HomeShelfProjectionParts,
    folioParts: HomeShelfFolioParts,
): HomeShelfSurfaceParts {
    // Values the earlier pieces own, re-bound under their original names so the code below is unchanged.
    var pendingLaunchReturn by durableSources.pendingLaunchReturnState
    var launchReturnNeedsRoute by durableSources.launchReturnNeedsRouteState
    var pendingSurfaceRestore by durableSources.pendingSurfaceRestoreState
    var restorationTruthReady by durableSources.restorationTruthReadyState
    var homeViewMode by durableSources.homeViewModeState
    var editorsChoiceRoot by durableSources.editorsChoiceRootState
    var pinnedTomesRoot by durableSources.pinnedTomesRootState
    var homeSurfaceEpoch by durableSources.homeSurfaceEpochState
    var homeSurfaceHandoffDirection by durableSources.homeSurfaceHandoffDirectionState
    val inputProjection = durableSources.inputProjection
    val alphabetJumpFeedback = durableSources.alphabetJumpFeedback
    val homeFavoriteTruthReady = shelfProjection.homeFavoriteTruthReady
    val editorsChoiceEntries = shelfProjection.editorsChoiceEntries
    val orderedLooseGames = shelfProjection.orderedLooseGames
    val pinnedTomesEntries = shelfProjection.pinnedTomesEntries
    val visibleRootEntries = shelfProjection.visibleRootEntries
    val tomeCatalog = shelfProjection.tomeCatalog
    var tomeSession by shelfProjection.tomeSessionState
    var tomeSessionGeneration by shelfProjection.tomeSessionGenerationState
    val contextRowsOrNull = shelfProjection.contextRowsOrNull
    val tiledDisplayRows = shelfProjection.tiledDisplayRows
    val homeOverviewSlots = shelfProjection.homeOverviewSlots
    var toast by folioParts.toastState
    val scope = folioParts.scope
    var folioFocusedIndex by folioParts.folioFocusedIndexState
    var folioTransition by folioParts.folioTransitionState
    val resetFolioFocusSettlement = folioParts.resetFolioFocusSettlement
    var tomeArrangeState by folioParts.tomeArrangeStateState

    val onNavigateNow by rememberUpdatedState(onNavigate)

    // Cold process return can compose after ON_RESUME already fired. Finalize that durable token here
    // as well; the store is token-idempotent, so the normal onResume path may safely race it.
    LaunchedEffect(persistedLaunchReturn?.token) {
        // D3: not while an M-button command holds that game's return back; its real return finishes the token.
        if (PrimaryActivityObservationState.gameReturnWithheldByMButtonCommand()) return@LaunchedEffect
        persistedLaunchReturn?.let { handle ->
            HomeExternalSessionElapsedStore.finalize(
                context = context,
                returnToken = handle.token,
                nowElapsedMillis = android.os.SystemClock.elapsedRealtime(),
            )
        }
    }

    LaunchedEffect(toast?.eventId) {
        val activeEventId = toast?.eventId ?: return@LaunchedEffect
        delay(HomeShelfFeedbackWindowMs)
        if (toast?.eventId == activeEventId) toast = null
    }

    DisposableEffect(context, focusAuthority) {
        val owner = context as? LifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                // D3 : a resume an M-button command caused is not the launched game coming back. While
                // that game's return is held back its record stays unread: no elapsed finish, no route to its tile.
                if (PrimaryActivityObservationState.gameReturnWithheldByMButtonCommand()) return
                scope.launch {
                    HomeLaunchReturnStore.peek(context)?.let { handle ->
                        HomeExternalSessionElapsedStore.finalize(
                            context = context,
                            returnToken = handle.token,
                            nowElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                        )
                        if (handle.tomeSession == null) {
                            focusAuthority.rememberFocusForScope(HomeShelfScope, handle.target.focusKey)
                        }
                        if (pendingLaunchReturn != handle) {
                            pendingLaunchReturn = handle
                            launchReturnNeedsRoute = true
                        }
                        pendingSurfaceRestore = null
                        restorationTruthReady = true
                    }
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    val returnGraceDeadlines = remember { HashMap<String, Long>() }
    // A SEPARATE map from returnGraceDeadlines, deliberately. The Ready branch's first act is
    // `returnGraceDeadlines.remove(handle.token)`, so a focus deadline parked there would be wiped on
    // every effect restart -- and truth re-emissions restart this effect constantly, which is exactly the
    // unbounded-window failure the absent-row deadline was introduced to prevent. Keeping the focus
    // deadline in its own map leaves the reviewed absent-row semantics untouched.
    val returnFocusGraceDeadlines = remember { HashMap<String, Long>() }
    LaunchedEffect(
        pendingLaunchReturn,
        destination,
        contextRowsOrNull,
        tomeCatalog,
        tiledDisplayRows,
        homeFavoriteTruthReady,
        tomeSessionGeneration,
    ) {
        val handle = pendingLaunchReturn ?: return@LaunchedEffect
        val target = handle.target
        val returnMode = handle.tomeSession?.rootMode ?: handle.homeViewMode
        if (homeViewMode != returnMode) {
            homeSurfaceHandoffDirection = 0
            homeSurfaceEpoch += 1
        }
        homeViewMode = returnMode
        if (launchReturnNeedsRoute) {
            launchReturnNeedsRoute = false
            if (destination != MimirDestination.HOME) {
                onNavigateNow(MimirDestination.HOME)
                return@LaunchedEffect
            }
        }
        if (destination != MimirDestination.HOME) {
            return@LaunchedEffect
        }

        val persistedSession = handle.tomeSession
        val restoredSession = restorableHomeTomeSession(persistedSession, tomeSessionGeneration)
        if (persistedSession != null && restoredSession == null) {
            // The user already closed or replaced this Folio. A later repository/art emission must not
            // replay the older durable route and start the Library <-> Folio bounce again.
            returnGraceDeadlines.remove(handle.token)
            acknowledgeHomeLaunchReturnDurably(context, handle)
            pendingLaunchReturn = HomeLaunchReturnStore.peek(context)
            launchReturnNeedsRoute = pendingLaunchReturn != null
            return@LaunchedEffect
        }
        val returnRows = if (restoredSession == null) {
            contextRowsOrNull
        } else {
            if (!homeFavoriteTruthReady) return@LaunchedEffect
            val members = if (restoredSession.tomeId == TomeId.HomeOverview) {
                homeOverviewSlots.filterNotNull()
            } else {
                tomeCatalog.eligibleMembersByTome[restoredSession.tomeId]
            }
            if (members == null) emptyList() else {
                val byId = tiledDisplayRows.associateBy(HomeShelfRow::itemId)
                members.mapNotNull { byId[it.toHomeShelfItemId()] }
            }
        }
        if (restoredSession != null && returnRows?.any { it.itemId == target.itemId } == true) {
            val restoredFocusKey = homeTomeMemberFocusKey(target.itemId)
            if (!enterHomeTomeScope(focusAuthority, HomeTomeFolioScope, restoredFocusKey)) {
                return@LaunchedEffect
            }
            tomeSessionGeneration = maxOf(tomeSessionGeneration, restoredSession.generation)
            tomeSession = restoredSession
            val targetMemberId = target.itemId.toFavoriteMemberIdOrNull()
            val targetSeat = targetMemberId?.let { memberId ->
                if (restoredSession.tomeId == TomeId.HomeOverview) {
                    homeOverviewSlots.indexOf(memberId)
                } else {
                    tomeCatalog.eligibleMemberSlotsByTome[restoredSession.tomeId]?.indexOf(memberId)
                }
            }?.takeIf { it >= 0 }
            val restored = restoredHomeTomePosition(
                targetSeat ?: returnRows.indexOfFirst { it.itemId == target.itemId }.coerceAtLeast(0),
            )
            folioFocusedIndex = restored.focusedIndex
            folioTransition = restored.transition
            resetFolioFocusSettlement()
            tomeArrangeState = null
        }

        when (val resolution = resolveHomeLaunchReturn(target, returnRows)) {
            HomeLaunchReturnResolution.AwaitingRowTruth -> Unit

            HomeLaunchReturnResolution.RowAbsent -> {
                // Bounded patience (panel review 2026-07-14): an identity that never projects again must
                // not hold the opaque return cover forever. The deadline is ABSOLUTE per handle — truth
                // re-emissions (art bumps, refreshes) restart this effect but can never extend the
                // window (review 2026-07-14: a churning source held the cover unboundedly). Per the
                // exact-return ruling no fallback focus is seeded — Home lands on its first row.
                val deadline = returnGraceDeadlines.getOrPut(handle.token) {
                    android.os.SystemClock.elapsedRealtime() + HomeLaunchReturnRenderableGraceMs
                }
                val wait = deadline - android.os.SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                returnGraceDeadlines.remove(handle.token)
                acknowledgeHomeLaunchReturnDurably(context, handle)
                pendingLaunchReturn = HomeLaunchReturnStore.peek(context)
                launchReturnNeedsRoute = pendingLaunchReturn != null
            }

            is HomeLaunchReturnResolution.Ready -> {
                returnGraceDeadlines.remove(handle.token)
                // F2 (cold-launch settle audit, 2026-09-02): rewarm the visible home window BEFORE the
                // RETURN_COVER drops.
                //
                // Coil 2.7.0 clears its entire strong memory cache at trim level >= 40, and this app has no
                // onTrimMemory handling anywhere -- so a heavy game evicts every decoded cover while the two
                // platform-art LruCaches survive it. That is why a return lands with the Library warm and
                // Home popping in: the surfaces genuinely disagree about how warm they are. The cover's own
                // drop condition is row + focus truth and nothing about paint.
                //
                // Self-tuning by construction, which is why it needs no trim detection: when the cache
                // survived, every execute is a memory-cache hit and this costs approximately nothing; when
                // the trim emptied it, the cover holds a beat and drops onto painted pixels. Bounded so a
                // slow decode can never hold the screen -- the same shape T-covers uses.
                withTimeoutOrNull(HomeReturnRewarmBudgetMs) {
                    val loader = context.imageLoader
                    // The rows this return is landing on -- the same set the cover is about to reveal.
                    val window = returnRows.orEmpty().take(WarmHomeRowCount)
                    coroutineScope {
                        window.mapNotNull { row ->
                            (row.tileIconUri ?: row.faceUri ?: row.artworkUri)?.trim()?.takeIf(String::isNotEmpty)
                        }.map { uri ->
                            async { runCatching { loader.execute(mimirWarmCoverRequest(context, uri)) } }
                        }.awaitAll()
                    }
                }
                val focusScope = if (restoredSession == null) HomeShelfScope else HomeTomeFolioScope
                val focusKey = if (restoredSession == null) {
                    resolution.focusKey
                } else {
                    val row = returnRows?.firstOrNull { it.itemId == target.itemId } ?: return@LaunchedEffect
                    HomeTomeMember(
                        itemId = row.itemId,
                        title = row.title,
                        artworkUri = row.tileIconUri ?: row.artworkUri,
                    ).focusKey
                }
                focusAuthority.rememberFocusForScope(focusScope, focusKey)
                // BOUNDED, on the SAME per-handle deadline [HomeLaunchReturnResolution.RowAbsent] below
                // already uses -- so one handle carries one patience budget no matter which way its
                // resolution goes. This spin was the twin of the surface-restore one further down and
                // carried the identical defect (P0 2026-08-14): `while (isActive)` with no deadline, whose
                // only exit is this authority landing. Whenever the shelf is not the active FocusScope --
                // any surface that pushes its own scope during the return beat -- nothing cleared
                // `pendingLaunchReturn`, so RETURN_COVER held the screen indefinitely while swallowing the
                // input that could have dismissed the surface holding the scope. The absent-row case was
                // given bounded patience by panel review 2026-07-14 for exactly this reason; the focus
                // case was left unbounded, and only the surface-restore twin was device-reproduced. It is
                // bounded here on the same evidence and the same mechanism rather than left as the one
                // remaining way to wedge the same cover.
                //
                // On expiry this takes the RowAbsent branch's own already-reviewed release: acknowledge
                // the handle durably and re-peek. The player lands on Home rather than behind an opaque
                // rune, with `rememberFocusForScope` above still seeding their tile.
                val focusDeadline = returnFocusGraceDeadlines.getOrPut(handle.token) {
                    android.os.SystemClock.elapsedRealtime() + HomeSurfaceRestoreFocusGraceMs
                }
                val landed = awaitHomeSurfaceFocusSettled(
                    deadlineMs = focusDeadline,
                    nowMillis = android.os.SystemClock::elapsedRealtime,
                    awaitFrame = { withFrameNanos { } },
                    retryMs = HomeLaunchReturnRetryMs,
                ) {
                    if (focusAuthority.state.value.scope != focusScope) {
                        false
                    } else {
                        focusAuthority.requestFocus(focusKey)
                        focusAuthority.state.value.focused == focusKey &&
                            HomeLaunchReturnStore.acknowledge(context, handle) !=
                            HomeLaunchReturnAcknowledgement.RETRY
                    }
                }
                returnFocusGraceDeadlines.remove(handle.token)
                // Landed: the acknowledgement already happened inside the attempt above, exactly as it did
                // when this was an inline loop. Expired: acknowledge durably now, or the handle would be
                // re-peeked unchanged and the cover would come straight back.
                if (!landed) acknowledgeHomeLaunchReturnDurably(context, handle)
                pendingLaunchReturn = HomeLaunchReturnStore.peek(context)
                launchReturnNeedsRoute = pendingLaunchReturn != null
            }
        }
    }

    // Cold-process restoration is lower priority than the external-launch anchor. Schema v3 restores
    // the Home mode plus one typed focus identity per mode; labels never participate in navigation.
    val editorsChoiceEntriesOrNull = editorsChoiceEntries.takeIf { homeFavoriteTruthReady }
    val pinnedTomesEntriesOrNull = pinnedTomesEntries.takeIf { homeFavoriteTruthReady }
    // P0 2026-08-14 (Odin test device, ~80 minutes on the loading rune). The restore's focus wait below is
    // bounded now, and the deadline is ABSOLUTE per stored snapshot for the same reason
    // `returnGraceDeadlines` is: this effect is keyed on library truth, so an art bump or a Room
    // re-emission restarts it, and a per-attempt duration would let a churning source extend the window
    // without limit. Plain `remember`, deliberately NOT `mutableStateOf`: writing it must not itself
    // schedule a recomposition of the surface it is trying to release.
    val surfaceRestoreGraceDeadlines = remember { HashMap<HomeSurfaceSnapshot, Long>() }
    LaunchedEffect(
        pendingSurfaceRestore,
        pendingLaunchReturn,
        destination,
        editorsChoiceEntriesOrNull,
        pinnedTomesEntriesOrNull,
    ) {
        if (pendingLaunchReturn != null) {
            pendingSurfaceRestore = null
            return@LaunchedEffect
        }
        val stored = pendingSurfaceRestore ?: return@LaunchedEffect
        if (destination != MimirDestination.HOME) return@LaunchedEffect
        when (
            val resolution = resolveHomeSurfaceRestore(
                stored = stored,
                editorsChoiceEntries = editorsChoiceEntriesOrNull,
                pinnedTomesEntries = pinnedTomesEntriesOrNull,
            )
        ) {
            HomeSurfaceRestoreResolution.AwaitingTruth -> Unit

            is HomeSurfaceRestoreResolution.Ready -> {
                val target = resolution.snapshot
                resetFolioFocusSettlement()
                tomeSession = null
                editorsChoiceRoot = target.editorsChoiceRoot
                pinnedTomesRoot = target.pinnedTomesRoot
                if (homeViewMode != target.mode) {
                    homeSurfaceHandoffDirection = 0
                    homeSurfaceEpoch += 1
                }
                homeViewMode = target.mode
                val focusKey = when (val root = target.rootEntry) {
                    is HomeRootEntryRecord.Game -> homeShelfFocusKey(root.itemId)
                    is HomeRootEntryRecord.Tome -> homeShelfTomeFocusKey(root.tomeId)
                    null -> null
                }
                if (focusKey == null) {
                    focusAuthority.clearFocus()
                    val revision = HomeSurfaceStateStore.nextRevision()
                    HomeSurfaceStateStore.replace(context, target, revision)
                    pendingSurfaceRestore = null
                    return@LaunchedEffect
                }
                focusAuthority.clearFocus()
                focusAuthority.rememberFocusForScope(HomeShelfScope, focusKey)
                // BOUNDED. This spin used to be `while (isActive)` with no deadline at all, and its only
                // exit was the Home shelf being the ACTIVE focus scope with `focusKey` taking focus. Any
                // cold start that raises a full-screen surface owning its own FocusScope before that
                // happens makes the exit unreachable: device-reproduced 2026-08-14 on emulator-5558 with
                // `--es mimir.openFiles manage`, where `FileBrowserController.open()` pushes
                // `FocusScope("files")` (FileBrowserController.kt:602) over a Home the browser also
                // replaces on screen, so neither half of the condition can ever hold. The consequence was
                // not a slow start, it was a wedged device: `pendingSurfaceRestore` stayed non-null, so
                // `launchReturnRestoring` stayed true (see this coordinator's own return-cover wiring
                // below), so RETURN_COVER stayed TOP of the overlay stack
                // (MimirOverlayCoordinator.kt:184) absorbing every controller action (:364) while
                // MimirAppHost's cover swallowed Back and every pointer event -- including the input that
                // would have closed the browser this loop was waiting on. A closed cycle, exited only by
                // force-stop.
                //
                // On expiry the restore is completed rather than abandoned: mode and roots are already
                // applied above, and `rememberFocusForScope` above has already seeded the landing, so when
                // the shelf does become the active scope `popScope` puts the player on the same tile. The
                // deadline costs the player nothing except the wait itself.
                val deadline = surfaceRestoreGraceDeadlines.getOrPut(stored) {
                    android.os.SystemClock.elapsedRealtime() + HomeSurfaceRestoreFocusGraceMs
                }
                awaitHomeSurfaceFocusSettled(
                    deadlineMs = deadline,
                    nowMillis = android.os.SystemClock::elapsedRealtime,
                    awaitFrame = { withFrameNanos { } },
                    retryMs = HomeLaunchReturnRetryMs,
                ) {
                    if (focusAuthority.state.value.scope != HomeShelfScope) {
                        false
                    } else {
                        focusAuthority.requestFocus(focusKey)
                        focusAuthority.state.value.focused == focusKey
                    }
                }
                surfaceRestoreGraceDeadlines.remove(stored)
                val revision = HomeSurfaceStateStore.nextRevision()
                HomeSurfaceStateStore.replace(context, target, revision)
                pendingSurfaceRestore = null
            }
        }
    }

    // Persist only the currently-visible Home surface after focus settles. Dock focus deliberately uses
    // HomeShelfScope memory, so opening Utility/Settings cannot replace the hero identity with a dock key.
    val surfaceFocusState by focusAuthority.state.collectAsState()
    val surfaceFocusKey = if (surfaceFocusState.scope == HomeShelfScope) {
        surfaceFocusState.focused ?: focusAuthority.rememberedFocusIn(HomeShelfScope)
    } else {
        focusAuthority.rememberedFocusIn(HomeShelfScope)
    }
    val visibleEntriesOrNull = when (homeViewMode) {
        HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntriesOrNull
        HomeViewMode.PINNED_TOMES -> pinnedTomesEntriesOrNull
    }
    SideEffect {
        inputProjection.reconcile(
            mode = homeViewMode,
            entries = visibleRootEntries,
            focusKey = surfaceFocusKey,
            epoch = homeSurfaceEpoch,
        )
    }
    val surfaceSnapshotToPersist = visibleEntriesOrNull?.let { visibleEntries ->
        val activeMode = tomeSession?.rootMode ?: homeViewMode
        val activeRoot = tomeSession?.let { session ->
            if (session.origin == HomeTomeOrigin.HOME_ROOT && session.tomeId != TomeId.HomeOverview) {
                HomeRootEntryRecord.Tome(session.tomeId)
            } else {
                session.rootEntry?.toRootRecord()
            }
        }
            ?: homeShelfEntryForKey(visibleEntries, surfaceFocusKey)?.toRootRecord()
        HomeSurfaceSnapshot(
            mode = activeMode,
            editorsChoiceRoot = if (activeMode == HomeViewMode.EDITORS_CHOICE) {
                activeRoot ?: editorsChoiceRoot
            } else {
                editorsChoiceRoot
            },
            pinnedTomesRoot = if (activeMode == HomeViewMode.PINNED_TOMES) {
                activeRoot as? HomeRootEntryRecord.Tome ?: pinnedTomesRoot
            } else {
                pinnedTomesRoot
            },
        )
    }

    val switchHomeViewMode: (HomeViewMode) -> Boolean = switch@{ targetMode ->
        if (!homeFavoriteTruthReady || tomeSession != null || targetMode == homeViewMode) return@switch false
        val targetEntries = when (targetMode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntries
            HomeViewMode.PINNED_TOMES -> pinnedTomesEntries
        }
        if (targetMode == HomeViewMode.PINNED_TOMES && targetEntries.isEmpty()) return@switch false

        val currentEntry = homeShelfEntryForKey(visibleRootEntries, surfaceFocusKey)
        val currentRoot = currentEntry?.toRootRecord()
        if (homeViewMode == HomeViewMode.EDITORS_CHOICE) {
            editorsChoiceRoot = currentRoot ?: editorsChoiceRoot
        } else {
            pinnedTomesRoot = currentRoot as? HomeRootEntryRecord.Tome ?: pinnedTomesRoot
        }
        val rememberedTarget = when (targetMode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceRoot
            HomeViewMode.PINNED_TOMES -> pinnedTomesRoot
        }
        val targetRoot = targetEntries
            .firstOrNull { it.toRootRecord() == rememberedTarget }
            ?.toRootRecord()
            ?: targetEntries.firstOrNull { it.toRootRecord() == currentRoot }?.toRootRecord()
            ?: targetEntries.firstOrNull()?.toRootRecord()
        if (targetMode == HomeViewMode.EDITORS_CHOICE) {
            editorsChoiceRoot = targetRoot
        } else {
            pinnedTomesRoot = targetRoot as? HomeRootEntryRecord.Tome
        }
        val targetKey = when (targetRoot) {
            is HomeRootEntryRecord.Game -> homeShelfFocusKey(targetRoot.itemId)
            is HomeRootEntryRecord.Tome -> homeShelfTomeFocusKey(targetRoot.tomeId)
            null -> null
        }
        val targetEpoch = homeSurfaceEpoch + 1
        // Publish input truth before invalidating the outgoing Compose tree. A HAT edge in the
        // recompose gap therefore advances the destination list and never falls back to old item zero.
        inputProjection.publish(targetMode, targetEntries, targetKey, targetEpoch)
        homeViewMode = targetMode
        // R1 brings Pinned Tomes in from the right, L1 Editor's Choice from the left (motion audit batch D).
        homeSurfaceHandoffDirection = if (targetMode == HomeViewMode.PINNED_TOMES) 1 else -1
        homeSurfaceEpoch = targetEpoch
        targetKey?.let { key ->
            if (focusAuthority.state.value.scope == HomeShelfScope) focusAuthority.clearFocus()
            focusAuthority.rememberFocusForScope(HomeShelfScope, key)
            if (focusAuthority.state.value.scope == HomeShelfScope) focusAuthority.requestFocus(key)
        } ?: run {
            if (focusAuthority.state.value.scope == HomeShelfScope) focusAuthority.clearFocus()
        }
        val snapshot = HomeSurfaceSnapshot(
            mode = targetMode,
            editorsChoiceRoot = editorsChoiceRoot,
            pinnedTomesRoot = pinnedTomesRoot,
        )
        val revision = HomeSurfaceStateStore.nextRevision()
        scope.launch { HomeSurfaceStateStore.replace(context, snapshot, revision) }
        true
    }
    val toggleHomeViewMode: () -> Boolean = {
        switchHomeViewMode(
            if (homeViewMode == HomeViewMode.EDITORS_CHOICE) {
                HomeViewMode.PINNED_TOMES
            } else {
                HomeViewMode.EDITORS_CHOICE
            },
        )
    }
    val jumpLooseHomeAlphabetBucket: (Boolean) -> Boolean = jump@{ forward ->
        if (homeViewMode != HomeViewMode.EDITORS_CHOICE ||
            focusAuthority.state.value.scope != HomeShelfScope
        ) return@jump false
        val focused = focusAuthority.state.value.focused
        val focusedIndex = editorsChoiceEntries.indexOfFirst { it.focusKey == focused }
        val recent = looseHomeRecentGame(orderedLooseGames)?.id
        val targetIndex = looseHomeAlphabetJumpIndex(
            games = orderedLooseGames,
            currentIndex = focusedIndex,
            forward = forward,
            recentItemId = recent,
        ) ?: return@jump true
        val target = orderedLooseGames.getOrNull(targetIndex) ?: return@jump true
        val targetKey = homeShelfFocusKey(target.row.itemId)
        // Commit the logical projection in this input epoch before Compose focus, feedback, or a
        // follow-on trigger can observe/persist the shelf. Same-epoch reconciliation preserves it.
        inputProjection.updateFocus(targetKey)
        focusAuthority.requestFocus(targetKey)
        // RECENT is the ring's neutral stop. Keep the preceding real letter alive only for its exit
        // animation; do not invent a character for the recency tile.
        if (target.id != recent) {
            alphabetJumpFeedback.publish(looseHomeAlphabetBucket(target.row.title))
        } else {
            alphabetJumpFeedback.notice?.let { alphabetJumpFeedback.expire(it.epoch) }
        }
        true
    }
    LaunchedEffect(homeViewMode, pinnedTomesEntries, homeFavoriteTruthReady) {
        if (homeFavoriteTruthReady && homeViewMode == HomeViewMode.PINNED_TOMES && pinnedTomesEntries.isEmpty()) {
            switchHomeViewMode(HomeViewMode.EDITORS_CHOICE)
        }
    }
    LaunchedEffect(
        destination,
        visibleEntriesOrNull,
        tomeSession,
        surfaceFocusKey,
        pendingLaunchReturn,
        pendingSurfaceRestore,
    ) {
        if (destination != MimirDestination.HOME) return@LaunchedEffect
        if (pendingLaunchReturn != null || pendingSurfaceRestore != null) return@LaunchedEffect
        val snapshot = surfaceSnapshotToPersist ?: return@LaunchedEffect
        editorsChoiceRoot = snapshot.editorsChoiceRoot
        pinnedTomesRoot = snapshot.pinnedTomesRoot
        val revision = HomeSurfaceStateStore.nextRevision()
        delay(HomeSurfacePersistenceSettleMs)
        HomeSurfaceStateStore.replace(context, snapshot, revision)
    }
    val latestSurfaceForStop by rememberUpdatedState(
        surfaceSnapshotToPersist.takeIf {
            destination == MimirDestination.HOME &&
                pendingLaunchReturn == null && pendingSurfaceRestore == null
        },
    )
    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                latestSurfaceForStop?.let { snapshot ->
                    val revision = HomeSurfaceStateStore.nextRevision()
                    scope.launch { HomeSurfaceStateStore.replace(context, snapshot, revision) }
                }
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    return HomeShelfSurfaceParts(
        switchHomeViewMode = switchHomeViewMode,
        toggleHomeViewMode = toggleHomeViewMode,
        jumpLooseHomeAlphabetBucket = jumpLooseHomeAlphabetBucket,
    )
}

/**
 * App-owned Home shelf state: durable Favorite and Tome truth, the Folio, launch-return and surface
 * restore, and the launch and Tome-management actions, handed to the root as one [HomeShelfCoordinator].
 *
 * ART's optimizing compiler refuses any method of 16,383 dex code units or more, and this function had
 * grown to about 17,400, so every recomposition of it ran in the interpreter. The state and effect wiring
 * now lives in [rememberHomeShelfDurableSources], [rememberHomeShelfProjection], [rememberHomeShelfFolio]
 * and [rememberHomeShelfReturnWiring] above, called here unconditionally and in the original order. This
 * body keeps the membership, launch and Tome-management actions and builds the coordinator. Add new work to
 * the smallest piece it fits, and check `dexdump -d` before any piece grows past about 10,000 code units.
 */
@Composable
internal fun rememberHomeShelfCoordinator(
    context: Context,
    persistedLaunchReturn: HomeLaunchReturnHandle?,
    persistedLaunchReturnReady: Boolean,
    focusAuthority: FocusAuthority,
    nativeGameRows: List<NativeShelfRow>,
    launchController: LaunchController,
    destination: MimirDestination,
    onNavigate: (MimirDestination) -> Unit,
    nativeRowsLoaded: Boolean = true,
): HomeShelfCoordinator {
    val durableSources = rememberHomeShelfDurableSources(context, persistedLaunchReturn, persistedLaunchReturnReady, nativeGameRows, nativeRowsLoaded)
    val shelfProjection = rememberHomeShelfProjection(context, nativeGameRows, durableSources)
    val folioParts = rememberHomeShelfFolio(context, focusAuthority, durableSources, shelfProjection)
    val surfaceParts = rememberHomeShelfReturnWiring(context, persistedLaunchReturn, focusAuthority, destination, onNavigate, durableSources, shelfProjection, folioParts)
    val homeResources = appResources()

    // Values the pieces above own, re-bound under their original names so the code below is unchanged.
    val libraryRepository = durableSources.libraryRepository
    val androidGameProofs = durableSources.androidGameProofs
    var nativeFavoriteOverrides by durableSources.nativeFavoriteOverridesState
    var pendingLaunchReturn by durableSources.pendingLaunchReturnState
    var pendingSurfaceRestore by durableSources.pendingSurfaceRestoreState
    var restorationTruthReady by durableSources.restorationTruthReadyState
    var homeViewMode by durableSources.homeViewModeState
    var editorsChoiceRoot by durableSources.editorsChoiceRootState
    var pinnedTomesRoot by durableSources.pinnedTomesRootState
    var homeSurfaceEpoch by durableSources.homeSurfaceEpochState
    var homeSurfaceHandoffDirection by durableSources.homeSurfaceHandoffDirectionState
    val inputProjection = durableSources.inputProjection
    val alphabetJumpFeedback = durableSources.alphabetJumpFeedback
    var pendingRemovalIds by durableSources.pendingRemovalIdsState
    val homeFavoriteTruthReady = shelfProjection.homeFavoriteTruthReady
    val platformLabels = shelfProjection.platformLabels
    val durableRootProjection = shelfProjection.durableRootProjection
    val rootProjection = shelfProjection.rootProjection
    val editorsChoiceEntries = shelfProjection.editorsChoiceEntries
    val pinnedTomesEntries = shelfProjection.pinnedTomesEntries
    val visibleRootEntries = shelfProjection.visibleRootEntries
    val tomeCatalog = shelfProjection.tomeCatalog
    val nativeProjection = shelfProjection.nativeProjection
    val membershipIds = shelfProjection.membershipIds
    var tomeSession by shelfProjection.tomeSessionState
    var tomeLibraryReturnPendingFence by shelfProjection.tomeLibraryReturnPendingFenceState
    var tomeSessionGeneration by shelfProjection.tomeSessionGenerationState
    var tomeReturnState by shelfProjection.tomeReturnStateState
    var tomeReplay by shelfProjection.tomeReplayState
    var pendingTomeProjection by shelfProjection.pendingTomeProjectionState
    val tomeProjectionMutationGate = shelfProjection.tomeProjectionMutationGate
    var sgdbEpoch by shelfProjection.sgdbEpochState
    val tiledDisplayRows = shelfProjection.tiledDisplayRows
    val tomeModels = shelfProjection.tomeModels
    val homeOverviewSlots = shelfProjection.homeOverviewSlots
    val homeOverviewModel = shelfProjection.homeOverviewModel
    val visualEntries = shelfProjection.visualEntries
    val launchGamesById = shelfProjection.launchGamesById
    var mutationIds by folioParts.mutationIdsState
    var mutationRevision by folioParts.mutationRevisionState
    var toast by folioParts.toastState
    val scope = folioParts.scope
    val emitMessageWithSupport = folioParts.emitMessageWithSupport
    val emitMessage = folioParts.emitMessage
    val consumeDeliveredMessage = folioParts.consumeDeliveredMessage
    var folioFocusedIndex by folioParts.folioFocusedIndexState
    var tomeFolioFocusMemory by folioParts.tomeFolioFocusMemoryState
    var folioTransition by folioParts.folioTransitionState
    val resetFolioFocusSettlement = folioParts.resetFolioFocusSettlement
    var tomeArrangeState by folioParts.tomeArrangeStateState
    val folioRows = folioParts.folioRows
    val tomeFolioState = folioParts.tomeFolioState
    val focusTomeMember = folioParts.focusTomeMember
    val settleTomeFolioFocus = folioParts.settleTomeFolioFocus
    val unsettleTomeFolioFocus = folioParts.unsettleTomeFolioFocus
    val captureAuthoritativeTomeFolioFocus = folioParts.captureAuthoritativeTomeFolioFocus
    val navigateTomeFolio = folioParts.navigateTomeFolio
    val routeTomeFolioAction = folioParts.routeTomeFolioAction
    val enterTomeArrange = folioParts.enterTomeArrange
    val dispatchTomeArrange = folioParts.dispatchTomeArrange
    val dispatchTouchTomeArrange = folioParts.dispatchTouchTomeArrange
    val cancelTomeResidue = folioParts.cancelTomeResidue
    var launchingRow by folioParts.launchingRowState
    val switchHomeViewMode = surfaceParts.switchHomeViewMode
    val toggleHomeViewMode = surfaceParts.toggleHomeViewMode
    val jumpLooseHomeAlphabetBucket = surfaceParts.jumpLooseHomeAlphabetBucket

    // The one shelf/Favorite membership write. Shared by the Favorite star (setMembershipById immediately
    // below) and by Tome membership (toggleTomeMembership further down, S-3 add-inherits-favorite). Reports success/failure by return value
    // instead of a fire-and-forget write, so both callers can compose it into their own combined result —
    // this extraction is what makes that composition possible at all: before it, the mutation lived inline
    // inside setMembershipById's coroutine with no way for another caller to await its outcome.
    val applyShelfMembership: suspend (HomeShelfItemId, Boolean) -> Boolean = { itemId, shouldBeOnShelf ->
        try {
            when (itemId) {
                is HomeShelfItemId.Emulated -> {
                    libraryRepository.setFavorite(itemId.gameId, shouldBeOnShelf)
                }
                is HomeShelfItemId.AndroidGame -> {
                    check(setNativeShelfFavoriteInteractive(context, itemId.packageName.value, shouldBeOnShelf)) {
                        "Favorite state was not accepted"
                    }
                    nativeFavoriteOverrides = nativeFavoriteOverrides +
                        (itemId.packageName.value to shouldBeOnShelf)
                }
                is HomeShelfItemId.AndroidApp -> error("Android Apps cannot enter Favorite Home")
            }
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            false
        }
    }
    val setMembershipById: (HomeShelfItemId, Boolean) -> Unit = { itemId, shouldBeOnShelf ->
        if (itemId !in mutationIds) {
            mutationIds = mutationIds + itemId
            if (!shouldBeOnShelf) pendingRemovalIds = pendingRemovalIds + itemId
            scope.launch {
                val changed = try {
                    applyShelfMembership(itemId, shouldBeOnShelf)
                } catch (cancellation: CancellationException) {
                    if (!shouldBeOnShelf) pendingRemovalIds = pendingRemovalIds - itemId
                    throw cancellation
                } finally {
                    mutationIds = mutationIds - itemId
                }
                val feedback = if (changed) {
                    HomeShelfMutationFeedback(
                        disposition = HomeShelfMutationDisposition.CHANGED,
                        message = if (shouldBeOnShelf) "Favorite added" else "Favorite removed",
                    )
                } else {
                    HomeShelfMutationFeedback(
                        disposition = HomeShelfMutationDisposition.FAILURE,
                        message = "Favorite not saved",
                    )
                }
                if (!shouldBeOnShelf && feedback.disposition == HomeShelfMutationDisposition.FAILURE) {
                    pendingRemovalIds = pendingRemovalIds - itemId
                }
                mutationRevision = nextHomeShelfMutationRevision(mutationRevision, feedback)
                emitMessage(
                    if (feedback.disposition == HomeShelfMutationDisposition.FAILURE) {
                        MimirMessageClass.Warning
                    } else {
                        MimirMessageClass.Confirmation
                    },
                    feedback.message,
                )
            }
        }
    }
    val setMembership: (LibraryDetailTarget, Boolean) -> Unit = { target, shouldBeOnShelf ->
        setMembershipById(target.itemId, shouldBeOnShelf)
    }

    val launch: (HomeShelfRow) -> Unit = launchAction@{ row ->
        if (!BuildConfig.FINAL_SHELF_ENABLED) return@launchAction
        if (!canActivateFavoriteItem(row.itemId, membershipIds, pendingRemovalIds)) return@launchAction
        // FUX-E re-entrancy guard (review #5/#7): a launch is already in flight (launchingRow stays set
        // until it settles). Ignore the re-press instead of clobbering the in-flight launch's NOW_PLAYING
        // seed — you cannot launch two games at once, and launchingRow is cleared on every terminal path.
        if (launchingRow != null) return@launchAction
        // Stamp the exact displayed typed row; a positional substitute guarantees a broken return.
        val returnTarget = HomeLaunchReturnTarget.from(row)
        val returnHandle = HomeLaunchReturnHandle.create(returnTarget, tomeSession, homeViewMode)
        when (val itemId = row.itemId) {
            is HomeShelfItemId.Emulated -> {
                val game = launchGamesById[itemId.gameId] ?: return@launchAction
                val restartFiled = HomeLaunchCardResume.consumeRestart(game.gameId) // taken at the entry of the Play path
                scope.launch {
                    // FUX-E G-2 seed ordering (review re-verify): prepareHomeGame is ALSO the mutex — it returns
                    // null iff another session is already active. Do it BEFORE marking this row launching, so a
                    // REJECTED launch never sets launchingRow and therefore never seeds CompanionState. That is
                    // what prevents this launch from wiping a DIFFERENT away-running game's retained hero card.
                    // O15: a refusal names what held it, read from the session that refused this admission.
                    var heldBy = GameLaunchSessionHold.STARTING
                    fun admitHomeGame() = MimirSessionAuthority.prepareHomeGame(
                        game = game,
                        returnTarget = returnTarget,
                        returnHandle = returnHandle,
                        onHeld = { heldBy = it },
                    )
                    var operation = admitHomeGame()
                    if (operation == null && CompanionProcessRuntime.releaseParkedLeaseForPress(context)) {
                        // The refusal was held by a
                        // stale parked lease -- task gone (e.g. Lime3DS's own Close Game reached via Recents),
                        // process still cached. Retry the SAME admission once, in this same press, instead of
                        // leaving a refusal that S1's `launch_refused … parked=true` line would otherwise be
                        // the only record of.
                        operation = admitHomeGame()
                    }
                    // F3: a game that HOME parked behind Mimir (never a stale one: the release above ran first). Its own Play
                    // resumes it, another game's Play asks to end it; only an ended one lets this launch through.
                    val parkedPress = if (operation == null) homeParkedPress(context, game.gameId, game.title, emitMessage, restart = restartFiled) else HomeParkedPress.NOT_PARKED
                    if (parkedPress == HomeParkedPress.ENDED_PARKED_GAME) operation = admitHomeGame()
                    // Bound to a val: `operation` is captured by the NonCancellable block below, and a
                    // captured var cannot smart-cast across that lambda boundary.
                    val admittedOperation = operation ?: run {
                        // O-3 (2026-08-12): used to return here in total silence — the shelf row's Play
                        // did nothing at all and gave no reason. This row has no launch-card state to set,
                        // so it shows the sentence the cards show for the same refusal (R6-P0-02 follow-up:
                        // the parked-specific one when release-at-the-press could not clear it).
                        if (parkedPress != HomeParkedPress.ANSWERED) emitHomeShelfGameRefusalMessage(heldBy, emitMessage)
                        return@launch
                    }
                    // This launch WILL dispatch: mark it launching so CompanionPayloadPush emits NOW_PLAYING
                    // (loading) into lastNowPlaying for the armed-owner republish (E6). Cleared on both terminal
                    // outcomes below; on FAILURE its own seed is dropped (this is the sole active session, so no
                    // other game's hero is at risk).
                    launchingRow = row
                    // On a single display, the launch cover bridges Mimir to the game and hides emulator loading.
                    val secondaryDisplayId = CompanionProcessRuntime.services(context)
                        .taskRegistry.state.value.secondaryDisplayId
                    // the one intro entry, shared with Home's launch card and every Library launch.
                    val intro = beginGameOpeningIntro(context, game, secondaryDisplayId)
                    var introLaunched = false
                    withContext(NonCancellable) {
                        // Failure cleanup: `launchingRow = null` moved into `finally` so it clears even when
                        // settleEmulatedHomeLaunch or the settle/fail call below throws -- previously a
                        // thrown exception here would leave the re-entrancy guard latched forever. The
                        // **E1, 2026-08-31 SETTLE BEFORE CLEARING** ordering is unchanged: settle/fail still
                        // runs, and still completes, before the clear -- `finally` runs after `try`'s body
                        // on the normal path exactly like the old trailing statement did.
                        try {
                            val launchResult = settleEmulatedHomeLaunch(
                                context = context,
                                itemId = row.itemId,
                                game = game,
                                returnHandle = returnHandle,
                                launchController = launchController,
                                onFailureToast = { result ->
                                    // F7: same typed message, laid out over the rail's two lines so the
                                    // no-emulator instruction is not clipped (homeShelfLaunchFailureToast).
                                    val failure = homeShelfLaunchFailureToast(
                                        result = result,
                                        gameId = game.gameId,
                                        // Same fallback chain the Home launch card already uses
                                        // (MimirAppHost.HomeLaunchCardLiveContent): platform label, then the
                                        // row's own supporting label, then the generic noun.
                                        platformLabelFor = { platformId ->
                                            platformLabels[platformId] ?: row.supportingLabel ?: "Game"
                                        },
                                    )
                                    emitMessageWithSupport(
                                        MimirMessageClass.Warning,
                                        failure.text,
                                        failure.supportingText,
                                    )
                                },
                            )
                            introLaunched = launchResult?.launched == true
                            launchResult?.let { result ->
                                MimirSessionAuthority.settleHomeGame(admittedOperation, game, result)
                            } ?: MimirSessionAuthority.failHomeGame(
                                admittedOperation,
                                game,
                                "dispatch_failed_without_result",
                            )
                        } finally {
                            launchingRow = null
                        }
                    }
                    if (intro != null) finishGameOpeningIntro(scope, launched = introLaunched)
                }
            }

            is HomeShelfItemId.AndroidGame -> {
                val native = nativeProjection.androidGamesByPackage[itemId.packageName.value] ?: return@launchAction
                scope.launch {
                    val launchIntent = withContext(Dispatchers.IO) {
                        resolveNativeShelfLaunchIntent(context.applicationContext, native.packageName)
                    }
                    if (launchIntent == null) {
                        // Never seeded (launchingRow is only set once the launch is committed below).
                        // No LaunchResult exists on this path (no emulator dispatch was ever attempted),
                        // so the generic line is the honest one here — see homeShelfLaunchFailureMessage.
                        emitMessage(MimirMessageClass.Warning, HomeShelfLaunchFailureFallback)
                        return@launch
                    }
                    // preparePrimaryApp is the mutex (null iff another session active) — seed only after it.
                    // O15: a refusal names what held it, read from the session that refused this admission.
                    var heldBy = GameLaunchSessionHold.STARTING
                    val operationId = MimirSessionAuthority.preparePrimaryApp(
                        CompanionRunningApp(native.packageName, native.label, native.iconPath),
                        onHeld = { heldBy = it },
                    ) ?: run {
                        // O-3 (2026-08-12): used to return here in total silence (audit finding L3).
                        emitHomeShelfSessionHeldMessage(heldBy, emitMessage)
                        return@launch
                    }
                    launchingRow = row
                    withContext(NonCancellable) {
                        // Failure cleanup: `launchingRow = null` moved into `finally` around the dispatch attempt --
                        // previously a thrown exception there (Tweaker IO, the native launch itself) would
                        // skip the clear that used to sit after `androidGameLaunchProofFor` below and leave
                        // the re-entrancy guard latched forever. This clears even earlier than the old
                        // position did, so it is still well ahead of `acknowledgeHomeLaunchReturnDurably`
                        // (review round-3's requirement, below, is unaffected).
                        val attempt = try {
                            dispatchHomeExternalLaunchAfterArm(
                                arm = { HomeLaunchReturnStore.arm(context, returnHandle) },
                                dispatch = {
                                    // Native games take the same cross-app Tweaker preparation, so they take the
                                    // same containment: LaunchController owns that guard for emulated launches,
                                    // this path has no controller and must apply it itself. Nothing here may
                                    // wedge inside NonCancellable.
                                    val tweakerAttemptId = LaunchAttemptId.create()
                                    val preDispatch = runContainedPreDispatch(
                                        dispatcher = Dispatchers.IO,
                                        timeoutMillis = DEFAULT_PRE_DISPATCH_TIMEOUT_MILLIS,
                                    ) {
                                        presentHomeTweakerLaunchStatus(
                                            context = context,
                                            attemptId = tweakerAttemptId,
                                            workloadPackage = native.packageName,
                                            gameTitle = native.label,
                                        )
                                    }
                                    val started = startNativeShelfLaunchIntent(
                                        context = context,
                                        packageName = native.packageName,
                                        target = launchIntent,
                                    )
                                    settleHomeTweakerAfterNativeDispatch(
                                        started = started,
                                        preDispatch = preDispatch,
                                        activate = {
                                            activateHomeTweakerAfterDispatch(
                                                context = context,
                                                attemptId = tweakerAttemptId,
                                                workloadPackage = native.packageName,
                                                gameTitle = native.label,
                                            )
                                        },
                                        presentDegraded = { outcome ->
                                            presentHomeTweakerLaunchDegraded(
                                                context,
                                                tweakerAttemptId,
                                                outcome,
                                            )
                                        },
                                        abandon = {
                                            TweakerBridge.abandonPendingLaunch(tweakerAttemptId)
                                        },
                                    )
                                    started
                                },
                            )
                        } finally {
                            launchingRow = null
                        }
                        val dispatched = attempt == HomeExternalLaunchAttempt.DISPATCHED
                        if (dispatched) {
                            // Says which profile the game is running under, keyed to the DISPATCH
                            // rather than to the Tweaker hooks -- LaunchController skips
                            // preDispatchHook when the prepared launch is not dispatchable and still
                            // reports Completed, so a game can start tuned with no Tweaker code run.
                            presentHomeTweakerActiveProfile(context, native.label)
                            HomeExternalSessionElapsedStore.begin(
                                context = context,
                                itemId = row.itemId,
                                returnToken = returnHandle.token,
                                dispatchedAtElapsedMillis = android.os.SystemClock.elapsedRealtime(),
                            )
                        }
                        val launchProof = androidGameLaunchProofFor(
                            packageName = native.packageName,
                            attempt = attempt,
                            timestampMillis = System.currentTimeMillis(),
                        )
                        // Clear launching UI state before the durability-retrying ack (review round-3), so a
                        // persistent return-store failure can't wedge future launches via the re-entrancy guard.
                        // (Cleared above now, right after the dispatch attempt settles -- see the follow-up
                        // note there. Still strictly before this ack, same as before.)
                        if (!dispatched) CompanionState.clearRetainedNowPlaying()
                        if (dispatched) {
                            MimirSessionAuthority.appDispatched(operationId)
                        } else {
                            val cleanup = settleFailedNativeGameLaunch(
                                attempt = attempt,
                                dispatcher = Dispatchers.IO,
                                timeoutMillis = DEFAULT_PRE_DISPATCH_TIMEOUT_MILLIS,
                            ) {
                                TweakerBridge.settleAfterReturn()
                            }
                            if (cleanup != null && cleanup !is PreDispatchOutcome.Completed) {
                                Log.w(
                                    "MimirHomeShelf",
                                    "Native-game failure cleanup degraded: $cleanup",
                                )
                            }
                            acknowledgeHomeLaunchReturnDurably(context, returnHandle)
                            MimirSessionAuthority.appFailed(operationId, attempt.name.lowercase())
                            // Native games carry no LaunchResult (no LaunchController on this path), so the
                            // generic line is the honest one. OWED (outcome-dropping audit 2026-08-12): the
                            // HomeExternalLaunchAttempt arms — ARMING_FAILED vs DISPATCH_FAILED — reach the
                            // session authority above but stay collapsed on screen. Splitting them needs new
                            // player-facing copy, which is an unresolved design decision, not one to guess here.
                            emitMessage(MimirMessageClass.Warning, HomeShelfLaunchFailureFallback)
                        }
                        // Proof persistence is downstream of the settled FUX-E transaction: it cannot retain
                        // the re-entrancy guard or rewrite the launch/session outcome if Room is unavailable.
                        launchProof?.let { proof ->
                            try {
                                androidGameProofs.record(proof)
                            } catch (error: Exception) {
                                Log.w("MimirHomeShelf", "Unable to persist Android-game launch proof", error)
                            }
                        }
                    }
                }
            }

            is HomeShelfItemId.AndroidApp -> Unit // Retired Home identity: never projected or launched.
        }
    }

    val cancelTomeReturn: () -> Unit = {
        val current = tomeReturnState
        val canceled = cancelHomeShelfTomeReturn(current)
        if (canceled !== current) tomeReturnState = canceled
        if (tomeReplay != null) tomeReplay = null
    }
    val finishTomeReturn: (Long, HomeShelfTomeReturnOutcome) -> Unit = { generation, outcome ->
        val current = tomeReturnState
        val pending = current.pending
        val deferredActions = pending?.deferredActions.orEmpty()
        val matched = pending?.generation == generation && current.generation == generation
        val finished = finishHomeShelfTomeReturn(current, generation, outcome)
        if (finished !== current) tomeReturnState = finished
        if (matched && outcome == HomeShelfTomeReturnOutcome.SETTLED && deferredActions.isNotEmpty()) {
            tomeReplay = HomeShelfTomeReplay(generation, deferredActions)
        } else if (matched && outcome == HomeShelfTomeReturnOutcome.FAILED) {
            tomeReplay = null
        }
    }
    val isTomeReturnCurrent: (Long) -> Boolean = { generation ->
        tomeReturnState.generation == generation && tomeReturnState.pending?.generation == generation
    }
    val deferTomeReturnAction: (ControllerAction) -> Boolean = defer@{ action ->
        if (tomeReturnState.pending == null || !isHomeShelfTomeDeferredContentAction(action)) {
            return@defer false
        }
        val current = tomeReturnState
        val deferred = deferHomeShelfTomeReturnAction(current, action)
        if (deferred !== current) tomeReturnState = deferred
        true // At the bounded cap the extra action is deliberately consumed and dropped.
    }
    val consumeTomeReplay: (Long) -> List<ControllerAction> = consume@{ generation ->
        val current = tomeReplay?.takeIf { it.generation == generation } ?: return@consume emptyList()
        tomeReplay = null
        current.actions
    }
    val canActivateHomeContent: () -> Boolean = {
        homeFavoriteTruthReady && canActivateHomeShelfContent(
            tomeReturnState,
            HomeFavoriteProjectionKey,
            HomeFavoriteProjectionKey,
        )
    }

    LaunchedEffect(pendingTomeProjection, rootProjection.entries, pinnedTomesEntries, homeViewMode) {
        val pending = pendingTomeProjection ?: return@LaunchedEffect
        val projected = rootProjection.entries.any {
            it is VisualHomeShelfEntry.Tome && it.tomeId == pending.tomeId
        }
        if (projected != pending.pinned) return@LaunchedEffect
        pending.returnEntry?.focusKey?.let { key ->
            focusAuthority.rememberFocusForScope(HomeShelfScope, key)
            tomeReturnState = beginHomeShelfTomeReturn(tomeReturnState, key)
        }
        if (!pending.pinned && homeViewMode == HomeViewMode.PINNED_TOMES && pinnedTomesEntries.isEmpty()) {
            switchHomeViewMode(HomeViewMode.EDITORS_CHOICE)
        }
        pendingTomeProjection = null
        pending.onSettled()
    }

    val createTome: (String, (HomeTomeEditResult) -> Unit) -> Unit = { name, done ->
        scope.launch { done(HomeTomeStore.create(context, name)) }
    }
    val renameTome: (TomeId.Custom, String, (HomeTomeEditResult) -> Unit) -> Unit = { tomeId, name, done ->
        scope.launch { done(HomeTomeStore.rename(context, tomeId, name)) }
    }
    val toggleTomeMembership: (TomeId.Custom, HomeShelfItemId, (Boolean) -> Unit) -> Unit = { tomeId, itemId, done ->
        val member = itemId.toFavoriteMemberIdOrNull()
        if (member == null) {
            done(false)
        } else {
            // S-3 : read BEFORE the toggle and
            // the live shelf snapshot now, at the same moment as the click that started this — not after
            // whatever dispatch delay `scope.launch` introduces.
            val before = HomeTomeStore.document.value
            val alreadyFavorite = itemId in membershipIds
            scope.launch {
                val tomeSaved = HomeTomeStore.toggleMember(context, tomeId, member)
                val after = HomeTomeStore.document.value
                // Direction is DERIVED from the actual document, not assumed from the caller's optimistic
                // UI flip — homeTomeToggleShouldFavorite is false for every case except a demonstrated ADD
                // (see its KDoc): a REMOVE, a refused tomeSaved, or a game that's already a favorite all
                // skip the write below, so a Tome-removal NEVER un-favorites. The request specified only for
                // add-inherits-favorite; silently dropping shelf state because a game left one Tome would
                // change state he never asked to change. Do not "fix" this asymmetry into a symmetric one.
                val shouldFavorite = tomeSaved &&
                    homeTomeToggleShouldFavorite(before, after, tomeId, member, alreadyFavorite)
                // applyShelfMembership is the exact same mutation setMembershipById uses for the Favorite
                // star -- one shared path, not a copy of its logic and not a fire-and-forget bolted on top.
                // If it fails, the composed result must say so: the player must never be told "saved" while
                // the game silently sits in the Tome as a non-favorite dormant identity -- see
                // CustomHomeTome.members' KDoc. A favorite failure does not roll back the Tome write;
                // retrying must account for the already-written membership.
                val favoriteApplied = !shouldFavorite || applyShelfMembership(itemId, true)
                // F8: confirm the add. Since OR-1 the game also stays on Editor's Choice, so nothing moved.
                if (tomeSaved) {
                    homeTomeAddedMessage(before, after, tomeId, member)
                        ?.let { emitMessage(MimirMessageClass.Confirmation, it) }
                }
                done(tomeSaved && favoriteApplied)
            }
        }
    }
    val deleteTome: (TomeId.Custom, (Boolean) -> Unit) -> Unit = delete@{ tomeId, done ->
        // Delete and pin both wait on the same single projection handoff. Never let a fast
        // X -> edit -> delete overwrite the pin callback and strand its gate indefinitely.
        if (!tomeProjectionMutationGate.tryBegin(tomeId)) {
            done(false)
            return@delete
        }
        cancelTomeReturn()
        if (tomeSession?.tomeId == tomeId) {
            tomeSessionGeneration = nextHomeTomeSessionGeneration(tomeSessionGeneration)
            leaveHomeTomeScope(focusAuthority, HomeTomeFolioScope)
            resetFolioFocusSettlement()
            tomeSession = null
        }
        val wasPinned = tomeId in rootProjection.document.pinOrder
        val returnEntry = nearestSurvivingHomeEntry(visibleRootEntries, VisualHomeShelfEntry.Tome(tomeId))
        scope.launch {
            val deleted = runCatching { HomeTomeStore.delete(context, tomeId) }.getOrDefault(false)
            if (!deleted) {
                tomeProjectionMutationGate.finish(tomeId)
                done(false)
            } else if (wasPinned) {
                pendingTomeProjection = PendingTomeProjection(tomeId, false, returnEntry) {
                    tomeProjectionMutationGate.finish(tomeId)
                    done(true)
                }
            } else {
                tomeProjectionMutationGate.finish(tomeId)
                done(true)
            }
        }
    }
    val setTomePinned: (TomeId, Boolean, (Boolean) -> Unit) -> Unit = pin@{ tomeId, pinned, onSettled ->
        // X may auto-repeat or be pressed twice before SharedPreferences + the Home projection settle.
        // The projection handoff is a single slot, so coalesce every later pin request until this one ends.
        if (!tomeProjectionMutationGate.tryBegin(tomeId)) {
            onSettled(false)
            return@pin
        }
        cancelTomeReturn()
        scope.launch {
            val persisted = runCatching { HomeTomeStore.setPinned(context, tomeId, pinned) }
                .getOrDefault(false)
            if (persisted) {
                pendingTomeProjection = PendingTomeProjection(tomeId, pinned, returnEntry = null) {
                    tomeProjectionMutationGate.finish(tomeId)
                    onSettled(true)
                }
            } else {
                tomeProjectionMutationGate.finish(tomeId)
                onSettled(false)
            }
        }
    }
    val openTome: (TomeId, HomeTomeOrigin, Int?, Int?) -> Unit = open@{
            tomeId, origin, browserIndex, browserScroll ->
        cancelTomeReturn()
        if (tomeId == TomeId.HomeOverview) return@open
        if (tomeId !in tomeCatalog.tomes) return@open
        val availableSlots = tomeCatalog.eligibleMemberSlotsByTome[tomeId]
            .orEmpty()
            .map { it?.toHomeShelfItemId() }
        val firstOccupiedSeat = availableSlots.indexOfFirst { it != null }.coerceAtLeast(0)
        val firstMemberSeat = openingHomeTomeFocusIndex(
            memory = tomeFolioFocusMemory[tomeId],
            memberSlots = availableSlots,
            preferredIndex = firstOccupiedSeat,
        )
        val firstMemberFocus = availableSlots.getOrNull(firstMemberSeat)?.let(::homeTomeMemberFocusKey)
        val scopeEntered = when (origin) {
            HomeTomeOrigin.HOME_ROOT -> enterHomeTomeScope(
                focusAuthority,
                HomeTomeFolioScope,
                firstMemberFocus,
            )
            HomeTomeOrigin.TOME_LIBRARY -> replaceHomeTomeScope(
                focusAuthority,
                HomeTomeLibraryScope,
                HomeTomeFolioScope,
                firstMemberFocus,
            )
        }
        if (!scopeEntered) return@open
        val sessionRootEntries = when (homeViewMode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntries
            HomeViewMode.PINNED_TOMES -> pinnedTomesEntries
        }
        val rootEntry = homeShelfEntryForKey(
            sessionRootEntries,
            focusAuthority.rememberedFocusIn(HomeShelfScope),
        )
        tomeSessionGeneration = nextHomeTomeSessionGeneration(tomeSessionGeneration)
        folioFocusedIndex = firstMemberSeat
        folioTransition = HomeTomePageTransition(settledPage = firstMemberSeat / HomeTomeFolioPageSize)
        resetFolioFocusSettlement()
        tomeArrangeState = null
        tomeSession = HomeTomeSession(
            tomeId = tomeId,
            origin = origin,
            rootEntry = rootEntry,
            rootMode = homeViewMode,
            rootFallbackEntry = nearestSurvivingHomeEntry(
                sessionRootEntries,
                VisualHomeShelfEntry.Tome(tomeId),
            ) ?: rootEntry,
            browserFocusedIndex = browserIndex,
            browserFirstVisibleIndex = browserScroll,
            generation = tomeSessionGeneration,
        )
    }
    val openHomeOverview: (HomeTomeOrigin, Int?, Int?) -> Boolean =
        overview@{ origin, browserIndex, browserScroll ->
        cancelTomeReturn()
        if (!homeFavoriteTruthReady) return@overview false
        val overviewRootEntries = homeRootEntriesForViewMode(
            homeViewMode,
            editorsChoiceEntries,
            pinnedTomesEntries,
        )
        val rootEntry = homeShelfEntryForKey(
            overviewRootEntries,
            focusAuthority.rememberedFocusIn(HomeShelfScope),
        )
        val preferredItem = (rootEntry as? VisualHomeShelfEntry.Game)?.itemId
        val preferredMember = preferredItem?.toFavoriteMemberIdOrNull()
        val preferredSeat = preferredMember
            ?.let(homeOverviewSlots::indexOf)
            ?.takeIf { it >= 0 }
            ?: homeOverviewSlots.indexOfFirst { it != null }.coerceAtLeast(0)
        val initialSeat = openingHomeTomeFocusIndex(
            memory = tomeFolioFocusMemory[TomeId.HomeOverview],
            memberSlots = homeOverviewSlots.map { it?.toHomeShelfItemId() },
            preferredIndex = preferredSeat,
        )
        val initialKey = homeOverviewSlots.getOrNull(initialSeat)
            ?.toHomeShelfItemId()
            ?.let(::homeTomeMemberFocusKey)
        val scopeEntered = when (origin) {
            HomeTomeOrigin.HOME_ROOT -> enterHomeTomeScope(focusAuthority, HomeTomeFolioScope, initialKey)
            HomeTomeOrigin.TOME_LIBRARY ->
                replaceHomeTomeScope(focusAuthority, HomeTomeLibraryScope, HomeTomeFolioScope, initialKey)
        }
        if (!scopeEntered) return@overview false
        tomeSessionGeneration = nextHomeTomeSessionGeneration(tomeSessionGeneration)
        folioFocusedIndex = initialSeat
        folioTransition = HomeTomePageTransition(
            settledPage = initialSeat / HomeTomeFolioPageSize,
        )
        resetFolioFocusSettlement()
        tomeArrangeState = null
        tomeSession = HomeTomeSession(
            tomeId = TomeId.HomeOverview,
            origin = origin,
            rootEntry = rootEntry,
            rootMode = homeViewMode,
            rootFallbackEntry = rootEntry,
            browserFocusedIndex = browserIndex,
            browserFirstVisibleIndex = browserScroll,
            generation = tomeSessionGeneration,
        )
        true
    }
    val restoreRootEntry: (VisualHomeShelfEntry?) -> Unit = { entry ->
        captureAuthoritativeTomeFolioFocus()
        val restoringMode = tomeSession?.rootMode ?: homeViewMode
        // The last pin may have been removed inside the Tome: the return then lands on Editor's Choice, not on the mode it left.
        val destinationMode = if (
            restoringMode == HomeViewMode.PINNED_TOMES && pinnedTomesEntries.isEmpty()
        ) {
            HomeViewMode.EDITORS_CHOICE
        } else {
            restoringMode
        }
        // The destination is decided BEFORE the scope is popped, because the pop is where focus lands (RC-49, H-03c). It used to be
        // resolved after, so the pop restored the shelf's memory from before the Tome opened and the ring sat on that tile (or, when
        // it was deleted, on a geometric neighbour) until the key was stamped a frame or up to eight later, then snapped.
        val restoringEntries = when (destinationMode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntries
            HomeViewMode.PINNED_TOMES -> pinnedTomesEntries
        }
        val rememberedRoot = when (destinationMode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceRoot
            HomeViewMode.PINNED_TOMES -> pinnedTomesRoot
        }
        val resolvedEntry = entry?.takeIf { it in restoringEntries }
            ?: restoringEntries.firstOrNull { it.toRootRecord() == rememberedRoot }
            ?: restoringEntries.firstOrNull()
        val landingKey = resolvedEntry?.focusKey
        when (focusAuthority.state.value.scope) {
            HomeTomeLibraryScope -> leaveHomeTomeScopeLandingOn(focusAuthority, HomeTomeLibraryScope, landingKey)
            HomeTomeFolioScope -> leaveHomeTomeScopeLandingOn(focusAuthority, HomeTomeFolioScope, landingKey)
            else -> Unit
        }
        tomeSessionGeneration = nextHomeTomeSessionGeneration(tomeSessionGeneration)
        resetFolioFocusSettlement()
        tomeSession = null
        // Every mode switch bumps the epoch, this fallback included: the shelf's window, finger mover and ring seat are keyed on it
        // (and on the surface label, which changes with the mode), so a switch that left the epoch alone landed the new list on the old one's seat.
        if (homeViewMode != restoringMode || homeViewMode != destinationMode) {
            homeSurfaceHandoffDirection = 0
            homeSurfaceEpoch += 1
        }
        homeViewMode = destinationMode
        // The ordered return still runs: it covers a destination the shelf has not registered yet (a mode switch registers its keys
        // after the epoch recomposes), where the pop could only land on a neighbour.
        landingKey?.let { key ->
            focusAuthority.rememberFocusForScope(HomeShelfScope, key)
            tomeReturnState = beginHomeShelfTomeReturn(tomeReturnState, key)
        }
    }
    val closeTome: () -> Boolean = close@{
        val session = tomeSession ?: return@close false
        val sessionEntries = when (session.rootMode) {
            HomeViewMode.EDITORS_CHOICE -> editorsChoiceEntries
            HomeViewMode.PINNED_TOMES -> pinnedTomesEntries
        }
        val entry = if (session.origin == HomeTomeOrigin.HOME_ROOT) {
            if (session.tomeId == TomeId.HomeOverview) {
                session.rootEntry ?: session.rootFallbackEntry
            } else {
                VisualHomeShelfEntry.Tome(session.tomeId).takeIf { it in sessionEntries }
                    ?: session.rootFallbackEntry
            }
        } else {
            session.rootEntry
        }
        restoreRootEntry(entry)
        true
    }
    val closeTomeToLibrary: (arrivalFence: Long?) -> Boolean = close@{ arrivalFence ->
        val session = tomeSession?.takeIf { it.origin == HomeTomeOrigin.TOME_LIBRARY } ?: return@close false
        @Suppress("UNUSED_VARIABLE") val retainedRootEntry = session.rootEntry
        captureAuthoritativeTomeFolioFocus()
        tomeSessionGeneration = nextHomeTomeSessionGeneration(tomeSessionGeneration)
        leaveHomeTomeScope(focusAuthority, HomeTomeFolioScope)
        resetFolioFocusSettlement()
        // LOGICAL NOW, PIXELS A MOMENT LONGER -- the return path's half of the coordination.
        //
        // Every line above is logical: focus leaves the Folio scope, the generation bumps, settlement
        // resets. Those must be synchronous, because the caller enters the browser's scope on the very
        // next line and the two scopes cannot both own focus.
        //
        // Clearing `tomeSession` is not logical, it is a PICTURE, and clearing it here is what produced
        // the return-path hole. Observed at 60fps on a Nova: the Folio unmounted and the browser's
        // first frame did not land for three more, so frames 56-58 were the Home shelf, sharp and at
        // +30% brightness. The browser is published BEFORE this call (see the dispatcher's own comment)
        // -- it just has not painted yet.
        //
        // So the Folio keeps painting across the handover.
        //
        // CP02 : the FOUR
        // FRAMES this function used to hold for were an open-loop clock that could not observe the layer
        // it was waiting for -- survivable only because the incoming Library ran with NO hold
        // (mimirIncomingOverlayHoldMsForPresentation's old FOLIO_RETURN arm). Now that arm carries
        // OverlayDissolveCoverMs (130ms), same as MimirAppHost.kt's kdoc. A frame count under a hold
        // reopens the exact hole this file exists to close. So the release is now the Library's OWN
        // arrival settling (bindings.navigation.homeTomeLibraryOpenSettled), the mirror of the open hop's
        // `folioCovers` release (MimirAppHost.kt, `LaunchedEffect(folioCovers, ...)`): wait for opacity,
        // not for a tuned duration.
        //
        // CRASH-DRIVEN RESTRUCTURE, 2026-09-02: the wait itself does NOT live here any more. It did on
        // the first attempt (a captured `navigation: MimirNavigationCoordinator` parameter on
        // rememberHomeShelfCoordinator plus a `scope.launch { snapshotFlow {...}.first {...} }` in this
        // very function body) and that build VerifyError'd on every cold start -- this composable
        // is ~2300 lines with dozens of INLINED `remember`/`derivedStateOf` bodies, and Compose's compiler
        // expands every @Composable PARAMETER into $changed dirty-bits plus a full-argument recomposition
        // retry lambda -- one more reference parameter pushed ART's verifier past what it accepts
        // (`v268`, a register number, not a logic defect). **So this function must not gain a
        // navigation-coordinator parameter, and the actual wait must not be new body weight here either.**
        //
        // What stays HERE, synchronous, cheap: only [arrivalFence] itself (a plain Long?, captured by the
        // CALLER -- see below) and one assignment to the tiny `tomeLibraryReturnPendingFence` local. The
        // wait moves to `HomeTomeLibraryReturnReleaseEffect`, a genuinely separate top-level composable
        // (`MimirAppCoordinator.kt`, called once from `MimirAppCoordinator`'s own body, itself already
        // huge and NOT grown by inlining a `LaunchedEffect` into it either -- one call line, matching that
        // file's existing `MimirAppLifecycleEffect`/`MimirCompanionEffects` idiom).
        //
        // THE FENCE ITSELF is unchanged in spirit from the first attempt, only in WHERE it is captured:
        // `closeTomeSurface` (MimirAppInputDispatch.kt) -- a plain, non-@Composable function, so none of
        // this register pressure applies to it -- republishes `bindings.navigation.homeTomeLibrary`
        // non-null, THEN reads `bindings.navigation.homeTomeLibraryOpenArrivalEpoch` SYNCHRONOUSLY, in the
        // same call stack, before calling this function. A Compose state write does not synchronously
        // trigger recomposition, so that read cannot have observed the epoch bump the write will
        // eventually cause (MimirAppHost.kt's onIncomingSettleChanged, which only runs once recomposition
        // picks up the fresh arrival). The bump is therefore guaranteed to land strictly AFTER the fence
        // is captured, for every timing this can run under -- including reduced motion's one-frame
        // false-then-true. Once the fence is a STORED value rather than a live read, WHEN the release
        // effect starts collecting no longer matters: the wait condition (`epoch > fence && settled`) is
        // on durable state, not on catching an edge.
        //
        // NULL ARRIVAL FENCE, corrected analysis: the false-edge
        // (and so the epoch bump) was "guaranteed on every reachable interaction." **That was wrong.** The
        // deleted-tome auto-close below calls this function too, and it never republishes
        // `bindings.navigation.homeTomeLibrary` -- no republish, no fresh arrival, no epoch bump, and the
        // first attempt's fenced wait would have hung forever on that path, stranding `tomeSession`
        // non-null. `arrivalFence == null` names exactly that case: nothing is arriving to sequence
        // against, so clear immediately rather than wait for a bump that will never come.
        if (arrivalFence == null) {
            tomeSession = null
        } else {
            tomeLibraryReturnPendingFence = arrivalFence
        }
        true
    }
    // CP02: the other half of closeTomeToLibrary's non-null-fence arm. Called by
    // HomeTomeLibraryReturnReleaseEffect (MimirAppCoordinator.kt) once its wait resolves -- either the
    // Library's own arrival settled, or the browser was torn down out from under it (the reversal
    // escape). Guarded on fence equality: a stale completion (should one ever race a newer pending fence)
    // is a no-op rather than clearing a session a NEWER return already owns.
    val completeTomeLibraryReturn: (fence: Long) -> Unit = complete@{ fence ->
        if (tomeLibraryReturnPendingFence != fence) return@complete
        tomeLibraryReturnPendingFence = null
        tomeSession = null
    }
    LaunchedEffect(tomeSession?.tomeId, tomeCatalog.tomes.keys, homeFavoriteTruthReady) {
        val session = tomeSession ?: return@LaunchedEffect
        if (homeFavoriteTruthReady &&
            session.tomeId != TomeId.HomeOverview &&
            session.tomeId !in tomeCatalog.tomes
        ) {
            // No browser republish on this path -- see closeTomeToLibrary's kdoc on the null fence.
            if (session.origin == HomeTomeOrigin.TOME_LIBRARY) closeTomeToLibrary(null) else closeTome()
        }
    }
    val launchFromLaunchCard: (
        HomeShelfRow,
        GameRow,
        (GameLaunchUiState) -> Unit,
        () -> Unit,
        LaunchChoice,
    ) -> Unit = launchCard@{ row, game, onLaunchState, onDispatched, choice ->
        if (!BuildConfig.FINAL_SHELF_ENABLED) {
            // R6-P0-02 G3 (house rule: log every silent decline).
            Log.i("MimirHomeShelf", "play declined guard=finalShelfEnabled item=${row.itemId}")
            return@launchCard
        }
        if (!canActivateFavoriteItem(row.itemId, membershipIds, pendingRemovalIds)) {
            // R6-P0-02 G4 (house rule: log every silent decline).
            Log.i("MimirHomeShelf", "play declined guard=canActivateFavoriteItem item=${row.itemId}")
            return@launchCard
        }
        if (launchingRow != null) {
            // R6-P0-02 G5 (house rule: log every silent decline).
            Log.i("MimirHomeShelf", "play declined guard=launchingRow item=${row.itemId}")
            return@launchCard
        }
        val restartFiled = HomeLaunchCardResume.consumeRestart(game.gameId) // taken at the entry of the Play path
        val returnTarget = HomeLaunchReturnTarget.from(row)
        val returnHandle = HomeLaunchReturnHandle.create(returnTarget, tomeSession, homeViewMode)
        onLaunchState(GameLaunchUiState.Launching)
        scope.launch {
            // O15: a refusal names what held it, read from the session that refused this admission.
            var heldBy = GameLaunchSessionHold.STARTING
            fun admitHomeGame() = MimirSessionAuthority.prepareHomeGame(
                game = game,
                returnTarget = returnTarget,
                returnHandle = returnHandle,
                onHeld = { heldBy = it },
            )
            var operation = admitHomeGame()
            if (operation == null && CompanionProcessRuntime.releaseParkedLeaseForPress(context)) {
                // The refusal was held by a
                // stale parked lease -- task gone (e.g. Lime3DS's own Close Game reached via Recents),
                // process still cached. Retry the SAME admission once, in this same press.
                operation = admitHomeGame()
            }
            // F3, as on the shelf row above: resume a HOME-parked game, or ask to end it for another one.
            val parkedPress = if (operation == null) homeParkedPress(context, game.gameId, game.title, emitMessage, restart = restartFiled) else HomeParkedPress.NOT_PARKED
            if (parkedPress == HomeParkedPress.ENDED_PARKED_GAME) operation = admitHomeGame()
            val admittedOperation = operation
            if (admittedOperation == null) {
                // O-3 (2026-08-12): Idle's own KDoc says "No launch attempted yet" — a lie here, since a
                // launch WAS attempted on this exact row and was refused because the session mutex is
                // held (audit finding L4).
                onLaunchState(GameLaunchUiState.SessionHeld(heldBy))
                // R6-P0-02 follow-up ("say so", policy): the card itself paints no status line
                // (2026-08-21 ruling) -- onLaunchState above stays exactly that inert for an ordinary
                // refusal. A still-parked refusal (release-at-the-press could not clear it) gets Main's
                // one-line toast instead, the same message the Home row shows for the same condition.
                MainGameParkingState.current()?.takeIf { parkedPress == HomeParkedPress.NOT_PARKED }?.let { lease ->
                    emitMessage(MimirMessageClass.Warning, parkedGameStillPausedMessage(parkedGameDisplayTitle(lease)))
                }
                // F3: the parked game was ended for this launch and the launch was refused anyway: say why.
                if (parkedPress == HomeParkedPress.ENDED_PARKED_GAME) emitHomeShelfSessionHeldMessage(heldBy, emitMessage)
                return@launch
            }
            launchingRow = row
            // On a single display, the launch cover bridges Mimir to the game and hides emulator loading.
            val secondaryDisplayId = CompanionProcessRuntime.services(context)
                .taskRegistry.state.value.secondaryDisplayId
            // the one intro entry, shared with Home's row and every Library launch.
            val intro = beginGameOpeningIntro(context, game, secondaryDisplayId)
            // Follow-up on the discovered failure path: the
            // clear moves into `finally` -- previously a thrown exception from settleEmulatedHomeLaunch
            // would skip it and leave the re-entrancy guard latched forever.
            val launchResult = try {
                withContext(NonCancellable) {
                    settleEmulatedHomeLaunch(
                        context = context,
                        itemId = row.itemId,
                        game = game,
                        returnHandle = returnHandle,
                        launchController = launchController,
                        choice = choice,
                    )
                }
            } finally {
                launchingRow = null
            }
            val uiState = launchResult?.let(GameLaunchUiState::from) ?: GameLaunchUiState.Unwired
            onLaunchState(uiState)
            if (launchResult != null) {
                MimirSessionAuthority.settleHomeGame(admittedOperation, game, launchResult)
            } else {
                MimirSessionAuthority.failHomeGame(admittedOperation, game, "dispatch_failed_without_result")
            }
            if (launchResult?.launched == true) {
                onDispatched()
            }
            if (intro != null) finishGameOpeningIntro(scope, launched = launchResult?.launched == true)
        }
    }

    return HomeShelfCoordinator(
        rows = tiledDisplayRows,
        entries = visualEntries,
        tomes = tomeModels,
        toast = toast,
        consumeDeliveredMessage = consumeDeliveredMessage,
        launchReturnFocusKey = pendingLaunchReturn?.takeIf { it.tomeSession == null }?.target?.focusKey
            ?: when (val root = pendingSurfaceRestore?.rootEntry) {
                is HomeRootEntryRecord.Game -> homeShelfFocusKey(root.itemId)
                is HomeRootEntryRecord.Tome -> homeShelfTomeFocusKey(root.tomeId)
                null -> null
            },
        launchReturnRestoring = (!homeFavoriteTruthReady && destination == MimirDestination.HOME) ||
            pendingLaunchReturn != null ||
            (pendingSurfaceRestore != null && destination == MimirDestination.HOME),
        surfaceRestorationPending = (!restorationTruthReady || pendingSurfaceRestore != null) &&
            destination == MimirDestination.HOME,
        viewMode = homeViewMode,
        viewModeToggleAvailable = pinnedTomesEntries.isNotEmpty(),
        toggleViewMode = toggleHomeViewMode,
        refusePinnedTomesSwitch = { emitMessage(HomePinnedTomesRefusalClass, HomePinnedTomesRefusalHint) },
        jumpLooseHomeAlphabetBucket = jumpLooseHomeAlphabetBucket,
        alphabetJumpFeedback = alphabetJumpFeedback,
        inputProjection = inputProjection,
        surfaceLabel = homeViewModeLabel(homeResources, homeViewMode),
        tomeMarkFor = { itemId ->
            if (homeViewMode == HomeViewMode.EDITORS_CHOICE) {
                homeTomeMarkLabel(homeResources, durableRootProjection.document, itemId.toFavoriteMemberIdOrNull())
            } else {
                null
            }
        },
        surfaceEpoch = homeSurfaceEpoch,
        surfaceHandoffDirection = homeSurfaceHandoffDirection,
        rootProjectionSettled = homeFavoriteTruthReady,
        contentSettled = homeFavoriteTruthReady,
        canActivateHomeContent = canActivateHomeContent,
        tomeDocument = rootProjection.document,
        availableTomes = tomeModels,
        homeOverviewTome = homeOverviewModel,
        tomeSession = tomeSession,
        tomeFolioState = tomeFolioState,
        tomeFolioRows = folioRows.associateBy(HomeShelfRow::itemId),
        focusTomeMember = focusTomeMember,
        settleTomeFolioFocus = settleTomeFolioFocus,
        unsettleTomeFolioFocus = unsettleTomeFolioFocus,
        navigateTomeFolio = navigateTomeFolio,
        routeTomeFolioAction = routeTomeFolioAction,
        enterTomeArrange = enterTomeArrange,
        dispatchTomeArrange = dispatchTomeArrange,
        dispatchTouchTomeArrange = dispatchTouchTomeArrange,
        cancelTomePageResidue = cancelTomeResidue,
        createTome = createTome,
        renameTome = renameTome,
        toggleTomeMembership = toggleTomeMembership,
        deleteTome = deleteTome,
        setTomePinned = setTomePinned,
        openTome = openTome,
        openHomeOverview = openHomeOverview,
        restoreRootEntry = restoreRootEntry,
        closeTome = closeTome,
        closeTomeToLibrary = closeTomeToLibrary,
        tomeLibraryReturnPendingFence = tomeLibraryReturnPendingFence,
        completeTomeLibraryReturn = completeTomeLibraryReturn,
        tomeReturn = tomeReturnState.pending,
        isTomeReturnCurrent = isTomeReturnCurrent,
        finishTomeReturn = finishTomeReturn,
        cancelTomeReturn = cancelTomeReturn,
        deferTomeReturnAction = deferTomeReturnAction,
        tomeReplay = tomeReplay,
        consumeTomeReplay = consumeTomeReplay,
        membershipIds = membershipIds,
        mutationIds = mutationIds,
        pendingRemovalIds = pendingRemovalIds,
        canActivateItem = { itemId ->
            canActivateFavoriteItem(itemId, membershipIds, pendingRemovalIds)
        },
        mutationRevision = mutationRevision,
        launch = launch,
        launchFromLaunchCard = launchFromLaunchCard,
        gameForAchievements = { row ->
            (row.itemId as? HomeShelfItemId.Emulated)?.let { launchGamesById[it.gameId] }
        },
        setMembership = setMembership,
        launchingRow = launchingRow,
        removeFavorite = { setMembershipById(it, false) },
        addFavorite = { setMembershipById(it, true) },
        refreshTiles = { sgdbEpoch++ },
    )
}

/**
 * The historical Home shelf launch-failure line. Kept as the single fallback for the paths that genuinely
 * have no typed reason to speak from, so the string lives in exactly one place.
 */
internal const val HomeShelfLaunchFailureFallback = "Game didn’t start"

/**
 * O-3 (2026-08-12): the one helper both Home shelf row-tap launch paths (Emulated and AndroidGame) call
 * when `MimirSessionAuthority.prepareHomeGame`/`preparePrimaryApp` refuses because another launch already
 * holds the session mutex. Neither row-tap path has a [GameLaunchUiState] card to update — unlike
 * `launchFromLaunchCard` in this same file — so both show the card's sentence as a message instead, rather than each
 * inventing its own wording.
 *
 * O15 : [hold] is what the refusing admission reported, and [gameLaunchSessionHeldMessage]
 * picks the sentence for it.
 */
internal fun emitHomeShelfSessionHeldMessage(
    hold: GameLaunchSessionHold,
    emitMessage: (MimirMessageClass, String) -> Unit,
) {
    emitMessage(MimirMessageClass.Warning, gameLaunchSessionHeldMessage(hold))
}

/**
 * The parked game's display title for the one-line Main
 * message, read from the session the ledger still carries for it -- the lease itself carries no title. Null
 * when the session has moved on (a release raced this read) or never had one.
 */
internal fun parkedGameDisplayTitle(lease: MainGameParkingLease): String? =
    MimirSessionAuthority.state.value.gameSession
        ?.takeIf { it.gameId == lease.gameId }
        ?.title
        ?.trim()
        ?.takeIf(String::isNotBlank)

/** Shared parked-game refusal copy. */
internal fun parkedGameStillPausedMessage(title: String?): String =
    if (title != null) {
        "$title is still paused. Return to it from the lower screen and close it first."
    } else {
        "A game is still paused. Return to it from the lower screen and close it first."
    }

/**
 * R6-P0-02 follow-up ("say so", policy): Main's one-line message for a PLAY refusal that
 * release-at-the-press (item 1) could not clear -- identified by the same live parked lease S1's
 * `launch_refused … parked=true` line already reads, not by the generic RUNNING hold, so an ordinary
 * "another app is open" refusal keeps today's [gameLaunchSessionHeldMessage] sentence untouched. Shared by
 * the Home row and the Home launch card -- the two Main PLAY entry points in this file.
 */
internal fun emitHomeShelfGameRefusalMessage(
    hold: GameLaunchSessionHold,
    emitMessage: (MimirMessageClass, String) -> Unit,
) {
    val parkedMessage = MainGameParkingState.current()?.let { lease ->
        parkedGameStillPausedMessage(parkedGameDisplayTitle(lease))
    }
    if (parkedMessage != null) {
        emitMessage(MimirMessageClass.Warning, parkedMessage)
    } else {
        emitHomeShelfSessionHeldMessage(hold, emitMessage)
    }
}

/**
 * Outcome-dropping audit (2026-08-12): the Home shelf row's Play reported EVERY launch refusal as the one
 * string [HomeShelfLaunchFailureFallback], discarding the typed [LaunchResult] it already had in hand — the
 * loss of precise refusal reasons surviving on the shelf row after the launch-card fix.
 *
 * The honest renderer already exists and is already consumed by THIS SAME FILE's launch-card call site
 * (`GameLaunchUiState.from` → `onLaunchState`), so this reuses it verbatim rather than inventing a second,
 * differently-worded vocabulary: plain-language lines covering NeedsEmulator / NeedsBios / MissingFile /
 * Unsupported plus all nine typed `LaunchDispatchFailure` values (R-2b added `LOCATOR_CONTENT_MALFORMED`).
 * Zero new player-facing copy is
 * introduced here — that is deliberate, because a shelf row and a launch card describing the same refusal
 * in two different sentences would be its own defect.
 *
 * Falls back to [HomeShelfLaunchFailureFallback] only when there is no result to speak from (the return
 * arm failed before dispatch, so `settleEmulatedHomeLaunch` never got one) or the resolved state carries no
 * runtime line.
 */
internal fun homeShelfLaunchFailureMessage(
    result: LaunchResult?,
    gameId: GameId,
    platformLabelFor: (PlatformId) -> String,
): String {
    val launchState = result?.let(GameLaunchUiState::from) ?: return HomeShelfLaunchFailureFallback
    return resolveHomeLaunchCardStatusLine(
        platformStatusLine = null,
        launchState = launchState,
        // This message is built for the game whose Play was just pressed, so it owns the line by
        // construction; the ownership guard exists to stop one game's status appearing on another's card.
        sessionGameId = gameId,
        targetGameId = gameId,
        platformLabelFor = platformLabelFor,
    ) ?: HomeShelfLaunchFailureFallback
}

/** F7: a Home launch-failure toast as the rail shows it -- its message line and optional supporting line. */
internal data class HomeShelfLaunchFailureToast(val text: String, val supportingText: String?)

/**
 * F7 (product review 2026-09-28; nova-9, rg-rotate-9, thor-4): [homeShelfLaunchFailureMessage], laid out for the
 * Home rail.
 *
 * The rail is a fixed 32/36 dp band that clips its message at two lines. The no-emulator sentence ran past that
 * and lost the half that says what to do. It is split at its own sentence break into the message and the rail's
 * supporting line, so the words are the ones already ruled and neither half shares a line with the other. Every
 * other refusal is unchanged and has no supporting line.
 */
internal fun homeShelfLaunchFailureToast(
    result: LaunchResult?,
    gameId: GameId,
    platformLabelFor: (PlatformId) -> String,
): HomeShelfLaunchFailureToast {
    val message = homeShelfLaunchFailureMessage(result, gameId, platformLabelFor)
    if (result?.let(GameLaunchUiState::from) !is GameLaunchUiState.NeedsEmulator) {
        return HomeShelfLaunchFailureToast(message, null)
    }
    val sentenceBreak = message.lastIndexOf(". ")
    if (sentenceBreak <= 0) return HomeShelfLaunchFailureToast(message, null)
    return HomeShelfLaunchFailureToast(
        text = message.substring(0, sentenceBreak + 1),
        supportingText = message.substring(sentenceBreak + 2),
    )
}

internal suspend fun settleEmulatedHomeLaunch(
    context: Context,
    /**
     * The launched item's identity.
     *
     * Was the whole [HomeShelfRow], of which this one field was all the body ever read. Narrowed
     * 2026-09-01 so the companion display can launch through THIS function rather than around it: a game
     * tapped on screen 2 has no Home row, and the alternative was calling `launchController.launch`
     * directly — which this function's own doc warns silently loses the return-target arming and both
     * Tweaker hooks. Narrowing the parameter was a smaller change than reproducing the function.
     */
    itemId: HomeShelfItemId,
    game: GameRow,
    returnHandle: HomeLaunchReturnHandle,
    launchController: LaunchController,
    onFailureToast: ((LaunchResult?) -> Unit)? = null,
    /**
     * The disc / version the owner picked, or [LaunchChoice.GroupDefault] for plain Play.
     *
     * Additive with a default, so every existing caller is unchanged and Home's ordinary launch still
     * hands the emulator the whole set. Threaded THROUGH this function rather than around it because
     * this is where the return target is armed and the Tweaker pre/post hooks run — a disc launch that
     * bypassed it would silently lose all three.
     */
    choice: LaunchChoice = LaunchChoice.GroupDefault,
    destination: LaunchDispatchDestination = LaunchDispatchDestination.Main,
): LaunchResult? {
    var launchResult: LaunchResult? = null
    val attempt = dispatchHomeExternalLaunchAfterArm(
        arm = { HomeLaunchReturnStore.arm(context, returnHandle) },
        dispatch = {
            launchController.launch(
                game.gameId,
                game.platformId,
                preferredDisc = (choice as? LaunchChoice.Disc)?.number ?: 0,
                launchMemberEntryKey = (choice as? LaunchChoice.Version)?.entryKey,
                preDispatchHook = { ready, attemptId ->
                    // The launch cover (Dolphin, ARMSX2) goes up FIRST: before the intent, and before the Tweaker's status
                    // overlay, which must draw above it. Main only: a Bottom launch has no intro and no cover.
                    if (destination is LaunchDispatchDestination.Main) {
                        armGameLaunchCoverBeforeDispatch(context, game, ready.target)
                    }
                    presentHomeTweakerLaunchStatus(
                        context = context,
                        attemptId = attemptId,
                        workloadPackage = ready.target.emulator.packageName,
                        gameTitle = game.title,
                    )
                },
                postDispatchHook = { ready, attemptId ->
                    activateHomeTweakerAfterDispatch(
                        context = context,
                        attemptId = attemptId,
                        workloadPackage = ready.target.emulator.packageName,
                        gameTitle = game.title,
                    )
                },
                onPreDispatchDegraded = { attemptId, outcome ->
                    presentHomeTweakerLaunchDegraded(context, attemptId, outcome)
                },
                onDispatchFailed = { _, _, attemptId ->
                    // A cover armed for an intent that never left must not sit over Mimir.
                    GameLaunchCover.dispatchFailed(game.gameId.value)
                    TweakerBridge.abandonPendingLaunch(attemptId)
                    TweakerBridge.settleAfterReturn()
                },
                destination = destination,
            )
                .also { launchResult = it }
                .launched
        },
    )
    val dispatched = attempt == HomeExternalLaunchAttempt.DISPATCHED
    if (dispatched) {
        presentHomeTweakerActiveProfile(context, game.title)
        HomeExternalSessionElapsedStore.begin(
            context = context,
            itemId = itemId,
            returnToken = returnHandle.token,
            dispatchedAtElapsedMillis = android.os.SystemClock.elapsedRealtime(),
            placement = homeExternalSessionPlacement(destination),
        )
    }
    if (!dispatched) CompanionState.clearRetainedNowPlaying()
    if (!dispatched) GameLaunchCover.dispatchFailed(game.gameId.value)
    if (!dispatched) {
        acknowledgeHomeLaunchReturnDurably(context, returnHandle)
    }
    if (!dispatched) {
        onFailureToast?.invoke(launchResult)
    }
    return launchResult
}

/** A return-arm failure never reached native dispatch, so it cannot create a launch proof. */
internal fun androidGameLaunchProofFor(
    packageName: String,
    attempt: HomeExternalLaunchAttempt,
    timestampMillis: Long,
): AndroidGameLaunchProof? {
    val outcome = when (attempt) {
        HomeExternalLaunchAttempt.ARMING_FAILED -> return null
        HomeExternalLaunchAttempt.DISPATCHED -> return androidGameDispatchProofFor(
            packageName = packageName,
            timestampMillis = timestampMillis,
        )
        HomeExternalLaunchAttempt.DISPATCH_FAILED -> LaunchOutcome.Failed(LaunchDispatchFailure.BRIDGE_ERROR)
    }
    return AndroidGameLaunchProof(
        packageName = AndroidPackageName(packageName),
        outcome = outcome,
        timestampMillis = timestampMillis,
    )
}

/**
 * Native Android games do not pass through [LaunchController], so a failed Activity start must run the
 * same contained Tweaker unwind owned by the controller's emulated-game path. An arm failure never ran
 * the pre-dispatch hook, and a successful dispatch is settled by the normal return path.
 */
internal suspend fun settleFailedNativeGameLaunch(
    attempt: HomeExternalLaunchAttempt,
    dispatcher: CoroutineDispatcher,
    timeoutMillis: Long,
    settle: suspend () -> Unit,
): PreDispatchOutcome? =
    if (attempt == HomeExternalLaunchAttempt.DISPATCH_FAILED) {
        runContainedPreDispatch(
            dispatcher = dispatcher,
            timeoutMillis = timeoutMillis,
            block = settle,
        )
    } else {
        null
    }

/** Composition-stable holder for the last non-null context rows (never a blank intermediate shelf). */
private class RowsHolder {
    var last: List<HomeShelfRow> = emptyList()
}

private class HomeEntriesHolder {
    var last: List<VisualHomeShelfEntry> = emptyList()
}

/** Keeps the GameRow backing map aligned with [RowsHolder]'s last non-null visual truth. */
internal class HomeGameRowsHolder {
    private var last: Map<GameId, GameRow> = emptyMap()

    fun resolve(current: Map<GameId, GameRow>?): Map<GameId, GameRow> =
        current?.also { last = it } ?: last
}

internal suspend fun acknowledgeHomeLaunchReturnDurably(
    context: Context,
    expected: HomeLaunchReturnHandle,
) {
    while (HomeLaunchReturnStore.acknowledge(context, expected) == HomeLaunchReturnAcknowledgement.RETRY) {
        delay(HomeLaunchReturnDurabilityRetryMs)
    }
}
