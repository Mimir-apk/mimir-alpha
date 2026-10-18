package com.gruning.mimir.rewrite

import android.util.Log
import com.gruning.mimir.rewrite.core.launch.LaunchController
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The "Opens with <launcher>" line on a NOW_PLAYING card that a LAUNCH SEED publishes (round 5b, found on the Thor,
 * build 47d5af6b).
 *
 * Policy 2026-08-31, quoted at the NOW_PLAYING hero in `CompanionSurface.kt`, keeps that line whenever there is
 * a launcher to name, and no line when there is none. Only the Library push ever set it. The two seeds — a Home shelf
 * row ([homeLaunchSeedPayload]) and a Bottom card ([companionLaunchSeedPayload]) — left it null, and so did the card
 * [CompanionState.republishRetainedNowPlaying] brings back once the emulator is in front. The same game read
 * "OPENS WITH RetroArch 64 / Gambatte" launched from the Library, and had no line launched from the Bottom.
 *
 * A seed names what the Library push names for the same game:
 * - **No override:** the platform default, from the shelf launch-status line ([companionLauncherLabel]). It costs
 *   nothing, so the seed carries it the moment it publishes.
 * - **An Open-With override:** that override's label ([companionInstalledOverrideLabel]). That needs a
 *   launch-controller read, and G-2 built both seeds with no read before publish so a fast launch cannot drop the
 *   card. So the seed publishes with NO line, and the label lands after it
 *   ([CompanionState.stampRetainedNowPlayingLauncherLabel]). **It never falls back to the platform default.** A wrong
 *   emulator name is worse than no line, and a launch whose override emulator is gone goes to Auto, which need not
 *   be the platform default either. When the read finds no override the launch can use, the card keeps no line.
 * - **No emulated game** (an Android row on the Home shelf): there is no launcher, so there is no line.
 *
 * One instance per launch. The Home push re-publishes its seed on every key change while the launch is in flight;
 * the instance keeps what this launch resolved, so a re-publish carries it, and it reads only once.
 */
internal class CompanionSeedLauncherLine {
    /** This launch's resolved override label. Only this instance's own read writes it. */
    private var resolvedOverrideLabel: String? = null
    private var resolution: Job? = null
    private var announced = false

    /**
     * Publish [seed] now, with the launcher it can name now. For an override game, also start (once) the read that
     * adds the override's label after it. Never waits on that read.
     *
     * [game] is the launching game's row, when the caller has one. It describes [seed] only when it IS the seed's
     * game: an Android row's seed carries no game identity, so no lookup can give it a line.
     */
    fun publish(
        seed: CompanionPayload,
        game: GameRow?,
        shelfLaunchStatusLines: Map<PlatformId, String>,
        resolutionScope: CoroutineScope,
        resolveOpenWith: suspend (GameRow) -> OpenWithOptions,
    ) {
        val launcherGame = game?.takeIf { it.gameId == seed.gameId }
        val label = when {
            launcherGame == null -> null
            launcherGame.hasEmulatorOverride -> resolvedOverrideLabel
            else -> companionLauncherLabel(shelfLaunchStatusLines[launcherGame.platformId])
        }
        CompanionState.set(seed.copy(launcherLabel = label))
        if (!announced) {
            announced = true
            Log.i(
                CompanionSeedLauncherTag,
                "seed game=${seed.gameId?.value ?: "-"} launcher=${seedLauncherSource(launcherGame, label)} " +
                    "label=${label ?: "-"}",
            )
        }
        if (launcherGame == null || !launcherGame.hasEmulatorOverride || resolution != null) return
        resolution = resolutionScope.launch {
            val resolved = companionOverrideLabel(fallback = null) {
                companionInstalledOverrideLabel(resolveOpenWith(launcherGame))
            }
            if (resolved == null) {
                Log.i(
                    CompanionSeedLauncherTag,
                    "override unresolved game=${launcherGame.gameId.value}: no launchable current override, no line",
                )
                return@launch
            }
            resolvedOverrideLabel = resolved
            val stamped = CompanionState.stampRetainedNowPlayingLauncherLabel(launcherGame.gameId, resolved)
            Log.i(
                CompanionSeedLauncherTag,
                "override resolved game=${launcherGame.gameId.value} label=$resolved retained=$stamped",
            )
        }
    }
}

/**
 * The label of the game's CURRENT Open-With override when the launch can use it; otherwise null. The one selector
 * the Library push and the launch seeds share, so both name the same candidate.
 *
 * "Can use" is the launch's own test for a preferred emulator: installed AND dispatch-supported
 * (`PlatformEmulatorStatus.resolveInstalledEmulator`), which is exactly `OpenWithCandidate.selectable`. An override
 * that is installed but not dispatchable is skipped for Auto, so naming it would name an emulator that will not
 * start (round 5b follow-up 2).
 */
internal fun companionInstalledOverrideLabel(options: OpenWithOptions): String? =
    options.candidates.firstOrNull { it.isCurrentOverride && it.selectable }?.label

/**
 * The launcher the LIBRARY push names for [game] on the NOW_PLAYING card (round 5b follow-up).
 *
 * No override: the platform default line. An override: that override's label when the launch can use it (installed
 * and dispatch-supported), otherwise NO line, the seeds' rule. It used to fall back to the platform default line, which names an emulator the launch does
 * not start: once a stored override exists, `LaunchController.resolveReadiness` never consults the platform default,
 * so an override that is not installed goes to Auto, and Auto is the platform default only by coincidence.
 *
 * [memo] keeps one answer per game, "no line" included, for as long as the caller keeps it (the push keys it on the
 * games list), so a read that finds nothing is not repeated on every focus move. The old non-null fallback is what
 * made that memo stick: `getOrPut` treats a stored null as missing.
 */
internal suspend fun companionLibraryLauncherLabel(
    game: GameRow,
    shelfLaunchStatusLines: Map<PlatformId, String>,
    memo: MutableMap<String, String?>,
    resolveOpenWith: suspend (GameRow) -> OpenWithOptions,
): String? {
    if (!game.hasEmulatorOverride) return companionLauncherLabel(shelfLaunchStatusLines[game.platformId])
    val key = game.gameId.value
    if (memo.containsKey(key)) return memo[key]
    return companionOverrideLabel(fallback = null) { companionInstalledOverrideLabel(resolveOpenWith(game)) }
        .also { memo[key] = it }
}

/**
 * The Bottom card's launch seed (`MimirAppCoordinator.launchCompanionPickedGame`), published through
 * [CompanionSeedLauncherLine]. It is one call there because that file sits near the ART instruction limit.
 */
internal fun publishCompanionPickedGameSeed(
    game: GameRow,
    themeId: String,
    platforms: List<PlatformRow>,
    shelfLaunchStatusLines: Map<PlatformId, String>,
    resolutionScope: CoroutineScope,
    launchController: LaunchController,
) = CompanionSeedLauncherLine().publish(
    seed = companionLaunchSeedPayload(game, themeId, platforms),
    game = game,
    shelfLaunchStatusLines = shelfLaunchStatusLines,
    resolutionScope = resolutionScope,
    resolveOpenWith = { launchController.openWithOptions(it.gameId, it.platformId) },
)

/** The log's word for where a seed's launcher came from (`adb logcat -s MimirCompanionLauncher`). */
private fun seedLauncherSource(game: GameRow?, label: String?): String = when {
    game == null -> "none"
    game.hasEmulatorOverride -> if (label == null) "override-pending" else "override"
    label == null -> "platform-unavailable"
    else -> "platform-default"
}

private const val CompanionSeedLauncherTag = "MimirCompanionLauncher"
