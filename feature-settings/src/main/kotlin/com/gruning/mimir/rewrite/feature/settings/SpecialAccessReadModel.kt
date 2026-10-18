package com.gruning.mimir.rewrite.feature.settings

/** Live Android special-access row surfaced by Settings without depending on native Android controllers. */
data class SpecialAccessRow(
    val id: String,
    val title: String,
    val supporting: String,
    val granted: Boolean,
    val actionLabel: String = "Open",
    val grantedLabel: String = "Allowed",
    val toggleable: Boolean = false,
    val actionable: Boolean = true,
)
