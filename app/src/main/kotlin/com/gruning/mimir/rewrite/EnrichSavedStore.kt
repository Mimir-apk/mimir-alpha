package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.model.GameId
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Presentation-only media lanes exposed by Home's Customize Media picker. */
internal enum class EnrichMediaKind(val wireName: String, val capacity: Int) {
    ICON("icon", 6),
    /**
     * Explicit physical-media art. It is no longer Tomes-only. The former description named only
     * the Library list and a launch card opened there: two of six surfaces, with the card derived
     * from its origin. C1 removed that origin-dependent resolution at `64d00e47`.
     *
     * As of 2026-08-26: a pick of this kind is the game's face wherever a face is painted — the Home tile,
     * the Library list row and carousel card, Tome tiles and wells — and the launch card resolves its own
     * state, which may hold a different pick. C4: a pick outranks every automatic tier under it.
     *
     * The wire name is DELIBERATELY THE OLD NAME (R3-M, policy 2026-08-25). It keys the player's saved choices in
     * [EnrichSavedStore]; renaming it orphans every one of them, silently. The ENUM CONSTANT may be
     * renamed freely -- that is compiler-checked -- but the string may not.
     */
    TOME_PHYSICAL("tome-physical", 6),
    HERO("hero", 5),
    CLIP("clip", 3),

    /**
     * The game's COVER — the face it wears in the gamelist and on its tile.
     *
     * Every menu that exposes media lanes includes a Cover lane. Previously, the cover was AUTOMATIC-ONLY.
     *
     * Why it had to exist rather than being a nicety: every title the provider carries also publishes
     * `box-2D`, and `ArtMediaResolver.BOX_TYPE_PRIORITY` is `box-2D → box-3D → box-texture`, so **a 3D box
     * is unreachable automatically in the reference corpus.** A picker is the only route to one.
     */
    COVER("cover", 6),
}

internal enum class EnrichSavedSource(val wireName: String, val label: String) {
    STEAMGRIDDB("sgdb", "SteamGridDB"),
    SCREENSCRAPER("ss", "ScreenScraper"),
    UPLOAD("up", "Uploaded"),
    LEGACY("leg", "Existing selection"),
}

/**
 * One offline-reusable custom-media choice. Remote URLs are deliberately absent: ScreenScraper URLs
 * contain credentials, while provider identity is represented by [providerRefHash].
 */
internal data class EnrichSavedAsset(
    val assetId: String,
    val source: EnrichSavedSource,
    val label: String,
    val localPath: String,
    val previewPath: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val durationMillis: Long? = null,
    val addedAt: Long,
    val providerRefHash: String? = null,
    /** Truthful CLIP payload type; old catalogs decode clips as VIDEO. */
    val mediaType: CustomHeroMediaType? = null,
    /**
     * The provider's own media type for this asset -- `box-3d`, `support-2d`, and so on -- or null.
     *
     * Added 2026-08-25 to close a real gap: since C1 the FACE picker offers both the box family and the
     * physical article in one grid, and both save through [EnrichMediaKind.COVER]. Without this, apply had
     * no way to know which KIND he actually chose, so a disc was recorded as a cover and painted in a
     * portrait lane instead of the square one -- right picture, wrong shape.
     *
     * Two shortcuts were rejected: [assetId] is a content hash and carries no type, and parsing [label]
     * would mean a copy change silently re-shaping his art.
     *
     * **Defaulted null so old catalogs decode exactly as before and there is no migration.**
     */
    val providerMediaType: String? = null,
)

internal data class EnrichSavedCatalog(
    val activeAssetId: String?,
    val entries: List<EnrichSavedAsset>,
    val selection: GameIdentityMediaSelection = if (activeAssetId == null) {
        GameIdentityMediaSelection.NEVER_CHOSEN
    } else {
        GameIdentityMediaSelection.SELECTED
    },
    val selectionChangedAtMillis: Long = 0L,
) {
    /** Reopen order is current first, then newest. The active choice never moves during an open session. */
    fun orderedForOpen(): List<EnrichSavedAsset> = entries.sortedWith(
        compareByDescending<EnrichSavedAsset> { it.assetId == activeAssetId }
            .thenByDescending(EnrichSavedAsset::addedAt),
    )
}

internal sealed interface EnrichSaveOutcome {
    data class Saved(val asset: EnrichSavedAsset, val catalog: EnrichSavedCatalog) : EnrichSaveOutcome
    data object Full : EnrichSaveOutcome
    data object Failed : EnrichSaveOutcome
}

internal sealed interface EnrichLegacyMigrationOutcome {
    data class Ready(val catalog: EnrichSavedCatalog) : EnrichLegacyMigrationOutcome
    data class Failed(val catalog: EnrichSavedCatalog) : EnrichLegacyMigrationOutcome
}

/** Prior renderer projection held while a durable activation rollback remains unresolved. */
internal sealed interface EnrichRendererReadProjection {
    data object Automatic : EnrichRendererReadProjection
    data class Override(
        val file: File,
        val iconMarker: String?,
    ) : EnrichRendererReadProjection
}

/** One process-wide writer gate; SharedPreferences strings are atomic, compound read/modify/write is not. */
internal class EnrichSavedMutationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Bounded, per-game/per-media durable Saved gallery. The legacy render files remain product-facing;
 * this store owns only the offline catalog and promotes a chosen local file through their atomic seams.
 */
internal object EnrichSavedStore {
    private const val LogTag = "MimirEnrichSaved"
    private const val PrefsName = "mimir_enrich_saved"
    private const val RootName = "enrich"
    private const val PendingRendererRecoveryKey = "pending-renderer-recovery"
    private val mutationGate = GameIdentityMediaMutationGate
    @Volatile private var activeRendererRecoveryId: String? = null
    @Volatile private var pendingRendererRecovery: RendererRecovery? = null

    fun load(context: Context, kind: EnrichMediaKind, cacheKey: String): EnrichSavedCatalog {
        pendingRendererRecovery
            ?.takeIf { it.kind == kind && it.cacheKey == cacheKey }
            ?.let { return it.priorCatalog }
        return loadUnlocked(context, kind, cacheKey)
    }

    /** Process-start barrier: no renderer may observe a half-finished activation from a prior process. */
    suspend fun recoverInterruptedRendererMutation(context: Context): Boolean =
        mutationGate.runRollbackOwner {
            recoverPendingRendererMutationLocked(context.applicationContext)
        }

    /**
     * Renderer read barrier. A pending transaction projects the durable prior bytes (or Automatic)
     * until all renderer/selection/catalog authorities have been restored and the journal is cleared.
     */
    fun rendererReadProjection(
        kind: EnrichMediaKind,
        cacheKey: String,
    ): EnrichRendererReadProjection? {
        val recovery = pendingRendererRecovery
            ?.takeIf { it.kind == kind && it.cacheKey == cacheKey }
            ?: return null
        val backup = recovery.snapshot.backup
        return if (recovery.snapshot.hadOverride && backup?.let(::valid) == true) {
            EnrichRendererReadProjection.Override(backup, recovery.snapshot.iconMarker)
        } else {
            EnrichRendererReadProjection.Automatic
        }
    }

