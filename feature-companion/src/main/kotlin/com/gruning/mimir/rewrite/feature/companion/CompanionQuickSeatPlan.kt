package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionAppEntry

/**
 * What a quick-launch seat IS, decided once and read by the mark, the spoken name and the tap.
 *
 * **The defect this exists to make unrepresentable.** The rail drew from `entry` and dispatched from
 * `pkg`, two values that disagree for a whole class of real states. `CompanionQuickLaunchCatalog.entries`
 * starts empty in every process and fills from a background sweep, so during the cold window every
 * pinned seat resolved to a null `entry` — and the rail drew `+`, told TalkBack *"Pin an app"*, and
 * **launched anyway** on tap. Three answers to one question, from one seat, on the first frames of every
 * session.
 *
 * The catalog already knew the difference. `CompanionQuickLaunchCatalog.hasLoaded` was built so that
 * *"no apps installed"* and *"nobody has swept yet"* could never be read as one observation; the picker
 * adopted it and the rail never did. `catalogLoaded` is that fact arriving here.
 */
internal enum class CompanionQuickSeatFace {
    /** No pin, or a pin that no longer resolves to anything. Wears `+`; a press opens the picker. */
    EMPTY,

    /** A real pin whose name and icon have not been swept yet. Must NOT wear `+` — it is not empty. */
    PENDING,

    /** A resolved app: its icon, its label, its launch. */
    APP,
}

/**
 * A seat's whole answer. [launchPackage] is non-null **exactly** when a press launches, so the mark and
 * the dispatch cannot disagree again: one field decides both.
 */
internal data class CompanionQuickSeatPlan(
    val face: CompanionQuickSeatFace,
    val launchPackage: String?,
    val name: String,
) {
    /** True when a press opens the picker rather than launching. The complement of [launchPackage]. */
    val opensPicker: Boolean get() = launchPackage == null
}

/** The name a seat gives the hold-line and TalkBack when it has no app label of its own. */
internal const val CompanionQuickSeatEmptyName = "Pin an app"

/**
 * A pin that has not resolved yet still has to answer *what are you*. "Pin an app" would be a lie (it is
 * already pinned) and the package name is not a name — a surface names the app in user-facing language rather than displaying its package id. It says what is true and no more.
 */
internal const val CompanionQuickSeatPendingName = "Pinned app"

/**
 * Decides a seat from the three facts that describe it. Pure, so the combinations are pinned as a table
 * rather than re-derived by the composable that renders them.
 *
 * The dead-pin row is the one worth naming: `QuickLaunchStore` **never prunes**, so a pin whose app has
 * been uninstalled survives forever. It reads EMPTY — and, critically, its press opens the picker rather
 * than firing a launch that cannot succeed. A seat wearing `+` that launches is the exact mismatch this
 * function exists to remove; it would be no better for being rare, and it is not rare.
 */
internal fun companionQuickSeatPlan(
    pinnedPackage: String?,
    entry: CompanionAppEntry?,
    catalogLoaded: Boolean,
): CompanionQuickSeatPlan {
    // A resolved entry is the strongest fact available and outranks the readiness flag: if the sweep has
    // already named this package, when it finished is not interesting.
    if (entry != null) {
        return CompanionQuickSeatPlan(
            face = CompanionQuickSeatFace.APP,
            launchPackage = entry.packageName,
            name = entry.label,
        )
    }

    // The store treats null and blank alike as a cleared slot; so does this.
    val pin = pinnedPackage?.trim()?.takeIf(String::isNotEmpty)
        ?: return CompanionQuickSeatPlan(
            face = CompanionQuickSeatFace.EMPTY,
            launchPackage = null,
            name = CompanionQuickSeatEmptyName,
        )

    return if (catalogLoaded) {
        // Swept, and this pin is not in the answer: the app is gone.
        CompanionQuickSeatPlan(
            face = CompanionQuickSeatFace.EMPTY,
            launchPackage = null,
            name = CompanionQuickSeatEmptyName,
        )
    } else {
        // Not swept yet. The pin is real and pressing it must still work.
        CompanionQuickSeatPlan(
            face = CompanionQuickSeatFace.PENDING,
            launchPackage = pin,
            name = CompanionQuickSeatPendingName,
        )
    }
}
