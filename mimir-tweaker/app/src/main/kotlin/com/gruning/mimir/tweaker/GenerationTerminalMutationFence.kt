package com.gruning.mimir.tweaker

import java.security.MessageDigest

/**
 * Fail-closed, generation-scoped protocol used to fence a delayed mutation behind a
 * restore guardian's terminal decision.  This is deliberately a pure renderer/model:
 * it does not know a hardware path or accept a caller supplied shell fragment.
 *
 * The integration seam must stage [session.marker] before arm, make the guardian call
 * [renderTerminalize], and make each typed mutation go through [renderApply].  All three
 * operations take the same exclusive flock.  The terminal tombstone is retained until the
 * coordinator has observed that no apply command can still be in flight.
 */
internal data class GenerationFenceIdentity(
    val bootIdentity: String,
    val generation: Long,
    val transactionId: String,
    val sessionId: String,
) {
    init {
        require(generation > 0) { "generation must be positive" }
        require(IDENTITY_TOKEN.matches(bootIdentity)) { "invalid boot identity" }
        require(IDENTITY_TOKEN.matches(transactionId)) { "invalid transaction identity" }
        require(IDENTITY_TOKEN.matches(sessionId)) { "invalid session identity" }
    }

    internal fun canonicalBytes(): ByteArray =
        "v1|$bootIdentity|$generation|$transactionId|$sessionId".toByteArray(Charsets.UTF_8)

    private companion object {
        val IDENTITY_TOKEN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}

internal data class FenceTimeouts(
    val commandTimeoutSeconds: Int = 5,
    val killGraceSeconds: Int = 1,
) {
    init {
        require(commandTimeoutSeconds in 1..60) { "command timeout must be bounded" }
        require(killGraceSeconds in 1..5) { "kill grace must be bounded" }
    }
}

internal enum class FenceApplyDecision {
    ALLOW,
    LOCK_TIMEOUT,
    MISSING_DIRECTORY,
    IDENTITY_MISMATCH,
    TERMINALIZED,
    CORRUPT_TERMINAL_MARKER,
}

internal data class FenceDiskState(
    val directoryPresent: Boolean,
    val sessionMarkerIdentity: GenerationFenceIdentity?,
    val terminalMarkerIdentity: GenerationFenceIdentity? = null,
    val lockAcquired: Boolean,
)

/** Pure fail-closed evaluator for a process that already holds (or failed to hold) the fence lock. */
internal object GenerationTerminalFencePolicy {
    fun evaluateApply(identity: GenerationFenceIdentity, state: FenceDiskState): FenceApplyDecision = when {
        !state.lockAcquired -> FenceApplyDecision.LOCK_TIMEOUT
        !state.directoryPresent -> FenceApplyDecision.MISSING_DIRECTORY
        state.sessionMarkerIdentity != identity -> FenceApplyDecision.IDENTITY_MISMATCH
        state.terminalMarkerIdentity == identity -> FenceApplyDecision.TERMINALIZED
        state.terminalMarkerIdentity != null -> FenceApplyDecision.CORRUPT_TERMINAL_MARKER
        else -> FenceApplyDecision.ALLOW
    }
}

/**
 * Small model used by host tests to assert the ordering contract independently from shell
 * execution.  A terminal request blocks new applies immediately, waits for an admitted one,
 * then leaves a tombstone.  Cleanup cannot erase that tombstone while any apply exists.
 */
internal class GenerationTerminalFenceLedger(private val identity: GenerationFenceIdentity) {
    private var activeApplies = 0
    private var terminalRequested = false
    private var terminalized = false

    fun beginApply(): FenceApplyDecision = when {
        terminalRequested || terminalized -> FenceApplyDecision.TERMINALIZED
        else -> {
            activeApplies += 1
            FenceApplyDecision.ALLOW
        }
    }

    fun completeApply() {
        check(activeApplies > 0) { "no admitted apply to complete" }
        activeApplies -= 1
        if (activeApplies == 0 && terminalRequested) terminalized = true
    }

    fun requestTerminal(): TerminalRequestResult {
        terminalRequested = true
        return if (activeApplies == 0) {
            terminalized = true
            TerminalRequestResult.TERMINALIZED
        } else {
            TerminalRequestResult.WAITING_FOR_IN_FLIGHT_APPLY
        }
    }

    fun canCleanupTombstone(): Boolean = terminalized && activeApplies == 0

    fun tombstoneIdentity(): GenerationFenceIdentity? = identity.takeIf { terminalized }
}

internal enum class TerminalRequestResult {
    TERMINALIZED,
    WAITING_FOR_IN_FLIGHT_APPLY,
}

/**
 * PServer-facing rendering is intentionally limited to a pinned installed-APK runner and one
 * fixed runner mode. Identity, flock, session-marker, and terminal validation belong to the
 * signed-APK runner after it has loaded authenticated arm truth; no identity-derived path,
 * restore payload, or shell body may enter Binder text.
 */
internal object GenerationTerminalMutationFence {
    private const val ROOT = "/data/local/tmp/mimir_restore_guardian_fence_v1"

    fun directory(identity: GenerationFenceIdentity): String = "$ROOT/${digest(identity)}"

    fun sessionMarker(identity: GenerationFenceIdentity): String = "${directory(identity)}/session.marker"

    fun terminalMarker(identity: GenerationFenceIdentity): String = "${directory(identity)}/terminal.marker"

    fun lockFile(identity: GenerationFenceIdentity): String = "${directory(identity)}/mutation.lock"

    fun renderStage(
        identity: GenerationFenceIdentity,
        source: InstalledTweakerApkSource,
        authority: PinnedTweakerApkAuthority,
        timeouts: FenceTimeouts = FenceTimeouts(),
    ): String = RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.STAGE)

    fun renderTerminalize(
        identity: GenerationFenceIdentity,
        source: InstalledTweakerApkSource,
        authority: PinnedTweakerApkAuthority,
        timeouts: FenceTimeouts = FenceTimeouts(),
    ): String = RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.TERMINALIZE)

    fun renderApply(
        identity: GenerationFenceIdentity,
        source: InstalledTweakerApkSource,
        authority: PinnedTweakerApkAuthority,
        timeouts: FenceTimeouts = FenceTimeouts(),
    ): String = RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.APPLY_UNDER_FENCE)

    fun renderCleanupAfterNoInflightProof(
        identity: GenerationFenceIdentity,
        source: InstalledTweakerApkSource,
        authority: PinnedTweakerApkAuthority,
        timeouts: FenceTimeouts = FenceTimeouts(),
    ): String = RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.CLEANUP)

    private fun digest(identity: GenerationFenceIdentity): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.canonicalBytes())
        .joinToString("") { "%02x".format(it) }
}
