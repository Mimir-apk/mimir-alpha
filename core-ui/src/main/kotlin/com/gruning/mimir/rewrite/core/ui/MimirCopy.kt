package com.gruning.mimir.rewrite.core.ui

/**
 * MIMIR-REWRITE :core-ui — the CANON user-facing vocabulary (shared terms + cross-surface string
 * contracts). One home so a shared rename lands everywhere at once and surfaces can't drift apart
 *.
 *
 * WHAT BELONGS HERE — and nothing else:
 *  - Terms with an policy (R1 posture chips, R8 "Library", R16 "Hero Motion", R3 plain failures).
 *  - Strings that form a CONTRACT between surfaces — e.g. the primary emits [StatusReady] and the
 *    companion keys its green success channel on the same label; both MUST read this constant.
 *
 * WHAT DOES NOT: single-surface body copy (pane blurbs, onboarding sentences, About text) stays at its
 * surface, and PROTECTED LORE (runic signatures, the Eoksadberg welcome, theme god-names, authored
 * poem verses) is never centralized or "tidied".
 */
object MimirCopy {

    // ── The APPROVED launch-status set (local-only policy; green = Ready ONLY, R1) ──
    /** The one green per-item state; the companion's success channel keys on this exact label. */
    const val StatusReady = "Ready"
    const val StatusNeedsEmulator = "Needs emulator"
    const val StatusUnsupported = "Unsupported"
    /** Dispatch-only claim — the intent was sent, nothing more . */
    const val StatusLaunching = "Launching…"

    // ── Dispatch-failure chips — fully plain language  ──
    const val FailureWrongFileForm = "Wrong file form"
    const val FailureCouldntStart = "Couldn't start"
    const val FailureAndroidBlocked = "Android blocked it"

    // ── Posture identity labels — gold chips, never green  ──
    const val PostureLocal = "Local"
    const val PostureLocalOnly = "Local only"
    const val PostureLocalNoNetwork = "Local · no network"
    const val PostureOnThisDevice = "On this device only"
    const val PostureConnected = "Connected"

    // ── Feature nouns  ──
    /** The user-facing noun; "Bifröst" names the display ENGINE only and never appears as a feature label. */
    const val HeroMotion = "Hero Motion"

    // ── Release-alpha interaction vocabulary  ──────────────────────────
    const val CustomizeMedia = "Customize media"
    const val IconArt = "Icon art"
    /** The game's own cover, choosable since policy 2026-08-25 (R5). */
    const val CoverArt = "Cover art"

    /**
     * The FACE row that is not the shelf's -- policy 2026-08-25 (C1/C3), name chosen by him the same day.
     *
     * Customize Media stops listing pictures and starts listing WIRINGS: the face a game wears is decided by where you are
     * looking at it, so the rows are named after the PLACE, not the picture. The shelf's face is [IconArt] since 2026-10-10
     * — one type that takes a square, a box, the physical article
     * or an upload, wherever the game stands on a shelf -- Home, Library, a Tome and the Bottom.
     */
    const val FaceLaunchCard = "Launch card"
    const val HeroArt = "Hero art"
    /**
     * renamed from "Tome cartridge art".
     *
     * *Hero* here is the family term — **the face a game wears** — which is why
     * this sits beside [HeroArt] and [HeroMotion] without meaning the same thing as either. Under that
     * reading it is [HeroArt] (the backdrop) that carries the drifted label. The *Hero* family terminology is retained;
     * renaming the backdrop row independently would separate it from its sibling face rows.
     * If the face rows are ever renamed by state, [HeroArt] and [HeroMotion] become the only *Hero* rows
     * left and the family loses its siblings in the menu; that is the moment to propose it afresh.
     *
     * Explicit scope, corrected AGAIN 2026-08-26 under C1/C2/C4. The paragraph here named the Library
     * gamelist list view, the Tome collection gamelist and *"a launch card opened from either of those"*,
     * and said the setting **swaps** them. Three things were wrong with that, and the row's own supporting
     * text in `BrightnessOverlay` had already been fixed while this was left standing — a KDoc and the
     * string it documents disagreeing about the same switch:
     *
     *  - **It is a DEFAULT, not a swap.** an explicit face pick outranks this in either position.
     *  - **It omits the Home shelf and the carousel.** Both read it today — the Home tile through
     *    `selectedShelfFace`, the carousel through `libraryListPreviewMedia` (2026-10-10: `selectedShelfFace` is gone; Home and the
     *    Library's shelf read the one resolver, `resolveShelfFaces`). Device-verified 2026-08-26:
     *    with the switch ON, Dreamcast cards paint discs in the CAROUSEL
     *    .
     *  - **"a launch card opened from either of those" is origin-derivation**, which C1 deleted at
     *    `64d00e47`. The card has its own state and its own default (the article), and this switch does
     *    not gate it at all.
     *
     * True scope: **it sets the SHELF state's default** — Home shelf, Library shelf, gamelist list view
     * and carousel, and inside a Tome — to the disc/cartridge instead of the cover. A pick always wins,
     * and the launch card is not governed by it.
     *
     * The sentence this replaces said *"changes Tome thumbnails only, never Home/Library icon art"*. That
     * stopped being true on 2026-08-22 (`06db20c4`), which extended cartridge art to the launch card, and
     * remained in this documentation after the underlying behaviour changed.
     */
    const val CartridgeHero = "Cartridge Hero"
    const val Default = "Default"
    const val SavedChoices = "Saved choices"
    const val SearchResults = "Search results"
    const val InUse = "In use"
    const val Play = "Play"
    const val Open = "Open"
    const val OpenWith = "Open with"
    const val Use = "Use"
    const val EditorsChoice = "Editor’s Choice"
    const val Tome = "Tome"
    const val MButton = "Mimir button"

