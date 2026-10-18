package com.gruning.mimir.rewrite

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.launch.RetroArchCoreListing
import java.math.BigDecimal
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.roundToInt

private const val VendorHudDiagnosticTag = "MimirVendorHud"
private const val HudDiagnosticMaxLoggedBytes = 8 * 1024 + 2

/**
 * Certified vendor `pservice` authority. The public surface remains [PrivilegedUtilityGateway]; the
 * private vendor service never receives caller-provided commands, paths, components, URIs, or keys.
 *
 * Access is decided by the live capability probe on every read and by nothing that was stored on a
 * previous one -- see the KDoc on [posture] for the ruling that settles this.
 */
internal class AndroidCertifiedVendorPrivilegeGateway(
    private val context: Context,
    private val identity: BrightnessDeviceIdentity,
    private val transport: CertifiedVendorTransport = AndroidPServerTransport(context),
    /**
     * Retired gate, deliberately still constructed and deliberately never read.
     *
     * took the per-device consent record out of every access decision in
     * this class. The parameter stays so the existing preference file is neither orphaned by a
     * rename nor silently deleted, and so the seam is available to a future surface that needs a
     * consent which is genuinely about consent. Reading it to decide posture is the defect the
     * ruling names; do not reintroduce a call site.
     */
    @Suppress("unused")
    private val consent: CertifiedVendorConsentStore = AndroidCertifiedVendorConsentStore(context),
    private val evidenceReader: (() -> CertifiedVendorEvidence)? = null,
    private val nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val hudTransportFactory: () -> CertifiedVendorTransport = {
        AndroidPServerTransport(context)
    },
    private val replacementEvidenceReader:
        ((CertifiedVendorTransport, Long) -> CertifiedVendorEvidence)? = null,
    private val emulatorHelperStager: EmulatorRenderFpsHelperStager? = null,
    /**
     * The display manager's brightness numbers, from this device's own framework. Read once per process, on
     * the first READY posture, and only ever as structural evidence -- see [FrameworkDisplayTransactions].
     */
    private val displayTransactionsReader: () -> DisplayBrightnessTransactions? = {
        FrameworkDisplayTransactions.read()
    },
    /**
     * Whether a display is a second screen this route may set: not the main screen, present, and a
     * presentation display. Checked in this process, because the root shell on the other side checks nothing.
     */
    private val isSecondaryPanel: (Int) -> Boolean = { displayId -> isPresentationSecondaryDisplay(context, displayId) },
    /** Mimir's own IME id, recomputed from the app's own context; a seam only so a test need not fake a ComponentName. */
    private val keyboardImeId: () -> String = { mimirKeyboardImeId(context) },
) : PrivilegedUtilityGateway, MButtonSystemRecentsGateway, PrivilegedTaskCloseGateway,
    PrivilegedKeyboardDefaultGateway {
    private val transactionLock = Any()
    private val hudLock = Any()
    private val hudTelemetry = VendorHudTelemetryCache(
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        nowEpochMs = nowEpochMs,
        readSnapshot = ::readHudSnapshot,
    )

    @Volatile
    private var poisoned = false

    /**
     * Diagnostics only, for [failedKnownAuthorityPosture].
     *
     * This is what lets a device that *was* admitted report CONNECTION_FAILED instead of
     * UNSUPPORTED_DEVICE once its transport dies and attestation stops answering. It is never read
     * on the READY path: under the 2026-09-11 ruling a READY decision must come from the
     * attestation taken during *this* call, never from a remembered one.
     */
    @Volatile
    private var lastAdmittedProfile: CertifiedVendorProfile? = null

    /** Last identity written to the admission log, so a steady state logs once, not once per read. */
    @Volatile
    private var lastLoggedAdmission: String? = null

    /** Whether a log line has already reported the route being lost, so the loss also logs once. */
    @Volatile
    private var admissionLossLogged = false

    /** Whether the retired consent no-op has already explained itself; see [requestAccess]. */
    @Volatile
    private var implicitConsentLogged = false

    /** See [displayTransactionsReader]. Null is a framework this reader does not understand: no display route. */
    private val displayTransactions: DisplayBrightnessTransactions? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val found = runCatching(displayTransactionsReader).getOrNull()
        Log.i(
            "MimirPrivilege",
            if (found == null) {
                "vendor display route: no display-manager brightness calls this reader trusts in the framework"
            } else {
                "vendor display route: framework numbers setBrightness=${found.setBrightness} " +
                    "getBrightness=${found.getBrightness}"
            },
        )
        found
    }

    /**
     * Set once the display manager has refused the vendor route's call on this device; for the rest of the
     * process the bottom screen loses its vendor route, which [posture] then says. Never cleared: a refusal is a
     * property of the device, and retrying it on every slider step would be the same refusal each time.
     */
    @Volatile
    private var displayRouteClosed = false

    /** The first panel the route actually moved, logged once, as run evidence. */
    @Volatile
    private var displayRouteProvenLogged = false

    /**
     * The kind of the last display-route failure logged, so a slider held against one failure logs it once.
     * A set that goes through clears it, so the next failure logs again.
     */
    @Volatile
    private var lastDisplayFailureLogged: String? = null

    private var hudTransport: CertifiedVendorTransport? = null
    private var hudProfile: CertifiedVendorProfile? = null
    private var hudGeneration = 0L
    private var closed = false
    private var emulatorHelperFiles: EmulatorRenderFpsHelperFiles? = null
    private val renderFpsHelperStager: EmulatorRenderFpsHelperStager? by lazy(LazyThreadSafetyMode.NONE) {
        emulatorHelperStager ?: runCatching { EmulatorRenderFpsHelperStager(context) }.getOrNull()
    }
    private val emulatorRenderFpsSession = EmulatorRenderFpsSession(
        approvedPackages = EmulatorSeedRegistry.allDispatchPackages().toSet(),
        nowBoottimeMs = nowElapsedRealtimeMs,
    )

    /**
     * Built-in access, decided by the live PServer capability probe and nothing else.
     *
     *  Admission depends on the live vendor capability rather than a device-model allowlist.
     * The same structural admission governs which operations are available.
     *
     * So there are exactly three outcomes here, and a persisted per-device key is not an input to
     * any of them:
     *  - **READY** -- [liveProfile] admitted a fresh attestation through
     *    [CertifiedVendorAdmissionPolicy] and the transport is alive. That is the whole test.
     *  - **CONNECTION_FAILED** -- the route is known to exist on this device (this process admitted
     *    it at least once) but the transport is dead or poisoned.
     *  - **Unsupported** -- no attestation has ever been admitted in this process.
     *
     * **PERMISSION_REQUIRED is not reachable for this authority and must not be reintroduced.** The
     * gate it used to serve keyed on `profileId|executableSha256|buildFingerprint`, so a firmware
     * update or a swapped `pservice` silently dropped a working device back to a consent prompt and
     * [CertifiedPrivilegeGateway] reported an unavailable route. That is precisely the stale-device-data failure
     * this admission requirement prevents.
     *
     * Re-attested every call. [liveProfile] rebuilds the evidence per invocation, so a changed
     * attestation is decided on its own merits rather than inheriting an earlier verdict.
     */
    override fun posture(): PrivilegedUtilityPosture {
        if (liveProfile() == null) return failedKnownAuthorityPosture()
        if (poisoned || !transport.isAlive()) {
            return PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.CONNECTION_FAILED,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            )
        }
        return PrivilegedUtilityPosture(
            status = PrivilegedUtilityStatus.READY,
            canWriteVendorSettings = true,
            // Structural, like the rest of this posture: the framework names the two calls with the shapes
            // the commands send, and the display manager has not refused them here. The first refusal closes
            // the route and this goes false, which leaves the bottom screen unavailable.
            canControlSecondaryBrightness = displayRouteOpen(),
            authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
        )
    }

    /**
     * Retired by the 2026-09-11 ruling; kept so onboarding and the brightness overlay still compile.
     *
     * Vendor consent is implicit now: if the live probe admits the device, access is already granted
     * and there is nothing for a caller to request. This grants nothing, writes nothing, and always
     * returns false -- the caller's own posture read is the answer.
     */
    override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean {
        if (!implicitConsentLogged) {
            implicitConsentLogged = true
            Log.i("MimirPrivilege", "vendor consent is implicit for structurally admitted access")
        }
        return false
    }

    override fun readVendorSetting(key: String): String? {
        if (!readyProfileExists() || !CertifiedVendorValuePolicy.isAllowedKey(key)) return null
        return synchronized(transactionLock) {
            when (val read = transport.readSystemSetting(key)) {
                is VendorTransportRead.Value -> read.value
                    ?.takeIf { CertifiedVendorValuePolicy.normalize(key, it) != null }
                VendorTransportRead.Failed -> null
            }
        }
    }

    /** The vendor route shares one short fixed snapshot between adjacent HUD CPU/FPS reads. */
    override fun readForegroundFrameRateMilli(packageName: String): Int? {
        if (packageName != GAME_NATIVE_FRAME_RATE_PACKAGE) {
            if (!MimirLaunchTargetStopPolicy.isAllowed(packageName)) return null
            return readEmulatorRenderFrameRateMilli(packageName)
        }
        return synchronized(hudLock) {
            val deadline = nowElapsedRealtimeMs() + HUD_READ_DEADLINE_MS
            if (!ensureHudTelemetryReadyLocked(deadline)) {
                hudTelemetry.invalidate()
                return@synchronized null
            }
            val generation = hudGeneration
            val value = hudTelemetry.readForegroundFrameRateMilli(packageName, deadline)
            if (generation != hudGeneration ||
                !activeHudTransportLocked().isAlive() ||
                nowElapsedRealtimeMs() >= deadline
            ) {
                hudTelemetry.invalidate()
                return@synchronized null
            }
            value
        }
    }

    private fun readEmulatorRenderFrameRateMilli(packageName: String): Int? = synchronized(hudLock) {
        val deadline = nowElapsedRealtimeMs() + HUD_READ_DEADLINE_MS
        if (!ensureHudTelemetryReadyLocked(deadline)) return@synchronized null
        val stager = renderFpsHelperStager ?: return@synchronized null
        val files = emulatorHelperFiles ?: stager.stage()
            ?.also { emulatorHelperFiles = it }
            ?: return@synchronized null
        val hud = activeHudTransportLocked()
        val target = hud.resolveEmulatorRenderFpsTarget(files, packageName, deadline) ?: run {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        var previous = emulatorRenderFpsSession.request
        // Do not reset the independent startup-progress timer merely because the short lease
        // expired: a failed launcher must be cleared before a later beat can start a new one.
        if (previous?.matchesTarget(target) == true &&
            emulatorRenderFpsSession.noProgressExpired()
        ) {
            invalidateEmulatorRenderSession(files, stager)
            previous = null
        }
        val reusable = previous?.matchesTarget(target) == true &&
            previous.leaseBoottimeMs > nowElapsedRealtimeMs()
        val request = if (reusable) {
            emulatorRenderFpsSession.renew(target)
        } else {
            emulatorRenderFpsSession.begin(target)
        } ?: run {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        if (!reusable) stager.clearRecord(files)
        if (!stager.writeRequest(files, request)) {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        if (!reusable && !hud.startEmulatorRenderFps(files, deadline)) {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        val read = hud.readEmulatorRenderFpsRecordResult(files, deadline)
        val encoded = when (read) {
            EmulatorRenderFpsRecordRead.Missing -> {
                if (emulatorRenderFpsSession.noProgressExpired()) {
                    invalidateEmulatorRenderSession(files, stager)
                }
                return@synchronized null
            }
            EmulatorRenderFpsRecordRead.Failed -> {
                invalidateEmulatorRenderSession(files, stager)
                return@synchronized null
            }
            is EmulatorRenderFpsRecordRead.Encoded -> read.value
        }
        val record = decodeVendorHudWire(encoded)?.let(::parseEmulatorRenderFpsRecord)
            ?: run {
                invalidateEmulatorRenderSession(files, stager)
                return@synchronized null
            }
        if (!emulatorRenderFpsSession.observeRecord(record)) {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        when (record.status) {
            "warming", "unavailable" -> return@synchronized null
            "unsupported" -> {
                emulatorRenderFpsSession.backoffUnsupported()
                stager.clear(files)
                return@synchronized null
            }
            "lost", "stale", "ambiguous" -> {
                invalidateEmulatorRenderSession(files, stager)
                return@synchronized null
            }
        }
        // A fresh zero-frame publication is valid collector warming. Keep its lease and request
        // alive so idle menus do not restart the native helper once per beat.
        if (record.status == "ok" && record.count == 0L) return@synchronized null
        val accepted = emulatorRenderFpsSession.acceptRecord(record) ?: run {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        if (nowElapsedRealtimeMs() >= deadline) {
            invalidateEmulatorRenderSession(files, stager)
            return@synchronized null
        }
        val milli = accepted.count
            .times(1_000_000L)
            .div(accepted.intervalMs)
        milli.coerceIn(1L, Int.MAX_VALUE.toLong() - 500L).toInt()
    }

    private fun invalidateEmulatorRenderSession(
        files: EmulatorRenderFpsHelperFiles,
        stager: EmulatorRenderFpsHelperStager,
    ) {
        emulatorRenderFpsSession.invalidate()
        stager.clear(files)
    }

    /** A vendor snapshot carries CPU only; GPU remains owned by the existing native observation. */
    override fun isHudTelemetryReady(): Boolean = synchronized(hudLock) {
        ensureHudTelemetryReadyLocked(nowElapsedRealtimeMs() + HUD_READ_DEADLINE_MS)
    }

    override fun readDeviceUtilization(): DeviceUtilizationSample? = synchronized(hudLock) {
        val deadline = nowElapsedRealtimeMs() + HUD_READ_DEADLINE_MS
        if (!ensureHudTelemetryReadyLocked(deadline)) {
            hudTelemetry.invalidate()
            return@synchronized null
        }
        val generation = hudGeneration
        val value = hudTelemetry.readDeviceUtilization(deadline)
        if (generation != hudGeneration ||
            !activeHudTransportLocked().isAlive() ||
            nowElapsedRealtimeMs() >= deadline
        ) {
            hudTelemetry.invalidate()
            return@synchronized null
        }
        value
    }

    override fun writeVendorSetting(key: String, value: String): Boolean {
        val normalized = CertifiedVendorValuePolicy.normalize(key, value) ?: return false
        if (!readyProfileExists()) return false
        return synchronized(transactionLock) {
            if (poisoned || !readyProfileExists()) return@synchronized false
            val original = transport.readSystemSetting(key) as? VendorTransportRead.Value
                ?: return@synchronized false
            if (!CertifiedVendorValuePolicy.isSafeSnapshot(original.value)) return@synchronized false
            if (original.value != null &&
                CertifiedVendorValuePolicy.normalize(key, original.value) != original.value
            ) {
                return@synchronized false
            }
            // Idempotent family behavior: a settled target performs no privileged mutation.
            if (original.value == normalized) return@synchronized true

            val mutation = transport.writeSystemSetting(key, normalized)
            val applied = mutation == VendorTransportMutation.APPLIED &&
                readBackMatches(key, normalized)
            if (applied) return@synchronized true

            if (mutation == VendorTransportMutation.TIMED_OUT ||
                !restoreAndVerify(key, original.value)
            ) {
                poisoned = true
            }
            false
        }
    }

    /**
     * The panel's own brightness, from the display manager, through the vendor route.
     *
     * Uses the display manager's own call without Shizuku, made by root through the
     * stock `service` tool, by the number this device's framework gives it ([FrameworkDisplayTransactions]).
     */
    override fun readDisplayBrightness(displayId: Int): Float? {
        val transactions = openDisplayRoute(displayId) ?: return null
        if (!readyProfileExists()) return null
        return synchronized(transactionLock) {
            if (poisoned) return@synchronized null
            when (val read = transport.readDisplayBrightness(transactions, displayId)) {
                is VendorDisplayRead.Value -> read.fraction
                VendorDisplayRead.Refused -> {
                    closeDisplayRoute("the display manager refused a brightness read")
                    null
                }
                VendorDisplayRead.NoBrightness,
                VendorDisplayRead.Failed,
                -> {
                    logDisplayFailure("a panel read gave no brightness", "display=$displayId read=$read")
                    null
                }
            }
        }
    }

    /**
     * The bottom panel's brightness, and AYN's own level mirroring it, set and read back -- the same
     * transaction the former privileged service made (`transactSecondaryBrightness`), on the vendor route.
     *
     * Order and proof: read what the panel and the level hold now; set the panel; read the panel back; set
     * the level; read it back. Only both readbacks make this true. A failure after anything moved puts both
     * back and reads both back, and a put-back that cannot be read back poisons the route, as a failed
     * vendor-setting restore does. A call the display manager refuses while the panel stays where it was
     * changed nothing, so nothing is undone: the display route closes and bottom-screen control remains
     * unavailable for the rest of the process.
     */
    override fun setSecondaryBrightness(
        displayId: Int,
        fraction: Float,
        mirrorPercent: Int,
    ): Boolean {
        if (!CertifiedVendorValuePolicy.isValidDisplayTransaction(displayId, fraction, mirrorPercent)) return false
        val transactions = openDisplayRoute(displayId) ?: return false
        if (!readyProfileExists()) return false
        return synchronized(transactionLock) {
            if (poisoned || !readyProfileExists()) return@synchronized false
            val original = when (val read = transport.readDisplayBrightness(transactions, displayId)) {
                is VendorDisplayRead.Value -> read.fraction
                VendorDisplayRead.Refused -> {
                    closeDisplayRoute("the display manager refused a brightness read")
                    return@synchronized false
                }
                VendorDisplayRead.NoBrightness,
                VendorDisplayRead.Failed,
                -> {
                    logDisplayFailure("the panel gave no brightness before a set", "display=$displayId read=$read")
                    return@synchronized false
                }
            }
            val mirrorKey = BrightnessSettingsKeys.AYN_SECONDARY
            val mirrorRead = transport.readSystemSetting(mirrorKey)
            val originalMirror = mirrorRead as? VendorTransportRead.Value ?: run {
                logDisplayFailure("AYN's level gave no reading before a set", "read=$mirrorRead")
                return@synchronized false
            }
            if (!CertifiedVendorValuePolicy.isSafeSnapshot(originalMirror.value)) {
                logDisplayFailure(
                    "AYN's level holds nothing this route could put back",
                    "length=${originalMirror.value?.length}",
                )
                return@synchronized false
            }

            val write = transport.writeDisplayBrightness(transactions, displayId, fraction)
            val panel = transport.readDisplayBrightness(transactions, displayId)
            if (write == VendorTransportMutation.APPLIED && panel.holds(fraction)) {
                val mirrorValue = mirrorPercent.toString()
                val mirrorAlready = originalMirror.value
                    ?.let { CertifiedVendorValuePolicy.normalize(mirrorKey, it) } == mirrorValue
                val mirrorWrite = if (mirrorAlready) null else transport.writeSystemSetting(mirrorKey, mirrorValue)
                if (mirrorAlready ||
                    (mirrorWrite == VendorTransportMutation.APPLIED && readBackMatches(mirrorKey, mirrorValue))
                ) {
                    lastDisplayFailureLogged = null
                    logDisplayRouteProven(displayId)
                    return@synchronized true
                }
                // Both put-backs run whichever one fails; the reads decide.
                val displayRestored = restoreDisplayAndVerify(transactions, displayId, original)
                val mirrorRestored = restoreAndVerify(mirrorKey, originalMirror.value)
                if (mirrorWrite == VendorTransportMutation.TIMED_OUT || !displayRestored || !mirrorRestored) {
                    poisoned = true
                }
                logDisplayFailure(
                    "AYN's level did not take after the panel did",
                    "display=$displayId level=$mirrorValue write=$mirrorWrite panelRestored=$displayRestored " +
                        "levelRestored=$mirrorRestored poisoned=$poisoned",
                )
                return@synchronized false
            }
            if (write != VendorTransportMutation.TIMED_OUT && panel.holds(original)) {
                // Nothing moved, so there is nothing to put back.
                if (write == VendorTransportMutation.FAILED) {
                    closeDisplayRoute("a brightness write was not taken and the panel stayed at $original")
                } else {
                    // Taken and not applied: a dead slider on the vendor route. Only this line says so.
                    logDisplayFailure(
                        "the display manager took the call but the panel stayed put",
                        "display=$displayId asked=$fraction panel=$panel original=$original",
                    )
                }
                return@synchronized false
            }
            if (write == VendorTransportMutation.TIMED_OUT ||
                !restoreDisplayAndVerify(transactions, displayId, original)
            ) {
                poisoned = true
            }
            logDisplayFailure(
                "the panel did not settle where it was set",
                "display=$displayId write=$write asked=$fraction panel=$panel original=$original poisoned=$poisoned",
            )
            false
        }
    }

    private fun displayRouteOpen(): Boolean = !displayRouteClosed && displayTransactions != null

    private fun openDisplayRoute(displayId: Int): DisplayBrightnessTransactions? {
        if (displayRouteClosed || !runCatching { isSecondaryPanel(displayId) }.getOrDefault(false)) return null
        return displayTransactions
    }

    private fun closeDisplayRoute(reason: String) {
        if (displayRouteClosed) return
        displayRouteClosed = true
        Log.w("MimirPrivilege", "vendor display route closed: $reason; bottom-screen control is unavailable")
    }

    private fun logDisplayRouteProven(displayId: Int) {
        if (displayRouteProvenLogged) return
        displayRouteProvenLogged = true
        Log.i("MimirPrivilege", "vendor display route set display $displayId and read it back")
    }

    /** Run evidence for a set or read that did not go through; see [lastDisplayFailureLogged]. */
    private fun logDisplayFailure(kind: String, detail: String) {
        if (lastDisplayFailureLogged == kind) return
        lastDisplayFailureLogged = kind
        Log.w("MimirPrivilege", "vendor display route: $kind; $detail")
    }

    private fun VendorDisplayRead.holds(fraction: Float): Boolean =
        this is VendorDisplayRead.Value && abs(this.fraction - fraction) <= DisplayReadbackTolerance

    private fun restoreDisplayAndVerify(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
        original: Float,
    ): Boolean {
        if (transport.writeDisplayBrightness(transactions, displayId, original) != VendorTransportMutation.APPLIED) {
            return false
        }
        return transport.readDisplayBrightness(transactions, displayId).holds(original)
    }

    override fun forceStopLaunchTarget(packageName: String): PrivilegedLaunchTargetStop =
        closeLaunchTarget(packageName).asLaunchTargetStop()

    /**
     * One stop transaction whose liveness is read back before it claims anything.
     *
     * [MimirLaunchTargetStopPolicy] admits the target before the vendor operation, and
     * [VendorPServerOperation.ForceStop] re-checks shell safety before it composes.
     */
    override fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome {
        if (!MimirLaunchTargetStopPolicy.isAllowed(packageName)) {
            Log.w("MimirPrivilege", "vendor forceStop declined package=$packageName reason=not_allowed")
            return PrivilegedCloseOutcome.NOT_ALLOWED
        }
        if (!readyProfileExists()) {
            Log.w(
                "MimirPrivilege",
                "vendor forceStop declined package=$packageName reason=authority_unavailable",
            )
            return PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE
        }
        return synchronized(transactionLock) {
            val stop = transport.forceStopAllowedPackageVerified(packageName)
            Log.i("MimirPrivilege", "vendor forceStop package=$packageName stop=$stop")
            when (stop) {
                VendorTransportStop.STOPPED -> PrivilegedCloseOutcome.STOPPED
                VendorTransportStop.STILL_ALIVE -> PrivilegedCloseOutcome.STILL_ALIVE
                VendorTransportStop.ACCEPTED_UNVERIFIED -> PrivilegedCloseOutcome.REQUEST_UNVERIFIED
                VendorTransportStop.TIMED_OUT -> {
                    poisoned = true
                    PrivilegedCloseOutcome.FAILED
                }
                VendorTransportStop.FAILED -> PrivilegedCloseOutcome.FAILED
            }
        }
    }

    /**
     * Evicts one task by id, for the close that a whitelisted `force-stop` could not finish.
     *
     * There is no package or string argument: the id is an Int the registry already holds, so nothing
     * a caller controls can reach the rendered command.
     */
    override fun removeTask(taskId: Int): PrivilegedTaskRemoval {
        if (taskId <= 0) {
            Log.w("MimirPrivilege", "vendor removeTask declined taskId=$taskId reason=not_allowed")
            return PrivilegedTaskRemoval.NOT_ALLOWED
        }
        if (!readyProfileExists()) {
            Log.w(
                "MimirPrivilege",
                "vendor removeTask declined taskId=$taskId reason=authority_unavailable",
            )
            return PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE
        }
        return synchronized(transactionLock) {
            val mutation = transport.removeTask(taskId)
            Log.i("MimirPrivilege", "vendor removeTask taskId=$taskId mutation=$mutation")
            when (mutation) {
                VendorTransportMutation.APPLIED -> PrivilegedTaskRemoval.REMOVED
                VendorTransportMutation.TIMED_OUT -> {
                    poisoned = true
                    PrivilegedTaskRemoval.FAILED
                }
                VendorTransportMutation.FAILED -> PrivilegedTaskRemoval.FAILED
            }
        }
    }

    /**
     * Focuses one exact retained task. The caller resolves the package/display to this id before
     * entering this seam; no activity intent is issued, which preserves the native emulator task.
     */
    override fun focusTask(taskId: Int): PrivilegedTaskFocus {
        if (taskId <= 0) {
            Log.w("MimirPrivilege", "vendor focusTask declined taskId=$taskId reason=not_allowed")
            return PrivilegedTaskFocus.NOT_ALLOWED
        }
        if (!readyProfileExists()) {
            Log.w(
                "MimirPrivilege",
                "vendor focusTask declined taskId=$taskId reason=authority_unavailable",
            )
            return PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE
        }
        return synchronized(transactionLock) {
            val mutation = transport.focusTask(taskId)
            Log.i("MimirPrivilege", "vendor focusTask taskId=$taskId mutation=$mutation")
            when (mutation) {
                VendorTransportMutation.APPLIED -> PrivilegedTaskFocus.FOCUSED
                VendorTransportMutation.TIMED_OUT -> {
                    poisoned = true
                    PrivilegedTaskFocus.FAILED
                }
                VendorTransportMutation.FAILED -> PrivilegedTaskFocus.FAILED
            }
        }
    }

    /**
     * The display-scoped task id for a package Mimir is allowed to stop.
     *
     * Gated by the same [MimirLaunchTargetStopPolicy] door as the stop itself: resolving task ids for
     * arbitrary packages would be collecting information Mimir has no business holding, and the id it
     * returns feeds an eviction. Selection happens on parsed rows in Kotlin -- the command carries no
     * package at all.
     */
    override fun resolveTaskId(packageName: String, displayId: Int): Int? {
        if (!MimirLaunchTargetStopPolicy.isAllowed(packageName)) {
            Log.w("MimirPrivilege", "vendor resolveTask declined package=$packageName reason=not_allowed")
            return null
        }
        if (displayId < 0) {
            Log.w("MimirPrivilege", "vendor resolveTask declined package=$packageName reason=invalid_display")
            return null
        }
        if (!readyProfileExists()) {
            Log.w(
                "MimirPrivilege",
                "vendor resolveTask declined package=$packageName reason=authority_unavailable",
            )
            return null
        }
        return synchronized(transactionLock) {
            val reply = transport.resolveTaskReply(packageName, displayId)
            val taskId = parseVendorResolvedTaskId(reply, packageName)
            // The reply is logged as it arrived (bounded, newlines flattened): when a firmware's `am`
            // or `grep` differs, the line that shows it is the one that was actually returned.
            Log.i(
                "MimirPrivilege",
                "vendor resolveTask package=$packageName display=$displayId " +
                    "reply=${reply?.take(80)?.replace('\n', '|')?.replace('\r', ' ') ?: "none"} " +
                    "taskId=${taskId ?: "none"}",
            )
            taskId
        }
    }

    /**
     * R6-P0-02 review fix (BLOCKING): [resolveTaskId]'s tri-state sibling. The same three guards below
     * answer `Unavailable("unavailable")` -- no transaction was even attempted, so there is nothing more
     * specific to say -- and the transaction itself is [AndroidPServerTransport.resolveTaskOutcome]'s.
     */
    override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome {
        if (!MimirLaunchTargetStopPolicy.isAllowed(packageName)) {
            Log.w("MimirPrivilege", "vendor resolveTask declined package=$packageName reason=not_allowed")
            return VendorTaskOutcome.Unavailable("unavailable")
        }
        if (displayId < 0) {
            Log.w("MimirPrivilege", "vendor resolveTask declined package=$packageName reason=invalid_display")
            return VendorTaskOutcome.Unavailable("unavailable")
        }
        if (!readyProfileExists()) {
            Log.w(
                "MimirPrivilege",
                "vendor resolveTask declined package=$packageName reason=authority_unavailable",
            )
            return VendorTaskOutcome.Unavailable("unavailable")
        }
        return synchronized(transactionLock) {
            val outcome = transport.resolveTaskOutcome(packageName, displayId)
            Log.i(
                "MimirPrivilege",
                "vendor resolveTaskOutcome package=$packageName display=$displayId outcome=$outcome",
            )
            outcome
        }
    }

    /**
     * Process presence for one package, in one read-only transaction.
     *
     * The same two doors as the stop: [MimirLaunchTargetStopPolicy] decides which packages may be asked
     * about, and the live capability probe decides whether this route answers at all. Neither refusal
     * performs a transaction, and each answers UNKNOWN -- as does a missing reply -- so nothing here can
     * turn "could not ask" into "not running". Nothing is stopped and nothing is added to the route.
     */
    override fun packageLiveness(packageName: String): PrivilegedPackageLiveness {
        if (!MimirLaunchTargetStopPolicy.isAllowed(packageName)) {
            Log.w("MimirPrivilege", "vendor processPresence declined package=$packageName reason=not_allowed")
            return PrivilegedPackageLiveness.UNKNOWN
        }
        if (!readyProfileExists()) {
            Log.w(
                "MimirPrivilege",
                "vendor processPresence declined package=$packageName reason=authority_unavailable",
            )
            return PrivilegedPackageLiveness.UNKNOWN
        }
        return synchronized(transactionLock) {
            val reply = transport.processPresenceReply(packageName)
            val liveness = parseVendorProcessPresence(reply)
            Log.i(
                "MimirPrivilege",
                "vendor processPresence package=$packageName " +
                    "reply=${reply?.take(40)?.replace('\n', '|')?.replace('\r', ' ') ?: "none"} " +
                    "liveness=$liveness",
            )
            liveness
        }
    }

    /** Read-only and advisory: the same live-posture door as every other op, Unknown (launch as before) behind it. */
    override fun listRetroArchCores(userId: Int, packageName: String): RetroArchCoreListing {
        if (!readyProfileExists()) return RetroArchCoreListing.Unknown
        return synchronized(transactionLock) {
            RetroArchCoreListing.parse(transport.listRetroArchCoresReply(userId, packageName))
        }
    }

    override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus = posture().status

    override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean =
        requestAccess(expectedStatus)

    override fun requestBuiltInSystemRecentsConsent(
        expectedStatus: PrivilegedUtilityStatus,
    ): Boolean = requestAccess(expectedStatus)

    override fun openSystemRecents(): Boolean {
        if (!readyProfileExists()) return false
        return synchronized(transactionLock) {
            when (transport.openSystemRecents()) {
                VendorTransportMutation.APPLIED -> true
                VendorTransportMutation.TIMED_OUT -> {
                    poisoned = true
                    false
                }
                VendorTransportMutation.FAILED -> false
            }
        }
    }

    /**
     * W2f: the keyboard-default operations. Gated on [readyProfileExists] like every mutation here, run under
     * [transactionLock], and decided entirely by [MimirKeyboardDefaultOps], whose verdicts need the readbacks.
     * The IME id is recomputed from [context]; nothing a caller supplies reaches the shell.
     */
    override fun setMimirKeyboardDefault(shouldContinue: () -> Boolean): KeyboardDefaultResult {
        if (!readyProfileExists()) return KeyboardDefaultResult.UNAVAILABLE
        return synchronized(transactionLock) {
            if (poisoned || !readyProfileExists()) return@synchronized KeyboardDefaultResult.UNAVAILABLE
            MimirKeyboardDefaultOps.set(
                transport,
                keyboardImeId(),
                onTimedOut = { poisoned = true },
                // Per mutation: attest (blocking) -> the caller's cancel check (the player chose another keyboard)
                // -> send, with nothing blocking between the check and the send.
                authorize = { freshKeyboardAuthority() && shouldContinue() },
            )
        }.also { Log.i("MimirPrivilege", "vendor keyboardDefault set result=$it") }
    }

    /**
     *  a keyboard MUTATION never rides on the cached attestation. [liveProfile] normally reuses
     * the transport's cached attestation for as long as the same Binder lives, so a pservice whose capabilities
     * changed on that same Binder would still read READY. Before each of enable, select and reset the cache is
     * dropped and the live service is attested again; anything but an admitted profile refuses the mutation.
     * Called inside [transactionLock].
     */
    private fun freshKeyboardAuthority(): Boolean {
        transport.forgetAttestation()
        return !poisoned && readyProfileExists()
    }

    override fun mimirKeyboardIsDefault(): Boolean? {
        if (!readyProfileExists()) return null
        return synchronized(transactionLock) {
            if (poisoned) return@synchronized null
            MimirKeyboardDefaultOps.isDefault(transport, keyboardImeId())
        }
    }

    override fun resetKeyboardDefault(): KeyboardDefaultResult = turnMimirKeyboardOff(fallbackToReset = false)

    override fun revertKeyboardDefaultForSafety(): KeyboardDefaultResult = turnMimirKeyboardOff(fallbackToReset = true)

    private fun turnMimirKeyboardOff(fallbackToReset: Boolean): KeyboardDefaultResult {
        if (!readyProfileExists()) return KeyboardDefaultResult.UNAVAILABLE
        return synchronized(transactionLock) {
            if (poisoned || !readyProfileExists()) return@synchronized KeyboardDefaultResult.UNAVAILABLE
            MimirKeyboardDefaultOps.reset(
                transport,
                keyboardImeId(),
                onTimedOut = { poisoned = true },
                authorize = ::freshKeyboardAuthority,
                fallbackToReset = fallbackToReset,
                onNote = { Log.i("MimirPrivilege", it) },
            )
        }.also { Log.i("MimirPrivilege", "vendor keyboardDefault reset result=$it safety=$fallbackToReset") }
    }

    /**
     * Second stage of the screen idle -- see [VendorPrivilegedTransport.enforceDeviceSleep].
     *
     * Gated on [readyProfileExists] exactly like its sibling above, so enforcement happens only where
     * the certified vendor privilege is genuinely live. Everywhere else this returns false and the
     * caller releases keep-screen-on and lets the platform's own timeout take the screen -- which is
     * the right behaviour on a chassis whose wake path has not been confirmed.
     */
    override fun enforceDeviceSleep(): Boolean {
        if (!readyProfileExists()) return false
        return synchronized(transactionLock) {
            when (transport.enforceDeviceSleep()) {
                VendorTransportMutation.APPLIED -> true
                VendorTransportMutation.TIMED_OUT -> {
                    poisoned = true
                    false
                }
                VendorTransportMutation.FAILED -> false
            }
        }
    }

    override fun close() {
        poisoned = true
        synchronized(hudLock) {
            closed = true
            hudTelemetry.invalidate()
            emulatorHelperFiles?.let { files -> renderFpsHelperStager?.clear(files) }
            emulatorRenderFpsSession.invalidate()
            hudTransport?.takeUnless { it === transport }?.close()
            hudTransport = null
            hudProfile = null
            hudGeneration++
        }
        synchronized(transactionLock) {
            transport.close()
        }
    }

    private fun liveProfile(
        deadlineElapsedRealtimeMs: Long? = null,
    ): CertifiedVendorProfile? = synchronized(transactionLock) {
        // Admission may perform the first PServer attestation. Keep it on the same one-writer
        // boundary as mutations so simultaneous startup posture reads cannot overfill the bounded
        // Binder caller, fail a probe, and poison the process-owned transport.
        val evidence = evidenceReader?.invoke()
            ?: currentCertifiedVendorEvidence(
                context = context,
                identity = identity,
                transport = transport,
                attestationDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
            )
        CertifiedVendorAdmissionPolicy.admit(evidence).also { admitted ->
            if (admitted != null) lastAdmittedProfile = admitted
            logAdmissionChange(admitted)
        }
    }

    /**
     * One log line per *change* of admitted identity, so a steady device is silent.
     *
     * Since the route is re-attested on every posture read and nothing is persisted, the log is the
     * only place a firmware update or a swapped `pservice` becomes visible. Keyed on profile id plus
     * measured digest -- the two things that used to force a fresh consent -- so the transition the
     * 2026-09-11 ruling made invisible to the user stays visible to whoever reads a bug report.
     */
    private fun logAdmissionChange(admitted: CertifiedVendorProfile?) {
        if (admitted == null) {
            if (lastAdmittedProfile != null && !admissionLossLogged) {
                admissionLossLogged = true
                lastLoggedAdmission = null
                Log.w("MimirPrivilege", "vendor route lost: structural admission failed")
            }
            return
        }
        admissionLossLogged = false
        val admissionIdentity = "${admitted.profileId}|${admitted.attestedExecutableSha256}"
        if (admissionIdentity == lastLoggedAdmission) return
        lastLoggedAdmission = admissionIdentity
        Log.i(
            "MimirPrivilege",
            "vendor route admitted profile=${admitted.profileId} " +
                "executableSha256=${admitted.attestedExecutableSha256} " +
                "fingerprint=${admitted.buildFingerprint}",
        )
    }

    private fun failedKnownAuthorityPosture(): PrivilegedUtilityPosture {
        val knownAuthorityFailed = lastAdmittedProfile != null &&
            (poisoned || !transport.isAlive())
        return if (knownAuthorityFailed) {
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.CONNECTION_FAILED,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
            )
        } else {
            PrivilegedUtilityPosture.Unsupported
        }
    }

    /** The [posture] READY test, for callers that only need the boolean. Same inputs, same ruling. */
    private fun readyProfileExists(): Boolean {
        if (poisoned) return false
        if (liveProfile() == null) return false
        return transport.isAlive()
    }

    private fun readHudSnapshot(deadlineElapsedRealtimeMs: Long): VendorHudRawSnapshot? {
        if (nowElapsedRealtimeMs() >= deadlineElapsedRealtimeMs) return null
        val hud = activeHudTransportLocked()
        val snapshot = hud.readHudTelemetry(deadlineElapsedRealtimeMs) ?: return null
        if (nowElapsedRealtimeMs() >= deadlineElapsedRealtimeMs || !hud.isAlive()) return null
        return snapshot
    }

    /**
     * HUD admission follows the control route's ruling: structural admission plus a live binding.
     *
     * The consent conjuncts that used to sit beside every liveness check here were removed with the
     * control gate on 2026-09-11, and had to be. The only writer of that record was `requestAccess`
     * on a PERMISSION_REQUIRED posture; with that posture gone, nothing can ever grant it again, so
     * leaving the checks would have made vendor telemetry permanently unreachable on every device
     * while control posture read READY.
     */
    private fun ensureHudTelemetryReadyLocked(deadlineElapsedRealtimeMs: Long): Boolean {
        if (closed || nowElapsedRealtimeMs() >= deadlineElapsedRealtimeMs) return false
        val hud = activeHudTransportLocked()
        val profile = hudProfile
        if (hud === transport) {
            if (transport.isBindingInvalidatedForHud()) {
                return replaceHudTransportLocked(deadlineElapsedRealtimeMs)
            }
            if (poisoned) return false
            val originalProfile = synchronized(transactionLock) {
                val admitted = liveProfile(deadlineElapsedRealtimeMs) ?: return@synchronized null
                admitted.takeIf { transport.isAlive() }
            }
            if (originalProfile != null) {
                hudProfile = originalProfile
                return nowElapsedRealtimeMs() < deadlineElapsedRealtimeMs
            }
        } else if (profile != null && hud.isAlive() && !hud.isBindingInvalidatedForHud()) {
            return nowElapsedRealtimeMs() < deadlineElapsedRealtimeMs
        }

        if (hud.isBindingInvalidatedForHud()) {
            return replaceHudTransportLocked(deadlineElapsedRealtimeMs)
        }
        if (hud !== transport && profile == null && !hud.hasBoundBinderForHud()) {
            val admitted = admitHudTransportLocked(hud, deadlineElapsedRealtimeMs)
            if (admitted == null || !hud.isAlive()) return false
            return nowElapsedRealtimeMs() < deadlineElapsedRealtimeMs
        }
        return false
    }

    private fun replaceHudTransportLocked(deadlineElapsedRealtimeMs: Long): Boolean {
        if (nowElapsedRealtimeMs() >= deadlineElapsedRealtimeMs) return false
        val candidate = runCatching { hudTransportFactory() }.getOrNull() ?: return false
        hudTelemetry.invalidate()
        val previous = hudTransport
        hudTransport = candidate
        hudProfile = null
        hudGeneration++
        previous?.takeUnless { it === transport || it === candidate }?.close()
        val admitted = admitHudTransportLocked(candidate, deadlineElapsedRealtimeMs)
        if (admitted == null || !candidate.isAlive()) return false
        return nowElapsedRealtimeMs() < deadlineElapsedRealtimeMs
    }

    private fun admitHudTransportLocked(
        candidate: CertifiedVendorTransport,
        deadlineElapsedRealtimeMs: Long,
    ): CertifiedVendorProfile? {
        if (nowElapsedRealtimeMs() >= deadlineElapsedRealtimeMs) return null
        val admitted = runCatching {
            val evidence = replacementEvidenceReader?.invoke(candidate, deadlineElapsedRealtimeMs)
                ?: currentCertifiedVendorEvidence(
                    context = context,
                    identity = identity,
                    transport = candidate,
                    attestationDeadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                )
            CertifiedVendorAdmissionPolicy.admit(evidence)
        }.getOrNull() ?: return null
        if (nowElapsedRealtimeMs() >= deadlineElapsedRealtimeMs ||
            !candidate.isAlive() ||
            candidate.isBindingInvalidatedForHud()
        ) {
            return null
        }
        hudProfile = admitted
        return admitted
    }

    private fun activeHudTransportLocked(): CertifiedVendorTransport = hudTransport ?: transport

    private companion object {
        const val HUD_READ_DEADLINE_MS = 1_700L

        /** Panel readback tolerance for the vendor display transaction. */
        const val DisplayReadbackTolerance = 0.011f
    }

    private fun readBackMatches(key: String, expected: String): Boolean =
        (transport.readSystemSetting(key) as? VendorTransportRead.Value)
            ?.value
            ?.let { CertifiedVendorValuePolicy.normalize(key, it) } == expected

    private fun restoreAndVerify(key: String, snapshot: String?): Boolean {
        val restore = snapshot?.let { transport.writeSystemSetting(key, it) }
            ?: transport.deleteSystemSetting(key)
        if (restore != VendorTransportMutation.APPLIED) return false
        val restored = transport.readSystemSetting(key) as? VendorTransportRead.Value ?: return false
        return restored.value == snapshot
    }
}

/**
 * A second screen the vendor display route may set: present, valid, not the main screen, and a presentation
 * display -- what [com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySelector] requires before it
 * offers a display at all. The vendor transport asks nothing on the other side, so this process asks instead.
 */
private fun isPresentationSecondaryDisplay(context: Context, displayId: Int): Boolean {
    if (displayId == Display.DEFAULT_DISPLAY) return false
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return false
    return display.isValid && display.flags and Display.FLAG_PRESENTATION != 0
}

internal class AndroidCertifiedVendorConsentStore(context: Context) : CertifiedVendorConsentStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun isGranted(consentKey: String): Boolean =
        preferences.getBoolean(consentKey, false)

    override fun grant(consentKey: String) {
        preferences.edit().putBoolean(consentKey, true).apply()
    }

    private companion object {
        const val PreferencesName = "mimir_certified_vendor_privilege"
    }
}

internal fun currentCertifiedVendorEvidence(
    context: Context,
    identity: BrightnessDeviceIdentity,
    transport: CertifiedVendorTransport,
    attestationDeadlineElapsedRealtimeMs: Long? = null,
): CertifiedVendorEvidence {
    val packageName = identity.vendorSettingsPackage
    val application = packageName?.let { candidate ->
        runCatching { context.packageManager.getApplicationInfo(candidate, 0) }.getOrNull()
    }
    val platformSignatureMatch = packageName != null && runCatching {
        context.packageManager.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH
    }.getOrDefault(false)
    val signerSha256 = packageName
        ?.let { vendorPackageSignerSha256(context.packageManager, it) }
        .orEmpty()
    val preliminary = CertifiedVendorEvidence(
        manufacturer = identity.manufacturer,
        brand = identity.brand,
        model = identity.model,
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else "",
        buildFingerprint = Build.FINGERPRINT.orEmpty(),
        vendorSettingsPackage = packageName,
        vendorPackageEnabled = application?.enabled == true,
        vendorPackageIsSystem = application
            ?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: false,
        vendorPackageMatchesPlatformSignature = platformSignatureMatch,
        vendorPackageSignerSha256 = signerSha256,
        serviceAttestation = null,
    )
    // READ THIS BEFORE ADDING A CONDITION HERE. This gate has now been the same defect twice, and
    // the shape is easy to reintroduce a third time because each version looks prudent on its own.
    //
    //   v1 -- probe only if a registry contract already matched this exact firmware. Self-
    //         fulfilling: no probe without a contract, no contract without evidence, no evidence
    //         without a probe. An unlisted device could never qualify.
    //   v2 -- probe only if the SoC resolved to a known family. Same shape one level up: no probe
    //         without a known family, no attestation without a probe, no access without an
    //         attestation. Fixed 2026-08-22 so pservice access alone
    //         earns the sealed command set.
    //
    // The invariant that keeps it fixed: **this predicate may only test facts that do not depend on
    // having already probed.** Vendor-package posture qualifies -- PackageManager answers it
    // independently. A SoC allowlist does not, because the allowlist is exactly what the probe
    // exists to let a new device escape.
    //
    // Probing is safe to widen: every Probe* operation is side-effect-free by construction (see
    // VendorPServerOperation), and on a device with no such service ServiceManager simply returns
    // null, so the cost on a non-handheld is one failed reflective lookup.
    val mayAttest = CertifiedVendorAdmissionPolicy.vendorPackagePostureAdmissible(preliminary)
    return preliminary.copy(
        serviceAttestation = if (mayAttest) {
            if (attestationDeadlineElapsedRealtimeMs != null) {
                transport.attest(attestationDeadlineElapsedRealtimeMs)
            } else {
                transport.attest()
            }
        } else null,
    )
}

private fun vendorPackageSignerSha256(
    packageManager: PackageManager,
    packageName: String,
): Set<String> = runCatching {
    @Suppress("DEPRECATION")
    val packageInfo = packageManager.getPackageInfo(
        packageName,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )
    packageInfo.signingInfo
        ?.apkContentsSigners
        .orEmpty()
        .mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
        }
}.getOrDefault(emptySet())

/**
 * Retroid/AYN's observed `PServerBinder` ABI. All rendered text originates from sealed operations
 * below; no generic execution method leaves this class.
 */
internal class AndroidPServerTransport(
    private val binderResolver: () -> IBinder? = ::resolvePServerBinder,
    private val hudScriptStager: VendorHudScriptStager? = null,
) : CertifiedVendorTransport {
    private val caller = BoundedVendorBinderCaller()
    private val hudCaller = BoundedVendorBinderCaller(
        deadlineMs = HudCallerDeadlineMs,
        threadName = "mimir-certified-vendor-hud",
    )
    private val binderLock = Any()
    private val deathRecipient = IBinder.DeathRecipient {
        if (boundBinder != null) bindingInvalidatedForHud = true
        poisoned = true
        cachedAttestation = null
    }

    @Volatile
    private var poisoned = false

    @Volatile
    private var boundBinder: IBinder? = null

    @Volatile
    private var cachedAttestation: CertifiedVendorServiceAttestation? = null

    @Volatile
    private var bindingInvalidatedForHud = false

    @Volatile
    private var stagedHudScript: java.io.File? = null

    private val lastHudDiagnostic = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The last display reply written to the log; see [logDisplayReply]. */
    private val lastDisplayReplyLogged = java.util.concurrent.atomic.AtomicReference<String?>(null)

    constructor(
        context: Context,
        binderResolver: () -> IBinder? = ::resolvePServerBinder,
        hudScriptStager: VendorHudScriptStager = VendorHudScriptStager(context.applicationContext.filesDir),
    ) : this(binderResolver, hudScriptStager)

    override fun isAlive(): Boolean = validatedBoundBinder() != null

    override fun hasBoundBinderForHud(): Boolean = boundBinder != null

    override fun isBindingInvalidatedForHud(): Boolean {
        if (bindingInvalidatedForHud) return true
        val existing = boundBinder ?: return false
        val existingAlive = runCatching { existing.pingBinder() }.getOrDefault(false)
        val current = runCatching { binderResolver() }.getOrNull()
        val currentAlive = current?.let { runCatching { it.pingBinder() }.getOrDefault(false) }
            ?: false
        if (!existingAlive || (currentAlive && current != null && !sameBinder(existing, current))) {
            bindingInvalidatedForHud = true
        }
        return bindingInvalidatedForHud
    }

    override fun attest(): CertifiedVendorServiceAttestation? = attestInternal(null)

    override fun attest(deadlineElapsedRealtimeMs: Long): CertifiedVendorServiceAttestation? =
        when (val result = hudCaller.callValueUntil(deadlineElapsedRealtimeMs) {
            attestInternal(deadlineElapsedRealtimeMs)
        }) {
            is BoundedValueCall.Completed -> {
                val attestation = result.value
                if (attestation != null &&
                    SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs
                ) {
                    poison()
                    null
                } else {
                    attestation
                }
            }
            BoundedValueCall.Failed,
            BoundedValueCall.TimedOut,
            -> {
                poison()
                null
            }
        }

    private fun attestInternal(
        deadlineElapsedRealtimeMs: Long?,
    ): CertifiedVendorServiceAttestation? {
        if (poisoned) return null
        if (deadlineElapsedRealtimeMs != null &&
            SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs
        ) return null
        cachedAttestation?.let { cached ->
            if (validatedBoundBinder() != null) return cached
            return null
        }
        val binder = bindForAttestation() ?: return null
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull().orEmpty()
        val protocol = probe(binder, VendorPServerOperation.ProbeProtocol, deadlineElapsedRealtimeMs)
        val uid = probe(binder, VendorPServerOperation.ProbeUid, deadlineElapsedRealtimeMs)
        val gid = probe(binder, VendorPServerOperation.ProbeGid, deadlineElapsedRealtimeMs)
        val domain = probe(binder, VendorPServerOperation.ProbeSelinuxDomain, deadlineElapsedRealtimeMs)
        val executable = probe(binder, VendorPServerOperation.ProbeExecutable, deadlineElapsedRealtimeMs)
        val executableSha = probe(binder, VendorPServerOperation.ProbeExecutableSha256, deadlineElapsedRealtimeMs)
            ?.substringBefore(' ')
            ?.lowercase()
        // The measured marker is checked HERE and only here, because it is the one axis that has no
        // field on CertifiedVendorServiceAttestation to carry it: `protocolId` is Mimir's own
        // constant name for the ABI, not the bytes the service returned. An attestation object
        // therefore only ever exists if this echo already succeeded, which is what lets the
        // admission policy treat the object itself as ABI-proven.
        if (protocol != VendorPServerOperation.ProtocolMarker) {
            poison()
            return null
        }
        // Every remaining axis is judged by the one shared predicate, so the transport and the
        // admission policy can never drift into two definitions of a genuine pservice.
        val candidate = CertifiedVendorServiceAttestation(
            serviceName = CertifiedVendorAdmissionPolicy.ServiceName,
            binderDescriptor = descriptor,
            protocolId = CertifiedVendorAdmissionPolicy.ProtocolId,
            uid = uid.orEmpty(),
            gid = gid.orEmpty(),
            selinuxDomain = domain.orEmpty(),
            executablePath = executable.orEmpty(),
            executableSha256 = executableSha.orEmpty(),
        )
        if (!CertifiedVendorStructuralContract.isStructurallyValid(candidate)) {
            poison()
            return null
        }
        if (deadlineElapsedRealtimeMs != null &&
            SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs
        ) return null
        if (validatedBoundBinder()?.let { sameBinder(it, binder) } != true) return null
        return candidate.also { attestation ->
            cachedAttestation = attestation
        }
    }

    override fun readHudTelemetry(deadlineElapsedRealtimeMs: Long): VendorHudRawSnapshot? {
        if (poisoned) {
            reportHudDiagnostic("failed")
            return null
        }
        if (deadlineElapsedRealtimeMs <= SystemClock.elapsedRealtime()) {
            reportHudDiagnostic("timed-out")
            return null
        }
        if (hudScriptStager == null) {
            reportHudDiagnostic("failed")
            return null
        }
        val binder = validatedBoundBinder() ?: run {
            reportHudDiagnostic("failed")
            return null
        }
        return when (val result = executeHud(binder, deadlineElapsedRealtimeMs)) {
            VendorBinderCall.TimedOut -> {
                reportHudDiagnostic("timed-out")
                null
            }
            VendorBinderCall.Failed -> {
                reportHudDiagnostic("failed")
                null
            }
            is VendorBinderCall.Completed -> {
                val decoded = decodeVendorHudWire(result.output)
                val raw = decoded?.let(::parseVendorHudRawSnapshot)
                reportCompletedHudDiagnostic(result.output, decoded, raw)
                raw
            }
        }
    }

    override fun startEmulatorRenderFps(files: EmulatorRenderFpsHelperFiles): Boolean {
        val operation = VendorPServerOperation.StartEmulatorRenderFps.create(files) ?: return false
        return executeMutation(operation) == VendorTransportMutation.APPLIED
    }

    override fun startEmulatorRenderFps(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): Boolean {
        if (deadlineElapsedRealtimeMs <= SystemClock.elapsedRealtime()) return false
        val operation = VendorPServerOperation.StartEmulatorRenderFps.create(files) ?: return false
        val binder = validatedBoundBinder() ?: return false
        return when (val result = executeUntil(binder, operation.render(), deadlineElapsedRealtimeMs)) {
            is VendorBinderCall.Completed ->
                result.output.trim() == VendorPServerOperation.SuccessMarker
            VendorBinderCall.Failed, VendorBinderCall.TimedOut -> false
        }
    }

    override fun readEmulatorRenderFpsRecord(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): String? = when (val result = readEmulatorRenderFpsRecordResult(files, deadlineElapsedRealtimeMs)) {
        is EmulatorRenderFpsRecordRead.Encoded -> result.value
        EmulatorRenderFpsRecordRead.Missing,
        EmulatorRenderFpsRecordRead.Failed,
        -> null
    }

    override fun readEmulatorRenderFpsRecordResult(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): EmulatorRenderFpsRecordRead {
        val operation = VendorPServerOperation.ReadEmulatorRenderFpsRecord.create(files)
            ?: return EmulatorRenderFpsRecordRead.Failed
        val binder = validatedBoundBinder() ?: return EmulatorRenderFpsRecordRead.Failed
        return when (val result = executeUntil(
            binder = binder,
            rendered = operation.render(),
            deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
        )) {
            is VendorBinderCall.Completed -> when (val output = result.output.trim()) {
                EMULATOR_RENDER_FPS_MISSING_RECORD_SENTINEL -> EmulatorRenderFpsRecordRead.Missing
                "" -> EmulatorRenderFpsRecordRead.Failed
                else -> output.takeIf {
                    it.length <= EMULATOR_RENDER_FPS_MAX_WIRE_BASE64_CHARS &&
                        it.all { character -> character.code < 128 }
                }?.let(EmulatorRenderFpsRecordRead::Encoded)
                    ?: EmulatorRenderFpsRecordRead.Failed
            }
            VendorBinderCall.Failed, VendorBinderCall.TimedOut -> EmulatorRenderFpsRecordRead.Failed
        }
    }

    override fun resolveEmulatorRenderFpsTarget(
        files: EmulatorRenderFpsHelperFiles,
        packageName: String,
        deadlineElapsedRealtimeMs: Long,
    ): EmulatorRenderFpsTargetIdentity? {
        val operation = VendorPServerOperation.ResolveEmulatorRenderFpsTarget.create(files, packageName) ?: return null
        val binder = validatedBoundBinder() ?: return null
        val output = when (val result = executeUntil(binder, operation.render(), deadlineElapsedRealtimeMs)) {
            is VendorBinderCall.Completed -> result.output.trim()
            VendorBinderCall.Failed, VendorBinderCall.TimedOut -> return null
        }
        val values = output.split(Regex("\\s+")).takeIf { it.size == 3 } ?: return null
        val pid = values[0].toLongOrNull()?.takeIf { it in 1L..Int.MAX_VALUE }?.toInt() ?: return null
        val uid = values[1].toLongOrNull()?.takeIf { it in 0L..4_294_967_295L } ?: return null
        val startTicks = values[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return EmulatorRenderFpsTargetIdentity(packageName, uid, pid, startTicks).takeIf { it.isUsable() }
    }

    private fun reportCompletedHudDiagnostic(
        wire: String,
        decoded: String?,
        raw: VendorHudRawSnapshot?,
    ) {
        if (raw != null) {
            reportHudDiagnostic(
                if (raw.gameNativeSessionFileName == null) {
                    "completed-valid-no-frame"
                } else {
                    "completed-valid-frame"
                },
            )
            return
        }
        if (decoded == null) {
            val byteLength = wire.toByteArray(StandardCharsets.UTF_8).size
            val loggedByteLength = byteLength.coerceAtMost(HudDiagnosticMaxLoggedBytes)
            val details = buildString {
                append("completed-wire-invalid bytes=")
                append(loggedByteLength)
                if (byteLength > HudDiagnosticMaxLoggedBytes) append(" bytesCapped=true")
                append(" decode=invalid")
            }
            reportHudDiagnostic("completed-wire-invalid", details)
            return
        }
        val decodedByteLength = decoded.toByteArray(StandardCharsets.UTF_8).size
        reportHudDiagnostic(
            "completed-protocol-invalid",
            "completed-protocol-invalid decodedBytes=$decodedByteLength decode=valid protocol=invalid",
        )
    }

    private fun reportHudDiagnostic(edge: String, message: String = edge) {
        if (lastHudDiagnostic.getAndSet(edge) == edge) return
        Log.i(
            VendorHudDiagnosticTag,
            "route=vendor result=$message",
        )
    }

    override fun readSystemSetting(key: String): VendorTransportRead {
        val operation = VendorPServerOperation.readSetting(key) ?: return VendorTransportRead.Failed
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed -> {
                val value = result.output.trim()
                when {
                    value.isEmpty() -> VendorTransportRead.Failed
                    value == "null" -> VendorTransportRead.Value(null)
                    else -> VendorTransportRead.Value(value)
                }
            }
            VendorBinderCall.Failed, VendorBinderCall.TimedOut -> VendorTransportRead.Failed
        }
    }

    override fun writeSystemSetting(
        key: String,
        normalizedValue: String,
    ): VendorTransportMutation =
        VendorPServerOperation.writeSetting(key, normalizedValue)
            ?.let(::executeMutation)
            ?: VendorTransportMutation.FAILED

    override fun deleteSystemSetting(key: String): VendorTransportMutation =
        VendorPServerOperation.deleteSetting(key)
            ?.let(::executeMutation)
            ?: VendorTransportMutation.FAILED

    override fun readDisplayBrightness(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
    ): VendorDisplayRead {
        val operation = VendorPServerOperation.ReadDisplayBrightness.create(transactions, displayId)
            ?: return VendorDisplayRead.Failed
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed -> VendorServiceCallReply.brightness(result.output).also { read ->
                if (read !is VendorDisplayRead.Value) logDisplayReply("read", displayId, read, result.output)
            }
            VendorBinderCall.Failed, VendorBinderCall.TimedOut -> VendorDisplayRead.Failed
        }
    }

    override fun writeDisplayBrightness(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
        fraction: Float,
    ): VendorTransportMutation {
        val operation = VendorPServerOperation.WriteDisplayBrightness.create(transactions, displayId, fraction)
            ?: return VendorTransportMutation.FAILED
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed ->
                if (VendorServiceCallReply.succeeded(result.output)) {
                    VendorTransportMutation.APPLIED
                } else {
                    VendorTransportMutation.FAILED.also { logDisplayReply("write", displayId, it, result.output) }
                }
            VendorBinderCall.TimedOut -> VendorTransportMutation.TIMED_OUT
            VendorBinderCall.Failed -> VendorTransportMutation.FAILED
        }
    }

    /**
     * Run evidence for a display call that did not come back clean: what `service` printed, as
     * [VendorServiceCallReply.forLog] cuts it. A slider held against the same reply logs it once.
     */
    private fun logDisplayReply(call: String, displayId: Int, outcome: Any, output: String) {
        val line = "vendor display $call display=$displayId outcome=$outcome " +
            "reply=\"${VendorServiceCallReply.forLog(output)}\""
        if (lastDisplayReplyLogged.getAndSet(line) == line) return
        Log.w("MimirPrivilege", line)
    }

    override fun openSystemRecents(): VendorTransportMutation =
        executeMutation(VendorPServerOperation.OpenSystemRecents)

    override fun enforceDeviceSleep(): VendorTransportMutation =
        executeMutation(VendorPServerOperation.EnforceDeviceSleep)

    override fun enableKeyboard(imeId: String): VendorTransportMutation =
        VendorPServerOperation.enableKeyboard(imeId)?.let(::executeMutation) ?: VendorTransportMutation.FAILED

    override fun selectKeyboard(imeId: String): VendorTransportMutation =
        VendorPServerOperation.selectKeyboard(imeId)?.let(::executeMutation) ?: VendorTransportMutation.FAILED

    override fun keyboardIsEnabled(imeId: String): VendorTransportMutation =
        VendorPServerOperation.keyboardListed(imeId)?.let(::executeMutation) ?: VendorTransportMutation.FAILED

    override fun disableKeyboard(imeId: String): VendorTransportMutation =
        VendorPServerOperation.disableKeyboard(imeId)?.let(::executeMutation) ?: VendorTransportMutation.FAILED

    /** An empty reply is the real answer "no other keyboard is enabled"; only a failed call is unreadable. */
    override fun readOtherKeyboard(imeId: String): VendorTransportRead {
        val operation = VendorPServerOperation.otherKeyboardListed(imeId) ?: return VendorTransportRead.Failed
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed -> VendorTransportRead.Value(result.output.trim().ifEmpty { null })
            VendorBinderCall.Failed, VendorBinderCall.TimedOut -> VendorTransportRead.Failed
        }
    }

    /** "Sent", not "worked": the conditional reset prints nothing; the readback decides. */
    override fun resetKeyboards(imeId: String): VendorTransportMutation {
        val operation = VendorPServerOperation.resetKeyboardIfDefault(imeId) ?: return VendorTransportMutation.FAILED
        return when (execute(operation)) {
            is VendorBinderCall.Completed -> VendorTransportMutation.APPLIED
            VendorBinderCall.TimedOut -> VendorTransportMutation.TIMED_OUT
            VendorBinderCall.Failed -> VendorTransportMutation.FAILED
        }
    }

    override fun forgetAttestation() {
        cachedAttestation = null
    }

    override fun readDefaultKeyboard(): VendorTransportRead =
        when (val result = execute(VendorPServerOperation.ReadDefaultKeyboard)) {
            is VendorBinderCall.Completed -> {
                val value = result.output.trim()
                when {
                    value.isEmpty() -> VendorTransportRead.Failed
                    value == "null" -> VendorTransportRead.Value(null)
                    else -> VendorTransportRead.Value(value)
                }
            }
            VendorBinderCall.Failed,
            VendorBinderCall.TimedOut,
            -> VendorTransportRead.Failed
        }

    /**
     * Kept for callers that only need "was the stop accepted". It runs the SAME verified command, so
     * there is exactly one stop shape on the wire; a verified survivor reports FAILED rather than
     * APPLIED, because on this firmware an accepted `force-stop` can leave the app running.
     */
    override fun forceStopAllowedPackage(packageName: String): VendorTransportMutation =
        when (forceStopAllowedPackageVerified(packageName)) {
            VendorTransportStop.STOPPED,
            VendorTransportStop.ACCEPTED_UNVERIFIED,
            -> VendorTransportMutation.APPLIED
            VendorTransportStop.STILL_ALIVE,
            VendorTransportStop.FAILED,
            -> VendorTransportMutation.FAILED
            VendorTransportStop.TIMED_OUT -> VendorTransportMutation.TIMED_OUT
        }

    override fun forceStopAllowedPackageVerified(packageName: String): VendorTransportStop {
        val operation = VendorPServerOperation.forceStop(packageName)
            ?: return VendorTransportStop.FAILED
        return when (val result = execute(operation)) {
            // The privileged shell echoes the marker only when no process survived the stop, so the
            // marker IS the liveness evidence. A completed transaction without it means the command
            // ran and something is still alive -- which is exactly the whitelisted-package case.
            is VendorBinderCall.Completed -> completedVendorStopOutcome(result.output)
            VendorBinderCall.TimedOut -> VendorTransportStop.TIMED_OUT
            VendorBinderCall.Failed -> VendorTransportStop.FAILED
        }
    }

    /**
     * The resolver's one-line reply, verbatim, or null when no reply arrived.
     *
     * The channel returns only a command's first output line (Thor, build `23ca3300`), so the reply
     * is the answer itself rather than a table to parse. Nothing is trimmed or interpreted here --
     * [parseVendorResolvedTaskId] owns the strict read, and the gateway logs what came back.
     */
    override fun resolveTaskReply(packageName: String, displayId: Int): String? {
        val operation = VendorPServerOperation.resolveTask(packageName, displayId) ?: return null
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed -> result.output
            VendorBinderCall.TimedOut,
            VendorBinderCall.Failed,
            -> null
        }
    }

    /**
     * R6-P0-02 review fix (BLOCKING): the tri-state answer this transaction actually proved, computed
     * from the SAME [VendorBinderCall] [resolveTaskReply] collapses to null for both TimedOut and Failed.
     *
     * R6-P0-02 round-3 fix (BLOCKING): a Completed transaction with empty output is NOT proof of "no
     * task" by itself -- `am stack list` can fail silently (permission/SELinux denial, a missing binary,
     * a broken first pipe stage) and a Completed transaction still comes back with nothing on it, which
     * is indistinguishable at this layer from a genuine miss.
     *
     * R6-P0-02 round-5 fix (BLOCKING): this now composes [VendorPServerOperation.ResolveTaskOutcome], not
     * [VendorPServerOperation.ResolveTask] -- [resolveTaskId]/[resolveTaskReply] (every certified stop/
     * return path) go through the latter, restored byte-identical to base, so they never run the
     * marker-based rewrite below. [ResolveTaskOutcome.render] mirrors [VendorPServerOperation
     * .ProcessPresence]'s pattern: it captures `am`'s own output first, via an `if`/`then`/`fi` test (no
     * `exit`, so a persistent vendor shell is never killed), and echoes
     * [VendorPServerOperation.NoTaskMarker] only as a POSITIVE signal that `am` ran and the grep pipeline
     * genuinely matched nothing. [parseVendorTaskOutcome] reads [VendorTaskOutcome.NoTask] only from that
     * literal marker; an empty reply (the `if` test failed, or a genuinely empty first line) is
     * [VendorTaskOutcome.Unavailable] with reason `empty`, same family as a timeout or a failed
     * transaction -- never proof of absence.
     */
    override fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome {
        val operation = VendorPServerOperation.resolveTaskOutcome(packageName, displayId)
            ?: return VendorTaskOutcome.Unavailable("unavailable")
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed -> parseVendorTaskOutcome(result.output, packageName)
            VendorBinderCall.TimedOut -> VendorTaskOutcome.Unavailable("timeout")
            VendorBinderCall.Failed -> VendorTaskOutcome.Unavailable("unavailable")
        }
    }

    /**
     * The presence probe's reply, verbatim, or null when no reply arrived. A failed or timed-out
     * transaction is null, which [parseVendorProcessPresence] reads as UNKNOWN; a timeout also poisons this
     * transport through [execute], as it does for every other operation.
     */
    override fun processPresenceReply(packageName: String): String? {
        val operation = VendorPServerOperation.processPresence(packageName) ?: return null
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed -> result.output
            VendorBinderCall.TimedOut,
            VendorBinderCall.Failed,
            -> null
        }
    }

    override fun listRetroArchCoresReply(userId: Int, packageName: String): String? =
        (ListRetroArchCores.create(userId, packageName)?.let { execute(it) } as? VendorBinderCall.Completed)?.output

    override fun removeTask(taskId: Int): VendorTransportMutation {
        val operation = VendorPServerOperation.removeTask(taskId)
            ?: return VendorTransportMutation.FAILED
        return when (val result = execute(operation)) {
            // `am stack remove` is not documented to be silent on success, so the marker is matched as
            // a line rather than as the whole output. The marker is still `&&`-guarded: it can only be
            // echoed by a command that exited 0.
            is VendorBinderCall.Completed ->
                if (result.output.containsSuccessMarkerLine()) {
                    VendorTransportMutation.APPLIED
                } else {
                    VendorTransportMutation.FAILED
                }
            VendorBinderCall.TimedOut -> VendorTransportMutation.TIMED_OUT
            VendorBinderCall.Failed -> VendorTransportMutation.FAILED
        }
    }

    override fun focusTask(taskId: Int): VendorTransportMutation {
        val operation = VendorPServerOperation.focusTask(taskId)
            ?: return VendorTransportMutation.FAILED
        return when (val result = execute(operation)) {
            is VendorBinderCall.Completed ->
                if (result.output.containsSuccessMarkerLine()) {
                    VendorTransportMutation.APPLIED
                } else {
                    VendorTransportMutation.FAILED
                }
            VendorBinderCall.TimedOut -> VendorTransportMutation.TIMED_OUT
            VendorBinderCall.Failed -> VendorTransportMutation.FAILED
        }
    }

    override fun close() {
        poison()
        synchronized(binderLock) {
            boundBinder?.let { binder ->
                runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            }
            boundBinder = null
            cachedAttestation = null
        }
        caller.close()
        hudCaller.close()
    }

    private fun executeMutation(operation: VendorPServerOperation): VendorTransportMutation =
        when (val result = execute(operation)) {
            is VendorBinderCall.Completed ->
                if (result.output.trim() == VendorPServerOperation.SuccessMarker) {
                    VendorTransportMutation.APPLIED
                } else {
                    VendorTransportMutation.FAILED
                }
            VendorBinderCall.TimedOut -> VendorTransportMutation.TIMED_OUT
            VendorBinderCall.Failed -> VendorTransportMutation.FAILED
        }

    private fun execute(operation: VendorPServerOperation): VendorBinderCall {
        if (poisoned) return VendorBinderCall.Failed
        val rendered = operation.render()
        if (rendered.toByteArray(StandardCharsets.UTF_8).size > MaxCommandBytes) {
            return VendorBinderCall.Failed
        }
        val binder = validatedBoundBinder() ?: return VendorBinderCall.Failed
        return execute(binder, rendered)
    }

    private fun execute(
        binder: IBinder,
        rendered: String,
    ): VendorBinderCall {
        return execute(caller, binder, rendered).also { result ->
            if (result == VendorBinderCall.TimedOut) poison()
        }
    }

    private fun executeHud(
        binder: IBinder,
        deadlineElapsedRealtimeMs: Long,
    ): VendorBinderCall = hudCaller.callUntil(deadlineElapsedRealtimeMs) {
        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
            return@callUntil VendorBinderCall.TimedOut
        }
        val script = stagedHudScript ?: hudScriptStager?.stage()
            ?.also { stagedHudScript = it }
            ?: return@callUntil VendorBinderCall.Failed
        val rendered = vendorHudScriptCommand(script) ?: return@callUntil VendorBinderCall.Failed
        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
            return@callUntil VendorBinderCall.TimedOut
        }
        val result = transact(binder, rendered)
        if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
            VendorBinderCall.TimedOut
        } else {
            result
        }
    }

    private fun execute(
        caller: BoundedVendorBinderCaller,
        binder: IBinder,
        rendered: String,
    ): VendorBinderCall {
        return caller.call {
            transact(binder, rendered)
        }
    }

    private fun execute(
        caller: BoundedVendorBinderCaller,
        binder: IBinder,
        rendered: String,
        deadlineElapsedRealtimeMs: Long,
    ): VendorBinderCall {
        return caller.callUntil(deadlineElapsedRealtimeMs) {
            transact(binder, rendered)
        }.also { result ->
            if (result == VendorBinderCall.TimedOut) poison()
        }
    }

    private fun transact(
        binder: IBinder,
        rendered: String,
    ): VendorBinderCall {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(rendered, CaptureOutput))
            if (!binder.transact(TransactionCode, data, reply, 0)) {
                VendorBinderCall.Failed
            } else {
                val bytes = reply.createByteArray()
                if (bytes == null) {
                    VendorBinderCall.Failed
                } else {
                    VendorBinderCall.Completed(String(bytes, StandardCharsets.UTF_8))
                }
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun probe(
        binder: IBinder,
        operation: VendorPServerOperation,
        deadlineElapsedRealtimeMs: Long? = null,
    ): String? {
        val rendered = operation.render()
        if (rendered.toByteArray(StandardCharsets.UTF_8).size > MaxCommandBytes) return null
        val result = if (deadlineElapsedRealtimeMs == null) {
            execute(binder, rendered)
        } else {
            executeUntil(binder, rendered, deadlineElapsedRealtimeMs)
        }
        return (result as? VendorBinderCall.Completed)
            ?.output
            ?.trimEnd('\u0000', '\n', '\r', ' ', '\t')
    }

    private fun bindForAttestation(): IBinder? {
        val candidate = runCatching { binderResolver() }.getOrNull()
            ?.takeIf(IBinder::pingBinder)
            ?: return null
        return synchronized(binderLock) {
            val existing = boundBinder
            when {
                poisoned -> null
                existing == null -> {
                    // Retain the observed identity even if linkToDeath races or fails. HUD recovery
                    // can then distinguish this bound candidate from a transport that never found a
                    // service and can wait for a later Binder replacement without creating a loop.
                    boundBinder = candidate
                    val linked = runCatching {
                        candidate.linkToDeath(deathRecipient, 0)
                        true
                    }.getOrDefault(false)
                    if (!linked) {
                        poison()
                        null
                    } else {
                        candidate
                    }
                }
                sameBinder(existing, candidate) &&
                    runCatching { existing.pingBinder() }.getOrDefault(false) -> existing
                else -> {
                    bindingInvalidatedForHud = true
                    poison()
                    null
                }
            }
        }
    }

    private fun validatedBoundBinder(): IBinder? {
        if (poisoned) return null
        val existing = boundBinder ?: return null
        val current = runCatching { binderResolver() }.getOrNull()
        val existingAlive = runCatching { existing.pingBinder() }.getOrDefault(false)
        val currentAlive = current?.let { runCatching { it.pingBinder() }.getOrDefault(false) }
            ?: false
        if (!existingAlive || (currentAlive && current != null && !sameBinder(existing, current))) {
            bindingInvalidatedForHud = true
            poison()
            return null
        }
        val currentBinder = current ?: run {
            poison()
            return null
        }
        if (!currentAlive || !sameBinder(existing, currentBinder)) {
            poison()
            return null
        }
        return existing
    }

    private fun executeUntil(
        binder: IBinder,
        rendered: String,
        deadlineElapsedRealtimeMs: Long,
    ): VendorBinderCall = execute(caller, binder, rendered, deadlineElapsedRealtimeMs)

    private fun poison() {
        poisoned = true
        cachedAttestation = null
    }

    private fun sameBinder(first: IBinder, second: IBinder): Boolean =
        first === second || first == second

    private companion object {
        const val TransactionCode = 0
        const val CaptureOutput = "1"
        const val MaxCommandBytes = 198
        const val HudCallerDeadlineMs = 1_700L
    }
}

