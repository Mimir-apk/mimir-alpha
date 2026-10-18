package com.gruning.mimir.rewrite.core.companionnative

import android.app.AppOpsManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.annotation.StringRes
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gruning.mimir.rewrite.core.model.MimirMarkPlacement
import com.gruning.mimir.rewrite.core.model.mimirMarkBoxRect
import com.gruning.mimir.rewrite.core.model.mimirMarkDrawRect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "MimirCompanionNative"

/**
 * R12 dim-and-keep strength (HP-2: was 0.35, which COMPOSED with the button's 0.74 resting alpha to a
 * ~26% ghost that appeared pale). Shipping dims to 0.68 and stays clearly alive; the touch
 * animations now route through [restingAlpha]/[pressedAlpha] so a press can no longer clobber the dim.
 */
private const val DimmedAlpha = 0.80f

/** The button's idle strength when NOT dimmed (the design's own resting look). */
private const val RestingAlpha = 0.90f

/** How long the Bottom's coin takes to fade out when a Mimir panel opens over it, and back in when it closes (the overlay tier's 180 ms, `MimirMotion.OverlayMs`). */
internal const val CompanionPanelCoinFadeMs = 180L

/**
 * Whether the coin is hidden for a Mimir panel: only the Bottom's coin ([CompanionOverlayRole.SECONDARY_COMPANION]) answers, and only while the panel's owner has asked
 * ([requested], [AndroidCompanionOverlayController.setCoinHiddenForPanel]). Main's own coin (PRIMARY_SINGLE) is never hidden by a panel, and nothing hides the coin over a
 * foreign app or on the lattice: with no panel up nothing is asked.
 */
internal fun companionCoinHiddenForPanel(requested: Boolean, role: CompanionOverlayRole?): Boolean =
    requested && role == CompanionOverlayRole.SECONDARY_COMPANION

/** The coin's resting alpha: 0 while hidden for a panel (and the idle-hidden coin stays at 0), else the dim state, else the resting look. */
internal fun companionCoinRestingAlpha(hiddenForPanel: Boolean, dimmed: Boolean): Float =
    if (hiddenForPanel) 0f else if (dimmed) DimmedAlpha else RestingAlpha

/** The window flag that makes the coin's window pass every touch through while it is hidden for a panel; 0 when it is shown. */
internal fun companionCoinTouchFlag(hiddenForPanel: Boolean): Int =
    if (hiddenForPanel) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0

/**
 * CP03 edge-back tuning . The catch strip is [EdgeCatcherWidthDp] wide on the right edge;
 * an inward drag past [EdgeCatcherTriggerDp] fires the return. Both eased from the first pass (24/48) so the
 * gesture lands more readily — wider to grab, shorter to commit — without becoming twitchy.
 */
private const val EdgeCatcherWidthDp = 32
private const val EdgeCatcherTriggerDp = 40

internal const val MButtonSizeDp = 56
internal const val MButtonSafeInsetDp = 14
internal const val PrimaryIdleWatcherSizePx = 1

/** R9a: how often a continuous joystick stream on the Bottom's menu is noted as screen activity (keys are noted each). */
internal const val PadActivityThrottleMs = 100L
internal const val PrimaryIdleOutsideRevealDelayMs = 48L
internal const val CompanionMenuWidthDp = 244
/**
 * **Derived from the rows rather than hardcoded.**
 *
 * These were two hand-written numbers and one of them was wrong on the device. Photographed on the AYN
 * Thor, display 4: the companion menu rendered 428 px tall on a 2.3077 px/dp panel — 185.5 dp, matching
 * the old literal 186 — and CLIPPED, showing its title, `Tabs`, `Quick launch`, and `Utility` sliced
 * through mid-row at the window edge. Two of its five rows could not be reached at all.
 *
 * The primary menu's 273 was right for its four rows (38 + 4×54 + 16 = 270), which is exactly why the
 * companion's was never noticed: the constant that matched its content hid the one that did not. The
 * companion grew from two rows to five, and its height did not follow.
 *
 * So the height is now COMPUTED FROM THE ROWS IT HAS TO HOLD. A row added to
 * [companionOverlayMenuRows] resizes its own window; there is no second number to remember.
 */
internal fun companionOverlayMenuHeightDp(itemCount: Int): Int =
    CompanionMenuTitleHeightDp +
        companionOverlayMenuGridRows(itemCount) * CompanionMenuSlotHeightDp +
        (companionOverlayMenuGridRows(itemCount) - 1).coerceAtLeast(0) * CompanionMenuCoinRowGapDp +
        2 * CompanionMenuVerticalInsetDp

/**
 * Balanced rows, at most three across. Four items make 2x2, not 3+1.
 *
 * A lone cell under a full row reads as a mistake rather than a fifth command, and 244 dp is too narrow
 * for four across at a legible coin size.
 */
internal fun companionOverlayMenuGridRows(itemCount: Int): Int = (itemCount + 2) / 3

internal fun companionOverlayMenuGridColumns(itemCount: Int): Int {
    val rows = companionOverlayMenuGridRows(itemCount)
    return if (rows == 0) 0 else (itemCount + rows - 1) / rows
}

internal const val CompanionMenuOuterCornerDp = 8

/**
 * The coin, its label band, and the slot that is the actual touch target.
 *
 *  Measured on the Thor
 * before it changed: 37% of the panel was empty, ~25 dp of vacancy in every 54 dp row. The cause was
 * the SHAPE of the row -- a thin ribbon of 14 sp text floating in a tall band with a caption under it
 * restating the label. Two ranks of type per row, five times, is the generic settings list.
 *
 * A coin is a mark that stands the full height of its slot, so the slot is filled by its content
 * instead of padded around it, and the mark carries the product's own runic language rather than a
 * repeated accent tick. The captions are not deleted -- they move to `contentDescription`, where they
 * remain exactly as true and are read aloud to anyone who needs them.
 */
internal const val CompanionMenuCoinSizeDp = 56
internal const val CompanionMenuCoinLabelHeightDp = 28
internal const val CompanionMenuCoinLabelGapDp = 4
internal const val CompanionMenuCoinRowGapDp = 10
internal const val CompanionMenuSlotHeightDp =
    CompanionMenuCoinSizeDp + CompanionMenuCoinLabelGapDp + CompanionMenuCoinLabelHeightDp
/**
 * The minimum touch target is 48 dp (`MimirSpacing.minTarget`), and this said 44.
 *
 * It never bit because [CompanionMenuRowHeightDp] is 54 and the row is what actually gets touched — so
 * the wrong number sat here being rescued by a neighbour. A minimum that is only correct by accident is
 * one edit away from being incorrect in fact.
 */
internal const val CompanionMenuMinimumTouchTargetDp = 48
/**
 * One gutter, both roles —
 *
 * This was 29 on the companion to clear an 18 dp spine rail that no longer exists, and 12 on the
 * primary. A vacated gutter is the same mistake as a retired view whose height is still spent: it goes
 * out with the thing it was making room for.
 */
internal const val CompanionMenuContentStartDp = 12
internal const val CompanionMenuTitleHeightDp = 38
internal const val CompanionMenuVerticalInsetDp = 8

/**
 * The menu's two label roles, as Main's type scale names them: the title is `MimirTypeStep.Label` (11 sp),
 * a coin's caption `MimirTypeStep.Micro` (9 sp). They were 11.5 and 9.5 in the platform's bold. This module cannot read core-ui, so
 * the sizes are local and the face arrives through [AndroidCompanionOverlayController.menuLabelTypeface].
 */
internal const val CompanionMenuTitleTextSp = 11f
internal const val CompanionMenuCoinLabelTextSp = 9f

/**
 * The ᛗ button/menu swap, on Main's swap tier: the outgoing root fades over [CompanionOverlaySwapDurationMs];
 * at [CompanionOverlaySwapHandoffMs], where the house curve has it at alpha 0.044, it is removed and the replacement is added, and
 * the replacement fades in over [CompanionOverlaySwapDurationMs] and no scale, rising [CompanionOverlaySwapRiseDp] when it is the Bottom's
 * full-display menu (a window sized to its view would clip a rise): about 310 ms in all, the two overlapping for the last 50 ms of the outgoing's tail. This module has no core-ui dependency, so these are local
 * mirrors of `MimirMotion.OverlayMs`, `OverlayDissolveCoverMs` and `SwapTravelDp`; the app's `CompanionOverlaySwapSourcePinTest` reads
 * this file and holds them equal.
 */
internal const val CompanionOverlaySwapDurationMs = 180L
internal const val CompanionOverlaySwapHandoffMs = 130L
internal const val CompanionOverlaySwapRiseDp = 8

/**
 * The ᛗ coin's press on the BOTTOM, the Bottom's one press response: the coin draws in to [CompanionButtonPressScale] and
 * back on [CompanionButtonPressMs], both ways. This module has no core-ui dependency, so these are local mirrors of `MimirPressScale` and `MimirMotion.FocusMs`; the
 * app's `CompanionButtonPressSourcePinTest` reads this file and holds them equal. The coin keeps its pressed alpha ([pressedAlpha]) and the 56 dp window it fills,
 * which a scale below 1 can never spill past.
 *
 * **Main's coin (`PRIMARY_SINGLE`) is not the Bottom's and keeps what it had**: 0.92 over 90 ms down, 1 over 120 ms up ([CompanionMainButtonPressScale],
 * [CompanionMainButtonPressMs], [CompanionMainButtonReleaseMs]). Main's press timing remains unchanged; the pin holds these three literals too.
 */
internal const val CompanionButtonPressScale = 0.97f
internal const val CompanionButtonPressMs = 140L
internal const val CompanionMainButtonPressScale = 0.92f
internal const val CompanionMainButtonPressMs = 90L
internal const val CompanionMainButtonReleaseMs = 120L

/** The press the coin of [role] draws: the Bottom's is the one press response, Main's is exactly what it was. */
internal fun companionButtonPressScale(role: CompanionOverlayRole): Float =
    if (role == CompanionOverlayRole.SECONDARY_COMPANION) CompanionButtonPressScale else CompanionMainButtonPressScale

internal fun companionButtonPressMs(role: CompanionOverlayRole): Long =
    if (role == CompanionOverlayRole.SECONDARY_COMPANION) CompanionButtonPressMs else CompanionMainButtonPressMs

internal fun companionButtonReleaseMs(role: CompanionOverlayRole): Long =
    if (role == CompanionOverlayRole.SECONDARY_COMPANION) CompanionButtonPressMs else CompanionMainButtonReleaseMs

/**
 * How long the M menu's blur takes to clear after a pick on the main screen hands the screen to Mimir: the clock of
 * Mimir's own scene glass ramp (core-ui's MimirMotion.OverlayMs, 180 ms), on the same curve, so one blur gives way to
 * the other and the scene stays soft throughout.
 *
 * A former 700 ms hold blurred Utility/Quick Launch/Tweaker as they arrived on Nova (2026-09-29),
 * because the menu blur sat above Mimir's glass rather than below it. The menu
 * is an overlay window above Mimir, and FLAG_BLUR_BEHIND blurs the whole screen behind it, Mimir's arriving panel
 * included, so the panel came up blurred and snapped sharp when the window went. Cleared on this clock, the blur is
 * gone before Mimir's panel (held at alpha 0 through its 130 ms lead) has shown more than a trace.
 */
internal const val CompanionMenuBlurHandoffMs = 180L

/** The handoff's blur radius at [fraction] of the way from [fromPx] (1) to clear (0). */
internal fun companionMenuHandoffBlurRadius(fromPx: Int, fraction: Float): Int =
    (fromPx * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, fromPx.coerceAtLeast(0))

internal fun companionOverlayMenuLongPressItem(item: QuickMenuItem): QuickMenuItem? = when (item) {
    QuickMenuItem.SYSTEM_TABS,
    QuickMenuItem.SECONDARY_TABS,
    -> QuickMenuItem.SYSTEM_TABS
    else -> null
}

internal enum class CompanionOverlayChrome {
    BUTTON,
    MENU,
}

/**
 * Whether the menu overlay window takes key focus. PRIMARY: exactly while its menu is open. SECONDARY (R9a, 2026-10-02):
 * exactly while a Bottom pad owner is set ([BottomPadOwner]), whatever the menu's own openness says. That is the one rule the
 * window flags, the root's focusability and the key route all follow, so they cannot disagree. Every other state, and every
 * other window on display 4 (the button, the edge catcher, the Presentation), stays non-focusable.
 */
internal fun primaryQuickMenuOwnsWindowFocus(
    role: CompanionOverlayRole,
    menuOpen: Boolean,
    secondaryPadOwner: BottomPadOwner? = null,
): Boolean = when (role) {
    CompanionOverlayRole.PRIMARY_SINGLE -> menuOpen
    CompanionOverlayRole.SECONDARY_COMPANION -> secondaryPadOwner != null
}

internal enum class SecondaryMenuTouchRoute {
    DELEGATE,
    CONSUME,
    TERMINATE,
}

/**
 * The secondary menu's full-display host owns a gesture only when its initial DOWN was outside the card.
 * Once a gesture starts inside the card, later coordinates cannot turn a title drag into a dismissal.
 */
internal class SecondaryMenuOutsideTouchCapture {
    private var active = false
    private var outside = false

    val isActive: Boolean
        get() = active

    fun dispatch(actionMasked: Int, downInsideCard: Boolean): SecondaryMenuTouchRoute {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true
                outside = !downInsideCard
                if (outside) SecondaryMenuTouchRoute.CONSUME else SecondaryMenuTouchRoute.DELEGATE
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> if (outside) SecondaryMenuTouchRoute.CONSUME else SecondaryMenuTouchRoute.DELEGATE
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                val route = if (outside) SecondaryMenuTouchRoute.TERMINATE else SecondaryMenuTouchRoute.DELEGATE
                active = false
                outside = false
                route
            }
            // ACTION_OUTSIDE is an observation without the original gesture's terminal event. The
            // full-display host must not use it as a second, flag-only dismissal path.
            MotionEvent.ACTION_OUTSIDE -> SecondaryMenuTouchRoute.DELEGATE
            else -> if (outside) SecondaryMenuTouchRoute.CONSUME else SecondaryMenuTouchRoute.DELEGATE
        }
    }

}

/** Reveal for both the normal outside-touch observation and the watcher's one-pixel direct hit. */
internal fun primaryIdleWatcherShouldReveal(actionMasked: Int): Boolean =
    actionMasked == MotionEvent.ACTION_OUTSIDE || actionMasked == MotionEvent.ACTION_DOWN

/**
 * The screen activity one event on a native overlay window stands for. A DOWN is the player's own
 * touch; `ACTION_OUTSIDE` is the copy of a touch another window received; the rest of a gesture is not new activity.
 */
fun overlayScreenActivityFor(actionMasked: Int): OverlayScreenActivity? = when (actionMasked) {
    MotionEvent.ACTION_DOWN -> OverlayScreenActivity.DIRECT
    MotionEvent.ACTION_OUTSIDE -> OverlayScreenActivity.OUTSIDE
    else -> null
}

/**
 * The edge catcher remains touch-through over an idle own mirror. A visible Tabs Presentation and the
 * Bottom shelf's own open folder ("Tome", R6-P2-08) are the scoped exceptions: each is a closable layer
 * over the idle shelf, and EdgeBack may close whichever of them is up.
 *
 * Swipes must reach the emulator while a dual-screen game holds Bottom. While Mimir knows a dual-screen game holds the
 * Bottom (the DS handoff lease minted by "Return to game"), the strip is never touchable — a swipe there
 * must reach the emulator's own touch screen, not Mimir. This is the outer `&& !…`: it only ever removes
 * admission, never grants it.
 */
internal fun companionEdgeCatcherIsTouchable(
    dimmed: Boolean,
    ownMirrorDrawerEdgeBackEnabled: Boolean,
    dualScreenGameOwnsBottom: Boolean = false,
    // the same scoped own-mirror exception the visible Tabs drawer gets above, given to the
    // Bottom shelf's own open folder ("Tome") too — the other closable layer EdgeBack now reaches.
    ownMirrorFolderEdgeBackEnabled: Boolean = false,
    // a keyboard on the Bottom fills it, and the strip drawn over it took the keys' right-edge touches and
    // reclaimed the Bottom. Like the DS block above, this only ever removes admission.
    bottomImeShown: Boolean = false,
): Boolean = (!dimmed || ownMirrorDrawerEdgeBackEnabled || ownMirrorFolderEdgeBackEnabled) &&
    !dualScreenGameOwnsBottom && !bottomImeShown

internal enum class PrimaryQuickMenuKeyResult {
    PASS_TO_SYSTEM,
    ROUTE_VOLUME_TO_SYSTEM,
    CONSUME,
    DISMISS_AFTER_RELEASE,
}

internal fun primaryQuickMenuKeyResult(
    keyCode: Int,
    action: Int,
): PrimaryQuickMenuKeyResult = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
    -> PrimaryQuickMenuKeyResult.ROUTE_VOLUME_TO_SYSTEM

    KeyEvent.KEYCODE_POWER -> PrimaryQuickMenuKeyResult.PASS_TO_SYSTEM

    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BACK,
    -> if (action == KeyEvent.ACTION_UP) {
        PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE
    } else {
        PrimaryQuickMenuKeyResult.CONSUME
    }

    else -> PrimaryQuickMenuKeyResult.CONSUME
}

/**
 * One row of the ᛗ menu. The words are string resources, so the row is data a plain JVM test can compare; the
 * controller resolves [titleRes] and [subtitleRes] with the menu window's `Resources` when it builds the coin.
 */
internal data class CompanionOverlayMenuRowCopy(
    @StringRes val titleRes: Int,
    /**
     * Still product truth, and still required — it is now the accessible description rather than a
     * drawn second line. Retiring the caption from the FACE is a layout decision; retiring what it
     * says would be a copy decision, and nothing about the command changed.
     */
    @StringRes val subtitleRes: Int,
    /**
     * **Runes are reused where the command is the same one, and argued where it is not.**
     *
     * Tiwaz (ᛏ) and Sowilo (ᛋ) are lifted verbatim off `MimirQuickRailAction`: that rail already spends
     * them on quick launch and on Utility, and the same capability wearing two marks on two surfaces is
     * how a visual language stops being one. The other three are new here, on the pattern the rail set
     * when it argued Othala for Files rather than reaching for a pictograph:
     *
     *  - Berkanan (ᛒ) for TABS — an upright carrying two stacked chambers: the apps this screen is
     *    holding open, drawn as the thing that holds them.
     *  - Dagaz (ᛞ) for SWAP — the rune of the turning point, drawn as two triangles meeting at a waist.
     *    An hourglass is what an exchange between two places looks like.
     *  - Thurisaz (ᚦ) for TWEAKER — the thorn, raw force. Clocks and fan are the machine's own output.
     *
     * **TWO OF THESE ARE SECOND CHOICES, AND THE FIRST PAIR FAILED ON GLASS RATHER THAN IN REVIEW.**
     * Ingwaz (ᛜ) was chosen for TABS on the argument that a closed lozenge reads as a pane at coin size.
     * Photographed on the Thor it measured 8.2 x 8.2 dp against every other glyph's 16.9 dp — less than
     * half the height of its neighbours, a bullet rather than a mark. Ehwaz (ᛖ) was chosen for SWAP
     * because its meaning is two things moving as a pair; in this typeface it draws as a capital M and
     * sat four inches under the ᛗ in the header. A rune that collides with the BRAND is worse than no
     * rune, and codepoint-uniqueness — which the pin checks — cannot see it.
     *
     * The lesson is the one the geometry rule already states: a mark is not chosen when the argument is
     * good, it is chosen when it has been seen at the size it ships at.
     *
     * No rune appears twice within one role's menu; the ᛗ in the header is the brand mark, not a row.
     */
    val rune: String,
    val item: QuickMenuItem,
)