    // ── Gamelist Settings affordance ──
    /**
     * The gamelist hero affordance's player-facing word, renamed from "Hero". It opens the SAME top-level
     * menu (Customize media / Add to Tome / Achievements) the Home tile Edit menu uses — a menu LAUNCHER
     * label, not a settings SCREEN; the menu launcher and settings screen must remain distinct in copy. `GamelistGetArtKey`'s focus identity is unaffected — only this rendered word changed.
     */
    const val GamelistSettingsMenu = "Settings"

    // ── Manual game rename (cosmetic display override only) ──
    const val RenameGame = "Rename"
    /**
     * Content-writing pass. Was "Reset to detected name" — "detected" is OUR word for
     * what the scanner did, not one a player thinks in. "Identified name" is
     * still naming a mechanism (identification); "original" names the plain relationship a player actually
     * has to it — whatever this game was called before they changed it — with nothing left to explain.
     */
    const val ResetToOriginalName = "Reset to original name"

    /**
     * The one line under the rename field. Replaces a paragraph that narrated button mechanics and named an internal feature at the
     * player. It answers the only question a rename actually raises — whether this touches the ROM on
     * disk — and says nothing the checkmark key already says.
     */
    const val RenameDoesNotTouchFiles = "This won’t change your files."


    // Controller legends are the one deliberate caps lane. Actions and body copy remain sentence case.
    /**
     *  **Y is search, product-wide.**
     *
     * This is convergence on a convention the tree already had rather than a new one: the file manager
     * bound search to TERTIARY/FACE_Y and legended it `Y SEARCH` (`FileBrowserDispatch.kt`), and the
     * Customize Media picker was the single surface still answering search on X. It is a real BINDING
     * move, not a relabel — `reduceHomeCustomEnrich` now submits on [ControllerAction.TERTIARY] — because a
     * legend that names a key the dispatcher does not answer is worse than no legend at all.
     *
     * The move also collapses a same-surface collision: X meant SEARCH on the query field and REMOVE one
     * lane down ([HintMediaRemovable]), so the same key did two unrelated things inside one modal. After
     * this, X means exactly one thing here.
     */
    const val HintSearch = "A EDIT · Y SEARCH · B BACK"
    const val HintFilters = "A CYCLE · B BACK"
    const val HintMedia = "A USE · B BACK"
    /**
     * Content-writing pass (2026-08-11): reviewed and left AS-IS — "RENAME"/"BACK" are already plain verbs,
     * matching the other product legends, not internal jargon. Left unrendered: grep-confirmed (2026-08-11)
     * this constant has no call site anywhere in the tree — the shipped "Rename" hint beside the launch
     * card's edit mark reads [RenameGame] instead (`HomeLaunchCardTitleEditHint`, no button legend). Kept
     * rather than deleted since removing an unused public constant is a separate housekeeping call, not a
     * content-wording one.
     */
    const val HintRename = "A RENAME · B BACK"
    const val HintMediaRemovable = "A USE · X REMOVE · B BACK"

