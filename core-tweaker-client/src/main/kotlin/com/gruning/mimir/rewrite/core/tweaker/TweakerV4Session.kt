package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle

const val TWEAKER_V4_MIN_API_VERSION = 4
const val TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION = 5
val TWEAKER_SYSTEM_SELECTABLE_PROFILE_IDS =
    TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS + TWEAKER_STOCK_PROFILE_ID

enum class TweakerSessionScope {
    NONE,
    LAUNCH,
    SYSTEM,
    RECOVERY,
    UNKNOWN,
}

/** Exact public states exposed by the generation-bound Tweaker service contract. */
enum class TweakerV4SessionState {
    STOCK,
    APPLYING,
    ACTIVE,
    RESTORING,
    RECOVERY_FAILED,
    CORRUPT,
    UNKNOWN,
}

data class TweakerV4SessionTruth(
    val apiVersion: Int,
    val state: TweakerV4SessionState,
    val mutationMayBeActive: Boolean,
    val requestId: String?,
    val generation: Long?,
    val transactionId: String?,
    val requestDeadlineElapsedRealtimeMs: Long?,
    val leaseDeadlineElapsedRealtimeMs: Long?,
    val hardLeaseDeadlineElapsedRealtimeMs: Long?,
    val leaseRenewable: Boolean,
    val scope: TweakerSessionScope = TweakerSessionScope.UNKNOWN,
    val activeProfileId: String? = null,
)

class TweakerV5SystemSelectionAttempt internal constructor(
    val requestId: String,
    val profileId: String,
    val requestDeadlineElapsedRealtimeMs: Long,
) {
    init {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Selection request identity is invalid" }
        require(profileId in TWEAKER_SYSTEM_SELECTABLE_PROFILE_IDS) {
            "System-wide profile selection is invalid"
        }
        require(requestDeadlineElapsedRealtimeMs > 0L) { "Selection deadline must be positive" }
    }
}

sealed interface TweakerV5SystemSelectionAssessment {
    data class Active(
        val changed: Boolean,
        val truth: TweakerV4SessionTruth,
        val owner: TweakerV4OwnedSession?,
    ) : TweakerV5SystemSelectionAssessment

    data class Stock(
        val changed: Boolean,
        val truth: TweakerV4SessionTruth,
    ) : TweakerV5SystemSelectionAssessment

    data class Rejected(
        val code: String,
        val message: String,
        val truth: TweakerV4SessionTruth,
    ) : TweakerV5SystemSelectionAssessment

    data class Uncertain(val reason: String) : TweakerV5SystemSelectionAssessment
}

/** Owner identity allocated before the apply Binder call starts. */
class TweakerV4ApplyAttempt internal constructor(
    val requestId: String,
    val profileId: String,
    val packageName: String,
    val requestDeadlineElapsedRealtimeMs: Long,
) {
    init {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Apply request identity is invalid" }
        require(profileId in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS) {
            "Apply profile is not product-qualifiable"
        }
        require(isValidTweakerWorkloadPackageName(packageName)) { "Apply package name is invalid" }
        require(requestDeadlineElapsedRealtimeMs > 0L) { "Apply deadline must be positive" }
    }
}

/** Exact service-owned identity returned only by a canonical successful apply/renew response. */
class TweakerV4OwnedSession internal constructor(
    val requestId: String,
    val generation: Long,
    val transactionId: String,
    val requestDeadlineElapsedRealtimeMs: Long,
    val leaseDeadlineElapsedRealtimeMs: Long,
    val hardLeaseDeadlineElapsedRealtimeMs: Long,
) {
    init {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Owner request identity is invalid" }
        require(generation > 0L) { "Owner generation must be positive" }
        require(transactionId.isNotBlank()) { "Owner transaction identity is blank" }
        require(requestDeadlineElapsedRealtimeMs > 0L) { "Owner request deadline must be positive" }
        require(leaseDeadlineElapsedRealtimeMs > 0L) { "Owner lease deadline must be positive" }
        require(hardLeaseDeadlineElapsedRealtimeMs > 0L) { "Owner hard deadline must be positive" }
        require(leaseDeadlineElapsedRealtimeMs <= hardLeaseDeadlineElapsedRealtimeMs) {
            "Owner lease exceeds its immutable hard deadline"
        }
    }
}

