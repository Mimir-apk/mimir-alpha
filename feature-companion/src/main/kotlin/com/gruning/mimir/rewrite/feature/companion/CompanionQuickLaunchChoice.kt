package com.gruning.mimir.rewrite.feature.companion

/** The two user-facing destinations for a secondary quick launch. */
enum class CompanionQuickLaunchDestination {
    MAIN,
    BOTTOM,
    ASK_NEXT_TIME,
}

/** Exact-token chooser state shared by the Presentation and overlay hosts. */
data class CompanionQuickLaunchChoice(
    val requestId: Long,
    val packageName: String,
    val label: String?,
    val editing: Boolean,
    val fromGeneral: Boolean,
    /** The next Bottom launch needs an explicit acknowledgement because it covers a live Main DS/3DS lower screen. */
    val coversMainGame: Boolean = false,
)
