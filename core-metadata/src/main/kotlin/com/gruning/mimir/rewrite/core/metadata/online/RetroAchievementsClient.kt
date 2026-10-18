package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PUBLIC RetroAchievements surface — the only RA types another module can touch.
 *
 * Everything else in this lane is `internal` on purpose, and widening it would have been the wrong fix. The
 * engine's whole safety story lives in what callers CANNOT reach: [RetroAchievementsApi] builds URLs that
 * carry the user's Web API key and owns the `redact` that keeps them out of logs, so exposing it would put
 * key-bearing string-building one import away from any feature module. [RetroAchievementsSource] owns the
 * gate, the host allowlist and the fetch-once caching; exposing it would let a caller construct a second one
 * with a different `httpGet` and quietly bypass all three. This facade is the narrow door instead: construct,
 * validate an account, ask whether RA supports a platform and whether Mimir can identify its files, match a
 * file, and read progress.
 *
 * Mirrors the [ScreenScraperMetadataSource] precedent in this package — a public entry point over an API
 * object that stays contained.
 *
 * ## The outcome contract
 *
 * [match] returns [RaMatchOutcome]: [RaMatchOutcome.Answered] carries the complete file-only
 * [RetroAchievementsMatch] shape, while credential rejection and no answer remain claims about the key or
 * moment. the file
 * type stays file-only; [RetroAchievementsMatch.RecognizedNoSet] is reached only through the keyless fallback after a successful achievement-bearing index miss.
 */
