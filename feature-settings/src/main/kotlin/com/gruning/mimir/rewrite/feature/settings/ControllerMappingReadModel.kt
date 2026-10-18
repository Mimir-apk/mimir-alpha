package com.gruning.mimir.rewrite.feature.settings

const val ControllerMappingLauncherRowId = "controls/controller/mapping"
const val ControllerMappingApplyRowId = "controls/controller/mapping/apply"
const val ControllerMappingResetRowId = "controls/controller/mapping/reset"
const val ControllerMappingActionRowPrefix = "controls/controller/mapping/action/"

data class ControllerMappingRow(
    val id: String,
    val actionName: String,
    val title: String,
    val binding: String,
    val capturing: Boolean = false,
)
