package com.gruning.mimir.rewrite.core.files

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MIMIR ADDITION: **an interrupted extract must never be silent.**
 *
 * Killing Mimir while it was PLACING the files of a 2,000-file extract left 626 of them in the folder and
 * 1,376 in an orphaned `.extract-<id>` staging folder. After the relaunch the queue was empty and nothing
 * said so; a later recovery sweep deleted the 1,376 without a word, leaving a folder that looked finished
 * and was 30% of what the archive held.
 *
 * ## What reaches the player, and from where
 *
 * The facts are already on disk: [ArchiveHelper] writes a rollback journal (`FileOperationStaging`'s
 * manifest) before and after every file it places, so **the journal is the evidence** and this object
 * invents no second one. It adds only the two things the journal cannot do on its own:
 *
 *  1. **A pointer** (`jobs/<id>.job`, in app-private storage, written by [begin] and removed by [end]).
 *     The journal lives beside the extracted files, wherever the player pointed the extract, and nothing
 *     walks a whole card looking for abandoned ones. The pointer is how a later start knows WHERE to look,
 *     and it carries the archive's name, which the journal does not.
 *  2. **A report** (`reports/<id>.report`) written the moment ANY recovery sweep reads an abandoned
 *     extract journal, before that sweep deletes it. A sweep can fire from a browse, from a copy into the
 *     same folder, or from another extraction; whichever gets there first leaves the report behind for
 *     the Files queue to pick up with [drain].
 *
 * ## What the sentence promises
 *
 * Files already placed are **left where they are** and the sentence says so. Only Mimir's own staging is
 * removed: the unplaced remainder, the journal, and the empty `.mimir-staging` folder. Nothing outside
 * staging is ever deleted by this object (the recovery rollback it rides on deletes at most the one file
 * that was mid-move when the process died, which Mimir itself wrote).
 *
 * Without [configure] (JVM unit tests) there is no pointer and reports are kept in memory.
 */
object InterruptedExtractions {
    /** The journal's operation name for an extraction. */
    const val OPERATION = "extract"

    private val lock = Any()
    private var directory: File? = null

    /** Pointers this process wrote and has not yet [end]ed: a job that is running right now. */
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val inMemory = mutableListOf<InterruptedExtraction>()

    /** Where pointers and reports live. Idempotent; pass null to go back to in-memory only. */
    fun configure(directory: File?) {
        synchronized(lock) { this.directory = directory }
    }

    /** Forget everything this process remembers, as a fresh process would. Disk is untouched. */
    internal fun resetForTest() {
        synchronized(lock) {
            directory = null
            active.clear()
            inMemory.clear()
        }
    }

    /** One running extraction's pointer. */
    class Ticket internal constructor(internal val id: String)

    /**
     * Record that [archivePath] is being extracted into [destination] with its journal in [operationRoot].
     * Called after the journal exists, so a pointer always has something to find.
     */
    fun begin(archivePath: String, destination: File, operationRoot: File): Ticket? {
        val dir = synchronized(lock) { directory } ?: return null
        val id = UUID.randomUUID().toString()
        val ok = runCatching {
            writeAtomically(
                File(dir, "jobs/$id.job"),
                mapOf(
                    "archive" to archivePath,
                    "destination" to canon(destination),
                    "root" to canon(operationRoot),
                ),
            )
        }.isSuccess
        if (!ok) return null
        active += id
        return Ticket(id)
    }

    /** The job finished by itself (success, failure or cancellation): its pointer is no longer needed. */
    fun end(ticket: Ticket?) {
        ticket ?: return
        active -= ticket.id
        val dir = synchronized(lock) { directory } ?: return
        File(dir, "jobs/${ticket.id}.job").delete()
    }

