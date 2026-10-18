package com.gruning.mimir.rewrite

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Reads the live posture once per composition (PackageManager lookup is cheap but not free). */
@Composable
fun rememberPosture(context: Context): PostureReadout =
    remember(context) { PostureReadout.from(context) }

/**
 * A `clickable` that is safe on this M1 diagnostics surface. Named separately so the future
 * gamepad-focus wiring (via the core-input FocusAuthority) can replace it without touching call sites;
 * for M1 the diagnostics screen is touch-navigable (it is a static readout, not the shelf).
 */
fun Modifier.clickableSafe(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
