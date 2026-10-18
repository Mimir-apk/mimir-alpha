package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.companion.LaunchedEffectWhileLive
import kotlinx.coroutines.awaitCancellation
import com.gruning.mimir.rewrite.feature.companion.collectAsStateWhileLive
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.liquidGlassContentPalette
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl

/*
 * W3e (Y1): the Bottom's full music player, transcribed from YouTube Music's landscape now-playing. It replaces the
 * W3c centred card. Tapping the Activity page's media tile, or the mini player on the shelf, opens it in the page's own swap (CompanionWorkspaceSurface); the host holds
 * whether it is open (CompanionMusicPlayerState) so a trip to the shelf finds it as it was left.
 *
 * From the top: a 48 dp bar (a collapse chevron at the left, the source name on the centre line, the recent-app icon's reserve kept clear at the right); the body (the
 * album art square on the left, as large as fits, ignoring taps; at the right the title, the artist, the seek bar with its times and the metal transport, vertically
 * centred); and a 44 dp tab strip, VOLUME / EQUALIZER / SOURCE, whose tabs raise a bottom sheet over the player holding Mimir's own controls. A drag down anywhere on the
 * player collapses it, as YouTube Music's swipe down does; a drag down on the sheet closes the sheet.
 *
 * It is Main's panel's state and actions (MusicPanelController, the card and its pieces), not a copy. Touch only: no focus authority work (the controller keeps its own
 * because its constructor owns one), `holdPad = false` on the page's swap, never a Compose focus target. W3b's enabled/settle split holds: a button's look is the player's
 * say; the dock's and the strip's settle drop the tap in its callback.
 */

/** The scene's root, and the pieces the render tests measure. */
internal const val CompanionMusicPanelTag = "bottom-music-panel"
internal const val CompanionMusicPanelBandTag = "bottom-music-panel-band"
internal const val CompanionMusicPanelHeadingTag = "bottom-music-panel-heading"
internal const val CompanionMusicPlayerCollapseTag = "bottom-music-player-collapse"
internal const val CompanionMusicPlayerBodyTag = "bottom-music-player-body"
internal const val CompanionMusicPlayerArtTag = "bottom-music-player-art"
internal const val CompanionMusicPlayerColumnTag = "bottom-music-player-column"
internal const val CompanionMusicPlayerTitleTag = "bottom-music-player-title"
internal const val CompanionMusicPlayerTransportTag = "bottom-music-player-transport"
internal const val CompanionMusicPlayerTabStripTag = "bottom-music-player-tabs"
internal const val CompanionMusicPlayerSheetTag = "bottom-music-player-sheet"
internal const val CompanionMusicPlayerSheetGripTag = "bottom-music-player-sheet-grip"
internal const val CompanionMusicPanelMediaRowTag = "bottom-music-panel-media-row"
internal const val CompanionMusicPanelMusicRowTag = "bottom-music-panel-music-row"
internal const val CompanionMusicPanelEqualizerRowTag = "bottom-music-panel-equalizer-row"

/** A tab's own tag, for the render tests. */
internal fun companionMusicPlayerTabTag(tab: MusicPlayerTab): String = "bottom-music-player-tab-${tab.name.lowercase()}"

/** The tab strip's tabs, in YouTube Music's order of "UP NEXT / LYRICS / RELATED" replaced by Mimir's own controls. */
internal enum class MusicPlayerTab(val label: String) {
    /** The Artist / Album browser : only while Your music is the card and the library holds music. */
    LIBRARY("Library"),
    VOLUME("Volume"),
    EQUALIZER("Equalizer"),
    SOURCE("Source"),
}

/** The top bar: the chevron's 48 dp touch minimum. */
internal const val CompanionMusicPlayerBandDp = 48

/** The body's side gutters. */
internal const val CompanionMusicPlayerGutterDp = 24

/** The art: as large as fits, never above this, and never so large that the right column falls under [CompanionMusicPlayerColumnMinDp]. */
internal const val CompanionMusicPlayerArtMaxDp = 264

/** The right column keeps at least this much. */
internal const val CompanionMusicPlayerColumnMinDp = 200

