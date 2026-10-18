package com.gruning.mimir.rewrite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.mimirGestureHandleClearance
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.MimirMarkBadge

/**
 * The persistent app-shell tab frame. It wraps the "chrome"
 * surfaces — Settings / Browse — in one frame: a compact "Mimir" brand lockup at top-left, a
 * Home / Browse / Settings tab strip at top-right, and a controller-hint footer. The HOME tab
 * returns to the shelf (the shelf keeps its OWN chrome and is not wrapped). Tabs are switched by L2/R2
 * (TRIGGER_LEFT/RIGHT, routed by the host) or a touch tap — the strip is a passive indicator + touch
 * target, NOT part of the focus graph, so A/B still act on the wrapped content (matching shipping's
 * "A Select · B Back · L2/R2 Tabs").
 */
// LG01 : the TOOLS tab is retired — tool-shaped features live in Utility now, and the
// BIOS/Texture helper cards it hosted carried acquisition-adjacent copy. Three tabs remain.
enum class ShellTab(val label: String) {
    HOME("Home"),
    BROWSE("Browse"),
    SETTINGS("Settings"),
}

const val AppShellHeaderTestTag = "app_shell_header"

/**
 * One extra footer hint a screen adds for the row that is focused (Settings › Music: "X Rescan" while the Music folder row is focused). [token] is the
 * factory hint token (`X`), projected through the applied controller map like the shell's own; [label] is the word beside the keycap.
 */
data class ShellFooterHint(val token: String, val label: String)

@Composable
fun AppShellScaffold(
    activeTab: ShellTab,
    onSelectTab: (ShellTab) -> Unit,
    /**
     * The hints this screen adds after A and B, read where the footer is drawn (so a change in a snapshot state they read redraws the shell,
     * not the screen inside it). Empty (the default) is the shell's own legend, unchanged.
     */
    footerHints: () -> List<ShellFooterHint> = { emptyList() },
    content: @Composable () -> Unit,
) {
    val palette = LocalMimirPalette.current
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.surfaceBase)) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        val rootProfile = LocalMimirViewportProfile.current
        val compactSquare = if (rootProfile.widthDp > 0f) {
            rootProfile.isCompact
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).isCompact
        }
        Column(
            Modifier
                .mimirMeasuredViewport(viewport)
                .fillMaxSize(),
        ) {
        // ── Top bar: brand left, tab strip right ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compactSquare) 12.dp else 40.dp,
                    vertical = if (compactSquare) 6.dp else 0.dp,
                )
                .padding(top = if (compactSquare) 0.dp else 20.dp, bottom = if (compactSquare) 0.dp else 8.dp)
                // F10: the tab strip ends clear of the M badge's corner.
                .padding(end = com.gruning.mimir.rewrite.core.ui.LocalMimirTopEndReserve.current),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Themed ᛗ Mannaz mark (the shared companion "M-button" badge) + the active section name. The badge re-tints per theme; the
            // label follows the active shell tab (Settings / Browse), so the header reads in-context.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compactSquare) 6.dp else 12.dp),
            ) {
                MimirMarkBadge(palette = palette, sizeDp = if (compactSquare) 28.dp else 34.dp)
                MimirChromeText(
                    activeTab.label,
                    modifier = Modifier.testTag(AppShellHeaderTestTag),
                    style = if (compactSquare) MimirTypeScale.TitleCapsCompact else MimirTypeScale.TitleCaps,
                    color = palette.textPrimary,
                    maxLines = 1,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(if (compactSquare) 4.dp else 8.dp)) {
                ShellTab.entries.forEach { tab ->
                    ShellTabChip(
                        tab = tab,
                        active = tab == activeTab,
                        compact = compactSquare,
                        palette = palette,
                        onClick = { onSelectTab(tab) },
                    )
                }
            }
        }

        // ── Wrapped content ──
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }

        // ── Footer legend (shipping .controller-hints), now WITH the L2/R2 Tabs chip (the tab frame exists). ──
        // Thor drive 3: the system's gesture handle is drawn over the bottom strip, so the legend stands above it (mimirGestureHandleClearance: 0 where there is no handle).
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = mimirGestureHandleClearance()).padding(vertical = if (compactSquare) 4.dp else 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellHint("A", "Select", compactSquare, palette)
            Spacer(Modifier.width(if (compactSquare) 10.dp else 24.dp))
            ShellHint("B", "Back", compactSquare, palette)
            Spacer(Modifier.width(if (compactSquare) 10.dp else 24.dp))
            footerHints().forEach { hint ->
                ShellHint(hint.token, hint.label, compactSquare, palette)
                Spacer(Modifier.width(if (compactSquare) 10.dp else 24.dp))
            }
            ShellHint("L2/R2", "Tabs", compactSquare, palette)
        }
        }
    }
}

@Composable
private fun ShellTabChip(
    tab: ShellTab,
    active: Boolean,
    compact: Boolean,
    palette: com.gruning.mimir.rewrite.core.ui.MimirThemePalette,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (active) palette.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (active) palette.accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            tab.label,
            style = (if (compact) {
                MimirTypeScale.MenuActionCompact.copy(fontSize = 11.sp)
            } else {
                MimirTypeScale.MenuAction
            }).copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
            color = if (active) palette.accentBright else palette.textMuted,
        )
    }
}

@Composable
private fun ShellHint(
    token: String,
    label: String,
    compact: Boolean,
    palette: com.gruning.mimir.rewrite.core.ui.MimirThemePalette,
) {
    if (LocalMimirControllerHintLabels.current.labelFor(token).isBlank()) return
    ShellCap(token, compact, palette)
    ShellLabel(label, compact, palette)
}

@Composable
private fun ShellCap(
    text: String,
    compact: Boolean,
    palette: com.gruning.mimir.rewrite.core.ui.MimirThemePalette,
) {
    val projected = LocalMimirControllerHintLabels.current.labelFor(text)
    if (projected.isBlank()) return
    // The footer's one keycap (not tinted): the etched chip's plate with the key in the chip ink, shared with every other footer.
    MimirFooterKeycap(
        key = projected,
        compact = compact,
        palette = palette,
        modifier = Modifier.padding(end = if (compact) 4.dp else 6.dp),
    )
}

@Composable
private fun ShellLabel(
    text: String,
    compact: Boolean,
    palette: com.gruning.mimir.rewrite.core.ui.MimirThemePalette,
) {
    MimirChromeText(
        text,
        style = if (compact) MimirTypeScale.ControlHintCompact else MimirTypeScale.ControlHint,
        color = palette.textMuted,
    )
}
