package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * Physical labels projected from the applied controller map. Keys are Mimir's factory hint tokens
 * (`A`, `B`, `X`, `Y`, `L/R`, ...); a blank value means that action is currently unbound.
 */
@Immutable
data class MimirControllerHintLabels(
    val byFactoryToken: Map<String, String> = emptyMap(),
) {
    fun labelFor(factoryToken: String): String =
        byFactoryToken[factoryToken] ?: factoryToken

    /**
     * Rewrites only dot-separated controller guidance. It is deliberately not a general text
     * transform: game titles, descriptions, and authored prose never enter this function.
     */
    fun rewrite(guidance: String): String = guidance
        .split("·")
        .mapNotNull { rawSegment ->
            val segment = rawSegment.trim()
            val token = ControllerHintTokens.firstOrNull { candidate ->
                segment == candidate || segment.startsWith("$candidate ")
            }
            if (token == null) {
                segment.takeIf(String::isNotBlank)
            } else {
                val mapped = labelFor(token)
                if (mapped.isBlank()) {
                    null
                } else {
                    mapped + segment.removePrefix(token)
                }
            }
        }
        .joinToString(" · ")

    private companion object {
        val ControllerHintTokens = listOf(
            "L2/R2",
            "L1/R1",
            "L/R",
            "START",
            "SELECT",
            "L2",
            "R2",
            "L1",
            "R1",
            "A",
            "B",
            "X",
            "Y",
            "M",
        )
    }
}

val LocalMimirControllerHintLabels = staticCompositionLocalOf { MimirControllerHintLabels() }

@Composable
fun MimirControlHintText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    textAlign: TextAlign? = null,
) {
    val projected = LocalMimirControllerHintLabels.current.rewrite(text)
    MimirChromeText(
        text = projected,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        textAlign = textAlign,
    )
}
