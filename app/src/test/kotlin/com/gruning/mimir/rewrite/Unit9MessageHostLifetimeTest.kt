package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Unit9MessageHostLifetimeTest {

    @Test
    fun deliveredHomeMessageCannotReplayAndStaleDeliveryCannotClearNewerMessage() {
        val older = MimirMessage(1L, "Favorite added", MimirMessageClass.Confirmation)
        val newer = MimirMessage(2L, "Tome order not saved", MimirMessageClass.Warning)

        assertNull(consumeDeliveredHomeMessage(older, older.eventId))
        assertEquals(newer, consumeDeliveredHomeMessage(newer, older.eventId))
    }

    @Test
    fun deliveredSettingsSuccessCannotReplayOnHostRemount() {
        val feedback = SettingsConnectionFeedback(settingsIsDestination = { true })
        feedback.succeeded("retro-achievements")
        val firstMountMessage = checkNotNull(feedback.toast)

        feedback.consumeDeliveredMessage(firstMountMessage.eventId)

        assertNull(feedback.toast)
        val remountedHostMessage = feedback.toast
        assertNull(remountedHostMessage)
    }

    @Test
    fun staleDeliveryCannotConsumeNewerSettingsSuccess() {
        val feedback = SettingsConnectionFeedback(settingsIsDestination = { true })
        feedback.succeeded("retro-achievements")
        val older = checkNotNull(feedback.toast)
        feedback.succeeded("screen-scraper")
        val newer = checkNotNull(feedback.toast)

        feedback.consumeDeliveredMessage(older.eventId)
        assertEquals(newer, feedback.toast)

        feedback.consumeDeliveredMessage(newer.eventId)
        assertNull(feedback.toast)
    }

    @Test
    fun storageNoticeRemainsThroughExitAndClearsOnlyWhenSettled() {
        val removed = StorageVolumeMonitor.StorageNotice(
            kind = StorageVolumeMonitor.NoticeKind.CARD_REMOVED,
            unavailableCount = 2,
        )
        val active = retainedStorageNoticeForFrame(
            current = removed,
            retained = null,
            exitSettled = false,
        )
        val exiting = retainedStorageNoticeForFrame(
            current = null,
            retained = active,
            exitSettled = false,
        )
        val settled = retainedStorageNoticeForFrame(
            current = null,
            retained = exiting,
            exitSettled = true,
        )

        assertEquals(removed, active)
        assertEquals(removed, exiting)
        assertNull(settled)
    }

    @Test
    fun replacementStorageNoticeWinsEvenAtExitBoundary() {
        val removed = StorageVolumeMonitor.StorageNotice(
            kind = StorageVolumeMonitor.NoticeKind.CARD_REMOVED,
            unavailableCount = 2,
        )
        val swapped = StorageVolumeMonitor.StorageNotice(
            kind = StorageVolumeMonitor.NoticeKind.CARD_SWAPPED,
            unavailableCount = 3,
        )

        assertEquals(
            swapped,
            retainedStorageNoticeForFrame(
                current = swapped,
                retained = removed,
                exitSettled = true,
            ),
        )
    }

    @Test
    fun storageNoticeFadesOnHouseDurationAndEasingNotComposeDefault() {
        val active = storageNoticeFadeSpec(reducedMotion = false)
        assertEquals(MimirMotion.OverlayMs, active.durationMillis)
        assertEquals(MimirMotion.Easing, active.easing)

        val reduced = storageNoticeFadeSpec(reducedMotion = true)
        assertEquals(0, reduced.durationMillis)
        assertEquals(MimirMotion.Easing, reduced.easing)
    }

    /**
     * The composable must actually route through [storageNoticeFadeSpec] rather than calling a raw
     * `tween()` (which defaults to Compose's `FastOutSlowInEasing`, not the house easing) at its two
     * AnimatedVisibility call sites. No Compose harness runs in this test, so this proves the wiring is
     * present, not that a frame renders — the same distinction `MimirGlassTransitionMountTest` (core-ui)
     * draws for `MimirGlassTransition.kt`.
     */
    @Test
    fun storageNoticeHostRoutesBothEnterAndExitThroughTheSharedFadeSpec() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/StorageNoticeHost.kt")
        assertTrue("StorageNoticeHost.kt not found at ${file.absolutePath}", file.exists())
        val code = file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

        assertTrue(
            "enter = fadeIn(...) must call storageNoticeFadeSpec(reducedMotion), not a raw tween().",
            code.contains("enter = fadeIn(storageNoticeFadeSpec(reducedMotion))"),
        )
        assertTrue(
            "exit = fadeOut(...) must call storageNoticeFadeSpec(reducedMotion), not a raw tween().",
            code.contains("exit = fadeOut(storageNoticeFadeSpec(reducedMotion))"),
        )
    }
}
