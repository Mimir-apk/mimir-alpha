package com.gruning.mimir.tweaker

/** The PackageManager-only installed source and the certificate digest observed for it. */
internal data class InstalledTweakerApkSource(
    val sourceDir: String,
    val actualSigningCertificateSha256: String,
) {
    init {
        require(CANONICAL_SOURCE_DIR.matches(sourceDir)) { "installed sourceDir is not canonical" }
        require(SHA256.matches(actualSigningCertificateSha256)) { "actual certificate digest is invalid" }
    }

    private companion object {
        val CANONICAL_SOURCE_DIR = Regex("/data/app/[A-Za-z0-9._~+=-]+/[A-Za-z0-9._~+=-]+/base\\.apk")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

/** Pinned app certificate authority. A mismatch produces no PServer statement. */
internal data class PinnedTweakerApkAuthority(
    val expectedSigningCertificateSha256: String,
) {
    init { require(SHA256.matches(expectedSigningCertificateSha256)) { "expected certificate digest is invalid" } }

    fun accepts(source: InstalledTweakerApkSource): Boolean =
        source.actualSigningCertificateSha256 == expectedSigningCertificateSha256

    private companion object { val SHA256 = Regex("[0-9a-f]{64}") }
}

/** Fixed command plus explicit execution disposition; only the future watcher may detach. */
internal data class RestoreGuardianRunnerInvocation(
    val statement: String,
    val detached: Boolean,
)

/**
 * Renders the only permitted PServer statement for the future typed restore runner.
 *
 * The old `/data/local/tmp/mg/g` staging route and `-Djava.class.path` form are deliberately
 * retired. The only variable that enters Binder text is a PackageManager-observed, canonical
 * installed `sourceDir`, and only after its installed signing certificate matches the pinned
 * authority. This remains a model: it does not invoke PServer or mutate hardware.
 */
internal object RestoreGuardianDirectLauncher {
    const val RP5_PROVEN_STATEMENT_MAX_BYTES = 198
    private const val RUNNER_CLASS = "com.gruning.mimir.tweaker.G"

    fun renderOrNull(
        source: InstalledTweakerApkSource,
        authority: PinnedTweakerApkAuthority,
        mode: RestoreGuardianRunnerMode,
    ): RestoreGuardianRunnerInvocation? {
        if (!authority.accepts(source)) return null
        val detached = mode == RestoreGuardianRunnerMode.GUARDIAN_WATCH
        val statement = if (detached) {
            "setsid env CLASSPATH='${source.sourceDir}' app_process /system/bin $RUNNER_CLASS ${mode.argument} &"
        } else {
            "CLASSPATH='${source.sourceDir}' app_process /system/bin $RUNNER_CLASS ${mode.argument}"
        }
        if (statement.toByteArray(Charsets.UTF_8).size > RP5_PROVEN_STATEMENT_MAX_BYTES) return null
        return RestoreGuardianRunnerInvocation(statement, detached)
    }

    fun renderRequired(source: InstalledTweakerApkSource, authority: PinnedTweakerApkAuthority, mode: RestoreGuardianRunnerMode): String =
        requireNotNull(renderOrNull(source, authority, mode)) { "direct guardian invocation rejected" }.statement
}

internal enum class RestoreGuardianRunnerMode(val argument: String) {
    STAGE("s"),
    APPLY_UNDER_FENCE("a"),
    RESTORE_UNDER_FENCE("r"),
    TERMINALIZE("t"),
    CLEANUP("c"),
    /** The sole detached shape, reserved for a future authenticated guardian watcher. */
    GUARDIAN_WATCH("g"),
}

/** Public short app_process alias. It contains no logic; all modes remain inert through [RestoreGuardianMain]. */
object G {
    @JvmStatic
    fun main(args: Array<String>) = RestoreGuardianMain.main(args)
}

/** No hardware operation is available through this entrypoint; integration owns all I/O and launch wiring. */
internal object RestoreGuardianMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "guardian mode missing" }
        require(args.single() in RestoreGuardianRunnerMode.entries.map { it.argument }) { "guardian mode invalid" }
        error("RestoreGuardianMain is inert until authenticated controller wiring is certified")
    }
}

