package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.FocusScope

/**
 * The modal focus scope the Appearance theme picker owns while open — pushed on open, popped on close.
 *
 * The picker itself is the shared [ThemePreviewGallery] (a 3×3 wall of live per-theme preview cards, the same
 * one first-run onboarding uses). The old name-plus-swatch list ([ThemePickerOverlay] / `dispatchThemePicker`)
 * was retired in favour of the gallery.
 */
val ThemePickerScope = FocusScope("appearance/themePicker")
