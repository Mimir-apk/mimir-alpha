package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import android.view.KeyEvent
import com.gruning.mimir.rewrite.core.audio.EqualizerPresets
import com.gruning.mimir.rewrite.core.audio.EqualizerReach
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicLibraryView
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.audio.equalizerReach
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import kotlin.math.roundToInt

/**
 * The music panel's pure side: what the engine's state says on each card, which controls take the controller, where the
 * D-pad goes, and when A and B act. No Compose and no Android types beyond key-code constants, so all of it runs on the JVM.
 *
 * The music policy (2026-10-01) applies to the single-display panel; and
 * 2026-10-01 (Nova walk, 18:13): when another player's session and Your music are both there, ONE full card shows (the player that is
 * playing, or, if neither plays, the most recently played) with its own status line and transport, and the other source is one compact
 * switch row under it; the sliders follow once.
 */

/** The two cards the panel can stack. */
internal enum class MusicPanelCard(val source: MusicSource) {
    YOURS(MusicSource.YOUR_MUSIC),
    OTHER(MusicSource.OTHER_PLAYER),
}

internal enum class MusicPanelTransport { PREVIOUS, PLAY_PAUSE, NEXT }

/**
 * The panel's controls. A card's transport keys name the CARD, not the position: when the cards swap places the ring stays on
 * the button it was on, and a press never lands on the other player.
 */
internal enum class MusicPanelControl(val card: MusicPanelCard?, val transport: MusicPanelTransport?) {
    YOURS_PREVIOUS(MusicPanelCard.YOURS, MusicPanelTransport.PREVIOUS),
    YOURS_PLAY_PAUSE(MusicPanelCard.YOURS, MusicPanelTransport.PLAY_PAUSE),
    YOURS_NEXT(MusicPanelCard.YOURS, MusicPanelTransport.NEXT),
    /** The other player's artwork: A (or a tap) opens that player's app. Your music's art does nothing, so it has no control. */
    OTHER_ART(MusicPanelCard.OTHER, null),
    OTHER_PREVIOUS(MusicPanelCard.OTHER, MusicPanelTransport.PREVIOUS),
    OTHER_PLAY_PAUSE(MusicPanelCard.OTHER, MusicPanelTransport.PLAY_PAUSE),
    OTHER_NEXT(MusicPanelCard.OTHER, MusicPanelTransport.NEXT),
    /** The Library chip under Your music's transport: A (or a tap) opens the Artist / Album browser in place of the card's body. Present only while the library has music to browse. */
    BROWSE(MusicPanelCard.YOURS, null),

    /** The browser's list, while it is open: the controller holds focus on the list as a whole and the ring is on the row under the cursor ([MusicBrowseUi.selectedKey]). */
    BROWSE_LIST(null, null),

    /** The compact row under the full card that plays the other source (which pauses the current one). Present only when both sources are there. */
    SWITCH(null, null),
    MEDIA_VOLUME(null, null),
    MUSIC_VOLUME(null, null),

    /** The equalizer preset row under the Music slider: left and right cycle the preset. Present only while Your music is on the panel and the equalizer is on. */
    EQUALIZER(null, null),
}

internal fun musicPanelTransportControl(card: MusicPanelCard, transport: MusicPanelTransport): MusicPanelControl =
    MusicPanelControl.entries.first { it.card == card && it.transport == transport }

internal val MusicPanelScope = FocusScope("music-panel")

internal fun musicPanelFocusKey(control: MusicPanelControl): FocusKey = FocusKey("music-panel/${control.name.lowercase()}")

internal fun musicPanelControlFor(key: FocusKey?): MusicPanelControl? =
    MusicPanelControl.entries.firstOrNull { musicPanelFocusKey(it) == key }

internal const val MusicPanelHeadingYours = "Music on this device"
internal const val MusicPanelHeadingOther = "Now playing"
internal const val MusicPanelMediaLabel = "Game & media"
internal const val MusicPanelMusicLabel = "Music"
internal const val MusicPanelEqualizerLabel = "Equalizer"

/** The switch row's name for another player whose session names no app, and its action word. */
internal const val MusicPanelOtherPlayerName = "Other player"
internal const val MusicPanelSwitchPlay = "Play"


