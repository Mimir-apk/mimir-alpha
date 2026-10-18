package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationManagerCompat
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.CustomMusicFallback
import com.gruning.mimir.rewrite.core.audio.EqualizerPresets
import com.gruning.mimir.rewrite.core.audio.EqualizerReach
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.equalizerReach
import com.gruning.mimir.rewrite.core.audio.MimirMusicHours
import com.gruning.mimir.rewrite.core.audio.MimirMusicOrder
import com.gruning.mimir.rewrite.core.audio.MimirMusicSource
import com.gruning.mimir.rewrite.core.audio.MimirNowPlaying
import com.gruning.mimir.rewrite.core.audio.MusicLibraryError
import com.gruning.mimir.rewrite.core.audio.MusicLibraryState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.storage.scan.FriendlyPath
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.GameMusicDownloadReadModel
import com.gruning.mimir.rewrite.feature.settings.GameMusicFailureValue
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetCancelRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderForgetConfirmRowId
import com.gruning.mimir.rewrite.feature.settings.MusicFolderReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicFolderRowId
import com.gruning.mimir.rewrite.feature.settings.MusicEqualizerRowId
import com.gruning.mimir.rewrite.feature.settings.MusicGameDownloadRowId
import com.gruning.mimir.rewrite.feature.settings.MusicBehaviourRowId
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundRowId
import com.gruning.mimir.rewrite.feature.settings.MusicLaunchSoundValue
import com.gruning.mimir.rewrite.feature.settings.MusicNowPlayingReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicFadeRowId
import com.gruning.mimir.rewrite.feature.settings.MusicHoursRowId
import com.gruning.mimir.rewrite.feature.settings.MusicHoursValue
import com.gruning.mimir.rewrite.feature.settings.MusicOrderRowId
import com.gruning.mimir.rewrite.feature.settings.MusicOrderValue
import com.gruning.mimir.rewrite.feature.settings.MusicOtherPlayersRowId
import com.gruning.mimir.rewrite.feature.settings.MusicOutsideMimirRowId
import com.gruning.mimir.rewrite.feature.settings.MusicScreenOffRowId
import com.gruning.mimir.rewrite.feature.settings.MusicUnplugRowId
import com.gruning.mimir.rewrite.feature.settings.MusicPlayerEnabledRowId
import com.gruning.mimir.rewrite.feature.settings.MusicReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicRequestsTodayReadModel
import com.gruning.mimir.rewrite.feature.settings.MusicKeepMusicInGamesRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSongTitlesRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSourceRowId
import com.gruning.mimir.rewrite.feature.settings.MusicSourceValue
import com.gruning.mimir.rewrite.feature.settings.MusicVolumeRowId
import com.gruning.mimir.rewrite.feature.settings.musicVolumeDialSnap
import com.gruning.mimir.rewrite.feature.settings.settingsDetailRowIdForFocusKey
import kotlin.math.roundToInt

/**
 * Settings › Music's app-side seams: the pure
 * mappings between the engine's types (`core-audio`, `GameMusicDownload.kt`) and the pane's read model
 * (`feature-settings`), and the dial's session. Everything here is JVM-testable; the Android calls stay in
 * `MimirSettingsCoordinator` and `MimirSettingsReadCoordinator`.
 */

/** Which volume the dial is moving. The dial's title and subtitle are the row's own words. The Launch sound is a choice of five steps now, not a dial (update). */
internal enum class MusicVolumeDialTarget(val title: String, val subtitle: String) {
    MUSIC("Volume", "Also in Utility as Music volume."),
}

/**
 * An open dial. [levelPercent] is applied LIVE on every detent (there is no preview channel on
 * `AudioPreferences`, unlike the ring's `ringPreview`), so B restores [openedAtPercent] through the same
 * setter, and the player hears each step on the way.
 */
internal data class MusicVolumeDialSession(
    val target: MusicVolumeDialTarget,
    val levelPercent: Int,
    val openedAtPercent: Int,
)

/** A stored 0..1 level as the dial's 0..100 detent. `0.5` is 50; `0.37` snaps to 35. */
internal fun musicVolumePercent(fraction: Float): Int =
    musicVolumeDialSnap((fraction.coerceIn(0f, 1f) * 100f).roundToInt())

