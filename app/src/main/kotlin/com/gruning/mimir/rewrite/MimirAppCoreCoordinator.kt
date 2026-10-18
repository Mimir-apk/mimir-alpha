package com.gruning.mimir.rewrite

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.LaunchForegroundObserver
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.LibraryViewModel
import kotlinx.coroutines.flow.StateFlow

internal sealed interface HomeLaunchReturnLoadState {
    data object Loading : HomeLaunchReturnLoadState
    data class Ready(val handle: HomeLaunchReturnHandle?) : HomeLaunchReturnLoadState
}

/** Small root snapshot shared by the independently compiled app coordinators. */
internal class MimirAppCoreCoordinator(
    val context: Context,
    val themeId: MimirThemeId,
    // PERF-G: the STABLE flow, never a collected Boolean — collecting the per-press pulse here rebuilt
    // this whole object (and therefore re-executed every coordinator and the full app tree) twice per
    // d-pad press. Consumers collect where they actually need the value.
    val controllerNavigationActive: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val profileName: String,
    val persistedHomeLaunchReturn: HomeLaunchReturnHandle?,
    val persistedHomeLaunchReturnReady: Boolean,
    val focusAuthority: FocusAuthority,
    val brightness: AppBrightnessCoordinator,
    val repository: LibraryRepository,
    val launchController: LaunchController,
    val libraryViewModel: LibraryViewModel,
    val companionDisplayActive: Boolean,
    val companionOverlayController: CompanionOverlayController?,
    val launchForegroundObserver: LaunchForegroundObserver?,
    /**
     * R6-P3-02 review fix F1: bumped by the ONE `ContentObserver` on `Settings.Secure.DEFAULT_INPUT_METHOD`
     * / `ENABLED_INPUT_METHODS` registered below, in [rememberMimirAppCoreCoordinator] -- the shared root
     * both [MimirSettingsReadCoordinator] and [MimirOnboardingCoordinator] key their own
     * `currentMimirKeyboardPickerEntry` re-read on, so the picker dialog (a system dialog, not an Activity
     * -- neither `onResume` fires) still drives a live re-read for both consumers from a single observer.
     */
    val keyboardStateTick: Int,
)

/**
 * Wakes the ambient motion gate once for each explicit theme change ("an explicit theme change").
 *
 * O18 : this was a `LaunchedEffect(themeId)` inside [rememberMimirAppCoreCoordinator], and a
 * LaunchedEffect runs when it enters composition, so Main's first composition pulsed for the theme it merely started
 * with. That pulse was the last wake of every restart: it ended the Bottom's breath window about 12 s after Main's first
 * frame, and its missing root picked the 20 Hz rate. A change is now counted where it is made ([ThemeStore.setTheme])
 * and claimed here once, by whichever composition sees it first.
 */
@Composable
internal fun ThemeChangeMotionWake(interactionMotionGate: InteractionMotionGate) {
    // Keyed on the COUNT, not the theme value: a composition's start is not a change, and a value keeps no record of a
    // change made before this composition existed.
    val explicitChanges by ThemeStore.explicitChanges.collectAsState()
    LaunchedEffect(interactionMotionGate, explicitChanges) {
        if (ThemeStore.claimExplicitChangeWake()) interactionMotionGate.pulse(InteractionSource.THEME_CHANGE)
    }
}

