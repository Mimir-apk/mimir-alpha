package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What one Activity card knows about its game's achievements beyond the bulk counts: the badges, their texts and dates, and RA's title art. */
internal sealed interface ActivityCardDetail {
    /** Asked and not yet answered. */
    data object Loading : ActivityCardDetail

    /** [progress] is one `API_GetGameInfoAndUserProgress` answer, read at [atMillis]. */
    data class Loaded(val progress: RetroAchievementsProgress, val atMillis: Long) : ActivityCardDetail

    /**
     * Asked and answered with nothing usable (offline, RetroAchievements down or asking for a pause, a game it has no set for). The card says so and a tap on it asks again
     * ([ActivityCardDetailLoader.retry]); the window never retries a failure on its own.
     */
    data object Unavailable : ActivityCardDetail

    /** Asked, and RetroAchievements is not accepting the saved key. The card says that (the key's own words) and offers no retry: asking again with the same key cannot change it. */
    data object Rejected : ActivityCardDetail

    /**
     * Asked, and the read has been out longer than its deadline with no answer. Nothing is cut short (a slow success still lands and replaces this), but the card says it is
     * still loading and a tap asks again ([ActivityCardDetailLoader.retry]), so a read that never returns is never a "Loading badges..." with nothing to do.
     */
    data object Stalled : ActivityCardDetail

    /** RetroAchievements asked every client to wait (a Retry-After is in force): nothing was sent and nothing was stamped. The card says so and a tap tries again once the wait has passed. */
    data object Throttled : ActivityCardDetail
}

/**
 * How long a card the hub shows waits before it asks for its game's detail: a flick through many cards cancels the cards it passes before they ask, so only the card the
 * player settles on (and the few they rest on) reads anything. The pacer limits the rate of reads, not their number; this limits the number.
 */
internal const val ACTIVITY_CARD_ASK_DEBOUNCE_MS: Long = 300L

/** A game's detail is asked for at most once in this long (a failure counts: it is not retried on its own sooner, but a tap on the failed card asks again).. */
internal const val ACTIVITY_CARD_DETAIL_REFRESH_MS: Long = 5L * 60L * 1000L

/**
 * The most games the shared detail cache holds at once (Main's card and the Bottom's hub both fill it): the least recently used goes first. A whole badge set is tens of
 * kilobytes; visiting many games must not keep every one of them. A game a shown card holds ([ActivityCardDetailLoader.hold]) is never the one that goes.
 */
internal const val ACTIVITY_CARD_DETAIL_MAX_GAMES: Int = 8

/**
 * The most throttle timestamps kept (one per game asked about). They outlive the payload cache above: the rule is at most one probe per game per five minutes whatever the
 * cache holds. A timestamp is dropped only once its window has passed, except for a game's FIRST ask in the process: that is never turned away for want of room (the throttle
 * stops repeat polling, not a first view), and the oldest stamp makes way for it. A REPEAT ask with the throttle full of unexpired stamps is deferred until one expires.
 */
internal const val ACTIVITY_CARD_THROTTLE_MAX_GAMES: Int = 64

/**
 * How long a read may be under way before its claim counts as released (the client's own limits are about 10 s to connect and 20 s to read, plus slack). A read that hangs past this
 * no longer holds its game: a later ask may claim it (once the throttle's window allows), and the hung read's answer, if it ever lands, is dropped if a newer claim exists.
 */
internal const val ACTIVITY_CARD_READ_DEADLINE_MS: Long = 45_000L

