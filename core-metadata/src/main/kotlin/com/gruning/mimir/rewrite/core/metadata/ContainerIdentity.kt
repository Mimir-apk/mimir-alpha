package com.gruning.mimir.rewrite.core.metadata

import kotlinx.coroutines.CancellationException

/** Moves whenever a production container reader/catalog policy changes which old rows must be replayed. */
object AthenaContainerIdentityPolicy {
    const val STAMP =
        "athena-container-v4:mame287-c750bbcd:switch-ticket-hint-v2:libchdr-overall-v3:" +
            "libchdr-parent-chain-v1:optical-zip-reject-v1:zip-payload-select-v1:" +
            "archive-refusal-v1"

    /** The immediately superseded A1D stamp; v4+ZIP reopens only terminal ZIP attempts. */
    const val PREVIOUS_A1D_STAMP =
        "athena-container-v4:mame287-c750bbcd:switch-ticket-hint-v2:libchdr-overall-v3:" +
            "libchdr-parent-chain-v1:optical-zip-reject-v1"

    /**
     * The A1C stamp predates both parent-chain CHD readability and ZIP payload selection. Upgrading from
     * it directly to the current stamp therefore requires the full container replay closure.
     */
    const val PREVIOUS_A1C_STAMP =
        "athena-container-v3:mame287-c750bbcd:switch-ticket-hint-v2:libchdr-overall-v3:optical-zip-reject-v1"
}

/** Stable failure vocabulary for bounded container readers. */
internal enum class ContainerIdentityReason {
    NO_CATALOG_MATCH,
    MEMBER_ONLY,
    PARENT_REQUIRED,
    DLC_ROLE_NOT_ADMITTED,
    ZIPPED_OPTICAL_UNSUPPORTED,
    UNSUPPORTED_CONTAINER,
    MALFORMED,
    AMBIGUOUS,
    RESOURCE_REFUSED,
    SEEK_UNAVAILABLE,
    IO_RETRYABLE,
}

/**
 * A container read cannot collapse into nullable data: terminal release policy, retryable access failure and
 * an identified payload have different persistence semantics.
 */
internal sealed interface ContainerIdentityResult<out T> {
    data class Identified<T>(val value: T) : ContainerIdentityResult<T>

    /** A deterministic, intentional release-policy outcome that may be stamped as attempted. */
    data class PolicyAbstain(val reason: ContainerIdentityReason) : ContainerIdentityResult<Nothing>

    /** A deterministic content conflict requiring better evidence, never a guessed winner. */
    data class NeedsReview(val reason: ContainerIdentityReason) : ContainerIdentityResult<Nothing>

    /** Access, corruption or budget state that must remain eligible for a later retry. */
    data class RetryableFailure(val reason: ContainerIdentityReason) : ContainerIdentityResult<Nothing>
}

internal fun RandomAccessContent.readExact(offset: Long, byteCount: Int): ByteArray? {
    if (offset < 0L || byteCount < 0) return null
    val end = offset + byteCount.toLong()
    if (end < offset || end > length) return null
    val output = ByteArray(byteCount)
    var total = 0
    while (total < byteCount) {
        val read = try {
            readAt(offset + total, output, total, byteCount - total)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (read !in 1..(byteCount - total)) return null
        total += read
    }
    return output
}
