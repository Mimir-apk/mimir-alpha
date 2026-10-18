package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider

/**
 * A dock seat's name in the resources a Robolectric test runs under (the labels are string resources). The default
 * device locale is English, so this is the sentence the rail tests always pinned; [quickRailLabelIn] takes the `Resources` of any other.
 */
internal fun quickRailLabel(action: MimirQuickRailAction): String =
    quickRailLabelIn(ApplicationProvider.getApplicationContext<Context>().resources, action)

internal fun quickRailLabelIn(resources: Resources, action: MimirQuickRailAction): String = resources.getString(action.labelRes)