/** A dial detent back to the stored 0..1 level. */
internal fun musicVolumeFraction(percent: Int): Float = percent.coerceIn(0, 100) / 100f

internal fun musicSourceValue(source: MimirMusicSource): MusicSourceValue = when (source) {
    MimirMusicSource.MIMIR -> MusicSourceValue.MIMIR
    MimirMusicSource.CUSTOM -> MusicSourceValue.CUSTOM
}

internal fun mimirMusicSource(value: MusicSourceValue): MimirMusicSource = when (value) {
    MusicSourceValue.MIMIR -> MimirMusicSource.MIMIR
    MusicSourceValue.CUSTOM -> MimirMusicSource.CUSTOM
}

internal fun musicOrderValue(order: MimirMusicOrder): MusicOrderValue = when (order) {
    MimirMusicOrder.IN_ORDER -> MusicOrderValue.IN_ORDER
    MimirMusicOrder.SHUFFLE -> MusicOrderValue.SHUFFLE
}

internal fun mimirMusicOrder(value: MusicOrderValue): MimirMusicOrder = when (value) {
    MusicOrderValue.IN_ORDER -> MimirMusicOrder.IN_ORDER
    MusicOrderValue.SHUFFLE -> MimirMusicOrder.SHUFFLE
}

internal fun musicHoursValue(hours: MimirMusicHours): MusicHoursValue = when (hours) {
    MimirMusicHours.ALL_DAY -> MusicHoursValue.ALL_DAY
    MimirMusicHours.DAYTIME -> MusicHoursValue.DAYTIME
    MimirMusicHours.EVENING -> MusicHoursValue.EVENING
    MimirMusicHours.NOT_OVERNIGHT -> MusicHoursValue.NOT_OVERNIGHT
}

internal fun mimirMusicHours(value: MusicHoursValue): MimirMusicHours = when (value) {
    MusicHoursValue.ALL_DAY -> MimirMusicHours.ALL_DAY
    MusicHoursValue.DAYTIME -> MimirMusicHours.DAYTIME
    MusicHoursValue.EVENING -> MimirMusicHours.EVENING
    MusicHoursValue.NOT_OVERNIGHT -> MimirMusicHours.NOT_OVERNIGHT
}

/** The source as the Now playing row and the chip name it: a word, never a storage id or a path. */
internal fun musicSourceLabel(source: MimirMusicSource): String = when (source) {
    MimirMusicSource.MIMIR -> "Mimir"
    MimirMusicSource.CUSTOM -> "Music on this device"
}

/**
 * The engine's now-playing as the row reads it. A theme bed is one track that loops, so A restarts it
 * and the row says "Restart" ([MusicNowPlayingReadModel.singleTrack]); a folder track has a next, unless the folder holds one.
 */
internal fun musicNowPlayingReadModel(nowPlaying: MimirNowPlaying?, formatLine: String? = null): MusicNowPlayingReadModel? =
    nowPlaying?.let {
        MusicNowPlayingReadModel(
            title = it.title,
            source = musicSourceLabel(it.source),
            singleTrack = it.singleTrack,
            formatLine = formatLine,
        )
    }

/** Why Your music is the theme's music right now, in the player's words (shown on the Source row). */
internal fun musicCustomFallbackCopy(reason: CustomMusicFallback): String = when (reason) {
    CustomMusicFallback.NO_FOLDER -> "Playing Mimir’s music until you choose a music folder."
    CustomMusicFallback.EMPTY -> "Playing Mimir’s music: your folder has no tracks Mimir can play."
    CustomMusicFallback.GRANT_LOST -> "Playing Mimir’s music: Mimir can no longer read your folder. Choose it again."
    CustomMusicFallback.UNREADABLE -> "Playing Mimir’s music: your folder couldn’t be read. Try a rescan."
}

/** Your music can be chosen once a folder is chosen, readable, and holds at least one track: the choice is then audible. */
internal fun musicCustomSourceAvailable(library: MusicLibraryState): Boolean =
    library.treeUri != null && library.trackCount > 0 && library.error == null

