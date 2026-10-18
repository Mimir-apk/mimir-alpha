package com.gruning.mimir.rewrite

import java.util.Locale

/** The authority currently serving Mimir's narrow privileged Utility contract. */
enum class PrivilegedUtilityAuthority {
    NONE,
    CERTIFIED_VENDOR,
}

/**
 * Vendors whose settings package this build can recognise by name.
 *
 * **Descriptive, not a gate.** Since the SoC ruling of 2026-08-21 a handheld whose vendor is not in
 * this enum is still admitted; the enum only supplies a label for the profile id and the expected
 * settings-package name for the self-consistency check in
 * [CertifiedVendorAdmissionPolicy.vendorPackagePostureAdmissible]. Do not reintroduce it as a
 * precondition for access.
 */
internal enum class CertifiedHandheldVendor(
    val settingsPackage: String,
) {
    RETROID(VendorUtilityPackages.RETROID_SETTINGS),
    AYN(VendorUtilityPackages.AYN_SETTINGS),
}

internal enum class CertifiedQualcommFamily {
    SNAPDRAGON_865,
    SNAPDRAGON_8_GEN_2,
    SNAPDRAGON_8_ELITE,
}

/**
 * Side-effect-free evidence returned by the exact Binder instance that would own a transaction.
 *
 * A service-name ping alone is not authority for a root-capable protocol.
 *
 * **Every field here is self-reported by the service under test** -- the probes that produce them run
 * through that same Binder. Read them as "what this responder claims about itself", which is exactly
 * as much as any attestation over an unauthenticated local channel can be. What makes the claim worth
 * anything is the protocol-marker echo the transport verifies before this object is ever constructed;
 * see [CertifiedVendorStructuralContract].
 */
internal data class CertifiedVendorServiceAttestation(
    val serviceName: String,
    val binderDescriptor: String,
    val protocolId: String,
    val uid: String,
    val gid: String,
    val selinuxDomain: String,
    val executablePath: String,
    val executableSha256: String,
)

/**
 * Fresh process-start evidence used to admit the vendor authority.
 *
 * Package presence or a marketing model name is never sufficient, and neither is their absence
 * disqualifying. What decides admission is the SoC family plus a structurally valid, side-effect-free
 * attestation from the fixed vendor Binder instance -- see [CertifiedVendorAdmissionPolicy].
 *
 * [vendorPackageSignerSha256] is retained as **recorded evidence only**. It no longer gates access
 * (the ruling retired that pin); `vendorPackageMatchesPlatformSignature` carries the structural half
 * of what it used to assert. Keep collecting it -- it is what a human reads when diagnosing a refusal.
 */
internal data class CertifiedVendorEvidence(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val socModel: String,
    val buildFingerprint: String,
    val vendorSettingsPackage: String?,
    val vendorPackageEnabled: Boolean,
    val vendorPackageIsSystem: Boolean,
    val vendorPackageMatchesPlatformSignature: Boolean,
    val vendorPackageSignerSha256: Set<String>,
    val serviceAttestation: CertifiedVendorServiceAttestation?,
)

/**
 * What one live PServer attestation admitted, on this boot, for this process.
 *
 * **A record of a probe, not a credential.**  This limits what the object may be used for:
 *  - Built-in access is confirmed by the live capability probe **alone**: SoC-family posture, vendor
 *    package posture, and [CertifiedVendorStructuralContract] over a fresh attestation.
 *  - **No persisted per-device key and no signed qualification may gate it.** Every field below is
 *    carried for diagnostics, labelling, and logging. None of them is compared against a stored
 *    value, a device list, or a manifest before the route is granted.
 *
 * A profile is therefore only ever as good as the attestation it was built from, and it is rebuilt
 * from a fresh attestation on every posture read. Do not cache one and treat it as standing proof.
 */
