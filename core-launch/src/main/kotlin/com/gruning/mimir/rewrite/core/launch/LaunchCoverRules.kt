package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.LaunchTarget

/**
 * Which activities a launch cover watches for one emulator: the activity Mimir dispatches into and the activity the
 * running game is shown in.
 *
 * The cover ends when the game becomes visible rather than at intent dispatch. Dolphin dispatches
 * into a startup handler and ARMSX2 into a boot splash; both show the
 * game a moment later, in another activity. The cover keeps the launch animation up over the first until the second
 * resumes, so the pair is what it needs. Both halves are fully qualified class names as UsageStats reports them.
 *
 * Built by [launchCoverRuleFor] from the dispatch profile, so [dispatchActivity] is exactly the class the intent names
 * (including an installed alternate the profile fell back to) and cannot drift from what is actually dispatched.
 */
data class LaunchCoverRule(
    val packageName: String,
    val dispatchActivity: String,
    val gameActivity: String,
)

/**
 * The launch cover's rule for [target], or null when its dispatch profile names no game activity (every emulator but
 * Dolphin and ARMSX2, today). Null means no cover and today's behaviour: an emulator that dispatches straight into its
 * game screen has no loading to cover, and one nobody has observed must not be guessed at.
 */
fun launchCoverRuleFor(target: LaunchTarget): LaunchCoverRule? {
    val profile = ShippingLaunchProfiles.profileFor(target) ?: return null
    val gameActivity = profile.gameActivityName?.takeIf { it.isNotBlank() } ?: return null
    if (gameActivity == profile.activityName) return null
    return LaunchCoverRule(
        packageName = profile.packageName,
        dispatchActivity = profile.activityName,
        gameActivity = gameActivity,
    )
}