/**
 * The chosen folder as the player reads it. A `file://` choice (Mimir's own browser) is a plain path to [FriendlyPath], and
 * its URI spells a space as `%20`: the path is decoded for display only (the stored choice is untouched), so the row reads
 * `Cert Artist Delta`, not `Cert%20Artist%20Delta` (cert E7). A system-picker tree is decoded by [FriendlyPath] itself.
 */
internal fun musicFolderFriendlyPath(treeUri: String): String =
    if (treeUri.startsWith("file://")) {
        FriendlyPath.of(decodePercentEncoded(treeUri.removePrefix("file://")))
    } else {
        FriendlyPath.of(treeUri)
    }

internal fun musicLibraryErrorCopy(error: MusicLibraryError): String = when (error) {
    MusicLibraryError.GRANT_LOST -> "Mimir can no longer read this folder. Choose it again."
    MusicLibraryError.UNREADABLE -> "This folder couldn’t be read. Try a rescan."
}

/** "just now" / "5 min ago" / "3 h ago" / "1 day ago" / "2 days ago": how long ago [sinceMillis] was, never negative. */
internal fun musicElapsedLabel(sinceMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - sinceMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "just now"
        hours < 1L -> "$minutes min ago"
        days < 1L -> "$hours h ago"
        days == 1L -> "1 day ago"
        else -> "$days days ago"
    }
}

/** "Scanned just now" / "Scanned 5 min ago" / "Scanned 3 h ago" / "Scanned 2 days ago"; null before a scan. */
internal fun musicLastScanLabel(lastScanMillis: Long?, nowMillis: Long): String? =
    lastScanMillis?.let { "Scanned " + musicElapsedLabel(it, nowMillis) }

/**
 * The account's requests-today count as the last read left it, with its age; null when nothing has read it this
 * run. Only `ArtworkConnectController.lastRequestsTodayReading` feeds this: a fresh read is an authenticated call.
 */
internal fun musicRequestsTodayReadModel(
    reading: ArtworkConnectController.RequestsTodayReading?,
    nowMillis: Long,
): MusicRequestsTodayReadModel? =
    reading?.let { MusicRequestsTodayReadModel(count = it.count, ageLabel = musicElapsedLabel(it.readAtMillis, nowMillis)) }

/** The line every artwork entry carries while the game-music download holds the shared gate. */
internal const val GameMusicDownloadingCopy = "Game music is downloading."

/** The line every game-music entry carries while an artwork download holds the shared gate; the Music pane's own row reads it too. */
internal const val ArtworkDownloadingCopy = "Artwork is downloading."

/** What the Settings Enrichment Download row says instead of offering a start, or null when it may start. */
internal fun artworkDownloadBlockedCopy(operation: ArtworkOperation): String? =
    if (operation == ArtworkOperation.GAME_MUSIC) GameMusicDownloadingCopy else null

/** Whether a per-game artwork entry (Enrich, Get art) may start now; false while the game-music download holds the gate. */
internal fun artworkEntryMayStart(operation: ArtworkOperation): Boolean = operation != ArtworkOperation.GAME_MUSIC

/** Whether a game-music entry (the batch, the per-game lane) may start now; false while an artwork download holds the gate. */
internal fun gameMusicEntryMayStart(operation: ArtworkOperation): Boolean = operation != ArtworkOperation.ENRICH

internal fun musicFolderReadModel(state: MusicLibraryState, nowMillis: Long): MusicFolderReadModel? {
    val treeUri = state.treeUri ?: return null
    return MusicFolderReadModel(
        friendlyPath = musicFolderFriendlyPath(treeUri),
        trackCount = state.trackCount,
        lastScanLabel = musicLastScanLabel(state.lastScanMillis, nowMillis),
        scanning = state.scanning,
        error = state.error?.let(::musicLibraryErrorCopy),
    )
}

/**
 * The batch as the row shows it. A block wins over an idle or finished state (the row is gated and says
 * why); a RUNNING batch is shown running whatever the block says, because Cancel must stay reachable. An
 * idle total of 0 is the engine's "not counted yet, or nothing to count" and renders as neither a figure nor
 * a start.
 *
 * [requestsToday] is the count as the last read left it, with its age, never a fresh read: that is an
 * authenticated call the UI must not make. Null reads "unknown".
 */
