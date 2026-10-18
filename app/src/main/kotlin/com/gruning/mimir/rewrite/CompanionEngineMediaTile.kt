package com.gruning.mimir.rewrite

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiArtReceipt
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl

/*
 * W2e: Your music reaches the Bottom. The Activity page's lower-left tile is the media tile whenever Main's music panel would have a card, Your music
 * included, and it is built from the panel's own model: [musicTileCard] is the card [musicPanelModel] puts on top (the playing source, else the last one
 * played), the same function the panel's opening decision uses, so the tile and the panel read one answer from one engine and can never disagree.
 *
 * Another app's player keeps the companion bridge's richer tile (its seek lane, its track glide) while the bridge has that track; this tile stands in
 * for Your music, which the bridge never lists (it skips Mimir's own session, so the two are not double-listed), and for another player the bridge has
 * not delivered yet.
 */

/**
 * The card the Bottom's media tile shows for [state], or null when Main's panel would have none: exactly [musicPanelModel]'s top card. The sliders'
 * inputs are not read (the tile has none), so they are inert here.
 */
internal fun musicTileCard(state: MusicControlState): MusicPanelCardModel? =
    if (!state.present) {
        null
    } else {
        musicPanelModel(
            control = state,
            stopped = false,
            musicVolume = 0f,
            mediaVolume = 0f,
            mediaAvailable = false,
            mediaFixed = true,
            cappedByMedia = false,
            capPushed = false,
        ).top
    }

/**
 * The media tile for the engine's top card: the art seat (the panel's own art law, [MusicPanelArtwork]: nothing while the art loads or after it failed, the plate
 * and glyph only when the player has none, which is Your music's case unless its track carries an embedded picture ([withYoursArt])), the state word over the title, one 48 dp line (the pause hint, else the artist or
 * the track's format) where the seek lane is on the bridge's tile, and the transport. Previous, Play/Pause and Next call the engine's own `previous`,
 * `playPause` and `next` for the card's source, exactly what the panel's controller calls: there is no second control path. Your music exposes no position or
 * duration to the engine, so there is no seek lane for it (Main's panel has none either).
 */
