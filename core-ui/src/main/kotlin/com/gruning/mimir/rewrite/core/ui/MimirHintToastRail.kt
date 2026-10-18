package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The four release-approved message intentions. Visual and accessibility treatment derive from this type. */
enum class MimirMessageClass {
    Guidance,
    Confirmation,
    Warning,
    Blocking,
}

/**
 * One Mimir-owned message emission. [eventId] advances for a new event; semantic duplicates still coalesce
 * inside the display window. [supportingText] is the optional second line and is never ellipsized.
 * [holdMs] is how long this message stays up (null: the rail's ToastWindowMs); a producer whose message is read, not glanced at, asks for longer .
 */
@Immutable
data class MimirMessage(
    val eventId: Long,
    val text: String,
    val messageClass: MimirMessageClass = MimirMessageClass.Confirmation,
    val supportingText: String? = null,
    val holdMs: Long? = null,
) {
    init {
        require(text.isNotBlank()) { "Message text must not be blank" }
        require(holdMs == null || holdMs > 0L) { "Message hold must be null or positive" }
        require(supportingText == null || supportingText.isNotBlank()) {
            "Message supporting text must be null or non-blank"
        }
    }
}

/** Source-compatible bridge while callers move from the old visual name to the typed message model. */
typealias MimirToastMessage = MimirMessage

@Immutable
internal data class MimirMessageFingerprint(
    val messageClass: MimirMessageClass,
    val text: String,
    val supportingText: String?,
)

internal val MimirMessage.fingerprint: MimirMessageFingerprint
    get() = MimirMessageFingerprint(messageClass, text, supportingText)

internal val MimirMessage.runicGlyph: String
    get() = when (messageClass) {
        MimirMessageClass.Guidance -> "ᚨ" // Ansuz: direction / counsel.
        MimirMessageClass.Confirmation -> "ᛃ" // Jera: completion.
        MimirMessageClass.Warning -> "ᚦ" // Thurisaz: caution.
        MimirMessageClass.Blocking -> "ᛉ" // Algiz: protection / stop.
    }

internal val MimirMessage.accessibilityText: String
    get() = buildString {
        append(
            when (messageClass) {
                MimirMessageClass.Guidance -> "Guidance"
                MimirMessageClass.Confirmation -> "Confirmation"
                MimirMessageClass.Warning -> "Warning"
                MimirMessageClass.Blocking -> "Action blocked"
            },
        )
        append(". ")
        append(text)
        supportingText?.let {
            append(". ")
            append(it)
        }
    }

internal val MimirMessage.liveRegionMode: LiveRegionMode
    get() = when (messageClass) {
        MimirMessageClass.Guidance,
        MimirMessageClass.Confirmation -> LiveRegionMode.Polite
        MimirMessageClass.Warning,
        MimirMessageClass.Blocking -> LiveRegionMode.Assertive
    }

/** Guidance and confirmations are silent; warning feedback is emitted only by a real failure producer. */
val MimirMessageClass.requiresFailureFeedback: Boolean
    get() = this == MimirMessageClass.Warning || this == MimirMessageClass.Blocking

object MimirHintToastRailDefaults {
    const val ToastWindowMs: Long = 2_400L
    val CompactHeight: Dp = 32.dp
    val ExpandedHeight: Dp = 36.dp

    /**
     * The room kept between a rail card's rounded end and its rune (and its copy's far end): the pill's end caps are as round as the band is
     * tall, and with only the card's 2 dp of padding the rune sat on the curve and was cut by it .
     */
    val RuneInset: Dp = 6.dp
}

@Immutable
internal data class MimirHintToastRailColors(
    val background: Color,
    val content: Color,
    val outline: Color,
)

