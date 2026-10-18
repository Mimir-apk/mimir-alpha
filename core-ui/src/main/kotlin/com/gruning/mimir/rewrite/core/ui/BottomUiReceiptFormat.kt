package com.gruning.mimir.rewrite.core.ui

/**
 * The grammar of the Bottom (second display) receipts: one line per edge, `ev=<event> key=value ...`, on the
 * single tag [TAG].
 *
 * This file is PURE (no Android, no clock, no state) so the exact text a host driver greps is unit-pinned.
 * It is product logging, not debug instrumentation: always on, release-safe, one short line per edge.
 *
 * What a receipt may carry, by rule, because a device log is shared and kept:
 *  - stable ids only: a surface id, a control id, a package name, a platform id. Never a title, a URL (ScreenScraper
 *    URLs carry credentials), a file path or a credential. [token] enforces this by redacting anything URL- or path-shaped.
 *  - a game, a folder or any other id that is DERIVED FROM A TITLE OR A FILE NAME is written as an opaque id
 *    (`game:3fa9c1d2e0`, [opaque]): a game id is `catalog:<slug>` or `title:<platform>:<file stem>`, which spells the game.
 *    The same game gets the same opaque id on every line, so `tile=` and `target=` still correlate. [token] enforces this
 *    too: a game-id-shaped value, or free text (anything that needed cleaning to fit a `key=value` line), is replaced by an
 *    opaque id, so a call site that forgets [opaque] still cannot write a title.
 *  - no coordinates: a control is named, not located.
 *
 * Events (the whole vocabulary, in the order a reader meets them):
 *  - `surface.enter` / `surface.exit` -- a Bottom surface became visible / hidden. Carries `transition=<name>` (the
 *    animation WIRED for that edge) or `transition=none` (nothing is wired: it snaps). `host=<surface>` follows `display=` when
 *    the surface is composed inside another surface's window (`tabs`, `m-menu`), and is absent for the Presentation's own.
 *  - `transition.begin` / `transition.end` -- an animation ran, `ms=` is its measured length, `result=done|cancelled`.
 *  - `transition.missing` -- a surface edge named a transition and no `transition.begin` for it arrived in time. This is
 *    how a transition that is not actually wired becomes visible: it is the receipt of an absence.
 *  - `transition.unfinished` -- a `transition.begin` with no `transition.end` after a long wait.
 *  - `presentation.<create|recreate|show|ready|dismiss|reuse|reclaim|park|fail>` -- the Presentation window's life, with `reason=`.
 *  - `control.activate` -- a Bottom control was pressed, by stable `id=` (and `target=`, the stable id of what it
 *    acts on, when it acts on one: a platform id, a package, an opaque game id (`game:3fa9c1d2e0`); never a title).
 *  - `art.plate` -- an art-bearing tile: `hasArt=` (art is WIRED: a path or reference exists), `state=loading|loaded|failed`
 *    (`none` when `hasArt=false`) and `plate=visible|hidden`. The "plate" is the fallback drawn in place of the art (the
 *    console glyph, the letter glyph, the book); a tile's own raised square behind a loaded icon is not reported.
 *    `plate=visible hasArt=true` carries `breach=true` in EITHER `state=loading` or `state=failed`: R3 (no letter
 *    or plate while art is loading) and R8 (wired art that fails to load shows nothing) both forbid it. A tile's art lines are
 *    rate-limited; `suppressed=N` says how many were held back since the last one.
 *  - `focus.owner` -- the focus owner on the Bottom changed.
 *  - `theme.change` -- the Bottom's theme changed. `transition=theme-switch.crossfade` when a fade is wired, or
 *    `transition=none reason=reduced_motion` when the system's animator scale is zero and the switch is a deliberate cut: no
 *    begin, no end, and no `transition.missing`.
 *  - `session.<park|park_failed|return|exit>` -- a dual-screen game's session on the Bottom, with `session=<id>`. `park` is
 *    written only once Main has actually resumed; `park_failed reason=<why>` when the Main game could not be parked (Main
 *    would not start, did not resume in time, or the plan was superseded).
 *  - `tabs.close` -- a Tabs close, `outcome=requested|completed|failed|cleared`, `target=<package>`.
 *  - `display.select` -- the display the companion targets changed.
 */
