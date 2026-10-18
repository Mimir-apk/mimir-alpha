package com.gruning.mimir.rewrite

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class OnboardingScanStatus {
    NOT_SELECTED,
    QUEUED,
    RUNNING,
    COMPLETE,
    FAILED,
}

internal enum class OnboardingSettlementStatus {
    PENDING,
    READY,
    LIMITED,
}

internal data class OnboardingCheckpoint(
    val version: Int = OnboardingStore.CURRENT_VERSION,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val selectedTreeUri: String? = null,
    val scanGeneration: String? = null,
    val scanStatus: OnboardingScanStatus = OnboardingScanStatus.NOT_SELECTED,
    val scanFilesSeen: Int = 0,
    val scanRecognized: Int = 0,
    val scanGroups: Int = 0,
    val scanSkipped: Int = 0,
    val scanFailure: String? = null,
    val writeAccessGranted: Boolean = false,
    val settlementStatus: OnboardingSettlementStatus = OnboardingSettlementStatus.PENDING,
    val settlementReason: String? = null,
    /**
     * RC-13: durable "has this onboarding run ever left the ACCESS sub-sequence's ORIENTATION
     * preamble." [OnboardingAccessReviewState.currentScreen] (OnboardingFlow.kt) is deliberately
     * ephemeral — a genuine Activity/process recreation mid-ACCESS (e.g. the HOME_ROLE grant
     * relaunching the app) wipes it back to null, and a null anchor with no way to tell "never
     * visited" from "visited, lost my place" always resolves to the first unresolved screen, which
     * is always ORIENTATION (it has no grant/defer state of its own to ever mark it resolved). This
     * bit is the only durable signal that tells those two cases apart, without persisting the
     * anchor screen itself — see [onboardingAccessCurrentScreen]'s doc for why persisting the raw
     * anchor would soft-lock the sub-sequence instead.
     */
    val accessOrientationSeen: Boolean = false,
)

internal fun OnboardingCheckpoint.beginScan(
    treeUri: String,
    generation: String,
    writeAccessGranted: Boolean,
): OnboardingCheckpoint = copy(
    step = OnboardingStep.LIBRARY,
    selectedTreeUri = treeUri,
    scanGeneration = generation,
    scanStatus = OnboardingScanStatus.QUEUED,
    scanFilesSeen = 0,
    scanRecognized = 0,
    scanGroups = 0,
    scanSkipped = 0,
    scanFailure = null,
    writeAccessGranted = writeAccessGranted,
    settlementStatus = OnboardingSettlementStatus.PENDING,
    settlementReason = null,
)

internal fun OnboardingCheckpoint.updateScan(
    generation: String,
    status: OnboardingScanStatus,
    filesSeen: Int = scanFilesSeen,
    recognized: Int = scanRecognized,
    groups: Int = scanGroups,
    skipped: Int = scanSkipped,
    failure: String? = null,
): OnboardingCheckpoint? {
    if (scanGeneration != generation) return null
    return copy(
        scanStatus = status,
        scanFilesSeen = filesSeen,
        scanRecognized = recognized,
        scanGroups = groups,
        scanSkipped = skipped,
        scanFailure = failure,
    )
}

/**
 * Durable first-run checkpoint. Completion is still the startup gate, while every in-flight step is also
 * persisted so process death resumes the same setup transaction instead of replaying Welcome or exposing a
 * half-indexed shelf. Scan writes are guarded by a generation token; a replaced WorkManager job cannot
 * complete a newer folder selection.
 *
 * Same tiny [android.content.SharedPreferences] singleton shape as [ThemeStore] / [ProfileNameStore].
 * Read synchronously at startup (before first composition) so a fresh install boots straight into the
 * [MimirDestination.ONBOARDING] flow, and every relaunch after completion boots straight to the shelf —
 * no re-onboarding. Local-only; this is a device-scoped preference, never network.
 *
 * An explicit flag (not "is the library empty?") on purpose: a user who deliberately keeps zero source
 * folders must not be re-onboarded forever .
 */
object OnboardingStore {
    internal const val CURRENT_VERSION = 2

