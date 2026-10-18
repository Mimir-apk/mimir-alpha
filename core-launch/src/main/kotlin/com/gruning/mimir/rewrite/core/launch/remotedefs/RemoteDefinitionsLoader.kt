package com.gruning.mimir.rewrite.core.launch.remotedefs

enum class LoadStatus {
    /** Nothing stored: bundled data only. */
    NO_OVERLAY,

    /** A stored overlay is merged in. */
    OVERLAY_IN_USE,

    /** The bundled baseline is at least as new as the stored overlay, so the app ships newer data (note I5). */
    OVERLAY_RETIRED,

    /** The stored overlay was written by a newer app (an app downgrade). Ignored, kept for the next upgrade. */
    SCHEMA_UNSUPPORTED,

    /** The stored bytes are intact but no longer valid for THIS build (for example a package left the seeds). */
    INVALID_FOR_THIS_BUILD,

    /** The stored copy was missing or failed its hash; bundled data is used and the pointer was cleared (note I4). */
    CORRUPT_CLEARED,
}

data class LoadOutcome(val effective: EffectiveDefinitions, val status: LoadStatus)

/**
 * What the app does at start: read the stored overlay from disk, re-validate it, merge it. It takes a store and
 * the bundled data and nothing else: there is no transport parameter, so start-up, scan and launch cannot reach
 * the network through this path (test T22 pins that the refresher is never involved).
 */
object RemoteDefinitionsLoader {
    fun load(
        store: RemoteDefinitionsStore,
        bundled: BundledDefinitions,
        supportedSchema: Int = RemoteDefinitionsLimits.SUPPORTED_SCHEMA,
    ): LoadOutcome {
        val bundledOnly = DefinitionsMerge.merge(bundled, null)
        val record = store.readRecord()
        if (record == null) {
            return if (store.hasPointerFile()) {
                store.clearPointer()
                LoadOutcome(bundledOnly, LoadStatus.CORRUPT_CLEARED)
            } else {
                LoadOutcome(bundledOnly, LoadStatus.NO_OVERLAY)
            }
        }
        if (record.schema > supportedSchema) return LoadOutcome(bundledOnly, LoadStatus.SCHEMA_UNSUPPORTED)

        val verified = store.readVerified()
        if (verified == null) {
            store.clearPointer()
            return LoadOutcome(bundledOnly, LoadStatus.CORRUPT_CLEARED)
        }
        return when (val parsed = OverlayParser.parse(verified.bytes, bundled, record.canonicalSha256, supportedSchema)) {
            is ParseResult.Rejected -> LoadOutcome(bundledOnly, LoadStatus.INVALID_FOR_THIS_BUILD)
            is ParseResult.Accepted -> {
                val effective = DefinitionsMerge.merge(bundled, parsed.overlay)
                LoadOutcome(
                    effective,
                    if (effective.overlayOrdinal != null) LoadStatus.OVERLAY_IN_USE else LoadStatus.OVERLAY_RETIRED,
                )
            }
        }
    }
}
