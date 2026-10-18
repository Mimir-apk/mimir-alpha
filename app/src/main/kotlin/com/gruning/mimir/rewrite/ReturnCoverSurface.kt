package com.gruning.mimir.rewrite

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirMannazBadge
import com.gruning.mimir.rewrite.core.ui.MimirMotion

internal const val ReturnCoverTag = "return-cover"

/**
 * The branded return cover: the themed surface with the Mannaz lockup, up while a launch return restores what the player
 * left, making the settling state visible during a launch return.
 *
 * **It is composed ALWAYS and gated by [requested]**. The overlay stack and the cover share one flag, and the host used to compose the cover only while it
 * was on top of the stack, so it left composition on the very frame its request went false: its one-frame beat and its
 * `fadeOut` never ran, and the restored screen was cut in on the frame the rest of the app finished, the "at least 180 ms of
 * rune" that `primaryReturnRestoring` promises never happening. Mounted always, the exit has a composition to run in.
 *
 * Entry is unheld (the cover must appear on the frame it is asked for). The exit takes ONE frame beat first, so the cover
 * never lifts on the same frame the rows arrive (the `libraryStageHandedOff` pattern: the arriving surface gets a frame to paint under an opaque cover), and then fades
 * on [MimirMotion.OverlayMs].
 *
 * **Input follows [requested], never what is still showing.** A fading cover is pixels only. Its Back handler is registered
 * only while requested (so it is also the newest handler while it is up, as it was when it was mounted per request), and its
 * pointer consumer is a modifier that exists only while requested: a pointer-input node that merely does nothing would still
 * stop the siblings beneath it from being hit. This is the house rule that a departure is never animated by keeping a popped
 * scope alive (see the destination Box in `MimirAppHost`).
 *
 * [status] is the words (ReturnWaitStatus.kt), seated inside the cover so they leave with it.
 */
@Composable
internal fun MimirReturnCoverSurface(
    requested: Boolean,
    status: @Composable BoxScope.() -> Unit = {},
) {
    var lingering by remember { mutableStateOf(requested) }
    LaunchedEffect(requested) {
        if (requested) {
            lingering = true
        } else if (lingering) {
            withFrameNanos { }
            lingering = false
        }
    }
    if (requested) BackHandler { }
    AnimatedVisibility(
        visible = requested || lingering,
        enter = EnterTransition.None,
        exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Calmer than the welcome card: no atmosphere draw. The mask usually lives well under a second (worst case the 4 s
        // absent-row grace) and must never compete with the screen it reveals.
        //
        // Words: none for the first ReturnWaitStatusDelayMs, so a return that settles in a beat is exactly as quiet as it
        // was; past that, one line saying what it is doing (ReturnWaitStatus.kt).
        val palette = LocalMimirPalette.current
        Box(
            Modifier
                .fillMaxSize()
                .testTag(ReturnCoverTag)
                .background(palette.surfaceBase)
                .then(if (requested) Modifier.pointerInput(Unit) { consumeEveryPointerEvent() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            MimirMannazBadge(palette = palette, size = ReturnCoverBadgeSize)
            status()
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.consumeEveryPointerEvent() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
