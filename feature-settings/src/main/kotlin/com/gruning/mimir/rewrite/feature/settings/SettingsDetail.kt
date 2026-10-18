package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.RaKeyState
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import com.gruning.mimir.rewrite.core.ui.MimirAccentTemp
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirRingStop
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirViewportSettings
import com.gruning.mimir.rewrite.core.ui.screenToneFor
import com.gruning.mimir.rewrite.core.ui.shift

/**
 * The right-hand detail pane. One scope for the whole detail region so D-pad Up/Down traverses the
 * focusable rows of whichever category is open, and Left/Back hands focus back to the rail (the host
 * pops [DetailScope] → the rail scope). Every row is a real, representative control built on
 * [SettingsRow]; the panes bind the REAL host-passed read models. Missing reads render honest loading states;
 * preview fixtures are supplied explicitly by the preview host.
 */

/** The active focus scope while the detail pane owns focus. Rows register here; the rail is a sibling scope. */
val DetailScope = FocusScope("settings/detail")

internal const val RetroAchievementsTitle = "RetroAchievements"

internal val RetroAchievementsTitleIntent =
    MimirTextIntent.Authored(RetroAchievementsTitle)

internal const val MimirManagedArtworkStorageCopy = "Managed by Mimir on this device"
internal const val MimirGameFilesStorageCopy = "Stay in your chosen folders · never sent online"
internal const val MimirLibraryRescanSupportingCopy =
    "Rebuild reachable folders. Unreachable folders stay registered; only folders proven missing leave Mimir’s index. Game files stay untouched."

/**
 * Alpha cert 2026-10-08 (E3): what the Library pane and a RESCAN press say while "All files access" is off and the
 * folders it guards cannot be read. Public because the app's Settings coordinator shows it when RESCAN is pressed.
 */
const val LibraryFilesAccessLostCopy = "Mimir can’t read your folders right now. Allow file access to rescan."

/** The same sentence while only some of the folders are locked, so it does not claim the whole library is out of reach. */
const val LibraryFilesAccessLostSomeCopy = "Mimir can’t read some of your folders right now. Allow file access to rescan."
internal const val ScreenScraperDownloadSupportingCopy =
    "Download missing descriptions and artwork for games in your library."

internal fun screenScraperProfileSupportingCopy(account: String?): String =
    account?.trim()?.takeIf(String::isNotEmpty)?.let { connectedAccount ->
        "Connected as $connectedAccount. Downloads stay on this device after disconnecting."
    } ?: "Connect your ScreenScraper account to use enrichment."

/** Stable per-row focus key. Stable across recompositions so focus survives re-render (memory: FocusKey stability). */
fun detailKey(id: String) = settingsDetailFocusKey(id)

/** Registered platform ink only; raw resolver/storage slugs never become release-visible copy. */
internal fun settingsPlatformLabel(displayLabel: String?): String =
    displayLabel
        ?.takeIf(String::isNotBlank)
        ?: "Unknown platform"

/**
 * Render the detail panel for [category]. The panel scrolls (a handheld pane is short); focus rows are
 * registered in [DetailScope] via [SettingsRow]. [onRowActivate] is the host hook fired on CONFIRM/A or
 * a tap — in this slice it drives no native action (sample data), it is where the real picker/route
 * lands later. The row id is passed so the host can branch (e.g. open the theme picker).
 */
@Composable
fun SettingsDetailPanel(
    category: SettingsCategory,
    authority: FocusAuthority,
    onRowActivate: (rowId: String) -> Unit,
    modifier: Modifier = Modifier,
    library: LibraryReadModel? = null,
    /**
     * The shared read-failure sentence
     * from `MimirLibraryCoordinator.sourceReadFailureMessage`, non-null exactly when the most recent
     * `rescan`/`addSource` (the folder pickers below) hit `ScanOutcome.Failed`. Threaded down to
     * [LibraryPanel] verbatim — no new copy is authored here.
     */
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
    sourceRemovalConfirmation: SourceRow? = null,
    sourceRemovalRunning: Boolean = false,
    keyboardPickerEntry: MimirKeyboardPickerEntry? = null,
    keyboardBridgeReady: Boolean = false,
    trackpadSettings: TrackpadSettingsState? = null,
    /** The Music pane's read model (spec 2026-09-30). Null renders honest loading rows, never a hand-authored player. */
    music: MusicReadModel? = null,
    /** Fired once when the Music pane enters; the app asks the engine to count the games that hold a sound. */
    onMusicPaneShown: () -> Unit = {},
    /**
     * Non-null while the game-music download holds the artwork gate: the Enrichment Download row shows this line
     * with a Busy chip instead of offering a start the engine would refuse.
     */
    artworkDownloadBlockedCopy: String? = null,
) {
    // A FRESH scroll state per category → switching sub-menus always restores to the default (top) state,
    // never inheriting the previous pane's scroll offset. Keyed on `category` so a new
    // ScrollState(0) is created on every switch — resets on the first frame, no transient jump.
    // DB01 edge-dwell: the scroll is exposed to the D-pad dispatch ([SettingsDetailScrollBridge]) so a dead-end press can REVEAL the
    // clipped footer before the rail chain fires. Re-attaches on every category swap; detaches on dispose.
    // The Music pane and the Library pane have second pages (Music's Behaviour and Forget folder pages, Library's source-removal
    // confirmation) that take the pane's place on the swap tier (audit 2026-10-14, RC-34), so each of THEIR pages scrolls on its own
    // ([SettingsSubPageSwap]): a page leaving holds its place while it fades, and a page arriving starts at its top, instead of one
    // shared offset that would jump the page leaving. The rest of the panes are one page and wear one scroll here.
    when (category) {
        SettingsCategory.LIBRARY -> SettingsHostedPane(modifier) {
            LibraryPanel(
                authority = authority,
                onRowActivate = onRowActivate,
                library = library,
                sourceReadFailureMessage = sourceReadFailureMessage,
                sourceRemovalConfirmation = sourceRemovalConfirmation,
                sourceRemovalRunning = sourceRemovalRunning,
            )
        }
        // Settings › Music: the body lives in MusicPanel.kt, this file's `when` seats it.
        SettingsCategory.MUSIC -> SettingsHostedPane(modifier) {
            MusicPanel(
                authority = authority,
                onRowActivate = onRowActivate,
                music = music,
                onShown = onMusicPaneShown,
            )
        }
        else -> SettingsPaneScroll(scrollKey = category, modifier = modifier) {
            // NO page-level title. Shipping has none — the pane identity lives in the FIRST card's white
            // title (memory settings-parity-plan-2026-07-08; ship_profile/emulators/appearance captures). The
            // detail column's top pad (= the rail's) keeps the first card level with the rail + off the tab strip.
            // NO "SAMPLE DATA" / "PARTIAL LIVE" debug banner (eliminate the debug jargon — it
            // read as a debug panel, not a shipping product). On device the host passes live data for every pane;
            // anything genuinely not wired yet is rendered as an honest deferred/read-only state in its own pane,
            // never a page-top placeholder banner.
            when (category) {
                SettingsCategory.PROFILE -> ProfilePanel(
                    authority,
                    onRowActivate,
                    profileDisplayName,
                    retroAchievementsUser,
                    retroAchievementsKeyState,
                )
                SettingsCategory.EMULATORS -> EmulatorsPanel(
                    authority = authority,
                    onRowActivate = onRowActivate,
                    emulatorAssignments = emulatorAssignments,
                    usageAccess = specialAccessRows?.firstOrNull { it.id == "usage" },
                )
                SettingsCategory.APPEARANCE -> AppearancePanel(
                    authority = authority,
                    onRowActivate = onRowActivate,
                    backdropValue = backdropValue,
                    artworkAccount = artworkAccount,
                    steamGridDbKeyPresent = steamGridDbKeyPresent,
                    // The Mimir-button row adds "The Mimir button is off." from the same fact Controls shows.
                    mimirButtonOff = specialAccessRows?.firstOrNull { it.id == "m_button" }?.granted == false,
                    downloadBlockedCopy = artworkDownloadBlockedCopy,
                )
                SettingsCategory.CONTROLS -> ControlsPanel(
                    authority,
                    onRowActivate,
                    specialAccessRows,
                    controllerShortcutRows,
                    keyboardPickerEntry,
                    keyboardBridgeReady,
                    trackpadSettings,
                )
                SettingsCategory.ABOUT -> AboutPanel(authority, onRowActivate, appVersion)
                else -> Unit
            }
        }
    }
}