internal fun companionOverlayMenuRows(
    role: CompanionOverlayRole,
    secondaryReturnAvailable: Boolean = false,
): List<CompanionOverlayMenuRowCopy> =
    if (role == CompanionOverlayRole.PRIMARY_SINGLE) {
        listOf(
            CompanionOverlayMenuRowCopy(R.string.companion_menu_tabs, R.string.companion_menu_tabs_system_subtitle, "ᛒ", QuickMenuItem.SYSTEM_TABS),
            CompanionOverlayMenuRowCopy(R.string.companion_menu_quick_launch, R.string.companion_menu_quick_launch_subtitle, "ᛏ", QuickMenuItem.QUICK_LAUNCH),
            CompanionOverlayMenuRowCopy(R.string.companion_menu_utility, R.string.companion_menu_utility_subtitle, "ᛋ", QuickMenuItem.UTILITY),
            CompanionOverlayMenuRowCopy(R.string.companion_menu_tweaker, R.string.companion_menu_tweaker_subtitle, "ᚦ", QuickMenuItem.TWEAKER),
        )
    } else {
        buildList {
            add(CompanionOverlayMenuRowCopy(R.string.companion_menu_tabs, R.string.companion_menu_tabs_secondary_subtitle, "ᛒ", QuickMenuItem.SECONDARY_TABS))
            if (secondaryReturnAvailable) {
                add(CompanionOverlayMenuRowCopy(R.string.companion_menu_return, R.string.companion_menu_return_subtitle, "ᛇ", QuickMenuItem.SECONDARY_RETURN))
            }
            add(CompanionOverlayMenuRowCopy(R.string.companion_menu_quick_launch, R.string.companion_menu_quick_launch_secondary_subtitle, "ᛏ", QuickMenuItem.QUICK_LAUNCH))
            // **name the display, on this role only.** Two of these four rows
            // already do ("Bottom screen apps", "Move app to main") and Utility did not, so the menu
            // named a display exactly where it was incidental and stayed silent where it was the whole
            // question. The honest answer turned out to be BOTH, not one: `BrightnessOverlay` renders a
            // primary slider unconditionally and a secondary one under `capabilities.showsSecondary`,
            // so on this role Utility reaches both screens.
            //
            // PRIMARY_SINGLE above keeps the plain subtitle deliberately — there is one screen there,
            // and naming a display when there is only one is noise, not discoverability. The rule this
            // encodes: **a row names its display exactly where more than one exists.**
            add(CompanionOverlayMenuRowCopy(
                R.string.companion_menu_utility,
                R.string.companion_menu_utility_secondary_subtitle,
                "ᛋ",
                QuickMenuItem.UTILITY,
            ))
            add(CompanionOverlayMenuRowCopy(R.string.companion_menu_swap, R.string.companion_menu_swap_subtitle, "ᛞ", QuickMenuItem.SECONDARY_SWAP))
            // the full-screen trackpad is reachable from the Bottom menu too, not only
            // from the keyboard's PAD key. Last, so no existing row moves; the rune is new to this menu.
            add(CompanionOverlayMenuRowCopy(R.string.companion_menu_trackpad, R.string.companion_menu_trackpad_subtitle, "ᚱ", QuickMenuItem.TRACKPAD))
            // The Bottom shelf owns its dedicated Tweaker action. The native M menu stays focused on
            // cross-surface commands; the single-display menu below retains its direct Tweaker entry.
        }
    }

/** The menu's header in the language of [res]: the brand mark, then the display the menu is on. */
internal fun companionOverlayMenuTitle(res: Resources, role: CompanionOverlayRole): String =
    res.getString(
        if (role == CompanionOverlayRole.PRIMARY_SINGLE) R.string.companion_menu_title_primary else R.string.companion_menu_title_secondary,
    )

/**
 * The title with its runes in the system face. The words wear the product's face ([AndroidCompanionOverlayController.menuLabelTypeface]),
 * which has no runic glyphs; left to font fallback the rune would still draw, but in whatever face the platform picks. The coins'
 * runes are `CompanionMenuCoinView`'s own paint and stay `Typeface.DEFAULT`; this keeps the title's the same.
 */
