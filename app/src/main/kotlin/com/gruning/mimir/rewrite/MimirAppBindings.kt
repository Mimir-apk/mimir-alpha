package com.gruning.mimir.rewrite

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.PlatformTileArtStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.LaunchForegroundObserver
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirQuickRailAction
import com.gruning.mimir.rewrite.feature.browse.dispatchToBrowse
import com.gruning.mimir.rewrite.feature.library.GameLaunchUiState
import com.gruning.mimir.rewrite.feature.library.GameDetailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfQuickRailScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.homeShelfEntryForKey
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioActionRequest
import com.gruning.mimir.rewrite.feature.library.LibraryDetailActionState
import com.gruning.mimir.rewrite.feature.library.LibraryDetailPlayKey
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.dispatchToHomeShelf
import com.gruning.mimir.rewrite.feature.library.dispatchToLibrary
import com.gruning.mimir.rewrite.feature.library.providerEnrichmentState
import com.gruning.mimir.rewrite.feature.settings.dispatchToSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job

/** Everything input and rendering need, represented as a handful of object references. */
internal class HomeQuickLaunchBinding(
    val slots: List<HomeQuickLaunchSlotUi>,
    /** Open generation — the overlay host keys the panel composition on it (fade-window remount). */
    val openEpoch: Long,
    val pickerMode: HomeQuickLaunchPickerMode?,
    val pickerItems: List<HomeQuickLaunchPickerItem>,
    val onLaunch: (String) -> Unit,
    val onPickerTouchLaunch: (String) -> Boolean,
    val onOpenGeneral: () -> Unit,
    val onEdit: (Int) -> Unit,
    val onAssign: (Int, String) -> Unit,
    val onClear: (Int) -> Unit,
    val onPickerDismiss: () -> Unit,
    val onDismiss: () -> Unit,
)

internal class MimirAppBindings(
    val core: MimirAppCoreCoordinator,
    val library: MimirLibraryCoordinator,
    val nativeShelf: MimirNativeShelfCoordinator,
    val navigation: MimirNavigationCoordinator,
    val settingsRead: MimirSettingsReadCoordinator,
    val artwork: MimirArtworkCoordinator,
    val achievements: MimirAchievementsCoordinator,
    val onboarding: MimirOnboardingCoordinator,
    val migration: MimirMigrationCoordinator,
    val settings: MimirSettingsCoordinator,
    val homeShelf: HomeShelfCoordinator,
    val homeLaunchCard: HomeLaunchCardCoordinator,
    val overlays: MimirOverlayCoordinator,
    val detailActionState: LibraryDetailActionState?,
    val homeQuickLaunch: HomeQuickLaunchBinding,
    val onOpenLibraryHeroPicker: (GameRow) -> Unit,
    /**
     * Bumped once per media apply. Both launch-card face producers key their `remember` on it, because a
     * LAUNCH_CARD pick can leave the repository row byte-identical and `games` conflates equal emissions.
     * See `MimirAppCoordinator.refreshCustomEnrichOwner`.
     */
    val mediaApplyEpoch: Long,
) {
    val returnCoverVisible: Boolean
        get() = primaryReturnRestoring(
            native = nativeShelf.launchReturnRestoring,
            library = navigation.libraryLaunchReturnRestoring,
            home = homeShelf.launchReturnRestoring,
        )
}
