package com.gruning.mimir.rewrite.core.companionnative

import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Native-only companion seams. Compose owns the Presentation/read-model surface; these ports own the
 * Android pieces Compose cannot provide: overlay z-order, display-targeted launch, recents/session truth,
 * and return/reclaim signals.
 */
interface CompanionOverlayController {
    /** Select exactly one overlay role. Retargeting removes the old role before mounting the new one. */
    fun targetOverlay(target: CompanionOverlayTarget)

    /** Source-compatible dual-display adapter. New topology owners should call [targetOverlay]. */
    fun mountOnSecondaryDisplay(displayId: Int) {
        targetOverlay(CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, displayId))
    }

    fun dismiss()
    fun setVisible(visible: Boolean)

    /**
     * Minimize the active M button after its user-selected idle timeout. This is
     * deliberately separate from [setVisible]: structural topology visibility still owns mount/GONE,
     * while idle hiding retains a one-pixel outside-touch watcher so a screen touch can reveal M without
     * intercepting that touch. The active primary or secondary overlay owns the same watcher path.
     */
    fun setPrimaryIdleHidden(hidden: Boolean)

    /**
     * Process-level wake/rearm bridge for touches handled by the native overlay window rather than an
     * Activity. Passing null removes the observer. The callback never owns the touch event.
     */
    fun setPrimaryIdleInteractionObserver(observer: (() -> Unit)?)

    /**
     * Whether Your music (or another player's session) is there to control, so a plain tap on the single-display M badge opens
     * the music panel ([CompanionOverlayAction.MusicPanel]) instead of the menu. Supplied by the app, which owns the audio
     * state; this module has no audio dependency. Read on the release of a tap, so it is never stale by more than one flow
     * emission. A hold is unaffected, and so is the Bottom's badge. An implementation without a badge ignores it.
     */
    fun setMusicPanelAvailable(available: Boolean) {}

    /**
     * Round-5 triage B2 (2026-09-11): screen activity from native overlay windows, for the Activity's screen-idle
     * authority. The Bottom's ᛗ, its menu and the overlay-hosted Tabs drawer are windows no Activity dispatcher sees, so
     * Main dimmed while the player used them (Thor build 5fa4c795: brightness stayed at 13 through a ᛗ hold and the menu
     * it opened). [setPrimaryIdleInteractionObserver] is unchanged and still only re-arms the ᛗ's own hide timer.
     *
     * Reports real touches only: showing or collapsing the menu and mounting the button report nothing. Passing null
     * removes the observer. Main thread. An implementation without native touch surfaces has nothing to report.
     */
    fun setScreenActivityObserver(observer: ((OverlayScreenActivity) -> Unit)?) {}

    /** Removes [observer] only while it is still the installed one, so a torn-down host cannot unhook its successor. */
    fun clearScreenActivityObserver(observer: (OverlayScreenActivity) -> Unit) {}

    /**
     * The screen-idle rule for the Bottom ᛗ menu's pad : [interaction] is one pad press, answered with whether it woke a dimmed screen (the app hands it Main's
     * `MimirScreenIdleController.onUserInteraction`, through the process-wide sink, so it is false when no Main is resumed). While the menu owns the Bottom's pad each press goes
     * through a [PadIdleGate] built on it: it re-arms the idle deadline, and the press that woke a dimmed screen is eaten for its own events only. The controller never dims or
     * wakes anything itself. Passing null removes it: the menu's pad then counts for nothing, as before. Main thread; an implementation without a pad menu ignores it.
     */
    fun setPadIdleInteraction(interaction: (() -> Boolean)?) {}

    /** A panel window this controller lent (the Tabs drawer) reports a touch it received itself. Main thread. */
    fun notifyScreenActivity(activity: OverlayScreenActivity) {}

    /** The committed target, or null while no complete overlay window set is mounted. */
    val activeTarget: StateFlow<CompanionOverlayTarget?>

    /**
     * Whether the long-press quick menu is currently the mounted overlay window, rather than the M button.
     *
     * Published because the surface BEHIND the menu has to react to it and cannot see it: on a dual-screen
     * device the menu is a `TYPE_APPLICATION_OVERLAY` window while the shelf beneath it is Mimir's own
     * `Presentation`, two window trees with no composition in common. The companion blurs its own scene from
     * this flow — it owns what is behind the menu — where the single-display primary must ask the compositor,
     * because what is behind IT belongs to another app.
     *
     * Follows the mounted WINDOW, not the intent to open one: unmounting for any reason (display removed,
     * permission lost, panel revoked) publishes false, so a backdrop can never stay treated for a menu that
     * is no longer on screen.
     */
    val quickMenuOpen: StateFlow<Boolean>

    /**
     * [quickMenuOpen] as what is DRAWN behind the menu should show it. Never read it for logic.
     *
     * It takes the destination value when a button/menu swap STARTS, as the outgoing view begins to fade, where
     * [quickMenuOpen] follows the mounted window and changes only when the swap lands. A scene that blurs from
     * this blurs with the fade rather than after it (perf 2026-09-11 §4.4b). With no swap in flight the two are
     * equal, unmount included. Routing, input, focus and menu completion read [quickMenuOpen].
     *
     * An implementation with no swap animation has nothing to lead and inherits [quickMenuOpen].
     */
    val quickMenuVisualOpen: StateFlow<Boolean>
        get() = quickMenuOpen

    /**
     * Whether a button/menu swap is still moving: true from the moment the outgoing window starts to fade until the INCOMING
     * window has finished its own animation (or the swap ended without landing). [quickMenuOpen] lands halfway through, when the
     * outgoing window is replaced, so it cannot say when the menu has finished moving; this can. For receipts and tests only,
     * never for logic. An implementation with no swap animation is never in flight.
     */
    val quickMenuSwapInFlight: StateFlow<Boolean>
        get() = NoQuickMenuSwap

    /**
     * Apply the active Mimir theme to the native M-button and its long-press tome. Implementations retain the
     * current button/menu mode and window position when the material changes.
     */
    fun setOverlayTheme(theme: CompanionOverlayTheme)

    /**
     * Dim (but KEEP) the M-button while Mimir's own mirror owns screen 2 — policy R12 (2026-07-09):
     * dim-and-keep, never hide. Full brightness returns while a foreign host owns the display.
     */
    fun setDimmed(dimmed: Boolean)

    /**
     * Hide the Bottom's floating ᛗ coin while one of Mimir's own Bottom panels (Utility, Tweaker, Tabs) is open over it (on the Thor the coin covered the
     * Utility's "Bottom screen 60%" value). The coin FADES out and is not touchable while hidden, and fades back in at the same position when [hidden] is false again; the window is
     * never removed, so nothing about where it rests or how it mounts changes. Only the Bottom's coin ([CompanionOverlayRole.SECONDARY_COMPANION]) answers; the single-display
     * coin on Main is never hidden by it. The caller owns the decision (the app derives it from the Bottom's surface stack); this controller only draws it. Main-thread safe.
     * An implementation with no coin ignores it.
     */
    fun setCoinHiddenForPanel(hidden: Boolean) {}

    /**
     * True when `SYSTEM_ALERT_WINDOW` is currently missing. Permission loss is treated as a supported runtime
     * condition without asserting which install/device path caused it. The primary surfaces a re-grant notice;
     * the durable fix lives at Settings > Troubleshooting > Draw over other apps.
     */
    val overlayPermissionMissing: StateFlow<Boolean>

    val actions: SharedFlow<CompanionOverlayAction>

    /**
     * A menu remains open until its typed action is accepted. A stale completion cannot collapse a newer menu.
     */
    fun completeQuickMenu(requestId: UUID, accepted: Boolean)

    /**
     * An accepted pick that opens a surface inside Mimir (Utility, Tweaker, Quick Launch).
     * The menu hands the screen to Mimir's own glass instead of fading on its own clock over it. A host with nothing
     * to hand off simply completes.
     */
    fun completeQuickMenuIntoMimir(requestId: UUID) = completeQuickMenu(requestId, true)

    fun hasOverlayPermission(): Boolean
    fun requestOverlayPermission()

    /**
     * Re-read display and overlay-permission state. The Android implementation also invokes this from its
     * AppOps/display listeners; Activity hosts call it from `onResume` so returning from Settings is immediate.
     */
    fun reconcile()

    /**
     * Process-level bridge for native windows that bypass Activity/Compose touch dispatch. Callbacks are paired
     * from DOWN through UP/CANCEL on main; replacing the observer first ends any sequence held by the old one.
     */
    fun setNativeOverlayInteractionObserver(observer: NativeOverlayInteractionObserver?)

    /**
     * attach a full-screen child [View] in its OWN `TYPE_APPLICATION_OVERLAY` window on the secondary
     * display, so a Compose surface hosted by the app (the tab-manager drawer) can z-order OVER a foreground
     * foreign app without the app-evicting reclaim a [Presentation] would require. The window is
     * touch-enabled but `FLAG_NOT_FOCUSABLE` (companion is touch-only, no controller focus). Call on the main
     * thread. Attaching replaces any existing panel. The returned UUID lease is the only authority that can
     * detach its window; a stale lease cannot remove a replacement. [onRevoked] is invoked exactly once on the
     * main thread when the controller force-detaches this lease, and must dispose only state captured for this
     * lease.
     */
    fun attachSecondaryPanel(
        view: android.view.View,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult

    /**
     * Attach a focusable full-screen child panel on the default display while the committed overlay
     * role is [CompanionOverlayRole.PRIMARY_SINGLE]. This is the primary-display counterpart to
     * [attachSecondaryPanel]: it keeps the current foreground app mounted beneath Mimir's global
     * Quick Launch/Utility surface. The exact lease owns detach; revocation is delivered once on main.
     */
    fun attachPrimaryPanel(
        view: android.view.View,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult

    /** Apply or clear the app-window brightness fallback on the currently leased primary panel. */
    fun setPrimaryPanelBrightness(fraction: Float?)

    /**
     * The window blur behind the leased primary panel, [fraction] of the way (0 clear, 1 full) through the panel's own open or close
     * clock. A no-op where the platform does not blur across windows, and by default (a fake that has no window).
     */
    fun setPrimaryPanelBlurBehind(fraction: Float) = Unit

    /**
     * The leaving primary panel stops being the focused, touchable window while it fades out: it ORs not-focusable and not-touchable
     * into its window's flags (every other flag kept) and relayouts. The app beneath takes keys and touches from this call on.
     * A no-op by default (a fake that has no window).
     */
    fun releasePrimaryPanelInput() = Unit

    /**
     * Where the single-display M badge rests, in pixels of the default display, or null while no primary badge is
     * mounted (Bottom role, permission missing, target dropped).
     *
     * The 56 dp square at the saved spot, not the one-pixel idle watcher: an idle-hidden or menu-open badge still reports
     * the spot it returns to, so a reserve drawn from it does not jump. Republished on mount, when a drag settles on an
     * edge, and cleared on unmount. A display resize that does not remount the overlay does not republish it.
     *
     * Defaults to "none" so fakes and non-Android implementations need not override it.
     */
    val primaryButtonBounds: StateFlow<OverlayButtonBounds?>
        get() = NoOverlayButtonBounds
}

/** User-visible role, independent from the transient Android display id. */
enum class CompanionOverlayRole {
    PRIMARY_SINGLE,
    SECONDARY_COMPANION,
}

data class CompanionOverlayTarget(
    val role: CompanionOverlayRole,
    val displayId: Int,
) {
    init {
        require(displayId >= 0) { "Overlay display id must be non-negative" }
        require(role != CompanionOverlayRole.PRIMARY_SINGLE || displayId == android.view.Display.DEFAULT_DISPLAY) {
            "Primary-single overlay must target Android's default display"
        }
    }
}

/**
 * The mark on the floating ᛗ button's disc. Dependency-free like [CompanionOverlayTheme]: the app maps its
 * `MimirButtonMark` to this, because this module cannot depend on core-ui.
 *
 * Either way the mark is STRUCK, never tinted: its alpha is the mask and the theme's metal is poured through it
 * (`CompanionMenuCoinView`), so it recolours with the theme and reacts to a press as the rune does.
 */
sealed interface CompanionButtonMark {
    /** The shipping Mannaz rune (`mimir_mark`), the default and the fallback. */
    data object Rune : CompanionButtonMark

    /**
     * The player's own mark: a 256 px PNG in the app's private storage whose alpha is the mask. [id] is the content
     * hash the file is named by, kept for the device log line so no path is ever logged. A missing or unreadable file
     * draws the [Rune] and logs `mark=rune reason=missing`.
     *
     * [placement] is where the player put the image on the coin : its size and its offset from the coin's centre. It is part of
     * the mark's equality on purpose, so the controller's rebuild-on-change plan repaints an attached button when only the placement changed.
     * The rune has none.
     */
    data class Image(
        val id: String,
        val path: String,
        val placement: MimirMarkPlacement = MimirMarkPlacement.Default,
    ) : CompanionButtonMark
}

/**
 * Dependency-free native-overlay material roles. Values are opaque raw ARGB so the app can map any Mimir
 * theme without making this Android-native module depend on Compose or core-ui.
 */
data class CompanionOverlayTheme(
    val coverArgb: Int,
    val coverEdgeArgb: Int,
    val pageArgb: Int,
    val pageEdgeArgb: Int,
    val inkArgb: Int,
    val mutedInkArgb: Int,
    val accentArgb: Int,
    val toolingArgb: Int,
    /**
     * The shelf's own four-stop metallic ramp, carried across so the menu's marks are struck from the
     * SAME metal as the console art — not from a gold invented for the overlay.
     *
     * `platformConsoleIconBrush` in core-ui is the authority; these are its stops at 0.00 / 0.40 / 0.62
     * / 1.00, flattened to raw ARGB because this module cannot depend on Compose. Per-theme, so Folkvangr's
     * rose metal and Baldr's azure stay the theme's own and none of it is hardcoded here.
     */
    val metalLightArgb: Int,
    val metalHotArgb: Int,
    val metalMidArgb: Int,
    val metalDeepArgb: Int,
    /**
     * The primary quick menu's key-focus ring: the app's `tileFocusRing` token, so the player's ring colour reaches
     * the overlay the way it reaches every shelf. Defaults to [pageEdgeArgb], the authored
     * bright accent, which is what that token is until an override moves it. Outside the opaque check below on purpose: the
     * adapter forces it opaque and the ring view ignores whatever alpha it is handed.
     */
    val focusRingArgb: Int = pageEdgeArgb,
    /**
     * What the floating ᛗ button carries on its disc (concept A): the shipping rune, or the player's own
     * mark. Not a colour, so it is outside the opaque check below. A data-class member on purpose: the controller's
     * rebuild-on-change plan compares whole themes, so a mark change alone rebuilds an attached button, and an equal
     * mark (the same content-named file) never re-adds the window.
     */
    val mark: CompanionButtonMark = CompanionButtonMark.Rune,
) {
    init {
        require(
            listOf(
                coverArgb,
                coverEdgeArgb,
                pageArgb,
                pageEdgeArgb,
                inkArgb,
                mutedInkArgb,
                accentArgb,
                toolingArgb,
                metalLightArgb,
                metalHotArgb,
                metalMidArgb,
                metalDeepArgb,
            ).all { it ushr 24 == 0xff },
        ) { "Companion overlay material roles must be opaque ARGB colors" }
    }

    companion object {
        /** Readable neutral default used until the app supplies its active Mimir theme. */
        val Default = CompanionOverlayTheme(
            coverArgb = 0xff16191d.toInt(),
            coverEdgeArgb = 0xff8e7652.toInt(),
            pageArgb = 0xfff1e7ce.toInt(),
            pageEdgeArgb = 0xffc4af82.toInt(),
            inkArgb = 0xff25231f.toInt(),
            mutedInkArgb = 0xff655f54.toInt(),
            accentArgb = 0xff16737d.toInt(),
            toolingArgb = 0xffd7b86e.toInt(),
            metalLightArgb = 0xfffff3c8.toInt(),
            metalHotArgb = 0xfff7d07d.toInt(),
            metalMidArgb = 0xffe2b873.toInt(),
            metalDeepArgb = 0xffbb8c4e.toInt(),
        )
    }
}

@JvmInline
value class PanelLeaseId(val value: UUID) {
    init {
        require(value != UUID(0L, 0L)) { "Panel lease id must be non-empty" }
    }
}

interface CompanionPanelLease {
    val id: PanelLeaseId

    /** Call on main. Returns true only when this lease was still active and detached its own panel. */
    fun detach(): Boolean
}

sealed interface PanelAttachResult {
    data class Attached(val lease: CompanionPanelLease) : PanelAttachResult
    data class Rejected(val reason: PanelRejectReason) : PanelAttachResult
}

enum class PanelRejectReason {
    NOT_MAIN_THREAD,
    OVERLAY_PERMISSION_MISSING,
    DISPLAY_UNAVAILABLE,
    WINDOW_MANAGER_UNAVAILABLE,
    ADD_VIEW_FAILED,
}

enum class PanelRevocationReason {
    REPLACED,
    DISPLAY_LOST,
    PERMISSION_LOST,
    RETARGETED,
}

enum class NativeOverlayInteractionSource {
    M_BUTTON,
    MENU,
    EDGE_BACK,
}

fun interface NativeOverlayInteractionObserver {
    fun onInteractionChanged(source: NativeOverlayInteractionSource, active: Boolean)
}

/** Main-thread pairing core shared by the M-button, quick menu, and edge gesture adapters. */
internal class NativeOverlayInteractionSession(
    private val source: NativeOverlayInteractionSource,
    private val observerProvider: () -> NativeOverlayInteractionObserver?,
) {
    private var activeObserver: NativeOverlayInteractionObserver? = null

    fun begin() {
        finish()
        val observer = observerProvider() ?: return
        activeObserver = observer
        runCatching { observer.onInteractionChanged(source, true) }
    }

    fun finish(): Boolean {
        val observer = activeObserver ?: return false
        activeObserver = null
        runCatching { observer.onInteractionChanged(source, false) }
        return true
    }
}

/** What a native overlay window saw, as screen activity. See [CompanionOverlayController.setScreenActivityObserver]. */
enum class OverlayScreenActivity {
    /** A touch the overlay window received itself. Counts as interaction and wakes dimmed displays. */
    DIRECT,

    /**
     * The `ACTION_OUTSIDE` copy of a touch another window received. Re-arms an awake session and never wakes: the copy
     * also arrives for a touch on Mimir's own Presentation, whose first touch after a dim is wake-only.
     */
    OUTSIDE,
}

/** Main-thread observer slot behind [CompanionOverlayController.setScreenActivityObserver]. */
internal class OverlayScreenActivityObserverSlot {
    private var observer: ((OverlayScreenActivity) -> Unit)? = null

    fun set(observer: ((OverlayScreenActivity) -> Unit)?) {
        this.observer = observer
    }

    /** Clears only [expected]: a teardown that runs after its successor installed must leave the successor in place. */
    fun clear(expected: (OverlayScreenActivity) -> Unit): Boolean {
        if (observer !== expected) return false
        observer = null
        return true
    }

    fun emit(activity: OverlayScreenActivity?) {
        if (activity == null) return
        observer?.invoke(activity)
    }
}

sealed interface CompanionOverlayAction {
    /**
     * an inward swipe from the display's right edge — "return to previous state", the same gesture as
     * Android's back-from-app. Restores Mimir's mirror over whatever foreign app holds screen 2. (Replaces
     * the retired flick-on-M `ToggleCompanion`; the M itself no longer triggers a return.)
     */
    data object EdgeBack : CompanionOverlayAction
    data class QuickMenu(
        val requestId: UUID,
        val role: CompanionOverlayRole,
        val item: QuickMenuItem,
        /** Where the request came from: a row of an open menu, or  a tap on the second-display coin. */
        val origin: QuickMenuOrigin = QuickMenuOrigin.MENU_ROW,
    ) : CompanionOverlayAction
    /**
     * A plain tap on the single-display M badge while [CompanionOverlayController.setMusicPanelAvailable] was true: the music
     * panel is asked for. Carries no request id, because no overlay window is waiting on an answer (the badge stays as it is
     * and the app's panel host hides it while the panel is up).
     */
    data class MusicPanel(val role: CompanionOverlayRole) : CompanionOverlayAction
    data class DragMoved(
        val role: CompanionOverlayRole,
        val displayId: Int,
        val xRatio: Float,
        val yRatio: Float,
    ) : CompanionOverlayAction
}

/** What produced a [CompanionOverlayAction.QuickMenu]: only the receipt and the log tell them apart; the app routes both alike. */
enum class QuickMenuOrigin { MENU_ROW, COIN_TAP }

// CLOSE_ALL retired — apps close from the tab manager (per-tab ✕), never a bulk sweep.
enum class QuickMenuItem {
    SYSTEM_TABS,
    QUICK_LAUNCH,
    UTILITY,
    TWEAKER,
    SECONDARY_TABS,
    SECONDARY_RETURN,
    SECONDARY_SWAP,

    /** The Bottom menu's full-screen trackpad (a mouse for Main); answered by the app, never by the overlay. */
    TRACKPAD,
}

interface SecondaryTaskRegistry {
    val state: StateFlow<SecondaryDisplayState>
    fun launchOnSecondary(packageName: String): Boolean
    fun swapToPrimary(packageName: String, operationId: UUID = UUID.randomUUID()): SecondarySwapResult?
    fun swapToSecondary(packageName: String): SecondarySwapResult?
    fun closeAll(): SecondaryCloseResult
    /** CR-11: close ONE Mimir-tracked secondary app — best-effort, same honesty as [closeAll]. */
    fun closeOne(packageName: String): SecondaryCloseResult
    /** Observation-only close evidence. Advisory samples return false and cannot remove a tracked tab. */
    fun observeClose(observation: SecondaryCloseObservation): Boolean
    /** Forget tracking without claiming Android closed the package. */
    fun forget(packageName: String): Boolean
    fun recentAppIcons(limit: Int): List<AppEntry>
}

object PrimaryTransferHostContract {
    const val ACTIVITY_CLASS_SUFFIX = ".PrimaryTransferActivity"
    const val EXTRA_OPERATION_ID = "mimir_primary_transfer_operation_id"
    const val EXTRA_TARGET_PACKAGE = "mimir_primary_transfer_target_package"

    fun activityClassName(applicationPackage: String): String =
        applicationPackage.trimEnd('.') + ACTIVITY_CLASS_SUFFIX
}

/** Tokenized transfer dispatch. It never claims target-display ownership or settles screen 2. */
data class SecondarySwapResult(
    val packageName: String,
    val operationId: UUID,
    val targetDisplayId: Int,
    val dispatchAccepted: Boolean,
) {
    val transferConfirmed: Boolean = false
    val truth: String = "Transfer dispatch is not display-ownership evidence."

    init {
        require(packageName.isNotBlank()) { "Swap package is mandatory" }
        require(operationId != UUID(0L, 0L)) { "Swap operation id must be non-empty" }
        require(targetDisplayId >= 0) { "Swap target display must be valid" }
    }
}

object CompanionReturnHostContract {
    const val ACTIVITY_CLASS_SUFFIX = ".CompanionReturnActivity"
    const val EXTRA_OPERATION_ID = "mimir_secondary_operation_id"
    const val EXTRA_TARGET_PACKAGE = "mimir_secondary_target_package"
    const val EXTRA_LAUNCH_MODE = "mimir_secondary_launch_mode"
    const val EXTRA_TARGET_INTENT = "mimir_secondary_target_intent"
    const val EXTRA_RESTORE_REASON = "mimir_secondary_restore_reason"
    const val EXTRA_PRIMARY_ORIGIN_DESTINATION = "mimir_primary_origin_destination"
    const val EXTRA_PRIMARY_ORIGIN_KEYS = "mimir_primary_origin_keys"
    const val EXTRA_PRIMARY_ORIGIN_VALUES = "mimir_primary_origin_values"
    const val LAUNCH_MODE_RESUME = "resume"
    const val LAUNCH_MODE_SECONDARY_APP = "secondary_app"
    const val LAUNCH_MODE_SECONDARY_GAME = "secondary_game"
    /** Existing Main DS/3DS game exposed by an explicit Mimir handoff; never a new Bottom launch. */
    const val LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN = "existing_main_game_return"
    const val LAUNCH_MODE_SCREEN_TRANSFER = "screen_transfer"
    const val LAUNCH_MODE_RESTORE_ONLY = "restore_only"
    const val LAUNCH_MODE_HOME_RECLAIM = "home_reclaim"

    fun activityClassName(packageName: String): String = packageName + ACTIVITY_CLASS_SUFFIX
}

data class SecondaryCloseResult(
    val requestedPackages: List<String>,
    val operationIdsByPackage: Map<String, UUID>,
) {
    val closeRequested: Boolean = requestedPackages.isNotEmpty()
    val clearedTrackedState: Boolean = false
    val closeGuarantee: Boolean = false
    val mediaMayContinue: Boolean = true
    val localOnly: Boolean = true
    val usesNetwork: Boolean = false
    val truth: String =
        "Close is a best-effort Android request for Mimir-tracked external apps. Android may keep " +
            "foreground, protected, media, or restarting packages alive; tracking remains until certified " +
            "terminal evidence or an explicit, separately-labelled forget action."

    init {
        require(requestedPackages.size == requestedPackages.toSet().size) {
            "Close requests must not contain duplicate packages"
        }
        require(operationIdsByPackage.keys == requestedPackages.toSet()) {
            "Every requested package must carry exactly one close operation id"
        }
        require(operationIdsByPackage.values.none { it == UUID(0L, 0L) }) {
            "Close operation ids must be non-empty"
        }
    }
}

enum class SecondaryTaskCloseState {
    OPEN,
    CLOSE_REQUESTED_UNVERIFIED,
    CLOSE_UNCONFIRMED,
}

sealed interface SecondaryCloseObservation {
    val packageName: String

    /** Android package-removal broadcast: terminal evidence that the tracked package no longer exists. */
    data class PackageRemoved(override val packageName: String) : SecondaryCloseObservation

    /** Display-less UsageStats background is advisory and never terminal close evidence. */
    data class UsageStatsBackground(override val packageName: String) : SecondaryCloseObservation

    /** Absence from ActivityManager's process list is advisory and never terminal close evidence. */
    data class RunningProcessAbsent(override val packageName: String) : SecondaryCloseObservation

    /**
     * A privileged readback proved that no process runs under [packageName]. Terminal for an OPEN tab,
     * in the same evidence class as a verified close (`SecondaryTaskLedger.markCloseVerified`).
     *
     * What separates it from [RunningProcessAbsent] is who read it. That sample comes from a normal
     * app's own process list, which cannot see other apps' processes, so it stays advisory. This one is
     * a privileged liveness probe for ONE package in its own transaction (on this tree, the certified
     * vendor route's `pidof` readback), and the probe stops nothing. A probe that did not answer -- no
     * authority, no reply, no `pidof`, a timeout -- is UNKNOWN and never becomes this observation.
     * Dispatch success and UsageStats background stay non-terminal exactly as before.
     *
     * The ledger retires only an OPEN row on it: a row a close request already owns keeps that close's
     * evidence path. A GAME row retires too (Thor, 2026-09-12); the registry that reads the probe spares a
     * Bottom game launch still starting and settles what the row's ✕ settles besides the row, the Bottom
     * game session.
     */
    data class ProcessAbsentVerified(override val packageName: String) : SecondaryCloseObservation

    /** Successful best-effort request dispatch is not close evidence. */
    data class RequestDispatched(
        override val packageName: String,
        val operationId: UUID,
    ) : SecondaryCloseObservation
}

data class SecondaryDisplayState(
    val primaryDisplayId: Int = 0,
    val secondaryDisplayId: Int? = null,
    val foregroundByDisplay: Map<Int, String> = emptyMap(),
    val recents: List<AppEntry> = emptyList(),
    val generatedAtMs: Long = System.currentTimeMillis(),
    val truth: String = "Tracked apps are sessions Mimir launched or requested to move. Dispatch and display-less UsageStats do not prove display ownership. Tabs persist across a Mimir restart in their opened order; their processes may have ended.",
)

/**
 * What a tracked Bottom tab IS, which decides how the drawer names it and what its ✕ has to settle.
 *
 * a game
 * dispatched to the Bottom was deliberately kept out of this ledger, so the one surface that can bring a
 * screen-2 task back had no row for it. It is a tab — the kind is what keeps it from being mistaken for an
 * ordinary app launch, which is the hazard that exclusion was guarding against.
 */
enum class SecondaryTaskKind {
    APP,
    GAME,
}

data class AppEntry(
    val packageName: String,
    val label: String,
    val displayId: Int?,
    val lastSeenAtMs: Long,
    val iconPackageName: String = packageName,
    val closeState: SecondaryTaskCloseState = SecondaryTaskCloseState.OPEN,
    val closeOperationId: UUID? = null,
    val closeRequestedAtElapsedRealtimeMs: Long? = null,
    val kind: SecondaryTaskKind = SecondaryTaskKind.APP,
    /**
     * Library truth for a [SecondaryTaskKind.GAME] row, or null when the dispatch carried none.
     *
     * Null is not "no game": the registry learns the emulator package from the dispatched Intent, while
     * title/id/art come from the library row the app resolved. A GAME row with a null title falls back to
     * [label] (the emulator's own label), which is the honest name for "a game is running in this emulator".
     */
    val gameTitle: String? = null,
    val gameId: String? = null,
    /** The game's cover-art ref (`content:` / `file:` / `android.resource:`), never a bare file path. */
    val gameArtRef: String? = null,
) {
    init {
        require(kind == SecondaryTaskKind.GAME || (gameTitle == null && gameId == null && gameArtRef == null)) {
            "Only a GAME tab may carry game identity"
        }
        when (closeState) {
            SecondaryTaskCloseState.OPEN -> {
                require(closeOperationId == null && closeRequestedAtElapsedRealtimeMs == null)
            }
            SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
            SecondaryTaskCloseState.CLOSE_UNCONFIRMED,
            -> {
                require(closeOperationId != null && closeOperationId != UUID(0L, 0L))
                require(closeRequestedAtElapsedRealtimeMs != null && closeRequestedAtElapsedRealtimeMs >= 0L)
            }
        }
    }
}

interface ExternalForegroundMonitor {
    val returned: SharedFlow<ReturnEvent>
    /** UsageStats observations are advisory and deliberately separate from explicit return events. */
    val foregroundObserved: SharedFlow<ForegroundReturnObservation>
    /** Every foreign foreground edge; consumers may classify it only from their current display disposition. */
    val foreignForegroundObserved: SharedFlow<ForeignForegroundObservation>
    fun start()
    fun stop()
    fun updateObservationDemand(demand: ForegroundMonitorDemand)
    fun notifyExplicitReturn(packageName: String?, displayId: Int?, reason: String)
}

data class ForeignForegroundObservation(
    val packageName: String,
    val observedAtMs: Long = System.currentTimeMillis(),
    val truth: String = "Display-less UsageStats identifies a foreground edge, not the owning display.",
)

/**
 * One activity that UsageStats says resumed: WHICH activity, not only which package.
 *
 * [ForeignForegroundObservation] cannot serve a launch cover: it is a package-level edge with no class name, it skips the
 * shell packages, and it is a replaying flow, so a listener would be handed the last launch's edge. The cover has to tell
 * ARMSX2's boot splash from its game, both in one package, and has to see EVERY resume between the dispatch and the game,
 * so it gets its own observation with its own cursor (see [ActivityResumeObserver]).
 *
 * @property eventAtMs when the activity resumed, as UsageStats stamps it (wall clock, `System.currentTimeMillis()`).
 * @property observedAtMs when the poll that first read the event ran. `observedAtMs - eventAtMs` is how far the
 *   observation lagged the resume: the beat of the poll plus however long UsageStats took to publish it.
 */
data class ActivityResumedObservation(
    val packageName: String,
    val className: String,
    val eventAtMs: Long,
    val observedAtMs: Long,
)

/**
 * Local, advisory observation of activity resumes for the launch cover. Needs Usage Access; without it nothing is
 * delivered, and a caller that needs the observation checks [UsageAccessState.granted] first.
 */
interface ActivityResumeObserver {
    /**
     * Starts delivering every resume at or after [sinceEpochMs] (wall clock) to [onResumed], once each, on the monitor's
     * own thread, at the task-evidence beat ([ForegroundMonitorCadence.taskEvidencePollMs]) whatever the display
     * disposition. That is the point of this seam: the shared poll loop is idle on a single-screen device (no display
     * to follow), so a cover that waited on it would hear nothing. Closing the handle stops the beat; the loop stops
     * when its last observer closes.
     */
    fun observeActivityResumes(sinceEpochMs: Long, onResumed: (ActivityResumedObservation) -> Unit): AutoCloseable
}

data class ReturnEvent(
    val packageName: String?,
    val displayId: Int?,
    val reason: String,
    val observedAtMs: Long = System.currentTimeMillis(),
)

data class ForegroundReturnObservation(
    val packageName: String,
    val reason: String = "usage_stats_return_to_mimir_observed",
    val observedAtMs: Long = System.currentTimeMillis(),
    val truth: String = "Display-less UsageStats is advisory task evidence and cannot settle secondary-display disposition.",
) {
    val displayId: Int? = null
    val canSettleDisplayDisposition: Boolean = false
}

interface LaunchForegroundObserver {
    fun usageAccessState(): UsageAccessState
    fun requestUsageAccess()
    fun observeLaunchForeground(
        targetPackage: String,
        launchedAtMs: Long,
        returnedAtMs: Long,
        singleScreenLaunchShape: Boolean = true,
    ): LaunchForegroundObservation
}

data class UsageAccessState(
    val packageName: String,
    val granted: Boolean,
    val permission: String = "android.permission.PACKAGE_USAGE_STATS",
    val specialAccess: Boolean = true,
    val localOnly: Boolean = true,
    val usesNetwork: Boolean = false,
    val truth: String = "Usage Access is optional Android special access used only to observe local foreground app events after a launch intent.",
)

data class LaunchForegroundObservation(
    val targetPackage: String,
    val usageAccessGranted: Boolean,
    val singleScreenLaunchShape: Boolean,
    val foregroundObservable: Boolean,
    val targetForegroundObserved: Boolean = false,
    val firstForegroundAtMs: Long = 0L,
    val lastForeignForegroundAtMs: Long = 0L,
    val lastForeignBackgroundAtMs: Long = 0L,
    val targetForegroundSpanMs: Long = 0L,
    val targetForegroundSpanBounded: Boolean = false,
    val lastTargetEventType: String = "",
    val windowStartMs: Long,
    val windowEndMs: Long,
    val skipped: Boolean = false,
    val skipReason: String = "",
    val localOnly: Boolean = true,
    val usesNetwork: Boolean = false,
    val truth: String = "Foreground observation uses Android UsageStatsManager queryEvents locally between launch and return; it is evidence, not a synchronous LaunchOutcome verdict.",
) {
    val schemaVersion: String = "mimirLaunchForegroundObservation.v1"
    val silentDeathProven: Boolean = LaunchForegroundEvidence.silentDeathProven(this)
    val silentDeathReason: String = LaunchForegroundEvidence.silentDeathReason(this)
}
