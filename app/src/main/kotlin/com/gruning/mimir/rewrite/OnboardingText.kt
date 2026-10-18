package com.gruning.mimir.rewrite

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent

/**
 * The [Resources] first-run text is read through (localization plan step 1). It is the Activity's own, which follows the player's
 * language choice (`MimirLocale.attach`), never the Application's, which below API 33 stays in the device language. Reading
 * [LocalConfiguration] first is what `stringResource` does: a configuration change recomposes whatever read the resources.
 *
 * For the copy helpers that are not composable (`onboardingFinishLegend(res, ...)`): they take the [Resources] as their first
 * parameter rather than reading a context themselves, so a test hands them any locale it likes.
 */
@Composable
@ReadOnlyComposable
internal fun onboardingResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

/**
 * First-run text the store keeps as written. The settlement reason and the scan failure are durable values: a checkpoint written by
 * an earlier build (or before the player changed language) holds the English sentence, and changing what is stored would strand
 * it. So the stored sentence stays the key, and the screen resolves a key it knows to the string resource of the current language;
 * a stored value it does not know (a failure's own message) is shown as it is, exactly as before.
 *
 * `OnboardingStoredTextTest` asserts every key here equals the English resource it resolves to, so editing one without the
 * other fails a test.
 */
internal object OnboardingStoredText {
    /** The English sentence the store holds, and the resource that says it in the player's language. */
    internal class Entry(val stored: String, @StringRes val textRes: Int)

    /** The reasons [settlementReasonForEnrichment] writes, one per distinct sentence. */
    val settlementReasons: List<Entry> = listOf(
        Entry(
            settlementReasonForEnrichment(converged = false, connectedDetails = false),
            R.string.app_onboarding_reason_unconverged,
        ),
        Entry(
            settlementReasonForEnrichment(converged = true, connectedDetails = true, steamGridDbKey = false),
            R.string.app_onboarding_reason_details_art_ask,
        ),
        Entry(
            settlementReasonForEnrichment(converged = true, connectedDetails = true, steamGridDbKey = true),
            R.string.app_onboarding_reason_details_art_key,
        ),
        Entry(
            settlementReasonForEnrichment(converged = true, connectedDetails = false, steamGridDbKey = false),
            R.string.app_onboarding_reason_art_ask,
        ),
        Entry(
            settlementReasonForEnrichment(converged = true, connectedDetails = false, steamGridDbKey = true),
            R.string.app_onboarding_reason_art_key,
        ),
    )

    /** The scan failures the worker and the coordinator write. */
    val scanFailures: List<Entry> = listOf(
        Entry(OnboardingScanWorker.MissingReadGrant, R.string.app_onboarding_reason_scan_grant_lost),
        Entry(OnboardingScanWorker.ScanFailed, R.string.app_onboarding_reason_scan_failed),
        Entry(OnboardingScanAccessGoneReason, R.string.app_onboarding_reason_scan_access_gone),
    )

    /** [stored] in the language of [res]: the matching resource, or [stored] itself when no entry matches. */
    fun settlementReasonText(res: Resources, stored: String): String = resolve(res, settlementReasons, stored)

    fun scanFailureText(res: Resources, stored: String): String = resolve(res, scanFailures, stored)

    private fun resolve(res: Resources, entries: List<Entry>, stored: String): String =
        entries.firstOrNull { it.stored == stored }?.let { res.getString(it.textRes) } ?: stored
}

private val OnboardingNumberPlaceholder = Regex("%(\\d+)\\\$d")

/**
 * [template] (a string resource read without formatting, e.g. "%1$d of %2$d checked") as a mixed intent: the words between the
 * placeholders are chrome, the numbers are authored values. Splitting the one translated sentence around its placeholders keeps
 * the roles right in any word order; nothing translated is joined here.
 */
internal fun onboardingMixedWithNumbers(template: String, numbers: List<Int>): MimirTextIntent.Mixed {
    val fragments = mutableListOf<MimirTextFragment>()
    var next = 0
    for (match in OnboardingNumberPlaceholder.findAll(template)) {
        if (match.range.first > next) fragments += MimirTextFragment.Chrome(template.substring(next, match.range.first))
        fragments += MimirTextFragment.Authored(numbers[match.groupValues[1].toInt() - 1].toString())
        next = match.range.last + 1
    }
    if (next < template.length) fragments += MimirTextFragment.Chrome(template.substring(next))
    return MimirTextIntent.Mixed(fragments)
}