    /**
     * A recovery sweep is about to roll back an abandoned extraction journal. Called by
     * [FileOperationStaging.recoverOrphaned] while the journal still exists.
     *
     * [manifest] is null when the process died before the first journal write: nothing was placed.
     */
    internal fun recovered(operationRoot: File, destination: File, manifest: StagingManifest?) {
        val pointer = findPointerFor(operationRoot)
        // The pointer has done its job whether or not there is anything to say.
        pointer?.file?.delete()

        val (placed, total) = keptAndPlanned(manifest)
        // Everything was placed and only the journal's own tidy-up was missing: the extraction is
        // complete, the files are all there, and a failure card would be a lie.
        if (total > 0 && placed == total) return

        record(
            InterruptedExtraction(
                archiveName = pointer?.archive?.let { File(it).name },
                destination = canon(destination),
                placed = placed,
                total = total,
            ),
        )
    }

    /**
     * How many of a journal's planned files will STAY in the destination after its rollback, and how many were
     * planned (-1 when the job never got as far as a plan).
     *
     * A placed file counts only if no backup is held for its name: an entry that replaced an existing file
     * is put BACK by the rollback (the original returns, the new file goes), so counting it as extracted
     * would be a claim the folder contradicts.
     */
    internal fun keptAndPlanned(manifest: StagingManifest?): Pair<Int, Int> {
        val entries = manifest?.entries.orEmpty()
        val planned = entries.filter { it.originalPath.isEmpty() }
        if (planned.isEmpty()) return 0 to -1
        val backedUp = entries.filter { it.originalPath.isNotEmpty() && it.state == FileOperationStaging.STATE_BACKUP_MOVED }.map { it.originalPath }.toSet()
        val kept = planned.count {
            it.state == FileOperationStaging.STATE_COMMITTED && it.committedPath !in backedUp
        }
        return kept to planned.size
    }

    /**
     * The start-of-session pass: for every pointer a PREVIOUS process left behind, sweep the folder it
     * names (which records a report for any abandoned journal found there), then hand back every report
     * waiting. [retain] is the caller's held-delete roots, so the sweep cannot resurrect a delete the
     * player is still holding Undo for.
     */
    fun reconcile(retain: Set<String>): List<InterruptedExtraction> {
        val dir = synchronized(lock) { directory }
        if (dir != null) {
            File(dir, "jobs").listFiles().orEmpty()
                .filter { it.name.endsWith(".job") && it.name.removeSuffix(".job") !in active }
                .forEach { file ->
                    val fields = runCatching { readFields(file) }.getOrNull()
                    val destination = fields?.get("destination")?.let(::File)
                    if (destination != null) {
                        runCatching { FileOperationStaging.recoverOrphaned(destination, retain) }
                    }
                    // Still here: the sweep found no journal for this job (it was already swept by someone
                    // who reported it, or its folder is gone). If nobody reported it, say what is known.
                    if (file.exists()) {
                        file.delete()
                        if (fields != null && destination != null && !alreadyReported(fields)) {
                            record(
                                InterruptedExtraction(
                                    archiveName = fields["archive"]?.let { File(it).name },
                                    destination = canon(destination),
                                    placed = -1,
                                    total = -1,
                                ),
                            )
                        }
                    }
                }
        }
        return drain()
    }

    /** Every report waiting, oldest first, removed as it is returned. */
    fun drain(): List<InterruptedExtraction> = synchronized(lock) {
        val out = mutableListOf<InterruptedExtraction>()
        out += inMemory
        inMemory.clear()
        directory?.let { dir ->
            File(dir, "reports").listFiles().orEmpty()
                .filter { it.name.endsWith(".report") }
                .sortedBy { it.name }
                .forEach { file ->
                    runCatching { readFields(file) }.getOrNull()?.let { fields ->
                        out += InterruptedExtraction(
                            archiveName = fields["archive"]?.takeIf { it.isNotEmpty() },
                            destination = fields["destination"].orEmpty(),
                            placed = fields["placed"]?.toIntOrNull() ?: -1,
                            total = fields["total"]?.toIntOrNull() ?: -1,
                        )
                    }
                    file.delete()
                }
        }
        out
    }

