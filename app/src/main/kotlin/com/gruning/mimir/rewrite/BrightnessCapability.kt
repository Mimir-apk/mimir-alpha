package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.RetroArchCoreListing
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stable slider identities for the dock's Utility surface.
 *
 * The historical Brightness names and focus route stay in place so UT01 does not invalidate Home/Library
 * focus restoration while the user-facing surface grows beyond display brightness.
 */
enum class BrightnessTarget {
    PRIMARY,
    SECONDARY,
    SCREEN_TIMEOUT,
    JOYSTICK_LIGHT,
    JOYSTICK_COLOR,
    MIMIR_HAPTIC,
    NAV_SOUND,
    AMBIENT_SOUND,
    MEDIA_VOLUME,

    /** The equalizer editor's gain sliders: -12..+12 dB in 25 one-dB positions, 0 dB at the centre. A slider shape only; no Utility control reads it. */
    EQ_GAIN,
}

/** How much secondary-display control this device can truthfully provide. */
enum class SecondaryBrightnessSupport {
    /** No secondary Presentation is mounted and no recognized vendor setting exists. */
    UNAVAILABLE,

    /** Mimir can dim its Presentation window, but cannot claim a device-wide panel setting. */
    WINDOW_ONLY,

    /** AYN physical-panel control; adjustable only through a verified, user-authorized privileged route. */
    AYN_SYSTEM,
}

/** Explicit privileged-route states; none of the non-ready states imply privileged control. */
enum class PrivilegedUtilityStatus {
    UNSUPPORTED_DEVICE,
    MANAGER_MISSING,
    SERVICE_STOPPED,
    API_UNSUPPORTED,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    BINDING,
    REMOTE_PERMISSION_MISSING,
    READY,
    CONNECTION_FAILED,

    /**
     * The bridge is healthy (bound, permitted, READY) yet the typed vendor readback keeps failing
     * identically. Retrying cannot succeed from the user's side, so no retry action is offered;
     * recovery requires an app/firmware change or a real device-posture cycle.
     */
    READBACK_UNSATISFIABLE,
}

data class PrivilegedUtilityPosture(
    val status: PrivilegedUtilityStatus,
    val canWriteVendorSettings: Boolean = false,
    val canControlSecondaryBrightness: Boolean = false,
    val authority: PrivilegedUtilityAuthority = PrivilegedUtilityAuthority.NONE,
    /**
     * A READY certified-vendor route can still lack a capability the vendor contract does not own
     * (currently physical secondary-panel control). Operation dispatch never changes writers after a
     * transaction starts.
     */
    val supplementalAccessStatus: PrivilegedUtilityStatus? = null,
) {
    val isReady: Boolean
        get() = status == PrivilegedUtilityStatus.READY &&
            (canWriteVendorSettings || canControlSecondaryBrightness)

    val canRequestAccess: Boolean
        get() = requestedAccessStatus?.let(::isRequestablePrivilegedStatus) == true

    val requestedAccessStatus: PrivilegedUtilityStatus?
        get() = supplementalAccessStatus?.takeIf(::isRequestablePrivilegedStatus)
            ?: status.takeIf(::isRequestablePrivilegedStatus)

    companion object {
        val Unsupported = PrivilegedUtilityPosture(PrivilegedUtilityStatus.UNSUPPORTED_DEVICE)
    }
}

private fun isRequestablePrivilegedStatus(status: PrivilegedUtilityStatus): Boolean =
    status == PrivilegedUtilityStatus.MANAGER_MISSING ||
        status == PrivilegedUtilityStatus.SERVICE_STOPPED ||
        status == PrivilegedUtilityStatus.PERMISSION_REQUIRED ||
        status == PrivilegedUtilityStatus.PERMISSION_DENIED ||
        status == PrivilegedUtilityStatus.CONNECTION_FAILED

