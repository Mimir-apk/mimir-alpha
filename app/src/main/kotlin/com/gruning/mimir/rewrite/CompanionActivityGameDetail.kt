package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.LaunchedEffectWhileLive
import com.gruning.mimir.rewrite.feature.companion.collectAsStateWhileLive
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.min

/** A last-checked result from the existing achievement lane; this never initiates a request. */
internal data class CompanionActivityAchievementSnapshot(
    val gameId: String,
    val account: String,
    val unlocked: Int,
    val total: Int,
    val gameTitle: String? = null,
    val earned: List<CompanionActivityEarnedAchievement> = emptyList(),
    val checkedAtEpochMillis: Long? = null,
    /**
     * False for a count taken from the bulk completion read, which carries no per-achievement rows: the hub card then says so instead of claiming
     * "none earned yet" for a game that has earned some.
     */
    val rowsKnown: Boolean = true,
    /**
     * A validated relative RA icon path (`/Images/<digits>.png`) from the bulk read, or null. It is a decoration source only, so it takes no part in
     * [sameContentAs]; a later launch-card snapshot of the same game inherits it from the one it replaces.
     */
    val iconPath: String? = null,
    /**
     * RA's own id for the game, from the bulk read or a launch-card check: the key the per-game detail (badges, title art) is asked for by. Null when
     * unknown. A decoration/identity source only, so it takes no part in [sameContentAs].
     */
    val raGameId: Int? = null,
    /** A staged-art source (the RA title or in-game image path) the hub folds in from the card's detail; never set by a read. */
    val artPath: String? = null,
    /**
     * The console RA lists the game under (the bulk read's `ConsoleName`), or null when no read has said. The Activity page's console filter groups by it. A
     * decoration/identity source only, so it takes no part in [sameContentAs]; a launch-card snapshot of the same game inherits it.
     */
    val consoleName: String? = null,
    /**
     * RA's own date for the game's most recent award (epoch millis UTC), from the bulk read, or null. The "Recent" filter and the "Recent activity" order read it.
     * A decoration source only, so it takes no part in [sameContentAs].
     */
    val lastAwardedAtMillis: Long? = null,
    /**
     * How many of [unlocked] were earned in hardcore (0 where nothing says): inside [unlocked], never added to it. The bulk read carries RA's own figure, a check of the
     * game's rows counts its hardcore unlocks. A content field: more hardcore unlocks is a new snapshot even when the total did not move.
     */
    val earnedHardcore: Int = 0,
    /** The highest award RA reports for the game (beaten, completed, mastered), from the bulk read; null when none, or from a check of the game's rows, which names none. A decoration source only. */
    val awardKind: RaAwardKind? = null,
    /**
     * When the player last played the game (epoch millis), from the library, or null when nothing says. The hub folds it in as it lists the games (a read never sets it); with
     * [lastAwardedAtMillis] it orders the page, newest first. A decoration source only, so it takes no part in [sameContentAs].
     */
    val lastPlayedAtMillis: Long? = null,
) {
    /**
     * The hub's card for this snapshot, built once with the snapshot (which is built off the main thread when a check
     * lands) instead of on every composition of a hub page. Not a constructor property, so it takes no part in
     * equality: two snapshots with the same data are equal and carry equal cards.
     */
    val achievementCardState: MimirAchievementCardState.Loaded = buildAchievementCardState()

    /** Same data, whatever the check's time: a re-check that found nothing new is not a new snapshot. */
    fun sameContentAs(other: CompanionActivityAchievementSnapshot): Boolean =
        gameId == other.gameId && account == other.account && unlocked == other.unlocked && total == other.total &&
            gameTitle == other.gameTitle && earned == other.earned && rowsKnown == other.rowsKnown && earnedHardcore == other.earnedHardcore
}

internal data class CompanionActivityEarnedAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val hardcore: Boolean,
)

/**
 * One recent unlock for the Activity page's badge strip: an achievement the user earned, from the ONE recent-unlocks read that comes with the bulk read. [badgePath] is the
 * validated relative `/Badge/<digits>.png` (colour art) or null, the only shape a provider value is trusted in. [raGameId] is RA's id for the game, the key the strip's tap
 * finds the game's card by. Carries no credential, URL or username.
 */
internal data class ActivityRecentUnlock(
    val achievementId: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgePath: String?,
    val raGameId: Int,
    val gameTitle: String,
    val awardedAtMillis: Long?,
    val hardcore: Boolean,
)

/** The recent unlocks the lane holds, tagged with the account they were read for, so a switch can never show one account's badges for another. */
internal data class CompanionActivityRecent(val account: String, val unlocks: List<ActivityRecentUnlock>)

/**
 * The user's two scores and the account they were read for, so a switch can never show one account's points for another. [points] is the HARDCORE score (RetroAchievements'
 * main one) and [softcore] the softcore score; they are RA's own numbers, kept apart and never added.
 */
internal data class CompanionActivityPoints(val account: String, val points: Int, val softcore: Int = 0)

internal object CompanionActivityAchievements {
    private val mutableState = MutableStateFlow<CompanionActivityAchievementSnapshot?>(null)
    val state = mutableState.asStateFlow()
    private val mutableSnapshots = MutableStateFlow<List<CompanionActivityAchievementSnapshot>>(emptyList())
    val snapshots = mutableSnapshots.asStateFlow()
    private val lock = Any()

    /**
     * The snapshot a lane answer leaves for the Bottom, or null when it leaves none (anything but a loaded set with an account).
     * A sort-free filter and map over every achievement, so a caller on the main thread builds it elsewhere and passes it to [publish].
     */
    fun snapshotFor(gameId: String, account: String?, lane: MimirAchievementLaneState): CompanionActivityAchievementSnapshot? {
        val progress = (lane as? MimirAchievementLaneState.Loaded)?.progress
        return if (!account.isNullOrBlank() && progress != null && progress.totalCount > 0) {
            CompanionActivityAchievementSnapshot(
                gameId = gameId,
                account = account,
                unlocked = progress.unlockedCount,
                total = progress.totalCount,
                gameTitle = progress.title,
                earned = progress.achievements.filter { it.unlocked }.map { achievement ->
                    CompanionActivityEarnedAchievement(
                        id = achievement.id,
                        title = achievement.title,
                        description = achievement.description,
                        points = achievement.points,
                        hardcore = achievement.unlockedHardcore,
                    )
                },
                checkedAtEpochMillis = System.currentTimeMillis(),
                raGameId = progress.gameId,
                earnedHardcore = progress.achievements.count { it.unlockedHardcore },
                // The per-game answer names the console too, so a game checked at its launch card is listed under it even when no bulk read has said.
                consoleName = progress.consoleName?.trim()?.takeIf(String::isNotEmpty),
            )
        } else null
    }

