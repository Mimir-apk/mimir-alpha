package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRunicMessageCard
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion

/**
 * SD02 quiet banner: persistent (not a timed toast) while library games sit on an absent volume,
 * self-clearing the moment the monitor's reconcile finds them home again. Deliberately NOT
 * controller-focusable — it informs, it never traps the pad (`nav-feedback-gate-contract` class);
 * a touch anywhere on it dismisses until the next real volume event.
 */
@Composable
internal fun StorageNoticeHost(modifier: Modifier = Modifier) {
    val notice by StorageVolumeMonitor.notice.collectAsState()
    val reducedMotion = mimirReducedMotion()
    var retainedNotice by remember { mutableStateOf<StorageVolumeMonitor.StorageNotice?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = notice != null
    val exitSettled = visibility.isIdle && !visibility.currentState && !visibility.targetState
    val shownNotice = retainedStorageNoticeForFrame(
        current = notice,
        retained = retainedNotice,
        exitSettled = exitSettled,
    )
    SideEffect { retainedNotice = shownNotice }
    DisposableEffect(Unit) {
        onDispose { retainedNotice = null }
    }
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.TopCenter) {
        // fillMaxWidth(fraction).widthIn(max=) was inert here: fillMaxWidth fixes min==max at the
        // fraction of maxWidth, so widthIn's cap only ever coerced UP to that fixed width.
        val noticeWidth = minOf(maxWidth * 0.72f, 560.dp)
        AnimatedVisibility(
            visibleState = visibility,
            enter = fadeIn(storageNoticeFadeSpec(reducedMotion)),
            exit = fadeOut(storageNoticeFadeSpec(reducedMotion)),
        ) {
            val shown = shownNotice ?: return@AnimatedVisibility
            val title: String
            val caption: String
            when (shown.kind) {
                StorageVolumeMonitor.NoticeKind.CARD_REMOVED -> {
                    title = "SD card removed"
                    caption = "${gamesCount(shown.unavailableCount)} unavailable — they return with the card"
                }
                StorageVolumeMonitor.NoticeKind.CARD_SWAPPED -> {
                    title = "Different SD card inserted"
                    caption = "${gamesCount(shown.unavailableCount)} unavailable — add this card from Library › Add Games"
                }
            }
            val interaction = remember { MutableInteractionSource() }
            val message = remember(shown.kind, shown.unavailableCount) {
                MimirMessage(
                    eventId = shown.hashCode().toLong(),
                    text = title,
                    messageClass = MimirMessageClass.Warning,
                    supportingText = caption,
                )
            }
            MimirRunicMessageCard(
                message = message,
                compact = false,
                modifier = Modifier
                    .width(noticeWidth)
                    .clickable(interactionSource = interaction, indication = null) {
                        StorageVolumeMonitor.dismissNotice()
                    },
            )
        }
    }
}

/**
 * House duration on the house easing — never Compose's default (`FastOutSlowInEasing`) — so this notice's
 * own crossfade reads as the same gesture as every other surface in the OverlayMs/Easing grammar
 * (`HomeTomeLibraryOverlay.kt`, `MimirHintToastRail.kt`).
 */
internal fun storageNoticeFadeSpec(reducedMotion: Boolean): TweenSpec<Float> =
    tween(durationMillis = if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)

/** Retain exit content until AnimatedVisibility reports fully hidden; a new notice always wins immediately. */
internal fun retainedStorageNoticeForFrame(
    current: StorageVolumeMonitor.StorageNotice?,
    retained: StorageVolumeMonitor.StorageNotice?,
    exitSettled: Boolean,
): StorageVolumeMonitor.StorageNotice? = when {
    current != null -> current
    exitSettled -> null
    else -> retained
}

/** Canon count phrasing (local-only policy): "N games", singular-aware. */
internal fun gamesCount(count: Int): String = if (count == 1) "1 game" else "$count games"
