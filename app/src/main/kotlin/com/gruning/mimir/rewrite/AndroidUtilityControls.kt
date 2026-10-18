package com.gruning.mimir.rewrite

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirSoundEvent
import com.gruning.mimir.rewrite.core.audio.MimirUiSounds
import com.gruning.mimir.rewrite.core.audio.StreamVolumeWrite
import kotlin.math.roundToInt

private const val UtilityPreferencesName = "mimir_rewrite_utility"
private const val ThemeAwareLightKey = "theme_aware_joystick_light"
private const val ManualLightColorKey = "manual_joystick_light_argb"
private const val ThemeAwareLightRestorePendingKey = "theme_aware_joystick_light_restore_pending"
private const val HapticStrengthKey = "mimir_haptic_strength"
private const val ScreenTimeoutSecondsKey = "screen_timeout_seconds"
private const val IdleJoystickRestorePendingKey = "idle_joystick_restore_pending"
private const val IdleSystemBrightnessRestoreKey = "idle_system_brightness_restore"
private const val IdleSecondaryBrightnessRestoreKey = "idle_secondary_brightness_restore"
private const val IdleJoystickRestorePercentKey = "idle_joystick_restore_percent"
private const val IdleJoystickRestoreEnabledKey = "idle_joystick_restore_enabled"
private const val ThemeJoystickRestorePercentKey = "theme_joystick_restore_percent"
private const val DefaultHapticStrength = 0.4f
private const val NavigationTickDurationMs = 10L
private const val PreviewTickDurationMs = 18L

class AndroidUtilityPreferenceStore(context: Context) : UtilityPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        UtilityPreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun isThemeAwareJoystickLight(): Boolean =
        preferences.getBoolean(ThemeAwareLightKey, false)

    override fun setThemeAwareJoystickLight(enabled: Boolean) {
        preferences.edit().putBoolean(ThemeAwareLightKey, enabled).apply()
    }

    override fun readManualJoystickColor(): String? =
        runCatching { preferences.getString(ManualLightColorKey, null) }.getOrNull()
            ?.trim()
            ?.takeIf { JoystickColorCodec.parse(it) != null }

    override fun writeManualJoystickColor(value: String) {
        if (JoystickColorCodec.parse(value) == null) return
        preferences.edit().putString(ManualLightColorKey, value.trim()).apply()
    }

    override fun isThemeAwareJoystickLightRestorePending(): Boolean =
        preferences.getBoolean(ThemeAwareLightRestorePendingKey, false)

    override fun setThemeAwareJoystickLightRestorePending(pending: Boolean) {
        preferences.edit().putBoolean(ThemeAwareLightRestorePendingKey, pending).apply()
    }

    override fun readThemeAwareJoystickPercentRestore(): String? =
        runCatching { preferences.getString(ThemeJoystickRestorePercentKey, null) }
            .getOrNull()
            ?.takeIf { BrightnessMath.joystickFraction(it) != null }

    override fun beginThemeAwareJoystickPercentRestore(percent: String): Boolean {
        if (BrightnessMath.joystickFraction(percent) == null) return false
        return runCatching {
            preferences.edit().putString(ThemeJoystickRestorePercentKey, percent.trim()).commit()
        }.getOrDefault(false)
    }

    override fun clearThemeAwareJoystickPercentRestore(): Boolean =
        runCatching { preferences.edit().remove(ThemeJoystickRestorePercentKey).commit() }
            .getOrDefault(false)

    override fun beginThemeAwareJoystickLightRestore(manualColor: String) {
        if (JoystickColorCodec.parse(manualColor) == null) return
        preferences.edit()
            .putString(ManualLightColorKey, manualColor.trim())
            .putBoolean(ThemeAwareLightKey, false)
            .putBoolean(ThemeAwareLightRestorePendingKey, true)
            .commit()
    }

    override fun commitThemeAwareJoystickLightEnabled() {
        preferences.edit()
            .putBoolean(ThemeAwareLightKey, true)
            .putBoolean(ThemeAwareLightRestorePendingKey, false)
            .commit()
    }

    override fun readScreenTimeoutSeconds(): Int = BrightnessMath.normalizeScreenTimeoutSeconds(
        preferences.getInt(ScreenTimeoutSecondsKey, DefaultScreenTimeoutSeconds),
    )

    override fun writeScreenTimeoutSeconds(seconds: Int) {
        preferences.edit()
            .putInt(ScreenTimeoutSecondsKey, BrightnessMath.normalizeScreenTimeoutSeconds(seconds))
            .apply()
    }

    override fun readIdleJoystickLightRestore(): IdleJoystickLightRestore? {
        if (!preferences.getBoolean(IdleJoystickRestorePendingKey, false)) return null
        val percent = runCatching { preferences.getString(IdleJoystickRestorePercentKey, null) }
            .getOrNull()
            ?.takeIf { BrightnessMath.joystickFraction(it) != null }
        val enabled = runCatching { preferences.getString(IdleJoystickRestoreEnabledKey, null) }
            .getOrNull()
            ?.takeIf { BrightnessMath.joystickEnabled(it) != null }
        if (percent == null || enabled == null) {
            clearIdleJoystickLightRestore()
            return null
        }
        return IdleJoystickLightRestore(percent, enabled)
    }

    override fun beginIdleJoystickLightRestore(restore: IdleJoystickLightRestore): Boolean {
        if (BrightnessMath.joystickFraction(restore.percent) == null ||
            BrightnessMath.joystickEnabled(restore.enabled) == null
        ) {
            return false
        }
        return preferences.edit()
            .putString(IdleJoystickRestorePercentKey, restore.percent)
            .putString(IdleJoystickRestoreEnabledKey, restore.enabled)
            .putBoolean(IdleJoystickRestorePendingKey, true)
            .commit()
    }

    override fun readIdleSystemBrightnessRestore(): String? =
        runCatching { preferences.getString(IdleSystemBrightnessRestoreKey, null) }
            .getOrNull()
            ?.takeIf { BrightnessMath.systemFraction(it) != null }

    override fun beginIdleSystemBrightnessRestore(value: String): Boolean {
        if (BrightnessMath.systemFraction(value) == null) return false
        return preferences.edit().putString(IdleSystemBrightnessRestoreKey, value).commit()
    }

    override fun clearIdleSystemBrightnessRestore(): Boolean =
        preferences.edit().remove(IdleSystemBrightnessRestoreKey).commit()

    override fun readIdleSecondaryBrightnessRestore(): String? =
        runCatching { preferences.getString(IdleSecondaryBrightnessRestoreKey, null) }
            .getOrNull()
            ?.takeIf(::isPanelFraction)

    override fun beginIdleSecondaryBrightnessRestore(value: String): Boolean {
        if (!isPanelFraction(value)) return false
        return preferences.edit().putString(IdleSecondaryBrightnessRestoreKey, value).commit()
    }

    override fun clearIdleSecondaryBrightnessRestore(): Boolean =
        preferences.edit().remove(IdleSecondaryBrightnessRestoreKey).commit()

    private fun isPanelFraction(value: String): Boolean =
        value.toFloatOrNull()?.let { it.isFinite() && it in 0f..1f } == true

    override fun clearIdleJoystickLightRestore(): Boolean = preferences.edit()
        .remove(IdleJoystickRestorePercentKey)
        .remove(IdleJoystickRestoreEnabledKey)
        .putBoolean(IdleJoystickRestorePendingKey, false)
        .commit()
}

