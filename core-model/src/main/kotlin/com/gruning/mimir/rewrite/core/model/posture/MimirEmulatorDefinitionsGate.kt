package com.gruning.mimir.rewrite.core.model.posture

import java.util.concurrent.atomic.AtomicInteger

/**
 * The consent gate for fetching emulator definitions : a small list of launch details for
 * emulators Mimir already knows, downloaded only when the player presses the action in Settings.
 *
 * Scanning and launching never touch the network, and nothing runs this on a timer. The gate is therefore CLOSED
 * except for the duration of one user-started refresh:
 *
 *  - Default is CLOSED. The egress route for definitions asks [allowsRequestTo] and denies when it returns false.
 *  - While a refresh the player started is running, only the single data-repository host is permitted, matched
 *    exactly (no subdomains). Any other host is refused even while armed, so a stray or rogue URL can never egress.
 *  - Nothing persists here: a process that starts or restarts is closed until the player presses the action again.
 *    The durable fact is the player's confirmation of the one-line explanation, which lives with the Settings
 *    action, not in this gate.
 */
object MimirEmulatorDefinitionsGate {

    /** The only host the definitions fetch may ever reach. Exact match, no subdomains. */
    private val ALLOWED_HOSTS: Set<String> = setOf(
        "raw.githubusercontent.com",
    )

    private val running = AtomicInteger(0)

    /** True only while a user-started refresh is running. */
    val isArmed: Boolean get() = running.get() > 0

    /** True when [host] is exactly the data-repository host (armed or not). */
    fun isAllowedHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        if (h.isEmpty()) return false
        return h in ALLOWED_HOSTS
    }

    /** The question the definitions egress asks before proceeding: armed, and exactly the allowed host. */
    fun allowsRequestTo(host: String?): Boolean = isArmed && isAllowedHost(host)

    /** Runs [block] with the gate armed, and closes it again however [block] ends. The only way to arm it. */
    fun <T> whileUserStartedRefresh(block: () -> T): T {
        running.incrementAndGet()
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }
}
