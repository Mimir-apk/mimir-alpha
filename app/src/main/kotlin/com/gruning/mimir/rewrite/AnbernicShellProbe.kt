package com.gruning.mimir.rewrite

import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** One fixed, read-only identity check for the stock Anbernic shell gate. */
internal object AnbernicShellProbe {
    private const val Tag = "MimirAnbernicProbe"
    private const val Helper = "/system/xbin/anbernicsu"
    private const val OutputLimitBytes = 8 * 1024

    suspend fun runIfAvailable() = withContext(Dispatchers.IO) {
        val helper = File(Helper)
        if (!helper.isFile) return@withContext

        val callerUid = Process.myUid()
        val callerDomain = runCatching { File("/proc/self/attr/current").readText().trim() }
            .getOrDefault("unavailable")
        val process = try {
            ProcessBuilder(Helper, "0", "/system/bin/id").start()
        } catch (failure: Exception) {
            Log.i(Tag, "status=start-failed callerUid=$callerUid callerDomain=$callerDomain reason=${failure.message}")
            return@withContext
        }
        runCatching { process.outputStream.close() }

        val stdout = AtomicReference("")
        val stderr = AtomicReference("")
        val cancelled = AtomicBoolean(false)
        val stdoutDrainer = Thread({ stdout.set(readBounded(process.inputStream)) }, "mimir-anbernic-probe-out").apply {
            isDaemon = true
            start()
        }
        val stderrDrainer = Thread({ stderr.set(readBounded(process.errorStream)) }, "mimir-anbernic-probe-err").apply {
            isDaemon = true
            start()
        }

        suspendCancellableCoroutine { continuation ->
            val waiter = Thread({
                var status = "wait-failed"
                var exit = "unknown"
                var reason: String? = null
                try {
                    if (process.waitFor(3, TimeUnit.SECONDS)) {
                        exit = process.exitValue().toString()
                        status = if (exit == "0") "completed" else "nonzero-exit"
                    } else {
                        status = "timeout"
                        destroyChild(process, callerUid, callerDomain, "timeout")
                        runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
                    }
                } catch (failure: Exception) {
                    Thread.currentThread().interrupt()
                    status = if (cancelled.get()) "cancelled" else "wait-failed"
                    reason = failure.message ?: failure.javaClass.simpleName
                    destroyChild(process, callerUid, callerDomain, status)
                    runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
                } finally {
                    try {
                        runCatching { stdoutDrainer.join(250) }
                        runCatching { stderrDrainer.join(250) }
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                        if (cancelled.get()) status = "cancelled"
                        if (!process.isAlive) exit = process.exitValue().toString()
                        Log.i(
                            Tag,
                            "status=$status exit=$exit callerUid=$callerUid callerDomain=$callerDomain " +
                                "argv=[$Helper, 0, /system/bin/id] stdout=${stdout.get()} stderr=${stderr.get()} " +
                                "reason=${reason ?: "none"}",
                        )
                    } catch (failure: Exception) {
                        runCatching {
                            Log.i(Tag, "status=waiter-failed callerUid=$callerUid callerDomain=$callerDomain reason=${failure.message}")
                        }
                    } finally {
                        runCatching { continuation.resume(Unit) }
                    }
                }
            }, "mimir-anbernic-probe-wait").apply {
                isDaemon = true
                start()
            }
            continuation.invokeOnCancellation {
                cancelled.set(true)
                runCatching { destroyChild(process, callerUid, callerDomain, "cancelled") }
                waiter.interrupt()
            }
        }
    }

    private fun readBounded(stream: InputStream): String = runCatching {
        val captured = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            val remaining = OutputLimitBytes - captured.size()
            if (remaining > 0) captured.write(buffer, 0, minOf(read, remaining))
        }
        captured.toString(Charsets.UTF_8.name()).replace('\n', ' ').trim()
    }.getOrDefault("unavailable")

    private fun destroyChild(process: java.lang.Process, callerUid: Int, callerDomain: String, reason: String) {
        if (!process.isAlive) return
        runCatching { process.destroyForcibly() }
            .onFailure { failure ->
                Log.i(
                    Tag,
                    "status=destroy-failed callerUid=$callerUid callerDomain=$callerDomain " +
                        "reason=$reason detail=${failure.message ?: failure.javaClass.simpleName}",
                )
            }
    }
}
