package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import kotlinx.coroutines.delay

internal const val HomeAlphabetJumpIndicatorMs = 500L

/** Palette/runic alphabet acknowledgement; a new jump epoch supersedes the old dismissal timer. */
@Composable
internal fun HomeAlphabetJumpIndicator(
    feedback: HomeAlphabetJumpFeedback,
    modifier: Modifier = Modifier,
) {
    val notice = feedback.notice
    val displayLetter = notice?.letter ?: feedback.lastLetter
    LaunchedEffect(notice?.epoch) {
        val live = notice ?: return@LaunchedEffect
        delay(HomeAlphabetJumpIndicatorMs)
        feedback.expire(live.epoch)
    }
    val palette = LocalMimirPalette.current
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn(tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)),
        exit = fadeOut(tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(MimirSpacing.radiusCard))
                .background(palette.surfaceRaised.copy(alpha = 0.92f))
                .padding(horizontal = 18.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "ᛗ  ${displayLetter.orEmpty()}",
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
            )
        }
    }
}
