package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale

/**
 * An intentionally focusless capture harness. It cannot activate a Settings row with the button
 * being mapped; InputRouter owns the neutral-release quarantine for all controller input.
 */
@Composable
internal fun ControllerShortcutCaptureSheet(
    pendingAction: ControllerShortcutAction?,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = Modifier.fillMaxSize().background(palette.scrim.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassPanel(modifier = Modifier.widthIn(max = 440.dp)) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "ᛗ · MAP ${pendingAction?.title?.uppercase() ?: "SHORTCUT"}",
                    style = MimirTypeScale.RunicEyebrow,
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "PRESS AN EXTRA CONTROLLER BUTTON",
                    style = MimirTypeScale.MenuAction,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "B CANCELS · SYSTEM KEYS STAY WITH ANDROID",
                    style = MimirTypeScale.ControlHintCompact,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}
