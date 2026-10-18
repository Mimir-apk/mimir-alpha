package com.gruning.mimir.rewrite.core.companionnative

import com.gruning.mimir.rewrite.core.model.MimirMarkReferenceBoxDp

/**
 * The mark's inset from the ᛗ button's coin edge, per side: 13 dp on the 56 dp coin leaves the 30 dp box the shipping rune is
 * drawn in. Unchanged since the rune was struck; named here so the custom image's inset can be derived from it, not typed twice.
 */
internal const val CompanionCoinMarkInsetDp = 13f

/**
 * The box the player's own image is fitted into on the ᛗ button, in dp.
 *
 *  That got 1.18x the rune's 30 dp box (35.4 dp).  1.18x was too timid, so the box is 44 dp, 1.47x the rune's: the
 * image is trimmed of its own transparent padding first ([mimirMarkInkBounds]), then fitted into this box (aspect kept,
 * centred) and CLIPPED to the coin face (the circle the rim leaves, 26.1 dp of radius), so nothing ever crosses the rim. A
 * square box this size stands 31.1 dp from the centre at its corners, past the 26.1 dp the face allows: a mask whose ink
 * reaches its corners has them rounded by that clip, and that is accepted. The default rune is not scaled.
 *
 * Every place the custom mark is drawn as the button reads this one value: the live ᛗ on both displays and the Settings
 * stage's three copies are the same coin ([createMimirButtonCoin]). The 44 dp is `MimirMarkReferenceBoxDp` in `:core-model`, which
 * Settings' Mimir-button picker scales to its tiles (the Compose coin cannot reach this module).
 *
 * The player may size and move the image inside it : where the image is drawn, for any placement, is
 * `mimirMarkDrawRect` in `:core-model`, which the coin and the picker both call. The box here is the default placement's.
 */
internal const val CompanionCustomMarkBoxDp = MimirMarkReferenceBoxDp

/** How much larger the player's own image is drawn than the rune on the ᛗ button: [CompanionCustomMarkBoxDp] over the rune's 30 dp box. */
internal const val CompanionCustomMarkScale = CompanionCustomMarkBoxDp / (MButtonSizeDp - 2f * CompanionCoinMarkInsetDp)

/** The custom image's inset per side: the coin's half-width less half of [CompanionCustomMarkBoxDp]. */
internal const val CompanionCoinCustomMarkInsetDp = (MButtonSizeDp - CompanionCustomMarkBoxDp) / 2f

/** The mark inset the ᛗ button's coin draws with: the rune's, or the player's own image's. */
internal fun companionCoinMarkInsetDp(customImage: Boolean): Float =
    if (customImage) CompanionCoinCustomMarkInsetDp else CompanionCoinMarkInsetDp
