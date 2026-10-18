package com.gruning.mimir.rewrite.core.metadata.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * The RetroAchievements Web API contract — PURE url-building + response-parsing (no network, no Android),
 * so it is fully unit-testable off-device. Live sockets stay inside the guarded source transports.
 *
 * ⚠️ CREDENTIAL SAFETY: every documented Web API URL carries the user's key as `y`; the separately identified
 * hash-library fallback is the only keyless exception. RA says to store the key like a password. Web API URLs
 * must NEVER be logged; callers log a redacted endpoint name only. [redact] exists for exactly that.
 *
 * Web API endpoints verified against RA's published API docs (2026-07-18):
 *  - `API_GetConsoleIDs.php` — `y`, optional `a`/`g`. Read at runtime rather than hardcoding numeric
 *    console ids not verified here; RA publishes the names, not a stable public id table.
 *  - `API_GetGameList.php` — `y`, `i` (console id), `h=1` (include supported hashes), `f=1` (only games
 *    that HAVE achievements). This is the hash → gameId index.
 *  - `API_GetGameInfoAndUserProgress.php` — `y`, `u` (username), `g` (game id). Per-achievement
 *    `DateEarned` / `DateEarnedHardcore` are present only on achievements the user has unlocked.
 *  - `API_GetUserProfile.php` — `y`, `u`. Connect-time username validation; a missing user is a JSON 404
 *    rather than the 401/403 used for a refused Web API key.
 *  - `API_GetUserCompletionProgress.php` — `y`, `u`, `c` (page size), `o` (offset). The Activity page's bulk read.
 *  - `API_GetUserRecentAchievements.php` — `y`, `u`, `m` (minutes back). The Activity page's recent-badge strip: one read, the whole window.
 *
 * One **undocumented**, keyless emulator endpoint is also supported:
 * `dorequest.php?r=hashlibrary`. It is consulted only after a successful f=1 miss, and every refusal or shape
 * change degrades to that same miss. It is not represented as a documented Web API contract here.
 *
 * The API is READ-ONLY for a third-party client. Mimir cannot unlock an achievement and must never imply
 * that it does — unlocking happens inside an RA-enabled emulator, not in a launcher.
 */
internal object RetroAchievementsApi {

    private const val BASE = "https://retroachievements.org/API"

    /** RA mixes string/number JSON types across endpoints, so parsing stays defensive. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The user's own credentials. [username] scopes progress reads, [apiKey] authenticates, and
     * [credentialRevision] is a local-only activation identity that never enters a provider URL.
     */
    data class Auth(
        val username: String,
        val apiKey: String,
        val credentialRevision: String? = null,
    ) {
        val isUsable: Boolean get() = username.isNotBlank() && apiKey.isNotBlank()

        /** Even this internal transport value must be safe if an exception or debug line renders it. */
        override fun toString(): String =
            "Auth(username=$username, apiKey=***, credentialRevision=***)"
    }

    /** Active gaming systems only — hubs and event pseudo-consoles are not things a user owns files for. */
    fun consoleIdsUrl(auth: Auth): String =
        "$BASE/API_GetConsoleIDs.php?y=${enc(auth.apiKey)}&a=1&g=1"

    /** Every achievement-bearing game on [consoleId], WITH its supported hashes. */
    fun gameListUrl(auth: Auth, consoleId: Int): String =
        "$BASE/API_GetGameList.php?y=${enc(auth.apiKey)}&i=$consoleId&h=1&f=1"

    /** One game's achievement set plus this user's unlock state. */
    fun gameProgressUrl(auth: Auth, gameId: Int): String =
        "$BASE/API_GetGameInfoAndUserProgress.php?y=${enc(auth.apiKey)}&u=${enc(auth.username)}&g=$gameId"

    /** The candidate user's public profile: validates the username paired with the key, and carries the user's points (see [parseUserPoints]). */
    fun userProfileUrl(auth: Auth): String =
        "$BASE/API_GetUserProfile.php?y=${enc(auth.apiKey)}&u=${enc(auth.username)}"

