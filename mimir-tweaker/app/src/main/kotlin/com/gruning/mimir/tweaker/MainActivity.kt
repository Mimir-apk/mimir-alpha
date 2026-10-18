package com.gruning.mimir.tweaker

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var output: TextView
    private var service: IMimirTweaker? = null
    private var bound = false
    private var autoStartConsumed = false
    private var labCommandConsumed = false
    /**
     * The explicit SAFE receipt probe is a debug-only device-proof entry point. It must finish before
     * the normal activity constructs UI or binds the service; otherwise the proof itself would add an
     * unrelated client/bind side effect.
     */
    private var safeLiveProbeLaunchConsumed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IMimirTweaker.Stub.asInterface(binder)
            bound = true
            when {
                maybeRunLabCommand() -> Unit
                !maybeAutoStart() -> refresh()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            output.text = getString(R.string.service_disconnected)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (consumeSafeLiveProbeLaunch(intent)) return
        title = getString(R.string.app_name)
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextIsSelectable(true)
            text = getString(R.string.connecting)
        }
        val refresh = Button(this).apply {
            text = getString(R.string.refresh_probe)
            setOnClickListener { refresh() }
        }
        val plan = Button(this).apply {
            text = getString(R.string.preview_balanced)
            setOnClickListener { previewBalanced() }
        }
        val observe = Button(this).apply {
            text = getString(R.string.start_observation)
            setOnClickListener { startObservation("balanced", packageName, 30) }
        }
        val observationStatus = Button(this).apply {
            text = getString(R.string.observation_status)
            setOnClickListener { showObservationStatus() }
        }
        val stopObservation = Button(this).apply {
            text = getString(R.string.stop_observation)
            setOnClickListener { stopObservation() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            addView(refresh, matchWidth())
            addView(plan, matchWidth())
            addView(observe, matchWidth())
            addView(observationStatus, matchWidth())
            addView(stopObservation, matchWidth())
            addView(output, matchWidth())
            addLegalNotice(this, padding)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        if (safeLiveProbeLaunchConsumed) return
        bindService(Intent(this, MimirTweakerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!BuildConfig.DIAGNOSTICS_COMPILED) return
        labCommandConsumed = false
        autoStartConsumed = false
        if (service != null && !maybeRunLabCommand()) maybeAutoStart()
    }

    override fun onStop() {
        if (safeLiveProbeLaunchConsumed) {
            super.onStop()
            return
        }
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun refresh() {
        val api = service ?: return
        runCatching {
            buildString {
                appendLine("Mimir Tweaker API ${api.apiVersion}")
                appendLine("Dry run: ${api.dryRun}")
                appendLine("Posture: ${BuildConfig.POSTURE}")
                appendLine("Hardware writes compiled: ${BuildConfig.HARDWARE_WRITES_COMPILED}")
                appendLine("Diagnostics compiled: ${BuildConfig.DIAGNOSTICS_COMPILED}")
                appendLine("Signed manifest required: ${BuildConfig.SIGNED_MANIFEST_REQUIRED}")
                appendLine()
                appendLine("CAPABILITIES")
                appendBundle(api.capabilities)
                appendLine()
                appendLine("TELEMETRY")
                appendBundle(api.telemetry)
            }
        }.onSuccess { output.text = it }
            .onFailure { output.text = getString(R.string.probe_failed, it.message) }
    }

    /**
     * An explicit component launch remains exported only because this is the existing launcher Activity.
     * This is not caller authentication: the triple compile-time gate makes the action unreachable in
     * non-SAFE or hardware-write builds, and it never falls through to the normal UI on rejection.
     */
    private fun consumeSafeLiveProbeLaunch(intent: Intent?): Boolean {
        if (intent?.action != MimirTweakerService.ACTION_RCT1_CHANNEL_PROBE) return false
        safeLiveProbeLaunchConsumed = true
        if (SafeLiveProbeLaunchGate.allows(
                debugBuild = BuildConfig.DEBUG,
                posture = BuildConfig.POSTURE,
                hardwareWritesCompiled = BuildConfig.HARDWARE_WRITES_COMPILED,
            )
        ) {
            // Start synchronously before finish: no extras, caller nonce, bind, refresh, or UI work.
            startService(
                Intent(this, MimirTweakerService::class.java)
                    .setAction(MimirTweakerService.ACTION_RCT1_CHANNEL_PROBE),
            )
        }
        finish()
        return true
    }

    private fun previewBalanced() {
        val api = service ?: return
        runCatching { api.applyProfile("balanced", packageName) }
            .onSuccess { result -> output.text = buildString { appendBundle(result) } }
            .onFailure { output.text = getString(R.string.plan_failed, it.message) }
    }

    private fun startObservation(profileId: String, workloadPackage: String, durationSeconds: Int) {
        val api = service ?: return
        runCatching { api.startCalibrationObservation(profileId, workloadPackage, durationSeconds) }
            .onSuccess { result -> output.text = buildString { appendActionBundle(result) } }
            .onFailure { output.text = getString(R.string.observation_failed, it.message) }
    }

    private fun maybeAutoStart(): Boolean {
        if (autoStartConsumed || !intent.getBooleanExtra(EXTRA_AUTO_START, false)) return false
        autoStartConsumed = true
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty().ifBlank { "balanced" }
        val workloadPackage = intent.getStringExtra(EXTRA_WORKLOAD_PACKAGE).orEmpty()
        val durationSeconds = intent.getIntExtra(EXTRA_DURATION_SECONDS, 300)
        startObservation(profileId, workloadPackage, durationSeconds)
        return true
    }

    private fun maybeRunLabCommand(): Boolean {
        if (labCommandConsumed || !BuildConfig.DIAGNOSTICS_COMPILED) return false
        val command = intent.getStringExtra(EXTRA_LAB_COMMAND).orEmpty().ifBlank { return false }
        labCommandConsumed = true
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty().ifBlank { "balanced" }
        val workloadPackage = intent.getStringExtra(EXTRA_WORKLOAD_PACKAGE).orEmpty()
            .ifBlank { "dev.legacy.eden_emulator" }
        val drillCount = intent.getIntExtra(EXTRA_DRILL_COUNT, 3)
        val rungIndex = intent.getIntExtra(EXTRA_RUNG_INDEX, 1)
        val commandDelayMs = intent.getLongExtra(EXTRA_LAB_COMMAND_DELAY_MS, 0L)
            .coerceIn(0L, MAX_LAB_COMMAND_DELAY_MS)
        val api = service ?: return true
        output.text = getString(R.string.lab_command_running, command)
        Thread {
            runCatching {
                if (commandDelayMs > 0L) Thread.sleep(commandDelayMs)
                when (command) {
                LAB_DISABLE_DRY_RUN -> {
                    val enabled = api.setDryRun(false)
                    buildString {
                        appendLine("LAB: dry-run disabled")
                        appendLine("dryRun: ${api.dryRun}")
                        appendLine("accepted: $enabled")
                    }
                }
                LAB_APPLY_WRITE -> {
                    api.setDryRun(false)
                    buildString { appendBundle(api.applyProfile(profileId, workloadPackage)) }
                }
                LAB_RESTORE -> buildString { appendBundle(api.restore()) }
                LAB_APPLY_RUNG -> {
                    api.setDryRun(false)
                    buildString {
                        appendBundle(api.applyThermalDerateRungLab(profileId, workloadPackage, rungIndex))
                    }
                }
                LAB_ROLLBACK_DRILLS -> buildString {
                    appendBundle(api.runRollbackDrills(profileId, workloadPackage, drillCount))
                }
                LAB_CALIBRATION_STATUS -> buildString { appendBundle(api.calibrationStatus) }
                LAB_APPLY_HOLD -> {
                    startForegroundService(
                        Intent(this@MainActivity, MimirTweakerService::class.java).apply {
                            action = TweakerServiceActions.ACTION_BEGIN_LAB_PROFILE_HOLD
                            putExtra(MimirTweakerService.EXTRA_LAB_APPLY_HOLD, true)
                            putExtra(MimirTweakerService.EXTRA_PROFILE_ID, profileId)
                            putExtra(MimirTweakerService.EXTRA_WORKLOAD_PACKAGE, workloadPackage)
                            putExtra(
                                MimirTweakerService.EXTRA_LAB_OBSERVE_SECONDS,
                                intent.getIntExtra(EXTRA_LAB_OBSERVE_SECONDS, 0),
                            )
                            putExtra(
                                MimirTweakerService.EXTRA_LAB_SYSTEM_SCOPE,
                                intent.getBooleanExtra(EXTRA_LAB_SYSTEM_SCOPE, false),
                            )
                            // Forward only if the caller actually supplied it -- an absent
                            // extra must mean "no rung override," not "rung 1," so a plain
                            // applyHold command (no labRungIndex extra at all) is byte-identical
                            // to before this was added. rungIndex itself already defaults to 1
                            // above only for LAB_APPLY_RUNG's own use; that default must not leak
                            // into this unrelated command via hasExtra's own default.
                            if (intent.hasExtra(EXTRA_RUNG_INDEX)) {
                                putExtra(MimirTweakerService.EXTRA_RUNG_INDEX, rungIndex)
                            }
                        },
                    )
                    "LAB: apply-hold started; snapshot will persist until restore or reboot recovery."
                }
                    else -> "Unknown lab command: $command"
                }
            }.onSuccess { result -> runOnUiThread { output.text = result } }
                .onFailure { error ->
                    runOnUiThread { output.text = getString(R.string.plan_failed, error.message) }
                }
        }.start()
        return true
    }

    private fun showObservationStatus() {
        val api = service ?: return
        runCatching { api.calibrationStatus }
            .onSuccess { result -> output.text = buildString { appendBundle(result) } }
            .onFailure { output.text = getString(R.string.observation_failed, it.message) }
    }

    private fun stopObservation() {
        val api = service ?: return
        runCatching { api.stopCalibrationObservation() }
            .onSuccess { result -> output.text = buildString { appendBundle(result) } }
            .onFailure { output.text = getString(R.string.observation_failed, it.message) }
    }

    @Suppress("DEPRECATION")
    private fun StringBuilder.appendBundle(bundle: Bundle) {
        bundle.keySet().sorted().forEach { key -> appendLine("$key: ${bundle.get(key)}") }
    }

    @Suppress("DEPRECATION")
    private fun StringBuilder.appendActionBundle(bundle: Bundle) {
        ACTION_RESULT_KEYS.forEach { key ->
            if (bundle.containsKey(key)) appendLine("$key: ${bundle.get(key)}")
        }
        appendLine()
        bundle.keySet().minus(ACTION_RESULT_KEYS).sorted().forEach { key ->
            appendLine("lastReport.$key: ${bundle.get(key)}")
        }
    }

    /**
     * The Appropriate Legal Notices GPL-3.0 section 5(d) asks an interactive interface to show, at the bottom of the main screen so the
     * controls above it are untouched: the copyright line, that the Tweaker is free software with no warranty, the source address (a
     * tappable link), and two buttons that show the packaged notices and the license text below them. A second press hides the text.
     * The same buttons and the same selectable monospace text as the rest of this screen.
     */
    private fun addLegalNotice(column: LinearLayout, padding: Int) {
        val notice = TextView(this).apply {
            text = getString(R.string.legal_copyright) + "\n" + getString(R.string.legal_free_software) + "\n" +
                getString(R.string.legal_source, MimirSourceUrl)
            textSize = 14f
            setPadding(0, padding, 0, 0)
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }
        val legalText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        var shownPath: String? = null
        fun toggle(path: String) {
            if (shownPath == path) {
                shownPath = null
                legalText.visibility = View.GONE
                return
            }
            shownPath = path
            legalText.text = readLegalAsset(this, path) ?: getString(R.string.legal_unavailable)
            legalText.visibility = View.VISIBLE
        }
        val showNotices = Button(this).apply {
            text = getString(R.string.legal_show_notices)
            setOnClickListener { toggle(LegalNoticeAssetPath) }
        }
        val showLicense = Button(this).apply {
            text = getString(R.string.legal_show_license)
            setOnClickListener { toggle(LegalLicenseAssetPath) }
        }
        column.addView(notice, matchWidth())
        column.addView(showNotices, matchWidth())
        column.addView(showLicense, matchWidth())
        column.addView(legalText, matchWidth())
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_AUTO_START = "calibrationAutoStart"
        const val EXTRA_PROFILE_ID = "calibrationProfileId"
        const val EXTRA_WORKLOAD_PACKAGE = "calibrationWorkloadPackage"
        const val EXTRA_DURATION_SECONDS = "calibrationDurationSeconds"
        const val EXTRA_LAB_COMMAND = "labCommand"
        const val EXTRA_DRILL_COUNT = "labDrillCount"
        const val EXTRA_LAB_COMMAND_DELAY_MS = "labCommandDelayMs"
        const val EXTRA_LAB_OBSERVE_SECONDS = "labObserveSeconds"
        const val EXTRA_LAB_SYSTEM_SCOPE = "labSystemScope"
        /** 1-based WAVE2 ThermalDerateLadder rung index; see [LAB_APPLY_RUNG]. */
        const val EXTRA_RUNG_INDEX = "labRungIndex"

        const val LAB_DISABLE_DRY_RUN = "disableDryRun"
        const val LAB_APPLY_WRITE = "applyWrite"
        const val LAB_RESTORE = "restore"
        /**
         * WAVE2 thermal-derate rung verification programme (2026-08-08): applies
         * ThermalDerateLadder.forProfile(EXTRA_PROFILE_ID).at(EXTRA_RUNG_INDEX)'s compiled-in
         * CPU/GPU clamps through the ordinary apply path -- see
         * IMimirTweaker.applyThermalDerateRungLab. LAB_RESTORE undoes it exactly like any other
         * apply.
         *
         * NOT A HOLD, and NOT reliable as a second command dispatched to an already-running
         * MainActivity instance -- device-verified 2026-08-08: `am start` reported success
         * ("Status: ok", delivered to the top-most instance) while onNewIntent's dispatch is
         * gated on `service != null`, and after a prior LAB_APPLY_HOLD that binding is not
         * guaranteed, so the command silently did nothing and the vector never moved. A
         * force-stop before re-launch (the usual fix for in-place-dispatch unreliability, used
         * everywhere else this app is driven from a shell) is not an option here either: it would
         * kill the foreground service holding the profile. There is no known-reliable way to
         * apply a rung ON TOP of an existing LAB_APPLY_HOLD through this entry point today.
         * [LAB_APPLY_HOLD]'s own EXTRA_RUNG_INDEX forwarding (added the same day) is the
         * supported way to measure a rung under WLE: it holds the RUNG's plan from a single cold
         * start, never a mid-session transition.
         */
        const val LAB_APPLY_RUNG = "applyRung"
        const val LAB_ROLLBACK_DRILLS = "rollbackDrills"
        const val LAB_CALIBRATION_STATUS = "calibrationStatus"
        /**
         * WAVE2 thermal-derate rung verification programme (2026-08-08, same-day
         * revision): [EXTRA_RUNG_INDEX], if present on this command's intent, is forwarded into
         * the ACTION_BEGIN_LAB_PROFILE_HOLD service intent so the hold applies
         * ThermalDerateLadder.forProfile(profileId).at(rungIndex) instead of the profile nominal
         * -- see MimirTweakerService.EXTRA_RUNG_INDEX and its onStartCommand handling. Absent
         * extra means no override; existing callers that never set it are unaffected.
         */
        const val LAB_APPLY_HOLD = "applyHold"
        private val ACTION_RESULT_KEYS = setOf("success", "code", "message")
        private const val MAX_LAB_COMMAND_DELAY_MS = 30_000L
    }
}

/** Pure, fail-closed admission for the debug-only same-UID receipt-probe bridge. */
internal object SafeLiveProbeLaunchGate {
    fun allows(
        debugBuild: Boolean,
        posture: String,
        hardwareWritesCompiled: Boolean,
    ): Boolean = debugBuild &&
        posture == TweakerPostureTruth.SAFE &&
        !hardwareWritesCompiled
}
