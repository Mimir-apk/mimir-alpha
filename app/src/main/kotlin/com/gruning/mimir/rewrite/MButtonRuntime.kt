package com.gruning.mimir.rewrite

import android.app.AppOpsManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayAction
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayRole
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayTarget
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuItem
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class MButtonEligibility(
    val presentationDisplayId: Int?,
    val primaryEnabled: Boolean,
    val usageAccessGranted: Boolean,
    val onboardingComplete: Boolean,
    val overlayPermissionGranted: Boolean = true,
)

/** One user-facing policy: Disabled is the button off; Always visible disables only the idle timer. */
internal enum class MButtonVisibilityPolicy(
    val timeoutSeconds: Int?,
    val buttonEnabled: Boolean,
    val displayLabel: String,
) {
    DISABLED(null, false, "Disabled"),
    HIDE_AFTER_15_SECONDS(15, true, "15 seconds"),
    HIDE_AFTER_30_SECONDS(30, true, "30 seconds"),
    HIDE_AFTER_60_SECONDS(60, true, "60 seconds"),
    HIDE_AFTER_120_SECONDS(120, true, "120 seconds"),
    ALWAYS_VISIBLE(null, true, "Always visible"),
    ;

    val timeoutMillis: Long?
        get() = timeoutSeconds?.times(1_000L)

    fun step(direction: Int): MButtonVisibilityPolicy {
        if (direction == 0) return this
        val next = ordinal + if (direction < 0) -1 else 1
        return entries[next.coerceIn(0, entries.lastIndex)]
    }
}

internal data class MButtonIdleDeadline(
    val generation: Long,
    val policy: MButtonVisibilityPolicy,
    val delayMillis: Long,
)

/** Pure latest-wins ledger so stale Handler callbacks cannot hide a newly revealed or retargeted M. */
internal class MButtonIdleDeadlineLedger {
    private var generation = 0L

    fun arm(policy: MButtonVisibilityPolicy): MButtonIdleDeadline? {
        generation += 1
        val delay = policy.timeoutMillis ?: return null
        return MButtonIdleDeadline(generation, policy, delay)
    }

    fun cancel() {
        generation += 1
    }

    fun accepts(
        deadline: MButtonIdleDeadline,
        currentPolicy: MButtonVisibilityPolicy,
        mButtonActive: Boolean,
    ): Boolean = mButtonActive &&
        deadline.generation == generation &&
        deadline.policy == currentPolicy
}

internal fun resolveMButtonVisibilityPolicy(
    storedPolicyName: String?,
    legacyEnabled: Boolean,
): MButtonVisibilityPolicy = storedPolicyName
    ?.let { stored -> MButtonVisibilityPolicy.entries.firstOrNull { it.name == stored } }
    ?: if (legacyEnabled) MButtonVisibilityPolicy.ALWAYS_VISIBLE else MButtonVisibilityPolicy.DISABLED

/**
 * Stay visible over apps: the Bottom ᛗ never idle-hides while an app covers the Bottom,
 * so tapping it never touches the app. It can still hide over Mimir's own shelf.
 *
 * The cover evidence is the runtime's [SecondaryDisplayDisposition.Yielded], which it enters only with HOST_COVERED
 * evidence: Mimir's return host on the Bottom reported itself covered by the target it dispatched
 * (`CompanionReturnActivity`), a failed reclaim retreated to a host whose app is still in front, or a task Mimir did not launch
 * covered the floor and was named by a resume ([CompanionFloorCoverWatch], D41).
 *
 * Scope of this behavior: every other disposition keeps today's idle hide. That includes the ones where an app may cover
 * the Bottom without that evidence (RecoveryNeeded after a dispatch, a destroyed host or a process recovery) and a Main
 * app's own Presentation on the Bottom, which the runtime never sees.
 */
/**
 * The ᛗ is enabled by default once onboarding completes, regardless of display count.
 *
 * The seed used to be gated on `presentationDisplayId != null`, so it only fired on a chassis that had
 * resolved a Bottom. On the Thor that is always true and the ᛗ appeared; on a single-screen handheld the
 * gate never opened, [resolveMButtonVisibilityPolicy] kept answering DISABLED for a fresh install, and
 * the button could only be turned on by hand in Utility. Same build, opposite behaviour, decided by how
 * many displays the chassis happens to have, instead of the shared default.
 *
 * The seeded value stays [MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS] on both paths. Keeping them
 * identical is also what makes the seed safe on a cold start where the Bottom has not resolved yet: a
 * transiently null display id can no longer seed a different policy than the settled topology would.
 *
 * `defaultUninitialized` is the stored marker, not an inference. An install that was told DISABLED on
 * purpose carries it, so this never re-flips a choice the player already made.
 */
internal fun shouldSeedDefaultMButtonPolicy(
    policy: MButtonVisibilityPolicy,
    defaultUninitialized: Boolean,
): Boolean = policy == MButtonVisibilityPolicy.DISABLED && defaultUninitialized

internal fun mButtonBottomCoveredByForeignApp(disposition: SecondaryDisplayDisposition): Boolean =
    disposition is SecondaryDisplayDisposition.Yielded

/** Why an idle deadline leaves the ᛗ up instead of hiding it; [logReason] is the idle log line's `reason=`. */
internal enum class MButtonIdleHideSuppression(val logReason: String) {
    FOREIGN_APP_ON_BOTTOM("foreign_app_on_bottom"),
}

/** O9 is a Bottom rule: the single-display ᛗ on Main keeps its idle hide whatever the runtime reports. */
internal fun mButtonIdleHideSuppression(
    role: CompanionOverlayRole,
    bottomCoveredByForeignApp: Boolean,
): MButtonIdleHideSuppression? =
    MButtonIdleHideSuppression.FOREIGN_APP_ON_BOTTOM.takeIf {
        role == CompanionOverlayRole.SECONDARY_COMPANION && bottomCoveredByForeignApp
    }

/** Library-only controller legend preference; it never gates Home prompts, runes, status, or global M. */
internal enum class NavigationHintVisibility(
    val enabled: Boolean,
    val displayLabel: String,
) {
    OFF(false, "Off"),
    ON(true, "On"),
    ;

    fun step(direction: Int): NavigationHintVisibility {
        if (direction == 0) return this
        val next = ordinal + if (direction < 0) -1 else 1
        return entries[next.coerceIn(0, entries.lastIndex)]
    }
}