class RetroAchievementsClient internal constructor(
    private val source: RetroAchievementsSource,
    private val onCredentialsRejected: (credentialRevision: String?) -> Unit = {},
    /** Where the bulk read's one counts-only line per page goes; the real one writes it under the MimirRA tag in logcat. */
    private val diagnostic: (String) -> Unit = ::raDiagnosticLine,
) {

    /**
     * The two gate halves, as plain lambdas so this never depends on app types: [gateOpen] is the
     * connected-services opt-in, [account] the user's own sign-in. Both are read per call rather than
     * captured, so revoking consent or clearing credentials takes effect immediately instead of at the next
     * process start. A blank username or key reads as "no account" and closes the gate exactly like opting
     * out does.
     */
    constructor(
        gateOpen: () -> Boolean,
        account: () -> RetroAchievementsAccount?,
        onCredentialsRejected: (credentialRevision: String?) -> Unit = {},
        chdHashEngine: RetroAchievementsChdHashEngine? = null,
    ) : this(
        RetroAchievementsSource(
            gateOpen = gateOpen,
            auth = {
                account()?.let {
                    RetroAchievementsApi.Auth(
                        username = it.username,
                        apiKey = it.apiKey,
                        credentialRevision = it.credentialRevision,
                    )
                }
            },
            chdHash = chdHashEngine?.let { engine ->
                { consoleId, target -> engine.hash(consoleId, target) }
            },
            // The process-wide pacer: a burst of card opens can never start more than a handful of requests a second.
            pacer = RetroAchievementsRequestPacer.process,
        ),
        onCredentialsRejected,
    )

    /** True when [platformId] is in the current Mimir/RetroAchievements-supported intersection. */
    fun supports(platformId: String): Boolean = RetroAchievementsConsoleMap.supportsPlatform(platformId)

    /** True when Mimir has also ported RA's file-identification algorithm for [platformId]. */
    fun canIdentify(platformId: String): Boolean = RetroAchievementsHash.supports(platformId)

    /** True when the platform rule and this file's container are both locally readable. */
    fun canIdentify(platformId: String, fileName: String): Boolean =
        RetroAchievementsHash.supportsFile(platformId, fileName)

    /** Whether this supported file uses the seekable native reader rather than the streaming cartridge lane. */
    fun requiresLocalPath(platformId: String, fileName: String): Boolean =
        RetroAchievementsHash.requiresLocalPath(platformId, fileName)

    /**
     * Validate a candidate account before it is persisted. This deliberately bypasses the runtime opt-in
     * gate: connect-time validation happens before the account exists, and cannot depend on unrelated service
     * state. Key and username are checked independently so the caller can name the field the user must fix.
     */
    suspend fun validate(account: RetroAchievementsAccount): RaValidation =
        source.validate(RetroAchievementsApi.Auth(account.username, account.apiKey))

    /**
     * What RetroAchievements produced for this file. Only [RaMatchOutcome.Answered] contains a claim about
     * the file; rejected credentials and no usable answer remain outside [RetroAchievementsMatch].
     *
     * This separation prevents both a dead network and a refused key from rendering as "RetroAchievements
     * does not carry this game". Neither is a claim about the file and neither is cacheable.
     */
    suspend fun match(
        platformId: String,
        fileName: String,
        sizeBytes: Long,
        localPath: String? = null,
        targetChd: RetroAchievementsChdResident? = null,
        openStream: suspend () -> InputStream?,
    ): RaMatchOutcome = when (
        val found = source.lookup(
            platformId,
            fileName,
            sizeBytes,
            localPath,
            targetChd,
            openStream,
        )
    ) {
        is RaLookup.Hit -> RaMatchOutcome.Answered(RetroAchievementsMatch.Matched(found.gameId))
        // The fallback that tells "RA knows this file but attaches no set" apart from
        // "RA has never seen it" lives behind RaLookup.RecognizedNoSet.
        // A real miss remains the honest floor when the fallback is absent, refused, or does not carry it.
        RaLookup.Miss -> RaMatchOutcome.Answered(RetroAchievementsMatch.NotMatched)
        RaLookup.RecognizedNoSet -> RaMatchOutcome.Answered(RetroAchievementsMatch.RecognizedNoSet)
        is RaLookup.Rejected ->
            rejected(RaMatchOutcome.CredentialsRejected, found.credentialRevision)
        is RaLookup.Unavailable -> RaMatchOutcome.NoAnswer(found.reason)
    }

    /**
     * This file's RA game id, or null for every other outcome.
     *
     * A lossy convenience over [match], kept because callers that only want to fetch progress do not need
     * the distinction. Anything rendering a card should use [match] instead: this collapses "no set carries
     * this file" and "we never looked" back into one null, which is the conflation the three-state contract
     * exists to undo.
     */
    suspend fun resolveGameId(
        platformId: String,
        fileName: String,
        sizeBytes: Long,
        localPath: String? = null,
        targetChd: RetroAchievementsChdResident? = null,
        openStream: suspend () -> InputStream?,
    ): Int? = ((
        match(platformId, fileName, sizeBytes, localPath, targetChd, openStream) as? RaMatchOutcome.Answered
        )?.match
        as? RetroAchievementsMatch.Matched)?.gameId

    /**
     * True while a Retry-After the host handed ANY client of this process is in force: nothing key-bearing is sent until it has passed. A caller that polls (the play
     * session's unlock watch) asks this first, so it spends no request, and no wait, inside the host's own ask to slow down.
     */
    fun requestsHeld(): Boolean = RetroAchievementsRequestPacer.process.holding()

    /**
     * This user's progress on [gameId]. Credential rejection remains distinct from an unavailable response.
     * Not cached here: every call asks RetroAchievements. the count is read fresh whenever a launch card opens; nothing is held between reads. The Activity page's all-games answer is the separate bulk read ([userGames]).
     */
    suspend fun progressFor(gameId: Int): RaProgressOutcome = when (val result = source.progressFor(gameId)) {
        is RaProgressLookup.Read -> RaProgressOutcome.Read(
            RetroAchievementsProgress(
                gameId = result.progress.gameId,
                title = result.progress.title,
                achievements = result.progress.achievements.map {
                    RetroAchievementsAchievement(
                        id = it.id,
                        title = it.title,
                        description = it.description,
                        points = it.points,
                        unlocked = it.unlocked,
                        unlockedHardcore = it.unlockedHardcore,
                        badgeName = it.badgeName,
                        earnedAt = it.earnedAt,
                        earnedHardcoreAt = it.earnedHardcoreAt,
                    )
                },
                imageTitlePath = result.progress.imageTitlePath,
                imageIngamePath = result.progress.imageIngamePath,
                imageIconPath = result.progress.imageIconPath,
                consoleName = result.progress.consoleName,
            ),
        )
        is RaProgressLookup.Rejected ->
            rejected(RaProgressOutcome.CredentialsRejected, result.credentialRevision)
        RaProgressLookup.Unavailable -> RaProgressOutcome.Unavailable
    }


    /** The Mimir platform id RetroAchievements' console [consoleName] maps to, or null when it has none (a hub, an event, an unmapped system). */
    fun platformIdForConsoleName(consoleName: String): String? = RetroAchievementsConsoleMap.platformIdFor(consoleName)

    /**
     * Every game this user has progress on, with earned and total counts: the Activity page's ONE cold-start bulk read
     * (`API_GetUserCompletionProgress.php`, paged at 500 until the reported total is reached). Any failed page makes the answer NOT a success: it is
     * [RaUserGamesOutcome.Unavailable] when nothing was read, or [RaUserGamesOutcome.Partial] when some pages were, so a caller can tell the two apart in its
     * diagnostics but can never take either for everything the user has. A page that held a row this build could not read makes the answer partial too. Rejection
     * feeds the same one-shot callback as a per-game read. Not cached here.
     *
     * With [pinned] every page is asked as that ONE sign-in (the app captures it when the read begins), so a stored sign-in that changes mid-read can never
     * make one read of several pages a mix of two users. Without it each page reads the stored sign-in, as it always did.
     */
    suspend fun userGames(pinned: RetroAchievementsAccount? = null): RaUserGamesOutcome {
        val credentials = pinned?.toAuth()
        val games = LinkedHashMap<Int, RetroAchievementsUserGame>()
        var offset = 0
        var pages = 0
        var reportedTotal = 0
        // A page that held a row this build could not read: every game it DID read is still returned, but only as a partial answer, because the library is
        // not everything RetroAchievements reported. Without this, a total reached by counting the skipped row would read as complete and prune it away.
        var skippedRow = false
        // What a read that stopped short returns: nothing read at all is unavailable; some pages read is partial (and still never a success).
        fun incomplete(): RaUserGamesOutcome =
            if (games.isEmpty()) RaUserGamesOutcome.Unavailable else RaUserGamesOutcome.Partial(games.values.toList(), reportedTotal)
        while (pages < MAX_COMPLETION_PAGES) {
            when (val result = source.completionPage(offset, credentials)) {
                is RaCompletionPageLookup.Rejected ->
                    return rejected(RaUserGamesOutcome.CredentialsRejected, result.credentialRevision)
                RaCompletionPageLookup.Unavailable -> return incomplete()
                is RaCompletionPageLookup.Read -> {
                    val page = result.page
                    reportedTotal = page.total
                    if (page.skipped > 0) skippedRow = true
                    val pageGames = page.results.map { row ->
                        RetroAchievementsUserGame(
                            gameId = row.gameId,
                            title = row.title,
                            consoleName = row.consoleName,
                            // Softcore and hardcore awards are one union of unlocked achievements, as the per-game read counts them: a row that only
                            // carries (or has more) hardcore awards is not read as less progress than it is.
                            earned = maxOf(row.numAwarded, row.numAwardedHardcore),
                            total = row.maxPossible,
                            imageIconPath = row.imageIconPath,
                            lastAwardedAtMillis = row.lastAwardedAtMillis,
                            // The hardcore awards are INSIDE that union (an achievement earned in both modes is one achievement), so this never adds to it.
                            earnedHardcore = row.numAwardedHardcore,
                            awardKind = RaAwardKind.fromProvider(row.highestAwardKind),
                        )
                    }
                    pageGames.forEach { games.putIfAbsent(it.gameId, it) }
                    // Counts only: what RA said this page held (count, total), and how many of its rows the Activity page can show (parsed, and a total with the
                    // earned count inside it). An empty `valid` beside a non-empty `count` is the signature of a response this build cannot read.
                    diagnostic("bulk page=$pages count=${page.count} total=${page.total} valid=${pageGames.count { it.isShowable }}")
                    val advanced = maxOf(page.count, page.results.size + page.skipped)
                    offset += advanced
                    pages += 1
                    // Complete only when RA's own total is reached AND every row of every page was read. A page that adds nothing before that, a skipped
                    // row, or the page cap is an INCOMPLETE answer: it is never a Read, so nothing partial is ever published, saved or pruned against as
                    // everything the user has.
                    if (offset >= page.total) {
                        return if (skippedRow) incomplete() else RaUserGamesOutcome.Read(games.values.toList(), page.total)
                    }
                    if (advanced <= 0) return incomplete()
                }
            }
        }
        return incomplete()
    }

    /**
     * Stage one game icon to [target] (a local file), so the app can paint it offline through its local-only image loader. [imageIconPath] is a validated
     * relative RA path; the fetch is keyless, gated on the connected-services opt-in, bounded, and is NOT a RetroAchievements API call. True when [target] holds
     * a PNG afterwards, written atomically so a half-fetched file is never read. Never throws, never logs the URL.
     */
    suspend fun stageIcon(imageIconPath: String, target: File): Boolean {
        val bytes = source.iconBytes(imageIconPath) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                target.parentFile?.mkdirs()
                val partial = File(target.parentFile, target.name + ".partial")
                partial.writeBytes(bytes)
                if (partial.renameTo(target)) true else { partial.delete(); false }
            }.getOrDefault(false)
        }
    }

    /**
     * The user's most recent unlocks across every game: the Activity page's recent-badge strip. ONE request ([RetroAchievementsApi.RECENT_UNLOCKS_WINDOW_MINUTES]
     * back), newest first; a failed read is [RaRecentOutcome.Unavailable] and an empty window is a real, empty answer. Rejection feeds the same one-shot callback as
     * a per-game read. Not cached here.
     */
    suspend fun recentUnlocks(pinned: RetroAchievementsAccount? = null): RaRecentOutcome = when (val result = source.recentUnlocks(pinned?.toAuth())) {
        is RaRecentLookup.Read -> RaRecentOutcome.Read(
            result.unlocks.map {
                RetroAchievementsRecentUnlock(
                    achievementId = it.achievementId,
                    title = it.title,
                    description = it.description,
                    points = it.points,
                    badgeName = it.badgeName,
                    gameId = it.gameId,
                    gameTitle = it.gameTitle,
                    consoleName = it.consoleName,
                    awardedAtMillis = it.awardedAtMillis,
                    hardcore = it.hardcore,
                )
            },
        )
        is RaRecentLookup.Rejected -> rejected(RaRecentOutcome.CredentialsRejected, result.credentialRevision)
        RaRecentLookup.Unavailable -> RaRecentOutcome.Unavailable
    }

    /**
     * This user's two scores from the profile, as RetroAchievements keeps them: `TotalPoints` (hardcore) and `TotalSoftcorePoints`. They are returned apart and never
     * added: whether RA's softcore figure includes achievements also earned in hardcore is its own bookkeeping, so a sum here could count one achievement twice. Not cached here.
     */
    suspend fun userPoints(pinned: RetroAchievementsAccount? = null): RaPointsOutcome = when (val result = source.userPoints(pinned?.toAuth())) {
        is RaPointsLookup.Read -> RaPointsOutcome.Read(points = result.points.hardcore, softcorePoints = result.points.softcore)
        is RaPointsLookup.Rejected -> rejected(RaPointsOutcome.CredentialsRejected, result.credentialRevision)
        RaPointsLookup.Unavailable -> RaPointsOutcome.Unavailable
    }
    private fun RetroAchievementsAccount.toAuth() =
        RetroAchievementsApi.Auth(username = username, apiKey = apiKey, credentialRevision = credentialRevision)

    /**
     * Feed a runtime refusal back to the app without exposing credentials or the key-bearing URL. Called
     * exactly once for each rejected public outcome, never for a 404, parse failure, timeout, or ordinary
     * no-match. The callback receives only the local activation revision that authenticated this exact
     * request, so a delayed old-key refusal cannot reject a replacement. Validation intentionally does not
     * call this: candidate credentials have not been persisted.
     */
    private fun <T> rejected(outcome: T, credentialRevision: String?): T {
        onCredentialsRejected(credentialRevision)
        return outcome
    }
}