    // ---- internals ----

    /** One spelling of a path, so a pointer written for a folder is found from any route to it. */
    private fun canon(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private class Pointer(val file: File, val archive: String?)

    private fun findPointerFor(operationRoot: File): Pointer? {
        val dir = synchronized(lock) { directory } ?: return null
        val wanted = canon(operationRoot)
        return File(dir, "jobs").listFiles().orEmpty()
            .filter { it.name.endsWith(".job") }
            .firstNotNullOfOrNull { file ->
                val fields = runCatching { readFields(file) }.getOrNull() ?: return@firstNotNullOfOrNull null
                if (fields["root"] == wanted) Pointer(file, fields["archive"]) else null
            }
    }

    private fun alreadyReported(job: Map<String, String>): Boolean = synchronized(lock) {
        val archiveName = job["archive"]?.let { File(it).name }
        val destination = job["destination"]
        inMemory.any { it.destination == destination && it.archiveName == archiveName } ||
            directory?.let { dir ->
                File(dir, "reports").listFiles().orEmpty().any { file ->
                    val fields = runCatching { readFields(file) }.getOrNull() ?: return@any false
                    fields["destination"] == destination &&
                        (fields["archive"].orEmpty() == archiveName.orEmpty())
                }
            } == true
    }

    private fun record(report: InterruptedExtraction) {
        synchronized(lock) {
            val dir = directory
            if (dir == null) {
                inMemory += report
                return
            }
            val written = runCatching {
                writeAtomically(
                    File(dir, "reports/${System.currentTimeMillis()}-${UUID.randomUUID()}.report"),
                    mapOf(
                        "archive" to report.archiveName.orEmpty(),
                        "destination" to report.destination,
                        "placed" to report.placed.toString(),
                        "total" to report.total.toString(),
                    ),
                )
            }.isSuccess
            if (!written) inMemory += report
        }
    }

    private fun writeAtomically(target: File, fields: Map<String, String>) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeText(
            fields.entries.joinToString("\n") { (key, value) ->
                "$key=" + Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
            },
            StandardCharsets.UTF_8,
        )
        FileOperationStaging.atomicMove(temporary.toPath(), target.toPath(), replaceExisting = true)
    }

    private fun readFields(file: File): Map<String, String> =
        file.readLines(StandardCharsets.UTF_8)
            .filter { it.contains('=') }
            .associate { line ->
                val key = line.substringBefore('=')
                val value = String(Base64.getDecoder().decode(line.substringAfter('=')), StandardCharsets.UTF_8)
                key to value
            }
}

/**
 * An extraction that a previous process did not finish.
 *
 * @param archiveName the archive's file name, or null when the pointer that carried it was gone.
 * @param destination the folder the files were being placed into.
 * @param placed how many files were already in [destination] when the process died; -1 when not known.
 * @param total how many files the job meant to place; -1 when the job died before it had a plan.
 */
data class InterruptedExtraction(
    val archiveName: String?,
    val destination: String,
    val placed: Int,
    val total: Int,
) {
    private val folder: String get() = File(destination).name.ifEmpty { destination }

    /** The card's title: the archive, or a plain word when the name did not survive. */
    val title: String get() = archiveName ?: "Extraction"

    /**
     * The card's second line, in plain words. States what was kept, because the first thing a player
     * asks of "interrupted" is "what did I lose".
     */
    val sentence: String
        get() = when {
            placed < 0 -> "Interrupted: Mimir stopped while extracting to $folder. Check that folder for any files already placed."
            placed == 0 -> "Interrupted: nothing was extracted to $folder."
            total > 0 -> "Interrupted: ${count(placed)} of ${count(total)} files " +
                "${if (placed == 1) "was" else "were"} extracted to $folder. Those files were left in place."
            else -> "Interrupted: ${count(placed)} ${if (placed == 1) "file was" else "files were"} " +
                "extracted to $folder. Those files were left in place."
        }

    private fun count(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
}
