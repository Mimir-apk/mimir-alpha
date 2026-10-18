package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirButtonMarkStyle
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim

/**
 * The wire form of the two appearance-image preferences (concept A), kept pure so the codec is
 * pinned on the JVM:
 *
 *  - `appearanceGround.<THEME> = sha|DIM|BLUR` -- one per theme (R1), absent for Atmosphere;
 *  - `appearanceButtonMark = sha|STRUCK` -- absent for the rune.
 *
 * Choosing the default DELETES the key (the reset convention `AppearanceOverrideStore.setRing` follows), so no
 * migration is needed. An unknown step name (renamed in a later build) falls back to that field's default, never a
 * crash; an id that is not a plain hex hash is refused outright, because the id names a file under `filesDir`.
 */
internal data class AppearanceGroundKey(
    val id: String,
    val dim: MimirSceneGroundDim,
    val blur: MimirSceneGroundBlur,
)

internal data class AppearanceMarkKey(
    val id: String,
    val style: MimirButtonMarkStyle,
)

private const val Separator = '|'
private val HashId = Regex("[0-9a-f]{8,64}")

/** True for the ids the store mints: a lowercase hex content hash, so a tampered preference cannot name a path. */
internal fun isAppearanceImageId(id: String?): Boolean = id != null && HashId.matches(id)

internal fun encodeAppearanceGroundValue(id: String, dim: MimirSceneGroundDim, blur: MimirSceneGroundBlur): String =
    "$id$Separator${dim.name}$Separator${blur.name}"

internal fun decodeAppearanceGroundValue(value: String?): AppearanceGroundKey? {
    val fields = value?.split(Separator) ?: return null
    val id = fields.getOrNull(0)?.trim().takeIf(::isAppearanceImageId) ?: return null
    val dim = fields.getOrNull(1)?.let { name -> MimirSceneGroundDim.entries.firstOrNull { it.name == name } }
        ?: MimirSceneGroundDim.MEDIUM
    val blur = fields.getOrNull(2)?.let { name -> MimirSceneGroundBlur.entries.firstOrNull { it.name == name } }
        ?: MimirSceneGroundBlur.OFF
    return AppearanceGroundKey(id, dim, blur)
}

internal fun encodeAppearanceMarkValue(id: String, style: MimirButtonMarkStyle): String =
    "$id$Separator${style.name}"

internal fun decodeAppearanceMarkValue(value: String?): AppearanceMarkKey? {
    val fields = value?.split(Separator) ?: return null
    val id = fields.getOrNull(0)?.trim().takeIf(::isAppearanceImageId) ?: return null
    val style = fields.getOrNull(1)?.let { name -> MimirButtonMarkStyle.entries.firstOrNull { it.name == name } }
        ?: MimirButtonMarkStyle.STRUCK
    return AppearanceMarkKey(id, style)
}