internal data class CertifiedVendorProfile(
    val profileId: String,
    /** Descriptive label only. A handheld from an unrecognised vendor still qualifies. */
    val vendor: CertifiedHandheldVendor?,
    /**
     * The characterised Snapdragon family, or `null` for **uncharacterised silicon**.
     *
     * Recorded metadata, never an admission precondition.  `null` means "this build has not reasoned about this SoC" -- the
     * device still gets the sealed vendor command set, and gets no Tweaker tuning. Tuning is
     * refused by a different mechanism entirely, in a different Gradle build; see the class KDoc on
     * [CertifiedVendorAdmissionPolicy].
     */
    val family: CertifiedQualcommFamily?,
    val settingsPackage: String?,
    val serviceName: String,
    /**
     * The **measured** `/system/bin/pservice` digest for this boot, not a compiled-in expectation.
     *
     * **Measured and recorded, never compared.** It was briefly bound into [consentKey] so that a
     * swapped binary voided a stored grant; the ruling of 2026-09-11 retired that consumer along
     * with every other persisted gate. What admits the route is the live structural probe, and this
     * digest now exists so a log line can say which binary answered this boot.
     */
    val attestedExecutableSha256: String,
    val buildFingerprint: String,
) {
    /**
     * Retained as a **diagnostic identity only; it no longer gates access.**
     *
     * One token that names "this profile, on this binary, on this firmware". Nothing in
     * [AndroidCertifiedVendorPrivilegeGateway] reads it to decide posture: under the ruling of
     * 2026-09-11 a persisted per-device key may not gate built-in access, because a firmware or
     * `pservice` change would then silently degrade a device that still passes the live probe. Keep
     * it for logs and bug reports; do not reintroduce it as a precondition.
     */
    val consentKey: String
        get() = "$profileId|$attestedExecutableSha256|$buildFingerprint"
}

/**
 * What a legitimate vendor `pservice` looks like on **any** handheld, expressed without naming one.
 *
 *  Every axis below is a
 * structural property of the vendor contract itself, so a handheld nobody has seen yet satisfies it
 * on the same terms the RP5 and the Odin 3 do -- with no new code.
 *
 * **What each axis actually buys, stated honestly, because every one of these answers is
 * self-reported by the very service being attested:**
 * - [ProtocolMarker] is the load-bearing one. It is the only axis a *wrong* service fails by
 *   construction: an unrelated binder that happens to be registered under this name cannot echo
 *   this marker back through `transact(0, stringArray)`. This is the check that stops us firing a
 *   blind privileged transaction at an ABI we do not understand.
 * - uid/gid/SELinux domain/executable path establish that the responder claims to be the vendor
 *   daemon rather than a shell or an ordinary app service. Against an honest responder these are
 *   decisive; against a lying one they are worth nothing, and neither is a digest.
 * - The digest is required to be *well-formed*, not to equal a constant. Requiring 64 hex characters
 *   proves a `sha256sum` probe ran and produced a digest-shaped answer; it does not prove which
 *   binary answered. Binary identity is **deliberately not enforced anywhere**, here or downstream:
 *   structural evidence is sufficient for the frontend; no persisted per-device value may gate
 *   the route. The measured digest is recorded and logged.
 *
 * `binderDescriptor` is deliberately **recorded but not gated**. The two grounded devices both
 * report an empty descriptor, but an empty descriptor proves nothing that [ProtocolMarker] has not
 * already proved, and requiring it would refuse a future vendor that declares an interface for
 * unrelated reasons.
 */
internal object CertifiedVendorStructuralContract {
    const val ProtocolMarker = "__MIMIR_VENDOR_PSERVER_V1__"
    const val ExpectedUid = "0"
    const val ExpectedGid = "0"
    const val ExpectedSelinuxDomain = "u:r:pservice:s0"
    const val ExpectedExecutablePath = "/system/bin/pservice"

    fun isWellFormedSha256(digest: String?): Boolean =
        digest != null &&
            digest.length == 64 &&
            digest.all { it in '0'..'9' || it in 'a'..'f' }

    fun isStructurallyValid(attestation: CertifiedVendorServiceAttestation): Boolean =
        attestation.serviceName == CertifiedVendorAdmissionPolicy.ServiceName &&
            attestation.protocolId == CertifiedVendorAdmissionPolicy.ProtocolId &&
            attestation.uid == ExpectedUid &&
            attestation.gid == ExpectedGid &&
            attestation.selinuxDomain == ExpectedSelinuxDomain &&
            attestation.executablePath == ExpectedExecutablePath &&
            isWellFormedSha256(attestation.executableSha256.lowercase(Locale.ROOT))
}