object BottomUiReceiptFormat {
    const val TAG = "MimirBottomUi"

    /** Longest value a receipt carries; ids are short, so a longer value is something that should not be here. */
    const val MAX_VALUE_LENGTH = 64

    /** Display id used by a composition that is not hosted on a Bottom display. Receipts are not written for it. */
    const val NO_DISPLAY = -1

    private const val REDACTED = "redacted"

    /** Hex characters of the SHA-256 an [opaque] id carries: 40 bits, enough to tell a shelf's games apart, no way back. */
    const val OPAQUE_HEX_CHARS = 10

    private const val HEX = "0123456789abcdef"

    /**
     * An opaque, stable id for something whose real id spells a title or a file name: `<kind>:<first 10 hex of SHA-256>`,
     * e.g. `game:3fa9c1d2e0`. Deterministic, so the same game is the same id on every line and in every run.
     */
    fun opaque(kind: String, raw: String?): String {
        if (raw == null) return "none"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(OPAQUE_HEX_CHARS + 2)
        for (b in digest) {
            if (hex.length >= OPAQUE_HEX_CHARS) break
            hex.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return key(kind).ifEmpty { "id" } + ":" + hex.substring(0, OPAQUE_HEX_CHARS)
    }

    /** A game id as the resolver writes it: `catalog:<slug>`, `title:<platform>:<stem>`, `serial:<platform>:<serial>`, `hash:<hash>`. */
    private val GAME_ID_SHAPE = Regex("(?:^|[^A-Za-z0-9])((?:catalog|title|serial|hash):.*)$")

    /**
     * One value, made safe for a `key=value` line: no whitespace, no `=`, nothing URL- or path-shaped, and nothing that spells
     * a game. A game-id-shaped value, or free text (a value that needed cleaning, such as a title with spaces or brackets),
     * becomes an [opaque] id rather than a cleaned copy of itself.
     */
    fun token(value: Any?): String {
        val text = when (value) {
            null -> return "none"
            is Boolean, is Int, is Long, is Short, is Byte -> return value.toString()
            else -> value.toString().trim()
        }
        if (text.isEmpty()) return "none"
        if (looksLikeUrlOrPath(text)) return REDACTED
        GAME_ID_SHAPE.find(text)?.let { return opaque("game", it.groupValues[1]) }
        if (text.any { !isTokenChar(it) }) return opaque("text", text)
        return if (text.length > MAX_VALUE_LENGTH) text.take(MAX_VALUE_LENGTH) else text
    }

    private fun isTokenChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' || ch == ':' || ch == '#' || ch == '+'

    private fun looksLikeUrlOrPath(text: String): Boolean =
        text.contains("://") ||
            text.contains('\\') ||
            text.contains('?') ||
            text.contains('@') ||
            text.startsWith("/") ||
            text.startsWith("~") ||
            text.contains("/storage/") ||
            text.contains("/sdcard") ||
            text.contains(":/") ||
            (text.length > 2 && text[1] == ':' && text[0].isLetter() && (text[2] == '/' || text[2] == '\\'))

    /** Keys are literals in this codebase; this only guards against one that is not. */
    private fun key(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }.joinToString("")

    fun line(event: String, vararg fields: Pair<String, Any?>): String = buildString {
        append("ev=").append(token(event))
        for ((name, value) in fields) {
            append(' ').append(key(name)).append('=').append(token(value))
        }
    }

    /**
     * [reason] is written only when there is one, after `transition=none`: why this edge names no transition (`reduced_motion`,
     * `initial`), in the grammar `theme.change` uses. [host] is written only when there is one, right after `display=`: the surface
     * whose window composes this one (`tabs`, `m-menu`), so a picker in the Tabs drawer and a picker in the quick menu are two
     * different lines in a stack that reads `lattice,picker,tabs,m-menu,picker`.
     */
    fun surfaceEnter(
        surface: String,
        display: Int,
        from: String? = null,
        transition: String? = null,
        reason: String? = null,
        host: String? = null,
    ): String = line(
        "surface.enter",
        *listOfNotNull<Pair<String, Any?>>(
            "surface" to surface,
            "display" to display,
            host?.let { "host" to it },
            "from" to from,
            "transition" to (transition ?: "none"),
            reason?.let { "reason" to it },
        ).toTypedArray(),
    )

    fun surfaceExit(
        surface: String,
        display: Int,
        to: String? = null,
        transition: String? = null,
        reason: String? = null,
        host: String? = null,
    ): String = line(
        "surface.exit",
        *listOfNotNull<Pair<String, Any?>>(
            "surface" to surface,
            "display" to display,
            host?.let { "host" to it },
            "to" to to,
            "transition" to (transition ?: "none"),
            reason?.let { "reason" to it },
        ).toTypedArray(),
    )

    fun transitionBegin(name: String, display: Int): String =
        line("transition.begin", "name" to name, "display" to display)

    fun transitionEnd(name: String, display: Int, ms: Long, result: String): String =
        line("transition.end", "name" to name, "display" to display, "ms" to ms, "result" to result)

    fun transitionMissing(name: String, surface: String, display: Int, waitedMs: Long): String =
        line("transition.missing", "name" to name, "surface" to surface, "display" to display, "waitedMs" to waitedMs)

    fun transitionUnfinished(name: String, display: Int, runningMs: Long): String =
        line("transition.unfinished", "name" to name, "display" to display, "runningMs" to runningMs)

    fun presentation(action: String, display: Int, reason: String?, op: String? = null, extra: Pair<String, Any?>? = null): String =
        if (extra == null) {
            line("presentation.$action", "display" to display, "reason" to reason, "op" to op)
        } else {
            line("presentation.$action", "display" to display, "reason" to reason, "op" to op, extra)
        }

    fun control(id: String, display: Int, surface: String? = null, suppressed: Int = 0, target: String? = null): String {
        val fields = ArrayList<Pair<String, Any?>>(5)
        fields += "id" to id
        fields += "display" to display
        fields += "surface" to surface
        if (target != null) fields += "target" to target
        if (suppressed > 0) fields += "suppressed" to suppressed
        return line("control.activate", *fields.toTypedArray())
    }

    /**
     * An art-bearing tile. `hasArt` is "art is WIRED" (a path or reference exists); `state` is where that art is
     * ([BottomUiArtState]); `plate` is whether a fallback (the console glyph, the letter, the book) is drawn in its place.
     *
     * The breach is stated by the line itself, so a driver needs no knowledge of the law to fail it: a plate drawn over wired art is
     * `breach=true` in EITHER `state=loading` or `state=failed` (R3 and R8), and in `state=loaded` too, which no
     * surface does. [suppressed] counts art lines the rate limit held back since the last one for this tile.
     */
    fun artPlate(
        tile: String,
        display: Int,
        hasArt: Boolean,
        state: BottomUiArtState,
        plateVisible: Boolean,
        suppressed: Int = 0,
    ): String {
        val fields = ArrayList<Pair<String, Any?>>(7)
        fields += "tile" to tile
        fields += "display" to display
        fields += "hasArt" to hasArt
        fields += "state" to (if (hasArt) state else BottomUiArtState.NONE).token
        fields += "plate" to if (plateVisible) "visible" else "hidden"
        if (isArtPlateBreach(hasArt, plateVisible)) fields += "breach" to true
        if (suppressed > 0) fields += "suppressed" to suppressed
        return line("art.plate", *fields.toTypedArray())
    }

    fun isArtPlateBreach(hasArt: Boolean, plateVisible: Boolean): Boolean = hasArt && plateVisible

    fun focusOwner(owner: String?, previous: String?, display: Int): String =
        line("focus.owner", "display" to display, "owner" to owner, "prev" to previous)

    /** [reason] is written only when there is one: `reduced_motion` says a wired crossfade was deliberately cut. */
    fun theme(themeId: String?, display: Int, previous: String? = null, transition: String? = null, reason: String? = null): String =
        if (reason == null) {
            line("theme.change", "display" to display, "theme" to themeId, "prev" to previous, "transition" to (transition ?: "none"))
        } else {
            line(
                "theme.change", "display" to display, "theme" to themeId, "prev" to previous, "transition" to (transition ?: "none"),
                "reason" to reason,
            )
        }

    fun session(action: String, sessionId: String?, display: Int?, reason: String?, pkg: String? = null): String =
        line(
            "session.$action",
            "session" to sessionId,
            "display" to display,
            "reason" to reason,
            "pkg" to pkg,
        )

    /** A Tabs close, by outcome (`requested|completed|failed|cleared`), so the four exits no longer read alike. */
    fun tabClose(outcome: String, display: Int?, target: String?, op: String?): String =
        line("tabs.close", "outcome" to outcome, "display" to display, "target" to target, "op" to op)

    /**
     * The Bottom's surface stack on [display], bottom to top, as ONE line a reader can replay nothing for: a log
     * ring that has rotated `presentation.create` out still says which surface is on top. Each name is the id `surface.enter`
     * wrote, so it is token-safe; they are joined with `,` after being cleaned one by one, because [token] would turn a joined
     * list into an opaque id. An empty stack is `none`.
     */
    fun surfaceState(display: Int, stack: List<String>, reason: String): String =
        "ev=surface.state display=$display stack=" +
            (if (stack.isEmpty()) "none" else stack.joinToString(",") { token(it) }) +
            " reason=" + token(reason)

    fun displaySelect(selected: Int?, eligible: List<Int>): String =
        line("display.select", "selected" to selected, "eligible" to eligible.joinToString("+").ifEmpty { "none" })
}

/**
 * The Bottom's surface ids, in one place so a receipt, a derivation and a host driver cannot name the same thing two
 * ways. These are the design audit's names (2026-10-02) and are also the labels of the automated test steps, so a step
 * and the receipt it waits for read the same. An id is a noun of what the player sees, never a class name.
 */
object BottomSurface {
    /** The secondary-home floor's Mannaz cover: what the Bottom shows before the shelf lands. */
    const val COLD_START = "cold-start"
    const val LATTICE = "lattice"
    const val PLATFORM_SHELF = "platform-shelf"
    /** The platform shelf's game launch card (PLAY / Opens on). */
    const val LAUNCH_CARD = "launch-card"
    const val TOME_OPEN = "tome-open"
    /** The move-out confirm inside an open Tome (long-press a child): a page swap over the Leaves, nested in [TOME_OPEN]. */
    const val TOME_MOVE_OUT = "tome-move-out"
    const val ACTIVITY = "activity"
    /** The Activity page's media tile: its transport and seek lane. It is the tile on the page; the full panel it opens is [MUSIC_PANEL]. */
    const val ACTIVITY_MEDIA = "activity-media"

