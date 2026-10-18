package com.gruning.mimir.rewrite.core.launch

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import java.io.File

/**
 * The real [LaunchDispatcher]: builds and fires the launch [Intent] to the resolved emulator package
 * with the ROM locator, and reports the SYNCHRONOUS outcome — intent sent, or a typed dispatch failure.
 *
 * Scope discipline : dispatch-only.
 * The outcome is whether `startActivity` was accepted, NOT whether the emulator stayed open — there is
 * deliberately no silent-death verdict.
 *
 * Intent forms mirror the current product's real launch shapes (native launch seam contract):
 *  - [LaunchLocatorForm.CONTENT_URI] → `setData(content://…)` + `FLAG_GRANT_READ_URI_PERMISSION` so the
 *    emulator can read the exact SAF document (DS/3DS/Switch). Wrong form here is a real launch failure.
 *  - [LaunchLocatorForm.FILE_PATH] → the file exposed as a `content://` URI via the app's [FileProvider]
 *    (a raw `file://` URI is blocked by StrictMode on modern Android) PLUS the common `EXTRA_*` path
 *    extras the standalone emulators read, mirroring the product's file launches.
 *  - [LaunchLocatorForm.EITHER] → treated as a file path when the locator is a plain path, else content.
 *  - [LaunchLocatorForm.NONE] → open-emulator-only: fire the package's launch intent with no locator.
 *
 * Known seed packages use proof-backed per-emulator profiles first. A seeded package without a proven
 * profile is refused as [LaunchDispatchFailure.LAUNCH_PROFILE_UNPROVEN] instead of receiving a generic
 * payload. The generic fallback exists only for non-seed targets a caller may supply explicitly.
 */