    fun selectionReadProjection(
        kind: EnrichMediaKind,
        cacheKey: String,
    ): GameIdentityMediaSelectionRecord? = pendingRendererRecovery
        ?.takeIf { it.kind == kind && it.cacheKey == cacheKey }
        ?.priorSelection

    /**
     * Debug-device proof hook: leaves a real pre-mutation journal after mutating all three authorities,
     * then drops only process memory to emulate death. Production/release callers cannot enter it.
     */
    internal suspend fun seedInterruptedRendererMutationForTest(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
    ): Boolean {
        if (!BuildConfig.DEBUG) return false
        return withContext(Dispatchers.IO) {
            mutationGate.runRollbackOwner {
                if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                    return@runRollbackOwner false
                }
                val current = loadUnlocked(context, kind, cacheKey)
                val snapshot = snapshotRenderer(context, kind, cacheKey)
                    ?: return@runRollbackOwner false
                val priorSelection = GameIdentityMediaSelectionStore.record(context, kind, cacheKey)
                val recovery = beginRendererRecovery(
                    context = context,
                    kind = kind,
                    cacheKey = cacheKey,
                    snapshot = snapshot,
                    priorSelection = priorSelection,
                    priorCatalog = current,
                ) ?: return@runRollbackOwner false
                if (!GameIdentityMediaSelectionStore.set(
                        context,
                        kind,
                        cacheKey,
                        GameIdentityMediaSelection.AUTOMATIC,
                    )
                ) {
                    rollbackRendererRecovery(context, recovery)
                    activeRendererRecoveryId = null
                    return@runRollbackOwner false
                }
                if (!clearRenderer(context, kind, cacheKey) ||
                    !persist(
                        context.applicationContext,
                        kind,
                        cacheKey,
                        current.copy(
                            activeAssetId = null,
                            selection = GameIdentityMediaSelection.AUTOMATIC,
                            selectionChangedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                ) {
                    rollbackRendererRecovery(context, recovery)
                    activeRendererRecoveryId = null
                    return@runRollbackOwner false
                }
                activeRendererRecoveryId = null
                pendingRendererRecovery = null
                true
            }
        }
    }

    private fun loadUnlocked(context: Context, kind: EnrichMediaKind, cacheKey: String): EnrichSavedCatalog {
        val application = context.applicationContext
        val key = indexKey(kind, cacheKey)
        val raw = application.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .getString(key, null)
            ?: return EnrichSavedCatalog(activeAssetId = null, entries = emptyList())
        val decoded = decodeCatalog(raw)
        val surviving = decoded.entries.filter { entry ->
            val full = File(entry.localPath)
            val previewSurvives = kind == EnrichMediaKind.CLIP && entry.previewPath.isBlank() ||
                File(entry.previewPath).let { it.isFile && it.length() > 0L }
            full.isFile && full.length() > 0L && previewSurvives
        }
        val active = decoded.activeAssetId?.takeIf { id -> surviving.any { it.assetId == id } }
        val selection = if (decoded.selection == GameIdentityMediaSelection.SELECTED && active == null) {
            // A missing selected file must not make a retired winner authoritative again.
            GameIdentityMediaSelection.AUTOMATIC
        } else {
            decoded.selection
        }
        return EnrichSavedCatalog(
            activeAssetId = active,
            entries = surviving,
            selection = selection,
            selectionChangedAtMillis = decoded.selectionChangedAtMillis,
        )
    }

    /** Union a retired identity's offline gallery into the current winner; no source entry/file is removed. */
    suspend fun remapCacheKey(
        context: Context,
        kind: EnrichMediaKind,
        fromCacheKey: String,
        toCacheKey: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationGate.runRollbackOwner {
            if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                return@runRollbackOwner false
            }
            remapCacheKeyLocked(context, kind, fromCacheKey, toCacheKey)
        }
    }

    /** Caller must hold [GameIdentityMediaMutationGate]. */
    internal fun remapCacheKeyLocked(
        context: Context,
        kind: EnrichMediaKind,
        fromCacheKey: String,
        toCacheKey: String,
    ): Boolean {
        if (fromCacheKey == toCacheKey) return true
        val source = loadUnlocked(context, kind, fromCacheKey)
        if (source.entries.isEmpty() &&
            source.activeAssetId == null &&
            source.selection == GameIdentityMediaSelection.NEVER_CHOSEN
        ) {
            return true
        }
        val target = loadUnlocked(context, kind, toCacheKey)
        val merged = mergeEnrichSavedCatalog(kind, target, source)
        return merged == target || persist(context.applicationContext, kind, toCacheKey, merged)
    }