internal fun resolveNavigationHintVisibility(storedName: String?): NavigationHintVisibility =
    storedName?.let { stored -> NavigationHintVisibility.entries.firstOrNull { it.name == stored } }
        ?: NavigationHintVisibility.ON

/** Separate persistence keeps Library legend visibility independent from primary M-button policy. */
internal object NavigationHintSettingsStore {
    private const val PreferencesName = "mimir_navigation_hints"
    private const val VisibilityKey = "library_controller_legend_visibility"
    private val visibilityMutable = MutableStateFlow(NavigationHintVisibility.ON)
    private var initialized = false

    val visibility: StateFlow<NavigationHintVisibility> = visibilityMutable.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val policy = resolveNavigationHintVisibility(preferences.getString(VisibilityKey, null))
        visibilityMutable.value = policy
        initialized = true
    }

    @Synchronized
    fun setVisibility(context: Context, visibility: NavigationHintVisibility): Boolean {
        initialize(context)
        if (visibilityMutable.value == visibility) return true
        context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(VisibilityKey, visibility.name)
            .apply()
        visibilityMutable.value = visibility
        return true
    }
}

/** Real display topology wins. A dual device never receives a duplicate primary M button. */
internal fun selectMButtonOverlayTarget(eligibility: MButtonEligibility): CompanionOverlayTarget? {
    if (!eligibility.overlayPermissionGranted) return null
    eligibility.presentationDisplayId?.takeIf { eligibility.primaryEnabled }?.let { displayId ->
        return CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, displayId)
    }
    return if (
        eligibility.primaryEnabled &&
        eligibility.usageAccessGranted &&
        eligibility.onboardingComplete
    ) {
        CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, Display.DEFAULT_DISPLAY)
    } else {
        null
    }
}

/** Explicit user preference. Permission loss never clears it. */
internal object MButtonSettingsStore {
    private const val PreferencesName = "mimir_m_button"
    private const val EnabledKey = "primary_enabled"
    private const val VisibilityPolicyKey = "primary_visibility_policy"
    // The stored key keeps its original spelling on purpose. It is the marker that says "this install
    // has already had its default decided", and an install that chose Disabled deliberately carries it.
    // Renaming it would drop that marker and re-seed the button over the owner's own choice.
    private const val SecondaryDefaultInitializedKey = "secondary_default_initialized"
    private val enabledMutable = MutableStateFlow(false)
    private val visibilityPolicyMutable = MutableStateFlow(MButtonVisibilityPolicy.DISABLED)
    private var initialized = false
    private var defaultPolicyUninitialized = false

    val enabled: StateFlow<Boolean> = enabledMutable.asStateFlow()
    val visibilityPolicy: StateFlow<MButtonVisibilityPolicy> = visibilityPolicyMutable.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val storedPolicyName = preferences.getString(VisibilityPolicyKey, null)
        val legacyEnabled = preferences.getBoolean(EnabledKey, false)
        val policy = resolveMButtonVisibilityPolicy(
            storedPolicyName = storedPolicyName,
            legacyEnabled = legacyEnabled,
        )
        defaultPolicyUninitialized =
            !preferences.getBoolean(SecondaryDefaultInitializedKey, false) &&
                !legacyEnabled &&
                policy == MButtonVisibilityPolicy.DISABLED
        if (storedPolicyName != policy.name || legacyEnabled != policy.buttonEnabled) {
            preferences.edit()
                .putString(VisibilityPolicyKey, policy.name)
                .putBoolean(EnabledKey, policy.buttonEnabled)
                .commit()
        }
        visibilityPolicyMutable.value = policy
        enabledMutable.value = policy.buttonEnabled
        initialized = true
    }

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        initialize(context)
        val policy = if (enabled) {
            visibilityPolicyMutable.value.takeIf { it.buttonEnabled }
                ?: MButtonVisibilityPolicy.ALWAYS_VISIBLE
        } else {
            MButtonVisibilityPolicy.DISABLED
        }
        return setVisibilityPolicy(context, policy)
    }

    @Synchronized
    fun setVisibilityPolicy(context: Context, policy: MButtonVisibilityPolicy): Boolean {
        initialize(context)
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        if (visibilityPolicyMutable.value == policy) {
            preferences.edit()
                .putBoolean(SecondaryDefaultInitializedKey, true)
                .apply()
            defaultPolicyUninitialized = false
            return true
        }
        preferences.edit()
            .putString(VisibilityPolicyKey, policy.name)
            .putBoolean(EnabledKey, policy.buttonEnabled)
            .putBoolean(SecondaryDefaultInitializedKey, true)
            .apply()
        // Utility D-pad bursts must not block the main thread on disk. SharedPreferences.apply updates
        // process memory synchronously and persists the final value asynchronously.
        visibilityPolicyMutable.value = policy
        enabledMutable.value = policy.buttonEnabled
        defaultPolicyUninitialized = false
        return true
    }

    @Synchronized
    fun shouldSeedDefaultPolicy(): Boolean = defaultPolicyUninitialized
}