/** Between the art and the column. */
internal const val CompanionMusicPlayerArtGapDp = 24

/** The tab strip. */
internal const val CompanionMusicPlayerTabStripDp = 44

/** The sheet's reach: about 60% of the canvas. */
internal const val CompanionMusicPlayerSheetShare = 0.6f

/** The Library sheet's page, for the render tests. The sheet itself reaches from the tab strip up to the top bar (the whole body), not 60% of the canvas. */
internal const val CompanionMusicPlayerLibraryTestTag = "bottom-music-player-library"

/** A drag down this far (and more down than across) collapses the player or closes the sheet. */
internal const val CompanionMusicPlayerCollapseDragDp = 56

/** The column's gaps: title to artist, artist's line to the seek bar, seek bar to transport. */
internal const val CompanionMusicPlayerTitleGapDp = 4
internal const val CompanionMusicPlayerSeekGapDp = 16
internal const val CompanionMusicPlayerTransportGapDp = 12

/** Between the transport's plates. */
internal const val CompanionMusicPlayerPlateGapDp = 12

/** The scrim the palette lays over the art-derived backdrop. */
internal const val CompanionMusicPlayerScrimAlpha = 0.72f

/** The sheet's glass inset and the grip's height. */
internal const val CompanionMusicPlayerSheetPaddingDp = 12
internal const val CompanionMusicPlayerSheetGripDp = 20

/**
 * The music player scene. [engine] and [mediaVolume] are what Main's panel is built from ([PrimaryMButtonPanelHost]'s own construction): the process's music control and the
 * system's media volume. [onBack] collapses the player to the Activity page's resting layout; the scene writes its own receipt for it first.
 *
 * [bridgeTrack] is the companion bridge's track while the bridge is the engine's top player (the same package): its position, length and seek come from it, and a release
 * of the seek bar calls [onBridgeSeek] through [seekGate]. Your music's come from the engine's own state, and its seek is the engine's.
 *
 * Every control reports through the Bottom's own callbacks: `music-panel.back_to_activity|previous|play_pause|next|switch|equalizer|tab_open|tab_close|seek` on the tap
 * (the seek on the release), and `music-panel.media_volume|music_volume` ONCE on the finger's lift, and only when the finger moved the value. A press while the dock holds
 * navigation off does nothing and writes nothing.
 */