    suspend fun save(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        source: EnrichSavedSource,
        label: String,
        fullAsset: File,
        previewAsset: File? = fullAsset,
        allowMissingPreview: Boolean = false,
        width: Int = 0,
        height: Int = 0,
        durationMillis: Long? = null,
        mediaType: CustomHeroMediaType? = null,
        providerReference: String? = null,
        providerMediaType: String? = null,
        activate: Boolean,
        now: Long = System.currentTimeMillis(),
    ): EnrichSaveOutcome = withContext(Dispatchers.IO) {
        mutationGate.runRollbackOwner {
            if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                return@runRollbackOwner EnrichSaveOutcome.Failed
            }
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            saveLocked(
                context = context,
                kind = kind,
                cacheKey = currentCacheKey,
                source = source,
                label = label,
                fullAsset = fullAsset,
                previewAsset = previewAsset,
                allowMissingPreview = allowMissingPreview,
                width = width,
                height = height,
                durationMillis = durationMillis,
                mediaType = mediaType,
                providerReference = providerReference,
                providerMediaType = providerMediaType,
                activate = activate,
                now = now,
            )
        }
    }

    private fun saveLocked(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        source: EnrichSavedSource,
        label: String,
        fullAsset: File,
        previewAsset: File?,
        allowMissingPreview: Boolean,
        width: Int,
        height: Int,
        durationMillis: Long?,
        mediaType: CustomHeroMediaType?,
        providerReference: String?,
        providerMediaType: String?,
        activate: Boolean,
        now: Long,
        selectionChangedAtMillis: Long = now,
    ): EnrichSaveOutcome {
        if (!valid(fullAsset) || (!allowMissingPreview && previewAsset?.let(::valid) != true)) {
            return EnrichSaveOutcome.Failed
        }
        val application = context.applicationContext
        val contentHash = sha256(fullAsset) ?: return EnrichSaveOutcome.Failed
        val assetId = "${source.wireName}-${contentHash.take(16)}"
        val current = loadUnlocked(application, kind, cacheKey)
        val existing = current.entries.firstOrNull { it.assetId == assetId }
        if (existing != null) {
            val next = current.copy(
                activeAssetId = assetId.takeIf { activate } ?: current.activeAssetId,
                selection = if (activate) {
                    GameIdentityMediaSelection.SELECTED
                } else {
                    current.selection
                },
                selectionChangedAtMillis = if (activate) {
                    selectionChangedAtMillis
                } else {
                    current.selectionChangedAtMillis
                },
            )
            if (!persist(application, kind, cacheKey, next)) return EnrichSaveOutcome.Failed
            return EnrichSaveOutcome.Saved(existing, next)
        }

        val mutable = current.entries.toMutableList()
        var evicted: EnrichSavedAsset? = null
        if (mutable.size >= kind.capacity) {
            val evict = enrichSavedEvictionCandidate(mutable, current.activeAssetId)
                ?: return EnrichSaveOutcome.Full
            mutable.removeAll { it.assetId == evict.assetId }
            evicted = evict
        }

        val directory = catalogDirectory(application, kind, cacheKey).apply { mkdirs() }
        val fullTarget = File(directory, "$assetId.${fullExtension(kind, mediaType)}")
        val previewTarget = when {
            kind != EnrichMediaKind.CLIP -> fullTarget
            previewAsset?.let(::valid) == true -> File(directory, "$assetId.poster.jpg")
            mediaType == CustomHeroMediaType.ANIMATED_IMAGE -> fullTarget
            else -> null
        }
        if (!copyAtomically(
                fullAsset,
                fullTarget,
                mediaType?.let(::customHeroMaxAssetBytes) ?: CustomHeroStore.MaxAssetBytes,
            )
        ) {
            return EnrichSaveOutcome.Failed
        }
        if (previewTarget != null && previewTarget != fullTarget &&
            !copyAtomically(previewAsset!!, previewTarget, CustomHeroStore.MaxPreviewBytes)
        ) {
            fullTarget.delete()
            return EnrichSaveOutcome.Failed
        }
        val record = EnrichSavedAsset(
            assetId = assetId,
            source = source,
            label = label,
            localPath = fullTarget.absolutePath,
            previewPath = previewTarget?.absolutePath.orEmpty(),
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            bytes = fullTarget.length(),
            durationMillis = durationMillis?.coerceAtLeast(0L),
            addedAt = now,
            providerRefHash = providerReference?.let(::enrichProviderReferenceHash),
            mediaType = mediaType,
            providerMediaType = providerMediaType,
        )
        mutable += record
        val next = EnrichSavedCatalog(
            activeAssetId = assetId.takeIf { activate } ?: current.activeAssetId,
            entries = mutable,
            selection = if (activate) {
                GameIdentityMediaSelection.SELECTED
            } else {
                current.selection
            },
            selectionChangedAtMillis = if (activate) {
                selectionChangedAtMillis
            } else {
                current.selectionChangedAtMillis
            },
        )
        if (!persist(application, kind, cacheKey, next)) {
            fullTarget.delete()
            if (previewTarget != null && previewTarget != fullTarget) previewTarget.delete()
            return EnrichSaveOutcome.Failed
        }
        evicted?.let {
            File(it.localPath).delete()
            if (it.previewPath.isNotBlank() && it.previewPath != it.localPath) File(it.previewPath).delete()
        }
        return EnrichSaveOutcome.Saved(record, next)
    }

    suspend fun remove(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        assetId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationGate.runRollbackOwner {
            if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                return@runRollbackOwner false
            }
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            val current = loadUnlocked(context, kind, currentCacheKey)
            if (current.activeAssetId == assetId) return@runRollbackOwner false
            val removed = current.entries.firstOrNull { it.assetId == assetId }
                ?: return@runRollbackOwner false
            val next = current.copy(entries = current.entries.filterNot { it.assetId == assetId })
            if (!persist(context.applicationContext, kind, currentCacheKey, next)) {
                return@runRollbackOwner false
            }
            File(removed.localPath).delete()
            if (removed.previewPath.isNotBlank() && removed.previewPath != removed.localPath) {
                File(removed.previewPath).delete()
            }
            true
        }
    }

    suspend fun activate(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        assetId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationGate.runRollbackOwner {
            if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                return@runRollbackOwner false
            }
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            val current = loadUnlocked(context, kind, currentCacheKey)
            val asset = current.entries.firstOrNull { it.assetId == assetId }
                ?: return@runRollbackOwner false
            val snapshot = snapshotRenderer(context, kind, currentCacheKey)
                ?: return@runRollbackOwner false
            val priorSelection = GameIdentityMediaSelectionStore.record(context, kind, currentCacheKey)
            val recovery = beginRendererRecovery(
                context = context,
                kind = kind,
                cacheKey = currentCacheKey,
                snapshot = snapshot,
                priorSelection = priorSelection,
                priorCatalog = current,
            ) ?: return@runRollbackOwner false
            if (!GameIdentityMediaSelectionStore.set(
                    context,
                    kind,
                    currentCacheKey,
                    GameIdentityMediaSelection.SELECTED,
                )
            ) {
                rollbackRendererRecovery(context, recovery)
                activeRendererRecoveryId = null
                return@runRollbackOwner false
            }
            val selectedIntent = GameIdentityMediaSelectionStore.canonicalRecord(
                context,
                kind,
                currentCacheKey,
            )
            var sealed = false
            // The face record is the one authority this transaction writes that the renderer journal does not
            // restore, so a failed activation puts it back itself.
            val priorFaceRecord = WiredFaceStore.recorded(context, currentCacheKey, MediaState.SHELF)
            var faceRecordWritten = false
            try {
                val promoted = promoteRenderer(
                    context = context,
                    kind = kind,
                    cacheKey = currentCacheKey,
                    source = File(asset.localPath),
                    marker = "saved:$assetId",
                    uploaded = asset.source == EnrichSavedSource.UPLOAD,
                    mediaType = asset.mediaType,
                )
                if (!promoted) return@runRollbackOwner false
                // C5-F: record WHICH kind of picture is now the face, so "replace" means replace. The
                // face picker offers boxes and the physical article in one grid and saves both through
                // COVER, so without this a disc was recorded as a cover and drawn in a portrait lane
                // instead of the square one -- right picture, wrong shape. A null provider type (an
                // upload, or a legacy file adopted from disk) leaves the record alone and falls back to
                // C5-F2's store-consult, which is what shipped before this existed.
                //
                // One lane, one kind (2026-10-10, "Icon art" accepts anything and the shape follows the pick):
                //  - ICON is the SQUARE lane -- the file is in `sgdb2/`, the only place a SQUARE record is honoured.
                //    A PLATFORM tile shares this lane and has no face to record.
                //  - COVER is the box lane. Its record is the box or the disc by provider type; a square grid saved
                //    here before the lanes merged keeps the PHYSICAL record it always had ([coverLaneFaceKind]).
                //  - TOME_PHYSICAL is the article lane: the file is in `tome-cartridge-art/`.
                val faceKind: WiredFaceKind? = when (kind) {
                    EnrichMediaKind.COVER -> {
                        // An UPLOAD, or a legacy file adopted from disk, has no provider type to read a kind
                        // from. That used to mean no record at all -- harmless for the Shelf, which infers a
                        // kind from which stores hold picks (C5-F2), and SILENT DATA LOSS for the Launch
                        // card, whose resolve() returns null unless a record exists. An uploaded launch-card
                        // pick was written to disk, never read back, and the card carried on showing the
                        // shelf's face as though nothing had been chosen.
                        //
                        // COVER is the honest fallback rather than a guess: whatever the picture depicts, the
                        // file lands in CustomCoverStore, which is the store the COVER record points at. The
                        // cost is that an uploaded photo OF a disc gets a cover's portrait geometry -- wrong
                        // shape, right picture, and recoverable by re-picking. Losing the pick is not.
                        coverLaneFaceKind(wiredFaceKindOf(asset.providerMediaType) ?: WiredFaceKind.COVER)
                    }
                    EnrichMediaKind.ICON ->
                        WiredFaceKind.SQUARE.takeUnless { SteamGridDbTileFetcher.isPlatformKey(currentCacheKey) }
                    EnrichMediaKind.TOME_PHYSICAL -> WiredFaceKind.PHYSICAL
                    EnrichMediaKind.HERO, EnrichMediaKind.CLIP -> null
                }
                if (faceKind != null) {
                    WiredFaceStore.record(
                        context = context,
                        // `currentCacheKey` is ALREADY state-scoped -- MimirAppCoordinator applies
                        // mediaStorageKey when it opens the picker, so a launch-card pick arrives here
                        // as `launch-card#<id>`. Passing SHELF is therefore not a bug but the identity
                        // step: it writes the key verbatim, which is exactly the key
                        // `recorded(<id>, LAUNCH_CARD)` reads back. Pinned, because it reads like one.
                        cacheKey = currentCacheKey,
                        state = MediaState.SHELF,
                        kind = faceKind,
                    )
                    faceRecordWritten = true
                }
                if (persist(
                        context.applicationContext,
                        kind,
                        currentCacheKey,
                        current.copy(
                            activeAssetId = assetId,
                            selection = GameIdentityMediaSelection.SELECTED,
                            selectionChangedAtMillis = selectedIntent.changedAtMillis,
                        ),
                    )
                ) {
                    if (!clearRendererRecovery(context, recovery)) {
                        return@runRollbackOwner false
                    }
                    sealed = true
                    true
                } else {
                    false
                }
            } finally {
                if (!sealed && faceRecordWritten) {
                    if (priorFaceRecord != null) {
                        WiredFaceStore.record(context, currentCacheKey, MediaState.SHELF, priorFaceRecord)
                    } else {
                        WiredFaceStore.clear(context, currentCacheKey, MediaState.SHELF)
                    }
                }
                if (!sealed && !rollbackRendererRecovery(context, recovery)) {
                    Log.e(
                        LogTag,
                        "activation rollback remains journaled kind=${kind.wireName}",
                    )
                }
                activeRendererRecoveryId = null
            }
        }
    }

    /** Clear the active renderer and catalog marker as one rollback-safe local transaction. */
    suspend fun activateAutomatic(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
    ): Boolean = withContext(Dispatchers.IO) {
        mutationGate.runRollbackOwner {
            if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                return@runRollbackOwner false
            }
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            val current = loadUnlocked(context, kind, currentCacheKey)
            val snapshot = snapshotRenderer(context, kind, currentCacheKey)
                ?: return@runRollbackOwner false
            val priorSelection = GameIdentityMediaSelectionStore.record(context, kind, currentCacheKey)
            val recovery = beginRendererRecovery(
                context = context,
                kind = kind,
                cacheKey = currentCacheKey,
                snapshot = snapshot,
                priorSelection = priorSelection,
                priorCatalog = current,
            ) ?: return@runRollbackOwner false
            if (!GameIdentityMediaSelectionStore.set(
                    context,
                    kind,
                    currentCacheKey,
                    GameIdentityMediaSelection.AUTOMATIC,
                )
            ) {
                rollbackRendererRecovery(context, recovery)
                activeRendererRecoveryId = null
                return@runRollbackOwner false
            }
            val automaticIntent = GameIdentityMediaSelectionStore.canonicalRecord(
                context,
                kind,
                currentCacheKey,
            )
            var sealed = false
            try {
                if (!clearRenderer(context, kind, currentCacheKey)) {
                    return@runRollbackOwner false
                }
                if (persist(
                        context.applicationContext,
                        kind,
                        currentCacheKey,
                        current.copy(
                            activeAssetId = null,
                            selection = GameIdentityMediaSelection.AUTOMATIC,
                            selectionChangedAtMillis = automaticIntent.changedAtMillis,
                        ),
                    )
                ) {
                    if (!clearRendererRecovery(context, recovery)) {
                        return@runRollbackOwner false
                    }
                    sealed = true
                    true
                } else {
                    false
                }
            } finally {
                if (!sealed && !rollbackRendererRecovery(context, recovery)) {
                    Log.e(
                        LogTag,
                        "automatic rollback remains journaled kind=${kind.wireName}",
                    )
                }
                activeRendererRecoveryId = null
            }
        }
    }

    /**
     * When an adopted file is treated as having been picked. A lane whose marker already says SELECTED keeps its OWN time, whatever it
     * is: the time of the pick as he made it, or 0 where the marker is the old format that carried none (a pick with no time is read
     * as "unknown" by the shelf, and the icon wins over an unknown, so stamping it with a file's mtime would invent an order the shelf
     * had never seen). That time is never capped. Only a time made up here is: with no marker the catalog's time stands when it has one,
     * else the file's modification time, and that is never later than [notAfterMillis], the time of the pick the shelf paints right
     * now. The shelf orders his picks by these times, so an adoption that stamped a file with its mtime (a re-copied cover, a
     * regenerated article) could make an old pick look newer than one he made since, and merely opening Customize media would change
     * the face on the shelf.
     */
    private fun adoptedPickTimeMillis(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        catalog: EnrichSavedCatalog,
        file: File,
        notAfterMillis: Long?,
    ): Long {
        val marker = GameIdentityMediaSelectionStore.record(context, kind, cacheKey)
        if (marker.selection == GameIdentityMediaSelection.SELECTED) return marker.changedAtMillis.coerceAtLeast(0L)
        catalog.selectionChangedAtMillis.takeIf { it > 0L }?.let { return it }
        val at = file.lastModified().coerceAtLeast(0L)
        return if (notAfterMillis != null) minOf(at, notAfterMillis.coerceAtLeast(0L)) else at
    }

    suspend fun migrateLegacy(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        legacyActive: File?,
        retainedUpload: File?,
        activeWasUpload: Boolean,
        /** The time of the pick the shelf paints now; no adopted pick is stamped later than it. Null where nothing orders picks (the single-lane pickers). */
        notAfterMillis: Long? = null,
    ): EnrichLegacyMigrationOutcome = withContext(Dispatchers.IO) {
        mutationGate.runRollbackOwner transaction@{
            if (!recoverPendingRendererMutationLocked(context.applicationContext)) {
                return@transaction EnrichLegacyMigrationOutcome.Failed(
                    load(context, kind, cacheKey),
                )
            }
            val currentCacheKey = GameIdentityAliasResolver.resolve(context, GameId(cacheKey)).value
            var catalog = loadUnlocked(context, kind, currentCacheKey)
            if (catalog.selection == GameIdentityMediaSelection.NEVER_CHOSEN &&
                catalog.activeAssetId == null &&
                legacyActive?.let(::valid) == true
            ) {
                val generatedPoster = if (kind == EnrichMediaKind.CLIP) {
                    File(
                        context.cacheDir,
                        "enrich-migration/${indexKey(kind, currentCacheKey)}.jpg",
                    ).also { poster ->
                        poster.parentFile?.mkdirs()
                        poster.delete()
                    }.takeIf { poster -> CustomHeroFetcher.createClipPoster(legacyActive, poster) }
                } else {
                    null
                }
                val outcome = saveLocked(
                    context = context,
                    kind = kind,
                    cacheKey = currentCacheKey,
                    source = if (activeWasUpload) EnrichSavedSource.UPLOAD else EnrichSavedSource.LEGACY,
                    label = if (activeWasUpload) "Uploaded image" else "Existing selection",
                    fullAsset = legacyActive,
                    previewAsset = generatedPoster ?: legacyActive.takeUnless { kind == EnrichMediaKind.CLIP },
                    allowMissingPreview = kind == EnrichMediaKind.CLIP && generatedPoster == null,
                    width = 0,
                    height = 0,
                    durationMillis = null,
                    mediaType = if (kind == EnrichMediaKind.CLIP) {
                        CustomHeroFetcher.detectSavedMediaType(legacyActive)
                    } else {
                        null
                    },
                    providerReference = null,
                    // A legacy file adopted from disk: no provider type was ever recorded for it.
                    providerMediaType = null,
                    activate = true,
                    now = System.currentTimeMillis(),
                    selectionChangedAtMillis = adoptedPickTimeMillis(context, kind, currentCacheKey, catalog, legacyActive, notAfterMillis),
                )
                generatedPoster?.delete()
                if (outcome is EnrichSaveOutcome.Saved) {
                    catalog = outcome.catalog
                    if (!GameIdentityMediaSelectionStore.write(
                            context,
                            kind,
                            currentCacheKey,
                            GameIdentityMediaSelectionRecord(
                                GameIdentityMediaSelection.SELECTED,
                                catalog.selectionChangedAtMillis,
                            ),
                        )
                    ) {
                        return@transaction EnrichLegacyMigrationOutcome.Failed(catalog)
                    }
                } else {
                    return@transaction EnrichLegacyMigrationOutcome.Failed(catalog)
                }
            }
            if (retainedUpload?.let(::valid) == true &&
                catalog.entries.none { it.source == EnrichSavedSource.UPLOAD }
            ) {
                val outcome = saveLocked(
                    context = context,
                    kind = kind,
                    cacheKey = currentCacheKey,
                    source = EnrichSavedSource.UPLOAD,
                    label = "Uploaded image",
                    fullAsset = retainedUpload,
                    previewAsset = retainedUpload,
                    allowMissingPreview = false,
                    width = 0,
                    height = 0,
                    durationMillis = null,
                    mediaType = null,
                    providerReference = null,
                    // An uploaded image is his own file, not a provider asset -- it has no provider type.
                    providerMediaType = null,
                    activate = activeWasUpload,
                    now = System.currentTimeMillis(),
                    selectionChangedAtMillis = adoptedPickTimeMillis(context, kind, currentCacheKey, catalog, retainedUpload, notAfterMillis),
                )
                if (outcome is EnrichSaveOutcome.Saved) {
                    catalog = outcome.catalog
                    if (activeWasUpload &&
                        !GameIdentityMediaSelectionStore.write(
                            context,
                            kind,
                            currentCacheKey,
                            GameIdentityMediaSelectionRecord(
                                GameIdentityMediaSelection.SELECTED,
                                catalog.selectionChangedAtMillis,
                            ),
                        )
                    ) {
                        return@transaction EnrichLegacyMigrationOutcome.Failed(catalog)
                    }
                } else {
                    return@transaction EnrichLegacyMigrationOutcome.Failed(catalog)
                }
            }
            EnrichLegacyMigrationOutcome.Ready(catalog)
        }
    }

    private fun persist(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        catalog: EnrichSavedCatalog,
    ): Boolean = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        .edit()
        .putString(indexKey(kind, cacheKey), encodeCatalog(catalog))
        .commit()

    private data class RendererSnapshot(
        val hadOverride: Boolean,
        val backup: File?,
        val uploaded: Boolean,
        val iconMarker: String?,
    )

    private data class RendererRecovery(
        val id: String,
        val kind: EnrichMediaKind,
        val cacheKey: String,
        val snapshot: RendererSnapshot,
        val priorSelection: GameIdentityMediaSelectionRecord,
        val priorCatalog: EnrichSavedCatalog,
    )

    /**
     * Publish rollback intent before touching renderer, selection, or catalog. The durable backup and
     * journal survive process death; process startup consumes an unfinished rollback before rendering.
     */
    private fun beginRendererRecovery(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        snapshot: RendererSnapshot,
        priorSelection: GameIdentityMediaSelectionRecord,
        priorCatalog: EnrichSavedCatalog,
    ): RendererRecovery? {
        val recovery = RendererRecovery(
            id = System.nanoTime().toULong().toString(16),
            kind = kind,
            cacheKey = cacheKey,
            snapshot = snapshot,
            priorSelection = priorSelection,
            priorCatalog = priorCatalog,
        )
        activeRendererRecoveryId = recovery.id
        val stored = context.applicationContext
            .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(PendingRendererRecoveryKey, encodeRendererRecovery(recovery))
            .commit()
        if (!stored) {
            activeRendererRecoveryId = null
            snapshot.backup?.delete()
            return null
        }
        pendingRendererRecovery = recovery
        return recovery
    }

    /** Restore all three authorities; the journal is removed only after every readback-safe write wins. */
    private suspend fun rollbackRendererRecovery(context: Context, recovery: RendererRecovery): Boolean {
        val rendererRestored = restoreRenderer(
            context,
            recovery.kind,
            recovery.cacheKey,
            recovery.snapshot,
        )
        val selectionRestored = GameIdentityMediaSelectionStore.write(
            context,
            recovery.kind,
            recovery.cacheKey,
            recovery.priorSelection,
        )
        val catalogRestored = persist(
            context.applicationContext,
            recovery.kind,
            recovery.cacheKey,
            recovery.priorCatalog,
        )
        return rendererRestored && selectionRestored && catalogRestored &&
            clearRendererRecovery(context, recovery)
    }

    /** Seal either a successful transaction or a fully restored rollback. */
    private fun clearRendererRecovery(context: Context, recovery: RendererRecovery): Boolean {
        val preferences = context.applicationContext
            .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        val stored = preferences.getString(PendingRendererRecoveryKey, null)
        val storedId = stored?.let(::decodeRendererRecovery)?.id
        if (storedId != recovery.id) return false
        val removed = preferences.edit().remove(PendingRendererRecoveryKey).commit()
        if (removed) {
            if (pendingRendererRecovery?.id == recovery.id) pendingRendererRecovery = null
            recovery.snapshot.backup?.delete()
        }
        return removed
    }

    /** Cold/read-path consumer for a transaction interrupted before success or complete rollback. */
    /** Caller must hold [GameIdentityMediaMutationGate]. */
    internal suspend fun recoverPendingRendererMutationLocked(context: Context): Boolean {
        val raw = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            .getString(PendingRendererRecoveryKey, null)
        if (raw == null) {
            pendingRendererRecovery = null
            return true
        }
        val recovery = decodeRendererRecovery(raw)
        if (recovery == null) {
            Log.e(LogTag, "renderer recovery journal is unreadable; preserving it for diagnosis")
            return false
        }
        pendingRendererRecovery = recovery
        if (activeRendererRecoveryId == recovery.id) return true
        if (!rollbackRendererRecovery(context, recovery)) {
            Log.e(
                LogTag,
                "cold renderer rollback remains journaled kind=${recovery.kind.wireName}",
            )
            return false
        }
        return true
    }

    private fun encodeRendererRecovery(recovery: RendererRecovery): String = JSONObject().apply {
        put("v", 1)
        put("id", recovery.id)
        put("kind", recovery.kind.wireName)
        put("cacheKey", recovery.cacheKey)
        put("hadOverride", recovery.snapshot.hadOverride)
        put("backup", recovery.snapshot.backup?.absolutePath.orEmpty())
        put("uploaded", recovery.snapshot.uploaded)
        recovery.snapshot.iconMarker?.let { put("iconMarker", it) }
        put("selection", recovery.priorSelection.selection.name)
        put("selectionAt", recovery.priorSelection.changedAtMillis)
        put("catalog", encodeCatalog(recovery.priorCatalog))
    }.toString()

    private fun decodeRendererRecovery(raw: String): RendererRecovery? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("v") != 1) return@runCatching null
        val id = root.optString("id").takeIf(String::isNotBlank) ?: return@runCatching null
        val kind = EnrichMediaKind.entries.firstOrNull { it.wireName == root.optString("kind") }
            ?: return@runCatching null
        val cacheKey = root.optString("cacheKey").takeIf(String::isNotBlank)
            ?: return@runCatching null
        val hadOverride = root.optBoolean("hadOverride")
        val backup = root.optString("backup")
            .takeIf(String::isNotBlank)
            ?.let(::File)
        if (hadOverride && backup?.let(::valid) != true) return@runCatching null
        val selection = GameIdentityMediaSelection.entries.firstOrNull {
            it.name == root.optString("selection")
        } ?: return@runCatching null
        RendererRecovery(
            id = id,
            kind = kind,
            cacheKey = cacheKey,
            snapshot = RendererSnapshot(
                hadOverride = hadOverride,
                backup = backup,
                uploaded = root.optBoolean("uploaded"),
                iconMarker = root.optString("iconMarker").takeIf(String::isNotBlank),
            ),
            priorSelection = GameIdentityMediaSelectionRecord(
                selection = selection,
                changedAtMillis = root.optLong("selectionAt").coerceAtLeast(0L),
            ),
            priorCatalog = decodeCatalog(root.getString("catalog")),
        )
    }.getOrNull()

    private fun snapshotRenderer(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
    ): RendererSnapshot? {
        val gameId = GameId(cacheKey)
        val hadOverride = when (kind) {
            EnrichMediaKind.ICON -> SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey).let(::valid)
            EnrichMediaKind.TOME_PHYSICAL -> TomeCartridgeArtStore.hasCanonicalOverride(context, cacheKey)
            EnrichMediaKind.HERO -> CustomHeroStore.hasCanonicalOverride(context, gameId)
            EnrichMediaKind.CLIP -> CustomHeroStore.hasCanonicalPreviewClipOverride(context, gameId)
            EnrichMediaKind.COVER -> CustomCoverStore.hasCanonicalOverride(context, cacheKey)
        }
        if (!hadOverride) return RendererSnapshot(false, null, false, null)
        val source = when (kind) {
            EnrichMediaKind.ICON -> SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey)
            EnrichMediaKind.TOME_PHYSICAL -> TomeCartridgeArtStore.activeFile(context, cacheKey)
            EnrichMediaKind.HERO -> CustomHeroStore.assetFile(context, gameId, video = false)
            EnrichMediaKind.CLIP -> CustomHeroStore.assetFile(context, gameId, video = true)
            EnrichMediaKind.COVER -> CustomCoverStore.activeFile(context, cacheKey)
        }
        if (!valid(source)) return null
        val backup = File(
            File(context.filesDir, "enrich-rollback").apply { mkdirs() },
            "${kind.wireName}-${System.nanoTime().toULong().toString(16)}.rollback.${fullExtension(kind)}",
        )
        if (!copyAtomically(
                source,
                backup,
                if (kind == EnrichMediaKind.CLIP) {
                    CustomHeroStore.MaxMotionAssetBytes
                } else {
                    CustomHeroStore.MaxAssetBytes
                },
            )
        ) {
            return null
        }
        return RendererSnapshot(
            hadOverride = true,
            backup = backup,
            uploaded = when (kind) {
                EnrichMediaKind.ICON -> SteamGridDbTileFetcher.uploadedPickActive(context, cacheKey)
                EnrichMediaKind.TOME_PHYSICAL -> false
                EnrichMediaKind.HERO -> CustomHeroStore.uploadedHeroActive(context, gameId)
                EnrichMediaKind.CLIP -> false
                // A cover pick is always provider art chosen in the picker; there is no upload lane for it.
                EnrichMediaKind.COVER -> false
            },
            iconMarker = if (kind == EnrichMediaKind.ICON) {
                SteamGridDbTileFetcher.activePickMarker(context, cacheKey)
            } else {
                null
            },
        )
    }

    private suspend fun promoteRenderer(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        source: File,
        marker: String?,
        uploaded: Boolean,
        mediaType: CustomHeroMediaType? = null,
    ): Boolean = when (kind) {
        EnrichMediaKind.ICON -> SteamGridDbTileFetcher.activateLocalPick(
            context = context,
            cacheKey = cacheKey,
            source = source,
            marker = if (uploaded) "upload" else marker,
        )
        EnrichMediaKind.TOME_PHYSICAL -> TomeCartridgeArtStore.activate(
            context = context,
            cacheKey = cacheKey,
            source = source,
        )
        EnrichMediaKind.COVER -> CustomCoverStore.activate(
            context = context,
            cacheKey = cacheKey,
            source = source,
        )
        EnrichMediaKind.HERO -> CustomHeroStore.activateLocalAsset(
            context = context,
            gameId = GameId(cacheKey),
            source = source,
            video = false,
            uploaded = uploaded,
        )
        EnrichMediaKind.CLIP -> CustomHeroStore.activateLocalAsset(
            context = context,
            gameId = GameId(cacheKey),
            source = source,
            video = true,
            uploaded = false,
            mediaType = mediaType ?: CustomHeroFetcher.detectSavedMediaType(source),
        )
    }

    private fun clearRenderer(context: Context, kind: EnrichMediaKind, cacheKey: String): Boolean {
        val gameId = GameId(cacheKey)
        return when (kind) {
            EnrichMediaKind.ICON -> {
                val active = SteamGridDbTileFetcher.canonicalTileIconFile(context, cacheKey)
                // Always clear so an orphaned legacy `.idx` marker cannot survive Automatic.
                SteamGridDbTileFetcher.clearPick(context, cacheKey)
                !active.exists()
            }
            EnrichMediaKind.TOME_PHYSICAL -> TomeCartridgeArtStore.clear(context, cacheKey)
            // Clearing drops the PICK only. The ROM's own cover is untouched -- this store never wrote it,
            // which is the whole reason the pick lives here instead of in the cover slot.
            EnrichMediaKind.COVER -> CustomCoverStore.clear(context, cacheKey)
            EnrichMediaKind.HERO -> if (CustomHeroStore.hasCanonicalOverride(context, gameId)) {
                CustomHeroStore.clear(context, gameId) &&
                    !CustomHeroStore.hasCanonicalOverride(context, gameId)
            } else {
                true
            }
            EnrichMediaKind.CLIP -> if (CustomHeroStore.hasCanonicalPreviewClipOverride(context, gameId)) {
                CustomHeroStore.clearPreviewClip(context, gameId) &&
                    !CustomHeroStore.hasCanonicalPreviewClipOverride(context, gameId)
            } else {
                true
            }
        }
    }

    private suspend fun restoreRenderer(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        snapshot: RendererSnapshot,
    ): Boolean = if (snapshot.hadOverride) {
        snapshot.backup?.let { backup ->
            promoteRenderer(
                context = context,
                kind = kind,
                cacheKey = cacheKey,
                source = backup,
                marker = snapshot.iconMarker,
                uploaded = snapshot.uploaded,
                mediaType = null,
            )
        } == true
    } else {
        clearRenderer(context, kind, cacheKey)
    }

    private fun catalogDirectory(context: Context, kind: EnrichMediaKind, cacheKey: String): File =
        File(File(File(context.filesDir, RootName), kind.wireName), sha256(cacheKey.encodeToByteArray()).take(24))

    private fun indexKey(kind: EnrichMediaKind, cacheKey: String): String =
        "${kind.wireName}/${sha256(cacheKey.encodeToByteArray()).take(24)}"

    private fun fullExtension(
        kind: EnrichMediaKind,
        mediaType: CustomHeroMediaType? = null,
    ): String = when (kind) {
        EnrichMediaKind.ICON -> "png"
        EnrichMediaKind.TOME_PHYSICAL -> "png"
        EnrichMediaKind.HERO -> "img"
        EnrichMediaKind.CLIP -> if (mediaType == CustomHeroMediaType.ANIMATED_IMAGE) "webp" else "mp4"
        EnrichMediaKind.COVER -> "png"
    }

    private fun valid(file: File): Boolean = file.isFile && file.length() > 0L

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun copyAtomically(source: File, target: File, maxBytes: Long): Boolean {
        if (!valid(source) || source.length() > maxBytes) return false
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.delete()
        return try {
            source.inputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!valid(temp) || temp.length() > maxBytes) {
                temp.delete()
                false
            } else {
                if (target.exists() && !target.delete()) {
                    temp.delete()
                    false
                } else if (temp.renameTo(target)) {
                    true
                } else {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                    valid(target)
                }
            }
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }
}

