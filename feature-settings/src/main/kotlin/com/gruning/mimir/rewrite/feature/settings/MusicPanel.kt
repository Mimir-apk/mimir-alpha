package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.util.Locale

// ── Row ids. The focus KEY is the routing id (SettingsHome.focusedDetailRowId), so each constant is read by
// the row's key, its onActivate and the coordinator's branch alike, and the three cannot drift apart. ──
const val MusicPlayerEnabledRowId = "music/player/enabled"
const val MusicSourceRowId = "music/player/source"
const val MusicOrderRowId = "music/player/order"
const val MusicVolumeRowId = "music/player/volume"
const val MusicEqualizerRowId = "music/player/equalizer"

/** The row that opens the Behaviour sub-page (update). Its seven switches are [MusicBehaviourRowIds]. */
const val MusicBehaviourRowId = "music/player/behaviour"
const val MusicSongTitlesRowId = "music/player/songtitles"
const val MusicFadeRowId = "music/player/fade"
const val MusicHoursRowId = "music/player/hours"
const val MusicOutsideMimirRowId = "music/player/outside"
const val MusicScreenOffRowId = "music/player/screenoff"
const val MusicUnplugRowId = "music/player/unplug"
const val MusicOtherPlayersRowId = "music/player/otherplayers"
const val MusicFolderRowId = "music/custom/folder"
const val MusicLaunchSoundRowId = "music/game/launchsound"
const val MusicKeepMusicInGamesRowId = "music/game/keepmusic"
const val MusicGameDownloadRowId = "music/game/download"

/**
 * The Music page's ten focusable rows, in pane order (update: it was twenty-two). The pins and the coordinator's dispatch
 * test walk this list. The Behaviour sub-page's seven switches are not on it: they are on [MusicBehaviourRowIds].
 */
val MusicDetailRowIds: List<String> = listOf(
    MusicPlayerEnabledRowId,
    MusicSourceRowId,
    MusicOrderRowId,
    MusicVolumeRowId,
    MusicEqualizerRowId,
    MusicBehaviourRowId,
    MusicFolderRowId,
    MusicLaunchSoundRowId,
    MusicKeepMusicInGamesRowId,
    MusicGameDownloadRowId,
)

/**
 * The Behaviour sub-page's seven switches, in page order. Their ids are the ones they had on the Music page, so the coordinator's
 * command table and the stored settings are untouched.
 */
val MusicBehaviourRowIds: List<String> = listOf(
    MusicSongTitlesRowId,
    MusicFadeRowId,
    MusicHoursRowId,
    MusicOutsideMimirRowId,
    MusicScreenOffRowId,
    MusicUnplugRowId,
    MusicOtherPlayersRowId,
)

/**
 * The focus scope of the Behaviour sub-page. A press on the Behaviour row pushes it over [DetailScope]; the page's rows register
 * in it, and the Music page's rows leave the tree. B (or Back) pops it, and the authority's focus memory lands on the Behaviour
 * row, the one that was focused when the scope was pushed. [MusicPanel] reads the active scope to know which of the two it shows.
 */
val MusicBehaviourScope = FocusScope("settings/music/behaviour")

/** The Forget folder confirmation's two rows (Y on the Music folder row opens it): Cancel first, as on the Library's remove-folder confirmation. */
const val MusicFolderForgetCancelRowId = "music/custom/forget/cancel"
const val MusicFolderForgetConfirmRowId = "music/custom/forget/confirm"
val MusicFolderForgetRowIds: List<String> = listOf(MusicFolderForgetCancelRowId, MusicFolderForgetConfirmRowId)

/**
 * The focus scope of the Forget folder confirmation, pushed over [DetailScope] the way [MusicBehaviourScope] is. The confirmation takes the
 * pane's place, Cancel holds focus first, and B pops the scope, so focus lands back on the Music folder row that was focused when Y was pressed.
 */
val MusicFolderForgetScope = FocusScope("settings/music/forget")

/** The scopes that stand for a second page of the Music pane over [DetailScope]; leaving Settings or the pane pops them. */
val MusicSubPageScopes: Set<FocusScope> = setOf(MusicBehaviourScope, MusicFolderForgetScope)