internal fun companionOverlayMenuTitleSpanned(title: String): CharSequence {
    val spanned = android.text.SpannableString(title)
    title.forEachIndexed { index, char ->
        if (char in '\u16A0'..'\u16FF') {
            spanned.setSpan(
                android.text.style.TypefaceSpan(Typeface.DEFAULT_BOLD),
                index,
                index + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
    return spanned
}

internal data class CompanionOverlayThemeChangePlan(
    val changed: Boolean,
    val rebuildAttachedView: Boolean,
    val chrome: CompanionOverlayChrome,
    val position: Pair<Int, Int>?,
)

internal fun planCompanionOverlayThemeChange(
    current: CompanionOverlayTheme,
    next: CompanionOverlayTheme,
    attached: Boolean,
    menuOpen: Boolean,
    position: Pair<Int, Int>?,
): CompanionOverlayThemeChangePlan {
    val changed = current != next
    return CompanionOverlayThemeChangePlan(
        changed = changed,
        rebuildAttachedView = changed && attached,
        chrome = if (menuOpen) CompanionOverlayChrome.MENU else CompanionOverlayChrome.BUTTON,
        position = position,
    )
}

/**
 * Real Android overlay controller for the rewrite companion. The button/menu are native Views in a
 * TYPE_APPLICATION_OVERLAY window created from the secondary-display context, so they can z-order over a
 * foreign foreground app on display 2. The button and the edge catcher remain touch-only and not focusable, and so does
 * the secondary menu until the pad owns it. While the PRIMARY_SINGLE quick menu is visibly open, that one window temporarily
 * owns raw key/HAT input so the covered Mimir or foreign-app surface cannot mutate behind it; the menu
 * root keeps one lit index of its own (OR-11: D-pad, HAT and left stick move it, A, Enter and D-pad centre activate on
 * release, B and Back dismiss) and draws it as a ring view above the coins. No row is a focusable View and nothing here
 * touches the app's FocusAuthority.
 *
 * R9a : the SECONDARY menu does the same while [secondaryPadOwner] is set, and only then. The owner is set
 * when the touch long-press opens the menu and cleared on every way out ([clearSecondaryPadOwner]); the window is focusable
 * for exactly that span ([primaryQuickMenuOwnsWindowFocus]). Nothing requests focus across displays, so a game on Main keeps
 * the pad whenever the platform has focus on display 0. After an accepted pick the menu collapses and the owner clears: the
 * pad then lands where it did before R9a (slice 2 adds the panel owners that keep it for Utility, Quick Launch and Tabs).
 *
 * PROCESS SINGLETON (adversarial review 2026-07-10): this was one-per-MainActivity, and activity
 * recreation (which play sessions cause constantly — launch → background → maybe destroy → return)
 * raced the posted old-dismiss/new-mount and LEAKED stacked M windows. Device evidence: two M-button
 * windows at the same position, both `isReadyForDisplay()=false`, shadowing the live M — "the M stopped
 * working". One controller per process = one window, mounts idempotent, no interleaving to lose.
 */
class AndroidCompanionOverlayController private constructor(
    context: Context,
) : CompanionOverlayController {
    companion object {
        @Volatile private var shared: AndroidCompanionOverlayController? = null

        fun instance(context: Context): AndroidCompanionOverlayController =
            shared ?: synchronized(this) {
                shared ?: AndroidCompanionOverlayController(context).also { shared = it }
            }
    }

    private val appContext = context.applicationContext

    /**
     * The face the menu's words are set in: the product's IBM Plex, which this module cannot resolve (it has no core-ui dependency), so
     * the app hands it over in its `startCompanionOverlay`, before the first mount. Null (no app wiring, a font that would not resolve)
     * leaves the platform's bold. The rune coins and the title's rune never read it: Plex has no runic glyphs.
     */
    @Volatile
    var menuLabelTypeface: Typeface? = null

    /**
     * The `Resources` the menu's words (and the button's spoken name) are read from, given the window's context. The overlay lives in the
     * process, not in an Activity, so below API 33 its context is in the device language even when the player chose another in Mimir:
     * the app hands over a function that applies that choice. Null (no app wiring) reads the context's own.
     */
    @Volatile
    var menuResourcesProvider: ((Context) -> Resources)? = null

    private fun menuResources(context: Context): Resources = menuResourcesProvider?.invoke(context) ?: context.resources
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences("mimir_companion_overlay", Context.MODE_PRIVATE)
    private val displayManager = appContext.getSystemService(DisplayManager::class.java)
    private val appOpsManager = appContext.getSystemService(AppOpsManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val _actions = MutableSharedFlow<CompanionOverlayAction>(extraBufferCapacity = 24)
    private val _overlayPermissionMissing = MutableStateFlow(false)
    private val _activeTarget = MutableStateFlow<CompanionOverlayTarget?>(null)
    private val _primaryButtonBounds = MutableStateFlow<OverlayButtonBounds?>(null)
    override val primaryButtonBounds: StateFlow<OverlayButtonBounds?> = _primaryButtonBounds.asStateFlow()
    /** The glide of the single-display coin settling on its edge ([MButtonRestGlides]); a new touch lands it at once, an unmount or the next glide cancels it. Main thread. */
    private var buttonRestGlide: ValueAnimator? = null

    private var mountedOverlayDisplayId: Int? = null
    private var displayContext: Context? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val _quickMenuOpen = MutableStateFlow(false)
    override val quickMenuOpen: StateFlow<Boolean> = _quickMenuOpen.asStateFlow()

    /**
     * What the Presentation blurs its scene from: [_quickMenuOpen], leading it while a swap fades out.
     *
     * `menuOpen` lands only when the swap does, at the outgoing fade's hand-over and the window removal, and the
     * scene's 160 ms blur waited for it: 342 ms tap-to-sharp on close, 255 ms on open (Thor recordings). This flow takes the destination value when the outgoing fade STARTS
     * ([animateOverlaySwap]) so the blur runs with the fade. It is its own flow because `menuOpen` is logic:
     * window size, focus, alpha and the live-rebuild and collapse guards read it, and none of that may move.
     *
     * The lead exists only while that swap is in flight. Every `menuOpen` write lands this flow on the same
     * value, and a cancelled or failed swap resyncs it ([cancelOverlayTransition]), so a blur can never
     * outlive the menu it was for.
     */
    private val _quickMenuVisualOpen = MutableStateFlow(false)
    override val quickMenuVisualOpen: StateFlow<Boolean> = _quickMenuVisualOpen.asStateFlow()

    /**
     * True while a button/menu swap is in flight: from the moment the outgoing window starts to fade until the INCOMING window's
     * own animation has ended, or the swap ends without landing (cancelled, failed or unmounted). A reader that wants to know when
     * the menu has finished moving reads this, not [quickMenuOpen], which lands halfway (when the outgoing window is replaced).
     * Observation only: nothing in the controller reads it.
     */
    private val _quickMenuSwapInFlight = MutableStateFlow(false)
    override val quickMenuSwapInFlight: StateFlow<Boolean> = _quickMenuSwapInFlight.asStateFlow()

    /**
     * Backed by [_quickMenuOpen] so that EVERY assignment publishes, not only the one in [replaceView].
     *
     * It is written in three places — mount, unmount and the view swap — and only the swap is the one a
     * reader thinks of. A flow fed from the swap alone would stay true through an unmount that happened
     * with the menu up (display removed, visibility dropped, panel revoked), leaving the shelf behind it
     * treated for a window that is no longer on screen and nothing left to close to undo it. Making the
     * field itself the flow removes the possibility rather than adding a fourth place to remember.
     *
     * `StateFlow` conflates equal values, so the theme-change path's rebuild — which re-asserts the SAME
     * openness on every material change — publishes nothing and cannot flicker a backdrop.
     */
    private var menuOpen: Boolean
        get() = _quickMenuOpen.value
        set(value) {
            _quickMenuOpen.value = value
            _quickMenuVisualOpen.value = value
        }
    private var pendingMenuRequestId: UUID? = null
    /** Runtime truth: Return is offered only while a foreign host owns the secondary display. */
    @Volatile private var secondaryReturnAvailable = false

    /** Whether a tap on the single-display badge opens the music panel ([setMusicPanelAvailable]). Read on the main thread at a release. */
    @Volatile private var musicPanelAvailable = false
    private var visible = true
    private var primaryIdleHidden = false
    private var primaryIdleVisiblePosition: Pair<Int, Int>? = null
    private var primaryIdleInteractionObserver: (() -> Unit)? = null
    private val screenActivitySlot = OverlayScreenActivityObserverSlot()
    private var dimmed = false
    private var ownMirrorDrawerEdgeBackEnabled = false
    /** the Bottom shelf's own folder ("Tome") is open; the scoped own-mirror exception this
     *  strip already gives the visible Tabs drawer, given to that folder too. */
    private var ownMirrorFolderEdgeBackEnabled = false
    /** R6-P1-02: the DS handoff lease is the one state where Mimir knows the emulator's lower screen is up. */
    private var dualScreenGameOwnsBottom = false
    private var overlayTheme = CompanionOverlayTheme.Default
    private var renderedOverlayTheme = CompanionOverlayTheme.Default
    private var secondaryMenuGestureActive = false
    private var secondaryMenuDismissPending = false
    private var secondaryMenuRebuildDeferred = false
    /**
     * True from [collapseMenu] until the outgoing swap lands `menuOpen = false` (or the view is torn down).
     * `menuOpen` stays TRUE for the whole outgoing animation, and device observations caught the
     * Return-availability flip that follows an accepted swap arriving inside that window: it rebuilt the menu
     * as OPEN, so the coins came back on the Bottom after the swap it had just completed. A collapse in
     * flight is a dismissal for every live rebuild, exactly like [secondaryMenuDismissPending].
     */
    private var secondaryMenuCollapseInFlight = false
    private var menuLongPressConsumed = false

    /** OR-11: the primary quick menu's lit coin. Fresh each time the menu opens; carried across a rebuild of an open menu. */
    private var menuFocus = QuickMenuFocus.opened(0)

    /** The coins and ring of the menu root last built, which the frame-held confirm and the floor route act on. */
    private var menuRows: List<CompanionOverlayMenuRowCopy> = emptyList()
    private var menuRing: CompanionMenuFocusRingView? = null

    /**
     * R9a: who holds the Bottom's pad. Main thread only. Written ONLY by [setSecondaryPadOwner] and
     * [clearSecondaryPadOwner]; the menu window's focusability, its root's focusability and its key route all read this one
     * field. Slice 1 sets [BottomPadOwner.MENU] and nothing else.
     */
    private var secondaryPadOwner: BottomPadOwner? = null

    /** Pad state of the open menu: the press-order buffer, the hat's edge reader, the frame callback waiting on it. */
    private val padOrder = QuickMenuPadOrder()
    private var padAxisEdges = QuickMenuAxisEdges()
    private var padFrameCancel: (() -> Unit)? = null
    private var lastPadActivityMs = Long.MIN_VALUE

    /** Main's idle controller as one pad press ([setPadIdleInteraction]): true when the press woke a dimmed screen. Null until the app hands it over. */
    private var padIdleInteraction: (() -> Boolean)? = null

    /**
     * The Bottom menu's pad under Main's idle rule ([PadIdleGate], shared with Main's own lane and the Activity page's pad): every press re-arms the idle controller, and the press
     * that wakes a dimmed screen is eaten for its own events only, with the menu's pad state reset as Main resets its router. The ᛗ's own hide timer counts that wake press too.
     * One gate for the controller's life: a latch made under one pad owner is dropped when [BottomPadInput.ownerEpoch] moves, so a menu that closed with the release never leaves
     * the next one waiting for it.
     */
    private val padIdleGate = PadIdleGate(
        interaction = { padIdleInteraction?.invoke() ?: false },
        onWake = {
            resetPadInput()
            notifyPrimaryIdleInteraction()
        },
        ownership = { BottomPadInput.ownerEpoch },
    )

    /** Where a held confirm waits for its frame. Tests swap it; production is Main's Choreographer pattern. */
    internal var padFrameScheduler: MenuPadFrameScheduler = ChoreographerMenuPadFrameScheduler

    /** The exact lambdas handed to [BottomPadInput] so a release takes back only this controller's own. */
    private val floorPadKeys: (KeyEvent) -> Boolean = { event ->
        secondaryPadOwner != null && overlayView?.dispatchKeyEvent(event) == true
    }
    private val floorPadMotion: (MotionEvent) -> Boolean = { event ->
        secondaryPadOwner != null && overlayView?.dispatchGenericMotionEvent(event) == true
    }

    /** Replacements are serialized on the main handler; an old animation cannot settle a newer view. */
    private var overlayTransitionGeneration = 0L
    private var overlayTransitionView: View? = null
    private var overlayTransitionOutgoing = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressFired = false
    private var recentsBlockedAtDown = false
    private var longPressRunnable: Runnable? = null
    // a thin transparent strip pinned to the RIGHT EDGE of the companion display. An inward (leftward)
    // swipe on it means "return to previous state" — Android's back-from-app gesture, on screen 2. Held on
    // its own field so the button↔menu view swap (replaceView) never tears it down.
    private var edgeSwipeView: View? = null
    private var edgeSwipeDownX = 0f
    private var edgeSwipeFired = false
    private data class PanelWindow(
        val windowManager: WindowManager,
        val view: View,
        val role: CompanionOverlayRole,
    )

    // A panel's WindowManager/View/callback live in the UUID-owned entry, never in independently mutable fields.
    private val panelOwner = PanelLeaseOwner<PanelWindow>(
        mutationAllowed = { Looper.myLooper() == Looper.getMainLooper() },
    )
    private var primaryPanelWindow: PanelWindow? = null

    /**
     * How much of [companionPanelBlurRadiusDp] the primary panel's window blurs behind it, 0..1 (Q14). Written by
     * [setPrimaryPanelBlurBehind] on the panel's own open/close clock; reset to clear with every window, so one panel's blur is never
     * the next one's. [primaryPanelBlurListener] is the platform's "cross-window blur is available" watch on that window's manager.
     */
    private var primaryPanelBlurFraction = 0f
    private var primaryPanelBlurListener: java.util.function.Consumer<Boolean>? = null
    private var nativeOverlayInteractionObserver: NativeOverlayInteractionObserver? = null
    private val mButtonInteraction = NativeOverlayInteractionSession(
        NativeOverlayInteractionSource.M_BUTTON,
    ) { nativeOverlayInteractionObserver }
    private val menuInteraction = NativeOverlayInteractionSession(
        NativeOverlayInteractionSource.MENU,
    ) { nativeOverlayInteractionObserver }
    private val edgeInteraction = NativeOverlayInteractionSession(
        NativeOverlayInteractionSource.EDGE_BACK,
    ) { nativeOverlayInteractionObserver }

    private val mountReconciler = OverlayMountReconciler(
        hasPermission = ::hasOverlayPermission,
        windowSnapshot = ::currentWindowSnapshot,
        mount = ::mountOverlayWindowsOnMain,
        unmount = ::unmountOverlayWindowsOnMain,
        onPermissionMissingChanged = { missing ->
            if (_overlayPermissionMissing.value != missing) _overlayPermissionMissing.value = missing
        },
    )
    private val displayId: Int
        get() = mountReconciler.targetDisplayId ?: mountedOverlayDisplayId ?: -1
    private val displayRole: CompanionOverlayRole
        get() = mountReconciler.targetOverlay?.role
            ?: _activeTarget.value?.role
            ?: CompanionOverlayRole.SECONDARY_COMPANION

    /** One gesture receipt for debug APKs; keep release builds free of diagnostic output. */
    private fun debugMButtonReceipt(message: String) {
        if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.i(TAG, "m_button_receipt $message")
        }
    }

    private val appOpsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == appContext.packageName) {
            reconcile()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = reconcile()

        override fun onDisplayRemoved(displayId: Int) {
            handler.post { mountReconciler.displayRemoved(displayId) }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (mountReconciler.targetDisplayId == displayId) reconcile()
        }
    }

    override val actions: SharedFlow<CompanionOverlayAction> = _actions.asSharedFlow()
    override val overlayPermissionMissing: StateFlow<Boolean> = _overlayPermissionMissing.asStateFlow()
    override val activeTarget: StateFlow<CompanionOverlayTarget?> = _activeTarget.asStateFlow()

    init {
        runCatching { displayManager?.registerDisplayListener(displayListener, handler) }
            .onFailure { Log.w(TAG, "failed to register overlay display reconciliation", it) }
        runCatching {
            appOpsManager?.startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                appContext.packageName,
                appOpsListener,
            )
        }.onFailure { Log.w(TAG, "failed to register overlay permission reconciliation", it) }
        // The process singleton lives as long as the process, so the keyboard listener is never removed.
        BottomImeVisibility.addListener { handler.post { applyBottomImeChange() } }
        // With the ᛗ gone under the keyboard its outside-touch watcher is gone too; Mimir's keys tell Main's idle in its place.
        BottomImeVisibility.addKeyActivityListener { notifyScreenActivity(OverlayScreenActivity.OUTSIDE) }
        handler.post { mountReconciler.reconcile() }
    }

    /** A keyboard is up on the display the Bottom's overlays are mounted on (Mimir's own input method reports it). */
    private fun bottomImeShown(): Boolean =
        companionBottomImeShown(displayRole, mountedOverlayDisplayId, BottomImeVisibility.displayId)

    /** The mounted root's visibility: the topology's [visible] minus the ᛗ button while a keyboard is on the Bottom. */
    private fun overlayViewVisibility(): Int =
        if (companionOverlayViewVisible(visible, menuOpen, bottomImeShown())) View.VISIBLE else View.GONE

    private fun edgeCatcherTouchable(): Boolean = companionEdgeCatcherIsTouchable(
        dimmed,
        ownMirrorDrawerEdgeBackEnabled,
        dualScreenGameOwnsBottom,
        ownMirrorFolderEdgeBackEnabled,
        bottomImeShown(),
    )

    /** One gesture on the strip ends the moment it stops being admitted; the window flag follows. Main thread. */
    private fun refreshEdgeCatcher() {
        if (!edgeCatcherTouchable()) edgeInteraction.finish()
        updateEdgeCatcherTouchability()
    }

    /**
     * The keyboard on the Bottom came up or went down. Up: the ᛗ button is taken away (a GONE root hides its window, so it
     * takes no touch either) with any gesture on it ended, and the strip stands down. Down: both come back, and the ᛗ is
     * revealed and re-armed like any touch on the Bottom (it may have idle-hidden while the keyboard was up).
     */
    private fun applyBottomImeChange() {
        val shown = bottomImeShown()
        val wasShown = bottomImeApplied
        bottomImeApplied = shown
        if (shown && !menuOpen) {
            finishOverlayViewInteractions()
            cancelLongPress()
            dragging = false
            longPressFired = false
            // A GONE root still gets the rest of a touch it already had, and no CANCEL follows: without this the lift of a
            // press the keyboard interrupted reads as a tap and opens Recents. The next DOWN clears it.
            buttonGestureAborted = true
            (overlayView as? FrameLayout)?.getChildAt(0)?.let { coin ->
                coin.animate().cancel()
                coin.alpha = restingAlpha()
                coin.scaleX = 1f
                coin.scaleY = 1f
            }
        }
        overlayView?.visibility = overlayViewVisibility()
        refreshEdgeCatcher()
        if (wasShown && !shown) notifyPrimaryIdleInteraction()
    }

    /** What [applyBottomImeChange] last applied, so only the keyboard going down re-arms the ᛗ. */
    private var bottomImeApplied = false

    /** The ᛗ press in progress was cut short by the keyboard: its MOVEs, lift and CANCEL do nothing. Cleared by the next DOWN. */
    private var buttonGestureAborted = false

    override fun targetOverlay(target: CompanionOverlayTarget) {
        handler.post { mountReconciler.target(target) }
    }

    override fun dismiss() {
        handler.post { mountReconciler.clearTarget(PanelRevocationReason.DISPLAY_LOST) }
    }

    override fun reconcile() {
        handler.post { mountReconciler.reconcile() }
    }

    override fun setMusicPanelAvailable(available: Boolean) {
        musicPanelAvailable = available
    }

    override fun completeQuickMenu(requestId: UUID, accepted: Boolean) {
        handler.post {
            if (pendingMenuRequestId != requestId) return@post
            pendingMenuRequestId = null
            if (accepted) {
                collapseMenu()
            }
        }
    }

    override fun completeQuickMenuIntoMimir(requestId: UUID) {
        handler.post {
            if (pendingMenuRequestId != requestId) return@post
            pendingMenuRequestId = null
            if (displayRole == CompanionOverlayRole.PRIMARY_SINGLE) handOffMenuToMimir() else collapseMenu()
        }
    }

    /**
     * Opening Utility, Tweaker or Quick Launch from the M menu produced colliding fade layers on
     * Nova, while the dock path was clean. The menu is its own overlay window with
     * FLAG_BLUR_BEHIND: its card faded out on the overlay's clock while its blur held at full strength and then
     * dropped out in one frame at removal, under Mimir's glass ramping in on Compose's clock. So an accepted pick on
     * the main screen hides the card at once and gives up focus and touch (the opened surface takes the pad from
     * this frame), clears its blur on Mimir's glass clock while that glass comes up ([CompanionMenuBlurHandoffMs];
     * holding it blurred Mimir's arriving panel), and is removed without animation when the blur has
     * cleared. Mimir's own transition is then the only one, as from the dock.
     */
    private fun handOffMenuToMimir() {
        val view = overlayView
        val wm = windowManager
        if (!menuOpen || view == null || wm == null) {
            collapseMenu()
            return
        }
        cancelOverlayTransition()
        val generation = ++overlayTransitionGeneration
        _quickMenuVisualOpen.value = false
        view.animate().cancel()
        view.alpha = 0f
        view.isEnabled = false
        view.isClickable = false
        val lp = view.layoutParams as? WindowManager.LayoutParams
        lp?.let {
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(view, it) }
        }
        val fromPx = if (lp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lp.blurBehindRadius else 0
        if (lp == null || fromPx <= 0) {
            // Nothing to clear (no cross-window blur here): the card is already hidden, so the button returns now.
            collapseMenuWithoutFade()
            return
        }
        // The removal rides the ramp's own end, not a wall-clock delay: the animator follows the system animation
        // scale (instant when animations are off, slowed under a slow-motion check), and the window must never go
        // while any blur is left on it, or the snap this replaces comes back.
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = CompanionMenuBlurHandoffMs
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
            addUpdateListener { ramp ->
                if (overlayTransitionGeneration != generation || overlayView !== view) return@addUpdateListener
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lp.blurBehindRadius = companionMenuHandoffBlurRadius(fromPx, ramp.animatedValue as Float)
                    runCatching { wm.updateViewLayout(view, lp) }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (overlayTransitionGeneration != generation || overlayView !== view || !menuOpen) return
                    collapseMenuWithoutFade()
                }
            })
            start()
        }
    }

    /** Update the secondary menu from the process-owned display lease, fail-closed by default. */
    fun setSecondaryReturnAvailable(available: Boolean) {
        handler.post {
            if (secondaryReturnAvailable == available) return@post
            secondaryReturnAvailable = available
            if (secondaryMenuLiveRebuildAllowed(
                    menuOpen = menuOpen,
                    secondaryRole = displayRole == CompanionOverlayRole.SECONDARY_COMPANION,
                    collapseInFlight = secondaryMenuCollapseInFlight,
                )
            ) {
                if (secondaryMenuGestureActive || secondaryMenuDismissPending) {
                    secondaryMenuRebuildDeferred = true
                    return@post
                }
                replaceView(
                    buildMenu(
                        displayContext ?: appContext,
                        positionOverride = currentSecondaryMenuCardPosition(),
                    ),
                    targetMenuOpen = true,
                )
            }
        }
    }

    override fun setNativeOverlayInteractionObserver(observer: NativeOverlayInteractionObserver?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            installNativeOverlayInteractionObserver(observer)
        } else {
            handler.post { installNativeOverlayInteractionObserver(observer) }
        }
    }

    override fun setVisible(visible: Boolean) {
        this.visible = visible
        handler.post {
            if (!visible) {
                finishOverlayViewInteractions()
                // A hidden window must never keep the pad: it would swallow keys with nothing on screen to show for it.
                clearSecondaryPadOwner("hidden")
            }
            overlayView?.visibility = overlayViewVisibility()
        }
    }

    override fun setPrimaryIdleHidden(hidden: Boolean) {
        handler.post { applyPrimaryIdleHidden(hidden) }
    }

    override fun setPrimaryIdleInteractionObserver(observer: (() -> Unit)?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            primaryIdleInteractionObserver = observer
        } else {
            handler.post { primaryIdleInteractionObserver = observer }
        }
    }

    override fun setScreenActivityObserver(observer: ((OverlayScreenActivity) -> Unit)?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            screenActivitySlot.set(observer)
        } else {
            handler.post { screenActivitySlot.set(observer) }
        }
    }

    override fun clearScreenActivityObserver(observer: (OverlayScreenActivity) -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            screenActivitySlot.clear(observer)
        } else {
            handler.post { screenActivitySlot.clear(observer) }
        }
    }

    override fun setPadIdleInteraction(interaction: (() -> Boolean)?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            padIdleInteraction = interaction
        } else {
            handler.post { padIdleInteraction = interaction }
        }
    }

    override fun notifyScreenActivity(activity: OverlayScreenActivity) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            emitScreenActivity(activity)
        } else {
            handler.post { emitScreenActivity(activity) }
        }
    }

    private fun applyPrimaryIdleHidden(hidden: Boolean) {
        // A coin still gliding home lands first: the glide resizes this window on every frame, so left running it would grow the one-pixel idle watcher back to 56 dp
        // (an invisible window that takes taps) and carry on after the reveal from a spot the cache has already moved on from.
        finishButtonRestGlide()
        if (displayRole != CompanionOverlayRole.PRIMARY_SINGLE &&
            displayRole != CompanionOverlayRole.SECONDARY_COMPANION
        ) {
            primaryIdleHidden = false
            primaryIdleVisiblePosition = null
            return
        }
        if (hidden && (menuOpen || dragging || longPressRunnable != null || pendingMenuRequestId != null)) return
        if (primaryIdleHidden == hidden) return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val context = displayContext ?: appContext
        val previousHidden = primaryIdleHidden
        val previousVisiblePosition = primaryIdleVisiblePosition
        val visiblePosition = if (hidden) {
            lp.x to lp.y
        } else {
            val size = dp(context, MButtonSizeDp)
            val requested = primaryIdleVisiblePosition ?: (lp.x to lp.y)
            clampCompanionOverlayPosition(
                x = requested.first,
                y = requested.second,
                bounds = displayBounds(context),
                width = size,
                height = size,
            )
        }
        primaryIdleHidden = hidden
        primaryIdleVisiblePosition = visiblePosition.takeIf { hidden }
        // Keep one WindowManager input target for the complete hide -> observe -> reveal cycle. Removing
        // the 1px watcher from inside ACTION_OUTSIDE made Odin cancel the ORIGINAL touch headed to Mimir;
        // Library/Utility consequently needed an unexplained extra tap. Resizing this same host preserves
        // Android's active touch target while still shrinking the idle overlay to one non-visible pixel.
        view.alpha = if (hidden) 0f else restingAlpha()
        view.importantForAccessibility = if (hidden) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        val updatedParams = overlayParams(context, positionOverride = visiblePosition)
        val updated = runCatching {
            wm.updateViewLayout(view, updatedParams)
            true
        }.onFailure {
            Log.w(TAG, "failed to resize primary M idle surface in place", it)
        }.getOrDefault(false)
        if (!updated) {
            primaryIdleHidden = previousHidden
            primaryIdleVisiblePosition = previousVisiblePosition
            mountReconciler.forceRemount()
            return
        }
        // The reveal succeeded, so there is a real window again: apply any theme that arrived while this
        // button was a one-pixel watcher. Ordered AFTER the resize deliberately -- `overlayParams` reads
        // `primaryIdleHidden`, which is only now false, so the replacement attaches at full size.
        if (!hidden && themeRebuildDeferredWhileHidden) {
            themeRebuildDeferredWhileHidden = false
            applyOverlayTheme(overlayTheme)
        }
    }

    private fun notifyPrimaryIdleInteraction() {
        if (displayRole == CompanionOverlayRole.PRIMARY_SINGLE ||
            displayRole == CompanionOverlayRole.SECONDARY_COMPANION
        ) primaryIdleInteractionObserver?.invoke()
    }

    /** Round-5 triage B2: reports a real touch. Never called from showMenu, collapseMenu or mount. */
    private fun emitScreenActivity(activity: OverlayScreenActivity?) {
        screenActivitySlot.emit(activity)
    }

    override fun setOverlayTheme(theme: CompanionOverlayTheme) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyOverlayTheme(theme)
        } else {
            handler.post { applyOverlayTheme(theme) }
        }
    }

    /**
     * A theme arrived while the primary M was idle-hidden, so its view rebuild was DEFERRED to the next
     * reveal. See [applyOverlayTheme] for why rebuilding into the hidden window is not survivable.
     */
    private var themeRebuildDeferredWhileHidden = false

    private fun applyOverlayTheme(theme: CompanionOverlayTheme) {
        val currentView = overlayView
        val attached = currentView != null && windowManager != null
        val layout = currentView?.layoutParams as? WindowManager.LayoutParams
        val currentPosition = if (menuOpen && displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
            currentSecondaryMenuCardPosition()
        } else {
            layout?.let { it.x to it.y }
        }
        val plan = planCompanionOverlayThemeChange(
            current = if (attached) renderedOverlayTheme else overlayTheme,
            next = theme,
            attached = attached,
            menuOpen = menuOpen,
            position = currentPosition,
        )
        overlayTheme = theme
        if (!plan.rebuildAttachedView) return

        if (menuOpen && displayRole == CompanionOverlayRole.SECONDARY_COMPANION &&
            (secondaryMenuGestureActive || secondaryMenuDismissPending || secondaryMenuCollapseInFlight)
        ) {
            secondaryMenuRebuildDeferred = true
            return
        }

        // Reported 2026-08-29: changing theme while the Mimir button was hidden prevented it
        // from reappearing. Reproduced and A/B'd on the Odin.
        //
        // While the primary M is idle-hidden its window is shrunk to ONE PIXEL (`PrimaryIdleWatcherSizePx`,
        // so WindowManager keeps a live touch target through the hide -> observe -> reveal cycle). A theme
        // change rebuilds the attached view -- and `addCurrentView` therefore attached the REPLACEMENT into
        // that 1x1 window. The later reveal does resize the window and restore alpha, and the state machine
        // ends up perfectly correct (measured: flag=false, alpha=0.9, 129x129) -- but the view was born into
        // a one-pixel window and never renders again. The button is gone with every piece of state claiming
        // it is present, which is exactly why no amount of interaction brought it back.
        //
        // Control that isolates it: the same theme change with the button VISIBLE attaches at full size and
        // survives (button-region luminance 44.7 -> 41.5). Hidden, it dies.
        //
        // So do not rebuild into the hidden window at all. Keep the 1x1 watcher exactly as it is, remember
        // that the material is stale, and rebuild on the next reveal when there is a real window to attach
        // to. The M keeps its old skin for as long as it is invisible, which costs nothing.
        if (primaryIdleHidden && !menuOpen) {
            themeRebuildDeferredWhileHidden = true
            return
        }

        val context = displayContext ?: appContext
        val targetMenuOpen = plan.chrome == CompanionOverlayChrome.MENU
        val replacement = if (targetMenuOpen) {
            buildMenu(
                context,
                positionOverride = if (displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
                    plan.position
                } else {
                    null
                },
            )
        } else {
            buildButton(context)
        }
        replaceView(
            view = replacement,
            targetMenuOpen = targetMenuOpen,
            positionOverride = if (targetMenuOpen && displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
                null
            } else {
                plan.position
            },
        )
    }

    override fun setDimmed(dimmed: Boolean) {
        // The mirror (the Presentation's MirrorPresented disposition) leaving is an EDGE, never a level: the Bottom menu contract
        // keeps the menu walkable over a foreign app yielded on the Bottom, so "not dimmed" alone clears nothing.
        val mirrorLeft = this.dimmed && !(dimmed && displayRole == CompanionOverlayRole.SECONDARY_COMPANION)
        this.dimmed = dimmed && displayRole == CompanionOverlayRole.SECONDARY_COMPANION
        // Policy R12: over Mimir's OWN mirror the M dims but stays present + touchable (never GONE);
        // it returns to full strength while a foreign host owns screen 2.
        handler.post {
            // A collapse in flight already cleared the owner; this covers a menu that stays open under the change.
            if (mirrorLeft) clearSecondaryPadOwner("mirror_left")
            if (!edgeCatcherTouchable()) edgeInteraction.finish()
            overlayView?.alpha = if (primaryIdleHidden && !menuOpen) {
                0f
            } else if (menuOpen) {
                1f
            } else {
                restingAlpha()
            }
            // Adversarial review 2026-07-10: the edge catcher stays UNTOUCHABLE over an idle Mimir mirror.
            // Returning false from its DOWN did NOT pass the touch through — the window had already consumed
            // it, leaving the mirror's right 32dp strip touch-dead (edge drags, right-column tiles). The
            // scoped visible-Tabs override is applied separately by MainActivity.
            updateEdgeCatcherTouchability()
        }
    }

    /**
     * Arm the existing edge catcher for the visible Tabs Presentation over Mimir's own mirror. The caller
     * owns the topology/read-model admission; this controller only changes the existing strip's touch flag.
     * Main-thread WindowManager mutations remain serialized through [handler].
     */
    fun setOwnMirrorDrawerEdgeBackEnabled(enabled: Boolean) {
        handler.post {
            ownMirrorDrawerEdgeBackEnabled = enabled
            refreshEdgeCatcher()
        }
    }

    /**
     * arm the existing edge catcher for the Bottom shelf's own open folder ("Tome") over Mimir's
     * own mirror. Shaped exactly like [setOwnMirrorDrawerEdgeBackEnabled] — the caller owns the
     * read-model/session admission; this controller only changes the existing strip's touch flag.
     */
    fun setOwnMirrorFolderEdgeBackEnabled(enabled: Boolean) {
        handler.post {
            ownMirrorFolderEdgeBackEnabled = enabled
            refreshEdgeCatcher()
        }
    }

    /**
     * R6-P1-02: arm the strip's block while the DS handoff lease says a dual-screen game owns the Bottom.
     * Shaped exactly like [setOwnMirrorDrawerEdgeBackEnabled] — the caller owns the runtime/session
     * admission; this controller only changes the existing strip's touch flag.
     */
    fun setDualScreenGameOwnsBottom(owns: Boolean) {
        handler.post {
            dualScreenGameOwnsBottom = owns
            refreshEdgeCatcher()
        }
    }

    /** Edge catcher is touchable for a foreign host, the scoped own-mirror drawer exception, or the
     *  scoped own-mirror folder exception (R6-P2-08), and never while a keyboard is up on the Bottom. */
    private fun updateEdgeCatcherTouchability() {
        val wm = windowManager ?: return
        val strip = edgeSwipeView ?: return
        val lp = strip.layoutParams as? WindowManager.LayoutParams ?: return
        val flagsBefore = lp.flags
        lp.flags = if (edgeCatcherTouchable()) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (lp.flags != flagsBefore) runCatching { wm.updateViewLayout(strip, lp) }
    }

    /** Idle alpha: the dim state wins outright — never multiplied with the resting look (HP-2). Zero while a Mimir panel covers the Bottom's coin ([companionCoinRestingAlpha]). */
    private fun restingAlpha(): Float = companionCoinRestingAlpha(coinHiddenForPanel(), dimmed)

    /** The Bottom's panels (Utility, Tweaker, Tabs) asked for the coin to hide ([setCoinHiddenForPanel]); read on the main thread. */
    private var panelCoinHiddenRequested = false

    private fun coinHiddenForPanel(): Boolean = companionCoinHiddenForPanel(panelCoinHiddenRequested, displayRole)

    override fun setCoinHiddenForPanel(hidden: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyCoinHiddenForPanel(hidden)
        } else {
            handler.post { applyCoinHiddenForPanel(hidden) }
        }
    }

    /**
     * Fades the Bottom's coin out and makes its window pass touches through while a Mimir panel is open over it, and back again when it closes. The window is NOT removed or
     * moved: only its alpha and its NOT_TOUCHABLE flag change, so the coin returns at the same position. The request is remembered whatever the state of the window (no window
     * yet, or the menu is up in its place): [overlayParams] and [restingAlpha] read it, so a coin mounted or rebuilt later wears it too.
     */
    private fun applyCoinHiddenForPanel(hidden: Boolean) {
        if (panelCoinHiddenRequested == hidden) return
        panelCoinHiddenRequested = hidden
        if (menuOpen) return
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val hiddenNow = coinHiddenForPanel()
        val flagsBefore = lp.flags
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or companionCoinTouchFlag(hiddenNow)
        if (lp.flags != flagsBefore) runCatching { wm.updateViewLayout(view, lp) }
        view.importantForAccessibility = if (hiddenNow || primaryIdleHidden) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        // The idle-hidden coin is already at 0; the fade only moves a coin that is showing, and lands on the same alpha every other path uses.
        view.animate().cancel()
        view.animate().alpha(if (primaryIdleHidden) 0f else restingAlpha()).setDuration(CompanionPanelCoinFadeMs).setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()
    }

    // a press ALWAYS brightens to full, even when dimmed — the old `if (dimmed)
    // DimmedAlpha` short-circuit meant a tap over Mimir's own mirror gave no feedback and the M read as
    // "greyed out and dead". Now every touch flashes the M to 1f so it feels alive.
    private fun pressedAlpha(): Float = 1f

    override fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    override fun requestOverlayPermission() {
        if (hasOverlayPermission()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        )
        // From Mimir's own resumed Activity, so the screen joins Mimir's task instead of getting its own
        // Recents card; the application-context start (NEW_TASK) is the fallback when none is resumed.
        startSettingsSurface(appContext, intent)
    }

    private fun mountOverlayWindowsOnMain(target: CompanionOverlayTarget): Boolean {
        // OverlayMountReconciler has already sampled permission. Recheck at the Android boundary because the
        // appop can change between that sample and WindowManager.addView.
        if (!hasOverlayPermission()) return false
        val displayId = target.displayId
        val display = displayManager?.getDisplay(displayId) ?: return false
        val candidateContext = runCatching {
            appContext.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }.onFailure {
            Log.w(TAG, "failed to create overlay window context display=$displayId", it)
        }.getOrNull() ?: return false
        val candidateWindowManager = candidateContext.getSystemService(WindowManager::class.java) ?: return false
        if (target.role == CompanionOverlayRole.PRIMARY_SINGLE) dimmed = false
        primaryIdleHidden = false
        primaryIdleVisiblePosition = null
        // A fresh mount owns no pad: the previous mount's owner, if any, ended with its window (it also cleared on unmount).
        clearSecondaryPadOwner("mount", updateWindow = false)
        menuOpen = false
        secondaryMenuGestureActive = false
        secondaryMenuDismissPending = false
        secondaryMenuRebuildDeferred = false
        secondaryMenuCollapseInFlight = false
        // the strip is added BEFORE the ᛗ, so the ᛗ window is above it. Windows of one
        // type stack in the order they were added, the ᛗ is re-added after every menu swap (so it was above only after one), and the
        // strip is as tall as the display: added last, it covered the ᛗ's right 32 dp over a foreign app and took those touches.
        // A leftward drag from there fired EdgeBack and reclaimed the Bottom (21:50:03 and :24), so the ᛗ could not be grabbed.
        val candidateEdge = if (target.role == CompanionOverlayRole.SECONDARY_COMPANION) {
            addEdgeCatcher(candidateWindowManager, candidateContext)
        } else {
            null
        }
        if (target.role == CompanionOverlayRole.SECONDARY_COMPANION && candidateEdge == null) {
            if (!hasOverlayPermission()) _overlayPermissionMissing.value = true
            return false
        }
        val candidateView = buildButton(candidateContext)
        // A coin mounted (or re-mounted) while a Mimir panel is open is born hidden: the request outlives the window ([panelCoinHiddenRequested]), and [overlayParams] gives it the
        // touch-through flag. Every other coin keeps the alpha it was always added at.
        if (coinHiddenForPanel()) {
            candidateView.alpha = 0f
            candidateView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val buttonAdded = runCatching {
            candidateWindowManager.addView(candidateView, overlayParams(candidateContext))
        }.onFailure { Log.w(TAG, "failed to add companion overlay display=$displayId", it) }.isSuccess
        if (!buttonAdded) {
            candidateEdge?.let { runCatching { candidateWindowManager.removeViewImmediate(it) } }
            if (!hasOverlayPermission()) _overlayPermissionMissing.value = true
            return false
        }

        displayContext = candidateContext
        windowManager = candidateWindowManager
        overlayView = candidateView
        renderedOverlayTheme = overlayTheme
        edgeSwipeView = candidateEdge
        mountedOverlayDisplayId = displayId
        _activeTarget.value = target
        publishPrimaryButtonBounds()
        overlayView?.visibility = overlayViewVisibility()
        bottomImeApplied = bottomImeShown()
        updateEdgeCatcherTouchability()
        if (target.role == CompanionOverlayRole.PRIMARY_SINGLE) {
            notifyPrimaryIdleInteraction()
        }
        return true
    }

    private fun unmountOverlayWindowsOnMain(reason: PanelRevocationReason) {
        finishAllNativeOverlayInteractions()
        cancelLongPress()
        cancelOverlayTransition()
        removeEdgeCatcher()
        // Display removed, permission revoked, target cleared: the menu window goes below, and with it any pad ownership.
        // The floor stays focusable (the 09-11 ANR guard) and takes the pad back.
        clearSecondaryPadOwner("unmount_${reason.name.lowercase(Locale.ROOT)}", updateWindow = false)
        val wm = windowManager
        val view = overlayView
        overlayView = null
        windowManager = null
        displayContext = null
        mountedOverlayDisplayId = null
        _activeTarget.value = null
        _primaryButtonBounds.value = null
        cancelButtonRestGlide()
        menuOpen = false
        primaryIdleHidden = false
        primaryIdleVisiblePosition = null
        pendingMenuRequestId = null
        secondaryMenuGestureActive = false
        secondaryMenuDismissPending = false
        secondaryMenuRebuildDeferred = false
        secondaryMenuCollapseInFlight = false
        menuLongPressConsumed = false
        if (wm != null && view != null) runCatching { wm.removeViewImmediate(view) }
        panelOwner.revokeCurrent(reason)
    }

    private fun currentWindowSnapshot(): OverlayWindowSnapshot {
        val mountedDisplay = mountedOverlayDisplayId
        val hasWindowManager = windowManager != null
        return OverlayWindowSnapshot(
            mButtonDisplayId = mountedDisplay.takeIf { hasWindowManager && overlayView != null },
            edgeDisplayId = mountedDisplay.takeIf { hasWindowManager && edgeSwipeView != null },
            panelAttached = panelOwner.activeLeaseId != null,
        )
    }

    override fun attachSecondaryPanel(
        view: View,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult = attachPanel(
        view = view,
        role = CompanionOverlayRole.SECONDARY_COMPANION,
        focusable = false,
        title = "Mimir companion tabs",
        onRevoked = onRevoked,
    )

    override fun attachPrimaryPanel(
        view: View,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult = attachPanel(
        view = view,
        role = CompanionOverlayRole.PRIMARY_SINGLE,
        focusable = true,
        title = "Mimir global controls",
        onRevoked = onRevoked,
    )

    private fun attachPanel(
        view: View,
        role: CompanionOverlayRole,
        focusable: Boolean,
        title: String,
        onRevoked: (PanelLeaseId, PanelRevocationReason) -> Unit,
    ): PanelAttachResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return PanelAttachResult.Rejected(PanelRejectReason.NOT_MAIN_THREAD)
        }

        // Permission must be sampled before every non-null/active fast path. A newly denied appop tears down
        // M, edge, and the current panel before this request is rejected.
        if (!hasOverlayPermission()) {
            mountReconciler.reconcile()
            return PanelAttachResult.Rejected(PanelRejectReason.OVERLAY_PERMISSION_MISSING)
        }
        // Panels are children of the committed M/edge mount transaction, not merely of a remembered target.
        // In particular, a synchronous permission-recovery collector must not create a panel-only window set.
        val mounted = mountReconciler.mountedOverlay
            ?.takeIf { it == mountReconciler.targetOverlay }
            ?.takeIf { it.role == role }
            ?: return PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)
        val displayId = mounted.displayId
        val display = displayManager?.getDisplay(displayId)
        if (display == null) {
            mountReconciler.displayRemoved(displayId)
            return PanelAttachResult.Rejected(PanelRejectReason.DISPLAY_UNAVAILABLE)
        }
        // A fresh window context per attach — a WindowContext is single-window-scoped, so we do NOT cache/reuse
        // it across open/close. The dropped context is GC-reclaimed; the tab manager opens rarely, so the churn
        // is negligible (deliberately not cached to avoid window-token reuse bugs).
        val ctx = runCatching {
            appContext.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        }.getOrElse {
            panelOwner.revokeCurrent(PanelRevocationReason.REPLACED)
            return PanelAttachResult.Rejected(PanelRejectReason.WINDOW_MANAGER_UNAVAILABLE)
        }
        val wm = ctx.getSystemService(WindowManager::class.java) ?: run {
            panelOwner.revokeCurrent(PanelRevocationReason.REPLACED)
            return PanelAttachResult.Rejected(PanelRejectReason.WINDOW_MANAGER_UNAVAILABLE)
        }
        val panel = PanelWindow(wm, view, role)
        val result = panelOwner.attach(
            resource = panel,
            add = { candidate ->
                runCatching {
                    candidate.windowManager.addView(
                        candidate.view,
                        panelParams(focusable = focusable, title = title, role = role),
                    )
                }
                    .onFailure { Log.w(TAG, "failed to add $role panel", it) }
                    .isSuccess
                    .also { attached ->
                        if (attached && role == CompanionOverlayRole.PRIMARY_SINGLE) {
                            primaryPanelWindow = candidate
                            primaryPanelBlurFraction = 0f
                            watchCrossWindowBlur(candidate)
                        }
                    }
            },
            remove = { owned ->
                if (primaryPanelWindow === owned) {
                    primaryPanelWindow = null
                    primaryPanelBlurFraction = 0f
                    unwatchCrossWindowBlur(owned)
                }
                runCatching { owned.windowManager.removeViewImmediate(owned.view) }
            },
            onRevoked = { leaseId, reason ->
                Log.i(TAG, "panel revoked lease=${leaseId.value} display=$displayId reason=$reason")
                onRevoked(leaseId, reason)
            },
        )
        if (result is PanelAttachResult.Rejected && !hasOverlayPermission()) {
            mountReconciler.reconcile()
            return PanelAttachResult.Rejected(PanelRejectReason.OVERLAY_PERMISSION_MISSING)
        }
        return when (result) {
            is PanelAttachResult.Attached -> {
                val owned = result.lease
                Log.i(TAG, "panel attached lease=${owned.id.value} display=$displayId")
                PanelAttachResult.Attached(
                    object : CompanionPanelLease {
                        override val id: PanelLeaseId = owned.id

                        override fun detach(): Boolean {
                            val detached = owned.detach()
                            Log.i(
                                TAG,
                                "panel detach lease=${id.value} display=$displayId accepted=$detached",
                            )
                            return detached
                        }
                    },
                )
            }
            is PanelAttachResult.Rejected -> result
        }
    }

    override fun setPrimaryPanelBrightness(fraction: Float?) {
        handler.post {
            val panel = primaryPanelWindow ?: return@post
            val params = panel.view.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.screenBrightness = fraction
                ?.takeIf(Float::isFinite)
                ?.coerceIn(0.01f, 1f)
                ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            runCatching { panel.windowManager.updateViewLayout(panel.view, params) }
                .onFailure { Log.w(TAG, "failed to update primary panel brightness", it) }
        }
    }

    /**
     * Q14 : the ᛗ music panel blurs what is behind its window, where Android lets a window do that. [fraction] is
     * how far along its own open/close clock the panel is (0 clear, 1 fully blurred); the panel drives it every frame, so the blur
     * arrives with the card and clears with it, never held at full strength and dropped in one frame at removal (the M menu's
     * blur-handoff behavior, [handOffMenuToMimir]).
     *
     * Where cross-window blur is unavailable (before API 31, or [WindowManager.isCrossWindowBlurEnabled] false: battery saver, a
     * vendor switch, a disabled GPU path) this changes nothing, and the panel is exactly what it was: its scrim over a sharp
     * screen. The platform's availability is watched ([watchCrossWindowBlur]), so a blur that becomes available while the panel is
     * up joins at the clock's current value, and one that goes is cleared at once.
     */
    override fun setPrimaryPanelBlurBehind(fraction: Float) {
        // Applied in this call, on the main looper the panel's own frames run on, to the window that is current NOW: a queued
        // fraction could land on the window that replaces a leaving panel (review 2026-10-14, W1C #3). Off the main looper the
        // window is captured here and the post lands only if it is still the one.
        val clamped = fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val panel = primaryPanelWindow ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            primaryPanelBlurFraction = clamped
            applyPrimaryPanelBlur(panel)
        } else {
            handler.post {
                if (primaryPanelWindow !== panel) return@post
                primaryPanelBlurFraction = clamped
                applyPrimaryPanelBlur(panel)
            }
        }
    }

    /**
     * The exiting primary panel gives up focus and touch in the frame its exit begins (review 2026-10-14, W1C #1): its window stays up
     * to fade, but it is no longer the focused window, so the app beneath gets keys, the D-pad and the volume keys at once, and a held
     * direction is not lost to a window that is only drawing. The flags are ORed into the SAME [WindowManager.LayoutParams] the blur
     * re-reads, so neither write undoes the other; the host's key, motion and touch swallowing stays as a backstop for the frames
     * before the window manager applies this. The precedent is [handOffMenuToMimir]. Over Mimir itself MainActivity regains window
     * focus up to a fade (180 ms) earlier than it did; its focus callback only resets the input router.
     */
    override fun releasePrimaryPanelInput() {
        val panel = primaryPanelWindow ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releaseInput(panel)
        } else {
            handler.post { releaseInput(panel) }
        }
    }

    private fun releaseInput(panel: PanelWindow) {
        if (primaryPanelWindow !== panel) return
        val params = panel.view.layoutParams as? WindowManager.LayoutParams ?: return
        val released = companionPanelInputReleasedFlags(params.flags)
        if (released == params.flags) return
        params.flags = released
        runCatching { panel.windowManager.updateViewLayout(panel.view, params) }
            .onFailure { Log.w(TAG, "failed to release primary panel input", it) }
    }

    /** Puts [primaryPanelBlurFraction] on [panel]'s window (the current primary one): the flag and radius together, only when they changed. */
    private fun applyPrimaryPanelBlur(target: PanelWindow? = primaryPanelWindow) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val panel = target ?: return
        if (primaryPanelWindow !== panel) return
        val params = panel.view.layoutParams as? WindowManager.LayoutParams ?: return
        val available = runCatching { panel.windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        val radius = if (available) {
            companionPanelBlurRadius(primaryPanelBlurFraction, dp(appContext, companionPanelBlurRadiusDp(panel.role)))
        } else {
            0
        }
        val flagged = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
        if (params.blurBehindRadius == radius && flagged == radius > 0) return
        params.flags = if (radius > 0) {
            params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        }
        params.blurBehindRadius = radius
        runCatching { panel.windowManager.updateViewLayout(panel.view, params) }
            .onFailure { Log.w(TAG, "failed to update primary panel blur", it) }
    }

    private fun watchCrossWindowBlur(panel: PanelWindow) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = java.util.function.Consumer<Boolean> { handler.post { applyPrimaryPanelBlur() } }
        primaryPanelBlurListener = listener
        runCatching { panel.windowManager.addCrossWindowBlurEnabledListener({ handler.post(it) }, listener) }
            .onFailure { Log.w(TAG, "failed to watch cross-window blur", it) }
    }

    private fun unwatchCrossWindowBlur(panel: PanelWindow) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val listener = primaryPanelBlurListener ?: return
        primaryPanelBlurListener = null
        runCatching { panel.windowManager.removeCrossWindowBlurEnabledListener(listener) }
    }

    /** Full-screen panel. Primary is focusable for controller/Back; companion remains touch-only. */
    private fun panelParams(
        focusable: Boolean,
        title: String,
        role: CompanionOverlayRole,
    ): WindowManager.LayoutParams = companionPanelWindowParams(focusable, title, role)

    /**
     * CP03: mount the right-edge back-swipe strip. A thin, transparent, full-height window pinned to the
     * right edge of the companion display. An inward (leftward) drag that crosses 48dp emits [EdgeBack] —
     * the return-to-previous-state gesture, matching Android's own back-from-app edge swipe. Held on its
     * own window so the button↔menu [replaceView] swap never removes it.
     */
    private fun addEdgeCatcher(wm: WindowManager, context: Context): View? {
        val strip = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            setOnTouchListener { _, event -> handleEdgeSwipe(event) }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val lp = WindowManager.LayoutParams(
            // Catch strip width: 24→32dp — an easier edge to land the return swipe on
            // without stealing a meaningful slice of the mirror's own right edge (it only arms over a foreign app).
            dp(context, EdgeCatcherWidthDp),
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = 0
            title = "Mimir companion edge-back"
        }
        return runCatching {
            wm.addView(strip, lp)
            strip
        }
            .onFailure { Log.w(TAG, "failed to add companion edge catcher", it) }
            .getOrNull()
    }

    private fun removeEdgeCatcher() {
        val wm = windowManager
        val strip = edgeSwipeView
        if (wm != null && strip != null) {
            runCatching { wm.removeViewImmediate(strip) }
        }
        edgeSwipeView = null
    }

    private fun handleEdgeSwipe(event: MotionEvent?): Boolean {
        event ?: return false
        val context = displayContext ?: appContext
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Over Mimir's OWN mirror (dimmed) the strip is transparent to touch unless the visible Tabs
                // Presentation, or the shelf's own open folder (R6-P2-08), explicitly admits dismissal. A
                // FOREIGN app remains admitted by !dimmed. A dual-screen game's DS handoff lease (R6-P1-02)
                // blocks unconditionally, outside all three.
                if (!edgeCatcherTouchable()) return false
                // R6-P1-05: an admitted DOWN is a real touch on this overlay window and must wake a dimmed
                // screen, the same as every other overlay window (handleButtonTouch, buildMenu's root).
                emitScreenActivity(overlayScreenActivityFor(event.actionMasked))
                edgeInteraction.begin()
                edgeSwipeDownX = event.rawX
                edgeSwipeFired = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // A keyboard that came up mid-gesture ends it: the strip is NOT_TOUCHABLE now, and a fire here would reclaim the Bottom under it.
                if (!edgeSwipeFired && !bottomImeShown() && event.rawX - edgeSwipeDownX <= -dp(context, EdgeCatcherTriggerDp)) {
                    edgeSwipeFired = true
                    edgeSwipeView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    _actions.tryEmit(CompanionOverlayAction.EdgeBack)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                edgeInteraction.finish()
                edgeSwipeFired = false
                return true
            }
        }
        return false
    }

    private fun buildButton(context: Context): View {
        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // ACTION_OUTSIDE is only an observation copy. Do not resize this overlay window
                    // during the same input dispatch: Odin cancels the original DOWN headed to the
                    // app underneath, making Home/Library/Utility require an unexplained extra tap.
                    // One frame is enough for the original gesture to settle while keeping M's reveal
                    // visually immediate.
                    // Round-5 triage B2: read before posting, since the event is recycled once this dispatch
                    // returns. The copy is only ever OUTSIDE, which re-arms Main's screen idle and never wakes it.
                    val screenActivity = overlayScreenActivityFor(event.actionMasked)
                    handler.postDelayed(
                        {
                            notifyPrimaryIdleInteraction()
                            emitScreenActivity(screenActivity)
                        },
                        PrimaryIdleOutsideRevealDelayMs,
                    )
                    return false
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isClickable = true
        }
        // the shipping M — the Mannaz rune vector (mimir_mark.xml, the canonical #mimirMark
        // geometry) inside a dark disc, replacing a plain letter chip that differed from the product style.
        //
        // **STRUCK, not tinted — ** It was an `ImageView` with a flat `toolingArgb` `imageTintList` on a disc with a
        // flat 2 dp stroke of the same colour: one tone, no light direction, on the one control that
        // is on screen the entire time. A `ColorStateList` cannot carry a gradient, which is why this
        // is now the same drawn view the menu's rune coins use — one metal, one press behaviour, and
        // the button stops being the last flat thing in its own overlay.
        val button = createMimirButtonCoin(context, overlayTheme).apply {
            contentDescription = menuResources(context).getString(
                if (displayRole == CompanionOverlayRole.PRIMARY_SINGLE) {
                    R.string.companion_button_description_primary
                } else {
                    R.string.companion_button_description_secondary
                },
            )
            visibility = if (primaryIdleHidden) {
                View.INVISIBLE
            } else {
                View.VISIBLE
            }
            isFocusable = false
            isClickable = true
        }
        button.setOnTouchListener { view, event -> handleButtonTouch(view, event) }
        // Keep the shipping-sized disc and its overlay window 1:1 so the entire 56dp target is touchable.
        root.addView(
            button,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        return root
    }

    private fun buildMenu(
        context: Context,
        positionOverride: Pair<Int, Int>? = null,
    ): View {
        val theme = overlayTheme
        val role = displayRole
        val rows = companionOverlayMenuRows(role, secondaryReturnAvailable)
        val coinMarks = mutableListOf<View>()
        val fullScreenSecondary = role == CompanionOverlayRole.SECONDARY_COMPANION
        val cardWidth = dp(context, CompanionMenuWidthDp)
        val cardHeight = dp(context, companionOverlayMenuHeightDp(rows.size))
        val cardPosition = if (fullScreenSecondary) {
            val bounds = displayBounds(context)
            val requested = positionOverride ?: loadMenuPosition(context, bounds, cardWidth, cardHeight)
            clampCompanionMenuCardPosition(
                x = requested.first,
                y = requested.second,
                bounds = bounds,
                width = cardWidth,
                height = cardHeight,
                inset = dp(context, MButtonSafeInsetDp),
            )
        } else {
            null
        }
        val book = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isClickable = true
            addView(
                CompanionMenuGlassView(context = context, theme = theme, role = role),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val pageContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
            addView(
                menuTitle(context, theme, role),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(context, CompanionMenuTitleHeightDp),
                ),
            )
            // **A GRID OF COINS, not a ruled list**. The dividers go with the
            // second rank of type they were separating: four hairlines across a five-command menu is
            // the settings-list tell, and with each command now carrying its own mark there is nothing
            // left for them to keep apart.
            val columns = companionOverlayMenuGridColumns(rows.size)
            rows.chunked(columns).forEachIndexed { line, coins ->
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        isFocusable = false
                        // A short last line is centred, half a column either side, forming the ᛗ zigzag.
                        // Left-packing left a dead cell at the bottom right; nudging
                        // the pair into the GAPS above them lands each coin between two of the three,
                        // which is the ᛗ zigzag and, not incidentally, the only arrangement of 3-over-2
                        // with equal margins on both sides.
                        val slack = (columns - coins.size) / 2f
                        if (slack > 0f) {
                            addView(
                                View(context).apply { isFocusable = false },
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, slack),
                            )
                        }
                        coins.forEach { coin ->
                            addView(
                                menuCoin(context, theme, coin, role, coinMarks),
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                            )
                        }
                        if (slack > 0f) {
                            addView(
                                View(context).apply { isFocusable = false },
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, slack),
                            )
                        }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(context, CompanionMenuSlotHeightDp),
                    ).apply { if (line > 0) topMargin = dp(context, CompanionMenuCoinRowGapDp) },
                )
            }
        }
        book.addView(
            pageContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                // **EQUAL, and measured rather than assumed.** These were 29 and 9: unequal on purpose,
                // because 29 was clearing a spine rail and only the remainder read as padding. With the
                // rail retired the two numbers became a plain 12/9 asymmetry, and the row DIVIDERS are
                // what show it — they span exactly this content width, under a top sheen the glass draws
                // symmetrically. Photographed on the Thor at 12.1 dp left against 9.1 dp right before
                // this line changed.
                leftMargin = dp(context, CompanionMenuContentStartDp)
                rightMargin = dp(context, CompanionMenuContentStartDp)
                topMargin = dp(context, CompanionMenuVerticalInsetDp)
                bottomMargin = dp(context, CompanionMenuVerticalInsetDp)
            },
        )
        // OR-11: the lit coin is an index, drawn as one ring view over the whole card so no slot clips it. An open menu that
        // is rebuilt (a theme change) keeps its lit coin; a menu that is opening starts from the default.
        menuFocus = if (menuOpen && menuFocus.itemCount == rows.size) menuFocus else QuickMenuFocus.opened(rows.size)
        // R9a: built for both roles. The Bottom's ring is lit on open like Main's (QuickMenuRingLitOnOpen) and only the pad moves it.
        val focusRing: CompanionMenuFocusRingView = CompanionMenuFocusRingView(context, theme.focusRingArgb, coinMarks, book).also { ring ->
            ring.litIndex = menuFocus.litIndex
            book.addView(
                ring,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        menuRows = rows
        menuRing = focusRing
        // Secondary keeps its two actions (Tabs + Swap). Primary is a separate product surface:
        // Android Tabs + Mimir Quick Launch + Mimir Home, with no dual-display vocabulary or behavior.
        val outsideCapture = if (fullScreenSecondary) SecondaryMenuOutsideTouchCapture() else null
        return object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (overlayTransitionOutgoing && overlayTransitionView === this) return true
                if (event.actionMasked == MotionEvent.ACTION_DOWN) menuLongPressConsumed = false
                // Round-5 triage B2: a DOWN on either route below is the player's own touch on this screen.
                emitScreenActivity(overlayScreenActivityFor(event.actionMasked))
                if (outsideCapture != null) {
                    val insideCard = event.actionMasked == MotionEvent.ACTION_DOWN &&
                        event.x >= book.left && event.x < book.right &&
                        event.y >= book.top && event.y < book.bottom
                    when (outsideCapture.dispatch(event.actionMasked, insideCard)) {
                        SecondaryMenuTouchRoute.CONSUME -> {
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                secondaryMenuGestureActive = true
                                notifyPrimaryIdleInteraction()
                                menuInteraction.begin()
                            }
                            debugMButtonReceipt(
                                "event=MENU_TOUCH action=${MotionEvent.actionToString(event.actionMasked)} " +
                                    "role=$role display=$displayId rawX=${event.rawX} rawY=${event.rawY} " +
                                    "x=${event.x} y=${event.y} handled=true outside=true " +
                                    "pending=${pendingMenuRequestId != null}",
                            )
                            return true
                        }
                        SecondaryMenuTouchRoute.TERMINATE -> {
                            secondaryMenuGestureActive = false
                            secondaryMenuDismissPending = true
                            menuInteraction.finish()
                            notifyPrimaryIdleInteraction()
                            val menuRoot = this
                            handler.post {
                                if (menuOpen && overlayView === menuRoot && displayRole == role) {
                                    secondaryMenuDismissPending = false
                                    secondaryMenuRebuildDeferred = false
                                    collapseMenu()
                                } else {
                                    secondaryMenuDismissPending = false
                                }
                            }
                            debugMButtonReceipt(
                                "event=MENU_TOUCH action=${MotionEvent.actionToString(event.actionMasked)} " +
                                    "role=$role display=$displayId rawX=${event.rawX} rawY=${event.rawY} " +
                                    "x=${event.x} y=${event.y} handled=true outside=true terminal=true " +
                                    "pending=${pendingMenuRequestId != null}",
                            )
                            return true
                        }
                        SecondaryMenuTouchRoute.DELEGATE -> Unit
                    }
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (outsideCapture != null) secondaryMenuGestureActive = true
                        notifyPrimaryIdleInteraction()
                        menuInteraction.begin()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (outsideCapture != null) secondaryMenuGestureActive = false
                        menuInteraction.finish()
                        notifyPrimaryIdleInteraction()
                        if (outsideCapture != null) {
                            val menuRoot = this
                            handler.post {
                                if (menuOpen && overlayView === menuRoot && displayRole == role) {
                                    flushDeferredSecondaryMenuRebuild()
                                }
                            }
                        }
                    }
                }
                val handled = super.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    menuLongPressConsumed = false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> debugMButtonReceipt(
                        "event=MENU_TOUCH action=${MotionEvent.actionToString(event.actionMasked)} " +
                            "role=$role display=$displayId rawX=${event.rawX} rawY=${event.rawY} " +
                            "x=${event.x} y=${event.y} handled=$handled " +
                            "pending=${pendingMenuRequestId != null}",
                    )
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) menuInteraction.finish()
                return handled
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // PRIMARY: the open menu owns keys. SECONDARY (R9a): only while the pad owner is set; a Bottom menu
                // whose owner was cleared (its fade-out, a disposition that left) is not focusable and takes no keys.
                if (!quickMenuRoleOwnsKeys(role)) return super.dispatchKeyEvent(event)
                val result = primaryQuickMenuKeyResult(event.keyCode, event.action)
                val menuPad = result == PrimaryQuickMenuKeyResult.CONSUME || result == PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE
                fun act(): Boolean {
                    // R9a: on the Bottom every pad event the menu takes resets the ᛗ's own hide timer (the screen's idle deadline is the gate's, below).
                    if (role == CompanionOverlayRole.SECONDARY_COMPANION && menuPad) {
                        notePadActivity(event.eventTime, throttled = false)
                    }
                    return when (result) {
                        PrimaryQuickMenuKeyResult.PASS_TO_SYSTEM -> false
                        PrimaryQuickMenuKeyResult.ROUTE_VOLUME_TO_SYSTEM -> {
                            routePrimaryQuickMenuVolume(event)
                            true
                        }
                        PrimaryQuickMenuKeyResult.CONSUME -> {
                            quickMenuNavKeyFor(event.keyCode)?.let { key -> applyPadNavKey(key, event) }
                            true
                        }
                        PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE -> {
                            // Remove the focusable menu only AFTER its dismiss-key UP was consumed. Removing on
                            // DOWN returned focus early and leaked the matching UP into the covered app/router.
                            // On the Bottom this is the pad's close path: B arrives as BUTTON_B, and Back (which the vendor
                            // intercepts on display 4) is never the only way out.
                            handler.post {
                                if (menuOpen && displayRole == role) collapseMenu()
                            }
                            true
                        }
                    }
                }
                // the Bottom's pad is a screen-idle input like Main's own, so it asks Main's idle rule first: a press re-arms the idle controller, and the press that
                // woke a dimmed screen is eaten for its own events (its key up too) and the next press acts. Volume and power are the system's, never the pad's.
                return if (role == CompanionOverlayRole.SECONDARY_COMPANION && menuPad) {
                    padIdleGate.key(
                        down = event.action == KeyEvent.ACTION_DOWN,
                        up = event.action == KeyEvent.ACTION_UP,
                        keyCode = event.keyCode,
                    ) { act() }
                } else {
                    act()
                }
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (!quickMenuRoleOwnsKeys(role)) return super.dispatchGenericMotionEvent(event)
                // OR-11: a D-pad reported as AXIS_HAT_X/Y (or the left stick) moves the ring like the keys do. The event
                // is still consumed, so nothing reaches the app behind the menu and no synthetic D-pad key is generated.
                if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE) {
                    if (role == CompanionOverlayRole.SECONDARY_COMPANION) {
                        // The Bottom's pad under Main's idle rule, sample by sample (PadIdleGate): the wake press and the rest that releases it are eaten, and only the samples the
                        // gate permits move the ring. A joystick stream is continuous, so the hide-timer note is throttled (see notePadActivity).
                        padIdleGate.motion(event.idleWakeSamples()) { permitted ->
                            notePadActivity(event.eventTime, throttled = true)
                            applyPadMotion(event, permitted)
                            true
                        }
                    } else {
                        applyPadMotion(event, null)
                    }
                }
                return true
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            // PRIMARY is focusable from the build. SECONDARY follows the pad owner, which is set when the menu lands, so
            // addCurrentView applies [primaryQuickMenuOwnsWindowFocus] to the root just before the window is added.
            isFocusable = role == CompanionOverlayRole.PRIMARY_SINGLE
            isFocusableInTouchMode = role == CompanionOverlayRole.PRIMARY_SINGLE
            addView(
                book,
                if (fullScreenSecondary) {
                    FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
                        leftMargin = cardPosition?.first ?: 0
                        topMargin = cardPosition?.second ?: 0
                    }
                } else {
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            // Tap-outside dismisses (companion review M-1): FLAG_WATCH_OUTSIDE_TOUCH delivers ACTION_OUTSIDE
            // to the window root. The Bottom menu's own tap-outside is the full-display host's TERMINATE route above, not this
            // listener. The menu's temporary key-focus quarantine (PRIMARY always, SECONDARY while the pad owner is set)
            // handles B/Back in dispatchKeyEvent above and collapses only after UP.
            setOnTouchListener { _, event ->
                if (role == CompanionOverlayRole.PRIMARY_SINGLE &&
                    event.actionMasked == MotionEvent.ACTION_OUTSIDE &&
                    menuOpen
                ) {
                    debugMButtonReceipt(
                        "event=ACTION_OUTSIDE menu=collapse role=$displayRole display=$displayId " +
                            "rawX=${event.rawX} rawY=${event.rawY}",
                    )
                    collapseMenu()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun routePrimaryQuickMenuVolume(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return
        }
        runCatching {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI,
            )
        }.onFailure { Log.w(TAG, "failed to route primary quick-menu volume key", it) }
    }

    /** True while the menu of [role] owns raw key and HAT input: PRIMARY whenever its menu is open, SECONDARY while the pad owner is set. */
    private fun quickMenuRoleOwnsKeys(role: CompanionOverlayRole): Boolean = when (role) {
        CompanionOverlayRole.PRIMARY_SINGLE -> true
        CompanionOverlayRole.SECONDARY_COMPANION -> secondaryPadOwner != null
    }

    /**
     * OR-11: one navigation or confirm press on the open quick menu, in press order. [reduceQuickMenuFocus] owns the rules;
     * this applies its answer: repaint the ring and, on a confirm release, run the lit coin exactly as a tap on it would.
     */
    private fun applyQuickMenuFocusStep(key: QuickMenuNavKey, action: Int, repeatCount: Int) {
        if (!menuOpen || !quickMenuRoleOwnsKeys(displayRole)) return
        val step = reduceQuickMenuFocus(menuFocus, key, action, repeatCount)
        menuFocus = step.state
        menuRing?.litIndex = step.state.litIndex
        debugMButtonReceipt(
            "event=MENU_FOCUS key=$key action=$action lit=${step.state.litIndex} activate=${step.activate}",
        )
        val row = step.activate?.let { index -> menuRows.getOrNull(index) } ?: return
        emitMenuAction(row, displayRole)
    }

    private fun applyPadOps(ops: List<QuickMenuPadKey>) {
        ops.forEach { applyQuickMenuFocusStep(it.key, it.action, it.repeatCount) }
    }

    /**
     * One D-pad or confirm key through the press-order buffer ([QuickMenuPadOrder]). A confirm waits for its frame; a
     * direction goes in press order, ahead of a held confirm only when it was pressed before it. A direction's own UP
     * moves nothing.
     */
    private fun applyPadNavKey(key: QuickMenuNavKey, event: KeyEvent) {
        if (key == QuickMenuNavKey.CONFIRM) {
            applyPadOps(padOrder.confirm(event.action, event.repeatCount, event.eventTime, canHold = true))
            if (padOrder.holding) schedulePadFrame()
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            applyPadOps(padOrder.move(key, event.repeatCount, event.eventTime))
        }
    }

    /**
     * A joystick sample as direction presses, each stamped with its own event time. The batched HISTORY is read first and in
     * order: a press and release that land inside one batch used to leave only the released current sample, so the
     * press never existed. [permitted] flags the samples the idle gate let through (oldest first, the current one last; null is all of them): a sample it ate as the wake
     * press is never replayed here, the way `InputRouter.onMotionEvent` skips them on Main.
     */
    private fun applyPadMotion(event: MotionEvent, permitted: BooleanArray?) {
        for (index in 0 until event.historySize) {
            if (permitted != null && !permitted.getOrElse(index) { true }) continue
            applyPadAxisSample(
                hatX = event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, index),
                stickX = event.getHistoricalAxisValue(MotionEvent.AXIS_X, index),
                hatY = event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, index),
                stickY = event.getHistoricalAxisValue(MotionEvent.AXIS_Y, index),
                timeMs = event.getHistoricalEventTime(index),
            )
        }
        if (permitted != null && !permitted.getOrElse(event.historySize) { true }) return
        applyPadAxisSample(
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X),
            stickX = event.getAxisValue(MotionEvent.AXIS_X),
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            stickY = event.getAxisValue(MotionEvent.AXIS_Y),
            timeMs = event.eventTime,
        )
    }

    private fun applyPadAxisSample(hatX: Float, stickX: Float, hatY: Float, stickY: Float, timeMs: Long) {
        padAxisEdges.step(quickMenuAxisDirection(hatX, stickX), quickMenuAxisDirection(hatY, stickY)).forEach { key ->
            applyPadOps(padOrder.move(key, 0, timeMs))
        }
    }

    /** Waits for the frame's input stage, then releases whatever confirm is still held. One callback at a time. */
    private fun schedulePadFrame() {
        if (padFrameCancel != null) return
        padFrameCancel = padFrameScheduler.afterNextFrameInput {
            padFrameCancel = null
            if (menuOpen && quickMenuRoleOwnsKeys(displayRole)) applyPadOps(padOrder.frame()) else padOrder.reset()
        }
    }

    /** Drops a held confirm, the pending frame callback and the hat's held-direction state. */
    private fun resetPadInput() {
        padOrder.reset()
        padFrameCancel?.invoke()
        padFrameCancel = null
        padAxisEdges = QuickMenuAxisEdges()
        lastPadActivityMs = -1L
    }

    /**
     * R9a: a Bottom pad event the menu takes resets the ᛗ's own hide timer. Key edges are counted each; a joystick stream is counted at most once per
     * [PadActivityThrottleMs], because a deflected stick reports at the input rate. The SCREEN's idle deadline is not noted here any more: the Bottom's pad never reaches Main's
     * input path, so [padIdleGate] (Main's own idle rule) hands each press to Main's idle controller, which re-arms it and answers whether the press woke a dimmed screen.
     */
    private fun notePadActivity(eventTimeMs: Long, throttled: Boolean) {
        val last = lastPadActivityMs
        if (throttled && last >= 0L && eventTimeMs >= last && eventTimeMs - last < PadActivityThrottleMs) return
        lastPadActivityMs = eventTimeMs
        notifyPrimaryIdleInteraction()
    }

    /**
     * The menu has landed (open) or left (closed). SECONDARY: open sets the owner to [BottomPadOwner.MENU] and closed clears
     * it. Always called BEFORE the incoming window is added, so [overlayParams] reads the owner that window will live under.
     * A rebuild of an open menu (a theme or Return-row rebuild: [wasOpen] and [open] both true) keeps the ownership state it
     * found, granted or revoked, and drops only a held confirm: its root is new. It never grants again what a mirror_left or
     * any other revoke took away; only a menu that opens from closed grants.
     */
    private fun syncSecondaryPadOwnerToMenu(open: Boolean, wasOpen: Boolean) {
        resetPadInput()
        if (displayRole != CompanionOverlayRole.SECONDARY_COMPANION) return
        if (open && !secondaryMenuCollapseInFlight) {
            if (!wasOpen || secondaryPadOwner != null) setSecondaryPadOwner(BottomPadOwner.MENU)
        } else {
            clearSecondaryPadOwner("menu_landed_closed", updateWindow = false)
        }
    }

    private fun setSecondaryPadOwner(owner: BottomPadOwner) {
        if (secondaryPadOwner == owner) return
        secondaryPadOwner = owner
        BottomPadInput.register(floorPadKeys, floorPadMotion)
        debugMButtonReceipt("event=BOTTOM_PAD_OWNER owner=$owner state=set display=$displayId")
    }

    /**
     * Every exit from pad ownership goes through here: the close of a menu (B, tap-outside, a command, an accepted pick), a
     * display that was removed, a mount or unmount, a disposition that left the mirror, a window that failed to add, the
     * overlay hidden. It always drops a held confirm and the pending frame callback and resets the hat (the native coins have
     * no hat-repeat ticker to stop; the held-direction reader is that state). With an owner set it also takes the window out
     * of key focus at once, so focus falls to the floor, the one window on display 4 that is always focusable, instead of
     * staying on a menu that is fading out or no longer meant to hold the pad.
     */
    private fun clearSecondaryPadOwner(reason: String, updateWindow: Boolean = true) {
        resetPadInput()
        val owner = secondaryPadOwner ?: return
        secondaryPadOwner = null
        BottomPadInput.release(floorPadKeys, floorPadMotion)
        debugMButtonReceipt("event=BOTTOM_PAD_OWNER owner=$owner state=cleared why=$reason display=$displayId")
        if (!updateWindow) return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        if (displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            view.clearFocus()
        }
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if ((lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(view, lp) }
            .onFailure { Log.w(TAG, "failed to take the Bottom menu out of key focus", it) }
    }

    private fun menuTitle(
        context: Context,
        theme: CompanionOverlayTheme,
        role: CompanionOverlayRole,
    ): View =
        TextView(context).apply {
            val res = menuResources(context)
            text = companionOverlayMenuTitleSpanned(companionOverlayMenuTitle(res, role))
            contentDescription = res.getString(
                if (role == CompanionOverlayRole.PRIMARY_SINGLE) {
                    R.string.companion_menu_title_primary_description
                } else {
                    R.string.companion_menu_title_secondary_description
                },
            )
            textSize = CompanionMenuTitleTextSp
            typeface = menuLabelTypeface ?: Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(theme.inkArgb)
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener { _, event -> handleMenuDrag(event) }
        }

    private fun emitMenuAction(
        row: CompanionOverlayMenuRowCopy,
        role: CompanionOverlayRole,
        item: QuickMenuItem = row.item,
    ): Boolean {
        // The outgoing root stays attached during its short fade, but its children still receive
        // clicks even when the root is disabled. Treat that interval as a visual-only handoff so a
        // release cannot issue a command against a menu that is already leaving.
        if (overlayTransitionOutgoing && overlayTransitionView === overlayView) return false
        if (pendingMenuRequestId != null) return true
        val requestId = UUID.randomUUID()
        pendingMenuRequestId = requestId
        val emitted = _actions.tryEmit(CompanionOverlayAction.QuickMenu(requestId, role, item))
        debugMButtonReceipt(
            "event=MENU_ITEM_REQUEST request=$requestId role=$role item=$item " +
                "source=${row.item} tryEmit=$emitted",
        )
        if (!emitted) pendingMenuRequestId = null
        return emitted
    }

    /**
     * One command: a rune coin over its name, with the WHOLE SLOT as the touch target.
     *
     * The slot is the target rather than the coin — a 56 dp coin already clears the 48 dp floor, but a finger aimed at the NAME under it
     * would land on nothing, and a label that is not pressable while it looks like part of the button
     * is the same defect as a control drawn live that is inert.
     *
     * `duplicateParentState` on both wrappers is what carries the press down to the coin's oval, so the
     * mark lights from a press anywhere in its slot instead of only from a direct hit.
     */
    private fun menuCoin(
        context: Context,
        theme: CompanionOverlayTheme,
        row: CompanionOverlayMenuRowCopy,
        role: CompanionOverlayRole,
        marks: MutableList<View>? = null,
    ): View = FrameLayout(context).apply {
        isClickable = true
        isFocusable = false
        // The caption is not gone, it is spoken. Unchanged from when it was drawn.
        val res = menuResources(context)
        contentDescription = "${res.getString(row.titleRes)}. ${res.getString(row.subtitleRes)}"
        minimumHeight = dp(context, CompanionMenuMinimumTouchTargetDp)
        minimumWidth = dp(context, CompanionMenuMinimumTouchTargetDp)
        setOnClickListener {
            debugMButtonReceipt(
                "event=MENU_ITEM_ENTRY role=$role display=$displayId item=${row.item} " +
                    "pending=${pendingMenuRequestId != null}",
            )
            if (menuLongPressConsumed) {
                // Android normally suppresses click after a handled long-click. Keep the release
                // consumed explicitly for OEM dispatchers that synthesize one anyway.
                menuLongPressConsumed = false
                return@setOnClickListener
            }
            emitMenuAction(row, role)
        }
        setOnLongClickListener {
            val item = companionOverlayMenuLongPressItem(row.item)
                ?: return@setOnLongClickListener false
            // Tabs is the one command whose hold has a distinct, deliberate meaning: Android Recents,
            // on either native menu role. Return true so the subsequent release cannot short-click Tabs.
            val emitted = emitMenuAction(row, role, item)
            menuLongPressConsumed = emitted
            debugMButtonReceipt(
                "event=MENU_ITEM_LONG role=$role display=$displayId item=$item " +
                    "source=${row.item} emitted=$emitted",
            )
            emitted
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = false
                isDuplicateParentStateEnabled = true
                addView(
                    CompanionMenuCoinView(context, theme, row.rune).apply {
                        marks?.add(this)
                        isDuplicateParentStateEnabled = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(
                        dp(context, CompanionMenuCoinSizeDp),
                        dp(context, CompanionMenuCoinSizeDp),
                    ),
                )
                addView(
                    TextView(context).apply {
                        text = menuResources(context).getString(row.titleRes)
                        textSize = CompanionMenuCoinLabelTextSp
                        typeface = menuLabelTypeface ?: Typeface.DEFAULT_BOLD
                        // Plex's line box is taller than the platform's, and the band is a fixed 28 dp: with the font's own top and bottom
                        // padding the second line of "Return to shelf" at font scale 1.3 ended 2 px below it (1 px, in the old face). The
                        // text sits flush to the top of the band, which is where it fits.
                        includeFontPadding = false
                        letterSpacing = 0.05f
                        setTextColor(theme.mutedInkArgb)
                        gravity = Gravity.CENTER_HORIZONTAL
                        // Two lines are allowed so "Quick launch" keeps its real name instead of being
                        // abbreviated to fit; the band is a FIXED height either way, so a one-word
                        // neighbour cannot make the row reflow.
                        maxLines = 2
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(context, CompanionMenuCoinLabelHeightDp),
                    ).apply { topMargin = dp(context, CompanionMenuCoinLabelGapDp) },
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
    }

    /**
     * [mButtonTapMayOpenRecents] on this controller's own state. The mounted root is disabled the moment a swap starts, but the
     * coin is its child and keeps receiving touches until the 130 ms hand-over, and `menuOpen` only lands there: so the
     * authority is the swap, collapse and pending-request state, read at the release as well as at the press.
     */
    private fun coinTapMayOpenRecents(): Boolean = mButtonTapMayOpenRecents(
        menuOpen = menuOpen,
        swapInFlight = _quickMenuSwapInFlight.value,
        transitionOutgoing = overlayTransitionOutgoing,
        collapseInFlight = secondaryMenuCollapseInFlight,
        menuRequestPending = pendingMenuRequestId != null,
        touchEnabled = overlayView?.isEnabled != false,
    )

    private fun handleButtonTouch(view: View, event: MotionEvent?): Boolean {
        event ?: return false
        // Round-5 triage B2: the ᛗ DOWN counts for Main's screen idle, so the hold that opens the menu wakes a dim Main.
        emitScreenActivity(overlayScreenActivityFor(event.actionMasked))
        val lp = overlayView?.layoutParams as? WindowManager.LayoutParams
        val context = displayContext ?: appContext
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Under a keyboard on the Bottom the button is unavailable: a touch that still reaches it is ignored whole.
                buttonGestureAborted = bottomImeShown() && !menuOpen
                if (buttonGestureAborted) return true
                debugMButtonReceipt(
                    "event=BUTTON_DOWN role=$displayRole display=$displayId " +
                        "rawX=${event.rawX} rawY=${event.rawY}",
                )
                notifyPrimaryIdleInteraction()
                // A coin still gliding home lands at once: the window is touchable the whole way, and the touch below starts from where the coin rests.
                finishButtonRestGlide()
                mButtonInteraction.begin()
                downRawX = event.rawX
                downRawY = event.rawY
                startX = lp?.x ?: 0
                startY = lp?.y ?: 0
                dragging = false
                longPressFired = false
                // A touch that began while a menu was up, opening or closing never opens Recents, whatever the state is by its release.
                recentsBlockedAtDown = !coinTapMayOpenRecents()
                cancelLongPress()
                longPressRunnable = Runnable {
                    longPressRunnable = null
                    if (!dragging) {
                        longPressFired = true
                        debugMButtonReceipt(
                            "event=LONG_PRESS_FIRED role=$displayRole display=$displayId",
                        )
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showMenu()
                    }
                }.also { handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                // CP01: the button fills its 56dp window, so a grow-on-press (1.08) would spill past
                // the window and clip. Press IN instead — a shrink can never exceed the window, and reads
                // as a real button being pushed. The depth and the beat are the Bottom's one press response (B5).
                view.animate().alpha(pressedAlpha()).scaleX(companionButtonPressScale(displayRole)).scaleY(companionButtonPressScale(displayRole))
                    .setDuration(companionButtonPressMs(displayRole)).setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (lp == null || buttonGestureAborted) return true
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && mButtonMoveIsDrag(dx, dy, dp(context, MButtonDragSlopDp).toFloat())) {
                    dragging = true
                    cancelLongPress()
                    // Despite the name this is the DRAG START (it cancels the pending long press): a good drag logs DOWN, this, UP dragging=true.
                    debugMButtonReceipt(
                        "event=DRAG_CANCEL role=$displayRole display=$displayId",
                    )
                }
                if (dragging) {
                    val bounds = displayBounds(context)
                    lp.x = (startX + dx.roundToInt()).coerceIn(0, max(0, bounds.first - lp.width))
                    lp.y = (startY + dy.roundToInt()).coerceIn(0, max(0, bounds.second - lp.height))
                    runCatching { windowManager?.updateViewLayout(overlayView, lp) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (buttonGestureAborted) return true
                mButtonInteraction.finish()
                cancelLongPress()
                val wasDragging = dragging
                val pressFired = longPressFired
                debugMButtonReceipt(
                    "event=BUTTON_UP role=$displayRole display=$displayId " +
                        "longPressFired=$pressFired dragging=$wasDragging",
                )
                dragging = false
                view.animate().alpha(restingAlpha()).scaleX(1f).scaleY(1f).setDuration(companionButtonReleaseMs(displayRole)).setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()
                when (
                    classifyMButtonRelease(
                        displayRole,
                        longPressFired = pressFired,
                        wasDragging = wasDragging,
                        musicPanelAvailable = musicPanelAvailable,
                        recentsMayOpen = !recentsBlockedAtDown && coinTapMayOpenRecents(),
                    )
                ) {
                    MButtonReleaseOutcome.HOLD_HANDLED -> longPressFired = false
                    // A slow, deliberate drag is a reposition. On the single-display badge it settles on the nearest screen edge (OR-2), gliding there; the Bottom coin stays exactly
                    // where it was dropped (below), on every page and over every app (policy 2026-10-09, which retired the 2026-10-08 corner slot).
                    MButtonReleaseOutcome.DRAG_SNAP -> settleButtonAtRest(lp, context)
                    MButtonReleaseOutcome.DRAG_KEEP -> saveButtonPosition(lp, context)
                    // A plain tap on the single-display badge opens the same menu as a hold. On the Thor
                    // second display the tap opens Recents instead. Return to the previous state remains
                    // the right-edge swipe.
                    MButtonReleaseOutcome.TAP_OPENS_MENU -> showMenu()
                    // with music to control, the same tap opens the music panel instead. The app hosts it
                    // (its own focus graph, its own window); the badge stays put and the host hides it while the panel is up.
                    MButtonReleaseOutcome.TAP_OPENS_MUSIC -> {
                        val emitted = _actions.tryEmit(CompanionOverlayAction.MusicPanel(displayRole))
                        debugMButtonReceipt("event=MUSIC_PANEL_REQUEST role=$displayRole display=$displayId tryEmit=$emitted")
                    }
                    // a light press on the Bottom's badge opens Android
                    // Recents on MAIN. It is the action the menu's Tabs row emits, so the app's one SYSTEM_TABS route runs it
                    // (the PServer bridge's keyevent 187 on the default display) and its failure toast. No menu is open, so
                    // no request is pending here: the app's completeQuickMenu for this fresh id returns at once.
                    MButtonReleaseOutcome.TAP_OPENS_RECENTS -> {
                        val emitted = _actions.tryEmit(
                            CompanionOverlayAction.QuickMenu(
                                UUID.randomUUID(),
                                displayRole,
                                QuickMenuItem.SYSTEM_TABS,
                                QuickMenuOrigin.COIN_TAP,
                            ),
                        )
                        debugMButtonReceipt("event=RECENTS_TAP_REQUEST role=$displayRole display=$displayId tryEmit=$emitted")
                    }
                    // A tap while a menu is open, opening, closing or mid-swap: nothing, preserving the Bottom tap behavior.
                    MButtonReleaseOutcome.TAP_IGNORED -> debugMButtonReceipt("event=RECENTS_TAP_IGNORED role=$displayRole display=$displayId")
                }
                notifyPrimaryIdleInteraction()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (buttonGestureAborted) return true
                mButtonInteraction.finish()
                cancelLongPress()
                debugMButtonReceipt(
                    "event=BUTTON_CANCEL role=$displayRole display=$displayId " +
                        "longPressFired=$longPressFired dragging=$dragging",
                )
                val wasDragging = dragging
                dragging = false
                longPressFired = false
                view.animate().alpha(restingAlpha()).scaleX(1f).scaleY(1f).setDuration(companionButtonReleaseMs(displayRole)).setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()
                // A drag the system cancelled mid-move (a gesture or a dialog taking the touch) leaves the window where the finger last put it, and that place is committed exactly
                // as a release commits it: the Bottom coin saves where it is, Main's coin takes the edge snap and its glide. Without this the next start found the position before the drag.
                if (wasDragging) {
                    when (classifyMButtonRelease(displayRole, longPressFired = false, wasDragging = true)) {
                        MButtonReleaseOutcome.DRAG_SNAP -> settleButtonAtRest(lp, context)
                        MButtonReleaseOutcome.DRAG_KEEP -> saveButtonPosition(lp, context)
                        else -> Unit
                    }
                }
                notifyPrimaryIdleInteraction()
                return true
            }
        }
        return false
    }

    private fun handleMenuDrag(event: MotionEvent?): Boolean {
        event ?: return false
        val context = displayContext ?: appContext
        if (displayRole == CompanionOverlayRole.SECONDARY_COMPANION && menuOpen) {
            val root = overlayView as? FrameLayout ?: return true
            val card = root.getChildAt(0) ?: return true
            val cardLp = card.layoutParams as? FrameLayout.LayoutParams ?: return true
            // Perf 2026-09-11 §4.4a: a margin write per MOVE re-measured and re-laid-out the card, whose coins
            // build their shaders in onDraw, and the Thor drew 10 frames in a 585 ms drag. The card follows the
            // finger by translation (a RenderNode property, no layout); the margins take the position once, on
            // UP or CANCEL. Mid-gesture readers use the effective position, so a rebuild keeps the card in place.
            // The ᛗ BUTTON's drag still moves its window: that window is sized to the button, so translating the
            // view inside it would clip.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val (x, y) = effectiveCardPosition(card, cardLp)
                    startX = x
                    startY = y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > dp(context, 8)) dragging = true
                    if (dragging) {
                        val (x, y) = clampCompanionMenuCardPosition(
                            x = startX + dx.roundToInt(),
                            y = startY + dy.roundToInt(),
                            bounds = displayBounds(context),
                            width = cardLp.width,
                            height = cardLp.height,
                            inset = dp(context, MButtonSafeInsetDp),
                        )
                        card.translationX = (x - cardLp.leftMargin).toFloat()
                        card.translationY = (y - cardLp.topMargin).toFloat()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val position = commitCardTranslation(card, cardLp)
                    if (dragging) saveMenuPosition(position, context)
                    dragging = false
                    return true
                }
            }
            return false
        }
        val lp = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > dp(context, 8)) dragging = true
                if (dragging) {
                    val bounds = displayBounds(context)
                    lp.x = (startX + dx.roundToInt()).coerceIn(0, max(0, bounds.first - lp.width))
                    lp.y = (startY + dy.roundToInt()).coerceIn(0, max(0, bounds.second - lp.height))
                    runCatching { windowManager?.updateViewLayout(overlayView, lp) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) saveMenuPosition(lp, context)
                dragging = false
                return true
            }
        }
        return false
    }

    private fun currentSecondaryMenuCardPosition(): Pair<Int, Int>? {
        if (!menuOpen || displayRole != CompanionOverlayRole.SECONDARY_COMPANION) return null
        val root = overlayView as? FrameLayout ?: return null
        val card = root.getChildAt(0) ?: return null
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return null
        return effectiveCardPosition(card, lp)
    }

    /** Where the card is drawn mid-gesture: its committed margins plus the drag translation not yet folded in. */
    private fun effectiveCardPosition(card: View, cardLp: FrameLayout.LayoutParams): Pair<Int, Int> =
        secondaryMenuCardEffectivePosition(cardLp.leftMargin, cardLp.topMargin, card.translationX, card.translationY)

    /** Fold a drag's translation into the margins, the gesture's one layout, and return the committed position. */
    private fun commitCardTranslation(card: View, cardLp: FrameLayout.LayoutParams): Pair<Int, Int> {
        val position = effectiveCardPosition(card, cardLp)
        if (card.translationX != 0f || card.translationY != 0f) {
            cardLp.leftMargin = position.first
            cardLp.topMargin = position.second
            card.translationX = 0f
            card.translationY = 0f
            card.layoutParams = cardLp
        }
        return position
    }

    private fun flushDeferredSecondaryMenuRebuild() {
        if (!secondaryMenuRebuildDeferred) return
        if (secondaryMenuGestureActive || secondaryMenuDismissPending) return
        secondaryMenuRebuildDeferred = false
        if (!menuOpen || displayRole != CompanionOverlayRole.SECONDARY_COMPANION) return
        val context = displayContext ?: appContext
        replaceView(
            buildMenu(context, positionOverride = currentSecondaryMenuCardPosition()),
            targetMenuOpen = true,
        )
    }

    private fun showMenu() {
        notifyPrimaryIdleInteraction()
        pendingMenuRequestId = null
        menuLongPressConsumed = false
        val context = displayContext ?: appContext
        replaceView(buildMenu(context), targetMenuOpen = true, animateOutgoing = true)
    }

    private fun collapseMenu() = collapseMenu(animate = true)

    /** [handOffMenuToMimir]'s end: the card is already hidden, so the button returns without a second fade. */
    private fun collapseMenuWithoutFade() = collapseMenu(animate = false)

    private fun collapseMenu(animate: Boolean) {
        // Every way the menu closes passes here (an accepted pick, B after its release, tap-outside), so the pad owner
        // clears here, BEFORE the swap: the outgoing window leaves key focus at once and the incoming button is built
        // non-focusable. A held confirm of either role's menu dies with it.
        clearSecondaryPadOwner("collapse")
        pendingMenuRequestId = null
        menuLongPressConsumed = false
        secondaryMenuGestureActive = false
        secondaryMenuDismissPending = false
        secondaryMenuRebuildDeferred = false
        secondaryMenuCollapseInFlight = menuOpen
        val context = displayContext ?: appContext
        replaceView(buildButton(context), targetMenuOpen = false, animateOutgoing = animate)
        notifyPrimaryIdleInteraction()
    }

    private fun replaceView(
        view: View,
        targetMenuOpen: Boolean,
        positionOverride: Pair<Int, Int>? = null,
        animateOutgoing: Boolean = false,
    ): Boolean {
        // The glide moves the window being replaced: land it, so its frames never move (or resize) the view that is leaving.
        finishButtonRestGlide()
        val wm = windowManager ?: return false
        finishOverlayViewInteractions()
        val previous = overlayView
        if (animateOutgoing && previous != null && visible) {
            return animateOverlaySwap(previous, view, targetMenuOpen, positionOverride, wm)
        }
        return replaceViewImmediately(view, targetMenuOpen, positionOverride, wm)
    }

    private fun replaceViewImmediately(
        view: View,
        targetMenuOpen: Boolean,
        positionOverride: Pair<Int, Int>?,
        wm: WindowManager,
    ): Boolean {
        cancelOverlayTransition()
        val previous = overlayView
        if (previous != null) {
            val removed = runCatching {
                wm.removeViewImmediate(previous)
                true
            }.onFailure {
                Log.w(TAG, "failed to replace companion overlay without stacking", it)
            }.getOrDefault(false)
            if (!removed) return false
        }
        val wasMenuOpen = menuOpen
        menuOpen = targetMenuOpen
        if (!targetMenuOpen) secondaryMenuCollapseInFlight = false
        syncSecondaryPadOwnerToMenu(targetMenuOpen, wasMenuOpen)
        overlayView = view
        if (!addCurrentView(positionOverride, animate = false)) {
            overlayView = null
            clearSecondaryPadOwner("add_failed", updateWindow = false)
            mountReconciler.reconcile()
            return false
        }
        renderedOverlayTheme = overlayTheme
        return true
    }

    /** Fade the outgoing root, then mount exactly one incoming root; every callback is generation-owned. */
    private fun animateOverlaySwap(
        previous: View,
        replacement: View,
        targetMenuOpen: Boolean,
        positionOverride: Pair<Int, Int>?,
        wm: WindowManager,
    ): Boolean {
        cancelOverlayTransition()
        val generation = ++overlayTransitionGeneration
        overlayTransitionView = previous
        overlayTransitionOutgoing = true
        // Perf 2026-09-11 §4.4b: the destination value goes out as the fade starts, so the scene behind the menu
        // blurs (or sharpens) with it instead of after the swap lands below. The logic value stays where it is.
        // In flight BEFORE the destination goes out, so a reader that sees the visual value change already knows a swap runs.
        _quickMenuSwapInFlight.value = true
        _quickMenuVisualOpen.value = targetMenuOpen
        previous.isEnabled = false
        previous.isClickable = false
        // The hand-over runs once: from the outgoing animator's own clock at CompanionOverlaySwapHandoffMs (so it follows the system
        // animation scale, as the fade does), or from its end when that clock never reaches it (animations off).
        var handedOver = false
        fun handOver() {
            if (handedOver) return
            handedOver = true
            if (overlayTransitionGeneration != generation || overlayView !== previous) return
            overlayTransitionView = null
            val removed = runCatching {
                wm.removeViewImmediate(previous)
                true
            }.onFailure {
                Log.w(TAG, "failed to remove outgoing companion overlay", it)
            }.getOrDefault(false)
            if (!removed || overlayTransitionGeneration != generation || overlayView !== previous) {
                // The root is still attached, and its fade is still running: stop it first, or the frames that follow drive it on to
                // alpha 0 and leave invisible chrome that takes touches.
                previous.animate().setUpdateListener(null).setListener(null).cancel()
                previous.isEnabled = true
                previous.isClickable = true
                previous.alpha = if (primaryIdleHidden && !menuOpen) 0f else if (menuOpen) 1f else restingAlpha()
                previous.scaleX = 1f
                previous.scaleY = 1f
                overlayTransitionOutgoing = false
                // The swap did not land, so its visual lead ends with it, unless a newer swap owns the flow.
                if (overlayTransitionGeneration == generation) {
                    _quickMenuSwapInFlight.value = false
                    _quickMenuVisualOpen.value = menuOpen
                }
                return
            }
            overlayTransitionOutgoing = false
            val wasMenuOpen = menuOpen
            menuOpen = targetMenuOpen
            if (!targetMenuOpen) secondaryMenuCollapseInFlight = false
            syncSecondaryPadOwnerToMenu(targetMenuOpen, wasMenuOpen)
            overlayView = replacement
            // The removed root's tail is invisible now; stop it so it neither ticks nor calls back into the swap it left.
            previous.animate().setUpdateListener(null).setListener(null).cancel()
            if (!addCurrentView(positionOverride, animate = true)) {
                overlayView = null
                overlayTransitionView = null
                clearSecondaryPadOwner("add_failed", updateWindow = false)
                _quickMenuSwapInFlight.value = false
                mountReconciler.reconcile()
            } else {
                renderedOverlayTheme = overlayTheme
                // The incoming window's own animation ends the swap; a window that is not shown has none.
                if (!visible) _quickMenuSwapInFlight.value = false
            }
        }
        previous.animate()
            .alpha(0f)
            .setDuration(CompanionOverlaySwapDurationMs)
            .setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f))
            .setUpdateListener { fade -> if (fade.currentPlayTime >= CompanionOverlaySwapHandoffMs) handOver() }
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = handOver()
            })
            .start()
        return true
    }

    private fun addCurrentView(
        positionOverride: Pair<Int, Int>? = null,
        animate: Boolean = false,
    ): Boolean {
        val wm = windowManager ?: return false
        val view = overlayView ?: return false
        val targetAlpha = if (primaryIdleHidden && !menuOpen) 0f else if (menuOpen) 1f else restingAlpha()
        view.alpha = if (animate) 0f else targetAlpha
        // The Bottom's menu rises its 8 dp into place: its window is the whole display, so nothing clips it. Every other root only fades,
        // because its window is sized to its view and a translation inside it would clip the card's lower rim or the button's disc (the
        // CP01 note in handleButtonTouch): the ᛗ button, and Main's single-display menu. No scale on any.
        view.translationY = if (animate && menuOpen && displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
            dp(displayContext ?: appContext, CompanionOverlaySwapRiseDp).toFloat()
        } else {
            0f
        }
        view.visibility = overlayViewVisibility()
        // R9a: the window takes key focus under one rule (PRIMARY: menu open; SECONDARY: pad owner set), and the root's own
        // focusability follows it. requestFocus is View focus inside this one window, never a request across displays.
        val ownsFocus = primaryQuickMenuOwnsWindowFocus(displayRole, menuOpen, secondaryPadOwner)
        if (displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
            view.isFocusable = ownsFocus
            view.isFocusableInTouchMode = ownsFocus
        }
        val added = runCatching {
            wm.addView(view, overlayParams(displayContext ?: appContext, positionOverride))
            if (ownsFocus) {
                view.requestFocus()
            }
        }
            .onFailure { Log.w(TAG, "failed to add companion overlay", it) }
            .isSuccess
        if (added && animate && visible) {
            animateOverlayReplacement(view, targetAlpha)
        }
        return added
    }

    private fun animateOverlayReplacement(
        view: View,
        targetAlpha: Float,
        durationMs: Long = CompanionOverlaySwapDurationMs,
    ) {
        val generation = ++overlayTransitionGeneration
        overlayTransitionView = view
        view.animate()
            .alpha(targetAlpha)
            .translationY(0f)
            .setDuration(durationMs)
            .setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (overlayTransitionGeneration != generation || overlayView !== view) return
                    overlayTransitionView = null
                    view.alpha = targetAlpha
                    view.translationY = 0f
                    view.animate().setListener(null)
                    // The incoming window has finished its own animation: a menu swap, if one was running, is over.
                    _quickMenuSwapInFlight.value = false
                }
            })
            .start()
    }

    private fun cancelOverlayTransition() {
        overlayTransitionGeneration += 1
        overlayTransitionView?.let { current ->
            current.animate().setUpdateListener(null).setListener(null).cancel()
            current.alpha = if (primaryIdleHidden && !menuOpen) 0f else if (menuOpen) 1f else restingAlpha()
            current.scaleX = 1f
            current.scaleY = 1f
            current.translationY = 0f
            current.isEnabled = true
            current.isClickable = true
        }
        overlayTransitionView = null
        overlayTransitionOutgoing = false
        // A cancelled swap never reaches its menuOpen write, so its visual lead ends here.
        // The swap is over before the visual value settles back, so a reader never sees a settled value mid-swap.
        _quickMenuSwapInFlight.value = false
        _quickMenuVisualOpen.value = menuOpen
    }

    private fun overlayParams(
        context: Context,
        positionOverride: Pair<Int, Int>? = null,
    ): WindowManager.LayoutParams {
        val button = !menuOpen
        val buttonSize = dp(context, MButtonSizeDp)
        val normalWidth = if (button) buttonSize else dp(context, CompanionMenuWidthDp)
        // The window is sized from the rows this role actually carries, so the two can never disagree.
        val menuHeight = companionOverlayMenuHeightDp(
            companionOverlayMenuRows(displayRole, secondaryReturnAvailable).size,
        )
        val normalHeight = if (button) buttonSize else dp(context, menuHeight)
        val fullScreenSecondaryMenu = !button && displayRole == CompanionOverlayRole.SECONDARY_COMPANION
        val idleMinimized = button &&
            primaryIdleHidden
        val width = if (idleMinimized) PrimaryIdleWatcherSizePx else normalWidth
        val height = if (idleMinimized) PrimaryIdleWatcherSizePx else normalHeight
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = (if (primaryQuickMenuOwnsWindowFocus(displayRole, menuOpen, secondaryPadOwner)) {
            0
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }) or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            // The coin passes every touch through while a Mimir panel hides it; the menu, which is never hidden, is untouched.
            (if (button) companionCoinTouchFlag(coinHiddenForPanel()) else 0)
        val bounds = displayBounds(context)
        val windowWidth = if (fullScreenSecondaryMenu) bounds.first else width
        val windowHeight = if (fullScreenSecondaryMenu) bounds.second else height
        val requestedPosition = if (idleMinimized) primaryIdleVisiblePosition ?: positionOverride else positionOverride
        val xy = if (fullScreenSecondaryMenu) {
            0 to 0
        } else if (requestedPosition != null) {
            clampCompanionOverlayPosition(
                x = requestedPosition.first,
                y = requestedPosition.second,
                bounds = bounds,
                // Preserve the safe visible position while minimized; do not let a one-pixel watcher
                // drift into coordinates where the restored 56dp button would clip.
                width = normalWidth,
                height = normalHeight,
            )
        } else if (button) {
            loadButtonPosition(context, bounds, normalWidth, normalHeight)
        } else {
            loadMenuPosition(context, bounds, normalWidth, normalHeight)
        }
        return WindowManager.LayoutParams(windowWidth, windowHeight, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xy.first
            y = xy.second
            alpha = 1f
            if (!button &&
                companionMenuRequestsCrossWindowBlur(displayRole) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                this.flags = this.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = dp(context, companionMenuBlurRadiusDp(displayRole))
            }
            title = when {
                displayRole == CompanionOverlayRole.PRIMARY_SINGLE && button -> "Mimir M button"
                displayRole == CompanionOverlayRole.PRIMARY_SINGLE -> "Mimir quick menu"
                button -> "Mimir companion M-button"
                else -> "Mimir companion quick menu"
            }
        }
    }

    /**
     * Where the coin of this role sits: its one remembered spot. The single-display coin's is settled on the nearest edge the first time it is read (OR-2: a spot saved before
     * edge snap existed is mid-screen). The Bottom coin's is wherever the player last dropped it, on every Mimir page and over every other app ; with
     * nothing saved it starts at the right edge, just below the header row ([defaultBottomMButtonPosition]), so it covers no header control.
     */
    private fun loadButtonPosition(
        context: Context,
        bounds: Pair<Int, Int>,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val inset = dp(context, MButtonSafeInsetDp)
        val default = if (displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {
            defaultBottomMButtonPosition(
                bounds = bounds,
                width = width,
                height = height,
                safeInset = inset,
                top = dp(context, MButtonBottomDefaultTopDp),
            )
        } else {
            defaultMButtonPosition(bounds = bounds, width = width, height = height, safeInset = inset)
        }
        val loaded = loadPosition("button", bounds, width, height, default.first, default.second)
        // OR-2: a spot saved before edge snap existed (mid-screen) settles on the nearest edge the first time it is read.
        return if (mButtonEdgeSnapApplies(displayRole)) {
            snapMButtonToNearestEdge(
                x = loaded.first,
                y = loaded.second,
                bounds = bounds,
                width = width,
                height = height,
                inset = inset,
            ).position
        } else {
            loaded
        }
    }

    private fun loadMenuPosition(
        context: Context,
        bounds: Pair<Int, Int>,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        val buttonSize = dp(context, MButtonSizeDp)
        val button = loadButtonPosition(context, bounds, buttonSize, buttonSize)
        return loadPosition("menu", bounds, width, height, button.first.coerceAtMost(max(0, bounds.first - width)), button.second)
    }

    private fun loadPosition(kind: String, bounds: Pair<Int, Int>, width: Int, height: Int, defaultX: Int, defaultY: Int): Pair<Int, Int> {
        val px = prefs.getFloat(companionOverlayPositionKey(kind, displayRole, "x"), -1f)
        val py = prefs.getFloat(companionOverlayPositionKey(kind, displayRole, "y"), -1f)
        return resolveCompanionOverlayPosition(
            bounds = bounds,
            width = width,
            height = height,
            persistedXRatio = px,
            persistedYRatio = py,
            defaultPosition = defaultX to defaultY,
        )
    }

    private fun saveButtonPosition(lp: WindowManager.LayoutParams?, context: Context) {
        lp ?: return
        val bounds = displayBounds(context)
        savePosition("button", lp, bounds)
        announceButtonPosition(lp.x to lp.y, bounds)
    }

    private fun announceButtonPosition(position: Pair<Int, Int>, bounds: Pair<Int, Int>) {
        _actions.tryEmit(
            CompanionOverlayAction.DragMoved(
                role = displayRole,
                displayId = displayId,
                xRatio = if (bounds.first == 0) 0f else position.first.toFloat() / bounds.first,
                yRatio = if (bounds.second == 0) 0f else position.second.toFloat() / bounds.second,
            ),
        )
    }

    private fun saveMenuPosition(lp: WindowManager.LayoutParams, context: Context) {
        saveMenuPosition(lp.x to lp.y, context)
    }

    private fun saveMenuPosition(position: Pair<Int, Int>, context: Context) {
        savePosition("menu", position, displayBounds(context))
    }

    private fun savePosition(kind: String, lp: WindowManager.LayoutParams, bounds: Pair<Int, Int>) {
        savePosition(kind, lp.x to lp.y, bounds)
    }

    private fun savePosition(kind: String, position: Pair<Int, Int>, bounds: Pair<Int, Int>) {
        if (bounds.first <= 0 || bounds.second <= 0) return
        prefs.edit()
            .putFloat(
                companionOverlayPositionKey(kind, displayRole, "x"),
                position.first.toFloat() / bounds.first,
            )
            .putFloat(
                companionOverlayPositionKey(kind, displayRole, "y"),
                position.second.toFloat() / bounds.second,
            )
            .apply()
    }

    /**
     * OR-2: a drag on the single-display badge ends on the nearest screen edge. The settled spot is the one saved and announced (DragMoved), so neither a restart nor a listener
     * ever sees the raw drop point, and it is saved at the TARGET because the window is still at the drop point while the glide runs. The window then glides there
     * ([MButtonRestGlides], the policy of 2026-10-08) and the bounds are published at the target at once. The Bottom coin never comes here: its drop is kept
     * ([MButtonReleaseOutcome.DRAG_KEEP]).
     */
    private fun settleButtonAtRest(lp: WindowManager.LayoutParams?, context: Context) {
        lp ?: return
        val bounds = displayBounds(context)
        val snap = snapMButtonToNearestEdge(
            x = lp.x,
            y = lp.y,
            bounds = bounds,
            width = lp.width,
            height = lp.height,
            inset = dp(context, MButtonSafeInsetDp),
        )
        debugMButtonReceipt(
            "event=BUTTON_SNAP role=$displayRole display=$displayId edge=${snap.edge} x=${snap.position.first} y=${snap.position.second}",
        )
        savePosition("button", snap.position, bounds)
        announceButtonPosition(snap.position, bounds)
        moveButtonTo(lp, snap.position.first, snap.position.second, glide = MButtonRestGlides)
        publishPrimaryButtonBounds()
    }

    /**
     * Move the single-display coin's window to ([toX], [toY]): a glide on [MButtonRestGlideMs] and the house easing, or in one layout pass when [glide] is false (or animations are off:
     * the system's animator scale at 0 makes the duration nothing). The view stays attached and touchable the whole way; a touch lands it at once
     * ([finishButtonRestGlide]) and an unmount cancels it ([cancelButtonRestGlide]).
     */
    private fun moveButtonTo(lp: WindowManager.LayoutParams, toX: Int, toY: Int, glide: Boolean) {
        cancelButtonRestGlide()
        val fromX = lp.x
        val fromY = lp.y
        if (!glide || (fromX == toX && fromY == toY)) {
            lp.x = toX
            lp.y = toY
            runCatching { windowManager?.updateViewLayout(overlayView, lp) }
            return
        }
        val easing = MButtonRestGlideEasing
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = MButtonRestGlideMs
        animator.interpolator = PathInterpolator(easing[0], easing[1], easing[2], easing[3])
        animator.addUpdateListener { running ->
            val fraction = running.animatedValue as Float
            lp.x = fromX + ((toX - fromX) * fraction).roundToInt()
            lp.y = fromY + ((toY - fromY) * fraction).roundToInt()
            runCatching { windowManager?.updateViewLayout(overlayView, lp) }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (buttonRestGlide === animation) buttonRestGlide = null
            }
        })
        buttonRestGlide = animator
        animator.start()
    }

    private fun cancelButtonRestGlide() {
        buttonRestGlide?.cancel()
        buttonRestGlide = null
    }

    /** A touch arrived mid-glide: jump the coin to where it was gliding to, so a tap or a new drag never leaves it off its rest. */
    private fun finishButtonRestGlide() {
        buttonRestGlide?.end()
        buttonRestGlide = null
    }

    /** OR-2: the resting square of the single-display badge, for [primaryButtonBounds]. Main thread. */
    private fun publishPrimaryButtonBounds() {
        if (displayRole != CompanionOverlayRole.PRIMARY_SINGLE || overlayView == null) {
            _primaryButtonBounds.value = null
            return
        }
        val context = displayContext ?: appContext
        val bounds = displayBounds(context)
        val size = dp(context, MButtonSizeDp)
        _primaryButtonBounds.value = overlayButtonBoundsOf(loadButtonPosition(context, bounds, size, size), size, bounds)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun installNativeOverlayInteractionObserver(observer: NativeOverlayInteractionObserver?) {
        if (nativeOverlayInteractionObserver === observer) return
        finishAllNativeOverlayInteractions()
        nativeOverlayInteractionObserver = observer
    }

    private fun finishOverlayViewInteractions() {
        mButtonInteraction.finish()
        menuInteraction.finish()
    }

    private fun finishAllNativeOverlayInteractions() {
        finishOverlayViewInteractions()
        edgeInteraction.finish()
    }

    private fun displayBounds(context: Context): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }
}