internal sealed interface VendorPServerOperation {
    fun render(): String

    data object ProbeProtocol : VendorPServerOperation {
        override fun render(): String = "/system/bin/printf $ProtocolMarker"
    }

    data object ProbeUid : VendorPServerOperation {
        override fun render(): String = "/system/bin/id -u"
    }

    data object ProbeGid : VendorPServerOperation {
        override fun render(): String = "/system/bin/id -g"
    }

    data object ProbeSelinuxDomain : VendorPServerOperation {
        override fun render(): String = "/system/bin/cat /proc/self/attr/current"
    }

    data object ProbeExecutable : VendorPServerOperation {
        override fun render(): String = "/system/bin/readlink /proc/\$PPID/exe"
    }

    data object ProbeExecutableSha256 : VendorPServerOperation {
        override fun render(): String = "/system/bin/sha256sum /proc/\$PPID/exe"
    }

    class ReadSetting private constructor(private val key: String) : VendorPServerOperation {
        override fun render(): String = "/system/bin/settings get system $key"

        companion object {
            fun create(key: String): ReadSetting? =
                key.takeIf(CertifiedVendorValuePolicy::isAllowedKey)?.let(::ReadSetting)
        }
    }

    class WriteSetting private constructor(
        private val key: String,
        private val value: String,
    ) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/settings put system $key '$value' && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(key: String, value: String): WriteSetting? {
                val normalized = CertifiedVendorValuePolicy.normalize(key, value) ?: return null
                if (normalized != value) return null
                return WriteSetting(key, normalized)
            }
        }
    }

    class DeleteSetting private constructor(private val key: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/settings delete system $key && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(key: String): DeleteSetting? =
                key.takeIf(CertifiedVendorValuePolicy::isAllowedKey)?.let(::DeleteSetting)
        }
    }

    data object OpenSystemRecents : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/input keyevent 187 && /system/bin/echo $SuccessMarker"
    }

    /**
     * **223 is `KEYCODE_SLEEP`, and it is not `KEYCODE_POWER` (26) for a reason.**
     *
     * POWER toggles. If it arrives at a device that is already asleep it WAKES it, so a retry, a
     * duplicate deadline or a stale job turns the screen back on and the idle session flaps between
     * states. SLEEP only ever sleeps: sending it twice is the same as sending it once. For a step
     * that fires off a timer and may legitimately be attempted more than once, that is the whole
     * difference between "asleep" and "flickering".
     *
     * Enumerated like every other operation on this transport -- one fixed string, no interpolation,
     * nothing the caller can steer.
     */
    data object EnforceDeviceSleep : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/input keyevent 223 && /system/bin/echo $SuccessMarker"
    }

    /**
     * W2f: make Mimir Keyboard the system default. Four fixed commands and one fixed readback, none of which
     * takes anything but the IME id, which `mimirKeyboardImeId` recomputes from the app's own context and
     * [MimirImeId] re-validates here -- an id that is not Mimir's own `MimirInputMethodService` builds no
     * operation at all.
     *
     * **Every `ime` call is silenced** (`>/dev/null 2>&1`) because this channel returns only the FIRST LINE of
     * a command's output, and `ime enable` prints "Input method ... now enabled" first; unsilenced, the marker
     * would never be that line and every enable would read FAILED. The marker is chained with `&&`, so it is
     * echoed only when the `ime` call itself exited 0 -- and it is still not the verdict: APPLIED is decided by
     * the two readbacks ([ReadDefaultKeyboard], [KeyboardListed]), never by a marker alone.
     *
     * The IME id is interpolated bare: [MimirImeId] admits only letters, digits, `.`, `_` and the one `/`, none
     * of which the shell treats specially.
     */
    class EnableKeyboard private constructor(private val imeId: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/ime enable $imeId >/dev/null 2>&1 && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(imeId: String): EnableKeyboard? = mimirImeOperation(imeId, ::EnableKeyboard)
        }
    }

    /** `ime set <id>`, silenced and marker-chained exactly like [EnableKeyboard]. */
    class SelectKeyboard private constructor(private val imeId: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/ime set $imeId >/dev/null 2>&1 && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(imeId: String): SelectKeyboard? = mimirImeOperation(imeId, ::SelectKeyboard)
        }
    }

    /**
     * Readback B: the platform's own default, from the secure namespace -- not [ReadSetting], which reads the
     * `system` namespace behind an allow-list. One line, the id or `null`; nothing is interpolated.
     */
    data object ReadDefaultKeyboard : VendorPServerOperation {
        override fun render(): String = "/system/bin/settings get secure default_input_method"
    }

    /**
     * Readback C: is the id among the enabled keyboards. `ime list -s` prints one id per line and the channel
     * carries one line, so the match runs in the shell (`grep -Fx`, fixed string, whole line) and only the
     * marker comes back.
     */
    class KeyboardListed private constructor(private val imeId: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/ime list -s 2>/dev/null | /system/bin/grep -Fx $imeId >/dev/null && " +
                "/system/bin/echo $SuccessMarker"

        companion object {
            fun create(imeId: String): KeyboardListed? = mimirImeOperation(imeId, ::KeyboardListed)
        }
    }

    /**
     * Op D' (D32): `ime disable <Mimir's id>`, silenced and marker-chained like [EnableKeyboard]. Mimir leaves the
     * enabled list; when it is the selected keyboard the platform picks the most applicable remaining one. The "is
     * it still the current one" decision happens INSIDE the input-method service, under its own lock, so a keyboard
     * the player picks at the same moment is serialised, never overwritten, and the player's other keyboards stay
     * enabled (`ime reset` disables them all). The id is the validated, recomputed one -- no caller value; the
     * marker proves only that the call exited 0, the verdict is the readback.
     */
    class DisableKeyboard private constructor(private val imeId: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/ime disable $imeId >/dev/null 2>&1 && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(imeId: String): DisableKeyboard? = mimirImeOperation(imeId, ::DisableKeyboard)
        }
    }

    /**
     * Readback E (D32): the first enabled keyboard that is NOT Mimir's, or an empty reply when there is none. The
     * channel carries one line, so the filter runs in the shell (`grep -vFx`, fixed string, whole line) and
     * `head -n 1` keeps it to that line. The app accepts one input-method-id line as "another keyboard is enabled"
     * and anything else as none. No marker: the id itself is the answer.
     */
    class OtherKeyboardListed private constructor(private val imeId: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/ime list -s 2>/dev/null | /system/bin/grep -vFx $imeId | /system/bin/head -n 1"

        companion object {
            fun create(imeId: String): OtherKeyboardListed? = mimirImeOperation(imeId, ::OtherKeyboardListed)
        }
    }

    /**
     * Op D: `ime reset`, restoring the system's own keyboard list and default, made CONDITIONAL in the same
     * shell command: it runs only while the default setting still equals Mimir's own id. A player who switches
     * keyboard between Mimir's readback and this command is therefore never overwritten (the `[ ... ]` is false
     * and `ime reset` never starts). The id is the validated, recomputed one -- no caller value.
     *
     * Since D32 this is the safety revert's last resort only (Mimir is the one enabled keyboard, so `ime disable`
     * would leave none and restoring the system defaults is exactly right); the Settings switch never sends it.
     *
     * No success marker: a marker would not fit the 198-byte cap for the long devlab id, and it would not be the
     * verdict anyway. The command prints nothing; the transport reports only that it was sent, and APPLIED is
     * decided by the readback that the default is no longer Mimir's.
     */
    class ResetKeyboardIfDefault private constructor(private val imeId: String) : VendorPServerOperation {
        override fun render(): String =
            "[ \"\$(/system/bin/settings get secure default_input_method)\" = $imeId ] && " +
                "/system/bin/ime reset >/dev/null 2>&1"

        companion object {
            fun create(imeId: String): ResetKeyboardIfDefault? = mimirImeOperation(imeId, ::ResetKeyboardIfDefault)
        }
    }

    class StartEmulatorRenderFps private constructor(
        private val launcher: String,
    ) : VendorPServerOperation {
        override fun render(): String = "/system/bin/sh $launcher"

        companion object {
            fun create(files: EmulatorRenderFpsHelperFiles): StartEmulatorRenderFps? =
                safeRenderFpsFile(files, files.launcherScript)
                    ?.let(::StartEmulatorRenderFps)
                    ?.takeIf { it.render().toByteArray(StandardCharsets.UTF_8).size <= 198 }
        }
    }

    class ReadEmulatorRenderFpsRecord private constructor(
        private val reader: String,
    ) : VendorPServerOperation {
        override fun render(): String = "/system/bin/sh $reader"

        companion object {
            fun create(files: EmulatorRenderFpsHelperFiles): ReadEmulatorRenderFpsRecord? =
                safeRenderFpsFile(files, files.readerScript)
                    ?.let(::ReadEmulatorRenderFpsRecord)
                    ?.takeIf { it.render().toByteArray(StandardCharsets.UTF_8).size <= 198 }
        }
    }

    class ResolveEmulatorRenderFpsTarget private constructor(
        private val resolver: String,
        private val packageName: String,
    ) : VendorPServerOperation {
        override fun render(): String = "/system/bin/sh $resolver $packageName"

        companion object {
            fun create(
                files: EmulatorRenderFpsHelperFiles,
                packageName: String,
            ): ResolveEmulatorRenderFpsTarget? {
                if (!MimirLaunchTargetStopPolicy.isAllowed(packageName)) return null
                return safeRenderFpsFile(files, files.resolverScript)
                    ?.let { ResolveEmulatorRenderFpsTarget(it, packageName) }
                    ?.takeIf { it.render().toByteArray(StandardCharsets.UTF_8).size <= 198 }
            }
        }
    }

    /**
     * Stop, then prove it.
     *
     * The marker is no longer chained to the stop's exit code with `&&` -- on the AYN Thor the
     * firmware whitelists packages (`forceStopPackage() WhiteList packageName=com.android.chrome`,
     * device reproduction 2026-09-11 09:34), so `am force-stop` exits 0 and kills nothing. The marker is now
     * echoed only when `pidof` finds no live process afterwards, which makes a reported STOPPED mean
     * "no process left", not "the command was accepted".
     *
     * Known limit, deliberately not hidden: if `/system/bin/pidof` is missing, its non-zero exit
     * echoes the marker, so the readback degrades to the old accept-only semantics rather than
     * failing closed. The command keeps running the stop first either way.
     */
    class ForceStop private constructor(private val packageName: String) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/am force-stop --user 0 $packageName; " +
                "/system/bin/pidof $packageName >/dev/null 2>&1 || /system/bin/echo $SuccessMarker"

        companion object {
            fun create(packageName: String): ForceStop? =
                packageName
                    .takeIf(MimirLaunchTargetStopPolicy::isAllowed)
                    // Defence in depth at the composition site: the policy's own syntax rule already
                    // rejects shell metacharacters, and this refuses to render one even if it changes.
                    ?.takeIf { VendorPServerOperation.ShellSafePackage.matches(it) }
                    ?.let(::ForceStop)
                    ?.takeIf {
                        it.render().toByteArray(StandardCharsets.UTF_8).size <=
                            VendorPServerOperation.MaxRenderedBytes
                    }
        }
    }

    /**
     * Is a process running under this package -- asked, never changed.
     *
     * Round-5 B1 (Thor, 2026-09-11): a Tabs row outlived an app stopped outside Mimir. The only liveness
     * readback in this file was [ForceStop]'s, which stops first and, by its stated limit, echoes its
     * marker when `pidof` is missing. Neither is acceptable for a question about a tab nobody closed, so
     * this command is the readback alone, and it fails closed:
     *  - no `am`, no stop, no write: `pidof` and `echo` only;
     *  - `pidof` sits behind `[ -x /system/bin/pidof ]`, so a device without it prints nothing, which reads
     *    UNKNOWN, where an unguarded `||` would have echoed absence;
     *  - one package per command, because the channel returns only a command's first output line.
     *
     * The package is admitted by [MimirLaunchTargetStopPolicy] and re-checked against [ShellSafePackage]
     * before composition, exactly as [ForceStop]'s is.
     */
    class ProcessPresence private constructor(private val packageName: String) : VendorPServerOperation {
        override fun render(): String =
            "if [ -x /system/bin/pidof ]; then /system/bin/pidof $packageName >/dev/null 2>&1 " +
                "&& /system/bin/echo $ProcessAliveMarker || /system/bin/echo $ProcessAbsentMarker; fi"

        companion object {
            fun create(packageName: String): ProcessPresence? =
                packageName
                    .takeIf(MimirLaunchTargetStopPolicy::isAllowed)
                    ?.takeIf { VendorPServerOperation.ShellSafePackage.matches(it) }
                    ?.let(::ProcessPresence)
                    ?.takeIf {
                        it.render().toByteArray(StandardCharsets.UTF_8).size <=
                            VendorPServerOperation.MaxRenderedBytes
                    }
        }
    }

    /**
     * One task id, resolved in one line, because one line is all this channel carries.
     *
     * **Device fact** (Thor, build `23ca3300`, 2026-09-11): the vendor PServer reply contains only the
     * FIRST LINE of a command's output — a 62-byte reply for a table `am stack list` prints in full,
     * which read as `candidates=0` while the task was plainly there. A multi-line read is therefore
     * impossible here, and that is the same property the success-marker echo relies on. So the
     * selection runs in the shell and only the answer comes back.
     *
     * `-A2` keeps the two lines below the matching `RootTask … displayId=<d> ` header (the
     * `configuration=` line and the `taskId=` row), and the second grep prints only the first
     * `taskId=<n>: <pkg>/` found there.
     *
     * The dots in the package are regex wildcards to grep. That is harmless: the trailing `/` anchors
     * the component boundary, so the pattern can only over-match a package that differs from the
     * admitted one at a single character in the same position, and [parseVendorResolvedTaskId] then
     * rejects any reply that does not name the exact package asked for. The package is admitted by
     * [MimirLaunchTargetStopPolicy] and re-checked against [ShellSafePackage] before composition, so
     * it can carry no quote or shell metacharacter; the display id is an Int.
     *
     * R6-P0-02 round-5 fix (BLOCKING): this render is restored byte-identical to base `daf0e36b` --
     * round 3/4 had rewritten it in place to add the marker-based [NoTask][VendorTaskOutcome.NoTask]
     * distinction, but [resolveTaskId]/[resolveTaskReply] go through this SAME operation, and those two
     * are used by every certified stop/return path (Return focus, the parking arm, the landed-task note,
     * Quick-launch evict, the registry close fallback), not just the parking probe. Widening this one
     * render for one caller ran unproven shell syntax on every certified path at once. [ResolveTaskOutcome]
     * below is the split-off sibling that carries the marker-based rewrite instead, used ONLY by
     * [resolveTaskOutcome] -- nothing else may construct it.
     */
    class ResolveTask private constructor(
        private val packageName: String,
        private val displayId: Int,
    ) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/am stack list | /system/bin/grep -A2 'displayId=$displayId ' | " +
                "/system/bin/grep -o -m1 'taskId=[0-9]*: $packageName/'"

        companion object {
            fun create(packageName: String, displayId: Int): ResolveTask? {
                if (displayId < 0) return null
                return packageName
                    .takeIf(MimirLaunchTargetStopPolicy::isAllowed)
                    ?.takeIf { VendorPServerOperation.ShellSafePackage.matches(it) }
                    ?.let { ResolveTask(it, displayId) }
                    ?.takeIf {
                        it.render().toByteArray(StandardCharsets.UTF_8).size <=
                            VendorPServerOperation.MaxRenderedBytes
                    }
            }
        }
    }

    /**
     * [resolveTaskOutcome]'s own operation -- NOT used by [resolveTaskId] or [resolveTaskReply], which
     * both stay on the byte-identical-to-base [ResolveTask] above. Splitting this off (R6-P0-02 round-5,
     * BLOCKING) is what let the marker-based [NoTask][VendorTaskOutcome.NoTask] distinction exist at all
     * without changing shell syntax underneath every certified stop/return path.
     *
     * Two more round-5 fixes narrow the shape from round 3/4's version:
     *
     * 1. No `exit 0`. If the vendor daemon feeds a persistent shell rather than running `sh -c` fresh per
     *    transaction, `exit` would kill that shell and poison every later vendor transaction on it -- an
     *    unverifiable, high-blast-radius risk `exit 0` never should have carried. This uses only
     *    constructs the Thor has already been seen running: [ProcessPresence]'s `if`/`then`/`fi`, `[ ]`
     *    (implicitly, via the `if` test on the assignment's own exit status), `||`, and capture-then-use
     *    assignment. When `am` fails, `if L=$(...)` is false, the `then` branch never runs, and the
     *    command produces empty output -- [parseVendorTaskOutcome] reads that as `Unavailable("empty")`,
     *    exactly as the exit-0 version did, without ever touching the shell's own lifetime.
     * 2. Builtin `echo`, not `$B/echo`, for `"$L"`. The external toybox `echo` receives `"$L"` as one
     *    execve argument, bounded by `MAX_ARG_STRLEN` (128 KiB on Android). A large `am stack list` table
     *    would overflow that limit, fail with E2BIG, leave the pipe with nothing to grep, echo the
     *    NoTask marker, and release a live parked game -- the exact failure mode this operation exists to
     *    rule out. The shell's OWN builtin `echo` (used for the re-pipe and for the marker both) takes no
     *    execve call and no such argument-length limit.
     *
     * `B=/system/bin` is a same-shell prefix variable, not a new binary path: `am` and `grep` are still
     * invoked by the exact same absolute paths [ForceStop]/[ProcessPresence] use elsewhere; only the two
     * `echo`s are deliberately NOT prefixed, per fix 2 above.
     */
    class ResolveTaskOutcome private constructor(
        private val packageName: String,
        private val displayId: Int,
    ) : VendorPServerOperation {
        override fun render(): String =
            "B=/system/bin; if L=\$(\$B/am stack list); then echo \"\$L\" | " +
                "\$B/grep -A2 'displayId=$displayId ' | " +
                "\$B/grep -o -m1 'taskId=[0-9]*: $packageName/' || echo $NoTaskMarker; fi"

        companion object {
            fun create(packageName: String, displayId: Int): ResolveTaskOutcome? {
                if (displayId < 0) return null
                return packageName
                    .takeIf(MimirLaunchTargetStopPolicy::isAllowed)
                    ?.takeIf { VendorPServerOperation.ShellSafePackage.matches(it) }
                    ?.let { ResolveTaskOutcome(it, displayId) }
                    ?.takeIf {
                        it.render().toByteArray(StandardCharsets.UTF_8).size <=
                            VendorPServerOperation.MaxRenderedBytes
                    }
            }
        }
    }

    /**
     * The eviction that finishes a close the whitelisted `force-stop` could not: the surviving task is
     * removed, so it leaves display 4 AND leaves Android Recents (the Thor repro tapped it
     * there and it came back on the Main screen).
     *
     * [taskId] is an Int, formatted by Kotlin -- there is no string interpolation of caller input in
     * this command at all.
     */
    class RemoveTask private constructor(private val taskId: Int) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/am stack remove $taskId && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(taskId: Int): RemoveTask? = taskId.takeIf { it > 0 }?.let(::RemoveTask)
        }
    }

    /**
     * Brings one already-resolved task to the foreground. The id is formatted from a validated Int;
     * no package or caller text enters this command. The marker is chained to the command exit so
     * a completed transaction without it is never reported as focused.
     */
    class FocusTask private constructor(private val taskId: Int) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/am task focus $taskId >/dev/null && /system/bin/echo $SuccessMarker"

        companion object {
            fun create(taskId: Int): FocusTask? = taskId.takeIf { it > 0 }?.let(::FocusTask)
        }
    }

    /**
     * `IDisplayManager.getBrightness(displayId)`, asked through the stock `service` tool, which addresses a
     * binder method by number. Read-only.
     *
     * No Shizuku is needed. The number is the one this device's framework gives the method
     * ([FrameworkDisplayTransactions]); the
     * display id is an Int. So the command is two Ints in a fixed template, like [RemoveTask]'s one.
     *
     * The reply fits the channel, which only ever returns a command's first line: `service` prints a reply
     * of 16 bytes or fewer on one line, and a float result is 8 -- `Result: Parcel(00000000 3f266667
     * '....gf&?')` on the Thor, where the second word is 0.65. [VendorServiceCallReply] reads it.
     */
    class ReadDisplayBrightness private constructor(
        private val transaction: Int,
        private val displayId: Int,
    ) : VendorPServerOperation {
        override fun render(): String = "/system/bin/service call display $transaction i32 $displayId"

        companion object {
            fun create(transactions: DisplayBrightnessTransactions, displayId: Int): ReadDisplayBrightness? {
                if (transactions.getBrightness !in DisplayTransactionRange) return null
                if (displayId !in 1..MaxDisplayId) return null
                return ReadDisplayBrightness(transactions.getBrightness, displayId)
            }
        }
    }

    /**
     * `IDisplayManager.setBrightness(displayId, fraction)`: the one call that moves the bottom panel. Writing
     * AYN's `dual_screen_brightness_level` alone moved nothing (2026-09-06); this vendor transaction is what
     * moves the panel.
     *
     * The fraction is written by this class, not passed in as text: four decimals in [Locale.ROOT], so a
     * device language that writes "0,65" cannot turn it into a different number for `service`'s `atof`, and
     * then checked against the only two shapes that can come out of 0..1. Any value in 0..1 is accepted
     * here, not only the slider's floor, because a restore writes back whatever the panel held before.
     */
    class WriteDisplayBrightness private constructor(
        private val transaction: Int,
        private val displayId: Int,
        private val fraction: String,
    ) : VendorPServerOperation {
        override fun render(): String =
            "/system/bin/service call display $transaction i32 $displayId f $fraction"

        companion object {
            fun create(
                transactions: DisplayBrightnessTransactions,
                displayId: Int,
                fraction: Float,
            ): WriteDisplayBrightness? {
                if (transactions.setBrightness !in DisplayTransactionRange) return null
                if (displayId !in 1..MaxDisplayId) return null
                if (!fraction.isFinite() || fraction !in 0f..1f) return null
                val text = String.format(Locale.ROOT, "%.4f", fraction)
                if (!DisplayFractionText.matches(text)) return null
                return WriteDisplayBrightness(transactions.setBrightness, displayId, text)
            }
        }
    }

    companion object {
        const val SuccessMarker = "__MIMIR_VENDOR_OK__"

        /** The presence probe's two answers. Neither is [SuccessMarker], so a stop's reply is never one. */
        const val ProcessAliveMarker = "__MIMIR_ALIVE__"
        const val ProcessAbsentMarker = "__MIMIR_ABSENT__"

        /**
         * [ResolveTaskOutcome]'s positive "the grep pipe ran and matched nothing" answer (R6-P0-02 round-3,
         * split off from [ResolveTask] onto its own operation in round-5).
         * Echoed only after `am` itself is proven to have succeeded -- never a stand-in for a probe that
         * could not run. Distinct from [SuccessMarker] and the presence markers, so it is never confused
         * with any other command's reply.
         */
        const val NoTaskMarker = "__MIMIR_NO_TASK__"

        /** Matches the transport's own command cap; an over-long package fails closed at composition. */
        const val MaxRenderedBytes = 198

        /**
         * What may be interpolated into a rendered command. Not the access decision -- that stays with
         * [MimirLaunchTargetStopPolicy] -- only a shell-safety floor.
         */
        val ShellSafePackage = Regex("[A-Za-z0-9._]+")

        /**
         * Mimir's own IME id as `ComponentName.flattenToShortString` spells it: `<package>/<class>`, the class
         * abbreviated to `.MimirInputMethodService` only when it starts with the package. The `devlab` build
         * (package `...rewrite.devlab`, class `...rewrite.MimirInputMethodService`) therefore carries the LONG
         * form, which is why the class part admits a dotted prefix. Anything else builds no operation.
         */
        val MimirImeId = Regex("[A-Za-z0-9._]+/[A-Za-z0-9._]*\\.MimirInputMethodService")

        fun isMimirKeyboardImeId(imeId: String): Boolean = MimirImeId.matches(imeId)
        fun enableKeyboard(imeId: String): VendorPServerOperation? = EnableKeyboard.create(imeId)
        fun selectKeyboard(imeId: String): VendorPServerOperation? = SelectKeyboard.create(imeId)
        fun keyboardListed(imeId: String): VendorPServerOperation? = KeyboardListed.create(imeId)
        fun disableKeyboard(imeId: String): VendorPServerOperation? = DisableKeyboard.create(imeId)
        fun otherKeyboardListed(imeId: String): VendorPServerOperation? = OtherKeyboardListed.create(imeId)
        fun resetKeyboardIfDefault(imeId: String): VendorPServerOperation? = ResetKeyboardIfDefault.create(imeId)

        /** Binder's FIRST_CALL_TRANSACTION to LAST_CALL_TRANSACTION. */
        val DisplayTransactionRange = 1..0x00ffffff

        /** The bounded secondary-display range. Display 0 is the main screen and never a target. */
        const val MaxDisplayId = 4096

        /** What `%.4f` makes of a value in 0..1, and nothing else. */
        val DisplayFractionText = Regex("""0\.[0-9]{4}|1\.0000""")

        // One spelling, shared with the admission policy's structural contract. These were two
        // independent literals before; a rename on one side would have silently opened the gate.
        const val ProtocolMarker = CertifiedVendorStructuralContract.ProtocolMarker
        const val ExpectedExecutable = CertifiedVendorStructuralContract.ExpectedExecutablePath

        fun readSetting(key: String): VendorPServerOperation? = ReadSetting.create(key)
        fun writeSetting(key: String, value: String): VendorPServerOperation? =
            WriteSetting.create(key, value)
        fun deleteSetting(key: String): VendorPServerOperation? = DeleteSetting.create(key)
        fun forceStop(packageName: String): VendorPServerOperation? = ForceStop.create(packageName)
        fun removeTask(taskId: Int): VendorPServerOperation? = RemoveTask.create(taskId)
        fun focusTask(taskId: Int): VendorPServerOperation? = FocusTask.create(taskId)
        fun resolveTask(packageName: String, displayId: Int): VendorPServerOperation? =
            ResolveTask.create(packageName, displayId)
        fun resolveTaskOutcome(packageName: String, displayId: Int): VendorPServerOperation? =
            ResolveTaskOutcome.create(packageName, displayId)
        fun processPresence(packageName: String): VendorPServerOperation? = ProcessPresence.create(packageName)
    }
}

