package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * The two commands the vendor route sends root for the bottom screen's brightness, and the one line it reads
 * back. Root runs whatever this renders, so the rendering is pinned to the byte, and every way out of the
 * template is refused before a command exists.
 *
 * Uses built-in vendor access without Shizuku.
 */
class VendorDisplayBrightnessCommandTest {
    private val thor = DisplayBrightnessTransactions(setBrightness = 35, getBrightness = 36)

    @Test
    fun `vendor value policy keeps setting normalization and secondary display bounds`() {
        assertTrue(CertifiedVendorValuePolicy.isAllowedKey(BrightnessSettingsKeys.AYN_SECONDARY))
        assertTrue(CertifiedVendorValuePolicy.isAllowedKey(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT))
        assertFalse(CertifiedVendorValuePolicy.isAllowedKey(BrightnessSettingsKeys.PRIMARY))
        assertFalse(CertifiedVendorValuePolicy.isAllowedKey("adb_enabled"))
        assertEquals("74", CertifiedVendorValuePolicy.normalize(BrightnessSettingsKeys.AYN_SECONDARY, " 074 "))
        assertEquals(
            "0.05",
            CertifiedVendorValuePolicy.normalize(BrightnessSettingsKeys.VENDOR_JOYSTICK_PERCENT, "0.0500"),
        )
        assertNull(CertifiedVendorValuePolicy.normalize(BrightnessSettingsKeys.AYN_SECONDARY, "101"))

        assertTrue(CertifiedVendorValuePolicy.isValidDisplayTransaction(1, 1f, 100))
        assertTrue(CertifiedVendorValuePolicy.isValidDisplayTransaction(4096, 0.74f, 74))
        assertFalse(CertifiedVendorValuePolicy.isValidDisplayTransaction(0, 0.74f, 74))
        assertFalse(CertifiedVendorValuePolicy.isValidDisplayTransaction(4097, 0.74f, 74))
        assertFalse(CertifiedVendorValuePolicy.isValidDisplayTransaction(4, 0.74f, 75))
    }

    @Test
    fun `the read is the display manager's getBrightness, by number, for one display`() {
        val read = VendorPServerOperation.ReadDisplayBrightness.create(thor, 4)!!
        assertEquals("/system/bin/service call display 36 i32 4", read.render())
    }

    @Test
    fun `the write is setBrightness with the fraction written here, to four places`() {
        fun write(fraction: Float) = VendorPServerOperation.WriteDisplayBrightness.create(thor, 4, fraction)!!.render()
        assertEquals("/system/bin/service call display 35 i32 4 f 0.6500", write(0.65f))
        assertEquals("/system/bin/service call display 35 i32 4 f 0.0500", write(0.05f))
        assertEquals("/system/bin/service call display 35 i32 4 f 1.0000", write(1f))
        assertEquals("a restore may write what the panel held, below the slider's floor", "/system/bin/service call display 35 i32 4 f 0.0000", write(0f))
    }

