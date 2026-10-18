package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

import com.gruning.mimir.rewrite.core.ui.mimirDetailRailWidthDp
import com.gruning.mimir.rewrite.core.ui.mimirDetailPaneWidthDp
import com.gruning.mimir.rewrite.core.ui.mimirModalWidthCapDp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.input.LocalFocusAuthority
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.litFocusIn
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirIconMark
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoff
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.mimirStaticChrome
import com.gruning.mimir.rewrite.core.ui.mimirAtmosphereField
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirFocusRingMs
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType

/** The scope for the Settings rail — the active focus scope while the rail owns focus. */
val SettingsRailScope = FocusScope("settings/rail")
const val SettingsHomeRailTestTag = "settings_home_rail"

/** The test tag of one rail entry: this prefix and the category's name, for a test that has to read where an entry stands. */
const val SettingsRailEntryTestTagPrefix = "settings_rail_entry_"

/**
 * The real, controller-navigable Settings surface — two panes, one focus authority.
 *
 * ### Panes
 * - **Rail** (left, [SettingsRailScope]): the five lean-IA categories, ember fill+border focus
 *   treatment (deliberately NOT the app-wide focus ring), D-pad Up/Down.
 * - **Detail** (right, [DetailScope]): the open category's real rows (built on [SettingsRow]).
 *
 * ### Focus / nav model — all through the single [FocusAuthority], never a second focus engine
 * The rail scope is pushed on entry and focus lands on the active category. Which category is OPEN is
 * host state ([activeCategory]) — deliberately NOT derived from what's focused, so the detail pane keeps
 * showing the last-opened category even while focus lives in the detail scope. [dispatchToSettings]
 * (fed one normalized [ControllerAction] per press by the app shell) is the single input entry point:
 *
 * - In the **rail**: Up/Down move rail focus; RIGHT or CONFIRM open the focused category → push
 *   [DetailScope] and set [activeCategory]; focus lands on the first detail row (focus memory restores
 *   a previously-visited row).
 * - In the **detail**: Up/Down move detail focus; LEFT or CANCEL pop back to the rail; CONFIRM activates
 *   the focused row via the host hook (rescan, backup, theme/backdrop pickers, artwork fetch).
 *
 * Touch has parity: tapping a rail item opens it (and moves focus there); tapping a row focuses+activates
 * it. The whole thing is one truth — `Modifier.mimirFocusable` + the authority, no Compose `focusable()`.
 *
 * @param authority the single focus authority (defaults to the app-root one via the CompositionLocal).
 * @param onRowActivate host hook for a detail row's CONFIRM/tap, keyed by the row id (theme picker etc.).
 * @param library the REAL library read-model (summary + source folders) collected by the app from the
 *   single [com.gruning.mimir.rewrite.core.model.library.LibraryRepository]. Null renders an honest loading
 *   state; preview fixtures are passed explicitly by [SettingsHomePreview].
 * @param initialFocusKey optional one-shot startup target. Null preserves normal rail-memory entry.
 * @param initialFocusScope optional persisted scope for [initialFocusKey]. Null infers it from the key.
 */