/** The Game music card's row title (it was "Keep music playing in games"). "Play outside Mimir" on the Behaviour page keeps its own name. */
internal const val MusicKeepInGamesTitle = "Let other music play over games"

/** The row’s line: what it does and what it needs. Off needs no sentence; the value says it. */
internal const val MusicKeepInGamesCopy = "A game you start can’t pause music from other apps. Needs the Tweaker."

/** Order's line: which music it is for. A theme bed is one piece and has no order. */
internal const val MusicOrderCopy = "Applies to Music on this device."

internal const val MusicFadeCopy = "Off plays tracks gapless. On dips the music out and back in over two seconds at each track change."

/** Music hours' line: the window it holds, and what a press of play does outside it. */
internal fun musicHoursCopy(hours: MusicHoursValue): String =
    if (hours == MusicHoursValue.ALL_DAY) {
        "Music can play at any time of day."
    } else {
        "Music plays from ${hours.detail}. Pressing play outside those hours plays it until they change."
    }

/** Play outside Mimir's line: On is the setting where Music on this device plays in games and other apps. */
internal const val MusicOutsideMimirCopy = "Music on this device keeps playing in games and other apps. Off: it plays only while Mimir is in front."

internal const val MusicScreenOffCopy = "Music pauses when the screen goes off and carries on when it comes back. Off: it keeps playing."

internal const val MusicUnplugCopy = "Music pauses when headphones are unplugged and waits for you to press play. Off: it keeps playing from the speaker."

/** The Equalizer row's line while it is off: what it would do. The value says Off; the line never repeats it. */
internal const val MusicEqualizerOffCopy = "Shapes all sound on this device."

/** The Equalizer row's tail while it is on and reaches all sound: the one Equalizer shapes everything on the device . */
internal const val MusicEqualizerAllSoundCopy = "All sound on this device"

/**
 * The Equalizer row's tail while it is on and cannot reach all sound (another app holds the system equalizer, or Android refused it):
 * Mimir's own player still shapes its music, and the row says that is all it does right now rather than claim more. It is "Music on this
 * device" (the Source picker's name for the folder the player plays), not "Mimir's music", which is the Source picker's name for the theme's bed.
 */
internal const val MusicEqualizerMimirOnlyCopy = "Music on this device only right now"

/**
 * The Equalizer row's line (a row's line says what its value does not, fix round on the earlier update): the preset and what it reaches when on,
 * [MusicEqualizerOffCopy] when off.
 */
internal fun musicEqualizerSupportingCopy(model: MusicReadModel): String =
    if (model.equalizerEnabled) {
        "${model.equalizerPresetLabel} · ${if (model.equalizerMimirMusicOnly) MusicEqualizerMimirOnlyCopy else MusicEqualizerAllSoundCopy}"
    } else {
        MusicEqualizerOffCopy
    }

/** The Equalizer row's value: On or Off, the same two words as the Music row. The preset is on the line. */
internal fun musicEqualizerActionLabel(model: MusicReadModel): String = musicToggleLabel(model.equalizerEnabled)

/** The Show other players row's line: what the grant buys, in the pane's voice. */
internal const val MusicOtherPlayersCopy = "Lets the music panel list and control the other apps’ players."

/** The value on the Show other players row: the notification-access grant as the player would say it. */
internal fun musicOtherPlayersValue(granted: Boolean): String = if (granted) "Granted" else "Not allowed"

/**
 * Shown under the row while access is not granted. A sideloaded install (not from a store) has notification access greyed out by
 * Android until the player allows restricted settings for it, and the grey switch gives no reason.
 */
internal const val MusicOtherPlayersRestrictedHelp =
    "If Android blocks it, open Mimir’s App info › ⋮ › Allow restricted settings first."

internal const val MusicFolderSuggestedCopy = "None chosen. Suggested: SD › ROMs › music."
internal const val MusicFolderFormatsCopy = "mp3, m4a, ogg, flac, and wav, in the folder and its subfolders."
internal const val MusicDownloadConnectFirstCopy = "Connect ScreenScraper first."

/** The one line every entry gated by an artwork download carries; its twin is "Game music is downloading." */
internal const val MusicDownloadEnrichRunningCopy = "Artwork is downloading."

/** Total 0 from the engine: not counted yet, or no game to count. One true line for both. */
internal const val MusicDownloadCheckingCopy = "Nothing to download yet."

