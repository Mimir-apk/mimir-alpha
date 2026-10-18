package com.gruning.mimir.tweaker

/**
 * Every Mimir-owned root directly under /data/local/tmp.
 *
 * This is a registry of reservations, not permissions. Reducers and publication stores must keep
 * enforcing their own exact child-name and attestation policies; this registry cannot widen them.
 */
internal object MimirTmpRootReservations {
    const val RECEIPT_CHANNEL = "/data/local/tmp/mimir-rct1"
    const val MGDE_PUBLICATION = "/data/local/tmp/mimir-mgde1"
    const val TERMINAL_MUTATION_FENCE = "/data/local/tmp/mimir_restore_guardian_fence_v1"

    val ALL: Set<String> = setOf(
        RECEIPT_CHANNEL,
        MGDE_PUBLICATION,
        TERMINAL_MUTATION_FENCE,
    )
}
