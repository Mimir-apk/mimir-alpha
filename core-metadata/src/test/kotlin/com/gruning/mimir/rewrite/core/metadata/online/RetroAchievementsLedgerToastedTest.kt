package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The achievement ids already toasted ("Achievement earned"), kept per account in the ledger so a restart in the middle of a play session never toasts the same unlock twice:
 * saved and read back in order, per account, bounded, idempotent, and a damaged file reads as nothing (or as the previous generation), never as an error. Plain JVM.
 */
class RetroAchievementsLedgerToastedTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val root get() = File(temp.root, "ra_ledger")
    private fun ledger() = RetroAchievementsLedger(root)

    private fun toastedFile(account: String) = File(File(root, RetroAchievementsLedger.accountKey(account)!!), "toasted.json")

    @Test
    fun `nothing saved reads as an empty list`() {
        assertEquals(emptyList<Int>(), ledger().readToasted("player"))
    }

    @Test
    fun `the ids come back in the order they were saved`() {
        val ledger = ledger()
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveToasted("player", listOf(30, 10, 20)))
        assertEquals(listOf(30, 10, 20), ledger.readToasted("player"))
        // A new instance over the same folder (the next process) reads the same.
        assertEquals(listOf(30, 10, 20), RetroAchievementsLedger(root).readToasted("player"))
    }

    @Test
    fun `the same ids twice change nothing, and a longer list is written`() {
        val ledger = ledger()
        ledger.saveToasted("player", listOf(1, 2))
        assertEquals(RetroAchievementsLedgerWrite.UNCHANGED, ledger.saveToasted("player", listOf(1, 2)))
        assertEquals(RetroAchievementsLedgerWrite.WRITTEN, ledger.saveToasted("player", listOf(1, 2, 3)))
        assertEquals(listOf(1, 2, 3), ledger.readToasted("player"))
    }

    @Test
    fun `an account never reads another's ids, and the name is case-insensitive like RetroAchievements'`() {
        val ledger = ledger()
        ledger.saveToasted("Player", listOf(5))
        ledger.saveToasted("someone", listOf(9))
        assertEquals(listOf(5), ledger.readToasted("player"))
        assertEquals(listOf(9), ledger.readToasted("SOMEONE"))
        assertEquals(emptyList<Int>(), ledger.readToasted("nobody"))
    }

    @Test
    fun `the list is bounded to the newest ids, and a blank account saves nothing`() {
        val ledger = ledger()
        val many = (1..RetroAchievementsLedger.MAX_TOASTED + 50).toList()
        ledger.saveToasted("player", many)
        val back = ledger.readToasted("player")
        assertEquals(RetroAchievementsLedger.MAX_TOASTED, back.size)
        assertEquals("the oldest are the ones dropped", 51, back.first())
        assertEquals(RetroAchievementsLedger.MAX_TOASTED + 50, back.last())
        assertEquals(RetroAchievementsLedgerWrite.FAILED, ledger.saveToasted("  ", listOf(1)))
        assertEquals(emptyList<Int>(), ledger.readToasted(""))
    }

    @Test
    fun `an id that is not positive is not kept, and a repeat is kept once`() {
        val ledger = ledger()
        ledger.saveToasted("player", listOf(0, -4, 7, 7, 8))
        assertEquals(listOf(7, 8), ledger.readToasted("player"))
    }

    @Test
    fun `a damaged file reads as the previous generation, or as nothing, never as an error`() {
        val ledger = ledger()
        ledger.saveToasted("player", listOf(1))
        ledger.saveToasted("player", listOf(1, 2))
        val file = toastedFile("player")
        file.writeText("{ not json")
        assertEquals("the previous generation", listOf(1), ledger.readToasted("player"))
        File(file.parentFile, file.name + ".bak").writeText("also not json")
        assertEquals("nothing readable is nothing", emptyList<Int>(), ledger.readToasted("player"))
        // And a file written by a newer format reads as absent.
        file.writeText("""{"v":99,"ids":[1,2,3]}""")
        File(file.parentFile, file.name + ".bak").delete()
        assertEquals(emptyList<Int>(), ledger.readToasted("player"))
        assertTrue(file.exists())
    }

    @Test
    fun `forgetting an account forgets its toasted ids`() {
        val ledger = ledger()
        ledger.saveToasted("player", listOf(1, 2))
        assertTrue(ledger.forget("player"))
        assertEquals(emptyList<Int>(), ledger.readToasted("player"))
    }
}
