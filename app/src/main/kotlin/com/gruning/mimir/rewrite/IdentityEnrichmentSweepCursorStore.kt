package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.metadata.LibraryIdentityEnricher
import com.gruning.mimir.rewrite.core.storage.SourceIndexGeneration

/**
 * App-private crash cursor for one unresolved-identity sweep. It is policy-bound and contains no ROM bytes
 * or hashes; failure to durably advance is treated as a worker failure, never silently skipped work.
 */
internal object IdentityEnrichmentSweepCursorStore {
    private const val PreferencesName = "mimir_rewrite_identity_sweep"
    private const val StampKey = "policy_stamp"
    private const val SizeKey = "after_size"
    private const val EntryKey = "after_entry"
    private const val GenerationKey = "source_generation"

    data class Checkpoint(
        val cursor: LibraryIdentityEnricher.IdentityEnrichmentCursor,
        val generation: SourceIndexGeneration,
    )

    fun read(
        context: Context,
        policyStamp: String,
        currentGeneration: SourceIndexGeneration,
    ): Checkpoint? {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (preferences.getString(StampKey, null) != policyStamp) {
            check(preferences.edit().clear().commit()) { "failed to clear stale identity sweep cursor" }
            return null
        }
        if (preferences.getString(GenerationKey, null) != currentGeneration.persistenceKey) {
            check(preferences.edit().clear().commit()) {
                "failed to clear source-invalidated identity sweep cursor"
            }
            return null
        }
        val entryKey = preferences.getString(EntryKey, null)?.takeIf(String::isNotBlank) ?: return null
        val sizeBytes = preferences.getLong(SizeKey, -1L).takeIf { it >= 0L } ?: return null
        return Checkpoint(
            cursor = LibraryIdentityEnricher.IdentityEnrichmentCursor(sizeBytes, entryKey),
            generation = currentGeneration,
        )
    }

    fun write(
        context: Context,
        policyStamp: String,
        cursor: LibraryIdentityEnricher.IdentityEnrichmentCursor,
        generation: SourceIndexGeneration,
    ) {
        val committed = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(StampKey, policyStamp)
            .putLong(SizeKey, cursor.sizeBytes)
            .putString(EntryKey, cursor.entryKey)
            .putString(GenerationKey, generation.persistenceKey)
            .commit()
        check(committed) { "failed to persist identity sweep cursor" }
    }

    fun clear(context: Context) {
        val committed = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        check(committed) { "failed to clear identity sweep cursor" }
    }
}