/** A pane that hands its scrolling to its own sub-page swap (see [SettingsDetailPanel]): a box the size of the pane, and the flag [SettingsSubPageSwap] reads. */
@Composable
private fun SettingsHostedPane(modifier: Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsPaneHostsScroll provides true) {
        Box(modifier.fillMaxSize()) { content() }
    }
}

// ── PROFILE ────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun ProfilePanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    profileDisplayName: String? = null,
    retroAchievementsUser: String? = null,
    retroAchievementsKeyState: RaKeyState = RaKeyState.NEVER_CHECKED,
) {
    // LIVE when the host passes the ProfileNameStore value ("" = set-but-blank → "No name set").
    val displayName = profileDisplayName?.ifBlank { "No name set" } ?: "Loading profile…"
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        // Shipping's Profile card: the name row (name as the title, honest sub-line) + an "On this device
        // only" posture chip. Neutral gold, not green (green = success only, memory mimir-color-system).
        //
        // RC-8 Tier C1 : every
        // SECTION BLURB in this file — the card-level sentence between a section header and its first
        // row/receipt — moves from RowSupport (Body, 14sp) to MimirTypeScale.SupportCompact (Label,
        // 11sp) UNCONDITIONALLY, the same normalization Utility/Tweaker took on 2026-08-08
        // (`BrightnessOverlay.kt:1600`, `:1770`). A ROW'S OWN supporting text (inside
        // `SettingsRow`/`SettingsInfoRow`) is a DIFFERENT lane: the 2026-08-11 same-size pairing binds a
        // row's title to its own subtext (`core-ui/MimirTokens.kt:47-51`), and a blurb has no title of its
        // own so it never was that pair.
        //
        // Small-pane exception: "unconditionally" above is this blurb's own lane only. A row's own
        // supporting text is no longer flatly RowSupport everywhere — it now reads `settingsRowSupportStyle`
        // (`SettingsComponents.kt`), which resolves to RowSupportCompact below a 500dp pane and RowSupport
        // at/above it. `LibrarySourceRemovalConfirmation`'s explanatory line (below the folder path, see
        // that function) sits directly under a `RowTitle`-styled line the same way — a manual title/subtext
        // row, not a blurb — so it reads the same resolver rather than staying flatly RowSupport; see that
        // site for detail.
        SettingsSection(
            // Shipping's first Profile card IS the pane identity: title "Profile" + posture chip + blurb.
            title = "Profile",
            trailing = { StaticStatusChip(text = MimirCopy.PostureOnThisDevice, color = MimirColors.Gold) },
        ) {
            Text(
                "Your name and optional RetroAchievements connection.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextSecondary,
            )
            SettingsRow(
                key = detailKey("profile/name"),
                scope = DetailScope,
                authority = authority,
                title = displayName,
                titleIntent = MimirTextIntent.Authored(displayName),
                supporting = "No account, no sign-in. Change the name any time.",
                onActivate = { onRowActivate("profile/name") },
                trailing = { RowAction("Change name") },
            )
        }
        // RetroAchievements (RA01). It lives in Profile because it is an ACCOUNT, not an
        // art source — the SteamGridDB key sits in Appearance for the opposite reason. Optional by design:
        // a user who never connects sees no RA anywhere and Mimir makes no RA request.
        SettingsSection(
            title = RetroAchievementsTitle,
            titleIntent = RetroAchievementsTitleIntent,
            trailing = {
                //  — a live connection is a SUCCESS
                // state, which is exactly what the color system reserves green for (gold is
                // accent/focus/actions). Using gold here was the mistake.
                if (!retroAchievementsUser.isNullOrBlank()) {
                    when (retroAchievementsKeyState) {
                        RaKeyState.ACCEPTED -> StaticStatusChip("Connected", MimirColors.SuccessGreen)
                        RaKeyState.REJECTED_THIS_SESSION -> StaticStatusChip("Key rejected", MimirColors.Danger)
                        RaKeyState.NEVER_CHECKED -> StaticStatusChip("Not checked", MimirColors.TextMuted)
                    }
                }
            },
        ) {
            Text(
                // The load-bearing honesty line: Mimir is a launcher and cannot unlock anything. Without
                // this a user could reasonably read "connect RetroAchievements" as "achievements now work".
                "See your achievement progress on games you already have. Achievements unlock inside your " +
                    "emulator, not in Mimir — Mimir shows the progress. Your key stays encrypted on this device.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            SettingsRow(
                key = detailKey("profile/retroachievements"),
                scope = DetailScope,
                authority = authority,
                title = "RetroAchievements",
                titleIntent = MimirTextIntent.Authored("RetroAchievements"),
                supporting = when {
                    retroAchievementsUser.isNullOrBlank() ->
                        "Add your username and Web API key to see achievement progress. The key is at " +
                            "retroachievements.org › Settings › Web API Key."
                    retroAchievementsKeyState == RaKeyState.ACCEPTED ->
                        "Progress will be read for $retroAchievementsUser."
                    retroAchievementsKeyState == RaKeyState.REJECTED_THIS_SESSION ->
                        "RetroAchievements is no longer accepting this key. Select to enter a new one."
                    else -> com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy.SettingsNeverChecked
                },
                onActivate = { onRowActivate("profile/retroachievements") },
                trailing = {
                    RowAction(if (!retroAchievementsUser.isNullOrBlank()) "Update" else "Connect")
                },
            )
            if (!retroAchievementsUser.isNullOrBlank()) {
                SettingsRow(
                    key = detailKey("profile/retroachievements/disconnect"),
                    scope = DetailScope,
                    authority = authority,
                    title = "Disconnect",
                    supporting = "Forget the key and stop showing achievement progress.",
                    onActivate = { onRowActivate("profile/retroachievements/disconnect") },
                    trailing = { RowAction("Disconnect") },
                )
            }
        }
    }
}