/** Process-owned topology selector; the Activity only asks it to resample after returning from Settings. */
internal class MButtonTopologyController(
    context: Context,
    private val overlay: CompanionOverlayController,
    private val secondaryDisplaySelector: SecondaryDisplaySelector,
    private val usageAccessGranted: () -> Boolean,
    private val onboardingComplete: () -> Boolean,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val appOpsManager = appContext.getSystemService(AppOpsManager::class.java)
    private val primarySurfaceAvailableMutable = MutableStateFlow(true)
    private val mButtonVisibilityControlsAvailableMutable = MutableStateFlow(true)
    private var activeTarget: CompanionOverlayTarget? = null
    private var activePrimaryPolicy: MButtonVisibilityPolicy? = null
    private var primaryHostForeground = false
    private var primaryUiSuppressed = false
    /** O9: the runtime's latest Bottom cover evidence, main thread only. See [mButtonBottomCoveredByForeignApp]. */
    private var bottomCoveredByForeignApp = false
    private val idleDeadlineLedger = MButtonIdleDeadlineLedger()
    private var idleRunnable: Runnable? = null

    val primarySurfaceAvailable: StateFlow<Boolean> = primarySurfaceAvailableMutable.asStateFlow()
    /** The Utility policy remains reachable while either primary or secondary M is the active target. */
    val mButtonVisibilityControlsAvailable: StateFlow<Boolean> =
        mButtonVisibilityControlsAvailableMutable.asStateFlow()

    private val usageListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_GET_USAGE_STATS && packageName == appContext.packageName) reconcile()
    }

    init {
        MButtonSettingsStore.initialize(appContext)
        overlay.setPrimaryIdleInteractionObserver(::noteOverlayInteraction)
        // The Bottom ᛗ menu's pad under Main's screen-idle rule : the sink answers with the resumed Main's idle controller, false while no Main is resumed.
        overlay.setPadIdleInteraction(MimirMainInputSink::noteInteraction)
        secondaryDisplaySelector.addListener { reconcile() }
        runCatching {
            appOpsManager?.startWatchingMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                appContext.packageName,
                usageListener,
            )
        }
        scope.launch {
            MButtonSettingsStore.visibilityPolicy.collect { reconcile() }
        }
        scope.launch {
            overlay.overlayPermissionMissing.collect { reconcile() }
        }
        scope.launch {
            // Completing onboarding is the moment a PRIMARY_SINGLE ᛗ first becomes eligible
            // ([selectMButtonOverlayTarget] requires it), but nothing here was watching for it. The
            // Activity reconciles on resume, and finishing onboarding routes to HOME inside the SAME
            // resume -- so without this the seeded button only appeared after backgrounding and
            // returning. A SECONDARY_COMPANION ᛗ never needed it, which is why the Bottom hid the gap.
            // OnboardingStore.markComplete commits durably BEFORE it publishes DONE, so onboardingComplete()
            // already answers true by the time this fires.
            //
            // Watch completion itself, not the step. The step reaches DONE twice: FAVORITES moves to it
            // (OnboardingStore.moveTo, still incomplete: the "Setup complete" page) and then markComplete
            // publishes it again, complete. Watching `step == DONE` saw true -> true there and dropped the real
            // completion, so the button was judged once while onboarding was still incomplete and never again
            // (observed on Nova: allowed, seeded on, never shown).
            OnboardingStore.checkpoints(appContext)
                .map { onboardingComplete() }
                .distinctUntilChanged()
                .collect { reconcile() }
        }
        scope.launch {
            // Read from the process runtime, like this controller: the overlay's dim and Return signals are fed from
            // MainActivity's lifecycle and stop with it, while the Bottom ᛗ outlives the Activity.
            CompanionReturnCoordinator.runtime().snapshot
                .map { snapshot -> mButtonBottomCoveredByForeignApp(snapshot.disposition) }
                .distinctUntilChanged()
                .collect { covered -> noteBottomForeignAppCover(covered) }
        }
        reconcile()
    }

    fun reconcile() {
        main.post {
            val presentationDisplayId = secondaryDisplaySelector.snapshot.value.selectedId
            primarySurfaceAvailableMutable.value = presentationDisplayId == null
            var policy = MButtonSettingsStore.visibilityPolicy.value
            if (shouldSeedDefaultMButtonPolicy(
                    policy = policy,
                    defaultUninitialized = MButtonSettingsStore.shouldSeedDefaultPolicy(),
                )
            ) {
                MButtonSettingsStore.setVisibilityPolicy(
                    appContext,
                    MButtonVisibilityPolicy.HIDE_AFTER_30_SECONDS,
                )
                policy = MButtonSettingsStore.visibilityPolicy.value
            }
            val eligibility = MButtonEligibility(
                presentationDisplayId = presentationDisplayId,
                primaryEnabled = policy.buttonEnabled,
                usageAccessGranted = usageAccessGranted(),
                onboardingComplete = onboardingComplete(),
                overlayPermissionGranted = overlay.hasOverlayPermission(),
            )
            val target = selectMButtonOverlayTarget(eligibility)
            if (target?.role != activeTarget?.role) {
                // Only on a change, so a device run shows why the button did or did not appear.
                Log.i(
                    "MimirMButton",
                    "target ${activeTarget?.role} -> ${target?.role} display=${eligibility.presentationDisplayId} " +
                        "enabled=${eligibility.primaryEnabled} usage=${eligibility.usageAccessGranted} " +
                        "onboarded=${eligibility.onboardingComplete} overlay=${eligibility.overlayPermissionGranted}",
                )
            }
            val previousTarget = activeTarget
            val previousPrimaryPolicy = activePrimaryPolicy
            activeTarget = target
            if (shouldKeepMButtonProcessAlive(target)) {
                // Background starts are intentionally forbidden. MainActivity arms this while foreground;
                // once running, the service remains until eligibility is lost or the button is disabled.
                if (primaryHostForeground && MButtonKeepAliveService.ensureStarted(appContext)) {
                    // O13 : the first start after the Utility row turned the M button on is
                    // its background service's enabling moment, and the one time it may ask to post its notice. An
                    // unarmed start (every resume, every cold restore of a button already on) asks nothing.
                    MimirNotificationPermissionAsk.onMButtonKeepAliveStarted(appContext)
                }
            } else {
                MButtonKeepAliveService.stop(appContext)
            }
            when (target?.role) {
                null -> {
                    activePrimaryPolicy = null
                    cancelIdleTimer()
                    overlay.setPrimaryIdleHidden(false)
                    overlay.dismiss()
                }
                CompanionOverlayRole.SECONDARY_COMPANION -> {
                    activePrimaryPolicy = policy
                    overlay.targetOverlay(target)
                    if (previousTarget != target || previousPrimaryPolicy != policy) {
                        overlay.setPrimaryIdleHidden(false)
                        armIdleTimer(policy)
                    }
                }
                CompanionOverlayRole.PRIMARY_SINGLE -> {
                    activePrimaryPolicy = policy
                    overlay.targetOverlay(target)
                    if (primaryUiSuppressed) {
                        cancelIdleTimer()
                        overlay.setPrimaryIdleHidden(true)
                    } else if (previousTarget != target || previousPrimaryPolicy != policy) {
                        overlay.setPrimaryIdleHidden(false)
                        armIdleTimer(policy)
                    }
                }
            }
        }
    }

    fun setPrimaryHostForeground(foreground: Boolean) {
        main.post {
            if (primaryHostForeground == foreground) return@post
            primaryHostForeground = foreground
            if (foreground) reconcile()
        }
    }

    /** Activity and native-window interaction both reveal and start one latest-wins deadline. */
    fun notePrimaryInteraction() {
        main.post {
            if (activeTarget?.role != CompanionOverlayRole.PRIMARY_SINGLE) return@post
            if (primaryUiSuppressed) return@post
            revealMButtonForInteraction(CompanionOverlayRole.PRIMARY_SINGLE)
        }
    }

    /** Presentation touches reset only the secondary M policy; MainActivity input never reaches this path. */
    fun noteSecondaryInteraction() {
        main.post {
            if (activeTarget?.role != CompanionOverlayRole.SECONDARY_COMPANION) return@post
            revealMButtonForInteraction(CompanionOverlayRole.SECONDARY_COMPANION)
        }
    }

    /** Native overlay callbacks are role-aware even though the shared observer has no role parameter. */
    private fun noteOverlayInteraction() {
        main.post {
            when (activeTarget?.role) {
                CompanionOverlayRole.PRIMARY_SINGLE -> {
                    if (!primaryUiSuppressed) revealMButtonForInteraction(CompanionOverlayRole.PRIMARY_SINGLE)
                }
                CompanionOverlayRole.SECONDARY_COMPANION -> {
                    revealMButtonForInteraction(CompanionOverlayRole.SECONDARY_COMPANION)
                }
                null -> Unit
            }
        }
    }

    private fun revealMButtonForInteraction(role: CompanionOverlayRole) {
        if (activeTarget?.role != role) return
        val policy = MButtonSettingsStore.visibilityPolicy.value
        if (!policy.buttonEnabled) return
        overlay.setPrimaryIdleHidden(false)
        armIdleTimer(policy)
    }

    /**
     * A full-screen in-app modal can temporarily own the bottom-right composition where the system overlay
     * normally lives. Suppression is process-owned so later touch/key interaction cannot reveal the button
     * through the modal; releasing it counts as fresh interaction and restarts the user's idle policy.
     */
    fun setPrimaryUiSuppressed(suppressed: Boolean) {
        main.post {
            if (primaryUiSuppressed == suppressed) return@post
            primaryUiSuppressed = suppressed
            if (activeTarget?.role != CompanionOverlayRole.PRIMARY_SINGLE) return@post
            if (suppressed) {
                cancelIdleTimer()
                overlay.setPrimaryIdleHidden(true)
            } else {
                val policy = MButtonSettingsStore.visibilityPolicy.value
                if (!policy.buttonEnabled) return@post
                overlay.setPrimaryIdleHidden(false)
                armIdleTimer(policy)
            }
        }
    }

    /**
     * O9: the runtime's Bottom cover evidence changed. An app arriving brings a hidden Bottom ᛗ back with no touch, so its
     * reveal never goes through the app; the app leaving restarts the user's idle policy over the shelf, the way releasing
     * [setPrimaryUiSuppressed] does. Whether a deadline hides is decided when it fires, in [armIdleTimer].
     */
    private fun noteBottomForeignAppCover(covered: Boolean) {
        main.post {
            if (bottomCoveredByForeignApp == covered) return@post
            bottomCoveredByForeignApp = covered
            val target = activeTarget ?: return@post
            if (target.role != CompanionOverlayRole.SECONDARY_COMPANION) return@post
            val policy = MButtonSettingsStore.visibilityPolicy.value
            if (!policy.buttonEnabled) return@post
            val cover = if (covered) MButtonIdleHideSuppression.FOREIGN_APP_ON_BOTTOM.logReason else "none"
            Log.i(
                "MimirMButton",
                "idle cover=$cover role=${target.role} display=${target.displayId} policy=$policy " +
                    "action=reveal_and_rearm",
            )
            overlay.setPrimaryIdleHidden(false)
            armIdleTimer(policy)
        }
    }

    private fun armIdleTimer(policy: MButtonVisibilityPolicy) {
        cancelIdleTimer()
        if (activeTarget?.role != CompanionOverlayRole.PRIMARY_SINGLE &&
            activeTarget?.role != CompanionOverlayRole.SECONDARY_COMPANION
        ) return
        val targetAtArm = activeTarget ?: return
        val deadline = idleDeadlineLedger.arm(policy) ?: return
        idleRunnable = Runnable {
            if (activeTarget != targetAtArm || !idleDeadlineLedger.accepts(
                    deadline = deadline,
                    currentPolicy = MButtonSettingsStore.visibilityPolicy.value,
                    mButtonActive = targetAtArm.role == CompanionOverlayRole.PRIMARY_SINGLE ||
                        targetAtArm.role == CompanionOverlayRole.SECONDARY_COMPANION,
                )
            ) return@Runnable
            idleRunnable = null
            // O9: decided when the deadline fires, so an app that arrived after arming is honoured as well.
            val suppression = mButtonIdleHideSuppression(targetAtArm.role, bottomCoveredByForeignApp)
            if (targetAtArm.role == CompanionOverlayRole.SECONDARY_COMPANION) {
                // "requested": the overlay can still decline a hide (menu open, a drag, no window).
                Log.i(
                    "MimirMButton",
                    "idle deadline role=${targetAtArm.role} display=${targetAtArm.displayId} " +
                        "policy=${deadline.policy} hide=${if (suppression == null) "requested" else "suppressed"} " +
                        "reason=${suppression?.logReason ?: "none"}",
                )
            }
            if (suppression != null) return@Runnable
            overlay.setPrimaryIdleHidden(true)
        }.also { main.postDelayed(it, deadline.delayMillis) }
    }

    private fun cancelIdleTimer() {
        idleDeadlineLedger.cancel()
        idleRunnable?.let(main::removeCallbacks)
        idleRunnable = null
    }
}