internal const val MusicPanelOpenPlayerLabel = "Open player"

/** The Equalizer row's one line: what it shapes (the one Equalizer is for all sound on the device). The switch and the bands are in Settings › Music. */
internal const val MusicPanelEqualizerSupporting = "Shapes all sound on this device."

/**
 * The Equalizer row's line while the engine is not reaching all sound (another app holds the system equalizer, or Android refused it): Mimir's own
 * player still shapes its music, and the row says that is all it does right now, in the words of the Settings row and the editor's header
 * ([equalizerReach] is [EqualizerReach.MIMIR_ONLY]) rather than claim more.
 */
internal const val MusicPanelEqualizerMimirOnlySupporting = "Music on this device only right now."

/** The Equalizer row's line for [reach]: it never claims more than the engine read back as doing. */
internal fun musicPanelEqualizerSupporting(reach: EqualizerReach): String =
    if (reach == EqualizerReach.MIMIR_ONLY) MusicPanelEqualizerMimirOnlySupporting else MusicPanelEqualizerSupporting

/** The one explanatory line, shown only while the player pushes the Music slider past the Game & media level. */
internal const val MusicCapLine = "Music can't be louder than Game & media."

internal const val MusicHintOtherApp = "Paused for another app. Resumes when it stops."

/** A permanent hold with no notification access: nothing can tell Mimir the other player stopped, so only play resumes it. */
internal const val MusicHintOtherAppNeedsPlay = "Paused for another app. Press play to resume."
internal const val MusicHintScreenOff = "Paused when the screen went off."
internal const val MusicHintHeadphones = "Paused when headphones were unplugged."

/** The music hours (Settings › Music › Music hours) hold the music; a press of play plays it until the next change of hours. */
internal const val MusicHintSchedule = "Paused outside your music hours. Press play to listen anyway."

/**
 * The line at the foot of the panel while Your music is shown and notification access is not held: where to allow seeing other
 * players. Plain text, never a button: the panel is over a game, and Mimir does not open Settings from there.
 */
internal const val MusicPanelAccessHint = "See other players here — allow in Settings › Music"

/**
 * The hint under the status line: why Your music is paused when the player did not do it. A pause by the player says nothing.
 * [needsPlay] is a permanent hold that only the player's play ends ([MusicHintOtherAppNeedsPlay]).
 */
internal fun musicPanelHint(reason: MusicPauseReason?, needsPlay: Boolean = false): String? = when (reason) {
    MusicPauseReason.OTHER_APP -> if (needsPlay) MusicHintOtherAppNeedsPlay else MusicHintOtherApp
    MusicPauseReason.SCREEN_OFF -> MusicHintScreenOff
    MusicPauseReason.HEADPHONES -> MusicHintHeadphones
    MusicPauseReason.SCHEDULE -> MusicHintSchedule
    MusicPauseReason.USER, null -> null
}

/** One player's card: its own status line, hint and transport. */
internal data class MusicPanelCardModel(
    val card: MusicPanelCard,
    /** Chrome text; the caps renderer draws it in capitals. */
    val heading: String,
    /** "Playing — {title}", "Paused — {title}" (the companion card's line), or "Stopped — {title}" once nothing is left to control. */
    val statusLine: String,
    /** True when the title in [statusLine] is a real, authored track title (drawn in its own case); false when it is the bridge's own fallback copy, which stays chrome. */
    val titleIsAuthored: Boolean = true,
    val playing: Boolean,
    /**
     * The line under the status: the artist, the other player's or Your music's own artist tag (the library reads tags after a track has started, so the line
     * can arrive late), and for a Your music track whose tags name none, its source format ("FLAC · 24/96 · 2,304 kbps") once the player has reported it.
     * Null when there is nothing to say.
     */
    val artist: String? = null,
    /** True when [artist] is a real artist name (another player's, or Your music's tag), drawn in its own case; false when it is the format line, which stays chrome. */
    val artistIsAuthored: Boolean = false,
    /** True for another player: its art (or the fallback glyph) is shown as the companion card shows it. For Your music it is true only while the track has embedded art ([withYoursArt]); without art the card has no art seat. */
    val showArtwork: Boolean = false,
    val artwork: Bitmap? = null,
    val artworkTitle: String = "",
    /** The art is still loading: neither the art nor the fallback glyph is drawn until the load has finished ([MusicPanelArtwork]). */
    val artworkPending: Boolean = false,
    /** The art failed to load (R8): drawn exactly as pending is, with neither art, plate nor fallback glyph ([MusicPanelArtwork]). */
    val artworkFailed: Boolean = false,
    /** Tapping the art (or A on it) opens the player's app: another player's card while it can still be opened. */
    val canOpenPlayer: Boolean = false,
    val hint: String? = null,
    val canPrevious: Boolean = false,
    val canPlayPause: Boolean = false,
    val canNext: Boolean = false,
    /** Your music: the library id of the track on air, the key for its embedded art ([withYoursArt]); null for another player. */
    val trackId: String? = null,
    /** Your music's card offers the Library chip: the library has music to browse and the card is not stopped. */
    val canBrowse: Boolean = false,
) {
    fun canDo(transport: MusicPanelTransport): Boolean = when (transport) {
        MusicPanelTransport.PREVIOUS -> canPrevious
        MusicPanelTransport.PLAY_PAUSE -> canPlayPause
        MusicPanelTransport.NEXT -> canNext
    }
}

