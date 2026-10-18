package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchLook
import com.gruning.mimir.rewrite.feature.library.CompanionShelfDensity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The order the player filed a platform's games in, ON SCREEN 2.
 *
 * The companion's LATTICE already had an order — a full drag machine with transactions, page-turns
 * and rollback — but the platform shelf had none. This store supplies the platform shelf's order.
 *
 * **Screen 2 owns its own arrangement, and that is the existing model rather than a new one.**
 * [CompanionOrderStore] already holds a companion-only order for the lattice: the player files their tiles
 * on the panel they are holding, and screen 1 is not consulted. This is the same fact one level down. It
 * deliberately does NOT reorder the primary's gamelist — that surface is sorted, controller-driven and
 * shared, and quietly rewriting its order from a drag on the other screen would unexpectedly change
 * a separate surface.
 *
 * Shape and rules copied from [CompanionOrderStore] on purpose:
 * - **A joined string, never a StringSet.** SharedPreferences StringSets are unordered and order is the
 *   entire point.
 * - **Entries are never pruned.** A game that vanishes — a source unmounted, a scan pending — keeps its
 *   slot, so when it comes back it returns to the cell the player filed it in rather than to the end.
 *   Spatial memory survives the library breathing.
 * - Ids are joined by a unit separator and platform records by a record separator, neither of which can
 *   occur in a game id or a platform id.
 */
object CompanionShelfGameOrderStore {
    private const val Prefs = "mimir_companion_shelf"
    private const val KeyGameOrder = "companionShelfGameOrder.v1"
    private const val KeyDensity = "companionShelfDensity.v1"
    private const val KeyLook = "companionLaunchLook.v1"
    private const val Separator = '\u001F'
    private const val RecordSeparator = '\u001E'

    private val mutableOrder = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** Filed order per platform id. A platform absent here has never been arranged; producer order stands. */
    val order: StateFlow<Map<String, List<String>>> = mutableOrder.asStateFlow()

    private val mutableDensity = MutableStateFlow(CompanionShelfDensity.Default)

    /**
     * How many games a shelf page holds (slice L4): ONE global step for every platform, picked with the header's - / + stepper. It lives in this
     * store's prefs, so it survives a restart and a data wipe takes it back to [CompanionShelfDensity.Default]; a missing or unknown stored word reads as that default.
     */
    val density: StateFlow<CompanionShelfDensity> = mutableDensity.asStateFlow()

    private val mutableLook = MutableStateFlow(CompanionLaunchLook.Default)

    /**
     * The launch card's look (slices L5 and L6): ONE global choice for every game and every place the card opens (the shelf and the Activity page), made with the
     * card's own "Look" switch. It lives in this store's prefs beside the density, so it survives a restart and a data wipe takes it back to
     * [CompanionLaunchLook.Default] (Cinematic); a missing or unknown stored word reads as that default.
     */
    val look: StateFlow<CompanionLaunchLook> = mutableLook.asStateFlow()

    fun load(context: Context) {
        val prefs = prefs(context)
        mutableOrder.value = decodeCompanionShelfGameOrder(prefs.getString(KeyGameOrder, null).orEmpty())
        mutableDensity.value = CompanionShelfDensity.fromStorageKey(prefs.getString(KeyDensity, null))
        mutableLook.value = CompanionLaunchLook.fromStorageKey(prefs.getString(KeyLook, null))
    }

    /** Write the step. Idempotent: a step already held writes nothing. */
    fun setDensity(context: Context, density: CompanionShelfDensity) {
        if (mutableDensity.value == density) return
        mutableDensity.value = density
        prefs(context).edit().putString(KeyDensity, density.storageKey).apply()
    }

    /** Write the look. Idempotent: a look already held writes nothing, so Cinematic (the default) is never written until it is left. */
    fun setLook(context: Context, look: CompanionLaunchLook) {
        if (mutableLook.value == look) return
        mutableLook.value = look
        prefs(context).edit().putString(KeyLook, look.storageKey).apply()
    }

    /**
     * File [gameIds] as the order for [platformIdValue].
     *
     * Written synchronously-then-published like the sibling stores: the value the shelf reads back must be
     * the one the finger just produced, and a drop that repainted from a stale flow would snap the tile
     * back to where it was lifted from for one frame.
     */
    fun setOrder(context: Context, platformIdValue: String, gameIds: List<String>) {
        if (platformIdValue.isBlank()) return
        val next = mutableOrder.value + (platformIdValue to gameIds)
        prefs(context).edit().putString(KeyGameOrder, encodeCompanionShelfGameOrder(next)).apply()
        mutableOrder.value = next
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Prefs, Context.MODE_PRIVATE)

    internal const val FieldSeparator = Separator
    internal const val PlatformSeparator = RecordSeparator
}

/** One record per platform: `platformId <US> gameId <US> gameId …`, records joined by <RS>. */
internal fun encodeCompanionShelfGameOrder(order: Map<String, List<String>>): String =
    order.entries
        .filter { (platformId, ids) -> platformId.isNotBlank() && ids.isNotEmpty() }
        .joinToString(CompanionShelfGameOrderStore.PlatformSeparator.toString()) { (platformId, ids) ->
            (listOf(platformId) + ids).joinToString(CompanionShelfGameOrderStore.FieldSeparator.toString())
        }

/**
 * Decode, tolerantly. A malformed record is DROPPED rather than throwing or being partially adopted: this
 * is read at startup before anything is on screen, and a bad byte in a preferences file must cost the
 * player one platform's filed order, never the shelf.
 */
internal fun decodeCompanionShelfGameOrder(raw: String): Map<String, List<String>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(CompanionShelfGameOrderStore.PlatformSeparator)
        .mapNotNull { record ->
            val fields = record.split(CompanionShelfGameOrderStore.FieldSeparator)
                .filter { it.isNotEmpty() }
            val platformId = fields.firstOrNull() ?: return@mapNotNull null
            val ids = fields.drop(1)
            if (platformId.isBlank() || ids.isEmpty()) null else platformId to ids
        }
        .toMap()
}