enum class TweakerV4Operation {
    APPLY,
    CANCEL_IN_FLIGHT,
    CANCEL_ACTIVE,
    RENEW,
    RESTORE,
}

sealed interface TweakerV4OperationExpectation {
    val operation: TweakerV4Operation
    val ownerRequestId: String
    val callerDeadlineElapsedRealtimeMs: Long

    data class Apply(
        val attempt: TweakerV4ApplyAttempt,
    ) : TweakerV4OperationExpectation {
        override val operation = TweakerV4Operation.APPLY
        override val ownerRequestId = attempt.requestId
        override val callerDeadlineElapsedRealtimeMs = attempt.requestDeadlineElapsedRealtimeMs
    }

    data class CancelInFlight(
        val attempt: TweakerV4ApplyAttempt,
        override val callerDeadlineElapsedRealtimeMs: Long,
    ) : TweakerV4OperationExpectation {
        override val operation = TweakerV4Operation.CANCEL_IN_FLIGHT
        override val ownerRequestId = attempt.requestId
    }

    data class CancelActive(
        val owner: TweakerV4OwnedSession,
        override val callerDeadlineElapsedRealtimeMs: Long,
    ) : TweakerV4OperationExpectation {
        override val operation = TweakerV4Operation.CANCEL_ACTIVE
        override val ownerRequestId = owner.requestId
    }

    data class Renew(
        val owner: TweakerV4OwnedSession,
        override val callerDeadlineElapsedRealtimeMs: Long,
    ) : TweakerV4OperationExpectation {
        override val operation = TweakerV4Operation.RENEW
        override val ownerRequestId = owner.requestId
    }

    data class Restore(
        val owner: TweakerV4OwnedSession,
        override val callerDeadlineElapsedRealtimeMs: Long,
    ) : TweakerV4OperationExpectation {
        override val operation = TweakerV4Operation.RESTORE
        override val ownerRequestId = owner.requestId
    }
}

sealed interface TweakerV4OperationAssessment {
    data class Accepted(
        val operation: TweakerV4Operation,
        val code: String,
        val truth: TweakerV4SessionTruth,
        val owner: TweakerV4OwnedSession?,
    ) : TweakerV4OperationAssessment

    /** A structurally valid service rejection. Its authoritative session truth is retained. */
    data class Rejected(
        val operation: TweakerV4Operation,
        val code: String,
        val message: String,
        val truth: TweakerV4SessionTruth,
    ) : TweakerV4OperationAssessment

    data class Uncertain(
        val operation: TweakerV4Operation,
        val reason: String,
    ) : TweakerV4OperationAssessment
}

/**
 * Only [Stock] authorizes a no-op Stock conclusion. A well-formed non-Stock response is
 * [Blocked]; malformed, expired, or unreachable truth is [Uncertain].
 */
sealed interface TweakerV4SessionAssessment {
    data class Stock(val truth: TweakerV4SessionTruth) : TweakerV4SessionAssessment
    data class Blocked(val truth: TweakerV4SessionTruth) : TweakerV4SessionAssessment
    data class Uncertain(val reason: String) : TweakerV4SessionAssessment
}

