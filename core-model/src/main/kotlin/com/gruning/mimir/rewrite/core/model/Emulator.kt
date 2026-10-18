package com.gruning.mimir.rewrite.core.model

/**
 * The stable key of an installable emulator *choice* — and, critically, the **native launch/dispatch
 * key** the launch bridge resolves a target by. It is NOT `packageName#coreName`.
 *
 * Native launch contract: native dispatch resolves a target through `candidateByTargetKey(...)`
 * which accepts the candidate's `launcherKey`, its native `id`, or (fallback) its `packageName`. So
 * [EmulatorId] MUST carry native `launcherKey || id || packageName` — the exact string native accepts
 * as `targetPackageKey`. Native RetroArch candidate ids look like `gba.retroarch.aarch64.mgba`
 * (`<platform>.retroarch.<build>.<core>`); native does **not** accept `com.retroarch.aarch64#mgba`.
 *
 * The `packageName` and libretro `retroArchCore` remain SEPARATE fields on [EmulatorProfile] purely
 * for display/grouping (e.g. the FE de-dup that counts distinct EMULATORS by package while listing
 * cores) — they are never the dispatch id. Never invent short aliases; always store native's key.
 */
@JvmInline
value class EmulatorId(val value: String) {
    init {
        require(value.isNotBlank()) { "EmulatorId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Wrap the native launch/dispatch key verbatim (`launcherKey || id || packageName`) — the string
         * native's `candidateByTargetKey` accepts as `targetPackageKey`. Prefer this over any composed
         * form so the id is exactly what native dispatches by (e.g. `"gba.retroarch.aarch64.mgba"`,
         * `"org.mupen64plusae.v3.fzurita"`).
         */
        fun fromNativeLauncherKey(nativeKey: String): EmulatorId {
            require(nativeKey.isNotBlank()) { "native launcher key must not be blank" }
            return EmulatorId(nativeKey)
        }
    }
}

/**
 * How an emulator wants the ROM handed to it at launch. Ground truth: the native
 * `launchPlacedGameWithTarget` failure contract distinguishes `file_path_required` vs
 * `content_uri_required` — different emulators accept different locator forms, and picking the wrong
 * one is a real launch failure. Modeling it typed lets `core-launch` refuse to build an unlaunchable
 * payload rather than fail at dispatch.
 */
enum class LaunchLocatorForm {
    /** Emulator wants a plain filesystem path (`/storage/…/game.iso`). */
    FILE_PATH,

    /** Emulator wants a SAF `content://` URI. */
    CONTENT_URI,

    /** Emulator accepts either; the resolver may prefer the cheapest available locator. */
    EITHER,

    /**
     * No ROM locator is supplied — the target is opened bare (open-emulator-only, e.g. a shell-open
     * PS3/Xbox360-style launch). Maps to native intent form `open_emulator_only`; the locator string
     * is irrelevant/empty. Kept in the launch-readiness pipeline so open-only targets model uniformly.
     */
    NONE,
}

/**
 * The strongest launch behavior Mimir has actually verified for an emulator package.
 *
 * Package recognition, opening an emulator shell, and handing it a game are deliberately separate
 * facts. Keeping them typed prevents an installed-but-unproven package from becoming an Auto/Open-With
 * target merely because PackageManager can see it.
 */
enum class EmulatorCapability {
    /** Package/family is known and may be presented, but Mimir must not dispatch to it. */
    RECOGNIZE_ONLY,

    /** Mimir may open the emulator application without attaching a game locator. */
    OPEN_ONLY,

    /** Mimir has an exact component/action/payload contract for direct game handoff. */
    DIRECT_PAYLOAD,
}

/**
 * A concrete emulator the device could launch a game with — a typed re-expression of one entry from
 * the native `platformLauncherCandidates(platform)` list.
 *
 * This is a *candidate description*, not a launch decision. The launch decision (which candidate is
 * the resolved target for a given game) lives in `core-launch` and consults [installed] + any
 * per-game Open-With override.
 *
 * @property id the native launch/dispatch key (`launcherKey || id || packageName`) native resolves the target by — the value passed as `targetPackageKey`. NOT `package#core`.
 * @property packageName the Android package that will receive the launch intent (display/grouping only, never the dispatch id).
 * @property displayName human-facing emulator name (e.g. "Mupen64Plus AE", "DuckStation").
 * @property retroArchCore non-null when this candidate is a RetroArch core (the libretro core name), for display/grouping only; null for standalone emulators.
 * @property installed whether the package is currently installed (from native `isPackageInstalled`); a not-installed candidate is greyed "Needs install", never auto-selected.
 * @property capability strongest verified behavior; recognition alone is never selectable.
 * @property componentAvailable installed-component preflight. `null` means the package is absent or no
 * component probe applies; `false` means the package exists but its exact dispatch activity is missing,
 * disabled, or no longer exported.
 * @property locatorForm the ROM locator form this emulator expects.
 * @property supportedPlatforms the platforms this candidate can serve; a single emulator can serve many.
 */
data class EmulatorProfile(
    val id: EmulatorId,
    val packageName: String,
    val displayName: String,
    val retroArchCore: String? = null,
    val installed: Boolean = false,
    val capability: EmulatorCapability = EmulatorCapability.RECOGNIZE_ONLY,
    val componentAvailable: Boolean? = null,
    val locatorForm: LaunchLocatorForm = LaunchLocatorForm.EITHER,
    val supportedPlatforms: Set<PlatformId> = emptySet(),
    /**
     * True when [capability] was promoted to [EmulatorCapability.OPEN_ONLY] at runtime because PackageManager
     * found a launcher intent for a seed that was only [EmulatorCapability.RECOGNIZE_ONLY]. Declared
     * open-shell seeds (Vita3K, RPCSX, X360 Mobile, etc.) keep this false — they are intentional shell
     * routes, not inferred fallbacks. Inferred shells must not become direct-game dispatch targets.
     */
    val openShellInferred: Boolean = false,
    /**
     * True for a launchMode="singleTask" target whose own activity declares no `onNewIntent` override
     * (confirmed by decompiling the installed package, not assumed). A warm relaunch — the target's task
     * already alive in the background — gets routed by Android to that missing `onNewIntent` instead of
     * `onCreate`, so the new game's payload is silently dropped and the player just sees the already-open
     * emulator. Mimir cannot patch the target app, so [LaunchController] gives its own pre-dispatch
     * process-reap a short settle window before firing the launch intent for exactly these packages —
     * see `aenu.aps3e` in `EmulatorSeedRegistry` for the evidence. Every other candidate keeps this false
     * and launches with no added delay.
     */
    val residentTaskWithoutIntentRefresh: Boolean = false,
    /**
     * The launch activity the component preflight found installed, when it is one of the profile's known
     * alternates rather than its first choice; null means the profile's own. Flycast renamed its activity
     * between builds: the Thor's answers to `com.flycast.emulator.MainActivity`, the Nova's v2.2 only to
     * `com.reicast.emulator.MainActivity`.
     */
    val installedActivityName: String? = null,
) {
    /** True when this candidate is served by a RetroArch build (dispatch is generic per the launcher model). */
    val isRetroArchCore: Boolean get() = retroArchCore != null

    /** A candidate may serve a platform only if it declares support (empty set = "unconstrained / platform-agnostic"). */
    fun canServe(platformId: PlatformId): Boolean =
        supportedPlatforms.isEmpty() || platformId in supportedPlatforms
}
