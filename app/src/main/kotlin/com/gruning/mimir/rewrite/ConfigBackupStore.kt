package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleCodec
import com.gruning.mimir.rewrite.core.model.migration.MigrationBundleV1
import com.gruning.mimir.rewrite.core.model.migration.MigrationEmulatorOverride
import com.gruning.mimir.rewrite.core.model.migration.MigrationGameRecord
import com.gruning.mimir.rewrite.core.model.migration.MigrationProfile
import com.gruning.mimir.rewrite.core.model.migration.MigrationSourceRoot
import com.gruning.mimir.rewrite.core.model.migration.MigrationTheme
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import kotlinx.coroutines.flow.first

/**
 * Config backup — the EXPORT half of the M10 migration bundle (the IMPORT half is [MigrationImportOverlay]
 * + `core.resolver.migration`). Reads the rewrite's CURRENT local state and produces a
 * [MigrationBundleV1] — the SAME plain-JSON contract the shipping app writes — which the user saves via SAF
 * and can re-import here or on another device. Metadata only (favorites/hidden/overrides/theme markers +
 * source-folder HINTS); never ROM bytes, BIOS, or anything network-touching. Posture-clean: inspectable,
 * offline, on-device.
 *
 * Reads go DAO-direct through [MimirDatabaseFactory] because the per-file identity evidence a
 * [MigrationGameRecord] needs (fileName/size/hash/serial) lives on raw `GameEntity` rows, which the
 * collapsed `LibraryRepository` read-models don't expose.
 */
object ConfigBackupStore {

    /** Suggested filename for the SAF create-document picker. */
    const val SUGGESTED_FILE_NAME: String = "mimir-backup.json"

    /**
     * Snapshot the current setup into a bundle. Suspends (a handful of one-shot DB reads); call off the
     * main thread. Favorites/hidden/overrides that reference the current library are emitted with the
     * identity evidence the importer re-derives the gameId from.
     */
    suspend fun buildBundle(context: Context): MigrationBundleV1 {
        val db = MimirDatabaseFactory.get(context)
        val gameDao = db.gameDao()
        val platformDao = db.platformDao()
        val overrideDao = db.emulatorOverrideDao()
        val sourceRootDao = db.sourceRootDao()

        // Report-only human platform label ("dreamcast" -> "Dreamcast"); cached to avoid N round-trips.
        val labelCache = HashMap<String, String>()
        suspend fun platformLabel(platformId: String): String =
            labelCache.getOrPut(platformId) { platformDao.byId(platformId)?.displayName ?: platformId }

        // Build a record from the PRIMARY member of a dedup group. fileName MUST be displayName (the value
        // the scan minted the gameId from) — NOT relativePath — or the importer can't re-derive the gameId.
        suspend fun recordFor(gameId: String): MigrationGameRecord? {
            val primary = gameDao.membersOfGame(gameId).firstOrNull() ?: return null
            return MigrationGameRecord(
                platformId = primary.platformId,
                fileName = primary.displayName,
                sizeBytes = primary.sizeBytes,
                relativePath = primary.relativePath,
                contentHash = primary.contentHash,
                serial = primary.serial,
                title = canonicalTitleForBackup(primary),
                platformLabel = platformLabel(primary.platformId),
            )
        }

        val favorites = gameDao.favoriteGameRows().first().mapNotNull { recordFor(it.gameId) }
        val hidden = gameDao.hiddenGameRows().first().mapNotNull { recordFor(it.gameId) }
        val overrides = overrideDao.all().mapNotNull { row ->
            // A blank emulatorId is an explicit "Auto" reset, not an override — never export it.
            if (row.emulatorId.isBlank()) return@mapNotNull null
            recordFor(row.gameId)?.let { MigrationEmulatorOverride(game = it, emulatorId = row.emulatorId) }
        }
        val sourceRoots = sourceRootDao.all().first().map { r ->
            MigrationSourceRoot(friendlyLabel = r.friendlyLabel, treeUriHint = r.rootUri, isPrimary = r.isPrimarySource)
        }

        return MigrationBundleV1(
            producedByApp = "mimir-rewrite",
            producedByVersion = BuildConfig.VERSION_NAME,
            producedAtMillis = System.currentTimeMillis(),
            profile = MigrationProfile(
                localProfileId = "local",
                displayName = ProfileNameStore.displayName.value.ifBlank { null },
            ),
            theme = MigrationTheme(themeId = ThemeStore.themeId.value.storageKey),
            sourceRoots = sourceRoots,
            favorites = favorites,
            hidden = hidden,
            emulatorOverrides = overrides,
            // The achievement counts of the account signed in now (never a key): a restore or a move to another device starts from them, not from zeros.
            achievements = runCatching {
                achievementsForBackup(RetroAchievementsLedgers.forContext(context), RetroAchievementsCredentialStore.connectedUser.value)
            }.getOrDefault(emptyList()),
        )
    }

    /** Encode + write the bundle to a SAF document uri (truncating). Never throws — returns a [Result]. */
    fun writeBundle(context: Context, uri: Uri, bundle: MigrationBundleV1): Result<Unit> = runCatching {
        val json = MigrationBundleCodec.encode(bundle)
        val stream = context.contentResolver.openOutputStream(uri, "w")
            ?: error("Couldn't open the chosen file to write.")
        stream.bufferedWriter().use { it.write(json) }
    }
}

/** Keep an export truthful even if it races the startup repair of a legacy multi-disc stamp. */
internal fun canonicalTitleForBackup(primary: GameEntity): String {
    val raw = primary.canonicalTitle ?: primary.title
    val kind = primary.duplicateGroupKind?.let { stored ->
        DuplicateGroupKind.entries.firstOrNull { it.name == stored }
    }
    return DisplayTitle.canonicalForGroup(raw, kind)
}