    /** Main's music panel at full size on the Bottom (W2d): a scene of the Activity page that the media tile opens. It stands over [ACTIVITY], as an open Tome stands over the lattice. */
    const val MUSIC_PANEL = "music-panel"
    const val DOCK = "dock"
    /** The app picker: All apps, the Quick launch picker and the pin picker. */
    const val PICKER = "picker"
    /** The native ᛗ quick menu. */
    const val M_MENU = "m-menu"
    const val TABS = "tabs"
    const val UTILITY = "utility"
    const val TWEAKER = "tweaker"
    const val THEME_SWITCH = "theme-switch"

    // Surfaces the audit's list does not name but the Bottom also shows.
    const val LAUNCH_PROMPT = "launch-prompt"

    /** The "Opens on" chooser a game's PLAY raises when no display is saved for it (Main / Bottom / ask). */
    const val DISPLAY_CHOOSER = "display-chooser"
    const val PIN_ACTIONS = "pin-actions"
    const val RETURN_GUARD = "return-guard"

    /**
     * The surfaces that stand as the Bottom's BASE: the startup cover and the scenes (the Home lattice, the platform
     * shelf, Activity). A stack with none of these in it has lost its floor, which is what a scene swap looks like for the instant
     * between the old scene's exit and the new one's enter; [BottomUiReceiptLog] holds such a snapshot back for it. Their ORDER in
     * a stack is not this list's: it is [drawingLayer]'s.
     */
    val SCENE_BASE: List<String> = listOf(COLD_START, LATTICE, PLATFORM_SHELF, ACTIVITY)