/**
 * Builds one of the keyboard-default operations for [imeId], or null: the id must be Mimir's own IME id
 * ([VendorPServerOperation.MimirImeId]) and the rendered command must fit the transport's 198-byte cap.
 */
private fun <T : VendorPServerOperation> mimirImeOperation(imeId: String, build: (String) -> T): T? =
    imeId.takeIf { VendorPServerOperation.isMimirKeyboardImeId(it) }
        ?.let(build)
        ?.takeIf {
            it.render().toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes
        }

/**
 * Reads the one line `service call display` prints, strictly. Anything that is not exactly a display-manager
 * reply without an exception is not an answer.
 *
 * `service` prints a reply of up to 16 bytes as `Result: Parcel(<words> '<ascii>')` on one line, and the
 * first word is the exception code, 0 for none. A longer reply -- which is what an exception with its message
 * is -- starts with `Result: Parcel(` and goes on over the next lines, so its first line, the only one this
 * channel carries, matches neither shape below. So does `service`'s own complaint about a missing service.
 */
internal object VendorServiceCallReply {
    /** A float result: no exception, then the float's bits. `Result: Parcel(00000000 3f266667   '....gf&?')`. */
    private val FloatReply = Regex("""Result: Parcel\(00000000 ([0-9a-f]{8}) +'.{8}'\)""")