/**
 * The one compact row under the full card: the OTHER source, by name, with its track under it. A press (A or a tap) plays that source,
 * which pauses the current one (the engine's one-player-sounds rule). Never present beside "Stopped".
 */
internal data class MusicPanelSwitchRow(
    val card: MusicPanelCard,
    /** The row's primary line: the source's name ("Music on this device", or the other app's name). */
    val name: String,
    /** The secondary line: that source's track title, or its state when it names none. Ellipsized by the row. */
    val track: String,
    /** True when [track] is the source's own track title (authored: drawn in its own case), false for the "Playing"/"Paused" state words (chrome: capped). */
    val trackIsTitle: Boolean = false,
    val playing: Boolean,
)

internal data class MusicPanelModel(
    /** The full card: exactly one, never none ([musicPanelFullCard]). */
    val cards: List<MusicPanelCardModel>,
    /** The other source as a compact switch row under the full card, or null when there is only one source (or nothing left to control). */
    val switchRow: MusicPanelSwitchRow? = null,
    /** Game & media (Android's media volume): shown whenever the device has the stream. */
    val showMediaVolume: Boolean = false,
    val mediaFixed: Boolean = false,
    val mediaFraction: Float = 0f,
    /** "FIXED" when the device fixes it, else a percent. */
    val mediaReadout: String = "",
    /** Music (Your music's perceived level): only when Your music is on the panel; another player's audio lives inside Game & media. */
    val showMusicVolume: Boolean = false,
    /** What the Music thumb shows: the player's level, held at the Game & media level while that is lower. */
    val musicFraction: Float = 0f,
    val musicReadout: String = "",
    /** Where the Music track carries its marker (the Game & media level), or null. */
    val capMarker: Float? = null,
    /** The one cap line is on screen. */
    val capLineVisible: Boolean = false,
    /** The cap line's seat is reserved (so the line appearing under a finger does not move the card). */
    val capLineSeat: Boolean = false,
    /** The foot line when Your music is shown without notification access ([MusicPanelAccessHint]); null otherwise. */
    val accessHint: String? = null,
    /** The Equalizer row is on the panel (decided when the panel opened, never toggled while it is open). */
    val showEqualizer: Boolean = false,
    /** The preset the Equalizer row shows ("Rock", "Custom"). */
    val equalizerLabel: String = "",
    /** The Equalizer row's one line: what it reaches right now ([musicPanelEqualizerSupporting]). */
    val equalizerSupporting: String = MusicPanelEqualizerSupporting,
    /** The Artist / Album browser, while it is open: Main draws it in place of the card's body and the levels, the Bottom in its Library sheet. Null while the player is on the card. */
    val browse: MusicBrowseUi? = null,
) {
    val top: MusicPanelCardModel get() = cards.first()

    fun card(which: MusicPanelCard): MusicPanelCardModel? = cards.firstOrNull { it.card == which }
}