    /** Whatever the quick-launch layer raises above the instruments and the shelf while it is open: see [drawingLayer]. */
    private val PICKER_RAISERS: Set<String> = setOf(PICKER, PIN_ACTIONS, LAUNCH_PROMPT)

    /** A surface this table does not know draws above everything, so a reader that trusts the last element never taps under it. */
    const val UNKNOWN_LAYER = 1_000

    /** Added to the surface group's layers while a [PICKER_RAISERS] surface is open (MimirCompanionPresentation's z 0 -> 3). */
    private const val SURFACE_GROUP_RAISE = 300

    private val SURFACE_GROUP_LAYERS = 100..199

    /** Added to the quick-launch layer's own layers (121-123) while it is composed inside the Tabs drawer's window: 521-523, over `tabs` (500), under the chooser (600). */
    private const val TABS_HOSTED_RAISE = 400

    /**
     * Added to the same layers while the layer is composed inside the quick menu's own window (BottomQuickLaunchPanelHost.kt): 721-723,
     * over `m-menu` (700), because that window is an application overlay of its own and draws everything it shows above the menu layer.
     */
    private const val M_MENU_HOSTED_RAISE = 600

    /**
     * The drawing layer of every surface: larger draws above. Derived from where each is composed, not from the order it entered.
     *
     *  - 0, the ACTIVITY windows: the startup cover, the floor Activity's own window (CompanionSecondaryHomeActivity.kt:148), and the
     *    return guard, also an Activity (CompanionReturnActivity.kt). Android draws every Activity window below an application overlay
     *    window, and the Presentation sorts above ordinary activity windows on Thor (MimirCompanionPresentation.kt:2119), so nothing the
     *    Presentation shows is under either of them. The guard is NOT above the Presentation: on a reclaim the Presentation becomes
     *    visible again while the guard stays entered until its Activity is destroyed (CompanionReturnActivity.kt `onDestroy`), so a
     *    surface of the Presentation is on top of it. The two Activities share the layer and keep the order they entered in.
     *  - 100-199, the SURFACE GROUP: everything inside one `CompanionSurface`, which the Presentation draws at z 0
     *    (MimirCompanionPresentation.kt:1706). Inside it the scene is composed first (CompanionSurface.kt `renderScene`: lattice,
     *    the lattice or activity at 100), the scene's own overlays above it (an open Tome 110 and its move-out confirm 111, both in
     *    `CompanionHome`; Activity's media tile and, when it opens the music panel, that panel, 110), and the quick-launch layer after the scene Box (CompanionSurface.kt
     *    `CompanionQuickLaunchLayer`) in the order its body composes them: dock 120, picker 121, launch prompt 122, pin actions 123.
     *  - 200, the platform shelf, a SIBLING of the surface at z 1 (MimirCompanionPresentation.kt:130), and its launch card inside it.
     *  - 300, the instruments panel (Utility, Tweaker) at z 2 (MimirCompanionPresentation.kt:132).
     *  - while a picker, pin actions or the launch prompt is open the Presentation raises the whole surface group to z 3
     *    (MimirCompanionPresentation.kt:133 and :1706), above the shelf and the instruments: those layers move up by 300 (400-499).
     *  - 500, the Tabs drawer at z 4 (:134); 600, the game display chooser at z 5 (:135).
     *  - 700, the native quick menu: a `TYPE_APPLICATION_OVERLAY` window of its own (AndroidCompanionOverlayController.kt), above the
     *    Presentation and so above every Activity (WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY: displayed above all
     *    activity windows). The return guard is an Activity and is not up here.
     *  - the picker, pin actions and launch prompt can also be composed INSIDE the Tabs drawer's window, above its panel
     *    (CompanionRecentsDrawer.kt, `CompanionQuickLaunchLayer`). Hosted there they draw above `tabs` and below the display chooser
     *    (521-523), and they do not raise the surface group, because the Presentation is never told a picker is open ([drawingLayer]'s
     *    `host`, [TABS_HOSTED_RAISE]).
     *  - and inside the quick menu's own window (BottomQuickLaunchPanelHost.kt, `CompanionQuickLaunchLayer`) they draw above `m-menu`
     *    (721-723, [M_MENU_HOSTED_RAISE]): that window is an application overlay of its own, so nothing in it is under the menu layer.
     */
    private val LAYERS: Map<String, Int> = mapOf(
        COLD_START to 0,
        LATTICE to 100, ACTIVITY to 100,
        TOME_OPEN to 110, ACTIVITY_MEDIA to 110, MUSIC_PANEL to 110, TOME_MOVE_OUT to 111,
        DOCK to 120, PICKER to 121, LAUNCH_PROMPT to 122, PIN_ACTIONS to 123,
        PLATFORM_SHELF to 200, LAUNCH_CARD to 201,
        UTILITY to 300, TWEAKER to 300,
        TABS to 500, DISPLAY_CHOOSER to 600,
        M_MENU to 700, RETURN_GUARD to 0,
    )

