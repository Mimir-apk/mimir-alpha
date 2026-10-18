package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Window
import android.view.WindowManager

/** Public Android adapter for the special-access screen; the host decides when to launch it. */
object MimirBrightnessWriteAccess {
    val action: String get() = Settings.ACTION_MANAGE_WRITE_SETTINGS

    fun request(context: Context): BrightnessPermissionRequest = BrightnessPermissionRequest(
        action = action,
        dataUri = "package:${context.packageName}",
    )

    fun intent(context: Context): Intent = Intent(
        action,
        Uri.parse(request(context).dataUri),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

class AndroidBrightnessSettingsGateway(context: Context) : BrightnessSettingsGateway {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    override fun canWrite(): Boolean = runCatching {
        Settings.System.canWrite(appContext)
    }.getOrDefault(false)

    override fun read(key: String): String? = runCatching {
        Settings.System.getString(resolver, key)
    }.getOrNull()?.trim()?.ifEmpty { null }

    override fun write(key: String, value: String): Boolean {
        if (!isSupportedInstalledAppSystemWrite(key)) return false
        if (!canWrite()) return false
        return runCatching {
            Settings.System.putString(resolver, key, value)
        }.getOrDefault(false)
    }

    override fun permissionRequest(): BrightnessPermissionRequest = MimirBrightnessWriteAccess.request(appContext)
}

/** The installed-app gateway must never send private vendor keys to SettingsProvider. */
internal fun isSupportedInstalledAppSystemWrite(key: String): Boolean =
    key == BrightnessSettingsKeys.PRIMARY

class AndroidWindowBrightnessPreferenceStore(context: Context) : WindowBrightnessPreferenceStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "mimir_rewrite_window_brightness",
        Context.MODE_PRIVATE,
    )

    override fun read(target: BrightnessTarget): Float? {
        val key = keyFor(target) ?: return null
        val activeKey = activeKeyFor(target) ?: return null
        // Pre-UT01 values had no active marker and may be stale after a successful system write.
        if (!preferences.getBoolean(activeKey, false) || !preferences.contains(key)) return null
        return preferences.getFloat(key, Float.NaN)
            .takeIf { it.isFinite() }
            ?.let(BrightnessMath::windowFraction)
    }

    override fun write(target: BrightnessTarget, fraction: Float) {
        val key = keyFor(target) ?: return
        val activeKey = activeKeyFor(target) ?: return
        preferences.edit()
            .putFloat(key, BrightnessMath.windowFraction(fraction))
            .putBoolean(activeKey, true)
            .apply()
    }

    override fun clear(target: BrightnessTarget) {
        val key = keyFor(target) ?: return
        val activeKey = activeKeyFor(target) ?: return
        preferences.edit().remove(key).remove(activeKey).apply()
    }

    private fun keyFor(target: BrightnessTarget): String? = when (target) {
        BrightnessTarget.PRIMARY -> "primary_window_fraction"
        BrightnessTarget.SECONDARY -> "secondary_window_fraction"
        BrightnessTarget.JOYSTICK_LIGHT,
        BrightnessTarget.JOYSTICK_COLOR,
        BrightnessTarget.MIMIR_HAPTIC,
        BrightnessTarget.NAV_SOUND,
        BrightnessTarget.AMBIENT_SOUND,
        BrightnessTarget.MEDIA_VOLUME,
        BrightnessTarget.SCREEN_TIMEOUT,
        BrightnessTarget.EQ_GAIN,
        -> null
    }

    private fun activeKeyFor(target: BrightnessTarget): String? = when (target) {
        BrightnessTarget.PRIMARY -> "primary_window_override_active"
        BrightnessTarget.SECONDARY -> "secondary_window_override_active"
        BrightnessTarget.JOYSTICK_LIGHT,
        BrightnessTarget.JOYSTICK_COLOR,
        BrightnessTarget.MIMIR_HAPTIC,
        BrightnessTarget.NAV_SOUND,
        BrightnessTarget.AMBIENT_SOUND,
        BrightnessTarget.MEDIA_VOLUME,
        BrightnessTarget.SCREEN_TIMEOUT,
        BrightnessTarget.EQ_GAIN,
        -> null
    }
}