internal fun gameMusicDownloadReadModel(
    state: GameMusicDownloadState,
    requestsToday: MusicRequestsTodayReadModel?,
): GameMusicDownloadReadModel {
    if (state is GameMusicDownloadState.Running) return runningReadModel(state)
    return when (state.block) {
        GameMusicBlock.NotConnected -> GameMusicDownloadReadModel.NotConnected
        GameMusicBlock.EnrichRunning -> GameMusicDownloadReadModel.EnrichRunning
        null -> when (state) {
            is GameMusicDownloadState.Idle ->
                if (state.total <= 0) {
                    GameMusicDownloadReadModel.Unavailable
                } else {
                    GameMusicDownloadReadModel.Idle(have = state.have, total = state.total, requestsToday = requestsToday)
                }
            is GameMusicDownloadState.Finished ->
                GameMusicDownloadReadModel.Finished(
                    written = state.written,
                    skipped = state.skipped,
                    failed = state.failed,
                    stopped = state.stopped,
                    hint = state.failure?.let(::gameMusicFailureValue),
                )
            is GameMusicDownloadState.Running -> runningReadModel(state)
        }
    }
}

private fun runningReadModel(state: GameMusicDownloadState.Running) = GameMusicDownloadReadModel.Running(
    done = state.done,
    total = state.total,
    written = state.written,
    currentIndex = state.currentIndex,
    current = state.current,
    stopping = state.stopping,
)

/** The engine's reason as the card words it ([GameMusicFailureValue]), one for one. */
internal fun gameMusicFailureValue(failure: GameMusicFailure): GameMusicFailureValue = when (failure) {
    GameMusicFailure.NO_ANSWER -> GameMusicFailureValue.NO_ANSWER
    GameMusicFailure.DAILY_LIMIT -> GameMusicFailureValue.DAILY_LIMIT
    GameMusicFailure.BUSY -> GameMusicFailureValue.BUSY
    GameMusicFailure.SIGN_IN_REFUSED -> GameMusicFailureValue.SIGN_IN_REFUSED
    GameMusicFailure.BUILD_REFUSED -> GameMusicFailureValue.BUILD_REFUSED
    GameMusicFailure.COULDNT_SAVE -> GameMusicFailureValue.COULDNT_SAVE
}

/** What A does on the Download game music row, from the engine's state alone. */
internal enum class MusicGameDownloadAction { CONNECT, WAIT, CANCEL, START }

internal fun musicGameDownloadAction(state: GameMusicDownloadState): MusicGameDownloadAction = when {
    state is GameMusicDownloadState.Running -> MusicGameDownloadAction.CANCEL
    state.block == GameMusicBlock.NotConnected -> MusicGameDownloadAction.CONNECT
    state.block == GameMusicBlock.EnrichRunning -> MusicGameDownloadAction.WAIT
    else -> MusicGameDownloadAction.START
}

/**
 * X on the Music folder row rescans the folder (update; it forgot the folder before). With none chosen, or on any other row, X is left
 * unrouted, and the footer shows no X hint.
 */
internal fun musicRowSecondaryRescans(canonicalRowId: String?, hasFolder: Boolean): Boolean =
    canonicalRowId == MusicFolderRowId && hasFolder

/**
 * Y on the Music folder row asks to forget the folder (the fix round on removing the old X-forgets key must not drop the capability). It asks
 * first: the Forget folder confirmation opens, and nothing is forgotten until its destructive row is pressed. With none chosen, or on any other row,
 * Y is left unrouted, and the footer shows no Y hint.
 */
internal fun musicRowTertiaryForgets(canonicalRowId: String?, hasFolder: Boolean): Boolean =
    canonicalRowId == MusicFolderRowId && hasFolder

/**
 * The hints the Settings footer adds for the focused row: "X Rescan" and "Y Forget" while the Music folder row is focused and a folder is chosen, the
 * same conditions under which X and Y are claimed ([musicRowSecondaryRescans], [musicRowTertiaryForgets]), so the legend never names a key that does
 * nothing. [focusedRowId] is the focused detail row's id, or null when the rail (or anything else) holds focus.
 */
