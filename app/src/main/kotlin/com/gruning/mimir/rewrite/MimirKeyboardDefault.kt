package com.gruning.mimir.rewrite

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * W2f : Mimir sets Mimir Keyboard as the system default input method through the
 * certified vendor PServer bridge, hardened first, ON by default (restored the 2026-10-03
 * default after the secret-path hardening landed), with a Settings switch, and respecting a
 * player who switches to another keyboard. This REVERSES the 2026-09-15 "Picker shortcut" ruling that Mimir
 * never writes the default (see the amended header of `MimirKeyboardPickerNative.kt`).
 *
 * The bridge rule stands: no caller-supplied command, path, key or value; every operation fails closed and is
 * proven by a readback.
 */

/** What one keyboard-default transaction proved. */
internal enum class KeyboardDefaultResult {
    /** The readbacks show the asked-for state: Mimir Keyboard is the default (set) or no longer is (reset). */
    APPLIED,

    /** Nothing was asked of the platform because the readback already showed there was nothing to do. */
    UNCHANGED,

    /**
     * The select was sent and accepted, but its readback could not settle it: the default was unreadable, or it is
     * Mimir's with the listing unproven. Mimir may well be the default, so nothing is rolled back -- the next
     * observation decides by readback (Mimir's id: on; anything else: the player switched, so off for good).
     */
    UNCONFIRMED,

    /**
     * Turning off found Mimir the default and the ONLY enabled keyboard (D32 / OD3): disabling it would leave none, so
     * nothing was sent and Mimir stays the default. Only the Settings switch's turn-off can return this; the safety
     * revert falls through to `ime reset` instead.
     */
    NO_OTHER_KEYBOARD,

    /** Anything else: a refused or unreadable step, or a readback that did not match. Never retried here. */
    FAILED,

    /** No live certified vendor authority, so no command was built or sent. */
    UNAVAILABLE,
}

/** The fixed keyboard-default operations of the vendor bridge, fail-closed by default. */
internal interface PrivilegedKeyboardDefaultGateway {
    /**
     * Enable and select Mimir Keyboard, then read back that it is the default and is listed. [shouldContinue] is
     * asked immediately before each mutation: false (the player has picked another keyboard meanwhile) cancels the
     * rest, so a pending select never overrides the player.
     */
    fun setMimirKeyboardDefault(shouldContinue: () -> Boolean = { true }): KeyboardDefaultResult =
        KeyboardDefaultResult.UNAVAILABLE

    /** The authoritative readback of whether Mimir Keyboard is the default now; null when it cannot be read. */
    fun mimirKeyboardIsDefault(): Boolean? = null

    /**
     * The Settings switch's turn-off: `ime disable` of Mimir Keyboard, only while it is the default AND another
     * keyboard is enabled, then read back that it no longer is the default. Never `ime reset`; with no other keyboard
     * nothing is sent ([KeyboardDefaultResult.NO_OTHER_KEYBOARD]).
     */
    fun resetKeyboardDefault(): KeyboardDefaultResult = KeyboardDefaultResult.UNAVAILABLE

    /**
     * The safety revert's turn-off: as [resetKeyboardDefault], except that with Mimir the only enabled keyboard it
     * falls through to the fixed `ime reset`, which must win over a crash loop.
     */
    fun revertKeyboardDefaultForSafety(): KeyboardDefaultResult = KeyboardDefaultResult.UNAVAILABLE

    data object None : PrivilegedKeyboardDefaultGateway
}

/**
 * The transport half: seven fixed operations, none of which accepts anything but the IME id that
 * `mimirKeyboardImeId` recomputes from the app's own context and `VendorPServerOperation` re-validates.
 * Defaults fail closed so a transport that does not carry the route asserts nothing.
 */
internal interface KeyboardDefaultTransport {
    fun enableKeyboard(imeId: String): VendorTransportMutation = VendorTransportMutation.FAILED
    fun selectKeyboard(imeId: String): VendorTransportMutation = VendorTransportMutation.FAILED