fun parseTweakerV4SessionBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    expectedCallerRequestId: String,
    expectedCallerDeadlineElapsedRealtimeMs: Long,
    observedAtElapsedRealtimeMs: Long,
): TweakerV4SessionAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V4_MIN_API_VERSION) {
        "V4 query requires API $TWEAKER_V4_MIN_API_VERSION or newer"
    }
    require(expectedCallerRequestId.matches(REQUEST_ID_PATTERN)) {
        "Caller request identity is invalid"
    }
    require(expectedCallerDeadlineElapsedRealtimeMs > observedAtElapsedRealtimeMs) {
        "Query response arrived after its elapsed-realtime deadline"
    }

    val apiVersion = bundle.requiredInt("apiVersion")
    require(apiVersion == expectedApiVersion && apiVersion >= TWEAKER_V4_MIN_API_VERSION) {
        "Query API version did not match the negotiated service version"
    }
    require(bundle.requiredBoolean("success")) { "Session query did not succeed" }
    require(bundle.requiredString("code") == "SESSION_QUERY") { "Unexpected session query result code" }
    require(!bundle.requiredBoolean("applied") && !bundle.requiredBoolean("restored")) {
        "A query response cannot claim mutation or restoration"
    }
    require(bundle.requiredString("callerRequestId") == expectedCallerRequestId) {
        "Session query request identity was not echoed exactly"
    }
    require(
        bundle.requiredLong("callerDeadlineElapsedRealtimeMs") ==
            expectedCallerDeadlineElapsedRealtimeMs,
    ) {
        "Session query deadline was not echoed exactly"
    }

    val state = runCatching {
        TweakerV4SessionState.valueOf(bundle.requiredString("sessionState"))
    }.getOrElse { throw IllegalArgumentException("Unknown public session state") }
    val truth = TweakerV4SessionTruth(
        apiVersion = apiVersion,
        state = state,
        mutationMayBeActive = bundle.requiredBoolean("mutationMayBeActive"),
        requestId = bundle.optionalString("requestId"),
        generation = bundle.optionalLong("generation"),
        transactionId = bundle.optionalString("transactionId"),
        requestDeadlineElapsedRealtimeMs = bundle.optionalLong("requestDeadlineElapsedRealtimeMs"),
        leaseDeadlineElapsedRealtimeMs = bundle.optionalLong("leaseDeadlineElapsedRealtimeMs"),
        hardLeaseDeadlineElapsedRealtimeMs = bundle.optionalLong("hardLeaseDeadlineElapsedRealtimeMs"),
        leaseRenewable = bundle.requiredBoolean("leaseRenewable"),
        scope = bundle.sessionScope(),
        activeProfileId = bundle.optionalString("activeProfileId"),
    )
    validateV4Truth(truth)
    if (truth.state == TweakerV4SessionState.STOCK && !truth.mutationMayBeActive) {
        TweakerV4SessionAssessment.Stock(truth)
    } else {
        TweakerV4SessionAssessment.Blocked(truth)
    }
}.getOrElse { error ->
    TweakerV4SessionAssessment.Uncertain(
        error.message?.takeIf { it.isNotBlank() } ?: "Malformed V4 session truth",
    )
}

/**
 * Strict parser for mutation-bearing API-v4 calls. A successful but non-canonical bundle is
 * uncertain; only an exact canonical success earns [TweakerV4OperationAssessment.Accepted].
 */
