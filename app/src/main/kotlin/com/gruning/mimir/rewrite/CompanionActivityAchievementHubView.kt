package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the Bottom's Activity page reads about achievements, derived ONCE per change of its inputs and held as one immutable value.
 *
 * Before this the page's composable body filtered and sorted every checked game, summed the strip's counts and rebuilt the hub's Unknown card on each
 * recomposition, and that body recomposes whenever a swipe's settle flips. Now a recomposition reads a field.
 *
 * @property enabled the hub's gate: connected services on, the key accepted, an account saved.
 * @property snapshots the checked games for the connected account, valid rows only, most recently checked first (the hub's pages, in order).
 * @property unlocked the strip's count of achievements earned across [snapshots].
 * @property total the strip's count of achievements across [snapshots].
 * @property unknownState what the hub's card says when [snapshots] is empty, by cause.
 * @property totalPoints the user's hardcore RetroAchievements points (RA's main score) from the cold-start bulk read, only for the connected account and only while [enabled]; null when not read.
 * @property softcorePoints the user's softcore score beside it, 0 when none or not read. The two are RA's own numbers and are never added.
 * @property recent the user's latest unlocks across every game (the strip), newest first, only for the connected account and only while [enabled]; empty when none were read.
 * @property checking nothing is known about this account yet: no game is held, the durable ledger has not been read and no bulk read has ended. The page then says it is
 *   checking, never "0 earned" and never "nothing checked yet"; it is false the moment the ledger or a live read has put (or failed to put) something on the page.
 */
internal data class CompanionActivityAchievementHubView(
    val enabled: Boolean,
    val snapshots: List<CompanionActivityAchievementSnapshot>,
    val unlocked: Int,
    val total: Int,
    val unknownState: MimirAchievementCardState,
    val totalPoints: Int? = null,
    val recent: List<ActivityRecentUnlock> = emptyList(),
    val checking: Boolean = false,
    val softcorePoints: Int = 0,
)

/**
 * What the hub PAGE's card says when it has no game to show. While the view is still [CompanionActivityAchievementHubView.checking] (nothing is known about the account
 * yet, and the Activity card says it is checking) the card is in its own pending state, so the page and the card agree; once something is known it is the cause the view
 * derived ([CompanionActivityAchievementHubView.unknownState]). A checking view is always [CompanionActivityAchievementHubView.enabled], so no setup cause is hidden by it.
 */
internal fun CompanionActivityAchievementHubView.pageUnknownState(): MimirAchievementCardState =
    if (checking) MimirAchievementCardState.Unknown(MimirAchievementCopy.Checking) else unknownState

/** Pure: the view for one set of inputs. The same rules the page applied inline, in the same order. */
internal fun companionActivityAchievementHubView(
    snapshots: List<CompanionActivityAchievementSnapshot>,
    connectedServicesOn: Boolean,
    account: String?,
    validation: RaKeyState,
    points: CompanionActivityPoints? = null,
    recent: CompanionActivityRecent? = null,
    /** The account the page has settled for (its ledger was published or a bulk read ended); the default says it is settled, so a caller that does not track it never shows "checking". */
    settledAccount: String? = account,
): CompanionActivityAchievementHubView {
    val enabled = connectedServicesOn && validation == RaKeyState.ACCEPTED && !account.isNullOrBlank()
    val visible = companionActivityAchievementSnapshotsFor(snapshots = snapshots, account = account, enabled = enabled)
    return CompanionActivityAchievementHubView(
        enabled = enabled,
        snapshots = visible,
        unlocked = visible.sumOf { it.unlocked },
        total = visible.sumOf { it.total },
        unknownState = companionActivityAchievementUnknownState(
            connectedServicesOn = connectedServicesOn,
            account = account,
            validation = validation,
        ),
        totalPoints = points?.takeIf { enabled && it.account == account }?.points,
        softcorePoints = points?.takeIf { enabled && it.account == account }?.softcore ?: 0,
        recent = recent?.takeIf { enabled && it.account == account }?.unlocks.orEmpty(),
        checking = enabled && visible.isEmpty() && settledAccount != account,
    )
}

/**
 * The view as a flow of its inputs. [derive] runs once per change of any input, on [context] (the process's background dispatcher in the app),
 * never per collector and never per read; an input that re-emits an equal value does not reach it, and an unchanged result is not emitted.
 */
internal fun companionActivityAchievementHubViewFlow(
    snapshots: Flow<List<CompanionActivityAchievementSnapshot>>,
    connectedServicesOn: Flow<Boolean>,
    account: Flow<String?>,
    validation: Flow<RaKeyState>,
    points: Flow<CompanionActivityPoints?> = flowOf(null),
    recent: Flow<CompanionActivityRecent?> = flowOf(null),
    /** The account the page has settled for; null (the default) means the caller does not track it and the view is never "checking". */
    settledAccount: Flow<String?>? = null,
    context: CoroutineContext = EmptyCoroutineContext,
    derive: (List<CompanionActivityAchievementSnapshot>, Boolean, String?, RaKeyState, CompanionActivityPoints?) -> CompanionActivityAchievementHubView =
        ::companionActivityAchievementHubView,
): Flow<CompanionActivityAchievementHubView> =
    combine(
        combine(snapshots, connectedServicesOn, account, validation, points) { s, on, a, v, p -> derive(s, on, a, v, p) },
        recent,
        account,
        settledAccount ?: account,
    ) { view, held, signedIn, settled ->
        // The strip's unlocks ride on the derived view, gated the same way the points are: this account's, and only while the hub is enabled.
        val unlocks = held?.takeIf { view.enabled && it.account == signedIn }?.unlocks.orEmpty()
        val checking = view.enabled && view.snapshots.isEmpty() && settled != signedIn
        val withRecent = if (unlocks == view.recent) view else view.copy(recent = unlocks)
        if (checking == withRecent.checking) withRecent else withRecent.copy(checking = checking)
    }.flowOn(context)

private val hubViewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The process's one hub view, as a StateFlow the Bottom collects. Held for the process (not for a composition), so a page that is left and re-entered
 * re-reads it and derives nothing; only a change to the checked games, the account, the opt-in or the key's state derives again.
 */
internal val companionActivityHubViewState: StateFlow<CompanionActivityAchievementHubView> by lazy {
    val snapshots = CompanionActivityAchievements.snapshots
    val connectedServices = ConnectedServicesStore.optedIn
    val account = RetroAchievementsCredentialStore.connectedUser
    val validation = RetroAchievementsCredentialStore.validationState
    companionActivityAchievementHubViewFlow(
        snapshots = snapshots,
        connectedServicesOn = connectedServices,
        account = account,
        validation = validation,
        points = CompanionActivityAchievements.points,
        recent = CompanionActivityAchievements.recent,
        settledAccount = CompanionActivityAchievements.settledAccount,
        context = Dispatchers.Default,
    ).stateIn(
        scope = hubViewScope,
        started = SharingStarted.Eagerly,
        initialValue = companionActivityAchievementHubView(
            snapshots = snapshots.value,
            connectedServicesOn = connectedServices.value,
            account = account.value,
            validation = validation.value,
            points = CompanionActivityAchievements.points.value,
            recent = CompanionActivityAchievements.recent.value,
            settledAccount = CompanionActivityAchievements.settledAccount.value,
        ),
    )
}