/** What connect-time validation established about the candidate account. */
sealed interface RaValidation {

    /** Both the Web API key and username were accepted. The only state that may persist credentials. */
    data object Accepted : RaValidation

    /** RA refused the Web API key (401/403), or the candidate key was blank. */
    data object KeyRejected : RaValidation

    /** The key worked, but the candidate username did not resolve (404), or it was blank. */
    data object UsernameRejected : RaValidation

    /** RA could not be reached or returned an unusable body. Says nothing about either credential. */
    data object Unreachable : RaValidation
}

/**
 * What a file lookup produced. [Answered] is cacheable because it carries a claim about the file; the other
 * outcomes are claims about the key or moment and must never enter a file-verdict cache.
 */
sealed interface RaMatchOutcome {
    data class Answered(val match: RetroAchievementsMatch) : RaMatchOutcome
    data object CredentialsRejected : RaMatchOutcome
    data class NoAnswer(val reason: RaNoAnswerReason) : RaMatchOutcome
}

/**
 * Why a file match produced no answer. These are deliberately public and structural so the app can render
 * truthful copy without reverse-engineering a nullable result. None carries a URL, provider body, file path,
 * hash, username, or credential.
 */
enum class RaNoAnswerReason {
    /** Consent is off or no usable account is configured; the network and file were never touched. */
    UNCONFIGURED,