internal fun percentReadout(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"

/**
 * The one full card (policy 10-01, Nova 18:13): the only source there is; with both, the one that is playing, and when neither
 * plays (or both do) the most recently played ([MusicControlState.lastPlayed]). The controller decides this when the panel opens or when
 * a card arrives or leaves, and after a press on the switch row; it holds it between, so the card never swaps under a finger.
 */
internal fun musicPanelFullCard(control: MusicControlState): MusicPanelCard {
    val yours = control.yours
    val other = control.other
    return when {
        yours != null && other != null -> when {
            yours.isPlaying && !other.isPlaying -> MusicPanelCard.YOURS
            other.isPlaying && !yours.isPlaying -> MusicPanelCard.OTHER
            control.lastPlayed == MusicSource.OTHER_PLAYER -> MusicPanelCard.OTHER
            else -> MusicPanelCard.YOURS
        }
        other != null -> MusicPanelCard.OTHER
        else -> MusicPanelCard.YOURS
    }
}

/**
 * The one source that is playing while both are there, or null (neither, both, or only one source). The open panel's card follows it
 * ([MusicPanelController]); a tie is not a change, so the card holds.
 */
internal fun musicPanelPlayingCard(control: MusicControlState): MusicPanelCard? {
    val yours = control.yours ?: return null
    val other = control.other ?: return null
    return when {
        yours.isPlaying && !other.isPlaying -> MusicPanelCard.YOURS
        other.isPlaying && !yours.isPlaying -> MusicPanelCard.OTHER
        else -> null
    }
}

/** Whether both sources are there, so a full card and a switch row (not one card alone) are shown. */
internal fun musicPanelHasBoth(control: MusicControlState): Boolean = control.yours != null && control.other != null

private fun yoursSwitchRow(yours: YourMusicState): MusicPanelSwitchRow {
    val title = yours.title?.trim().orEmpty()
    return MusicPanelSwitchRow(
        card = MusicPanelCard.YOURS,
        name = MusicPanelHeadingYours,
        track = title.ifEmpty { if (yours.isPlaying) "Playing" else "Paused" },
        trackIsTitle = title.isNotEmpty(),
        playing = yours.isPlaying,
    )
}

private fun otherSwitchRow(other: OtherPlayerSnapshot): MusicPanelSwitchRow {
    val title = other.title.trim()
    return MusicPanelSwitchRow(
        card = MusicPanelCard.OTHER,
        name = other.appLabel?.trim()?.takeIf { it.isNotEmpty() } ?: MusicPanelOtherPlayerName,
        track = title.ifEmpty { if (other.isPlaying) "Playing" else "Paused" },
        trackIsTitle = title.isNotEmpty() && other.titleIsAuthored,
        playing = other.isPlaying,
    )
}

private fun yoursCardModel(yours: YourMusicState?, stopped: Boolean): MusicPanelCardModel {
    val playing = yours?.isPlaying == true && !stopped
    val title = yours?.title?.trim().orEmpty()
    val label = when {
        stopped -> "Stopped"
        playing -> "Playing"
        else -> "Paused"
    }
    // The tag artist says who made the track; only a track whose tags name none (or that the library has not read yet) says what file it is instead.
    val tagArtist = yours?.artist?.trim()?.takeIf { it.isNotEmpty() }
    return MusicPanelCardModel(
        card = MusicPanelCard.YOURS,
        heading = MusicPanelHeadingYours,
        statusLine = if (title.isEmpty()) label else "$label — $title",
        playing = playing,
        hint = if (yours != null && !stopped && !playing) musicPanelHint(yours.pausedReason, yours.pausedNeedsPlay) else null,
        artist = tagArtist ?: yours?.format?.sourceLine,
        artistIsAuthored = tagArtist != null,
        trackId = yours?.trackId,
        canPrevious = !stopped && yours?.canPrevious == true,
        canPlayPause = !stopped,
        canNext = !stopped && yours?.canNext == true,
    )
}

private fun otherCardModel(other: OtherPlayerSnapshot, stopped: Boolean): MusicPanelCardModel {
    val playing = other.isPlaying && !stopped
    val title = other.title.trim()
    val label = when {
        stopped -> "Stopped"
        playing -> "Playing"
        else -> "Paused"
    }
    return MusicPanelCardModel(
        card = MusicPanelCard.OTHER,
        heading = MusicPanelHeadingOther,
        statusLine = if (title.isEmpty()) label else "$label — $title",
        titleIsAuthored = other.titleIsAuthored,
        playing = playing,
        artist = other.artist?.takeIf { it.isNotBlank() },
        artistIsAuthored = true,
        showArtwork = true,
        artwork = other.artwork,
        artworkTitle = title,
        artworkPending = other.artworkPending && other.artwork == null,
        artworkFailed = other.artworkFailed && other.artwork == null,
        canOpenPlayer = !stopped,
        canPrevious = !stopped && other.canPrevious,
        canPlayPause = !stopped && other.canPlayPause,
        canNext = !stopped && other.canNext,
    )
}

/**
 * The panel as a pure function of what the engine says.
 *
 * [control] is what the panel shows (the controller holds the last real state when playback ends, so the card never empties under
 * the player); [stopped] is true when nothing is left to control, which shows only the top card with its transport disabled.
 * [otherOnTop] is the held card order ([musicPanelOtherOnTop], decided by the controller). [cappedByMedia] and [capPushed] are
 * the engine's cap and the player's push against it. [mediaAvailable] false hides Game & media.
 *
 * The sliders are once for both cards: Game & media always, Music only when Your music is on the panel. The foot line appears
 * when Your music is on the panel and the engine cannot see other players ([MusicControlState.otherAccess] false).
 */
internal fun musicPanelModel(
    control: MusicControlState,
    stopped: Boolean,
    musicVolume: Float,
    mediaVolume: Float,
    mediaAvailable: Boolean,
    mediaFixed: Boolean,
    cappedByMedia: Boolean,
    capPushed: Boolean,
    fullCard: MusicPanelCard = musicPanelFullCard(control),
    equalizer: EqualizerSettings = EqualizerSettings(),
    /** What the global engine honestly reports: with [equalizer], the reach the Equalizer row's line states. */
    systemEq: SystemEqualizerState = SystemEqualizerState(),
    /** The equalizer was on when the panel opened: the row is offered (with Your music on the panel). */
    equalizerShown: Boolean = false,
    /** The library the browser would show: Your music's card offers the Library chip when it holds music. Null (a test's, or no library) offers none. */
    library: MusicLibraryView? = null,
    /** The browser as it is drawn, while it is open. */
    browse: MusicBrowseUi? = null,
): MusicPanelModel {
    // The held card is honoured only while that player is still there (it may have left since the order was decided).
    val shown = when {
        fullCard == MusicPanelCard.OTHER && control.other != null -> MusicPanelCard.OTHER
        fullCard == MusicPanelCard.YOURS && control.yours != null -> MusicPanelCard.YOURS
        else -> musicPanelFullCard(control)
    }
    val card = when (shown) {
        MusicPanelCard.YOURS -> yoursCardModel(control.yours, stopped).let { yours ->
            if (!stopped && library?.tree?.isEmpty == false) yours.copy(canBrowse = true) else yours
        }
        MusicPanelCard.OTHER -> otherCardModel(control.other!!, stopped)
    }
    val switchRow = if (stopped || !musicPanelHasBoth(control)) {
        null
    } else {
        when (shown) {
            MusicPanelCard.YOURS -> otherSwitchRow(control.other!!)
            MusicPanelCard.OTHER -> yoursSwitchRow(control.yours!!)
        }
    }
    // The Music slider and the equalizer are Your music's: on the panel whenever Your music is, as the card or as the switch row.
    val showMusic = shown == MusicPanelCard.YOURS || switchRow?.card == MusicPanelCard.YOURS
    val capped = cappedByMedia && mediaAvailable && showMusic
    val media = mediaVolume.coerceIn(0f, 1f)
    val music = musicVolume.coerceIn(0f, 1f)
    val shownMusic = if (capped) minOf(music, media) else music
    val marker = if (showMusic && mediaAvailable && (capped || capPushed)) media else null
    return MusicPanelModel(
        cards = listOf(card),
        switchRow = switchRow,
        showMediaVolume = mediaAvailable,
        mediaFixed = mediaFixed,
        mediaFraction = media,
        mediaReadout = if (mediaFixed) "FIXED" else percentReadout(media),
        showMusicVolume = showMusic,
        musicFraction = shownMusic,
        musicReadout = percentReadout(shownMusic),
        capMarker = marker,
        capLineVisible = showMusic && mediaAvailable && capPushed,
        capLineSeat = showMusic && mediaAvailable && (capped || capPushed),
        accessHint = MusicPanelAccessHint.takeIf { showMusic && !control.otherAccess },
        showEqualizer = showMusic && equalizerShown,
        equalizerLabel = EqualizerPresets.label(equalizer.presetId),
        equalizerSupporting = musicPanelEqualizerSupporting(equalizerReach(equalizer.enabled, systemEq.status)),
        browse = browse.takeIf { card.canBrowse },
    )
}

/**
 * The controls the controller can reach, row by row: each card's transport row in card order (only the buttons that work; Your music's Library chip, on the heading, above its row), then
 * Game & media (not when the device fixes it: nothing to adjust), then Music. Disabled buttons are drawn but take no ring.
 */
internal fun musicPanelFocusRows(model: MusicPanelModel): List<List<MusicPanelControl>> = if (model.browse != null) {
    // The browser is one control: the list. Up and down move its cursor; B leaves it.
    listOf(listOf(MusicPanelControl.BROWSE_LIST))
} else buildList {
    for (card in model.cards) {
        // The other player's art is a row of its own above its transport: DOWN from it lands on Play/Pause.
        if (card.card == MusicPanelCard.OTHER && card.canOpenPlayer) add(listOf(MusicPanelControl.OTHER_ART))
        // Your music's Library chip is at the right end of the card's heading, above the transport: it is a row of its own, and UP from Play/Pause lands on it.
        if (card.card == MusicPanelCard.YOURS && card.canBrowse) add(listOf(MusicPanelControl.BROWSE))
        add(
            MusicPanelTransport.entries
                .filter { card.canDo(it) }
                .map { musicPanelTransportControl(card.card, it) },
        )
    }
    // The compact switch row sits right under the full card.
    add(if (model.switchRow != null) listOf(MusicPanelControl.SWITCH) else emptyList())
    add(if (model.showMediaVolume && !model.mediaFixed) listOf(MusicPanelControl.MEDIA_VOLUME) else emptyList())
    add(if (model.showMusicVolume) listOf(MusicPanelControl.MUSIC_VOLUME) else emptyList())
    add(if (model.showEqualizer) listOf(MusicPanelControl.EQUALIZER) else emptyList())
}.filter { it.isNotEmpty() }

private val MusicPanelControl.isPlayPause: Boolean get() = transport == MusicPanelTransport.PLAY_PAUSE

/** Where the ring starts: the top card's Play/Pause, or the first control there is when it is not available. */
internal fun musicPanelInitialControl(model: MusicPanelModel): MusicPanelControl? =
    if (model.browse != null) {
        MusicPanelControl.BROWSE_LIST
    } else if (model.top.canPlayPause) {
        musicPanelTransportControl(model.top.card, MusicPanelTransport.PLAY_PAUSE)
    } else {
        musicPanelFocusRows(model).firstOrNull()?.firstOrNull()
    }

/**
 * Where a directional press moves the ring, or null for no move (no wrap, and a slider's left/right adjusts rather than moves,
 * which the controller decides before it asks here). Left and right walk a transport row; up and down walk the rows: the top
 * card's transport, the bottom card's, then Game & media, then Music. Into a transport row the target is its Play/Pause.
 */
internal fun musicPanelNeighbor(
    model: MusicPanelModel,
    from: MusicPanelControl?,
    action: ControllerAction,
): MusicPanelControl? {
    val rows = musicPanelFocusRows(model)
    val rowIndex = rows.indexOfFirst { from in it }
    if (from == null || rowIndex < 0) return musicPanelInitialControl(model)
    val row = rows[rowIndex]
    return when (action) {
        ControllerAction.NAV_LEFT -> row.getOrNull(row.indexOf(from) - 1)
        ControllerAction.NAV_RIGHT -> row.getOrNull(row.indexOf(from) + 1)
        ControllerAction.NAV_DOWN -> rows.getOrNull(rowIndex + 1)?.let { below ->
            // Into a transport row the target is its Play/Pause; between sliders it is the single slider.
            below.firstOrNull { it.isPlayPause } ?: below.first()
        }
        ControllerAction.NAV_UP -> rows.getOrNull(rowIndex - 1)?.let { above ->
            above.firstOrNull { it.isPlayPause } ?: above.first()
        }
        else -> null
    }
}

/**
 * Press and release for the controller (A acts on release; B closes on release and swallows both edges, as the M menu's
 * does). [InputRouter] normalizes a key on its DOWN edge; this gate records which key produced a confirm or a cancel and fires
 * the action on that key's UP, so the release is always consumed here and never reaches the app underneath. The volume keys are
 * handed back (not consumed), so Android adjusts the stream and the Game & media slider follows it.
 *
 * A confirm or cancel that did not come from a key (no key to wait for) acts at once.
 */
internal class MusicPanelKeyGate(
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private var downKey = NoKey
    private var confirmKey = NoKey
    private var cancelKey = NoKey

    /** A confirm or a cancel is armed (its key is down, its release is coming): the card does not move under it. */
    val keyHeld: Boolean get() = downKey != NoKey || confirmKey != NoKey || cancelKey != NoKey

    /** Called by the controller's dispatch, synchronously inside [onKey]'s route, with the action the router produced. */
    fun arm(action: ControllerAction) {
        when (action) {
            ControllerAction.CONFIRM -> when (downKey) {
                RepeatKey -> Unit
                NoKey -> onConfirm()
                else -> confirmKey = downKey
            }
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> when (downKey) {
                RepeatKey -> Unit
                NoKey -> onCancel()
                else -> cancelKey = downKey
            }
            else -> Unit
        }
    }

    /** Returns whether the event is consumed. [route] feeds the router; its result is the answer for every key this gate does not own. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, route: () -> Boolean): Boolean {
        if (isVolumeKey(keyCode)) return false
        if (!down) {
            val confirm = confirmKey == keyCode
            val cancel = cancelKey == keyCode
            // The router still sees the release, so its own pressed-key bookkeeping stays honest; its answer is ours to give.
            val routed = route()
            if (confirm) {
                confirmKey = NoKey
                onConfirm()
                return true
            }
            if (cancel) {
                cancelKey = NoKey
                onCancel()
                return true
            }
            return routed
        }
        downKey = if (repeatCount == 0) keyCode else RepeatKey
        val routed = try {
            route()
        } finally {
            downKey = NoKey
        }
        // A press that armed a confirm or a cancel is this gate's whatever the router answered: its release is coming here.
        return routed || confirmKey == keyCode || cancelKey == keyCode
    }

    fun reset() {
        downKey = NoKey
        confirmKey = NoKey
        cancelKey = NoKey
    }

    private companion object {
        const val NoKey = -1

        /** A held key's repeat: it may move a slider, but it never presses a button again. */
        const val RepeatKey = -2
    }
}

internal fun isVolumeKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

/** The separator [yoursCardModel] and [otherCardModel] put between the state word and the track title in [MusicPanelCardModel.statusLine]. */
private const val MusicPanelStatusSeparator = " — "

/**
 * What the status line is, for the one renderer that draws it (Thor Bottom revamp, ruling 7): the state word ("Playing", "Paused", "Stopped") is
 * chrome and stays capped, and the track title after the dash is authored and is drawn in its own case, as `MimirTextIntent` says authored text
 * is. The line a card model holds is unchanged; this only reads where the chrome ends. The state words never contain the separator.
 * A title the bridge synthesised ("Media unavailable") is [titleIsAuthored] false and the whole line stays chrome, capped as it always was.
 */
internal fun musicPanelStatusIntent(statusLine: String, titleIsAuthored: Boolean = true): MimirTextIntent {
    val at = statusLine.indexOf(MusicPanelStatusSeparator)
    if (at < 0 || !titleIsAuthored) return MimirTextIntent.StaticChrome(statusLine)
    return MimirTextIntent.Mixed(
        listOf(
            MimirTextFragment.Chrome(statusLine.substring(0, at)),
            MimirTextFragment.Authored(statusLine.substring(at)),
        ),
    )
}