/** The chip on a row an operation in the other direction has gated. */
internal const val MusicDownloadBusyChip = "Busy"

/**
 * "7 of 12 games have music · 40 requests today, read 12 min ago" -- the batch row's idle line, figures from
 * the engine only. The request count is shown with the age of the read it came from, or as unknown when
 * nothing has read it this run; a fresh figure is an authenticated call the UI must not make, so the line
 * never promises an allowance.
 */
internal fun musicDownloadIdleCopy(have: Int, total: Int, requestsToday: MusicRequestsTodayReadModel?): String = buildString {
    append(have).append(" of ").append(total).append(if (total == 1) " game has music" else " games have music")
    if (requestsToday == null) {
        append(" · requests today unknown")
    } else {
        append(" · ").append(requestsToday.count).append(" requests today, read ").append(requestsToday.ageLabel)
    }
}

/** What the line says before the batch has counted its games (the library read and the account's allowance read come first). */
internal const val MusicDownloadStartingCopy = "Starting…"

/** What the line says from the press on Cancel until the work has stopped; the counts follow on the next line. */
internal const val MusicDownloadStoppingCopy = "Stopping…"

/** The chip that stands where Cancel was while the work stops: the press landed, and there is nothing more to press. */
internal const val MusicDownloadStoppingChip = "Stopping"

/**
 * The running batch's line, true at every moment: "Starting…" before anything is counted, then while a game is being looked up
 * "Looking up 2 of 4 · Game name" with "1 written" under it (the name is already cut to one line by the app), then "3 of 4 · 1 written"
 * between games, and "Stopping…" over the same counts once Cancel is pressed.
 */
internal fun musicDownloadRunningCopy(batch: GameMusicDownloadReadModel.Running): String {
    val counts = "${batch.done} of ${batch.total} · ${batch.written} written"
    return when {
        batch.stopping -> if (batch.total == 0) MusicDownloadStoppingCopy else "$MusicDownloadStoppingCopy\n$counts"
        batch.total == 0 -> MusicDownloadStartingCopy
        batch.currentIndex > 0 ->
            "Looking up ${batch.currentIndex} of ${batch.total}" +
                (batch.current?.let { " · $it" } ?: "") + "\n${batch.written} written"
        else -> counts
    }
}

/**
 * "Done · 5 written · 2 skipped · 1 failed" -- the batch's own tally; a zero figure is dropped, never a lie. A batch the player stopped
 * leads with "Stopped" instead of "Done": it did not finish, and the games it did not reach are still missing. When the failed games
 * share a cause the line after the counts says it ([GameMusicFailureValue.copy]).
 */
internal fun musicDownloadFinishedCopy(batch: GameMusicDownloadReadModel.Finished): String = buildString {
    append(if (batch.stopped) "Stopped · " else "Done · ").append(batch.written).append(" written")
    if (batch.skipped > 0) append(" · ").append(batch.skipped).append(" skipped")
    if (batch.failed > 0) append(" · ").append(batch.failed).append(" failed")
    batch.hint?.let { append('\n').append(it.copy) }
}

/** What the Music row says while the player is off: the one thing Off does. */
internal const val MusicOffCopy = "Off silences the player everywhere."

/** What the Music row says while the player is on and has no track. */
internal const val MusicNothingPlayingCopy = "Nothing playing right now."

/**
 * The Music row's line (update: the Now playing and Output rows folded into it). Off: [MusicOffCopy]. On: the track's title and,
 * on a second line when the player has said what it hands the mixer, "Output: 16-bit · 48 kHz"; with no track, [MusicNothingPlayingCopy].
 * The source is not on this line: the Source row two rows down is its one place (and says why when the theme's bed is sounding
 * instead), so "Tiny 079 · Music on this device" repeated its value. The song-title chip keeps "Title · Source" (it has no Source row
 * beside it). Never a path, never a storage id.
 */
internal fun musicPlayerSupportingCopy(model: MusicReadModel): String = when {
    !model.musicEnabled -> MusicOffCopy
    model.nowPlaying == null -> MusicNothingPlayingCopy
    else -> model.nowPlaying.title +
        (model.outputValue?.let { "\nOutput: $it" } ?: "")
}