// ── LIBRARY ────────────────────────────────────────────────────────────────────────────────────
/**
 * The Library panel binds the real `observeSummary`/`observeSources` read-model. A missing first emission
 * renders an honest loading state and never a hand-authored library.
 */
@Composable
private fun LibraryPanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    library: LibraryReadModel?,
    sourceReadFailureMessage: String?,
    sourceRemovalConfirmation: SourceRow?,
    sourceRemovalRunning: Boolean,
) {
    // The source-removal confirmation takes the page's place on the swap tier (audit 2026-10-14, RC-34; it was `if (...) { ...; return }`, a cut both ways).
    // The page being left on a cancel still wears the folder it asked about, so the row is kept by its root.
    val asked = remember { SourceRemovalMemory() }
    sourceRemovalConfirmation?.let { asked.rows[it.rootUri] = it }
    SettingsSubPageSwap(
        target = sourceRemovalConfirmation?.rootUri,
        returning = sourceRemovalConfirmation == null,
        label = "settings-library-page",
    ) { rootUri ->
        val source = rootUri?.let(asked.rows::get)
        if (source != null) {
            LibrarySourceRemovalConfirmation(
                authority = authority,
                onRowActivate = onRowActivate,
                source = source,
                running = sourceRemovalRunning,
            )
        } else {
            LibraryRowsPage(authority, onRowActivate, library, sourceReadFailureMessage)
        }
    }
}

/** Plain memory, not snapshot state: written during composition and read only there, as the detail pane's glide keeps its last category in SettingsHome. */
private class SourceRemovalMemory {
    val rows = HashMap<String, SourceRow>()
}

@Composable
private fun LibraryRowsPage(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    library: LibraryReadModel?,
    sourceReadFailureMessage: String?,
) {
    val libraryLoading = library == null
    // Live counts + the primary source path when bound; otherwise honest loading placeholders.
    val gameCount: Int
    val platformCount: Int
    val primaryFolder: String
    val primaryConfigured: Boolean
    val primaryAvailable: Boolean
    val extraFolders: Int
    if (library != null) {
        gameCount = library.summary.gameCount
        platformCount = library.summary.platformCount
        val primary = library.sources.firstOrNull { it.isPrimary }
        primaryFolder = primary?.friendlyPath ?: "Not set yet"
        primaryConfigured = primary != null
        primaryAvailable = primary?.let { library.isReadable(it) } ?: false
        extraFolders = library.sources.count { !it.isPrimary }
    } else {
        gameCount = 0
        platformCount = 0
        primaryFolder = "Loading library…"
        primaryConfigured = false
        primaryAvailable = false
        extraFolders = 0
    }

    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        SettingsSection(title = "Library") {
            Text(
                "Folders, rescans, hidden games, and backup.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextSecondary,
            )
        }
        // Shipping's Library-folders card: pane identity
        // ("Library folders" + a GOLD "Local" posture chip — green is per-item success only) + blurb + a RECEIPT grid of the folder read-model + one
        // gradient "Library folders" CTA into folder management. Friendly paths only.
        SettingsSection(
            title = "Library folders",
            trailing = { StaticStatusChip(text = MimirCopy.PostureLocal, color = MimirColors.Gold) },
        ) {
            Text("The folders Mimir reads your games from — in place, nothing moved.", style = MimirTypeScale.SupportCompact, color = MimirColors.TextSecondary)
            Spacer(Modifier.height(MimirSpacing.xs))
            ReceiptRow(
                label = "Mimir ROM folder",
                value = when {
                    libraryLoading -> "Loading library…"
                    !primaryConfigured -> primaryFolder
                    primaryAvailable -> primaryFolder
                    else -> "$primaryFolder  ·  unavailable"
                },
            )
            ReceiptRow(
                label = "Additional Library folders",
                value = when {
                    libraryLoading -> "Loading…"
                    extraFolders == 0 -> "None"
                    else -> "$extraFolders connected"
                },
            )
            // No scan-DATE in the read model — "Folder detection" carries the honest detection RESULT (the
            // counts), never a fabricated date. Folder access is shipping's exact copy; the storage row follows
            // the live All-files grant (cert 2026-10-08 B2) so it never says "per folder" over a granted switch.
            ReceiptRow(
                label = "Folder detection",
                value = if (libraryLoading) "Loading library…" else "$gameCount games · $platformCount platforms",
            )
            // Allowed, not Granted: one word for a permission product-wide.
            ReceiptRow(label = "Folder access", value = "Allowed when you pick a folder")
            ReceiptRow(
                label = "Storage permission",
                value = if (library?.allFilesAccess == true) "Allowed for all files" else "Allowed per folder when you pick it",
            )
            Spacer(Modifier.height(MimirSpacing.sm))
            // Primary and additive roots are deliberately separate actions: replacing the Mimir ROM folder
            // cannot silently turn every later Library folder into another primary.
            GradientCta(
                label = "Choose Mimir ROM folder",
                key = detailKey("library/primary"),
                scope = DetailScope,
                authority = authority,
                onActivate = { onRowActivate("library/primary") },
            )
            SettingsRow(
                key = detailKey("library/add"),
                scope = DetailScope,
                authority = authority,
                title = "Add Library folder",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Read another folder without replacing your primary Mimir ROM folder.",
                onActivate = { onRowActivate("library/add") },
                trailing = { RowAction("Add folder") },
            )
            // N6: the shared sentence from `MimirLibraryCoordinator.sourceReadFailureMessage`,
            // reused verbatim -- no new copy. Non-null exactly when the folder just picked above (primary
            // or additional) could not be read; the coordinator overwrites this field on every completed
            // `rescan`/`addSource`, so the NEXT successful pick replaces it with null and this note
            // disappears on that recomposition -- there is no separate dismiss action. Plain `Text`, the
            // same non-interactive-note idiom already used for this pane's blurbs above (no
            // `mimirFocusable`): it never registers with FocusAuthority, so it cannot capture focus and
            // does not shift the D-pad order of the real rows around it.
            if (sourceReadFailureMessage != null) {
                Spacer(Modifier.height(MimirSpacing.xs))
                Text(
                    sourceReadFailureMessage,
                    style = MimirTypeScale.SupportCompact,
                    color = MimirColors.Danger,
                )
            }
            // Cert E3: with "All files access" off a rescan cannot read shared folders, and they must not read
            // "Available". Say so, and offer the route to the screen that fixes it.
            val fileAccessNote = library?.fileAccessNote
            if (fileAccessNote != null) {
                Spacer(Modifier.height(MimirSpacing.xs))
                Text(
                    fileAccessNote,
                    style = MimirTypeScale.SupportCompact,
                    color = MimirColors.Danger,
                )
                SettingsRow(
                    key = detailKey(LibraryAllowFilesRowId),
                    scope = DetailScope,
                    authority = authority,
                    title = "Allow file access",
                    titleStyle = MimirType.SettingsActionTitle,
                    supporting = "Opens Android’s file access screen for Mimir.",
                    onActivate = { onRowActivate(LibraryAllowFilesRowId) },
                    trailing = { RowAction("Allow") },
                )
            }
            library?.sources
                ?.filterNot(SourceRow::isPrimary)
                ?.forEach { source ->
                    val rowId = librarySourceRemovalRowId(source.rootUri)
                    SettingsRow(
                        key = detailKey(rowId),
                        scope = DetailScope,
                        authority = authority,
                        title = source.friendlyPath,
                        supporting = buildString {
                            append(source.gameCount)
                            append(if (source.gameCount == 1) " game" else " games")
                            append(if (library?.isReadable(source) ?: source.available) " · Available" else " · Unavailable")
                        },
                        onActivate = { onRowActivate(rowId) },
                        trailing = { RowAction("Remove") },
                    )
                }
            SettingsRow(
                key = detailKey("library/rescan"),
                scope = DetailScope,
                authority = authority,
                title = "Rescan library",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = MimirLibraryRescanSupportingCopy,
                onActivate = { onRowActivate("library/rescan") },
                trailing = { RowAction("Rescan") },
            )
        }
        // a clear map of WHERE the user's media/assets live, so nothing feels hidden. Friendly
        // terms only  — covers/clips sit beside the games; tile/icon art
        // is Mimir’s private app storage on this device; nothing is ever uploaded (local-only posture).
        SettingsSection(title = "Where Mimir keeps things") {
            Text(
                "Artwork managed by Mimir stays in its private folder on this device. Your games stay where you chose them.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            Spacer(Modifier.height(MimirSpacing.xs))
            ReceiptRow(label = "Your games", value = primaryFolder)
            ReceiptRow(
                label = "Hero Media",
                value = "Saved beside the game when its folder allows it",
            )
            ReceiptRow(label = "Tile & icon art", value = MimirManagedArtworkStorageCopy)
            ReceiptRow(label = "Game files", value = MimirGameFilesStorageCopy)
        }
        // Only when the user has hidden something — the recover path for "Hide from library" (memory
        // `dismiss-hide-game-nondestructive`). Restoring a group puts it back on the shelf/gamelist.
        val hidden = library?.hiddenGames.orEmpty()
        if (hidden.isNotEmpty()) {
            SettingsSection(title = "Hidden games") {
                Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                    Text(
                        "Games you’ve hidden. Restore one to put it back on the shelf.",
                        style = MimirTypeScale.SupportCompact,
                        color = MimirColors.TextMuted,
                    )
                    hidden.forEach { g ->
                        val platformLabel = library?.platforms
                            ?.firstOrNull { it.platformId == g.platformId }
                            ?.label
                        SettingsRow(
                            key = detailKey("library/hidden/${g.gameId.value}"),
                            scope = DetailScope,
                            authority = authority,
                            title = g.title,
                            titleIntent = MimirTextIntent.Authored(g.title),
                            supporting = settingsPlatformLabel(platformLabel),
                            onActivate = { onRowActivate("library/hidden/${g.gameId.value}") },
                            trailing = { RowAction("Restore") },
                        )
                    }
                }
            }
        }
        BackupSection(authority, onRowActivate)
    }
}