data class BrightnessCapabilities(
    val canWriteSystemSettings: Boolean,
    val mainSystemBrightnessActive: Boolean,
    val secondaryWindowAvailable: Boolean,
    val secondarySupport: SecondaryBrightnessSupport,
    val joystickLightSupported: Boolean,
    val joystickLightColorSupported: Boolean,
    val mimirHapticsSupported: Boolean,
    val mimirHapticAmplitudeControl: Boolean,
    val mediaVolumeSupported: Boolean,
    val mediaVolumeFixed: Boolean,
    val mainWriteAccessUseful: Boolean = true,
    val privilegedUtilityPosture: PrivilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
    /** BM03: the navigation-cue loudness row. Trailing default keeps gateway-less hosts/tests as-is. */
    val navSoundSupported: Boolean = false,
    /** App-local frontend music level. It never changes Android's media stream. */
    val ambientSoundSupported: Boolean = false,
) {
    val showsSecondary: Boolean get() = secondarySupport != SecondaryBrightnessSupport.UNAVAILABLE
    val canAdjustSecondary: Boolean
        get() = when (secondarySupport) {
            SecondaryBrightnessSupport.UNAVAILABLE -> false
            SecondaryBrightnessSupport.WINDOW_ONLY -> secondaryWindowAvailable
            SecondaryBrightnessSupport.AYN_SYSTEM ->
                secondaryWindowAvailable &&
                    privilegedUtilityPosture.isReady &&
                    privilegedUtilityPosture.canControlSecondaryBrightness
        }
    val canAdjustJoystickLight: Boolean
        get() = joystickLightSupported &&
            privilegedUtilityPosture.isReady &&
            privilegedUtilityPosture.canWriteVendorSettings
    val canAdjustJoystickColor: Boolean
        get() = joystickLightColorSupported &&
            privilegedUtilityPosture.isReady &&
            privilegedUtilityPosture.canWriteVendorSettings
    val hasVendorControls: Boolean
        get() = secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM ||
            joystickLightSupported ||
            joystickLightColorSupported
    val hasReadOnlyVendorControls: Boolean
        get() = hasVendorControls &&
            ((secondarySupport == SecondaryBrightnessSupport.AYN_SYSTEM && !canAdjustSecondary) ||
                (joystickLightSupported && !canAdjustJoystickLight) ||
                (joystickLightColorSupported && !canAdjustJoystickColor))
    val shouldOfferPrivilegedAccess: Boolean
        get() = hasVendorControls && privilegedUtilityPosture.canRequestAccess
    val shouldOfferMainWriteAccess: Boolean get() = mainWriteAccessUseful && !canWriteSystemSettings
    val canAdjustMediaVolume: Boolean get() = mediaVolumeSupported && !mediaVolumeFixed
}

/** Values a host reapplies to Activity and Presentation windows after either window is recreated. */
data class WindowBrightnessFallbacks(
    val primary: Float?,
    val secondary: Float?,
)

data class BrightnessState(
    val capabilities: BrightnessCapabilities,
    val primaryFraction: Float,
    val primaryWindowFallback: Float?,
    val secondaryFraction: Float?,
    val secondaryWindowFallback: Float?,
    val joystickLightFraction: Float?,
    val joystickColorFraction: Float?,
    val joystickColorArgb: Int?,
    val themeAwareJoystickLight: Boolean,
    val mimirHapticFraction: Float?,
    val mediaVolumeFraction: Float?,
    /** Persisted user intent can survive a vendor-route outage while effective hardware-follow stays false. */
    val themeAwareJoystickLightRequested: Boolean = themeAwareJoystickLight,
    val themeAwareJoystickLightRestorePending: Boolean = false,
    val privilegedFailureMessage: String? = null,
    /** BM03: navigation-cue loudness (AudioPreferences.navVolume surfaced as a Utility fraction). */
    val navSoundFraction: Float? = null,
    /**
     * App-idle timeout. Zero delegates to Android's system timeout; a nonzero value owns
     * keep-screen-on and dims mounted Mimir windows, not Android globally.
     */
    val screenTimeoutSeconds: Int = DefaultScreenTimeoutSeconds,
    /** Persisted frontend-bed level, independent from navigation cues and device media volume. */
    val ambientSoundFraction: Float? = null,
) {
    val windowFallbacks: WindowBrightnessFallbacks
        get() = WindowBrightnessFallbacks(
            primary = primaryWindowFallback?.let(BrightnessMath::windowFraction),
            secondary = secondaryWindowFallback?.let(BrightnessMath::windowFraction),
        )

    fun fractionFor(target: BrightnessTarget): Float? = when (target) {
        BrightnessTarget.PRIMARY -> primaryFraction
        BrightnessTarget.SECONDARY -> secondaryFraction
        BrightnessTarget.SCREEN_TIMEOUT -> BrightnessMath.screenTimeoutFraction(screenTimeoutSeconds)
        BrightnessTarget.JOYSTICK_LIGHT -> joystickLightFraction
        BrightnessTarget.JOYSTICK_COLOR -> joystickColorFraction
        BrightnessTarget.MIMIR_HAPTIC -> mimirHapticFraction
        BrightnessTarget.NAV_SOUND -> navSoundFraction
        BrightnessTarget.AMBIENT_SOUND -> ambientSoundFraction
        BrightnessTarget.MEDIA_VOLUME -> mediaVolumeFraction
        // A slider shape for the equalizer editor: no Utility state carries it.
        BrightnessTarget.EQ_GAIN -> null
    }
}