internal fun musicFooterHints(focusedRowId: String?, hasFolder: Boolean): List<ShellFooterHint> = buildList {
    if (musicRowSecondaryRescans(focusedRowId, hasFolder)) add(ShellFooterHint(token = "X", label = "Rescan"))
    if (musicRowTertiaryForgets(focusedRowId, hasFolder)) add(ShellFooterHint(token = "Y", label = "Forget"))
}

/**
 * The Settings footer's extra hints for the live focus: the focused detail row (the rail, and the Behaviour page, name none) and whether a music folder
 * is chosen. The one function the Settings destination and the render tests both call.
 */
internal fun settingsFooterHints(focus: FocusState, hasFolder: Boolean): List<ShellFooterHint> =
    musicFooterHints(
        focusedRowId = if (focus.scope == DetailScope) settingsDetailRowIdForFocusKey(focus.focused) else null,
        hasFolder = hasFolder,
    )

/**
 * What A does on each Settings › Music row, decided from state alone so the dispatch is a table a JVM test can
 * walk: every id in `MusicDetailRowIds` must map to exactly one of these, and the coordinator only executes.
 */
internal sealed interface MusicRowCommand {
    data object ToggleMusic : MusicRowCommand
    data object OpenSourcePicker : MusicRowCommand

    /** A on Order: In order or Shuffle, on the select shell (the Source picker's overlay slot). */
    data object OpenOrderPicker : MusicRowCommand

    /** A on Music hours: the named windows, on the same shell. */
    data object OpenHoursPicker : MusicRowCommand
    data object ToggleFade : MusicRowCommand
    data object ToggleOutsideMimir : MusicRowCommand
    data object ToggleScreenOffPause : MusicRowCommand
    data object ToggleUnplugPause : MusicRowCommand
    data class OpenVolumeDial(val target: MusicVolumeDialTarget) : MusicRowCommand

    /** A on Equalizer: the editor sheet, hosted beside the volume dial. */
    data object OpenEqualizer : MusicRowCommand
    data object ToggleSongTitles : MusicRowCommand

    /** A on Behaviour: the sub-page of the seven switches, a focus scope pushed over the detail pane. */
    data object OpenBehaviour : MusicRowCommand

    /** A on Show other players: Android's notification-access page for Mimir's listener, from the Activity. */
    data object OpenNotificationAccess : MusicRowCommand
    data object PickFolder : MusicRowCommand

    /** A on Cancel in the Forget folder confirmation: back to the Music folder row, nothing forgotten. */
    data object CancelFolderForget : MusicRowCommand

    /** A on Forget folder in the confirmation: the index lets go of the folder (the files are untouched). */
    data object ConfirmFolderForget : MusicRowCommand

    /** A on Launch sound: the five steps, Off to 100 percent, on the select shell. */
    data object OpenLaunchSoundPicker : MusicRowCommand
    data object ToggleKeepMusicInGames : MusicRowCommand
    data class GameDownload(val action: MusicGameDownloadAction) : MusicRowCommand
}

/** The command for [canonicalRowId], or null when the id is not a Music row (the caller's `when` moves on). */
internal fun musicRowCommand(
    canonicalRowId: String,
    gameMusic: GameMusicDownloadState,
): MusicRowCommand? = when (canonicalRowId) {
    MusicPlayerEnabledRowId -> MusicRowCommand.ToggleMusic
    MusicSourceRowId -> MusicRowCommand.OpenSourcePicker
    MusicOrderRowId -> MusicRowCommand.OpenOrderPicker
    MusicVolumeRowId -> MusicRowCommand.OpenVolumeDial(MusicVolumeDialTarget.MUSIC)
    MusicEqualizerRowId -> MusicRowCommand.OpenEqualizer
    MusicBehaviourRowId -> MusicRowCommand.OpenBehaviour
    MusicSongTitlesRowId -> MusicRowCommand.ToggleSongTitles
    MusicFadeRowId -> MusicRowCommand.ToggleFade
    MusicHoursRowId -> MusicRowCommand.OpenHoursPicker
    MusicOutsideMimirRowId -> MusicRowCommand.ToggleOutsideMimir
    MusicScreenOffRowId -> MusicRowCommand.ToggleScreenOffPause
    MusicUnplugRowId -> MusicRowCommand.ToggleUnplugPause
    MusicOtherPlayersRowId -> MusicRowCommand.OpenNotificationAccess
    MusicFolderRowId -> MusicRowCommand.PickFolder
    MusicFolderForgetCancelRowId -> MusicRowCommand.CancelFolderForget
    MusicFolderForgetConfirmRowId -> MusicRowCommand.ConfirmFolderForget
    MusicLaunchSoundRowId -> MusicRowCommand.OpenLaunchSoundPicker
    MusicKeepMusicInGamesRowId -> MusicRowCommand.ToggleKeepMusicInGames
    MusicGameDownloadRowId -> MusicRowCommand.GameDownload(musicGameDownloadAction(gameMusic))
    else -> null
}