internal data class GuardianLiveIdentity(
    val bootIdentity: String,
    val ownerPid: Int,
    val ownerProcessStartTimeTicks: Long,
) {
    init {
        GuardianEnvelopeValidation.requireToken(bootIdentity, "boot identity")
        require(ownerPid > 0) { "owner PID must be positive" }
        require(ownerProcessStartTimeTicks > 0) { "owner process start time must be positive" }
    }
}

internal enum class GuardianOwnerDecision { OWNER_LIVE, OWNER_GONE, PID_REUSED_OR_MISMATCH, BOOT_MISMATCH }

internal enum class GuardianCommandAdmission { ALLOW, BOOT_MISMATCH, TERMINAL }

/** Every future apply/restore command must sample boot identity immediately before its fence entry. */
internal object GuardianCommandAdmissionPolicy {
    fun evaluate(envelope: RestoreGuardianEnvelope, liveBootIdentity: String?, terminalized: Boolean): GuardianCommandAdmission = when {
        terminalized -> GuardianCommandAdmission.TERMINAL
        liveBootIdentity?.trim() != envelope.bootIdentity -> GuardianCommandAdmission.BOOT_MISMATCH
        else -> GuardianCommandAdmission.ALLOW
    }
}

/** Runner-only fence validation derives the identity from the authenticated envelope, never Binder arguments. */
internal object GuardianRunnerFencePolicy {
    fun identityFromAuthenticatedEnvelope(envelope: RestoreGuardianEnvelope): GenerationFenceIdentity =
        GenerationFenceIdentity(
            bootIdentity = envelope.bootIdentity,
            generation = envelope.generation,
            transactionId = envelope.transactionId,
            sessionId = envelope.taskNonce,
        )

    fun evaluate(
        envelope: RestoreGuardianEnvelope,
        state: FenceDiskState,
        liveBootIdentity: String?,
        terminalized: Boolean,
    ): FenceApplyDecision? = when (GuardianCommandAdmissionPolicy.evaluate(envelope, liveBootIdentity, terminalized)) {
        GuardianCommandAdmission.ALLOW -> GenerationTerminalFencePolicy.evaluateApply(identityFromAuthenticatedEnvelope(envelope), state)
        GuardianCommandAdmission.TERMINAL -> FenceApplyDecision.TERMINALIZED
        GuardianCommandAdmission.BOOT_MISMATCH -> FenceApplyDecision.IDENTITY_MISMATCH
    }
}

/** A live mismatch is a fault, never permission to kill or take over a reused PID. */
internal object GuardianOwnerPolicy {
    fun evaluate(expected: GuardianLiveIdentity, observedBootIdentity: String?, observedPid: Int?, observedStartTimeTicks: Long?): GuardianOwnerDecision = when {
        observedBootIdentity != expected.bootIdentity -> GuardianOwnerDecision.BOOT_MISMATCH
        observedPid == null || observedStartTimeTicks == null -> GuardianOwnerDecision.OWNER_GONE
        observedPid != expected.ownerPid || observedStartTimeTicks != expected.ownerProcessStartTimeTicks -> GuardianOwnerDecision.PID_REUSED_OR_MISMATCH
        else -> GuardianOwnerDecision.OWNER_LIVE
    }
}

internal enum class GuardianFrozenOwnerDecision {
    TERMINATE_EXACT_FROZEN_OWNER,
    OWNER_STILL_ACTIVE,
    REJECT_REUSED_OR_MISMATCH,
    BOOT_MISMATCH,
}