    /** RetroAchievements does not currently support achievements for this Mimir platform. */
    UNSUPPORTED_SYSTEM,

    /** RA supports the platform, but Mimir has not ported its file-identification algorithm yet. */
    IDENTIFICATION_UNAVAILABLE,

    /** The live console list did not map this otherwise-hashable Mimir platform. */
    CONSOLE_UNMAPPED,

    /** The selected file could not be opened. */
    FILE_UNAVAILABLE,

    /** Timeout, DNS, rate limiting, or another transport-level failure. */
    TRANSPORT,

    /** RA answered, but its response was malformed or incomplete and no safe fallback could close the gap. */
    PROVIDER_DATA,
}

/** What a per-game progress request produced; no nullable seam can collapse key rejection into weather. */
sealed interface RaProgressOutcome {
    data class Read(val progress: RetroAchievementsProgress) : RaProgressOutcome
    data object CredentialsRejected : RaProgressOutcome
    data object Unavailable : RaProgressOutcome
}

/**
 * What RetroAchievements has to say about one local file. Three cases, and the difference between the last
 * two is the whole point: they look identical to a user but mean opposite things about their file.
 */
sealed interface RetroAchievementsMatch {

    /** An achievement set carries this exact file. [gameId] feeds [RetroAchievementsClient.progressFor]. */
    data class Matched(val gameId: Int) : RetroAchievementsMatch