/**
 * Target choices win, source choices fill remaining capacity, and an adopted source active choice is
 * guaranteed a slot. Overflow stays recoverable in the retired source catalog; remap never deletes it.
 */
internal fun mergeEnrichSavedCatalog(
    kind: EnrichMediaKind,
    target: EnrichSavedCatalog,
    source: EnrichSavedCatalog,
): EnrichSavedCatalog {
    val sourceWins = gameIdentityMediaSourceWins(
        source = GameIdentityMediaSelectionRecord(
            source.selection,
            source.selectionChangedAtMillis,
        ),
        target = GameIdentityMediaSelectionRecord(
            target.selection,
            target.selectionChangedAtMillis,
        ),
    )
    val selection = if (sourceWins) source.selection else target.selection
    val selectionChangedAtMillis = if (sourceWins) {
        source.selectionChangedAtMillis
    } else {
        target.selectionChangedAtMillis
    }
    val activeAssetId = if (sourceWins) source.activeAssetId else target.activeAssetId
    val sourceById = source.entries.associateBy(EnrichSavedAsset::assetId)
    val candidates = buildList {
        addAll(target.entries)
        source.activeAssetId?.let(sourceById::get)?.let(::add)
        addAll(source.entries.sortedByDescending(EnrichSavedAsset::addedAt))
    }.distinctBy(EnrichSavedAsset::assetId)

    val retained = candidates.take(kind.capacity).toMutableList()
    val active = activeAssetId?.let { id -> candidates.firstOrNull { it.assetId == id } }
    if (active != null && retained.none { it.assetId == active.assetId }) {
        if (retained.size < kind.capacity) retained += active
        else if (retained.isNotEmpty()) retained[retained.lastIndex] = active
    }
    return EnrichSavedCatalog(
        activeAssetId = activeAssetId?.takeIf { id -> retained.any { it.assetId == id } },
        entries = retained.distinctBy(EnrichSavedAsset::assetId),
        selection = selection,
        selectionChangedAtMillis = selectionChangedAtMillis,
    )
}