/** Termination eligibility is intentionally narrower than liveness: exact PID, start-time, boot, and frozen proof. */
internal object GuardianFrozenOwnerPolicy {
    fun evaluate(
        expected: GuardianLiveIdentity,
        observedBootIdentity: String?,
        observedPid: Int?,
        observedStartTimeTicks: Long?,
        observedFrozen: Boolean,
    ): GuardianFrozenOwnerDecision = when {
        observedBootIdentity != expected.bootIdentity -> GuardianFrozenOwnerDecision.BOOT_MISMATCH
        observedPid != expected.ownerPid || observedStartTimeTicks != expected.ownerProcessStartTimeTicks -> GuardianFrozenOwnerDecision.REJECT_REUSED_OR_MISMATCH
        observedFrozen -> GuardianFrozenOwnerDecision.TERMINATE_EXACT_FROZEN_OWNER
        else -> GuardianFrozenOwnerDecision.OWNER_STILL_ACTIVE
    }
}

internal sealed interface GuardianObservedState {
    data class Sysfs(val value: Int?, val mode: String?, val uid: Int?, val gid: Int?) : GuardianObservedState
    data class FanMode(val value: Int?) : GuardianObservedState

    /** The live `performance_mode`; null when unreadable. Distinct from [FanMode] so a fan read can never satisfy a vendor entry. */
    data class VendorMode(val value: Int?) : GuardianObservedState
}

internal enum class GuardianRestoreDecision {
    ALREADY_STOCK,
    WRITE_REQUIRED,
    INVALID_OBSERVATION,

    /**
     * Vendor mode only: the live `performance_mode` is neither Mimir's applied value nor the
     * origin, so the PLAYER changed it and their choice stands. Nothing is written
     * ([VendorModeRestoreAction.SKIP_PLAYER_CHANGED]); it is not [ALREADY_STOCK], so the readback
     * policy still NAMES it rather than silently counting it as restored.
     */
    PLAYER_CHANGED,
}

/**
 * Pure compare-before-write and full readback policy.
 *
 * CORRECTED 2026-09-06 (guardian map §4.8): this used to say "its port is intentionally unwired",
 * which read as a claim that this policy has no live caller. It does: [decide] runs live from
 * [RestoreGuardianRunnerEngine.planRestore] and from [GuardianDoubleStockReadbackPolicy.evaluate].
 * There is no separate port/adapter abstraction for this object to be unwired FROM -- it is a
 * plain function called directly, and "port" in the original sentence could not be disambiguated
 * from source alone.
 */
internal object GuardianTypedRestorePolicy {
    fun decide(entry: RestoreGuardianRestoreEntry, observed: GuardianObservedState): GuardianRestoreDecision = when (entry) {
        is RestoreGuardianSysfsEntry -> matchSysfs(entry.expectedStock, observed)
        is RestoreGuardianFanPutEntry -> matchFan(entry.expectedStock, observed)
        is RestoreGuardianFanDeleteEntry -> when (observed) {
            is GuardianObservedState.FanMode -> if (observed.value == null) GuardianRestoreDecision.ALREADY_STOCK else GuardianRestoreDecision.WRITE_REQUIRED
            else -> GuardianRestoreDecision.INVALID_OBSERVATION
        }
        is RestoreGuardianVendorModePutEntry -> matchVendorMode(entry, observed)
    }

    fun exactReadback(entry: RestoreGuardianRestoreEntry, observed: GuardianObservedState): Boolean =
        decide(entry, observed) == GuardianRestoreDecision.ALREADY_STOCK

    /**
     * Whether a restore of [entry] LANDED: exactly Stock, or -- for the vendor mode only -- left
     * alone because the player moved it. The one thing that is not a landed restore is the vendor
     * mode still reading Mimir's own value (or not reading at all). Mirrors the daemon-side
     * `readbackVendorMode` restore rule so the two processes cannot disagree about what "restored"
     * means for a key that belongs to the player.
     */
    fun restoreLanded(entry: RestoreGuardianRestoreEntry, observed: GuardianObservedState): Boolean =
        when (decide(entry, observed)) {
            GuardianRestoreDecision.ALREADY_STOCK -> true
            GuardianRestoreDecision.PLAYER_CHANGED -> true
            GuardianRestoreDecision.WRITE_REQUIRED, GuardianRestoreDecision.INVALID_OBSERVATION -> false
        }