/** App-only vibration adapter shared by the Utility slider and normalized navigation ticks. */
class AndroidMimirHaptics(context: Context) : MimirHapticGateway {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(UtilityPreferencesName, Context.MODE_PRIVATE)
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        }
    }

    override fun isAvailable(): Boolean = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    override fun hasAmplitudeControl(): Boolean =
        isAvailable() && runCatching { vibrator?.hasAmplitudeControl() == true }.getOrDefault(false)

    override fun readFraction(): Float? {
        if (!isAvailable()) return null
        val stored = BrightnessMath.utilityFraction(
            preferences.getFloat(HapticStrengthKey, DefaultHapticStrength),
        )
        return if (hasAmplitudeControl()) stored else if (stored <= 0f) 0f else 1f
    }

    override fun writeFraction(fraction: Float): Boolean {
        if (!isAvailable()) return false
        val stored = if (hasAmplitudeControl()) {
            BrightnessMath.utilityFraction(fraction)
        } else if (fraction <= 0f) {
            0f
        } else {
            1f
        }
        preferences.edit().putFloat(HapticStrengthKey, stored).apply()
        return true
    }

    // RP5 pad verification 2026-07-17: even the 18ms preview at 100% is imperceptible —
    // the RP5 motor never spins up on our short one-shots, though a 400ms drive hits amplitude 1.0.
    // prefer the VENDOR-TUNED predefined effects wherever the device explicitly advertises them
    // (VIBRATION_EFFECT_SUPPORT_YES), and keep the amplitude one-shot as the fallback lane. On the
    // predefined lane the strength slider gates on/off (predefined effects ignore amplitude) — the same
    // collapse readFraction() already applies to devices without amplitude control. If Thor also
    // advertises support its feel changes from the 10ms one-shot to the vendor TICK — pad-verify on
    // reattach and gate per-vendor only after a separate feel review.
    private val predefinedTickSupported: Boolean by lazy {
        supportsPredefined(VibrationEffect.EFFECT_TICK)
    }
    private val predefinedClickSupported: Boolean by lazy {
        supportsPredefined(VibrationEffect.EFFECT_CLICK)
    }

    private fun supportsPredefined(effectId: Int): Boolean = runCatching {
        vibrator?.areAllEffectsSupported(effectId) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
    }.getOrDefault(false)

    override fun preview() {
        if (predefinedClickSupported) {
            vibratePredefined(VibrationEffect.EFFECT_CLICK)
        } else {
            vibrate(PreviewTickDurationMs)
        }
    }

    override fun navigationTick() {
        if (predefinedTickSupported) {
            vibratePredefined(VibrationEffect.EFFECT_TICK)
        } else {
            vibrate(NavigationTickDurationMs)
        }
    }

    private fun vibratePredefined(effectId: Int) {
        val strength = readFraction() ?: return
        if (strength <= 0f) return
        runCatching {
            val effect = VibrationEffect.createPredefined(effectId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA),
                )
            } else {
                vibrator?.vibrate(effect)
            }
        }
    }

    private fun vibrate(durationMs: Long) {
        val strength = readFraction() ?: return
        if (strength <= 0f) return
        val amplitude = if (hasAmplitudeControl()) {
            (strength * 255f).roundToInt().coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        runCatching {
            // Device parity (RP5 verification 2026-07-17): a bare vibrate() is attributed Usage=TOUCH
            // and rides the system's touch-feedback scaling — on the RP5 that scaled every Mimir tick to
            // zero (vibrator_manager: "scale: 0.00" on all requests) while Thor's scaling passes it
            // through. Mimir haptics are an explicit in-app choice (the Utility row), so they carry
            // their own MEDIA-usage attributes, which follow media vibration intensity instead of the
            // per-OEM touch-feedback toggle.
            val effect = VibrationEffect.createOneShot(durationMs, amplitude)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator?.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA),
                )
            } else {
                vibrator?.vibrate(effect)
            }
        }
    }
}

