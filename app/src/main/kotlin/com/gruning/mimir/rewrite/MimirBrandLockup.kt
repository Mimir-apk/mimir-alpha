package com.gruning.mimir.rewrite

/**
 * The shared Mimir brand lockup pieces — the runic sub-eyebrow. Extracted from OnboardingFlow (which stays its first
 * consumer) so the cold-start Welcome-back lane renders the SAME mark instead of forking it (the MimirBoxArt/B10 lesson: one
 * drawing, two surfaces). The ᛗ Mannaz badge that sat beside it moved to core-ui (`MimirMannazBadge`, `MimirBrandMark.kt`), so the
 * Bottom's surfaces can draw it too.
 */

/** The runic wordmark line — `ᛗᛁᛗᛁᚱ · THE WELL OF MEMORY` (shipping `.eok-rune-line`). PROTECTED LORE. */
internal const val MimirRuneEyebrow = com.gruning.mimir.rewrite.core.ui.MimirRuneEyebrow
