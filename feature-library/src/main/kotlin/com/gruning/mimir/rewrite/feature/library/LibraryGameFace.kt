package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.MimirFlatLogoArt
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import com.gruning.mimir.rewrite.core.ui.rememberMimirLogoInk

/*
 * A game's face on the Bottom's surfaces that are not a grid of tiles (the Activity page, the launch card), under the one art law.
 *
 * Wired art is drawn bare and FIT (never cropped, never blurred); nothing is drawn while it loads or after it fails to decode (R3/R8); a local
 * file that no longer exists is no art, so the book (R8a); art whose lookup is not finished draws nothing at all, because the book is a claim
 * that the game has none and the product has not established it yet; and a face is only ever the game's own, never another game's.
 */

/**
 * The cover, or the disc if that is all there is, from [libraryListPreviewMedia]: the same ladder the shelf uses, so a game wears one face
 * everywhere. Drawn FIT in the seat [modifier] gives it, centred.
 */
@Composable
fun LibraryGameFace(game: GameRow, modifier: Modifier = Modifier) {
    val media = remember(game) { libraryListPreviewMedia(game) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val ref = media.artUri
        when {
            ref != null -> MimirBoxArt(model = ref, title = game.title, focused = false, contentScale = ContentScale.Fit)
            media.source == LibraryListPreviewMediaSource.FALLBACK ->
                MediaArtFallbackGlyph(title = game.title, platformIdValue = game.platformId.value, showTitle = false, fitTitle = true)
            else -> Unit
        }
    }
}

/**
 * The landscape zone of a tile: the game's HERO fills it (drawn bare, cropped to the zone, never blurred); with no wired hero its clear LOGO is
 * ink-fit and centred on the glass; with neither, the ladder's portrait cover is fitted (contain, not cropped, not blurred) and centred.
 * "Wired" is the off-main file check ([rememberMimirArtWired]), so a hero or logo whose file was deleted falls through to the next tier rather
 * than leaving a hole, and one that is still being checked counts as wired (nothing is drawn until it loads).
 */
@Composable
fun LibraryGameHeroZone(game: GameRow, modifier: Modifier = Modifier) {
    val hero = remember(game.heroArtUri) { game.heroArtUri?.trim()?.takeIf { it.isNotEmpty() } }
    val logo = remember(game.flatArtUri) { game.flatArtUri?.trim()?.takeIf { it.isNotEmpty() } }
    val heroWired = rememberMimirArtWired(hero)
    val logoWired = rememberMimirArtWired(logo)
    val logoInk = rememberMimirLogoInk(logo)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when {
            hero != null && heroWired -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(MimirSpacing.sm))) {
                MimirBoxArt(model = hero, title = game.title, focused = false, contentScale = ContentScale.Crop)
            }
            logo != null && logoWired -> MimirFlatLogoArt(title = game.title, logoUri = logo, modifier = Modifier.fillMaxSize(), ink = logoInk)
            else -> LibraryGameFace(game, Modifier.fillMaxSize())
        }
    }
}
