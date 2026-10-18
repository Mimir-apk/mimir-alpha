package com.gruning.mimir.rewrite.core.model.posture

/**
 * The runtime consent gate for SteamGridDB API/media clients, and — riding the same opt-in — the keyless Steam storefront description lookup (`store.steampowered.com/api/appdetails`).
 * The storefront call needs no credential of its own to derive a gate from, so it deliberately does not
 * get a second toggle: it is authorized by the same act (an active SteamGridDB key) that already lets
 * ENRICH reach `pc_games` at all.
 *
 * Default is CLOSED on every cold start. Only [com.gruning.mimir.rewrite.SteamGridDbKeyStore] may open
 * this gate after it has durably loaded or saved a decrypt-verified, non-blank user API key. A build-time
 * debug fallback or [resolvedKey] lookup never opens the gate.
 *
 * Even when OPEN, only this allowlist is permitted — any other remote host is refused regardless, so a
 * stray/rogue URL can never egress.
 */
object MimirSteamGridDbGate {

    /**
     * Hosts the opt-in SteamGridDB transport (and the Steam storefront description lookup that rides its
     * gate) may reach. Matched exactly or as a subdomain (case-insensitive).
     */
    private val ALLOWED_HOSTS: Set<String> = setOf(
        "steamgriddb.com",
        "www.steamgriddb.com",
        "cdn2.steamgriddb.com",
        "store.steampowered.com",
    )

    @Volatile
    private var open: Boolean = false

    /** True only after a verified user API key record is active for this process. Default false. */
    val isOpen: Boolean get() = open

    /** True when [host] is on the SteamGridDB allowlist (open or not). */
    fun isAllowedHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        if (h.isEmpty()) return false
        return ALLOWED_HOSTS.any { allowed -> h == allowed || h.endsWith(".$allowed") }
    }

    /**
     * The question every SteamGridDB transport asks before proceeding. Deny unless the gate is open and
     * [host] is on the SteamGridDB allowlist.
     */
    fun allowsRequestTo(host: String?): Boolean = open && isAllowedHost(host)

    /** Opened/closed only from persisted SteamGridDB credential state in [SteamGridDbKeyStore]. */
    fun setOpen(value: Boolean) {
        open = value
    }
}
