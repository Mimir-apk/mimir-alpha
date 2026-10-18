package com.gruning.mimir.rewrite.core.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale

/**
 * Declares why text exists before deciding how it is painted.
 *
 * Static Mimir chrome is full caps visually but keeps natural speech. Authored values and prose are never
 * transformed. Mixed copy caps only the controlled fragments, which prevents provider, user, or game data
 * from leaking into a blanket uppercase operation.
 */
@Immutable
sealed interface MimirTextIntent {
    val spokenText: String

    @Immutable
    data class StaticChrome(
        override val spokenText: String,
        val visualOverride: String? = null,
    ) : MimirTextIntent

    @Immutable
    data class Authored(override val spokenText: String) : MimirTextIntent

    @Immutable
    data class Body(override val spokenText: String) : MimirTextIntent

    @Immutable
    data class Mixed(val fragments: List<MimirTextFragment>) : MimirTextIntent {
        override val spokenText: String = fragments.joinToString(separator = "", transform = MimirTextFragment::text)
    }
}

@Immutable
sealed interface MimirTextFragment {
    val text: String

    @Immutable
    data class Chrome(override val text: String) : MimirTextFragment

    @Immutable
    data class Authored(override val text: String) : MimirTextFragment
}

@Immutable
data class MimirResolvedText(
    val visualText: String,
    val spokenText: String,
)

fun MimirTextIntent.resolve(): MimirResolvedText = when (this) {
    is MimirTextIntent.StaticChrome -> MimirResolvedText(
        visualText = visualOverride ?: spokenText.uppercase(Locale.ROOT),
        spokenText = spokenText,
    )
    is MimirTextIntent.Authored -> MimirResolvedText(spokenText, spokenText)
    is MimirTextIntent.Body -> MimirResolvedText(spokenText, spokenText)
    is MimirTextIntent.Mixed -> MimirResolvedText(
        visualText = fragments.joinToString(separator = "") { fragment ->
            when (fragment) {
                is MimirTextFragment.Chrome -> fragment.text.uppercase(Locale.ROOT)
                is MimirTextFragment.Authored -> fragment.text
            }
        },
        spokenText = spokenText,
    )
}

/** Pure visual projection for non-semantic tests and legacy components during the bounded migration. */
fun mimirStaticChrome(text: String): String =
    MimirTextIntent.StaticChrome(text).resolve().visualText

/**
 * Renders visual caps and replaces raw all-caps/rune semantics with the natural authored speech string.
 * Interactive parents may merge this descendant; they still receive only the natural description.
 */
@Composable
fun MimirChromeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    textAlign: TextAlign? = null,
    visualOverride: String? = null,
) {
    MimirIntentText(
        intent = MimirTextIntent.StaticChrome(
            spokenText = text,
            visualOverride = visualOverride,
        ),
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        textAlign = textAlign,
    )
}

@Composable
fun MimirAnimatedChromeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    textAlign: TextAlign? = null,
    visualOverride: String? = null,
) {
    MimirAnimatedChromeText(
        intent = MimirTextIntent.StaticChrome(
            spokenText = text,
            visualOverride = visualOverride,
        ),
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        textAlign = textAlign,
    )
}

@Composable
fun MimirAnimatedChromeText(
    intent: MimirTextIntent,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    textAlign: TextAlign? = null,
) {
    val resolved = intent.resolve()
    AnimatedContent(
        targetState = resolved.visualText,
        transitionSpec = {
            // The resize rides the fade's own clock, unclipped. The bare `togetherWith` carries a default SizeTransform: a clipped spring
            // that settles on its own time, so a line that grew was cut at the old width while it faded in.
            (
                fadeIn(tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)) togetherWith
                    fadeOut(tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))
                ).using(
                SizeTransform(clip = false) { _, _ -> tween(MimirMotion.FocusMs, easing = MimirMotion.Easing) },
            )
        },
        contentAlignment = when (textAlign) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        },
        modifier = modifier.clearAndSetSemantics {
            contentDescription = resolved.spokenText
        },
        label = "mimir-animated-chrome-text",
    ) { visualText ->
        Text(
            text = visualText,
            modifier = Modifier.fillMaxWidth(),
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
            textAlign = textAlign,
        )
    }
}

/**
 * Where a test hears the laid-out text of every [MimirIntentText] under it: the spoken text, and the [TextLayoutResult] the line was laid out as. The
 * node's own semantics hide its layout (`clearAndSetSemantics` drops the text's `GetTextLayoutResult`), so this is the only way to ask a real line
 * whether it ellipsised, how many lines it took and where the line ended. Null in the product: nothing is observed and nothing is allocated.
 */
val LocalMimirTextLayoutObserver = compositionLocalOf<((spokenText: String, layout: TextLayoutResult) -> Unit)?> { null }

/**
 * Intent-aware text renderer for shared components whose title slot may be static, authored, body, or mixed.
 * The visual projection and spoken projection remain coupled at the rendering boundary.
 */
@Composable
fun MimirIntentText(
    intent: MimirTextIntent,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    textAlign: TextAlign? = null,
) {
    val resolved = intent.resolve()
    val observer = LocalMimirTextLayoutObserver.current
    Text(
        text = resolved.visualText,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = resolved.spokenText
        },
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        textAlign = textAlign,
        onTextLayout = observer?.let { heard -> { layout: TextLayoutResult -> heard(resolved.spokenText, layout) } },
    )
}