@Composable
internal fun ActivityEngineMediaTile(
    card: MusicPanelCardModel,
    engine: MusicPanelEngine,
    navigationEnabled: Boolean,
    onOpenPanel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val mediaControl = rememberBottomUiControl(BottomSurface.ACTIVITY_MEDIA)
    val latestOpenPanel by rememberUpdatedState(onOpenPanel)
    // "Enabled" on a button is the player's say only (it draws the look); the dock's and the strip's settle drop the tap in onClick instead, so nothing flashes disabled.
    val latestNavigationEnabled by rememberUpdatedState(navigationEnabled)
    val openSource = remember { MutableInteractionSource() }
    val stateWord = card.statusLine.substringBefore(" — ")
    val title = card.statusLine.substringAfter(" — ", "")
    // Your music's embedded picture, when its track carries one, decoded at the tile's 56 dp seat; another player's card is returned as it is.
    val artCard = card.withYoursArt(rememberYourMusicArt(card.trackId, 56.dp))
    val artWired = artCard.artwork != null || artCard.artworkPending || artCard.artworkFailed
    BottomUiArtReceipt(
        tile = "activity.media",
        hasArt = artWired,
        loaded = artCard.artwork != null,
        failed = artCard.artworkFailed && artCard.artwork == null,
        plateVisible = !artWired,
    )
    MimirLiquidGlassPanel(
        // The whole tile opens the music panel, the press response right after the tap (B5, R4): the same chain as the bridge's tile.
        modifier = modifier.fillMaxWidth()
            .mimirActivatable(
                label = "Open music panel",
                enabled = navigationEnabled,
                interactionSource = openSource,
                onActivate = {
                    mediaControl("activity-media.open_panel", null)
                    latestOpenPanel()
                },
            )
            .mimirPressResponse(openSource),
        contentPadding = 10.dp,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        radius = MimirSpacing.radiusCard,
        gradientBody = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(56.dp).testTag(ActivityMediaArtTag)) {
                // The panel's art, not a tap target on the Bottom: the card says it cannot open its player here.
                MusicPanelArtwork(
                    // Your music's card carries no artwork title (only another player's does), so a track with no picture drew its book with an empty title, which the book reads "Untitled". The
                    // book names what the tile names: the card's own title. A card with no title at all keeps the book's own word.
                    model = artCard.copy(canOpenPlayer = false, artworkTitle = artCard.artworkTitle.ifBlank { title }),
                    palette = palette,
                    focused = false,
                    authority = null,
                    controlModifier = Modifier,
                    onOpen = {},
                    onPlaced = { _, _ -> },
                    artSize = 56.dp,
                    verticalPadding = 0.dp,
                    // The seat is 56 dp: the book is 42 dp wide, and its title is set to it rather than cut where it runs out ("Untit" / "led").
                    fitTitle = true,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MimirChromeText(
                    stateWord,
                    style = MimirTypeScale.RunicEyebrow,
                    color = if (card.playing) palette.accent else palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.isNotEmpty()) {
                    MimirIntentText(
                        if (card.titleIsAuthored) MimirTextIntent.Authored(title) else MimirTextIntent.StaticChrome(title),
                        style = MimirTypeScale.Row,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            val hint = card.hint
            val line = card.artist
            if (hint != null) {
                MimirChromeText(
                    hint,
                    style = MimirTypeScale.ControlHintCompact,
                    color = palette.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else if (line != null) {
                MusicPanelArtistLine(line, authored = card.artistIsAuthored, palette = palette)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaButton(
                label = "Previous",
                enabled = card.canPrevious,
                skipTargetSize = MediaTileSkipTargetDp,
                skipPlateSize = MediaTileSkipPlateDp,
                pressResponse = true,
                onClick = {
                    if (!latestNavigationEnabled) return@MediaButton
                    mediaControl("activity-media.previous", null)
                    engine.previous(card.card.source)
                },
            )
            MediaButton(
                label = if (card.playing) "Pause" else "Play",
                enabled = card.canPlayPause,
                primary = true,
                primarySize = MediaTilePlayDp,
                pressResponse = true,
                onClick = {
                    if (!latestNavigationEnabled) return@MediaButton
                    mediaControl("activity-media.play_pause", null)
                    engine.playPause(card.card.source)
                },
            )
            MediaButton(
                label = "Next",
                enabled = card.canNext,
                skipTargetSize = MediaTileSkipTargetDp,
                skipPlateSize = MediaTileSkipPlateDp,
                pressResponse = true,
                onClick = {
                    if (!latestNavigationEnabled) return@MediaButton
                    mediaControl("activity-media.next", null)
                    engine.next(card.card.source)
                },
            )
        }
    }
}

/**
 * Whether the bridge's [track] is the player the engine's top card ([other]) shows. Policy: the same app is the same player, and "the same app" is the same
 * package: [OtherPlayerSnapshot.packageName] is filled from the bridge's track by [otherPlayerSnapshotFor], so the two sides carry one key. Labels are no key at
 * all (two apps can share one, and a blank one survives the resolver). A snapshot with no package never matches, and the engine's tile stands in.
 *
 * A track skip inside one app keeps the bridge's tile (its seek lane and its glide) while the engine catches up, and for that moment the tile may name the new title
 * while the panel still has the old one; so may a session switch inside one app (session B on the tile, session A in the panel). During a Spotify to YouTube switch
 * the packages differ, and the engine's own tile (the panel's card) stands in until they agree.
 */
internal fun bridgeTrackIsEnginePlayer(track: CompanionMediaTrack, other: com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot): Boolean =
    other.packageName != null && track.packageName == other.packageName
