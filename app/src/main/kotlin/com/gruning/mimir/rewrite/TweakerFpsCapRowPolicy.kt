package com.gruning.mimir.rewrite

/**
 * The frame-cap row, which must always name the game it caps.
 *
 * The engine has been applying per-package caps at launch for some time; what was missing was any
 * way for a player to set one. The obvious fix — a cap control on the Tweaker rail — is the one
 * thing this must not be. `TweakerBridge.discardLegacyGlobalGameFpsSlot` retired exactly that, and
 * says why: an app-wide slot "names no game, so promoting it onto whichever package launches next
 * would silently cap an unrelated game". A row with no subject is that slot with a nicer label.
 *
 * So the row exists only while a game is actually in front, and its subject is that package. With
 * no attested foreground game there is nothing to cap and the row is absent rather than showing a
 * value that would be written somewhere unpredictable.
 */
internal sealed interface TweakerFpsCapRow {

    /** No attested foreground game. The row must not appear; there is no package to write against. */
    data object NoSubject : TweakerFpsCapRow

    /** The device or profile offers no frame limits. Say so rather than offer an empty stepper. */
    data class Unavailable(val packageName: String) : TweakerFpsCapRow

    /**
     * A cap can be chosen for [packageName]. [selectedHz] is null for "no cap".
     * [optionsHz] never contains null; the off state is modelled by [selectedHz] being null.
     */
    data class Available(
        val packageName: String,
        val selectedHz: Int?,
        val optionsHz: List<Int>,
    ) : TweakerFpsCapRow
}

/**
 * [foregroundPackage] must be the ATTESTED live foreground package, not a remembered or
 * last-launched one — a stale subject would write a cap onto whatever is running now.
 */
internal fun tweakerFpsCapRow(
    foregroundPackage: String?,
    optionsHz: List<Int>,
    selectedHz: Int?,
): TweakerFpsCapRow {
    val target = foregroundPackage?.takeIf { it.isNotBlank() } ?: return TweakerFpsCapRow.NoSubject
    val options = optionsHz.filter { it in TWEAKER_FPS_CAP_ADMITTED_HZ }.distinct().sorted()
    if (options.isEmpty()) return TweakerFpsCapRow.Unavailable(target)
    return TweakerFpsCapRow.Available(
        packageName = target,
        // A stored cap the device no longer offers is not honoured at launch, so the row must not
        // display it either — that is the same fake-value trap the profile row already avoids.
        selectedHz = selectedHz?.takeIf { it in options },
        optionsHz = options,
    )
}

/** Mirrors the engine's own bound (`TweakerGameFpsPolicy` requires 30..120). */
internal val TWEAKER_FPS_CAP_ADMITTED_HZ: IntRange = 30..120

/**
 * Steps the cap. Position 0 is always "no cap", so the off state is reachable from either end and a
 * player can always get back to unmodified behaviour.
 */
internal fun stepTweakerFpsCap(row: TweakerFpsCapRow.Available, direction: Int): Int? {
    if (direction == 0) return row.selectedHz
    val ladder: List<Int?> = listOf(null) + row.optionsHz
    val current = ladder.indexOf(row.selectedHz).coerceAtLeast(0)
    val delta = if (direction < 0) -1 else 1
    return ladder[(current + delta).coerceIn(0, ladder.lastIndex)]
}

/** Row value. Names the cap, or says plainly that there is none. */
internal fun tweakerFpsCapValueLabel(row: TweakerFpsCapRow): String = when (row) {
    is TweakerFpsCapRow.NoSubject -> "NO GAME"
    is TweakerFpsCapRow.Unavailable -> "UNAVAILABLE"
    is TweakerFpsCapRow.Available -> row.selectedHz?.let { "$it FPS" } ?: "OFF"
}

/**
 * Supporting copy. When a cap is available it must name the game, because the whole point is that
 * this setting belongs to one package and not to Mimir as a whole.
 */
internal fun tweakerFpsCapSupportingText(row: TweakerFpsCapRow, displayName: String?): String =
    when (row) {
        is TweakerFpsCapRow.NoSubject -> "Start a game to cap its frame rate."
        is TweakerFpsCapRow.Unavailable -> "This device offers no frame limits."
        is TweakerFpsCapRow.Available -> {
            val subject = displayName?.takeIf(String::isNotBlank) ?: row.packageName
            "Applies to $subject only, next time it launches."
        }
    }