/**
 * SoC-family characterization is separate from vendor-access admission.
 * The family table records characterized silicon; it does not restrict vendor access.
 *
 * So there are two questions with two different answers, and they must never be collapsed:
 *  - **Vendor access** -- brightness, recents, the sealed command set -- is granted on the strength
 *    of an attested `pservice` ALONE, on any silicon.
 *  - **Tweaker tuning** -- profiles, derates, fan, anything reading CPU policy layout or thermal
 *    headroom -- stays behind the characterised family table AND signed qualification.
 *
 * An unlisted Snapdragon with a genuine `pservice` gets brightness and gets no Tweaker.
 *
 * **The rule, stated so an unseen device's outcome is predictable.** Built-in access is granted when
 * all three hold, and refused the moment any one does not:
 *  1. The vendor settings package, **if one is present at all**, is enabled, a system package, and
 *     signed with the running firmware's platform key. Absent vendor package is not a refusal -- a
 *     handheld from a vendor this build has never heard of has no such package and still qualifies.
 *  2. A live `PServerBinder` returned a side-effect-free attestation.
 *  3. That attestation satisfies [CertifiedVendorStructuralContract] -- structure only, no identity.
 *
 * Those three, and **nothing after them**.  A successful admission here is the whole decision; no caller may add a persisted key
 * or a signed manifest on top of it.
 *
 * [family] is still resolved, but only to *record* whether the silicon is characterised. It is
 * carried on the profile and folded into [CertifiedVendorProfile.profileId], so an uncharacterised
 * device is visibly labelled in logs and diagnostics rather than silently pooled with a known
 * family. That label never changes whether the route is granted.
 *
 * Nothing here consults a device list, a model name, a firmware fingerprint, a vendor signing key,
 * or an expected binary digest. Vendor and model are read only to label the resulting profile and
 * to catch a self-contradictory device; they never decide admission.
 *
 * **Where tuning is refused instead, since it is deliberately NOT refused here.** Nothing in this
 * file, and nothing in the `app` module, can grant Tweaker tuning. That decision lives in the
 * separate `mimir-tweaker` Gradle build behind three independent mechanisms, none of which shares a
 * predicate with this policy:
 *  - `ProductQualificationRuntime` admits a profile only when a signed manifest matches BOTH
 *    `hardwareFingerprint` and `deviceProfile` exactly -- device-exact, far narrower than a family.
 *  - `TuningPlanner.certifiedCpuTargetsKhz` returns null unless the family id is a key of
 *    `MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ` or a member of `ODIN_TRANSPOSITION_ELIGIBLE_FAMILY_IDS`.
 *  - `TweakerHardwareFamilies` is a table of its own, in that build, resolved from `socModel`
 *    independently of [family] here. Widening this one cannot widen that one.
 * The app learns tuning availability only as `TweakerUtilityAvailability` over `TweakerBridge` IPC.
 * [PrivilegedUtilityAuthority.CERTIFIED_VENDOR] never enables a tuning surface -- it selects copy
 * and settings-read routing only.
 *
 * **This stays fail-closed.** The caveat widened which silicon may qualify, not what counts as
 * proof. A missing or failing attestation still yields no profile and therefore no access, exactly
 * as before. Idempotency is still earned by the operation transaction, and every process start
 * revalidates package posture and service from scratch.
 */
internal object CertifiedVendorAdmissionPolicy {
    const val ServiceName = "PServerBinder"
    const val ProtocolId = "pserver-transaction0-string-array-v1"

    fun admit(evidence: CertifiedVendorEvidence): CertifiedVendorProfile? {
        // NOT a precondition.  pservice access alone earns the sealed
        // vendor command set on any silicon. `family` is resolved here purely so the profile can
        // record whether this SoC is characterised, and so the consent key says so out loud.
        val family = family(evidence.socModel)
        if (!vendorPackagePostureAdmissible(evidence)) return null
        val attestation = evidence.serviceAttestation ?: return null
        if (!CertifiedVendorStructuralContract.isStructurallyValid(attestation)) return null
        val vendor = vendor(evidence)
        val vendorLabel = vendor?.name?.lowercase(Locale.ROOT) ?: GenericVendorLabel
        return CertifiedVendorProfile(
            // `-v2` retires the `-v1` identity-gated lineage deliberately: the trust basis changed,
            // so a consent granted under the old basis must not silently carry over to the new one.
            profileId = "$vendorLabel-${familySegment(family, evidence.socModel)}-pserver-v2",
            vendor = vendor,
            family = family,
            settingsPackage = evidence.vendorSettingsPackage,
            serviceName = attestation.serviceName,
            attestedExecutableSha256 = attestation.executableSha256.lowercase(Locale.ROOT),
            buildFingerprint = evidence.buildFingerprint,
        )
    }