    private const val PREFS = "mimir_rewrite_onboarding"
    private const val KEY_COMPLETE = "onboardingComplete"
    private const val KEY_VERSION = "checkpointVersion"
    private const val KEY_STEP = "step"
    private const val KEY_TREE_URI = "treeUri"
    private const val KEY_SCAN_GENERATION = "scanGeneration"
    private const val KEY_SCAN_STATUS = "scanStatus"
    private const val KEY_FILES_SEEN = "scanFilesSeen"
    private const val KEY_RECOGNIZED = "scanRecognized"
    private const val KEY_GROUPS = "scanGroups"
    private const val KEY_SKIPPED = "scanSkipped"
    private const val KEY_SCAN_FAILURE = "scanFailure"
    private const val KEY_WRITE_ACCESS = "writeAccess"
    private const val KEY_SETTLEMENT_STATUS = "settlementStatus"
    private const val KEY_SETTLEMENT_REASON = "settlementReason"
    private const val KEY_ACCESS_ORIENTATION_SEEN = "accessOrientationSeen"

    private val mutableCheckpoint = MutableStateFlow(OnboardingCheckpoint())
    private var loadedPackageName: String? = null

    /** True on a genuine first run (nothing persisted yet). Cheap synchronous read at startup. */
    fun isFirstRun(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_COMPLETE, false)

    @Synchronized
    internal fun checkpoint(context: Context): OnboardingCheckpoint {
        ensureLoaded(context)
        return mutableCheckpoint.value
    }

    @Synchronized
    internal fun checkpoints(context: Context): StateFlow<OnboardingCheckpoint> {
        ensureLoaded(context)
        return mutableCheckpoint.asStateFlow()
    }

    @Synchronized
    internal fun moveTo(context: Context, step: OnboardingStep): Boolean {
        ensureLoaded(context)
        return persist(context, mutableCheckpoint.value.copy(step = step))
    }

    @Synchronized
    internal fun beginScan(
        context: Context,
        treeUri: String,
        generation: String,
        writeAccessGranted: Boolean,
    ): Boolean {
        ensureLoaded(context)
        return persist(
            context,
            mutableCheckpoint.value.beginScan(treeUri, generation, writeAccessGranted),
        )
    }

    @Synchronized
    internal fun markScanRunning(context: Context, generation: String): Boolean =
        updateScan(context, generation, OnboardingScanStatus.RUNNING)

    @Synchronized
    internal fun markScanComplete(
        context: Context,
        generation: String,
        filesSeen: Int,
        recognized: Int,
        groups: Int,
        skipped: Int,
    ): Boolean = updateScan(
        context = context,
        generation = generation,
        status = OnboardingScanStatus.COMPLETE,
        filesSeen = filesSeen,
        recognized = recognized,
        groups = groups,
        skipped = skipped,
    )

    @Synchronized
    internal fun markScanFailed(context: Context, generation: String, reason: String): Boolean =
        updateScan(
            context = context,
            generation = generation,
            status = OnboardingScanStatus.FAILED,
            failure = reason,
        )

    @Synchronized
    internal fun markSettlement(
        context: Context,
        status: OnboardingSettlementStatus,
        reason: String? = null,
    ): Boolean {
        ensureLoaded(context)
        return persist(
            context,
            mutableCheckpoint.value.copy(
                settlementStatus = status,
                settlementReason = reason,
            ),
        )
    }

    /**
     * RC-13 funnel: the single place the durable "left ORIENTATION" bit is written. Idempotent — a
     * no-op once already true, so callers can invoke it freely from every input path (touch and
     * gamepad) without worrying about redundant commits.
     */
    @Synchronized
    internal fun markAccessOrientationSeen(context: Context): Boolean {
        ensureLoaded(context)
        if (mutableCheckpoint.value.accessOrientationSeen) return true
        return persist(context, mutableCheckpoint.value.copy(accessOrientationSeen = true))
    }

    /** Mark onboarding done only after the durable write succeeds. */
    @Synchronized
    fun markComplete(context: Context): Boolean {
        ensureLoaded(context)
        val committed = prefs(context).edit()
            .clear()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putBoolean(KEY_COMPLETE, true)
            .putString(KEY_STEP, OnboardingStep.DONE.name)
            .commit()
        if (committed) mutableCheckpoint.value = OnboardingCheckpoint(step = OnboardingStep.DONE)
        return committed
    }