/**
 * A struck coin: a dark disc, a metallic rim, and the rune drawn in the shelf's own console metal.
 *
 *  The
 * first build drew each rune as flat `inkArgb` text on a flat oval — one colour, no ramp, no light
 * direction. Flat is exactly what "stale" names: everything else on this device that reads as struck
 * metal earns it from a gradient with a top light and a bottom shadow, and the marks had none.
 *
 * The metal is NOT authored here. `platformConsoleIconBrush` in core-ui already defines the four stops
 * the shelf's console art wears, at 0.00 / 0.40 / 0.62 / 1.00 — this is the same ramp, over the same
 * vertical span, arriving through [CompanionOverlayTheme]. So a rune coin and a console tile catch the
 * light identically, per theme, and adjusting one adjusts both instead of starting a second gold that
 * drifts from the first.
 *
 * Drawn rather than assembled from `GradientDrawable`: a gradient STROKE is what makes a rim read as a
 * turned edge, and `setStroke` takes a single flat colour.
 *
 * **The player's own mark takes the rune's path.** [mark] is any drawable whose alpha is
 * the mask: the shipping vector, or the bitmap of a 256 px PNG the app processed into a mask
 * (`companionButtonMarkDrawable`). Either is drawn into a layer and the theme's metal is poured through it with
 * `SRC_IN`, so a custom mark recolours with the theme and takes the press boost exactly as the rune does. The disc, the
 * rim, the states and the 56 dp size are not the mark's to change.
 */