@Composable
fun SettingsHome(
    authority: FocusAuthority = LocalFocusAuthority.current,
    onRowActivate: (rowId: String) -> Unit = {},
    library: LibraryReadModel? = null,
    /** N6: threaded verbatim to [SettingsDetailPanel]'s Library pane. See its doc comment. */
    sourceReadFailureMessage: String? = null,
    profileDisplayName: String? = null,
    emulatorAssignments: List<EmulatorAssignment>? = null,
    biosRequirements: List<BiosRequirementRow>? = null,
    biosGrantPresent: Boolean = false,
    specialAccessRows: List<SpecialAccessRow>? = null,
    controllerShortcutRows: List<ControllerShortcutRow> = emptyList(),
    artworkAccount: String? = null,
    backdropValue: String = BackdropValueMedia,
    steamGridDbKeyPresent: Boolean = false,
    retroAchievementsUser: String? = null,
    retroAchievementsKeyState: RaKeyState = RaKeyState.NEVER_CHECKED,
    appVersion: String = "",
    /** The Music pane's read model (spec 2026-09-30). Null renders an honest loading state. */
    music: MusicReadModel? = null,
    /** See [SettingsDetailPanel]'s parameter of the same name. */
    onMusicPaneShown: () -> Unit = {},
    /** See [SettingsDetailPanel]'s parameter of the same name. */
    artworkDownloadBlockedCopy: String? = null,
    initialFocusKey: FocusKey? = null,
    initialFocusScope: FocusScope? = null,
    sourceRemovalConfirmation: SourceRow? = null,
    sourceRemovalRunning: Boolean = false,
    keyboardPickerEntry: MimirKeyboardPickerEntry? = null,
    keyboardBridgeReady: Boolean = false,
    trackpadSettings: TrackpadSettingsState? = null,
) {
    val palette = LocalMimirPalette.current
    // Initial inputs are consumed once. A caller changing them later must remount Settings rather than turn an
    // initial restore into a live focus command.
    val startupFocusKey = remember {
        canonicalSettingsFocusKey(initialFocusKey)
            ?.takeIf { settingsInitialFocusCategory(it, initialFocusScope) != null }
    }
    val startupFocusScope = startupFocusKey?.let(::settingsScopeForFocusKey)
    val startupCategory = startupFocusKey?.let(::settingsCategoryForFocusKey)

    // Which category's detail is showing. Host state, seeded to the first rail item. While the rail owns
    // focus this MIRRORS the focused rail item (live preview); once focus enters the detail scope it
    // freezes to whatever was previewed — so opening a category (controller RIGHT/CONFIRM or a tap) can
    // never desync the detail pane from the rail. (Deriving it from focus is the single-truth discipline.)
    // HP-9b: seed from focus MEMORY so the first frame already shows the category the post-layout
    // focus seed will land on — the old first-item default painted Profile then swapped the whole right
    // pane 1-2 frames later (the visible Start-press re-render jank).
    var activeCategory by remember {
        mutableStateOf(
            startupCategory
                ?: SettingsCategory.rail.firstOrNull {
                    settingsRailFocusKey(it) == authority.rememberedFocusIn(SettingsRailScope)
                }
                ?: SettingsCategory.rail.first(),
        )
    }

    // Make the rail the active scope for the life of this screen; restore the prior scope on exit.
    DisposableEffect(authority) {
        val landingRailKey = settingsRailFocusKey(activeCategory)
        // An explicit startup target wins over stale in-process rail memory. Plant it before registration so
        // the first rail item cannot auto-seed and replace the first-frame category while detail mounts.
        if (startupFocusKey != null) {
            authority.rememberFocusForScope(SettingsRailScope, landingRailKey)
        }
        authority.pushScope(SettingsRailScope)
        // Land focus on the active rail item on entry (memory: focus lands on the active item on entry).
        authority.requestFocus(landingRailKey)
        onDispose {
            while (authority.state.value.scope != FocusScope.Root &&
                authority.state.value.scope in SETTINGS_HOME_SCOPES
            ) {
                authority.popScope()
            }
        }
    }

    // Detail rows register from onGloballyPositioned, after composition. Poll the registration contract on
    // frame boundaries and push DetailScope only when the exact persisted row can receive focus.
    LaunchedEffect(authority, startupFocusKey, startupFocusScope) {
        val target = startupFocusKey
        val category = startupCategory
        if (target == null || category == null || startupFocusScope != DetailScope) return@LaunchedEffect
        val targetRailKey = settingsRailFocusKey(category)
        for (attempt in 0 until SettingsDetailRestoreAttemptLimit) {
            val focus = authority.state.value
            if (focus.scope != SettingsRailScope ||
                (focus.focused != null && focus.focused != targetRailKey)
            ) {
                return@LaunchedEffect
            }
            if (restoreSettingsDetailFocusIfReady(authority, target)) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    val focusState by authority.state.collectAsState()
    val railFocused = focusState.scope == SettingsRailScope
    // The compact layout's pane target follows the live scope ONLY while a Settings scope is the live one (audit 2026-10-14 W1A #5). Utility, a picker and the select overlays push
    // their scopes on this same authority; read raw, `scope == SettingsRailScope` turned false under any of them and swapped the rail for the detail under the panel, and swapped it
    // back (a double fade) on close. The last Settings-owned answer is held under a foreign scope. Before any scope is pushed (the first composition) it is the rail, or the detail
    // when the restore is into the detail.
    val compactOnRail = rememberSettingsCompactOnRail(
        scope = focusState.scope,
        railFocused = railFocused,
        startsOnDetail = startupFocusScope == DetailScope,
    )
    val railScroll = rememberScrollState()

    LaunchedEffect(railFocused, focusState.focused, railScroll.maxValue) {
        if (!railFocused) return@LaunchedEffect
        when (settingsRailCategoryForFocusKey(focusState.focused)) {
            // The rail follows the focus on the focus clock and the house ease, not the default spring (policy 10-14, Q6).
            SettingsCategory.PROFILE -> railScroll.animateScrollTo(0, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
            SettingsCategory.ABOUT -> railScroll.animateScrollTo(railScroll.maxValue, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
            else -> Unit // middle entries use mimirFocusable's authority-owned bring-into-view request
        }
    }

    // STATE channel (core-input's two-channel law): WHICH PANE IS SHOWN. Read from the rail identity
    // FocusAuthority owns now, or remembers while Detail owns focus or any other pushed scope covers this
    // screen. This also closes NAV→RIGHT bursts before SideEffect runs: pushing Detail preserves the exact
    // rail key, so the destination category never falls back to the preceding pane for one frame.
    val activeRailKey = authority.renderedFocusIn(SettingsRailScope, focusState)
    // RING channel: WHICH SEAT IS LIT. The wide layout shows rail and detail side by side, so a rail that
    // holds a lit seat while Detail owns input is a second ring with no input behind it -- the exact
    // artifact observed on device for the Home dock, and the same rule applies to
    // every same-surface sub-scope. The compact branch below needs no equivalent: it never shows the rail
    // and the detail at the same time, and already passes the live key.
    val litRailKey = authority.litFocusIn(SettingsRailScope, focusState)
    val previewed = settingsRailCategoryForFocusKey(activeRailKey)
    val renderedActiveCategory = previewed ?: activeCategory
    // RC-11 (audit 2026-10-14): the RAIL (its ring and its open plate) follows the focus at once; the PANE follows it after the ring has reached the weight the pane starts at,
    // and at once when the rail is not the one moving. See [rememberSettingsPaneCategory].
    val paneCategory = rememberSettingsPaneCategory(
        target = renderedActiveCategory,
        railMoves = focusState.scope == SettingsRailScope,
        reducedMotion = mimirReducedMotion(),
    )
    SideEffect {
        if (previewed != null && activeCategory != previewed) activeCategory = previewed
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // RC-11 (Settings glass spec §8, option A / OWED-3 approved 2026-08-21): the same authored
            // top/horizon/body/footer ramp the shelf paints, not a flat surfaceBase fill. Zero new
            // material — this is MimirThemePalette.fieldTreatment() exposed as a modifier — and it gives
            // the now-translucent section card something with real vertical structure to sit on.
            // No theme crest here either (sweep 10-09): its faint outlined diamond stood at the top right under the status pill and the page badges, a stray mark.
            .mimirAtmosphereField(palette),
    ) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        val rootProfile = LocalMimirViewportProfile.current
        val compactSquare = if (rootProfile.widthDp > 0f) {
            rootProfile.isCompact
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).isCompact
        }
        // Captured once here (not read as `maxWidth.value` inside the Row lambda below): the K2 compiler
        // rejects an implicit BoxWithConstraintsScope receiver once RowScope is also in scope ("cannot be
        // called in this context with an implicit receiver"), so RC-8's rail/margin solver takes a plain
        // Float instead of re-resolving the receiver at each call site.
        val screenWidthDp = viewport.width.value
        val selectCategory: (SettingsCategory) -> Unit = { cat ->
            // A Music sub-page (Behaviour, or the Forget folder confirmation) sits over the detail scope: a touch on the rail leaves both.
            if (authority.state.value.scope in MusicSubPageScopes) authority.popScope()
            if (authority.state.value.scope == DetailScope) authority.popScope()
            authority.requestFocus(settingsRailFocusKey(cat))
            activeCategory = cat
            // A square handheld cannot keep a useful preview pane beside the rail. A touch therefore opens
            // the selected category just like A/Right; Back returns to the same rail identity.
            if (compactSquare) openDetail(authority, cat)
        }
        // Brand lockup + Home/Browse/Tools/Settings tab strip + controller-hint footer are provided by the
        // app-shell scaffold that WRAPS this screen (AppShellScaffold, tab-shell). Here we
        // render only rail + detail; the category title + blurb live INSIDE each pane (showHeader=true).
        if (compactSquare) {
            // RC-34 (audit 2026-10-14): the compact layout shows the rail OR the detail, and the swap between them was `if (railFocused)`, a hard cut on every hop of the RG Rotate
            // class. It is the swap tier now: the pane left fades out, the pane arriving waits the measured lead and fades in, and B (the move back to the rail) is the swap's return,
            // the rail at rest at once. The detail composes during the lead, so its first row has taken the key (openDetail parks the target and the row seeds it as it registers)
            // before the pane shows; the rail's one ringless frame on a return is the authority's floor, under a ring that grows over 420 ms.
            val swapReduced = mimirReducedMotion()
            AnimatedContent(
                targetState = compactOnRail,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopStart,
                transitionSpec = { mimirSwapTransform(swapReduced, fade = false, returning = compactOnRail) },
                label = "settingsCompactPane",
            ) { onRail ->
                val pageAlpha = mimirSwapFadeAlpha(swapReduced, returning = compactOnRail)
                val pageInput = MimirSwapPageGate(pageAlpha)
                Box(Modifier.fillMaxSize().mimirRingSafeFade(pageAlpha).then(pageInput)) {
                    if (onRail) {
                        SettingsRailPane(
                            activeCategory = renderedActiveCategory,
                            railFocused = true,
                            focusedKey = focusState.focused,
                            authority = authority,
                            railScroll = railScroll,
                            onOpen = selectCategory,
                            modifier = Modifier
                                .mimirMeasuredViewport(viewport)
                                .fillMaxSize()
                                // The top 4 dp sit inside the rail's scroll host now (see SettingsRailPane).
                                .padding(start = MimirSpacing.lg, end = MimirSpacing.lg, bottom = MimirSpacing.xs),
                        )
                    } else {
                        SettingsDetailPane(
                            category = paneCategory,
                            authority = authority,
                            onRowActivate = onRowActivate,
                            library = library,
                            sourceReadFailureMessage = sourceReadFailureMessage,
                            profileDisplayName = profileDisplayName,
                            emulatorAssignments = emulatorAssignments,
                            biosRequirements = biosRequirements,
                            biosGrantPresent = biosGrantPresent,
                            specialAccessRows = specialAccessRows,
                            controllerShortcutRows = controllerShortcutRows,
                            artworkAccount = artworkAccount,
                            backdropValue = backdropValue,
                            steamGridDbKeyPresent = steamGridDbKeyPresent,
                            retroAchievementsUser = retroAchievementsUser,
                            retroAchievementsKeyState = retroAchievementsKeyState,
                            appVersion = appVersion,
                            sourceRemovalConfirmation = sourceRemovalConfirmation,
                            sourceRemovalRunning = sourceRemovalRunning,
                            keyboardPickerEntry = keyboardPickerEntry,
                            keyboardBridgeReady = keyboardBridgeReady,
                            trackpadSettings = trackpadSettings,
                            music = music,
                            onMusicPaneShown = onMusicPaneShown,
                            artworkDownloadBlockedCopy = artworkDownloadBlockedCopy,
                            modifier = Modifier
                                .mimirMeasuredViewport(viewport)
                                .fillMaxSize()
                                .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.xs),
                        )
                    }
                }
            }
        } else {
            Row(
                Modifier
                    .mimirMeasuredViewport(viewport)
                    .fillMaxSize(),
            ) {
                SettingsRailPane(
                    activeCategory = renderedActiveCategory,
                    // True unconditionally (as the compact branch above already hardcodes) -- the key
                    // match alone decides which seat lights, and that key is now the LIT one, so this rail
                    // goes dark while Detail owns input and relights on return. The pane it is showing is
                    // unaffected: that comes from renderedActiveCategory, on the conserved channel.
                    railFocused = true,
                    focusedKey = litRailKey,
                    authority = authority,
                    railScroll = railScroll,
                    onOpen = selectCategory,
                    modifier = Modifier
                        // RC-8 A1: named reuse of the already-specced formula
                        //  — 141dp at the
                        // Nova (+27 over the old fixed 168dp), unchanged 168dp at the Odin.
                        .width(settingsRailWidthDp(screenWidthDp).dp)
                        .fillMaxHeight()
                        // The top 4 dp sit inside the rail's scroll host now (see SettingsRailPane).
                        .padding(start = MimirSpacing.xl, end = MimirSpacing.sm),
                )
                SettingsDetailPane(
                    category = paneCategory,
                    authority = authority,
                    onRowActivate = onRowActivate,
                    library = library,
                    sourceReadFailureMessage = sourceReadFailureMessage,
                    profileDisplayName = profileDisplayName,
                    emulatorAssignments = emulatorAssignments,
                    biosRequirements = biosRequirements,
                    biosGrantPresent = biosGrantPresent,
                    specialAccessRows = specialAccessRows,
                    controllerShortcutRows = controllerShortcutRows,
                    artworkAccount = artworkAccount,
                    backdropValue = backdropValue,
                    steamGridDbKeyPresent = steamGridDbKeyPresent,
                    retroAchievementsUser = retroAchievementsUser,
                    retroAchievementsKeyState = retroAchievementsKeyState,
                    appVersion = appVersion,
                    sourceRemovalConfirmation = sourceRemovalConfirmation,
                    sourceRemovalRunning = sourceRemovalRunning,
                    keyboardPickerEntry = keyboardPickerEntry,
                    keyboardBridgeReady = keyboardBridgeReady,
                    trackpadSettings = trackpadSettings,
                    music = music,
                    onMusicPaneShown = onMusicPaneShown,
                    artworkDownloadBlockedCopy = artworkDownloadBlockedCopy,
                    modifier = Modifier
                        .fillMaxSize()
                        // Retired 2026-08-21 (policy, see settingsDetailEdgeMarginDp's own KDoc):
                        // xl (24dp) unconditionally, at every width, equal to the rail's own start padding
                        // below. RC-8 A2's xl/xxl split held the Odin at 32dp on the right while its own
                        // left edge (the rail's start) sat at 24dp -- an asymmetry the split fixed on the
                        // Nova but left standing on the Odin itself.
                        .padding(
                            start = settingsDetailEdgeMarginDp(screenWidthDp),
                            end = settingsDetailEdgeMarginDp(screenWidthDp),
                            top = MimirSpacing.xs,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SettingsRailPane(
    activeCategory: SettingsCategory,
    railFocused: Boolean,
    focusedKey: FocusKey?,
    authority: FocusAuthority,
    railScroll: androidx.compose.foundation.ScrollState,
    onOpen: (SettingsCategory) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            // Seven entries overflow 640x480 dp, so the rail scrolls and its clip met Profile's and About's rings
            // (walk-32m). Ring room sits inside the scroll host; the viewport bleeds up so no entry moves. The bleed is
            // for the ring at the top of the rail only: once scrolled, the viewport is cut to the pane (the header's
            // lower edge down), so no entry draws up under the ᛗ badge (alpha-cert sweep 10-09; the Utility rail's D44).
            .settingsRailClipBleedWhenScrolled(railScroll)
            .settingsRailBleedUp(SettingsRailRingBleed)
            .verticalScroll(railScroll)
            .padding(top = MimirFocusTileRingReach, bottom = MimirFocusTileRingReach)
            .testTag(SettingsHomeRailTestTag),
        // sm -> xs : Main's pane holds seven 48 dp entries with room to spare only at a 4 dp gap, so all seven stand whole at rest. The entry stays 48 dp
        // (SettingsRailEntryHeightDp) and its type is unchanged; what gives is the air between entries, and the focused ring's outer stroke now meets its neighbours' edge.
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
    ) {
        SettingsCategory.rail.forEach { category ->
            RailItem(
                category = category,
                focused = railFocused && focusedKey == settingsRailFocusKey(category),
                active = activeCategory == category,
                authority = authority,
                onOpen = { onOpen(category) },
            )
        }
    }
}

/**
 * Keeps the rail's scrolled entries from drawing under the shell's header (alpha-cert sweep 2026-10-09: with About focused, the list scrolled and PROFILE rose under the
 * ᛗ badge). [settingsRailBleedUp] reaches [SettingsRailRingBleed] above the pane so the first entry's ring is not cut at rest, and that band lies in the header's own bottom
 * padding: an entry scrolled through it drew over the badge. The ring needs the band only at the top of the rail -- Profile is the one entry whose reveal is a scroll to
 * zero, and every other entry settles at least a ring's reach inside the pane -- so once the rail has scrolled, everything above the pane's top edge is cut; at zero it is
 * not, and Profile's ring still reaches up into the header's air. The same law as the Utility rail's `utilityRailClipBleedWhenScrolled`.
 *
 * Place it BEFORE (outside) [settingsRailBleedUp] in the chain: it cuts at the bounds that modifier reports, and the overflow above them is what goes. The cut is read in the
 * draw phase, so a scroll that stays on one side of zero draws nothing extra; the other edges stay open (a ring's halo reaches past the pane's sides), the scroll
 * container's own clip still ending the viewport at the footer.
 */
internal fun Modifier.settingsRailClipBleedWhenScrolled(scroll: androidx.compose.foundation.ScrollState): Modifier = drawWithContent {
    if (scroll.value > 0) {
        val open = MimirFocusTileRingReach.toPx()
        clipRect(left = -open, top = 0f, right = size.width + open, bottom = size.height + open) {
            this@drawWithContent.drawContent()
        }
    } else {
        drawContent()
    }
}

/**
 * How far the rail's viewport reaches above the pane, and so how far the first entry sits below it (the ring's reach less this). The shell leaves 8 dp of air between the
 * badge's lower edge and the pane ([MimirSpacing.sm]); the first entry's ring, 14 dp ([MimirFocusTileRingReach]) beyond it, must end at that edge and not inside the badge's band
 * (it had ended 2.3 dp inside, alpha-cert sweep 10-09). So the entry sits 6.5 dp under the pane and the viewport reaches 7.5 dp above it: the air less half a hairline, which is
 * the one pixel the ring's rounding needs. Seven entries at a 4 dp gap, from that seat, still end inside Main's pane. The Utility rail's `UtilityRailRingBleed`, 8 dp, is the same law.
 */
internal val SettingsRailRingBleed: Dp = MimirSpacing.sm - MimirSpacing.hairlineWidth / 2

// Utility's UT01 shape (utilityRailBleedUp): measured [bleed] taller and placed [bleed] higher, reporting the given height.
private fun Modifier.settingsRailBleedUp(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = constraints.minHeight + extra,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight,
        ),
    )
    val height = (placeable.height - extra).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, -extra) }
}

@Composable
private fun SettingsDetailPane(
    category: SettingsCategory,
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    library: LibraryReadModel?,
    sourceReadFailureMessage: String?,
    profileDisplayName: String?,
    emulatorAssignments: List<EmulatorAssignment>?,
    biosRequirements: List<BiosRequirementRow>?,
    biosGrantPresent: Boolean,
    specialAccessRows: List<SpecialAccessRow>?,
    controllerShortcutRows: List<ControllerShortcutRow>,
    artworkAccount: String?,
    backdropValue: String,
    steamGridDbKeyPresent: Boolean,
    retroAchievementsUser: String?,
    retroAchievementsKeyState: RaKeyState,
    appVersion: String,
    sourceRemovalConfirmation: SourceRow?,
    sourceRemovalRunning: Boolean,
    keyboardPickerEntry: MimirKeyboardPickerEntry?,
    keyboardBridgeReady: Boolean,
    trackpadSettings: TrackpadSettingsState?,
    music: MusicReadModel?,
    onMusicPaneShown: () -> Unit,
    artworkDownloadBlockedCopy: String?,
    modifier: Modifier,
) {
    // Solve from the given box rather than the window: [modifier] already carries this
    // pane's own margins (the wide layout's edge margins, or the compactSquare branch's flat lg padding),
    // so measuring HERE with BoxWithConstraints reads the pane's real content box directly, the same
    // `BoxWithConstraints` + pure-gate shape [ReceiptRow] uses via `settingsReceiptStacks`
    // (`SettingsComponents.kt`) rather than re-deriving it from the window width one level up.
    // This single measurement point covers BOTH SettingsHome branches (wide rail+detail Row and the
    // compactSquare single pane) with no posture check — whichever box this Composable is actually given
    // is the pane, full stop.
    BoxWithConstraints(modifier) {
        val paneWidthDp = maxWidth.value
        // The two-layer handoff (the old pane leaving as recorded pixels, a lead, then the new one arriving)
        // is gone: the new pane glides in alone, a few dp on the vertical axis signed by where the rail moved, from
        // a partly visible start, so a burst never shows a blank or two panes at once. Nothing gates input.
        val glide = rememberSettingsDetailGlide(category, mimirReducedMotion())
        val glideTravelPx = with(LocalDensity.current) { SettingsDetailGlideTravelDp.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = glide.progress.value.coerceIn(0f, 1f)
                    alpha = SettingsDetailGlideStartAlpha + (1f - SettingsDetailGlideStartAlpha) * p
                    translationY = glide.direction * glideTravelPx * (1f - p)
                },
        ) {
        CompositionLocalProvider(LocalSettingsRowSupportCompact provides settingsRowSupportCompact(paneWidthDp)) {
            Column(Modifier.fillMaxSize()) {
                SettingsDetailPanel(
                    category = category,
                    authority = authority,
                    onRowActivate = onRowActivate,
                    library = library,
                    sourceReadFailureMessage = sourceReadFailureMessage,
                    profileDisplayName = profileDisplayName,
                    emulatorAssignments = emulatorAssignments,
                    biosRequirements = biosRequirements,
                    biosGrantPresent = biosGrantPresent,
                    specialAccessRows = specialAccessRows,
                    controllerShortcutRows = controllerShortcutRows,
                    artworkAccount = artworkAccount,
                    backdropValue = backdropValue,
                    steamGridDbKeyPresent = steamGridDbKeyPresent,
                    retroAchievementsUser = retroAchievementsUser,
                    retroAchievementsKeyState = retroAchievementsKeyState,
                    appVersion = appVersion,
                    sourceRemovalConfirmation = sourceRemovalConfirmation,
                    sourceRemovalRunning = sourceRemovalRunning,
                    keyboardPickerEntry = keyboardPickerEntry,
                    keyboardBridgeReady = keyboardBridgeReady,
                    trackpadSettings = trackpadSettings,
                    music = music,
                    onMusicPaneShown = onMusicPaneShown,
                    artworkDownloadBlockedCopy = artworkDownloadBlockedCopy,
                )
            }
        }
        }
    }
}

@Composable
private fun RailItem(
    category: SettingsCategory,
    focused: Boolean,
    active: Boolean,
    authority: FocusAuthority,
    onOpen: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // A focused rail entry wears the shared selection ring over the wash. The open category
    // keeps its active ember plate (0.14 fill, 0.55 border), never a second ring.
    val selected = focused || active
    // Radius rides the house card token (was a hand-typed 12dp — theme-critique F14).
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    val interactiveAccent =
        if (palette.usesBrightField) palette.materials.readableAccent else palette.accent
    val key = settingsRailFocusKey(category)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SettingsRailEntryHeightDp.dp)
            .testTag(SettingsRailEntryTestTagPrefix + category.name)
            .mimirFocusable(
                authority = authority,
                key = key,
                scope = SettingsRailScope,
                // Profile/About have an explicit whole-header/footer edge owner in SettingsHome.
                // Automatic bring-into-view remains useful for middle rows but would race those endpoints.
                autoBringIntoView = settingsRailUsesAutomaticBringIntoView(category),
                // Revealed with the ring's reach, so a focused entry never stops flush with the rail's edge, ring cut.
                revealOutset = MimirFocusTileRingReach,
            )
            // Ring before clip: the ring is paint outside the entry (ring migration, 2026-09-28).
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                active = active,
                accent = interactiveAccent,
            )
            .clip(shape)
            // Touch parity: a tap focuses this rail item AND opens it ,
            // with no second Compose focus node beside the authority's (motion audit batch A).
            .mimirActivatable(
                label = "Open",
                authority = authority,
                key = settingsRailFocusKey(category),
                onActivate = onOpen,
            )
            // **`sm`, not `lg` — MEASURED on the Nova, which is where this failed.** The rail is capped
            // at its 154 dp FLOOR on a 640 dp device, so its pill is 121 dp and held 89 dp of content.
            // A mark plus its gap takes 22 of that, and APPEARANCE needs ~80, so the entry clipped to
            // "APPEARA…" and EMULATORS to "EMULATO…" — both were observed on device. The inner padding is the only
            // slack that is not product copy, a configured width, or the mark's legible floor, so it is what
            // gives. The rail's own PANE margin is untouched: that one is symmetric with the detail
            // pane's by the shared margin-symmetry constraint.
            .padding(horizontal = MimirSpacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        val entryInk = if (selected) interactiveAccent else MimirColors.TextMuted
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            // **The mark tracks the row, it does not sit outside it.** `MimirIconMark` defaults both
            // inks from the palette, which would leave a constant-accent glyph beside a label that
            // dims and lifts with selection — the mark would read as a decoration attached to the rail
            // rather than as part of the entry. Both inks are handed the label's own colour so the
            // whole entry changes temperature together.
            MimirIconMark(
                icon = category.mark,
                // 16, not the set's default 24. MEASURED on BOTH handhelds, and the narrow one is what
                // sets it: the Thor's rail is 168 dp and the Nova's is 154 (its floor), so anything
                // sized for the Thor alone clips on the Nova. 24 dp truncated both devices; 18 dp still
                // truncated the Nova. The label is product copy and the rail width is configured, so the mark
                // is what gives way — and it gives way to the SMALLER device, not the roomier one.
                size = 16.dp,
                ink = entryInk,
                cellInk = entryInk,
                // The rail already announces its label; a second spoken name per row would make every
                // TalkBack pass through Settings twice as long and say nothing new.
                contentDescription = null,
            )
            RailLabel(category.label, entryInk, Modifier.weight(1f))
        }
    }
}

