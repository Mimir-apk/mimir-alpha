package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The remembered per-game destination. Absence means ask on the next Play. */
internal enum class CompanionGameDisplayPreference(val storageValue: String) {
    MAIN("main"),
    BOTTOM("bottom"),
}

internal fun companionGameDisplayPreferenceFromStorage(value: String?): CompanionGameDisplayPreference? = when {
    value.equals(CompanionGameDisplayPreference.MAIN.storageValue, ignoreCase = true) ->
        CompanionGameDisplayPreference.MAIN
    value.equals(CompanionGameDisplayPreference.BOTTOM.storageValue, ignoreCase = true) ->
        CompanionGameDisplayPreference.BOTTOM
    else -> null
}

/** Local GameId-keyed memory; it is deliberately separate from package-keyed Android app preferences. */
internal object CompanionGameDisplayPreferenceStore {
    private const val PREFS = "mimir_rewrite_game_display"
    private const val PREFIX = "game/"
    private val _preferences = MutableStateFlow<Map<String, CompanionGameDisplayPreference>>(emptyMap())
    val preferences: StateFlow<Map<String, CompanionGameDisplayPreference>> = _preferences.asStateFlow()

    fun load(context: Context) {
        _preferences.value = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
            .asSequence()
            .filter { (key, value) -> key.startsWith(PREFIX) && value is String }
            .mapNotNull { (key, value) ->
                companionGameDisplayPreferenceFromStorage(value as String)?.let {
                    key.removePrefix(PREFIX) to it
                }
            }
            .toMap()
    }

    fun preference(gameId: GameId): CompanionGameDisplayPreference? = _preferences.value[gameId.value]

    fun set(context: Context, gameId: GameId, destination: CompanionQuickLaunchDestination) {
        val value = when (destination) {
            CompanionQuickLaunchDestination.MAIN -> CompanionGameDisplayPreference.MAIN
            CompanionQuickLaunchDestination.BOTTOM -> CompanionGameDisplayPreference.BOTTOM
            CompanionQuickLaunchDestination.ASK_NEXT_TIME -> {
                clear(context, gameId)
                return
            }
        }
        val key = PREFIX + gameId.value.trim()
        if (gameId.value.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value.storageValue).apply()
        _preferences.value = _preferences.value + (gameId.value to value)
    }

    fun clear(context: Context, gameId: GameId) {
        if (gameId.value.isBlank()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PREFIX + gameId.value).apply()
        _preferences.value = _preferences.value - gameId.value
    }
}

internal data class CompanionGameDisplayChoice(
    val requestId: Long,
    val gameId: GameId,
    val label: String,
    val editing: Boolean,
)

/** Process-local exact choice state; a stale chooser can never launch after its row disappeared. */
internal object CompanionGameDisplayChoiceState {
    private var nextRequestId = 0L
    private val _pending = MutableStateFlow<CompanionGameDisplayChoice?>(null)
    val pending: StateFlow<CompanionGameDisplayChoice?> = _pending.asStateFlow()

    @Synchronized
    fun request(gameId: GameId, label: String, editing: Boolean) {
        nextRequestId += 1L
        _pending.value = CompanionGameDisplayChoice(nextRequestId, gameId, label, editing)
    }

    @Synchronized
    fun dismiss(choice: CompanionGameDisplayChoice): Boolean {
        if (_pending.value?.requestId != choice.requestId) return false
        _pending.value = null
        return true
    }
}