/**
 * The per-game detail behind an Activity card (its badges), asked ONLY for a card the hub is actually showing, never at cold start and never for the whole
 * list. One [request] is at most one `API_GetGameInfoAndUserProgress` call for that game in [refreshMillis]; a request for a game already loaded or being
 * loaded, or asked about inside the window, asks nothing. Epoch-fenced like every other read: an answer that began under one account and finished after a
 * switch publishes nothing, and an account change forgets every detail ([reset]).
 *
 * The call runs on [scope] (the lane's lifetime), not on the composition that asked, so a card scrolled away from mid-read does not cancel the read and then
 * leave its game unasked for the window.
 *
 * With a [ledger], every answer that lands is saved under the signed-in account (an upsert: the same answer twice writes nothing), and a card for a game the
 * memory cache does not hold is shown the SAVED answer at once (its badges, flags and dates) while the fresh read is under way. A refresh that fails keeps it.
 *
 * Two bounded things, kept apart. The PAYLOAD cache holds at most [ACTIVITY_CARD_DETAIL_MAX_GAMES] answers, least recently used out first, and an answer older than
 * [refreshMillis] is only STALE: it is still served (its card keeps its badges) until the refresh lands, and it goes only under that LRU pressure, never while a shown card holds
 * its game ([hold]). The THROTTLE ([ACTIVITY_CARD_THROTTLE_MAX_GAMES] timestamps) is its own map and is not touched by the cache's evictions: a game whose answer was
 * evicted is not probed again inside its window, so cycling through many games never re-asks any of them within five minutes.
 */
