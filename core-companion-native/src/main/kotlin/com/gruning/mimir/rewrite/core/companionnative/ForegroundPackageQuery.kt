package com.gruning.mimir.rewrite.core.companionnative

/** What a UsageStats event says about one package, reduced to the two edges [ForegroundPackageReducer] needs. */
internal enum class ForegroundUsageKind { FOREGROUND, BACKGROUND }

/**
 * Which package is in front, from UsageStats events read in time order: the package of the last foreground edge, unless that same
 * ACTIVITY has since gone to the background. Another package's background edge says nothing about it, and neither does another
 * activity's of the same package: moving inside a game from its game activity to its menu emits RESUMED(menu), then STOPPED(game),
 * and the stop must not clear the package that has just come forward.
 *
 * This is the on-demand answer to "who is in front right now", for a caller that cannot rely on [AndroidExternalForegroundMonitor]'s
 * own polling (which runs only while the companion's display state has something to observe, and not at all with no second display).
 */
internal class ForegroundPackageReducer {
    var current: String? = null
        private set
    private var currentClass: String? = null

    /** [className] is the event's activity; null when the event names none, which then matches any activity of its package. */
    fun accept(packageName: String, kind: ForegroundUsageKind, className: String? = null) {
        when (kind) {
            ForegroundUsageKind.FOREGROUND -> {
                current = packageName
                currentClass = className
            }
            ForegroundUsageKind.BACKGROUND -> if (current == packageName && (className == null || currentClass == null || className == currentClass)) {
                current = null
                currentClass = null
            }
        }
    }
}