/**
 * A rail entry's section name, set whole. At the Thor's 168 dp rail APPEARANCE needs about 80 dp of label at 1.0x and about 104 dp at 1.3x, against the 100 dp the entry leaves
 * (alpha-cert sweep 10-09: "APPEARAN..."), and a section name is never cut. So the size steps down in half-sp steps, measured against the room the label is actually given, from the
 * eyebrow's own size to the type system's floor ([MimirTypeStep.Micro]); the ellipsis is for a name that still cannot stand at the floor (a font scale past about 1.5). The same
 * step-down the Files folder titles take. Pure measure: [settingsRailLabelSizeSp] decides, so a test can drive it with any measure.
 */
@Composable
private fun RailLabel(label: String, ink: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val base = MimirType.RunicEyebrow
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val merged = LocalTextStyle.current.merge(base)
    val visual = mimirStaticChrome(label)
    BoxWithConstraints(modifier) {
        val room = constraints.maxWidth
        val sizeSp = remember(visual, room, density.density, density.fontScale, merged) {
            settingsRailLabelSizeSp(maxSp = base.fontSize.value, minSp = MimirTypeStep.Micro.value) { size ->
                measurer.measure(
                    text = visual,
                    style = merged.copy(fontSize = size.sp),
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                    maxLines = 1,
                    density = density,
                ).size.width <= room
            }
        }
        MimirChromeText(
            label,
            style = base.copy(fontSize = sizeSp.sp),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The largest size from [maxSp] down to [minSp], in half-sp steps, at which [fits] holds; [minSp] when none does. */
internal fun settingsRailLabelSizeSp(maxSp: Float, minSp: Float, fits: (sizeSp: Float) -> Boolean): Float {
    var size = maxSp
    while (size > minSp) {
        if (fits(size)) return size
        size -= 0.5f
    }
    return minSp.coerceAtMost(maxSp)
}

/**
 * Open [category] into the detail pane: push [DetailScope] (if not already active) so the pane's rows
 * become the focus universe, then land focus on the first row (focus memory restores a previously
 * visited row). Called from a rail tap and from [dispatchToSettings]'s RIGHT/CONFIRM.
 */
internal fun openDetail(authority: FocusAuthority, category: SettingsCategory) {
    val target = detailKey(settingsFirstDetailRowId(category))
    SettingsDetailScrollBridge.cancelReveal()
    if (authority.state.value.scope != DetailScope) {
        authority.pushScope(DetailScope)
    }
    // A rapid rail NAV→RIGHT can arrive before Compose has replaced the preceding category's registered
    // detail rows. Do not let pushScope's temporary fallback become truth. Park the exact destination and
    // leave focus empty until that row registers; DefaultFocusAuthority then seeds it directly with no
    // old-row flash or post-layout correction.
    authority.clearFocus()
    authority.rememberFocusForScope(DetailScope, target)
    authority.requestFocus(target)
}

/** The first focusable row id of each panel — the landing target when a category opens. */
internal fun settingsFirstDetailRowId(category: SettingsCategory): String = when (category) {
    SettingsCategory.PROFILE -> "profile/name"
    // The receipt pane's only focusable action is the "Library folders" CTA (rowId library/add).
    SettingsCategory.LIBRARY -> "library/add"
    SettingsCategory.EMULATORS -> FirstSelectableEmulatorRowId
    SettingsCategory.APPEARANCE -> "appearance/theme"
    // The Music switch: always present, always focusable, the first row of the first card.
    SettingsCategory.MUSIC -> MusicPlayerEnabledRowId
    SettingsCategory.CONTROLS -> "controls/utility"
    SettingsCategory.ABOUT -> "about/credits/gametdb"
}

/**
 * THE single input entry point for Settings. The app shell feeds it one normalized [ControllerAction]
 * per press (already dual-source/hat/stick-coalesced by the InputAuthority). It routes by the currently
 * active scope so the two panes share one focus truth. Returns true if it handled the action (so the
 * shell knows not to treat it as a shell-level Back, etc.).
 *
 * @param onRailOpen invoked when a category is opened from the rail (host may want to sync its own state).
 * @param onRowActivate invoked on CONFIRM/A over a detail row (theme picker, rescan, backup, artwork fetch, …).
 * @param onExit invoked when CANCEL/Back is pressed while the rail already owns focus — the caller
 *   decides "leave Settings" (single back scope).
 * @param onRowSecondary invoked on SECONDARY/X over a detail row (Settings › Music: X on the Music folder
 *   row rescans it). Returns whether the row took the press; a row that does not leaves X exactly
 *   as unrouted as it was, so no other row's X changes.
 * @param onRowTertiary the same for TERTIARY/Y over a detail row (Settings › Music: Y on the Music folder row opens the Forget folder
 *   confirmation). Y is not routed on a Music sub-page.
 */
fun dispatchToSettings(
    authority: FocusAuthority,
    action: ControllerAction,
    onRailOpen: (SettingsCategory) -> Unit,
    onRowActivate: (rowId: String) -> Unit,
    onExit: () -> Unit,
    onRowSecondary: (rowId: String) -> Boolean = { false },
    onRowTertiary: (rowId: String) -> Boolean = { false },
): Boolean {
    val scope = authority.state.value.scope
    return when (scope) {
        SettingsRailScope -> when (action) {
            // Directional input is ALWAYS consumed (never passed to a platform focus engine — contract
            // rule 4); moveFocus's boolean only reports whether it moved (false at an edge, still handled).
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
                if (authority.moveFocus(action)) {
                    focusedCategory(authority)?.let(onRailOpen)
                }
                true
            }
            ControllerAction.NAV_RIGHT, ControllerAction.CONFIRM -> {
                focusedCategory(authority)?.let { cat ->
                    onRailOpen(cat)
                    openDetail(authority, cat)
                }
                true
            }
            // At the rail, Back/Cancel leaves Settings. LEFT is inert to prevent accidental exits; B stays
            // the one deliberate way out, per the single back-scope contract).
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onExit()
                true
            }
            ControllerAction.NAV_LEFT -> true // consumed; nothing sits left of the rail
            else -> false
        }

        DetailScope -> when (action) {
            // The rail scrolls when selection reaches a clipping point —
            // edges FLOW to the rail instead of dead-ending, so the joystick alone reaches all content.
            // DOWN past the last row pops to the rail AND advances it one section (the rail preview
            // re-targets the pane on the next frame — the pane swap must happen via recomposition, so we
            // deliberately STOP at the rail rather than re-pushing detail inside this same dispatch:
            // activeCategory mirrors rail focus only across a compose frame). RIGHT re-enters, exactly
            // like arriving at any section. UP past the first row returns to the rail in place.
            ControllerAction.NAV_DOWN -> {
                val from = authority.state.value.focused
                if (authority.moveFocus(action)) {
                    landOnPairLeft(authority, from)
                    SettingsDetailScrollBridge.cancelReveal()
                } else {
                    // DB01 edge-dwell : the first dead-end press REVEALS the pane's
                    // clipped footer (non-focusable trailing content) instead of chaining; only a press
                    // with the pane already fully scrolled advances the rail.
                    if (!SettingsDetailScrollBridge.revealEdge(down = true)) {
                        authority.popScope()
                        authority.moveFocus(ControllerAction.NAV_DOWN)
                        focusedCategory(authority)?.let(onRailOpen)
                    }
                }
                true
            }
            ControllerAction.NAV_UP -> {
                val from = authority.state.value.focused
                if (authority.moveFocus(action)) {
                    landOnPairLeft(authority, from)
                    SettingsDetailScrollBridge.cancelReveal()
                } else {
                    if (!SettingsDetailScrollBridge.revealEdge(down = false)) {
                        authority.popScope()
                    }
                }
                true
            }
            // LEFT first steps to the left half of a side-by-side pair (Backup's and Recovery's buttons); from the left half, or from any other row, it
            // returns to the rail as before. RIGHT is the same step the other way, and is left unhandled outside a pair.
            ControllerAction.NAV_LEFT -> {
                val sibling = settingsDetailPairSibling(authority.state.value.focused, action)
                if (sibling != null) {
                    authority.requestFocus(sibling)
                } else {
                    SettingsDetailScrollBridge.cancelReveal()
                    authority.popScope()
                }
                true
            }
            ControllerAction.NAV_RIGHT -> {
                val sibling = settingsDetailPairSibling(authority.state.value.focused, action) ?: return false
                authority.requestFocus(sibling)
                true
            }
            // Cancel / Back return to the rail — pop the detail scope, restoring the rail's focus.
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                SettingsDetailScrollBridge.cancelReveal()
                authority.popScope()
                true
            }
            ControllerAction.CONFIRM -> {
                focusedDetailRowId(authority)?.let(onRowActivate)
                true
            }
            // Consumed only when the focused row claims it: every row that has no X keeps today's fall-through.
            ControllerAction.SECONDARY -> focusedDetailRowId(authority)?.let(onRowSecondary) ?: false
            ControllerAction.TERTIARY -> focusedDetailRowId(authority)?.let(onRowTertiary) ?: false
            else -> false
        }

        // Settings › Music › Behaviour (update) and the Forget folder confirmation: a sub-page is one short list over the detail scope. UP and DOWN walk it and stop
        // at its ends (the page is short, and an edge never carries focus to the rail), A presses a switch, and B, Back or LEFT pop the scope:
        // the authority's focus memory then lands on the row that was focused when the page opened (Behaviour, or the Music folder).
        MusicBehaviourScope, MusicFolderForgetScope -> when (action) {
            ControllerAction.NAV_DOWN -> {
                if (authority.moveFocus(action)) SettingsDetailScrollBridge.cancelReveal() else SettingsDetailScrollBridge.revealEdge(down = true)
                true
            }
            ControllerAction.NAV_UP -> {
                if (authority.moveFocus(action)) SettingsDetailScrollBridge.cancelReveal() else SettingsDetailScrollBridge.revealEdge(down = false)
                true
            }
            ControllerAction.NAV_LEFT, ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                SettingsDetailScrollBridge.cancelReveal()
                authority.popScope()
                true
            }
            // RIGHT has nothing to the right of a list of switches; it is left unhandled, as in the detail pane outside a pair.
            ControllerAction.CONFIRM -> {
                focusedDetailRowId(authority)?.let(onRowActivate)
                true
            }
            ControllerAction.SECONDARY -> focusedDetailRowId(authority)?.let(onRowSecondary) ?: false
            else -> false
        }

        else -> false
    }
}