internal class CompanionMenuCoinView(
    context: Context,
    private val theme: CompanionOverlayTheme,
    private val rune: String = "",
    /**
     * A vector mark to strike instead of a rune — the ᛗ on the floating button.
     *
     *  The button wore a flat
     * `toolingArgb` tint on the mark and the same flat colour on its 2 dp rim: the identical
     * one-tone-no-ramp defect the coins had, on the one control that is on screen the whole time. A
     * `ColorStateList` tint cannot carry a gradient, so the mark is drawn into a layer and the metal is
     * composited through it with `SRC_IN` — which gilds any drawable without needing a second asset.
     */
    private val mark: android.graphics.drawable.Drawable? = null,
    /** The button's disc is denser than a menu coin's: it floats over arbitrary content, not over glass. */
    private val fillAlpha: Int = 190,
    /**
     * How far the [mark] is inset from the coin's own edge, in dp: the mark's box is the coin less this on each side. The
     * shipping rune keeps [CompanionCoinMarkInsetDp]; the player's own image is given the smaller
     * [CompanionCoinCustomMarkInsetDp], so it is drawn [CompanionCustomMarkScale] times as large ([companionCoinMarkInsetDp]).
     */
    private val markInsetDp: Float = CompanionCoinMarkInsetDp,
    /**
     * Where the player put their own image on the coin ; the default is centred at 100%, the look every mark had before. Read
     * only by a bitmap [mark]: the rune is not the player's to move. Settable, so the Mimir-button picker's stage can follow a nudge or a pinch on
     * the view it already hosts instead of rebuilding it for every half dp.
     */
    placement: MimirMarkPlacement = MimirMarkPlacement.Default,
) : View(context) {
    var placement: MimirMarkPlacement = placement.sanitized()
        set(value) {
            val next = value.sanitized()
            if (field == next) return
            field = next
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val runePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    private val bounds = android.graphics.Rect()
    private val facePath = android.graphics.Path()

    /**
     * True while the menu's focus ring is on this coin ([CompanionMenuFocusRingView]). The coin then draws its ground only and no rim: the ring hugs the disc as its one edge, and
     * a rim left under it reads as two outlines with a gap between them (a focused element hands its rim to the ring). Every other coin keeps its rim.
     */
    var ringed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** The press has to reach the metal, so the coin repaints on its own state change. */
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private fun metalShader(top: Float, bottom: Float, boost: Int): Shader = LinearGradient(
        0f,
        top,
        0f,
        bottom,
        intArrayOf(
            argbWithAlpha(theme.metalLightArgb, (200 + boost).coerceAtMost(255)),
            argbWithAlpha(theme.metalHotArgb, (235 + boost).coerceAtMost(255)),
            argbWithAlpha(theme.metalMidArgb, (235 + boost).coerceAtMost(255)),
            argbWithAlpha(theme.metalDeepArgb, (215 + boost).coerceAtMost(255)),
        ),
        floatArrayOf(0f, 0.40f, 0.62f, 1f),
        Shader.TileMode.CLAMP,
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        fun px(value: Float): Float = value * density
        val pressed = isPressed
        val boost = if (pressed) 20 else 0
        val cx = width / 2f
        val cy = height / 2f
        val rim = px(CompanionCoinRimDp)
        val radius = (minOf(width, height) / 2f) - rim

        // The disc the mark sits on. Darker at the bottom so the rim's own ramp has something to turn
        // against; the accent only enters on press, where it is a state and not decoration.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f,
            cy - radius,
            0f,
            cy + radius,
            argbWithAlpha(if (pressed) theme.accentArgb else theme.pageArgb, if (pressed) 96 else 70),
            argbWithAlpha(theme.coverArgb, fillAlpha),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)

        if (!ringed) {
            paint.shader = metalShader(cy - radius, cy + radius, boost)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rim
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
        }

        if (mark != null) {
            // A view that has not been measured has no mark box to strike into.
            if (width <= 0 || height <= 0) return
            // The mark is struck, not tinted. Drawn into its own layer first, then the metal is poured
            // through it: `SRC_IN` keeps the gradient only where the mark already put ink -- the
            // vector's strokes, or the alpha of the player's own PNG.
            val inset = px(markInsetDp)
            // The player's own image (a bitmap) is placed by the one shared geometry (mimirMarkDrawRect, in :core-model): aspect-fitted
            // into the 44 dp box, sized and moved by the player's placement. A trimmed mask is not square, and a bitmap drawable stretches
            // to its bounds, so the bounds ARE the fit. The rune's vector keeps the full box, as it always did.
            val placedImage = mark is BitmapDrawable && mark.intrinsicWidth > 0 && mark.intrinsicHeight > 0
            val box = if (placedImage) mimirMarkBoxRect(width.toFloat(), placement) else null
            val fit = if (placedImage) {
                val placed = mimirMarkDrawRect(width.toFloat(), mark.intrinsicWidth, mark.intrinsicHeight, placement)
                val left = placed.left.roundToInt()
                val top = placed.top.roundToInt()
                intArrayOf(left, top, max(placed.right.roundToInt(), left + 1), max(placed.bottom.roundToInt(), top + 1))
            } else {
                intArrayOf(inset.toInt(), inset.toInt(), (width - inset).toInt(), (height - inset).toInt())
            }
            mark.setBounds(fit[0], fit[1], fit[2], fit[3])
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            if (mark is BitmapDrawable) {
                // the player's image is drawn big (CompanionCustomMarkBoxDp) and clipped to the coin face, the
                // circle inside the rim's inner edge, so no ink ever crosses the rim. A mask that inks its corners gets them rounded.
                facePath.rewind()
                facePath.addCircle(cx, cy, radius - rim / 2f, android.graphics.Path.Direction.CW)
                canvas.clipPath(facePath)
            }
            mark.draw(canvas)
            // The metal's ramp runs the height of the mark's box, which a placed image carries with it (so the top light stays at the image's top).
            paint.shader = metalShader(box?.top ?: inset, box?.bottom ?: (height - inset), boost)
            paint.style = Paint.Style.FILL
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.xfermode = null
            paint.shader = null
            canvas.restoreToCount(layer)
            return
        }

        if (rune.isEmpty()) return
        runePaint.textSize = px(24f)
        runePaint.getTextBounds(rune, 0, rune.length, bounds)
        // Centred on the GLYPH's own ink box, not on the font's line box: the futhark marks in this
        // face carry uneven ascent, and centring on the baseline metrics hung ᛞ and ᛒ visibly low.
        val baseline = cy - bounds.exactCenterY()
        runePaint.shader = metalShader(cy - bounds.height() / 2f, cy + bounds.height() / 2f, boost)
        canvas.drawText(rune, cx, baseline, runePaint)
        runePaint.shader = null
    }
}