@Composable
private fun LibrarySourceRemovalConfirmation(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    source: SourceRow,
    running: Boolean,
) {
    SettingsSection(title = "Remove Library folder?") {
        // NOT a RC-8 Tier C1 section blurb: `source.friendlyPath` above is styled RowTitle and this line
        // is its subtext — a manually-laid-out title/subtext PAIR (the 2026-08-11 same-size pairing,
        // core-ui/MimirTokens.kt:47-51), not the "sentence between a header and its first row" a blurb is.
        // Small-pane exception: the pairing is now scoped, not absolute — it holds at every
        // pane at/above 500dp and REVERSES below it, exactly for pairs shaped like this one. Reading the
        // same ambient resolver the `SettingsRow`s two lines down already use keeps this pair and theirs
        // in lockstep at every width, rather than leaving this one asserting the un-scoped 2026-08-11
        // reading beside rows that no longer do.
        Text(
            source.friendlyPath,
            style = MimirType.RowTitle,
            color = MimirColors.TextPrimary,
        )
        Text(
            "Mimir will stop scanning this folder and update your library. Your game files will not be " +
                "deleted or moved. They stay exactly where they are.",
            style = settingsRowSupportStyle,
            color = MimirColors.TextSecondary,
        )
        SettingsRow(
            key = detailKey(LibrarySourceRemovalCancelRowId),
            scope = DetailScope,
            authority = authority,
            title = "Cancel",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = "Keep this Library folder connected.",
            onActivate = { onRowActivate(LibrarySourceRemovalCancelRowId) },
            trailing = { RowAction("Cancel") },
        )
        SettingsRow(
            key = detailKey(LibrarySourceRemovalConfirmRowId),
            scope = DetailScope,
            authority = authority,
            title = if (running) "Removing folder…" else "Remove folder",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = "Remove it from Mimir only. Game files stay untouched.",
            onActivate = { onRowActivate(LibrarySourceRemovalConfirmRowId) },
            trailing = { RowAction(if (running) "Working…" else "Remove folder") },
        )
    }
}