object VendorUtilityPackages {
    const val AYN_SETTINGS = "com.odin.settings"
    const val RETROID_SETTINGS = "com.rp.settings"
}

data class BrightnessDeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val vendorSettingsPackage: String? = null,
) {
    val isAyn: Boolean
        get() = manufacturer.trim().equals("ayn", ignoreCase = true) ||
            brand.trim().equals("ayn", ignoreCase = true)

    val isRetroid: Boolean
        get() = manufacturer.trim().equals("moorechip", ignoreCase = true) ||
            brand.trim().equals("retroid", ignoreCase = true) ||
            model.trim().startsWith("retroid", ignoreCase = true)

    val hasAynSettingsContract: Boolean
        get() = isAyn && vendorSettingsPackage == VendorUtilityPackages.AYN_SETTINGS

    val hasRetroidSettingsContract: Boolean
        get() = isRetroid && vendorSettingsPackage == VendorUtilityPackages.RETROID_SETTINGS
}

object BrightnessSettingsKeys {
    const val PRIMARY = "screen_brightness"
    const val PRIMARY_MODE = "screen_brightness_mode"
    const val AYN_SECONDARY = "dual_screen_brightness_level"
    const val VENDOR_JOYSTICK_PERCENT = "led_light_brightness_percent"
    const val VENDOR_JOYSTICK_ENABLED = "joystick_light_enabled"
    const val VENDOR_JOYSTICK_COLOR = "joystick_led_light_picker_color"
}

data class BrightnessPermissionRequest(
    val action: String,
    val dataUri: String,
)

/** Small seam around Settings.System so capability and permission behavior stay JVM-testable. */
interface BrightnessSettingsGateway {
    fun canWrite(): Boolean
    fun read(key: String): String?

    /** Implementations must refuse the write when [canWrite] is false, even if a caller forgets. */
    fun write(key: String, value: String): Boolean

    fun permissionRequest(): BrightnessPermissionRequest
}

/**
 * Narrow privileged bridge for UT01. Implementations never accept arbitrary commands, paths, setting keys,
 * or values. Every operation must fail closed and include authoritative readback.
 */
interface PrivilegedUtilityGateway {
    fun posture(): PrivilegedUtilityPosture

    /**
     * May prompt only after the user explicitly activates the dedicated Utility action.
     * Implementations must perform no consent side effect if live state no longer equals [expectedStatus].
     */
    fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean

    /**
     * End the screen-idle session by putting the device to sleep. Returns whether it actually did.
     *
     * Defaulted to false so only an implementation that genuinely holds the privilege claims it.
     * A false answer is not a failure: the caller has already released `FLAG_KEEP_SCREEN_ON` by the
     * time it asks, so the platform's own screen timeout takes over. See
     * `MimirScreenIdleController.armSleepDeadline`.
     */
    fun enforceDeviceSleep(): Boolean = false

    fun readVendorSetting(key: String): String?
    fun writeVendorSetting(key: String, value: String): Boolean
    fun readDisplayBrightness(displayId: Int): Float?

    /** Sets the physical secondary panel and synchronizes its vendor mirror as one verified transaction. */
    fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean

    /** Fixed launch-registry package only; never accepts a command, component, URI, or argument vector. */
    fun forceStopLaunchTarget(packageName: String): PrivilegedLaunchTargetStop =
        PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE

    /**
     * The foreground game's own rendered frame rate x1000, or null when unavailable.
     *
     * Only the fixed supported package identifier crosses this seam. The privileged reader selects its
     * own producer path; callers cannot supply paths or commands. Unsupported producers return null.
     */
    fun readForegroundFrameRateMilli(packageName: String): Int? = null

    /** True only when the selected authority can serve the fixed HUD CPU/FPS route now. */
    fun isHudTelemetryReady(): Boolean = posture().status == PrivilegedUtilityStatus.READY

    /** One fixed CPU snapshot from the privileged utility, or null when the route is unavailable. */
    fun readDeviceUtilization(): DeviceUtilizationSample? = null

    /**
     * Which libretro core files the RetroArch build [packageName] holds for Android user [userId], from one fixed,
     * read-only listing of its `cores` folder. Only the known RetroArch packages are asked about; no path or command
     * crosses this seam. [RetroArchCoreListing.Unknown] by default and for everything the route cannot prove: it is
     * advisory, so not knowing must never cost the player a launch.
     */
    fun listRetroArchCores(userId: Int, packageName: String): RetroArchCoreListing = RetroArchCoreListing.Unknown

    fun close()

    data object None : PrivilegedUtilityGateway {
        override fun posture(): PrivilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported
        override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean = false
        override fun readVendorSetting(key: String): String? = null
        override fun writeVendorSetting(key: String, value: String): Boolean = false
        override fun readDisplayBrightness(displayId: Int): Float? = null
        override fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean = false
        override fun forceStopLaunchTarget(packageName: String): PrivilegedLaunchTargetStop =
            PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE
        override fun readDeviceUtilization(): DeviceUtilizationSample? = null
        override fun close() = Unit
    }
}

enum class PrivilegedLaunchTargetStop {
    REQUEST_ACCEPTED,
    NOT_ALLOWED,
    AUTHORITY_UNAVAILABLE,
    FAILED,
}

interface WindowBrightnessPreferenceStore {
    fun read(target: BrightnessTarget): Float?
    fun write(target: BrightnessTarget, fraction: Float)
    fun clear(target: BrightnessTarget)
}

/** Crash-safe raw vendor values for a temporary idle-only joystick-light dim. */
data class IdleJoystickLightRestore(
    val percent: String,
    val enabled: String,
)

/** App-local state that must not be conflated with Android or vendor global settings. */
interface UtilityPreferenceStore {
    fun isThemeAwareJoystickLight(): Boolean
    fun setThemeAwareJoystickLight(enabled: Boolean)
    fun readManualJoystickColor(): String?
    fun writeManualJoystickColor(value: String)
    fun isThemeAwareJoystickLightRestorePending(): Boolean
    fun setThemeAwareJoystickLightRestorePending(pending: Boolean)
    fun beginThemeAwareJoystickLightRestore(manualColor: String)
    fun commitThemeAwareJoystickLightEnabled()
    fun readScreenTimeoutSeconds(): Int = DefaultScreenTimeoutSeconds
    fun writeScreenTimeoutSeconds(seconds: Int) = Unit
    /**
     * The vendor stick brightness Mimir raised when its theme light was switched on, or null when it
     * did not have to raise one.
     *
     * On the Nova,
     * `led_light_brightness_percent` was 0.0 while the sticks were enabled and coloured, so Mimir's
     * light wrote a colour to a lamp that could not illuminate and reported success. Mimir now raises
     * the vendor level when it takes the light over — and therefore owes it back, which is what this
     * journal is for. Same discipline as [readIdleJoystickLightRestore], which is proven on hardware.
     *
     * Defaulted so existing implementers and fakes are unaffected.
     */
    fun readThemeAwareJoystickPercentRestore(): String? = null
    fun beginThemeAwareJoystickPercentRestore(percent: String): Boolean = false
    fun clearThemeAwareJoystickPercentRestore(): Boolean = true

    fun readIdleJoystickLightRestore(): IdleJoystickLightRestore? = null
    fun beginIdleJoystickLightRestore(restore: IdleJoystickLightRestore): Boolean = false
    fun clearIdleJoystickLightRestore(): Boolean = true