    /**
     * Posture of the vendor settings package, when the device has one.
     *
     * A present-but-wrong vendor app is a real signal that something has been tampered with, so it
     * refuses. A device with no vendor app at all is simply a device this build does not recognise,
     * which under the policy is not grounds for refusal -- the `pservice` attestation
     * carries that decision.
     *
     * The platform-signature match is retained because it is one of the few axes here that is
     * verified by `PackageManager` rather than self-reported by the service under test. What was
     * dropped is the pinned *signer digest*: `checkSignatures("android", pkg) == SIGNATURE_MATCH`
     * already proves the package carries the running firmware's own platform key, and pinning
     * *which* platform key merely re-encodes device identity.
     */
    fun vendorPackagePostureAdmissible(evidence: CertifiedVendorEvidence): Boolean {
        val packageName = evidence.vendorSettingsPackage ?: return true
        val vendor = vendor(evidence)
        if (vendor != null && packageName != vendor.settingsPackage) return false
        return evidence.vendorPackageEnabled &&
            evidence.vendorPackageIsSystem &&
            evidence.vendorPackageMatchesPlatformSignature
    }

    fun family(socModel: String): CertifiedQualcommFamily? = when (
        socModel.trim().uppercase(Locale.ROOT)
    ) {
        "SM8250" -> CertifiedQualcommFamily.SNAPDRAGON_865
        "SM8550", "QCS8550" -> CertifiedQualcommFamily.SNAPDRAGON_8_GEN_2
        "SM8750", "CQ8725S", "QCS8750" -> CertifiedQualcommFamily.SNAPDRAGON_8_ELITE
        else -> null
    }

    internal const val GenericVendorLabel = "handheld"
    internal const val UncharacterisedFamilyPrefix = "uncharacterised"

    /**
     * The family segment of [CertifiedVendorProfile.profileId], which lands in every log line and in
     * the diagnostic [CertifiedVendorProfile.consentKey].
     *
     * Uncharacterised silicon is spelled out rather than pooled into one generic bucket, for two
     * reasons. It must be **obvious** to whoever reads a log line that this device's SoC was never
     * reasoned about -- a silently generic id would read like any other admitted handheld. And it
     * must be **per-silicon**: folding in the SoC token means two different uncharacterised chips
     * are told apart in diagnostics instead of sharing one indistinguishable identity.
     *
     * Neither reason is an access decision. Since 2026-09-11 nothing downstream compares this
     * segment, or the id it lands in, against anything stored.
     */
    internal fun familySegment(family: CertifiedQualcommFamily?, socModel: String): String =
        family?.name?.lowercase(Locale.ROOT)
            ?: "$UncharacterisedFamilyPrefix-${socToken(socModel)}"

    /** Reduces an arbitrary vendor SoC string to something safe to embed in a preferences key. */
    private fun socToken(socModel: String): String {
        val collapsed = socModel.trim().lowercase(Locale.ROOT)
            .map { character ->
                if (character in 'a'..'z' || character in '0'..'9') character else '-'
            }
            .joinToString(separator = "")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString(separator = "-")
        return collapsed.ifEmpty { "unknown-soc" }.take(32)
    }

    internal fun vendor(evidence: CertifiedVendorEvidence): CertifiedHandheldVendor? {
        val manufacturer = evidence.manufacturer.trim()
        val brand = evidence.brand.trim()
        val model = evidence.model.trim()
        return when {
            manufacturer.equals("moorechip", ignoreCase = true) ||
                brand.equals("retroid", ignoreCase = true) ||
                model.startsWith("retroid", ignoreCase = true) ->
                CertifiedHandheldVendor.RETROID

            manufacturer.equals("ayn", ignoreCase = true) ||
                brand.equals("ayn", ignoreCase = true) ||
                model.startsWith("odin", ignoreCase = true) ||
                model.contains("thor", ignoreCase = true) ->
                CertifiedHandheldVendor.AYN

            else -> null
        }
    }
}