    /**
     * One page of the user's games with progress, from `API_GetUserCompletionProgress.php` (`y`, `u`, `c` page size, `o` offset): every game this
     * user has earned something in, with `NumAwarded` of `MaxPossible`. It is the Activity page's cold-start bulk read: ONE response replaces a
     * per-game progress request for the whole library.
     */
    fun userCompletionProgressUrl(auth: Auth, offset: Int, count: Int = COMPLETION_PAGE_SIZE): String =
        "$BASE/API_GetUserCompletionProgress.php?y=${enc(auth.apiKey)}&u=${enc(auth.username)}&c=$count&o=$offset"

    /** RA's documented maximum page size for [userCompletionProgressUrl]. */
    const val COMPLETION_PAGE_SIZE: Int = 500

    /**
     * The user's most recent unlocks across EVERY game, from `API_GetUserRecentAchievements.php` (`y`, `u`, `m` minutes back): the Activity page's recent-badge
     * strip. ONE response covers the whole window, so it replaces a per-game read. [minutes] is [RECENT_UNLOCKS_WINDOW_MINUTES] (30 days) in production.
     */
    fun userRecentAchievementsUrl(auth: Auth, minutes: Int = RECENT_UNLOCKS_WINDOW_MINUTES): String =
        "$BASE/API_GetUserRecentAchievements.php?y=${enc(auth.apiKey)}&u=${enc(auth.username)}&m=$minutes"

    /** The look-back of the recent-unlocks read: 30 days of minutes, and the largest `m` the transport allows. */
    const val RECENT_UNLOCKS_WINDOW_MINUTES: Int = 43_200