/** WCAG contrast ratio for opaque palette tokens. */
internal fun mimirContrastRatio(first: Color, second: Color): Double {
    val lighter = maxOf(first.luminance(), second.luminance()).toDouble()
    val darker = minOf(first.luminance(), second.luminance()).toDouble()
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Chooses only authored palette colors. The opaque tile fill shields text from bright artwork; content
 * maximizes text contrast, and the outline maximizes its worst contrast against every authored scene edge.
 */
internal fun mimirHintToastRailColors(palette: MimirThemePalette): MimirHintToastRailColors {
    // Light themes keep their transient chrome in the same fresh ivory/sky field as the surface beneath it.
    // A dark hero-cover toast made light themes look like the Default palette had leaked into them.
    if (palette.usesBrightField) {
        return MimirHintToastRailColors(
            background = palette.surfaceRaised,
            content = palette.textPrimary,
            outline = palette.focusRing,
        )
    }

    val background = palette.tileFill
    val content = listOf(
        palette.textPrimary,
        palette.surfaceBase,
        palette.surfaceRaised,
        palette.surfaceHigh,
        palette.accentBright,
        palette.onAccent,
    ).maxBy { candidate -> mimirContrastRatio(candidate, background) }

    val sceneEdges = listOf(
        palette.surfaceBase,
        palette.surfaceRaised,
        palette.surfaceHigh,
        palette.signatureTop,
        palette.signatureBottom,
    )
    val outline = listOf(
        palette.focusRing,
        palette.accent,
        palette.accentBright,
        palette.textPrimary,
        palette.tileFill,
        palette.surfaceBase,
        palette.onAccent,
    ).maxBy { candidate ->
        sceneEdges.minOf { sceneColor -> mimirContrastRatio(candidate, sceneColor) }
    }

    return MimirHintToastRailColors(
        background = background,
        content = content,
        outline = outline,
    )
}

/**
 * The shared glass panel normally uses the dark authored hero-cover material. For a light-theme message,
 * replace only that panel material with the authored light surface ramp so hints and toasts remain fresh;
 * all surrounding theme roles and every dark theme stay unchanged.
 */
internal fun mimirMessageCardPalette(palette: MimirThemePalette): MimirThemePalette =
    if (!palette.usesBrightField) {
        palette
    } else {
        palette.copy(
            materials = palette.materials.copy(
                hero = palette.materials.hero.copy(
                    coverTop = palette.surfaceBase,
                    coverMiddle = palette.surfaceRaised,
                    coverBottom = palette.surfaceHigh,
                    onCover = palette.textPrimary,
                ),
            ),
        )
    }

internal fun mimirHintToastRailHeight(availableWidth: Dp): Dp =
    if (availableWidth < 480.dp) {
        MimirHintToastRailDefaults.CompactHeight
    } else {
        MimirHintToastRailDefaults.ExpandedHeight
    }

internal data class MimirMessageDecision(
    val message: MimirMessage,
    val accepted: Boolean,
    val expiresAtMs: Long,
)

internal typealias MimirToastDecision = MimirMessageDecision

/** Pure dedupe policy so a duplicate cannot restart or strand the active toast timer. */
internal class MimirMessageDeduplicator(
    private val windowMs: Long = MimirHintToastRailDefaults.ToastWindowMs,
) {
    private var latestMessage: MimirMessage? = null
    private var latestFingerprint: MimirMessageFingerprint? = null
    private var expiresAtMs: Long = Long.MIN_VALUE

    init {
        require(windowMs > 0L) { "Toast window must be positive" }
    }

    fun offer(message: MimirMessage, nowMs: Long): MimirMessageDecision {
        val fingerprint = message.fingerprint
        val duplicate = fingerprint == latestFingerprint && nowMs < expiresAtMs
        if (!duplicate) {
            latestMessage = message
            latestFingerprint = fingerprint
            expiresAtMs = nowMs + (message.holdMs ?: windowMs)
        }
        return MimirMessageDecision(
            message = checkNotNull(latestMessage),
            accepted = !duplicate,
            expiresAtMs = expiresAtMs,
        )
    }

    /** Compatibility for the existing pure policy test; the rail itself is typed end to end. */
    fun offer(message: String, nowMs: Long): MimirMessageDecision =
        offer(
            message = MimirMessage(eventId = nowMs, text = message),
            nowMs = nowMs,
        )

    fun current(nowMs: Long): MimirMessageDecision? {
        val message = latestMessage ?: return null
        return if (nowMs < expiresAtMs) {
            MimirMessageDecision(message = message, accepted = false, expiresAtMs = expiresAtMs)
        } else {
            null
        }
    }

    fun isCurrent(decision: MimirMessageDecision): Boolean =
        latestFingerprint == decision.message.fingerprint && expiresAtMs == decision.expiresAtMs
}

internal typealias MimirToastDeduplicator = MimirMessageDeduplicator

/** The lines a fitting card's copy may take before it is cut: more than any message here needs at font 2.0, so cutting is not a state it reaches. */
private const val FitsContentMaxLines = 3

@Composable
fun MimirRunicMessageCard(
    message: MimirMessage,
    compact: Boolean,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    exposeSemantics: Boolean = true,
    /**
     * The rail's card : the rune is seated [MimirHintToastRailDefaults.RuneInset] inside the card's padding, and the copy may take up to
     * three lines, so a larger font wraps it and the card grows (the rail gives it a floor, not a fixed height) rather than cutting it. False
     * keeps every other caller's card exactly as it was: Storage notices and the cross-app launch card size themselves.
     */
    fitsContent: Boolean = false,
) {
    val cardPalette = remember(palette) { mimirMessageCardPalette(palette) }
    val layoutObserver = LocalMimirTextLayoutObserver.current
    val content = cardPalette.materials.hero.onCover
    val accent = when (message.messageClass) {
        MimirMessageClass.Guidance -> if (palette.usesBrightField) palette.focusRing else palette.runeMark
        // The authored mint is a fill with dark ink, not readable small glyph ink on a pale glass card.
        MimirMessageClass.Confirmation -> if (palette.usesBrightField) palette.textSecondary else palette.success
        MimirMessageClass.Warning -> palette.warning
        MimirMessageClass.Blocking -> palette.danger
    }
    val padding = if (compact) 2.dp else MimirSpacing.md
    val semanticsModifier = if (exposeSemantics) {
        Modifier.semantics {
            contentDescription = message.accessibilityText
            liveRegion = message.liveRegionMode
        }
    } else {
        // Crossfade mounts outgoing and incoming cards together. Its stable parent owns the one announcement;
        // animated descendants must be silent or TalkBack can announce both messages.
        Modifier.clearAndSetSemantics {}
    }
    CompositionLocalProvider(LocalMimirPalette provides cardPalette) {
        MimirLiquidGlassPanel(
            modifier = modifier.then(semanticsModifier),
            contentPadding = padding,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fitsContent) Modifier.padding(horizontal = MimirHintToastRailDefaults.RuneInset) else Modifier)
                    // The authored rune and rendered copy are represented once by the parent description.
                    .clearAndSetSemantics {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.runicGlyph,
                    style = if (compact) MimirTypeScale.Pill else MimirTypeScale.RuneAction,
                    color = accent,
                    maxLines = 1,
                )
                Spacer(Modifier.width(if (compact) MimirSpacing.sm else MimirSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    MimirChromeText(
                        text = message.text,
                        style = if (compact) {
                            if (message.messageClass == MimirMessageClass.Guidance) {
                                MimirTypeScale.ControlHintCompact.copy(lineHeight = 13.sp)
                            } else {
                                MimirTypeScale.MicroStatus.copy(lineHeight = 13.sp)
                            }
                        } else {
                            MimirTypeScale.TitleCapsCompact
                        },
                        color = content,
                        maxLines = if (fitsContent) FitsContentMaxLines else if (message.supportingText == null) 2 else 1,
                        // A fitting card wraps at words up to its lines; past them the last line ends in an ellipsis, so a longer copy is shown to be cut and not dropped silently.
                        overflow = if (fitsContent) TextOverflow.Ellipsis else TextOverflow.Clip,
                    )
                    message.supportingText?.let { supportingText ->
                        Text(
                            text = supportingText,
                            style = if (compact) {
                                MimirTypeScale.Pill.copy(fontSize = 10.sp, lineHeight = 12.sp)
                            } else {
                                MimirTypeScale.Support
                            },
                            color = content.copy(alpha = 0.78f),
                            // One line, never wrapped: a settings path is read whole or not at all, so when it cannot fit the sentence above it wraps,
                            // not this . Heard by a test through the same observer the copy uses.
                            softWrap = false,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            onTextLayout = { layout -> layoutObserver?.invoke(supportingText, layout) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A fixed 32/36 dp message band. Typed messages temporarily replace Guidance, semantic duplicates coalesce
 * for 2.4 s, and the latest distinct message wins within the 180 ms overlay ceiling. Reduced motion snaps.
 * The outer band is always measured, even when empty.
 */
@Composable
fun MimirHintToastRail(
    hint: String?,
    toast: MimirToastMessage? = null,
    modifier: Modifier = Modifier,
    palette: MimirThemePalette = LocalMimirPalette.current,
    reducedMotion: Boolean = mimirReducedMotion(),
    onMessageDelivered: (eventId: Long) -> Unit = {},
    /**
     * True for a host that lets the band grow (Main's one message rail, the earlier update): the band is [mimirHintToastRailHeight] whenever the message fits it, and
     * grows to the card when a larger font wraps the copy to more lines, so nothing is cut; the rune is seated inside the pill's curve. False (the
     * default) keeps the fixed band every other host reserves (Home's hint/toast lane is solved to it): the same card, the same two-line limit.
     */
    growsToFit: Boolean = false,
) {
    val deduplicator = remember { MimirMessageDeduplicator() }
    var activeToast by remember { mutableStateOf<MimirMessage?>(null) }
    val currentOnMessageDelivered by rememberUpdatedState(onMessageDelivered)

    LaunchedEffect(toast?.eventId, toast?.fingerprint) {
        val nowMs = SystemClock.uptimeMillis()
        val decision = if (toast != null) {
            deduplicator.offer(toast, nowMs)
        } else {
            deduplicator.current(nowMs)
        }

        if (decision == null) {
            activeToast = null
            return@LaunchedEffect
        }

        activeToast = decision.message
        // A semantic duplicate can intentionally keep the already-visible card, but this new producer event
        // has still been delivered. Acknowledging the INCOMING identity lets its owner consume exactly that
        // event without risking a newer message that arrived between composition and effect execution.
        toast?.let { currentOnMessageDelivered(it.eventId) }
        val remainingMs = (decision.expiresAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        if (remainingMs > 0L) delay(remainingMs)
        if (deduplicator.isCurrent(decision) && SystemClock.uptimeMillis() >= decision.expiresAtMs) {
            activeToast = null
        }
    }

    val projectedHint = hint
        ?.let(LocalMimirControllerHintLabels.current::rewrite)
        ?.takeIf(String::isNotBlank)
    val guidance = projectedHint?.let { text ->
        MimirMessage(
            eventId = Long.MIN_VALUE,
            text = text,
            messageClass = MimirMessageClass.Guidance,
        )
    }
    val currentMessage = activeToast ?: guidance

    // Retain the outgoing value across the rail's OWN appear/disappear — the same pattern
    // StorageNoticeHost.kt uses (`storageNoticeFadeSpec` / `retainedStorageNoticeForFrame`). Without it the
    // rail's FIRST appearance and LAST disappearance were a one-frame cut even though every message-to-
    // message change in between already crossfades on OverlayMs/Easing via the Crossfade below.
    var retainedMessage by remember { mutableStateOf<MimirMessage?>(null) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = currentMessage != null
    val exitSettled = visibility.isIdle && !visibility.currentState && !visibility.targetState
    val shownMessage = retainedHintToastMessageForFrame(
        current = currentMessage,
        retained = retainedMessage,
        exitSettled = exitSettled,
    )
    SideEffect { retainedMessage = shownMessage }
    DisposableEffect(Unit) {
        onDispose { retainedMessage = null }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val railHeight = mimirHintToastRailHeight(maxWidth)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (growsToFit) Modifier.heightIn(min = railHeight) else Modifier.height(railHeight)),
            contentAlignment = Alignment.Center,
        ) {
            // Keep the band measured at all times, but do not emit a visual shell while it is idle.
            // This avoids a blank outlined pill covering shelf content without destabilizing geometry.
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(mimirHintToastRailFadeSpec(reducedMotion)),
                exit = fadeOut(mimirHintToastRailFadeSpec(reducedMotion)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val activeMessage = shownMessage ?: return@AnimatedVisibility
                // This node survives the outgoing/incoming overlap and is the rail's sole live region.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {
                            contentDescription = activeMessage.accessibilityText
                            liveRegion = activeMessage.liveRegionMode
                        },
                ) {
                    Crossfade(
                        targetState = activeMessage,
                        animationSpec = tween(
                            durationMillis = if (reducedMotion) 0 else MimirMotion.OverlayMs,
                            easing = MimirMotion.Easing,
                        ),
                        label = "mimirMessageRail",
                        modifier = Modifier.fillMaxWidth(),
                    ) { message ->
                        MimirRunicMessageCard(
                            message = message,
                            compact = true,
                            palette = palette,
                            exposeSemantics = false,
                            fitsContent = growsToFit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (growsToFit) Modifier.heightIn(min = railHeight) else Modifier.height(railHeight))
                                .padding(horizontal = MimirSpacing.xs),
                        )
                    }
                }
            }
        }
    }
}

/**
 * House duration on the house easing — never Compose's default (`FastOutSlowInEasing`) — for the rail's
 * own appear/disappear. Mirrors `StorageNoticeHost.storageNoticeFadeSpec`.
 */
internal fun mimirHintToastRailFadeSpec(reducedMotion: Boolean): TweenSpec<Float> =
    tween(durationMillis = if (reducedMotion) 0 else MimirMotion.OverlayMs, easing = MimirMotion.Easing)

/** Retain the rail's outgoing message until AnimatedVisibility reports fully hidden; a new message always wins immediately. */
internal fun retainedHintToastMessageForFrame(
    current: MimirMessage?,
    retained: MimirMessage?,
    exitSettled: Boolean,
): MimirMessage? = when {
    current != null -> current
    exitSettled -> null
    else -> retained
}
