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
 * The privileged close protocol the AYN Thor forced on 2026-09-11 09:34.
 *
 * A device test pressed ✕ on Chrome in Bottom Tabs. The firmware answered
 * `forceStopPackage() WhiteList packageName=com.android.chrome`, `am force-stop` exited 0, Mimir
 * logged `mutation=APPLIED`, and Chrome kept its task on display 4 — visible in Android Recents and
 * re-openable from there. These tests pin the two things that stops that repeating: the stop command
 * carries its own liveness probe, and a survivor is answered by evicting its task.
 */
class CertifiedVendorVerifiedCloseTest {
    private lateinit var previousProvider: () -> Set<String>

    @Before
    fun allowSessionPackage() {
        previousProvider = MimirLaunchTargetStopPolicy.sessionAllowedPackages
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(CHROME, LONG_PACKAGE) }
    }

    @After
    fun restoreProvider() {
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = previousProvider
    }

    // ----------------------------------------------------------------- rendered command contract

    @Test
    fun `stop command proves liveness instead of trusting the exit code`() {
        val rendered = VendorPServerOperation.forceStop(CHROME)?.render()

        assertEquals(
            "/system/bin/am force-stop --user 0 com.android.chrome; " +
                "/system/bin/pidof com.android.chrome >/dev/null 2>&1 || " +
                "/system/bin/echo __MIMIR_VENDOR_OK__",
            rendered,
        )
        // The marker is no longer `&&`-chained to the stop: an accepted-but-whitelisted force-stop
        // must not be able to echo it.
        assertFalse(rendered!!.contains("force-stop --user 0 $CHROME &&"))
        assertTrue(rendered.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes)
    }

    @Test
    fun `task removal is composed from an Int and never from a caller string`() {
        assertEquals(
            "/system/bin/am stack remove 1234 && /system/bin/echo __MIMIR_VENDOR_OK__",
            VendorPServerOperation.removeTask(1234)?.render(),
        )
        assertNull(VendorPServerOperation.removeTask(0))
        assertNull(VendorPServerOperation.removeTask(-1))
    }

    @Test
    fun `task focus is composed from a positive Int and requires the success marker`() {
        val rendered = VendorPServerOperation.focusTask(4052)?.render()

        assertEquals(
            "/system/bin/am task focus 4052 >/dev/null && /system/bin/echo __MIMIR_VENDOR_OK__",
            rendered,
        )
        assertNull(VendorPServerOperation.focusTask(0))
        assertNull(VendorPServerOperation.focusTask(-1))
        assertTrue(
            rendered!!.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes,
        )
        assertTrue(rendered.contains("&& /system/bin/echo ${VendorPServerOperation.SuccessMarker}"))
    }

    @Test
    fun `the task resolver renders one line because the channel returns one line`() {
        // Thor, build 23ca3300: the PServer reply carried only the FIRST line of `am stack list`
        // (62 bytes, parsed=0) while the task was plainly there. The selection moved into the shell.
        //
        // R6-P0-02 round-5 fix (BLOCKING): pinned byte-identical to base `daf0e36b` again. Round 3/4 had
        // rewritten this SAME render for the marker-based NoTask distinction, but [resolveTaskId] and
        // [resolveTaskReply] compose this exact operation too, and both back every certified stop/return
        // path (Return focus, the parking arm, the landed-task note, Quick-launch evict, the registry
        // close fallback) -- widening it ran unproven shell syntax on all of them at once. The marker
        // rewrite now lives only on [VendorPServerOperation.ResolveTaskOutcome], used solely by
        // `resolveTaskOutcome` -- see `the outcome resolver's own render mirrors ProcessPresence` below.
        val rendered = VendorPServerOperation.resolveTask(CHROME, 4)?.render()

        assertEquals(
            "/system/bin/am stack list | /system/bin/grep -A2 'displayId=4 ' | " +
                "/system/bin/grep -o -m1 'taskId=[0-9]*: com.android.chrome/'",
            rendered,
        )
        assertTrue(
            rendered!!.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes,
        )
    }

    // R6-P0-02 round-5 fix (BLOCKING): [VendorPServerOperation.ResolveTaskOutcome] is the split-off sibling
    // that carries the marker-based NoTask rewrite -- constructed ONLY by `VendorPServerOperation
    // .resolveTaskOutcome`, and composed ONLY by `AndroidPServerTransport.resolveTaskOutcome`. Two more
    // narrowings from round 3/4's now-reverted version, both required by the round-5 review:
    //   1. No `exit 0` -- if the vendor daemon feeds a persistent shell rather than `sh -c` per
    //      transaction, `exit` would kill that shell and poison every later transaction on it. This uses
    //      only `if`/`then`/`fi` (already shipped in [VendorPServerOperation.ProcessPresence]), `||`, and
    //      capture-then-use assignment -- a failed `am` leaves the `if` false and the command silent.
    //   2. Builtin `echo`, never `$B/echo`, for `"$L"` -- the external toybox `echo` receives `"$L"` as
    //      one execve argument, bounded by `MAX_ARG_STRLEN` (128 KiB); a large `am stack list` table could
    //      overflow that, fail E2BIG, and echo the NoTask marker over a still-live parked game. The
    //      shell's own builtin `echo` takes no execve call and no such limit -- used for both the `"$L"`
    //      re-pipe and the marker.
    @Test
    fun `the outcome resolver's own render mirrors ProcessPresence, not the plain resolver`() {
        val rendered = VendorPServerOperation.resolveTaskOutcome(CHROME, 4)?.render()

        assertEquals(
            "B=/system/bin; if L=\$(\$B/am stack list); then echo \"\$L\" | " +
                "\$B/grep -A2 'displayId=4 ' | " +
                "\$B/grep -o -m1 'taskId=[0-9]*: com.android.chrome/' || echo __MIMIR_NO_TASK__; fi",
            rendered,
        )
        // Neither echo is `$B/echo` -- both are the shell builtin, per fix 2 above.
        assertFalse(rendered!!.contains("\$B/echo"))
        assertTrue(rendered.contains("echo \"\$L\""))
        assertTrue(rendered.contains("|| echo __MIMIR_NO_TASK__"))
        // No `exit` anywhere -- per fix 1 above, a persistent vendor shell must never be killed.
        assertFalse(rendered.contains("exit"))
        assertTrue(
            rendered.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes,
        )
    }

    @Test
    fun `the outcome resolver still fits the wire cap for the longest allow-listed emulator packages`() {
        // EmulatorSeedRegistry's longest dispatch packages as of 2026-09-15: 32-33 characters. Pinning the
        // margin rather than trusting the arithmetic -- this is the operation the round-5 byte budget was
        // actually re-solved for; [VendorPServerOperation.ResolveTask] above is back to its short base form.
        for (longPackage in listOf(
            "org.mupen64plusae.v3.fzurita.pro",
            "io.github.mandarine3ds.mandarine",
            "dev.legacy.eden_emulator.nightly",
        )) {
            MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(longPackage) }
            val rendered = VendorPServerOperation.resolveTaskOutcome(longPackage, 0)?.render()
            assertNotNull(longPackage, rendered)
            assertTrue(
                longPackage,
                rendered!!.toByteArray(StandardCharsets.UTF_8).size <= VendorPServerOperation.MaxRenderedBytes,
            )
        }
    }

    @Test
    fun `the task resolver refuses a package it may not stop or a display that cannot exist`() {
        assertNull(VendorPServerOperation.resolveTask("com.example.fake", 4))
        assertNull(VendorPServerOperation.resolveTask("com.android.chrome; id", 4))
        assertNull(VendorPServerOperation.resolveTask(CHROME, -1))
        assertNotNull(VendorPServerOperation.resolveTask(CHROME, 0))
    }

    // -------------------------------------------------------------------------- resolver reply read

    @Test
    fun `the resolved reply is read strictly and only for the package that was asked`() {
        assertEquals(2295, parseVendorResolvedTaskId("taskId=2295: com.android.chrome/", CHROME))
        assertEquals(2295, parseVendorResolvedTaskId("  taskId=2295: com.android.chrome/\n", CHROME))

        // No match on the device is an empty reply; no reply at all is a failed transaction.
        assertNull(parseVendorResolvedTaskId("", CHROME))
        assertNull(parseVendorResolvedTaskId(null, CHROME))

        // Another package's row is not an answer -- this id would be evicted for real.
        assertNull(parseVendorResolvedTaskId("taskId=2258: com.gruning.mimir.rewrite/", CHROME))
        assertNull(parseVendorResolvedTaskId("taskId=2295: com.android.chromex/", CHROME))
        assertNull(parseVendorResolvedTaskId("taskId=0: com.android.chrome/", CHROME))
        assertNull(parseVendorResolvedTaskId("RootTask id=2310 bounds=[0,0] displayId=4 ", CHROME))

        // One line is the contract: a multi-line reply is read as its first line and nothing else.
        assertEquals(
            2295,
            parseVendorResolvedTaskId("taskId=2295: com.android.chrome/\ntaskId=9: x/", CHROME),
        )
        assertNull(parseVendorResolvedTaskId("garbage\ntaskId=2295: com.android.chrome/", CHROME))
    }

    // R6-P0-02 round-3 fix (BLOCKING): [parseVendorResolvedTaskId]'s tri-state sibling used to read an
    // empty first line as [VendorTaskOutcome.NoTask] -- the same value a permission/SELinux denial, a
    // missing binary, or a broken first pipe stage ALSO produces on a Completed transaction, since all
    // of those leave the resolver's old two-grep pipe with nothing to print. That re-opened the original
    // release-on-uncertainty bug one layer below the round-2 decision fix. [VendorTaskOutcome.NoTask] now
    // comes ONLY from the literal [VendorPServerOperation.NoTaskMarker] line
    // [VendorPServerOperation.ResolveTaskOutcome.render] (round-5: its own split-off operation, never the
    // plain [VendorPServerOperation.ResolveTask] every certified stop/return path still uses) echoes as a
    // positive "am ran, grep found nothing" signal; an empty reply is [VendorTaskOutcome.Unavailable] with
    // reason `empty`, same family as `timeout`/`unavailable`. This test exercises the parser directly with
    // string literals, so it is unaffected by which operation produced them.
    @Test
    fun `the task outcome answers NoTask only from the marker, never from a bare empty reply`() {
        assertEquals(
            VendorTaskOutcome.Found(2295),
            parseVendorTaskOutcome("taskId=2295: com.android.chrome/", CHROME),
        )
        assertEquals(
            VendorTaskOutcome.Found(2295),
            parseVendorTaskOutcome("  taskId=2295: com.android.chrome/\n", CHROME),
        )

        // The positive "am ran, grep matched nothing" answer.
        assertEquals(
            VendorTaskOutcome.NoTask,
            parseVendorTaskOutcome(VendorPServerOperation.NoTaskMarker, CHROME),
        )
        assertEquals(
            VendorTaskOutcome.NoTask,
            parseVendorTaskOutcome("${VendorPServerOperation.NoTaskMarker}\n", CHROME),
        )

        // An empty reply is silence, not an answer -- `am` itself may have failed before the marker
        // branch of the command ever ran. This is the exact case the round-3 review reopened.
        assertEquals(VendorTaskOutcome.Unavailable("empty"), parseVendorTaskOutcome("", CHROME))

        // A non-empty, non-marker, non-matching line is a parse surprise, not an answer either way.
        assertEquals(
            VendorTaskOutcome.Unavailable("parse"),
            parseVendorTaskOutcome("taskId=2258: com.gruning.mimir.rewrite/", CHROME),
        )
        assertEquals(
            VendorTaskOutcome.Unavailable("parse"),
            parseVendorTaskOutcome("taskId=0: com.android.chrome/", CHROME),
        )
        assertEquals(
            VendorTaskOutcome.Unavailable("parse"),
            parseVendorTaskOutcome("RootTask id=2310 bounds=[0,0] displayId=4 ", CHROME),
        )

        // One line is the contract here too.
        assertEquals(
            VendorTaskOutcome.Found(2295),
            parseVendorTaskOutcome("taskId=2295: com.android.chrome/\ntaskId=9: x/", CHROME),
        )
        assertEquals(
            VendorTaskOutcome.NoTask,
            parseVendorTaskOutcome("${VendorPServerOperation.NoTaskMarker}\ntaskId=2295: com.android.chrome/", CHROME),
        )
    }

    // --------------------------------------------------------------------------- refusal contract

    @Test
    fun `a package carrying shell metacharacters is refused even when the session lists it`() {
        val hostile = "com.android.chrome; /system/bin/id"
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(hostile) }

        // The stop policy is the only door, and its syntax rule rejects the name before composition.
        assertFalse(MimirLaunchTargetStopPolicy.isAllowed(hostile))
        assertNull(VendorPServerOperation.forceStop(hostile))
        assertNull(VendorPServerOperation.forceStop("com.example.fake"))
    }

    @Test
    fun `the composition floor refuses anything outside the package alphabet`() {
        listOf("com.android.chrome; id", "com.android.chrome|id", "com.android.chrome id", "\$PKG", "")
            .forEach { assertFalse(it, VendorPServerOperation.ShellSafePackage.matches(it)) }
        assertTrue(VendorPServerOperation.ShellSafePackage.matches(CHROME))
    }

    @Test
    fun `an over-long package fails closed at composition instead of on the wire`() {
        // The package is interpolated twice now, so the transport's 198-byte command cap is reached
        // at 43 characters. Longer names render nothing and fall back to the best-effort kill; every
        // emulator seed package and every ordinary app package observed so far is far shorter.
        assertTrue(MimirLaunchTargetStopPolicy.isAllowed(LONG_PACKAGE))
        assertNull(VendorPServerOperation.forceStop(LONG_PACKAGE))
        assertNotNull(VendorPServerOperation.forceStop(CHROME))
    }

    // ------------------------------------------------------------------------- readback semantics

    @Test
    fun `the marker is the liveness evidence and its absence means still alive`() {
        assertEquals(
            VendorTransportStop.STOPPED,
            completedVendorStopOutcome(VendorPServerOperation.SuccessMarker),
        )
        assertEquals(
            VendorTransportStop.STOPPED,
            completedVendorStopOutcome("\n${VendorPServerOperation.SuccessMarker}\n"),
        )
        assertEquals(
            VendorTransportStop.STOPPED,
            completedVendorStopOutcome("Stopping...\n${VendorPServerOperation.SuccessMarker}"),
        )
        // The whitelisted-package shape: the transaction completed and nothing echoed the marker.
        assertEquals(VendorTransportStop.STILL_ALIVE, completedVendorStopOutcome(""))
        assertEquals(VendorTransportStop.STILL_ALIVE, completedVendorStopOutcome("2831\n"))
        assertEquals(
            VendorTransportStop.STILL_ALIVE,
            completedVendorStopOutcome("${VendorPServerOperation.SuccessMarker}_NOT"),
        )
    }

    @Test
    fun `the legacy stop projection reports a survivor as a failed stop`() {
        assertEquals(
            PrivilegedLaunchTargetStop.REQUEST_ACCEPTED,
            PrivilegedCloseOutcome.STOPPED.asLaunchTargetStop(),
        )
        assertEquals(
            PrivilegedLaunchTargetStop.REQUEST_ACCEPTED,
            PrivilegedCloseOutcome.REQUEST_UNVERIFIED.asLaunchTargetStop(),
        )
        // The point of the change: a pre-dispatch reap that leaves the target running must NOT read
        // as accepted, or `performLaunchTargetReap` skips its best-effort kill on the old evidence.
        assertEquals(
            PrivilegedLaunchTargetStop.FAILED,
            PrivilegedCloseOutcome.STILL_ALIVE.asLaunchTargetStop(),
        )
        assertEquals(
            PrivilegedLaunchTargetStop.NOT_ALLOWED,
            PrivilegedCloseOutcome.NOT_ALLOWED.asLaunchTargetStop(),
        )
        assertEquals(
            PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE,
            PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE.asLaunchTargetStop(),
        )
        assertEquals("false", PrivilegedCloseOutcome.STOPPED.aliveLogValue())
        assertEquals("true", PrivilegedCloseOutcome.STILL_ALIVE.aliveLogValue())
        assertEquals("unknown", PrivilegedCloseOutcome.REQUEST_UNVERIFIED.aliveLogValue())
    }

    // ------------------------------------------------------------------------------ composite route

    @Test
    fun `a READY vendor owns the verified close and the task eviction`() {
        val vendorClose = RecordingCloseGateway(
            PrivilegedCloseOutcome.STILL_ALIVE,
            PrivilegedTaskRemoval.REMOVED,
            resolved = 2260,
        )
        val vendor = StubUtilityGateway(PrivilegedUtilityStatus.READY, PrivilegedLaunchTargetStop.FAILED)
        val composite = CertifiedPrivilegeGateway(
            vendor = vendor,
            vendorRecents = vendor,
            vendorClose = vendorClose,
        )

        assertEquals(PrivilegedCloseOutcome.STILL_ALIVE, composite.closeLaunchTarget(CHROME))
        assertEquals(2260, composite.resolveTaskId(CHROME, 4))
        assertEquals(PrivilegedTaskRemoval.REMOVED, composite.removeTask(77))
        assertEquals(listOf(CHROME), vendorClose.closedPackages)
        assertEquals(listOf(CHROME to 4), vendorClose.resolveRequests)
        assertEquals(listOf(77), vendorClose.removedTaskIds)
        assertEquals(0, vendor.stopCalls)
        // The legacy entry point stays usable and now reports the survivor honestly.
        assertEquals(PrivilegedLaunchTargetStop.FAILED, composite.forceStopLaunchTarget(CHROME))
    }

    @Test
    fun `without a vendor route the close and task eviction are unavailable`() {
        val vendorClose = RecordingCloseGateway(
            PrivilegedCloseOutcome.STOPPED,
            PrivilegedTaskRemoval.REMOVED,
            resolved = 2260,
        )
        val vendor = StubUtilityGateway(
            PrivilegedUtilityStatus.CONNECTION_FAILED,
            PrivilegedLaunchTargetStop.AUTHORITY_UNAVAILABLE,
        )
        val composite = CertifiedPrivilegeGateway(
            vendor = vendor,
            vendorRecents = vendor,
            vendorClose = vendorClose,
        )

        assertEquals(PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE, composite.closeLaunchTarget(CHROME))
        assertEquals(0, vendor.stopCalls)
        assertTrue(vendorClose.closedPackages.isEmpty())
        assertEquals(PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE, composite.removeTask(77))
        assertTrue(vendorClose.removedTaskIds.isEmpty())
        // No task lookup is attempted through an unavailable route.
        assertNull(composite.resolveTaskId(CHROME, 4))
        assertTrue(vendorClose.resolveRequests.isEmpty())
    }

    private companion object {
        const val CHROME = "com.android.chrome"

        /** 44 characters: one past the cap the doubled interpolation imposes. */
        const val LONG_PACKAGE = "com.example.verylongpackage.namespace.tail.x"
    }
}