internal fun shouldKeepMButtonProcessAlive(target: CompanionOverlayTarget?): Boolean =
    target?.role == CompanionOverlayRole.PRIMARY_SINGLE

/** Fresh service-start truth. A stale process or service request never substitutes for user eligibility. */
internal fun currentMButtonKeepAliveTarget(context: Context): CompanionOverlayTarget? {
    val appContext = context.applicationContext
    MButtonSettingsStore.initialize(appContext)
    val presentationDisplayId = CompanionProcessRuntime.services(appContext)
        .secondaryDisplaySelector
        .snapshot
        .value
        .selectedId
    return selectMButtonOverlayTarget(
        MButtonEligibility(
            presentationDisplayId = presentationDisplayId,
            primaryEnabled = MButtonSettingsStore.visibilityPolicy.value.buttonEnabled,
            usageAccessGranted = currentMButtonUsageAccessGranted(appContext),
            onboardingComplete = !OnboardingStore.isFirstRun(appContext),
            overlayPermissionGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext),
        ),
    )
}

private fun currentMButtonUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Tabs uses whichever fixed app-switching authority the certified broker has already admitted. */
internal interface MButtonSystemRecentsGateway {
    fun systemRecentsAccessStatus(): PrivilegedUtilityStatus
    fun isReady(): Boolean = systemRecentsAccessStatus() == PrivilegedUtilityStatus.READY
    fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean
    /**
     * Settles only Mimir's local consent for an admitted built-in vendor authority.
     *
     * The default is deliberately fail-closed: fallback gateways must not turn
     * a Tabs press into privilege setup. Settings retains the broader access-request route.
     */
    fun requestBuiltInSystemRecentsConsent(expectedStatus: PrivilegedUtilityStatus): Boolean = false
    fun openSystemRecents(): Boolean
}

