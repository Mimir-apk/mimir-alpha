package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an account's last COMPLETE bulk read left on disk (every game the user has progress on, their profile points and their recent unlocks), plus the rows the
 * app's per-game reads folded in since. [points] and [recent] are null when no read has ever produced them (never a zero standing in for "unknown").
 */
data class RetroAchievementsLedgerBulk(
    /** When these games last CHANGED (an identical re-write keeps the old time), epoch millis. */
    val savedAtMillis: Long,
    val points: Int?,
    val games: List<RetroAchievementsUserGame>,
    val recent: List<RetroAchievementsRecentUnlock>?,
    /**
     * True while this file holds only what per-game reads (and, at most, points and recent unlocks) put there: no complete bulk read of this device and no
     * restored backup is recorded in it. A backup restore then merges into it instead of being refused ([RetroAchievementsLedger.insertBulkIfAbsent]). A file
     * written before this flag existed reads as false: it came from a complete read or a restore.
     */
    val laneOnly: Boolean = false,
    /** The profile's softcore score as last read, or null; [points] is the hardcore one. Never added to it. */
    val softcorePoints: Int? = null,
)

enum class RetroAchievementsLedgerVerdictKind { MATCHED, RECOGNIZED_NO_SET, NOT_MATCHED }

/** What RetroAchievements said about one library file. [fileKey] is the app's own identity|size|modified key: a changed file is a different key. */
data class RetroAchievementsLedgerVerdict(
    val fileKey: String,
    val kind: RetroAchievementsLedgerVerdictKind,
    /** RA's game id; set exactly when [kind] is [RetroAchievementsLedgerVerdictKind.MATCHED]. */
    val raGameId: Int?,
    val atMillis: Long,
)

/** The game-to-RA-id resolution: the file verdicts, and which library game each RA game was identified as. */
data class RetroAchievementsLedgerResolution(
    val verdicts: List<RetroAchievementsLedgerVerdict> = emptyList(),
    val localByRaGameId: Map<Int, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = verdicts.isEmpty() && localByRaGameId.isEmpty()
}

/** One game's whole answer (badges, earned flags and dates, console), as it was read at [savedAtMillis]. */
data class RetroAchievementsLedgerDetail(val progress: RetroAchievementsProgress, val savedAtMillis: Long)

/**
 * What a ledger write did. [UNCHANGED] is the idempotent case: the same answer twice touches nothing (and an insert-if-absent that found a row). [REFUSED] is a
 * write the caller's own guard turned away at the last moment, inside the lock. [FAILED] wrote nothing and left the old file as it was: a disk error, a blank
 * account, or an answer too large to keep (see the bounds in the companion).
 */
enum class RetroAchievementsLedgerWrite { WRITTEN, UNCHANGED, REFUSED, FAILED }

/**
 * The durable RetroAchievements ledger: what Mimir learned about one account's progress, kept in app-private storage so a restart, an `adb install -r`, a
 * switch of account and a return all find it again. Plain files, written atomically, no database and so no schema to migrate on an upgrade.
 *
 * ```
 * <root>/<accountKey>/bulk.json        the last complete bulk read (games, points, recent unlocks) plus the per-game rows folded in  + bulk.json.bak
 * <root>/<accountKey>/resolution.json  file verdicts and the library-game-to-RA-id links               + resolution.json.bak
 * <root>/<accountKey>/detail/g<id>.json one game's badges, earned flags and dates
 * ```
 *
 * **Keyed by account.** [accountKey] is a one-way hash of the username (lower-cased, so RA's case-insensitive names agree). The username itself, the API key
 * and every URL never enter a file, a folder name or a log line: this class takes the username only to derive the key.
 *
 * **Idempotent.** A write is an upsert of a whole unit; when the unit equals what is already on disk (its timestamp aside) nothing is written and the answer
 * is [RetroAchievementsLedgerWrite.UNCHANGED]. **Redundant.** The two units that matter most keep the previous generation as a `.bak`; a read falls back to it
 * when the current file is missing or damaged. **Fail-closed.** A file that does not parse, is too large, or was written by a newer format reads as absent,
 * never as an empty-but-confident answer; only a complete bulk read is ever saved (the caller's rule, see [saveBulk]).
 *
 * Every method does blocking file work: call it from an IO context. One lock serialises all writers.
 */