    /**
     * The system PRIMARY brightness Mimir dimmed for app-idle, owed back on wake.
     *
     * Measured on the Thor 2026-09-06: the app-idle dim moved `panel1-backlight` 1562 -> 19 and
     * left `panel0-backlight` at 2450 through the whole cycle. The window override DOES reach the
     * framework (`mScreenBrightnessOverrideFromWindowManager` goes NaN -> 0.01), but on this device the
     * primary panel follows the SYSTEM brightness that Mimir itself writes in
     * `BrightnessController.updatePrimary` -- which then releases the window override entirely
     * (`applyPrimary(null)`). So a window-level dim can never move the main screen here, and "Dim
     * displays after" only ever dimmed the second one.
     *
     * The same Thor at 23:08 the same day, on the build carrying that fix, read the other way: panel1
     * stayed at 1562 through the whole dim while panel0 went 2450 -> 44. Both readings stand with their
     * clocks. The second screen has its own journal below for the same reason.
     *
     * Journalled, not just remembered, for the same reason the joystick light is: a process death while
     * dimmed must not leave the player's main screen at 1%. Defaulted so existing fakes are unaffected.
     */
    fun readIdleSystemBrightnessRestore(): String? = null
    fun beginIdleSystemBrightnessRestore(value: String): Boolean = false
    fun clearIdleSystemBrightnessRestore(): Boolean = true

    /** The SECONDARY panel brightness Mimir dimmed for app-idle, owed back on wake. See [BrightnessController.applyIdleSecondarySystemBrightness]. */
    fun readIdleSecondaryBrightnessRestore(): String? = null
    fun beginIdleSecondaryBrightnessRestore(value: String): Boolean = false
    fun clearIdleSecondaryBrightnessRestore(): Boolean = true

    data object None : UtilityPreferenceStore {
        override fun isThemeAwareJoystickLight(): Boolean = false
        override fun setThemeAwareJoystickLight(enabled: Boolean) = Unit
        override fun readManualJoystickColor(): String? = null
        override fun writeManualJoystickColor(value: String) = Unit
        override fun isThemeAwareJoystickLightRestorePending(): Boolean = false
        override fun setThemeAwareJoystickLightRestorePending(pending: Boolean) = Unit
        override fun beginThemeAwareJoystickLightRestore(manualColor: String) = Unit
        override fun commitThemeAwareJoystickLightEnabled() = Unit
        override fun readScreenTimeoutSeconds(): Int = DefaultScreenTimeoutSeconds
        override fun writeScreenTimeoutSeconds(seconds: Int) = Unit
    }
}

/** Controls only vibration effects emitted by Mimir; it never claims global haptic intensity. */
interface MimirHapticGateway {
    fun isAvailable(): Boolean
    fun hasAmplitudeControl(): Boolean
    fun readFraction(): Float?
    fun writeFraction(fraction: Float): Boolean
    fun preview()
    fun navigationTick()

    data object None : MimirHapticGateway {
        override fun isAvailable(): Boolean = false
        override fun hasAmplitudeControl(): Boolean = false
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float): Boolean = false
        override fun preview() = Unit
        override fun navigationTick() = Unit
    }
}

/** Public Android media-stream control. This is device media volume, not per-app/game volume. */
interface MediaVolumeGateway {
    fun isAvailable(): Boolean
    fun isFixedVolume(): Boolean
    fun readFraction(): Float?
    fun writeFraction(fraction: Float): Boolean
    fun step(direction: Int): Float?

    data object None : MediaVolumeGateway {
        override fun isAvailable(): Boolean = false
        override fun isFixedVolume(): Boolean = true
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float): Boolean = false
        override fun step(direction: Int): Float? = null
    }
}

/**
 * BM03: the navigation-cue loudness (a Mimir preference, not a device setting) surfaced as a
 * Utility slider. [preview] plays a nav tick at the just-set level so adjusting is
 * self-demonstrating — haptic-preview parity for the ears.
 */
interface NavSoundGateway {
    fun readFraction(): Float?
    fun writeFraction(fraction: Float): Boolean
    fun preview()

    data object None : NavSoundGateway {
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float): Boolean = false
        override fun preview() = Unit
    }
}

/** App-local frontend music level surfaced in Utility; Android's media stream remains untouched. */
interface AmbientSoundGateway {
    fun readFraction(): Float?
    fun writeFraction(fraction: Float): Boolean

