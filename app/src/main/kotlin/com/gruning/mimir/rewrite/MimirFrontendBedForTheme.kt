package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.MimirFrontendBed
import com.gruning.mimir.rewrite.core.ui.MimirThemeId

/**
 * Which frontend music a theme wears.
 *
 * the slow ambient piece becomes the frontend music for every theme
 * EXCEPT Hel — the Dread, persisted under the legacy key `nightmare` — which keeps "Fallen Hero".
 *
 * The mapping lives in `:app` because this is where themes are known: `:core-audio` is a leaf with
 * no `:core-ui` dependency and deliberately has no idea what a theme is. It is a `when` on the enum
 * rather than a lookup with a default so that adding a tenth theme fails to compile here, which is
 * the one place a new identity's music should have to be decided rather than inherited by accident.
 */
internal fun mimirFrontendBedFor(themeId: MimirThemeId): MimirFrontendBed =
    when (themeId) {
        MimirThemeId.HEL -> MimirFrontendBed.FallenHero
        MimirThemeId.DEFAULT,
        MimirThemeId.FREYR,
        MimirThemeId.SURTR,
        MimirThemeId.MANI,
        MimirThemeId.NIFLHEIM,
        MimirThemeId.YGGDRASIL,
        MimirThemeId.FOLKVANGR,
        MimirThemeId.BALDR,
        -> MimirFrontendBed.Ambient
    }
