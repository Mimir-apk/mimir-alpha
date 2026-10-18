package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedger
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsLedgerWrite
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementAccount
import com.gruning.mimir.rewrite.core.model.migration.MigrationAchievementGame
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The process's RetroAchievements ledger over the app's files directory. One instance per folder, so every writer (the achievements lane, a restore) shares one lock
 * and the app never has two objects writing the same files.
 */
internal object RetroAchievementsLedgers {
    const val LEDGER_FOLDER: String = "ra_ledger"

    private val byFolder = ConcurrentHashMap<String, RetroAchievementsLedger>()

    fun forContext(context: Context): RetroAchievementsLedger =
        forFolder(File(context.applicationContext.filesDir, LEDGER_FOLDER))

    fun forFolder(folder: File): RetroAchievementsLedger =
        byFolder.computeIfAbsent(folder.absolutePath) { RetroAchievementsLedger(folder) }
}

/**
 * What a backup carries of the achievement ledger: the account signed in NOW and its saved progress (counts per game with the hardcore count and RA's award word, and both
 * scores, hardcore and softcore). Other accounts' rows are kept on
 * this device but are keyed by a one-way hash, so only the account whose name is known can be named in a file. Empty when nobody is signed in or nothing is saved.
 * Blocking file work: call it off the main thread.
 */
internal fun achievementsForBackup(ledger: RetroAchievementsLedger, account: String?): List<MigrationAchievementAccount> {
    val name = account?.trim()?.takeIf(String::isNotEmpty) ?: return emptyList()
    val saved = ledger.readBulk(name) ?: return emptyList()
    if (saved.games.isEmpty()) return emptyList()
    return listOf(
        MigrationAchievementAccount(
            username = name,
            points = saved.points,
            softcorePoints = saved.softcorePoints,
            games = saved.games.map { game ->
                MigrationAchievementGame(
                    raGameId = game.gameId,
                    title = game.title,
                    consoleName = game.consoleName,
                    earned = game.earned,
                    total = game.total,
                    lastAwardedAtMillis = game.lastAwardedAtMillis,
                    iconPath = game.imageIconPath,
                    earnedHardcore = game.earnedHardcore,
                    awardWord = game.awardKind?.word,
                )
            },
        ),
    )
}

/**
 * Keep a backup's achievement progress for each account in it, so the account has its counts the moment it signs in on this device. ADDITIVE and idempotent:
 * an account the device already holds a complete read for is left exactly as it is (what the device read itself is newer than a file), so importing twice, or onto a
 * device that has already read, changes nothing. An account whose saved list holds only what per-game reads put there (the Bottom's opened games) is MERGED instead:
 * those rows win for the games they name, the backup adds the rest and its scores. What is taken is what the backup holds: both scores, each game's hardcore count and award. The check and the write are ONE step under the ledger's lock
 * ([RetroAchievementsLedger.insertBulkIfAbsent]), so a live read that saves while an import runs always wins. Returns how many games were taken. Blocking file
 * work: call it off the main thread.
 */
internal fun restoreAchievementsFromBackup(
    ledger: RetroAchievementsLedger,
    accounts: List<MigrationAchievementAccount>,
    nowMillis: Long,
): Int {
    var taken = 0
    for (account in accounts) {
        if (account.username.isBlank() || account.games.isEmpty()) continue
        val games = account.games.map { game ->
            RetroAchievementsUserGame(
                gameId = game.raGameId,
                title = game.title,
                consoleName = game.consoleName,
                earned = game.earned,
                total = game.total,
                imageIconPath = game.iconPath,
                lastAwardedAtMillis = game.lastAwardedAtMillis,
                earnedHardcore = game.earnedHardcore.coerceIn(0, maxOf(0, game.earned)),
                // RA's word as the backup holds it; one this app does not know is no award, never a guess.
                awardKind = RaAwardKind.fromProvider(game.awardWord),
            )
        }
        if (ledger.insertBulkIfAbsent(account.username, games, account.points, nowMillis, softcorePoints = account.softcorePoints) == RetroAchievementsLedgerWrite.WRITTEN) {
            taken += games.size
        }
    }
    return taken
}
