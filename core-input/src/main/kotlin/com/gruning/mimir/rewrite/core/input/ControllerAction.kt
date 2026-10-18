package com.gruning.mimir.rewrite.core.input

/**
 * The **logical** input vocabulary of Mimir — the single, device-independent set of actions the UI
 * ever reacts to. Physical inputs (a hat press, an analog-stick flick past a deadzone, a face
 * button, a keyboard arrow) are all NORMALIZED into exactly one of these by an [InputAuthority]
 * before any Composable sees them.
 *
 * ### Why an enum and not raw key codes
 * The same physical button arrives as several different raw
 * events — a face button reports `source = GAMEPAD or KEYBOARD` (dual-source) and fires twice; a
 * D-pad arrives as either a `KEYCODE_DPAD_*` KEY or an `AXIS_HAT_X/Y` motion; the left stick arrives
 * as `AXIS_X/AXIS_Y`. If every screen inspects raw codes it re-derives this reconciliation N times
 * and gets it subtly wrong N times. Collapsing to ONE enum at the edge means every surface reasons
 * about *intent*, never hardware, and the hard dual-source/hat/stick logic lives in one tested place.
 *
 * ### Directional vs. activation vs. system
 * The split matters because of the **single focus authority** contract: DIRECTIONAL actions move the app's
 * own focus and must NEVER be delegated to a platform focus engine; ACTIVATION actions fire on the
 * key-DOWN edge (never the key-UP — the dual-source button drops its up event, so up-gated actions
 * "appear dead"); SYSTEM actions are global (Back/Home/Menu) and route through the app shell.
 */
enum class ControllerAction {

    // ── Directional (move the app's ONE focus authority; never delegated to a platform focus engine) ──
    /** Move focus up. From `KEYCODE_DPAD_UP`, hat `AXIS_HAT_Y < 0`, or left-stick `AXIS_Y` past deadzone. */
    NAV_UP,
    /** Move focus down. */
    NAV_DOWN,
    /** Move focus left (also the horizontal-carousel previous). */
    NAV_LEFT,
    /** Move focus right (also the horizontal-carousel next). */
    NAV_RIGHT,

    // ── Activation (fire on key-DOWN edge only — see class KDoc; never gate on UP) ──
    /** Primary confirm — the "A" button. Launches the focused tile, commits a picker option, opens. */
    CONFIRM,
    /** Cancel / back-one-scope — the "B" button. Closes a modal, cancels a picker, steps back. */
    CANCEL,
    /**
     * Secondary — the "X" button. Context action; on Home it opens the Tome library
     * (`HomeShelfModels.kt`, `SECONDARY -> onTomes()`).
     *
     * QA-14 (2026-09-16): this read "favorite / hide in the old product's shelf", which stopped being
     * true on 2026-08-25 when the favorite moved to [SYSTEM_SELECT]. The onboarding copy that was
     * audited against this file inherited the error, so the examples here are now anchored to the
     * dispatch that implements them rather than to the old product.
     */
    SECONDARY,
    /**
     * Tertiary — the "Y" button. Context action: Customize Media on a focused game in the gamelist
     * (`LibraryDispatch.kt:726-729`), the tile menu on a Home game entry (`HomeShelfModels.kt:429`).
     */
    TERTIARY,

    // ── Shoulders / triggers (page-level movement, tab switching, quick-menus) ──
    /** Left shoulder (L1) — previous page / previous tab. */
    PAGE_LEFT,
    /** Right shoulder (R1) — next page / next tab. */
    PAGE_RIGHT,
    /**
     * Left trigger (L2). NOTE: in the old product L2 was a dual-source hazard that was deliberately
     * stripped of its keyboard role . Kept in the vocabulary
     * for jump-navigation, but any surface binding it MUST tolerate a missing key-up.
     */
    TRIGGER_LEFT,
    /** Right trigger (R2). Same dual-source caution as [TRIGGER_LEFT]. */
    TRIGGER_RIGHT,

    // ── System (global; routed by the app shell, not by leaf surfaces) ──
    /** Start button — opens Settings › Library everywhere . */
    SYSTEM_START,
    /**
     * Select button — **toggles favorite**, which is what puts a game on the Home shelf and takes it off
     * (`LibraryDispatch.kt:266` native, `:458` detail, `:745` gamelist). Moved here from [TERTIARY] on
     * 2026-08-25 so one key means one thing across both shelf modes. On the Home shelf itself it opens
     * the focused game's achievement card instead (`HomeShelfModels.kt:481`, RA01 policy).
     *
     * QA-14 (2026-09-16): this read "no-op on the home shelf; reserved", left over from before either
     * move. Anything auditing controller copy reads this doc, so a stale line here becomes stale copy
     * everywhere — which is exactly how onboarding went on teaching Y for three weeks after the change.
     */
    SYSTEM_SELECT,
    /** Hardware Back — single scope resolver (`handleBackAction` in the old product). */
    SYSTEM_BACK,
    /** Hardware Home — steps to the app root, which is INERT . */
    SYSTEM_HOME,
    /** Menu / long-press-Home — opens the contextual quick-menu. */
    SYSTEM_MENU,

    // ── User-mappable global destinations ─────────────────────────────────────────────────────
    /** Open Mimir's Quick Launch surface from any in-app destination. */
    GLOBAL_QUICK_LAUNCH,
    /** Open Utility over the current Mimir destination. */
    GLOBAL_UTILITY,
    /** Open Tweaker over the current destination. */
    GLOBAL_TWEAKER,
    /** Open Settings while preserving the current return destination. */
    GLOBAL_SETTINGS,

    // ── Leaf helpers ─────────────────────────────────────────────────────────────────────────────
    /**
     * Opens or closes the Library's controller-hint legend, the one the Guidance button shows (a controller reveal lets a pad player reach the same legend as the touch button). L3 by default. Unhandled, and so harmless, anywhere without a legend.
     */
    SHOW_HINTS,
    ;

    /** True for the four focus-moving actions. Directional input is the exclusive property of Mimir's focus authority. */
    val isDirectional: Boolean
        get() = this == NAV_UP || this == NAV_DOWN || this == NAV_LEFT || this == NAV_RIGHT

    /** True for the face-button activations that fire on the key-DOWN edge (never the up edge). */
    val isActivation: Boolean
        get() = this == CONFIRM || this == CANCEL || this == SECONDARY || this == TERTIARY

    /** True for globally-routed system actions handled by the app shell, not leaf surfaces. */
    val isSystem: Boolean
        get() = this == SYSTEM_START || this == SYSTEM_SELECT || this == SYSTEM_BACK ||
            this == SYSTEM_HOME || this == SYSTEM_MENU ||
            this == GLOBAL_QUICK_LAUNCH || this == GLOBAL_UTILITY || this == GLOBAL_TWEAKER ||
            this == GLOBAL_SETTINGS
}
