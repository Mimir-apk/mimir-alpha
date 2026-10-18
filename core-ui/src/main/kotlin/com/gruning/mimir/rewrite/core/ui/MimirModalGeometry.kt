package com.gruning.mimir.rewrite.core.ui

// Rail/detail modals share these dimensions so the picker cannot outgrow its opening card.
fun mimirDetailRailWidthDp(widthDp: Float): Float = (0.22f * widthDp).coerceIn(154f, 168f)

fun mimirDetailPaneWidthDp(widthDp: Float): Float =
    widthDp - mimirDetailRailWidthDp(widthDp) - 2f * MimirSpacing.xl.value

fun mimirModalWidthCapDp(widthDp: Float, heightDp: Float, authoredCapDp: Float): Float =
    if (widthDp < 680f && !mimirViewportProfile(widthDp, heightDp).isCompact) {
        minOf(authoredCapDp, mimirDetailPaneWidthDp(widthDp))
    } else {
        authoredCapDp
    }