/**
 * Cheap first-frame bridge for art selected before the Saved catalog existed. It references only local
 * bytes and is replaced by [migrateLegacy]'s content-addressed record without blocking picker entry.
 */
internal fun enrichCatalogWithProvisionalLocal(
    catalog: EnrichSavedCatalog,
    kind: EnrichMediaKind,
    legacyActive: File?,
    retainedUpload: File?,
    activeWasUpload: Boolean,
): EnrichSavedCatalog {
    val entries = catalog.entries.toMutableList()
    var active = catalog.activeAssetId
    var selection = catalog.selection
    var selectionChangedAtMillis = catalog.selectionChangedAtMillis

    fun add(file: File, source: EnrichSavedSource, label: String, makeActive: Boolean) {
        if (!file.isFile || file.length() <= 0L || entries.any { it.localPath == file.absolutePath }) return
        val id = "provisional-${source.wireName}-${enrichProviderReferenceHash(file.absolutePath).take(12)}"
        entries += EnrichSavedAsset(
            assetId = id,
            source = source,
            label = label,
            localPath = file.absolutePath,
            // A video file is never passed to the still-image renderer. Migration derives its poster off-thread.
            previewPath = file.absolutePath.takeUnless { kind == EnrichMediaKind.CLIP }.orEmpty(),
            width = 0,
            height = 0,
            bytes = file.length(),
            addedAt = file.lastModified().coerceAtLeast(0L),
        )
        if (makeActive && active == null && selection == GameIdentityMediaSelection.NEVER_CHOSEN) {
            active = id
            selection = GameIdentityMediaSelection.SELECTED
            selectionChangedAtMillis = file.lastModified().coerceAtLeast(0L)
        }
    }

    legacyActive
        ?.takeIf { active == null && selection == GameIdentityMediaSelection.NEVER_CHOSEN }
        ?.let {
        add(
            file = it,
            source = if (activeWasUpload) EnrichSavedSource.UPLOAD else EnrichSavedSource.LEGACY,
            label = if (activeWasUpload) "Uploaded image" else "Existing selection",
            makeActive = true,
        )
    }
    retainedUpload?.takeIf { entries.none { entry -> entry.source == EnrichSavedSource.UPLOAD } }?.let {
        add(it, EnrichSavedSource.UPLOAD, "Uploaded image", makeActive = activeWasUpload)
    }
    return EnrichSavedCatalog(
        activeAssetId = active,
        entries = entries,
        selection = selection,
        selectionChangedAtMillis = selectionChangedAtMillis,
    )
}

