package com.gruning.mimir.tweaker

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 2026-09-19 trap, for a setting that survives a reboot: a daemon frozen while Performance (2)
 * is applied, then re-armed, would read `2` as the stock to restore and hold it forever. The record
 * keeps the player's own mode (the origin) apart from Mimir's value (applied) so a capture can tell
 * the two apart.
 *
 * Modes are the vendor's: 0 = Standard, 1 = Performance, 2 = High performance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VendorModeOriginTest {
    private val held = VendorModeOriginRecord(origin = 1, applied = 2, transactionId = "tx-1")

    // ---- capture: the origin a snapshot must carry ----

    @Test
    fun `with no record the live mode is the origin`() {
        val resolved = VendorModeOriginPolicy.resolveAtCapture(stored = null, live = 1)

        assertEquals(1, resolved.origin)
        assertNull(resolved.held)
        assertNull(resolved.retired)
    }

    /** The strand itself: a re-arm observing Mimir's own 2 must not call it the player's mode. */
    @Test
    fun `a re-arm that finds Mimir's own value keeps the stored origin and never adopts the live read`() {
        val resolved = VendorModeOriginPolicy.resolveAtCapture(stored = held, live = 2)

        assertEquals(1, resolved.origin)
        assertEquals(held, resolved.held)
        assertNull(resolved.retired)
    }

    @Test
    fun `a live value that is not the applied one means the player moved it so the record retires`() {
        // The player chose Standard in the vendor UI.
        val moved = VendorModeOriginPolicy.resolveAtCapture(stored = held, live = 0)
        assertEquals(0, moved.origin)
        assertNull(moved.held)
        assertEquals(held, moved.retired)

        // Restored to the origin but the record was not cleared (crash between): same rule, same answer.
        val restored = VendorModeOriginPolicy.resolveAtCapture(stored = held, live = 1)
        assertEquals(1, restored.origin)
        assertNull(restored.held)
        assertEquals(held, restored.retired)
    }

    // ---- apply: when a record is written ----

    @Test
    fun `the first apply that changes the key writes origin applied and the transaction`() {
        val record = VendorModeOriginPolicy.recordAfterApply(origin = 0, target = 2, transactionId = "tx-a")

        assertEquals(VendorModeOriginRecord(origin = 0, applied = 2, transactionId = "tx-a"), record)
    }

    @Test
    fun `an apply that targets the origin itself holds no record`() {
        assertNull(VendorModeOriginPolicy.recordAfterApply(origin = 1, target = 1, transactionId = "tx-a"))
    }

    @Test
    fun `a profile switch under a held record keeps the origin and moves applied`() {
        val resolved = VendorModeOriginPolicy.resolveAtCapture(stored = held, live = 2)

        val next = VendorModeOriginPolicy.recordAfterApply(resolved.origin, target = 0, transactionId = "tx-2")

        assertEquals(VendorModeOriginRecord(origin = 1, applied = 0, transactionId = "tx-2"), next)
    }

    @Test
    fun `an apply back onto the origin clears the held record`() {
        val resolved = VendorModeOriginPolicy.resolveAtCapture(stored = held, live = 2)

        assertNull(VendorModeOriginPolicy.recordAfterApply(resolved.origin, target = 1, transactionId = "tx-3"))
    }

    @Test
    fun `unreachable inputs build no record rather than throwing in the apply path`() {
        assertNull(VendorModeOriginPolicy.recordAfterApply(origin = 7, target = 2, transactionId = "tx"))
        assertNull(VendorModeOriginPolicy.recordAfterApply(origin = 0, target = 3, transactionId = "tx"))
        assertNull(VendorModeOriginPolicy.recordAfterApply(origin = 0, target = 2, transactionId = " "))
    }

    // ---- restore: the Guardian's three rows ----

    @Test
    fun `restore writes the origin only while the key still reads Mimir's value`() {
        assertEquals(
            VendorModeRestoreAction.WRITE_ORIGIN,
            VendorModeOriginPolicy.decideRestore(origin = 1, applied = 2, live = 2),
        )
        assertEquals(
            VendorModeRestoreAction.ALREADY_AT_ORIGIN,
            VendorModeOriginPolicy.decideRestore(origin = 1, applied = 2, live = 1),
        )
        assertEquals(
            VendorModeRestoreAction.SKIP_PLAYER_CHANGED,
            VendorModeOriginPolicy.decideRestore(origin = 1, applied = 2, live = 0),
        )
        assertEquals(
            VendorModeRestoreAction.UNREADABLE,
            VendorModeOriginPolicy.decideRestore(origin = 1, applied = 2, live = null),
        )
    }

    @Test
    fun `a degenerate entry whose origin equals applied is already restored not rewritten`() {
        assertEquals(
            VendorModeRestoreAction.ALREADY_AT_ORIGIN,
            VendorModeOriginPolicy.decideRestore(origin = 2, applied = 2, live = 2),
        )
    }

    /**
     * V-P1 (2026-10-13 review): a restore that did not land keeps the record (the next attempt still needs
     * the origin); every other readback clears it, so Mimir's `applied` cannot outlive its session and be
     * taken for the player's mode by a later capture.
     */
    @Test
    fun `the record survives only a restore that did not land`() {
        // held = origin 1, applied 2.
        assertEquals("still Mimir's own value: the restore did not land", held, VendorModeOriginPolicy.recordAfterRestore(held, readback = 2))
        assertEquals("unreadable: nothing is known", held, VendorModeOriginPolicy.recordAfterRestore(held, readback = null))
        assertNull("the origin is back", VendorModeOriginPolicy.recordAfterRestore(held, readback = 1))
        assertNull("a mode the player chose", VendorModeOriginPolicy.recordAfterRestore(held, readback = 0))
        assertNull(VendorModeOriginPolicy.recordAfterRestore(null, readback = 1))
        assertNull(VendorModeOriginPolicy.recordAfterRestore(null, readback = null))
    }

    // ---- whole lifecycle, through a real store ----

    @Test
    fun `apply freeze re-arm and restore put the players mode back, not Mimir's`() {
        val directory = Files.createTempDirectory("vendor-origin-life").toFile()
        try {
            val store = VendorModeOriginStore(directory)
            var live = 0 // the player's own mode: Standard

            // Transaction 1: Performance (High performance, 2). Persist the record BEFORE the write.
            val first = VendorModeOriginPolicy.resolveAtCapture(store.load().recordOrNull, live)
            val record1 = VendorModeOriginPolicy.recordAfterApply(first.origin, target = 2, transactionId = "tx-1")!!
            assertTrue(store.save(record1))
            live = 2

            // The daemon is frozen; a fresh process re-arms the same profile. The store is re-read.
            val rearmStore = VendorModeOriginStore(directory)
            val second = VendorModeOriginPolicy.resolveAtCapture(rearmStore.load().recordOrNull, live)
            assertEquals("the re-arm must not adopt Mimir's 2 as stock", 0, second.origin)
            val record2 = VendorModeOriginPolicy.recordAfterApply(second.origin, target = 2, transactionId = "tx-2")!!
            assertTrue(rearmStore.save(record2))
            assertEquals(VendorModeOriginLoad.Held(record2), rearmStore.load())

            // Restore: the key still reads Mimir's value, so the origin goes back and the record clears.
            val stored = rearmStore.load().recordOrNull!!
            assertEquals(
                VendorModeRestoreAction.WRITE_ORIGIN,
                VendorModeOriginPolicy.decideRestore(stored.origin, stored.applied, live),
            )
            live = stored.origin
            val after = VendorModeOriginPolicy.recordAfterRestore(stored, readback = live)
            assertNull(after)
            assertTrue(rearmStore.clear())
            assertEquals(VendorModeOriginLoad.Absent, rearmStore.load())
            assertEquals(0, live)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the record survives a restart and is not keyed to a boot`() {
        val directory = Files.createTempDirectory("vendor-origin-boot").toFile()
        try {
            assertTrue(VendorModeOriginStore(directory).save(held))

            // "Reboot": nothing about the record names a boot, so a later process reads it unchanged.
            assertEquals(VendorModeOriginLoad.Held(held), VendorModeOriginStore(directory).load())

            val json = JSONObject(String(VendorModeOriginCodec.encode(held), Charsets.UTF_8))
            assertEquals(setOf("schema", "origin", "applied", "transactionId"), json.keys().asSequence().toSet())
        } finally {
            directory.deleteRecursively()
        }
    }

    // ---- codec ----

    @Test
    fun `a record survives encoding unchanged`() {
        assertEquals(held, VendorModeOriginCodec.decode(VendorModeOriginCodec.encode(held)))
    }

    @Test
    fun `corrupt foreign or out of range records decode to nothing rather than guessing`() {
        fun decode(text: String) = VendorModeOriginCodec.decode(text.toByteArray())

        assertNull(VendorModeOriginCodec.decode(null))
        assertNull(VendorModeOriginCodec.decode(ByteArray(0)))
        assertNull(decode("not json"))
        assertNull(decode("{\"schema\":99,\"origin\":1,\"applied\":2,\"transactionId\":\"t\"}"))
        assertNull(decode("{\"origin\":1,\"applied\":2,\"transactionId\":\"t\"}"))
        assertNull(decode("{\"schema\":1,\"applied\":2,\"transactionId\":\"t\"}"))
        assertNull(decode("{\"schema\":1,\"origin\":1,\"applied\":2}"))
        assertNull(decode("{\"schema\":1,\"origin\":1,\"applied\":2,\"transactionId\":\" \"}"))
        // origin == applied is not a deviation; a mode outside the vendor's three is not a mode.
        assertNull(decode("{\"schema\":1,\"origin\":2,\"applied\":2,\"transactionId\":\"t\"}"))
        assertNull(decode("{\"schema\":1,\"origin\":3,\"applied\":2,\"transactionId\":\"t\"}"))
        assertNull(decode("{\"schema\":1,\"origin\":-1,\"applied\":2,\"transactionId\":\"t\"}"))
        // Wrong types are not coerced.
        assertNull(decode("{\"schema\":1,\"origin\":\"1\",\"applied\":2,\"transactionId\":\"t\"}"))
        assertNull(decode("{\"schema\":1,\"origin\":1.0,\"applied\":2,\"transactionId\":\"t\"}"))
        assertEquals(held, decode("{\"schema\":1,\"origin\":1,\"applied\":2,\"transactionId\":\"tx-1\"}"))
    }

    @Test
    fun `the record type itself refuses a state that is not a deviation`() {
        listOf(
            { VendorModeOriginRecord(origin = 2, applied = 2, transactionId = "t") },
            { VendorModeOriginRecord(origin = 3, applied = 2, transactionId = "t") },
            { VendorModeOriginRecord(origin = 0, applied = -1, transactionId = "t") },
            { VendorModeOriginRecord(origin = 0, applied = 2, transactionId = "") },
        ).forEach { build ->
            val refused = runCatching { build() }.isFailure
            assertTrue(refused)
        }
    }

    // ---- store ----

    @Test
    fun `an empty store is absent and a saved record loads back and clears`() {
        val directory = Files.createTempDirectory("vendor-origin-store").toFile()
        try {
            val store = VendorModeOriginStore(File(directory, "nested"))
            assertEquals(VendorModeOriginLoad.Absent, store.load())
            assertTrue("clearing nothing is fine", store.clear())

            assertTrue(store.save(held))
            assertEquals(VendorModeOriginLoad.Held(held), store.load())
            assertEquals(held, store.load().recordOrNull)

            val replacement = VendorModeOriginRecord(origin = 1, applied = 0, transactionId = "tx-9")
            assertTrue(store.save(replacement))
            assertEquals(VendorModeOriginLoad.Held(replacement), store.load())
            assertFalse("no staging file may outlive a save", File(directory, "nested/.origin.json.tmp").exists())

            assertTrue(store.clear())
            assertEquals(VendorModeOriginLoad.Absent, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a damaged file is reported as corrupt not as no record`() {
        val directory = Files.createTempDirectory("vendor-origin-corrupt").toFile()
        try {
            val store = VendorModeOriginStore(directory)
            File(directory, "origin.json").writeText("{ half a rec")

            assertEquals(VendorModeOriginLoad.Corrupt, store.load())
            assertNull("the policy sees no record", store.load().recordOrNull)

            // A good save repairs it.
            assertTrue(store.save(held))
            assertEquals(VendorModeOriginLoad.Held(held), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** A crash mid-write leaves a staging file; it is never read and the next save replaces it. */
    @Test
    fun `a leftover staging file from a crashed write is ignored and replaced`() {
        val directory = Files.createTempDirectory("vendor-origin-staging").toFile()
        try {
            val store = VendorModeOriginStore(directory)
            File(directory, ".origin.json.tmp").writeText("torn")

            assertEquals(VendorModeOriginLoad.Absent, store.load())
            assertTrue(store.save(held))
            assertEquals(VendorModeOriginLoad.Held(held), store.load())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Unlike the baseline store, a failed write is reported: a missing origin has no fallback. */
    @Test
    fun `an unwritable store reports the failed save`() {
        val blocked = File(Files.createTempDirectory("vendor-origin-blocked").toFile(), "occupied")
        try {
            blocked.writeText("a file where the directory should be")
            val store = VendorModeOriginStore(blocked)

            assertFalse(store.save(held))
            assertEquals(VendorModeOriginLoad.Absent, store.load())
        } finally {
            blocked.parentFile.deleteRecursively()
        }
    }
}
