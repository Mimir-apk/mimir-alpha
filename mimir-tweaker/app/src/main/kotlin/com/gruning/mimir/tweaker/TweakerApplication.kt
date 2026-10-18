package com.gruning.mimir.tweaker

import android.app.Application
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process start for the Tweaker. Its job is the family catalog, and it does it without touching the service:
 * - the manifest names this class for every process. The `:guardian` process installs the catalog in force too,
 *   because the main process mints its device profile (and with it the manual-fan authority) from that catalog
 *   and the guardian must mint the SAME profile to restore a manual fan; it never schedules or fetches anything,
 *   and never promotes a staged download (the guardian re-reads the store at each arm, see [GuardianFamilyCatalog]);
 * - in the main and guardian processes it first installs the catalog in force (the promoted download if its
 *   signature still verifies, else the bundled asset) into [FamilyCatalogRegistry], so every
 *   `TweakerHardwareFamilies.resolve` after this point reads it. A failed install leaves the registry empty and the
 *   compiled default applies; a staged download is never promoted here (that happens at an idle apply, never
 *   mid-session);
 * - with no catalog public key compiled in (the default until a public key is configured), nothing is created at
 *   all: no thread, no executor, no connection, one log line;
 * - otherwise the first fetch runs at least 10 s after start on a daemon background thread, then every 24 h while
 *   the process lives. It never runs on the main thread or the transaction worker, and a failed fetch is a log
 *   line, never a crash or a gate on anything.
 */
class TweakerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The whole body is guarded: process start must never fail because of an optional refresh.
        try {
            installFamilyCatalog()
        } catch (t: Throwable) {
            runCatching { Log.w(FamilyCatalogFetcher.TAG, "family catalog not installed: ${t.javaClass.simpleName}") }
        }
        try {
            startFamilyCatalogRefresh()
        } catch (t: Throwable) {
            runCatching { Log.w(FamilyCatalogFetcher.TAG, "family catalog refresh not started: ${t.javaClass.simpleName}") }
        }
    }

    private fun installFamilyCatalog() {
        if (!FamilyCatalogProcess.usesLiveCatalogIn(Application.getProcessName(), packageName)) return
        val loaded = FamilyCatalogRegistry.installFrom(FamilyCatalogStores.forContext(applicationContext))
        Log.i(
            FamilyCatalogFetcher.TAG,
            "family catalog in force: ${loaded.source}" + (loaded.catalog?.let { " revision ${it.revision}" } ?: ""),
        )
    }

    private fun startFamilyCatalogRefresh() {
        if (!FamilyCatalogProcess.fetchesIn(Application.getProcessName(), packageName)) return
        val publicKey = BuildConfig.FAMILY_CATALOG_PUBLIC_KEY_BASE64
        if (FamilyCatalogSignature.decodePublicKey(publicKey) == null) {
            Log.i(FamilyCatalogFetcher.TAG, "live fetch disabled (no valid catalog public key compiled in); bundled catalog only")
            return
        }
        val sink = familyCatalogSinkFor(applicationContext) ?: run {
            Log.i(FamilyCatalogFetcher.TAG, "live fetch disabled (no catalog store wired); bundled catalog only")
            return
        }
        val fetcher = FamilyCatalogFetcher(
            catalogUrl = BuildConfig.FAMILY_CATALOG_URL,
            publicKeyBase64 = publicKey,
            sink = sink,
            transport = HttpsCatalogTransport(),
            userAgent = "MimirTweaker/${BuildConfig.VERSION_NAME}",
        )
        poller = FamilyCatalogPoller.onDaemonThread { fetcher.fetchOnce() }.also { it.start() }
    }

    private companion object {
        /** Held so the schedule has an owner for the life of the process (the executor thread is a daemon). */
        @Volatile var poller: FamilyCatalogPoller? = null
    }
}

/**
 * The sink the live catalog is handed to: the process's [FamilyCatalogStore] over the device-protected files
 * directory (the daemon is direct-boot aware). Null when the store cannot be created (storage not ready), in
 * which case the refresh is not armed and only the bundled catalog is used; nothing here throws.
 */
internal fun familyCatalogSinkFor(context: Context): VerifiedCatalogSink? =
    runCatching { FamilyCatalogStoreSink(FamilyCatalogStores.forContext(context)) }.getOrNull()

internal object FamilyCatalogProcess {
    /**
     * True only in the app's main process, whose name equals the package name. A null name (unknown) or any
     * `:suffix` process, i.e. `:guardian`, never fetches.
     */
    fun fetchesIn(processName: String?, packageName: String): Boolean =
        processName != null && processName == packageName

    /**
     * True in the main process and in the `:guardian` process (named exactly `<package>:guardian`): both read the
     * catalog in force, so both mint the same device profile for a given SoC. Only the main process fetches
     * ([fetchesIn]) and promotes; the guardian only reads what main has promoted.
     */
    fun usesLiveCatalogIn(processName: String?, packageName: String): Boolean =
        fetchesIn(processName, packageName) ||
            (processName != null && processName == "$packageName:guardian")
}

/**
 * Runs [task] once [initialDelayMillis] after [start] and then [periodMillis] after each run finishes.
 * A task that throws is logged and does not cancel later runs. [start] is idempotent.
 */
internal class FamilyCatalogPoller(
    private val task: () -> Unit,
    private val schedule: (Runnable, Long, Long) -> Unit,
    private val initialDelayMillis: Long = FIRST_FETCH_DELAY_MILLIS,
    private val periodMillis: Long = REFRESH_PERIOD_MILLIS,
) {
    private val started = AtomicBoolean(false)

    /** True the first time, false when already started. */
    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return false
        schedule(Runnable { runOnce() }, initialDelayMillis, periodMillis)
        return true
    }

    internal fun runOnce() {
        try {
            task()
        } catch (t: Throwable) {
            // A throwing periodic task would silently cancel every later run; the refresh is best effort.
            runCatching { Log.w(FamilyCatalogFetcher.TAG, "family catalog refresh failed: ${t.javaClass.simpleName}") }
        }
    }

    companion object {
        /** At least 10 s after process start, so the refresh never competes with the service coming up. */
        const val FIRST_FETCH_DELAY_MILLIS = 10_000L
        const val REFRESH_PERIOD_MILLIS = 24L * 60L * 60L * 1000L

        /** A poller on its own single low-priority daemon thread. Nothing is created until [start]. */
        fun onDaemonThread(task: () -> Unit): FamilyCatalogPoller {
            val executor by lazy {
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "mimir-family-catalog").apply {
                        isDaemon = true
                        priority = Thread.MIN_PRIORITY
                    }
                }
            }
            return FamilyCatalogPoller(
                task = task,
                schedule = { runnable, initialDelay, period ->
                    executor.scheduleWithFixedDelay(runnable, initialDelay, period, TimeUnit.MILLISECONDS)
                },
            )
        }
    }
}
