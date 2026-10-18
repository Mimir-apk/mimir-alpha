package com.gruning.mimir.rewrite.core.model

import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName

/**
 * The outcome of a launch *dispatch* — the typed re-expression of the native launch-result contracts
 * (`mimirLaunchFailureResult.v1` / the success `launchStarted:true` stamp).
 *
 * Scope discipline (policy `launch-silent-death-and-playcount`, 2026-07-03): this records only
 * what the launcher can KNOW synchronously — did the intent get sent, or which dispatch failure
 * occurred. Silent post-dispatch death detection was RETIRED as jargon; there is deliberately no
 * "did the emulator actually stay open" verdict here.
 */
sealed interface LaunchOutcome {

    /** The launch intent was sent to the target emulator (native `launchStarted:true`, reason `intent_sent`). */
    data object IntentSent : LaunchOutcome

    /**
     * Dispatch failed synchronously with a known reason.
     *
     * @property reason the typed dispatch failure ([LaunchDispatchFailure]).
     * @property targetEmulatorId the emulator that failed (so the FE can badge it "didn't open" per-game).
     * @property nativeMetadata optional raw native failure fields (`status`/`code`/`reason`/`message`/`targetPackageKey`, …) preserved verbatim so the UI keeps the exact failure-chip language that debugged HOTD/Saturn/Open-With. Optional/defaulted — the typed [reason] is the source of truth; this is display/debug detail.
     */
    data class Failed(
        val reason: LaunchDispatchFailure,
        val targetEmulatorId: EmulatorId? = null,
        val nativeMetadata: Map<String, String> = emptyMap(),
    ) : LaunchOutcome {
        /**
         * The one plain-English sentence for a failure whose reason's stock copy is too general ("Disc 2 is missing:
         * name"), carried in [nativeMetadata] so the failure keeps one shape. Null for every other failure.
         */
        val detail: String? get() = nativeMetadata[DETAIL_KEY]?.takeIf(String::isNotBlank)

        companion object {
            const val DETAIL_KEY = "detail"
        }
    }
}

/**
 * A durable native Android-game launch attempt. Package identity is explicit: it is never coerced into
 * a ROM [GameId]. As with [LaunchProof], success means only that the launch intent was synchronously sent.
 */
data class AndroidGameLaunchProof(
    val packageName: AndroidPackageName,
    val outcome: LaunchOutcome,
    val timestampMillis: Long,
)

/** Latest successful Android-game dispatch used to merge Home's mixed Last Played context. */
data class AndroidGameLaunchRecency(
    val packageName: AndroidPackageName,
    val lastPlayedMillis: Long,
)

/**
 * A durable record that a launch was attempted — persisted (see `core-storage` `launch_proofs`) so the
 * FE can honestly reflect the last launch attempt on a game (e.g. a failure chip tied to the launch
 * context). A failure marker's lifetime is tied to the
 * launch **locator/context**, not merely "cleared on success" — a re-placement / path change drops it.
 *
 * @property gameId the game launched.
 * @property platformId its platform.
 * @property emulatorId the emulator target used.
 * @property locator the exact locator dispatched (the failure-marker lifetime key — a new locator invalidates an old failure).
 * @property outcome the dispatch outcome.
 * @property timestampMillis when the attempt happened (epoch millis; wall-clock, local-only).
 */
data class LaunchProof(
    val gameId: GameId,
    val platformId: PlatformId,
    val emulatorId: EmulatorId,
    val locator: String,
    val outcome: LaunchOutcome,
    val timestampMillis: Long,
) {
    /** True when the dispatch succeeded (intent sent). */
    val succeeded: Boolean get() = outcome is LaunchOutcome.IntentSent

    /**
     * Whether a failure recorded by this proof still applies to a launch that would use
     * [currentLocator]. Per the ruling, a failure marker is scoped to its locator: if the game's
     * current launch locator differs (re-placed / moved), the old failure is stale and must not show.
     */
    fun failureAppliesTo(currentLocator: String): Boolean =
        outcome is LaunchOutcome.Failed && locator == currentLocator
}