@Composable
internal fun CompanionMusicPanelScene(
    engine: MusicPanelEngine,
    mediaVolume: MediaVolumeGateway,
    navigationEnabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bridgeTrack: CompanionMediaTrack? = null,
    seekGate: CompanionSeekGestureGate = remember { CompanionSeekGestureGate() },
    onBridgeSeek: (Long) -> Unit = {},
    /** The tab whose sheet is open when the scene appears (the render tests'); the player opens with none. */
    initialTab: MusicPlayerTab? = null,
) {
    val palette = LocalMimirPalette.current.liquidGlassContentPalette()
    val control = rememberBottomUiControl(BottomSurface.MUSIC_PANEL)
    val latestEnabled by rememberUpdatedState(navigationEnabled)
    val latestBack by rememberUpdatedState(onBack)
    val latestBridgeSeek by rememberUpdatedState(onBridgeSeek)
    val scope = rememberCoroutineScope()
    // Built as Main builds it (PrimaryMButtonPanelHost): its own authority and scope, so it owns no process-lifetime state. The panel never closes itself
    // (the art's open-player is off on the Bottom and its key gate is never fed), so onClose is the collapse's own path.
    val controller = remember(engine) {
        MusicPanelController(
            engine = engine,
            authority = DefaultFocusAuthority(),
            scope = scope,
            mediaVolume = mediaVolume,
            anchor = null,
            onClose = { latestBack() },
        )
    }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    // D24: a parked pane (the player is open and the workspace has swiped away) keeps this controller but not its work: the engine collection and the follow
    // timer stop with the pane and start again, from the engine's current state, when a drag brings it back.
    LaunchedEffectWhileLive(controller) {
        controller.setLive(true)
        try {
            awaitCancellation()
        } finally {
            controller.setLive(false)
        }
    }
    // The art does not respond to a tap on the Bottom for now : which display the other player's app opens on is still to be ruled.
    val shown = controller.model
    val model = remember(shown) { shown.copy(cards = shown.cards.map { it.copy(canOpenPlayer = false) }) }
    val card = model.top
    val engineState by engine.state.collectAsStateWhileLive()
    // One receipt per lift, and only if the finger MOVED the value: the track's own end fires on every gesture, even a scroll across it, and a tap on the level
    // it already had, or a push the cap refused, is a request that moved nothing. Each gesture remembers where its value started; the end compares.
    val latestModel by rememberUpdatedState(model)
    val mediaStart = remember { floatArrayOf(Float.NaN) }
    val mediaMoved = remember { booleanArrayOf(false) }
    val musicStart = remember { floatArrayOf(Float.NaN) }

    // A sideways drag that starts on a transport button, a tab or the switch row is that control's own, so the workspace strip behind never pans under it. The
    // art, the sliders (they take along-track moves themselves) and the page's empty ground leave the swipe to the strip.
    val controlModifier: @Composable (MusicPanelControl, Boolean) -> Modifier = { which, _ ->
        when (which) {
            MusicPanelControl.OTHER_ART -> Modifier
            MusicPanelControl.MEDIA_VOLUME -> Modifier.testTag(CompanionMusicPanelMediaRowTag)
            MusicPanelControl.MUSIC_VOLUME -> Modifier.testTag(CompanionMusicPanelMusicRowTag)
            MusicPanelControl.EQUALIZER -> Modifier.testTag(CompanionMusicPanelEqualizerRowTag)
            else -> Modifier.companionOwnsHorizontalDrag()
        }
    }
    val onMediaRequested: (Float) -> Unit = { fraction ->
        if (latestEnabled) {
            if (mediaStart[0].isNaN()) mediaStart[0] = BrightnessMath.quantize(BrightnessTarget.MEDIA_VOLUME, latestModel.mediaFraction)
            if (kotlin.math.abs(fraction - mediaStart[0]) > MovedEpsilon) mediaMoved[0] = true
            controller.onMediaRequested(fraction)
        }
    }
    val onMusicRequested: (Float) -> Unit = { fraction ->
        if (latestEnabled) {
            if (musicStart[0].isNaN()) musicStart[0] = BrightnessMath.quantize(BrightnessTarget.AMBIENT_SOUND, engine.musicVolume.value)
            controller.onMusicRequested(fraction)
        }
    }
    val onMusicInteractionEnd: () -> Unit = {
        controller.onMusicInteractionEnd()
        // The engine's own level, read after the request: a push the cap refused left it where it was, a tap on the same level wrote the same one.
        val moved = !musicStart[0].isNaN() && kotlin.math.abs(BrightnessMath.quantize(BrightnessTarget.AMBIENT_SOUND, engine.musicVolume.value) - musicStart[0]) > MovedEpsilon
        musicStart[0] = Float.NaN
        if (moved) control("music-panel.music_volume", null)
    }
    val onMediaInteractionEnd: () -> Unit = {
        val moved = mediaMoved[0]
        mediaMoved[0] = false
        mediaStart[0] = Float.NaN
        if (moved) control("music-panel.media_volume", null)
    }
    val onEqualizerStep: (Int) -> Unit = { direction ->
        if (latestEnabled && controller.onEqualizerStep(direction)) control("music-panel.equalizer", null)
    }

    // The tabs: only the ones with something to show (the Equalizer only while it was on when the panel opened, the Source tab with a second source or the access hint).
    val tabs = buildList {
        if (card.canBrowse) add(MusicPlayerTab.LIBRARY)
        if (model.showMediaVolume || model.showMusicVolume) add(MusicPlayerTab.VOLUME)
        if (model.showEqualizer) add(MusicPlayerTab.EQUALIZER)
        if (model.switchRow != null || model.accessHint != null) add(MusicPlayerTab.SOURCE)
    }
    var openTab by remember { mutableStateOf<MusicPlayerTab?>(initialTab) }
    // The tab the sheet last showed, so its content stays while the sheet leaves.
    val lastTab = remember { arrayOfNulls<MusicPlayerTab>(1) }
    val sheetTab = openTab?.takeIf { it in tabs }
    if (sheetTab != null) lastTab[0] = sheetTab
    val contentTab = sheetTab ?: lastTab[0]
    // The browser is open exactly while its sheet is: the controller keeps where the player was in it, so the sheet opens as it was left.
    LaunchedEffect(sheetTab, controller) {
        if (sheetTab == MusicPlayerTab.LIBRARY) controller.onBrowse() else controller.onBrowseExit()
    }
    // What the browser showed while its sheet was up, so the sheet that is leaving still draws it.
    val lastBrowse = remember { arrayOfNulls<MusicBrowseUi>(1) }
    model.browse?.let { lastBrowse[0] = it }
    fun toggleTab(tab: MusicPlayerTab) {
        if (!latestEnabled) return
        if (openTab == tab) {
            openTab = null
            control("music-panel.tab_close", tab.name.lowercase())
        } else {
            openTab = tab
            control("music-panel.tab_open", tab.name.lowercase())
        }
    }
    fun closeSheet(): Boolean {
        if (openTab == null) return false
        val was = openTab
        openTab = null
        control("music-panel.tab_close", was?.name?.lowercase())
        return true
    }
    fun collapse() {
        if (!latestEnabled) return
        // The chevron and the drag close the sheet first, then the player.
        if (closeSheet()) return
        control("music-panel.back_to_activity", null)
        latestBack()
    }

    // A press acts on the card it began on (the controller's touch tracking, as Main's card keeps it): while a finger is down the controller holds a follow of the
    // playing source, so the card does not move under it; and if the card did change anyway (its source went away), the release does nothing rather than act on the
    // other player. A control reached without a finger (an accessibility click) has no press to compare, so it acts on the card shown.
    val shownSource by rememberUpdatedState(card.card)
    val pressedSource = remember { arrayOfNulls<MusicPanelCard>(1) }
    fun pressStillOnShownCard(): Boolean = pressedSource[0].let { it == null || it == shownSource }

    // The seek bar's source: another player's from the bridge while it is the top player, Your music's from the engine's own state; none when the track has no position.
    val seekSource = remember(card.card, bridgeTrack, engineState.yours) {
        when (card.card) {
            MusicPanelCard.OTHER -> bridgeTrack?.let { musicSeekSourceFor(it) }
            MusicPanelCard.YOURS -> engineState.yours?.let { musicSeekSourceFor(it) }
        }
    }

    // The Library sheet covers the whole body: the player behind it (its title, seek bar and buttons) would show through the glass and sit under the list, so it fades out for as
    // long as the sheet is up and back in as it leaves. The other sheets are short and leave the player as it is.
    val bodyAlpha by animateFloatAsState(
        targetValue = if (sheetTab == MusicPlayerTab.LIBRARY) 0f else 1f,
        animationSpec = tween(if (mimirReducedMotion()) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "music-player-body-under-library",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(palette.surfaceBase.copy(alpha = CompanionMusicPlayerScrimAlpha))
            .testTag(CompanionMusicPanelTag)
            .dragDownToClose(
                enabled = { latestEnabled },
                onClose = {
                    if (latestEnabled) {
                        if (!closeSheet()) {
                            control("music-panel.back_to_activity", null)
                            latestBack()
                        }
                    }
                },
            )
            .pointerInput(controller) {
                // Main's card keeps this too (MusicPanelCard): it tells the controller while a finger is down, so a follow of the playing source waits for the lift and the
                // card never moves under it. It never consumes, so the strip's swipe, the seek bar and every control see the stream as they would without it. The release's
                // own tap is handled in the Main pass, before this Final-pass loop ends and the follow is applied.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressedSource[0] = shownSource
                    controller.onTouchActive(true)
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        pressedSource[0] = null
                        controller.onTouchActive(false)
                    }
                }
            },
    ) {
        val canvasHeight = maxHeight
        val canvasWidth = maxWidth
        // The Library sheet reaches from the tab strip up to the top bar: the whole body.
        val libraryMax = canvasHeight - ActivityPageTopDp.dp - CompanionMusicPlayerBandDp.dp - CompanionMusicPlayerTabStripDp.dp - ActivityDockReserveDp.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = ActivityPageTopDp.dp, bottom = ActivityDockReserveDp.dp),
        ) {
            MusicPlayerTopBand(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ActivityPageInsetDp.dp).testTag(CompanionMusicPanelBandTag),
                collapse = { MusicPlayerCollapseChevron(enabled = navigationEnabled, onCollapse = { collapse() }) },
                heading = {
                    MimirChromeText(
                        model.top.heading,
                        style = MimirTypeScale.RunicEyebrow,
                        color = palette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(CompanionMusicPanelHeadingTag),
                    )
                },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = CompanionMusicPlayerGutterDp.dp)
                    .graphicsLayer { alpha = bodyAlpha }
                    .testTag(CompanionMusicPlayerBodyTag),
            ) {
                // As large as fits: the body's height, the art's own ceiling, and what leaves the column its minimum.
                val artSize = minOf(
                    maxHeight,
                    CompanionMusicPlayerArtMaxDp.dp,
                    (maxWidth - CompanionMusicPlayerArtGapDp.dp - CompanionMusicPlayerColumnMinDp.dp).coerceAtLeast(0.dp),
                )
                // Your music's embedded picture for the track on air, decoded at the size this seat draws it.
                val yoursArt = rememberYourMusicArt(card.trackId, artSize)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(CompanionMusicPlayerArtGapDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(artSize).testTag(CompanionMusicPlayerArtTag)) {
                        // The panel's art law ([MusicPanelArtwork]); Your music shows its track's embedded picture, and a track with none the plate and its glyph. It is not a tap target on the Bottom.
                        MusicPanelArtwork(
                            model = card.withYoursArt(yoursArt).copy(artworkTitle = card.artworkTitle.ifBlank { musicPlayerTitle(card) }),
                            palette = palette,
                            focused = false,
                            authority = null,
                            controlModifier = Modifier,
                            onOpen = {},
                            onPlaced = { _, _ -> },
                            artSize = artSize,
                            verticalPadding = 0.dp,
                            // The ruled fit, as the Activity tile's book: a title the seat cannot hold whole breaks at a syllable hyphen or ends in an ellipsis, never mid-word.
                            fitTitle = true,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).testTag(CompanionMusicPlayerColumnTag),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val title = musicPlayerTitle(card)
                        MimirIntentText(
                            if (card.titleIsAuthored) MimirTextIntent.Authored(title) else MimirTextIntent.StaticChrome(title),
                            style = MimirTypeScale.Title,
                            color = palette.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().testTag(CompanionMusicPlayerTitleTag),
                        )
                        card.artist?.let { artist ->
                            Spacer(Modifier.height(CompanionMusicPlayerTitleGapDp.dp))
                            MimirIntentText(
                                if (card.artistIsAuthored) MimirTextIntent.Authored(artist) else MimirTextIntent.StaticChrome(artist),
                                style = MimirTypeScale.Row,
                                color = palette.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Playing and Paused are the Play plate's own face; a stopped player says so, since its plates are all unavailable.
                        musicPlayerStoppedWord(card)?.let { word ->
                            Spacer(Modifier.height(CompanionMusicPlayerTitleGapDp.dp))
                            MimirChromeText(
                                word,
                                style = MimirTypeScale.ControlHintCompact,
                                color = palette.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        card.hint?.let { hint ->
                            Spacer(Modifier.height(CompanionMusicPlayerTitleGapDp.dp))
                            MimirChromeText(
                                hint,
                                style = MimirTypeScale.ControlHintCompact,
                                color = palette.textMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(CompanionMusicPlayerSeekGapDp.dp))
                        MusicSeekBar(
                            source = seekSource,
                            enabled = navigationEnabled,
                            seekGate = seekGate,
                            onSeek = { position ->
                                if (latestEnabled) {
                                    control("music-panel.seek", null)
                                    when (card.card) {
                                        MusicPanelCard.OTHER -> latestBridgeSeek(position)
                                        MusicPanelCard.YOURS -> engine.seekYourMusic(position)
                                    }
                                }
                            },
                        )
                        Spacer(Modifier.height(CompanionMusicPlayerTransportGapDp.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag(CompanionMusicPlayerTransportTag),
                            horizontalArrangement = Arrangement.spacedBy(CompanionMusicPlayerPlateGapDp.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val previous = musicPanelTransportControl(card.card, MusicPanelTransport.PREVIOUS)
                            val playPause = musicPanelTransportControl(card.card, MusicPanelTransport.PLAY_PAUSE)
                            val next = musicPanelTransportControl(card.card, MusicPanelTransport.NEXT)
                            MediaButton(
                                label = "Previous",
                                enabled = card.canPrevious,
                                skipTargetSize = MediaHubSkipTargetDp,
                                skipPlateSize = MediaHubSkipPlateDp,
                                modifier = controlModifier(previous, card.canPrevious),
                                pressResponse = true,
                                onClick = {
                                    if (latestEnabled && pressStillOnShownCard()) {
                                        control("music-panel.previous", null)
                                        controller.onPrevious(card.card)
                                    }
                                },
                            )
                            MediaButton(
                                label = if (card.playing) "Pause" else "Play",
                                enabled = card.canPlayPause,
                                primary = true,
                                primarySize = MediaHubPlayDp,
                                modifier = controlModifier(playPause, card.canPlayPause),
                                pressResponse = true,
                                onClick = {
                                    if (latestEnabled && pressStillOnShownCard()) {
                                        control("music-panel.play_pause", null)
                                        controller.onPlayPause(card.card)
                                    }
                                },
                            )
                            MediaButton(
                                label = "Next",
                                enabled = card.canNext,
                                skipTargetSize = MediaHubSkipTargetDp,
                                skipPlateSize = MediaHubSkipPlateDp,
                                modifier = controlModifier(next, card.canNext),
                                pressResponse = true,
                                onClick = {
                                    if (latestEnabled && pressStillOnShownCard()) {
                                        control("music-panel.next", null)
                                        controller.onNext(card.card)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (tabs.isNotEmpty()) {
                MusicPlayerTabStrip(
                    tabs = tabs,
                    active = sheetTab,
                    onTab = { toggleTab(it) },
                    modifier = Modifier.fillMaxWidth().height(CompanionMusicPlayerTabStripDp.dp).testTag(CompanionMusicPlayerTabStripTag),
                )
            }
        }
        // The sheet: up from the tab strip over the player, about 60% of the canvas at most.
        val reduced = mimirReducedMotion()
        val ms = if (reduced) 0 else MimirMotion.OverlayMs
        AnimatedVisibility(
            visible = sheetTab != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = CompanionMusicPlayerGutterDp.dp,
                    end = CompanionMusicPlayerGutterDp.dp,
                    bottom = ActivityDockReserveDp.dp + CompanionMusicPlayerTabStripDp.dp,
                ),
            enter = fadeIn(tween(ms, easing = MimirMotion.Easing)) + slideInVertically(tween(ms, easing = MimirMotion.Easing)) { it / 6 },
            exit = fadeOut(tween(ms, easing = MimirMotion.Easing)) + slideOutVertically(tween(ms, easing = MimirMotion.Easing)) { it / 6 },
        ) {
            MimirLiquidGlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (contentTab == MusicPlayerTab.LIBRARY) libraryMax else canvasHeight * CompanionMusicPlayerSheetShare)
                    .testTag(CompanionMusicPlayerSheetTag)
                    .closeOnDragDown(enabled = { latestEnabled }, onClose = { closeSheet() }),
                contentPadding = CompanionMusicPlayerSheetPaddingDp.dp,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                radius = MimirSpacing.radiusCard,
                gradientBody = false,
            ) {
                // The grip: a small bar at the top, the sheet's own handle.
                Box(
                    Modifier.fillMaxWidth().height(CompanionMusicPlayerSheetGripDp.dp).testTag(CompanionMusicPlayerSheetGripTag),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).background(palette.textMuted.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                }
                if (contentTab == MusicPlayerTab.LIBRARY) {
                    // The browser is a list of its own (it scrolls itself), so it sits beside the grip rather than inside the sheet's scroll column.
                    lastBrowse[0]?.let { ui ->
                        MusicLibraryBrowserPage(
                            ui = ui,
                            palette = palette,
                            ringFocused = false,
                            onPress = { key -> if (latestEnabled) controller.onBrowsePress(key) },
                            onBack = { if (latestEnabled && !controller.onBrowseBack()) closeSheet() },
                            modifier = Modifier.weight(1f).testTag(CompanionMusicPlayerLibraryTestTag),
                            touch = true,
                            controlModifier = Modifier.companionOwnsHorizontalDrag(),
                        )
                    }
                } else Column(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (contentTab) {
                        MusicPlayerTab.VOLUME -> {
                            MusicPanelMediaVolumeRow(
                                model = model,
                                focused = null,
                                palette = palette,
                                controlModifier = controlModifier,
                                onRequestFocus = {},
                                onMediaRequested = onMediaRequested,
                                onMediaInteractionEnd = onMediaInteractionEnd,
                            )
                            MusicPanelMusicVolumeRow(
                                model = model,
                                focused = null,
                                palette = palette,
                                controlModifier = controlModifier,
                                onRequestFocus = {},
                                onMusicRequested = onMusicRequested,
                                onMusicInteractionEnd = onMusicInteractionEnd,
                            )
                            MusicPanelCapLine(model = model, palette = palette)
                        }
                        MusicPlayerTab.EQUALIZER -> MusicPanelEqualizerRow(
                            model = model,
                            focused = null,
                            palette = palette,
                            controlModifier = controlModifier,
                            onRequestFocus = {},
                            onEqualizerStep = onEqualizerStep,
                            pressResponse = true,
                        )
                        MusicPlayerTab.SOURCE -> {
                            MusicPanelSwitchRowSlot(
                                model = model,
                                focused = null,
                                palette = palette,
                                controlModifier = controlModifier,
                                onRequestFocus = {},
                                onSwitchSource = {
                                    if (latestEnabled) {
                                        control("music-panel.switch", null)
                                        controller.onSwitchSource()
                                    }
                                },
                                pressResponse = true,
                                topPadding = 0.dp,
                            )
                            MusicPanelAccessHint(model = model, palette = palette)
                        }
                        MusicPlayerTab.LIBRARY, null -> Unit
                    }
                }
            }
        }
    }
}

/** The shown card's title: the track, without the state word the status line leads with ("Playing — Track"). */
internal fun musicPlayerTitle(card: MusicPanelCardModel): String = card.statusLine.substringAfter(" — ", card.statusLine)

/** "Stopped" when the card's state word is that and a title stands beside it (with no title the title line already is the word); null otherwise. */
internal fun musicPlayerStoppedWord(card: MusicPanelCardModel): String? =
    card.statusLine.substringBefore(" — ", "").takeIf { it == "Stopped" }

/**
 * The top bar: the collapse chevron at the left, the heading on the page's centre line, and a right end that mirrors the chevron. That right end is at least
 * [ActivityRecentAppReserveDp] (the recent-app icon the Presentation lays over the page's top end), so the heading, which gets only what is left between the two equal
 * ends, never runs under the icon; a longer heading is cut with an ellipsis, a larger font grows the ends together.
 */
@Composable
private fun MusicPlayerTopBand(modifier: Modifier, collapse: @Composable () -> Unit, heading: @Composable () -> Unit) {
    Layout(
        content = {
            collapse()
            heading()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val bandPx = CompanionMusicPlayerBandDp.dp.roundToPx()
        val reservePx = ActivityRecentAppReserveDp.dp.roundToPx()
        val chevron = measurables[0].measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
        val end = maxOf(chevron.width, reservePx)
        val headingMax = (constraints.maxWidth - 2 * end).coerceAtLeast(0)
        val headingPlaceable = measurables[1].measure(Constraints(maxWidth = headingMax, maxHeight = constraints.maxHeight))
        val width = constraints.maxWidth
        val height = maxOf(bandPx, chevron.height, headingPlaceable.height)
        layout(width, height) {
            chevron.placeRelative(0, (height - chevron.height) / 2)
            headingPlaceable.placeRelative((width - headingPlaceable.width) / 2, (height - headingPlaceable.height) / 2)
        }
    }
}

/** The collapse chevron, YouTube Music's down arrow: a 48 dp target (the press response on it), a thin down-pointing chevron in the primary text colour. */
@Composable
private fun MusicPlayerCollapseChevron(enabled: Boolean, onCollapse: () -> Unit) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .testTag(CompanionMusicPlayerCollapseTag)
            .companionOwnsHorizontalDrag()
            .size(MimirSpacing.minTarget)
            .mimirActivatable(
                label = "Collapse player",
                contentDescription = "Collapse player",
                enabled = enabled,
                interactionSource = source,
                onActivate = onCollapse,
            )
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 20.dp, height = 12.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path().apply {
                moveTo(0f + stroke.width / 2f, stroke.width / 2f)
                lineTo(size.width / 2f, size.height - stroke.width / 2f)
                lineTo(size.width - stroke.width / 2f, stroke.width / 2f)
            }
            drawPath(path, palette.textPrimary, style = stroke)
        }
    }
}

/** The tab strip: all caps like YouTube Music's "UP NEXT  LYRICS  RELATED", the secondary colour, the active tab in the primary. */
@Composable
private fun MusicPlayerTabStrip(tabs: List<MusicPlayerTab>, active: MusicPlayerTab?, onTab: (MusicPlayerTab) -> Unit, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { tab ->
            MusicPlayerTabItem(tab = tab, active = tab == active, onTap = { onTab(tab) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MusicPlayerTabItem(tab: MusicPlayerTab, active: Boolean, onTap: () -> Unit, modifier: Modifier) {
    val palette = LocalMimirPalette.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(companionMusicPlayerTabTag(tab))
            .companionOwnsHorizontalDrag()
            .mimirActivatable(
                label = tab.label,
                contentDescription = "${tab.label} tab",
                interactionSource = source,
                onActivate = onTap,
            )
            .mimirPressResponse(source),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            tab.label,
            style = MimirTypeScale.MenuAction,
            color = if (active) palette.textPrimary else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A drag down that ends this far below where it began, and more down than across, calls [onClose]. It watches in the Main pass without consuming and ignores a gesture
 * that anything under it has consumed (the seek bar claims its own, a slider its along-track drags, a list its scroll), so it never takes a finger a control already has.
 */
private fun Modifier.dragDownToClose(enabled: () -> Boolean, onClose: () -> Unit): Modifier = pointerInput(Unit) {
    val threshold = CompanionMusicPlayerCollapseDragDp.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (change.isConsumed) return@awaitEachGesture
            if (!change.pressed) {
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (enabled() && dy >= threshold && dy > kotlin.math.abs(dx)) onClose()
                return@awaitEachGesture
            }
        }
    }
}

/**
 * The sheet's own drag down: like [dragDownToClose], and it consumes the release when it closes, so the player's drag behind it (which sees the same gesture afterwards)
 * does not also collapse the player.
 */
private fun Modifier.closeOnDragDown(enabled: () -> Boolean, onClose: () -> Boolean): Modifier = pointerInput(Unit) {
    val threshold = CompanionMusicPlayerCollapseDragDp.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (change.isConsumed) return@awaitEachGesture
            if (!change.pressed) {
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (enabled() && dy >= threshold && dy > kotlin.math.abs(dx) && onClose()) change.consume()
                return@awaitEachGesture
            }
        }
    }
}

/** The system media volume Main's panel is built with, for a Bottom host that was not handed one. */
@Composable
internal fun rememberCompanionMusicPanelMediaVolume(supplied: MediaVolumeGateway?): MediaVolumeGateway {
    val context = LocalContext.current
    return supplied ?: remember(context) { AndroidMediaVolume(context.applicationContext) }
}

/** A change in a level smaller than this is the same level: the slider's own steps are far coarser. */
private const val MovedEpsilon = 0.0005f