internal class ActivityCardDetailLoader(
    private val scope: CoroutineScope,
    private val book: RetroAchievementsLaneBook,
    private val account: () -> String?,
    private val fetch: suspend (raGameId: Int) -> RaProgressOutcome,
    private val sink: MutableStateFlow<Map<Int, ActivityCardDetail>>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshMillis: Long = ACTIVITY_CARD_DETAIL_REFRESH_MS,
    private val workContext: CoroutineContext = Dispatchers.IO,
    /** Where the epoch is re-checked and the answer written, one step: the serialized context the account-change handler also runs on. */
    private val publishContext: CoroutineContext = Dispatchers.Main.immediate,
    /** The durable ledger, or null for none: answers are saved to it and a game not held in memory starts from what it saved. */
    private val ledger: RetroAchievementsLedger? = null,
    /** True while a Retry-After the host handed this process is in force: nothing is sent, so nothing is claimed, stamped or shown as loading. */
    private val held: () -> Boolean = { false },
    /** Waits [millis] for a read's deadline to come; a test replaces it so the deadline can be reached without waiting. */
    private val stallDelay: suspend (millis: Long) -> Unit = { delay(it) },
) {
    /** The throttle: when each game was last asked about, oldest first (stamps never go backwards, so insertion order is age order). */
    private val askedAt = LinkedHashMap<Int, Long>()

    /** When each cached answer was stored, least recently used first: the cache's LRU order and the age that makes an answer stale. */
    private val stored = LinkedHashMap<Int, Long>()

    /** The games a shown card holds, with how many cards hold each. */
    private val holds = HashMap<Int, Int>()

    /** One read's claim on its game: who holds it (a token), under which account's life (the generation [reset] advances), and until when. */
    private class Claim(val token: Long, val generation: Long, val deadlineAt: Long)

    private enum class Standing { CURRENT, SUPERSEDED, OLD_ACCOUNT }

    /** The reads under way, by game: a second ask for one does not stand in for its answer, until its deadline. */
    private val inFlight = HashMap<Int, Claim>()

    /** Advances at every [reset]: a claim made under an earlier value belongs to the account that was, and never blocks the account that is. */
    private var generation = 0L
    private var nextToken = 0L

    /** An account change: nothing the old account's card knew is the new one's, and no read the old account had under way blocks the new one's. */
    @Synchronized
    fun reset() {
        generation += 1L
        inFlight.clear()
        askedAt.clear()
        everAsked.clear()
        stored.clear()
        sink.value = emptyMap()
    }

    /** Stores [detail] for [raGameId] as the most recently used answer, then evicts past the bound. */
    @Synchronized
    private fun put(raGameId: Int, detail: ActivityCardDetail) {
        stored.remove(raGameId)
        stored[raGameId] = clock()
        val gone = evictBeyondBound(keep = raGameId)
        sink.value = (sink.value + (raGameId to detail)) - gone
    }

    /** Takes the least recently used answers out of the order until it fits, skipping [keep] and any game a shown card holds (those may exceed the bound); returns who went. */
    @Synchronized
    private fun evictBeyondBound(keep: Int?): List<Int> {
        if (stored.size <= ACTIVITY_CARD_DETAIL_MAX_GAMES) return emptyList()
        val gone = ArrayList<Int>()
        val eldest = stored.entries.iterator()
        while (stored.size > ACTIVITY_CARD_DETAIL_MAX_GAMES && eldest.hasNext()) {
            val id = eldest.next().key
            if (id == keep || id in holds) continue
            eldest.remove()
            gone += id
        }
        return gone
    }

    /**
     * A shown card holds its game: the answer it shows is not evicted, however many other games are asked about, until the returned release runs (once; a second call
     * changes nothing). Asks nothing.
     */
    @Synchronized
    fun hold(raGameId: Int): () -> Unit {
        if (raGameId <= 0) return {}
        holds[raGameId] = (holds[raGameId] ?: 0) + 1
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) release(raGameId) }
    }

    @Synchronized
    private fun release(raGameId: Int) {
        val left = (holds[raGameId] ?: 0) - 1
        if (left > 0) holds[raGameId] = left else holds.remove(raGameId)
        val gone = evictBeyondBound(keep = null)
        if (gone.isNotEmpty()) sink.value = sink.value - gone
    }

    /** True when the cached answer for [raGameId] is older than the refresh window: it is still served, and the next ask for the game after the window refreshes it. */
    @Synchronized
    fun isStale(raGameId: Int): Boolean {
        val at = stored[raGameId] ?: return false
        return clock() - at >= refreshMillis
    }

    /** What the cache holds for [raGameId] (stale or not), or null; a served answer becomes the most recently used one (its age is unchanged). */
    @Synchronized
    fun read(raGameId: Int): ActivityCardDetail? {
        val held = sink.value[raGameId] ?: return null
        stored[raGameId]?.let { at ->
            stored.remove(raGameId)
            stored[raGameId] = at
        }
        return held
    }

    /**
     * Whether a probe for [raGameId] may go out now, and if so its claim, stamped and counted as in flight. Null when the game was asked about inside its window (unless it is
     * a [manual] ask: the player's own tap on a failed card goes out whatever the window says), or a read for it by this account is under way and inside its deadline. A game's
     * FIRST ask is never turned away because the throttle is full of unexpired stamps: the oldest one makes way. A read an earlier account had under way, or one past its
     * deadline, does not block it. A manual ask shows Loading at once, so the card answers the tap before the read does.
     */
    @Synchronized
    private fun claim(raGameId: Int, manual: Boolean): Claim? {
        val now = clock()
        if (hasLiveClaim(raGameId, now)) return null
        val last = askedAt[raGameId]
        if (!manual && last != null && now - last < refreshMillis) return null
        if (last == null && !room(now)) {
            // A repeat ask with the throttle full waits for a stamp to expire; a game's FIRST ask in this process (and a tap) makes room instead.
            if (!manual && raGameId in everAsked) return null
            askedAt.remove(askedAt.keys.first())
        }
        everAsked += raGameId
        noteAsked(raGameId, now)
        val claim = Claim(++nextToken, generation, now + ACTIVITY_CARD_READ_DEADLINE_MS)
        inFlight[raGameId] = claim
        if (manual && sink.value[raGameId] !is ActivityCardDetail.Loaded) put(raGameId, ActivityCardDetail.Loading)
        return claim
    }

    private fun hasLiveClaim(raGameId: Int, now: Long): Boolean {
        val live = inFlight[raGameId] ?: return false
        return live.generation == generation && now < live.deadlineAt
    }

    /** Whether the throttle can take one more game: drops the stamps whose window has passed, oldest first, and never an unexpired one. */
    private fun room(now: Long): Boolean {
        if (askedAt.size < ACTIVITY_CARD_THROTTLE_MAX_GAMES) return true
        val oldest = askedAt.entries.iterator()
        while (askedAt.size >= ACTIVITY_CARD_THROTTLE_MAX_GAMES && oldest.hasNext()) {
            if (now - oldest.next().value < refreshMillis) break
            oldest.remove()
        }
        return askedAt.size < ACTIVITY_CARD_THROTTLE_MAX_GAMES
    }

    /** Stamps [raGameId] as asked now (the newest). The caller has made room: this never evicts. */
    private fun noteAsked(raGameId: Int, now: Long) {
        askedAt.remove(raGameId)
        askedAt[raGameId] = now
    }

    /** Ends [claim]'s read; only its own claim comes out, never a newer one for the same game. */
    @Synchronized
    private fun finish(raGameId: Int, claim: Claim) {
        if (inFlight[raGameId]?.token == claim.token) inFlight.remove(raGameId)
    }

    /** Whether [claim]'s answer may be published: it is the game's current claim; superseded by a newer one for the same game (dropped); or the account it was made for is gone. */
    @Synchronized
    private fun standing(raGameId: Int, claim: Claim): Standing {
        if (claim.generation != generation) return Standing.OLD_ACCOUNT
        val live = inFlight[raGameId]
        if (live != null && live.token != claim.token) return Standing.SUPERSEDED
        return Standing.CURRENT
    }

    @Synchronized
    private fun isHeld(raGameId: Int): Boolean = raGameId in holds

    /** The games asked about (or seeded) since the account began, window or no window: the throttle turns away a REPEAT ask, never a game's first. */
    private val everAsked = HashSet<Int>()

    /** The games whose saved answer is being fetched from the ledger right now, so a card asking again does not open the file again. */
    private val restoring = HashSet<Int>()

    /**
     * An ask the throttle refused (the game was asked about inside its window) leaves the card honest, never bare: what the cache shows (an answer, stale or not, or a failure
     * the player can tap) stays, a read under way is left alone, and a game with NOTHING to show (its answer was evicted, or a read ended without publishing) is given the
     * answer the ledger saved for it, else the failed state with its retry. Nothing is asked of the network.
     */
    private fun serveWithoutAsking(raGameId: Int) {
        val signedIn: String
        val epoch: Long
        synchronized(this) {
            if (hasLiveClaim(raGameId, clock())) return
            val shown = sink.value[raGameId]
            if (shown != null && shown !== ActivityCardDetail.Loading) return
            // A read that was started and has not ended (even one past its deadline, which may still land) is not a failure: the card says it is loading, or stalled.
            if (shown === ActivityCardDetail.Loading && inFlight[raGameId]?.generation == generation) return
            if (!restoring.add(raGameId)) return
            signedIn = account()?.takeIf(String::isNotBlank) ?: run {
                restoring.remove(raGameId)
                return
            }
            epoch = book.accountEpoch()
        }
        scope.launch {
            try {
                val saved = ledger?.let { store -> withContext(workContext) { runCatching { store.readDetail(signedIn, raGameId) }.getOrNull() } }
                withContext(publishContext) {
                    synchronized(this@ActivityCardDetailLoader) {
                        val shown = sink.value[raGameId]
                        val nothingToShow = shown == null || shown === ActivityCardDetail.Loading
                        if (nothingToShow && !hasLiveClaim(raGameId, clock()) && book.accountUnchangedSince(epoch) && account() == signedIn) {
                            put(raGameId, saved?.let { ActivityCardDetail.Loaded(it.progress, it.savedAtMillis) } ?: ActivityCardDetail.Unavailable)
                        }
                    }
                }
            } finally {
                synchronized(this@ActivityCardDetailLoader) { restoring.remove(raGameId) }
            }
        }
    }

    /**
     * A fresh `API_GetGameInfoAndUserProgress` answer a card probe on MAIN already read for [progress]'s game: it is that game's ask, so neither surface asks again for
     * [refreshMillis] (Main's card and the Bottom's hub share this cache, and opening one warms the other). It publishes only while the account is still the one the probe
     * began under ([epoch]), and an account change forgets it like any other detail ([reset]). Asks nothing.
     */
    @Synchronized
    fun seed(progress: RetroAchievementsProgress, epoch: Long) {
        if (progress.gameId <= 0 || !book.accountUnchangedSince(epoch)) return
        val now = clock()
        // The read already happened, so its answer is cached; it is stamped when the throttle has room (full of unexpired stamps, nothing is evicted and nothing new is asked).
        everAsked += progress.gameId
        if (progress.gameId in askedAt || room(now)) noteAsked(progress.gameId, now)
        put(progress.gameId, ActivityCardDetail.Loaded(progress, now))
        account()?.takeIf(String::isNotBlank)?.let { saveLater(it, progress) }
    }

    /** Saves [progress] for [signedIn] off the main thread. A failed write is a missed save and nothing more. */
    private fun saveLater(signedIn: String, progress: RetroAchievementsProgress) {
        val store = ledger ?: return
        scope.launch(workContext) { runCatching { store.saveDetail(signedIn, progress, clock()) } }
    }

    /** Ask for [raGameId]'s detail unless it was asked for inside the window. Returns at once; the answer lands in the sink. */
    fun request(raGameId: Int) {
        ask(raGameId, manual = false)
    }

    /**
     * The player's tap on a failed card: ONE more read of [raGameId], whatever the window says. It is still the same read as any other (it goes through the client, so the
     * process-wide pacer and a Retry-After in force apply, and a tap while a read is under way starts nothing), and it stamps the game, so the window then runs from it.
     */
    fun retry(raGameId: Int) {
        ask(raGameId, manual = true)
    }

    /**
     * While a Retry-After is in force nothing may be sent, so nothing is claimed or stamped either: the card (unless it shows badges or a refused key) says RetroAchievements
     * asked for a wait, instead of flashing "Loading badges..." and failing at once, and a tap after the wait asks as usual. A read already under way is left alone.
     */
    @Synchronized
    private fun waitingOutHold(raGameId: Int): Boolean {
        if (!held()) return false
        if (hasLiveClaim(raGameId, clock())) return true
        val shown = sink.value[raGameId]
        if (shown !is ActivityCardDetail.Loaded && shown !== ActivityCardDetail.Rejected) put(raGameId, ActivityCardDetail.Throttled)
        return true
    }

    /** Marks [raGameId]'s card Stalled if [claim]'s read is still the one out and the card still says Loading. */
    @Synchronized
    private fun markStalled(raGameId: Int, claim: Claim) {
        if (claim.generation != generation || inFlight[raGameId]?.token != claim.token) return
        if (sink.value[raGameId] === ActivityCardDetail.Loading) put(raGameId, ActivityCardDetail.Stalled)
    }

    /** Waits for [claim]'s deadline by the loader's clock (not cutting the read short), then says so on the card. Cancelled when the read ends. */
    private suspend fun watchDeadline(raGameId: Int, claim: Claim) {
        while (true) {
            val left = claim.deadlineAt - clock()
            if (left <= 0L) break
            stallDelay(left)
        }
        withContext(publishContext) { markStalled(raGameId, claim) }
    }

    private fun ask(raGameId: Int, manual: Boolean) {
        if (raGameId <= 0 || account().isNullOrBlank()) return
        if (waitingOutHold(raGameId)) return
        val claim = claim(raGameId, manual)
        if (claim == null) {
            serveWithoutAsking(raGameId)
            return
        }
        val signedIn = account() ?: run {
            finish(raGameId, claim)
            return
        }
        val epoch = book.accountEpoch()
        scope.launch {
            var askAgain = false
            val watch = launch { watchDeadline(raGameId, claim) }
            try {
                // A game already shown keeps showing its last answer (stale or not) while a refresh is under way. One never shown starts from what the ledger
                // saved for it (its badges at once, replaced when the fresh read lands), else reads Loading. The saved answer is the account's own and is only
                // shown while that account and this claim are still the current ones.
                if (sink.value[raGameId] !is ActivityCardDetail.Loaded) {
                    val saved = ledger?.let { store ->
                        withContext(workContext) { runCatching { store.readDetail(signedIn, raGameId) }.getOrNull() }
                    }
                    val mine = book.accountUnchangedSince(epoch) && account() == signedIn && standing(raGameId, claim) == Standing.CURRENT
                    if (mine) put(raGameId, saved?.let { ActivityCardDetail.Loaded(it.progress, it.savedAtMillis) } ?: ActivityCardDetail.Loading)
                }
                // Whatever the read does, the card ends up with an answer it can show: a read that throws is a read that failed, never a "Loading" with nothing coming.
                val answer: ActivityCardDetail = withContext(workContext) {
                    try {
                        when (val outcome = fetch(raGameId)) {
                            is RaProgressOutcome.Read -> ActivityCardDetail.Loaded(outcome.progress, clock())
                            RaProgressOutcome.CredentialsRejected -> ActivityCardDetail.Rejected
                            RaProgressOutcome.Unavailable -> ActivityCardDetail.Unavailable
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        ActivityCardDetail.Unavailable
                    }
                }
                withContext(publishContext) {
                    val verdict = standing(raGameId, claim)
                    if (verdict == Standing.OLD_ACCOUNT) {
                        askAgain = true
                    } else if (verdict == Standing.CURRENT) {
                        if (book.accountUnchangedSince(epoch) && account() == signedIn) {
                            // A refresh that failed does not erase what the card already shows.
                            val failed = answer === ActivityCardDetail.Unavailable || answer === ActivityCardDetail.Rejected
                            if (!(failed && sink.value[raGameId] is ActivityCardDetail.Loaded)) put(raGameId, answer)
                            if (answer is ActivityCardDetail.Loaded) saveLater(signedIn, answer.progress)
                        } else {
                            askAgain = true
                        }
                    }
                    // (A SUPERSEDED claim - a newer read for this game exists because this one hung past its deadline - publishes nothing.)
                    Unit
                }
            } finally {
                watch.cancel()
                finish(raGameId, claim)
            }
            // The account changed under this read, so its answer was dropped. A card still showing the game asks again as the account that is now signed in, without being remounted.
            if (askAgain && isHeld(raGameId)) request(raGameId)
        }
    }
}