fun parseTweakerV4OperationBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    expectation: TweakerV4OperationExpectation,
    observedAtElapsedRealtimeMs: Long,
): TweakerV4OperationAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V4_MIN_API_VERSION) {
        "V4 operation requires API $TWEAKER_V4_MIN_API_VERSION or newer"
    }
    require(expectation.ownerRequestId.matches(REQUEST_ID_PATTERN)) {
        "Owner request identity is invalid"
    }
    require(expectation.callerDeadlineElapsedRealtimeMs > observedAtElapsedRealtimeMs) {
        "Operation response arrived after its elapsed-realtime deadline"
    }

    val apiVersion = bundle.requiredInt("apiVersion")
    require(apiVersion == expectedApiVersion && apiVersion >= TWEAKER_V4_MIN_API_VERSION) {
        "Operation API version did not match the negotiated service version"
    }
    val success = bundle.requiredBoolean("success")
    val applied = bundle.requiredBoolean("applied")
    val restored = bundle.requiredBoolean("restored")
    require(!(applied && restored)) { "Operation cannot claim apply and restore together" }
    val code = bundle.requiredString("code")
    require(code.isNotBlank()) { "Operation result code is blank" }
    val message = bundle.requiredString("message")
    require(message.isNotBlank()) { "Operation result message is blank" }
    require(bundle.requiredString("callerRequestId") == expectation.ownerRequestId) {
        "Operation owner identity was not echoed exactly"
    }
    require(
        bundle.requiredLong("callerDeadlineElapsedRealtimeMs") ==
            expectation.callerDeadlineElapsedRealtimeMs,
    ) {
        "Operation deadline was not echoed exactly"
    }

    val state = runCatching {
        TweakerV4SessionState.valueOf(bundle.requiredString("sessionState"))
    }.getOrElse { throw IllegalArgumentException("Unknown public session state") }
    val truth = TweakerV4SessionTruth(
        apiVersion = apiVersion,
        state = state,
        mutationMayBeActive = bundle.requiredBoolean("mutationMayBeActive"),
        requestId = bundle.optionalString("requestId"),
        generation = bundle.optionalLong("generation"),
        transactionId = bundle.optionalString("transactionId"),
        requestDeadlineElapsedRealtimeMs = bundle.optionalLong("requestDeadlineElapsedRealtimeMs"),
        leaseDeadlineElapsedRealtimeMs = bundle.optionalLong("leaseDeadlineElapsedRealtimeMs"),
        hardLeaseDeadlineElapsedRealtimeMs = bundle.optionalLong("hardLeaseDeadlineElapsedRealtimeMs"),
        leaseRenewable = bundle.requiredBoolean("leaseRenewable"),
        scope = bundle.sessionScope(),
        activeProfileId = bundle.optionalString("activeProfileId"),
    )
    validateV4Truth(truth)

    if (!success) {
        require(!applied) { "Rejected operation cannot claim an applied profile" }
        require(code !in SUCCESS_ONLY_OPERATION_CODES) {
            "Rejected operation used a success-only result code"
        }
        if (restored) {
            require(truth.state == TweakerV4SessionState.STOCK && !truth.mutationMayBeActive) {
                "Rejected operation claimed restore without authoritative Stock truth"
            }
        }
        TweakerV4OperationAssessment.Rejected(
            operation = expectation.operation,
            code = code,
            message = message,
            truth = truth,
        )
    } else {
        requireCanonicalOperation(
            expectation = expectation,
            code = code,
            applied = applied,
            restored = restored,
            truth = truth,
            observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
        )
    }
}.getOrElse { error ->
    TweakerV4OperationAssessment.Uncertain(
        operation = expectation.operation,
        reason = error.message?.takeIf { it.isNotBlank() } ?: "Malformed V4 operation truth",
    )
}