/** After an UP or DOWN move that left [from]: entering a side-by-side pair from outside lands on its left half (see [settingsDetailPairEntry]). */
private fun landOnPairLeft(authority: FocusAuthority, from: FocusKey?) {
    val landed = authority.state.value.focused
    val entry = settingsDetailPairEntry(from, landed)
    if (entry != null && entry != landed) authority.requestFocus(entry)
}

/** The rail category currently focused, if the rail owns focus. */
private fun focusedCategory(authority: FocusAuthority): SettingsCategory? {
    val focused = authority.state.value.focused ?: return null
    return settingsRailCategoryForFocusKey(focused)
}

internal fun settingsRailUsesAutomaticBringIntoView(category: SettingsCategory): Boolean =
    category != SettingsCategory.rail.firstOrNull() &&
        category != SettingsCategory.rail.lastOrNull()

/** The detail row id currently focused (strips the "settings/detail/" prefix), if the detail pane or one of its Music sub-pages owns focus. */
private fun focusedDetailRowId(authority: FocusAuthority): String? {
    val s = authority.state.value
    if (s.scope != DetailScope && s.scope !in MusicSubPageScopes) return null
    return settingsDetailRowIdForFocusKey(s.focused)
}

internal val SETTINGS_HOME_SCOPES = setOf(SettingsRailScope, DetailScope) + MusicSubPageScopes
internal const val SettingsDetailRestoreAttemptLimit = 120