/**
 * What a Launch sound step stores. The toggle is the pair's first half; [volume] is the level to store with it, or null to leave the stored
 * level alone (Off turns the toggle off and keeps the level, so a later step brings the sound back at the step chosen then).
 */
internal data class MusicLaunchSoundWrite(val enabled: Boolean, val volume: Float?)

/** A Launch sound step as the two stored preferences: Off is the toggle off; any other step is the toggle on at that level. */
internal fun musicLaunchSoundWrite(value: MusicLaunchSoundValue): MusicLaunchSoundWrite =
    if (value.enabled) MusicLaunchSoundWrite(enabled = true, volume = musicVolumeFraction(value.percent)) else MusicLaunchSoundWrite(enabled = false, volume = null)

/** The step the two stored preferences read as: Off when the toggle is off, else the nearest of 25 to 100 percent to the stored level. */
internal fun musicLaunchSoundValue(enabled: Boolean, volume: Float): MusicLaunchSoundValue =
    MusicLaunchSoundValue.fromStored(enabled, musicVolumePercent(volume))

/**
 * Makes the stored Launch sound level the level the row shows. The row reads a stored toggle and level as one of five steps ([musicLaunchSoundValue]); the
 * intro plays the stored level exactly, and plays nothing at zero. So a toggle on at a level that is not a step (zero from the old volume row's dial, or 60
 * percent) showed a step it did not play. Run once at startup, after [AudioPreferences.load]: a toggle on at a level off the steps writes the step back
 * (zero becomes 25 percent, the quietest sounding step). A toggle off is Off and is left alone, level and all, and a level already on a step is not written.
 */
internal fun settleLaunchSoundStep(context: Context) {
    val enabled = AudioPreferences.launchSoundEnabled.value
    val stored = AudioPreferences.launchSoundVolume.value
    val step = musicLaunchSoundValue(enabled, stored)
    if (!step.enabled) return
    val level = musicVolumeFraction(step.percent)
    if (stored != level) AudioPreferences.setLaunchSoundVolume(context, level)
}

/** The suggested folder's document id under the library's own tree: `<tree id>/music` (`SD/ROMs/music`). */
internal fun musicFolderSuggestedDocumentId(treeDocumentId: String): String =
    treeDocumentId.trimEnd('/') + "/music"

/**
 * The system picker's starting point: `music` under the primary library tree, when that tree is a SAF
 * document tree. A hint only (`EXTRA_INITIAL_URI`): a folder that does not exist leaves the picker at its
 * own default, and a `file://` or absent primary gives null. Android types, so not under JVM test.
 */
internal fun musicFolderInitialUri(primaryRootUri: String?): Uri? {
    val root = primaryRootUri?.takeIf { it.startsWith("content://") } ?: return null
    return runCatching {
        val tree = Uri.parse(root)
        DocumentsContract.buildDocumentUriUsingTree(
            tree,
            musicFolderSuggestedDocumentId(DocumentsContract.getTreeDocumentId(tree)),
        )
    }.getOrNull()
}

/**
 * Whether Mimir holds notification access (the grant that lets the music panel see other apps' players). The same read the media
 * bridge makes; the Settings pane re-reads it each time the app comes back to the front, because the grant is made on another screen.
 */