    /** `settings get secure default_input_method`: [VendorTransportRead.Value] with null for "null". */
    fun readDefaultKeyboard(): VendorTransportRead = VendorTransportRead.Failed

    /** APPLIED only when `ime list -s` contains exactly the id. */
    fun keyboardIsEnabled(imeId: String): VendorTransportMutation = VendorTransportMutation.FAILED

    /** `ime disable <id>` (op D'): APPLIED only when the call exited 0; whether it worked is the readback's to say. */
    fun disableKeyboard(imeId: String): VendorTransportMutation = VendorTransportMutation.FAILED

    /**
     * Readback E: the first enabled keyboard that is not [imeId] ([VendorTransportRead.Value] with null for an empty
     * reply, which is the real answer "none"); [VendorTransportRead.Failed] when the call itself failed.
     */
    fun readOtherKeyboard(imeId: String): VendorTransportRead = VendorTransportRead.Failed

    /** The conditional `ime reset` (op D, the safety revert's last resort): sent only; the readback says if it worked. */
    fun resetKeyboards(imeId: String): VendorTransportMutation = VendorTransportMutation.FAILED

    /** Drops any cached attestation so the next admission probes the live service again. */
    fun forgetAttestation() = Unit
}

/**
 * The orchestration, kept apart from the Binder so every verdict is unit-testable: which readbacks decide, and
 * what any other outcome is.
 */
internal object MimirKeyboardDefaultOps {

    fun isDefault(transport: KeyboardDefaultTransport, imeId: String): Boolean? =
        when (val read = transport.readDefaultKeyboard()) {
            is VendorTransportRead.Value -> read.value == imeId
            VendorTransportRead.Failed -> null
        }

    /**
     * Op A (enable, then select) with readbacks B (the default setting equals the id) and C (`ime list -s`
     * contains it). APPLIED only when BOTH match; a refused step or a readable mismatch is FAILED, with no retry;
     * an accepted select whose readback cannot settle it is UNCONFIRMED.
     * Idempotent: when B and C already match, nothing is sent.
     */
    fun set(
        transport: KeyboardDefaultTransport,
        imeId: String,
        onTimedOut: () -> Unit = {},
        authorize: () -> Boolean = { true },
    ): KeyboardDefaultResult {
        if (!VendorPServerOperation.isMimirKeyboardImeId(imeId)) return KeyboardDefaultResult.FAILED
        if (isDefault(transport, imeId) == true &&
            transport.keyboardIsEnabled(imeId) == VendorTransportMutation.APPLIED
        ) {
            return KeyboardDefaultResult.APPLIED
        }
        if (!mutate(authorize, onTimedOut) { transport.enableKeyboard(imeId) }) return KeyboardDefaultResult.FAILED
        if (!mutate(authorize, onTimedOut) { transport.selectKeyboard(imeId) }) return KeyboardDefaultResult.FAILED
        val readbackB = isDefault(transport, imeId)
        val readbackC = transport.keyboardIsEnabled(imeId) == VendorTransportMutation.APPLIED
        return when {
            readbackB == true && readbackC -> KeyboardDefaultResult.APPLIED
            // Readable and not ours: select did not take, or the player moved on. Definitely not applied.
            readbackB == false -> KeyboardDefaultResult.FAILED
            // Unreadable, or ours with the listing unproven: select was accepted, so do not claim otherwise.
            else -> KeyboardDefaultResult.UNCONFIRMED
        }
    }

