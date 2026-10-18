package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gruning.mimir.rewrite.core.companionnative.BottomPadInput
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.companion.CompanionMannazCover

/**
 * **Claims display 4 so `SecondaryDisplayLauncher` cannot** — policy 2026-08-31.
 *
 * ## The defect this exists to remove
 *
 * Mimir is the device's default launcher, but it declared only `CATEGORY_HOME`. There was **no
 * `SECONDARY_HOME` filter anywhere in the manifest**, so `com.android.launcher3`'s
 * `SecondaryDisplayLauncher` was the only candidate for the second display and Android handed it that
 * display whenever one was needed. Mimir's `Presentation` drew OVER it, which is why the arrangement
 * appeared to work — until anything surfaced the real home underneath, at which point the Presentation
 * unmounted and did not come back. That ended three device-verification windows on 2026-08-31 before
 * the cause was found; see `DEFECT-presentation-does-not-remount.md`.
 *
 * ## Why a separate activity and not a filter on `MainActivity`
 *
 * Putting `SECONDARY_HOME` on `MainActivity` is the one-line version and it walks into a bug the
 * manifest already records: with a HOME-capable activity, a home-stack instance and a normal-launch
 * instance coexisted on the Thor — *"two monitors doubling return events, two companion controllers
 * fighting the reclaim ledger, a shelf-fresh duplicate breaking launch-return."* `singleTask` was
 * added to kill that. Declaring a SECOND home category on the same activity invites the second
 * instance straight back, on a different display this time.
 *
 * So the claim is made by something that cannot have that problem: an activity with no launch surface
 * of its own and no back-stack presence beyond the floor itself. MainActivity's two-instance bug turns
 * on whether `SECONDARY_HOME` resolves to a second instance of MainActivity — it does not turn on
 * whether the floor paints a static cover or calls a stateless coordinator method (2026-09-11 additions,
 * see below), so neither reopens it. It holds the display so nothing else can, and Mimir's
 * `Presentation` renders above it exactly as it does today.
 *
 * ## Why `singleTop`, and never `singleTask` — the reason this floor stood empty for a week
 *
 * The first declaration was `singleInstance`, the second `singleTask`, on the belief that *"the system
 * launcher declares exactly that."* It does not: the Thor's own `Launcher3QuickStepEX6.apk` declares
 * `SecondaryDisplayLauncher` with `launchMode=1` = `singleTop`. And the platform is explicit —
 * `RootWindowContainer.canStartHomeOnDisplayArea` (Android 13) returns false for any home whose launch
 * mode is `singleTask` or `singleInstance` on a non-default display: *"Can't launch home on secondary
 * displays if it requested to be single instance."* So `resolveSecondaryHomeActivity` found this floor in
 * the default-home package, rejected it, and fell back to `config_secondaryHomePackage` = launcher3 —
 * every time, on every boot. That is why `MimirSecondaryHome` never logged once and display 4's
 * `topResumedActivity` stayed `SecondaryDisplayLauncher` from 2026-08-31 to 2026-09-06, while
 * `am start --display 4` worked: an explicit start never passes through that check.
 * `CompanionSecondaryHomeManifestTest` pins the attribute — and `configChanges`: on 2 of 5 measured wakes the
 * platform pushed a transient 320 → 369 dpi config on display 4 and relaunched this floor for it, freezing the
 * second screen for ~400 ms. Handling the set MainActivity already handles removes the relaunch; nothing here
 * depends on configuration.
 *
 * ## Focusable, never touchable — the ANR this shape exists to remove (Thor 2026-09-11 09:34)
 *
 * This floor used to add [FLAG_NOT_FOCUSABLE][WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] alongside
 * [FLAG_NOT_TOUCHABLE][WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE], on the reasoning that a floor
 * receives nothing at all. Every other Mimir window on display 4 is non-focusable too — the
 * `Presentation` (`MimirCompanionPresentation`: `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL`) and the
 * ᛗ overlay (`AndroidCompanionOverlayController.panelParams`, whose own comment is *"Primary is
 * focusable for controller/Back; companion remains touch-only"*) — so with this floor resumed and no
 * foreign window present, display 4 had a focused *app* and no focused *window* at all.
 *
 * Android's `RootWindowContainer` makes a display with a focused app the top focused display whether or
 * not it has a focused window, so every key and joystick event on the device routed there and waited.
 * Measured on the Thor at 09:34 (Mimir `468807603de9`) after Chrome closed itself off display 4:
 * `dumpsys input` showed `FocusedDisplayId: 4` with `FocusedWindows:` empty, and the dispatcher logged
 * `"Waiting because no window has focus but ActivityRecord{… CompanionSecondaryHomeActivity} may
 * eventually add a window when it finishes starting"`, then four seconds of `"Pruning input queue
 * because user touched a different application while waiting for …"`, then
 * `"ANR in com.gruning.mimir.rewrite … Input dispatching timed out (Application does not have a focused
 * window)"` — the whole process, from the second screen's floor. An earlier trace of the same shape is
 * on the device from 2026-09-10 23:15, so it recurs.
 *
 * Dropping `FLAG_NOT_FOCUSABLE` is the fix, and it costs nothing the flag was protecting: the two
 * flags are independent, and TOUCH is what the Presentation needed protected. `FLAG_NOT_TOUCHABLE`
 * stays, so taps on display 4 still pass straight through this window to the Presentation above it —
 * including the ones outside the Presentation's own bounds, which it declines via `NOT_TOUCH_MODAL`.
 * What changes is that keys now have a window to land on instead of a five-second wait, and
 * [dispatchKeyEvent]/[dispatchGenericMotionEvent] below give them somewhere to go: the resumed
 * `MainActivity`'s own authority, through [MimirMainInputSink].
 *
 * ## Why it draws only the Mannaz cover
 *
 * What is visible on the rare frames where the Presentation is NOT attached above it was, per device
 * evidence (below), a plain black window — which reads as broken. `CompanionMannazCover` paints Main's return cover: the badge on the theme's ground, no text
 * (policy 6, 2026-10-02; it was a sigil disc with "Returning to shelf…", false on a cold start). It is drawn by the same
 * badge Main's own return cover draws (see that composable's KDoc), so a covered frame and an uncovered one show the same
 * thing. That is not a second Mimir-looking thing with its own lifecycle; it is the one loading look,
 * available from whichever window happens to be on top.
 *
 * ## Auto-recovery on resume
 *
 * Device evidence: after `am force-stop` + restart with an app previously yielded on the Bottom, the
 * runtime logs `ProcessRecovery NoDisplay->RecoveryNeeded`, then `RendererBound`/
 * `DisplayAvailableObserved` land while still `RecoveryNeeded` — display 4 stayed on the black cover
 * above until the user did the right-edge swipe (`explicitReclaim` -> `PresentationShown`). `onResume`
 * now reports itself to [CompanionReturnCoordinator.noteSecondaryHomeResumed], which reclaims
 * automatically in that disposition and, since the earlier update (Thor 2026-10-08), over a `Yielded` app lease too: a HOME
 * press on the Bottom puts this floor over the app and its return host, so neither resumes and nothing else
 * would end the lease, and Mimir's Presentation sat parked behind this cover for good. See its KDoc for why
 * every other disposition is left alone.
 *
 * ## Cover reporting — D41 (Thor 2026-10-08)
 *
 * The converse of the resume above: this floor being paused and then STOPPED on display 4, while Mimir is presenting there, is the display-exact
 * evidence that an opaque task now covers the screen, e.g. a Chrome picked from Android's Recents. [CompanionFloorCoverWatch] pairs it with the
 * UsageStats resume that names the task and yields the Bottom to it. It decides nothing here: these two callbacks only report.
 */