// ── Width-keyed Settings modal geometry ──────────────────────────────────────────────────────
// The 680dp threshold matches Library search compactness so both treat Nova as narrow.
// It previously also split the detail margin (24dp below, 32dp above), but that margin now
// stays symmetric at every width. This constant serves settingsModalWidthCapDp only.
// A shared production solver should replace this literal when available; MimirCertifiedViewports
// is test-scope and cannot provide it here.
internal const val SettingsNarrowThresholdWidthDp = 680f

/**
 * Shared rail-width solve: the rail narrows continuously with the pane instead of holding a fixed
 * 168dp against a shrinking content column. Ceiling 168dp is the Odin's existing width — held so the
 * Odin's rail never moves, at any width at/above ~763.6dp (168/0.22).
 *
 * FLOOR — corrected 2026-08-21 (device regression, build `54ff6193`: `APPEARAN…` clipped on the Nova).
 * The old floor (132dp) was **derived wrong**, not just tuned wrong: it took the longest rail label's own
 * ink (~82dp) and called that "plus margin" without ever adding the padding the label actually sits
 * inside. The real floor has to clear ink **plus every padding layer between the rail's edge and the
 * label's first glyph**, chained end to end:
 *
 * - **Ink** — `"APPEARANCE"` (the longest of the seven [SettingsCategory] labels; `MUSIC` is the shortest; next is `EMULATORS` at
 *   ~73.5sp, then `CONTROLS` ~64.8sp — everything else is shorter), as [MimirChromeText] renders it
 *   uppercase in [MimirType.RunicEyebrow] (11sp, `FontWeight.SemiBold`, 1sp letterSpacing — [MimirTokens]).
 *   Ground-truthed here by reading the shipped `ibm_plex_sans_semibold.ttf`'s own `hmtx` advance-width
 *   table directly (the same method [MimirFonts]'s `SansUppercaseAdvanceRatio` etc. were derived by —
 *   cross-checked: re-deriving that constant from Regular's table this same way reproduces 0.6336 exactly).
 *   Summed per-glyph SemiBold advances for A,P,P,E,A,R,A,N,C,E = 6.523em; at 11sp that is 71.75sp of glyph
 *   ink. Add tracking: 1sp letterSpacing × 10 characters (Compose/Android letter-spacing pads after every
 *   character; using 9 gaps instead — i.e. no trailing pad — gives 80.75sp, the more conservative of the
 *   two readings) = **~81.75sp, i.e. ≈82dp either way** — corroborates the spec figure this floor was
 *   originally built on , rather than
 *   overturning it. This is a font-table estimate, not a live Compose/device text measurement.
 * - **+ 24dp** column start (`MimirSpacing.xl`, `SettingsRailPane`'s call site below:
 *   `.padding(start = MimirSpacing.xl, end = MimirSpacing.sm, ...)`)
 * - **+ 8dp** column end (`MimirSpacing.sm`, same call site)
 * - **+ 32dp** rail-item horizontal padding (`MimirSpacing.lg` = 16dp, [RailItem]'s own
 *   `.padding(horizontal = MimirSpacing.lg)`, BOTH sides of the item Box)
 *
 * `82 + 24 + 8 + 32 = 146dp` — the true minimum, with zero margin. Floor is set to **154dp**: that
 * minimum plus an 8dp margin ([MimirSpacing.sm]) to absorb the ink estimate's own ±1dp spread (trailing-
 * tracking convention, font hinting) rather than sitting exactly on the clip line the way the old floor
 * silently did. Every rail width this function can return is still coerced into `[154, 168]`, so the Odin
 * (natural value 183.15dp, always ceiling-clamped) is untouched by this change.
 */
