package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchChoice
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-owned first-use/edit chooser. A request id makes stale taps and a late host callback harmless;
 * one pending choice also closes the double-tap race before the secondary launch coordinator is involved.
 */
internal object CompanionQuickLaunchChoiceState {
    private val ids = AtomicLong(0L)
    private val mutablePending = MutableStateFlow<CompanionQuickLaunchChoice?>(null)
    val pending: StateFlow<CompanionQuickLaunchChoice?> = mutablePending.asStateFlow()

    @Synchronized
    fun request(
        packageName: String,
        label: String?,
        editing: Boolean = false,
        fromGeneral: Boolean = true,
        coversMainGame: Boolean = false,
    ): Boolean {
        val clean = packageName.trim().takeIf { it.isNotBlank() } ?: return false
        if (mutablePending.value != null) return false
        mutablePending.value = CompanionQuickLaunchChoice(
            requestId = ids.incrementAndGet(),
            packageName = clean,
            label = label?.trim()?.takeIf { it.isNotBlank() },
            editing = editing,
            fromGeneral = fromGeneral,
            coversMainGame = coversMainGame,
        )
        return true
    }

    @Synchronized
    fun claim(choice: CompanionQuickLaunchChoice): Boolean {
        if (mutablePending.value != choice) return false
        mutablePending.value = null
        return true
    }

    @Synchronized
    fun dismiss(choice: CompanionQuickLaunchChoice): Boolean {
        if (mutablePending.value != choice) return false
        mutablePending.value = null
        return true
    }

    @Synchronized
    fun requestCoverWarning(choice: CompanionQuickLaunchChoice): Boolean {
        if (mutablePending.value != choice || choice.coversMainGame || choice.editing) return false
        mutablePending.value = choice.copy(coversMainGame = true)
        return true
    }

    @Synchronized
    fun clear() {
        mutablePending.value = null
    }
}

/** Route only the secondary rail's app launches; lattice app tiles retain their existing action. */
internal fun routeSecondaryQuickLaunch(
    packageName: String,
    label: String?,
    fromGeneral: Boolean = true,
    onPrimary: (String, Boolean) -> Boolean,
    onSecondary: (String, String?) -> Boolean,
): Boolean {
    val role = QuickLaunchStore.displayPreference(packageName)
    return when (role) {
        QuickLaunchDisplayRole.PRIMARY -> onPrimary(packageName.trim(), fromGeneral)
        QuickLaunchDisplayRole.SECONDARY -> {
            if (companionQuickLaunchWouldCoverMainGame()) {
                CompanionQuickLaunchChoiceState.request(
                    packageName = packageName,
                    label = label,
                    fromGeneral = fromGeneral,
                    coversMainGame = true,
                )
            } else {
                onSecondary(packageName.trim(), label)
            }
        }
        null -> CompanionQuickLaunchChoiceState.request(
            packageName = packageName,
            label = label,
            fromGeneral = fromGeneral,
        )
    }
}

/** Claim the exact request, persist the explicit role, then dispatch once; refusal leaves that role saved. */
internal fun settleSecondaryQuickLaunchChoice(
    context: android.content.Context,
    choice: CompanionQuickLaunchChoice,
    destination: CompanionQuickLaunchDestination,
    onPrimary: (String, Boolean) -> Boolean,
    onSecondary: (String, String?) -> Boolean,
): Boolean {
    if (!choice.editing && destination == CompanionQuickLaunchDestination.BOTTOM && !choice.coversMainGame &&
        companionQuickLaunchWouldCoverMainGame()
    ) {
        // Keep the original choice pending while the warning is shown. Nothing is persisted until Continue.
        return CompanionQuickLaunchChoiceState.requestCoverWarning(choice)
    }
    if (choice.coversMainGame && destination != CompanionQuickLaunchDestination.BOTTOM) return false
    if (!CompanionQuickLaunchChoiceState.claim(choice)) return false
    if (choice.editing) {
        when (destination) {
            CompanionQuickLaunchDestination.ASK_NEXT_TIME ->
                QuickLaunchStore.clearDisplayPreference(context, choice.packageName)
            CompanionQuickLaunchDestination.MAIN ->
                QuickLaunchStore.setDisplayPreference(context, choice.packageName, QuickLaunchDisplayRole.PRIMARY)
            CompanionQuickLaunchDestination.BOTTOM ->
                QuickLaunchStore.setDisplayPreference(context, choice.packageName, QuickLaunchDisplayRole.SECONDARY)
        }
        return true
    }
    return when (destination) {
        CompanionQuickLaunchDestination.ASK_NEXT_TIME -> {
            QuickLaunchStore.clearDisplayPreference(context, choice.packageName)
            true
        }
        CompanionQuickLaunchDestination.MAIN -> {
            QuickLaunchStore.setDisplayPreference(context, choice.packageName, QuickLaunchDisplayRole.PRIMARY)
            runCatching { onPrimary(choice.packageName, choice.fromGeneral) }.getOrDefault(false)
        }
        CompanionQuickLaunchDestination.BOTTOM -> {
            // A saved Bottom choice only acknowledged the warning; do not rewrite the user's preference.
            if (QuickLaunchStore.displayPreference(choice.packageName) != QuickLaunchDisplayRole.SECONDARY) {
                QuickLaunchStore.setDisplayPreference(context, choice.packageName, QuickLaunchDisplayRole.SECONDARY)
            }
            runCatching { onSecondary(choice.packageName, choice.label) }.getOrDefault(false)
        }
    }
}

/**
 * The semantic session is the only source for whether a Main DS/3DS game is still using the Bottom.
 *
 * R6-P0-02 S2: a parked game (a live [parkedLease] with Main NOT stopped) already covers nothing new --
 * Continue would leave the exact same game parked behind the exact same Mimir foreground -- so the warning
 * is redundant there. This is the same predicate [CompanionProcessRuntime]'s `launchBottomAppPlan` already
 * uses to skip parking; the defaults read the live session the same way the old no-arg body did, so every
 * existing caller (`routeSecondaryQuickLaunch`, `settleSecondaryQuickLaunchChoice`) is unchanged.
 */
internal fun companionQuickLaunchWouldCoverMainGame(
    game: MimirGameSession? = MimirSessionAuthority.state.value.gameSession,
    parkedLease: MainGameParkingLease? = game?.let(MainGameParkingState::currentFor),
    mainStopped: Boolean = PrimaryActivityObservationState.stopped.value,
): Boolean = companionMainDualScreenGameActive(game) && (parkedLease == null || mainStopped)