    /**
     * RetroAchievements recognises this exact file, and no achievement set is attached to it.
     *
     * That is the entire claim. NOT "no achievements yet" — whether a set ever appears is a future nobody
     * here knows, and wording it that way would promise one. Carries no game id: the index that answers this
     * is set-membership only (see below), so "recognised" is all it can honestly report.
     *
     * Produced only after a successful f=1 miss and positive membership in the keyless hash
     * library. That 2.18 MiB response is streamed into two packed Longs per MD5 and cached once per process;
     * refusal or shape drift preserves [NotMatched] instead of fabricating this stronger claim.
     */
    data object RecognizedNoSet : RetroAchievementsMatch

    /**
     * Nothing carried this file. Deliberately silent about why: it folds together "RA has never seen this
     * dump", "known game, this particular dump is not linked to its set", and — per policy — a damaged
     * archive Mimir could not read. Those are not distinguishable here, so this state claims only the little
     * it can stand behind.
     */
    data object NotMatched : RetroAchievementsMatch
}

/**
 * A RetroAchievements sign-in as this module needs it: the user's name, Web API key, and a local-only
 * activation revision used to fence delayed rejection. The revision is never sent to RetroAchievements.
 *
 * Deliberately NOT a data class, matching the app's own `RetroAchievementsCredentials`: a generated
 * `toString` prints every field, so one stray `"$account"` in a log line would print the key. RA's docs treat the key as a secret and it must stay out of logs — a public type that undoes
 * that at the boundary would be the easiest possible way to leak it.
 */