@Composable
private fun EnrichmentSections(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    artworkAccount: String?,
    steamGridDbKeyPresent: Boolean,
    downloadBlockedCopy: String? = null,
) {
    SettingsSection(title = "Enrichment & artwork") {
        Text(
            // Hero Motion was removed from this sentence 2026-09-17, and it was WRONG rather than merely
            // out of date: the bulk download of clips is retired and its Settings row with it, so this
            // promised the player something no provider here will ever fetch. Hero Motion is a per-game
            // choice in Customize Media now. Hero Art is what enrichment downloads, and it says so.
            "Optional providers can enrich titles, descriptions, covers, logos, and Hero Art for games " +
                "you already have.",
            style = MimirTypeScale.SupportCompact,
            color = MimirColors.TextMuted,
        )
        SettingsInfoRow(
            title = "ScreenScraper",
            titleIntent = MimirTextIntent.Authored("ScreenScraper"),
            supporting = "Game descriptions and artwork provider.",
            trailing = {
                StaticStatusChip(
                    text = if (artworkAccount == null) "Not connected" else "Connected",
                    color = if (artworkAccount == null) MimirColors.TextMuted else MimirColors.SuccessGreen,
                )
            },
        )
        if (artworkAccount != null) {
            SettingsRow(
                key = detailKey("appearance/enrichment/screenscraper/download"),
                scope = DetailScope,
                authority = authority,
                title = "Enrichment",
                titleStyle = MimirType.SettingsActionTitle,
                // Settings › Music: while the game-music download holds the shared gate this
                // row says so and offers nothing, the mirror of the Music pane's own row under an artwork download.
                supporting = downloadBlockedCopy ?: ScreenScraperDownloadSupportingCopy,
                onActivate = { onRowActivate("appearance/enrichment/screenscraper/download") },
                trailing = {
                    if (downloadBlockedCopy != null) {
                        StaticStatusChip(text = MusicDownloadBusyChip, color = MimirColors.TextMuted)
                    } else {
                        RowAction("Download")
                    }
                },
            )
        }
        SettingsRow(
            key = detailKey("appearance/enrichment/screenscraper"),
            scope = DetailScope,
            authority = authority,
            title = "Profile",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = screenScraperProfileSupportingCopy(artworkAccount),
            onActivate = { onRowActivate("appearance/enrichment/screenscraper") },
            trailing = { RowAction(if (artworkAccount == null) "Connect" else "Disconnect") },
        )
        // The bulk Hero Motion download was retired here : enrich downloads Hero
        // ART, and motion stays a per-game choice in Customize Media. The row was a library-wide download
        // of ~12 MB clips per title sitting inside the artwork section, which read as part of enrich.
        // Nothing replaces it — a player who wants a moving backdrop picks one on the game itself.
        SettingsInfoRow(
            title = "SteamGridDB",
            titleIntent = MimirTextIntent.Authored("SteamGridDB"),
            supporting = "Optional artwork search provider.",
            trailing = {
                StaticStatusChip(
                    text = if (steamGridDbKeyPresent) "Connected" else "Not connected",
                    color = if (steamGridDbKeyPresent) MimirColors.SuccessGreen else MimirColors.TextMuted,
                )
            },
        )
        SettingsRow(
            key = detailKey("appearance/enrichment/steamgriddb"),
            scope = DetailScope,
            authority = authority,
            title = "Access key",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = "Optional source for game icons, hero art, and platform icons. Your key stays on this device.",
            onActivate = { onRowActivate("appearance/enrichment/steamgriddb") },
            trailing = { RowAction(if (steamGridDbKeyPresent) "Update" else "Connect") },
        )
    }
}