    /**
     * One recent unlock. [badgeName] is digits only or null (see [RetroAchievementsIcons.validatedBadgeName]): a provider value is trusted for no other shape.
     * [awardedAtMillis] is RA's own date (UTC), null when unreadable.
     */
    data class RecentUnlock(
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

    /**
     * The recent-unlocks answer, newest first. Null when the body is not a JSON array. A row without a positive achievement id, a title or a positive game id is
     * skipped (one odd row never blanks the strip); one achievement listed twice (softcore and hardcore) keeps its newest row, the hardcore one on a tie. An empty
     * array is a real answer: nothing was unlocked in the window.
     */
    fun parseRecentUnlocks(body: String?): List<RecentUnlock>? = tolerant(body) { root ->
        val rows = root.jsonArray.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.positiveInt("AchievementID") ?: return@mapNotNull null
            val gameId = row.positiveInt("GameID") ?: return@mapNotNull null
            val title = (row["Title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            RecentUnlock(
                achievementId = id,
                title = title,
                description = (row["Description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                points = row.nonNegativeInt("Points") ?: 0,
                badgeName = RetroAchievementsIcons.validatedBadgeName((row["BadgeName"] as? JsonPrimitive)?.contentOrNull?.trim()),
                gameId = gameId,
                gameTitle = (row["GameTitle"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                consoleName = (row["ConsoleName"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty(),
                awardedAtMillis = parseAwardedMillis((row["Date"] as? JsonPrimitive)?.contentOrNull),
                hardcore = (row["HardcoreMode"] as? JsonPrimitive)?.contentOrNull?.trim()?.let { it == "1" || it.equals("true", ignoreCase = true) } == true,
            )
        }
        rows.sortedWith(compareByDescending<RecentUnlock> { it.awardedAtMillis ?: Long.MIN_VALUE }.thenByDescending { it.hardcore }.thenByDescending { it.achievementId })
            .distinctBy { it.achievementId }
    }

    /** One game of the user's completion progress. [consoleName] is RA's, used only to map to a Mimir platform when matching the library. */
    data class CompletionEntry(
        val gameId: Int,
        val title: String,
        val consoleName: String,
        val maxPossible: Int,
        val numAwarded: Int,
        val numAwardedHardcore: Int,
        /** A validated relative `/Images/<digits>.png` ([RetroAchievementsIcons.validatedPath]) or null; anything else the response held is dropped. */
        val imageIconPath: String? = null,
        /** When the user last earned something in this game (RA's `MostRecentAwardedDate`), epoch millis UTC; null when absent or unreadable. */
        val lastAwardedAtMillis: Long? = null,
        /** RA's `HighestAwardKind` as given (`beaten-softcore`, `beaten-hardcore`, `completed`, `mastered`), or null when the game has no award; read with [RaAwardKind.fromProvider]. */
        val highestAwardKind: String? = null,
    )

    /**
     * [total] is every game RA holds for the user; [count] is this page's size; [results] this page's usable rows. [skipped] is how many rows of the page were
     * malformed and left out: a page with any is NOT everything RA said, so a read that met one can never be taken for the user's whole library.
     */
    data class CompletionPage(val count: Int, val total: Int, val results: List<CompletionEntry>, val skipped: Int = 0)

    /**
     * Null when the body is unusable. A row without a positive game id or title, or with a negative count, is skipped rather than failing the page: a
     * count here is display-only, never a claim about a file, so one odd row must not blank the user's whole Activity page.
     */
    fun parseUserCompletionProgress(body: String?): CompletionPage? = tolerant(body) { root ->
        val obj = root.jsonObject
        val results = obj["Results"]?.jsonArray ?: return@tolerant null
        val total = obj["Total"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return@tolerant null
        val entries = results.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val gameId = row.positiveInt("GameID") ?: return@mapNotNull null
            val title = row["Title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifEmpty { return@mapNotNull null }
            val maxPossible = row.nonNegativeInt("MaxPossible") ?: return@mapNotNull null
            // A hardcore-only row (RA may leave `NumAwarded` out or null when only hardcore awards exist) is still progress: the missing softcore figure falls
            // back to the hardcore one, and the row is skipped only when it carries neither.
            val hardcoreGiven = row.nonNegativeInt("NumAwardedHardcore")
            val awarded = row.nonNegativeInt("NumAwarded") ?: hardcoreGiven ?: return@mapNotNull null
            val hardcore = hardcoreGiven ?: 0
            CompletionEntry(
                gameId = gameId,
                title = title,
                consoleName = row["ConsoleName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                maxPossible = maxPossible,
                numAwarded = awarded,
                numAwardedHardcore = hardcore,
                imageIconPath = RetroAchievementsIcons.validatedPath((row["ImageIcon"] as? JsonPrimitive)?.contentOrNull),
                lastAwardedAtMillis = parseAwardedMillis((row["MostRecentAwardedDate"] as? JsonPrimitive)?.contentOrNull),
                highestAwardKind = (row["HighestAwardKind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
        CompletionPage(
            count = obj["Count"]?.jsonPrimitive?.intOrNull ?: results.size,
            total = total,
            results = entries,
            skipped = results.size - entries.size,
        )
    }

    /**
     * RA's own date for an award as epoch millis (UTC): the ISO form with an offset (`2023-10-26T22:47:17+00:00`) or the plain form without one
     * (`2023-10-26 22:47:17`, read as UTC). Null for blank or anything else, so an odd date is simply no date and never an error.
     */
    fun parseAwardedMillis(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(text.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()
    }

    /**
     * The user's two scores from the profile (`API_GetUserProfile.php`, the same call connect-time validation makes): `TotalPoints` (hardcore, RA's main score) and
     * `TotalSoftcorePoints`. They are RA's own numbers and are deliberately NOT summed anywhere: whether the softcore figure also covers achievements later earned in
     * hardcore is RA's bookkeeping, and adding them could count one achievement twice.
     */
    data class UserPoints(val hardcore: Int, val softcore: Int)

    /** Null when neither points field is present or readable, so an absent field is never rendered as zero points. */
    fun parseUserPoints(body: String?): UserPoints? = tolerant(body) { root ->
        val obj = root.jsonObject
        val hardcore = obj.nonNegativeInt("TotalPoints")
        val softcore = obj.nonNegativeInt("TotalSoftcorePoints")
        if (hardcore == null && softcore == null) null else UserPoints(hardcore ?: 0, softcore ?: 0)
    }

    /** Keyless whole-database hash membership, used despite its undocumented stability contract. */
    fun hashLibraryUrl(): String = "https://retroachievements.org/dorequest.php?r=hashlibrary"

    /** A loggable stand-in for a credential-bearing URL: the endpoint name and nothing else. */
    fun redact(url: String): String =
        runCatching {
            URI(url).rawPath
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: "retroachievements"

    /** RA console id → console name. Empty on any parse failure — an empty map just means "no matches". */
    fun parseConsoleIds(body: String?): Map<Int, String> = tolerant(body) { root ->
        root.jsonArray.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["ID"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = obj["Name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) null else id to name
        }.toMap()
    } ?: emptyMap()

    /**
     * Lowercase ROM hash → RA game id, flattened from the console's game list. One game carries many
     * hashes (regional dumps, revisions); every one of them points at the same game.
     *
     * Unlike the console-name helper, this parser cannot safely skip a malformed row: a partial f=1 index
     * would make an absent hash look like a completed search, and could then incorrectly authorize the
     * stronger keyless f=0 claim. Therefore null means the WHOLE response is unusable. Unknown fields remain
     * tolerated, but every returned row must carry a positive id, nonblank title, the requested positive
     * console id, a positive f=1 achievement count, and an array made entirely of canonical MD5 values.
     * An empty array contributes no positive mapping and marks the response incomplete; see
     * [parseUsableHashIndex]. A hash repeated for one game is harmless; one assigned to two games is not.
     */
    data class HashIndex(
        val hashes: Map<String, Int>,
        /** Every achievement-bearing game declared by this f=1 response, including rows with no hashes. */
        val gameIds: Set<Int>,
        val complete: Boolean,
    )

    /** Strict compatibility surface: incomplete responses still cannot become a completed miss. */
    fun parseHashIndex(body: String?, expectedConsoleId: Int? = null): Map<String, Int>? =
        parseUsableHashIndex(body, expectedConsoleId)?.takeIf(HashIndex::complete)?.hashes

    /**
     * Retains safe positive mappings when RA includes an achievement-bearing row with no published hash.
     * [HashIndex.complete] is false in that case, so callers may return a positive hit but may never turn
     * an absence into a miss or consult the stronger keyless fallback.
     */
    fun parseUsableHashIndex(body: String?, expectedConsoleId: Int? = null): HashIndex? = tolerant(body) { root ->
        val index = linkedMapOf<String, Int>()
        val gameIds = linkedSetOf<Int>()
        var complete = true
        for (entry in root.jsonArray) {
            val obj = entry.jsonObject
            val id = obj.positiveInt("ID") ?: return@tolerant null
            if (!gameIds.add(id)) return@tolerant null
            val consoleId = obj.positiveInt("ConsoleID") ?: return@tolerant null
            if (expectedConsoleId != null && consoleId != expectedConsoleId) return@tolerant null
            obj.requiredString("Title", allowBlank = false) ?: return@tolerant null
            obj.positiveInt("NumAchievements") ?: return@tolerant null
            val hashes = obj["Hashes"]?.jsonArray ?: return@tolerant null
            if (hashes.isEmpty()) {
                complete = false
                continue
            }
            for (element in hashes) {
                val primitive = element as? JsonPrimitive ?: return@tolerant null
                if (!primitive.isString) return@tolerant null
                val hash = primitive.content.lowercase(Locale.US)
                if (!MD5.matches(hash)) return@tolerant null
                val previous = index.putIfAbsent(hash, id)
                if (previous != null && previous != id) return@tolerant null
            }
        }
        HashIndex(index, gameIds, complete)
    }

    /** Credential-free structural reason for a rejected game-list body; never includes titles or hashes. */
    fun hashIndexDiagnostic(body: String?, expectedConsoleId: Int? = null): String {
        val root = runCatching { json.parseToJsonElement(body ?: "").jsonArray }.getOrElse {
            val lead = body?.trimStart()?.firstOrNull()?.let { char ->
                when (char) {
                    '<' -> "html"
                    '{' -> "object"
                    else -> "other"
                }
            } ?: "empty"
            return "root=$lead bytes=${body?.length ?: 0}"
        }
        for ((index, entry) in root.withIndex()) {
            val obj = runCatching { entry.jsonObject }.getOrNull()
                ?: return "rows=${root.size} invalid[$index]=not_object"
            val id = obj.positiveInt("ID") ?: return "rows=${root.size} invalid[$index]=id"
            val consoleId = obj.positiveInt("ConsoleID")
                ?: return "rows=${root.size} invalid[$index]=console_id"
            if (expectedConsoleId != null && consoleId != expectedConsoleId) {
                return "rows=${root.size} invalid[$index]=console_mismatch"
            }
            if (obj.requiredString("Title", allowBlank = false) == null) {
                return "rows=${root.size} invalid[$index]=title"
            }
            if (obj.positiveInt("NumAchievements") == null) {
                return "rows=${root.size} invalid[$index]=achievement_count"
            }
            val hashes = runCatching { obj["Hashes"]?.jsonArray }.getOrNull()
                ?: return "rows=${root.size} invalid[$index]=hashes_missing"
            if (hashes.isEmpty()) return "rows=${root.size} invalid[$index]=hashes_empty id_positive=${id > 0}"
            if (hashes.any { element ->
                    val primitive = element as? JsonPrimitive
                    primitive == null || !primitive.isString || !MD5.matches(primitive.content.lowercase(Locale.US))
                }
            ) {
                return "rows=${root.size} invalid[$index]=hash_value"
            }
        }
        return "rows=${root.size} structurally_valid"
    }

    data class UserProfile(val username: String, val ulid: String)

    /** A successful profile response proves the candidate username resolves under the candidate key. */
    fun parseUserProfile(body: String?): UserProfile? = tolerant(body) { root ->
        val obj = root.jsonObject
        val username = obj["User"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val ulid = obj["ULID"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (username.isEmpty() || ulid.isEmpty()) null else UserProfile(username, ulid)
    }

    /** One achievement and whether THIS user has unlocked it. */
    data class Achievement(
        val id: Int,
        val title: String,
        val description: String,
        val points: Int,
        val unlocked: Boolean,
        /** True only for a hardcore-mode unlock; implies [unlocked]. */
        val unlockedHardcore: Boolean,
        /** The badge name (digits only) the badge art is fetched by, or null when the response held none or one that is not digits. */
        val badgeName: String? = null,
        /** RA's own date strings for the unlock (`2023-05-01 12:34:56`), shown as given; null when locked. */
        val earnedAt: String? = null,
        val earnedHardcoreAt: String? = null,
    )

    /** A game's achievement set with this user's progress folded in. */
    data class GameProgress(
        val gameId: Int,
        val title: String,
        val achievements: List<Achievement>,
        /** Validated relative `/Images/<digits>.png` paths from the same response (title screen, in-game shot, icon), or null. */
        val imageTitlePath: String? = null,
        val imageIngamePath: String? = null,
        val imageIconPath: String? = null,
        /** RA's own name for the game's console from the same response (`ConsoleName`, "PlayStation 2"), or null when absent or blank. Display and grouping only. */
        val consoleName: String? = null,
    ) {
        val unlockedCount: Int get() = achievements.count { it.unlocked }
        val totalCount: Int get() = achievements.size
        val earnedPoints: Int get() = achievements.filter { it.unlocked }.sumOf { it.points }
        val totalPoints: Int get() = achievements.sumOf { it.points }
    }

    /**
     * Parse a game's achievements + progress. Null when the body is unusable — the caller then reports
     * "couldn't load", never an empty-but-confident "0 of 0".
     *
     * This is deliberately all-or-nothing over the ROWS: every row must carry the fields Mimir renders, its
     * object key must agree with its id, and duplicate ids are rejected. Skipping one malformed row would turn
     * incomplete data into a false claim about the user's progress. The declared total and unlock counts are
     * required fields but are NOT compared with the rows; see the body for why.
     *
     * Unlock truth is presence-based: RA omits `DateEarned` entirely on a locked achievement, so a blank,
     * null, or absent value means locked. A non-string date field is malformed rather than an unlock.
     */
    fun parseGameProgress(body: String?): GameProgress? = tolerant(body) { root ->
        val obj = root.jsonObject
        val gameId = obj.positiveInt("ID") ?: return@tolerant null
        val title = obj.requiredString("Title", allowBlank = false) ?: return@tolerant null
        // RAWeb fills these three from denormalised columns (games.achievements_published and
        // player_games.achievements_unlocked[_hardcore]) that a queued UpdatePlayerGameMetricsAction job rewrites
        // after each unlock, while `Achievements` and its DateEarned fields are read live. Right after a play
        // session they disagree, and requiring equality turned exactly the games just played into "progress
        // unavailable". They stay required as a shape check; every count Mimir shows is derived from the rows.
        obj.nonNegativeInt("NumAchievements") ?: return@tolerant null
        obj.nonNegativeInt("NumAwardedToUser") ?: return@tolerant null
        obj.nonNegativeInt("NumAwardedToUserHardcore") ?: return@tolerant null
        val rows = obj["Achievements"]?.jsonObject ?: return@tolerant null

        // A matched f=1 game is achievement-bearing. If RA ever documents a legitimate empty response, it
        // needs an explicit contract; until then, "unavailable" is more honest than a confident zero-of-zero.
        if (rows.isEmpty()) return@tolerant null

        val seenIds = hashSetOf<Int>()
        val achievements = ArrayList<Achievement>(rows.size)
        for ((rowKey, entry) in rows) {
            val a = entry.jsonObject
            val id = a.positiveInt("ID") ?: return@tolerant null
            if (rowKey.toIntOrNull() != id || !seenIds.add(id)) return@tolerant null
            val achievementTitle = a.requiredString("Title", allowBlank = false) ?: return@tolerant null
            val description = a.requiredString("Description", allowBlank = true) ?: return@tolerant null
            val points = a.nonNegativeInt("Points") ?: return@tolerant null
            val earned = a.optionalUnlock("DateEarned") ?: return@tolerant null
            val hardcore = a.optionalUnlock("DateEarnedHardcore") ?: return@tolerant null
            achievements += Achievement(
                id = id,
                title = achievementTitle,
                description = description,
                points = points,
                unlocked = hardcore || earned,
                unlockedHardcore = hardcore,
                badgeName = RetroAchievementsIcons.validatedBadgeName((a["BadgeName"] as? JsonPrimitive)?.contentOrNull),
                earnedAt = a.dateString("DateEarned"),
                earnedHardcoreAt = a.dateString("DateEarnedHardcore"),
            )
        }
        GameProgress(
            gameId = gameId,
            title = title,
            achievements = achievements.sortedBy { it.id },
            imageTitlePath = RetroAchievementsIcons.validatedPath((obj["ImageTitle"] as? JsonPrimitive)?.contentOrNull),
            imageIngamePath = RetroAchievementsIcons.validatedPath((obj["ImageIngame"] as? JsonPrimitive)?.contentOrNull),
            imageIconPath = RetroAchievementsIcons.validatedPath((obj["ImageIcon"] as? JsonPrimitive)?.contentOrNull),
            // Optional and never a reason to reject the answer: a missing or odd console is simply no console.
            consoleName = (obj["ConsoleName"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun JsonObject.positiveInt(field: String): Int? =
        this[field]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }

    private fun JsonObject.nonNegativeInt(field: String): Int? =
        this[field]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }

    private fun JsonObject.requiredString(field: String, allowBlank: Boolean): String? {
        val primitive = this[field] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val value = primitive.content.trim()
        return value.takeIf { allowBlank || it.isNotEmpty() }
    }

    /** The unlock date as RA wrote it, or null when absent, null, blank or not a string. */
    private fun JsonObject.dateString(field: String): String? =
        (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** null = malformed; false = absent/null/blank and therefore locked; true = a real unlock value. */
    private fun JsonObject.optionalUnlock(field: String): Boolean? {
        val value = this[field] ?: return false
        if (value is JsonNull) return false
        val primitive = value as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.isNotBlank()
    }

    /** Any malformed/absent/unexpected body yields null rather than throwing — a miss is never a crash. */
    private fun <T> tolerant(body: String?, parse: (JsonElement) -> T?): T? {
        if (body.isNullOrBlank()) return null
        return runCatching { parse(json.parseToJsonElement(body)) }.getOrNull()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val MD5 = Regex("^[0-9a-f]{32}$")
}
