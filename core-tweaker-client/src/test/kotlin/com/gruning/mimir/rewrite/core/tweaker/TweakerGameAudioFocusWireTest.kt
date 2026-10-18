package com.gruning.mimir.rewrite.core.tweaker

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import com.gruning.mimir.tweaker.IMimirTweaker
import com.gruning.mimir.tweaker.TweakerGameAudioFocusContract
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * API v11, client half. The property defended: nothing on this wire can be read as a hold that was not
 * confirmed. Only an outcome of HELD that names a package is [TweakerGameAudioFocusResult.Held]; a version
 * mismatch, an unknown outcome and an older daemon are all [TweakerGameAudioFocusResult.Unavailable], and an
 * older daemon is never even asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class TweakerGameAudioFocusWireTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    private fun bundleOf(outcome: String, apiVersion: Int = 11, fill: Bundle.() -> Unit = {}) = Bundle().apply {
        putInt(TweakerGameAudioFocusContract.KEY_API_VERSION, apiVersion)
        putString(TweakerGameAudioFocusContract.KEY_OUTCOME, outcome)
        fill()
    }

    @Test
    fun `only a HELD that names a package is a hold`() {
        val held = parseTweakerGameAudioFocusBundle(
            bundleOf(TweakerGameAudioFocusContract.OUTCOME_HELD) {
                putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, "org.game.x")
            },
            11,
        )
        assertEquals(TweakerGameAudioFocusResult.Held("org.game.x"), held)

        assertTrue(
            parseTweakerGameAudioFocusBundle(bundleOf(TweakerGameAudioFocusContract.OUTCOME_HELD), 11)
                is TweakerGameAudioFocusResult.Unavailable,
        )
    }

    @Test
    fun `a refusal keeps its code and its release debt`() {
        val refused = parseTweakerGameAudioFocusBundle(
            bundleOf(TweakerGameAudioFocusContract.OUTCOME_REFUSED) {
                putString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE, TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD)
                putString(TweakerGameAudioFocusContract.KEY_REFUSAL_DETAIL, "readback allow")
                putBoolean(TweakerGameAudioFocusContract.KEY_RELEASE_DEBT, true)
            },
            11,
        )
        assertEquals(
            TweakerGameAudioFocusResult.Refused(TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD, "readback allow", true),
            refused,
        )
    }

    @Test
    fun `release outcomes map one to one`() {
        assertEquals(
            TweakerGameAudioFocusResult.Released("org.game.x"),
            parseTweakerGameAudioFocusBundle(
                bundleOf(TweakerGameAudioFocusContract.OUTCOME_RELEASED) {
                    putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, "org.game.x")
                },
                11,
            ),
        )
        assertEquals(
            TweakerGameAudioFocusResult.NothingHeld,
            parseTweakerGameAudioFocusBundle(bundleOf(TweakerGameAudioFocusContract.OUTCOME_NOTHING_HELD), 11),
        )
        assertEquals(
            TweakerGameAudioFocusResult.Retained("RELEASE_NOT_OBSERVED"),
            parseTweakerGameAudioFocusBundle(
                bundleOf(TweakerGameAudioFocusContract.OUTCOME_RETAINED) {
                    putString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE, "RELEASE_NOT_OBSERVED")
                },
                11,
            ),
        )
    }

    @Test
    fun `a version mismatch, an unknown outcome and a bundle with no outcome are all unavailable`() {
        listOf(
            bundleOf(TweakerGameAudioFocusContract.OUTCOME_HELD, apiVersion = 10),
            bundleOf("SOMETHING_NEW"),
            Bundle().apply { putInt(TweakerGameAudioFocusContract.KEY_API_VERSION, 11) },
        ).forEach {
            assertTrue(parseTweakerGameAudioFocusBundle(it, 11) is TweakerGameAudioFocusResult.Unavailable)
        }
    }

    @Test
    fun `an older daemon is never asked`() {
        var asked = 0
        val session = connectedSession { asked++ }
        assertTrue(runBlocking { session.bind() })

        val hold = session.holdGameAudioFocusV11(10, "org.game.x")
        val release = session.releaseGameAudioFocusV11(10)

        assertTrue(hold is TweakerGameAudioFocusResult.Unavailable)
        assertTrue(release is TweakerGameAudioFocusResult.Unavailable)
        assertEquals(0, asked)
    }

    @Test
    fun `a malformed package is never sent, and a valid one goes with the process token`() {
        val tokens = mutableListOf<Any?>()
        val packages = mutableListOf<Any?>()
        val session = connectedSession { args ->
            tokens += args[0]
            packages += args[1]
        }
        assertTrue(runBlocking { session.bind() })

        assertTrue(session.holdGameAudioFocusV11(11, "x; reboot") is TweakerGameAudioFocusResult.Unavailable)
        assertTrue(packages.isEmpty())

        val held = session.holdGameAudioFocusV11(11, "org.game.x")
        assertEquals(TweakerGameAudioFocusResult.Held("org.game.x"), held)
        assertEquals(listOf<Any?>("org.game.x"), packages)
        assertTrue(tokens.single() is IBinder)
    }

    private fun connectedSession(onHold: (Array<out Any?>) -> Unit): TweakerSession {
        val binder = Binder()
        val api = Proxy.newProxyInstance(
            IMimirTweaker::class.java.classLoader,
            arrayOf(IMimirTweaker::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder
                "getApiVersion" -> 11
                "holdGameAudioFocusV11" -> {
                    onHold(args.orEmpty())
                    bundleOf(TweakerGameAudioFocusContract.OUTCOME_HELD) {
                        putString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME, args[1] as String)
                    }
                }
                "releaseGameAudioFocusV11" ->
                    bundleOf(TweakerGameAudioFocusContract.OUTCOME_NOTHING_HELD)
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as IMimirTweaker
        return TweakerSession(
            appContext = appContext,
            bindService = { _, connection, _ ->
                connection.onServiceConnected(COMPONENT, Binder())
                true
            },
            unbindService = {},
            asInterface = { api },
        )
    }

    private companion object {
        val COMPONENT = ComponentName("com.gruning.mimir.tweaker", "com.gruning.mimir.tweaker.MimirTweakerService")
    }
}
