package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsCheck
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsConsent
import com.gruning.mimir.rewrite.core.launch.remotedefs.DefinitionsInfo
import com.gruning.mimir.rewrite.core.launch.remotedefs.EmulatorDefinitionsApi
import com.gruning.mimir.rewrite.core.launch.remotedefs.EmulatorDefinitionsService
import com.gruning.mimir.rewrite.core.launch.remotedefs.RejectCode
import com.gruning.mimir.rewrite.core.launch.remotedefs.RemoteDefinitionsResponse
import com.gruning.mimir.rewrite.core.launch.remotedefs.RemoteDefinitionsTransport
import com.gruning.mimir.rewrite.core.model.posture.MimirEmulatorDefinitionsGate
import com.gruning.mimir.rewrite.feature.settings.EmulatorDefinitionsResult
import com.gruning.mimir.rewrite.feature.settings.EmulatorDefinitionsRowBridge
import com.gruning.mimir.rewrite.feature.settings.EmulatorDefinitionsRowState
import com.gruning.mimir.rewrite.feature.settings.emulatorDefinitionsStatusLine
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Emulator definitions in the app : a small list of launch details, fetched ONLY when the
 * player presses "Check for updates" in Settings > Emulators, never on a schedule and never on a scan or a launch.
 *
 * - [start] runs once in `Application.onCreate`: it reads the stored copy from disk and installs it. No network.
 * - [onRowPressed] is the press. Everything after it is [EmulatorDefinitionsController].
 */
internal object EmulatorDefinitionsRuntime {
    private const val TAG = "MimirDefinitions"
    private const val DIRECTORY = "emulator-definitions"

    @Volatile
    private var controller: EmulatorDefinitionsController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Disk only. Never throws: a failure here leaves the compiled list in force, which is always safe. */
    fun start(context: Context) {
        try {
            val app = context.applicationContext
            val consent = PrefsDefinitionsConsent(app)
            val service = EmulatorDefinitionsService(File(app.filesDir, DIRECTORY), EmulatorDefinitionsTransport(), consent)
            // A damaged or unreadable stored copy leaves the compiled list in force; the row still shows and still works.
            try {
                Log.i(TAG, "start status=${service.load().status}")
            } catch (e: Exception) {
                Log.w(TAG, "load failed ${e::class.java.simpleName}; compiled list stays in force")
            }
            val ready = EmulatorDefinitionsController(service, consent, EmulatorDefinitionsRowBridge::publish) { work ->
                scope.launch { work() }
            }
            controller = ready
            ready.showCurrent()
        } catch (e: Exception) {
            Log.w(TAG, "start failed ${e::class.java.simpleName}")
        }
    }

    /** The Settings row's press. */
    fun onRowPressed() {
        controller?.onPress()
    }
}

/** The player's confirmation of the one-line explanation. Off until they confirm; a plain flag, no account involved. */
internal class PrefsDefinitionsConsent(context: Context) : DefinitionsConsent {
    private val prefs = context.applicationContext.getSharedPreferences("mimir_rewrite_emulator_definitions", Context.MODE_PRIVATE)

    override var granted: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEY, value).apply()
        }

    private companion object {
        const val KEY = "fetch_confirmed"
    }
}

/**
 * The row's behaviour, without Android types so it is tested on the JVM.
 *
 * - First press, before the player has confirmed: show the one-line explanation (what is fetched, from where) and fetch
 *   nothing. The press after that confirms, remembers it, and checks.
 * - Later presses check straight away. A press while a check runs does nothing.
 * - The check runs off the calling thread with the egress gate armed for exactly its duration ([armed]), and the row
 *   ends on the plain-English result line.
 */
internal class EmulatorDefinitionsController(
    private val service: EmulatorDefinitionsApi,
    private val consent: DefinitionsConsent,
    private val publish: (EmulatorDefinitionsRowState) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val armed: (() -> DefinitionsCheck) -> DefinitionsCheck = { check -> MimirEmulatorDefinitionsGate.whileUserStartedRefresh(check) },
    private val runInBackground: (() -> Unit) -> Unit,
) {
    private var info: DefinitionsInfo = service.info()
    private var explaining = false
    private var checking = false
    private var result: EmulatorDefinitionsResult? = null

    @Synchronized
    fun showCurrent() {
        publish(state())
    }

    fun onPress() {
        val startCheck = synchronized(this) {
            when {
                checking -> false
                !consent.granted && !explaining -> {
                    explaining = true
                    result = null
                    publish(state())
                    false
                }
                else -> {
                    consent.granted = true
                    explaining = false
                    checking = true
                    result = null
                    publish(state())
                    true
                }
            }
        }
        if (startCheck) runInBackground { finish() }
    }

    private fun finish() {
        val outcome = try {
            armed { service.check() }
        } catch (_: Exception) {
            DefinitionsCheck.Failed(RejectCode.INTERNAL, offline = false, needsAppUpdate = false, usingFetched = false)
        }
        synchronized(this) {
            info = service.info()
            checking = false
            when (outcome) {
                DefinitionsCheck.NeedsConsent -> {
                    consent.granted = false
                    explaining = true
                    result = null
                }
                else -> result = outcome.toRowResult()
            }
            publish(state())
        }
    }

    private fun state(): EmulatorDefinitionsRowState {
        val fetchedOn = info.fetchedAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val status = emulatorDefinitionsStatusLine(LocalDate.parse(info.builtInDate), fetchedOn, info.tag, locale)
        return EmulatorDefinitionsRowState(status = status, explaining = explaining, result = result, checking = checking)
    }
}

/** The launch engine's answer in the row's terms. Null: nothing to say (a check was already running). */
internal fun DefinitionsCheck.toRowResult(): EmulatorDefinitionsResult? = when (this) {
    DefinitionsCheck.UpToDate -> EmulatorDefinitionsResult.UpToDate
    is DefinitionsCheck.Updated -> EmulatorDefinitionsResult.Updated(emulators)
    is DefinitionsCheck.Failed -> EmulatorDefinitionsResult.CouldntUpdate(usingFetched = usingFetched, needsAppUpdate = needsAppUpdate)
    DefinitionsCheck.InFlight, DefinitionsCheck.NeedsConsent -> null
}

/**
 * The definitions fetch reaches the network only through [MimirProviderEgress], on the one route that admits the
 * single data-repository host and only while [MimirEmulatorDefinitionsGate] is armed by the player's own press.
 * [opener] is for tests; production leaves it null and gets the live one.
 */
internal class EmulatorDefinitionsTransport(
    private val opener: MimirProviderEgressOpener? = null,
) : RemoteDefinitionsTransport {
    override fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestHeaders: Map<String, String>,
    ): RemoteDefinitionsResponse? {
        val route = MimirProviderEgressRoute.EMULATOR_DEFINITIONS
        val response = (
            if (opener == null) {
                MimirProviderEgress.open(route, url, connectTimeoutMs, readTimeoutMs, requestHeaders)
            } else {
                MimirProviderEgress.open(route, url, connectTimeoutMs, readTimeoutMs, requestHeaders, opener)
            }
            ) ?: return null
        return object : RemoteDefinitionsResponse {
            override val statusCode: Int get() = response.statusCode
            override fun header(name: String): String? = response.header(name)
            override val declaredLength: Long get() = response.declaredLength
            override fun body(): InputStream = response.body()
            override fun close() = response.close()
        }
    }
}