/**
 * The seam between the achievements lane (which owns the RetroAchievements client and the epoch) and the Bottom's hub (which shows the cards). The lane
 * installs its [ActivityCardDetailLoader] while it lives; with none installed, [request] asks nothing.
 */
internal object CompanionActivityCardDetails {
    internal val sink = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
    val state: StateFlow<Map<Int, ActivityCardDetail>> = sink.asStateFlow()

    private class Installed(val owner: Any, val loader: ActivityCardDetailLoader)

    @Volatile
    private var installed: Installed? = null

    fun install(owner: Any, loader: ActivityCardDetailLoader) {
        installed = Installed(owner, loader)
    }

    fun release(owner: Any) {
        if (installed?.owner === owner) {
            installed = null
            sink.value = emptyMap()
        }
    }

    /** A card the hub is showing asks for its game's detail. Not for a card that is not composed, and not at cold start. */
    fun request(raGameId: Int) {
        installed?.loader?.request(raGameId)
    }

    /** The player tapped a card whose read failed: one more read, outside the window (see [ActivityCardDetailLoader.retry]). A no-op with no lane installed. */
    fun retry(raGameId: Int) {
        installed?.loader?.retry(raGameId)
    }

    /** The cached detail for [raGameId], stale or not (an answer past the five-minute window is still served until its refresh lands). Null with no lane installed. */
    fun read(raGameId: Int): ActivityCardDetail? = installed?.loader?.read(raGameId)

    /** A shown card holds its game's answer so no other game's ask can evict it; the returned release lets go (once). A no-op with no lane installed. */
    fun hold(raGameId: Int): () -> Unit = installed?.loader?.hold(raGameId) ?: {}

    fun resetForTest() {
        installed = null
        sink.value = emptyMap()
    }
}