internal fun systemRecentsFailureCopy(): String =
    "Android app switching couldn’t open. Review access in Settings › Controls."

/** Quick launch the ᛗ menu could not open: Main's menu whose panel and Activity start were refused, or the Bottom's picker (O5). */
private const val QuickLaunchFailureCopy = "Mimir couldn’t open Quick launch from the background."
private const val BottomUtilityFailureCopy = "Mimir couldn’t open Utility on the bottom screen."

/**
 * Selecting Tabs is the user's explicit request to use Android app switching. On an admitted
 * Retroid/AYN built-in bridge, the only missing step can be Mimir's local consent bit; settle that
 * exact state and retry once. Every other posture stays fail-closed and no external service is started
 * implicitly from the overlay.
 */
internal data class MButtonSystemRecentsOpenResult(
    val statusBefore: PrivilegedUtilityStatus,
    val accessRequested: Boolean,
    val statusAfter: PrivilegedUtilityStatus,
    val accepted: Boolean,
)

internal fun openSystemRecentsFromMButton(
    gateway: MButtonSystemRecentsGateway,
): MButtonSystemRecentsOpenResult {
    val statusBefore = gateway.systemRecentsAccessStatus()
    val requested = statusBefore == PrivilegedUtilityStatus.PERMISSION_REQUIRED &&
        gateway.requestBuiltInSystemRecentsConsent(statusBefore)
    val statusAfter = if (requested) gateway.systemRecentsAccessStatus() else statusBefore
    val accepted = statusAfter == PrivilegedUtilityStatus.READY && gateway.openSystemRecents()
    return MButtonSystemRecentsOpenResult(
        statusBefore = statusBefore,
        accessRequested = requested,
        statusAfter = statusAfter,
        accepted = accepted,
    )
}

/** [MUSIC] is the single-display badge's tap while there is music to control: hosted by the process panel host only, never an Activity route. */
internal enum class MButtonPrimaryCommand { QUICK_LAUNCH, HOME, UTILITY, TWEAKER, SETTINGS, MUSIC }

/** The M menu's picks that open a surface inside Mimir, over the current scene. */
internal fun mButtonCommandOpensMimirSurface(command: MButtonPrimaryCommand): Boolean = when (command) {
    MButtonPrimaryCommand.QUICK_LAUNCH, MButtonPrimaryCommand.UTILITY, MButtonPrimaryCommand.TWEAKER -> true
    MButtonPrimaryCommand.HOME, MButtonPrimaryCommand.SETTINGS, MButtonPrimaryCommand.MUSIC -> false
}

internal enum class PrimaryMButtonDispatch { SYSTEM_RECENTS, QUICK_LAUNCH, UTILITY, TWEAKER, REJECT }

internal fun primaryMButtonDispatch(item: QuickMenuItem): PrimaryMButtonDispatch = when (item) {
    QuickMenuItem.SYSTEM_TABS -> PrimaryMButtonDispatch.SYSTEM_RECENTS
    QuickMenuItem.QUICK_LAUNCH -> PrimaryMButtonDispatch.QUICK_LAUNCH
    QuickMenuItem.UTILITY -> PrimaryMButtonDispatch.UTILITY
    QuickMenuItem.TWEAKER -> PrimaryMButtonDispatch.TWEAKER
    QuickMenuItem.SECONDARY_TABS,
    QuickMenuItem.SECONDARY_RETURN,
    QuickMenuItem.SECONDARY_SWAP,
    QuickMenuItem.TRACKPAD,
    -> PrimaryMButtonDispatch.REJECT
}

/** Unsupported primary-only commands must terminate a secondary menu request instead of latching it. */
internal fun secondaryQuickMenuFallbackAcceptance(item: QuickMenuItem): Boolean = when (item) {
    QuickMenuItem.SYSTEM_TABS,
    QuickMenuItem.QUICK_LAUNCH,
    QuickMenuItem.UTILITY,
    QuickMenuItem.TWEAKER,
    // Bottom-only: [bottomTrackpadRoute] answers it in the router; the Activity must not complete it as a primary command.
    QuickMenuItem.TRACKPAD,
    -> false
    QuickMenuItem.SECONDARY_TABS,
    QuickMenuItem.SECONDARY_RETURN,
    QuickMenuItem.SECONDARY_SWAP,
    -> error("Secondary-owned commands require their explicit route")
}

/**
 * Quick Launch and Utility are Mimir-global regardless of which display currently hosts the M button, except the Bottom
 * menu's Quick launch, which [bottomQuickLaunchRoute] answers on the Bottom before this mapping is read (O5).
 */
internal fun globalMButtonCommand(item: QuickMenuItem): MButtonPrimaryCommand? = when (item) {
    QuickMenuItem.QUICK_LAUNCH -> MButtonPrimaryCommand.QUICK_LAUNCH
    QuickMenuItem.UTILITY -> MButtonPrimaryCommand.UTILITY
    QuickMenuItem.TWEAKER -> MButtonPrimaryCommand.TWEAKER
    else -> null
}