/**
 * The floating ᛗ button's coin: the shipping mark, or the player's own ([CompanionOverlayTheme.mark]), struck in the
 * theme's metal. ONE definition, so the live overlay ([AndroidCompanionOverlayController.buildButton]) and the
 * Mimir-button picker's preview ([createMimirButtonPreviewView]) cannot drift apart: what the player judges on the
 * stage is the view the overlay will wear. A missing or unreadable file draws the rune and logs
 * `mark=rune reason=missing` ([companionButtonMarkDrawable]).
 */
internal fun createMimirButtonCoin(context: Context, theme: CompanionOverlayTheme): CompanionMenuCoinView {
    val mark = companionButtonMarkDrawable(context, theme.mark)
    return CompanionMenuCoinView(
        context = context,
        theme = theme,
        mark = mark,
        // The player's own image reads larger than the rune . Decided by what was actually drawn, not by what
        // was asked for: a custom mark whose file is gone draws the rune, and the rune keeps the shipping size.
        markInsetDp = companionCoinMarkInsetDp(customImage = mark is BitmapDrawable),
        // Where the player put the image; the rune (or a missing file, which draws the rune) has none and keeps the default.
        placement = (theme.mark as? CompanionButtonMark.Image)?.placement ?: MimirMarkPlacement.Default,
        // **OPAQUE, and that is the whole difference between this coin and the menu's.** The 190
        // default was authored for a coin sitting on the menu's own glass, which is already dense;
        // this one sits on whatever the game is drawing. Left at 190 it COMPOUNDED with the root
        // view's `RestingAlpha` to 0.67 of a fill, and 0.60 while dimmed -- the button's disc going
        // half-transparent over arbitrary content, which made the M appear pale. The dim state wins outright and is never
        // multiplied with the resting look; this applies the same rule to the fill. The root view's
        // alpha stays the ONE translucency channel, so dim and press still behave exactly as tuned.
        fillAlpha = 255,
    )
}