/** The test tag of the Music player card's chip, so a test can read the chip itself and not any other text with the same word. */
const val MusicPlayerChipTag = "music-player-chip"

/** The Music player card's chip while the switch is off, while Mimir's music sounds, and while it is on but held or silent (another app, a call, the cable, the screen, the hours, the player's pause, level zero). */
internal const val MusicChipOff = "Off"
internal const val MusicChipPlaying = "Playing"
internal const val MusicChipPaused = "Paused"

/** The pane identity chip's word: the engine's own reading of whether the music sounds ([MusicReadModel.playing]), not the switch alone. */
internal fun musicPlayerChipText(model: MusicReadModel): String = when {
    !model.musicEnabled -> MusicChipOff
    model.playing -> MusicChipPlaying
    else -> MusicChipPaused
}

internal const val MusicSourceDefaultCopy = "Mimir’s music is the theme’s own bed. Music on this device plays from your folder."

/**
 * The Source row's line. When Your music is chosen but cannot play, the player's own reason replaces the explainer, so the
 * row says the theme bed is what is sounding and why, rather than leaving a source that quietly does nothing.
 */
internal fun musicSourceCopy(model: MusicReadModel?): String = model?.customFallbackCopy ?: MusicSourceDefaultCopy

/** "1,234" for the track figure: grouped, and the same on every device so a render and a test agree. */
internal fun musicTrackCountLabel(count: Int): String = String.format(Locale.ROOT, "%,d", count)

/**
 * The folder row's line (update: the Rescan row folded into it): "SD › ROMs › music · 1,234 tracks", "… · Scanning…" while a scan runs,
 * a scan error when the index has one, or the suggestion when no folder is chosen.
 */
internal fun musicFolderCopy(folder: MusicFolderReadModel?): String = when {
    folder == null -> MusicFolderSuggestedCopy
    folder.scanning -> "${folder.friendlyPath} · Scanning…"
    folder.error != null -> "${folder.friendlyPath} · ${folder.error}"
    else -> "${folder.friendlyPath} · ${musicTrackCountLabel(folder.trackCount)} ${if (folder.trackCount == 1) "track" else "tracks"}"
}

/** The Launch sound row's line: its own level, apart from the music volume. */
internal const val MusicLaunchSoundCopy = "Its own level, apart from the music volume."

/** The Behaviour row's value: it opens a page. */
internal const val MusicBehaviourAction = "Open"

/** The Behaviour row's line when nothing on its page is on. */
internal const val MusicBehaviourAllOffCopy = "Everything is off."

/** [text] with its spaces made non-breaking: a line that wraps breaks between names and never inside one. */
private fun noBreak(text: String): String = text.replace(' ', Char(0x00A0))

/**
 * The Behaviour row's line: the switches that are on, by short name, in page order ("Titles · Fade · Screen-off pause"). The names are
 * short on purpose: all seven fit two lines at 1.3x, and each holds its words together with no-break spaces, so a line wraps between
 * names and never inside one. Music hours counts as on when it holds a window.
 */
internal fun musicBehaviourSummary(model: MusicReadModel): String {
    val on = buildList {
        if (model.songTitlesEnabled) add("Titles")
        if (model.fadeBetweenTracks) add("Fade")
        if (model.hours != MusicHoursValue.ALL_DAY) add("Hours")
        if (model.playOutsideMimir) add(noBreak("Outside Mimir"))
        if (model.pauseOnScreenOff) add(noBreak("Screen-off pause"))
        if (model.pauseOnUnplug) add(noBreak("Unplug pause"))
        if (model.otherPlayersAccessGranted) add(noBreak("Other players"))
    }
    return if (on.isEmpty()) MusicBehaviourAllOffCopy else on.joinToString(" · ")
}

/**
 * Settings › Music (reduced from twenty-two rows to ten). Three cards, MUSIC PLAYER, YOUR MUSIC
 * and GAME MUSIC, and a Behaviour sub-page that holds the seven switches. Rows bind the read model and route through
 * [onRowActivate].
 *
 * The page and its sub-pages are one pane. While [MusicBehaviourScope] is the active focus scope the pane shows the Behaviour card
 * with its seven switches in place of the three cards; B pops the scope and the pane shows the page again, focus on the Behaviour
 * row. [MusicFolderForgetScope] does the same for the Forget folder confirmation (Y on the Music folder row), focus back on that row.
 * There is no back chip: B is the way back on Main.
 *
 * Grammar (spec "Grammar decisions"): On/Off rows are toggle-as-row with a [RowAction] value, the `appearance/layout` precedent;
 * Volume opens [MimirVolumeDialOverlay]; Source, Order, Music hours and Launch sound open the select shell.
 */