/**
 * The retired per-device consent record. **Retained, and no longer consulted by any gate.**
 *
 * Persisted device state was removed from the built-in access decision, so
 * [AndroidCertifiedVendorPrivilegeGateway] reads nothing here before granting the vendor route. The
 * interface and its `SharedPreferences` implementation stay so an existing install's records are not
 * destroyed and so the shape is available if a *different* surface ever needs a per-device consent
 * that is genuinely about consent. It must not be reattached to the vendor posture.
 */
internal interface CertifiedVendorConsentStore {
    fun isGranted(consentKey: String): Boolean
    fun grant(consentKey: String)
}

/**
 * Transport contract deliberately contains no command/string execution method. Only fixed Mimir
 * operations can reach the private vendor Binder protocol.
 */
internal interface CertifiedVendorTransport : AutoCloseable, KeyboardDefaultTransport {
    fun isAlive(): Boolean
    fun attest(): CertifiedVendorServiceAttestation?

    /** Deadline-aware admission hook for a replacement HUD transport; normal control admission is unchanged. */
    fun attest(deadlineElapsedRealtimeMs: Long): CertifiedVendorServiceAttestation? = attest()

    /** True only after a bound service died or a different live Binder replaced it. */
    fun isBindingInvalidatedForHud(): Boolean = false

    /** True when this transport has actually bound a service, including one that failed admission. */
    fun hasBoundBinderForHud(): Boolean = false

    /** One fixed, read-only HUD snapshot; an implementation without the route returns null. */
    fun readHudTelemetry(deadlineElapsedRealtimeMs: Long): VendorHudRawSnapshot? = null

    /** Starts the no-argument renderer helper beside its fixed request/record files. */
    fun startEmulatorRenderFps(files: EmulatorRenderFpsHelperFiles): Boolean = false

    /** Starts the helper without allowing Binder startup to outlive the current HUD read. */
    fun startEmulatorRenderFps(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): Boolean = startEmulatorRenderFps(files)

    /** Reads one bounded base64 record line from the helper's fixed record path. */
    fun readEmulatorRenderFpsRecord(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): String? = null

    /** Distinguishes first-publication warmup from a malformed or failed record read. */
    fun readEmulatorRenderFpsRecordResult(
        files: EmulatorRenderFpsHelperFiles,
        deadlineElapsedRealtimeMs: Long,
    ): EmulatorRenderFpsRecordRead =
        readEmulatorRenderFpsRecord(files, deadlineElapsedRealtimeMs)
            ?.let(EmulatorRenderFpsRecordRead::Encoded)
            ?: EmulatorRenderFpsRecordRead.Failed

    /** Resolves only a validated allowlisted foreground package to its current process identity. */
    fun resolveEmulatorRenderFpsTarget(
        files: EmulatorRenderFpsHelperFiles,
        packageName: String,
        deadlineElapsedRealtimeMs: Long,
    ): EmulatorRenderFpsTargetIdentity? = null

    fun readSystemSetting(key: String): VendorTransportRead
    fun writeSystemSetting(key: String, normalizedValue: String): VendorTransportMutation
    fun deleteSystemSetting(key: String): VendorTransportMutation
    fun openSystemRecents(): VendorTransportMutation

    /**
     * Put the device to sleep, for the second stage of Mimir's screen idle.
     *
     *  The first stage dims; this one ends the session. Without it a non-zero Mimir
     * screen timeout held `FLAG_KEEP_SCREEN_ON` for as long as Mimir was focused and merely dimmed to
     * 1%, so the display wakelock never dropped, `SystemSuspend`'s counter never reached zero, and the
     * SoC could not suspend at all — on a launcher, that is most of the device's idle life.
     *
     * Defaults to [VendorTransportMutation.FAILED] rather than being abstract: a transport that cannot
     * sleep the device should report that it did not, and the caller then falls back to releasing
     * keep-screen-on and letting the platform's own timeout take the screen. Enforcement is the
     * privilege; giving the screen back is not.
     */
    fun enforceDeviceSleep(): VendorTransportMutation = VendorTransportMutation.FAILED
    fun forceStopAllowedPackage(packageName: String): VendorTransportMutation

    /**
     * Stop the package and read its liveness back through the SAME success-marker channel, in one
     * privileged transaction.
     *
     * The default implementation is for transports that cannot verify: it reports
     * [VendorTransportStop.ACCEPTED_UNVERIFIED], which says the command was accepted and says nothing
     * about whether anything died. Only a transport that actually probes liveness may return
     * [VendorTransportStop.STOPPED].
     */
    fun forceStopAllowedPackageVerified(packageName: String): VendorTransportStop =
        when (forceStopAllowedPackage(packageName)) {
            VendorTransportMutation.APPLIED -> VendorTransportStop.ACCEPTED_UNVERIFIED
            VendorTransportMutation.TIMED_OUT -> VendorTransportStop.TIMED_OUT
            VendorTransportMutation.FAILED -> VendorTransportStop.FAILED
        }

