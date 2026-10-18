package com.gruning.mimir.rewrite

/*
 * A native hide shatters its card: an Android game or app the player hides leaves the shelf
 * the moment they confirm, not when the shelf's rescan lands.
 *
 * The hide is one write ([setNativeShelfExcluded]) and then a full PackageManager sweep on IO, and the card used to stay
 * on the shelf for as long as that took. The shatter breaks the card apart over the shelf, so the card has to be gone in
 * the frame the shards start, and the focus has to have been handed to a card that stays before it is gone (the same
 * order the walk-32g removal foundation gives the emulated games). So a hide is applied to what the Library sees straight
 * away, and reconciled against the scan that follows it.
 *
 * THE RECONCILE. A hide records the shelf revision its own refresh bumped to. The scan for that revision (or a later one)
 * ran after the write and is the truth: from then on the snapshot is trusted as it is, and the entry is dropped. Until
 * then the package is filtered out of whatever snapshot is on hand, and that includes a scan that was already running
 * when the write happened (it cannot have seen it). If the write somehow did not take, the scan that judges it shows the
 * package again and the shelf tells the truth. Nothing is kept past that scan, so nothing can hide a package for good.
 */
internal data class NativeShelfOptimisticHides(
    /** Package name to the shelf revision whose scan will judge the hide. */
    val revisionByPackage: Map<String, Int> = emptyMap(),
) {
    /** [packageName] is hidden from the moment the shelf revision [revision] (the refresh this hide requested) is asked for. */
    fun hiding(packageName: String, revision: Int): NativeShelfOptimisticHides =
        copy(revisionByPackage = revisionByPackage + (packageName to revision))

    /** What is still unjudged once a scan for [scanRevision] has landed: only the hides that asked for a later one. */
    fun settledBy(scanRevision: Int): NativeShelfOptimisticHides {
        if (revisionByPackage.isEmpty()) return this
        val remaining = revisionByPackage.filterValues { it > scanRevision }
        return if (remaining.size == revisionByPackage.size) this else copy(revisionByPackage = remaining)
    }

    val isEmpty: Boolean get() = revisionByPackage.isEmpty()

    operator fun contains(packageName: String): Boolean = packageName in revisionByPackage
}

/**
 * The snapshot with the hidden packages taken out of its rows. The candidates for adding a package back are the scan's to
 * decide, and it has not run yet, so they are left as they are. The same instance comes back when there is nothing to take
 * out, so a shelf that is not being hidden from does not see its rows change identity.
 */
internal fun NativeShelfSnapshot.withoutOptimisticHides(hides: NativeShelfOptimisticHides): NativeShelfSnapshot {
    if (hides.isEmpty) return this
    if (games.none { it.packageName in hides } && apps.none { it.packageName in hides }) return this
    return copy(
        games = games.filterNot { it.packageName in hides },
        apps = apps.filterNot { it.packageName in hides },
    )
}
