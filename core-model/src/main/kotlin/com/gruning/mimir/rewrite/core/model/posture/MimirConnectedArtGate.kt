package com.gruning.mimir.rewrite.core.model.posture

/**
 * The runtime consent gate for connected ScreenScraper metadata and media clients.
 *
 * Mimir's scanning and launching paths never touch the network. This gate owns the opt-in
 * "connect your own ScreenScraper account" flow : the user
 * connects their account in Settings, and explicit provider clients may then retrieve metadata
 * or media for games already in their library. This gate contains that provider without a
 * build-flavor split:
 *
 *  - Default is CLOSED. Every ScreenScraper transport MUST ask [allowsRequestTo] and deny when it
 *    returns false.
 *  - Even when OPEN, only the ScreenScraper host allowlist is permitted — any other remote host
 *    is refused regardless, so a stray/rogue URL can never egress (belt-and-suspenders).
 *  - Renderers remain unconditionally local-only. Opening this gate never authorizes Coil,
 *    composition, prewarming, or navigation to retrieve a remote URL.
 *
 * A process-wide flag (not persisted here) is intentional: on every cold start the gate is CLOSED
 * until `ConnectedArtStore` re-reads accepted account/opt-in state and explicitly re-opens it during
 * app startup, so stale process state is never the durable consent authority.
 */
object MimirConnectedArtGate {

    /**
     * The only hosts the opt-in art fetch may ever reach. Matched exactly or as a subdomain.
     * Nothing else is allowed, even when the gate is open.
     */
    private val ALLOWED_HOSTS: Set<String> = setOf(
        "screenscraper.fr",
        "www.screenscraper.fr",
        "api.screenscraper.fr",
    )

    @Volatile
    private var open: Boolean = false

    /** True only after the user has connected a ScreenScraper account and opted in. Default false. */
    val isOpen: Boolean get() = open

    /** True when [host] is on the ScreenScraper allowlist (open or not). */
    fun isAllowedHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        if (h.isEmpty()) return false
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * The question every ScreenScraper transport asks before proceeding. Deny unless the gate is
     * open and [host] is on the ScreenScraper allowlist.
     */
    fun allowsRequestTo(host: String?): Boolean = open && isAllowedHost(host)

    /**
     * Opened/closed only from persisted ScreenScraper opt-in and credential state.
     */
    fun setOpen(value: Boolean) {
        open = value
    }
}