    /**
     * The three-way rule ([VendorModeOriginPolicy.decideRestore]) over the entry's persisted origin and
     * applied values: live == origin is already restored (checked FIRST, so an entry whose applied
     * value equals its origin can never ask for a write), live == applied is Mimir's own value and
     * must be written back, anything else is the player's choice.
     */
    private fun matchVendorMode(entry: RestoreGuardianVendorModePutEntry, observed: GuardianObservedState): GuardianRestoreDecision =
        when (observed) {
            is GuardianObservedState.VendorMode -> when (VendorModeOriginPolicy.decideRestore(entry.origin, entry.applied, observed.value)) {
                VendorModeRestoreAction.ALREADY_AT_ORIGIN -> GuardianRestoreDecision.ALREADY_STOCK
                VendorModeRestoreAction.WRITE_ORIGIN -> GuardianRestoreDecision.WRITE_REQUIRED
                VendorModeRestoreAction.SKIP_PLAYER_CHANGED -> GuardianRestoreDecision.PLAYER_CHANGED
                VendorModeRestoreAction.UNREADABLE -> GuardianRestoreDecision.INVALID_OBSERVATION
            }
            else -> GuardianRestoreDecision.INVALID_OBSERVATION
        }

    private fun matchSysfs(expected: RestoreGuardianStockState.PresentSysfs, observed: GuardianObservedState): GuardianRestoreDecision = when (observed) {
        is GuardianObservedState.Sysfs -> if (
            observed.value == expected.value && observed.mode == expected.mode &&
            observed.uid == expected.uid && observed.gid == expected.gid
        ) GuardianRestoreDecision.ALREADY_STOCK else GuardianRestoreDecision.WRITE_REQUIRED
        else -> GuardianRestoreDecision.INVALID_OBSERVATION
    }

    /**
     * TR-08 as an policy (2026-10-13), the Guardian's production gate: a captured SILENT or SPORT
     * fan restores to Smart and Smart counts as restored -- STRICTLY `observed == FanRestoreTarget.of(captured)`,
     * never "captured or Smart", so a loop-stopper still live on the device is WRITE_REQUIRED, not stock.
     * The same rule the daemon's port writes by and its readbacks demand, from one shared object.
     */
    private fun matchFan(expected: RestoreGuardianStockState.PresentFanMode, observed: GuardianObservedState): GuardianRestoreDecision = when (observed) {
        is GuardianObservedState.FanMode ->
            if (FanRestoreTarget.restored(expected.value, observed.value)) GuardianRestoreDecision.ALREADY_STOCK else GuardianRestoreDecision.WRITE_REQUIRED
        else -> GuardianRestoreDecision.INVALID_OBSERVATION
    }
}

internal data class GuardianStockReadback(
    val entries: Map<String, GuardianObservedState>,
    val observedElapsedRealtimeMs: Long,
)

internal enum class GuardianDoubleStockDecision {
    FIRST_READBACK_NOT_STOCK,
    SETTLE_GAP_TOO_SHORT,
    SECOND_READBACK_NOT_STOCK,
    STOCK_CONFIRMED,
}

/** Two complete, independently sampled Stock readbacks are required before terminal success. */
internal object GuardianDoubleStockReadbackPolicy {
    const val MIN_SETTLE_GAP_MS = 75L

