package com.gruning.mimir.rewrite

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.Dp
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassHold

/**
 * Home's launch card glass behind a child modal, as two DRAW-PHASE reads: how much of the blur shows ([level], 0 to 1) and how big it is
 * ([radius]). They are separate on purpose (review 2026-10-14): the size used to ride the level (0.53 of a 34 dp ceiling for the disc
 * menu and Open with), which draws a 34 dp blur at half strength over the sharp card for as long as the modal is open, the card's text
 * legible through the panel. The level always runs 0 to 1, and the radius is the modal's own. [titleEdit] says the radius in force is the rename editor's (the glass
 * keeps the radius it was raised with through its hold and its clear), which holds the card as a snapshot as it always did; the pickers hold it by the Library's rule.
 */
internal class HomeLaunchCardGlass(val level: () -> Float, val radius: () -> Dp, val titleEdit: () -> Boolean = { false })

/** The blur radius a modal asks for: the rename editor's ceiling, or the product's standard modal backdrop for the disc / version menu and Open with. */
internal fun homeLaunchCardGlassRadius(
    titleEditActive: Boolean,
    titleEditRadius: Dp,
    childRadius: Dp,
): Dp = if (titleEditActive) titleEditRadius else childRadius

/**
 * The glass the Home launch card wears behind a child modal (the card's content is blurred by
 * `Modifier.mimirGlassBackdropProgress(radius, level)`, which reads both only inside its draw block).
 *
 *  The card's blur was a Dp read
 * in composition (`animateDpAsState(...).value`) that also decided whether the blur layer existed at all (`if (blurring)`), the
 * anti-pattern `rememberMimirGlassProgress`'s own kdoc forbids: every frame of the ramp recomposed the card, the layer appeared on
 * the first frame past 0 dp and was removed on the frame the radius reached 0, and the clear began the moment the child modal's
 * request ended while the modal was still half-visible (two detailed images part-way at once, the 08-15 "mush").
 *
 * Here the modifier is constant (the layer is never added or removed mid-transition; below ~0.1 px it draws the sharp layer
 * alone and costs nothing), the level is an [Animatable] on the house overlay clock, and its target follows
 * [rememberMimirGlassHold]: the glass stays up through the child modal's own dissolve and clears after it ("dissolve, then
 * clear").
 *
 * **The radius is frozen through the hold and the clear, and changes only when a modal is first requested while the glass is
 * CLEAR.** A modal requested while the glass is still clearing keeps the radius that is on screen and runs the level back up
 * from where it is: switching the radius at partial strength would step the blur (18 to 34 dp at full strength for a rename
 * opened within the ~310 ms after Open with closed).
 */
@Composable
internal fun rememberHomeLaunchCardGlass(
    titleEditActive: Boolean,
    childModalActive: Boolean,
    titleEditRadius: Dp,
    childRadius: Dp,
    cancelKey: Any?,
): HomeLaunchCardGlass {
    val requested = titleEditActive || childModalActive
    val held = rememberMimirGlassHold(requested, cancelKey)
    val level = remember { Animatable(0f) }
    val radius = remember { mutableStateOf(homeLaunchCardGlassRadius(titleEditActive, titleEditRadius, childRadius)) }
    // Declared before the level's own effect: both start in one composition and this one must have written the radius
    // before the level's frame beat has passed.
    LaunchedEffect(requested) {
        if (requested && level.value <= 0.001f) {
            radius.value = homeLaunchCardGlassRadius(titleEditActive, titleEditRadius, childRadius)
        }
    }
    val target = if (held) 1f else 0f
    LaunchedEffect(target) {
        // The frame beat before any pixel moves (rememberMimirGlassProgress's UI-01 reason).
        withFrameNanos { }
        level.animateTo(target, tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing))
    }
    return remember(level, radius) {
        HomeLaunchCardGlass(level = { level.value }, radius = { radius.value }, titleEdit = { radius.value == titleEditRadius })
    }
}

/**
 * The EMULATOR fact while this game's platform has no emulator verdict yet (RC-31, the second source of the loading-label defect): a [label] the card
 * could not name, with [resolving] true, is [HomeLaunchCardEmulatorLoadingLabel] ("Checking…") and never an absent value the
 * card turns into "Automatic". Once the verdict is in ([resolving] false) an absent label is the truth and is left as it is.
 */
internal fun homeLaunchCardEmulatorLabelWhileResolving(label: String?, resolving: Boolean): String? =
    label ?: HomeLaunchCardEmulatorLoadingLabel.takeIf { resolving }

/**
 * Whether [platformId]'s emulator verdict is still to come: it is not among the [resolvedPlatforms] AND the verdicts are not all in
 * ([statusesLoaded], L3's flag). Per platform, so a rescan that adds a platform (which makes the global flag false until the new list
 * resolves) does not send every open card, whose platform already has its verdict, back to the loading copy; and a platform the list
 * never carries is not "loading" for ever once the rest are in.
 */
internal fun homeLaunchCardEmulatorResolving(
    platformId: PlatformId,
    resolvedPlatforms: Set<PlatformId>,
    statusesLoaded: Boolean,
): Boolean = platformId !in resolvedPlatforms && !statusesLoaded

/**
 * The face the Home launch card last wore for each item this host has shown a card for, newest [capacity] kept (review 2026-10-15, #7). The
 * face resolves off the composing thread, so a card's state starts empty; a card reopened inside its own close, or one that snaps in under
 * reduced motion (no 130 ms lead to hide the resolve), would paint without it for a frame and pop it in. It seeds from here instead, and the
 * resolve still runs and replaces it. A resolve that finds no face removes the entry, so a stale face is never kept past the next answer.
 */
internal class HomeLaunchCardFaceMemory(private val capacity: Int = 8) {
    private val entries = object : LinkedHashMap<Any, MimirWiredFace>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, MimirWiredFace>?): Boolean = size > capacity
    }

    operator fun get(itemId: Any): MimirWiredFace? = entries[itemId]

    fun record(itemId: Any, face: MimirWiredFace?) {
        if (face == null) entries.remove(itemId) else entries[itemId] = face
    }
}

/**
 * Whether the launch card is held at [HomeLaunchCardUnderSheetAlpha] under a glass sheet, "dissolve, then clear" (RC-25 / H-04g): true while a sheet ([homeLaunchCardUnderSheet]) is the top overlay, and for [MimirMotion.OverlayDissolveCoverMs] after it
 * stops being, on the animation clock, so the card's rise from the held alpha begins once the sheet has dissolved and not on the commit.
 * Without the hold the card rose 0.3 to 1 on the same 180 ms the sheet faded on, and card art and sheet were both half-visible
 * (the 08-15 midpoint "mush"). Released at once on a changed [cancelKey], the launch card's own cancellation of a dissolve.
 */
@Composable
internal fun rememberHomeLaunchCardSheetHold(active: MimirOverlayKind?, cancelKey: Any?, reducedMotion: Boolean = false): Boolean =
    rememberMimirGlassHold(
        requested = homeLaunchCardUnderSheet(active),
        cancelKey = cancelKey,
        holdMs = if (reducedMotion) 0 else MimirMotion.OverlayDissolveCoverMs,
    )
