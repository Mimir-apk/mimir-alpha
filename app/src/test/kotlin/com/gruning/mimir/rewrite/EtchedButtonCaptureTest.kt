package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.feature.settings.GradientCta
import com.gruning.mimir.rewrite.feature.settings.TonalCta
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.onNodeWithText
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirViewportPosture
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.feature.browse.BrowseScreen
import com.gruning.mimir.rewrite.feature.browse.BrowseSearchKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCard
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardUiState
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.MimirLaunchCardExtraAction
import com.gruning.mimir.rewrite.feature.library.enterHomeLaunchCard
import org.junit.Assert.assertEquals

/**
 * Pictures of the buttons update converted to the etched chip, on Main at 853 x 480 dp (density 2), at rest and focused (one lit element at a time): the Onboarding ACCESS
 * screen's ghost "Not now" beside its primary "Allow" (the same dark plate, the primary's rim and word in the accent), the Settings backup pair (primary and tonal), and the
 * Activity filter row (the search pill, the Show chip and the console chip, each with the ring on its PLATE). Drawn the way this repo rasterises (the view hierarchy into a software
 * bitmap; see [CustomMediaPageCaptureTest]), as API 30, the level at which the platform cannot blur, because a software canvas cannot draw the glass sheet's RenderNode layers.
 * The PNGs land in `app/build/btn265/` and are copied to `build/test-captures` when it exists. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtchedButtonCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 8L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun writePng(fileName: String) {
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/btn265").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("BTN265 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    @Test
    fun `onboarding - Allow is the primary chip and Not now the unselected one, each lit only while focused`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        val authority = DefaultFocusAuthority()
        val accessReview = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
        val profile = OnboardingProfileState()
        val library = OnboardingLibraryState()
        val access = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = false,
            homeRoleAvailable = true,
            homeRoleHeld = false,
            filesGranted = false,
            overlayGranted = false,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp)) {
                        OnboardingFlow(
                            authority = authority,
                            step = OnboardingStep.ACCESS,
                            profile = profile,
                            accessReview = accessReview,
                            focusIntent = focusIntent,
                            access = access,
                            library = library,
                            libraryGameCount = 0,
                            libraryPlatformCount = 0,
                            artworkAccount = null,
                            steamGridDbKeyPresent = false,
                            settlement = OnboardingSettlementUi(
                                phase = OnboardingSettlementPhase.IDENTIFYING,
                                title = "Finishing your library",
                                detail = "Mimir is finishing up.",
                            ),
                            currentThemeId = MimirThemeId.DEFAULT,
                            onAdvance = {},
                            onCommitName = {},
                            onRequestFilesAccess = {},
                            onRequestUsageAccess = {},
                            onRequestHomeRole = {},
                            onChooseFolder = {},
                            onOpenArtworkSetup = {},
                            onOpenSteamGridDbKey = {},
                            onRetrySettlement = {},
                            onContinueLocally = {},
                            onApplyTheme = {},
                        )
                    }
                }
            }
        }
        // FILES, not yet granted: its primary Allow beside the ghost "Not now" (ORIENTATION has only the primary Continue).
        Snapshot.withMutableSnapshot { accessReview.goToScreen(OnboardingAccessScreenId.FILES) }
        settle()
        // The flow lands focus on the primary: Allow wears the ring and Not now is at rest.
        writePng("btn265-onboarding-pair.png")
        // Then the ghost is focused: Not now wears the ring and Allow is at rest (the primary's own look, rim and word in the accent, no ring).
        compose.runOnIdle { authority.requestFocus(FocusKey("onboarding/access/files/defer")) }
        settle()
        writePng("btn265-onboarding-pair-ghost-focused.png")
    }

    private val settingsScope = FocusScope("btn265/settings")

    private fun mountSettingsPair(authority: DefaultFocusAuthority) {
        val scope = settingsScope
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(
                        Modifier.requiredSize(853.dp, 480.dp).background(palette.surfaceBase),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                            Text(
                                "Export your library setup, folders, emulator choices, and settings as one file.",
                                style = MimirTypeScale.SupportCompact,
                                color = palette.textSecondary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                                GradientCta(
                                    label = "Export backup",
                                    key = FocusKey("btn265/settings/export"),
                                    scope = scope,
                                    authority = authority,
                                    onActivate = {},
                                    modifier = Modifier.weight(1f),
                                )
                                TonalCta(
                                    label = "Import backup",
                                    key = FocusKey("btn265/settings/import"),
                                    scope = scope,
                                    authority = authority,
                                    onActivate = {},
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `settings backup - Export is the primary chip and Import the unselected one at the same height, at rest and each focused`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        val authority = DefaultFocusAuthority()
        mountSettingsPair(authority)
        settle()
        writePng("btn265-settings-backup.png")
        // The pane's scope is the authority's current one, as it is on the Settings screen; without it no key reads as focused.
        compose.runOnIdle {
            authority.pushScope(settingsScope)
            authority.requestFocus(FocusKey("btn265/settings/export"))
        }
        settle()
        writePng("btn265-settings-backup-export-focused.png")
        compose.runOnIdle { authority.requestFocus(FocusKey("btn265/settings/import")) }
        settle()
        writePng("btn265-settings-backup-import-focused.png")
    }

    @Test
    fun `activity filters - the search pill, the Show chip and the console chip, each ringed on its plate, and none lit at rest`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        // Which plate is lit: -1 none (rest), 0 the search pill, 1 the Show chip, 2 the console chip. One at a time.
        val lit = mutableIntStateOf(-1)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1f)) {
                MimirTheme {
                    val palette = LocalMimirPalette.current
                    Box(
                        Modifier.requiredSize(853.dp, 480.dp).background(palette.surfaceBase).padding(start = 20.dp, top = 40.dp, end = 20.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(120.dp))
                            ActivitySearchPill(
                                query = "",
                                active = false,
                                onOpen = {},
                                onClear = {},
                                modifier = Modifier.weight(1f),
                                plateModifier = Modifier.mimirSelectionRing(focused = lit.intValue == 0, identity = "btn265/search", shape = MimirRingShape.Pill, edgeFlush = true),
                                plateFocused = lit.intValue == 0,
                            )
                            Spacer(Modifier.width(MimirSpacing.xs))
                            ActivityFilterChip(
                                text = "Show: All ▾",
                                description = "Show, All",
                                actionLabel = "choose what to show",
                                selected = false,
                                onClick = {},
                                plateModifier = Modifier.mimirSelectionRing(focused = lit.intValue == 1, identity = "btn265/show", shape = MimirRingShape.Pill, edgeFlush = true),
                                plateFocused = lit.intValue == 1,
                            )
                            Spacer(Modifier.width(MimirSpacing.xs))
                            ActivityFilterChip(
                                text = "Console ▾",
                                description = "Console, all consoles",
                                actionLabel = "choose a console",
                                selected = false,
                                onClick = {},
                                plateModifier = Modifier.mimirSelectionRing(focused = lit.intValue == 2, identity = "btn265/console", shape = MimirRingShape.Pill, edgeFlush = true),
                                plateFocused = lit.intValue == 2,
                            )
                        }
                    }
                }
            }
        }
        settle()
        writePng("btn265-activity-filters.png")
        for ((index, name) in listOf(0 to "btn265-activity-filters-search-focused.png", 1 to "btn265-activity-filters-show-focused.png", 2 to "btn265-activity-filters-console-focused.png")) {
            Snapshot.withMutableSnapshot { lit.intValue = index }
            settle()
            writePng(name)
        }
    }

    // -- Update's second sweep (10-09): Browse and the Home launch card (Files is pictured in :feature-files: its surface records layers a software canvas cannot draw) --

    private fun hiddenTetris() = GameRow(
        gameId = GameId("g1"),
        title = "Tetris",
        platformId = PlatformId("gb"),
        memberCount = 1,
        duplicateKind = null,
    )

    @Test
    fun `browse - the search bar is a capsule reached by pad, the hidden row names its platform, one lit element at a time`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        val authority = DefaultFocusAuthority()
        val hidden = listOf(hiddenTetris())
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp)) {
                        BrowseScreen(
                            hiddenGames = hidden,
                            platformLabels = browsePlatformLabels(hidden, emptyList()),
                            searchQuery = "",
                            keyboardOpen = false,
                            authority = authority,
                            onRestore = {},
                            onSearchChange = {},
                            onOpenSearch = {},
                            onClearSearch = {},
                            onCloseKeyboard = {},
                            onExit = {},
                        )
                    }
                }
            }
        }
        settle()
        compose.onNodeWithText("Game Boy").assertExists()
        compose.onNodeWithText("Unknown platform").assertDoesNotExist()
        // Browse is entered on the search bar: it registers first.
        assertEquals(BrowseSearchKey, authority.state.value.focused)
        writePng("btn265-browse-search-focused.png")
        compose.runOnIdle { authority.requestFocus(FocusKey("browse/restore/g1")) }
        settle()
        writePng("btn265-browse-row-focused.png")
        compose.runOnIdle { authority.clearFocus() }
        settle()
        writePng("btn265-browse-rest.png")
    }

    @Test
    fun `launch card - PLAY is the primary chip beside unselected ones, at rest and with PLAY and then Open with focused`() {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        val authority = DefaultFocusAuthority()
        val state = HomeLaunchCardUiState(
            row = HomeShelfRow(
                itemId = HomeShelfItemId.Emulated(GameId("game-1")),
                rank = 0L,
                addedAtMillis = 0L,
                title = "Chrono Trigger",
                supportingLabel = "snes",
            ),
            platformLabel = "Super Nintendo",
            emulatorLabel = "RetroArch",
            lastPlayedLabel = "Never",
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = 1f),
                LocalMimirViewportProfile provides MimirViewportProfile(MimirViewportPosture.STANDARD, 853f, 480f),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(853.dp, 480.dp)) {
                        HomeLaunchCard(
                            state = state,
                            onPlay = {},
                            onAchievements = {},
                            onOpenWith = {},
                            onClose = {},
                            authority = authority,
                            extraAction = MimirLaunchCardExtraAction(label = "Favorite", key = FocusKey("capture/favorite"), onActivate = {}),
                        )
                    }
                }
            }
        }
        settle()
        writePng("btn265-launchcard-rest.png")
        compose.runOnIdle { enterHomeLaunchCard(authority, emulated = true) }
        settle()
        writePng("btn265-launchcard-play-focused.png")
        compose.runOnIdle { authority.requestFocus(HomeLaunchCardOpenWithKey) }
        settle()
        writePng("btn265-launchcard-openwith-focused.png")
    }

    // -- The footer keycaps (not tinted; the etched plate with the key in the chip ink, from the one shared keycap) --

    private fun mountShellFooter(widthDp: Float, heightDp: Float) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = 1f),
                LocalMimirViewportProfile provides mimirViewportProfile(widthDp, heightDp),
            ) {
                MimirTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        AppShellScaffold(activeTab = ShellTab.SETTINGS, onSelectTab = {}) {
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun `footer - the Main shell footer's keycaps are etched plates, at rest`() {
        mountShellFooter(853f, 480f)
        writePng("btn265-footer-main.png")
    }

    @Test
    fun `footer - the same footer at the Bottom screen's size, at rest`() {
        // The Bottom screen draws 537.7 x 468.3 dp (not the 445 dp window).
        mountShellFooter(537.7f, 468.3f)
        writePng("btn265-footer-bottom.png")
    }
}