    fun evaluate(
        entries: List<RestoreGuardianRestoreEntry>,
        first: GuardianStockReadback,
        second: GuardianStockReadback,
        minSettleGapMs: Long = MIN_SETTLE_GAP_MS,
    ): GuardianDoubleStockDecision {
        require(minSettleGapMs > 0) { "settle gap must be positive" }
        // Only the axes a withdrawal can actually guarantee get a vote -- see [vetoesWindowedVerification].
        // An empty list confirms vacuously: a GPU-only plan has nothing left to verify, the withdrawal
        // write still happened, and the alternative is a device that can never leave the profile.
        val deciding = entries.filter { vetoesWindowedVerification(it.logicalId) }
        // The vendor mode is the PLAYER'S, so it never vetoes on the player's own choice (it is named
        // under `unheld=` instead, see [describe]). What it must veto is a restore that did not land:
        // Mimir's own value still live, or a key that cannot be read at all. Settling Stock on a
        // `performance_mode` we cannot vouch for would leave the player on Mimir's mode forever.
        val vendor = entries.filterIsInstance<RestoreGuardianVendorModePutEntry>()
        fun vendorLanded(readback: GuardianStockReadback): Boolean = vendor.all { entry ->
            readback.entries[entry.logicalId]?.let { GuardianTypedRestorePolicy.restoreLanded(entry, it) } == true
        }
        if (!deciding.all { entry -> first.entries[entry.logicalId]?.let { GuardianTypedRestorePolicy.exactReadback(entry, it) } == true } || !vendorLanded(first)) {
            return GuardianDoubleStockDecision.FIRST_READBACK_NOT_STOCK
        }
        if (second.observedElapsedRealtimeMs - first.observedElapsedRealtimeMs < minSettleGapMs) {
            return GuardianDoubleStockDecision.SETTLE_GAP_TOO_SHORT
        }
        return if (deciding.all { entry -> second.entries[entry.logicalId]?.let { GuardianTypedRestorePolicy.exactReadback(entry, it) } == true } && vendorLanded(second)) {
            GuardianDoubleStockDecision.STOCK_CONFIRMED
        } else {
            GuardianDoubleStockDecision.SECOND_READBACK_NOT_STOCK
        }
    }

    /**
     * One line naming the decision and, when it is a mismatch, WHICH axes disagreed.
     *
     * Pure and side-effect free so it stays unit-testable; the caller decides whether to log it.
     *
     * **Why this exists.** On 2026-08-30 a Nova test device held at `STOCK_UNCONFIRMED` across three
     * daemon installs and every force-stop, and the daemon could not say which of this policy's
     * four decisions produced it — every log in the guardian process sits behind
     * `BuildConfig.DIAGNOSTICS_COMPILED`, which is false in PRODUCTION, the posture used in that report. Worse, the two decisions have different causes that this policy deliberately
     * collapses: an axis MISSING from the readback (unreadable node, node absent after a boot) and
     * an axis PRESENT with the wrong value both produce `FIRST_READBACK_NOT_STOCK`, because the
     * gate is `map[id]?.let { exact } == true`. Naming `absent` separately is the whole point of
     * this function.
     *
     * **Disclosure.** `logicalId` only — never `RestoreGuardianSysfsEntry.path`, which is a
     * filesystem path and excluded by the same rule that redacts paths out of the restore log.
     * Values are CPU/GPU ceilings and fan modes: hardware numbers, never user-library content.
     * Bounded to [MAX_NAMED_MISMATCHES] so one line cannot become a wall on a large entry set.
     */
    fun describe(
        entries: List<RestoreGuardianRestoreEntry>,
        first: GuardianStockReadback,
        second: GuardianStockReadback,
        minSettleGapMs: Long = MIN_SETTLE_GAP_MS,
    ): String {
        val decision = evaluate(entries, first, second, minSettleGapMs)
        if (decision == GuardianDoubleStockDecision.STOCK_CONFIRMED) {
            // Confirmation is no longer the same thing as "everything matched". An axis that stopped
            // vetoing is still NAMED here, because silence on the deciding branch is exactly the
            // 08-30 failure this function was written to end -- suppressing a veto must not suppress
            // the observation. Rendered off the SECOND readback: the later of the two is the one that
            // says whether the axis was still adrift after the settle gap.
            val unheld = namedMismatches(entries.filterNot { vetoesWindowedVerification(it.logicalId) }, second)
            if (unheld.isEmpty()) return decision.name
            return decision.name + " unheld=" + unheld.take(MAX_NAMED_MISMATCHES).joinToString(",")
        }
        val readback = when (decision) {
            GuardianDoubleStockDecision.SECOND_READBACK_NOT_STOCK -> second
            else -> first
        }
        val mismatches = namedMismatches(entries, readback)
        val gapMs = second.observedElapsedRealtimeMs - first.observedElapsedRealtimeMs
        val named = mismatches.take(MAX_NAMED_MISMATCHES).joinToString(",")
        val overflow = (mismatches.size - MAX_NAMED_MISMATCHES).coerceAtLeast(0)
        return buildString {
            append(decision.name)
            append(" entries=").append(entries.size)
            append(" mismatched=").append(mismatches.size)
            append(" gapMs=").append(gapMs)
            if (named.isNotEmpty()) append(" axes=").append(named)
            if (overflow > 0) append("+").append(overflow).append("more")
        }
    }

