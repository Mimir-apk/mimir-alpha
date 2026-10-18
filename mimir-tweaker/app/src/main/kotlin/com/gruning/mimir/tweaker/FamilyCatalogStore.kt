package com.gruning.mimir.tweaker

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Where a [FamilyCatalog] comes from: the bundled asset, or a newer one staged by the fetcher and
 * promoted at idle (design D2).
 *
 * ```
 * filesDir/family_catalog/
 *   staged.json [+ staged.json.sig]   a verified download, not yet in force
 *   active.json [+ active.json.sig]   the promoted one; read at every start
 * ```
 *
 * **Signatures and fetching are not this class's job.** It validates *content* (every byte it
 * persists went through [FamilyCatalogParser]) and treats a signature as an opaque blob it carries
 * next to the catalog it belongs to. The caller (the fetcher) has verified the signature
 * before [stageVerified]; [activeGate] is the hook a caller may use to re-verify the pair at every
 * load. Without it the store trusts what it wrote itself.
 *
 * **Rules** (D2): the bundled revision is the floor -- an `active.json` is used only when it parses,
 * validates and its `revision` is at least the bundled one, so an APK upgrade with a newer bundled
 * catalog wins over an older download. A corrupt, unreadable, oversize or gated-out `active.json`
 * is not an error: the bundled catalog is used. Nothing here ever throws into the caller, and
 * nothing ever gates running -- with no readable catalog at all [Loaded.catalog] is null and the
 * caller's compiled fallbacks apply.
 *
 * **Equal revisions with different bytes are ambiguous** and this class does not arbitrate them: a
 * strictly newer revision is required to stage, so only a bundled catalog edited without bumping its
 * revision can produce one. The fetcher's same-sha no-op keeps the download side from creating one.
 *
 * Writes mirror [StockCeilingBaselineStore]: a staging file, `fsync`, then an atomic move. The
 * directory is the caller's choice (the daemon runs direct-boot-aware, so a device-protected
 * directory is the one that is readable before first unlock).
 */