    /**
     * Turning Mimir Keyboard off (D32). Readback B first: another default means there is nothing to restore and
     * nothing is sent (UNCHANGED); an unreadable default is FAILED, never a send on a guess. B is no longer what
     * protects a concurrent pick (the platform serialises that, see below); it only avoids a needless send.
     *
     * Readback E: is another keyboard enabled (unreadable: FAILED).
     *  - Yes: attest, then op D' (`ime disable`). The "is it still the current one" decision runs inside the
     *    input-method service under its own lock, so a keyboard the player picks at the same moment is never
     *    overwritten, and the player's other keyboards stay enabled. Then B' (the default is no longer Mimir's, or is
     *    unset: APPLIED; still Mimir's or unreadable: FAILED). C (is Mimir still listed) is only noted for the log.
     *  - No: nothing is sent and the verdict is [KeyboardDefaultResult.NO_OTHER_KEYBOARD], because disabling the only
     *    keyboard would leave none -- unless [fallbackToReset] (the safety revert, which must win over a crash
     *    loop): then attest and send op D, the conditional `ime reset`, and read B' back the same way.
     */
    fun reset(
        transport: KeyboardDefaultTransport,
        imeId: String,
        onTimedOut: () -> Unit = {},
        authorize: () -> Boolean = { true },
        fallbackToReset: Boolean = false,
        onNote: (String) -> Unit = {},
    ): KeyboardDefaultResult {
        if (!VendorPServerOperation.isMimirKeyboardImeId(imeId)) return KeyboardDefaultResult.FAILED
        when (isDefault(transport, imeId)) {
            null -> return KeyboardDefaultResult.FAILED
            false -> return KeyboardDefaultResult.UNCHANGED
            true -> Unit
        }
        val otherEnabled = anotherKeyboardEnabled(transport, imeId) ?: return KeyboardDefaultResult.FAILED
        val sent = when {
            otherEnabled -> mutate(authorize, onTimedOut) { transport.disableKeyboard(imeId) }
            fallbackToReset -> mutate(authorize, onTimedOut) { transport.resetKeyboards(imeId) }
            else -> return KeyboardDefaultResult.NO_OTHER_KEYBOARD
        }
        if (!sent) return KeyboardDefaultResult.FAILED
        val verdict = when (val after = transport.readDefaultKeyboard()) {
            is VendorTransportRead.Value ->
                if (after.value != imeId && (after.value == null || ImeIdShape.matches(after.value))) {
                    KeyboardDefaultResult.APPLIED
                } else {
                    KeyboardDefaultResult.FAILED
                }
            VendorTransportRead.Failed -> KeyboardDefaultResult.FAILED
        }
        if (otherEnabled) {
            onNote("vendor keyboardDefault disable stillListed=${transport.keyboardIsEnabled(imeId) == VendorTransportMutation.APPLIED}")
        }
        return verdict
    }

    /** Readback E: one input-method-id line that is not Mimir's is "another keyboard is enabled"; anything else is none. */
    private fun anotherKeyboardEnabled(transport: KeyboardDefaultTransport, imeId: String): Boolean? =
        when (val read = transport.readOtherKeyboard(imeId)) {
            is VendorTransportRead.Value -> read.value?.let { it != imeId && ImeIdShape.matches(it) } ?: false
            VendorTransportRead.Failed -> null
        }

    /** What a real default looks like, so a stray error line in the reply is never read as "no longer ours". */
    private val ImeIdShape = Regex("[A-Za-z0-9._$]+/[A-Za-z0-9._$]+")

    /**
     * One mutation, authorised IMMEDIATELY before it runs ([authorize] is the gateway's fresh attestation of the
     * live service) -- a false answer sends nothing.
     */
    private inline fun mutate(
        authorize: () -> Boolean,
        onTimedOut: () -> Unit,
        run: () -> VendorTransportMutation,
    ): Boolean {
        if (!authorize()) return false
        val result = run()
        if (result == VendorTransportMutation.TIMED_OUT) onTimedOut()
        return result == VendorTransportMutation.APPLIED
    }
}

/** What the start / observer pass should do. */
internal enum class KeyboardDefaultStep { NOTHING, APPLY, TURN_PREFERENCE_OFF }