    /**
     *  SELECT has opened a full preview
     * for hero and clip all along; nothing said so, and the cover lanes could not do it at all. Shown
     * only when the focused candidate can actually expand, so the legend never promises a key that does
     * nothing.
     */
    const val HintMediaPreviewable = "A USE · SELECT PREVIEW · B BACK"
    const val HintMediaRemovablePreviewable = "A USE · SELECT PREVIEW · X REMOVE · B BACK"
    const val HintUpload = "A CHOOSE · B BACK"
    const val HintOpen = "A OPEN · B BACK"
    const val HintRetry = "A RETRY · B BACK"
    const val HintStop = "B STOP"

    // ── Settings rail (canon "Library", as in "Settings › Library") ──
    const val RailLibrary = "Library"

    // ── Tweaker transport legibility ──
    // A silent fallback to Stock must have a visible reason. The advancedCapabilitiesV6 bundle
    // carries lastRestoreReason/lastRestoreIsTelemetryTransport (AdvancedControlsPublicBundles.kt),
    // classified by isTelemetryTransportRestoreReason (GuardianRestoreObservability.kt) from
    // HardwareSessionSupervisor's restore reason, such as MISSING_TELEMETRY_CPU.
    // PROPOSED SEAM: surface consumption remains unwired. These strings are proposed content
    // for a consumer, not evidence that a player-visible surface renders them.
    /** Why the profile fell back to Stock: the daemon stopped receiving live thermal readings. */
    const val TweakerProfileDroppedTelemetryLost =
        "Mimir returned to Stock because live thermal readings stopped arriving."
    /** Paired recovery hint; a measured recovery incident needed both halves restarted, not one. */
    const val TweakerProfileDroppedRestartHint = "Restart Mimir Tweaker, then re-apply your profile."

    // ── Guardian hand-back (the Tweaker's guardian restored Stock
    // on the Odin three times -- a false stale verdict twice, a suspend once -- and the
    // player was told nothing while the device sat at 103 C on the vendor's floors). The daemon
    // now classifies these on the same bundle (`lastRestoreIsGuardianHandback`); this is the
    // sentence the existing restore message carries for that class. ──
    /** Why the profile fell back to Stock, in plain words (policy D, 2026-10-14). */
    const val TweakerProfileDroppedGuardianHandback =
        "Performance profile paused — the device is back on its own settings."
    /** Paired recovery hint: no restart is needed, the profile only has to be applied again. */
    const val TweakerProfileDroppedReapplyHint = "Re-apply your profile when you're ready."


    /**
     * Heat cut the active profile's clock ceilings mid-session.
     *
     * After reported performance-clock loss mid-game, the row continued to show
     * the profile at its nominal caps. This sentence makes the derate visible.
     */
    fun tweakerProfileDeratedHeat(profileCaps: String) =
        "$profileCaps clocks were reduced to hold temperature."

    /**
     * The recovery story, and it is verified rather than guessed: `thermalRung` resets only in
     * `resetTelemetryState()`, which runs on a fresh apply or at session end. So re-applying the
     * profile really does return nominal caps, and saying only "when the game closes" would be an
     * overclaim of exactly the kind this landing exists to remove.
     */
    const val TweakerProfileDeratedHoldHint =
        "Full speed returns when the game closes or the profile is re-applied."
    // ── Controller mapping "no binding" sentinel — a CONTRACT between :app's ControllerMapping.kt (builds
    // both the main row list and the Controller Shortcuts row list) and :feature-settings'
    // ControllerMappingOverlay.kt (reads the same sentinel to color the chip and count bound rows).
    // the
    // identical "no binding assigned" state read "UNBOUND" in the main row list and "Not set" in the
    // sibling Controller Shortcuts row list — same file (ControllerMapping.kt:519 vs :560),
    // same concept, two spellings. "Not set" was picked (reused, not invented) because it already matches
    // Settings' plain sentence-case voice; both call sites now read this one constant so the split cannot
    // silently return.
    const val NoBindingAssigned = "Not set"
}