internal fun settingsRailWidthDp(widthDp: Float): Float =
    mimirDetailRailWidthDp(widthDp)

/**
 * The detail pane's own start/end screen margin (`SettingsHome.kt`, `SettingsDetailPane` call site).
 *
 * RC-8 A2 (2026-08-21) introduced an `xl` (24dp) / `xxl` (32dp) split keyed on
 * [SettingsNarrowThresholdWidthDp]: `xxl` at/above the threshold (the Odin's pre-existing value,
 * unmoved), `xl` below it. That repaired the Nova's own left/right asymmetry (rail start 24dp vs this
 * function's 32dp) by bringing the Nova down to 24/24 -- but left the Odin itself asymmetric on its own
 * terms: 24dp from the rail's hardcoded `start = xl` on the left, 32dp from this function on the right,
 * measured on device as 24.0/33.0.
 *
 * **The split was removed**, exposed by the Odin-parity harness's `settings.marginSymmetry`
 * check flagging the *Nova* red (24/24 = 1.00 against an Odin baseline of 24/32 = 0.75) -- the harness
 * measures parity-to-Odin, so the symmetric device read as the one diverging. The reference device was made symmetric too: this now returns [MimirSpacing.xl] unconditionally, at every
 * width, because it must equal the rail's own start padding (`SettingsRailPane` call site, `start =
 * MimirSpacing.xl`, unchanged) -- the symmetry is the point, not the number. The width
 * branch on [SettingsNarrowThresholdWidthDp] is gone from this function; that constant survives only for
 * B1's [settingsModalWidthCapDp] below, which still uses it directly.
 *
 * Consequence, stated plainly: **the Odin's detail pane moves 8dp on the right** (32dp -> 24dp), and its
 * gap to the rail narrows by the same 8dp -- the first deliberate change to the reference device's own
 * appearance this pass has made. Every other viewport already at 24dp (anything below the old threshold)
 * is bit-identical before and after.
 */