/**
 * The respect-the-user rule, pure.
 *
 * @param enabled the stored "Mimir Keyboard as default" preference (default ON: only a MISSING preference reads ON; an explicit stored OFF stays OFF).
 * @param wasDefault Mimir Keyboard became, or was ATTEMPTED to become, the default under this preference (Mimir
 *   set it or tried to, or the player did): the intent is recorded before the mutation, so a different default
 *   afterwards is the player's choice even if the readback never confirmed ours.
 * @param currentDefault the platform's default input method id now; null or blank is "unknown", never a switch.
 */
internal fun mimirKeyboardDefaultStep(
    enabled: Boolean,
    wasDefault: Boolean,
    currentDefault: String?,
    imeId: String,
    bridgeReady: Boolean,
): KeyboardDefaultStep = when {
    !enabled -> KeyboardDefaultStep.NOTHING
    currentDefault == imeId -> KeyboardDefaultStep.NOTHING
    // The default was Mimir's and is now somebody else's: the player chose a keyboard in Android. Never fight.
    wasDefault && !currentDefault.isNullOrBlank() -> KeyboardDefaultStep.TURN_PREFERENCE_OFF
    wasDefault -> KeyboardDefaultStep.NOTHING
    !bridgeReady -> KeyboardDefaultStep.NOTHING
    else -> KeyboardDefaultStep.APPLY
}

/** The two persisted bits. */
internal interface MimirKeyboardDefaultStore {
    /** The player-facing preference: Mimir Keyboard as the system default. ON until the player or Android says no. A missing key reads ON; a stored false (the switch turned off, a switch-away, the safety revert) is read back as OFF and never re-applied. */
    var enabled: Boolean

    /** Mimir Keyboard has been the default under this preference, so a different one now is the player's choice. */
    var wasDefault: Boolean

    /**
     * One DURABLE write of the named bits together (null leaves a bit alone): true only when it reached storage. The
     * writes that gate an automatic attempt (the intent before a mutation, and every OFF) go through this and are
     * checked; a plain in-memory store simply assigns.
     */
    fun write(enabled: Boolean? = null, wasDefault: Boolean? = null): Boolean {
        if (enabled != null) this.enabled = enabled
        if (wasDefault != null) this.wasDefault = wasDefault
        return true
    }
}

internal class SharedPrefsMimirKeyboardDefaultStore(context: Context) : MimirKeyboardDefaultStore {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KeyEnabled, true)
        set(value) { write(enabled = value) }

    override var wasDefault: Boolean
        get() = prefs.getBoolean(KeyWasDefault, false)
        set(value) { write(wasDefault = value) }

    /** commit(), not apply(): the write is on disk before the caller goes on, and its result is reported. */
    override fun write(enabled: Boolean?, wasDefault: Boolean?): Boolean {
        val edit = prefs.edit()
        if (enabled != null) edit.putBoolean(KeyEnabled, enabled)
        if (wasDefault != null) edit.putBoolean(KeyWasDefault, wasDefault)
        return edit.commit()
    }

    private companion object {
        const val PrefsName = "mimir_keyboard_default"
        const val KeyEnabled = "enabled"
        const val KeyWasDefault = "wasDefault"
    }
}

/**
 * What Mimir's own apply (or reset) in flight shares, process-wide, with the default-changed observer.
 *
 * Observations DURING a session are not acted on one by one -- Mimir's own enable fires the observer while the
 * default is still the old keyboard, and its select makes the default Mimir's, so a single event proves nothing.
 * The one thing a session does between operations is [cancelIfPlayerChose], asked right before each mutation; the
 * verdict is a single fresh readback when the session ends ([MimirKeyboardDefaultController.finalizeApply]).
 */
internal class KeyboardApplySession {
    /** Guards the read-then-write of the default-keyboard bits across the apply and the observer. */
    val lock = Any()

    @Volatile
    var active = false
        private set

    @Volatile
    var preApplyDefault: String? = null
        private set

    @Volatile
    var cancelled = false
        private set

    /**
     * A write that gates an automatic attempt did not reach storage: nothing automatic may run for the rest of this
     * process (it could otherwise repeat an attempt the stored record does not know about).
     */
    @Volatile
    var automaticBlocked = false
        private set

