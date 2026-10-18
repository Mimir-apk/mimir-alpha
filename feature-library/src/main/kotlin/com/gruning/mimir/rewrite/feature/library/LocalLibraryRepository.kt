package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.staticCompositionLocalOf
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository

/**
 * Composition-wide handle to the app's single [LibraryRepository]. Provided ONCE at the app root
 * (alongside `LocalFocusAuthority`) so the Library UI and the Settings summary read the SAME instance —
 * one library truth, no divergent copies.
 *
 * `staticCompositionLocalOf` because the repository instance never changes for the life of the window.
 *
 * The default provider errors on purpose: the repository is a real dependency, not something to silently
 * fabricate. The app provides the Room-backed instance through LibraryInjection; previews provide their own.
 */
val LocalLibraryRepository = staticCompositionLocalOf<LibraryRepository> {
    error(
        "No LibraryRepository provided. Provide LocalLibraryRepository at the app root " +
            "(see LibraryInjection in :app, backed by LibraryStore.repository(context)).",
    )
}