    data object None : AmbientSoundGateway {
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float): Boolean = false
    }
}

/** The app host supplies real Activity/Presentation windows without coupling this controller to either host. */
interface WindowBrightnessSink {
    fun applyPrimary(fraction: Float?)
    fun applySecondary(fraction: Float?)

    /** App-idle-only override. Production bypasses the normal 5% manual-brightness floor for this path. */
    fun applyIdleDim(includeSecondary: Boolean) {
        applyPrimary(MimirIdleDimFraction)
        if (includeSecondary) applySecondary(MimirIdleDimFraction)
    }

    data object None : WindowBrightnessSink {
        override fun applyPrimary(fraction: Float?) = Unit
        override fun applySecondary(fraction: Float?) = Unit
        override fun applyIdleDim(includeSecondary: Boolean) = Unit
    }
}

data class VendorBrightnessSupport(
    val secondarySetting: Boolean,
    val joystickLight: Boolean,
    val joystickColor: Boolean,
)

object BrightnessVendorDetector {
    fun detect(
        identity: BrightnessDeviceIdentity,
        settings: BrightnessSettingsGateway,
    ): VendorBrightnessSupport {
        val aynContract = identity.hasAynSettingsContract
        val handheldLightContract = aynContract || identity.hasRetroidSettingsContract
        if (!aynContract && !handheldLightContract) {
            return VendorBrightnessSupport(false, false, false)
        }

        val secondary = aynContract &&
            BrightnessMath.vendorPercentFraction(settings.read(BrightnessSettingsKeys.AYN_SECONDARY)) != null
        val joystickPercent = BrightnessMath.joystickFraction(
            settings.read(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT),
        )
        val joystickEnabled = BrightnessMath.joystickEnabled(
            settings.read(BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED),
        )
        val joystickLight = handheldLightContract && joystickPercent != null && joystickEnabled != null
        val joystickColor = joystickLight &&
            JoystickColorCodec.parse(settings.read(BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR)) != null
        return VendorBrightnessSupport(
            secondarySetting = secondary,
            joystickLight = joystickLight,
            joystickColor = joystickColor,
        )
    }
}

/** Pure normalization and stepping rules shared by touch, controller input, persistence, and tests. */
object BrightnessMath {
    const val WINDOW_MIN = 0.05f
    const val STEP = 0.05f
    val ScreenTimeoutSeconds: List<Int> = listOf(0, 15, 30, 60, 120, 300, 600)
    private const val ANDROID_SYSTEM_MAX = 255
    private const val VENDOR_PERCENT_MAX = 100

    fun windowFraction(value: Float): Float = value.coerceIn(WINDOW_MIN, 1f)
    fun utilityFraction(value: Float): Float = value.coerceIn(0f, 1f)

    fun normalizeScreenTimeoutSeconds(seconds: Int): Int =
        ScreenTimeoutSeconds.minBy { kotlin.math.abs(it - seconds.coerceAtLeast(0)) }

    fun screenTimeoutFraction(seconds: Int): Float {
        val index = ScreenTimeoutSeconds.indexOf(normalizeScreenTimeoutSeconds(seconds)).coerceAtLeast(0)
        return index / ScreenTimeoutSeconds.lastIndex.toFloat()
    }

    fun screenTimeoutSeconds(fraction: Float): Int {
        val index = (utilityFraction(fraction) * ScreenTimeoutSeconds.lastIndex)
            .roundToInt()
            .coerceIn(0, ScreenTimeoutSeconds.lastIndex)
        return ScreenTimeoutSeconds[index]
    }

    /** Android's public `screen_brightness` codec (0..255 on the supported handheld firmware). */
    fun systemFraction(raw: String?): Float? {
        val level = raw?.trim()?.toFloatOrNull() ?: return null
        if (!level.isFinite() || level < 0f || level > ANDROID_SYSTEM_MAX.toFloat()) return null
        return windowFraction(level / ANDROID_SYSTEM_MAX.toFloat())
    }

    fun systemLevel(fraction: Float): Int =
        (windowFraction(fraction) * ANDROID_SYSTEM_MAX).roundToInt().coerceIn(1, ANDROID_SYSTEM_MAX)