internal fun settingsDetailEdgeMarginDp(widthDp: Float): Dp = MimirSpacing.xl

/**
 * The rendered width of the Settings section card: available pane width, less the rail and both detail
 * edge margins. Used by B1 below — the width a Settings modal opened from this screen must not exceed
 * once the card itself has shrunk, so a glass modal never renders wider than the glass card that opened
 * it (RC-11 fallout: invisible while the card was opaque, visible once both are the same material).
 */
internal fun settingsDetailPaneWidthDp(widthDp: Float): Float =
    mimirDetailPaneWidthDp(widthDp)

/**
 * B1: a Settings modal's (MimirSelectOverlay / ControllerMappingOverlay / MimirColorDial) authored width
 * cap, additionally bounded below [SettingsNarrowThresholdWidthDp] by the section card it was opened
 * from — restoring the parent/child size relationship RC-11's glass card made visually comparable to the
 * glass modal above it. At/above the threshold this returns [authoredCapDp] unchanged: 0.0dp of movement
 * on the Odin, where the branch above is never taken (832.5dp >= threshold) regardless of the section
 * card's own width -- so this is a no-op there independent of that width's value. (For the record: the
 * card itself is 616.5dp on the Odin as of the 2026-08-21 edge-margin change, up from 600.5dp before it --
 * the pane got 16dp leaner and the card grew to match -- but that number never reaches this branch.)
 *
 * [settingsDetailPaneWidthDp] describes ONLY the wide rail+detail `Row` layout `SettingsHome` renders in
 * [MimirViewportPosture.STANDARD]. `SettingsHome`'s own `compactSquare` branch (`BoxWithConstraints` at
 * this file's top) shows one full-bleed pane with a flat `MimirSpacing.lg` (16dp) margin instead — a
 * completely different card width the rail/margin formula does not describe. A modal can open from either
 * branch, so this gate additionally requires [MimirViewportPosture.STANDARD]: on
 * [MimirViewportPosture.COMPACT_SQUARE] it returns [authoredCapDp] unchanged, same as at/above the
 * threshold, rather than applying the wide-layout card width where it was never measured.
 */
fun settingsModalWidthCapDp(widthDp: Float, heightDp: Float, authoredCapDp: Float): Float =
    mimirModalWidthCapDp(widthDp, heightDp, authoredCapDp)