@Composable
private fun BackupSection(authority: FocusAuthority, onRowActivate: (String) -> Unit) {
    SettingsSection(title = "Backup") {
        Text(
            "Export your library setup, folders, emulator choices, and settings as one file.",
            style = MimirTypeScale.SupportCompact,
            color = MimirColors.TextMuted,
        )
        // Each pair of buttons stands the ring's whole reach below its caption, so a focused button's halo clears the line above it.
        Row(modifier = Modifier.padding(top = MimirFocusTileRingReach), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
            GradientCta(
                label = "Export backup",
                key = detailKey("library/backup/export"),
                scope = DetailScope,
                authority = authority,
                onActivate = { onRowActivate("library/backup/export") },
                modifier = Modifier.weight(1f),
            )
            TonalCta(
                label = "Import backup",
                key = detailKey("library/backup/import"),
                scope = DetailScope,
                authority = authority,
                onActivate = { onRowActivate("library/backup/import") },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(MimirSpacing.md))
        Text(
            "Library database + setup. Not included: collections, art, credentials.",
            style = MimirTypeScale.SupportCompact,
            color = MimirColors.TextMuted,
        )
        Row(modifier = Modifier.padding(top = MimirFocusTileRingReach), horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
            GradientCta(
                label = "Export recovery",
                key = detailKey("library/recovery/export"),
                scope = DetailScope,
                authority = authority,
                onActivate = { onRowActivate("library/recovery/export") },
                modifier = Modifier.weight(1f),
            )
            TonalCta(
                label = "Restore recovery",
                key = detailKey("library/recovery/restore"),
                scope = DetailScope,
                authority = authority,
                onActivate = { onRowActivate("library/recovery/restore") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── EMULATORS ──────────────────────────────────────────────────────────────────────────────────
@Composable
private fun EmulatorsPanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    emulatorAssignments: List<EmulatorAssignment>? = null,
    usageAccess: SpecialAccessRow? = null,
) {
    // LIVE installed-emulator detection. Null is a first-emission loading state, never sample data.
    val rows = emulatorAssignments.orEmpty()
    val firstSelectable = firstSelectableEmulatorAssignment(rows)
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        // Shipping order: the Emulators card leads, the Launch-check card follows it.
        SettingsSection(
            // Shipping shows a green "Local only" chip here; posture chips are GOLD by palette semantics (green = per-item
            // success only) — a deliberate, deliberate divergence from shipping parity.
            title = "Emulators",
            trailing = { StaticStatusChip(text = MimirCopy.PostureLocalOnly, color = MimirColors.Gold) },
        ) {
            Text(
                // Canon vocabulary "platform" (memory mimir-content-vocabulary) + shipping's exact blurb.
                "Which app launches each platform. Mimir picks a default — change it any time.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            when {
                emulatorAssignments == null -> SettingsInfoRow(
                    title = "Checking installed emulators…",
                    supporting = "This updates when Android finishes checking your apps.",
                )
                rows.isEmpty() -> SettingsInfoRow(
                    title = "No emulator choices yet",
                    supporting = "Add games to your library to see emulator choices by platform.",
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                    rows.forEachIndexed { i, a ->
                        // EM01: each row shows one of the SEVEN honest route states. A legacy/
                        // sample row (no routeState) maps Ready→dispatch-ready, Needs-emulator→install-needed so
                        // both paths render through the one state grammar.
                        val state = a.routeState ?: if (a.emulator != null) {
                            EmulatorRouteState.DISPATCH_READY
                        } else {
                            EmulatorRouteState.INSTALL_NEEDED
                        }
                        // The focus KEY *is* the routing id — the controller CONFIRM path derives the rowId from the
                        // focused key (SettingsHome.focusedDetailRowId), so key and onActivate MUST agree or the
                        // gamepad path silently no-ops. Live rows carry the platform id → the per-platform picker
                        // (the SAME candidate model Open-With uses — Settings holds no second eligibility truth).
                        // An UNSUPPORTED platform stays visible but is passive: controller focus never lands on it.
                        val rowId = emulatorAssignmentRowId(a, i, firstSelectable)
                        val supporting = buildString {
                            if (a.emulator != null) append("Auto · ${a.emulator} — ")
                            append(state.primaryCopy(a.unsupportedReason, a.recommendedEmulator))
                        }
                        val trailing: @Composable (() -> Unit) = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                            ) {
                                StaticStatusChip(
                                    text = state.chipLabel(),
                                    color = when (state) {
                                        // Certification is THE verified-success verdict — the one green.
                                        EmulatorRouteState.CERTIFIED -> MimirColors.SuccessGreen
                                        EmulatorRouteState.NOT_WIRED -> MimirColors.TextSecondary
                                        EmulatorRouteState.UNSUPPORTED -> MimirColors.TextMuted
                                        else -> MimirColors.Gold
                                    },
                                )
                                when (state) {
                                    EmulatorRouteState.INSTALL_NEEDED -> RowAction("Choose emulator")
                                    EmulatorRouteState.NOT_WIRED -> RowAction("Choose another")
                                    EmulatorRouteState.UNSUPPORTED -> Unit
                                    else -> RowAction("Change")
                                }
                            }
                        }
                        key(a.platformId ?: a.platform) {
                            if (state == EmulatorRouteState.UNSUPPORTED) {
                                SettingsInfoRow(
                                    title = a.platform,
                                    titleIntent = MimirTextIntent.Authored(a.platform),
                                    supporting = supporting,
                                    trailing = trailing,
                                )
                            } else {
                                SettingsRow(
                                    key = detailKey(rowId),
                                    scope = DetailScope,
                                    authority = authority,
                                    title = a.platform,
                                    titleIntent = MimirTextIntent.Authored(a.platform),
                                    supporting = supporting,
                                    onActivate = { onRowActivate(rowId) },
                                    trailing = trailing,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Emulator updates: the opt-in "Check for updates" row. Draws nothing until the app publishes its state.
        EmulatorDefinitionsSection(authority, onRowActivate)
        usageAccess?.let { row ->
            SettingsSection(title = "Launch check") {
                SettingsRow(
                    key = detailKey("emulators/launchCheck/usage"),
                    scope = DetailScope,
                    authority = authority,
                    title = "Usage access",
                    titleStyle = MimirType.SettingsActionTitle,
                    supporting = "Optional. Lets Mimir confirm whether a launched game opened.",
                    onActivate = { onRowActivate("emulators/launchCheck/usage") },
                    trailing = {
                        if (row.granted) {
                            StaticStatusChip(text = "Allowed", color = MimirColors.SuccessGreen)
                        } else {
                            RowAction(row.actionLabel)
                        }
                    },
                )
            }
        }
    }
}

// ── CONTROLS ────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun ControlsPanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    specialAccessRows: List<SpecialAccessRow>? = null,
    controllerShortcutRows: List<ControllerShortcutRow> = emptyList(),
    keyboardPickerEntry: MimirKeyboardPickerEntry? = null,
    keyboardBridgeReady: Boolean = false,
    trackpadSettings: TrackpadSettingsState? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        SettingsSection(title = "Controls") {
            SettingsRow(
                key = detailKey("controls/utility"),
                scope = DetailScope,
                authority = authority,
                title = "Utility",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Brightness, display timeout, lighting, and Mimir button visibility.",
                onActivate = { onRowActivate("controls/utility") },
                trailing = { RowAction("Open") },
            )
        }
        // R6-P3-02 ("make Mimir keyboard default"). Policy 2026-09-15 ("Picker shortcut") had this row only
        // open Android's own keyboard screens or picker; the vendor-bridge path now differs: with a
        // live READY vendor bridge Mimir sets itself as the default (ON by default) and this row is the SWITCH,
        // its state the platform readback. Without the bridge the row keeps the three picker states below. It
        // stays reachable and visible once it IS the default -- the precedent is the "M button permissions"
        // section's own `rows.filterNot { it.id == "m_button" }` loop below (this file), which renders every
        // OTHER SpecialAccessRow, granted or not, as a focusable SettingsRow rather than hiding a granted one.
        keyboardPickerEntry?.let { entry ->
            val row = mimirKeyboardRow(entry, keyboardBridgeReady)
            SettingsSection(title = "Keyboard") {
                SettingsRow(
                    key = detailKey("controls/keyboard"),
                    scope = DetailScope,
                    authority = authority,
                    title = "Mimir Keyboard",
                    titleStyle = MimirType.SettingsActionTitle,
                    supporting = when (row) {
                        is MimirKeyboardRow.Switch ->
                            if (row.on) KeyboardSwitchOnSupporting else KeyboardSwitchOffSupporting
                        is MimirKeyboardRow.Picker -> when (row.entry) {
                            MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS ->
                                "Turn on Mimir Keyboard in Android's keyboard settings."
                            MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER ->
                                "Enabled. Choose it as your default keyboard."
                            MimirKeyboardPickerEntry.ALREADY_DEFAULT ->
                                "Mimir Keyboard is your default keyboard."
                        }
                    },
                    onActivate = { onRowActivate("controls/keyboard") },
                    trailing = {
                        when (row) {
                            is MimirKeyboardRow.Switch -> RowAction(musicToggleLabel(row.on))
                            is MimirKeyboardRow.Picker -> when (row.entry) {
                                MimirKeyboardPickerEntry.OPEN_KEYBOARD_SETTINGS -> RowAction("Open")
                                MimirKeyboardPickerEntry.SHOW_INPUT_METHOD_PICKER -> RowAction("Choose")
                                MimirKeyboardPickerEntry.ALREADY_DEFAULT ->
                                    DynamicValueChip(text = "Default", color = MimirColors.SuccessGreen)
                            }
                        }
                    },
                )
            }
        }
        // D34b: the keyboard trackpad. Present only where the device can host it (API 32+, a second screen). The
        // status row deep-links to Android's Accessibility settings; the switch flips the scroll direction.
        trackpadSettings?.let { trackpad -> TrackpadPanel(authority, onRowActivate, trackpad) }
        SettingsSection(title = "Controller shortcuts") {
            Text(
                "Assign extra handheld or controller macro buttons to open Mimir tools.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            controllerShortcutRows.forEach { row ->
                key(row.id) {
                    SettingsRow(
                        key = detailKey(row.id),
                        scope = DetailScope,
                        authority = authority,
                        title = row.title,
                        titleStyle = MimirType.SettingsActionTitle,
                        supporting = row.supporting,
                        onActivate = { onRowActivate(row.id) },
                        trailing = {
                            if (row.id == ControllerShortcutResetRowId) {
                                RowAction(row.value)
                            } else {
                                DynamicValueChip(
                                    text = row.value,
                                    color = if (row.capturing) MimirColors.Gold else MimirColors.TextSecondary,
                                )
                            }
                        },
                    )
                }
            }
        }
        SettingsSection(title = "Controller mapping") {
            SettingsRow(
                key = detailKey(ControllerMappingLauncherRowId),
                scope = DetailScope,
                authority = authority,
                title = "Map console controls",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Remap every Mimir action. On-screen hints follow your saved controller layout.",
                onActivate = { onRowActivate(ControllerMappingLauncherRowId) },
                trailing = { RowAction("Open") },
            )
        }
        specialAccessRows?.let { rows ->
            SettingsSection(title = "Mimir button permissions") {
                Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm)) {
                    Text(
                        "Android permissions keep the Mimir button available above other apps and allow Tabs to open the app switcher.",
                        style = MimirTypeScale.SupportCompact,
                        color = MimirColors.TextMuted,
                    )
                    rows.filterNot { it.id == "m_button" }.forEach { row ->
                        key(row.id) {
                            if (row.actionable) {
                                SettingsRow(
                                    key = detailKey("controls/special/${row.id}"),
                                    scope = DetailScope,
                                    authority = authority,
                                    title = row.title,
                                    titleStyle = MimirType.SettingsActionTitle,
                                    supporting = row.supporting,
                                    onActivate = { onRowActivate("controls/special/${row.id}") },
                                    trailing = {
                                        if (row.toggleable) {
                                            RowAction(if (row.granted) "Disable" else row.actionLabel)
                                        } else if (row.granted) {
                                            DynamicValueChip(text = row.grantedLabel, color = MimirColors.SuccessGreen)
                                        } else {
                                            RowAction(row.actionLabel)
                                        }
                                    },
                                )
                            } else {
                                SettingsInfoRow(
                                    title = row.title,
                                    supporting = row.supporting,
                                    trailing = {
                                        DynamicValueChip(
                                            text = if (row.granted) row.grantedLabel else row.actionLabel,
                                            color = if (row.granted) MimirColors.SuccessGreen else MimirColors.TextMuted,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── ABOUT ───────────────────────────────────────────────────────────────────────────────────────
@Composable
private fun AboutPanel(authority: FocusAuthority, onRowActivate: (String) -> Unit, appVersion: String) {
    // Local reveal only — the credits page carries no interactive elements, so it needs no focus scope of
    // its own and cannot strand controller traversal while open. The legal reader under it (license, source
    // code, the NOTICE and GPL texts) is ordinary DetailScope rows, and which text is open is local state too.
    var creditsOpen by remember { mutableStateOf(false) }
    var legalOpen by remember { mutableStateOf<LegalDoc?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        SettingsSection(
            title = "About Mimir",
            titleIntent = MimirTextIntent.Mixed(
                listOf(
                    MimirTextFragment.Chrome("About "),
                    MimirTextFragment.Authored("Mimir"),
                ),
            ),
            trailing = { StaticStatusChip(text = "Local-first", color = MimirColors.Gold) },
        ) {
            ReceiptRow(label = "Version", value = appVersion.ifBlank { "Loading version…" })
            Text(
                "Mimir keeps your library on this device. Connected artwork and achievement services are optional. " +
                    "Mimir does not provide games or BIOS files; downloaded artwork comes from the providers you connect.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextSecondary,
            )
            Text(
                "Legal and acknowledgements: third-party names, services, and emulator projects belong to their respective owners.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
        }
        SettingsSection(title = "Credits & data sources") {
            SettingsRow(
                key = detailKey("about/credits/gametdb"),
                scope = DetailScope,
                authority = authority,
                title = "GameTDB",
                titleStyle = MimirType.SettingsActionTitle,
                titleIntent = MimirTextIntent.Authored("GameTDB"),
                supporting = "Game identity data by GameTDB. Visit to add or correct game entries and artwork.",
                onActivate = { onRowActivate("about/credits/gametdb") },
                trailing = { RowAction("Visit") },
            )
            // R6-P3-01: static attribution line for the IGDB last-resort name-search synopsis (Twitch
            // Developer Services Agreement §VII, "appropriately attribute uses of Program Materials").
            // A plain, non-interactive line beside the existing credits — no new screen, no new row kind.
            Text(
                "Game details from IGDB.com",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            SettingsRow(
                key = detailKey(AboutCreditsRowId),
                scope = DetailScope,
                authority = authority,
                title = "Credits & licenses",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = stringResource(R.string.settings_about_credits_supporting),
                onActivate = {
                    creditsOpen = !creditsOpen
                    if (!creditsOpen) legalOpen = null
                },
                trailing = { RowAction(if (creditsOpen) "Close" else "Open") },
            )
            if (creditsOpen) {
                CreditsTomePanel()
                LegalReader(
                    authority = authority,
                    onRowActivate = onRowActivate,
                    open = legalOpen,
                    onOpenChange = { legalOpen = it },
                )
            }
            Spacer(Modifier.height(MimirSpacing.sm))
            Text("ᛗᛁᛗᛁᚱ · THE WELL OF MEMORY", style = MimirType.Eyebrow, color = MimirColors.GoldBright)
            Text("— EOKSADBERG", style = MimirType.Eyebrow, color = MimirColors.GoldBright)
        }
    }
}

// ── APPEARANCE ─────────────────────────────────────────────────────────────────────────────────
/**
 * The Appearance pane: five calm cards, one job
 * each, in display-menu order — the debug-panel Layout wall is DELETED and the two art
 * providers split into their own cards. Two new controls: the Default-theme ACCENT row (curated stops,
 * persisted) and the all-themes FOCUS HIGHLIGHT row (per-theme dialer; one ring colour
 * everywhere incl. the stick LEDs). `appearance/theme` stays row #1 (rail landing + UP
 * contract). Artwork and enrichment providers now belong to Library, leaving this pane focused on
 * visual presentation only.
 */
@Composable
private fun AppearancePanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    backdropValue: String = BackdropValueMedia,
    artworkAccount: String? = null,
    steamGridDbKeyPresent: Boolean = false,
    mimirButtonOff: Boolean = false,
    downloadBlockedCopy: String? = null,
) {
    val currentTheme = com.gruning.mimir.rewrite.core.ui.LocalMimirThemeId.current
    val isDefaultTheme = currentTheme == com.gruning.mimir.rewrite.core.ui.MimirThemeId.DEFAULT
    // Chip honesty (spec §1): "Custom" once ANY of theme / accent stop / focus-ring stop / background / mark moves off stock.
    val isCustom = !isDefaultTheme || MimirAppearanceOverrides.customizes(currentTheme)
    val accentStop = MimirAppearanceOverrides.accentStop
    val accentTemp = MimirAppearanceOverrides.accentTemps[currentTheme] ?: MimirAccentTemp.ORIGINAL
    val ringStop = MimirAppearanceOverrides.ringStops[currentTheme] ?: MimirRingStop.THEME_DEFAULT
    // Concept A : this theme's background and the one Mimir-button mark, committed choices only.
    val ground = MimirAppearanceOverrides.grounds[currentTheme]
    val buttonMark = MimirAppearanceOverrides.buttonMark
    val authoredPalette = MimirThemePalette.forId(currentTheme)
    Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
        // Card 1 — pane identity + the Theme row (must stay row #1 for the rail's default landing).
        SettingsSection(
            title = "Appearance",
            trailing = {
                StaticStatusChip(text = if (isCustom) "Custom" else "Default", color = MimirColors.Gold)
            },
        ) {
            Text(
                AppearanceBlurb,
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextSecondary,
            )
            SettingsRow(
                key = detailKey("appearance/theme"),
                scope = DetailScope,
                authority = authority,
                title = "Theme",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Preview nine themes.",
                onActivate = { onRowActivate("appearance/theme") },
                trailing = {
                    // A protected authored name, not static chrome: preserve “Máni” instead of mechanically
                    // uppercasing it through RowAction.
                    Text(
                        currentTheme.label,
                        style = MimirType.MenuAction,
                        color = MimirColors.Gold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            // Row 2 — Background (concept A; R2: never "Backdrop", which is a game's hero). Atmosphere is the default.
            SettingsRow(
                key = detailKey("appearance/background"),
                scope = DetailScope,
                authority = authority,
                title = "Background",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Behind your shelves. A game’s hero covers it while Game backdrops is on.",
                onActivate = { onRowActivate("appearance/background") },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ground != null) {
                            Box(
                                Modifier
                                    .size(width = 28.dp, height = 18.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MimirColors.SurfaceHigh)
                                    .border(1.dp, MimirColors.Outline, RoundedCornerShape(3.dp)),
                            ) {
                                AppearanceThumbnailImage(ground.path)
                            }
                        }
                        RowAction(if (ground == null) BackgroundDefaultLabel else "Your image")
                    }
                },
            )
            // Row 3 — the Mimir button's LOOK only: permissions stay in Controls, visibility in Utility.
            SettingsRow(
                key = detailKey("appearance/mbutton"),
                scope = DetailScope,
                authority = authority,
                title = "Mimir button",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = if (mimirButtonOff) {
                    "The M you hold for Mimir’s menu. The Mimir button is off."
                } else {
                    "The M you hold for Mimir’s menu."
                },
                onActivate = { onRowActivate("appearance/mbutton") },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarkCoin(path = buttonMark?.path, diameter = MimirButtonRowCoinSize, placement = buttonMark?.markPlacement() ?: MimirMarkPlacement.Default)
                        RowAction(if (buttonMark == null) "Rune" else "Your mark")
                    }
                },
            )
        }
        SettingsSection(title = "Layout") {
            Text(
                "Arrange Mimir for your screen.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            SettingsRow(
                key = detailKey("appearance/layout"),
                scope = DetailScope,
                authority = authority,
                title = "Layout",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Automatic adapts to the window. Compact simplifies smaller screens.",
                onActivate = { onRowActivate("appearance/layout") },
                trailing = {
                    RowAction(MimirViewportSettings.mode.label)
                },
            )
        }
        // The language of Mimir's own text (localization step 0): Automatic, English or Español (Latinoamérica).
        LanguageSection(authority = authority, onRowActivate = onRowActivate)
        // Card 2 — Colour & focus: the two dialers, on EVERY theme (CF01).
        // Default keeps its six curated gold stops; each named theme cools/warms its OWN accent.
        SettingsSection(title = "Color & focus") {
            SettingsRow(
                key = detailKey("appearance/accent"),
                scope = DetailScope,
                authority = authority,
                title = "Accent color",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = if (isDefaultTheme) {
                    "Warm or cool the gold that highlights buttons and picks. " +
                        "Classic Gold is the original."
                } else {
                    "Warm or cool the ${currentTheme.label} accent. Original is the authored look."
                },
                onActivate = { onRowActivate("appearance/accent") },
                trailing = {
                    if (isDefaultTheme) {
                        StopSwatchAction(accentStop.accentBright, accentStop.label)
                    } else {
                        StopSwatchAction(
                            swatch = accentTemp.shift(authoredPalette.accentBright),
                            label = accentTemp.label,
                        )
                    }
                },
            )
            SettingsRow(
                key = detailKey("appearance/focusring"),
                scope = DetailScope,
                authority = authority,
                // CF01: the joystick-LED theme-follow was discarded — the ring copy claims screens only.
                title = "Focus highlight",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "The color of the ring around whatever you’re pointing at.",
                onActivate = { onRowActivate("appearance/focusring") },
                trailing = {
                    StopSwatchAction(
                        swatch = if (ringStop == MimirRingStop.THEME_DEFAULT) {
                            authoredPalette.focusRing
                        } else {
                            ringStop.screenToneFor(authoredPalette)
                        },
                        label = ringStop.label,
                    )
                },
            )
        }
        // Card 3 — the living gamelist backdrop. "Bifröst" appears ONLY in the engine blurb (MimirCopy R16).
        SettingsSection(title = "Game backdrops") {
            Text(
                // Names both hero kinds, because the one switch below governs both. Corrected 2026-09-17:
                // this said "the selected hero", which read as a still and left a player wondering which
                // setting turned the moving one off. There is no such setting — this is it.
                "Bifröst places the game’s hero behind the focused game on Home and in the game list: " +
                    "Hero Motion when you have chosen one in Customize Media, otherwise Hero Art.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
            SettingsRow(
                key = detailKey("appearance/backdrop"),
                scope = DetailScope,
                authority = authority,
                title = "Backdrop",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = "Show the hero behind the focused game, or turn the backdrop off.",
                onActivate = { onRowActivate("appearance/backdrop") },
                trailing = { RowAction(backdropValueLabel(backdropValue)) },
            )
            Text(
                "No hero for a game? Mimir uses the cover, then your theme’s color.",
                style = MimirTypeScale.SupportCompact,
                color = MimirColors.TextMuted,
            )
        }
        EnrichmentSections(
            authority = authority,
            onRowActivate = onRowActivate,
            artworkAccount = artworkAccount,
            steamGridDbKeyPresent = steamGridDbKeyPresent,
            downloadBlockedCopy = downloadBlockedCopy,
        )
    }
}

/** A row action with a leading colour swatch — the accent/focus rows' "{StopName}" + dot trailing. */
@Composable
private fun StopSwatchAction(swatch: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.dp, MimirColors.Outline, CircleShape),
        )
        RowAction(label)
    }
}