    /**
     * Evicts ONE task by id. The id is an Int the caller already holds; no package, path, or string
     * argument crosses this seam, so the rendered command cannot carry a caller-supplied token.
     */
    fun removeTask(taskId: Int): VendorTransportMutation = VendorTransportMutation.FAILED

    /**
     * Focuses ONE task by id. The id is an Int the caller already holds; no package, path, or
     * string argument crosses this seam, so the rendered command cannot carry caller-supplied text.
     */
    fun focusTask(taskId: Int): VendorTransportMutation = VendorTransportMutation.FAILED

    /**
     * The one-line reply to the fixed task-resolver command, verbatim, or null when none arrived.
     *
     * This channel returns only a command's FIRST output line (Thor, build `23ca3300`, 2026-09-11:
     * a 62-byte reply for a table that prints in full under `adb`), so the selection happens inside
     * the command and the reply is the answer. Null means "nothing could be read", which the consumer
     * treats as "no task id" -- never as evidence about a task.
     */
    fun resolveTaskReply(packageName: String, displayId: Int): String? = null

    /**
     * The tri-state answer behind [resolveTaskReply]'s raw reply: a positively-found task, a positively-empty
     * reply (the resolver command ran and matched nothing for this package), or the probe could not answer at
     * all. R6-P0-02 review fix (BLOCKING): [resolveTaskReply]'s null collapses "ran and found nothing" and
     * "never got an answer" into the same value, and [parkedGameProvenGone] must never treat those alike -- a
     * PServer timeout landing mid-PLAY-press must retain the parked lease, not end its session and orphan the
     * live emulator underneath a newly admitted game (Thor finding). Default `Unavailable("unavailable")` is
     * for transports with no route of their own.
     */
    fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome =
        VendorTaskOutcome.Unavailable("unavailable")

    /**
     * The one-line reply to the fixed, read-only process-presence probe for ONE package, verbatim, or
     * null when no reply arrived.
     *
     * The probe runs `pidof` and echoes a presence marker; it stops nothing and writes nothing. Null means
     * nothing could be read -- a transport without the route, a failed or timed-out transaction -- which
     * the gateway reports as UNKNOWN, never as absence.
     */
    fun processPresenceReply(packageName: String): String? = null

    /**
     * The one-line reply to the fixed, read-only RetroArch cores listing ([ListRetroArchCores]) for one allowlisted
     * package, verbatim, or null when none arrived (no route, an unlisted package, a failed or timed-out call).
     * Null and every unparseable reply are read as Unknown, never as an empty folder.
     */
    fun listRetroArchCoresReply(userId: Int, packageName: String): String? = null

    /**
     * What the display manager holds as display [displayId]'s brightness: its own `getBrightness`, called by
     * the number [transactions] read from this device's framework ([FrameworkDisplayTransactions]). The
     * display id is an Int and the number is an Int, so no caller text reaches the rendered command.
     *
     * The default is for transports without the route: [VendorDisplayRead.Failed], which asserts nothing.
     */
    fun readDisplayBrightness(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
    ): VendorDisplayRead = VendorDisplayRead.Failed

    /**
     * One `setBrightness(displayId, fraction)` on the display manager. APPLIED says only that the call returned
     * without an exception; whether the panel's value changed is [readDisplayBrightness]'s to say.
     */
    fun writeDisplayBrightness(
        transactions: DisplayBrightnessTransactions,
        displayId: Int,
        fraction: Float,
    ): VendorTransportMutation = VendorTransportMutation.FAILED

    override fun close()
}

internal sealed interface VendorTransportRead {
    data class Value(val value: String?) : VendorTransportRead
    data object Failed : VendorTransportRead
}

/** One display-brightness read through the vendor route, sorted by what it proves. */
internal sealed interface VendorDisplayRead {
    /** The display manager answered with a brightness in 0..1. */
    data class Value(val fraction: Float) : VendorDisplayRead