// ── Motion audit batch F: the detail pane's swap ──────────────────────────────

/**
 * Which way the new pane travels : on the vertical axis, signed by where the rail moved. Down
 * the rail is +1 in the page grammar's sign, so the new pane rises from below and the old one leaves upward; up the
 * rail is -1 and mirrors it. No previous category, no move, or a category off the rail is 0: nothing travels.
 */
internal fun settingsDetailSwapDirection(from: SettingsCategory?, to: SettingsCategory): Int {
    val fromIndex = from?.let { SettingsCategory.rail.indexOf(it) } ?: return 0
    val toIndex = SettingsCategory.rail.indexOf(to)
    return when {
        fromIndex < 0 || toIndex < 0 -> 0
        toIndex > fromIndex -> 1
        toIndex < fromIndex -> -1
        else -> 0
    }
}

/**
 * The Settings detail pane's glide-in. Each category
 * change restarts one clock from 0: the pane starts at [SettingsDetailGlideStartAlpha], [SettingsDetailGlideTravelDp]
 * off its place on the side the rail moved from, and settles over [SettingsDetailGlideMs]. A first composition and
 * reduced motion start settled.
 */
internal class SettingsDetailGlide(
    val progress: Animatable<Float, AnimationVector1D>,
    val direction: Int,
)

/** Plain memory, not snapshot state: it is written inside `remember` and read only there. */
private class SettingsDetailGlideMemory {
    var last: SettingsCategory? = null
}

@Composable
private fun rememberSettingsDetailGlide(category: SettingsCategory, reducedMotion: Boolean): SettingsDetailGlide {
    val memory = remember { SettingsDetailGlideMemory() }
    val glide = remember(category) {
        val previous = memory.last
        memory.last = category
        SettingsDetailGlide(
            progress = Animatable(settingsDetailGlideStart(first = previous == null, reducedMotion = reducedMotion)),
            direction = settingsDetailSwapDirection(previous, category),
        )
    }
    LaunchedEffect(glide) {
        if (glide.progress.value < 1f) {
            glide.progress.animateTo(1f, tween(SettingsDetailGlideMs, easing = MimirMotion.Easing))
        }
    }
    return glide
}

/** Where a category's glide starts: settled for the first pane and under reduced motion, else from 0. */
internal fun settingsDetailGlideStart(first: Boolean, reducedMotion: Boolean): Float =
    if (first || reducedMotion) 1f else 0f

internal const val SettingsDetailGlideMs = 280
internal const val SettingsDetailGlideTravelDp = 6
internal const val SettingsDetailGlideStartAlpha = 0.4f

/** Whether the compact layout shows the rail: the live answer under a Settings scope, the last one under any other (see [SettingsHome]'s `compactOnRail`). */
internal fun settingsCompactOnRail(scope: FocusScope, railFocused: Boolean, held: Boolean): Boolean =
    if (scope in SETTINGS_HOME_SCOPES) railFocused else held

/** Plain memory, not snapshot state: written during composition with the same answer every pass, and read only there. */
private class SettingsCompactLatch(var held: Boolean)

@Composable
private fun rememberSettingsCompactOnRail(scope: FocusScope, railFocused: Boolean, startsOnDetail: Boolean): Boolean {
    val latch = remember { SettingsCompactLatch(held = !startsOnDetail) }
    latch.held = settingsCompactOnRail(scope, railFocused, latch.held)
    return latch.held
}

/**
 * The category the detail pane shows, which trails the rail's ring (RC-11, audit 2026-10-14: "the Settings rail swaps content 50-100 ms before its ring moves", Thor and emulator).
 *
 * The ring and the pane retarget on the SAME frame (both read one collected focus state), so starting the pane on the ring's frame fixes nothing: the lead is in the curves. The pane's floor
 * is [SettingsDetailGlideStartAlpha] from its first frame, a cut from the old pane to the new at 40%; the ring's floor is nothing, on a slow-start ease over 420 ms (140 mid-traverse), 11%
 * of its weight on the pane's first frame, measured. So while the rail is the one moving, the pane holds its previous category for [settingsDetailCommitLeadMs] and commits once the
 * ring has reached the weight the pane starts at. The hold restarts on every hop, so a burst of hops each sooner than the lead composes the pane it stops on and none it passes. A held
 * pad is NOT such a burst: its hops stream on the 140 ms ring clock, the lead is 35 ms, and its 120 ms cruise composes every pane it passes, as before this change, so this does not
 * touch the category tail (RC-17, which stays untraced). The hold ends the moment the rail is not moving (RIGHT or A opened the pane: it commits at once, so the key lands on the
 * pane's own row), and under reduced motion, where the ring snaps. It waits on the animation clock ([mimirHold]), so it scales with the animator exactly as the ring does.
 *
 * Only what the PANE shows follows this; the rail's ring, its open plate and everything that routes input stay on the focus itself.
 */
@Composable
private fun rememberSettingsPaneCategory(target: SettingsCategory, railMoves: Boolean, reducedMotion: Boolean): SettingsCategory {
    var committed by remember { mutableStateOf(target) }
    val holds = railMoves && !reducedMotion && committed != target
    LaunchedEffect(target, holds) {
        if (holds) mimirHold(settingsDetailCommitLeadMs(mimirFocusRingMs()))
        committed = target
    }
    return if (holds) committed else target
}

/**
 * How long the pane holds its previous category: the first millisecond at which a ring on [ringMs] and the house ease ([MimirMotion.Easing]) has reached
 * [SettingsDetailGlideStartAlpha], the weight the new pane starts at. Both come from clocks that already exist: 104 ms at the 420 ms settle, 35 ms (two frames) on the 140 ms
 * traverse clock. No literal of its own.
 */
internal fun settingsDetailCommitLeadMs(ringMs: Int): Int {
    if (ringMs <= 0) return 0
    for (ms in 0..ringMs) {
        if (MimirMotion.Easing.transform(ms / ringMs.toFloat()) >= SettingsDetailGlideStartAlpha) return ms
    }
    return ringMs
}

// ── Preview (standalone: self-provides a focus authority so the rail + detail render + are navigable) ──
@Preview(showBackground = true, widthDp = 960, heightDp = 540)
@Composable
private fun SettingsHomePreview() {
    val authority = remember { DefaultFocusAuthority() }
    val previewLibrary = remember {
        LibraryReadModel(
            summary = LibrarySummary(SettingsSample.totalGames, SettingsSample.totalPlatforms),
            sources = SettingsSample.sources.mapIndexed { index, source ->
                SourceRow(
                    friendlyPath = source.friendlyPath,
                    rootUri = "preview://source/$index",
                    gameCount = source.gamesCount,
                    available = source.status == SettingsSample.SampleStatus.READY,
                    isPrimary = index == 0,
                )
            },
        )
    }
    val previewAssignments = remember {
        SettingsSample.emulatorAssignments.map {
            EmulatorAssignment(it.platform, it.emulator, it.status)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFocusAuthority provides authority) {
        SettingsHome(
            authority = authority,
            library = previewLibrary,
            profileDisplayName = SettingsSample.profileDisplayName,
            emulatorAssignments = previewAssignments,
            appVersion = SettingsSample.appVersion,
        )
    }
}