    /** Whether [visible] contains a surface that raises the surface group above the shelf and the instruments. */
    fun raisesSurfaceGroup(visible: Collection<String>): Boolean = visible.any { it in PICKER_RAISERS }

    /**
     * [surface]'s drawing layer; [raised] is [raisesSurfaceGroup] of what is visible with it. [host] is the surface whose window
     * composes [surface] when that is not the Presentation's own surface group: [TABS] for the picker, pin actions and launch prompt
     * opened inside the Tabs drawer, which composes them above its panel, and [M_MENU] for the same three opened inside the quick menu's
     * window, which composes them above the menu.
     */
    fun drawingLayer(surface: String, raised: Boolean, host: String? = null): Int {
        val layer = LAYERS[surface] ?: return UNKNOWN_LAYER
        if (host == TABS && surface in PICKER_RAISERS) return layer + TABS_HOSTED_RAISE
        if (host == M_MENU && surface in PICKER_RAISERS) return layer + M_MENU_HOSTED_RAISE
        return if (raised && layer in SURFACE_GROUP_LAYERS) layer + SURFACE_GROUP_RAISE else layer
    }

    /**
     * The surfaces the Presentation window's own composition reports, and so the only ones a new Presentation may reset
     * ([BottomUiReceiptLog.resetWindow]). Not here: [COLD_START] (the secondary-home floor), [RETURN_GUARD] (the return
     * activity), [M_MENU] (a native window), [TABS] and the quick-launch layer's [DOCK] / [PICKER] / [PIN_ACTIONS] /
     * [LAUNCH_PROMPT] (also composed in windows lent on the Bottom), and [THEME_SWITCH]. Those windows' state is theirs.
     */
    val PRESENTATION_OWNED: Set<String> = setOf(
        LATTICE, PLATFORM_SHELF, LAUNCH_CARD, TOME_OPEN, TOME_MOVE_OUT, ACTIVITY, ACTIVITY_MEDIA, MUSIC_PANEL, DISPLAY_CHOOSER, UTILITY, TWEAKER,
    )
}

/** Where a tile's wired art is, as `art.plate state=`; [NONE] when no art is wired at all. */
enum class BottomUiArtState(val token: String) {
    NONE("none"),
    LOADING("loading"),
    LOADED("loaded"),
    FAILED("failed"),
    ;

    companion object {
        /**
         * The state of a tile's art: none when nothing is wired, loaded when the bitmap is there, failed when the decode finished
         * with nothing, and loading otherwise.
         */
        fun of(hasArt: Boolean, loaded: Boolean, failed: Boolean): BottomUiArtState = when {
            !hasArt -> NONE
            loaded -> LOADED
            failed -> FAILED
            else -> LOADING
        }
    }
}
