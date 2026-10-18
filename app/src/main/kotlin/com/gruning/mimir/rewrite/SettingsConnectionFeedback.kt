package com.gruning.mimir.rewrite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass

/**
 * What happens after ANY connected service finishes connecting.
 *
 * Before this, a successful connect just closed the overlay — the user was returned to a Settings pane with
 * no confirmation that anything happened and no focus on the thing they had just set up, which reads as
 * "did that work?". Both halves are now one shared path so RetroAchievements, SteamGridDB and ScreenScraper
 * cannot drift into three different post-connect behaviours.
 *
 * The focus half matters as much as the toast: this is a controller device, so returning the user to a pane
 * with focus parked somewhere else costs them a hunt. [focusRowId] names the ROW of the service that was
 * just connected, and the Settings destination re-focuses it once the overlay is gone.
 *
 * **Only the Settings destination delivers either half.** The SteamGridDB key overlay and the ScreenScraper
 * connect are shared overlays: onboarding's METADATA step opens them, and so does the Get-art card on the
 * Library and Home. Neither of those destinations has a rail to show "Connection successful!" or a Settings row
 * to focus, so a success recorded over them was never consumed. It waited, and fired with a focus jump on the
 * next Settings open. [succeeded] is the one entry all three producers
 * call, and it drops the success unless [settingsIsDestination] says Settings is the surface under the overlay,
 * so the toast and the focus row are guarded together and no producer can forget the check. The surface that
 * opened the overlay already says what happened in its own words (onboarding's METADATA row reads "Added" or
 * "Connected").
 *
 * @param settingsIsDestination read at the moment of a success, not at construction.
 */
internal class SettingsConnectionFeedback(
    private val settingsIsDestination: () -> Boolean,
) {

    /** The success toast to show, or null when nothing is pending. */
    var toast by mutableStateOf<MimirMessage?>(null)
        private set

    /** The settings row id to return focus to, or null when nothing is pending. */
    var focusRowId by mutableStateOf<String?>(null)
        private set

    private var eventId = 0L

    /**
     * Record that [rowId]'s service connected successfully, when Settings is the destination under the overlay
     * that just closed; from any other destination this records nothing, neither the toast nor the focus row.
     * The event id advances for state observation; semantic duplicates still coalesce inside the shared message
     * window.
     */
    fun succeeded(rowId: String) {
        if (!settingsIsDestination()) return
        eventId += 1
        toast = MimirMessage(
            eventId = eventId,
            text = ConnectionSuccessful,
            messageClass = MimirMessageClass.Confirmation,
        )
        focusRowId = rowId
    }

    /**
     * Consume only the event the rail confirms it delivered. The identity check is load-bearing: an older
     * delivery callback must never clear a newer success that replaced it before the effect ran.
     */
    fun consumeDeliveredMessage(deliveredEventId: Long) {
        if (toast?.eventId == deliveredEventId) toast = null
    }

    /** The focus request has been issued; clear it so it fires once, not on every recomposition. */
    fun consumeFocus() {
        focusRowId = null
    }

    /**
     * Drop whatever the Settings destination has not delivered yet, both halves. The destination calls this as it
     * leaves composition: a success recorded in the last frames of a visit must not wait for the NEXT visit and
     * arrive as news. Safe because the destination stays composed under the key and connect overlays (they are
     * siblings above the destination host and never change it), so a legitimate success is delivered before the
     * destination can leave.
     */
    fun clearPending() {
        toast = null
        focusRowId = null
    }

    companion object {
        /** Shared confirmation wording keeps every service consistent. */
        const val ConnectionSuccessful: String = "Connection successful!"
    }
}
