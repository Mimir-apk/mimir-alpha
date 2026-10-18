package com.gruning.mimir.rewrite.core.input

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-wide handle to the single [FocusAuthority]. Provided ONCE at the app root; every
 * `Modifier.mimirFocusable` reads it from here so there is provably one authority per app window.
 *
 * `staticCompositionLocalOf` (not `compositionLocalOf`) because the authority instance never changes
 * for the life of the window — reads should not add recomposition scope tracking.
 */
val LocalFocusAuthority = staticCompositionLocalOf<FocusAuthority> {
    error("No FocusAuthority provided. Wrap the app in a MimirFocusHost / provide LocalFocusAuthority at the root.")
}