/** Window-only fallback adapter. It does not imply that Android changed either panel system-wide. */
class AndroidWindowBrightnessSink(
    private val primaryWindow: () -> Window?,
    private val secondaryWindow: () -> Window?,
) : WindowBrightnessSink {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun applyPrimary(fraction: Float?) {
        applyOnMain(primaryWindow, fraction)
    }

    override fun applySecondary(fraction: Float?) {
        applyOnMain(secondaryWindow, fraction)
    }

    override fun applyIdleDim(includeSecondary: Boolean) {
        applyRawOnMain(primaryWindow, MimirIdleDimFraction)
        if (includeSecondary) applyRawOnMain(secondaryWindow, MimirIdleDimFraction)
    }

    private fun applyOnMain(window: () -> Window?, fraction: Float?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            window()?.applyMimirBrightness(fraction)
        } else {
            mainHandler.post { window()?.applyMimirBrightness(fraction) }
        }
    }

    private fun applyRawOnMain(window: () -> Window?, fraction: Float) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            window()?.applyMimirIdleDim(fraction)
        } else {
            mainHandler.post { window()?.applyMimirIdleDim(fraction) }
        }
    }
}

fun Window.applyMimirBrightness(fraction: Float?) {
    val updated = attributes
    updated.screenBrightness = fraction?.let(BrightnessMath::windowFraction)
        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    attributes = updated
}

private fun Window.applyMimirIdleDim(fraction: Float) {
    val updated = attributes
    updated.screenBrightness = fraction.coerceIn(0.01f, 1f)
    attributes = updated
}

fun currentBrightnessDeviceIdentity(context: Context): BrightnessDeviceIdentity {
    val manufacturer = Build.MANUFACTURER.orEmpty()
    val brand = Build.BRAND.orEmpty()
    val model = Build.MODEL.orEmpty()
    val packageCandidates = when {
        manufacturer.equals("ayn", ignoreCase = true) || brand.equals("ayn", ignoreCase = true) ->
            listOf(VendorUtilityPackages.AYN_SETTINGS)
        manufacturer.equals("moorechip", ignoreCase = true) ||
            brand.equals("retroid", ignoreCase = true) ||
            model.startsWith("retroid", ignoreCase = true) ->
            listOf(VendorUtilityPackages.RETROID_SETTINGS)
        else -> emptyList()
    }
    return BrightnessDeviceIdentity(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        vendorSettingsPackage = packageCandidates.firstOrNull(context::hasInstalledPackage),
    )
}

/**
 * Resolve the sole live non-default Presentation display on demand; display IDs are never persisted.
 * Public Android SDK stubs do not expose Display#getType. The privileged service therefore performs
 * the authoritative TYPE_INTERNAL check again before any read or write crosses the privileged boundary.
 */
fun currentSecondaryPresentationDisplayId(context: Context): Int? {
    val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
    return runCatching {
        selectSoleSecondaryPresentationDisplayId(
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).map { display ->
                SecondaryDisplayCandidate(
                    displayId = display.displayId,
                    isPresentation = display.flags and Display.FLAG_PRESENTATION != 0,
                    isValid = display.isValid,
                )
            },
        )
    }.getOrNull()
}

internal data class SecondaryDisplayCandidate(
    val displayId: Int,
    val isPresentation: Boolean,
    val isValid: Boolean,
)

internal fun selectSoleSecondaryPresentationDisplayId(candidates: List<SecondaryDisplayCandidate>): Int? =
    candidates
        .filter { candidate ->
            candidate.displayId != Display.DEFAULT_DISPLAY &&
                candidate.isPresentation &&
                candidate.isValid
        }
        .singleOrNull()
        ?.displayId

@Suppress("DEPRECATION")
private fun Context.hasInstalledPackage(packageName: String): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
    } else {
        packageManager.getApplicationInfo(packageName, 0)
    }
}.isSuccess
