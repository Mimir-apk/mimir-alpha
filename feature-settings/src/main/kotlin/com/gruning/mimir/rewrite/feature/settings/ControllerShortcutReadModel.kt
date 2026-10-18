package com.gruning.mimir.rewrite.feature.settings

const val ControllerShortcutQuickLaunchRowId = "controls/controller/quick-launch"
const val ControllerShortcutUtilityRowId = "controls/controller/utility"
const val ControllerShortcutTweakerRowId = "controls/controller/tweaker"
const val ControllerShortcutResetRowId = "controls/controller/reset"

/** App-projected controller shortcut state; raw descriptors/key codes never enter the visual module. */
data class ControllerShortcutRow(
    val id: String,
    val title: String,
    val supporting: String,
    val value: String,
    val capturing: Boolean = false,
)