fun parseTweakerV5SystemSelectionBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    attempt: TweakerV5SystemSelectionAttempt,
    observedAtElapsedRealtimeMs: Long,
): TweakerV5SystemSelectionAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION) {
        "System-wide selection requires API $TWEAKER_V5_SYSTEM_SCOPE_MIN_API_VERSION or newer"
    }
    require(attempt.requestDeadlineElapsedRealtimeMs > observedAtElapsedRealtimeMs) {
        "System-wide selection response arrived after its deadline"
    }
    val apiVersion = bundle.requiredInt("apiVersion")
    require(apiVersion == expectedApiVersion) { "Selection API version did not match negotiation" }
    require(bundle.requiredString("callerRequestId") == attempt.requestId) {
        "Selection request identity was not echoed exactly"
    }
    require(bundle.requiredLong("callerDeadlineElapsedRealtimeMs") == attempt.requestDeadlineElapsedRealtimeMs) {
        "Selection deadline was not echoed exactly"
    }
    val success = bundle.requiredBoolean("success")
    val applied = bundle.requiredBoolean("applied")
    val restored = bundle.requiredBoolean("restored")
    require(!(applied && restored)) { "Selection cannot claim apply and restore together" }
    val code = bundle.requiredString("code")
    val message = bundle.requiredString("message")
    require(code.isNotBlank() && message.isNotBlank()) { "Selection result is incomplete" }
    val truth = TweakerV4SessionTruth(
        apiVersion = apiVersion,
        state = runCatching {
            TweakerV4SessionState.valueOf(bundle.requiredString("sessionState"))
        }.getOrElse { throw IllegalArgumentException("Unknown public session state") },
        mutationMayBeActive = bundle.requiredBoolean("mutationMayBeActive"),
        requestId = bundle.optionalString("requestId"),
        generation = bundle.optionalLong("generation"),
        transactionId = bundle.optionalString("transactionId"),
        requestDeadlineElapsedRealtimeMs = bundle.optionalLong("requestDeadlineElapsedRealtimeMs"),
        leaseDeadlineElapsedRealtimeMs = bundle.optionalLong("leaseDeadlineElapsedRealtimeMs"),
        hardLeaseDeadlineElapsedRealtimeMs = bundle.optionalLong("hardLeaseDeadlineElapsedRealtimeMs"),
        leaseRenewable = bundle.requiredBoolean("leaseRenewable"),
        scope = bundle.sessionScope(),
        activeProfileId = bundle.optionalString("activeProfileId"),
    )
    validateV4Truth(truth)
    if (!success) {
        require(!applied) { "Rejected selection cannot claim an applied profile" }
        TweakerV5SystemSelectionAssessment.Rejected(code, message, truth)
    } else if (attempt.profileId == TWEAKER_STOCK_PROFILE_ID) {
        require(code == "RESTORED" || code == "SYSTEM_STOCK_UNCHANGED") {
            "Successful Stock selection used an unexpected code"
        }
        require(!applied && truth.state == TweakerV4SessionState.STOCK && !truth.mutationMayBeActive) {
            "Stock selection did not return authoritative Stock truth"
        }
        require(restored == (code == "RESTORED")) { "Stock selection restore claim is inconsistent" }
        TweakerV5SystemSelectionAssessment.Stock(code == "RESTORED", truth)
    } else {
        require(code == "APPLIED" || code == "SYSTEM_PROFILE_UNCHANGED") {
            "Successful system profile selection used an unexpected code"
        }
        require(!restored && applied == (code == "APPLIED")) {
            "System profile mutation claim is inconsistent"
        }
        val exactActiveOwner = requireExactSystemActiveTruth(
            truth = truth,
            expectedProfileId = attempt.profileId,
            expectedNewOwner = attempt.takeIf { code == "APPLIED" },
            observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
        )
        val owner = exactActiveOwner.takeIf { code == "APPLIED" }
        TweakerV5SystemSelectionAssessment.Active(code == "APPLIED", truth, owner)
    }
}.getOrElse { error ->
    TweakerV5SystemSelectionAssessment.Uncertain(
        error.message?.takeIf { it.isNotBlank() } ?: "Malformed system-wide selection truth",
    )
}

private fun requireExactSystemActiveTruth(
    truth: TweakerV4SessionTruth,
    expectedProfileId: String,
    expectedNewOwner: TweakerV5SystemSelectionAttempt?,
    observedAtElapsedRealtimeMs: Long,
): TweakerV4OwnedSession {
    require(
        truth.state == TweakerV4SessionState.ACTIVE && truth.mutationMayBeActive &&
            truth.scope == TweakerSessionScope.SYSTEM && truth.activeProfileId == expectedProfileId,
    ) { "Selection did not return the requested active SYSTEM profile" }
    val requestId = requireNotNull(truth.requestId) { "SYSTEM owner request identity is missing" }
    val requestDeadline = requireNotNull(truth.requestDeadlineElapsedRealtimeMs) {
        "SYSTEM owner request deadline is missing"
    }
    expectedNewOwner?.let { attempt ->
        require(requestId == attempt.requestId) { "Selection returned another owner" }
        require(requestDeadline == attempt.requestDeadlineElapsedRealtimeMs) {
            "Selection changed the original request deadline"
        }
    }
    require(!truth.leaseRenewable) { "SYSTEM hard lease must not be renewable" }
    val leaseDeadline = requireNotNull(truth.leaseDeadlineElapsedRealtimeMs) {
        "SYSTEM lease deadline is missing"
    }
    val hardDeadline = requireNotNull(truth.hardLeaseDeadlineElapsedRealtimeMs) {
        "SYSTEM hard deadline is missing"
    }
    require(leaseDeadline == hardDeadline && hardDeadline > observedAtElapsedRealtimeMs) {
        "SYSTEM lease must equal its live immutable hard deadline"
    }
    return TweakerV4OwnedSession(
        requestId = requestId,
        generation = requireNotNull(truth.generation),
        transactionId = requireNotNull(truth.transactionId),
        requestDeadlineElapsedRealtimeMs = requestDeadline,
        leaseDeadlineElapsedRealtimeMs = leaseDeadline,
        hardLeaseDeadlineElapsedRealtimeMs = hardDeadline,
    )
}

