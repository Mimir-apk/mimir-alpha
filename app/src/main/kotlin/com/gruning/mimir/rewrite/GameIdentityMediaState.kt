package com.gruning.mimir.rewrite

import android.content.Context
import java.security.MessageDigest

/** Durable distinction required by target-wins replay; absence means the user has never ruled. */
internal enum class GameIdentityMediaSelection {
    NEVER_CHOSEN,
    SELECTED,
    AUTOMATIC,
}

internal data class GameIdentityMediaSelectionRecord(
    val selection: GameIdentityMediaSelection,
    val changedAtMillis: Long,
)

/** Newer user intent wins a recurrence; equal generations remain target-owned and idempotent. */
internal fun gameIdentityMediaSourceWins(
    source: GameIdentityMediaSelectionRecord,
    target: GameIdentityMediaSelectionRecord,
): Boolean = source.selection != GameIdentityMediaSelection.NEVER_CHOSEN &&
    (
        target.selection == GameIdentityMediaSelection.NEVER_CHOSEN ||
            source.changedAtMillis > target.changedAtMillis
        )

/** One writer for renderer files, Saved catalogs, and explicit selection tombstones. */
internal object GameIdentityMediaMutationGate {
    private val gate = EnrichSavedMutationGate()

    /**
     * The only production entry point for media mutation outside the rollback owner.
     *
     * A valid interrupted transaction is recovered before the new intent runs. If recovery cannot
     * be proven, [blocked] is returned and canonical renderer/selection state remains untouched.
     */
    suspend fun <T> run(
        context: Context,
        blocked: T,
        block: suspend () -> T,
    ): T = gate.run {
        if (EnrichSavedStore.recoverPendingRendererMutationLocked(context.applicationContext)) {
            block()
        } else {
            blocked
        }
    }

    /**
     * Rollback-owner entry point. Only [EnrichSavedStore] may use this while creating, restoring,
     * or proving its own journal; nesting the guarded entry point would deadlock on the same mutex.
     */
    suspend fun <T> runRollbackOwner(block: suspend () -> T): T = gate.run(block)
}

/**
 * Cross-generation selection intent. AUTOMATIC is a real tombstone, not absence, so a retired winner's
 * media cannot return after the user explicitly cleared the current target.
 */
internal object GameIdentityMediaSelectionStore {
    private const val PreferencesName = "mimir_identity_media_selection"

    fun record(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
    ): GameIdentityMediaSelectionRecord {
        EnrichSavedStore.selectionReadProjection(kind, cacheKey)?.let { return it }
        return canonicalRecord(context, kind, cacheKey)
    }

    /**
     * Canonical authority read for the rollback owner after it has committed a new selection.
     * Render paths must use [record] so an unfinished transaction continues to project prior state.
     */
    internal fun canonicalRecord(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
    ): GameIdentityMediaSelectionRecord {
        val stored = preferences(context).getString(key(kind, cacheKey), null)
        val selectionName = stored?.substringBefore('|')
        val selection = GameIdentityMediaSelection.entries.firstOrNull { it.name == selectionName }
            ?: GameIdentityMediaSelection.NEVER_CHOSEN
        val changedAtMillis = stored?.substringAfter('|', missingDelimiterValue = "")
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        return GameIdentityMediaSelectionRecord(selection, changedAtMillis)
    }

    fun state(context: Context, kind: EnrichMediaKind, cacheKey: String): GameIdentityMediaSelection =
        record(context, kind, cacheKey).selection

    /** Records a new user intent with a process-monotonic timestamp for this slot. */
    fun set(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        selection: GameIdentityMediaSelection,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val previous = canonicalRecord(context, kind, cacheKey)
        val changedAtMillis = maxOf(nowMillis.coerceAtLeast(0L), previous.changedAtMillis + 1L)
        return write(
            context,
            kind,
            cacheKey,
            GameIdentityMediaSelectionRecord(selection, changedAtMillis),
        )
    }

    /** Exact replay/rollback write; unlike [set], this never manufactures a newer intent. */
    fun write(
        context: Context,
        kind: EnrichMediaKind,
        cacheKey: String,
        record: GameIdentityMediaSelectionRecord,
    ): Boolean {
        val editor = preferences(context).edit()
        if (record.selection == GameIdentityMediaSelection.NEVER_CHOSEN) {
            editor.remove(key(kind, cacheKey))
        } else {
            editor.putString(
                key(kind, cacheKey),
                "${record.selection.name}|${record.changedAtMillis.coerceAtLeast(0L)}",
            )
        }
        return editor.commit()
    }

    /** Newer intent wins; ties remain target-owned. Source history is never removed. */
    fun adoptSourceIfNewer(
        context: Context,
        kind: EnrichMediaKind,
        toCacheKey: String,
        source: GameIdentityMediaSelectionRecord,
        target: GameIdentityMediaSelectionRecord,
    ): Boolean {
        val sourceWins = gameIdentityMediaSourceWins(source, target)
        return !sourceWins || write(context, kind, toCacheKey, source)
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    private fun key(kind: EnrichMediaKind, cacheKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.encodeToByteArray())
            .toMimirLowerHex()
        return "${kind.wireName}/${digest.take(24)}"
    }
}

/** Legacy active files/catalogs are treated as selected until their explicit marker is written. */
internal fun effectiveGameIdentityMediaSelectionRecord(
    context: Context,
    kind: EnrichMediaKind,
    cacheKey: String,
): GameIdentityMediaSelectionRecord {
    val explicit = GameIdentityMediaSelectionStore.record(context, kind, cacheKey)
    val catalog = EnrichSavedStore.load(context, kind, cacheKey)
    val renderer = when (kind) {
        EnrichMediaKind.ICON -> SteamGridDbTileFetcher.tileIconFile(context, cacheKey)
        EnrichMediaKind.TOME_PHYSICAL -> TomeCartridgeArtStore.hasOverride(context, cacheKey)
            .let { active -> TomeCartridgeArtStore.activeFile(context, cacheKey).takeIf { active } }
        EnrichMediaKind.COVER -> CustomCoverStore.hasOverride(context, cacheKey)
            .let { active -> CustomCoverStore.activeFile(context, cacheKey).takeIf { active } }
        EnrichMediaKind.HERO -> CustomHeroStore.assetFile(
            context,
            com.gruning.mimir.rewrite.core.model.GameId(cacheKey),
            video = false,
        )
        EnrichMediaKind.CLIP -> CustomHeroStore.assetFile(
            context,
            com.gruning.mimir.rewrite.core.model.GameId(cacheKey),
            video = true,
        )
    }
    val rendererExists = when (renderer) {
        is java.io.File -> renderer.isFile && renderer.length() > 0L
        else -> false
    }
    if (explicit.selection != GameIdentityMediaSelection.NEVER_CHOSEN) {
        return if (explicit.selection == GameIdentityMediaSelection.SELECTED &&
            !rendererExists &&
            catalog.activeAssetId == null
        ) {
            explicit.copy(selection = GameIdentityMediaSelection.AUTOMATIC)
        } else {
            explicit
        }
    }
    if (catalog.selection != GameIdentityMediaSelection.NEVER_CHOSEN) {
        return GameIdentityMediaSelectionRecord(
            catalog.selection,
            catalog.selectionChangedAtMillis,
        )
    }
    return if (rendererExists && renderer is java.io.File) {
        GameIdentityMediaSelectionRecord(
            GameIdentityMediaSelection.SELECTED,
            renderer.lastModified().coerceAtLeast(0L),
        )
    } else {
        GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.NEVER_CHOSEN, 0L)
    }
}