    fun blockAutomatic() {
        automaticBlocked = true
    }

    fun begin(preApplyDefault: String?) {
        this.preApplyDefault = preApplyDefault
        cancelled = false
        active = true
    }

    fun end() {
        active = false
    }

    /**
     * Before a mutation: a default that is neither Mimir's id nor the one the apply started from is the player's
     * own choice, so the rest of the apply is cancelled. (A player who goes back to the starting keyboard cannot be
     * told from nothing happening here; the fresh readback at the end decides that case.)
     */
    fun cancelIfPlayerChose(current: String?, imeId: String): Boolean {
        if (!current.isNullOrBlank() && current != imeId && current != preApplyDefault) cancelled = true
        return cancelled
    }
}

/** Decides and runs the default-keyboard transitions. Blocking: call it off the main thread. */
internal class MimirKeyboardDefaultController(
    private val store: MimirKeyboardDefaultStore,
    private val gateway: PrivilegedKeyboardDefaultGateway,
    private val bridgeReady: () -> Boolean,
    private val currentDefault: () -> String?,
    private val imeId: String,
    /** Shared process-wide, so the observer's controller sees the apply the Settings controller is running. */
    private val session: KeyboardApplySession = KeyboardApplySession(),
    /** D38: the keyboard-safety trail. A revert it still owes (its durable OFF never landed) is retried at start. */
    private val safetyTrail: ImeSafetyStore? = null,
    /** D38: may an AUTOMATIC apply run? False while the keyboard cannot be trusted (see [imeKeyboardTrusted]). */
    private val keyboardTrusted: () -> Boolean = { true },
) {
    /**
     * Process start: apply once if ON, ready, trusted and not already the default; respect a switch-away. A safety
     * revert still owed is settled FIRST, so nothing automatic can run on a stored ON that the revert meant to undo.
     */
    fun onStart(): KeyboardDefaultStep {
        settleOwedRevert()
        return settle(applyAllowed = true)
    }

    /**
     * D38: the safety revert's OFF commit may have failed (twice) in a process that then died. The demand is on disk,
     * so retry the OFF here; only a write that reached storage settles the debt, and until it does
     * [decideAndRecord] refuses every automatic apply (the trail reads as untrusted while the demand stands).
     */
    private fun settleOwedRevert() {
        val trail = safetyTrail ?: return
        if (!trail.read().revertDemanded) return
        synchronized(session.lock) {
            if (!store.write(enabled = false, wasDefault = false)) {
                session.blockAutomatic()
                return
            }
            trail.clearRevertDemand()
        }
    }

    /** The default changed: only the respect rule runs here, never an apply. */
    fun onDefaultChanged(): KeyboardDefaultStep = settle(applyAllowed = false)

    private fun settle(applyAllowed: Boolean): KeyboardDefaultStep {
        // Not acted on one by one while Mimir is applying: the single fresh readback at the end decides.
        if (session.active) return KeyboardDefaultStep.NOTHING
        val ready = applyAllowed && bridgeReady()
        val step: KeyboardDefaultStep
        // Read-decide-write under the process-wide lock, so no apply can finish between this read and the write.
        synchronized(session.lock) {
            if (session.active) return KeyboardDefaultStep.NOTHING
            step = decideAndRecord(currentDefault(), ready)
        }
        if (step == KeyboardDefaultStep.APPLY) applyAndFinalize()
        return step
    }

    /** The respect rule and its bookkeeping writes; APPLY only records the intent, the caller runs the apply. */
    private fun decideAndRecord(current: String?, ready: Boolean): KeyboardDefaultStep {
        // A durable write failed earlier in this process: no automatic attempt, and nothing to record.
        if (session.automaticBlocked) return KeyboardDefaultStep.NOTHING
        val step = mimirKeyboardDefaultStep(
            enabled = store.enabled,
            wasDefault = store.wasDefault,
            currentDefault = current,
            imeId = imeId,
            bridgeReady = ready,
        )
        when (step) {
            KeyboardDefaultStep.TURN_PREFERENCE_OFF -> {
                if (!store.write(enabled = false, wasDefault = false)) session.blockAutomatic()
            }
            KeyboardDefaultStep.APPLY -> {
                // D38: a keyboard that cannot be trusted (a revert is owed, or a crash trail says so) is never applied
                // automatically; nothing is recorded, so the intent is not on file for an apply that never ran.
                if (!runCatching { keyboardTrusted() }.getOrDefault(false)) return KeyboardDefaultStep.NOTHING
                // The INTENT is recorded before the mutation: if select lands but its readback fails,
                // any later different default still counts as the player switching away. It must reach storage: if it
                // does not, the bridge is never asked, and nothing automatic runs again in this process.
                if (!store.write(wasDefault = true)) {
                    session.blockAutomatic()
                    return KeyboardDefaultStep.NOTHING
                }
            }
            KeyboardDefaultStep.NOTHING ->
                if (store.enabled && current == imeId && !store.write(wasDefault = true)) session.blockAutomatic()
        }
        return step
    }

    /**
     * THE invariant: when an apply ends, the preference equals a FRESH readback of the default.
     *  - Mimir is the default: on, intent recorded.
     *  - Anything else that can be read -- including the keyboard the apply started from, to which the player may
     *    have gone straight back -- is the player's choice or a select that did not take: off, never re-applied.
     *  - Nothing was sent (no live bridge): the preference stands for a later start, the intent is withdrawn.
     *  - The readback itself is unreadable: an accepted select stays on, unconfirmed; anything else is off.
     */
    private fun finalizeApply(result: KeyboardDefaultResult) {
        // D33 (d): ONE fresh read, taken under the lock right before the write, so the observer cannot record an
        // Off between them. A player who switches in the instant AFTER the read is caught by the recheck that
        // follows the session's end ([applyAndFinalize]); the observer never acts while the session is open.
        synchronized(session.lock) {
            val fresh = currentDefault()
            writeFinalState(fresh, result)
        }
    }

    private fun writeFinalState(fresh: String?, result: KeyboardDefaultResult) {
        val written = when {
            fresh == imeId -> store.write(enabled = true, wasDefault = true)
            result == KeyboardDefaultResult.UNAVAILABLE -> store.write(wasDefault = false)
            !fresh.isNullOrBlank() -> store.write(enabled = false, wasDefault = false)
            result == KeyboardDefaultResult.APPLIED || result == KeyboardDefaultResult.UNCONFIRMED -> true
            else -> store.write(enabled = false, wasDefault = false)
        }
        if (!written) session.blockAutomatic()
    }

    /**
     * The Settings switch turned ON. It ends equal to a fresh readback (see [finalizeApply]): on only if Mimir is
     * the default (or the select was accepted and the readback is unreadable); a failure, a cancel because the
     * player chose another keyboard, or a player who went back to their old one all end off.
     */
    fun turnOn(): KeyboardDefaultResult {
        if (!bridgeReady()) return KeyboardDefaultResult.UNAVAILABLE
        // The intent reaches storage BEFORE the bridge is asked; if it does not, nothing is sent.
        if (!store.write(enabled = true, wasDefault = true)) {
            session.blockAutomatic()
            return KeyboardDefaultResult.FAILED
        }
        // D38: the player's explicit ON supersedes an OFF the safety revert still owes; the detector reverts again if
        // the keyboard dies again. Without this the next start would retry that OFF over the player's choice.
        runCatching { safetyTrail?.clearRevertDemand() }
        val result = applyAndFinalize()
        if (result == KeyboardDefaultResult.UNAVAILABLE && !store.write(enabled = false)) session.blockAutomatic()
        return when {
            store.enabled -> result
            session.cancelled -> KeyboardDefaultResult.UNCHANGED
            result == KeyboardDefaultResult.FAILED || result == KeyboardDefaultResult.UNAVAILABLE -> result
            else -> KeyboardDefaultResult.UNCHANGED
        }
    }

    /**
     * The Settings switch turned OFF. The preference and [MimirKeyboardDefaultStore.wasDefault] are cleared
     * BEFORE `ime disable`, so the default-changed pass that the change itself triggers cannot read it as the
     * player switching away. It runs only while Mimir Keyboard is still the default and another keyboard is
     * enabled; with none, nothing is sent ([KeyboardDefaultResult.NO_OTHER_KEYBOARD]): Mimir stays the default,
     * the switch (a platform readback) stays on, and the stored OFF is only the preference the player expressed.
     */
    fun turnOff(): KeyboardDefaultResult {
        // OFF reaches storage BEFORE the disable; if it does not, nothing is sent and the switch honestly stays on.
        if (!store.write(enabled = false, wasDefault = false)) {
            session.blockAutomatic()
            return KeyboardDefaultResult.FAILED
        }
        if (!bridgeReady()) return KeyboardDefaultResult.UNAVAILABLE
        session.begin(currentDefault())
        try {
            return gateway.resetKeyboardDefault()
        } finally {
            session.end()
        }
    }

    /**
     * Runs the apply with the session open. Right before EVERY mutation (after the gateway's fresh attestation,
     * with nothing blocking between this check and the send) the platform default is read again: a third keyboard
     * means the player chose, and the remaining operations are cancelled -- never a select over them.
     */
    private fun applyAndFinalize(): KeyboardDefaultResult {
        session.begin(currentDefault())
        try {
            val result = gateway.setMimirKeyboardDefault(shouldContinue = {
                !session.cancelIfPlayerChose(currentDefault(), imeId)
            })
            // The session stays OPEN until the result is written: the observer is held off for exactly that long.
            finalizeApply(result)
            return result
        } finally {
            session.end()
            // Whatever the observer was held off from while the session was open is decided now, on a fresh read:
            // a switch away that landed after finalize's read turns the preference off here, never On over it.
            settle(applyAllowed = false)
        }
    }
}

