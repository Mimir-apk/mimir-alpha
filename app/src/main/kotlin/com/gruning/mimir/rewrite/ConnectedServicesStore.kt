package com.gruning.mimir.rewrite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Provider-neutral consent for connected services. ScreenScraper and RetroAchievements keep independent
 * credentials and health; this flag answers only whether the user has ever explicitly connected a service.
 *
 * Migration is one-shot and conservative. An installed user who had connected ScreenScraper keeps the
 * existing opt-in; everybody else remains off. Merely finding old RA ciphertext never enables network.
 */
object ConnectedServicesStore {
    private const val PREFS = "mimir_rewrite_connected_services"
    private const val KEY_OPTED_IN = "connectedServicesOptedIn"
    private const val KEY_MIGRATED = "connectedServicesMigratedV1"
    private val preferenceKeys = setOf(KEY_OPTED_IN, KEY_MIGRATED)

    private val accessLock = Any()
    private val _optedIn = MutableStateFlow(false)
    val optedIn: StateFlow<Boolean> = _optedIn.asStateFlow()

    fun restore(context: Context, legacyScreenScraperOptedIn: Boolean) {
        synchronized(accessLock) {
            val preferences = prefs(context)
            val migrated = preferences.safeBoolean(KEY_MIGRATED) ?: run {
                _optedIn.value = false
                return
            }
            val stored = preferences.safeBoolean(KEY_OPTED_IN) ?: run {
                _optedIn.value = false
                return
            }
            val resolved = resolveConnectedServicesOptIn(migrated, stored, legacyScreenScraperOptedIn)
            if (migrated) {
                _optedIn.value = resolved
                return
            }

            val persisted = preferences.commitCredentialEdit(preferenceKeys) {
                putBoolean(KEY_OPTED_IN, resolved)
                putBoolean(KEY_MIGRATED, true)
            }
            val verified = persisted &&
                preferences.safeBoolean(KEY_MIGRATED) == true &&
                preferences.safeBoolean(KEY_OPTED_IN) == resolved
            // A failed migration write fails closed; the next process may retry from the legacy source.
            _optedIn.value = resolved && verified
        }
    }

    /**
     * Publish the durable consent without running the one-time migration. For a ScreenScraper record that cannot
     * be read, whose legacy opt-in (the migration's only seed) is therefore unknown: a completed migration is
     * published exactly as stored, and an unmigrated install stays closed and unmigrated, to retry next process.
     * Product review 2026-09-28 (F5): RetroAchievements consent no longer depends on ScreenScraper's record.
     */
    fun restoreWithoutMigration(context: Context) {
        synchronized(accessLock) {
            _optedIn.value = hasDurableOptIn(context)
        }
    }

    /** Called only after one provider has accepted the user's explicit connect action. */
    fun markOptedIn(context: Context): Boolean =
        synchronized(accessLock) {
            val preferences = prefs(context)
            if (
                preferences.safeBoolean(KEY_OPTED_IN) == true &&
                preferences.safeBoolean(KEY_MIGRATED) == true
            ) {
                _optedIn.value = true
                return@synchronized true
            }
            val persisted = preferences.commitCredentialEdit(preferenceKeys) {
                putBoolean(KEY_OPTED_IN, true)
                putBoolean(KEY_MIGRATED, true)
            }
            val verified = persisted &&
                preferences.safeBoolean(KEY_OPTED_IN) == true &&
                preferences.safeBoolean(KEY_MIGRATED) == true
            _optedIn.value = verified
            verified
        }

    fun hasDurableOptIn(context: Context): Boolean =
        synchronized(accessLock) {
            val preferences = prefs(context)
            preferences.safeBoolean(KEY_OPTED_IN) == true &&
                preferences.safeBoolean(KEY_MIGRATED) == true
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.safeBoolean(key: String): Boolean? =
        runCatching { getBoolean(key, false) }.getOrNull()
}

internal fun resolveConnectedServicesOptIn(
    migrationComplete: Boolean,
    storedOptIn: Boolean,
    legacyScreenScraperOptIn: Boolean,
): Boolean = if (migrationComplete) storedOptIn else legacyScreenScraperOptIn

internal fun retroAchievementsGateShouldOpen(
    connectedServicesOptedIn: Boolean,
    hasUsableCredentialsForSession: Boolean,
): Boolean = connectedServicesOptedIn && hasUsableCredentialsForSession