class IntentLaunchDispatcher(
    private val context: Context,
    private val preDispatchReap: (String) -> LaunchTargetReapResult = {
        LaunchTargetReapResult.AUTHORITY_UNAVAILABLE
    },
    private val bestEffortReap: (String) -> Unit = { packageName ->
        val am = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.killBackgroundProcesses(packageName)
    },
    private val packageLaunchIntent: (String) -> Intent? = { packageName ->
        context.applicationContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: context.applicationContext.packageManager.getLeanbackLaunchIntentForPackage(packageName)
    },
    /** Only a positive ENOENT/ENOTDIR result blocks dispatch; denied or uncertain paths pass through. */
    private val plainPathDefinitelyMissing: (String) -> Boolean = ::plainPathDefinitelyMissing,
    /**
     * The Android user this process dispatches from: the RetroArch config and core paths are per-user (`/data/user/<id>`,
     * `/storage/emulated/<id>`), and a launch from a secondary user or work profile must not name the owner's.
     */
    private val androidUserId: Int = androidUserIdOf(android.os.Process.myUid()),
    /** The installed build's versionCode, or null when Mimir cannot see the package (see [MelonDsRomFolderRule]). */
    private val installedVersionCode: (String) -> Long? = { packageName ->
        try {
            context.applicationContext.packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (_: Exception) {
            null
        }
    },
    /** How the pre-dispatch playlist check reads and asks about files (see [PlaylistPreflight]). */
    private val playlistOps: PlaylistFileOps = androidPlaylistFileOps(context, plainPathDefinitelyMissing),
    /** Overridable so a test can assert the built intent without a real `startActivity`. */
    private val startActivity: (Intent) -> Unit = { intent ->
        // Pin every game launch to the PRIMARY display — shipping's
        // startActivityOnPrimary. Without this, Android 11 places the new task on the last-active
        // display, and after any companion/second-screen activity the emulator landed on SCREEN 2.
        val options = android.app.ActivityOptions.makeBasic().apply {
            launchDisplayId = android.view.Display.DEFAULT_DISPLAY
        }
        context.applicationContext.startActivity(intent, options.toBundle())
    },
) : LaunchDispatcher {

    /** The last emulator package this process dispatched to — see the pre-dispatch reap below. */
    @Volatile
    private var lastDispatchedPackage: String? = null

    /**
     * [LaunchController] consumes this immediately after [dispatch] on the same worker thread.
     * Thread-local storage prevents a rapid second launch from attaching another target's diagnostic
     * context to the first result.
     */
    private val lastPreflight = ThreadLocal<LaunchTargetPreflightContext?>()

    override fun preflightContext(): LaunchTargetPreflightContext? = lastPreflight.get()

    private companion object {
        /**
         * The shipping "resident external launch task policy" (thor-shop MainActivity.java:2210-2223,
         * `new_task_new_document_multiple_task_clear_task_no_finish`), ported for HP-1: on this device a
         * plain NEW_TASK intent does NOT bring forward an emulator task left alive in the background by
         * an exit-via-recents — launch #2 is silently ignored. Force a fresh foreground task every launch,
         * exactly as shipping does. Companion/secondary launch paths keep their own flag logic.
         */
        const val ResidentLaunchFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
    }

    override fun dispatch(target: LaunchTarget): LaunchOutcome = prepare(target).dispatch()

    override fun prepare(
        target: LaunchTarget,
        destination: LaunchDispatchDestination,
    ): PreparedLaunch {
        if (destination is LaunchDispatchDestination.Main) return prepare(target)
        return prepareInternal(target, destination)
    }

    override fun prepare(target: LaunchTarget): PreparedLaunch {
        return prepareInternal(target, LaunchDispatchDestination.Main)
    }

    private fun prepareInternal(
        target: LaunchTarget,
        destination: LaunchDispatchDestination,
    ): PreparedLaunch {
        lastPreflight.remove()
        val pkg = target.emulator.packageName
        if (pkg.isBlank()) {
            return PreparedLaunch.failed(
                failed(target, LaunchDispatchFailure.TARGET_MISSING, "target_missing"),
            )
        }

        // F4/F8: a launch Mimir can see is doomed is refused in words, before an intent is built or a target reaped.
        LaunchPreflight.refusalFor(target, installedVersionCode, playlistOps)?.let { refusal ->
            return PreparedLaunch.failed(failed(target, refusal.failure, refusal.reason, detail = refusal.detail))
        }

        val intent = try {
            buildIntent(target)
        } catch (e: LocatorFormException) {
            return PreparedLaunch.failed(failed(target, e.failure, e.reason))
        } catch (e: Exception) {
            return PreparedLaunch.failed(
                failed(target, LaunchDispatchFailure.BRIDGE_ERROR, "intent_prepare_failed", e.message),
            )
        } ?: return PreparedLaunch.failed(
            failed(target, LaunchDispatchFailure.TARGET_NOT_INSTALLED, "target_not_installed"),
        )

        // C1: reap the exact resolved target through the typed privileged route first. Missing/refused
        // authority degrades to Android's background-only fallback and never changes dispatch truth.
        val reap = performLaunchTargetReap(pkg, preDispatchReap, bestEffortReap)
        val preflight = LaunchTargetPreflightContext(pkg, reap)
        lastPreflight.set(preflight)
        // Preserve the old cross-emulator cleanup without repeating a same-package fallback.
        lastDispatchedPackage
            ?.takeUnless { it == pkg }
            ?.let { previous -> runCatching { bestEffortReap(previous) } }
        return PreparedLaunch.dispatchable(preflight) {
            try {
                val accepted = when (destination) {
                    LaunchDispatchDestination.Main -> {
                        startActivity(intent)
                        true
                    }
                    is LaunchDispatchDestination.Secondary -> destination.dispatch(Intent(intent))
                }
                if (!accepted) {
                    return@dispatchable failed(target, LaunchDispatchFailure.BRIDGE_ERROR, "secondary_dispatch_rejected")
                }
                lastDispatchedPackage = pkg
                LaunchOutcome.IntentSent
            } catch (_: ActivityNotFoundException) {
                failed(target, LaunchDispatchFailure.ACTIVITY_NOT_STARTED, "activity_not_started")
            } catch (e: SecurityException) {
                // A revoked SAF grant most commonly manifests here on a content launch.
                val (failure, reason) = if (target.locatorForm == LaunchLocatorForm.CONTENT_URI) {
                    LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE to "content_uri_unavailable"
                } else {
                    LaunchDispatchFailure.ACTIVITY_NOT_STARTED to "activity_not_started"
                }
                failed(target, failure, reason, e.message)
            } catch (e: Exception) {
                failed(target, LaunchDispatchFailure.BRIDGE_ERROR, "bridge_error", e.message)
            }
        }
    }

    /**
     * Build the concrete launch [Intent], or return null when the target package has no launchable
     * activity (→ TARGET_NOT_INSTALLED). Throws [LocatorFormException] when the locator is missing or
     * malformed for the requested form (→ a typed dispatch failure, not a crash).
     */
    private fun buildIntent(target: LaunchTarget): Intent? {
        // GameNative (R-2b) intercepts BEFORE profileFor() -- its proven flag contract (NEW_TASK|
        // CLEAR_TOP only) must never pick up buildProfileIntent's shared ResidentLaunchFlags, and its
        // payload is read from the locator FILE's content, not attached as a URI/path. See
        // buildGameNativeIntent and GameNativeLaunchRoute.
        if (GameNativeLaunchRoute.isTarget(target.emulator.packageName)) {
            return buildGameNativeIntent(target)
        }

        ShippingLaunchProfiles.profileFor(target, androidUserId)?.let { profile ->
            return buildProfileIntent(target, profile)
        }

        val pkg = target.emulator.packageName
        if (pkg in EmulatorSeedRegistry.allSeedPackages()) {
            if (
                target.emulator.capability == EmulatorCapability.OPEN_ONLY &&
                target.locatorForm == LaunchLocatorForm.NONE
            ) {
                val launcher = packageLaunchIntent(pkg) ?: return null
                return Intent(launcher).apply {
                    setPackage(pkg)
                    addFlags(ResidentLaunchFlags)
                }
            }
            throw LocatorFormException(
                LaunchDispatchFailure.LAUNCH_PROFILE_UNPROVEN,
                "launch_profile_unproven",
            )
        }

        // Base: the package's own launch intent so we hit its real entry activity (component-resolved),
        // then attach the ROM locator per form. If the package has no launcher activity it is not a
        // usable target on this device.
        val base = packageLaunchIntent(pkg)
            ?: Intent(Intent.ACTION_VIEW).setPackage(pkg)

        val intent = Intent(base).apply {
            setPackage(pkg)
            addFlags(ResidentLaunchFlags)
        }

        when (target.locatorForm) {
            LaunchLocatorForm.NONE -> {
                // Open-emulator-only: nothing to attach; the base launch intent is the payload.
            }

            LaunchLocatorForm.CONTENT_URI -> {
                val uri = parseContentUri(target)
                intent.action = Intent.ACTION_VIEW
                intent.setData(uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                attachPathExtras(intent, target.locator)
            }

            LaunchLocatorForm.FILE_PATH -> {
                attachFileLocator(intent, fileFor(target))
            }

            LaunchLocatorForm.EITHER -> {
                // Cheapest valid: a content:// locator → content form, else a plain path.
                if (target.locator.startsWith("content://")) {
                    intent.action = Intent.ACTION_VIEW
                    intent.setData(parseContentUri(target))
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    attachPathExtras(intent, target.locator)
                } else {
                    attachFileLocator(intent, fileFor(target))
                }
            }
        }
        return intent
    }

    /** Build one of the proof-backed per-emulator intent shapes ported from the shipping bridge. */
    private fun buildProfileIntent(target: LaunchTarget, profile: LaunchDispatchProfile): Intent {
        val intent = if (profile.action != null) Intent(profile.action) else Intent()
        intent.setClassName(profile.packageName, profile.activityName)
        if (profile.addDefaultCategory) intent.addCategory(Intent.CATEGORY_DEFAULT)
        intent.addFlags(ResidentLaunchFlags)

        when (profile.pathStrategy) {
            LaunchPathStrategy.ABSOLUTE_PATH_EXTRA -> {
                val path = fileFor(target).absolutePath
                profile.pathExtraKey?.let { intent.putExtra(it, path) }
            }

            LaunchPathStrategy.FILE_PROVIDER_DATA -> {
                val uri = fileProviderUri(fileFor(target))
                attachShareableUri(intent, uri, profile)
            }

            LaunchPathStrategy.CONTENT_URI_DATA -> {
                // The exact SAF document when the locator IS one. When it is a path -- the game was
                // indexed by file rather than through a tree grant, which is what happens whenever Mimir
                // has all-files access and no persisted grant -- expose it through our own FileProvider
                // instead of failing. The emulator still receives a readable `content://` URI in setData,
                // which is the whole of what this contract promises it; where that URI comes from was
                // never part of the promise.
                //
                // Measured on the Odin 2026-09-06: its only tree grant belonged to
                // `com.gruning.mimir.rewrite.devlab`, so every Switch title resolved to a path and could
                // not be launched at all, while file-path platforms were unaffected.
                //
                // This is deliberately the same shape as SHAREABLE_URI_DATA below. The two strategies now
                // differ in declared INTENT, not behaviour, and the per-emulator profiles keep their
                // proven labels -- which is why the contract tests that pin those labels still pass.
                val uri = if (target.locator.startsWith("content://")) {
                    parseContentUri(target)
                } else {
                    fileProviderUri(fileFor(target))
                }
                attachShareableUri(intent, uri, profile)
            }

            LaunchPathStrategy.SHAREABLE_URI_DATA -> {
                val uri = if (target.locator.startsWith("content://")) {
                    parseContentUri(target)
                } else {
                    fileProviderUri(fileFor(target))
                }
                attachShareableUri(intent, uri, profile)
            }

            LaunchPathStrategy.PLAIN_PATH_DATA -> {
                intent.setData(Uri.parse(fileFor(target).absolutePath))
            }

            LaunchPathStrategy.NO_CONTENT -> Unit

            LaunchPathStrategy.DISPATCHER_OWNED_PAYLOAD -> error(
                "unreachable: a DISPATCHER_OWNED_PAYLOAD profile must be intercepted in buildIntent() " +
                    "before ShippingLaunchProfiles.profileFor() is ever consulted for it (see " +
                    "buildGameNativeIntent) -- reaching here means that intercept regressed for " +
                    "${profile.packageName}. Caught upstream as a typed BRIDGE_ERROR, never a crash.",
            )
        }

        for ((key, value) in profile.booleanExtras) {
            intent.putExtra(key, value)
        }
        for ((key, value) in profile.stringExtras) {
            intent.putExtra(key, value)
        }
        return intent
    }

    /**
     * GameNative's proven two-sided contract :
     * `LAUNCH_GAME` + explicit `MainActivity` component + typed `app_id`/`game_source` extras +
     * EXACTLY `NEW_TASK|CLEAR_TOP`. Handled here rather than through [buildProfileIntent] because that
     * shared path hardcodes [ResidentLaunchFlags] for every OTHER profile -- adding flags GameNative's
     * own contract does not call for is exactly the kind of unproven addition this codebase has already
     * been burned by. The shortcut FILE is read here (through the pure, unit-tested
     * [GameNativeLaunchRoute.parse]) because the payload GameNative wants -- a typed Int `app_id` --
     * lives in the file's CONTENT, not its path, unlike every ROM emulator this dispatcher otherwise
     * serves.
     *
     * [fileFor] already turns a blank locator into [LaunchDispatchFailure.ROM_PATH_MISSING] and a
     * `content://` locator into [LaunchDispatchFailure.LOCATOR_FORM_REJECTED] -- reused as-is; GameNative
     * always wants [com.gruning.mimir.rewrite.core.model.LaunchLocatorForm.FILE_PATH].
     */
    private fun buildGameNativeIntent(target: LaunchTarget): Intent {
        val file = fileFor(target)
        val payload = when (val result = GameNativeLaunchRoute.parse(file)) {
            is GameNativeLaunchRoute.ParseResult.Payload -> result
            is GameNativeLaunchRoute.ParseResult.Unreadable ->
                throw LocatorFormException(LaunchDispatchFailure.ROM_PATH_MISSING, result.reason)
            is GameNativeLaunchRoute.ParseResult.Malformed ->
                throw LocatorFormException(LaunchDispatchFailure.LOCATOR_CONTENT_MALFORMED, result.reason)
        }
        return Intent(GameNativeLaunchRoute.ACTION_LAUNCH_GAME).apply {
            setClassName(GameNativeLaunchRoute.PACKAGE_NAME, GameNativeLaunchRoute.ACTIVITY_NAME)
            putExtra(GameNativeLaunchRoute.EXTRA_APP_ID, payload.appId)
            putExtra(GameNativeLaunchRoute.EXTRA_GAME_SOURCE, payload.gameSource)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /** Parse the content:// locator; a non-content string in CONTENT_URI form is a form rejection. */
    private fun parseContentUri(target: LaunchTarget): Uri {
        val loc = target.locator
        if (loc.isBlank()) throw LocatorFormException(LaunchDispatchFailure.ROM_PATH_MISSING, "rom_path_missing")
        if (!loc.startsWith("content://")) {
            throw LocatorFormException(LaunchDispatchFailure.LOCATOR_FORM_REJECTED, "content_uri_required")
        }
        return Uri.parse(loc)
    }

    /** Resolve the plain-path locator to a [File]; a content:// string in FILE_PATH form is rejected. */
    private fun fileFor(target: LaunchTarget): File {
        val loc = target.locator
        if (loc.isBlank()) throw LocatorFormException(LaunchDispatchFailure.ROM_PATH_MISSING, "rom_path_missing")
        if (loc.startsWith("content://")) {
            throw LocatorFormException(LaunchDispatchFailure.LOCATOR_FORM_REJECTED, "file_path_required")
        }
        if (plainPathDefinitelyMissing(loc)) {
            throw LocatorFormException(LaunchDispatchFailure.ROM_PATH_MISSING, "rom_path_missing")
        }
        return File(loc)
    }

    /**
     * Attach a plain-path ROM locator to the intent for the modern-Android launch shape.
     *
     * A raw `file://` URI (`Uri.fromFile`) is REJECTED by StrictMode on targetSdk ≥ 24
     * (`FileUriExposedException`) the instant it is handed to another app, so — exactly like the
     * CONTENT_URI form — we expose the file through our [FileProvider] as a `content://` URI and add
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] so the resolved emulator can read it. The well-known raw
     * path extras are ALSO attached for the standalone emulators that read a path string rather than the
     * data URI. If FileProvider cannot map the path (unconfigured root), the throw is caught upstream and
     * surfaced as a typed dispatch failure rather than a crash.
     */
    private fun attachFileLocator(intent: Intent, file: File) {
        intent.action = Intent.ACTION_VIEW
        intent.setData(fileProviderUri(file))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        attachPathExtras(intent, file.absolutePath)
    }

    /** Map a launchable [File] to a shareable `content://` URI via the app's [FileProvider]. */
    private fun fileProviderUri(file: File): Uri {
        val authority = "${context.applicationContext.packageName}.launchfiles"
        return FileProvider.getUriForFile(context.applicationContext, authority, file)
    }

    /** Attach one exact, grantable URI envelope shared by SAF- and path-backed library rows. */
    private fun attachShareableUri(intent: Intent, uri: Uri, profile: LaunchDispatchProfile) {
        if (!profile.attachUriAsData) {
            intent.type = profile.mimeType
        } else if (profile.mimeType != null) {
            intent.setDataAndType(uri, profile.mimeType)
        } else {
            intent.setData(uri)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(
            context.applicationContext.contentResolver,
            "Mimir launch resource",
            uri,
        )
        profile.uriStringExtraKey?.let { intent.putExtra(it, uri.toString()) }
    }

    /**
     * Attach the well-known path/title extras the standalone emulators read, mirroring the current
     * product's file launches. Emulators pick whichever key they support; extra keys are harmless.
     */
    private fun attachPathExtras(intent: Intent, path: String) {
        intent.putExtra("rom", path)
        intent.putExtra("ROM", path)
        intent.putExtra("path", path)
        intent.putExtra("GAMEPATH", path)
    }

    private fun failed(
        target: LaunchTarget,
        failure: LaunchDispatchFailure,
        reason: String,
        message: String? = null,
        detail: String? = null,
    ): LaunchOutcome.Failed = LaunchOutcome.Failed(
        reason = failure,
        targetEmulatorId = target.emulator.id,
        nativeMetadata = buildMap {
            put("reason", reason)
            put("targetPackageKey", target.emulator.id.value)
            put("package", target.emulator.packageName)
            put("locatorForm", target.locatorForm.name)
            if (message != null) put("message", message)
            // The sentence the card shows, when the stock copy for this failure's reason would be too general.
            if (detail != null) put(LaunchOutcome.Failed.DETAIL_KEY, detail)
        },
    )

    /** Internal control-flow signal for a malformed/missing locator, mapped to a typed failure. */
    private class LocatorFormException(
        val failure: LaunchDispatchFailure,
        val reason: String,
    ) : Exception(reason)
}

/** ACCESS_DENIED and other errors are UNKNOWN: they must never be misreported as a missing ROM. */
private fun plainPathDefinitelyMissing(path: String): Boolean = try {
    Os.stat(path)
    false
} catch (error: ErrnoException) {
    error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR
} catch (_: Exception) {
    false
}

/** Diagnostic preflight only; none of these values redefine [LaunchOutcome]. */
enum class LaunchTargetReapResult {
    REQUEST_ACCEPTED,
    NOT_ALLOWED,
    AUTHORITY_UNAVAILABLE,
    FAILED,
}

fun performLaunchTargetReap(
    packageName: String,
    privileged: (String) -> LaunchTargetReapResult,
    bestEffort: (String) -> Unit,
): LaunchTargetReapResult {
    val result = runCatching { privileged(packageName) }
        .getOrDefault(LaunchTargetReapResult.FAILED)
    if (result == LaunchTargetReapResult.AUTHORITY_UNAVAILABLE ||
        result == LaunchTargetReapResult.FAILED
    ) {
        runCatching { bestEffort(packageName) }
    }
    return result
}
