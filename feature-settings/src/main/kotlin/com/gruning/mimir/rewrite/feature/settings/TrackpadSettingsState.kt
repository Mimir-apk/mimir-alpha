package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.TrackpadPointerStyle

/**
 * D34b: what Settings > Controls > Trackpad shows. Null means this device cannot host the keyboard trackpad (no
 * API 32+, no second screen), and the section is not drawn at all.
 *
 * @param serviceEnabled whether Mimir's Accessibility service is switched on in Android's settings.
 * @param naturalScroll two-finger scroll moves the content with the fingers (the default); off reverses it.
 * @param speed the pointer speed step, 1 to 5 (3 is the speed the pad had before the dial); the pad's own dial is the same value.
 * @param pointer the pointer's look: size, shape and color. Small, slim arrow and white until the player chooses.
 */
data class TrackpadSettingsState(
    val serviceEnabled: Boolean,
    val naturalScroll: Boolean,
    val speed: Int = 3,
    val pointer: TrackpadPointerStyle = TrackpadPointerStyle.Default,
)

const val TrackpadAccessRowId = "controls/trackpad/access"
const val TrackpadScrollRowId = "controls/trackpad/scroll"
const val TrackpadSpeedRowId = "controls/trackpad/speed"
const val TrackpadPointerSizeRowId = "controls/trackpad/pointer-size"
const val TrackpadPointerShapeRowId = "controls/trackpad/pointer-shape"
const val TrackpadPointerColorRowId = "controls/trackpad/pointer-color"

const val TrackpadAccessOnSupporting =
    "Mimir's Accessibility service is on. The keyboard's Trackpad key places taps and swipes on your main screen."
const val TrackpadAccessOffSupporting =
    "Turn on Mimir's Accessibility service so the keyboard's Trackpad key can tap and swipe on your main screen. " +
        "It does not read your screen, your typing or your passwords."
const val TrackpadSpeedSupporting =
    "How far the pointer travels for a swipe on the pad, in five steps. The pad's rune dial in its header sets the same value."
const val TrackpadScrollSupporting =
    "On: two fingers move the content with them. Off: the opposite way."
const val TrackpadPointerSizeSupporting = "How big the pointer is on your main screen."
const val TrackpadPointerShapeSupporting = "A slim arrow taps at its tip. A dot taps at its center."
const val TrackpadPointerColorSupporting =
    "White, the theme's metal, or your accent color. The pointer always keeps a dark edge, so it shows on any background."
