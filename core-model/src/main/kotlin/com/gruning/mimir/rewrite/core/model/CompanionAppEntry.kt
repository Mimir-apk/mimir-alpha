package com.gruning.mimir.rewrite.core.model

/**
 * An FE-facing recent-app entry for the companion recents drawer — the display-only projection of the
 * native `SecondaryTaskRegistry`'s `AppEntry`. Deliberately carries NO icon bitmap: the drawer paints a
 * themed glyph tile (honest holding-frame, like the shelf) rather than depending on a native module or a
 * PackageManager reach from the FE. The `packageName` is what the launch action (native
 * `SecondaryTaskRegistry.launchOnSecondary`) needs; `label` is the human name to show.
 *
 * Lives in core-model (not core-companion-native) so feature-companion can render it without depending on
 * the native seam module — the app maps native `AppEntry` → this at the boundary.
 */
data class CompanionAppEntry(
    val packageName: String,
    val label: String,
    val iconPath: String? = null,
    /**
     * A cover-art REF (`content:` / `file:` / `android.resource:`) that outranks [iconPath] when set.
     *
     * Kept separate rather than folded into [iconPath]: that field is a plain filesystem path the drawer
     * decodes with `BitmapFactory.decodeFile`, while an art ref is a URI that needs the content resolver.
     * A game tab paints its cover from this and falls back to the emulator's icon when it cannot be opened.
     */
    val artRef: String? = null,
    val favorite: Boolean = false,
    val kindLabel: String? = null,
    /** CR-11: this is a LIVE tab on the bottom screen (a Mimir-launched session), so it carries a close. */
    val closable: Boolean = false,
)