    /** `0` is Android's manual mode; automatic/adaptive mode remains untouched. */
    fun isManualBrightnessMode(raw: String?): Boolean = raw?.trim() == "0"

    /** Thor's `dual_screen_brightness_level` is a distinct vendor 0..100 contract. */
    fun vendorPercentFraction(raw: String?): Float? {
        val level = raw?.trim()?.toFloatOrNull() ?: return null
        if (!level.isFinite() || level < 0f || level > VENDOR_PERCENT_MAX.toFloat()) return null
        return windowFraction(level / VENDOR_PERCENT_MAX.toFloat())
    }

    fun vendorPercentLevel(fraction: Float): Int =
        (windowFraction(fraction) * VENDOR_PERCENT_MAX).roundToInt().coerceIn(1, VENDOR_PERCENT_MAX)

    /** Vendor handhelds store a 0..1 fraction; accept 0..100 defensively after capability gating. */
    fun joystickFraction(raw: String?): Float? {
        val value = raw?.trim()?.toFloatOrNull() ?: return null
        if (!value.isFinite() || value < 0f || value > 100f) return null
        val normalized = if (value <= 1f) value else value / 100f
        return utilityFraction(normalized)
    }

    fun joystickEnabled(raw: String?): Boolean? {
        val tokens = raw
            ?.split(',')
            ?.map { it.trim() }
            ?.takeIf { it.isNotEmpty() && it.all { token -> token == "0" || token == "1" } }
            ?: return null
        return tokens.any { it == "1" }
    }

    fun joystickEnabledValue(previous: String?, enabled: Boolean): String {
        val channels = previous?.split(',')?.size?.coerceAtLeast(1) ?: 1
        return List(channels) { if (enabled) "1" else "0" }.joinToString(",")
    }

    fun joystickEnabledMatches(raw: String?, enabled: Boolean): Boolean {
        val expected = if (enabled) "1" else "0"
        return raw?.split(',')?.map(String::trim)?.takeIf { it.isNotEmpty() }?.all { it == expected } == true
    }

    fun joystickPercentValue(fraction: Float): String {
        val fixed = String.format(Locale.ROOT, "%.2f", utilityFraction(fraction))
        return fixed.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    fun quantize(target: BrightnessTarget, fraction: Float): Float {
        if (target == BrightnessTarget.EQ_GAIN) return EqGain.fractionFor(EqGain.positionFor(fraction))
        if (target == BrightnessTarget.JOYSTICK_COLOR) {
            return JoystickColorCodec.quantizeFraction(fraction)
        }
        if (target == BrightnessTarget.SCREEN_TIMEOUT) {
            return screenTimeoutFraction(screenTimeoutSeconds(fraction))
        }
        val bounded = when (target) {
            BrightnessTarget.PRIMARY, BrightnessTarget.SECONDARY -> windowFraction(fraction)
            else -> utilityFraction(fraction)
        }
        // Preserve the approved 22% ambient default exactly. Controller steps remain 5% through
        // [step], while touch can retain a one-percent value without the thumb jumping on open.
        val snapStep = if (target == BrightnessTarget.AMBIENT_SOUND) 0.01f else STEP
        val snapped = (bounded / snapStep).roundToInt() * snapStep
        return when (target) {
            BrightnessTarget.PRIMARY, BrightnessTarget.SECONDARY -> windowFraction(snapped)
            else -> utilityFraction(snapped)
        }
    }

    fun step(target: BrightnessTarget, current: Float, direction: Int): Float {
        if (target == BrightnessTarget.SCREEN_TIMEOUT) {
            val currentSeconds = screenTimeoutSeconds(current)
            val index = ScreenTimeoutSeconds.indexOf(currentSeconds).coerceAtLeast(0)
            val next = (index + direction.coerceIn(-1, 1)).coerceIn(0, ScreenTimeoutSeconds.lastIndex)
            return screenTimeoutFraction(ScreenTimeoutSeconds[next])
        }
        if (target == BrightnessTarget.EQ_GAIN) {
            return EqGain.fractionFor((EqGain.positionFor(current) + direction.coerceIn(-1, 1)).coerceIn(0, EqGain.Positions - 1))
        }
        val delta = direction.coerceIn(-1, 1) * STEP
        return quantize(target, current + delta)
    }
}

internal const val DefaultScreenTimeoutSeconds: Int = 0
internal const val MimirIdleDimFraction: Float = 0.01f

internal fun screenTimeoutLabel(seconds: Int): String = when (val normalized =
    BrightnessMath.normalizeScreenTimeoutSeconds(seconds)
) {
    0 -> "SYSTEM"
    1 -> "1 second"
    else -> "$normalized seconds"
}

/** Codec for the observed vendor shape `#AARRGGBB,#AARRGGBB`, preserving its channel count. */
object JoystickColorCodec {
    /** White plus 24 full-chroma hues at 15-degree intervals; brightness remains independent. */
    val presets: List<Int> = buildList {
        add(0xffffffff.toInt())
        repeat(24) { index -> add(argbForHue(index / 24f)) }
    }

