package com.gruning.mimir.rewrite.core.model.home

import com.gruning.mimir.rewrite.core.model.GameId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeShelfContractTest {
    @Test
    fun `emulated and Android game identities cannot collide`() {
        val emulated = HomeShelfItemId.Emulated(GameId("same-id"))
        val android = HomeShelfItemId.AndroidGame(AndroidPackageName("same-id"))

        assertEquals(HomeShelfItemKind.EMULATED_GAME, emulated.kind)
        assertEquals(HomeShelfItemKind.ANDROID_GAME, android.kind)
        assertNotEquals(emulated, android)
    }

    @Test
    fun `Android package identity rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) { AndroidPackageName("") }
        assertThrows(IllegalArgumentException::class.java) { AndroidPackageName("   ") }
    }

    @Test
    fun `idempotent result retains the same durable membership`() {
        val membership = HomeShelfMembership(
            itemId = HomeShelfItemId.Emulated(GameId("chrono-cross")),
            rank = 1_024L,
            addedAtMillis = 42L,
        )

        assertEquals(membership, AddToShelfResult.Added(membership).membership)
        assertEquals(membership, AddToShelfResult.AlreadyPresent(membership).membership)
    }
}