    /** A void result: no exception and nothing else. `Result: Parcel(00000000    '....')`. */
    private val VoidReply = Regex("""Result: Parcel\(00000000 +'.{4}'\)""")

    fun brightness(reply: String): VendorDisplayRead {
        val line = reply.trimReply()
        // `service call` prints a result line whatever the display manager answers, so an empty reply is the
        // channel losing it, as an empty setting read is ([AndroidPServerTransport.readSystemSetting]): not a
        // refusal, which would close the display route for the process.
        if (line.isEmpty()) return VendorDisplayRead.Failed
        val match = FloatReply.matchEntire(line) ?: return VendorDisplayRead.Refused
        val value = Float.fromBits(match.groupValues[1].toLong(16).toInt())
        return if (value.isFinite() && value in 0f..1f) {
            VendorDisplayRead.Value(value)
        } else {
            VendorDisplayRead.NoBrightness
        }
    }

    fun succeeded(reply: String): Boolean = VoidReply.matches(reply.trimReply())

    /**
     * A reply as a log shows it: one line of at most [LoggedReplyChars], control characters as `?`. What
     * `service` prints is a parcel's hex dump or its own complaint, with nothing secret in either.
     */
    fun forLog(reply: String): String =
        reply.trimReply().take(LoggedReplyChars)
            .map { if (it.code < 0x20 || it.code == 0x7f) '?' else it }
            .joinToString("")