internal class FamilyCatalogStore(
    directory: File,
    private val bundledBytes: () -> ByteArray?,
    private val activeGate: (bytes: ByteArray, signature: ByteArray?) -> Boolean = { _, _ -> true },
    private val log: (String) -> Unit = { line -> runCatching { Log.w(TAG, line) } },
) {
    private val root = directory.absoluteFile
    private val active = File(root, ACTIVE_NAME)
    private val activeSignature = File(root, ACTIVE_NAME + SIGNATURE_SUFFIX)
    private val staged = File(root, STAGED_NAME)
    private val stagedSignature = File(root, STAGED_NAME + SIGNATURE_SUFFIX)

    init { require(root.isAbsolute) }

    enum class Source { ACTIVE, BUNDLED, NONE }

    /**
     * [catalog] is null only when neither the bundled nor the active catalog could be used.
     * [sha256Hex] is the lower-case SHA-256 of the exact bytes of the catalog that was chosen (the
     * active file, or the bundled asset when the active one was absent, invalid or below the
     * bundled revision), or null when [catalog] is. The fetcher compares a download against it.
     */
    data class Loaded(
        val catalog: FamilyCatalog?,
        val source: Source,
        val notes: List<String>,
        val sha256Hex: String? = null,
    )

    sealed interface StageResult {
        data class Staged(val revision: Int) : StageResult

        /** The same bytes are already staged or already active. */
        data class Unchanged(val revision: Int) : StageResult

        /** Not above the highest of the bundled, active and staged revisions. */
        data class NotNewer(val floorRevision: Int) : StageResult

        data class Invalid(val reason: CatalogRejection, val detail: String) : StageResult

        data object WriteFailed : StageResult
    }

    sealed interface PromoteResult {
        data class Promoted(val revision: Int) : PromoteResult

        /** A session is running; the staged catalog stays staged. */
        data object NotIdle : PromoteResult

        data object NothingStaged : PromoteResult

        /** The staged file was unusable and has been removed so it is not retried forever. */
        data class Discarded(val reason: String) : PromoteResult

        data object WriteFailed : PromoteResult
    }

    /** Pick the catalog to run: active if valid and not below the bundled revision, else bundled. */
    @Synchronized
    fun load(): Loaded {
        val notes = ArrayList<String>()
        val bundled = readBundled(notes)
        val installed = readActive(notes)
        val chosen: Loaded = when {
            installed != null && (bundled == null || installed.catalog.revision >= bundled.catalog.revision) -> {
                installed.dropped.forEach { notes += "active: dropped ${describe(it)}" }
                Loaded(installed.catalog, Source.ACTIVE, notes, installed.sha256Hex)
            }
            bundled != null -> {
                if (installed != null) {
                    notes += "active revision ${installed.catalog.revision} is below bundled " +
                        "${bundled.catalog.revision}; the bundled catalog wins"
                }
                bundled.dropped.forEach { notes += "bundled: dropped ${describe(it)}" }
                Loaded(bundled.catalog, Source.BUNDLED, notes, bundled.sha256Hex)
            }
            else -> Loaded(null, Source.NONE, notes)
        }
        notes.forEach(log)
        return chosen.copy(notes = notes.toList())
    }

    /**
     * Persist a catalog the fetcher has **already signature-verified**. Content is re-validated here
     * regardless: a verified signature proves provenance, not that the entries are well-formed.
     * Idempotent: the same bytes again are [StageResult.Unchanged], and nothing at or below the
     * highest known revision is accepted. "The same bytes" means the same bytes as a catalog that is
     * actually trusted: an `active.json` or `staged.json` whose stored signature the [activeGate] now refuses
     * (the key was rotated, the pair was damaged) does not make a re-signed copy of it a no-op, or the device
     * would stay on the bundled catalog forever (F-P3-8, 2026-10-13 review).
     */
    @Synchronized
    fun stageVerified(bytes: ByteArray, signature: ByteArray? = null): StageResult {
        val parsed = when (val result = FamilyCatalogParser.parse(bytes)) {
            is FamilyCatalogParse.Rejected -> return StageResult.Invalid(result.reason, result.detail)
            is FamilyCatalogParse.Accepted -> result.catalog
        }
        if (verifiedBytes(active, activeSignature)?.contentEquals(bytes) == true ||
            verifiedBytes(staged, stagedSignature)?.contentEquals(bytes) == true
        ) {
            return StageResult.Unchanged(parsed.revision)
        }
        val floor = maxOf(
            readBundled(ArrayList())?.catalog?.revision ?: 0,
            readActive(ArrayList())?.catalog?.revision ?: 0,
            readStaged()?.revision ?: 0,
        )
        if (parsed.revision <= floor) return StageResult.NotNewer(floor)
        return if (writeAtomically(bytes, signature, staged, stagedSignature)) {
            StageResult.Staged(parsed.revision)
        } else {
            StageResult.WriteFailed
        }
    }

    /**
     * Promote the staged catalog to active -- only when [isIdle] says no session is running (the design's
     * `supervisor.activeSession() == null`), never mid-session. The caller invalidates whatever it
     * cached from the old catalog (the static topology's family id) after a [PromoteResult.Promoted].
     */
    @Synchronized
    fun promoteStagedIfIdle(isIdle: () -> Boolean): PromoteResult {
        if (!staged.isFile) return PromoteResult.NothingStaged
        if (!isIdle()) return PromoteResult.NotIdle
        // Only a staged file whose stored signature the load gate still accepts may be promoted; a damaged .sig
        // would otherwise replace a trusted active catalog with one load() refuses (2026-10-13 re-check P3).
        val bytes = verifiedBytes(staged, stagedSignature)
        val stagedCatalog = bytes?.let { (FamilyCatalogParser.parse(it) as? FamilyCatalogParse.Accepted)?.catalog }
        if (bytes == null || stagedCatalog == null) return discardStaged("staged file is unreadable, refused by the load gate, or invalid")
        val floor = maxOf(
            readBundled(ArrayList())?.catalog?.revision ?: 0,
            readActive(ArrayList())?.catalog?.revision ?: 0,
        )
        if (stagedCatalog.revision <= floor) {
            return discardStaged("staged revision ${stagedCatalog.revision} is not above $floor")
        }
        val signature = readBytes(stagedSignature)
        return if (writeAtomically(bytes, signature, active, activeSignature)) {
            staged.delete()
            stagedSignature.delete()
            PromoteResult.Promoted(stagedCatalog.revision)
        } else {
            PromoteResult.WriteFailed
        }
    }

    private fun discardStaged(reason: String): PromoteResult {
        staged.delete()
        stagedSignature.delete()
        log("staged catalog discarded: $reason")
        return PromoteResult.Discarded(reason)
    }

    private class Read(val catalog: FamilyCatalog, val dropped: List<DroppedEntry>, val sha256Hex: String)

    private fun readBundled(notes: MutableList<String>): Read? {
        val bytes = runCatching { bundledBytes() }.getOrNull()
        if (bytes == null) {
            notes += "bundled catalog is unreadable"
            return null
        }
        return accept("bundled", bytes, notes)
    }

    private fun readActive(notes: MutableList<String>): Read? {
        if (!active.isFile) return null
        val bytes = readBytes(active)
        if (bytes == null) {
            notes += "active catalog is unreadable; ignored"
            return null
        }
        if (!activeGate(bytes, readBytes(activeSignature))) {
            notes += "active catalog was refused by the load gate; ignored"
            return null
        }
        return accept("active", bytes, notes)
    }

    /** The file's bytes, only when its stored signature passes the [activeGate]; else null (never trusted, so never "the same"). */
    private fun verifiedBytes(file: File, signature: File): ByteArray? =
        readBytes(file)?.takeIf { activeGate(it, readBytes(signature)) }

    /** The staged catalog, only while its stored signature passes the [activeGate] (an unverifiable one is no revision floor). */
    private fun readStaged(): FamilyCatalog? =
        verifiedBytes(staged, stagedSignature)?.let { (FamilyCatalogParser.parse(it) as? FamilyCatalogParse.Accepted)?.catalog }

    private fun accept(name: String, bytes: ByteArray, notes: MutableList<String>): Read? =
        when (val result = FamilyCatalogParser.parse(bytes)) {
            is FamilyCatalogParse.Accepted -> Read(result.catalog, result.dropped, sha256Hex(bytes))
            is FamilyCatalogParse.Rejected -> {
                notes += "$name catalog rejected: ${result.reason} ${result.detail}"
                null
            }
        }

    private fun describe(dropped: DroppedEntry): String =
        "entry #${dropped.index}${dropped.id?.let { " ($it)" }.orEmpty()}: ${dropped.reason}"

    /** The file's bytes, or null when it is absent, unreadable or larger than any catalog may be (never read whole). */
    private fun readBytes(file: File): ByteArray? = runCatching {
        if (!file.isFile || file.length() > FamilyCatalogLimits.MAX_BYTES) return null
        file.readBytes()
    }.getOrNull()

    /** The catalog and its signature each get a staging file, `fsync` and an atomic move; the signature moves first. */
    private fun writeAtomically(bytes: ByteArray, signature: ByteArray?, target: File, targetSignature: File): Boolean =
        runCatching {
            if (!root.exists() && !root.mkdirs()) return false
            val catalogTemp = File(root, ".${target.name}.tmp")
            val signatureTemp = File(root, ".${targetSignature.name}.tmp")
            writeSynced(catalogTemp, bytes)
            if (signature != null) writeSynced(signatureTemp, signature)
            if (signature != null) {
                move(signatureTemp, targetSignature)
            } else {
                targetSignature.delete()
            }
            move(catalogTemp, target)
            true
        }.getOrDefault(false)

    private fun writeSynced(file: File, bytes: ByteArray) {
        if (file.exists() && !file.delete()) error("cannot clear ${file.name}")
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val DIRECTORY = "family_catalog"
        const val ASSET_NAME = "tweaker-families.json"
        private const val ACTIVE_NAME = "active.json"
        private const val STAGED_NAME = "staged.json"
        private const val SIGNATURE_SUFFIX = ".sig"
        private const val TAG = "MimirTweakerFamilies"

        /** The reader for the bundled asset: `FamilyCatalogStore(dir, FamilyCatalogStore.assetReader(context))`. */
        fun assetReader(context: Context): () -> ByteArray? = {
            runCatching { context.assets.open(ASSET_NAME).use { it.readBytes() } }.getOrNull()
        }

        /** Lower-case hex SHA-256, for the fetcher's same-sha no-op and for logs. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

/**
 * The process-wide catalog in force. The application (or the service) installs the store's
 * [FamilyCatalogStore.load] result once; readers call [current]. Null means "none installed":
 * a reader then uses its compiled fallbacks, exactly as for a device that matches no entry.
 */
internal object FamilyCatalogRegistry {
    @Volatile
    private var installed: FamilyCatalog? = null

    fun current(): FamilyCatalog? = installed

    fun install(catalog: FamilyCatalog?) {
        installed = catalog
    }

    /** Load [store] and install whatever it chose. */
    fun installFrom(store: FamilyCatalogStore): FamilyCatalogStore.Loaded =
        store.load().also { install(it.catalog) }
}

/**
 * `ro.board.platform`, read once and lazily (a `getprop` process, off the hot path), lower-cased and
 * shape-checked. Null when unreadable. Kept here because the helper in `AndroidHardware.kt` is private.
 * The reader is a parameter so a test never spawns a process.
 */
internal object BoardPlatform {
    private const val PROPERTY = "ro.board.platform"
    private val SHAPE = Regex("[a-z0-9_-]{1,32}")

    val current: String? by lazy { read { name -> getprop(name) } }

    internal fun read(getprop: (String) -> String?): String? =
        runCatching { getprop(PROPERTY) }.getOrNull()
            ?.trim()?.lowercase(Locale.US)?.takeIf { SHAPE.matches(it) }

    private fun getprop(name: String): String? = runCatching {
        ProcessBuilder("/system/bin/getprop", name).start().inputStream.bufferedReader()
            .use { it.readText().trim() }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