    /** The display manager answered, but not with a brightness: NaN for a display with none of its own. */
    data object NoBrightness : VendorDisplayRead

    /**
     * The route answered, but not with a display-manager reply -- an exception, no such service, a denial.
     * Deterministic on a device, so it closes the vendor display route for the process rather than being
     * retried on every slider step.
     */
    data object Refused : VendorDisplayRead

    /**
     * Nothing came back at all: no call, or an empty reply. Says nothing about the display manager, so it
     * closes nothing either.
     */
    data object Failed : VendorDisplayRead
}

internal enum class VendorTransportMutation {
    APPLIED,
    FAILED,
    TIMED_OUT,
}

/**
 * What one privileged stop transaction actually proved.
 *
 * Device incident, AYN Thor 2026-09-11 09:34: `✕` on Chrome logged
 * `ActivityManager: forceStopPackage() WhiteList packageName=com.android.chrome`, `am force-stop`
 * exited 0, Mimir logged `mutation=APPLIED`, and Chrome's task stayed alive on display 4 and came
 * back from Android Recents. An exit code is therefore NOT a close on this firmware; only the
 * liveness readback is. [STOPPED] is the sole value that carries that evidence.
 */
internal enum class VendorTransportStop {
    /** The success marker arrived, which the privileged shell echoes only when no process survives. */
    STOPPED,
    /** The transaction completed without the marker: a process for this package is still alive. */
    STILL_ALIVE,
    /** A transport with no liveness probe accepted the command. Dispatch evidence, never a close. */
    ACCEPTED_UNVERIFIED,
    FAILED,
    TIMED_OUT,
}

/** A close attempt's typed result, liveness included, as reported by the selected authority. */
internal enum class PrivilegedCloseOutcome {
    /** Verified: the stop ran and the package has no live process left. */
    STOPPED,

    /** The stop ran and a live process remains -- the task must be evicted to finish the close. */
    STILL_ALIVE,

    /** Accepted by an authority that cannot prove liveness. */
    REQUEST_UNVERIFIED,
    NOT_ALLOWED,
    AUTHORITY_UNAVAILABLE,
    FAILED,
}

/** The result of evicting one task by id. [REMOVED] means the privileged command reported success. */
internal enum class PrivilegedTaskRemoval {
    REMOVED,
    NOT_ALLOWED,
    AUTHORITY_UNAVAILABLE,
    FAILED,
}

/** The result of focusing one retained task by its exact id. */
internal enum class PrivilegedTaskFocus {
    FOCUSED,
    NOT_ALLOWED,
    AUTHORITY_UNAVAILABLE,
    FAILED,
}

/**
 * What one privileged process-presence probe proved about one package.
 *
 * [ABSENT] means the probe ran `pidof` and found no process; it is the only value a Tabs row may be
 * retired on. Everything that is not an answer -- a package the stop policy refuses, no authority, no
 * reply, a device without `pidof`, a timeout -- is [UNKNOWN], and nothing maps UNKNOWN to ABSENT.
 */
internal enum class PrivilegedPackageLiveness {
    ALIVE,
    ABSENT,
    UNKNOWN,
}

/**
 * R6-P0-02 review fix (BLOCKING): what a task-resolution probe actually proved, so a caller that must not
 * confuse "gone" with "couldn't tell" (a parked lease's release decision) has a value to read instead of a
 * lossy `Int?`. [reason] is one of `unavailable` (policy/profile/posture declined before any transaction, or
 * the transaction itself failed), `timeout` (the transaction timed out), or `parse` (a completed reply that
 * matched neither a task line nor a definitively-empty one).
 */
internal sealed interface VendorTaskOutcome {
    data class Found(val taskId: Int) : VendorTaskOutcome
    data object NoTask : VendorTaskOutcome
    data class Unavailable(val reason: String) : VendorTaskOutcome
}

/**
 * The deterministic-close half of the privileged contract: a stop whose liveness is read back, plus
 * the task eviction that finishes a close when the process survives the stop.
 *
 * Kept separate from [PrivilegedUtilityGateway] on purpose -- this is a two-step close protocol with
 * its own vocabulary, not another fire-and-forget utility operation.
 */
internal interface PrivilegedTaskCloseGateway {
    fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome
    fun removeTask(taskId: Int): PrivilegedTaskRemoval

    /** Brings the exact retained task to the foreground without starting a new activity intent. */
    fun focusTask(taskId: Int): PrivilegedTaskFocus = PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE

    /**
     * The task id this package currently holds on [displayId], or null when none can be proved.
     *
     * The visible task wins, because that is the one the player is looking at; a package with only
     * background tasks on that display still answers, since a background task is exactly what keeps
     * appearing in Android Recents after a whitelisted force-stop.
     */
    fun resolveTaskId(packageName: String, displayId: Int): Int? = null

    /**
     * The tri-state answer behind [resolveTaskId] -- see [VendorTaskOutcome]. Default forwards to
     * [resolveTaskId] so no other implementer needs a body of its own: a found id becomes [VendorTaskOutcome.Found],
     * and null (which [resolveTaskId] never distinguishes) becomes `Unavailable("unavailable")`, the safe
     * (never-release) reading for an implementer that has not been taught the finer distinction.
     */
    fun resolveTaskOutcome(packageName: String, displayId: Int): VendorTaskOutcome =
        resolveTaskId(packageName, displayId)?.let(VendorTaskOutcome::Found)
            ?: VendorTaskOutcome.Unavailable("unavailable")

    /**
     * Whether a live process runs under [packageName], read back by a privileged probe that stops nothing.
     *
     * UNKNOWN by default: an authority without such a probe has no
     * answer to give, and a Tabs row may only be retired on an answer.
     */
    fun packageLiveness(packageName: String): PrivilegedPackageLiveness = PrivilegedPackageLiveness.UNKNOWN

    data object None : PrivilegedTaskCloseGateway {
        override fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome =
            PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE

        override fun removeTask(taskId: Int): PrivilegedTaskRemoval =
            PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE

        override fun focusTask(taskId: Int): PrivilegedTaskFocus =
            PrivilegedTaskFocus.AUTHORITY_UNAVAILABLE

        override fun resolveTaskId(packageName: String, displayId: Int): Int? = null

        override fun packageLiveness(packageName: String): PrivilegedPackageLiveness =
            PrivilegedPackageLiveness.UNKNOWN
    }
}

/**
 * Legacy projection for [PrivilegedUtilityGateway.forceStopLaunchTarget] callers (the pre-dispatch
 * launch reaper). REQUEST_ACCEPTED keeps meaning "the stop was accepted"; a verified survivor is a
 * FAILED stop, so `performLaunchTargetReap` runs its best-effort kill instead of believing the exit
 * code -- which is the behaviour the Thor incident showed was missing.
 */
internal fun PrivilegedCloseOutcome.asLaunchTargetStop(): PrivilegedLaunchTargetStop = when (this) {
    PrivilegedCloseOutcome.STOPPED,
    PrivilegedCloseOutcome.REQUEST_UNVERIFIED,
    -> PrivilegedLaunchTargetStop.REQUEST_ACCEPTED
    PrivilegedCloseOutcome.STILL_ALIVE -> PrivilegedLaunchTargetStop.FAILED
    PrivilegedCloseOutcome.NOT_ALLOWED -> PrivilegedLaunchTargetStop.NOT_ALLOWED
    PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE -> PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE
    PrivilegedCloseOutcome.FAILED -> PrivilegedLaunchTargetStop.FAILED
}

/** How an authority with no liveness probe reports a stop it only dispatched. */
internal fun PrivilegedLaunchTargetStop.asUnverifiedCloseOutcome(): PrivilegedCloseOutcome = when (this) {
    PrivilegedLaunchTargetStop.REQUEST_ACCEPTED -> PrivilegedCloseOutcome.REQUEST_UNVERIFIED
    PrivilegedLaunchTargetStop.NOT_ALLOWED -> PrivilegedCloseOutcome.NOT_ALLOWED
    PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE -> PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE
    PrivilegedLaunchTargetStop.FAILED -> PrivilegedCloseOutcome.FAILED
}

/** Liveness as the log should print it: unverified routes must never claim `alive=false`. */
internal fun PrivilegedCloseOutcome.aliveLogValue(): String = when (this) {
    PrivilegedCloseOutcome.STOPPED -> "false"
    PrivilegedCloseOutcome.STILL_ALIVE -> "true"
    PrivilegedCloseOutcome.REQUEST_UNVERIFIED,
    PrivilegedCloseOutcome.NOT_ALLOWED,
    PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE,
    PrivilegedCloseOutcome.FAILED,
    -> "unknown"
}