/** Safe comparison identity for provider results; only this hash may cross the persistence seam. */
internal fun enrichProviderReferenceHash(value: String): String = MessageDigest.getInstance("SHA-256")
    // Signed media URLs carry credentials/resize parameters in the query. The provider path is the
    // durable asset identity; stripping the query also keeps credential rotation from duplicating Saved.
    .digest(value.substringBefore('?').substringBefore('#').encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
    .take(24)

/** Oldest non-active provider/legacy entry; uploads and the active asset are never auto-evicted. */
internal fun enrichSavedEvictionCandidate(
    entries: List<EnrichSavedAsset>,
    activeAssetId: String?,
): EnrichSavedAsset? = entries
    .asSequence()
    .filter { it.assetId != activeAssetId && it.source != EnrichSavedSource.UPLOAD }
    .minByOrNull(EnrichSavedAsset::addedAt)

internal fun encodeCatalog(catalog: EnrichSavedCatalog): String = JSONObject().apply {
    put("v", 4)
    put("active", catalog.activeAssetId ?: "auto")
    put("selection", catalog.selection.name)
    put("selectionAt", catalog.selectionChangedAtMillis.coerceAtLeast(0L))
    put("entries", JSONArray().apply {
        catalog.entries.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.assetId)
                put("src", entry.source.wireName)
                put("label", entry.label)
                put("path", entry.localPath)
                put("preview", entry.previewPath)
                put("w", entry.width)
                put("h", entry.height)
                put("bytes", entry.bytes)
                entry.durationMillis?.let { put("duration", it) }
                put("added", entry.addedAt)
                entry.providerRefHash?.let { put("ref", it) }
                entry.providerMediaType?.let { put("ptype", it) }
                entry.mediaType?.let { put("mediaType", it.name) }
            })
        }
    })
}.toString()

