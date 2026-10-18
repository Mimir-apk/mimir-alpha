package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability

/**
 * Fan mode's sentence, said once per state the row can show -- the three AUTO states differ in WHO is driving the fan, and the sentence
 * names who, so it cannot be one string.
 *
 * **Why this is not one string (device frames, 2026-10-13).** The row read "THE DEVICE'S OWN FAN CONTROLLER KEEPS THE SPEED IN STEP WITH
 * TEMPERATURE" beside AUTO while the Balanced profile held `fan_mode=6` -- Mimir's own duty loop, not the vendor's. That sentence was written on
 * 2026-08-23 for release spec R2/R3 ("the vendor's SMART loop keeps regulating and Mimir never drives duty"), and the 2026-09-07 control change replaced that as the ordinary case: a profile apply enters Mimir-owned duty (vendor `fan_mode` 5 or 6) in the same
 * transaction wherever manual fan control qualifies, and the daemon's AUTO is the profile's own authored curve at bias 0 (Quiet 1.00x, Balanced 1.15x, Performance 1.30x, never below the
 * vendor's own SMART duty at the same temperature), with MANUAL the same curve shifted Cooler or Quieter. Both are Mimir's loop. The vendor's
 * controller runs only when the daemon says it does not own duty.
 *
 * What the row can know is [TweakerManualFanCapability.hardwareDutyOwned], the daemon's attestation of LIVE HARDWARE:
 * - `true` -- Mimir's duty loop is driving: the sentence names Mimir's curve for the profile.
 * - `false` -- the hardware sits on the vendor's SMART mode (the parser has already turned the stored selection into AUTO): the vendor's controller
 *   IS in charge, and the sentence that was always written for that state stays as it was.
 * - `null` -- an older Tweaker that cannot attest. Neither claim can be made, so the sentence says only what is true of both: the speed follows temperature.
 * MANUAL is an instruction ("choose a cooler or quieter response"), true whoever drives, and is unchanged. Stock has no fan section at all (the
 * UNAVAILABLE row below), so it never reaches here.
 */
internal fun tweakerFanModeSupportingText(fan: TweakerManualFanCapability): String = when {
    fan.currentMode == TweakerFanMode.MANUAL -> "Choose a cooler or quieter response, then apply."
    fan.hardwareDutyOwned == true -> "Mimir's fan curve for this profile keeps the speed in step with temperature."
    fan.hardwareDutyOwned == false -> "The device's own fan controller keeps the speed in step with temperature."
    else -> "The fan speed follows temperature automatically."
}

/** The same sentence as TalkBack speaks it, behind the mode's name ("Auto." / "Manual."). */
internal fun tweakerFanModeStateDescription(fan: TweakerManualFanCapability): String =
    (if (fan.currentMode == TweakerFanMode.AUTO) "Auto. " else "Manual. ") + tweakerFanModeSupportingText(fan)