    fun parse(raw: String?): List<Int>? {
        val tokens = raw?.split(',')?.map(String::trim)?.takeIf { it.isNotEmpty() } ?: return null
        return tokens.map { token -> parseToken(token) ?: return null }
    }

    fun hueFraction(argb: Int): Float {
        val red = (argb ushr 16 and 0xff) / 255f
        val green = (argb ushr 8 and 0xff) / 255f
        val blue = (argb and 0xff) / 255f
        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))
        val delta = high - low
        if (delta == 0f) return 0f
        val sector = when (high) {
            red -> ((green - blue) / delta) % 6f
            green -> (blue - red) / delta + 2f
            else -> (red - green) / delta + 4f
        }
        val degrees = sector * 60f
        return ((degrees / 360f) + 1f) % 1f
    }

    /** Full-chroma RGB is intentional: light intensity remains an independent hardware slider. */
    fun argbForHue(fraction: Float): Int {
        val hue = BrightnessMath.utilityFraction(fraction) % 1f
        val scaled = hue * 6f
        val sector = floor(scaled).toInt().coerceIn(0, 5)
        val offset = scaled - floor(scaled)
        val secondary = (offset * 255f).roundToInt().coerceIn(0, 255)
        val inverse = 255 - secondary
        val (red, green, blue) = when (sector) {
            0 -> Triple(255, secondary, 0)
            1 -> Triple(inverse, 255, 0)
            2 -> Triple(0, 255, secondary)
            3 -> Triple(0, inverse, 255)
            4 -> Triple(secondary, 0, 255)
            else -> Triple(255, 0, inverse)
        }
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun quantizeFraction(fraction: Float): Float {
        val last = presets.lastIndex.coerceAtLeast(1)
        val index = (BrightnessMath.utilityFraction(fraction) * last).roundToInt().coerceIn(0, last)
        return index / last.toFloat()
    }

    fun argbForSelection(fraction: Float): Int {
        val index = (quantizeFraction(fraction) * presets.lastIndex).roundToInt()
        return presets[index.coerceIn(0, presets.lastIndex)]
    }

    fun fractionForArgb(argb: Int): Float {
        val opaque = argb or (0xff shl 24)
        val exact = presets.indexOf(opaque)
        val index = if (exact >= 0) exact else presets.indices.minBy { colorDistance(presets[it], opaque) }
        return index / presets.lastIndex.toFloat()
    }

    fun value(previous: String?, argb: Int): String {
        val channels = parse(previous)?.size ?: 1
        val token = String.format(Locale.ROOT, "#%08X", argb or (0xff shl 24))
        return List(channels) { token }.joinToString(",")
    }

    fun matches(raw: String?, argb: Int): Boolean {
        val expected = argb or (0xff shl 24)
        return parse(raw)?.all { it == expected } == true
    }

    private fun parseToken(token: String): Int? {
        if (!token.startsWith('#')) return null
        val digits = token.drop(1)
        val normalized = when (digits.length) {
            6 -> "FF$digits"
            8 -> digits
            else -> return null
        }
        return normalized.toLongOrNull(16)?.takeIf { it <= 0xffffffffL }?.toInt()
    }

    private fun colorDistance(first: Int, second: Int): Int {
        val red = (first ushr 16 and 0xff) - (second ushr 16 and 0xff)
        val green = (first ushr 8 and 0xff) - (second ushr 8 and 0xff)
        val blue = (first and 0xff) - (second and 0xff)
        return red * red + green * green + blue * blue
    }
}
