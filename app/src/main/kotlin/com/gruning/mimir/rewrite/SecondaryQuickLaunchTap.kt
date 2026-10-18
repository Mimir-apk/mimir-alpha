package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchChoice
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination

/**
 * Round 5 (5b): a quick-launch tap on the Bottom shows one toast, on the display the player tapped, with the most specific
 * reason there is.
 *
 * The Bottom's quick-launch rails, its "Opens on" sheets and the Bottom Quick launch window (hosted by the overlay Tabs
 * drawer, the Presentation and [BottomQuickLaunchPanelHost]) send each tap through these two. A tap for an app saved for
 * Main reaches Main's route ([CompanionQuickLaunchPrimaryRoute]). For a Bottom request that route makes no refusal toast of
 * its own and answers with its reason ([QuickLaunchPrimaryRouteResult.Refused]), which becomes the tap's toast. The
 * surface's own [unavailableCopy] covers what the route did not explain: no route was installed, the request threw, a sheet
 * that was already settled, or a Bottom launch that failed. A start that fails after the route accepted is the route's to
 * report, on the Bottom, so an accepted tap makes no toast here.
 *
 * [feedbackContext] is a context on the display the player tapped. It is read only when there is a toast to make.
 */
internal fun routeSecondaryQuickLaunchTap(
    packageName: String,
    label: String?,
    fromGeneral: Boolean,
    onPrimary: (String, Boolean) -> QuickLaunchPrimaryRouteResult,
    onSecondary: (String, String?) -> Boolean,
    feedbackContext: () -> Context,
    unavailableCopy: String,
): Boolean {
    var mainAnswer: QuickLaunchPrimaryRouteResult? = null
    val accepted = routeSecondaryQuickLaunch(
        packageName = packageName,
        label = label,
        fromGeneral = fromGeneral,
        onPrimary = { name, general ->
            onPrimary(name, general).also { mainAnswer = it } == QuickLaunchPrimaryRouteResult.Accepted
        },
        onSecondary = onSecondary,
    )
    // As before, only a tap for an app saved for Main reports here: one saved for the Bottom reports in the Bottom's own
    // launch line, and a first tap opens the sheet.
    if (!accepted && QuickLaunchStore.displayPreference(packageName) == QuickLaunchDisplayRole.PRIMARY) {
        showSecondaryQuickLaunchFailure(packageName, mainAnswer, feedbackContext, unavailableCopy)
    }
    return accepted
}

/** The "Opens on" sheet's choice, with the same one toast: every choice that is not accepted reports, as before. */
internal fun settleSecondaryQuickLaunchChoiceTap(
    context: Context,
    choice: CompanionQuickLaunchChoice,
    destination: CompanionQuickLaunchDestination,
    onPrimary: (String, Boolean) -> QuickLaunchPrimaryRouteResult,
    onSecondary: (String, String?) -> Boolean,
    feedbackContext: () -> Context,
    unavailableCopy: String,
): Boolean {
    var mainAnswer: QuickLaunchPrimaryRouteResult? = null
    val accepted = settleSecondaryQuickLaunchChoice(
        context = context,
        choice = choice,
        destination = destination,
        onPrimary = { name, general ->
            onPrimary(name, general).also { mainAnswer = it } == QuickLaunchPrimaryRouteResult.Accepted
        },
        onSecondary = onSecondary,
    )
    if (!accepted) showSecondaryQuickLaunchFailure(choice.packageName, mainAnswer, feedbackContext, unavailableCopy)
    return accepted
}

/** The tap's one toast: Main's route's reason when it refused, otherwise the surface's own copy. */
private fun showSecondaryQuickLaunchFailure(
    packageName: String,
    mainAnswer: QuickLaunchPrimaryRouteResult?,
    feedbackContext: () -> Context,
    unavailableCopy: String,
) {
    val routeReason = (mainAnswer as? QuickLaunchPrimaryRouteResult.Refused)?.reason
    Log.i(
        "MimirCompanionRuntime",
        "quick launch tap not accepted package=$packageName main=${mainAnswer ?: "not_asked"} " +
            "copy=${if (routeReason != null) "route_reason" else "surface"}",
    )
    showMimirMessage(feedbackContext(), routeReason ?: unavailableCopy)
}
