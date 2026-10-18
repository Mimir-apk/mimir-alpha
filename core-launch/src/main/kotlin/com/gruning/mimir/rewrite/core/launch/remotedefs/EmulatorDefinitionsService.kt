package com.gruning.mimir.rewrite.core.launch.remotedefs

import java.io.File

/** The player's opt-in for fetching emulator definitions. Off until they confirm the one-line explanation. */
interface DefinitionsConsent {
    var granted: Boolean
}

/** What Settings shows about the definitions in force. Display only; nothing here is read by scan or launch. */
data class DefinitionsInfo(
    /** [BUNDLED_DEFINITIONS_DATE] unless a different bundled date was injected. */
    val builtInDate: String,
    /** The fetched release in force (`defs-2`), or null when only the compiled list applies. */
    val tag: String?,
    /** When the fetched release was stored (epoch millis, display only), or null. */
    val fetchedAtMillis: Long?,
)

/** The result of one user-started check, in the terms Settings words (note section 6, I10). */
sealed interface DefinitionsCheck {
    /** Nothing new for this player: same release, a release the app already contains, or one that changes nothing. */
    data object UpToDate : DefinitionsCheck

    /** A new release was stored and is in force; [emulators] emulators have different launch details now. */
    data class Updated(val emulators: Int) : DefinitionsCheck

    /**
     * Nothing changed. [needsAppUpdate]: the release is written for a newer Mimir. [offline]: no usable answer from the
     * network. Otherwise the answer was refused (damaged, mismatched checksum, not valid for this build).
     * [usingFetched]: a previously fetched release stays in force; false means the built-in list.
     */
    data class Failed(
        val reason: RejectCode?,
        val offline: Boolean,
        val needsAppUpdate: Boolean,
        val usingFetched: Boolean,
    ) : DefinitionsCheck

    /** Another check is already running. */
    data object InFlight : DefinitionsCheck

    /** The player has not confirmed the opt-in: nothing left the process. */
    data object NeedsConsent : DefinitionsCheck
}

/** What Settings needs from the definitions: what is in force, and the player's check. Lets the app test its row with a fake. */
interface EmulatorDefinitionsApi {
    fun info(): DefinitionsInfo

    /** The player's press. Never throws. */
    fun check(): DefinitionsCheck
}

/**
 * Everything the app does with remote definitions, behind two calls and no Android type, so it is tested on the JVM
 * with a fake transport and a temp directory:
 *
 * - [load]: start-up. Reads the stored copy from disk, re-validates it, installs the merged view. No transport is
 *   touched; scan and launch never reach [check].
 * - [check]: the Settings action, called only on the player's press. One refresh, never a timer, never in the background.
 *
 * Failure of any kind leaves what was in force in force (the previous release, else the compiled list): note
 * section 5 item 8 and I3 to I5.
 */
class EmulatorDefinitionsService(
    dir: File,
    transport: RemoteDefinitionsTransport,
    private val consent: DefinitionsConsent,
    private val bundled: BundledDefinitions = ActiveDefinitions.bundled,
    private val holder: EffectiveDefinitionsHolder = ActiveDefinitions.holder,
    source: RemoteDefinitionsSource = RemoteDefinitionsEndpoint.PRODUCTION,
    displayClock: () -> Long = System::currentTimeMillis,
    private val builtInDate: String = BUNDLED_DEFINITIONS_DATE,
    faultHook: (StoreStep) -> Unit = {},
    checkBudgetMillis: Long = RemoteDefinitionsLimits.CHECK_BUDGET_MS,
) : EmulatorDefinitionsApi {
    val store: RemoteDefinitionsStore = RemoteDefinitionsStore(dir, faultHook)
    private val refresher = RemoteDefinitionsRefresher(
        store = store,
        transport = transport,
        bundled = bundled,
        holder = holder,
        optIn = { consent.granted },
        displayClock = displayClock,
        source = source,
        checkBudgetMillis = checkBudgetMillis,
    )

    /** Start-up: disk only. Installs what the stored copy (if any, and if still valid for this build) says. */
    fun load(): LoadOutcome {
        val outcome = RemoteDefinitionsLoader.load(store, bundled)
        holder.publish(outcome.effective)
        return outcome
    }

    override fun info(): DefinitionsInfo {
        val record = store.readRecord()
        val inUse = holder.current.overlayOrdinal != null && record != null
        return DefinitionsInfo(
            builtInDate = builtInDate,
            tag = if (inUse) "defs-${record!!.ordinal}" else null,
            fetchedAtMillis = if (inUse) record!!.fetchedAtMillis else null,
        )
    }

    /** True once the opt-in has been confirmed. */
    val consentGranted: Boolean get() = consent.granted

    /** The player's press. Returns what happened; never throws. */
    override fun check(): DefinitionsCheck {
        val before = holder.current
        val result = try {
            refresher.refreshLatest()
        } catch (_: Exception) {
            RefreshResult.Rejected(RejectCode.INTERNAL)
        }
        return when (result) {
            RefreshResult.NotEnabled -> DefinitionsCheck.NeedsConsent
            RefreshResult.SkippedInFlight -> DefinitionsCheck.InFlight
            RefreshResult.NoChange -> DefinitionsCheck.UpToDate
            is RefreshResult.Applied -> {
                val changed = holder.current.packagesChangedFrom(before).size
                if (changed == 0) DefinitionsCheck.UpToDate else DefinitionsCheck.Updated(changed)
            }
            is RefreshResult.Offline -> DefinitionsCheck.Failed(null, offline = true, needsAppUpdate = false, usingFetched = usingFetched())
            is RefreshResult.Rejected -> DefinitionsCheck.Failed(
                reason = result.code,
                offline = false,
                needsAppUpdate = result.code == RejectCode.SCHEMA_NEWER || result.code == RejectCode.NEEDS_NEWER_APP,
                usingFetched = usingFetched(),
            )
        }
    }

    private fun usingFetched(): Boolean = holder.current.overlayOrdinal != null
}