    @Test
    fun `a device language with a decimal comma cannot change the number`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(
                "/system/bin/service call display 35 i32 4 f 0.6500",
                VendorPServerOperation.WriteDisplayBrightness.create(thor, 4, 0.65f)!!.render(),
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the longest command still fits the channel`() {
        val widest = DisplayBrightnessTransactions(setBrightness = 0x00ffffff, getBrightness = 0x00fffffe)
        val commands = listOf(
            VendorPServerOperation.WriteDisplayBrightness.create(widest, 4096, 0.9999f)!!.render(),
            VendorPServerOperation.ReadDisplayBrightness.create(widest, 4096)!!.render(),
        )
        for (command in commands) {
            assertTrue(
                command,
                command.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes,
            )
        }
    }

    @Test
    fun `anything outside the template is refused before a command exists`() {
        val read = VendorPServerOperation.ReadDisplayBrightness
        val write = VendorPServerOperation.WriteDisplayBrightness
        assertNull("the main screen is never a target", write.create(thor, 0, 0.5f))
        assertNull(write.create(thor, -1, 0.5f))
        assertNull(write.create(thor, 4097, 0.5f))
        assertNull(read.create(thor, 0))
        assertNull(read.create(thor, 4097))
        for (fraction in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.0001f, 2f)) {
            assertNull("fraction $fraction", write.create(thor, 4, fraction))
        }
        assertNull(write.create(thor.copy(setBrightness = 0), 4, 0.5f))
        assertNull(write.create(thor.copy(setBrightness = 0x01000000), 4, 0.5f))
        assertNull(read.create(thor.copy(getBrightness = -3), 4))
    }

    // -- The reply ---------------------------------------------------------------------------------------

    @Test
    fun `the Thor's own reply reads as the bottom screen's 0_65`() {
        // Read over adb on 2026-09-18 with `service call display 36 i32 4`, while AYN's level read 65.
        val reply = "Result: Parcel(00000000 3f266667   '....gf&?')"
        val read = VendorServiceCallReply.brightness(reply) as VendorDisplayRead.Value
        assertEquals(0.65f, read.fraction, 0.0001f)
        assertEquals(
            "the channel's NUL and newline padding is not part of the answer",
            read,
            VendorServiceCallReply.brightness("$reply\n\u0000\u0000"),
        )
    }

    @Test
    fun `a clean reply that is not a brightness is no brightness, not a refusal`() {
        // NaN is what the display manager answers for a display without a brightness of its own.
        assertEquals(VendorDisplayRead.NoBrightness, VendorServiceCallReply.brightness("Result: Parcel(00000000 7fc00000   '........')"))
        assertEquals(VendorDisplayRead.NoBrightness, VendorServiceCallReply.brightness("Result: Parcel(00000000 40000000   '.......@')"))
        assertEquals(VendorDisplayRead.NoBrightness, VendorServiceCallReply.brightness("Result: Parcel(00000000 bf000000   '........')"))
    }

    @Test
    fun `anything but a clean display-manager reply is a refusal`() {
        val refusals = listOf(
            // The first line of an exception, whose message pushes the reply onto several lines.
            "Result: Parcel(",
            // An exception code in the first word.
            "Result: Parcel(ffffffff 00000000   '........')",
            "Result: Parcel(fffffffc 3f266667   '....gf&?')",
            // Three words: some other method's reply.
            "Result: Parcel(00000000 3f266667 00000001 '............')",
            // A void reply where a float was asked for.
            "Result: Parcel(00000000    '....')",
            "Result: Parcel(Error: 0xffffffb6 \"Not a data message\")",
            "service: Service display does not exist",
        )
        for (reply in refusals) {
            assertEquals(reply, VendorDisplayRead.Refused, VendorServiceCallReply.brightness(reply))
        }
    }

    @Test
    fun `an empty reply is the channel losing it, not a refusal that would close the route`() {
        assertEquals(VendorDisplayRead.Failed, VendorServiceCallReply.brightness(""))
        assertEquals(VendorDisplayRead.Failed, VendorServiceCallReply.brightness("\n${Char(0)}${Char(0)} \r\n"))
        assertFalse("nor is it a write that took", VendorServiceCallReply.succeeded(""))
    }

    @Test
    fun `a reply in the log is one short printable line`() {
        assertEquals("Result: Parcel(", VendorServiceCallReply.forLog("Result: Parcel(\n${Char(0)}"))
        assertEquals("a?b?c", VendorServiceCallReply.forLog("a${Char(7)}b${Char(0x7f)}c"))
        assertEquals(80, VendorServiceCallReply.forLog("0".repeat(500)).length)
        assertEquals("", VendorServiceCallReply.forLog(""))
    }

    @Test
    fun `a write succeeded only when the display manager returned nothing and threw nothing`() {
        assertTrue(VendorServiceCallReply.succeeded("Result: Parcel(00000000    '....')"))
        assertTrue(VendorServiceCallReply.succeeded("Result: Parcel(00000000    '....')\n"))
        assertFalse(VendorServiceCallReply.succeeded("Result: Parcel("))
        assertFalse(VendorServiceCallReply.succeeded("Result: Parcel(ffffffec    '....')"))
        assertFalse("a value where nothing was expected is another method", VendorServiceCallReply.succeeded("Result: Parcel(00000000 3f266667   '....gf&?')"))
        assertFalse(VendorServiceCallReply.succeeded("service: Service display does not exist"))
        assertFalse(VendorServiceCallReply.succeeded(""))
    }
}
