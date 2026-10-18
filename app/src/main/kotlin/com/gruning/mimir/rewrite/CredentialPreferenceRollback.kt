package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * A redacted snapshot of the exact preference keys participating in one credential-state write.
 *
 * Android's SharedPreferences mutates its live process map before the backing file reports whether the
 * write succeeded. Keeping this snapshot lets a failed write restore the process map so later reads cannot
 * mistake an uncommitted mutation for durable credential or consent truth.
 */
internal class CredentialPreferenceSnapshot(
    val values: Map<String, Any?>,
) {
    override fun toString(): String = "CredentialPreferenceSnapshot(values=***)"
}

internal class CredentialPreferenceRollback(
    private val snapshot: () -> CredentialPreferenceSnapshot,
    private val restore: (CredentialPreferenceSnapshot) -> Unit,
) {
    fun commit(operation: () -> Boolean): Boolean {
        val before = runCatching(snapshot).getOrElse { return false }
        val committed = runCatching(operation).getOrDefault(false)
        if (!committed) runCatching { restore(before) }
        return committed
    }
}

/**
 * Commit one credential/consent edit synchronously and restore the pre-edit process map on false/throw.
 * The caller must still fail closed for the process because restoration does not prove disk durability.
 */
internal fun SharedPreferences.commitCredentialEdit(
    keys: Set<String>,
    mutate: SharedPreferences.Editor.() -> Unit,
): Boolean {
    val rollback = CredentialPreferenceRollback(
        snapshot = {
            val current = all
            CredentialPreferenceSnapshot(
                keys.mapNotNull { key ->
                    if (current.containsKey(key)) key to current[key] else null
                }.toMap(),
            )
        },
        restore = { snapshot -> restoreCredentialSnapshot(keys, snapshot) },
    )
    return rollback.commit {
        edit().apply(mutate).commit()
    }
}

internal fun SharedPreferences.snapshotCredentialValues(
    keys: Set<String>,
): CredentialPreferenceSnapshot? =
    runCatching {
        val current = all
        CredentialPreferenceSnapshot(
            keys.mapNotNull { key ->
                if (current.containsKey(key)) key to current[key] else null
            }.toMap(),
        )
    }.getOrNull()

internal fun SharedPreferences.restoreCredentialValues(
    keys: Set<String>,
    snapshot: CredentialPreferenceSnapshot,
): Boolean {
    val committed = commitCredentialEdit(keys) {
        keys.forEach(::remove)
        snapshot.values.forEach { (key, value) -> putCredentialPreferenceValue(key, value) }
    }
    return committed && credentialValuesMatch(keys, snapshot)
}

internal fun SharedPreferences.credentialValuesMatch(
    keys: Set<String>,
    snapshot: CredentialPreferenceSnapshot,
): Boolean =
    runCatching {
        val current = all
        keys.all { key ->
            val expectedPresent = snapshot.values.containsKey(key)
            val currentPresent = current.containsKey(key)
            expectedPresent == currentPresent &&
                (!expectedPresent || current[key].preferenceValueEquals(snapshot.values[key]))
        }
    }.getOrDefault(false)

@SuppressLint("ApplySharedPref") // commit is intentional: process state must match the durability result.
private fun SharedPreferences.restoreCredentialSnapshot(
    keys: Set<String>,
    snapshot: CredentialPreferenceSnapshot,
) {
    val editor = edit()
    keys.forEach(editor::remove)
    snapshot.values.forEach { (key, value) -> editor.putCredentialPreferenceValue(key, value) }
    // SharedPreferences applies this restoration to its live map before returning. A second false result
    // therefore still repairs process-visible state; the original caller continues to report failure.
    editor.commit()
}

@Suppress("UNCHECKED_CAST")
private fun SharedPreferences.Editor.putCredentialPreferenceValue(
    key: String,
    value: Any?,
) {
    when (value) {
        null -> remove(key)
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> putStringSet(key, (value as Set<String>).toSet())
        else -> error("Unsupported SharedPreferences value")
    }
}

private fun Any?.preferenceValueEquals(other: Any?): Boolean = when {
    this is Set<*> && other is Set<*> -> this == other
    else -> this == other
}