private fun requireCanonicalOperation(
    expectation: TweakerV4OperationExpectation,
    code: String,
    applied: Boolean,
    restored: Boolean,
    truth: TweakerV4SessionTruth,
    observedAtElapsedRealtimeMs: Long,
): TweakerV4OperationAssessment.Accepted {
    val owner = when (expectation) {
        is TweakerV4OperationExpectation.Apply -> {
            require(code == "APPLIED" && applied && !restored) {
                "Successful apply response is not canonical"
            }
            requireExactActiveTruth(
                truth = truth,
                requestId = expectation.attempt.requestId,
                originalRequestDeadlineElapsedRealtimeMs =
                    expectation.attempt.requestDeadlineElapsedRealtimeMs,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            )
        }
        is TweakerV4OperationExpectation.CancelInFlight -> {
            require(code == "CANCEL_REQUESTED" && !applied && !restored) {
                "Successful in-flight cancel response is not canonical"
            }
            require(truth.state == TweakerV4SessionState.APPLYING) {
                "In-flight cancel must retain Applying truth until settlement"
            }
            require(truth.requestId == expectation.attempt.requestId) {
                "In-flight cancel returned another owner"
            }
            truth.requestDeadlineElapsedRealtimeMs?.let { originalDeadline ->
                require(originalDeadline == expectation.attempt.requestDeadlineElapsedRealtimeMs) {
                    "In-flight cancel changed the original apply deadline"
                }
            }
            null
        }
        is TweakerV4OperationExpectation.CancelActive -> {
            requireCanonicalStockRestore(code, applied, restored, truth)
            null
        }
        is TweakerV4OperationExpectation.Renew -> {
            require(code == "LEASE_RENEWED" && !applied && !restored) {
                "Successful renew response is not canonical"
            }
            val renewed = requireExactActiveTruth(
                truth = truth,
                requestId = expectation.owner.requestId,
                originalRequestDeadlineElapsedRealtimeMs =
                    expectation.owner.requestDeadlineElapsedRealtimeMs,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            )
            require(renewed.generation == expectation.owner.generation) {
                "Renew changed the service generation"
            }
            require(renewed.transactionId == expectation.owner.transactionId) {
                "Renew changed the transaction identity"
            }
            require(
                renewed.hardLeaseDeadlineElapsedRealtimeMs ==
                    expectation.owner.hardLeaseDeadlineElapsedRealtimeMs,
            ) {
                "Renew changed the immutable hard deadline"
            }
            require(
                renewed.leaseDeadlineElapsedRealtimeMs >
                    expectation.owner.leaseDeadlineElapsedRealtimeMs,
            ) {
                "Renew did not strictly advance the owner lease"
            }
            renewed
        }
        is TweakerV4OperationExpectation.Restore -> {
            requireCanonicalStockRestore(code, applied, restored, truth)
            null
        }
    }
    return TweakerV4OperationAssessment.Accepted(
        operation = expectation.operation,
        code = code,
        truth = truth,
        owner = owner,
    )
}

