package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp

/**
 * The one shape an app's icon wears: a rounded square whose corner is 11/40 of its
 * edge, which is Main's own icon (`HomeQuickLaunchPickerIcon` in the app module: `Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))`).
 *
 * **Mirrored, not promoted.** Main's line is a literal in the app module and is left exactly as it is, so Main's pixels cannot move. This is
 * the same proportion written once, so a Bottom icon of any edge (40 dp in the dock and the picker, 34 dp on the lattice, 54 dp in a Tab)
 * is the shape Main's icon is at that size; at 40 dp it is Main's 11 dp to the bit. A source pin in feature-companion (`CompanionDockRenderTest`)
 * fails if Main's literal ever stops being 11 over 40, and the same class measures the corner from rendered pixels in all four Bottom places.
 */
const val MimirAppIconCornerRatio: Float = 11f / 40f

/** The corner radius of an app icon [edge] on a side. */
fun mimirAppIconCorner(edge: Dp): Dp = edge * MimirAppIconCornerRatio

/** The clip (and the ground) of an app icon [edge] on a side. */
fun mimirAppIconShape(edge: Dp): RoundedCornerShape = RoundedCornerShape(mimirAppIconCorner(edge))
