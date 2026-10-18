package com.gruning.mimir.rewrite

/**
 * The primary-display quick-launch callback is installed while the primary coordinator is composed. A
 * secondary overlay therefore gets a synchronous acceptance/refusal result instead of queueing a command
 * into a collector that may not exist while the Activity is stopped.
 *
 * `noAnimation` is for SWAP alone (Main cuts the way the Bottom does). Quick launch
 * passes nothing and keeps the system's window animation.
 *
 * Round 5 (5b): the result is a [QuickLaunchPrimaryRouteResult], so a refusal reaches the Bottom caller with the route's own
 * reason. The route makes no refusal toast for a Bottom request; that caller makes the tap's one toast from this result.
 */
internal object CompanionQuickLaunchPrimaryRoute {
    private var handler: ((String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult)? = null

    @Synchronized
    fun install(next: (String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult) {
        handler = next
    }

    @Synchronized
    fun clear(expected: (String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult) {
        if (handler === expected) handler = null
    }

    @Synchronized
    fun dispatch(packageName: String, fromGeneral: Boolean, noAnimation: Boolean = false): QuickLaunchPrimaryRouteResult =
        handler?.invoke(packageName.trim(), fromGeneral, noAnimation) ?: QuickLaunchPrimaryRouteResult.NotHandled
}

/** What Main's quick-launch route did with one request. */
internal sealed interface QuickLaunchPrimaryRouteResult {
    /** The route took the request. A start that fails after this is the route's own to report, on the Bottom for a Bottom request. */
    data object Accepted : QuickLaunchPrimaryRouteResult

    /** The route turned the request away. [reason] is its own sentence for why, which it toasts only for Main's own request. */
    data class Refused(val reason: String) : QuickLaunchPrimaryRouteResult

    /** No route was installed to take the request (Main's composition is not up), so there is no reason to show. */
    data object NotHandled : QuickLaunchPrimaryRouteResult
}