/**
 * The Bottom coin opens Quick launch on the Bottom screen, leaving Main's game and session intact.
 *
 * So the Bottom menu's Quick launch is not a global command. It never reaches [MButtonIntentAuthority], which starts
 * MainActivity on Main (QA D1) and, over a game there, resumed it into that game's return (D3). Every other item keeps its
 * route, the Bottom Utility's primary fallback included.
 */
internal fun bottomQuickLaunchRoute(role: CompanionOverlayRole, item: QuickMenuItem): Boolean =
    role == CompanionOverlayRole.SECONDARY_COMPANION && item == QuickMenuItem.QUICK_LAUNCH

/**
 * the Bottom menu's Trackpad row opens the full-screen pad (or, with the service off, its consent
 * card) on the Bottom. Like Quick launch it is answered in the router, never a global command and never MainActivity.
 */
internal fun bottomTrackpadRoute(role: CompanionOverlayRole, item: QuickMenuItem): Boolean =
    role == CompanionOverlayRole.SECONDARY_COMPANION && item == QuickMenuItem.TRACKPAD

/** What opening the Bottom's Quick launch picker did. [refusal] says why it did not open, for the router's line. */
internal data class BottomQuickLaunchOpenResult(
    val opened: Boolean,
    val refusal: String? = null,
)

/** Shows the Bottom's own Quick launch picker on the Bottom display (O5). Main thread. */
internal fun interface MButtonBottomQuickLaunchGateway {
    fun open(): BottomQuickLaunchOpenResult
}

/**
 * Policy 2026-10-09 , verbatim in substance: Utility (and the Tweaker inside it), opened from the Bottom ᛗ, appears as an
 * overlay window above the game's bottom screen, covering it until closed.
 *
 * Utility used to be drawn inside Mimir's own Bottom Presentation. melonDS, Lime3DS and Azahar each add their own window on the Bottom,
 * the same second-screen window type Mimir uses, and theirs is made later, so it sits on top: Utility opened beneath it and showed
 * nothing. Hosting it in the overlay window [BottomUtilityPanelHost] lends on the Bottom puts it above every one of them, and above
 * Mimir's own Presentation as well, so the route does not ask which of the two is on top: the Bottom's Utility is always that window.
 *
 * Answered in the router, before the global mapping below is read, so it can neither start MainActivity (over a game it would
 * resume Main into that game's return) nor reach the Presentation's instruments layer.
 */
internal fun bottomUtilityRoute(role: CompanionOverlayRole, item: QuickMenuItem): Boolean =
    role == CompanionOverlayRole.SECONDARY_COMPANION && item == QuickMenuItem.UTILITY

/**
 * What opening the Bottom's Utility did. [refusal] says why the window did not open, for the router's line; with [opened] true it
 * names a press the Presentation carried instead (`presentation_<reason>`).
 */
internal data class BottomUtilityOpenResult(
    val opened: Boolean,
    val refusal: String? = null,
)

/** The refusal that means there is no Utility model to show yet: the process has not bound one (no host has ever created it). */
internal const val BottomUtilityHostUnbound = "host_unbound"

/** Shows the Bottom's Utility in an overlay window on the Bottom display . Main thread. */
internal fun interface MButtonBottomUtilityGateway {
    fun open(): BottomUtilityOpenResult
}

internal data class MButtonNavigationRequest(
    val id: UUID,
    val command: MButtonPrimaryCommand,
)

internal class MButtonCommandLedger {
    private val pending = linkedMapOf<String, MButtonPrimaryCommand>()

    @Synchronized
    fun issue(command: MButtonPrimaryCommand): String = UUID.randomUUID().toString().also { token ->
        pending.clear()
        pending[token] = command
    }

    @Synchronized
    fun consume(token: String?): MButtonPrimaryCommand? = token?.let(pending::remove)

    @Synchronized
    fun revoke(token: String) {
        pending.remove(token)
    }
}

/**
 * The display every M-button-authored MainActivity start is pinned to: the Main display, whichever
 * display holds focus. A pure constant so a test can pin the contract (QA 2026-09-11, D1).
 */
internal fun mButtonAuthorityLaunchDisplayId(): Int = Display.DEFAULT_DISPLAY

internal object MButtonIntentAuthority {
    const val ExtraCommandToken = "com.gruning.mimir.rewrite.extra.M_BUTTON_COMMAND_TOKEN"
    private val ledger = MButtonCommandLedger()

    fun dispatch(context: Context, command: MButtonPrimaryCommand): Boolean {
        val token = ledger.issue(command)
        // Retain the exact destination before Android foregrounds the Activity. A stopped composition
        // can then consume it on resume even if CLEAR_TOP restores Home before onNewIntent is delivered.
        val requestId = MButtonNavigationCommands.publish(command)
        // P5: fires once per M-button press, release builds included (was BuildConfig.DEBUG-gated).
        Log.i("MimirMButton", "authority publish request=$requestId command=$command")
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(ExtraCommandToken, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Pinned to the Main display. Without options Android starts the Activity on the FOCUSED display,
        // and on the Thor the Bottom is focused whenever the player just touched it -- the QA pass of
        // 2026-09-11 (D1) saw Quick launch and the Utility fallback create a second MainActivity task on
        // display 4 beside the display-0 instance. Every Mimir-global command lands on Main by contract.
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(mButtonAuthorityLaunchDisplayId())
        val accepted = runCatching { context.startActivity(intent, options.toBundle()) }.isSuccess
        // P5: release builds included (was BuildConfig.DEBUG-gated).
        Log.i("MimirMButton", "authority startActivity request=$requestId command=$command accepted=$accepted")
        if (!accepted) {
            ledger.revoke(token)
            MButtonNavigationCommands.acknowledge(requestId)
        }
        return accepted
    }

    fun consume(
        intent: Intent?,
        noteDelivered: () -> Unit = PrimaryActivityObservationState::noteMButtonCommandDelivered,
    ): MButtonPrimaryCommand? {
        val command = ledger.consume(intent?.getStringExtra(ExtraCommandToken)) ?: return null
        // D3: MainActivity is being brought forward by this command, not by a game on Main coming back, so the resume it
        // causes must not start that game's return (PrimaryStopResumeLedger). Only a token this process issued counts.
        noteDelivered()
        // Dispatch already retained the route. Return it only as a cold fallback if that process-local
        // request disappeared before Activity delivery; never publish a duplicate command on warm entry.
        return command.takeUnless { MButtonNavigationCommands.pending.value?.command == command }
    }
}

internal object MButtonNavigationCommands {
    private val pendingMutable = MutableStateFlow<MButtonNavigationRequest?>(null)
    val pending: StateFlow<MButtonNavigationRequest?> = pendingMutable.asStateFlow()

