package com.gruning.mimir.rewrite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the Bottom's full music player is open (W3e, Y0). Process state, exactly as [CompanionNowPlayingState] holds the takeover's key: the Activity scene is composed
 * only while the workspace is on the Activity page, so a flag the surface `remember`ed was forgotten on every trip to the shelf and the player came back closed. The host
 * collects [open] and hands it to the surface with a change callback; the mini player on the shelf sets it before it switches the workspace to Activity.
 */
internal object CompanionMusicPlayerState {
    private val mutableOpen = MutableStateFlow(false)
    val open: StateFlow<Boolean> = mutableOpen.asStateFlow()

    fun setOpen(value: Boolean) {
        mutableOpen.value = value
    }

    /** Tests only: closes the player. */
    internal fun resetForTest() {
        mutableOpen.value = false
    }
}