    /**
     * Publish one lane answer. Three rules keep a Main focus move from stirring the Bottom's whole Activity page, which collects these flows:
     *  - the Checking placeholder carries no snapshot and is a no-op here (it used to clear [state] and the answer then set it again: two emissions
     *    per focus commit for nothing);
     *  - a re-check that finds the same data keeps the snapshot the page already holds (same instance, same time), so nothing emits and nothing is
     *    derived again; the time on a snapshot is the time its DATA last changed, which is what the hub's most-recent-first order wants;
     *  - only a changed answer replaces its entry in [snapshots].
     */
    fun publish(
        gameId: String,
        account: String?,
        lane: MimirAchievementLaneState,
        snapshot: CompanionActivityAchievementSnapshot? = snapshotFor(gameId, account, lane),
    ) {
        if (lane == retroAchievementsCheckingState()) return
        synchronized(lock) {
            val existing = mutableSnapshots.value.firstOrNull { it.gameId == gameId && it.account == account }
            val held = snapshot?.let { fresh -> existing?.takeIf { it.sameContentAs(fresh) } }
            // A check from the launch card carries no icon, console or award date; the game keeps the ones the bulk read gave it. The bulk row may sit under RA's own key
            // (`ra:<id>`, a game the library could not be matched to) while the check is keyed by the library game: it is the same RA game, so the decoration comes from it too
            // (without it a lane-only snapshot has no console, and the console picker has nothing to list).
            val effective = held ?: snapshot?.let { fresh ->
                val decorated = existing ?: fresh.raGameId?.let { raId ->
                    mutableSnapshots.value.firstOrNull { it.account == account && it.raGameId == raId && it.consoleName != null }
                }
                inheritBulkDecoration(fresh, decorated)
            }
            mutableState.value = effective
            when {
                effective != null && held == null -> mutableSnapshots.update { current ->
                    current.filterNot { it.gameId == gameId && it.account == account }.plus(effective)
                }
                effective == null && lane is MimirAchievementLaneState.NoSet && !account.isNullOrBlank() -> {
                    mutableSnapshots.update { current -> current
                        .filterNot { it.gameId == gameId && it.account == account }
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * What a launch-card check inherits from the snapshot it replaces: the bulk read's icon and console (a check carries neither), and the award date, which
     * stays the bulk read's unless the check found MORE earned than the snapshot held, in which case the award happened since and the check's own time is
     * the newest date there is.
     */
    private fun inheritBulkDecoration(
        fresh: CompanionActivityAchievementSnapshot,
        existing: CompanionActivityAchievementSnapshot?,
    ): CompanionActivityAchievementSnapshot {
        if (existing == null) return fresh
        var next = fresh
        if (next.iconPath == null && existing.iconPath != null) next = next.copy(iconPath = existing.iconPath)
        // An award belongs to a set: a ROM replaced by another set keeps its library game, and the old set's award is not the new set's.
        if (next.awardKind == null && existing.awardKind != null && existing.raGameId == fresh.raGameId) next = next.copy(awardKind = existing.awardKind)
        if (next.consoleName == null && existing.consoleName != null) next = next.copy(consoleName = existing.consoleName)
        if (next.lastAwardedAtMillis == null) {
            val awarded = if (fresh.unlocked > existing.unlocked) fresh.checkedAtEpochMillis else existing.lastAwardedAtMillis
            if (awarded != null) next = next.copy(lastAwardedAtMillis = awarded)
        }
        return next
    }

    /** The user's total RetroAchievements points from the bulk read, tagged with the account it was read for. */
    private val mutablePoints = MutableStateFlow<CompanionActivityPoints?>(null)
    val points = mutablePoints.asStateFlow()

    /** The user's latest unlocks across every game (the strip), from the recent-unlocks read that comes with the bulk read; null until one has been read. */
    private val mutableRecent = MutableStateFlow<CompanionActivityRecent?>(null)
    val recent = mutableRecent.asStateFlow()

    /**
     * Publish the recent unlocks read for [account] (newest first). It REPLACES this account's earlier list; an answer equal to what is held emits nothing. Another
     * account's list is replaced the same way (one list is held, for one account at a time); [dropAccountsExcept] removes a stale one.
     */
    fun publishRecent(account: String, unlocks: List<ActivityRecentUnlock>) {
        synchronized(lock) {
            mutableRecent.value = CompanionActivityRecent(account, unlocks)
            recentLandedAccount = account
        }
    }

    /** The hardcore [totalPoints] for [account], keeping the softcore score already held for the SAME account (it moves only by [publishSoftcore]), so a new hardcore total never blinks it away. */
    private fun pointsFor(account: String, totalPoints: Int): CompanionActivityPoints =
        CompanionActivityPoints(account, totalPoints, mutablePoints.value?.takeIf { it.account == account }?.softcore ?: 0)

    /**
     * Publish the profile's SOFTCORE score beside the hardcore one already held for [account] (it changes nothing when no points are held for that account, or when the
     * score is the same). A [hydrated] score is the ledger's copy: it never goes over a live answer for the account.
     */
    fun publishSoftcore(account: String, softcorePoints: Int, hydrated: Boolean = false) {
        synchronized(lock) {
            if (hydrated && pointsLandedAccount == account) return
            val held = mutablePoints.value ?: return
            if (held.account != account || held.softcore == softcorePoints) return
            mutablePoints.value = held.copy(softcore = softcorePoints)
        }
    }

    /**
     * Publish the profile's points alone for [account], leaving every held game as it is: the answer of a read whose empty games list was not believed. It is a
     * LIVE points answer (the account's saved points may no longer be published over it), and the page is settled.
     */
    fun publishPoints(account: String, totalPoints: Int) {
        synchronized(lock) {
            mutablePoints.value = pointsFor(account, totalPoints)
            pointsLandedAccount = account
            mutableSettled.value = account
        }
    }

    /**
     * Publish the cold-start bulk answer for [account]: a snapshot for every game the user has progress on ([bulk], counts only), and their total
     * points when the read had them. It REPLACES this account's earlier bulk counts but keeps a richer snapshot a launch-card check already
     * published for the same game when its counts agree, and keeps this account's checked games the bulk read does not list. Another account's data is
     * untouched here; [dropAccountsExcept] removes it. An answer equal to what is held changes nothing and emits nothing.
     *
     * This is a LIVE answer: from now on the account's persisted ledger ([publishHydrated]) may no longer be published over it, and the page is settled.
     */
    fun publishBulk(account: String, bulk: List<CompanionActivityAchievementSnapshot>, totalPoints: Int?) {
        synchronized(lock) {
            // The first live answer of an account's session is authoritative for the bulk rows: a row the ledger published that RA no longer lists goes
            // (a checked game, which carries its achievement rows, stays as it always did).
            publishBulkLocked(account, bulk, totalPoints, dropUnlistedBulkRows = bulkLandedAccount != account)
            bulkLandedAccount = account
            if (totalPoints != null) pointsLandedAccount = account
            mutableSettled.value = account
        }
    }

    /**
     * Publish what the durable ledger held for [account] at start-up (or on switching back to it), so the page shows the last known counts at once instead of
     * zeros or nothing. It is exactly [publishBulk] for the data, with one guard PER FIELD: a field a LIVE read has already landed for this account (the games,
     * the points, the recent unlocks) is left alone, so a slow disk read can never put stale values over fresh ones, while a field the live read did NOT bring
     * (its points or recent request failed) still gets the saved value. Returns whether it published anything. [recent] is the strip's last known unlocks, or
     * null when none were saved.
     */
    fun publishHydrated(
        account: String,
        bulk: List<CompanionActivityAchievementSnapshot>,
        totalPoints: Int?,
        recent: List<ActivityRecentUnlock>?,
    ): Boolean = synchronized(lock) {
        val gamesLive = bulkLandedAccount == account
        val pointsLive = pointsLandedAccount == account
        val recentLive = recentLandedAccount == account
        var published = false
        if (!gamesLive) {
            publishBulkLocked(account, bulk, if (pointsLive) null else totalPoints)
            published = true
        } else if (!pointsLive && totalPoints != null) {
            mutablePoints.value = pointsFor(account, totalPoints)
            published = true
        }
        if (!recentLive && recent != null) {
            mutableRecent.value = CompanionActivityRecent(account, recent)
            published = true
        }
        if (published) mutableSettled.value = account
        published
    }

    /**
     * Whether the page can say anything about [account] yet: its ledger was published, or a live bulk read ended (whatever it found). Until then the page shows the
     * neutral "checking" line, never a zero and never "nothing checked". Account-tagged like every other published value, so a switch cannot inherit it.
     */
    private val mutableSettled = MutableStateFlow<String?>(null)
    val settledAccount = mutableSettled.asStateFlow()

    /** The account whose live bulk read has published its games / its points / its recent unlocks, so the ledger's copy of that field must not be published over it. */
    private var bulkLandedAccount: String? = null
    private var pointsLandedAccount: String? = null
    private var recentLandedAccount: String? = null

    /** A bulk read for [account] has ended without publishing (unavailable, partial, rejected): the page stops saying "checking" and says what it knows. */
    fun markSettled(account: String) {
        synchronized(lock) { mutableSettled.value = account }
    }

    private fun publishBulkLocked(
        account: String,
        bulk: List<CompanionActivityAchievementSnapshot>,
        totalPoints: Int?,
        dropUnlistedBulkRows: Boolean = false,
    ) {
        run {
            mutableSnapshots.update { current ->
                val own = current.filter { it.account == account }.associateBy { it.gameId }
                val merged = bulk.map { fresh ->
                    // A card check's snapshot (it carries the game's rows) stands unless the bulk row is NEWER by the same rule the card and the chip use: a row read at the
                    // same count, or a LOWER one (RA's denormalised counts still catching up after a play), never replaces what the check saw.
                    own[fresh.gameId]
                        ?.takeIf {
                            it.earned.isNotEmpty() &&
                                !activityLaterCountWins(it.checkedAtEpochMillis, it.unlocked, it.total, fresh.checkedAtEpochMillis, fresh.unlocked, fresh.total)
                        }
                        ?.let { rich ->
                            val console = fresh.consoleName ?: rich.consoleName
                            val awarded = fresh.lastAwardedAtMillis ?: rich.lastAwardedAtMillis
                            val award = fresh.awardKind ?: rich.awardKind
                            val hardcore = maxOf(rich.earnedHardcore, fresh.earnedHardcore)
                            if (rich.iconPath == fresh.iconPath && rich.consoleName == console && rich.lastAwardedAtMillis == awarded &&
                                rich.awardKind == award && rich.earnedHardcore == hardcore
                            ) {
                                rich
                            } else {
                                rich.copy(iconPath = fresh.iconPath, consoleName = console, lastAwardedAtMillis = awarded, awardKind = award, earnedHardcore = hardcore)
                            }
                        }
                        ?: fresh
                }
                val listed = bulk.mapTo(HashSet()) { it.gameId }
                current.filter { it.account != account } +
                    own.values.filter { it.gameId !in listed && !(dropUnlistedBulkRows && !it.rowsKnown) } + merged
            }
            if (totalPoints != null) {
                mutablePoints.value = pointsFor(account, totalPoints)
            } else if (mutablePoints.value?.account != account) {
                mutablePoints.value = null
            }
            // The slot (what the Now Playing chip reads) is set only by a card check, so during a play it would keep the count from before it. When this read has
            // since counted MORE for the very game the slot holds, the slot follows it. Equal counts leave it as it is, so a read that found nothing new never stirs the chip.
            val held = mutableState.value
            if (held != null && held.account == account) {
                val fresh = mutableSnapshots.value.firstOrNull { it.account == account && it.gameId == held.gameId }
                if (fresh != null && fresh !== held &&
                    activityLaterCountWins(held.checkedAtEpochMillis, held.unlocked, held.total, fresh.checkedAtEpochMillis, fresh.unlocked, fresh.total)
                ) {
                    mutableState.value = fresh
                }
            }
        }
    }

    /** An account switch or a sign-out: nothing that belongs to any other account (or to none) may stay held. */
    fun dropAccountsExcept(account: String?) {
        synchronized(lock) {
            mutableSnapshots.update { current -> current.filter { it.account == account } }
            if (mutablePoints.value?.account != account) mutablePoints.value = null
            if (mutableState.value?.account != account) mutableState.value = null
            if (mutableRecent.value?.account != account) mutableRecent.value = null
            if (bulkLandedAccount != account) bulkLandedAccount = null
            if (pointsLandedAccount != account) pointsLandedAccount = null
            if (recentLandedAccount != account) recentLandedAccount = null
            if (mutableSettled.value != account) mutableSettled.value = null
        }
    }

    /** Test seam: the sink is a process singleton. */
    internal fun resetForTest() {
        synchronized(lock) {
            mutableState.value = null
            mutableSnapshots.value = emptyList()
            mutablePoints.value = null
            mutableRecent.value = null
            mutableSettled.value = null
            bulkLandedAccount = null
            pointsLandedAccount = null
            recentLandedAccount = null
        }
    }
}

internal fun companionActivityAchievementSnapshotsFor(
    snapshots: List<CompanionActivityAchievementSnapshot>,
    account: String?,
    enabled: Boolean,
): List<CompanionActivityAchievementSnapshot> = if (!enabled || account.isNullOrBlank()) {
    emptyList()
} else {
    snapshots.filter {
        it.account == account && it.total > 0 && it.unlocked in 0..it.total
    }
        .sortedWith(compareByDescending<CompanionActivityAchievementSnapshot> { it.checkedAtEpochMillis ?: 0L }
            .thenBy { it.gameTitle.orEmpty() })
}

/**
 * The card the Bottom's achievements hub shows for one checked game: the last-checked snapshot as [MimirAchievementCardState.Loaded], earned rows only, in the order the
 * snapshot holds them (the lane's own: points, then id). The snapshot carries no locked rows and no points total, so the card is told its points are unknown
 * ([MimirAchievementCardState.Loaded.pointsKnown] false) and prints no points line; each earned row keeps its own points, which the snapshot does carry. Pure, and it
 * starts no request: the snapshot is what a check already published.
 */
internal fun CompanionActivityAchievementSnapshot.toAchievementCardState(): MimirAchievementCardState.Loaded = achievementCardState

private fun CompanionActivityAchievementSnapshot.buildAchievementCardState(): MimirAchievementCardState.Loaded =
    MimirAchievementCardState.Loaded(
        gameTitle = gameTitle?.takeIf(String::isNotBlank) ?: gameId,
        unlocked = unlocked,
        total = total,
        earnedPoints = earned.sumOf { it.points },
        totalPoints = 0,
        rows = earned.map { achievement ->
            MimirAchievementRow(
                id = achievement.id,
                title = achievement.title,
                description = achievement.description,
                points = achievement.points,
                medal = if (achievement.hardcore) MimirMedalState.EARNED_HARDCORE else MimirMedalState.EARNED,
            )
        },
        pointsKnown = false,
        // A set that has achievements and none earned is not an empty set: the snapshot holds earned rows only, so "no rows" here means "none earned yet".
        emptyLine = when {
            !rowsKnown -> MimirAchievementCopy.BadgesNotLoaded
            total > 0 && earned.isEmpty() -> MimirAchievementCopy.NoneEarnedYet
            else -> MimirAchievementCopy.SetListedNothing
        },
    )

/**
 * Why the hub has no snapshot to show, in the card's own words (the same copy Main's card uses for the same causes, through the same mapper): the saved key was
 * rejected this session, no account is saved, connected services are off, or simply nothing has been checked yet. The card then reads as its honest Unknown state,
 * never as an empty Loaded.
 *
 * The causes are tried in the order of the game card's own preflight ([retroAchievementsPreflightState]): rejected, then no credentials, then opted out. A
 * never-connected install has services off AND no account; it is told to connect ("Add your RetroAchievements username..."), not to "Reconnect" something it never had.
 * The no-credentials words say "progress here", not "this game": the hub is the Activity-level page and has no game. For the same reason a connected account with
 * nothing checked yet gets the Activity card's own two lines ([MimirAchievementCopy.NothingCheckedYet]), not the game card's "no answer for this game yet".
 */
internal fun companionActivityAchievementUnknownState(
    connectedServicesOn: Boolean,
    account: String?,
    validation: com.gruning.mimir.rewrite.core.model.RaKeyState,
): MimirAchievementCardState = when {
    validation == com.gruning.mimir.rewrite.core.model.RaKeyState.REJECTED_THIS_SESSION -> mimirAchievementCardState(retroAchievementsRejectedState())
    account.isNullOrBlank() -> mimirAchievementCardState(retroAchievementsNoCredentialsState())
    !connectedServicesOn -> mimirAchievementCardState(retroAchievementsOptedOutState())
    else -> MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet)
}

internal fun companionActivityAchievementFor(
    snapshot: CompanionActivityAchievementSnapshot?,
    gameId: String,
    account: String?,
    enabled: Boolean,
): CompanionActivityAchievementSnapshot? = snapshot?.takeIf {
    enabled && !account.isNullOrBlank() && it.account == account && it.gameId == gameId &&
        it.total > 0 && it.unlocked in 0..it.total
}

// ── The Now Playing takeover ─────────────────────────────────────────────────────────────────────────────────────────────────────────
//
// One surface for a running game (it replaced the old "session detail" card): the page's scene while a game has the screen.
//
//     [< Activity]                                              1:24:07      (back pill 48 dp; the clock, Title, tabular)
//                                          18 h 40 min before this session   (the total, from the elapsed store)
//               +------ the logo stage, ink-fit (430 x 215 at most) ------+
//                              Title                                         (OverlayTitle)
//                  Genesis · opens with RetroArch                            (Support)
//        [ 12 of 40 ]   [ Return to game ]   [ Quit ]                        (48 dp actions; absent ones drop out)
//
// The art law: a wired hero is drawn bare under a palette scrim (never blurred); a wired logo is ink-fit on the stage; nothing is drawn while art
// loads or after it fails; no title is lent from another game. The 1 Hz clock is a SIBLING of every art layer, never a child, and it is the only
// thing that recomposes on the tick: the game shares the GPU with this window, so a tick must not repaint art. Touch-first (R9a): every control is a
// `mimirActivatable` target of at least 48 dp; nothing here takes focus or holds the process-wide pad gate.

const val NowPlayingTakeoverTag = "nowplaying-takeover"
const val NowPlayingBackdropTag = "nowplaying-backdrop"
const val NowPlayingBackPillTag = "nowplaying-back-pill"
const val NowPlayingClockTag = "nowplaying-clock"
const val NowPlayingPausedTag = "nowplaying-paused"
const val NowPlayingTotalTag = "nowplaying-total"
const val NowPlayingStageTag = "nowplaying-stage"
const val NowPlayingTitleTag = "nowplaying-title"
const val NowPlayingSubtitleTag = "nowplaying-subtitle"
const val NowPlayingActionsTag = "nowplaying-actions"
const val NowPlayingAchievementsTag = "nowplaying-achievements"
const val NowPlayingReturnTag = "nowplaying-return"
const val NowPlayingQuitTag = "nowplaying-quit"

private const val NowPlayingGapDp = 8
private const val NowPlayingMinStageDp = 96
private const val NowPlayingActionGapDp = 12
private val NowPlayingClockStyle = MimirTypeScale.Title.copy(fontFeatureSettings = "tnum")

/** What Quit does when it is confirmed: close the game's package through the existing verified close, settle, and report what the readback proved. */
internal class NowPlayingQuit(val perform: suspend () -> PrivilegedCloseOutcome)

/**
 * The 1 Hz clock's time source, as a state ONLY the clock text reads: a parent that reads it would recompose on every tick. The tick lands on the
 * clock's second boundary ([nowPlayingClockDelayMs]) so the shown second never lags the elapsed time.
 */
@Composable
private fun rememberNowPlayingClock(game: MimirGameSession, segments: () -> HomeParkSegments?, nowElapsedMs: () -> Long): LongState {
    val latestNow by rememberUpdatedState(nowElapsedMs)
    val latestSegments by rememberUpdatedState(segments)
    val now = remember(game.operation, game.sequence) { mutableLongStateOf(latestNow()) }
    LaunchedEffectWhileLive(game.operation, game.sequence) {
        while (true) {
            val t = latestNow()
            now.longValue = t
            delay(nowPlayingClockDelayMs(nowPlayingSessionElapsed(game.dispatchedAtElapsedMs, t, latestSegments()) ?: 0L))
        }
    }
    return now
}

@Composable
internal fun CompanionActivityGameDetail(
    game: MimirGameSession,
    payload: CompanionPayload,
    row: GameRow?,
    achievements: CompanionActivityAchievementSnapshot?,
    totalBeforeMs: Long?,
    platformLabel: String?,
    navigationEnabled: Boolean,
    onBack: () -> Unit,
    onOpenAchievements: (() -> Unit)?,
    onReturnToGame: ((GameId) -> Boolean)?,
    quit: NowPlayingQuit?,
    nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    segmentsFlow: StateFlow<HomeParkSegments?> = homeParkSegments.state,
) {
    val palette = LocalMimirPalette.current
    val control = rememberBottomUiControl(BottomSurface.ACTIVITY)
    val latestEnabled by rememberUpdatedState(navigationEnabled)
    val currentRow = row?.takeIf { it.gameId == game.gameId }
    val currentAchievements = achievements?.takeIf { it.gameId == game.gameId.value }
    // A parked game holds its clock and shows PAUSED; Resume continues it. Parked time is not played time,
    // and the total leaves out the segments the clock shows.
    val segmentsRecord by segmentsFlow.collectAsStateWhileLive()
    val segments = segmentsRecord.forSession(game.operation)
    val parked = segments != null && segments.segmentStartedAtElapsedMs == null
    val now = rememberNowPlayingClock(game, { segments }, nowElapsedMs)
    val title = nowPlayingTitle(currentRow, payload.title, game.title)
    val logo = remember(currentRow?.flatArtUri, payload.heroArtRef, payload.heroArtIsLogo) {
        (currentRow?.flatArtUri ?: payload.heroArtRef.takeIf { payload.heroArtIsLogo })?.trim()?.takeIf { it.isNotEmpty() }
    }
    val hero = remember(currentRow?.heroArtUri) { currentRow?.heroArtUri?.trim()?.takeIf { it.isNotEmpty() } }
    // A logo whose file is gone is no logo (the title takes the stage); one still being checked counts as wired and draws nothing until it loads.
    val logoWired = rememberMimirArtWired(logo)
    val logoShown = logo != null && logoWired
    val total = nowPlayingTotalText(nowPlayingTotalBeforeMs(totalBeforeMs, segments))
    val subtitle = nowPlayingSubtitle(platformLabel ?: payload.platformLabel, payload.launcherLabel)
    val parkedAtElapsedMillis = MainGameParkingState.currentFor(game)?.parkedAtElapsedMillis
    Box(Modifier.fillMaxSize().testTag(NowPlayingTakeoverTag)) {
        // Sibling 1: the art. Drawn bare under a palette scrim when a hero is wired, nothing otherwise.
        NowPlayingBackdrop(hero = hero, title = title)
        // Sibling 2: everything that reads the clock. Never a child of the art above.
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(
                start = ActivityPageInsetDp.dp,
                top = ActivityPageTopDp.dp,
                end = ActivityPageInsetDp.dp,
                bottom = ActivityDockReserveDp.dp,
            ),
        ) {
            val availableHeightPx = constraints.maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                NowPlayingBody(
                    availableHeightPx = availableHeightPx,
                    header = {
                        // The header stops short of the recent-app tile laid over the page's top end (the slot's reserve, zero with no app leased), so the clock and the total are never under it.
                        // The gap between the Back chip and the clock is the band an "Achievement earned" toast stands in (it never covers a control).
                        val toastBand = rememberAchievementToastBandBetween()
                        Row(
                            Modifier.fillMaxWidth().bottomHeaderTrailingReserve().heightIn(min = 48.dp).then(toastBand.row),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NowPlayingBackPill(enabled = navigationEnabled, onBack = onBack, modifier = toastBand.leading)
                            Spacer(Modifier.weight(1f))
                            // The clock and the total are one column, and the gap ends where the whole of it begins (the total is the wider of the two).
                            Column(Modifier.then(toastBand.trailing), horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NowPlayingPausedLabel(parked = parked)
                                    NowPlayingClock(startedAtElapsedMs = game.dispatchedAtElapsedMs, now = now, segments = segments)
                                }
                                if (total != null) {
                                    Text(
                                        text = total,
                                        modifier = Modifier.testTag(NowPlayingTotalTag),
                                        style = MimirTypeScale.SupportCompact,
                                        color = palette.textMuted,
                                        textAlign = TextAlign.End,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    stage = {
                        Box(Modifier.fillMaxSize().testTag(NowPlayingStageTag), contentAlignment = Alignment.Center) {
                            if (logoShown) {
                                MimirFlatLogoArt(
                                    title = title,
                                    logoUri = logo,
                                    modifier = Modifier.fillMaxSize(),
                                    ink = rememberMimirLogoInk(logo),
                                )
                            } else {
                                // No logo: the title IS the stage (as on the launch transition), and is not said twice.
                                Text(
                                    text = title,
                                    modifier = Modifier.testTag(NowPlayingTitleTag),
                                    style = MimirTypeScale.Title,
                                    color = palette.textPrimary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    text = if (logoShown || subtitle != null) {
                        {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (logoShown) {
                                    Text(
                                        text = title,
                                        modifier = Modifier.testTag(NowPlayingTitleTag),
                                        style = MimirTypeScale.OverlayTitle,
                                        color = palette.textPrimary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        modifier = Modifier.testTag(NowPlayingSubtitleTag),
                                        style = MimirTypeScale.Support,
                                        color = palette.textSecondary,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                    actions = if (currentAchievements != null || onReturnToGame != null || quit != null) {
                        {
                            Row(
                                // The ring pills' rings reach 17 dp past their edge, and the scrolling column clips at its own bounds, so the row
                                // stands that far in on every side (the stage, which shrinks first, pays for it).
                                Modifier.fillMaxWidth().padding(MimirFilledControlRingReach).testTag(NowPlayingActionsTag),
                                horizontalArrangement = Arrangement.spacedBy(NowPlayingActionGapDp.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (currentAchievements != null) {
                                    NowPlayingAchievementsChip(
                                        snapshot = currentAchievements,
                                        enabled = navigationEnabled,
                                        onOpen = onOpenAchievements?.let { open ->
                                            {
                                                if (latestEnabled) {
                                                    control("nowplaying.achievements", null)
                                                    open()
                                                }
                                            }
                                        },
                                        modifier = Modifier,
                                    )
                                }
                                if (onReturnToGame != null) {
                                    // R6-P1-07 re-ruling ("keep parking, warn honestly"): once the parking lease is old enough that Android may
                                    // have frozen the process, say so. The label reads the shared 1 Hz clock through a derived flag, so the
                                    // action recomposes only when the flag flips, not every second.
                                    val mayRestart by remember(parkedAtElapsedMillis) {
                                        derivedStateOf { parkedAtElapsedMillis != null && returnMayRestart(parkedAtElapsedMillis, now.longValue) }
                                    }
                                    NowPlayingPill(
                                        label = "Return",
                                        description = "Return to game",
                                        sublabel = if (mayRestart) "Return may restart the game." else null,
                                        tag = NowPlayingReturnTag,
                                        enabled = navigationEnabled,
                                        emphasis = true,
                                        modifier = Modifier.weight(1f, fill = false),
                                        onActivate = {
                                            if (latestEnabled) {
                                                control("activity.return_to_game", BottomUiReceiptFormat.opaque("game", game.gameId.value))
                                                onReturnToGame.invoke(game.gameId)
                                            }
                                        },
                                    )
                                }
                                if (quit != null) {
                                    NowPlayingQuitPill(
                                        game = game,
                                        quit = quit,
                                        enabled = navigationEnabled,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The running game's hero, bare and cropped under a palette scrim for legibility. Nothing when there is no wired hero, while it loads, or after it fails. */
@Composable
private fun NowPlayingBackdrop(hero: String?, title: String) {
    val wired = rememberMimirArtWired(hero)
    if (hero == null || !wired) return
    val palette = LocalMimirPalette.current
    Box(Modifier.fillMaxSize().testTag(NowPlayingBackdropTag)) {
        MimirBoxArt(model = hero, title = title, focused = false, contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(palette.surfaceBase.copy(alpha = 0.60f), palette.surfaceBase.copy(alpha = 0.88f))),
            ),
        )
    }
}

/** The live clock. It reads the shared tick state itself, so it is the one composable that recomposes each second. */
@Composable
private fun NowPlayingClock(startedAtElapsedMs: Long?, now: LongState, segments: HomeParkSegments?) {
    val text = nowPlayingClockText(startedAtElapsedMs, now.longValue, segments) ?: return
    Text(
        text = text,
        modifier = Modifier.testTag(NowPlayingClockTag),
        style = NowPlayingClockStyle,
        color = LocalMimirPalette.current.textPrimary,
        maxLines = 1,
    )
}

/**
 * "PAUSED" beside the clock while a HOME press has the game parked explains the held played time.
 * The Bottom's eyebrow grammar, as the Activity page's section labels
 * ([MimirTypeScale.RunicEyebrow] in the accent), fading in and out on [MimirMotion.FocusMs]; reduced motion snaps. Composed only while shown or
 * still leaving, so the clock's own bounds are untouched while the game runs, and gone once the exit fade has finished.
 */
@Composable
private fun NowPlayingPausedLabel(parked: Boolean) {
    val reduced = bottomUiReducedMotion()
    val alpha by animateFloatAsState(
        targetValue = if (parked) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "nowplaying-paused",
    )
    val up by remember { derivedStateOf { alpha > 0f } }
    if (parked || up) {
        MimirChromeText(
            text = "PAUSED",
            modifier = Modifier.graphicsLayer { this.alpha = alpha }.testTag(NowPlayingPausedTag),
            style = MimirTypeScale.RunicEyebrow,
            color = LocalMimirPalette.current.accent,
            maxLines = 1,
        )
        Spacer(Modifier.width(NowPlayingGapDp.dp))
    }
}

/**
 * A takeover control's horizontal drag is its own, as the timeline scrubber's is: a finger that goes down on it and moves sideways past touch slop takes the rest
 * of that gesture, so the workspace strip behind the page never pans under it (the press is cancelled by the move, as for any control). A tap, and a drag that
 * starts anywhere else, are untouched. It consumes in the Main pass, which a child reaches before the strip's draggable on an ancestor.
 */
internal fun Modifier.companionOwnsHorizontalDrag(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // The claim is decided ONCE, at the first crossing of touch slop, and held for the rest of the gesture: a horizontal-dominant crossing is the control's own drag; a
        // vertical-dominant one (the page's scroll winning), or one an ancestor had already consumed, stands the control down, and it never consumes again, however the
        // finger drifts sideways later. A claim that could be taken up late would cancel a vertical scroll that was already under way.
        var decided = false
        var claimed = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (!decided) {
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                val slop = viewConfiguration.touchSlop
                if (change.isConsumed) {
                    decided = true
                } else if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                    decided = true
                    claimed = kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)
                }
            }
            if (claimed) change.consume()
        }
    }
}

/** The back pill's visible plate, inside its 48 dp touch target. Its height is the filter chips' plate height by construction (see [NowPlayingBackPill]). */
const val NowPlayingBackPillPlateTag = "nowplaying-back-pill-plate"

/**
 * The back control is an ordinary ETCHED CHIP, identical to an unselected [ActivityFilterChip],
 * labelled "‹ Activity" or "‹ Session" in a game and inside a 48 dp touch target. Its plate matches
 * the achievements filter chips' height. The Now Playing header and achievements hub share it: an outer box that
 * is the whole hit area (at least 48 dp each way, the tap and the press response both on it, so the target is larger than the visual and nothing visible grew
 * to earn it) around a plate with the chip's own padding tokens (md across, sm down) and the chip's own label style, so the plate is exactly as tall as a chip.
 *
 * The chevron and the label are two texts of that one style in one centred row, a single [MimirSpacing.xs] apart, rather than one string with a space in it:
 * they share a centre line (no font padding, one line box each) and the gap is the same whatever the font's space glyph measures.
 */
@Composable
internal fun NowPlayingBackPill(enabled: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier, label: String = "Activity") {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .testTag(NowPlayingBackPillTag)
            .companionOwnsHorizontalDrag()
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            // The Bottom's one press response (B5, R4): after the tap and before the fill, so the pill draws in whole and its hit box stays whole.
            .mimirActivatable(
                label = "Back to $label",
                contentDescription = "Back to $label",
                enabled = enabled,
                interactionSource = source,
                onActivate = onBack,
            )
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .testTag(NowPlayingBackPillPlateTag)
                // An UNSELECTED filter chip, exactly (no filled tint blob): the etched metal plate, the chip's padding, its label style and its text colour.
                .mimirEtchedChipSurface(palette, warmed = false)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackPillText("‹", palette.materials.hero.onCover)
            BackPillText(label, palette.materials.hero.onCover)
        }
    }
}

/**
 * One word of the back pill, in the filter chip's own text style and weight, untouched (so the plate is as tall as a chip's). Compose 1.7 (BOM 2024.09) draws
 * text with no font padding by default, so the line box is the font's own ascent to descent and the row's centring is the optical centring.
 */
@Composable
private fun BackPillText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MimirTypeScale.SupportCompact,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

/**
 * A 48 dp action on the Now Playing page (update). The page's primary, [emphasis] (Return, and Quit once it asks to be confirmed), is the etched chip struck as a PRIMARY:
 * the same dark plate as the unselected chip beside it, its rim in the accent and its word in the accent, no fill. Every other state is [NowPlayingEtchedAction], the unselected
 * chip. Neither wears a ring: the Bottom is touch, there is no pad cursor to move one, and a ring that never moves reads as a fill and hides which control is armed
 * (one illuminated control at a time). A chip never wraps: [label] is short enough for one line at every font scale, and [description] is what a screen reader says
 * ("Return", not "Return to game", is what the chip shows); the optional [sublabel] is a second, smaller line.
 */
@Composable
private fun NowPlayingPill(
    label: String,
    tag: String,
    enabled: Boolean,
    emphasis: Boolean,
    modifier: Modifier,
    onActivate: () -> Unit,
    sublabel: String? = null,
    description: String = label,
) {
    if (!emphasis) {
        NowPlayingEtchedAction(label = label, description = description, tag = tag, enabled = enabled, modifier = modifier, onActivate = onActivate)
        return
    }
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .testTag(tag)
            .companionOwnsHorizontalDrag()
            // The tap, then the press response, then the surface: the chip draws in whole while its hit box stays whole (B5, R4).
            .mimirActivatable(label = description, contentDescription = description, enabled = enabled, interactionSource = source, onActivate = onActivate)
            .mimirPressResponse(source)
            .mimirEtchedChipSurface(palette, primary = true)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(primary = true),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (sublabel != null) {
            Text(
                text = sublabel,
                style = MimirTypeScale.SupportCompact,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** "12 of 40" with its ring: only drawn when the snapshot has data, and a tap opens the achievements hub when the host offers it. */
@Composable
private fun NowPlayingAchievementsChip(
    snapshot: CompanionActivityAchievementSnapshot,
    enabled: Boolean,
    onOpen: (() -> Unit)?,
    modifier: Modifier,
) {
    val palette = LocalMimirPalette.current
    val spoken = mimirProgressSpoken(snapshot.unlocked, snapshot.total) ?: return
    val source = remember { MutableInteractionSource() }
    val base = modifier
        .testTag(NowPlayingAchievementsTag)
        .companionOwnsHorizontalDrag()
        .heightIn(min = 48.dp)
    // The tap, then the press response, then the fill: the chip draws in whole and its hit box stays whole (B5, R4).
    val tappable = if (onOpen != null) {
        base.mimirActivatable(
            label = "Achievements",
            contentDescription = "Achievements, $spoken",
            enabled = enabled,
            interactionSource = source,
            onActivate = onOpen,
        )
    } else {
        base.semantics { contentDescription = "Achievements, $spoken" }
    }
    // An unselected etched chip (update): the metal surface, the chip's padding and label, no fill of its own. It stays 48 dp tall, the height of the Return and Quit
    // actions it sits beside, so the row reads as one line of equal plates (its progress ring is 28 dp, the plate's two paddings 16).
    val interactive = tappable
        .mimirPressResponse(source)
        .mimirEtchedChipSurface(palette, warmed = false)
    Row(
        modifier = interactive.padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        MimirProgressRing(done = snapshot.unlocked, total = snapshot.total, size = 28.dp, stroke = 3.dp, palette = palette)
        Text(
            text = spoken,
            style = MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(),
            maxLines = 1,
        )
    }
}

/**
 * An action on the Now Playing page that is not its primary: an unselected etched chip inside a 48 dp hit box (never a filled blob). It keeps the 48 dp
 * row height of the Return chip beside it, so the pair stays even, with the chip's metal surface and label (SupportCompact, SemiBold) on ONE line at every font scale (its
 * words are short enough, so it never wraps). It takes no ring: the Bottom is touch.
 */
@Composable
private fun NowPlayingEtchedAction(
    label: String,
    description: String,
    tag: String,
    enabled: Boolean,
    modifier: Modifier,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .testTag(tag)
            .companionOwnsHorizontalDrag()
            .heightIn(min = MimirSpacing.minTarget)
            // At least 72 dp across: at the 48 dp row height a short word ("Quit") is then a capsule, not a circle, and still well past the 48 dp a finger needs.
            .widthIn(min = 72.dp)
            // The tap, then the press response, then the surface: the chip draws in whole while its hit box stays whole (B5, R4).
            .mimirActivatable(label = description, contentDescription = description, enabled = enabled, interactionSource = source, onActivate = onActivate)
            .mimirPressResponse(source)
            .mimirEtchedChipSurface(palette)
            // 18 dp across, the Return chip's own.
            .padding(horizontal = 18.dp, vertical = MimirSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Quit: two taps. "Quit", then "Tap again" for [NowPlayingQuitConfirmMs], then the close. The words after are the readback's: "Closed" only
 * for a verified stop, "Couldn't close it" for anything else, which then offers Quit again.
 */
@Composable
private fun NowPlayingQuitPill(game: MimirGameSession, quit: NowPlayingQuit, enabled: Boolean, modifier: Modifier) {
    val control = rememberBottomUiControl(BottomSurface.ACTIVITY)
    val scope = rememberCoroutineScope()
    var phase by remember(game.operation, game.sequence) { mutableStateOf(NowPlayingQuitPhase.IDLE) }
    LaunchedEffect(phase) {
        if (nowPlayingQuitOnExpiry(phase) != phase) {
            delay(NowPlayingQuitConfirmMs)
            phase = nowPlayingQuitOnExpiry(phase)
        }
    }
    val latestEnabled by rememberUpdatedState(enabled)
    NowPlayingPill(
        label = nowPlayingQuitCopy(phase),
        description = nowPlayingQuitSpoken(phase),
        tag = NowPlayingQuitTag,
        enabled = enabled && phase != NowPlayingQuitPhase.CLOSING && phase != NowPlayingQuitPhase.CLOSED,
        emphasis = phase == NowPlayingQuitPhase.CONFIRM,
        modifier = modifier,
        onActivate = {
            if (latestEnabled) {
                val step = nowPlayingQuitOnTap(phase)
                if (step.phase != phase) {
                    control(if (step.performClose) "nowplaying.quit_confirm" else "nowplaying.quit", null)
                }
                phase = step.phase
                if (step.performClose) {
                    scope.launch {
                        phase = nowPlayingQuitPhaseFor(quit.perform())
                    }
                }
            }
        },
    )
}

/**
 * The takeover's vertical stack. The logo stage takes what the header, the words and the actions leave of [availableHeightPx], up to the ruled
 * [MimirLogoStage] (430 x 215) and never below [NowPlayingMinStageDp], so a larger font shrinks the stage instead of cutting a word or pushing a
 * target off the screen (and below the minimum the page scrolls). The stage keeps its 2:1 shape.
 */
@Composable
private fun NowPlayingBody(
    availableHeightPx: Int,
    header: @Composable () -> Unit,
    stage: @Composable () -> Unit,
    text: (@Composable () -> Unit)?,
    actions: (@Composable () -> Unit)?,
) {
    Layout(
        content = {
            Box(Modifier.layoutId("header")) { header() }
            Box(Modifier.layoutId("stage")) { stage() }
            if (text != null) Box(Modifier.layoutId("text")) { text() }
            if (actions != null) Box(Modifier.layoutId("actions")) { actions() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = NowPlayingGapDp.dp.roundToPx()
        val full = Constraints(minWidth = width, maxWidth = width)
        val headerP = measurables.first { it.layoutId == "header" }.measure(full)
        val textP = measurables.firstOrNull { it.layoutId == "text" }?.measure(Constraints(maxWidth = width))
        val actionsP = measurables.firstOrNull { it.layoutId == "actions" }?.measure(full)
        val parts = 2 + (if (textP != null) 1 else 0) + (if (actionsP != null) 1 else 0)
        val gaps = gap * (parts - 1)
        val used = headerP.height + (textP?.height ?: 0) + (actionsP?.height ?: 0) + gaps
        val stageH = (availableHeightPx - used).coerceIn(NowPlayingMinStageDp.dp.roundToPx(), MimirLogoStage.height.roundToPx())
        val stageW = min(min(MimirLogoStage.width.roundToPx(), (stageH * MimirLogoStage.AspectRatio).toInt()), width)
        val stageP = measurables.first { it.layoutId == "stage" }.measure(Constraints.fixed(stageW, stageH))
        val height = used + stageH
        layout(width, height) {
            var y = 0
            headerP.place(0, y)
            y += headerP.height + gap
            stageP.place((width - stageP.width) / 2, y)
            y += stageH
            if (textP != null) {
                y += gap
                textP.place((width - textP.width) / 2, y)
                y += textP.height
            }
            if (actionsP != null) {
                y += gap
                actionsP.place(0, y)
            }
        }
    }
}