class RetroAchievementsAccount(
    val username: String,
    val apiKey: String,
    val credentialRevision: String? = null,
) {

    /** Guards against a stray `"$account"` in a log line ever printing the key. */
    override fun toString(): String =
        "RetroAchievementsAccount(username=$username, apiKey=***, credentialRevision=***)"
}

/**
 * RA's own date string for an unlock or an award (`2023-05-01 12:34:56`, UTC, or the ISO form with an offset) as epoch milliseconds, or null for blank or anything
 * unreadable. The app sorts badges by it and prints it in the viewer's time zone; the raw string is never compared.
 */
fun retroAchievementsAwardMillis(raw: String?): Long? = RetroAchievementsApi.parseAwardedMillis(raw)

/**
 * One achievement and whether this user has it. [unlockedHardcore] is the stricter tier RA tracks separately
 * (no save states, no rewind); it implies [unlocked].
 */
data class RetroAchievementsAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    val unlockedHardcore: Boolean,
    /** Digits-only badge name the badge art is fetched by (see [RetroAchievementsIcons.badgePath]), or null. */
    val badgeName: String? = null,
    /** RA's own unlock date strings, as given; null when locked. */
    val earnedAt: String? = null,
    val earnedHardcoreAt: String? = null,
)

/**
 * A user's progress on one game. The counts are derived from [achievements] rather than carried alongside it,
 * so a total and its rows cannot disagree — "3 of 12" is always literally what the list holds.
 */
data class RetroAchievementsProgress(
    val gameId: Int,
    val title: String,
    val achievements: List<RetroAchievementsAchievement>,
    /** Validated relative `/Images/<digits>.png` paths of the game's title screen, in-game shot and icon, or null. */
    val imageTitlePath: String? = null,
    val imageIngamePath: String? = null,
    val imageIconPath: String? = null,
    /** RA's own name for the game's console (`ConsoleName`), or null when the response had none. The Activity page's console picker groups by it. */
    val consoleName: String? = null,
) {
    val unlockedCount: Int get() = achievements.count { it.unlocked }
    val totalCount: Int get() = achievements.size
    val earnedPoints: Int get() = achievements.filter { it.unlocked }.sumOf { it.points }
    val totalPoints: Int get() = achievements.sumOf { it.points }
}

/**
 * The highest award RetroAchievements reports for a game (`HighestAwardKind` in the completion read). Mastered and Completed are also readable from the counts (every
 * achievement earned in hardcore, or in either mode); Beaten is only ever RA's word.
 */
enum class RaAwardKind(
    /** RA's own word for it (`HighestAwardKind`), which is also what the ledger stores. */
    val word: String,
) {
    BEATEN_SOFTCORE("beaten-softcore"),
    BEATEN_HARDCORE("beaten-hardcore"),
    COMPLETED("completed"),
    MASTERED("mastered"),
    ;

    companion object {
        /** RA's string as a kind; anything absent or unknown is no award, never a guess. */
        fun fromProvider(raw: String?): RaAwardKind? {
            val word = raw?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null
            return entries.firstOrNull { it.word == word }
        }
    }
}

