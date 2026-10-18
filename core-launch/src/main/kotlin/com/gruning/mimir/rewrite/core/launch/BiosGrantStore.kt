package com.gruning.mimir.rewrite.core.launch

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * the BIOS-specific SAF tree grant (tier 3).
 *
 * ## Why this exists at all
 *
 * Tiers 1/2/4/5 read folders Mimir can already see: an emulator's own BIOS directory when it is world
 * readable, and the library roots the user already granted. Several emulators keep BIOS inside
 * `Android/data/<pkg>/files/`, which is app-private on modern Android — enumerable by that emulator and by
 * nobody else. For those, presence is genuinely UNKNOWN and [BiosPresenceProbe] correctly refuses to guess.
 * This grant is the escape hatch: the user points Mimir at that folder once, and UNKNOWN becomes an answer.
 *
 * ## Why it is NOT a library source root
 *
 * A source root is a place Mimir scans FOR GAMES. Storing this there would put `scph5501.bin` on the shelf.
 * It lives in its own store, is surfaced through [BiosSeedPersistence.biosGrantRoots], and is read-only
 * evidence for BIOS presence — never a scan target.
 *
 * ## Why the permission is re-checked on every read
 *
 * A persisted URI permission is not a promise. The user can revoke it in system settings, and the granting
 * app can be uninstalled; the string in prefs survives both. Reporting a revoked grant as a readable folder
 * would let the seeder conclude MISSING from a folder it can no longer open — manufacturing exactly the
 * false negative the probe's UNKNOWN state exists to prevent. So every read intersects the stored URIs with
 * the permissions actually held right now.
 */
object BiosGrantStore {

    private const val PREFS = "mimir_rewrite_bios_grant"
    private const val KEY_URIS = "biosGrantUris"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The intent for the picker. Flags match what [remember] takes, or the grant will not survive reboot. */
    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Persist a tree the user just picked. Takes the persistable READ permission first: if that throws the
     * grant is worthless, so nothing is stored and this returns false rather than recording a URI that will
     * never open.
     */
    fun remember(context: Context, treeUri: Uri): Boolean {
        val app = context.applicationContext
        val taken = runCatching {
            app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (!taken) return false
        val existing = prefs(app).getStringSet(KEY_URIS, emptySet()).orEmpty()
        prefs(app).edit().putStringSet(KEY_URIS, existing + treeUri.toString()).apply()
        return true
    }

    /** Stop consulting [treeUri] and release its permission. Kept symmetrical with [remember]. */
    fun forget(context: Context, treeUri: Uri) {
        val app = context.applicationContext
        val existing = prefs(app).getStringSet(KEY_URIS, emptySet()).orEmpty()
        prefs(app).edit().putStringSet(KEY_URIS, existing - treeUri.toString()).apply()
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /**
     * The grants that are BOTH remembered and still readable, newest-agnostic order.
     *
     * Anything stored whose permission has since gone is dropped from the result AND pruned from the store,
     * so a revoked grant stops costing a lookup on every seed.
     */
    fun grantUris(context: Context): List<String> {
        val app = context.applicationContext
        val stored = prefs(app).getStringSet(KEY_URIS, emptySet()).orEmpty()
        if (stored.isEmpty()) return emptyList()
        val held = app.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val live = stored.filter { it in held }
        if (live.size != stored.size) {
            prefs(app).edit().putStringSet(KEY_URIS, live.toSet()).apply()
        }
        return live
    }

    /** True when at least one usable BIOS grant exists — for deciding whether to offer the picker. */
    fun hasGrant(context: Context): Boolean = grantUris(context).isNotEmpty()
}
