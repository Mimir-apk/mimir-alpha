package com.gruning.mimir.rewrite

import android.content.Context

/**
 * RELEASE: there is no lab tuner axis, and there is no code here that could make one.
 *
 * **An absent implementation, not a disabled one.** The synthetic axis, its file writer, its shell
 * command and the contract constants it is built from are compiled into debuggable builds only
 * (`app/src/debug/kotlin`). This stub exists so `MainActivity` — which lives in the shared main source
 * set and must call the installer unconditionally — still compiles for the release variant. The release
 * APK therefore contains three empty methods and nothing else: no directory name to create, no path to
 * rewrite, no `ProcessBuilder`.
 *
 * **This is defence in depth, and its limit is stated rather than glossed.** The two APKs share one
 * debug signing key, so the proof runs on debug builds regardless — the load-bearing wall is the
 * `DebugIntentHookGate(BuildConfig.DEBUG)` that guards the arming extra, and this is the second wall
 * behind it. Two walls that both have to fail is the standard this tree already holds `mimir.holdJob`
 * to; this goes one step further by removing the code rather than the branch.
 */
internal object LabTunerAxisInstaller {
    fun installIfArmed(context: Context) = Unit

    fun arm(context: Context) = Unit

    fun disarm(context: Context) = Unit
}