    @Synchronized
    private fun updateScan(
        context: Context,
        generation: String,
        status: OnboardingScanStatus,
        filesSeen: Int = mutableCheckpoint.value.scanFilesSeen,
        recognized: Int = mutableCheckpoint.value.scanRecognized,
        groups: Int = mutableCheckpoint.value.scanGroups,
        skipped: Int = mutableCheckpoint.value.scanSkipped,
        failure: String? = null,
    ): Boolean {
        ensureLoaded(context)
        val updated = mutableCheckpoint.value.updateScan(
            generation = generation,
            status = status,
            filesSeen = filesSeen,
            recognized = recognized,
            groups = groups,
            skipped = skipped,
            failure = failure,
        ) ?: return false
        return persist(context, updated)
    }

    private fun ensureLoaded(context: Context) {
        val packageName = context.applicationContext.packageName
        if (loadedPackageName == packageName) return
        mutableCheckpoint.value = read(context)
        loadedPackageName = packageName
    }

    private fun read(context: Context): OnboardingCheckpoint {
        val prefs = prefs(context)
        fun <T : Enum<T>> enumValue(raw: String?, fallback: T, values: Array<T>): T =
            values.firstOrNull { it.name == raw } ?: fallback

        return OnboardingCheckpoint(
            version = prefs.getInt(KEY_VERSION, CURRENT_VERSION),
            step = enumValue(prefs.getString(KEY_STEP, null), OnboardingStep.WELCOME, OnboardingStep.entries.toTypedArray()),
            selectedTreeUri = prefs.getString(KEY_TREE_URI, null),
            scanGeneration = prefs.getString(KEY_SCAN_GENERATION, null),
            scanStatus = enumValue(
                prefs.getString(KEY_SCAN_STATUS, null),
                OnboardingScanStatus.NOT_SELECTED,
                OnboardingScanStatus.entries.toTypedArray(),
            ),
            scanFilesSeen = prefs.getInt(KEY_FILES_SEEN, 0),
            scanRecognized = prefs.getInt(KEY_RECOGNIZED, 0),
            scanGroups = prefs.getInt(KEY_GROUPS, 0),
            scanSkipped = prefs.getInt(KEY_SKIPPED, 0),
            scanFailure = prefs.getString(KEY_SCAN_FAILURE, null),
            writeAccessGranted = prefs.getBoolean(KEY_WRITE_ACCESS, false),
            settlementStatus = enumValue(
                prefs.getString(KEY_SETTLEMENT_STATUS, null),
                OnboardingSettlementStatus.PENDING,
                OnboardingSettlementStatus.entries.toTypedArray(),
            ),
            settlementReason = prefs.getString(KEY_SETTLEMENT_REASON, null),
            accessOrientationSeen = prefs.getBoolean(KEY_ACCESS_ORIENTATION_SEEN, false),
        )
    }

    private fun persist(context: Context, checkpoint: OnboardingCheckpoint): Boolean {
        // A finished setup has no checkpoint: markComplete cleared it. A write that lands after FINISH (a settlement
        // or scan result racing the last page) is stale by definition, and writing it would also store
        // onboardingComplete = false, sending the player back through first run on the next launch. Refused instead.
        if (prefs(context).getBoolean(KEY_COMPLETE, false)) return false
        val editor = prefs(context).edit()
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putBoolean(KEY_COMPLETE, false)
            .putString(KEY_STEP, checkpoint.step.name)
            .putString(KEY_TREE_URI, checkpoint.selectedTreeUri)
            .putString(KEY_SCAN_GENERATION, checkpoint.scanGeneration)
            .putString(KEY_SCAN_STATUS, checkpoint.scanStatus.name)
            .putInt(KEY_FILES_SEEN, checkpoint.scanFilesSeen)
            .putInt(KEY_RECOGNIZED, checkpoint.scanRecognized)
            .putInt(KEY_GROUPS, checkpoint.scanGroups)
            .putInt(KEY_SKIPPED, checkpoint.scanSkipped)
            .putString(KEY_SCAN_FAILURE, checkpoint.scanFailure)
            .putBoolean(KEY_WRITE_ACCESS, checkpoint.writeAccessGranted)
            .putString(KEY_SETTLEMENT_STATUS, checkpoint.settlementStatus.name)
            .putString(KEY_SETTLEMENT_REASON, checkpoint.settlementReason)
            .putBoolean(KEY_ACCESS_ORIENTATION_SEEN, checkpoint.accessOrientationSeen)
        val committed = editor.commit()
        if (committed) mutableCheckpoint.value = checkpoint
        return committed
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
