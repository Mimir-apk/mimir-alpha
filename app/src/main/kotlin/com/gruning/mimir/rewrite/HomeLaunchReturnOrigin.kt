package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.library.HomeShelfRow

/**
 * Where a launch carrying a [HomeLaunchReturnHandle] started, as the launching call site knows it.
 *
 * **In memory only.** It is never persisted, so every handle decoded from the durable record reads [MAIN_HOME];
 * a comparison against a decoded record must ignore it (see [HomeLaunchReturnLedger.acknowledge]).
 *
 * Rank 7c (AYN Thor, build 5fa4c795, 2026-09-11): Zelda launched from the Bottom card armed Main's Home return
 * although it has no row on Main's Home. On quit Home resolved the return as RowAbsent and held the branded return
 * cover for the whole `HomeLaunchReturnRenderableGraceMs` before acknowledging. [BOTTOM_CARD_OFF_HOME] is that
 * launch, and it no longer arms.
 */
internal enum class HomeLaunchOrigin {
    /** Main's Home surface: a shelf row or its launch card. Arms, as it always has. */
    MAIN_HOME,

    /** The Bottom card, for a game with a row on Main's Home in the recorded view mode. Arms, as it always has. */
    BOTTOM_CARD_ON_HOME,

    /** The Bottom card, for a game with no row on Main's Home. Main has nothing to restore, so it does not arm. */
    BOTTOM_CARD_OFF_HOME,
}

/** Whether a launch arms Main's Home return, and the `reason=` its one log line carries. */
internal enum class HomeLaunchReturnArmDecision(val arms: Boolean, val reason: String) {
    ARM_MAIN_HOME_LAUNCH(arms = true, reason = "main_home_launch"),
    ARM_ROW_ON_MAIN_HOME(arms = true, reason = "row_on_main_home"),
    SKIP_NO_ROW_ON_MAIN_HOME(arms = false, reason = "no_row_on_main_home"),
}

internal fun homeLaunchReturnArmDecision(origin: HomeLaunchOrigin): HomeLaunchReturnArmDecision = when (origin) {
    HomeLaunchOrigin.MAIN_HOME -> HomeLaunchReturnArmDecision.ARM_MAIN_HOME_LAUNCH
    HomeLaunchOrigin.BOTTOM_CARD_ON_HOME -> HomeLaunchReturnArmDecision.ARM_ROW_ON_MAIN_HOME
    HomeLaunchOrigin.BOTTOM_CARD_OFF_HOME -> HomeLaunchReturnArmDecision.SKIP_NO_ROW_ON_MAIN_HOME
}

/**
 * The Bottom card's origin, from the launching call site's OWN Home-row lookup: the same
 * `homeRows.firstOrNull { it.itemId == itemId }` that picks the Home row over the bare-identity target.
 *
 * It takes the looked-up row and never a rank. A bare target has `rank = 0`, and so does a real first favourite
 * (`HomeFavoriteProjection`'s `index * HomeFavoriteRankStep`), so a rank test would stop Main's first tile arming.
 */
internal fun bottomCardHomeLaunchOrigin(mainHomeRow: HomeShelfRow?): HomeLaunchOrigin =
    if (mainHomeRow != null) HomeLaunchOrigin.BOTTOM_CARD_ON_HOME else HomeLaunchOrigin.BOTTOM_CARD_OFF_HOME

/**
 * The EXTERNAL ELAPSED token Main's return path finishes for the game session it just returned, or null.
 *
 * Home's resume finishes a Main game's measurement only from a durable return record it can peek
 * (`HomeShelfCoordinator`'s ON_RESUME observer and cold-start effect). A skipped arm leaves no record, so Main's
 * return path finishes the session's own handle. The store's finish is token-matched and clears its anchor, so
 * when Home also finishes the same token, whichever runs second changes nothing.
 *
 * MAIN placement only, as a guard: `MainActivity.onStop` already records only MAIN-placed sessions in the return
 * ticket. A Bottom game's measurement belongs to `CompanionProcessRuntime.settleBottomGameCleanup`, which finishes
 * it when that game actually ends. After a process death, a MAIN one is finished by
 * `HomeExternalSessionElapsedStore.finishOrphanedMainReturn` instead.
 *
 * a session with no Home return handle (a Library launch) names its measurement by its own operation
 * ([elapsedToken], opened at the dispatch edge by [GameSessionPlayTime]), so it is finished here too.
 */
internal fun primaryGameReturnElapsedToken(
    session: MimirGameSession?,
    expectedOperation: MimirGameSessionOperation,
): String? = session
    ?.takeIf { it.operation == expectedOperation && it.placement == MimirGamePlacement.MAIN }
    ?.elapsedToken()
