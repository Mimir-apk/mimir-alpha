package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `originScope` SURVIVES the retirement of the origin-derived face (C1, 2026-08-25).
 *
 * This file used to pin `launchCardShowsPhysicalMedia(originScope)` — that a card opened from a Tome wore
 * the disc and one opened from Home wore the cover. C1 supersedes that: the card reads an explicit LAUNCH
 * CARD state, so the same game wears the same face whichever route reached it.
 *
 * **The field is not the function, and deleting it with the function would have broken something
 * unrelated.** `HomeLaunchCardSession.originScope` also carries the FOCUS-RETURN contract — where focus
 * goes when the card closes — in five call sites that have nothing to do with art. That is exactly the
 * failure mode of throwing a real invariant out alongside a superseded one, so it gets a pin of its own.
 */
class HomeLaunchCardOriginTest {

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")

    @Test
    fun `the session still carries where it was opened from`() {
        val coordinator = source("HomeLaunchCardCoordinator.kt")
        assertTrue(
            "HomeLaunchCardSession.originScope carries the focus-return contract and must outlive the " +
                "origin-derived face it used to share a name with",
            coordinator.contains("val originScope: FocusScope"),
        )
        assertTrue(
            "and open() must still record it, not merely accept it",
            coordinator.contains("HomeLaunchCardSession(row, game, originScope)"),
        )
    }

    @Test
    fun `the origin no longer decides the card's face`() {
        assertFalse(
            "launchCardShowsPhysicalMedia derived the face from the open site; C1 replaced derivation " +
                "with an explicit LAUNCH CARD state",
            source("HomeLaunchCardCoordinator.kt").contains("fun launchCardShowsPhysicalMedia"),
        )
        val host = source("MimirAppHost.kt")
        assertTrue(
            "the card resolves its own state instead",
            host.contains("MediaState.LAUNCH_CARD"),
        )
        assertFalse(
            "and nothing gates its art on the origin any more",
            host.contains("launchCardShowsPhysicalMedia"),
        )
    }

    /**
     * The duty the field actually carries, pinned in the file that owns it rather than by counting
     * references across modules: `originScope` is one half of the ORIGIN GUARD. `open()` refuses to raise
     * a card when focus has already moved off the tile that asked for it — the coalesced-input case where
     * a second press lands after focus advanced. Remove the field as "unused after C1" and the card opens
     * on whatever focus drifted to.
     */
    @Test
    fun `the origin guard still refuses a card whose origin lost focus`() {
        val coordinator = source("HomeLaunchCardCoordinator.kt")
        assertTrue(
            "the guard compares live focus against the recorded origin",
            coordinator.contains("focusState.scope == originScope && focusState.focused == originFocusKey"),
        )
        val openBody = coordinator.substringAfter("fun open(").substringBefore("\n    fun ")
        assertTrue(
            "and open() must consult it before it mutates any state",
            openBody.contains("if (!homeLaunchCardOriginIsCurrent("),
        )
        assertEquals(
            "the guard returns early rather than opening anyway",
            true,
            openBody.substringAfter("homeLaunchCardOriginIsCurrent(").contains("return"),
        )
    }
}