internal fun musicOtherPlayersAccessGranted(context: Context): Boolean =
    runCatching { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }.getOrDefault(false)

/** One read model from the engine's flows. Pure, so the dispatch and render tests can build any state. */
internal fun musicReadModel(
    musicEnabled: Boolean,
    nowPlaying: MimirNowPlaying?,
    source: MimirMusicSource,
    order: MimirMusicOrder,
    ambientVolume: Float,
    songTitlesEnabled: Boolean,
    library: MusicLibraryState,
    launchSoundEnabled: Boolean,
    launchSoundVolume: Float,
    gameMusic: GameMusicDownloadState,
    requestsToday: ArtworkConnectController.RequestsTodayReading?,
    playOnHighlight: Boolean,
    keepMusicInGames: Boolean = true,
    nowMillis: Long,
    customFallback: CustomMusicFallback? = null,
    otherPlayersAccessGranted: Boolean = false,
    equalizer: EqualizerSettings = EqualizerSettings(),
    /** What the global engine honestly reports for the Equalizer's switch; the row says "Mimir's music only" while it is not applying. */
    systemEq: SystemEqualizerState = SystemEqualizerState(),
    /** The track Your music has on air, once the player has reported its format; read only while Your music is the sounding source. */
    trackFormat: TrackFormat? = null,
    hours: MimirMusicHours = MimirMusicHours.ALL_DAY,
    fadeBetweenTracks: Boolean = false,
    playOutsideMimir: Boolean = true,
    pauseOnScreenOff: Boolean = true,
    pauseOnUnplug: Boolean = true,
    /** Mimir's music is sounding right now ([MusicControlState.mimirMusicSounding]): the chip says Playing only then, and Paused while it is held or silent. */
    playing: Boolean = false,
): MusicReadModel = MusicReadModel(
    musicEnabled = musicEnabled,
    playing = playing,
    nowPlaying = musicNowPlayingReadModel(nowPlaying, formatLine = musicFormatSource(source, customFallback, trackFormat)?.sourceLine),
    source = musicSourceValue(source),
    customSourceAvailable = musicCustomSourceAvailable(library),
    order = musicOrderValue(order),
    volumePercent = musicVolumePercent(ambientVolume),
    songTitlesEnabled = songTitlesEnabled,
    folder = musicFolderReadModel(library, nowMillis),
    launchSoundEnabled = launchSoundEnabled,
    launchSoundVolumePercent = musicVolumePercent(launchSoundVolume),
    gameMusic = gameMusicDownloadReadModel(gameMusic, musicRequestsTodayReadModel(requestsToday, nowMillis)),
    playOnHighlight = playOnHighlight,
    keepMusicInGames = keepMusicInGames,
    // Only while Your music is chosen: the engine's reason is null otherwise, but the pure model says it too.
    customFallbackCopy = customFallback?.takeIf { source == MimirMusicSource.CUSTOM }?.let(::musicCustomFallbackCopy),
    otherPlayersAccessGranted = otherPlayersAccessGranted,
    equalizerEnabled = equalizer.enabled,
    equalizerMimirMusicOnly = equalizerReach(equalizer.enabled, systemEq.status) == EqualizerReach.MIMIR_ONLY,
    equalizerPresetLabel = EqualizerPresets.label(equalizer.presetId),
    outputValue = musicFormatSource(source, customFallback, trackFormat)?.outputValue,
    fadeBetweenTracks = fadeBetweenTracks,
    hours = musicHoursValue(hours),
    playOutsideMimir = playOutsideMimir,
    pauseOnScreenOff = pauseOnScreenOff,
    pauseOnUnplug = pauseOnUnplug,
)

/**
 * The format of the track on air, but only while Your music is what sounds: a theme bed (the other source, or Your music chosen but
 * falling back to the bed) has no format line and no Output row.
 */
internal fun musicFormatSource(
    source: MimirMusicSource,
    customFallback: CustomMusicFallback?,
    trackFormat: TrackFormat?,
): TrackFormat? = trackFormat.takeIf { source == MimirMusicSource.CUSTOM && customFallback == null }