private fun requireExactActiveTruth(
    truth: TweakerV4SessionTruth,
    requestId: String,
    originalRequestDeadlineElapsedRealtimeMs: Long,
    observedAtElapsedRealtimeMs: Long,
): TweakerV4OwnedSession {
    require(truth.state == TweakerV4SessionState.ACTIVE && truth.mutationMayBeActive) {
        "Operation did not return authoritative Active truth"
    }
    require(truth.requestId == requestId) { "Operation returned another owner" }
    require(truth.requestDeadlineElapsedRealtimeMs == originalRequestDeadlineElapsedRealtimeMs) {
        "Operation changed the original apply deadline"
    }
    require(truth.leaseRenewable) { "Active owner lease is not renewable" }
    val leaseDeadline = requireNotNull(truth.leaseDeadlineElapsedRealtimeMs) {
        "Active owner lease deadline is missing"
    }
    require(leaseDeadline > observedAtElapsedRealtimeMs) { "Active owner lease is already expired" }
    val hardDeadline = requireNotNull(truth.hardLeaseDeadlineElapsedRealtimeMs) {
        "Active owner hard deadline is missing"
    }
    require(hardDeadline > observedAtElapsedRealtimeMs) { "Active owner hard deadline is expired" }
    return TweakerV4OwnedSession(
        requestId = requestId,
        generation = requireNotNull(truth.generation),
        transactionId = requireNotNull(truth.transactionId),
        requestDeadlineElapsedRealtimeMs = originalRequestDeadlineElapsedRealtimeMs,
        leaseDeadlineElapsedRealtimeMs = leaseDeadline,
        hardLeaseDeadlineElapsedRealtimeMs = hardDeadline,
    )
}

/** Re-validates query truth before the owner controller may adopt it for an exact restore. */
internal fun TweakerV4SessionTruth.ownedSessionFor(
    requestId: String,
    originalRequestDeadlineElapsedRealtimeMs: Long,
    observedAtElapsedRealtimeMs: Long,
): TweakerV4OwnedSession? = runCatching {
    validateV4Truth(this)
    requireExactActiveTruth(
        truth = this,
        requestId = requestId,
        originalRequestDeadlineElapsedRealtimeMs = originalRequestDeadlineElapsedRealtimeMs,
        observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
    )
}.getOrNull()

private fun requireCanonicalStockRestore(
    code: String,
    applied: Boolean,
    restored: Boolean,
    truth: TweakerV4SessionTruth,
) {
    require(code == "RESTORED" && !applied && restored) {
        "Successful restore response is not canonical"
    }
    require(truth.state == TweakerV4SessionState.STOCK && !truth.mutationMayBeActive) {
        "Restore did not return authoritative Stock truth"
    }
}

