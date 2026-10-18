package com.gruning.mimir.rewrite

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager
import com.gruning.mimir.rewrite.feature.settings.MimirKeyboardPickerEntry
import com.gruning.mimir.rewrite.feature.settings.mimirKeyboardPickerEntryState

private const val KeyboardPickerLogTag = "MimirKeyboardPicker"

/**
 * How long to wait before deciding the input-method picker never appeared. Long enough for the system
 * to raise a real picker and take focus, short enough that a player who pressed the row is still
 * looking at it when the fallback opens instead.
 */
private const val PickerAppearanceGraceMs = 450L

/**
 * Mimir Keyboard default selection. Native platform reads/actions for the Settings/onboarding
 * entry -- the impure counterpart of [mimirKeyboardPickerEntryState] (`feature-settings`).
 *
 * **Replaces the earlier picker-shortcut-only behavior.** Mimir Keyboard now becomes the
 * system default itself, ON by default (a stored OFF keeps it off), through the certified vendor PServer bridge only
 * (`MimirKeyboardDefault.kt`: fixed `ime enable` / `ime set` / `ime reset`, every one proven by a readback,
 * gated on a live READY posture), with a Settings > Keyboard switch, and it respects a player who picks another
 * keyboard in Android: the switch turns off and Mimir never fights. That is the ONLY writer; nothing in THIS file
 * writes the setting.
 *
 * What this file still does is the path for a device without the bridge (no live READY posture), where the row
 * keeps its three states and every function here either READS platform state or opens one of Android's OWN
 * keyboard screens -- the player picks Mimir Keyboard there.
 */

/**
 * Mimir's own IME component id, in exactly the form `InputMethodManager` reports it back in
 * `InputMethodInfo.id` (a flattened [ComponentName]). Read from [context] rather than hardcoded, because
 * the `.lab`/`.devlab` build flavors carry an `applicationIdSuffix` (`app/build.gradle.kts`) that changes
 * the real package id at runtime.
 */
internal fun mimirKeyboardImeId(context: Context): String =
    ComponentName(context, MimirInputMethodService::class.java).flattenToShortString()

/**
 * The live [MimirKeyboardPickerEntry] for this device right now: reads
 * `InputMethodManager.getEnabledInputMethodList()` and `Settings.Secure.DEFAULT_INPUT_METHOD` (a read,
 * never a write -- see this file's own doc) and hands both to the pure decision function.
 */
internal fun currentMimirKeyboardPickerEntry(context: Context): MimirKeyboardPickerEntry {
    val imm = context.getSystemService(InputMethodManager::class.java)
    val enabledIds = imm?.enabledInputMethodList.orEmpty().mapTo(mutableSetOf()) { it.id }
    val defaultId = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return mimirKeyboardPickerEntryState(
        mimirImeId = mimirKeyboardImeId(context),
        enabledInputMethodIds = enabledIds,
        defaultInputMethodId = defaultId,
    )
}

/**
 * [MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS]'s action: Android's OWN keyboard-settings screen,
 * where the player turns Mimir Keyboard on. Mirrors the existing native-settings pattern
 * already used (`AndroidExternalForegroundMonitor.requestUsageAccess`, `core-companion-native`) --
 * `FLAG_ACTIVITY_NEW_TASK` because this can be invoked from a non-Activity `Context`, with the same
 * `ACTION_SETTINGS` fallback for a device that has stripped the specific screen.
 */
internal fun openMimirKeyboardSettings(context: Context) {
    val appContext = context.applicationContext
    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { appContext.startActivity(intent) }
        .onFailure {
            val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { appContext.startActivity(fallback) }
        }
}

/**
 * [MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER]'s action: Android's OWN input-method picker
 * (the same dialog the player would reach by long-pressing the keyboard's own switch key), where the
 * player picks Mimir Keyboard as the default. Never `Settings.Secure.DEFAULT_INPUT_METHOD` -- see this
 * file's own doc.
 *
 * R6-P3-02 review fix F2: `IInputMethodManagerGlobalLock.showInputMethodPickerFromClient` silently drops
 * the request when the calling window is not focused -- a real gap here, not a hypothetical, because
 * this can be invoked mid-transition (the row's own tap always has focus, but a controller CONFIRM
 * replayed while the window is settling would not). Checked directly on the decor view rather than
 * assumed from the call site, with the one honest fallback: Android's own keyboard-settings screen still
 * lets the player finish the job by hand.
 */