/**
 * The REAL Mimir button, for a preview surface: the Mimir-button picker's stage hosts this
 * in a non-focusable `AndroidView`, at 56 dp, again at 2x, and again on a light panel.
 *
 * Built from [theme] alone, so it never touches the overlay: nothing here calls [CompanionOverlayController.setOverlayTheme],
 * because the controller repaints the live button by re-adding its window, and a push per previewed tile would do that on
 * every focus move. Only a committed mark reaches the live button, through the app's one theme push.
 *
 * The view takes no focus and no touch and is hidden from accessibility (the picker's tiles already announce their name
 * and state; the spoken name "Mimir M button" belongs to the live button alone). It has no intrinsic size: the host gives
 * it the button's 56 dp.
 */
fun createMimirButtonPreviewView(context: Context, theme: CompanionOverlayTheme): View =
    createMimirButtonCoin(context, theme).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

/**
 * Moves and sizes the player's image on a button [createMimirButtonPreviewView] built, in place : the picker's adjust stage calls
 * this for every nudge, pinch and drag on the view it already hosts, so no half dp rebuilds the button or cross-fades a copy of it. A view that is
 * not that coin is left alone. Like the preview itself it never touches the overlay: only a saved placement reaches the live button, through the
 * app's one theme push.
 */
fun setMimirButtonPreviewPlacement(view: View, placement: MimirMarkPlacement) {
    (view as? CompanionMenuCoinView)?.placement = placement
}

