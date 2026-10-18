package com.gruning.mimir.rewrite

import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The read-only process-presence probe for stale Open Apps entries reported on AYN Thor, 2026-09-11.
 *
 * A Tabs row is retired only when this probe answers ABSENT, so its contract is narrower than the stop's.
 * It never stops anything. It is composed only for a package [MimirLaunchTargetStopPolicy] admits. And a
 * reply that is not one of its two markers -- the stop's own success marker included -- reads UNKNOWN.
 */
class CertifiedVendorProcessPresenceTest {
    private lateinit var previousProvider: () -> Set<String>

    @Before
    fun allowTabPackages() {
        previousProvider = MimirLaunchTargetStopPolicy.sessionAllowedPackages
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(YOUTUBE, CHROME) }
    }

    @After
    fun restoreProvider() {
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = previousProvider
    }

    @Test
    fun `the presence probe is a guarded pidof that stops nothing`() {
        val rendered = VendorPServerOperation.processPresence(YOUTUBE)?.render()

        assertEquals(
            "if [ -x /system/bin/pidof ]; then /system/bin/pidof com.google.android.youtube >/dev/null 2>&1 " +
                "&& /system/bin/echo __MIMIR_ALIVE__ || /system/bin/echo __MIMIR_ABSENT__; fi",
            rendered,
        )
        assertFalse(rendered!!.contains("force-stop"))
        assertFalse(rendered.contains("/system/bin/am "))
        assertFalse(rendered.contains(VendorPServerOperation.SuccessMarker))
        assertTrue(rendered.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes)
    }

    @Test
    fun `the presence probe is composed only for a package the stop policy admits`() {
        assertNotNull(VendorPServerOperation.processPresence(YOUTUBE))

        // The same package and the same syntax; only the policy's answer changes. This null is the stop
        // policy's door, not the shell-safety floor.
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(CHROME) }
        assertNull(VendorPServerOperation.processPresence(YOUTUBE))
        assertNotNull(VendorPServerOperation.processPresence(CHROME))

        // A package Mimir never launched. The broader open-app stop policy would admit it; this door does not.
        assertNull(VendorPServerOperation.processPresence("com.example.unrelated"))
        assertNull(VendorPServerOperation.processPresence(BuildConfig.APPLICATION_ID))
        assertNull(VendorPServerOperation.processPresence("com.android.chrome; /system/bin/id"))
    }

    @Test
    fun `the probe's own markers are its only answers`() {
        assertEquals(PrivilegedPackageLiveness.ABSENT, parseVendorProcessPresence("__MIMIR_ABSENT__"))
        assertEquals(PrivilegedPackageLiveness.ABSENT, parseVendorProcessPresence("__MIMIR_ABSENT__\n"))
        assertEquals(PrivilegedPackageLiveness.ALIVE, parseVendorProcessPresence("__MIMIR_ALIVE__\r\n"))
        assertEquals(PrivilegedPackageLiveness.ALIVE, parseVendorProcessPresence(VendorPServerOperation.ProcessAliveMarker))
        assertEquals(PrivilegedPackageLiveness.ABSENT, parseVendorProcessPresence(VendorPServerOperation.ProcessAbsentMarker))
    }

    @Test
    fun `anything that is not an answer is unknown and never absent`() {
        // The STOP's success marker is not a presence answer: read as absence, it would retire a tab.
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence(VendorPServerOperation.SuccessMarker))
        // The `[ -x /system/bin/pidof ]` guard found no pidof, so the probe printed nothing.
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence(""))
        // No reply at all: the transaction failed or timed out.
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence(null))
        // A shell error line, a pid, or a near-miss is not an answer either.
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence("sh: [: not found"))
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence("2831"))
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence("__MIMIR_ABSENT__X"))
        // The channel carries one line; a marker below another line was never the answer.
        assertEquals(PrivilegedPackageLiveness.UNKNOWN, parseVendorProcessPresence("garbage\n__MIMIR_ABSENT__"))
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val CHROME = "com.android.chrome"
    }
}