/**
 * BM03: the navigation-cue loudness — a Mimir preference ([AudioPreferences.navVolume]), not a
 * device setting. [preview] plays the nav tick at the just-set level so the slider is audibly
 * self-demonstrating (at 0 the engine suppresses it, correctly demonstrating OFF).
 */
class AndroidNavSound(context: Context) : NavSoundGateway {
    private val appContext = context.applicationContext

    override fun readFraction(): Float = AudioPreferences.navVolume.value

    override fun writeFraction(fraction: Float): Boolean {
        AudioPreferences.setNavVolume(appContext, fraction)
        return true
    }

    override fun preview() = MimirUiSounds.play(MimirSoundEvent.NavFocusMove)
}

/** Mimir's frontend composition level; writes only the app-local persisted preference. */
class AndroidAmbientSound(context: Context) : AmbientSoundGateway {
    private val appContext = context.applicationContext

    override fun readFraction(): Float = AudioPreferences.ambientVolume.value

    override fun writeFraction(fraction: Float): Boolean {
        AudioPreferences.setAmbientVolume(appContext, fraction)
        return true
    }
}

/** Generic Android media volume; intentionally not described as per-app or per-display volume. */
class AndroidMediaVolume(context: Context) : MediaVolumeGateway {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun isAvailable(): Boolean = bounds() != null

    override fun isFixedVolume(): Boolean = runCatching {
        audioManager?.isVolumeFixed != false
    }.getOrDefault(true)

    override fun readFraction(): Float? {
        val manager = audioManager ?: return null
        val (minimum, maximum) = bounds() ?: return null
        val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return null
        return BrightnessMath.utilityFraction((current - minimum).toFloat() / (maximum - minimum).toFloat())
    }

    override fun writeFraction(fraction: Float): Boolean {
        val manager = audioManager ?: return false
        if (isFixedVolume()) return false
        val (minimum, maximum) = bounds() ?: return false
        val level = (
            minimum + BrightnessMath.utilityFraction(fraction) * (maximum - minimum)
        ).roundToInt().coerceIn(minimum, maximum)
        return runCatching {
            // A drag reports every pointer event, most of them on the step the stream is already at: write only when the step changes.
            if (StreamVolumeWrite.shouldWrite(manager.getStreamVolume(AudioManager.STREAM_MUSIC), level)) {
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            }
            readFraction() != null
        }.getOrDefault(false)
    }

    override fun step(direction: Int): Float? {
        val manager = audioManager ?: return null
        if (isFixedVolume() || bounds() == null) return null
        val adjustment = when {
            direction < 0 -> AudioManager.ADJUST_LOWER
            direction > 0 -> AudioManager.ADJUST_RAISE
            else -> return readFraction()
        }
        return runCatching {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0)
            readFraction()
        }.getOrNull()
    }

    private fun bounds(): Pair<Int, Int>? {
        val manager = audioManager ?: return null
        val minimum = runCatching { manager.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return null
        val maximum = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return null
        return if (maximum > minimum) minimum to maximum else null
    }
}
