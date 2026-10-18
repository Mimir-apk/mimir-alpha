package com.gruning.mimir.rewrite

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.library.ScanOutcome
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceReconcileResult
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryShelfScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

internal data class NewGameEnrichPending(
    val scanRevision: Long,
    val gameIds: Set<String>,
)

/**
 * Whether a completed scan has earned the artwork question.
 *
 * `artOptedIn` used to be a fourth condition here, and that made the Enrich prompt appear at unrelated times. Requiring a working ScreenScraper account before ASKING meant the question could
 * never appear when the hash finished on a first run -- it appeared later, at the unrelated moment the
 * account was connected from Settings. The offer is now the thing that asks, so having an account is the
 * ANSWER to this question and cannot also be its precondition.
 */
internal fun shouldOfferEnrichForNewGames(
    addedCount: Int,
    hashSettled: Boolean,
    alreadyOfferedForRevision: Boolean,
): Boolean = addedCount > 0 && hashSettled && !alreadyOfferedForRevision

/**
 * Alpha cert 2026-10-08 (E4): the games a completed scan added that the player has NOT already answered the artwork question
 * for. A game id is content-derived, so a folder removed and added back, or restored after a lost grant, returns the same ids;
 * those games were present when "Not now" was pressed and are not new. Only a game never answered for earns a question.
 */
internal fun gamesNotYetAnswered(added: Set<String>, answered: Set<String>): Set<String> = added - answered

/** How many answered game ids are kept: well past any library, so the cap only guards a runaway file. */
internal const val AnsweredGamesCap: Int = 20_000

/** [answered] plus [more], newest first, trimmed to [cap] so a runaway set drops the oldest answers rather than the latest. */
internal fun rememberAnsweredGames(
    answered: Set<String>,
    more: Set<String>,
    cap: Int = AnsweredGamesCap,
): Set<String> = (more.asSequence() + answered.asSequence()).distinct().take(cap).toSet()

/**
 * Where Home or the Library RESTS: the shelf with nothing in front of it, its quick rail, the
 * app shell's root, and the card itself. Anything else (a gamelist, a game's page, Search, a keyboard, Settings) is a surface the
 * player is working in.
 */
internal val NewGameEnrichRestingScopes: Set<FocusScope> = setOf(
    FocusScope.Root,
    HomeShelfScope,
    HomeShelfQuickRailScope,
    LibraryShelfScope,
    LibraryQuickRailScope,
    // The card's own scope. Left out, the showing card would cover itself, queue itself, and come back: a flicker loop.
    NewGameEnrichFocusScope,
)

/**
 * F7 (alpha cert 2026-10-08, Thor re-check on combined16): whether the player is somewhere other than a resting Home or Library
 * root, so the artwork question must wait.
 *
 * It was a denylist (an in-app keyboard owns focus, or an app-shell overlay is up), and Library Search is neither: it is a child
 * of the retained gamelist with its own focus scope, and a touch-typed query never enters the keyboard scope. The identification
 * stall path raised "Fetch it now?" over a search being typed, with Fetch pre-focused. This is the allowlist the question
 * always needed: it may only land when the destination is Home or the Library, no overlay but the card is up, focus rests in
 * [NewGameEnrichRestingScopes], and no text entry is open. [textEntryOpen] is the Library's search keyboard or Search surface,
 * read from the view model, because a scope can lag a frame behind the keyboard that opened it.
 *
 * Earlier (F6, product review 2026-09-28; thor-10, rg-rotate-6): the card is ARTWORK_FETCH, which `resolveMimirOverlayStack` ranks
 * above every surface a player types into, so an offer that arrived while a Tome was being named took the next presses and the typed
 * name was lost. The card is excluded from the overlays on purpose: counting itself would defer the offer the frame it appeared.
 */
internal fun newGameEnrichOfferCovered(
    overlays: List<MimirOverlayKind>,
    activeScope: FocusScope,
    destination: MimirDestination = MimirDestination.HOME,
    textEntryOpen: Boolean = false,
): Boolean = textEntryOpen ||
    (destination != MimirDestination.HOME && destination != MimirDestination.LIBRARY) ||
    activeScope !in NewGameEnrichRestingScopes ||
    overlays.any { it != MimirOverlayKind.ARTWORK_FETCH }

/** What [watchNewGameEnrichCover] watches: whether the question is covered, and which key has focus (any move is activity). */
internal data class NewGameEnrichCoverSample(val covered: Boolean, val focused: FocusKey?)

/** How long a resting Home or Library must stay quiet before a held artwork question is shown. */
internal const val NewGameEnrichIdleMillis: Long = 1_500L