/** One game of the user's completion progress: [earned] of [total] achievements. Carries no credential, URL or username. */
data class RetroAchievementsUserGame(
    val gameId: Int,
    val title: String,
    val consoleName: String,
    val earned: Int,
    val total: Int,
    /** A validated relative RA icon path (`/Images/<digits>.png`), or null. Never a URL; [RetroAchievementsIcons] builds the one allowed URL from it. */
    val imageIconPath: String? = null,
    /** When the user last earned something in this game (epoch millis UTC), or null. */
    val lastAwardedAtMillis: Long? = null,
    /** How many of [earned] were earned in hardcore (0 where RA gave none). Always inside [earned], never added to it: an achievement earned in both modes is counted once. */
    val earnedHardcore: Int = 0,
    /** The highest award RA reports for the game, or null: the bulk read carries it, a per-game read does not. */
    val awardKind: RaAwardKind? = null,
) {
    /** What the Activity page can show for this game: a set with achievements, and an earned count inside it. Anything else is left out of the page. */
    val isShowable: Boolean get() = total > 0 && earned in 0..total
}

/**
 * This progress as one row of the user's games: the per-game read's unlocked and total counts, its console and icon, and the newest date among the unlocks it
 * holds. Null for a game with no id or no achievements. The per-game read is live, so the row it makes is at least as fresh as the bulk read's.
 */
fun RetroAchievementsProgress.toUserGame(): RetroAchievementsUserGame? {
    if (gameId <= 0 || totalCount <= 0) return null
    val newest = achievements.asSequence()
        .filter { it.unlocked }
        .flatMap { sequenceOf(it.earnedAt, it.earnedHardcoreAt) }
        .mapNotNull(RetroAchievementsApi::parseAwardedMillis)
        .maxOrNull()
    return RetroAchievementsUserGame(
        gameId = gameId,
        title = title,
        consoleName = consoleName.orEmpty(),
        earned = unlockedCount,
        total = totalCount,
        imageIconPath = imageIconPath,
        lastAwardedAtMillis = newest,
        earnedHardcore = achievements.count { it.unlockedHardcore },
    )
}

/** What the bulk completion read produced. [Read.totalGames] is RA's own count of the user's games, for a display that wants it. */
sealed interface RaUserGamesOutcome {
    data class Read(val games: List<RetroAchievementsUserGame>, val totalGames: Int) : RaUserGamesOutcome
    data object CredentialsRejected : RaUserGamesOutcome

    /**
     * Some pages were read and then the read stopped short (a later page failed, came back empty before RA's total, or the page cap was hit): [games] is what
     * was read and [totalGames] RA's own count. NOT a success: a caller reports it but never publishes or saves it as everything the user has.
     */
    data class Partial(val games: List<RetroAchievementsUserGame>, val totalGames: Int) : RaUserGamesOutcome

    data object Unavailable : RaUserGamesOutcome
}

/** One recent unlock: an achievement the user earned, with its game. [badgeName] is digits only or null. Carries no credential, URL or username. */
data class RetroAchievementsRecentUnlock(
    val achievementId: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgeName: String?,
    val gameId: Int,
    val gameTitle: String,
    val consoleName: String,
    val awardedAtMillis: Long?,
    val hardcore: Boolean,
)

/** What the recent-unlocks read produced, newest first. An empty [Read] is a real answer. */
sealed interface RaRecentOutcome {
    data class Read(val unlocks: List<RetroAchievementsRecentUnlock>) : RaRecentOutcome
    data object CredentialsRejected : RaRecentOutcome
    data object Unavailable : RaRecentOutcome
}

/** What the points read produced. */
sealed interface RaPointsOutcome {
    /** [points] is the hardcore score (RA's main one) and [softcorePoints] the softcore score, kept apart: they are never summed. */
    data class Read(val points: Int, val softcorePoints: Int = 0) : RaPointsOutcome
    data object CredentialsRejected : RaPointsOutcome
    data object Unavailable : RaPointsOutcome
}

/** A user with more than this many pages of games (500 each) is read to the cap and no further. */
private const val MAX_COMPLETION_PAGES = 40
