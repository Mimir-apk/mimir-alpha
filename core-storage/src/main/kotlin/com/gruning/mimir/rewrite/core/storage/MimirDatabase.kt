package com.gruning.mimir.rewrite.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.gruning.mimir.rewrite.core.storage.dao.BiosProfileDao
import com.gruning.mimir.rewrite.core.storage.dao.EmulatorOverrideDao
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameIdentityDao
import com.gruning.mimir.rewrite.core.storage.dao.HomeShelfDao
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.dao.LaunchProofDao
import com.gruning.mimir.rewrite.core.storage.dao.PlatformDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceReconcileDao
import com.gruning.mimir.rewrite.core.storage.dao.TitleMetadataCacheDao
import com.gruning.mimir.rewrite.core.storage.entity.BiosProfileEntity
import com.gruning.mimir.rewrite.core.storage.entity.EmulatorOverrideEntity
import com.gruning.mimir.rewrite.core.storage.entity.FavoriteEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import com.gruning.mimir.rewrite.core.storage.entity.GameIdAliasEntity
import com.gruning.mimir.rewrite.core.storage.entity.HomeShelfItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import com.gruning.mimir.rewrite.core.storage.entity.IdentityMaintenanceEntity
import com.gruning.mimir.rewrite.core.storage.entity.LaunchProofEntity
import com.gruning.mimir.rewrite.core.storage.entity.LogicalGameGroupEntity
import com.gruning.mimir.rewrite.core.storage.entity.PlatformEntity
import com.gruning.mimir.rewrite.core.storage.entity.RemapJournalEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRootEntity
import com.gruning.mimir.rewrite.core.storage.entity.SourceReconcileRunEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleMetadataCacheEntity
import com.gruning.mimir.rewrite.core.storage.entity.TitleOverrideEntity

/**
 * The Mimir Alpha local database. A single local SQLite file — NO network, NO cloud sync in the
 * foundation (posture manifest). Schema is exported to `core-storage/schemas` (KSP arg) so every
 * migration is auditable.
 *
 * The stable on-disk name is `mimir-rewrite.db`, retained for existing installations.
 * The database *builder* (context, name,
 * migrations) is left to the app/DI layer — this module only declares the schema so it stays free of
 * an Android `Context` dependency at the type level.
 */
@Database(
    entities = [
        GameEntity::class,
        PlatformEntity::class,
        SourceRootEntity::class,
        HiddenItemEntity::class,
        EmulatorOverrideEntity::class,
        FavoriteEntity::class,
        HomeShelfItemEntity::class,
        LaunchProofEntity::class,
        BiosProfileEntity::class,
        TitleMetadataCacheEntity::class,
        LogicalGameGroupEntity::class,
        GameIdAliasEntity::class,
        RemapJournalEntity::class,
        IdentityMaintenanceEntity::class,
        SourceReconcileRunEntity::class,
        SourceReconcileRootEntity::class,
        TitleOverrideEntity::class,
    ],
    // v2: title_metadata_cache enrichment cols. v3: games.sourceDocumentUri (open a SAF game on demand).
    // v4: games identity-enrichment cols (canonicalTitle / collapseKey / identityOrigin / resolvedAt).
    // v5: favorites table (user-marked favorites; M9 gamelist affordances).
    // v6: typed, profile-scoped primary Home Shelf membership.
    // v7: explicit Android-package targets in launch proofs (gameId becomes nullable).
    // v8: identity-sweep index. v9: durable logical groups, old-id aliases, and replay journal.
    // v10: crash-resumable, per-root-atomic multi-source reconciliation ledger.
    // v11: injective physical resident keys and exact, non-null source ownership.
    // v12: emulator-scoped BIOS presence keyed by (platformId, emulatorScopeKey, fileName).
    // v13: title_overrides table — manual cosmetic rename , keyed on gameId like
    // emulator_overrides so it survives collapse/disc-swap and is never touched by a scan/enrichment write.
    version = 14,
    exportSchema = true,
)
abstract class MimirDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao
    abstract fun platformDao(): PlatformDao
    abstract fun sourceRootDao(): SourceRootDao
    abstract fun hiddenItemDao(): HiddenItemDao
    abstract fun emulatorOverrideDao(): EmulatorOverrideDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun homeShelfDao(): HomeShelfDao
    abstract fun launchProofDao(): LaunchProofDao
    abstract fun biosProfileDao(): BiosProfileDao
    abstract fun titleMetadataCacheDao(): TitleMetadataCacheDao
    abstract fun gameIdentityDao(): GameIdentityDao
    abstract fun sourceReconcileDao(): SourceReconcileDao

    companion object {
        /** Recommended on-disk name; side-by-side-safe (distinct from com.gruning.mimir). */
        const val DB_NAME = "mimir-rewrite.db"
    }
}