class CompanionSecondaryHomeActivity : MimirLocaleActivity() {

    // Follows the stored language when created, like every Mimir Activity, but is never recreated for a change: the floor draws no text,
    // and a relaunch on display 4 is the freeze its manifest configChanges exist to avoid. Below API 33 this flag keeps it; from API 33
    // the manifest's locale|layoutDirection does (LocaleManager relaunches only windows that do not handle locale themselves).
    override val recreatesWhenLanguageChanges: Boolean = false

    /** Keys and motion sources already reported absorbed — see [logAbsorbedOnce]. */
    private val absorbedKeyCodes = mutableSetOf<Int>()
    private val absorbedMotionSources = mutableSetOf<Int>()

    /** The display this floor claimed in `onCreate`, for the cold-start handoff to disarm on when the floor goes. */
    private var floorDisplay: Int = BottomUiReceiptFormat.NO_DISPLAY

    override fun onDestroy() {
        CompanionColdStartHandoff.floorDestroyed(floorDisplay)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The floor belongs on a secondary display only (P2-3, code review 2026-10-08). The recreate in [planFloorFront] aims its start at display 4, but a
        // presentation display where the platform never places a secondary home could hand it to display 0 instead, and a floor over Main would take Main's
        // keys and paint the cover over the shelf. On the default display it ends here, before it claims or draws anything. Anywhere else it is remembered
        // ([CompanionFloorDisplays]), which is what lets a later reclaim recreate it there.
        val claimedDisplay = display?.displayId ?: BottomUiReceiptFormat.NO_DISPLAY
        if (CompanionFloorDisplayGuard.refuses(claimedDisplay)) {
            Log.w(TAG, "secondary home refused display $claimedDisplay: the floor belongs on a secondary display, finishing")
            finish()
            return
        }
        CompanionFloorDisplays.record(applicationContext, claimedDisplay)
        // Never take a TOUCH from the Presentation drawn above: this window exists to occupy the
        // display, not to be tapped. Without NOT_TOUCHABLE it would swallow touches that belong to the
        // companion surface -- the exact failure mode it was created to prevent, arriving from the
        // other side.
        //
        // NOT_FOCUSABLE is deliberately NOT set with it (Thor 09:34 2026-09-11 -- see the class KDoc):
        // it is the only window Mimir puts on display 4 that can hold key focus, and with nothing
        // focusable there the dispatcher ANR'd the process rather than deliver a key. Focus here is
        // where display 4's keys land; NOT_TOUCHABLE is what keeps its touches passing through.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // NEVER the input-method target (Thor 2026-10-06, KBD-T). The vendor build routes every IME to display 4, and
        // this focusable floor was WindowManager's IME layering, input and control target there, so Chrome's
        // SHOW_SOFT_INPUT (from Main!) landed on this window and no keyboard ever appeared. FLAG_ALT_FOCUSABLE_IM
        // alone makes canBeImeTarget() false (a window is a target only with NOT_FOCUSABLE and ALT_FOCUSABLE_IM both
        // set or both clear) while it stays focusable, so the key-focus role above is untouched. The floor has no
        // text field and never needs a keyboard.
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        Log.i(TAG, "secondary home claimed display ${display?.displayId}")
        // paint the same loading cover the Presentation shows, so a frame
        // where the Presentation is not yet attached reads as "still connecting" instead of a bare
        // black window. NOT_TOUCHABLE above already keeps this floor out of the touch path; drawing a
        // cover only changes what pixels are underneath, never who can touch them.
        //
        // The theme is collected, not read once. Nothing recreates this floor when the theme changes, and it is
        // usually created before Main's first push: after a death or an install the platform restarts it in a process
        // with no MainActivity, where the payload carries only the theme process start seeded
        // (seedCompanionThemeAtProcessStart). A one-time `.value` read kept that theme for the floor's whole life,
        // whatever Main pushed later; CompanionSecondaryHomeThemeTest pins both orders.
        val floorDisplay = display?.displayId ?: BottomUiReceiptFormat.NO_DISPLAY
        this.floorDisplay = floorDisplay
        // Synchronously, before `setContent`, so no composition can race the decision: arms the cold-start handoff only when no Presentation is attached on this
        // display (CompanionColdStartHandoff). The cover below is composed whatever the answer; this only says whether the Presentation follows it.
        CompanionColdStartHandoff.floorCreated(floorDisplay)
        setContent {
            val payload by CompanionState.payload.collectAsState()
            MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                // Receipt: the startup cover. A static Mannaz cover with no transition wired, so it says transition=none.
                BottomUiSurfaceReceipt(surface = BottomSurface.COLD_START, visible = true, display = floorDisplay)
                // Stays visible for good: this window is beneath the Presentation and replaced by it, so a fade here would paint nothing. The
                // cover's handoff fade is the Presentation's own (MimirCompanionPresentation.coldStartHandoff, decided by CompanionColdStartHandoff), over its first frame.
                CompanionMannazCover()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Auto-recovery : this floor resuming is proof nothing else covers
        // display 4, so a stranded RecoveryNeeded has nothing left to protect. See
        // CompanionReturnCoordinator.noteSecondaryHomeResumed for the guard and the device evidence.
        CompanionReturnCoordinator.noteSecondaryHomeResumed(display?.displayId)
        // O10 : the platform restarting this floor after Mimir's process died is one of the
        // two moments that may bring Main back to Mimir's Home -- once per process, and never once MainActivity exists
        // in it, so resumes that follow a reclaim stay no-ops. See MimirMainHomeRecovery.
        MimirMainHomeRecovery.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
    }

    override fun onPause() {
        CompanionReturnCoordinator.noteSecondaryHomePaused(applicationContext, display?.displayId)
        super.onPause()
    }

    override fun onStop() {
        CompanionReturnCoordinator.noteSecondaryHomeStopped(display?.displayId, isFinishing, isChangingConfigurations)
        super.onStop()
    }

    /**
     * A HOME press while display 4 holds the focus lands here, not on MainActivity (Thor 2026-10-08: Main driven by the pad after a
     * Bottom app was reclaimed, so `START {cat=[SECONDARY_HOME]}` on display 4 and Settings stayed open). When this floor was the
     * started, focused window and a MainActivity is resumed on display 0, it is the same user HOME press MainActivity answers:
     * [routeMainHomeKeyIntent] decides with the same guards and publishes the same HOME command. It starts nothing itself.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeMainHomeKeyIntent(intent, FloorMainHomeKeyPlatform(this))
    }

    /**
     * Keys that land on display 4 are Main's keys, except while the Bottom has a pad owner: the open ᛗ menu ([BottomPadInput]) and, ahead of Main, the Activity
     * achievement page while it is live ([CompanionActivityPadRoute]).
     *
     * Volume/power go to `super`: the framework owns them on whichever display they arrive on, exactly
     * as `MainActivity` lets them through. Everything else is offered to the resumed Main's authority
     * via [MimirMainInputSink] — and if there is no resumed Main, or it declines, the event is consumed
     * here rather than passed to `super`. There is nothing on this floor to receive a controller key,
     * and the point of the whole change is that no event on display 4 is ever left waiting.
     *
     * `RestrictedApi` is suppressed as on `MainActivity` and `CompanionReturnActivity`. This overrides the public
     * `Activity.dispatchKeyEvent`; lint attributes the override and its `super` call to androidx.core's
     * `ComponentActivity`, whose only restriction here is its class-level `@RestrictTo(LIBRARY_GROUP_PREFIX)`.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isMimirSystemKey(event.keyCode)) return super.dispatchKeyEvent(event)
        // R9a: the Bottom's pad owner (the menu) is asked BEFORE Main's sink, so a pad event that falls to the floor still
        // reaches the menu instead of walking Main's shelf. No owner registered: false, and nothing changes.
        if (BottomPadInput.routeKey(event)) return true
        // The Activity achievement page's pad : while that page is live it takes the pad ahead of Main's sink, so a pad press walks the page instead of
        // Main's shelf. Nothing registered (the page is not up, its pane is parked, or the window is parked, hidden or dark): false, and the event goes on to Main exactly as before.
        if (CompanionActivityPadRoute.routeKey(event)) return true
        if (MimirMainInputSink.routeKey(event) == MimirSecondaryInputRoute.ABSORBED_NO_MAIN) {
            logAbsorbedOnce(absorbedKeyCodes, event.keyCode, "key=${event.keyCode}")
        }
        return true
    }

    /**
     * The same contract for generic motion, and not optional: the Thor's physical D-pad arrives as
     * HAT-axis `MotionEvent`s rather than key events (`MainActivity.dispatchKeyEvent`, FUX-11), so a
     * key-only floor would forward the buttons and swallow every direction.
     *
     * Deduplicated per source rather than per keycode — a motion event has no keycode, and one held
     * stick would otherwise log at the event rate.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // R9a: same order for motion, so a hat that falls to the floor still moves the menu's ring.
        if (BottomPadInput.routeMotion(event)) return true
        if (CompanionActivityPadRoute.routeMotion(event)) return true
        if (MimirMainInputSink.routeMotion(event) == MimirSecondaryInputRoute.ABSORBED_NO_MAIN) {
            logAbsorbedOnce(absorbedMotionSources, event.source, "motion source=${event.source}")
        }
        return true
    }

    /**
     * One line per distinct key/source, not one per event: a held D-pad repeats at the input rate and
     * would bury the log it is meant to make readable. Instance-scoped, so a relaunched floor reports
     * again — a new claim on the display is worth seeing. Dispatch is main-thread only, so the plain
     * [MutableSet] needs no lock.
     */
    private fun logAbsorbedOnce(seen: MutableSet<Int>, token: Int, describe: String) {
        if (!seen.add(token)) return
        Log.i(TAG, "absorbed $describe (Main not resumed)")
    }

    private companion object {
        const val TAG = "MimirSecondaryHome"
    }
}