    fun publish(command: MButtonPrimaryCommand): UUID {
        val request = MButtonNavigationRequest(UUID.randomUUID(), command)
        pendingMutable.value = request
        return request.id
    }

    fun acknowledge(id: UUID) {
        if (pendingMutable.value?.id == id) pendingMutable.value = null
    }
}

internal fun interface MButtonPrimaryPanelGateway {
    fun show(command: MButtonPrimaryCommand): Boolean
}

internal fun dispatchMButtonPrimarySurface(
    role: CompanionOverlayRole,
    primaryActivityStopped: Boolean,
    command: MButtonPrimaryCommand,
    showPanel: (MButtonPrimaryCommand) -> Boolean,
    dispatchActivity: (MButtonPrimaryCommand) -> Boolean,
): Boolean {
    val externalPanelEligible =
        role == CompanionOverlayRole.PRIMARY_SINGLE && primaryActivityStopped
    return if (externalPanelEligible) {
        showPanel(command) || dispatchActivity(command)
    } else {
        dispatchActivity(command)
    }
}

/** Handles the primary M menu even when no Activity instance is alive. */
internal class MButtonProcessActionRouter(
    private val context: Context,
    private val overlay: CompanionOverlayController,
    private val systemRecents: MButtonSystemRecentsGateway,
    private val primaryPanel: MButtonPrimaryPanelGateway,
    private val bottomQuickLaunch: MButtonBottomQuickLaunchGateway,
    private val isPrimaryActivityStopped: () -> Boolean,
    scope: CoroutineScope,
    /** The Bottom ᛗ menu's Utility row : shown in an overlay window on the Bottom, never on Main during a game. */
    private val bottomUtility: MButtonBottomUtilityGateway =
        MButtonBottomUtilityGateway { BottomUtilityOpenResult(opened = false, refusal = BottomUtilityHostUnbound) },
    /** What Main's Recents shows and how its prior top comes back ; see [MButtonMainRecents]. */
    private val mainRecents: MButtonMainRecents = MButtonMainRecents.Unavailable,
    /** The Bottom menu's Trackpad row (2026-10-06). */
    private val bottomTrackpad: MButtonBottomTrackpadGateway =
        MButtonBottomTrackpadGateway { BottomTrackpadOpenResult(opened = false, refusal = "unwired") },
) {
    private val handlerLock = Any()
    /** One Recents tap at a time: a second tap during the first one's settle wait would read a half-landed state and reopen Recents. */
    private val recentsTapInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var secondaryHandler: Pair<UUID, (CompanionOverlayAction) -> Unit>? = null

    init {
        scope.launch {
            overlay.actions.collect { action ->
                recordBottomMenuControl(overlay, action)
                if (action is CompanionOverlayAction.DragMoved &&
                    action.role == CompanionOverlayRole.PRIMARY_SINGLE
                ) {
                    return@collect
                }
                if (action is CompanionOverlayAction.MusicPanel) {
                    // a tap on the single-display badge with music to control. Hosted in the process panel
                    // host and nowhere else: its Activity fallback would bring Mimir forward over a game for a panel that has
                    // no Activity route, so a refused attach is only logged.
                    val shown = action.role == CompanionOverlayRole.PRIMARY_SINGLE &&
                        primaryPanel.show(MButtonPrimaryCommand.MUSIC)
                    Log.i("MimirMButton", "router music panel role=${action.role} shown=$shown")
                    return@collect
                }
                val menu = action as? CompanionOverlayAction.QuickMenu
                // the earlier update : the Bottom menu's Utility is an overlay window above whatever the Bottom shows, a
                // dual-screen emulator's own window included. Answered here, before the global mapping, so it never starts MainActivity
                // over a game. Utility NEVER falls back to Main : a press the window could not carry is carried by
                // the Presentation when no game runs (BottomUtilityPanelHost), and otherwise reported on the Bottom.
                if (menu != null && bottomUtilityRoute(menu.role, menu.item)) {
                    val result = bottomUtility.open()
                    // P5: one line per press, release builds included.
                    Log.i(
                        "MimirMButton",
                        "router request=${menu.requestId} role=${menu.role} item=${menu.item} " +
                            "route=BOTTOM_UTILITY accepted=${result.opened}" +
                            (result.refusal?.let { " reason=$it" } ?: ""),
                    )
                    overlay.completeQuickMenu(menu.requestId, accepted = result.opened)
                    if (!result.opened) failureToast(menu.role, BottomUtilityFailureCopy)
                    return@collect
                }
                // O5 : the Bottom menu's Quick launch opens the Bottom's own picker on the Bottom.
                // Answered here, before the global mapping below, so it can neither start MainActivity nor bring it
                // forward over a game on Main.
                if (menu != null && bottomQuickLaunchRoute(menu.role, menu.item)) {
                    val result = bottomQuickLaunch.open()
                    // P5: one line per press, release builds included.
                    Log.i(
                        "MimirMButton",
                        "router request=${menu.requestId} role=${menu.role} item=${menu.item} " +
                            "route=BOTTOM_PICKER accepted=${result.opened}" +
                            (result.refusal?.let { " reason=$it" } ?: ""),
                    )
                    overlay.completeQuickMenu(menu.requestId, accepted = result.opened)
                    // Round 5 (5b): a picker that could not open says so on the Bottom, with the Quick launch failure copy the
                    // Bottom showed there before O5, so a press that opens nothing is not silent.
                    if (!result.opened) failureToast(menu.role, QuickLaunchFailureCopy)
                    return@collect
                }
                // 2026-10-06: the Bottom menu's Trackpad row opens the full-screen pad (or its consent card) on the Bottom.
                if (menu != null && bottomTrackpadRoute(menu.role, menu.item)) {
                    val result = bottomTrackpad.open()
                    Log.i(
                        "MimirMButton",
                        "router request=${menu.requestId} role=${menu.role} item=${menu.item} " +
                            "route=BOTTOM_TRACKPAD accepted=${result.opened}" +
                            (result.refusal?.let { " reason=$it" } ?: ""),
                    )
                    overlay.completeQuickMenu(menu.requestId, accepted = result.opened)
                    return@collect
                }
                val globalCommand = menu?.item?.let(::globalMButtonCommand)
                if (menu != null && globalCommand != null) {
                    completePrimaryDispatch(menu, globalCommand)
                    return@collect
                }
                // The secondary Tabs hold deliberately maps to SYSTEM_TABS too: Android Recents is a
                // global compositor action, so both native M roles must use this same authority and
                // accepted/failure completion path.
                if (menu?.item == QuickMenuItem.SYSTEM_TABS) {
                    if (!recentsTapInFlight.compareAndSet(false, true)) {
                        Log.i("MimirAppSwitching", "source=M_BUTTON origin=${menu.origin} action=dropped reason=tap-in-flight")
                        overlay.completeQuickMenu(menu.requestId, accepted = true)
                        return@collect
                    }
                    scope.launch(Dispatchers.IO) {
                        try {
                            val tap = runMButtonRecentsTap(menu.origin, mainRecents, systemRecents)
                            val result = tap.press
                            if (result != null) {
                                Log.i(
                                    "MimirAppSwitching",
                                    "source=M_BUTTON statusBefore=${result.statusBefore} " +
                                        "accessRequested=${result.accessRequested} " +
                                        "statusAfter=${result.statusAfter} accepted=${result.accepted}",
                                )
                            }
                            Log.i("MimirAppSwitching", "source=M_BUTTON origin=${menu.origin} action=${tap.action.token} accepted=${tap.accepted}")
                            if (menu.origin == QuickMenuOrigin.COIN_TAP) recordCoinRecentsAction(overlay, tap.action)
                            withContext(Dispatchers.Main.immediate) {
                                overlay.completeQuickMenu(menu.requestId, tap.accepted)
                                if (!tap.accepted) {
                                    failureToast(
                                        menu.role,
                                        if (tap.action == RecentsTapAction.OPEN) systemRecentsFailureCopy() else recentsCloseFailureCopy(),
                                    )
                                }
                            }
                        } finally {
                            recentsTapInFlight.set(false)
                        }
                    }
                    return@collect
                }
                if (menu?.role != CompanionOverlayRole.PRIMARY_SINGLE) {
                    val handler = synchronized(handlerLock) { secondaryHandler?.second }
                    if (handler != null) {
                        handler(action)
                    } else if (menu != null) {
                        overlay.completeQuickMenu(menu.requestId, accepted = false)
                    }
                    return@collect
                }
                when (primaryMButtonDispatch(menu.item)) {
                    // SYSTEM_TABS is handled above for both native roles.
                    PrimaryMButtonDispatch.SYSTEM_RECENTS -> Unit
                    PrimaryMButtonDispatch.QUICK_LAUNCH -> completePrimaryDispatch(
                        menu,
                        MButtonPrimaryCommand.QUICK_LAUNCH,
                    )
                    PrimaryMButtonDispatch.UTILITY -> completePrimaryDispatch(menu, MButtonPrimaryCommand.UTILITY)
                    PrimaryMButtonDispatch.TWEAKER -> completePrimaryDispatch(menu, MButtonPrimaryCommand.TWEAKER)
                    PrimaryMButtonDispatch.REJECT -> overlay.completeQuickMenu(menu.requestId, accepted = false)
                }
            }
        }
    }

    fun bindSecondaryHandler(handler: (CompanionOverlayAction) -> Unit): UUID = synchronized(handlerLock) {
        UUID.randomUUID().also { lease -> secondaryHandler = lease to handler }
    }

    fun unbindSecondaryHandler(lease: UUID) {
        synchronized(handlerLock) {
            if (secondaryHandler?.first == lease) secondaryHandler = null
        }
    }

    private fun completePrimaryDispatch(
        menu: CompanionOverlayAction.QuickMenu,
        command: MButtonPrimaryCommand,
    ) {
        val primaryActivityStopped = isPrimaryActivityStopped()
        val accepted = dispatchMButtonPrimarySurface(
            role = menu.role,
            primaryActivityStopped = primaryActivityStopped,
            command = command,
            showPanel = primaryPanel::show,
            dispatchActivity = { requested -> MButtonIntentAuthority.dispatch(context, requested) },
        )
        // P5: release builds included (was BuildConfig.DEBUG-gated).
        Log.i(
            "MimirMButton",
            "router request=${menu.requestId} role=${menu.role} item=${menu.item} " +
                "primaryStopped=$primaryActivityStopped command=$command accepted=$accepted",
        )
        // A pick that opens a Mimir surface hands the menu to Mimir's glass; the rest collapse
        // as before (RETURN TO SHELF's reclaim is ordered behind that plain collapse).
        if (accepted && mButtonCommandOpensMimirSurface(command)) {
            overlay.completeQuickMenuIntoMimir(menu.requestId)
        } else {
            overlay.completeQuickMenu(menu.requestId, accepted)
        }
        if (!accepted) {
            failureToast(
                menu.role,
                if (command == MButtonPrimaryCommand.UTILITY) {
                    "Mimir couldn’t open Utility from the background."
                } else if (command == MButtonPrimaryCommand.TWEAKER) {
                    "Mimir couldn’t open Tweaker from the background."
                } else {
                    QuickLaunchFailureCopy
                },
            )
        }
    }

    /**
     * Round 5 (5b): a command pressed on the Bottom's ᛗ menu reports its failure on the Bottom, where the player is
     * looking. [context] is the process context, which Android places on the default display, so the toast is made from
     * a display context of the display that menu is mounted on: the committed overlay target, while that target is the
     * secondary companion. A command from Main's single-screen menu keeps [context], and so does a Bottom command whose
     * overlay is no longer mounted when the toast is made.
     */
    private fun failureToast(sourceRole: CompanionOverlayRole, message: String) {
        val menuDisplayId = overlay.activeTarget.value
            ?.takeIf {
                sourceRole == CompanionOverlayRole.SECONDARY_COMPANION &&
                    it.role == CompanionOverlayRole.SECONDARY_COMPANION
            }
            ?.displayId
        showMimirMessage(companionFeedbackContext(context, menuDisplayId), message)
    }
}