/** The process-wide wiring: one start per process, off the main thread, plus the Settings toggle. */
internal object MimirKeyboardDefaultRuntime {
    private const val LogTag = "MimirKeyboardDefault"
    private val started = AtomicBoolean(false)
    private val Session = KeyboardApplySession()

    private fun controller(context: Context, gateway: CertifiedPrivilegeGateway): MimirKeyboardDefaultController {
        val app = context.applicationContext
        return MimirKeyboardDefaultController(
            store = SharedPrefsMimirKeyboardDefaultStore(app),
            gateway = gateway,
            bridgeReady = { gateway.posture().status == PrivilegedUtilityStatus.READY },
            currentDefault = {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            },
            imeId = mimirKeyboardImeId(app),
            session = Session,
            safetyTrail = SharedPrefsImeSafetyStore(app),
            keyboardTrusted = {
                imeKeyboardTrusted(SharedPrefsImeSafetyStore(app).read(), ImeProcess.versionStamp(app), ImeProcess.token)
            },
        )
    }

    /** Once per process; the work runs on a background thread. Never throws. */
    fun startOnce(context: Context, gateway: CertifiedPrivilegeGateway) {
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching { controller(context, gateway).onStart() }
                .onSuccess { Log.i(LogTag, "start step=$it") }
                .onFailure { Log.w(LogTag, "start failed cause=${it.javaClass.simpleName}") }
        }, "MimirKeyboardDefault").start()
    }

    /**
     * The keyboard cannot be trusted (see `MimirImeSafety.kt`): turn the preference off first, so nothing
     * re-applies it, then ask for the fixed revert through the bridge so Android's own keyboard returns:
     * `ime disable` of Mimir's keyboard, or the conditional `ime reset` when Mimir is the only enabled one (the
     * reset must win over a crash loop). Runs on its own thread with its own short-lived gateway, because the IME
     * may be the only thing that started this process. If the bridge is not READY nothing is sent, and the plain
     * fallback keyboard the safety state forces still gives the player a keyboard. Never throws.
     *
     * The demand was written down (`revertDemanded`) before this thread started. It is cleared here only when the
     * OFF below reached storage; if it did not, it stays on disk and the next start retries the OFF and refuses
     * every automatic apply until it lands (D38).
     */
    fun revertForSafety(context: Context) {
        val app = context.applicationContext
        Thread({
            // OFF first, durably, on this thread (never the caller's, which may be the main thread), then the revert: the
            // order is unchanged. One retry; a write that still fails also latches the automatic path off for this process.
            val written = runCatching {
                val store = SharedPrefsMimirKeyboardDefaultStore(app)
                store.write(enabled = false, wasDefault = false) || store.write(enabled = false, wasDefault = false)
            }.getOrDefault(false)
            if (!written) {
                Session.blockAutomatic()
                Log.w(LogTag, "safety revert could not record the preference")
            } else {
                runCatching { SharedPrefsImeSafetyStore(app).clearRevertDemand() }
            }
            var vendor: AndroidCertifiedVendorPrivilegeGateway? = null
            runCatching {
                val gateway = AndroidCertifiedVendorPrivilegeGateway(
                    context = app,
                    identity = currentBrightnessDeviceIdentity(app),
                )
                vendor = gateway
                val broker = CertifiedPrivilegeGateway(vendor = gateway, vendorRecents = gateway, vendorKeyboard = gateway)
                Log.i(LogTag, "safety revert result=${broker.revertKeyboardDefaultForSafety()}")
            }.onFailure { Log.w(LogTag, "safety revert failed cause=${it.javaClass.simpleName}") }
            runCatching { vendor?.close() }
        }, "MimirKeyboardDefaultRevert").start()
    }

    /** Called when the platform default changes; applies only the respect rule, off the main thread. */
    fun onDefaultChanged(context: Context) {
        val app = context.applicationContext
        Thread({
            runCatching {
                val gateway = CompanionProcessRuntime.services(app).privilegedUtility
                controller(app, gateway).onDefaultChanged()
            }.onFailure { Log.w(LogTag, "change failed cause=${it.javaClass.simpleName}") }
        }, "MimirKeyboardDefaultChange").start()
    }

    /** The Settings switch: turn it [on] or off, off the main thread, then say how it went. */
    fun setSwitch(context: Context, on: Boolean) {
        val app = context.applicationContext
        CompanionProcessRuntime.processScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val gateway = CompanionProcessRuntime.services(app).privilegedUtility
                    val controller = controller(app, gateway)
                    if (on) controller.turnOn() else controller.turnOff()
                }.getOrElse {
                    Log.w(LogTag, "switch failed cause=${it.javaClass.simpleName}")
                    KeyboardDefaultResult.FAILED
                }
            }
            Log.i(LogTag, "switch on=$on result=$result")
            val message = mimirKeyboardSwitchFeedback(on, result)
            if (message != null) Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/** What the Settings switch tells the player; null when the row's own state change says it all. */
internal fun mimirKeyboardSwitchFeedback(on: Boolean, result: KeyboardDefaultResult): String? = when {
    result == KeyboardDefaultResult.APPLIED || result == KeyboardDefaultResult.UNCONFIRMED -> null
    // UNCHANGED while turning on: the player picked another keyboard meanwhile, which needs no apology.
    on && result == KeyboardDefaultResult.UNCHANGED -> null
    on -> "Couldn’t make Mimir Keyboard your default. Choose it in Android’s keyboard settings."
    result == KeyboardDefaultResult.UNCHANGED -> null
    // D32 / OD3: Mimir is the only keyboard, so nothing was sent and the switch stays on.
    result == KeyboardDefaultResult.NO_OTHER_KEYBOARD -> "Enable another keyboard in Android’s keyboard settings first."
    else -> "Couldn’t restore your keyboard. Choose one in Android’s keyboard settings."
}