private class RecordingCloseGateway(
    private val outcome: PrivilegedCloseOutcome,
    private val removal: PrivilegedTaskRemoval,
    private val resolved: Int? = null,
) : PrivilegedTaskCloseGateway {
    val closedPackages = mutableListOf<String>()
    val removedTaskIds = mutableListOf<Int>()
    val resolveRequests = mutableListOf<Pair<String, Int>>()

    override fun closeLaunchTarget(packageName: String): PrivilegedCloseOutcome {
        closedPackages += packageName
        return outcome
    }

    override fun removeTask(taskId: Int): PrivilegedTaskRemoval {
        removedTaskIds += taskId
        return removal
    }

    override fun resolveTaskId(packageName: String, displayId: Int): Int? {
        resolveRequests += packageName to displayId
        return resolved
    }
}

private class StubUtilityGateway(
    private val status: PrivilegedUtilityStatus,
    private val stopResult: PrivilegedLaunchTargetStop,
) : PrivilegedUtilityGateway, MButtonSystemRecentsGateway {
    var stopCalls = 0

    override fun posture(): PrivilegedUtilityPosture = PrivilegedUtilityPosture(status = status)
    override fun requestAccess(expectedStatus: PrivilegedUtilityStatus): Boolean = false
    override fun readVendorSetting(key: String): String? = null
    override fun writeVendorSetting(key: String, value: String): Boolean = false
    override fun readDisplayBrightness(displayId: Int): Float? = null
    override fun setSecondaryBrightness(displayId: Int, fraction: Float, mirrorPercent: Int): Boolean = false

    override fun forceStopLaunchTarget(packageName: String): PrivilegedLaunchTargetStop {
        stopCalls += 1
        return stopResult
    }

    override fun systemRecentsAccessStatus(): PrivilegedUtilityStatus = status
    override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus): Boolean = false
    override fun openSystemRecents(): Boolean = false
    override fun close() = Unit
}