@Composable
internal fun MusicPanel(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    music: MusicReadModel?,
    /**
     * Fired once when the pane enters. The engine counts which games hold a sound only on request
     * (`MimirArtworkState.refreshGameMusicCounts`), so without this the Download row would read total 0 forever.
     */
    onShown: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onShown() }
    val subPage by rememberMusicSubPage(authority)
    // The three cards and the two pages that take their place swap on the swap tier (audit 2026-10-14, RC-34; `when (subPage)` was a cut both ways). B returns to the
    // cards, so that move is the swap's return. Each page keeps its own scroll when the pane hosts it (see [SettingsSubPageSwap]).
    SettingsSubPageSwap(
        target = subPage,
        returning = subPage == MusicSubPage.NONE,
        label = "settings-music-page",
    ) { page ->
        when (page) {
            MusicSubPage.BEHAVIOUR -> MusicBehaviourCard(authority, onRowActivate, music)
            MusicSubPage.FORGET_FOLDER -> MusicFolderForgetCard(authority, onRowActivate, music)
            MusicSubPage.NONE -> {
                MusicPlayerCard(authority, onRowActivate, music)
                YourMusicCard(authority, onRowActivate, music)
                GameMusicCard(authority, onRowActivate, music)
            }
        }
    }
}

/** Which page of the Music pane is showing: the page of ten rows, or one of the two pages that take its place. */
internal enum class MusicSubPage { NONE, BEHAVIOUR, FORGET_FOLDER }

/**
 * The page showing, read from the authority's active scope ([MusicBehaviourScope], [MusicFolderForgetScope], the page under the detail or the rail), and HELD under any other
 * scope (audit 2026-10-14 W1A #5): Utility, a picker and the select overlays push their scopes on the app's one authority, and read raw those dropped an open sub-page to the
 * cards under the panel and brought it back, on its swap's lead and with its scroll reset, when the panel closed. A derived read, so the caller recomposes when the answer changes
 * and not on every focus move.
 */
@Composable
internal fun rememberMusicSubPage(authority: FocusAuthority): State<MusicSubPage> {
    val focus = authority.state.collectAsState()
    val held = remember { MusicSubPageLatch() }
    return remember(focus) {
        derivedStateOf {
            musicSubPageUnder(focus.value.scope, held.page).also { held.page = it }
        }
    }
}

/** Plain memory, not snapshot state: it holds the last page a Settings scope named, and is read only by the derivation above. */
private class MusicSubPageLatch {
    var page: MusicSubPage = MusicSubPage.NONE
}

/** The page [scope] names: a sub-page's own scope, or the cards under the detail or the rail; [held] under any scope Settings does not own. */
internal fun musicSubPageUnder(scope: FocusScope, held: MusicSubPage): MusicSubPage = when (scope) {
    MusicBehaviourScope -> MusicSubPage.BEHAVIOUR
    MusicFolderForgetScope -> MusicSubPage.FORGET_FOLDER
    DetailScope, SettingsRailScope -> MusicSubPage.NONE
    else -> held
}

/**
 * A section's one-line blurb. It stands the ring's whole reach above the first row, so that row's halo clears the words (the section's own gap is
 * less than the reach).
 */
@Composable
private fun SectionBlurb(text: String) {
    Text(
        text,
        modifier = Modifier.padding(bottom = MimirFocusTileRingReach - MimirSpacing.sm),
        style = MimirTypeScale.SupportCompact,
        color = MimirColors.TextSecondary,
    )
}