    /** `id=absent` or `id=observed!=expected`, in entry order, for whichever entries are asked about. */
    private fun namedMismatches(
        entries: List<RestoreGuardianRestoreEntry>,
        readback: GuardianStockReadback,
    ): List<String> = entries.mapNotNull { entry ->
        val observed = readback.entries[entry.logicalId]
        when {
            // An unholdable axis MISSING from the readback stops vetoing too, not just one holding a
            // wrong value. Deliberate: an unreadable GPU node is the same "the platform will not let
            // us hold this" that the holdability exception covers, and it is still named on the line below.
            observed == null -> "${entry.logicalId}=absent"
            !GuardianTypedRestorePolicy.exactReadback(entry, observed) ->
                "${entry.logicalId}=${renderObserved(observed)}!=${renderExpected(entry)}"
            else -> null
        }
    }

    /**
     * **Every field [GuardianTypedRestorePolicy.matchSysfs] compares, not just the value.** It
     * requires `value && mode && uid && gid` to be exactly equal, so a line carrying only the value
     * can show a number that looks perfectly correct beside a mismatch that is really a permissions
     * change on the node. The first version of this function did exactly that, and it would have
     * sent the next reader at the wrong mechanism -- which is the failure mode this whole
     * diagnostic exists to end, reproduced inside the diagnostic itself.
     *
     * Rendered as `value/mode/uid/gid` so the mismatching component is visible at a glance, with
     * the expected side beside it at the call site.
     */
    private fun renderObserved(state: GuardianObservedState): String = when (state) {
        is GuardianObservedState.Sysfs ->
            "${state.value ?: "null"}/${state.mode ?: "null"}/${state.uid ?: "null"}/${state.gid ?: "null"}"
        is GuardianObservedState.FanMode -> "fan${state.value ?: "null"}"
        is GuardianObservedState.VendorMode -> "vmode${state.value ?: "null"}"
    }

    /** The captured side, in the same shape, so a reader can diff the two without the envelope. */
    private fun renderExpected(entry: RestoreGuardianRestoreEntry): String =
        when (val expected = entry.expectedStock) {
            is RestoreGuardianStockState.PresentSysfs ->
                "${expected.value ?: "null"}/${expected.mode ?: "null"}/" +
                    "${expected.uid ?: "null"}/${expected.gid ?: "null"}"
            // The value the readback DEMANDS (FanRestoreTarget), with the captured one beside it when
            // TR-08 redirected: `fan4(captured1)` says why a line reads fan4 against a journal holding 1.
            is RestoreGuardianStockState.PresentFanMode -> expected.value?.let { captured ->
                val target = FanRestoreTarget.of(captured)
                if (target == captured) "fan$captured" else "fan$target(captured$captured)"
            } ?: "fannull"
            is RestoreGuardianStockState.PresentVendorMode -> "vmode${expected.value}"
            else -> "unknown"
        }

    private const val MAX_NAMED_MISMATCHES = 6
}