    private const val LoggedReplyChars = 80

    private fun String.trimReply(): String = trim('\u0000', '\n', '\r', ' ', '\t')
}

/**
 * True when the privileged shell echoed the success marker as one of its output lines.
 *
 * A marker can only reach the wire from a command Mimir composed, so a line-exact match is the same
 * proof as a whole-output match while tolerating anything the stopped command printed before it.
 */
internal fun String.containsSuccessMarkerLine(): Boolean =
    lineSequence().any { it.trim() == VendorPServerOperation.SuccessMarker }

/**
 * The liveness readback rule for a stop transaction that completed.
 *
 * The marker is echoed only on the `pidof` miss, so its presence is the whole evidence: marker means
 * STOPPED, and a completed transaction without one means something is still running under that
 * package name. Nothing here infers liveness from an exit code.
 */
internal fun completedVendorStopOutcome(output: String): VendorTransportStop =
    if (output.containsSuccessMarkerLine()) {
        VendorTransportStop.STOPPED
    } else {
        VendorTransportStop.STILL_ALIVE
    }

/**
 * Reads the resolver's reply: `taskId=<n>: <pkg>/`, or nothing.
 *
 * Strict, and anchored on the package that was asked for. The channel carries one line, so only the
 * first line is read; a reply that names any other package, or is shaped any other way, is treated
 * as no answer at all. The id this returns is evicted for real, so "roughly right" is not a value
 * this function may produce.
 */
