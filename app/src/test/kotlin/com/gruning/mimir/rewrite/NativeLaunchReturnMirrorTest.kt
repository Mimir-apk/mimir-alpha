package com.gruning.mimir.rewrite

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * RC-30 (audit 2026-10-14, M-03): the resume hook asked `SharedPreferences` for the durable native launch-return request on
 * every foreground return, including the return from a game while the cover is up. The request is read from disk once per
 * process now and answered from memory; the one writer and the one clearer, both in the same lock, keep the copy true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NativeLaunchReturnMirrorTest {

    private class CountingContext(base: Context) : ContextWrapper(base) {
        var preferenceLookups = 0

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceLookups++
            return super.getSharedPreferences(name, mode)
        }
    }

    private val request = MimirNativeLaunchReturnRequest(LibraryShelfMode.ANDROID_GAMES, "com.example.game", "token-1")

    @Before
    fun setUp() {
        forgetMimirNativeLaunchReturnMirror()
        RuntimeEnvironment.getApplication().getSharedPreferences("mimir_native_launch_return", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        forgetMimirNativeLaunchReturnMirror()
    }

    @Test
    fun `the preferences are read once and every later resume is answered from memory`() {
        val context = CountingContext(RuntimeEnvironment.getApplication())
        assertTrue(persistMimirNativeLaunchReturn(context, request))
        context.preferenceLookups = 0
        forgetMimirNativeLaunchReturnMirror()

        assertEquals("the first read comes from disk", request, readMimirNativeLaunchReturn(context))
        assertEquals(1, context.preferenceLookups)
        repeat(5) { assertEquals(request, readMimirNativeLaunchReturn(context)) }
        assertEquals("five resumes, no further lookups", 1, context.preferenceLookups)
    }

    @Test
    fun `a written request is seen at once without another disk read`() {
        val context = CountingContext(RuntimeEnvironment.getApplication())
        assertNull(readMimirNativeLaunchReturn(context))
        assertTrue(persistMimirNativeLaunchReturn(context, request))
        val lookupsAfterWrite = context.preferenceLookups

        assertEquals(request, readMimirNativeLaunchReturn(context))
        assertEquals(lookupsAfterWrite, context.preferenceLookups)
    }

    @Test
    fun `a request written by an earlier process is found by the first read of this one`() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(persistMimirNativeLaunchReturn(context, request))
        forgetMimirNativeLaunchReturnMirror()

        assertEquals(request, readMimirNativeLaunchReturn(context))
    }

    @Test
    fun `the copy follows an acknowledgement and a superseding request`() {
        val context = RuntimeEnvironment.getApplication()
        val newer = request.copy(token = "token-2")
        assertTrue(persistMimirNativeLaunchReturn(context, request))
        assertTrue(persistMimirNativeLaunchReturn(context, newer))
        assertEquals("the newer request replaces the older", newer, readMimirNativeLaunchReturn(context))

        assertEquals(
            "an acknowledgement for the older request is refused: the newer one is what is durable",
            MimirNativeLaunchReturnAcknowledgement.SUPERSEDED,
            acknowledgeMimirNativeLaunchReturn(context, request),
        )
        assertEquals("and the copy still says so", newer, readMimirNativeLaunchReturn(context))

        assertEquals(MimirNativeLaunchReturnAcknowledgement.CLEARED, acknowledgeMimirNativeLaunchReturn(context, newer))
        assertNull("a cleared file clears the copy", readMimirNativeLaunchReturn(context))
        forgetMimirNativeLaunchReturnMirror()
        assertNull("and the disk agrees", readMimirNativeLaunchReturn(context))
    }
}
