package com.gruning.mimir.tweaker

import android.os.Bundle

/**
 * The refusal `setDisplayRateSettingV8` now returns for every call, whatever key and value it is handed.
 *
 * Mimir removed display-rate switching on 2026-09-15 and nothing sends this transaction any more, so a daemon that
 * still accepted the write would be a privileged `settings put` with no legitimate caller. The AIDL method stays so
 * every later transaction code keeps its number and an older client still gets an answer, but the answer is "retired":
 * it never writes, never reaches the privileged transport, and never touches the settings provider.
 */
internal object RetiredDisplayRateWrite {
    const val CODE = "DISPLAY_RATE_RETIRED"
    const val MESSAGE =
        "Display-rate switching was removed from Mimir; the Tweaker no longer writes display-rate settings."

    /** Same keys the live path used (`apiVersion`, `success`, `status`, `detail`) plus the `code`/`message` the other retired methods carry. */
    fun refusal(apiVersion: Int): Bundle = Bundle().apply {
        putInt("apiVersion", apiVersion)
        putBoolean("success", false)
        putString("status", CheckedCommandStatus.FAILED.name)
        putString("code", CODE)
        putString("message", MESSAGE)
        putString("detail", MESSAGE)
    }
}
