package com.gruning.mimir.rewrite.core.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Floating badge reserve: how much of
 * the top-right corner the floating M badge takes inside Mimir. Chrome that lives in that corner (the app-shell tabs,
 * the Files header; not the status pill, which stays put under the veiled badge) adds this to its own end padding so nothing sits under the badge. 0 when the
 * badge is elsewhere, hidden, or not drawn on this screen. Provided once at the activity root.
 */
val LocalMimirTopEndReserve = compositionLocalOf<Dp> { 0.dp }