private fun validateV4Truth(truth: TweakerV4SessionTruth) {
    truth.requestId?.let {
        require(it.matches(REQUEST_ID_PATTERN)) { "Session request identity is invalid" }
    }
    truth.generation?.let { require(it > 0L) { "Session generation must be positive" } }
    truth.transactionId?.let { require(it.isNotBlank()) { "Session transaction identity is blank" } }
    listOf(
        truth.requestDeadlineElapsedRealtimeMs,
        truth.leaseDeadlineElapsedRealtimeMs,
        truth.hardLeaseDeadlineElapsedRealtimeMs,
    ).forEach { value ->
        value?.let { require(it > 0L) { "Session deadlines must be positive" } }
    }
    if (truth.leaseDeadlineElapsedRealtimeMs != null &&
        truth.hardLeaseDeadlineElapsedRealtimeMs != null
    ) {
        require(truth.leaseDeadlineElapsedRealtimeMs <= truth.hardLeaseDeadlineElapsedRealtimeMs) {
            "Renewable lease exceeds the immutable hard deadline"
        }
    }
    require(
        (truth.generation == null) == (truth.transactionId == null),
    ) {
        "Generation and transaction identity must be present together"
    }
    if (truth.generation != null ||
        truth.requestDeadlineElapsedRealtimeMs != null ||
        truth.leaseDeadlineElapsedRealtimeMs != null ||
        truth.hardLeaseDeadlineElapsedRealtimeMs != null
    ) {
        require(truth.requestId != null) { "Session identity fields require a request identity" }
    }
    require(!truth.leaseRenewable || truth.state == TweakerV4SessionState.ACTIVE) {
        "Only an active session may be renewable"
    }

    val hasAnyIdentity = truth.requestId != null ||
        truth.generation != null ||
        truth.transactionId != null ||
        truth.requestDeadlineElapsedRealtimeMs != null ||
        truth.leaseDeadlineElapsedRealtimeMs != null ||
        truth.hardLeaseDeadlineElapsedRealtimeMs != null
    val hasRecordIdentity = truth.requestId != null &&
        truth.generation != null &&
        truth.transactionId != null &&
        truth.requestDeadlineElapsedRealtimeMs != null &&
        truth.hardLeaseDeadlineElapsedRealtimeMs != null

    when (truth.state) {
        TweakerV4SessionState.STOCK -> {
            require(!truth.mutationMayBeActive) { "Stock cannot report possible mutation" }
            require(!truth.leaseRenewable && !hasAnyIdentity) {
                "Stock cannot carry owner, transaction, or lease identity"
            }
        }
        TweakerV4SessionState.APPLYING -> {
            require(truth.requestId != null) { "Applying requires a request identity" }
            if (truth.mutationMayBeActive || truth.generation != null) {
                require(hasRecordIdentity) { "Mutation-bearing apply truth requires complete journal identity" }
            }
            require(!truth.leaseRenewable) { "Applying cannot renew a lease" }
        }
        TweakerV4SessionState.ACTIVE -> {
            require(truth.mutationMayBeActive) { "Active must report possible mutation" }
            require(hasRecordIdentity && truth.leaseDeadlineElapsedRealtimeMs != null) {
                "Active truth requires complete owner and lease identity"
            }
        }
        TweakerV4SessionState.RESTORING,
        TweakerV4SessionState.RECOVERY_FAILED,
        -> {
            require(truth.mutationMayBeActive) { "Recovery truth must report possible mutation" }
            require(hasRecordIdentity) { "Recovery truth requires complete journal identity" }
            require(!truth.leaseRenewable) { "Recovery cannot renew a lease" }
        }
        TweakerV4SessionState.CORRUPT -> {
            require(truth.mutationMayBeActive) { "Corrupt truth must fail closed" }
            require(!truth.leaseRenewable && !hasAnyIdentity) {
                "Corrupt truth cannot assert trustworthy owner identity"
            }
        }
        TweakerV4SessionState.UNKNOWN -> {
            require(truth.mutationMayBeActive) { "Unknown truth must fail closed" }
            require(!truth.leaseRenewable) { "Unknown truth cannot renew a lease" }
        }
    }
}

private fun Bundle.sessionScope(): TweakerSessionScope = optionalString("sessionScope")?.let { raw ->
    runCatching { TweakerSessionScope.valueOf(raw) }.getOrDefault(TweakerSessionScope.UNKNOWN)
} ?: TweakerSessionScope.UNKNOWN

@Suppress("DEPRECATION")
private fun Bundle.requiredInt(key: String): Int =
    requireNotNull(takeIf { containsKey(key) }?.get(key) as? Int) { "Missing or invalid $key" }

@Suppress("DEPRECATION")
private fun Bundle.requiredLong(key: String): Long =
    requireNotNull(takeIf { containsKey(key) }?.get(key) as? Long) { "Missing or invalid $key" }

@Suppress("DEPRECATION")
private fun Bundle.requiredBoolean(key: String): Boolean =
    requireNotNull(takeIf { containsKey(key) }?.get(key) as? Boolean) { "Missing or invalid $key" }

@Suppress("DEPRECATION")
private fun Bundle.requiredString(key: String): String =
    requireNotNull(takeIf { containsKey(key) }?.get(key) as? String) { "Missing or invalid $key" }

@Suppress("DEPRECATION")
private fun Bundle.optionalLong(key: String): Long? {
    if (!containsKey(key)) return null
    return requireNotNull(get(key) as? Long) { "Invalid $key" }
}

@Suppress("DEPRECATION")
private fun Bundle.optionalString(key: String): String? {
    if (!containsKey(key)) return null
    return requireNotNull(get(key) as? String) { "Invalid $key" }
}

private val REQUEST_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val SUCCESS_ONLY_OPERATION_CODES = setOf(
    "APPLIED",
    "CANCEL_REQUESTED",
    "LEASE_RENEWED",
    "RESTORED",
)