internal fun decodeCatalog(raw: String): EnrichSavedCatalog = runCatching {
    val root = JSONObject(raw)
    val version = root.optInt("v")
    if (version !in 1..4) return@runCatching EnrichSavedCatalog(null, emptyList())
    val entries = root.optJSONArray("entries") ?: JSONArray()
    val decoded = (0 until entries.length()).mapNotNull { index ->
        val item = entries.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val source = EnrichSavedSource.entries.firstOrNull { it.wireName == item.optString("src") }
            ?: return@mapNotNull null
        val path = item.optString("path").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val preview = if (item.has("preview")) item.optString("preview") else path
        EnrichSavedAsset(
            assetId = id,
            source = source,
            label = item.optString("label").ifBlank { source.label },
            localPath = path,
            previewPath = preview,
            width = item.optInt("w").coerceAtLeast(0),
            height = item.optInt("h").coerceAtLeast(0),
            bytes = item.optLong("bytes").coerceAtLeast(0L),
            durationMillis = item.optLong("duration").takeIf { item.has("duration") },
            addedAt = item.optLong("added"),
            providerRefHash = item.optString("ref").takeIf(String::isNotBlank),
            // Absent in every catalog written before 2026-08-25, and null is the correct answer for
            // those: it falls back to the behaviour that shipped this morning rather than guessing.
            providerMediaType = item.optString("ptype").takeIf(String::isNotBlank),
            mediaType = if (version >= 4) {
                CustomHeroMediaType.entries.firstOrNull {
                    it.name == item.optString("mediaType")
                }
            } else if (path.endsWith(".mp4", ignoreCase = true)) {
                CustomHeroMediaType.VIDEO
            } else {
                null
            },
        )
    }.distinctBy(EnrichSavedAsset::assetId)
    val active = root.optString("active").takeUnless { it.isBlank() || it == "auto" }
    val selection = if (version >= 2) {
        GameIdentityMediaSelection.entries.firstOrNull {
            it.name == root.optString("selection")
        } ?: if (active == null) {
            GameIdentityMediaSelection.NEVER_CHOSEN
        } else {
            GameIdentityMediaSelection.SELECTED
        }
    } else if (active == null) {
        GameIdentityMediaSelection.NEVER_CHOSEN
    } else {
        GameIdentityMediaSelection.SELECTED
    }
    EnrichSavedCatalog(
        activeAssetId = active,
        entries = decoded,
        selection = selection,
        selectionChangedAtMillis = if (version >= 3) {
            root.optLong("selectionAt").coerceAtLeast(0L)
        } else {
            0L
        },
    )
}.getOrDefault(EnrichSavedCatalog(activeAssetId = null, entries = emptyList()))
