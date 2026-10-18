package com.gruning.mimir.rewrite

/**
 * The test tag of one etched chip's PLATE on a screen (update): a layout test measures a focused chip's halo from the plate, not from the 48 dp hit box around it, and the hit box
 * and the plate are different sizes. [screen] names the surface, [label] the chip.
 */
internal fun etchedChipPlateTag(screen: String, label: String): String = "etched-chip-plate/$screen/$label"