/** How long after the card lands a press of A or B is ignored: a reflex press on returning to Main must not answer a question it never saw. */
internal const val NewGameEnrichInputGraceMillis: Long = 300L

/** Whether a press at [nowElapsedMs] may answer a card that landed at [shownAtElapsedMs]. */
internal fun newGameEnrichInputArmed(shownAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
    nowElapsedMs - shownAtElapsedMs >= NewGameEnrichInputGraceMillis

/**
 * Feeds [NewGameEnrichPromptState.holdWhile] from [samples], with two independent facts. COVERED is applied at once, either way. QUIET is the
 * second: every sample is activity, so it clears quiet (which never hides a card already showing), and only [idleMillis] of no sample, on a surface that
 * is not covered, sets it again. An offer is admitted only when it is uncovered AND quiet, so one that arrives while the player is scrolling the shelf
 * waits too, not just one held behind Search (P2-2, code review 2026-10-08). [collectLatest] restarts the wait on every sample.
 */
internal suspend fun watchNewGameEnrichCover(
    samples: Flow<NewGameEnrichCoverSample>,
    idleMillis: Long = NewGameEnrichIdleMillis,
    apply: (covered: Boolean, quiet: Boolean) -> Unit = NewGameEnrichPromptState::holdWhile,
) {
    samples.collectLatest { sample ->
        apply(sample.covered, false)
        if (sample.covered) return@collectLatest
        delay(idleMillis)
        apply(false, true)
    }
}

/** [watchNewGameEnrichCover] over live snapshot reads: [read] is evaluated inside a snapshot and re-run on any state it touched. */
internal suspend fun watchNewGameEnrichCover(read: () -> NewGameEnrichCoverSample) {
    watchNewGameEnrichCover(snapshotFlow(read))
}

/**
 * The queue behind [newGameEnrichOfferCovered]. It holds the offer in the same shape as
 * [NewGameEnrichPromptState.deferForConnect]: nothing is stamped and `pending` is untouched, so a held
 * question is delayed -- never answered, never dropped. The hold is open only when the surface is not [covered]
 * AND [quiet]; the two change independently ([cover], [settle]).
 */
internal class NewGameEnrichOfferHold(startCovered: Boolean = false, startQuiet: Boolean = true) {
    var covered: Boolean = startCovered
        private set
    var quiet: Boolean = startQuiet
        private set
    private var queued: NewGameEnrichPending? = null

    private val open: Boolean get() = !covered && quiet

    /** What to show for a ready [offer]: the offer itself, or null while the hold is closed, which queues it. */
    fun admit(offer: NewGameEnrichPending): NewGameEnrichPending? {
        if (open) return offer
        queued = offer
        return null
    }

    /**
     * Applies a change of cover and returns what the card should show next. A cover arriving over a showing card
     * queues that card; the cover leaving shows the queued offer if the hold is then open and [stillPending] still accepts it.
     */
    fun cover(
        value: Boolean,
        showing: NewGameEnrichPending?,
        stillPending: (NewGameEnrichPending) -> Boolean,
    ): NewGameEnrichPending? {
        if (value == covered) return showing
        covered = value
        if (value) {
            if (showing != null) queued = showing
            return null
        }
        return release(showing, stillPending)
    }

    /**
     * Applies a change of quiet and returns what the card should show next. Activity (`false`) closes the hold to new offers and never
     * hides a card that is already showing; quiet (`true`) releases a queued offer if the surface is not covered.
     */
    fun settle(
        value: Boolean,
        showing: NewGameEnrichPending?,
        stillPending: (NewGameEnrichPending) -> Boolean,
    ): NewGameEnrichPending? {
        if (value == quiet) return showing
        quiet = value
        return if (value) release(showing, stillPending) else showing
    }

    private fun release(showing: NewGameEnrichPending?, stillPending: (NewGameEnrichPending) -> Boolean): NewGameEnrichPending? {
        if (!open) return showing
        val next = queued
        queued = null
        return next?.takeIf(stillPending) ?: showing
    }

    fun forget() {
        queued = null
    }
}

/**
 * First-run artwork consent: leaving onboarding's accounts step with no ScreenScraper account is an
 * answer to the artwork question for the first-run scan, so Main does not ask it again straight after.
 */
internal fun onboardingAccountsSkipped(step: OnboardingStep, artworkAccount: String?): Boolean =
    step == OnboardingStep.METADATA && artworkAccount.isNullOrBlank()

/** The durable handoff from a completed scan to the one-time artwork question on Main. */
internal object NewGameEnrichPromptState {
    private const val PreferencesName = "mimir-new-game-enrich"

    /**
     * Every game the player has been asked about and answered, newest first, capped: see [rememberAnsweredGames]. Its own
     * file, so the first composition's [restore] (which runs on Main) never reads a set that can hold thousands of ids; it
     * is read only when a scan records or an answer is given.
     */
    internal const val AnsweredPreferencesName = "mimir-new-game-enrich-answered"
    internal const val AnsweredGameIdsKey = "answered-game-ids"
    private const val NextScanRevisionKey = "next-scan-revision"
    private const val OfferedRevisionKey = "offered-scan-revision"
    private const val PendingRevisionKey = "pending-scan-revision"
    private const val PendingGameIdsKey = "pending-game-ids"
    private const val OnboardingSkipArmedKey = "onboarding-skip-armed"

    private val lock = Any()
    private var restored = false
    // Closed until [holdWhile] has seen a resting, quiet surface: an offer ready before the first look (a cold start with
    // identification long done) must not beat the watcher onto the screen.
    private val hold = NewGameEnrichOfferHold(startCovered = true, startQuiet = false)
    private var shownAtElapsedMs = 0L
    private val _pending = MutableStateFlow<NewGameEnrichPending?>(null)
    private val _current = MutableStateFlow<NewGameEnrichPending?>(null)

    val pending: StateFlow<NewGameEnrichPending?> = _pending.asStateFlow()
    val current: StateFlow<NewGameEnrichPending?> = _current.asStateFlow()

    /** Hydrates [pending] from disk once per process and returns [pending], so a caller collects the flow it restored. */
    fun restore(context: Context): StateFlow<NewGameEnrichPending?> {
        synchronized(lock) {
            if (restored) return pending
            restored = true
            val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            val revision = preferences.getLong(PendingRevisionKey, Long.MIN_VALUE)
            val ids = preferences.getStringSet(PendingGameIdsKey, emptySet()).orEmpty().toSet()
            if (revision != Long.MIN_VALUE && ids.isNotEmpty() &&
                preferences.getLong(OfferedRevisionKey, Long.MIN_VALUE) != revision
            ) {
                _pending.value = NewGameEnrichPending(revision, ids)
            } else {
                preferences.edit()
                    .remove(PendingRevisionKey)
                    .remove(PendingGameIdsKey)
                    .apply()
            }
        }
        return pending
    }

    fun recordScan(context: Context, result: ScanResult) {
        if (result.outcome != ScanOutcome.Healthy) return
        recordAddedGameIds(context, result.addedGameIds)
    }

    fun recordScan(context: Context, result: SourceReconcileResult) {
        recordAddedGameIds(context, result.addedGameIds)
    }

    private fun recordAddedGameIds(context: Context, addedGameIds: Set<String>) {
        if (addedGameIds.isEmpty()) return
        synchronized(lock) {
            val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            val answeredPreferences =
                context.applicationContext.getSharedPreferences(AnsweredPreferencesName, Context.MODE_PRIVATE)
            val answered = answeredPreferences.getStringSet(AnsweredGameIdsKey, emptySet()).orEmpty()
            val revision = preferences.getLong(NextScanRevisionKey, 0L) + 1L
            // OR-5: the first-run scan finished after the player skipped accounts; that skip answered it.
            if (preferences.getBoolean(OnboardingSkipArmedKey, false)) {
                answeredPreferences.edit()
                    .putStringSet(AnsweredGameIdsKey, rememberAnsweredGames(answered, addedGameIds))
                    .apply()
                preferences.edit()
                    .putLong(NextScanRevisionKey, revision)
                    .putLong(OfferedRevisionKey, revision)
                    .remove(OnboardingSkipArmedKey)
                    .remove(PendingRevisionKey)
                    .remove(PendingGameIdsKey)
                    .apply()
                _pending.value = null
                _current.value = null
                hold.forget()
                return
            }
            // Cert E4: only games the player has not been asked about are news. Removing a folder and adding it back, or a
            // rescan after the grant returns, brings the SAME games back; they were present when "Not now" was pressed.
            val unanswered = gamesNotYetAnswered(addedGameIds, answered)
            if (unanswered.isEmpty()) return
            // A question still waiting is replaced by this one, and its games ride along: they were never asked about, so
            // they are not answered, and the card must still offer them (195 held back plus 3 new is 198, not 3).
            val pending = NewGameEnrichPending(revision, unanswered + _pending.value?.gameIds.orEmpty())
            preferences.edit()
                .putLong(NextScanRevisionKey, revision)
                .putLong(PendingRevisionKey, revision)
                .putStringSet(PendingGameIdsKey, pending.gameIds)
                .apply()
            _pending.value = pending
            _current.value = null
            hold.forget()
        }
    }

    /**
     * Raises the question once the hash has settled.
     *
     * Deliberately does NOT stamp [OfferedRevisionKey]. Showing a question is not the same as answering it:
     * the stamp belongs to [dismiss], which runs when the player actually answers -- "Not now", or by
     * starting the fetch. Stamping here is what would strand [deferForConnect], because a player who goes
     * off to sign in would come back to a revision already marked as asked and never be asked again.
     *
     * Being killed mid-question therefore re-asks on the next start, which is correct: the question was
     * never answered.
     */
    fun offerAfterHash(context: Context, pending: NewGameEnrichPending) {
        synchronized(lock) {
            if (_pending.value != pending) return
            val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            val alreadyOffered = preferences.getLong(OfferedRevisionKey, Long.MIN_VALUE) == pending.scanRevision
            if (!shouldOfferEnrichForNewGames(pending.gameIds.size, true, alreadyOffered)) return
            // F6: under a keyboard or another modal the offer is queued, not shown.
            show(hold.admit(pending))
        }
    }

    /**
     * Steps the card aside so the ScreenScraper sign-in can have the screen, WITHOUT answering it.
     *
     * Written when the overlay stack put ARTWORK_FETCH above ARTWORK_CONNECT (`resolveMimirOverlayStack` added it
     * first, and `top` is the first entry), so leaving the card up would have hidden the very keyboard the player
     * just asked for. The stack now ranks ARTWORK_CONNECT above the Quick Launch panel and ARTWORK_FETCH below it
     * (walk-32k), so the sign-in is on top whatever the card does; stepping the card aside is kept anyway, because
     * it is what lets the question wait for the sign-in and return by itself.
     *
     * [pending] survives, and nothing is stamped, so the question returns by itself when the sign-in closes
     * -- answered by then, one hopes, in which case the card comes back able to actually fetch.
     */
    fun deferForConnect() {
        synchronized(lock) { _current.value = null }
    }

    /**
     * F6/P2-2: applies what [watchNewGameEnrichCover] saw. The card steps aside while [covered] and comes back when the cover clears AND the surface
     * is [quiet]; activity (`quiet = false`) closes the hold to new offers without hiding a card that is already showing. A repeat of the current
     * values changes nothing.
     */
    fun holdWhile(covered: Boolean, quiet: Boolean) {
        synchronized(lock) {
            val stillPending: (NewGameEnrichPending) -> Boolean = { it == _pending.value }
            val afterCover = hold.cover(covered, _current.value, stillPending)
            show(hold.settle(quiet, afterCover, stillPending))
        }
    }

    /** Whether a press of A or B may answer the card now: false for [NewGameEnrichInputGraceMillis] after it landed. */
    fun cardInputArmed(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
        newGameEnrichInputArmed(shownAtElapsedMs, nowElapsedMs)

    /** Every write that can put the card on screen, so the moment it lands is stamped once, for [cardInputArmed]. Called under [lock]. */
    private fun show(next: NewGameEnrichPending?) {
        if (_current.value == null && next != null) shownAtElapsedMs = SystemClock.elapsedRealtime()
        _current.value = next
    }

    /**
     * OR-5: the player left onboarding's accounts step with no account. A scan already waiting is answered now; one
     * still running is answered the moment it records (the armed flag), once. Nothing later is suppressed.
     */
    fun stampOnboardingSkip(context: Context) {
        synchronized(lock) {
            if (_current.value != null || _pending.value != null) {
                dismissLocked(context)
            } else {
                context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE).edit()
                    .putBoolean(OnboardingSkipArmedKey, true)
                    .apply()
            }
        }
    }

    fun dismiss(context: Context) {
        synchronized(lock) { dismissLocked(context) }
    }

    private fun dismissLocked(context: Context) {
        val pending = _current.value ?: _pending.value ?: return
        val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        // Cert E4: answering "Not now" (or starting the fetch) settles these games for good. They are kept on disk, so a
        // restart still knows, and a later scan that brings only these games back does not ask again.
        val answeredPreferences =
            context.applicationContext.getSharedPreferences(AnsweredPreferencesName, Context.MODE_PRIVATE)
        val answered = answeredPreferences.getStringSet(AnsweredGameIdsKey, emptySet()).orEmpty()
        answeredPreferences.edit()
            .putStringSet(AnsweredGameIdsKey, rememberAnsweredGames(answered, pending.gameIds))
            .apply()
        preferences.edit()
            .putLong(OfferedRevisionKey, pending.scanRevision)
            .remove(PendingRevisionKey)
            .remove(PendingGameIdsKey)
            .apply()
        _current.value = null
        _pending.value = null
        hold.forget()
    }
}