class RetroAchievementsLedger(
    private val root: File,
    private val maxDetailGames: Int = DEFAULT_MAX_DETAIL_GAMES,
) {
    private val lock = Any()

    /** True when this account has any ledger folder at all. */
    fun hasAccount(account: String): Boolean = accountDir(account)?.isDirectory == true

    // Bulk ------------------------------------------------------------------------------------------------------------------------------

    /** The last complete bulk read for [account], or null when none was saved or none can be read. */
    fun readBulk(account: String): RetroAchievementsLedgerBulk? = synchronized(lock) {
        val file = fileOf(account, BULK) ?: return null
        readGeneration(file) { parseBulk(it) }
    }

    /** How many games the account's saved bulk read holds (0 when none). */
    fun bulkGameCount(account: String): Int = readBulk(account)?.games?.size ?: 0

    /**
     * Save a COMPLETE bulk read: [games] replaces the saved list as a whole (the read is authoritative for what the user has progress on). [points] and [recent]
     * are saved when read and left as they were when null, so a profile read that failed this time never turns a known point total into "unknown".
     *
     * The caller saves ONLY after a complete read; a partial, rejected or unavailable one never reaches this method, so nothing incomplete can overwrite a
     * complete snapshot. The same answer twice is [RetroAchievementsLedgerWrite.UNCHANGED].
     *
     * [mayWrite] is asked INSIDE the lock, at the last moment before the file would change: false turns the write away as [RetroAchievementsLedgerWrite.REFUSED].
     * The caller passes its own "is this read still the current one" test (an account epoch), so a read that a sign-in change overtook cannot write even if the
     * change landed while this call waited for the lock.
     *
     * Bounded BEFORE anything is written: more than [MAX_BULK_GAMES] games is [RetroAchievementsLedgerWrite.FAILED] (a list cut short would pose as the whole
     * answer), text is cut to its caps, recent unlocks to [MAX_RECENT], and an encoding larger than a file the ledger will read is [RetroAchievementsLedgerWrite.FAILED]
     * with the old file untouched, so a write can never leave behind a file the next start would refuse.
     *
     * With [keepObserved] the games the app's per-game reads put in the saved list with NOTHING earned, and that [games] does not name, stay: a list of games with
     * progress never names them, and they are what the Bottom has seen once. Which rows those are is decided HERE, inside the lock, from the file as it is at this
     * moment, so a per-game write that lands while the caller was busy reading cannot be erased by this save. A game with earned counts that [games] no longer lists
     * is dropped, as the read is the authority on those. A complete read always clears [RetroAchievementsLedgerBulk.laneOnly].
     */
    fun saveBulk(
        account: String,
        games: List<RetroAchievementsUserGame>,
        points: Int?,
        recent: List<RetroAchievementsRecentUnlock>?,
        nowMillis: Long,
        mayWrite: () -> Boolean = { true },
        keepObserved: Boolean = false,
        softcorePoints: Int? = null,
    ): RetroAchievementsLedgerWrite = synchronized(lock) {
        val file = fileOf(account, BULK) ?: return RetroAchievementsLedgerWrite.FAILED
        if (games.size > MAX_BULK_GAMES) return RetroAchievementsLedgerWrite.FAILED
        val onDisk = parseBulkOrNull(file)
        val held = onDisk ?: readGeneration(file) { parseBulk(it) }
        val kept = if (keepObserved && held != null) {
            val listed = games.mapTo(HashSet()) { it.gameId }
            held.games.filter { it.earned == 0 && it.gameId !in listed }
        } else {
            emptyList()
        }
        commitBulkLocked(
            file, onDisk, held,
            RetroAchievementsLedgerBulk(
                savedAtMillis = nowMillis,
                points = points?.takeIf { it >= 0 } ?: held?.points,
                games = (games + kept).map { it.bounded() },
                recent = recent?.take(MAX_RECENT)?.map { it.bounded() } ?: held?.recent,
                softcorePoints = softcorePoints?.takeIf { it >= 0 } ?: held?.softcorePoints,
            ),
            mayWrite,
        )
    }

    /**
     * Change ONLY the saved points and recent unlocks, leaving the games exactly as they are (and creating a games-less, per-game-only file when there is none).
     * A null argument leaves that field as it was, so a profile read that failed this time never turns a known total into "unknown". For a bulk read whose empty
     * answer was not believed: its games are discarded, its points and recent unlocks are still the freshest there are. Same lock, bounds and [mayWrite] test as
     * [saveBulk]; the same answer twice is [RetroAchievementsLedgerWrite.UNCHANGED].
     */
    fun saveBulkExtras(
        account: String,
        points: Int?,
        recent: List<RetroAchievementsRecentUnlock>?,
        nowMillis: Long,
        mayWrite: () -> Boolean = { true },
        softcorePoints: Int? = null,
    ): RetroAchievementsLedgerWrite = synchronized(lock) {
        if (points == null && recent == null) return RetroAchievementsLedgerWrite.UNCHANGED
        val file = fileOf(account, BULK) ?: return RetroAchievementsLedgerWrite.FAILED
        val onDisk = parseBulkOrNull(file)
        val held = onDisk ?: readGeneration(file) { parseBulk(it) }
        commitBulkLocked(
            file, onDisk, held,
            RetroAchievementsLedgerBulk(
                savedAtMillis = nowMillis,
                points = points?.takeIf { it >= 0 } ?: held?.points,
                games = held?.games.orEmpty(),
                recent = recent?.take(MAX_RECENT)?.map { it.bounded() } ?: held?.recent,
                laneOnly = held?.laneOnly ?: true,
                softcorePoints = softcorePoints?.takeIf { it >= 0 } ?: held?.softcorePoints,
            ),
            mayWrite,
        )
    }

    /**
     * Fold [games] (rows a per-game read produced) into the account's saved list, as ONE step under the lock: a game already saved is replaced by its fresher row,
     * a new one is added, every other row is left exactly as it was, and [points] and the recent unlocks are untouched. So whatever the app has seen once survives
     * a restart even when no bulk read ever succeeds, and a later complete bulk read ([saveBulk]) still replaces the whole list as the authority.
     *
     * A fresher row without an icon or an award date keeps the saved row's (a per-game read names no date for a game with nothing earned, and the date of an
     * earlier award is not lost to a read that merely did not carry one). Everything else is [saveBulk]'s: the same bounds ([MAX_BULK_GAMES] refuses the whole
     * write, never a cut-short list), the [mayWrite] test inside the lock, the idempotence ([RetroAchievementsLedgerWrite.UNCHANGED] when nothing differs).
     */
    fun upsertBulkGames(
        account: String,
        games: List<RetroAchievementsUserGame>,
        nowMillis: Long,
        mayWrite: () -> Boolean = { true },
    ): RetroAchievementsLedgerWrite = synchronized(lock) {
        // Only rows a read would accept back are kept; with none, there is nothing to fold in (and an empty list is never written from here).
        val rows = games.map { it.bounded() }.filter { it.gameId > 0 && it.total >= 0 && it.earned >= 0 }
        if (rows.isEmpty()) return RetroAchievementsLedgerWrite.UNCHANGED
        val file = fileOf(account, BULK) ?: return RetroAchievementsLedgerWrite.FAILED
        val onDisk = parseBulkOrNull(file)
        val held = onDisk ?: readGeneration(file) { parseBulk(it) }
        val merged = LinkedHashMap<Int, RetroAchievementsUserGame>()
        held?.games?.forEach { merged[it.gameId] = it }
        for (row in rows) {
            val saved = merged[row.gameId]
            merged[row.gameId] = if (saved == null) {
                row
            } else {
                row.copy(
                    consoleName = row.consoleName.ifEmpty { saved.consoleName },
                    imageIconPath = row.imageIconPath ?: saved.imageIconPath,
                    lastAwardedAtMillis = row.lastAwardedAtMillis ?: saved.lastAwardedAtMillis.takeIf { row.earned > 0 },
                    // A per-game read names no award; the one the bulk read gave stays (an award is never taken back by a read that merely did not carry it).
                    awardKind = row.awardKind ?: saved.awardKind,
                )
            }
        }
        commitBulkLocked(
            file, onDisk, held,
            RetroAchievementsLedgerBulk(
                savedAtMillis = nowMillis,
                points = held?.points,
                games = merged.values.map { it.bounded() },
                recent = held?.recent,
                // Rows folded in by per-game reads belong to the file's kind: a complete read stays complete, a fresh file is per-game-only.
                laneOnly = held?.laneOnly ?: true,
                softcorePoints = held?.softcorePoints,
            ),
            mayWrite,
        )
    }

    /**
     * Keep [games] as the account's saved bulk read when the account has no saved read of its own, as ONE step under the lock: a live read that saves between a
     * caller's "is there one?" and its write can never be overwritten, because there is no gap between the two. The restore from a backup uses this.
     *
     *  - No saved file: [games] and [points] are saved as a read.
     *  - A saved COMPLETE read (or an earlier restore): left exactly as it was, [RetroAchievementsLedgerWrite.UNCHANGED].
     *  - A file that holds only what per-game reads put there ([RetroAchievementsLedgerBulk.laneOnly]): MERGED. The per-game rows win for any game they name
     *    (they were read on this device, though a game with no award takes the backup's), the backup adds the games they do not, and the backup's [points] and [softcorePoints] are kept unless that score is already
     *    saved. The file stays per-game-only, so the same backup twice changes nothing and a later complete read still takes over.
     */
    fun insertBulkIfAbsent(
        account: String,
        games: List<RetroAchievementsUserGame>,
        points: Int?,
        nowMillis: Long,
        softcorePoints: Int? = null,
    ): RetroAchievementsLedgerWrite = synchronized(lock) {
        val file = fileOf(account, BULK) ?: return RetroAchievementsLedgerWrite.FAILED
        if (games.size > MAX_BULK_GAMES) return RetroAchievementsLedgerWrite.FAILED
        val onDisk = parseBulkOrNull(file)
        val held = onDisk ?: readGeneration(file) { parseBulk(it) }
        if (held != null && !held.laneOnly) return RetroAchievementsLedgerWrite.UNCHANGED
        val merged = LinkedHashMap<Int, RetroAchievementsUserGame>()
        held?.games?.forEach { merged[it.gameId] = it }
        // A game on both sides keeps the device's row (it was read here), but not without the backup's award: a per-game read names none, and an award is never taken back by
        // a read that merely did not carry it (the rule [upsertBulkGames] keeps).
        games.forEach { backup ->
            val device = merged[backup.gameId]
            merged[backup.gameId] = if (device == null) backup else device.copy(awardKind = device.awardKind ?: backup.awardKind)
        }
        commitBulkLocked(
            file, onDisk, held,
            RetroAchievementsLedgerBulk(
                savedAtMillis = nowMillis,
                points = held?.points ?: points?.takeIf { it >= 0 },
                games = merged.values.map { it.bounded() },
                recent = held?.recent,
                laneOnly = held != null,
                softcorePoints = held?.softcorePoints ?: softcorePoints?.takeIf { it >= 0 },
            ),
            mayWrite = { true },
        )
    }

    /** The one place a bulk file is replaced: bounded, idempotent (compared with what is on disk), stamped, guarded and written atomically. */
    private fun commitBulkLocked(
        file: File,
        onDisk: RetroAchievementsLedgerBulk?,
        held: RetroAchievementsLedgerBulk?,
        next: RetroAchievementsLedgerBulk,
        mayWrite: () -> Boolean,
    ): RetroAchievementsLedgerWrite {
        if (next.games.size > MAX_BULK_GAMES) return RetroAchievementsLedgerWrite.FAILED
        if (onDisk != null && sameBulkContent(onDisk, next)) return RetroAchievementsLedgerWrite.UNCHANGED
        // A change of content takes the new time; a repair of a missing or damaged current file keeps the time of the data it restores.
        val stamped = if (held != null && sameBulkContent(held, next)) next.copy(savedAtMillis = held.savedAtMillis) else next
        if (!mayWrite()) return RetroAchievementsLedgerWrite.REFUSED
        return writeGeneration(file, encodeBulk(stamped), keepBackup = onDisk != null)
    }

    // Resolution ------------------------------------------------------------------------------------------------------------------------

    /** The account's saved resolution, empty when there is none (never null: an absent file is simply nothing resolved yet). */
    fun readResolution(account: String): RetroAchievementsLedgerResolution = synchronized(lock) {
        val file = fileOf(account, RESOLUTION) ?: return RetroAchievementsLedgerResolution()
        readGeneration(file) { parseResolution(it) } ?: RetroAchievementsLedgerResolution()
    }

    /** Save [resolution] as the account's whole resolution. Bounded: the newest [MAX_VERDICTS] verdicts and [MAX_LOCAL_LINKS] links are kept. */
    fun saveResolution(account: String, resolution: RetroAchievementsLedgerResolution): RetroAchievementsLedgerWrite = synchronized(lock) {
        val file = fileOf(account, RESOLUTION) ?: return RetroAchievementsLedgerWrite.FAILED
        val bounded = RetroAchievementsLedgerResolution(
            verdicts = resolution.verdicts.filter { it.fileKey.length <= MAX_KEY_CHARS }.sortedBy { it.atMillis }.takeLast(MAX_VERDICTS),
            localByRaGameId = resolution.localByRaGameId.entries
                .filter { it.value.length <= MAX_KEY_CHARS }
                .takeLast(MAX_LOCAL_LINKS)
                .associate { it.key to it.value },
        )
        val onDisk = parseResolutionOrNull(file)
        if (onDisk != null && onDisk == bounded) return RetroAchievementsLedgerWrite.UNCHANGED
        writeGeneration(file, encodeResolution(bounded), keepBackup = onDisk != null)
    }

    // Toasted ---------------------------------------------------------------------------------------------------------------------------

    /**
     * The achievement ids already shown to the player as an "Achievement earned" toast for [account], oldest first; empty when none were saved or the file cannot be read.
     * Kept so a restart in the middle of a session never toasts the same unlock twice.
     */
    fun readToasted(account: String): List<Int> = synchronized(lock) {
        val file = fileOf(account, TOASTED) ?: return emptyList()
        readGeneration(file) { parseToasted(it) } ?: emptyList()
    }

    /** Save [ids] (oldest first) as the account's whole toasted set. Bounded: the newest [MAX_TOASTED] are kept. */
    fun saveToasted(account: String, ids: List<Int>): RetroAchievementsLedgerWrite = synchronized(lock) {
        val file = fileOf(account, TOASTED) ?: return RetroAchievementsLedgerWrite.FAILED
        val bounded = ids.filter { it > 0 }.distinct().takeLast(MAX_TOASTED)
        val onDisk = readText(file)?.let { parseToasted(it) }
        if (onDisk != null && onDisk == bounded) return RetroAchievementsLedgerWrite.UNCHANGED
        writeGeneration(file, json.encodeToString(ToastedDto.serializer(), ToastedDto(ids = bounded)), keepBackup = onDisk != null)
    }

    private fun parseToasted(text: String): List<Int>? = runCatching {
        val dto = json.decodeFromString(ToastedDto.serializer(), text)
        if (dto.v != FORMAT_VERSION) return null
        dto.ids.filter { it > 0 }
    }.getOrNull()

    // Per-game detail -------------------------------------------------------------------------------------------------------------------

    /** One game's saved answer for [account], or null. A read keeps the game young for the eviction below. */
    fun readDetail(account: String, raGameId: Int): RetroAchievementsLedgerDetail? = synchronized(lock) {
        val file = detailFile(account, raGameId) ?: return null
        val detail = parseDetailOrNull(file) ?: return null
        if (detail.progress.gameId != raGameId) return null
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        detail
    }

    /**
     * Save one game's answer. An answer with no achievements is not saved (the lane treats it as unavailable, and an empty set must never replace a real one).
     * At most [maxDetailGames] games are kept per account, least recently used out first.
     */
    fun saveDetail(account: String, progress: RetroAchievementsProgress, nowMillis: Long): RetroAchievementsLedgerWrite = synchronized(lock) {
        if (progress.gameId <= 0 || progress.achievements.isEmpty() || progress.achievements.size > MAX_DETAIL_ACHIEVEMENTS) return RetroAchievementsLedgerWrite.FAILED
        val file = detailFile(account, progress.gameId) ?: return RetroAchievementsLedgerWrite.FAILED
        val kept = progress.bounded()
        val onDisk = parseDetailOrNull(file)
        if (onDisk != null && onDisk.progress == kept) return RetroAchievementsLedgerWrite.UNCHANGED
        val written = writeGeneration(file, encodeDetail(RetroAchievementsLedgerDetail(kept, nowMillis)), keepBackup = false)
        if (written == RetroAchievementsLedgerWrite.WRITTEN) evictDetailsLocked(file.parentFile, keep = file.name)
        written
    }

    /** The RA ids of the games whose detail is saved for [account]. */
    fun detailGameIds(account: String): Set<Int> = synchronized(lock) {
        val dir = accountDir(account)?.let { File(it, DETAIL_DIR) } ?: return emptySet()
        dir.listFiles().orEmpty().mapNotNull { detailIdOf(it.name) }.toSet()
    }

    // Forgetting ------------------------------------------------------------------------------------------------------------------------

    /**
     * Delete every row this ledger holds for [account]. Nothing in the app calls it today: signing out keeps the rows (that is the point of the ledger), and the
     * Settings row that disconnects RetroAchievements forgets the KEY, not the data. It exists so a future "forget my achievement data" row is one call.
     */
    fun forget(account: String): Boolean = synchronized(lock) {
        val dir = accountDir(account) ?: return true
        !dir.exists() || dir.deleteRecursively()
    }

    // Files -----------------------------------------------------------------------------------------------------------------------------

    private fun accountDir(account: String): File? = accountKey(account)?.let { File(root, it) }

    private fun fileOf(account: String, name: String): File? = accountDir(account)?.let { File(it, name) }

    private fun detailFile(account: String, raGameId: Int): File? =
        if (raGameId <= 0) null else accountDir(account)?.let { File(File(it, DETAIL_DIR), "g$raGameId.json") }

    private fun detailIdOf(name: String): Int? =
        if (name.startsWith("g") && name.endsWith(".json")) name.substring(1, name.length - ".json".length).toIntOrNull() else null

    /** The current file when it reads, else the previous generation, else null. */
    private fun <T> readGeneration(file: File, parse: (String) -> T?): T? =
        readText(file)?.let(parse) ?: readText(File(file.parentFile, file.name + BACKUP_SUFFIX))?.let(parse)

    private fun readText(file: File): String? = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) null else file.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun parseBulkOrNull(file: File): RetroAchievementsLedgerBulk? = readText(file)?.let { parseBulk(it) }

    private fun parseResolutionOrNull(file: File): RetroAchievementsLedgerResolution? = readText(file)?.let { parseResolution(it) }

    private fun parseDetailOrNull(file: File): RetroAchievementsLedgerDetail? = readText(file)?.let { parseDetail(it) }

    /**
     * Write [text] to [file] so a reader sees the old file or the new one, never half of either: a temp file in the same folder, flushed to disk, then renamed
     * over the target. With [keepBackup] the current file first becomes the `.bak` (so a crash between the two renames leaves a readable generation).
     */
    private fun writeGeneration(file: File, text: String, keepBackup: Boolean): RetroAchievementsLedgerWrite = runCatching {
        val dir = file.parentFile ?: return RetroAchievementsLedgerWrite.FAILED
        val bytes = text.toByteArray(Charsets.UTF_8)
        // A file the next start would refuse to read must never replace one it can: past the read limit the write fails here, with nothing touched.
        if (bytes.size > MAX_FILE_BYTES) return RetroAchievementsLedgerWrite.FAILED
        dir.mkdirs()
        val temp = File(dir, file.name + TEMP_SUFFIX)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (keepBackup && file.isFile) moveReplacing(file, File(dir, file.name + BACKUP_SUFFIX))
        moveReplacing(temp, file)
        RetroAchievementsLedgerWrite.WRITTEN
    }.getOrDefault(RetroAchievementsLedgerWrite.FAILED)

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun evictDetailsLocked(dir: File?, keep: String) {
        val files = dir?.listFiles().orEmpty().filter { detailIdOf(it.name) != null }
        if (files.size <= maxDetailGames) return
        files.filter { it.name != keep }
            .sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))
            .take(files.size - maxDetailGames)
            .forEach { runCatching { it.delete() } }
    }

    // Wire format -----------------------------------------------------------------------------------------------------------------------

    private fun encodeBulk(bulk: RetroAchievementsLedgerBulk): String = json.encodeToString(
        BulkDto.serializer(),
        BulkDto(
            savedAt = bulk.savedAtMillis,
            // Written only when true (a null is left out), so a file of a complete read stays byte-for-byte what it always was.
            lane = if (bulk.laneOnly) true else null,
            points = bulk.points,
            // Written only when there is a score (a zero is left out, like a null), so a file of a player with none is byte-for-byte what it always was.
            softcore = bulk.softcorePoints?.takeIf { it > 0 },
            games = bulk.games.map {
                GameDto(
                    it.gameId, it.title, it.consoleName, it.earned, it.total, it.imageIconPath, it.lastAwardedAtMillis,
                    // Written only when there is something to write (a null is left out), so a row without them is byte-for-byte what it always was.
                    hc = it.earnedHardcore.takeIf { hardcore -> hardcore > 0 },
                    awardWord = it.awardKind?.word,
                )
            },
            recent = bulk.recent?.map {
                RecentDto(it.achievementId, it.title, it.description, it.points, it.badgeName, it.gameId, it.gameTitle, it.consoleName, it.awardedAtMillis, it.hardcore)
            },
        ),
    )

    private fun parseBulk(text: String): RetroAchievementsLedgerBulk? = runCatching {
        val dto = json.decodeFromString(BulkDto.serializer(), text)
        if (dto.v != FORMAT_VERSION) return null
        RetroAchievementsLedgerBulk(
            savedAtMillis = dto.savedAt,
            laneOnly = dto.lane == true,
            points = dto.points?.takeIf { it >= 0 },
            softcorePoints = dto.softcore?.takeIf { it >= 0 },
            games = dto.games.mapNotNull { g ->
                if (g.id <= 0 || g.earned < 0 || g.total < 0) {
                    null
                } else {
                    RetroAchievementsUserGame(
                        g.id, g.title, g.console, g.earned, g.total, RetroAchievementsIcons.validatedPath(g.icon), g.awarded,
                        // Inside the earned count by construction, whatever a damaged file says.
                        earnedHardcore = (g.hc ?: 0).coerceIn(0, g.earned),
                        // RA's word; a file written when the award was stored as a number (ordinal + 1) still reads.
                        awardKind = RaAwardKind.fromProvider(g.awardWord) ?: g.award?.let { index -> RaAwardKind.entries.getOrNull(index - 1) },
                    )
                }
            },
            recent = dto.recent?.mapNotNull { r ->
                if (r.id <= 0 || r.game <= 0) {
                    null
                } else {
                    RetroAchievementsRecentUnlock(
                        achievementId = r.id,
                        title = r.title,
                        description = r.desc,
                        points = r.points,
                        badgeName = RetroAchievementsIcons.validatedBadgeName(r.badge),
                        gameId = r.game,
                        gameTitle = r.gameTitle,
                        consoleName = r.console,
                        awardedAtMillis = r.awarded,
                        hardcore = r.hardcore,
                    )
                }
            },
        )
    }.getOrNull()

    private fun sameBulkContent(a: RetroAchievementsLedgerBulk, b: RetroAchievementsLedgerBulk): Boolean =
        a.points == b.points && (a.softcorePoints ?: 0) == (b.softcorePoints ?: 0) && a.games == b.games && a.recent == b.recent && a.laneOnly == b.laneOnly

    // Bounds: what is written is cut to these first, so the encoding is bounded in memory and in size whatever a caller (a restore, a hostile file) hands over.
    // Every field a read validates is validated the same way on the way in, so what is saved equals what a read returns and an identical answer is UNCHANGED.

    private fun String.capped(max: Int): String {
        if (length <= max) return this
        val end = if (max > 0 && Character.isHighSurrogate(this[max - 1])) max - 1 else max
        return substring(0, end)
    }

    private fun RetroAchievementsUserGame.bounded() = copy(
        title = title.capped(MAX_TITLE_CHARS),
        consoleName = consoleName.capped(MAX_CONSOLE_CHARS),
        imageIconPath = RetroAchievementsIcons.validatedPath(imageIconPath),
    )

    private fun RetroAchievementsRecentUnlock.bounded() = copy(
        title = title.capped(MAX_TITLE_CHARS),
        description = description.capped(MAX_DESCRIPTION_CHARS),
        badgeName = RetroAchievementsIcons.validatedBadgeName(badgeName),
        gameTitle = gameTitle.capped(MAX_TITLE_CHARS),
        consoleName = consoleName.capped(MAX_CONSOLE_CHARS),
    )

    private fun RetroAchievementsProgress.bounded() = copy(
        title = title.capped(MAX_TITLE_CHARS),
        achievements = achievements.map {
            it.copy(
                title = it.title.capped(MAX_TITLE_CHARS),
                description = it.description.capped(MAX_DESCRIPTION_CHARS),
                unlocked = it.unlocked || it.unlockedHardcore,
                badgeName = RetroAchievementsIcons.validatedBadgeName(it.badgeName),
                earnedAt = it.earnedAt?.capped(MAX_DATE_CHARS),
                earnedHardcoreAt = it.earnedHardcoreAt?.capped(MAX_DATE_CHARS),
            )
        },
        imageTitlePath = RetroAchievementsIcons.validatedPath(imageTitlePath),
        imageIngamePath = RetroAchievementsIcons.validatedPath(imageIngamePath),
        imageIconPath = RetroAchievementsIcons.validatedPath(imageIconPath),
        consoleName = consoleName?.capped(MAX_CONSOLE_CHARS),
    )

    private fun encodeResolution(resolution: RetroAchievementsLedgerResolution): String = json.encodeToString(
        ResolutionDto.serializer(),
        ResolutionDto(
            verdicts = resolution.verdicts.map { VerdictDto(it.fileKey, it.kind.ordinal, it.raGameId, it.atMillis) },
            links = resolution.localByRaGameId.map { LinkDto(it.key, it.value) },
        ),
    )

    private fun parseResolution(text: String): RetroAchievementsLedgerResolution? = runCatching {
        val dto = json.decodeFromString(ResolutionDto.serializer(), text)
        if (dto.v != FORMAT_VERSION) return null
        RetroAchievementsLedgerResolution(
            verdicts = dto.verdicts.mapNotNull { v ->
                val kind = RetroAchievementsLedgerVerdictKind.entries.getOrNull(v.kind) ?: return@mapNotNull null
                // A match without its game id is no match at all; a non-match carries none.
                val raId = if (kind == RetroAchievementsLedgerVerdictKind.MATCHED) (v.ra?.takeIf { it > 0 } ?: return@mapNotNull null) else null
                if (v.key.isBlank()) null else RetroAchievementsLedgerVerdict(v.key, kind, raId, v.at)
            },
            localByRaGameId = dto.links.filter { it.ra > 0 && it.game.isNotBlank() }.associate { it.ra to it.game },
        )
    }.getOrNull()

    private fun encodeDetail(detail: RetroAchievementsLedgerDetail): String {
        val p = detail.progress
        return json.encodeToString(
            DetailDto.serializer(),
            DetailDto(
                savedAt = detail.savedAtMillis,
                id = p.gameId,
                title = p.title,
                titleImage = p.imageTitlePath,
                ingameImage = p.imageIngamePath,
                iconImage = p.imageIconPath,
                console = p.consoleName,
                achievements = p.achievements.map {
                    AchievementDto(it.id, it.title, it.description, it.points, it.unlocked, it.unlockedHardcore, it.badgeName, it.earnedAt, it.earnedHardcoreAt)
                },
            ),
        )
    }

    private fun parseDetail(text: String): RetroAchievementsLedgerDetail? = runCatching {
        val dto = json.decodeFromString(DetailDto.serializer(), text)
        if (dto.v != FORMAT_VERSION || dto.id <= 0 || dto.achievements.isEmpty()) return null
        RetroAchievementsLedgerDetail(
            progress = RetroAchievementsProgress(
                gameId = dto.id,
                title = dto.title,
                achievements = dto.achievements.map { a ->
                    RetroAchievementsAchievement(
                        id = a.id,
                        title = a.title,
                        description = a.desc,
                        points = a.points,
                        unlocked = a.unlocked || a.hardcore,
                        unlockedHardcore = a.hardcore,
                        badgeName = RetroAchievementsIcons.validatedBadgeName(a.badge),
                        earnedAt = a.earned,
                        earnedHardcoreAt = a.earnedHardcore,
                    )
                },
                imageTitlePath = RetroAchievementsIcons.validatedPath(dto.titleImage),
                imageIngamePath = RetroAchievementsIcons.validatedPath(dto.ingameImage),
                imageIconPath = RetroAchievementsIcons.validatedPath(dto.iconImage),
                consoleName = dto.console,
            ),
            savedAtMillis = dto.savedAt,
        )
    }.getOrNull()

    @Serializable
    internal class BulkDto(
        val v: Int = FORMAT_VERSION,
        val savedAt: Long = 0L,
        /** True only for a file holding per-game rows alone (see [RetroAchievementsLedgerBulk.laneOnly]); absent otherwise. */
        val lane: Boolean? = null,
        val points: Int? = null,
        /** The profile's softcore score; absent in a file written before it was kept. */
        val softcore: Int? = null,
        val games: List<GameDto> = emptyList(),
        val recent: List<RecentDto>? = null,
    )

    @Serializable
    internal class GameDto(
        val id: Int,
        val title: String,
        val console: String,
        val earned: Int,
        val total: Int,
        val icon: String? = null,
        val awarded: Long? = null,
        /** How many of [earned] were earned in hardcore; absent when none (or in a file written before it was kept). */
        val hc: Int? = null,
        /** The RA award as RA words it ("beaten-hardcore", "mastered"); absent when there is none. */
        val awardWord: String? = null,
        /** READ ONLY: an earlier file kept the award as the ordinal of [RaAwardKind] plus one. It is never written any more, and is read when there is no [awardWord]. */
        val award: Int? = null,
    )

    @Serializable
    internal class RecentDto(
        val id: Int,
        val title: String,
        val desc: String,
        val points: Int,
        val badge: String? = null,
        val game: Int,
        val gameTitle: String,
        val console: String,
        val awarded: Long? = null,
        val hardcore: Boolean = false,
    )

    @Serializable
    internal class ResolutionDto(
        val v: Int = FORMAT_VERSION,
        val verdicts: List<VerdictDto> = emptyList(),
        val links: List<LinkDto> = emptyList(),
    )

    @Serializable
    internal class VerdictDto(val key: String, val kind: Int, val ra: Int? = null, val at: Long = 0L)

    @Serializable
    internal class LinkDto(val ra: Int, val game: String)

    @Serializable
    internal class ToastedDto(val v: Int = FORMAT_VERSION, val ids: List<Int> = emptyList())

    @Serializable
    internal class DetailDto(
        val v: Int = FORMAT_VERSION,
        val savedAt: Long = 0L,
        val id: Int,
        val title: String,
        val titleImage: String? = null,
        val ingameImage: String? = null,
        val iconImage: String? = null,
        val console: String? = null,
        val achievements: List<AchievementDto> = emptyList(),
    )

    @Serializable
    internal class AchievementDto(
        val id: Int,
        val title: String,
        val desc: String,
        val points: Int,
        val unlocked: Boolean = false,
        val hardcore: Boolean = false,
        val badge: String? = null,
        val earned: String? = null,
        val earnedHardcore: String? = null,
    )

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val DEFAULT_MAX_DETAIL_GAMES: Int = 256

        /** The most file verdicts and RA-to-library links a resolution keeps (the same bounds as the in-memory caches they persist). */
        const val MAX_VERDICTS: Int = 4096
        const val MAX_LOCAL_LINKS: Int = 512

        /** The most toasted achievement ids kept per account (the newest); far more than a player earns in the life of an install. */
        const val MAX_TOASTED: Int = 2_000

        /**
         * A ledger file past this is not one of ours and is not read, and one that would encode past it is not written. Bounds on what goes in (so the encoding
         * is bounded in memory too): more games than [MAX_BULK_GAMES] or achievements than [MAX_DETAIL_ACHIEVEMENTS] is refused outright (a cut-short list would
         * pose as the whole answer); text is cut to its caps; a recent-unlock window is cut to [MAX_RECENT]. A user's real library is a small fraction of these.
         */
        private const val MAX_FILE_BYTES: Long = 8L * 1024L * 1024L
        const val MAX_BULK_GAMES: Int = 10_000
        const val MAX_RECENT: Int = 100
        const val MAX_DETAIL_ACHIEVEMENTS: Int = 2_000
        const val MAX_TITLE_CHARS: Int = 256
        const val MAX_DESCRIPTION_CHARS: Int = 512
        const val MAX_CONSOLE_CHARS: Int = 64
        const val MAX_DATE_CHARS: Int = 40
        const val MAX_KEY_CHARS: Int = 1024

        private const val BULK = "bulk.json"
        private const val RESOLUTION = "resolution.json"
        private const val TOASTED = "toasted.json"
        private const val DETAIL_DIR = "detail"
        private const val BACKUP_SUFFIX = ".bak"
        private const val TEMP_SUFFIX = ".tmp"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /**
         * The folder name an account's rows live under: 32 hex characters of a SHA-256 over a fixed label and the lower-cased username. One-way, stable across
         * installs and devices (so a restore can find the same folder), and never the username. Null for a blank name.
         */
        fun accountKey(account: String?): String? {
            val name = account?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest("mimir.ra.ledger.v1\u0000$name".toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