/**
 * Hands the preview coin's rim to a focus ring drawn around it, or takes it back: a [ringed] coin draws its disc and no rim, so the ring is its one edge (a
 * rim left under a ring reads as two outlines with a gap). The picker's adjust stage rings the large coin the pad moves; the 56 dp one beside it is not focused and keeps its rim.
 */
fun setMimirButtonPreviewRinged(view: View, ringed: Boolean) {
    (view as? CompanionMenuCoinView)?.ringed = ringed
}

private class CompanionMenuGlassView(
    context: Context,
    private val theme: CompanionOverlayTheme,
    private val role: CompanionOverlayRole,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        fun px(dp: Float): Float = dp * density

        val right = width.toFloat()
        val bottom = height.toFloat()
        val cover = RectF(px(0.5f), px(0.5f), right - px(0.5f), bottom - px(0.5f))

        // ONE MATERIAL, BOTH ROLES —
        //
        // What used to be here on the companion was a drawn book: an opaque cover, a page-edge and a page
        // inside it, an 18 dp spine rail, a rune stamped on the rail and a decorative accent tick. Five
        // ornaments around five rows. The primary menu already used one uninterrupted panel;
        // the companion uses the same material for visual consistency.
        // The panel is the same product surface as Home's Compose sheets, but it lives in a system-overlay
        // Android View and cannot reuse the Compose modifier, so the material is reproduced rather than
        // shared: a translucent themed vertical wash, a restrained glass rim, and a top sheen.
        paint.style = Paint.Style.FILL
        // A shader's colours are multiplied by the paint's own alpha, and this paint is shared by the strokes below, which leave it at the sheen's 88: without this a SECOND draw of this view
        // (a redraw, a capture) filled the card at about a third of its opacity, and the first draw alone had the wash its alphas name.
        paint.alpha = 255
        paint.shader = LinearGradient(
            0f,
            cover.top,
            0f,
            cover.bottom,
            argbWithAlpha(theme.pageArgb, companionMenuGlassTopAlpha(role)),
            argbWithAlpha(theme.coverArgb, companionMenuGlassBottomAlpha(role)),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            cover,
            px(CompanionMenuOuterCornerDp.toFloat()),
            px(CompanionMenuOuterCornerDp.toFloat()),
            paint,
        )
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = px(1f)
        paint.color = argbWithAlpha(theme.pageEdgeArgb, 190)
        canvas.drawRoundRect(
            cover,
            px(CompanionMenuOuterCornerDp.toFloat()),
            px(CompanionMenuOuterCornerDp.toFloat()),
            paint,
        )
        paint.color = argbWithAlpha(theme.toolingArgb, 88)
        canvas.drawLine(px(12f), px(5f), right - px(12f), px(5f), paint)
    }
}

/**
 * Every quick menu is the same translucent command surface. There is no second material.
 *
 * This used to be primary-only and is kept as a function rather than folded away because it answers a
 * question the next role will also have to answer, and because [companionMenuRequestsCrossWindowBlur]
 * reads as its sibling — which is the distinction that actually matters and used to be conflated here.
 */
internal fun companionMenuUsesLiquidGlass(@Suppress("UNUSED_PARAMETER") role: CompanionOverlayRole): Boolean = true

/**
 * WHO BLURS WHAT IS BEHIND THE MENU — and the answer is not the same for the two roles.
 *
 * The material and this flag were one predicate until 2026-08-31, so turning the glass on for the
 * companion would have turned `FLAG_BLUR_BEHIND` on with it. They are separate questions:
 *
 *  - the COMPANION owns what is behind its menu. That is Mimir's own `Presentation`, and it blurs its
 *    own scene from [CompanionOverlayController.quickMenuVisualOpen] — in-app, on a pipeline already proven
 *    on this hardware, and visible in a photograph.
 *  - the PRIMARY does not. Behind its menu is somebody else's app, which only the compositor can touch,
 *    so it asks — knowing the request may be refused.
 *
 * Cross-window blur has never been observed to execute on this dual-screen hardware, so the companion
 * asking for it would be shipping an unmeasurable effect on top of a measured one. Where the request is
 * refused or not made, the glass alphas are the answer: dense enough to read against anything (232 / 220
 * on the primary's menu; the Bottom's card is opaque, see [companionMenuGlassTopAlpha]).
 */
internal fun companionMenuRequestsCrossWindowBlur(role: CompanionOverlayRole): Boolean =
    role == CompanionOverlayRole.PRIMARY_SINGLE

internal fun companionMenuBlurRadiusDp(role: CompanionOverlayRole): Int =
    if (companionMenuRequestsCrossWindowBlur(role)) 24 else 0

/**
 * The ᛗ music panel's window blur (Q14): the Main role's menu strength, because the panel is anchored to the same badge and is the
 * same family. The Bottom role has no such panel and asks for none.
 */
internal fun companionPanelBlurRadiusDp(role: CompanionOverlayRole): Int = companionMenuBlurRadiusDp(role)

/**
 * A panel window's flags once its input is released: the same flags with the window neither focusable nor touchable, every other
 * flag kept (the blur flag, layout-in-screen, not-touch-modal). Idempotent.
 */
internal fun companionPanelInputReleasedFlags(flags: Int): Int =
    flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

/** The blur radius in pixels [fraction] of the way through the panel's clock: whole pixels, clamped, 0 at 0. */
internal fun companionPanelBlurRadius(fraction: Float, maxPx: Int): Int = companionMenuHandoffBlurRadius(maxPx, fraction)

/**
 * The card's wash at its top and at its foot. The primary's menu keeps Compose glass's denser no-blur fallback (232 / 220) on devices that ignore cross-window blur.
 *
 * The Bottom's card is OPAQUE, top and foot alike (reported on Thor: on the Super Nintendo shelf the label "Super Metroid" showed faintly through the card's header, and the shelf behind it was
 * not blurred): no content is readable through any part of the card. A glass this dense is not dense enough where bright text sits right behind it, which is the header's case and no other part of
 * the card's. The page-to-cover gradient, the rim and the top sheen are unchanged; only what lies behind the card stops showing, as the Quick Launch picker's ground stopped showing it.
 */
internal fun companionMenuGlassTopAlpha(role: CompanionOverlayRole): Int =
    if (role == CompanionOverlayRole.SECONDARY_COMPANION) 255 else 232

internal fun companionMenuGlassBottomAlpha(role: CompanionOverlayRole): Int =
    if (role == CompanionOverlayRole.SECONDARY_COMPANION) 255 else 220

internal fun argbWithAlpha(argb: Int, alpha: Int): Int =
    (argb and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

internal fun dp(context: Context, value: Int): Int =
    dpToPx(value, context.resources.displayMetrics.density)

internal fun dpToPx(value: Int, density: Float): Int = (value * density).roundToInt()

/** Position identity follows the product role, not a vendor display id that may change after reconnect. */
internal fun companionOverlayPositionKey(
    kind: String,
    role: CompanionOverlayRole,
    axis: String,
): String {
    require(kind == "button" || kind == "menu") { "Unknown overlay position kind" }
    require(axis == "x" || axis == "y") { "Unknown overlay position axis" }
    return "${kind}_${role.name.lowercase()}_$axis"
}

internal fun defaultMButtonPosition(
    bounds: Pair<Int, Int>,
    width: Int,
    height: Int,
    safeInset: Int,
): Pair<Int, Int> = clampCompanionOverlayPosition(
    x = bounds.first - width - safeInset,
    y = safeInset,
    bounds = bounds,
    width = width,
    height = height,
)

internal fun resolveCompanionOverlayPosition(
    bounds: Pair<Int, Int>,
    width: Int,
    height: Int,
    persistedXRatio: Float,
    persistedYRatio: Float,
    defaultPosition: Pair<Int, Int>,
): Pair<Int, Int> = clampCompanionOverlayPosition(
    x = if (persistedXRatio >= 0f) {
        (persistedXRatio * bounds.first).roundToInt()
    } else {
        defaultPosition.first
    },
    y = if (persistedYRatio >= 0f) {
        (persistedYRatio * bounds.second).roundToInt()
    } else {
        defaultPosition.second
    },
    bounds = bounds,
    width = width,
    height = height,
)

internal fun clampCompanionOverlayPosition(
    x: Int,
    y: Int,
    bounds: Pair<Int, Int>,
    width: Int,
    height: Int,
): Pair<Int, Int> =
    x.coerceIn(0, max(0, bounds.first - width)) to
        y.coerceIn(0, max(0, bounds.second - height))

/**
 * Where the Bottom's open ᛗ menu card may stand: [clampCompanionOverlayPosition]'s bounds, held [inset] clear of every edge of the screen. The card opens at the coin's spot, and the coin
 * rests on the right edge, so the card used to open flush with the glass (sweep 28 F3). The inset is the overlay family's own edge margin ([MButtonSafeInsetDp], what a fresh coin keeps).
 * Only the CARD is clamped this way: where the coin itself rests is wherever it was dropped, and its own clamp is unchanged. A card too big to keep the margin on an axis is centred on it.
 */
internal fun clampCompanionMenuCardPosition(
    x: Int,
    y: Int,
    bounds: Pair<Int, Int>,
    width: Int,
    height: Int,
    inset: Int,
): Pair<Int, Int> = clampWithInset(x, bounds.first, width, inset) to clampWithInset(y, bounds.second, height, inset)

private fun clampWithInset(value: Int, extent: Int, size: Int, inset: Int): Int {
    val low = inset.coerceAtLeast(0)
    val high = extent - size - low
    return if (high >= low) value.coerceIn(low, high) else ((extent - size) / 2).coerceAtLeast(0)
}

/**
 * Whether a live rebuild of the OPEN secondary menu (Return-availability flip, theme change) may run now.
 * A collapse in flight is a "no": `menuOpen` is still true for the outgoing animation, and rebuilding the
 * menu as open inside that window is how the coins came back after an accepted swap.
 */
internal fun secondaryMenuLiveRebuildAllowed(
    menuOpen: Boolean,
    secondaryRole: Boolean,
    collapseInFlight: Boolean,
): Boolean = menuOpen && secondaryRole && !collapseInFlight

/**
 * Where the open secondary menu's card is drawn: its committed margins plus the drag translation not yet folded
 * into them. A drag moves the card by translation and commits the margins on UP or CANCEL (perf 2026-09-11
 * §4.4a), so anything that reads the card's position mid-gesture reads this, or a rebuild would snap it back.
 */
internal fun secondaryMenuCardEffectivePosition(
    leftMargin: Int,
    topMargin: Int,
    translationX: Float,
    translationY: Float,
): Pair<Int, Int> = (leftMargin + translationX.roundToInt()) to (topMargin + translationY.roundToInt())

/**
 * The window a panel is lent in: the whole display, an application overlay. Public so the size it gives can be asserted from the
 * params themselves.
 *
 * The Bottom's panels (Quick Launch, Tabs, Utility and the Tweaker) are lent with `fitInsetsTypes = 0`. Left at the default the
 * window fits the system bars' insets, and the Bottom's window came out 445 dp tall on a 468.3 dp display (reported 2026-10-09),
 * clipping the plate's foot. With none to fit, MATCH_PARENT is the whole display. Main's focusable panel keeps the default.
 */
fun companionPanelWindowParams(
    focusable: Boolean,
    title: String,
    role: CompanionOverlayRole,
): WindowManager.LayoutParams {
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
    val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    return WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        type,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.title = title
        if (role == CompanionOverlayRole.SECONDARY_COMPANION) fitInsetsTypes = 0
    }
}
