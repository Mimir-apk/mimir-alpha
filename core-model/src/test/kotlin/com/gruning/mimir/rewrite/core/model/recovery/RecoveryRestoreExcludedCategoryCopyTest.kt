package com.gruning.mimir.rewrite.core.model.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Not restored" list is read by the player, so its words follow the product's copy conventions. The elapsed store's rule is that Mimir measures the
 * time between a launch and the return to Mimir, not the time a game ran, so no surface calls it "playtime" (see `MimirTimeCopy`). The category the
 * archive carries under the key "playtime" is that elapsed total, and the list names it as the Activity page and the launch card do: "Session time".
 */
class RecoveryRestoreExcludedCategoryCopyTest {

    @Test
    fun `the elapsed-time category is named Session time, as the Activity page and the launch card name it`() {
        val elapsed = RecoveryRestoreExcludedCategory.entries.single { it.storageKey == "playtime" }
        assertEquals("Session time", elapsed.label)
    }

    @Test
    fun `no disclosed category says playtime or play time`() {
        RecoveryRestoreExcludedCategory.requiredDisclosures.forEach { category ->
            val words = category.label.lowercase()
            assertFalse("'${category.label}' must not say playtime", "playtime" in words)
            assertFalse("'${category.label}' must not say play time", "play time" in words)
        }
    }

    @Test
    fun `every disclosed category still has a label and a unique key`() {
        val all = RecoveryRestoreExcludedCategory.entries
        assertTrue(all.all { it.label.isNotBlank() && it.storageKey.isNotBlank() })
        assertEquals(all.size, all.map { it.storageKey }.toSet().size)
        assertEquals(all.size, all.map { it.label }.toSet().size)
    }
}
