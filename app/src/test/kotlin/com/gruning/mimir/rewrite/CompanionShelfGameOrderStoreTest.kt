package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion shelf's filed game order, on the wire.
 *
 * This is read at startup before anything is on screen, so the property that matters is not that a good
 * value round-trips — it is that a BAD one costs at most one platform's order and never the shelf.
 */
class CompanionShelfGameOrderStoreTest {

    @Test
    fun aFiledOrderRoundTrips() {
        val order = mapOf("psx" to listOf("a", "b", "c"), "gba" to listOf("x"))
        assertEquals(order, decodeCompanionShelfGameOrder(encodeCompanionShelfGameOrder(order)))
    }

    @Test
    fun anUntouchedDeviceDecodesToNothingRatherThanThrowing() {
        assertEquals(emptyMap<String, List<String>>(), decodeCompanionShelfGameOrder(""))
        assertEquals(emptyMap<String, List<String>>(), decodeCompanionShelfGameOrder("   "))
    }

    @Test
    fun aMalformedRecordCostsThatPlatformAndNothingElse() {
        // A platform with no ids, and a stray separator run, alongside a good record.
        val raw = encodeCompanionShelfGameOrder(mapOf("psx" to listOf("a", "b"))) +
            CompanionShelfGameOrderStore.PlatformSeparator +
            "orphan" +
            CompanionShelfGameOrderStore.PlatformSeparator +
            CompanionShelfGameOrderStore.FieldSeparator.toString().repeat(3)
        val decoded = decodeCompanionShelfGameOrder(raw)
        assertEquals(listOf("a", "b"), decoded["psx"])
        assertTrue("a record with no ids is not an order", "orphan" !in decoded)
        assertEquals(1, decoded.size)
    }

    @Test
    fun anEmptyOrderIsNotWrittenAsARecord() {
        // Otherwise "arranged, then every game vanished" would encode a platform with no ids, which the
        // decoder drops — a write that cannot be read back is a bug waiting for a puzzled evening.
        assertEquals("", encodeCompanionShelfGameOrder(mapOf("psx" to emptyList())))
        assertEquals("", encodeCompanionShelfGameOrder(mapOf("" to listOf("a"))))
    }
}
