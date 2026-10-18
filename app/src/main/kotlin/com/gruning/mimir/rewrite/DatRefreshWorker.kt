package com.gruning.mimir.rewrite

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import com.gruning.mimir.rewrite.core.storage.ProcessSourceRootMutations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Debug-only proof worker for re-fetching the LIGHT, single-file, community
 * ID catalog (GameTDB — GameCube/Wii Game-ID → title, ~340 KB) and rebuilds it into `filesDir/dat/`, which
 * [AssetDatProvider] prefers over the bundled asset. Then it clears the resolved-but-no-match stamps and
 * re-enqueues enrichment, so games that a newer catalog now covers get resolved.
 *
 * EP-B6 removes the historical implicit monthly schedule. An old WorkManager request can survive an app
 * upgrade, so [doWork] also requires a debug-build authority carried by a newly enqueued proof request.
 * Unknown/legacy work exits before opening a URL. The transport is HTTPS/host/redirect/size bounded and the
 * generated catalog replaces the old file only through a same-directory atomic move.
 */
class DatRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!catalogRefreshAuthorized(BuildConfig.DEBUG, inputData.getString(InputAuthority))) {
            Log.i("MimirEnrich", "catalog refresh ignored: no explicit debug authority")
            return@withContext Result.success()
        }

        runCatching {
            refreshGameTdb()
            // A newer catalog may cover games that previously didn't match → re-attempt them.
            val dao = MimirDatabaseFactory.get(applicationContext).gameDao()
            resetUnmatchedIdentityForCatalogueChange(applicationContext) {
                ProcessSourceRootMutations.coordinator.commitIndexMutation {
                    dao.clearUnmatchedIdentity()
                }
            }
            Result.success()
        }.getOrElse {
            Log.w("MimirEnrich", "dat refresh failed — keeping the current catalog", it)
            if (catalogRefreshShouldRetry(runAttemptCount)) Result.retry() else Result.failure()
        }
    }

    private fun refreshGameTdb() {
        val text = fetch(GAMETDB_URL)
        val rows = parseGameTdbRows(text)
        require(rows.size >= MIN_ROWS) { "gametdb refresh too small (${rows.size} rows) — treating as a bad fetch" }
        val dir = AssetDatProvider.datDir(applicationContext)
        val out = File(dir, "gametdb.idcatgz")
        val writtenBytes = writeGzippedCatalogAtomically(out, rows)
        Log.i("MimirEnrich", "dat refresh: gametdb ${rows.size} rows -> $writtenBytes bytes gz")
    }

    private fun fetch(url: String): String {
        var current = requireAllowedCatalogUrl(URL(url))
        var redirectCount = 0

        while (true) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = ConnectTimeoutMillis
                readTimeout = ReadTimeoutMillis
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "text/plain")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                when (val responseCode = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        return connection.inputStream.use { input ->
                            readUtf8Bounded(
                                input = input,
                                declaredLength = connection.contentLengthLong,
                                maxBytes = MaxResponseBytes,
                            )
                        }
                    }

                    in RedirectResponseCodes -> {
                        require(redirectCount < MaxRedirects) {
                            "Too many catalog redirects from $current"
                        }
                        current = resolveAllowedCatalogRedirect(
                            current = current,
                            location = connection.getHeaderField("Location"),
                        )
                        redirectCount += 1
                    }

                    else -> error("HTTP $responseCode for $current")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val GAMETDB_URL = "https://www.gametdb.com/wiitdb.txt?LANG=EN&GAMECUBE=1"
        private const val MIN_ROWS = 500
        private const val PERIODIC = "mimir-dat-refresh"
        private const val ONESHOT = "mimir-dat-refresh-now"
        private const val InputAuthority = "mimir.catalogRefresh.authority"

        /**
         * Removes the unique periodic job shipped by older builds. Cancellation is best-effort, so the
         * worker's authority check is the decisive guard if an already-dispatched legacy job starts racing.
         */
        fun revokeLegacyAutomaticSchedule(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(PERIODIC)
        }

        /** Debug proof hook only. Release builds refuse to enqueue even if this method is reached. */
        fun runNow(context: Context) {
            if (!BuildConfig.DEBUG) {
                Log.w("MimirEnrich", "catalog refresh refused outside a debug build")
                return
            }
            val request = OneTimeWorkRequestBuilder<DatRefreshWorker>()
                .setInputData(workDataOf(InputAuthority to CatalogRefreshDebugAuthority))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, MinBackoffSeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

internal const val CatalogRefreshDebugAuthority = "debug-explicit-v1"
internal const val CatalogRefreshMaxAttempts = 3
internal const val CatalogRefreshMaxResponseBytes = 4 * 1024 * 1024

private const val ConnectTimeoutMillis = 20_000
private const val ReadTimeoutMillis = 60_000
private const val MaxRedirects = 2
private const val MinBackoffSeconds = 10L
private const val MaxResponseBytes = CatalogRefreshMaxResponseBytes
private val GameIdPattern = Regex("[A-Z0-9]{4,6}")
private val AllowedCatalogHosts = setOf("gametdb.com", "www.gametdb.com")
private val RedirectResponseCodes = setOf(301, 302, 303, 307, 308)

internal fun catalogRefreshAuthorized(debugBuild: Boolean, authority: String?): Boolean =
    debugBuild && authority == CatalogRefreshDebugAuthority

internal fun catalogRefreshShouldRetry(runAttemptCount: Int): Boolean =
    runAttemptCount.coerceAtLeast(0) < CatalogRefreshMaxAttempts - 1

internal fun requireAllowedCatalogUrl(url: URL): URL {
    require(url.protocol.equals("https", ignoreCase = true)) { "Catalog URL must use HTTPS" }
    require(url.host.lowercase() in AllowedCatalogHosts) { "Catalog host is not allowed" }
    require(url.port == -1 || url.port == 443) { "Catalog URL must use the default HTTPS port" }
    require(url.userInfo == null) { "Catalog URL must not contain user info" }
    return url
}

internal fun resolveAllowedCatalogRedirect(current: URL, location: String?): URL {
    require(!location.isNullOrBlank()) { "Catalog redirect is missing Location" }
    return requireAllowedCatalogUrl(URL(current, location))
}

internal fun readUtf8Bounded(
    input: InputStream,
    declaredLength: Long,
    maxBytes: Int,
): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    require(declaredLength < 0L || declaredLength <= maxBytes.toLong()) {
        "Catalog response is too large ($declaredLength bytes)"
    }

    val output = ByteArrayOutputStream(
        if (declaredLength in 0..maxBytes.toLong()) declaredLength.toInt() else minOf(maxBytes, 32 * 1024),
    )
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        require(total <= maxBytes - read) { "Catalog response exceeded $maxBytes bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toString(Charsets.UTF_8.name())
}

internal fun parseGameTdbRows(text: String): List<String> {
    val rows = ArrayList<String>()
    val seen = HashSet<String>()
    text.lineSequence().forEach { line ->
        val equalsAt = line.indexOf('=')
        if (equalsAt <= 0 || line.startsWith("TITLES")) return@forEach
        val gameId = line.substring(0, equalsAt).trim().uppercase()
        val title = line.substring(equalsAt + 1).trim()
        if (!GameIdPattern.matches(gameId) || title.isEmpty() || !seen.add(gameId)) return@forEach
        rows += "GAME_ID\t$gameId\t${title.replace('\t', ' ')}"
    }
    return rows
}

internal fun writeGzippedCatalogAtomically(output: File, rows: List<String>): Long {
    require(rows.isNotEmpty()) { "Catalog must not be empty" }
    val directory = requireNotNull(output.parentFile) { "Catalog output needs a parent directory" }
    require(directory.isDirectory || directory.mkdirs()) { "Catalog directory is unavailable" }
    val temporary = File.createTempFile("${output.name}.", ".tmp", directory)
    try {
        FileOutputStream(temporary).use { fileOutput ->
            val gzip = GZIPOutputStream(fileOutput)
            try {
                gzip.write(rows.joinToString("\n").toByteArray(Charsets.UTF_8))
                gzip.finish()
                gzip.flush()
                fileOutput.fd.sync()
            } finally {
                gzip.close()
            }
        }
        Files.move(
            temporary.toPath(),
            output.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return output.length()
    } finally {
        temporary.delete()
    }
}