internal fun parseVendorResolvedTaskId(reply: String?, packageName: String): Int? {
    val line = reply?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    if (line.isEmpty()) return null
    val pattern = Regex("^taskId=(\\d+): " + Regex.escape(packageName) + "/$")
    return pattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * R6-P0-02 review fix (BLOCKING): [parseVendorResolvedTaskId]'s tri-state sibling for a COMPLETED
 * transaction's verbatim [reply] -- called only when the transaction itself completed.
 *
 * R6-P0-02 round-3 fix (BLOCKING): an empty first line is NOT read as [VendorTaskOutcome.NoTask] any
 * more. [VendorPServerOperation.ResolveTaskOutcome.render] (round-5: split off from the plain
 * [VendorPServerOperation.ResolveTask] every certified stop/return path still uses unmodified) bails
 * with empty output whenever its own `am` invocation fails, exactly as a probe that could not run at
 * all would -- so an empty reply here is [VendorTaskOutcome.Unavailable] with reason `empty`, same
 * family as `timeout`/`unavailable`, never proof of absence. [VendorTaskOutcome.NoTask] is read ONLY
 * from the literal [VendorPServerOperation.NoTaskMarker] line, [ResolveTaskOutcome.render]'s positive
 * signal that `am` succeeded and the grep pipe still matched nothing. Any other non-matching, non-empty
 * line stays [VendorTaskOutcome.Unavailable] with reason `parse` -- a firmware/format surprise, not
 * proof of absence either.
 */
internal fun parseVendorTaskOutcome(reply: String, packageName: String): VendorTaskOutcome {
    val line = reply.lineSequence().firstOrNull()?.trim().orEmpty()
    if (line.isEmpty()) return VendorTaskOutcome.Unavailable("empty")
    if (line == VendorPServerOperation.NoTaskMarker) return VendorTaskOutcome.NoTask
    val pattern = Regex("^taskId=(\\d+): " + Regex.escape(packageName) + "/$")
    val taskId = pattern.find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    return taskId?.let(VendorTaskOutcome::Found) ?: VendorTaskOutcome.Unavailable("parse")
}

/**
 * Reads the presence probe's reply: exactly one of its two markers on the first line, or UNKNOWN.
 *
 * Fail-closed. No reply (a failed or timed-out transaction), an empty reply (the `[ -x /system/bin/pidof ]`
 * guard found no `pidof`), a shell error line, a pid, a near-miss, and the stop's success marker are all
 * UNKNOWN. Only the absent marker reads ABSENT, and ABSENT is the only answer a tab may be retired on. The
 * channel returns one line, so only the first line is read, as [parseVendorResolvedTaskId] does; NUL and
 * whitespace around the marker are ignored.
 */
internal fun parseVendorProcessPresence(reply: String?): PrivilegedPackageLiveness =
    when (reply?.lineSequence()?.firstOrNull()?.trim { it.code == 0 || it.isWhitespace() }) {
        VendorPServerOperation.ProcessAliveMarker -> PrivilegedPackageLiveness.ALIVE
        VendorPServerOperation.ProcessAbsentMarker -> PrivilegedPackageLiveness.ABSENT
        else -> PrivilegedPackageLiveness.UNKNOWN
    }

private fun safeRenderFpsFile(
    files: EmulatorRenderFpsHelperFiles,
    file: File,
): String? = runCatching {
    val directory = files.directory.canonicalFile
    val target = file.canonicalFile
    if (!directory.isDirectory || Files.isSymbolicLink(file.toPath()) ||
        target.parentFile?.canonicalFile != directory
    ) return@runCatching null
    val path = target.path
    if (!path.matches(Regex("^/[A-Za-z0-9_./-]+$"))) return@runCatching null
    path
}.getOrNull()

private sealed interface VendorBinderCall {
    data class Completed(val output: String) : VendorBinderCall
    data object Failed : VendorBinderCall
    data object TimedOut : VendorBinderCall
}

private sealed interface BoundedValueCall<out T> {
    data class Completed<T>(val value: T) : BoundedValueCall<T>
    data object Failed : BoundedValueCall<Nothing>
    data object TimedOut : BoundedValueCall<Nothing>
}

private class BoundedVendorBinderCaller(
    private val deadlineMs: Long = 2_000L,
    private val threadName: String = "mimir-certified-vendor",
) : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    fun call(block: () -> VendorBinderCall): VendorBinderCall {
        return callUntil(SystemClock.elapsedRealtime() + deadlineMs, block)
    }

    fun callUntil(
        deadlineElapsedRealtimeMs: Long,
        block: () -> VendorBinderCall,
    ): VendorBinderCall {
        val remainingMs = deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) return VendorBinderCall.TimedOut
        val future = try {
            executor.submit<VendorBinderCall> {
                if (SystemClock.elapsedRealtime() >= deadlineElapsedRealtimeMs) {
                    VendorBinderCall.TimedOut
                } else {
                    block()
                }
            }
        } catch (_: RejectedExecutionException) {
            return VendorBinderCall.Failed
        }
        return try {
            future.get(remainingMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            (future as? Runnable)?.let(executor::remove)
            VendorBinderCall.TimedOut
        } catch (_: Exception) {
            VendorBinderCall.Failed
        }
    }

    fun <T> callValueUntil(
        deadlineElapsedRealtimeMs: Long,
        block: () -> T,
    ): BoundedValueCall<T> {
        val remainingMs = deadlineElapsedRealtimeMs - SystemClock.elapsedRealtime()
        if (remainingMs <= 0L) return BoundedValueCall.TimedOut
        val future = try {
            executor.submit<T> { block() }
        } catch (_: RejectedExecutionException) {
            return BoundedValueCall.Failed
        }
        return try {
            BoundedValueCall.Completed(future.get(remainingMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            (future as? Runnable)?.let(executor::remove)
            BoundedValueCall.TimedOut
        } catch (_: Exception) {
            BoundedValueCall.Failed
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private fun resolvePServerBinder(): IBinder? = runCatching {
    val serviceManager = Class.forName("android.os.ServiceManager")
    serviceManager.getDeclaredMethod("getService", String::class.java)
        .apply { isAccessible = true }
        .invoke(null, CertifiedVendorAdmissionPolicy.ServiceName) as? IBinder
}.getOrNull()

/** Validation shared by the vendor gateway's setting and display transactions. */
internal object CertifiedVendorValuePolicy {
    private val allowedKeys = setOf(
        BrightnessSettingsKeys.AYN_SECONDARY,
        BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT,
        BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED,
        BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR,
    )
    private val decimalPercent = Regex("\\d{1,3}")
    private val decimalFraction = Regex("(?:0(?:\\.\\d{1,4})?|1(?:\\.0{1,4})?)")
    private val argb = Regex("#[0-9a-fA-F]{8}")

    fun isAllowedKey(key: String): Boolean = key in allowedKeys

    fun normalize(key: String, value: String): String? {
        if (!isAllowedKey(key) || value.length > MAX_SETTING_VALUE_LENGTH) return null
        val trimmed = value.trim()
        return when (key) {
            BrightnessSettingsKeys.AYN_SECONDARY -> trimmed.takeIf(decimalPercent::matches)
                ?.toIntOrNull()?.takeIf { it in 0..100 }?.toString()
            BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT -> trimmed
                .takeIf(decimalFraction::matches)?.let(::canonicalDecimal)
            BrightnessSettingsKeys.VENDOR_JOYSTICK_ENABLED -> normalizeChannels(trimmed) { it == "0" || it == "1" }
            BrightnessSettingsKeys.VENDOR_JOYSTICK_COLOR -> normalizeChannels(trimmed, argb::matches)
                ?.lowercase(Locale.ROOT)
            else -> null
        }
    }

    fun isValidDisplayTransaction(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean {
        if (displayId !in 1..MAX_DISPLAY_ID) return false
        if (!fraction.isFinite() || fraction !in MIN_PANEL_FRACTION..1f) return false
        if (mirrorPercent !in 1..100) return false
        return mirrorPercent == (fraction * 100f).roundToInt().coerceIn(1, 100) &&
            abs(fraction - mirrorPercent / 100f) <= DISPLAY_PERCENT_TOLERANCE
    }

    fun isSafeSnapshot(value: String?): Boolean = value == null || value.length <= MAX_SETTING_VALUE_LENGTH

    private fun canonicalDecimal(value: String): String =
        BigDecimal(value).stripTrailingZeros().toPlainString()

    private fun normalizeChannels(value: String, accepts: (String) -> Boolean): String? {
        val channels = value.split(',').map(String::trim)
        if (channels.size !in 1..2 || channels.any { !accepts(it) }) return null
        return channels.joinToString(",")
    }

    private const val MAX_SETTING_VALUE_LENGTH = 96
    private const val MAX_DISPLAY_ID = 4096
    private const val MIN_PANEL_FRACTION = 0.05f
    private const val DISPLAY_PERCENT_TOLERANCE = 0.0051f
}

/** Admission gate for emulator targets and apps recorded as launched by Mimir. */
internal object MimirLaunchTargetStopPolicy {
    const val REQUEST_ACCEPTED = 0
    const val NOT_ALLOWED = 1
    const val FAILED = 2

    private val PackageSyntax = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private val AllowedPackages = EmulatorSeedRegistry.allDispatchPackages().toSet()

    @Volatile var sessionAllowedPackages: () -> Set<String> = { emptySet() }

    fun isEmulatorTarget(packageName: String): Boolean = packageName in AllowedPackages

    fun isAllowed(packageName: String): Boolean =
        packageName.length in 3..255 &&
            PackageSyntax.matches(packageName) &&
            packageName != BuildConfig.APPLICATION_ID &&
            (isEmulatorTarget(packageName) || packageName in sessionAllowedPackages())

    fun argv(packageName: String): List<String> =
        if (isAllowed(packageName)) {
            listOf("/system/bin/am", "force-stop", "--user", "0", packageName)
        } else {
            emptyList()
        }

    fun fromWire(value: Int): PrivilegedLaunchTargetStop = when (value) {
        REQUEST_ACCEPTED -> PrivilegedLaunchTargetStop.REQUEST_ACCEPTED
        NOT_ALLOWED -> PrivilegedLaunchTargetStop.NOT_ALLOWED
        else -> PrivilegedLaunchTargetStop.FAILED
    }
}
