package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.BiosProfile
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchProof
import com.gruning.mimir.rewrite.core.model.AndroidGameLaunchRecency
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.flow.Flow

/**
 * The ports (dependency-inversion boundaries) the launch engine needs. Each is the seam where the app
 * layer plugs in real native behavior (package-installed checks, SAF locator resolution, the native
 * launch bridge). Keeping them as interfaces here lets the readiness ENGINE be tested as a pure
 * function (memory: native bridge calls are expensive + can't be JS/JVM-stubbed — so the engine must
 * never call them directly).
 */

/** Supplies the emulator candidates that can serve a platform, each with its live `installed` flag. */
fun interface InstalledEmulatorProvider {
    /** Candidates for [platformId], ordered most-compatible-first; `installed` reflects the device now. */
    fun candidatesFor(platformId: PlatformId): List<EmulatorProfile>
}

/** Looks up the per-game "Open with…" override . */
fun interface OpenWithOverrideLookup {
    /** The user-pinned emulator for [gameId], or null when the game is on Auto. */
    fun overrideFor(gameId: GameId): EmulatorId?
}

/** Reports BIOS requirements + presence for a platform (honest UNKNOWN under scoped storage). */
fun interface BiosPresenceProvider {
    /**
     * BIOS profiles Mimir tracks for [platformId], with current presence knowledge. BIOS truth is
     * emulator-aware (native launch seam contract): PS2 forks, DuckStation PS1 and HLE-optional
     * melonDS all differ by emulator/package. Pass the resolved [emulatorId] so the provider can
     * return the presence read from THAT emulator's folder; a null [emulatorId] returns all/platform-wide
     * rows and lets the caller filter (see [BiosProfile.appliesTo]).
     *
     * ([emulatorId] has no default here because a `fun interface` SAM method cannot carry one — the
     * engine always passes the resolved emulator; a raw caller passes null explicitly for platform-wide.)
     */
    fun biosFor(platformId: PlatformId, emulatorId: EmulatorId?): List<BiosProfile>
}

/**
 * Resolves the launch locator for the chosen MEMBER of a game group, in the form an emulator wants.
 * Returns null when the file cannot be reached (drives [com.gruning.mimir.rewrite.core.model.LaunchReadiness.MissingFile]).
 *
 * A [GameId] is a dedup GROUP (multi-disc / region / cross-storage siblings). Resolving by group id
 * alone can launch the wrong disc (native launch seam contract), so the caller passes the
 * chosen member's [EntryKey].
 */
interface LaunchLocatorProvider {
    /**
     * @param gameId the game (group) being launched.
     * @param launchMemberEntryKey the specific member file to launch (a disc / storage sibling); null lets the provider pick the group's default launch member.
     * @param preferredDisc a 1-based disc selection when no exact member key is supplied; 0 means automatic playlist/default.
     * @param preferredForm the locator form the resolved emulator expects.
     * @return the (locator, form) pair, or null when the file is unreachable.
     */
    fun locatorFor(
        gameId: GameId,
        launchMemberEntryKey: EntryKey?,
        preferredDisc: Int,
        preferredForm: LaunchLocatorForm,
    ): LaunchLocatorResult?
}

/** A resolved locator + the form it is actually in (may differ from the request when only one exists). */
data class LaunchLocatorResult(
    val locator: String,
    val form: LaunchLocatorForm,
)

/**
 * Records launch attempts durably (backed by `core-storage` `launch_proofs`). The engine builds the
 * [LaunchProof]; this port persists it. Kept separate so the pure engine has no storage dependency.
 */
fun interface LaunchProofRecorder {
    fun record(proof: LaunchProof)
}

/** Durable Android-package proof writer plus the successful recency read consumed by Home. */
interface AndroidGameLaunchProofStore {
    suspend fun record(proof: AndroidGameLaunchProof)

    fun observeRecentlyPlayed(limit: Int): Flow<List<AndroidGameLaunchRecency>>
}