internal fun showMimirKeyboardPicker(context: Context) {
    val decorView = (context as? Activity)?.window?.decorView
    if (decorView?.hasWindowFocus() != true) {
        Log.i(
            KeyboardPickerLogTag,
            "showInputMethodPicker skipped: window not focused; falling back to keyboard settings " +
                "(${MimirInputMethodService::class.java.simpleName})",
        )
        openMimirKeyboardSettings(context)
        return
    }
    context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    // R6-P3-02 follow-up, 2026-09-16: passing the check above is NOT sufficient. Reproduced on the
    // Thor with `Settings > Controls > Mimir Keyboard`: onRowActivate fires with the right id, this
    // function runs, the guard passes, showInputMethodPicker() is called -- and the row appeared to do
    // nothing at all. decorView.hasWindowFocus() is not the same condition the platform checks in
    // showInputMethodPickerFromClient, so the guard above can only ever catch a subset.
    //
    // QA-07  -- THE DIAGNOSIS ABOVE WAS WRONG, AND THE FALLBACK IT JUSTIFIED
    // WAS ACTIVELY DESTRUCTIVE. The device capture shows the picker appearing every single time, on
    // the SECOND display, and this function tearing it down 344 ms later:
    //
    //   12:22:32.395  input_focus: Focus request 681af02 Select input method
    //   12:22:32.407  input_focus: Focus leaving ... launcher3.secondarydisplay.SecondaryDisplayLauncher
    //   12:22:32.449  input_focus: Focus entering 681af02 Select input method
    //   12:22:32.793  MimirKeyboardPicker: "did not appear ... falling back to keyboard settings"
    //   12:22:32.807  wm_create_activity: com.android.settings/.Settings$AvailableVirtualKeyboardActivity
    //   12:22:33.016  InputManager-JNI: Input channel '681af02 Select input method' was disposed
    //
    // Three presses, three identical cycles. `mLastFocusDisplay 0` never moves, because the picker takes
    // focus from the SECONDARY display's launcher -- this window keeps focus throughout, so the heuristic
    // reports "never appeared" about a window that is on screen and focused somewhere else. The system
    // announces the policy in the same log: `InputMethodManagerService: isImeShowOnSecondDisplay true /
    // getSecondDisplayId 4`. On this chassis the IME, and its picker, belong to the bottom screen.
    //
    // So the original "it does nothing" report was most likely this too: the chooser was opening on the
    // bottom screen while the player watched the top one.
    //
    // A window on another display cannot be detected from this one through any public API. Where that is
    // possible, DO NOT GUESS -- requesting the picker and then closing it is far worse than requesting it
    // and saying nothing, because it removes the very control the player was sent to use. The timed check
    // is kept only where it is sound: a single-display device, where the picker must take focus here or
    // nowhere.
    if (mimirKeyboardPickerMayOpenOnAnotherDisplay(context)) {
        Log.i(
            KeyboardPickerLogTag,
            "showInputMethodPicker requested; another display is present so this window cannot observe " +
                "the picker -- no automatic fallback (QA-07: the fallback used to close a working picker)",
        )
        return
    }
    decorView.postDelayed(
        {
            if (decorView.hasWindowFocus()) {
                Log.i(
                    KeyboardPickerLogTag,
                    "showInputMethodPicker did not appear (single display, window still focused after " +
                        "${PickerAppearanceGraceMs}ms); falling back to keyboard settings",
                )
                openMimirKeyboardSettings(context)
            }
        },
        PickerAppearanceGraceMs,
    )
}

/**
 * Whether Android could put the input-method picker on a display this window cannot observe.
 *
 * Asked of [DisplayManager] rather than of Mimir's own companion registry on purpose: the question is
 * about the platform's window placement, and it must answer correctly even before Mimir has mounted its
 * own Presentation -- onboarding's KEYBOARD screen is reached long before the companion surface exists.
 *
 * Any additional display that is on counts. Which one the picker actually chooses is a vendor policy
 * (`isImeShowOnSecondDisplay` on the Thor) with no public API behind it, so the honest test is "is there
 * anywhere else it could be", not "where will it be".
 *
 * Internal rather than private because the KEYBOARD screen's own copy must be keyed on the SAME predicate
 * the action is keyed on. If the two ever diverge, the screen tells the player to watch a screen the
 * picker will not use, or stays silent about the one it will.
 */
internal fun mimirKeyboardPickerMayOpenOnAnotherDisplay(context: Context): Boolean {
    val here = (context as? Activity)?.display?.displayId ?: Display.DEFAULT_DISPLAY
    val displays = context.getSystemService(DisplayManager::class.java)?.displays ?: return false
    return displays.any { it.displayId != here && it.state == Display.STATE_ON }
}

/**
 * The one branch every "Mimir Keyboard" entry point shares -- the Settings row (`MimirSettingsCoordinator.kt`,
 * row id `controls/keyboard`) and the onboarding ACCESS/KEYBOARD screen (`OnboardingFlow.kt`) both press one
 * button whose job depends on the live [MimirKeyboardPickerEntry]. Factored here once so F2's window-focus
 * guard (inside [showMimirKeyboardPicker]) and F1's re-read-on-observer-tick fix apply identically to both,
 * rather than two independently-maintained copies of the same three-way branch.
 */
internal fun performMimirKeyboardPickerAction(context: Context, entry: MimirKeyboardPickerEntry) {
    when (entry) {
        MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS -> openMimirKeyboardSettings(context)
        MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER -> showMimirKeyboardPicker(context)
        MimirKeyboardPickerEntry.ALREADY_DEFAULT -> Unit
    }
}