@Composable
internal fun rememberMimirAppCoreCoordinator(
    controllerNavigationActive: StateFlow<Boolean>,
    interactionMotionGate: InteractionMotionGate,
    companionOverlayController: CompanionOverlayController?,
    companionDisplayActive: StateFlow<Boolean>,
    launchForegroundObserver: LaunchForegroundObserver?,
    brightnessController: BrightnessController?,
): MimirAppCoreCoordinator {
    val context = LocalContext.current
    NavigationHintSettingsStore.initialize(context)
    val themeId by ThemeStore.themeId.collectAsState()
    val profileName by ProfileNameStore.displayName.collectAsState()
    val companionDisplayActiveNow by companionDisplayActive.collectAsState()

    ThemeChangeMotionWake(interactionMotionGate)

    var homeLaunchReturnLoad by remember(context) {
        mutableStateOf<HomeLaunchReturnLoadState>(HomeLaunchReturnLoadState.Loading)
    }
    LaunchedEffect(context) {
        homeLaunchReturnLoad = HomeLaunchReturnLoadState.Ready(
            HomeLaunchReturnStore.peek(context),
        )
    }
    val persistedHomeLaunchReturn = (homeLaunchReturnLoad as? HomeLaunchReturnLoadState.Ready)?.handle
    val focusAuthority = remember { DefaultFocusAuthority() }
    LaunchedEffect(persistedHomeLaunchReturn?.token) {
        persistedHomeLaunchReturn?.let { handle ->
            if (handle.tomeSession == null) {
                focusAuthority.rememberFocusForScope(HomeShelfScope, handle.target.focusKey)
            }
        }
    }
    val brightness = rememberAppBrightnessCoordinator(context, brightnessController, focusAuthority)
    val repository = remember { LibraryInjection.repository(context) }
    val launchController = remember { LaunchInjection.controller(context) }
    val libraryViewModel: LibraryViewModel =
        viewModel(
            factory = LibraryViewModel.Factory(
                repository = repository,
                launchController = launchController,
                gameSessionObserver = MimirSessionAuthority,
                preDispatchHook = { game, ready, attemptId ->
                    // The launch cover (Dolphin, ARMSX2) goes up FIRST, exactly as on Home: before the intent, and before
                    // the Tweaker's status overlay, which must draw above it. Library launches are always Main.
                    armGameLaunchCoverBeforeDispatch(context, game, ready.target)
                    presentHomeTweakerLaunchStatus(
                        context = context,
                        attemptId = attemptId,
                        workloadPackage = ready.target.emulator.packageName,
                        gameTitle = game.title,
                    )
                },
                postDispatchHook = { game, ready, attemptId ->
                    activateHomeTweakerAfterDispatch(
                        context = context,
                        attemptId = attemptId,
                        workloadPackage = ready.target.emulator.packageName,
                        gameTitle = game.title,
                    )
                },
                onPreDispatchDegraded = { _, attemptId, outcome ->
                    presentHomeTweakerLaunchDegraded(context, attemptId, outcome)
                },
                onDispatchFailed = { game, attemptId ->
                    // A cover armed for an intent that never left must not sit over Mimir.
                    GameLaunchCover.dispatchFailed(game.gameId.value)
                    TweakerBridge.abandonPendingLaunch(attemptId)
                    TweakerBridge.settleAfterReturn()
                },
                // The shared Library launch intro uses application context: the
                // ViewModel outlives this Activity's composition.
                launchOpening = GameOpeningIntroLibraryLaunchOpening(
                    context = context.applicationContext,
                    secondaryDisplayId = {
                        CompanionProcessRuntime.services(context.applicationContext)
                            .taskRegistry.state.value.secondaryDisplayId
                    },
                ),
            ),
        )
    LaunchedEffect(libraryViewModel) {
        installLaunchHonesty(context.applicationContext, libraryViewModel)
    }

    // R6-P3-02 review fix F1: the IME picker (`showInputMethodPicker()`) and Android's keyboard-settings
    // screen are BOTH reached by leaving Mimir's Activity, but neither is an Activity itself -- the picker
    // is a system dialog, the settings screen is a separate task the player can leave open. Neither
    // reliably drives Mimir's own `onResume`, so a `ContentObserver` on the two `Settings.Secure` keys the
    // keyboard-picker entry actually reads is the correct signal, fired exactly when the platform fact
    // changes. ONE observer, registered here (the shared root both `MimirSettingsReadCoordinator` and
    // `MimirOnboardingCoordinator` are built from) rather than one per consumer -- see [keyboardStateTick]'s
    // own doc on the class above.
    var keyboardStateTick by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                keyboardStateTick += 1
                // W2f: the one place that notices the player picking another keyboard in Android.
                MimirKeyboardDefaultRuntime.onDefaultChanged(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false,
            observer,
        )
        resolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return MimirAppCoreCoordinator(
        context = context,
        themeId = themeId,
        controllerNavigationActive = controllerNavigationActive,
        profileName = profileName,
        persistedHomeLaunchReturn = persistedHomeLaunchReturn,
        persistedHomeLaunchReturnReady = homeLaunchReturnLoad is HomeLaunchReturnLoadState.Ready,
        focusAuthority = focusAuthority,
        brightness = brightness,
        repository = repository,
        launchController = launchController,
        libraryViewModel = libraryViewModel,
        companionDisplayActive = companionDisplayActiveNow,
        companionOverlayController = companionOverlayController,
        launchForegroundObserver = launchForegroundObserver,
        keyboardStateTick = keyboardStateTick,
    )
}