@Composable
private fun MusicPlayerCard(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    music: MusicReadModel?,
) {
    SettingsSection(
        title = "Music player",
        trailing = {
            // The pane identity chip: what the player hears right now. Gold when playing (an active state, not a
            // success), muted when off or when it waits (held or silent) -- green stays reserved for success.
            if (music != null) {
                val chip = musicPlayerChipText(music)
                // Tagged so a test reads THIS chip: its word is spoken text, and "Off" is also the word of other rows on the page.
                Box(Modifier.testTag(MusicPlayerChipTag)) {
                    StaticStatusChip(
                        text = chip,
                        color = if (chip == MusicChipPlaying) MimirColors.Gold else MimirColors.TextMuted,
                    )
                }
            }
        },
    ) {
        SectionBlurb("Mimir’s own music under every screen, or your own folder of tracks.")
        val enabled = music?.musicEnabled ?: true
        SettingsRow(
            key = detailKey(MusicPlayerEnabledRowId),
            scope = DetailScope,
            authority = authority,
            title = "Music",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = music?.let(::musicPlayerSupportingCopy) ?: MusicOffCopy,
            swapSupporting = true,
            onActivate = { onRowActivate(MusicPlayerEnabledRowId) },
            trailing = { RowAction(if (music == null) "Loading…" else musicToggleLabel(enabled)) },
        )
        SettingsRow(
            key = detailKey(MusicSourceRowId),
            scope = DetailScope,
            authority = authority,
            title = "Source",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = musicSourceCopy(music),
            onActivate = { onRowActivate(MusicSourceRowId) },
            trailing = { RowAction(music?.source?.label ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicOrderRowId),
            scope = DetailScope,
            authority = authority,
            title = "Order",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicOrderCopy,
            onActivate = { onRowActivate(MusicOrderRowId) },
            trailing = { RowAction(music?.order?.label ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicVolumeRowId),
            scope = DetailScope,
            authority = authority,
            title = "Volume",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = "Also in Utility as Music volume.",
            onActivate = { onRowActivate(MusicVolumeRowId) },
            trailing = { RowAction(music?.let { musicPercentLabel(it.volumePercent) } ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicEqualizerRowId),
            scope = DetailScope,
            authority = authority,
            title = "Equalizer",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = music?.let(::musicEqualizerSupportingCopy) ?: "Loading…",
            swapSupporting = true,
            onActivate = { onRowActivate(MusicEqualizerRowId) },
            trailing = { RowAction(music?.let(::musicEqualizerActionLabel) ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicBehaviourRowId),
            scope = DetailScope,
            authority = authority,
            title = "Behaviour",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = music?.let(::musicBehaviourSummary) ?: "Loading…",
            swapSupporting = true,
            onActivate = { onRowActivate(MusicBehaviourRowId) },
            trailing = { RowAction(MusicBehaviourAction) },
        )
    }
}

/**
 * The Behaviour sub-page: the seven switches that were on the Music page, with the same ids, words and routing. Its rows live in
 * [MusicBehaviourScope], not [DetailScope], which is how the authority knows the page is open and how B finds its way back.
 */
@Composable
private fun MusicBehaviourCard(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    music: MusicReadModel?,
) {
    SettingsSection(title = "Behaviour") {
        SectionBlurb("How the player acts around songs, games, headphones, and the screen.")
        SettingsRow(
            key = detailKey(MusicSongTitlesRowId),
            scope = MusicBehaviourScope,
            authority = authority,
            title = "Song titles",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = "A short chip names each new track.",
            onActivate = { onRowActivate(MusicSongTitlesRowId) },
            trailing = { RowAction(music?.let { musicToggleLabel(it.songTitlesEnabled) } ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicFadeRowId),
            scope = MusicBehaviourScope,
            authority = authority,
            title = "Fade between tracks",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicFadeCopy,
            onActivate = { onRowActivate(MusicFadeRowId) },
            trailing = { RowAction(music?.let { musicToggleLabel(it.fadeBetweenTracks) } ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicHoursRowId),
            scope = MusicBehaviourScope,
            authority = authority,
            title = "Music hours",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = music?.let { musicHoursCopy(it.hours) } ?: "Loading…",
            swapSupporting = true,
            onActivate = { onRowActivate(MusicHoursRowId) },
            trailing = { RowAction(music?.hours?.label ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicOutsideMimirRowId),
            scope = MusicBehaviourScope,
            authority = authority,
            title = "Play outside Mimir",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicOutsideMimirCopy,
            onActivate = { onRowActivate(MusicOutsideMimirRowId) },
            trailing = { RowAction(music?.let { musicToggleLabel(it.playOutsideMimir) } ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicScreenOffRowId),
            scope = MusicBehaviourScope,
            authority = authority,
            title = "Pause when screen is off",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicScreenOffCopy,
            onActivate = { onRowActivate(MusicScreenOffRowId) },
            trailing = { RowAction(music?.let { musicToggleLabel(it.pauseOnScreenOff) } ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicUnplugRowId),
            scope = MusicBehaviourScope,
            authority = authority,
            title = "Pause if headphones unplug",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicUnplugCopy,
            onActivate = { onRowActivate(MusicUnplugRowId) },
            trailing = { RowAction(music?.let { musicToggleLabel(it.pauseOnUnplug) } ?: "Loading…") },
        )
        // The row and the help under it are revealed together: the last row's reveal used to stop at the row and left the line under it cut through by the footer (font 1.3).
        SettingsRowWithNote {
            SettingsRow(
                key = detailKey(MusicOtherPlayersRowId),
                scope = MusicBehaviourScope,
                authority = authority,
                title = "Show other players",
                titleStyle = MimirType.SettingsActionTitle,
                supporting = MusicOtherPlayersCopy,
                onActivate = { onRowActivate(MusicOtherPlayersRowId) },
                trailing = { RowAction(if (music == null) "Loading…" else musicOtherPlayersValue(music.otherPlayersAccessGranted)) },
            )
            if (music != null && !music.otherPlayersAccessGranted) {
                Text(
                    MusicOtherPlayersRestrictedHelp,
                    style = MimirTypeScale.SupportCompact,
                    color = MimirColors.TextMuted,
                )
            }
        }
    }
}

/**
 * The Forget folder confirmation's words: what stops, and that no file is touched. The files are said to be safe ONCE, in the explainer; the
 * rows under it do not say it again ("Forget it in Mimir only" scopes the action and is not a second reassurance).
 */
internal const val MusicFolderForgetTitle = "Forget music folder?"
internal const val MusicFolderForgetExplainerCopy =
    "Mimir will stop playing from this folder. Your music files will not be deleted or moved."
internal const val MusicFolderForgetCancelCopy = "Keep this music folder."
internal const val MusicFolderForgetConfirmCopy = "Forget it in Mimir only."

/**
 * The Forget folder confirmation (Y on the Music folder row): the Library's remove-folder confirmation reused, a title, the folder, one line on what
 * happens, then Cancel and the destructive row, with Cancel first so the safe press is the one focus lands on. The rows carry no value: a title and a
 * value that said the same word twice was unnecessary repetition. It takes the pane's place while [MusicFolderForgetScope] is active.
 */
@Composable
private fun MusicFolderForgetCard(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    music: MusicReadModel?,
) {
    SettingsSection(title = MusicFolderForgetTitle) {
        Text(
            music?.folder?.friendlyPath ?: "Your music folder",
            style = MimirType.RowTitle,
            color = MimirColors.TextPrimary,
        )
        Text(
            MusicFolderForgetExplainerCopy,
            // The first row's ring stands its whole reach above it; the line clears that halo, as a section blurb does.
            modifier = Modifier.padding(bottom = MimirFocusTileRingReach - MimirSpacing.sm),
            style = settingsRowSupportStyle,
            color = MimirColors.TextSecondary,
        )
        SettingsRow(
            key = detailKey(MusicFolderForgetCancelRowId),
            scope = MusicFolderForgetScope,
            authority = authority,
            title = "Cancel",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicFolderForgetCancelCopy,
            onActivate = { onRowActivate(MusicFolderForgetCancelRowId) },
        )
        SettingsRow(
            key = detailKey(MusicFolderForgetConfirmRowId),
            scope = MusicFolderForgetScope,
            authority = authority,
            title = "Forget folder",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicFolderForgetConfirmCopy,
            onActivate = { onRowActivate(MusicFolderForgetConfirmRowId) },
        )
    }
}

@Composable
private fun YourMusicCard(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    music: MusicReadModel?,
) {
    val folder = music?.folder
    SettingsSection(title = "Your music") {
        SectionBlurb(MusicFolderFormatsCopy)
        SettingsRow(
            key = detailKey(MusicFolderRowId),
            scope = DetailScope,
            authority = authority,
            title = "Music folder",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = if (music == null) "Loading…" else musicFolderCopy(folder),
            swapSupporting = true,
            onActivate = { onRowActivate(MusicFolderRowId) },
            trailing = { RowAction(if (folder == null) "Choose" else "Change") },
        )
    }
}

@Composable
private fun GameMusicCard(
    authority: FocusAuthority,
    onRowActivate: (String) -> Unit,
    music: MusicReadModel?,
) {
    SettingsSection(title = "Game music") {
        SectionBlurb("The sound under a game’s launch animation, kept beside its art.")
        SettingsRow(
            key = detailKey(MusicLaunchSoundRowId),
            scope = DetailScope,
            authority = authority,
            title = "Launch sound",
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicLaunchSoundCopy,
            onActivate = { onRowActivate(MusicLaunchSoundRowId) },
            trailing = { RowAction(music?.let { musicPercentLabel(it.launchSound.percent) } ?: "Loading…") },
        )
        SettingsRow(
            key = detailKey(MusicKeepMusicInGamesRowId),
            scope = DetailScope,
            authority = authority,
            title = MusicKeepInGamesTitle,
            titleStyle = MimirType.SettingsActionTitle,
            supporting = MusicKeepInGamesCopy,
            onActivate = { onRowActivate(MusicKeepMusicInGamesRowId) },
            trailing = { RowAction(music?.let { musicToggleLabel(it.keepMusicInGames) } ?: "Loading…") },
        )
        val batch = music?.gameMusic ?: GameMusicDownloadReadModel.Unavailable
        SettingsRow(
            key = detailKey(MusicGameDownloadRowId),
            scope = DetailScope,
            authority = authority,
            title = "Download game music",
            titleStyle = MimirType.SettingsActionTitle,
            titleIntent = MimirTextIntent.StaticChrome("Download game music"),
            supporting = when (batch) {
                is GameMusicDownloadReadModel.Idle -> musicDownloadIdleCopy(batch.have, batch.total, batch.requestsToday)
                is GameMusicDownloadReadModel.Running -> musicDownloadRunningCopy(batch)
                is GameMusicDownloadReadModel.Finished -> musicDownloadFinishedCopy(batch)
                GameMusicDownloadReadModel.NotConnected -> MusicDownloadConnectFirstCopy
                GameMusicDownloadReadModel.EnrichRunning -> MusicDownloadEnrichRunningCopy
                GameMusicDownloadReadModel.Unavailable -> MusicDownloadCheckingCopy
            },
            // The line moves with the batch (a game looked up, Stopping, Stopped): it takes the house value swap, never a snap.
            swapSupporting = true,
            onActivate = { onRowActivate(MusicGameDownloadRowId) },
            trailing = {
                when (batch) {
                    is GameMusicDownloadReadModel.Idle ->
                        if (batch.total > 0 && batch.have >= batch.total) {
                            // Every game has its sound: the one verified-complete state, the one green.
                            StaticStatusChip(text = "Complete", color = MimirColors.SuccessGreen)
                        } else {
                            RowAction("Download")
                        }
                    is GameMusicDownloadReadModel.Running ->
                        if (batch.stopping) {
                            // Plain muted words where CANCEL stood, through the same value swap: never a filled chip .
                            RowAction(MusicDownloadStoppingChip, color = MimirColors.TextMuted)
                        } else {
                            RowAction("Cancel")
                        }
                    // Pressing again resumes the missing ones (spec).
                    is GameMusicDownloadReadModel.Finished -> RowAction("Download")
                    GameMusicDownloadReadModel.NotConnected -> RowAction("Connect")
                    GameMusicDownloadReadModel.EnrichRunning ->
                        StaticStatusChip(text = MusicDownloadBusyChip, color = MimirColors.TextMuted)
                    GameMusicDownloadReadModel.Unavailable -> Unit
                }
            },
        )
        // "Play on highlight" is hidden until it ships (update): it still showed "coming next" copy. Its preference
        // (`AudioPreferences.gameMusicOnHighlight`), its read-model field ([MusicReadModel.playOnHighlight]) and the app's wiring are
        // kept as they were; only the row is gone. When the feature works, a SettingsRow goes back here.
    }
}
